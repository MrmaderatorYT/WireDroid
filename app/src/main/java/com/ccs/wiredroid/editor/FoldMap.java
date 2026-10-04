package com.ccs.wiredroid.editor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The arithmetic behind folding: which line is drawn on which row.
 *
 * <p>Collapsing a block does not change the text, only which lines get a row.
 * Every part of the editor that thinks in rows — scrolling, hit testing, the
 * cursor, the renderer — goes through this translation, so it has to be right
 * in both directions and fast enough to run inside a draw pass.</p>
 *
 * <p>Ranges are stored sorted and disjoint, with a running count of the lines
 * hidden before each one, which makes both directions a binary search. Kept
 * apart from the editor so the mapping can be checked against plain numbers.</p>
 */
public final class FoldMap {

    /** First and last hidden line of each range, ascending and disjoint. */
    private int[] starts = new int[0];
    private int[] ends = new int[0];
    /** Lines hidden before range i. */
    private int[] hiddenBefore = new int[0];
    /** Row at which range i begins — {@code starts[i] - hiddenBefore[i]}. */
    private int[] rowKeys = new int[0];
    private int totalHidden;

    public boolean hasFolds() {
        return totalHidden > 0;
    }

    public int totalHidden() {
        return totalHidden;
    }

    public int rangeCount() {
        return starts.length;
    }

    /** The ranges as flat {@code start, end} pairs of hidden lines. */
    public int[] snapshot() {
        int[] pairs = new int[starts.length * 2];
        for (int i = 0; i < starts.length; i++) {
            pairs[i * 2] = starts[i];
            pairs[i * 2 + 1] = ends[i];
        }
        return pairs;
    }

    /** The range starting at this hidden line, as a length, or 0 if there is none. */
    public int lengthOfRangeStartingAt(int hiddenStart) {
        for (int i = 0; i < starts.length; i++) {
            if (starts[i] == hiddenStart) return ends[i] - starts[i] + 1;
        }
        return 0;
    }

    /**
     * Replaces the ranges.
     *
     * <p>Input may overlap, nest, repeat or run past the end of the file — a
     * folded class and a folded method inside it arrive as two ranges, and after
     * an edit either may be stale. All of it is clamped and merged here so no
     * caller has to think about it.</p>
     *
     * @param ranges    flat {@code start, end} pairs of hidden lines
     * @param lineCount lines in the file
     */
    public void set(int[] ranges, int lineCount) {
        List<int[]> valid = new ArrayList<>(ranges.length / 2);
        for (int i = 0; i + 1 < ranges.length; i += 2) {
            int start = Math.max(0, ranges[i]);
            int end = Math.min(ranges[i + 1], lineCount - 1);
            // The last line stays visible: with no row below a fold that reaches
            // the end of the file, there is nowhere to put the caret to undo it.
            if (end >= lineCount - 1) end = lineCount - 2;
            if (end >= start) valid.add(new int[]{start, end});
        }
        Collections.sort(valid, (a, b) -> Integer.compare(a[0], b[0]));

        List<int[]> merged = new ArrayList<>(valid.size());
        for (int[] range : valid) {
            if (!merged.isEmpty()) {
                int[] last = merged.get(merged.size() - 1);
                // Adjacent ranges merge too: one hidden run, one lookup.
                if (range[0] <= last[1] + 1) {
                    last[1] = Math.max(last[1], range[1]);
                    continue;
                }
            }
            merged.add(new int[]{range[0], range[1]});
        }

        int size = merged.size();
        starts = new int[size];
        ends = new int[size];
        hiddenBefore = new int[size];
        rowKeys = new int[size];
        int running = 0;
        for (int i = 0; i < size; i++) {
            int[] range = merged.get(i);
            starts[i] = range[0];
            ends[i] = range[1];
            hiddenBefore[i] = running;
            rowKeys[i] = range[0] - running;
            running += range[1] - range[0] + 1;
        }
        totalHidden = running;
    }

    public boolean isHidden(int line) {
        return indexOfRangeContaining(line) >= 0;
    }

    /** The row a line is drawn on; a hidden line reports its header's row. */
    public int rowForLine(int line) {
        if (totalHidden == 0) return line;
        int inside = indexOfRangeContaining(line);
        if (inside >= 0) return Math.max(0, starts[inside] - 1 - hiddenBefore[inside]);
        return line - hiddenBeforeLine(line);
    }

    /** The line drawn on a row — the inverse of {@link #rowForLine}. */
    public int lineForRow(int row) {
        if (totalHidden == 0) return row;
        int low = 0;
        int high = rowKeys.length - 1;
        int passed = 0;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (rowKeys[mid] <= row) {
                passed = hiddenBefore[mid] + (ends[mid] - starts[mid] + 1);
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return row + passed;
    }

    /** The next line at or after this one that is drawn. */
    public int nextVisible(int line) {
        int inside = indexOfRangeContaining(line);
        return inside < 0 ? line : ends[inside] + 1;
    }

    /** The last line at or before this one that is drawn. */
    public int previousVisible(int line) {
        int inside = indexOfRangeContaining(line);
        return inside < 0 ? line : Math.max(0, starts[inside] - 1);
    }

    /**
     * Moves the ranges to follow an insertion.
     *
     * @param startLine where the insertion began
     * @param addedLines lines it added
     * @return the adjusted ranges, ready for {@link #set}
     */
    public int[] shiftedForInsert(int startLine, int addedLines) {
        int[] pairs = snapshot();
        if (addedLines == 0) return pairs;
        for (int i = 0; i < pairs.length; i += 2) {
            if (startLine < pairs[i]) {
                pairs[i] += addedLines;
                pairs[i + 1] += addedLines;
            } else if (startLine <= pairs[i + 1]) {
                pairs[i + 1] += addedLines;         // grown from inside
            }
        }
        return pairs;
    }

    /**
     * Moves the ranges to follow a deletion, dropping any it cut into.
     *
     * <p>A range the deletion reached is no longer the block that was folded, and
     * a guess at its new extent would hide the wrong lines. Opening it shows the
     * user what is actually there.</p>
     */
    public int[] shiftedForDelete(int startLine, int endLine) {
        int removed = endLine - startLine;
        List<Integer> kept = new ArrayList<>();
        int[] pairs = snapshot();
        for (int i = 0; i < pairs.length; i += 2) {
            if (endLine < pairs[i]) {
                kept.add(pairs[i] - removed);
                kept.add(pairs[i + 1] - removed);
            } else if (startLine > pairs[i + 1]) {
                kept.add(pairs[i]);
                kept.add(pairs[i + 1]);
            }
        }
        int[] flat = new int[kept.size()];
        for (int i = 0; i < flat.length; i++) flat[i] = kept.get(i);
        return flat;
    }

    private int indexOfRangeContaining(int line) {
        int low = 0;
        int high = starts.length - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (line < starts[mid]) {
                high = mid - 1;
            } else if (line > ends[mid]) {
                low = mid + 1;
            } else {
                return mid;
            }
        }
        return -1;
    }

    private int hiddenBeforeLine(int line) {
        int low = 0;
        int high = starts.length - 1;
        int hidden = 0;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (ends[mid] < line) {
                hidden = hiddenBefore[mid] + (ends[mid] - starts[mid] + 1);
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return hidden;
    }
}
