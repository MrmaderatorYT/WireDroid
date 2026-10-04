package com.ccs.wiredroid.util;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LocalHistoryDiffTest {
    @Test public void restoresOnlySelectedReplacement() {
        String current = "one\nCURRENT\nthree\nCURRENT-2\n";
        String snapshot = "one\nOLD\nthree\nOLD-2\n";
        List<LocalHistoryDiff.Hunk> hunks = LocalHistoryDiff.hunks(current, snapshot);
        assertEquals(2, hunks.size());
        assertEquals("one\nOLD\nthree\nCURRENT-2\n",
                LocalHistoryDiff.restoreHunk(current, snapshot, hunks.get(0)));
    }

    @Test public void restoresInsertionAndDeletion() {
        String current = "a\nc\nextra\n";
        String snapshot = "a\nb\nc\n";
        List<LocalHistoryDiff.Hunk> hunks = LocalHistoryDiff.hunks(current, snapshot);
        String value = current;
        // Apply from bottom to top so original coordinates remain stable.
        for (int i = hunks.size() - 1; i >= 0; i--) {
            value = LocalHistoryDiff.restoreHunk(value, snapshot, hunks.get(i));
        }
        assertEquals(snapshot, value);
    }

    @Test public void createsUnifiedPreview() {
        String diff = LocalHistoryDiff.unifiedDiff("a\nnew\n", "a\nold\n", "Current", "Snapshot");
        assertTrue(diff.contains("--- Current"));
        assertTrue(diff.contains("+++ Snapshot"));
        assertTrue(diff.contains("-new"));
        assertTrue(diff.contains("+old"));
        assertFalse(diff.contains("No differences"));
    }
}
