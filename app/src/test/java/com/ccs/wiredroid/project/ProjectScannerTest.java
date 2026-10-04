package com.ccs.wiredroid.project;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;

/**
 * Deciding whether the Run button runs a file or runs the test suite.
 *
 * <p>Getting this wrong is expensive in both directions: a false positive makes
 * plain Run resolve surefire and compile the whole test source set, and a false
 * negative runs a test class as a program with no main method.</p>
 */
public class ProjectScannerTest {

    private static boolean isTest(String path, String source) {
        return ProjectScanner.isTestFile(new File(path), source);
    }

    @Test
    public void aPackageNamedTestIsNotATestSourceRoot() {
        // The reported case: package com.ccs.test put "/test/" in the path, so
        // running App.java built and ran the test suite instead.
        assertFalse(isTest("/proj/src/main/java/com/ccs/test/App.java",
                "package com.ccs.test; public class App { }"));
        assertFalse(isTest("/proj/src/main/java/com/example/tests/Helper.java",
                "public class Helper { }"));
        assertFalse(isTest("/proj/src/main/kotlin/com/ccs/test/App.kt", "class App"));
    }

    @Test
    public void realTestSourceRootsStillCount() {
        assertTrue(isTest("/proj/src/test/java/com/ccs/AppTest.java", ""));
        assertTrue(isTest("/proj/src/test/kotlin/com/ccs/AppSpec.kt", ""));
        assertTrue(isTest("/proj/src/androidTest/java/com/ccs/UiCheck.java", ""));
        assertTrue(isTest("/proj/test/java/com/ccs/Legacy.java", ""));
        assertTrue(isTest("/proj/tests/java/com/ccs/Legacy.java", ""));
    }

    @Test
    public void namesStillCount() {
        assertTrue(isTest("/proj/src/main/java/com/ccs/ZooTest.java", ""));
        assertTrue(isTest("/proj/src/main/java/com/ccs/ZooTests.java", ""));
        assertTrue(isTest("/proj/src/main/java/com/ccs/ZooTestCase.java", ""));
        assertTrue(isTest("/proj/src/main/java/com/ccs/ZooIT.java", ""));
        assertTrue(isTest("/proj/src/main/kotlin/com/ccs/ZooTest.kt", ""));
    }

    @Test
    public void annotationsStillCount() {
        assertTrue(isTest("/proj/src/main/java/com/ccs/Checks.java",
                "import org.junit.Test;\npublic class Checks { @Test public void a() {} }"));
        assertTrue(isTest("/proj/src/main/java/com/ccs/Checks.java",
                "public class Checks extends TestCase { }"));
    }

    @Test
    public void ordinaryFilesAreNotTests() {
        assertFalse(isTest("/proj/src/main/java/com/ccs/Zoo.java",
                "public class Zoo { public static void main(String[] a) {} }"));
    }
}
