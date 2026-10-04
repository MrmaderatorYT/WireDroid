package com.ccs.wiredroid.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;

import com.ccs.wiredroid.editor.EditorFolding;

import io.github.rosemoe.sora.event.ClickEvent;
import io.github.rosemoe.sora.event.ContentChangeEvent;
import io.github.rosemoe.sora.event.SelectionChangeEvent;
import io.github.rosemoe.sora.lang.Language;
import io.github.rosemoe.sora.text.Content;
import io.github.rosemoe.sora.text.ContentLine;
import io.github.rosemoe.sora.text.Cursor;
import io.github.rosemoe.sora.widget.CodeEditor;
import io.github.rosemoe.sora.widget.SymbolPairMatch;
import java.util.ArrayList;
import java.util.List;

/**
 * The editor with typing over a closing bracket added.
 *
 * <p>sora auto-inserts the closing half of a pair when the opening one is typed,
 * but typing the closing one yourself inserts a second copy: {@code (} gives
 * {@code (|)}, and the {@code )} that follows naturally gives {@code ()|)}. Every
 * IDE steps over the one already there instead, which is what this adds — for
 * the soft keyboard, a hardware keyboard and the symbol bar alike, since all
 * three end up in one of the two methods below.</p>
 *
 * <p>The rule is "the character to the right is the same closing character",
 * not "we put it there": sora keeps no record of which brackets it inserted, and
 * a heuristic that matches what the user sees beats one that needs bookkeeping
 * the editor does not have. Only closings of pairs the current language actually
 * declares take part, so a language with no pairs types exactly as before.</p>
 */
public class WireDroidCodeEditor extends CodeEditor {

    /** Openings whose closings may be typed over; quotes included, both halves equal. */
    private static final char[] PAIR_OPENINGS = { '(', '[', '{', '"', '\'' };

    private final Paint swatchPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF swatchRect = new RectF();

    private final Paint foldPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF foldRect = new RectF();
    private EditorFolding folding;
    private final List<int[]> extraSelections = new ArrayList<>();
    private int occurrenceSearchFrom = -1;
    private int expandStage;

    public WireDroidCodeEditor(Context context) {
        super(context);
        installFolding();
    }

    public WireDroidCodeEditor(Context context, AttributeSet attrs) {
        super(context, attrs);
        installFolding();
    }

    public WireDroidCodeEditor(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        installFolding();
    }

    public WireDroidCodeEditor(Context context, AttributeSet attrs, int defStyleAttr,
                               int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        installFolding();
    }

    /** Folding, plus the three things that have to happen around it. */
    private void installFolding() {
        folding = new EditorFolding(this);
        // A rescan is only worth doing when a command asks for one.
        subscribeEvent(ContentChangeEvent.class, (event, sub) -> folding.invalidate());
        // Anything that moves the caret — a jump to a problem, a search hit —
        // must open the block it lands in, or the caret goes somewhere unseen.
        subscribeEvent(SelectionChangeEvent.class,
                (event, sub) -> folding.reveal(event.getLeft().line));
        subscribeEvent(ClickEvent.class, (event, sub) -> {
            if (tappedFoldChip(event.getLine(), event.getX(), event.getY())
                    && event.canIntercept()) {
                folding.unfoldAt(event.getLine());
                event.intercept();
            }
        });
    }

    /** Folding for this editor; never null once the constructor has run. */
    public EditorFolding folding() {
        return folding;
    }

    /** Start selecting identical occurrences of the current selection/word. */
    public boolean selectNextOccurrence() {
        String source = getText().toString();
        int start = getCursor().getLeft(), end = getCursor().getRight();
        if (start == end) {
            io.github.rosemoe.sora.text.TextRange word = getWordRange(
                    getCursor().getLeftLine(), getCursor().getLeftColumn());
            if (word == null) return false;
            start = word.getStart().index;
            end = word.getEnd().index;
            io.github.rosemoe.sora.text.CharPosition a = getText().getIndexer().getCharPosition(start);
            io.github.rosemoe.sora.text.CharPosition b = getText().getIndexer().getCharPosition(end);
            setSelectionRegion(a.line, a.column, b.line, b.column);
        }
        if (start < 0 || end <= start || end > source.length()) return false;
        String needle = source.substring(start, end);
        if (extraSelections.isEmpty()) occurrenceSearchFrom = end;
        int found = source.indexOf(needle, Math.max(0, occurrenceSearchFrom));
        while (found >= 0 && isAlreadySelected(found, found + needle.length(), start, end))
            found = source.indexOf(needle, found + needle.length());
        if (found < 0 && occurrenceSearchFrom > 0) {
            found = source.indexOf(needle, 0);
            if (found >= 0 && isAlreadySelected(found, found + needle.length(), start, end)) return false;
        }
        if (found < 0) return false;
        extraSelections.add(new int[]{found, found + needle.length()});
        occurrenceSearchFrom = found + needle.length();
        invalidate();
        return true;
    }

    /** Skip one candidate without selecting it. */
    public boolean skipNextOccurrence() {
        String source = getText().toString();
        int start = getCursor().getLeft(), end = getCursor().getRight();
        if (start == end) {
            io.github.rosemoe.sora.text.TextRange word = getWordRange(
                    getCursor().getLeftLine(), getCursor().getLeftColumn());
            if (word == null) return false;
            start = word.getStart().index; end = word.getEnd().index;
        }
        if (end <= start || end > source.length()) return false;
        int found = source.indexOf(source.substring(start, end), Math.max(end, occurrenceSearchFrom));
        if (found < 0) found = source.indexOf(source.substring(start, end), 0);
        if (found < 0) return false;
        occurrenceSearchFrom = found + (end - start);
        return true;
    }

    public void selectAllOccurrences() {
        while (selectNextOccurrence()) { /* collect every exact occurrence */ }
    }

    public void clearOccurrenceSelections() {
        extraSelections.clear(); occurrenceSearchFrom = -1; invalidate();
    }

    public boolean hasOccurrenceSelections() { return !extraSelections.isEmpty(); }

    /** Grow the current selection through word, expression, line and enclosing block. */
    public boolean expandSelection() {
        String source = getText().toString();
        int start = getCursor().getLeft(), end = getCursor().getRight();
        if (start == end) {
            io.github.rosemoe.sora.text.TextRange word = getWordRange(
                    getCursor().getLeftLine(), getCursor().getLeftColumn());
            if (word == null) return false;
            start = word.getStart().index; end = word.getEnd().index;
        } else if (expandStage >= 2) {
            int[] enclosing = enclosingBraceRange(source, start, end);
            if (enclosing != null) { start = enclosing[0]; end = enclosing[1]; }
        } else if (expandStage == 1) {
            io.github.rosemoe.sora.text.CharPosition a = getText().getIndexer().getCharPosition(start);
            io.github.rosemoe.sora.text.CharPosition b = getText().getIndexer().getCharPosition(end);
            start = getText().getCharIndex(a.line, 0);
            end = getText().getCharIndex(b.line, getText().getColumnCount(b.line));
        } else {
            int[] expression = expressionRange(source, start, end);
            if (expression != null) { start = expression[0]; end = expression[1]; }
        }
        io.github.rosemoe.sora.text.CharPosition a = getText().getIndexer().getCharPosition(start);
        io.github.rosemoe.sora.text.CharPosition b = getText().getIndexer().getCharPosition(end);
        setSelectionRegion(a.line, a.column, b.line, b.column);
        expandStage = Math.min(3, expandStage + 1);
        return true;
    }

    private static boolean overlapsSelection(int a, int b, int x, int y) { return a < y && b > x; }

    private boolean isAlreadySelected(int a, int b, int primaryStart, int primaryEnd) {
        if (overlapsSelection(a, b, primaryStart, primaryEnd)) return true;
        for (int[] selected : extraSelections) if (overlapsSelection(a, b, selected[0], selected[1])) return true;
        return false;
    }

    private int[] expressionRange(String source, int start, int end) {
        int a = start, b = end;
        while (a > 0 && ";={}\n".indexOf(source.charAt(a - 1)) < 0) a--;
        while (b < source.length() && ";={}\n".indexOf(source.charAt(b)) < 0) b++;
        while (a < b && Character.isWhitespace(source.charAt(a))) a++;
        while (b > a && Character.isWhitespace(source.charAt(b - 1))) b--;
        return b > a && (a != start || b != end) ? new int[]{a, b} : null;
    }

    private int[] enclosingBraceRange(String source, int start, int end) {
        for (int i = start - 1; i >= 0; i--) if (source.charAt(i) == '{') {
            int depth = 1;
            for (int j = i + 1; j < source.length(); j++) {
                if (source.charAt(j) == '{') depth++;
                else if (source.charAt(j) == '}' && --depth == 0 && j >= end) return new int[]{i, j + 1};
            }
        }
        return null;
    }

    private List<int[]> editRanges() {
        List<int[]> ranges = new ArrayList<>(extraSelections);
        if (getCursor().isSelected()) ranges.add(new int[]{getCursor().getLeft(), getCursor().getRight()});
        ranges.sort((a, b) -> Integer.compare(b[0], a[0]));
        return ranges;
    }

    private void replaceAllSelections(CharSequence replacement) {
        List<int[]> ranges = editRanges();
        if (ranges.size() < 2) return;
        getText().beginBatchEdit();
        try {
            for (int[] range : ranges) {
                io.github.rosemoe.sora.text.CharPosition a = getText().getIndexer().getCharPosition(range[0]);
                io.github.rosemoe.sora.text.CharPosition b = getText().getIndexer().getCharPosition(range[1]);
                getText().replace(a.line, a.column, b.line, b.column, replacement);
            }
        } finally { getText().endBatchEdit(); }
        int caret = ranges.get(ranges.size() - 1)[0] + replacement.length();
        io.github.rosemoe.sora.text.CharPosition pos = getText().getIndexer().getCharPosition(caret);
        clearOccurrenceSelections(); setSelection(pos.line, pos.column);
    }


    /**
     * Keeps the folds across a layout rebuild.
     *
     * <p>sora drops the layout on a resize or a word-wrap change and builds a new
     * one, which would quietly unfold everything. The folds are saved around the
     * rebuild and put back — unless the document itself changed, in which case
     * line numbers from the old file mean nothing.</p>
     */
    @Override
    protected void createLayout(boolean clearWordwrapCache) {
        // Called from the superclass constructor, before this class has fields.
        if (folding == null) {
            super.createLayout(clearWordwrapCache);
            return;
        }
        int[] saved = folding.save();
        Content text = getText();
        super.createLayout(clearWordwrapCache);
        folding.restore(saved, text);
    }

    /**
     * Draws a small square beside every colour literal on screen.
     *
     * <p>{@code #3574F0} says nothing until it is rendered somewhere. The square
     * goes right after the literal rather than in the gutter, so it stays with
     * the value it describes even when a line holds several.</p>
     *
     * <p>Only visible lines are looked at, and a line without a {@code #} or an
     * {@code 0x} is rejected by a substring check before the regex runs — this
     * is on the draw path, and most lines contain no colour at all.</p>
     */
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        try {
            drawColorSwatches(canvas);
            drawFoldChips(canvas);
            drawOccurrenceSelections(canvas);
        } catch (Exception ignored) {
            // Never let a decoration take the editor down with it.
        }
    }

    private void drawOccurrenceSelections(Canvas canvas) {
        if (extraSelections.isEmpty()) return;
        Paint p = swatchPaint;
        p.setStyle(Paint.Style.FILL);
        p.setColor(0x554C9AFF);
        for (int[] range : extraSelections) {
            io.github.rosemoe.sora.text.CharPosition a = getText().getIndexer().getCharPosition(range[0]);
            io.github.rosemoe.sora.text.CharPosition b = getText().getIndexer().getCharPosition(range[1]);
            float x1 = getCharOffsetX(a.line, a.column), x2 = getCharOffsetX(b.line, b.column);
            float y1 = getCharOffsetY(a.line, a.column), y2 = getCharOffsetY(b.line, b.column);
            canvas.drawRect(Math.min(x1, x2), y1 - getRowHeight(), Math.max(x1, x2), y1, p);
        }
    }

    /**
     * Draws the {@code { … n }} badge that stands for a folded block.
     *
     * <p>It sits just after the signature, where the body used to start, and it
     * is the way back: a tap on the badge opens the block. Nothing is drawn for
     * a block that could be folded but is not — an arrow on every method would
     * cost more attention than it saves on a phone screen.</p>
     */
    private void drawFoldChips(Canvas canvas) {
        if (folding == null || !folding.hasFolds()) return;
        Content content = getText();
        if (content == null) return;
        int first = Math.max(0, getFirstVisibleLine());
        int last = Math.min(content.getLineCount() - 1, getLastVisibleLine());

        foldPaint.setTextSize(getTextSizePx() * 0.82f);
        int textColor = getColorScheme().getColor(
                io.github.rosemoe.sora.widget.schemes.EditorColorScheme.COMMENT);

        for (int line = first; line <= last; line++) {
            int hidden = folding.hiddenAt(line);
            if (hidden <= 0) continue;
            if (!chipBounds(line, hidden, foldRect)) continue;

            foldPaint.setStyle(Paint.Style.FILL);
            foldPaint.setColor((textColor & 0x00FFFFFF) | 0x22000000);
            float radius = foldRect.height() * 0.3f;
            canvas.drawRoundRect(foldRect, radius, radius, foldPaint);

            foldPaint.setStyle(Paint.Style.STROKE);
            foldPaint.setStrokeWidth(Math.max(1f, getDpUnit()));
            foldPaint.setColor((textColor & 0x00FFFFFF) | 0x55000000);
            canvas.drawRoundRect(foldRect, radius, radius, foldPaint);

            foldPaint.setStyle(Paint.Style.FILL);
            foldPaint.setColor(textColor);
            Paint.FontMetrics metrics = foldPaint.getFontMetrics();
            float baseline = foldRect.centerY() - (metrics.ascent + metrics.descent) / 2f;
            canvas.drawText(chipLabel(hidden), foldRect.left + foldRect.height() * 0.35f,
                    baseline, foldPaint);
        }
    }

    private String chipLabel(int hiddenLines) {
        return "{ \u2026 " + hiddenLines + " }";
    }

    /**
     * Where the badge for a folded header sits, in view coordinates.
     *
     * @return false when the line has no badge or it is off to the side
     */
    private boolean chipBounds(int line, int hiddenLines, RectF out) {
        Content content = getText();
        if (content == null || line < 0 || line >= content.getLineCount()) return false;
        int endColumn = content.getColumnCount(line);
        float x = getCharOffsetX(line, endColumn);
        float bottom = getCharOffsetY(line, endColumn);
        if (Float.isNaN(x) || Float.isNaN(bottom)) return false;

        foldPaint.setTextSize(getTextSizePx() * 0.82f);
        float height = getRowHeightOfText() * 1.05f;
        float width = foldPaint.measureText(chipLabel(hiddenLines)) + height * 0.7f;
        float left = x + getRowHeightOfText() * 0.5f;
        float top = bottom - getRowHeight() + (getRowHeight() - height) / 2f;
        out.set(left, top, left + width, top + height);
        return out.right > 0 && out.left < getWidth();
    }

    /** True when this tap landed on the badge of a folded block. */
    private boolean tappedFoldChip(int line, float x, float y) {
        if (folding == null) return false;
        int hidden = folding.hiddenAt(line);
        if (hidden <= 0) return false;
        // A finger is wider than the badge; a little slack in both directions.
        float slack = getDpUnit() * 6f;
        return chipBounds(line, hidden, foldRect)
                && x >= foldRect.left - slack && x <= foldRect.right + slack
                && y >= foldRect.top - slack && y <= foldRect.bottom + slack;
    }

    private void drawColorSwatches(Canvas canvas) {
        Content content = getText();
        if (content == null) return;
        int first = Math.max(0, getFirstVisibleLine());
        int last = Math.min(content.getLineCount() - 1, getLastVisibleLine());
        if (last < first) return;

        float size = getRowHeightOfText() * 0.55f;
        float radius = size * 0.25f;

        for (int line = first; line <= last; line++) {
            // A folded-away line has no row of its own; its swatch would be
            // painted on top of the header.
            if (folding != null && folding.isHidden(line)) continue;
            String text = content.getLineString(line);
            if (text.indexOf('#') < 0 && text.indexOf("0x") < 0 && text.indexOf("0X") < 0) {
                continue;
            }
            for (com.ccs.wiredroid.editor.ColorPreviewSpans.Swatch swatch
                    : com.ccs.wiredroid.editor.ColorPreviewSpans.findIn(text)) {
                float x = getCharOffsetX(line, swatch.end);
                float y = getCharOffsetY(line, swatch.end);
                if (x < 0 || y < 0) continue;
                float top = y - getRowHeight() * 0.5f - size * 0.5f + getRowHeight() * 0.5f;
                swatchRect.set(x + size * 0.35f, top - size, x + size * 1.35f, top);

                swatchPaint.setStyle(Paint.Style.FILL);
                swatchPaint.setColor(swatch.color);
                canvas.drawRoundRect(swatchRect, radius, radius, swatchPaint);

                // Outlined, or a swatch the colour of the background vanishes.
                swatchPaint.setStyle(Paint.Style.STROKE);
                swatchPaint.setStrokeWidth(Math.max(1f, size * 0.08f));
                swatchPaint.setColor(0x66888888);
                canvas.drawRoundRect(swatchRect, radius, radius, swatchPaint);
            }
        }
    }

    /** Everything the IME commits arrives here, including single typed characters. */
    @Override
    public void commitText(CharSequence text, boolean applyAutoIndent, boolean simulateKeys) {
        if (hasOccurrenceSelections()) { replaceAllSelections(text); return; }
        if (surrounded(text)) return;
        if (steppedOver(text)) return;
        super.commitText(text, applyAutoIndent, simulateKeys);
    }

    /**
     * Deleting backwards over an empty pair takes both halves.
     *
     * <p>The editor inserted the closing bracket, so the editor should be the one
     * to take it back; leaving a stray {@code )} behind after undoing a typo is
     * the most common complaint about auto-insertion.</p>
     */
    @Override
    public void deleteText() {
        if (hasOccurrenceSelections()) { replaceAllSelections(""); return; }
        if (com.ccs.wiredroid.editor.EditorTextActions.deleteEmptyPair(this)) return;
        super.deleteText();
    }

    /**
     * Wraps a selection in the pair whose opening half was typed.
     *
     * <p>Select a word, press {@code "}, and the word is quoted rather than
     * destroyed. Without this the keystroke replaces the selection, which is
     * correct for a text field and wrong for an editor.</p>
     */
    private boolean surrounded(CharSequence text) {
        if (text == null || text.length() != 1 || !isEditable()) return false;
        Content content = getText();
        if (content == null || content.getCursor() == null) return false;
        if (!content.getCursor().isSelected()) return false;
        char typed = text.charAt(0);
        if (com.ccs.wiredroid.editor.EditorTextActions.closingFor(typed) == null) return false;
        return com.ccs.wiredroid.editor.EditorTextActions.surroundSelection(this, typed);
    }

    /** The symbol bar's route in; it writes to the buffer without going through commitText. */
    @Override
    public void insertText(String text, int selectionOffset) {
        if (hasOccurrenceSelections()) { replaceAllSelections(text); return; }
        if (steppedOver(text)) return;
        super.insertText(text, selectionOffset);
    }

    /**
     * Moves the caret past an identical closing character instead of inserting.
     *
     * @return true when the caret was moved and nothing should be inserted
     */
    private boolean steppedOver(CharSequence text) {
        if (text == null || text.length() != 1 || !isEditable()) return false;
        char typed = text.charAt(0);
        if (!closesAPair(typed)) return false;

        Content content = getText();
        if (content == null) return false;
        Cursor cursor = content.getCursor();
        // With a selection the character typed replaces it, which is an insert
        // by any reading — stepping over would silently drop the selected text.
        if (cursor == null || cursor.isSelected()) return false;

        int line = cursor.getLeftLine();
        int column = cursor.getLeftColumn();
        if (line < 0 || line >= content.getLineCount()) return false;
        ContentLine row = content.getLine(line);
        if (row == null || column < 0 || column >= row.length()) return false;
        if (row.charAt(column) != typed) return false;

        setSelection(line, column + 1);
        return true;
    }

    /** Whether the current language declares a pair that ends with this character. */
    private boolean closesAPair(char typed) {
        Language language = getEditorLanguage();
        if (language == null) return false;
        SymbolPairMatch pairs = language.getSymbolPairs();
        if (pairs == null) return false;
        for (char opening : PAIR_OPENINGS) {
            SymbolPairMatch.SymbolPair pair = pairs.matchBestPairBySingleChar(opening);
            if (pair == null || pair.close == null || pair.close.length() != 1) continue;
            if (pair.close.charAt(0) == typed) return true;
        }
        return false;
    }
}
