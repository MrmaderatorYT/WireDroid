package com.ccs.wiredroid.editor;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Line-to-row translation with blocks folded away.
 *
 * <p>Both directions must agree: every visible line's row maps back to that
 * line. A discrepancy puts the caret one place and the text another.</p>
 */
public class FoldMapTest {

    private static final int LINES = 20;

    private static FoldMap mapOf(int... ranges) {
        FoldMap map = new FoldMap();
        map.set(ranges, LINES);
        return map;
    }

    @Test
    public void withNothingFoldedRowsAreLines() {
        FoldMap map = mapOf();
        assertFalse(map.hasFolds());
        for (int line = 0; line < LINES; line++) {
            assertEquals(line, map.rowForLine(line));
            assertEquals(line, map.lineForRow(line));
        }
    }

    @Test
    public void linesBelowAFoldMoveUpByItsLength() {
        FoldMap map = mapOf(5, 9);              // 5 lines hidden
        assertEquals(5, map.totalHidden());
        assertEquals(4, map.rowForLine(4));
        assertEquals(5, map.rowForLine(10));
        assertEquals(10, map.rowForLine(15));
    }

    @Test
    public void everyVisibleLineSurvivesTheRoundTrip() {
        FoldMap map = mapOf(3, 4, 8, 12, 16, 17);
        for (int line = 0; line < LINES; line++) {
            if (map.isHidden(line)) continue;
            assertEquals("line " + line, line, map.lineForRow(map.rowForLine(line)));
        }
    }

    @Test
    public void everyRowNamesAVisibleLine() {
        FoldMap map = mapOf(3, 4, 8, 12);
        int rows = LINES - map.totalHidden();
        for (int row = 0; row < rows; row++) {
            int line = map.lineForRow(row);
            assertFalse("row " + row + " -> hidden line " + line, map.isHidden(line));
            assertEquals(row, map.rowForLine(line));
        }
    }

    @Test
    public void aHiddenLineReportsItsHeadersRow() {
        // The caret jumping into a folded block must not land off the screen.
        FoldMap map = mapOf(5, 9);
        assertEquals(map.rowForLine(4), map.rowForLine(7));
    }

    @Test
    public void overlappingAndNestedRangesBecomeOne() {
        FoldMap map = mapOf(5, 12, 7, 9, 10, 14);
        assertEquals(1, map.rangeCount());
        assertArrayEquals(new int[]{5, 14}, map.snapshot());
    }

    @Test
    public void touchingRangesJoinUp() {
        FoldMap map = mapOf(3, 5, 6, 8);
        assertEquals(1, map.rangeCount());
        assertArrayEquals(new int[]{3, 8}, map.snapshot());
    }

    @Test
    public void aRangeRunningPastTheEndIsTrimmed() {
        // Clamped short of the last line, so there is always a row to click on.
        FoldMap map = mapOf(15, 99);
        assertArrayEquals(new int[]{15, LINES - 2}, map.snapshot());
    }

    @Test
    public void movingBetweenVisibleLinesSkipsTheFold() {
        FoldMap map = mapOf(5, 9);
        assertEquals(10, map.nextVisible(6));
        assertEquals(4, map.previousVisible(6));
        assertEquals(11, map.nextVisible(11));
        assertEquals(11, map.previousVisible(11));
    }

    @Test
    public void anInsertionAbovePushesTheFoldDown() {
        FoldMap map = mapOf(5, 9);
        assertArrayEquals(new int[]{8, 12}, map.shiftedForInsert(2, 3));
    }

    @Test
    public void anInsertionInsideGrowsTheFold() {
        FoldMap map = mapOf(5, 9);
        assertArrayEquals(new int[]{5, 11}, map.shiftedForInsert(6, 2));
    }

    @Test
    public void anInsertionBelowLeavesItAlone() {
        FoldMap map = mapOf(5, 9);
        assertArrayEquals(new int[]{5, 9}, map.shiftedForInsert(12, 4));
    }

    @Test
    public void aDeletionAbovePullsTheFoldUp() {
        FoldMap map = mapOf(5, 9);
        assertArrayEquals(new int[]{3, 7}, map.shiftedForDelete(1, 3));
    }

    @Test
    public void aDeletionReachingIntoTheFoldOpensIt() {
        // What was folded is no longer what is there; showing it beats guessing.
        FoldMap map = mapOf(5, 9);
        assertEquals(0, map.shiftedForDelete(7, 8).length);
        assertEquals(0, map.shiftedForDelete(3, 6).length);
        assertEquals(0, map.shiftedForDelete(8, 12).length);
    }

    @Test
    public void aDeletionBelowLeavesItAlone() {
        FoldMap map = mapOf(5, 9);
        assertArrayEquals(new int[]{5, 9}, map.shiftedForDelete(11, 13));
    }

    @Test
    public void aFoldKnowsItsOwnLength() {
        FoldMap map = mapOf(5, 9, 12, 13);
        assertEquals(5, map.lengthOfRangeStartingAt(5));
        assertEquals(2, map.lengthOfRangeStartingAt(12));
        assertEquals(0, map.lengthOfRangeStartingAt(6));
    }

    @Test
    public void manyFoldsStillAgreeInBothDirections() {
        FoldMap map = new FoldMap();
        int lines = 1000;
        int[] ranges = new int[100];
        for (int i = 0; i < 50; i++) {
            ranges[i * 2] = i * 20 + 3;
            ranges[i * 2 + 1] = i * 20 + 11;
        }
        map.set(ranges, lines);
        assertEquals(50 * 9, map.totalHidden());
        int rows = lines - map.totalHidden();
        for (int row = 0; row < rows; row++) {
            int line = map.lineForRow(row);
            assertFalse(map.isHidden(line));
            assertEquals(row, map.rowForLine(line));
        }
        assertTrue(map.hasFolds());
    }
}
