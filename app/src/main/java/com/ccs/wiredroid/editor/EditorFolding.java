package com.ccs.wiredroid.editor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.github.rosemoe.sora.text.Content;
import io.github.rosemoe.sora.widget.CodeEditor;
import io.github.rosemoe.sora.widget.EditorLayouts;
import io.github.rosemoe.sora.widget.FoldingLayout;
import io.github.rosemoe.sora.widget.layout.Layout;

/**
 * Folding, from the editor's side: what is collapsible, what is collapsed.
 *
 * <p>The hiding itself is {@link FoldingLayout}'s job. This decides which block
 * a command refers to, keeps the scan of the file, and puts the folding layout
 * in and out of the editor.</p>
 *
 * <p>The wrapper is installed only while something is folded. An editor nobody
 * folds in runs exactly as it did before — same layout, same fast paths — and
 * the moment the last fold is opened it goes back to that state.</p>
 */
public final class EditorFolding {

    private final CodeEditor editor;

    /** Cached block scan; dropped whenever the text changes. */
    @Nullable private List<FoldingRegions.Region> regions;
    /** The content the scan and the folds belong to. */
    @Nullable private Content scannedText;

    public EditorFolding(@NonNull CodeEditor editor) {
        this.editor = editor;
    }

    /** Call when the text changes; the next command rescans. */
    public void invalidate() {
        regions = null;
    }

    public boolean hasFolds() {
        FoldingLayout folding = installed();
        return folding != null && folding.hasFolds();
    }

    /** True when this line is a folded block's header — the one showing {@code {…}}. */
    public boolean isFoldedHeader(int line) {
        FoldingLayout folding = installed();
        return folding != null && !folding.isHidden(line) && folding.isHidden(line + 1);
    }

    /** How many lines the fold at this header is hiding, or 0 if it is not one. */
    public int hiddenAt(int headerLine) {
        FoldingLayout folding = installed();
        if (folding == null) return 0;
        return folding.lengthOfFoldAt(headerLine + 1);
    }

    /** True when this line is inside a collapsed block and not on screen. */
    public boolean isHidden(int line) {
        FoldingLayout folding = installed();
        return folding != null && folding.isHidden(line);
    }

    /**
     * Folds the block at this line, or opens it if it is already folded.
     *
     * @return true when something moved, so the caller can say so when nothing did
     */
    public boolean toggleAt(int line) {
        if (isFoldedHeader(line)) {
            return unfoldAt(line);
        }
        FoldingRegions.Region region = FoldingRegions.regionFor(scan(), line);
        if (region == null) return false;
        return fold(Collections.singletonList(region));
    }

    /** Opens the fold whose header is this line. */
    public boolean unfoldAt(int line) {
        FoldingLayout folding = installed();
        if (folding == null) return false;
        int[] pairs = folding.snapshot();
        List<Integer> kept = new ArrayList<>();
        boolean removed = false;
        for (int i = 0; i < pairs.length; i += 2) {
            if (pairs[i] == line + 1) {
                removed = true;
                continue;
            }
            kept.add(pairs[i]);
            kept.add(pairs[i + 1]);
        }
        if (!removed) return false;
        apply(folding, toArray(kept));
        return true;
    }

    /** Folds every method and field block, leaving the class declaration open. */
    public boolean foldAll() {
        List<FoldingRegions.Region> members = FoldingRegions.membersOf(scan());
        return fold(members);
    }

    public boolean unfoldAll() {
        FoldingLayout folding = installed();
        if (folding == null || !folding.hasFolds()) return false;
        apply(folding, new int[0]);
        return true;
    }

    /** Opens whatever is hiding this line, so a jump to it lands somewhere visible. */
    public void reveal(int line) {
        FoldingLayout folding = installed();
        if (folding == null || !folding.isHidden(line)) return;
        int[] pairs = folding.snapshot();
        List<Integer> kept = new ArrayList<>();
        for (int i = 0; i < pairs.length; i += 2) {
            if (line >= pairs[i] && line <= pairs[i + 1]) continue;
            kept.add(pairs[i]);
            kept.add(pairs[i + 1]);
        }
        apply(folding, toArray(kept));
    }

    private boolean fold(List<FoldingRegions.Region> regions) {
        if (regions.isEmpty() || editor.isWordwrap()) return false;
        FoldingLayout folding = ensureInstalled();
        if (folding == null) return false;
        int[] existing = folding.snapshot();
        int[] pairs = new int[existing.length + regions.size() * 2];
        System.arraycopy(existing, 0, pairs, 0, existing.length);
        int at = existing.length;
        for (FoldingRegions.Region region : regions) {
            pairs[at++] = region.start + 1;             // the header stays visible
            pairs[at++] = region.end;
        }
        apply(folding, pairs);
        FoldingLayout after = installed();
        return after != null && !java.util.Arrays.equals(existing, after.snapshot());
    }

    private void apply(FoldingLayout folding, int[] pairs) {
        int caretLine = editor.getCursor() == null ? -1 : editor.getCursor().getLeftLine();
        folding.setFolds(pairs);
        if (!folding.hasFolds()) {
            // Nothing left folded: hand the plain layout back and get out of the way.
            EditorLayouts.install(editor, folding.getBase());
        } else if (caretLine >= 0 && folding.isHidden(caretLine)) {
            // The caret must never sit on a line that is not drawn.
            editor.setSelection(folding.previousVisible(caretLine),
                    Math.min(editor.getCursor().getLeftColumn(),
                            editor.getText().getColumnCount(folding.previousVisible(caretLine))));
        }
        editor.getEventHandler().scrollBy(0, 0);
        editor.invalidate();
    }

    /** The folding layout, if one is currently installed. */
    @Nullable
    public FoldingLayout installed() {
        Layout layout = editor.getLayout();
        return layout instanceof FoldingLayout ? (FoldingLayout) layout : null;
    }

    @Nullable
    private FoldingLayout ensureInstalled() {
        FoldingLayout existing = installed();
        if (existing != null) return existing;
        Layout base = editor.getLayout();
        if (base == null) return null;
        FoldingLayout folding = new FoldingLayout(editor, base);
        EditorLayouts.install(editor, folding);
        return folding;
    }

    /**
     * Folds carried across a layout rebuild.
     *
     * <p>sora throws the layout away on a resize, a word-wrap change or a new
     * document. The first two should keep the folds; the third must not, so the
     * text is checked before they go back on.</p>
     */
    @NonNull
    public int[] save() {
        FoldingLayout folding = installed();
        return folding == null ? new int[0] : folding.snapshot();
    }

    public void restore(@NonNull int[] pairs, @Nullable Content textWhenSaved) {
        if (pairs.length == 0 || editor.isWordwrap()) return;
        if (editor.getText() != textWhenSaved) return;      // a different document
        FoldingLayout folding = ensureInstalled();
        if (folding != null) folding.setFolds(pairs);
    }

    /** The blocks in the current text, scanned at most once per edit. */
    @NonNull
    public List<FoldingRegions.Region> scan() {
        Content text = editor.getText();
        if (text == null) return Collections.emptyList();
        if (regions != null && scannedText == text) return regions;
        regions = FoldingRegions.scan(text::getLine, text.getLineCount());
        scannedText = text;
        return regions;
    }

    private static int[] toArray(List<Integer> values) {
        int[] array = new int[values.size()];
        for (int i = 0; i < array.length; i++) array[i] = values.get(i);
        return array;
    }
}
