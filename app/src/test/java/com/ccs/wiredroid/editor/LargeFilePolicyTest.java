package com.ccs.wiredroid.editor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** How much of the editor a file gets, decided by its size alone. */
public class LargeFilePolicyTest {

    private static final long KB = 1024;
    private static final long MB = 1024 * KB;

    @Test
    public void ordinarySourceGetsEverything() {
        // A 4000-line Java file is well under 200 KB.
        assertEquals(LargeFilePolicy.Mode.FULL, LargeFilePolicy.modeFor(180 * KB));
        assertEquals(LargeFilePolicy.Mode.FULL, LargeFilePolicy.modeFor(0));
    }

    @Test
    public void pastTwoMegabytesHighlightingGoes() {
        assertEquals(LargeFilePolicy.Mode.FULL, LargeFilePolicy.modeFor(2 * MB - 1));
        assertEquals(LargeFilePolicy.Mode.PLAIN, LargeFilePolicy.modeFor(2 * MB));
        assertEquals(LargeFilePolicy.Mode.PLAIN, LargeFilePolicy.modeFor(9 * MB));
    }

    @Test
    public void pastTwelveMegabytesItIsReadOnly() {
        assertEquals(LargeFilePolicy.Mode.PLAIN, LargeFilePolicy.modeFor(12 * MB - 1));
        assertEquals(LargeFilePolicy.Mode.READ_ONLY, LargeFilePolicy.modeFor(12 * MB));
        assertEquals(LargeFilePolicy.Mode.READ_ONLY, LargeFilePolicy.modeFor(400 * MB));
    }

    @Test
    public void aMissingFileIsTreatedAsOrdinary() {
        // Nothing to protect against, and refusing to open would be worse.
        assertEquals(LargeFilePolicy.Mode.FULL, LargeFilePolicy.modeFor((java.io.File) null));
        assertEquals(LargeFilePolicy.Mode.FULL,
                LargeFilePolicy.modeFor(new java.io.File("/no/such/file.java")));
        assertFalse(LargeFilePolicy.readsAsync(null));
    }

    @Test
    public void sizesReadLikeSizes() {
        assertEquals("512 B", LargeFilePolicy.describeSize(512));
        assertEquals("2 KB", LargeFilePolicy.describeSize(2 * KB));
        assertEquals("1.5 MB", LargeFilePolicy.describeSize(3 * MB / 2));
    }

    @Test
    public void theAsyncThresholdIsBelowThePlainOne() {
        // Otherwise a file would lose highlighting before it was ever read off
        // the main thread, which is the wrong way round.
        assertTrue(LargeFilePolicy.ASYNC_READ_BYTES < LargeFilePolicy.PLAIN_BYTES);
        assertTrue(LargeFilePolicy.PLAIN_BYTES < LargeFilePolicy.READ_ONLY_BYTES);
    }
}
