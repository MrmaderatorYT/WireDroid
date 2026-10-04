package com.ccs.wiredroid.scratch;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class ScratchManagerTest {

    private File tempDir;

    @Before
    public void setUp() throws IOException {
        tempDir = Files.createTempDirectory("scratch_test_").toFile();
    }

    @After
    public void tearDown() {
        if (tempDir != null && tempDir.exists()) {
            deleteRecursive(tempDir);
        }
    }

    private void deleteRecursive(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    @Test
    public void testCreateJavaScratchDefaultNaming() throws IOException {
        File scratch1 = ScratchManager.createJavaScratch(tempDir, null);
        assertNotNull(scratch1);
        assertTrue(scratch1.exists());
        assertEquals("Scratch.java", scratch1.getName());

        String content1 = new String(Files.readAllBytes(scratch1.toPath()));
        assertTrue(content1.contains("public class Scratch"));
        assertTrue(content1.contains("public static void main(String[] args)"));

        // Second scratch should be Scratch_1.java
        File scratch2 = ScratchManager.createJavaScratch(tempDir, "");
        assertNotNull(scratch2);
        assertTrue(scratch2.exists());
        assertEquals("Scratch_1.java", scratch2.getName());

        String content2 = new String(Files.readAllBytes(scratch2.toPath()));
        assertTrue(content2.contains("public class Scratch_1"));

        // Third scratch should be Scratch_2.java
        File scratch3 = ScratchManager.createJavaScratch(tempDir, null);
        assertNotNull(scratch3);
        assertEquals("Scratch_2.java", scratch3.getName());
    }

    @Test
    public void testCreateJavaScratchCustomName() throws IOException {
        File scratch = ScratchManager.createJavaScratch(tempDir, "AlgorithmTest");
        assertNotNull(scratch);
        assertTrue(scratch.exists());
        assertEquals("AlgorithmTest.java", scratch.getName());

        String content = new String(Files.readAllBytes(scratch.toPath()));
        assertTrue(content.contains("public class AlgorithmTest"));
    }

    @Test
    public void testCreateJavaScratchSanitizeName() throws IOException {
        File scratch = ScratchManager.createJavaScratch(tempDir, "123-bad!name");
        assertNotNull(scratch);
        assertTrue(scratch.exists());
        assertEquals("_123_bad_name.java", scratch.getName());

        String content = new String(Files.readAllBytes(scratch.toPath()));
        assertTrue(content.contains("public class _123_bad_name"));
    }

    @Test
    public void testCreateKotlinScratch() throws IOException {
        File scratch1 = ScratchManager.createKotlinScratch(tempDir, null);
        assertNotNull(scratch1);
        assertTrue(scratch1.exists());
        assertEquals("scratch.kt", scratch1.getName());

        String content1 = new String(Files.readAllBytes(scratch1.toPath()));
        assertTrue(content1.contains("fun main()"));

        File scratch2 = ScratchManager.createKotlinScratch(tempDir, null);
        assertNotNull(scratch2);
        assertEquals("scratch_1.kt", scratch2.getName());
    }

    @Test
    public void testListScratches() throws IOException {
        File s1 = ScratchManager.createJavaScratch(tempDir, "TestA");
        File s2 = ScratchManager.createKotlinScratch(tempDir, "test_b");

        List<File> list = ScratchManager.listScratches(tempDir);
        assertEquals(2, list.size());

        assertTrue(ScratchManager.isScratchFile(tempDir, s1));
        assertTrue(ScratchManager.isScratchFile(tempDir, s2));

        File outsideFile = new File(tempDir, "Outside.java");
        assertFalse(ScratchManager.isScratchFile(tempDir, outsideFile));
    }

    @Test
    public void testDeleteScratch() throws IOException {
        File scratch = ScratchManager.createJavaScratch(tempDir, "ToDelete");
        assertTrue(scratch.exists());

        boolean deleted = ScratchManager.deleteScratch(scratch);
        assertTrue(deleted);
        assertFalse(scratch.exists());
    }

    @Test
    public void createJavaScratchNeverOverwritesAnExistingScratch() throws IOException {
        File first = ScratchManager.createJavaScratch(tempDir, "Algo");
        Files.write(first.toPath(), "public class Algo { /* real work */ }".getBytes());

        File second = ScratchManager.createJavaScratch(tempDir, "Algo");

        assertEquals("Algo_1.java", second.getName());
        assertNotEquals(first, second);
        assertTrue("the first scratch must still be there", first.exists());
        assertTrue("and must still hold what was typed into it",
                new String(Files.readAllBytes(first.toPath())).contains("real work"));
        assertTrue("the new one gets a class name matching its file name",
                new String(Files.readAllBytes(second.toPath())).contains("public class Algo_1"));
    }

    @Test
    public void createKotlinScratchNeverOverwritesAnExistingScratch() throws IOException {
        File first = ScratchManager.createKotlinScratch(tempDir, "probe");
        Files.write(first.toPath(), "fun main() { /* real work */ }".getBytes());

        File second = ScratchManager.createKotlinScratch(tempDir, "probe");

        assertEquals("probe_1.kt", second.getName());
        assertTrue(new String(Files.readAllBytes(first.toPath())).contains("real work"));
    }

    @Test
    public void createScratchKeepsNamesInsideTheScratchDirectory() throws IOException {
        // An unsanitised name carrying a separator would have written outside it.
        File escaped = ScratchManager.createKotlinScratch(tempDir, "../escaped");
        assertTrue(ScratchManager.isScratchFile(tempDir, escaped));
        assertEquals(ScratchManager.getScratchDir(tempDir), escaped.getParentFile());
    }

    @Test
    public void isScratchFileDoesNotClaimSimilarlyNamedNeighbours() {
        File lookalikeDir = new File(tempDir, "scratches_backup");
        assertTrue(lookalikeDir.mkdirs());

        assertFalse(ScratchManager.isScratchFile(tempDir, new File(lookalikeDir, "Old.java")));
    }

    @Test
    public void isScratchFileAsksWithoutCreatingTheDirectory() {
        File fresh = new File(tempDir, "untouched");
        File candidate = new File(fresh, "scratches/Thing.java");

        assertTrue(ScratchManager.isScratchFile(fresh, candidate));
        assertFalse("a read-only question must not leave a directory behind",
                new File(fresh, "scratches").exists());
    }
}
