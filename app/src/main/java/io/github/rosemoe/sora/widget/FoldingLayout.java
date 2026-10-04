package io.github.rosemoe.sora.widget;

import android.util.SparseArray;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.ccs.wiredroid.editor.FoldMap;

import java.util.NoSuchElementException;

import io.github.rosemoe.sora.text.Content;
import io.github.rosemoe.sora.text.ContentLine;
import io.github.rosemoe.sora.util.IntPair;
import io.github.rosemoe.sora.widget.layout.Layout;
import io.github.rosemoe.sora.widget.layout.Row;
import io.github.rosemoe.sora.widget.layout.RowIterator;

/**
 * A layout that leaves folded lines out of the picture.
 *
 * <p>sora has no folding: the editor draws one row per line and asks the layout
 * where each one goes. That is the whole seam this needs. Wrapping the real
 * layout and reporting fewer rows makes folded lines cease to exist as far as
 * drawing, scrolling, touching and cursor movement are concerned — the text
 * itself is untouched, so saving, undo and search still see the whole file.</p>
 *
 * <p>Only the plain line-break layout is wrapped. With word wrap on, one line is
 * several rows and the row arithmetic no longer holds; folding is switched off
 * there rather than made wrong.</p>
 *
 * <p>The row translation lives in {@link FoldMap}, away from the editor, where
 * it can be tested. What is left here is the {@link Layout} contract.</p>
 */
public final class FoldingLayout implements Layout {

    private final CodeEditor editor;
    private final Layout base;
    private final FoldMap folds = new FoldMap();

    public FoldingLayout(@NonNull CodeEditor editor, @NonNull Layout base) {
        this.editor = editor;
        this.base = base;
    }

    /** The layout underneath, to be put back when the last fold is opened. */
    @NonNull
    public Layout getBase() {
        return base;
    }

    public boolean hasFolds() {
        return folds.hasFolds();
    }

    /** Collapsed ranges as flat {@code start, end} pairs of hidden lines. */
    @NonNull
    public int[] snapshot() {
        return folds.snapshot();
    }

    /** Replaces the collapsed ranges; overlapping and stale ones are sorted out. */
    public void setFolds(@NonNull int[] ranges) {
        folds.set(ranges, lineCount());
    }

    public boolean isHidden(int line) {
        return folds.isHidden(line);
    }

    public int rowForLine(int line) {
        return folds.rowForLine(line);
    }

    public int previousVisible(int line) {
        return folds.previousVisible(line);
    }

    /** Lines hidden by the fold whose first hidden line is this one. */
    public int lengthOfFoldAt(int hiddenStart) {
        return folds.lengthOfRangeStartingAt(hiddenStart);
    }

    private int lineCount() {
        Content text = editor.getText();
        return text == null ? 1 : text.getLineCount();
    }

    // ---- Layout ----------------------------------------------------------

    @Override
    public void destroyLayout() {
        base.destroyLayout();
    }

    @Override
    public int getLineNumberForRow(int row) {
        return Math.max(0, Math.min(folds.lineForRow(row), lineCount() - 1));
    }

    @NonNull
    @Override
    public RowIterator obtainRowIterator(int initialRow, @Nullable SparseArray<ContentLine> preloadedLines) {
        return new FoldedRowIterator(initialRow, preloadedLines);
    }

    @NonNull
    @Override
    public Row getRowAt(int rowIndex) {
        return base.getRowAt(getLineNumberForRow(rowIndex));
    }

    @Override
    public int getLayoutWidth() {
        return base.getLayoutWidth();
    }

    @Override
    public int getLayoutHeight() {
        return getRowCount() * editor.getRowHeight();
    }

    @Override
    public int getRowCount() {
        return Math.max(1, base.getRowCount() - folds.totalHidden());
    }

    @Override
    public long getCharPositionForLayoutOffset(float xOffset, float yOffset) {
        int rowHeight = Math.max(1, editor.getRowHeight());
        int row = Math.max(0, Math.min(getRowCount() - 1, (int) (yOffset / rowHeight)));
        int line = getLineNumberForRow(row);
        // Asked at that line's own place in the unfolded layout, the base answers
        // with the same line and the column the horizontal offset lands on.
        long packed = base.getCharPositionForLayoutOffset(xOffset, line * rowHeight + rowHeight / 2f);
        return IntPair.pack(line, IntPair.getSecond(packed));
    }

    @NonNull
    @Override
    public float[] getCharLayoutOffset(int line, int column, float[] array) {
        float[] result = base.getCharLayoutOffset(line, column, array);
        result[0] = editor.getRowBottom(folds.rowForLine(line));
        return result;
    }

    @Override
    public int getRowCountForLine(int line) {
        return 1;
    }

    @Override
    public long getUpPosition(int line, int column) {
        Content text = editor.getText();
        if (text == null || line <= 0) return IntPair.pack(0, 0);
        int target = folds.previousVisible(line - 1);
        return IntPair.pack(target, Math.min(column, text.getColumnCount(target)));
    }

    @Override
    public long getDownPosition(int line, int column) {
        Content text = editor.getText();
        if (text == null) return IntPair.pack(line, column);
        int target = folds.nextVisible(line + 1);
        if (target >= text.getLineCount()) {
            return IntPair.pack(line, text.getColumnCount(line));
        }
        return IntPair.pack(target, Math.min(column, text.getColumnCount(target)));
    }

    @Override
    public int getRowIndexForPosition(int index) {
        return folds.rowForLine(base.getRowIndexForPosition(index));
    }

    // ---- content events --------------------------------------------------

    @Override
    public void beforeReplace(@NonNull Content content) {
        base.beforeReplace(content);
    }

    @Override
    public void afterInsert(@NonNull Content content, int startLine, int startColumn,
                            int endLine, int endColumn, @NonNull CharSequence insertedContent) {
        base.afterInsert(content, startLine, startColumn, endLine, endColumn, insertedContent);
        if (!folds.hasFolds() || endLine == startLine) return;
        folds.set(folds.shiftedForInsert(startLine, endLine - startLine), content.getLineCount());
    }

    @Override
    public void afterDelete(@NonNull Content content, int startLine, int startColumn,
                            int endLine, int endColumn, @NonNull CharSequence deletedContent) {
        base.afterDelete(content, startLine, startColumn, endLine, endColumn, deletedContent);
        if (!folds.hasFolds()) return;
        folds.set(folds.shiftedForDelete(startLine, endLine), content.getLineCount());
    }

    /** Walks visible rows, handing back the line each one draws. */
    private final class FoldedRowIterator implements RowIterator {

        private final SparseArray<ContentLine> preloadedLines;
        private final Row result = new Row();
        private final int initialRow;
        private int row;

        FoldedRowIterator(int initialRow, @Nullable SparseArray<ContentLine> preloadedLines) {
            this.initialRow = this.row = initialRow;
            this.preloadedLines = preloadedLines;
            result.isLeadingRow = true;
            result.startColumn = 0;
        }

        @NonNull
        @Override
        public Row next() {
            if (!hasNext()) throw new NoSuchElementException();
            Content text = editor.getText();
            int line = getLineNumberForRow(row);
            result.lineIndex = line;
            ContentLine content = preloadedLines != null ? preloadedLines.get(line) : null;
            if (content == null && text != null) content = text.getLine(line);
            result.endColumn = content == null ? 0 : content.length();
            row++;
            return result;
        }

        @Override
        public boolean hasNext() {
            return row >= 0 && row < getRowCount();
        }

        @Override
        public void reset() {
            row = initialRow;
        }
    }
}
