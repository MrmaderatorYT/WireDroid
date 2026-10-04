package com.ccs.wiredroid.archive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;

/**
 * The zip-slip guard.
 *
 * <p>An archive entry can be named {@code ../../databases/x}. Without a check,
 * extracting it writes outside the destination folder — into the app's own data
 * in this case, since that is what an IDE has access to. The guard exists and is
 * commented; this is here so that a later tidy-up cannot quietly remove it. The
 * failure mode has no symptom until someone opens a malicious archive.</p>
 */
public class ArchiveExtractorResolveTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File root;
    private ArchiveExtractor.Counters counters;

    private File resolve(String entryName) throws IOException {
        if (root == null) {
            root = tmp.newFolder("dest");
            counters = new ArchiveExtractor.Counters();
        }
        return ArchiveExtractor.resolve(root, entryName, counters);
    }

    @Test
    public void ordinaryEntryLandsInsideTheDestination() throws Exception {
        File f = resolve("src/main/java/App.java");
        assertNotNull("a normal entry must resolve", f);
        assertTrue("resolved outside the destination: " + f.getPath(),
                f.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator));
    }

    @Test
    public void parentTraversalIsRefused() throws Exception {
        assertNull("../evil must be skipped", resolve("../evil.txt"));
        assertNull("deep traversal must be skipped", resolve("../../../../etc/passwd"));
        assertNull("traversal in the middle must be skipped", resolve("a/b/../../../../evil"));
    }

    @Test
    public void backslashSeparatorsAreNormalisedNotTrusted() throws Exception {
        // Archives written on Windows use backslashes; the traversal has to be
        // caught after normalising, not before.
        assertNull("..\\evil must be skipped", resolve("..\\evil.txt"));
    }

    @Test
    public void leadingSlashDoesNotEscapeToTheFilesystemRoot() throws Exception {
        File f = resolve("/etc/passwd");
        assertNotNull("a rooted name should be treated as relative, not skipped", f);
        assertTrue("an absolute-looking entry escaped: " + f.getPath(),
                f.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator));
    }

    @Test
    public void windowsDriveLetterIsRefused() throws Exception {
        assertNull("C:/x must be skipped", resolve("C:/windows/system32/evil"));
    }

    @Test
    public void emptyAndDotNamesAreRefused() throws Exception {
        assertNull(resolve(""));
        assertNull(resolve("."));
        assertNull(resolve(".."));
        assertNull(resolve(null));
    }

    @Test
    public void everyRefusalIsRecordedNotSilent() throws Exception {
        resolve("../evil.txt");
        resolve("C:/evil");
        assertEquals("skipped entries must be reported to the user, not dropped",
                2, counters.skipped.size());
        assertTrue("the reason should mention the escape",
                counters.skipped.toString().contains("escapes")
                        || counters.skipped.toString().contains("absolute"));
    }
}
