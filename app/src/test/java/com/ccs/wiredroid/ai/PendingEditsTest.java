package com.ccs.wiredroid.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

import java.util.List;

/**
 * The queue the agent writes edits into.
 *
 * <p>The interesting property is that a patch carries the text it expects to
 * replace. Without that the agent could only add code or overwrite the file, so
 * changing one method meant pasting a second copy of it or rewriting everything
 * — which is what "it inserts the code instead of editing" looked like.</p>
 */
public class PendingEditsTest {

    @After
    public void tearDown() {
        PendingEdits.clear();
    }

    @Test
    public void patchKeepsBothHalvesOfTheEdit() {
        PendingEdits.addPatch("int x = 1;", "int x = 2;");
        List<PendingEdits.Edit> edits = PendingEdits.drain();
        assertEquals(1, edits.size());
        assertEquals(PendingEdits.LOCATION_PATCH, edits.get(0).location);
        assertEquals("int x = 1;", edits.get(0).find);
        assertEquals("int x = 2;", edits.get(0).code);
    }

    @Test
    public void patchKeepsItsTargetFile() {
        PendingEdits.addPatch("/project/src/Main.java", "old", "new");
        List<PendingEdits.Edit> edits = PendingEdits.drain();

        assertEquals(1, edits.size());
        assertEquals("/project/src/Main.java", edits.get(0).path);
        assertEquals("old", edits.get(0).find);
        assertEquals("new", edits.get(0).code);
    }

    @Test
    public void fileTargetedPatchesRemainOrdered() {
        PendingEdits.addPatch("/project/A.java", "one", "ONE");
        PendingEdits.addPatch("/project/A.java", "ONE two", "done");
        List<PendingEdits.Edit> edits = PendingEdits.drain();

        assertEquals(2, edits.size());
        assertEquals("one", edits.get(0).find);
        assertEquals("ONE two", edits.get(1).find);
        assertEquals("/project/A.java", edits.get(1).path);
    }

    @Test
    public void patchMayDelete() {
        // Empty replacement is a real edit — removing a line — and must not be
        // dropped the way an empty *insert* is.
        PendingEdits.addPatch("System.out.println(debug);\n", "");
        List<PendingEdits.Edit> edits = PendingEdits.drain();
        assertEquals(1, edits.size());
        assertEquals("", edits.get(0).code);
    }

    @Test
    public void patchWithoutTargetIsRefused() {
        PendingEdits.addPatch("", "something");
        PendingEdits.addPatch(null, "something");
        assertTrue(PendingEdits.drain().isEmpty());
    }

    @Test
    public void plainInsertsStillCarryNoTarget() {
        PendingEdits.add("int y = 3;", PendingEdits.LOCATION_CURSOR);
        List<PendingEdits.Edit> edits = PendingEdits.drain();
        assertEquals(1, edits.size());
        assertEquals(null, edits.get(0).find);
    }

    @Test
    public void orderIsPreservedAcrossMixedEdits() {
        // Two patches against the same file only compose if they arrive in the
        // order the model asked for them.
        PendingEdits.addPatch("a", "A");
        PendingEdits.add("new code", PendingEdits.LOCATION_APPEND);
        PendingEdits.addPatch("b", "B");

        List<PendingEdits.Edit> edits = PendingEdits.drain();
        assertEquals(3, edits.size());
        assertEquals("a", edits.get(0).find);
        assertEquals(PendingEdits.LOCATION_APPEND, edits.get(1).location);
        assertEquals("b", edits.get(2).find);
    }

    @Test
    public void drainingEmptiesTheQueue() {
        PendingEdits.addPatch("a", "A");
        PendingEdits.drain();
        assertTrue("a drained edit must not be applied twice",
                PendingEdits.drain().isEmpty());
    }
}
