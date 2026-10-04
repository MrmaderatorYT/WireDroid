package com.ccs.wiredroid.project;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class ProjectScannerKotlinTest {

    @Rule
    public final TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void testListKotlinSourcesInMainKotlin() throws IOException {
        File projectRoot = tempFolder.newFolder("kt-project");
        File mainKotlin = new File(projectRoot, "src/main/kotlin/com/example");
        assertTrue(mainKotlin.mkdirs());
        File mainKt = new File(mainKotlin, "Main.kt");
        writeFile(mainKt, "package com.example\nfun main() { println(\"Hi\") }\n");

        List<File> ktSources = ProjectScanner.listKotlinSources(projectRoot);
        assertEquals(1, ktSources.size());
        assertEquals(mainKt.getAbsolutePath(), ktSources.get(0).getAbsolutePath());

        List<File> javaSources = ProjectScanner.listJavaSources(projectRoot);
        assertTrue(javaSources.isEmpty());
    }

    @Test
    public void testListKotlinSourcesMixed() throws IOException {
        File projectRoot = tempFolder.newFolder("mixed-project");
        File mainKotlin = new File(projectRoot, "src/main/kotlin/com/example");
        assertTrue(mainKotlin.mkdirs());
        File mainKt = new File(mainKotlin, "App.kt");
        writeFile(mainKt, "package com.example\nclass App\n");

        File mainJava = new File(projectRoot, "src/main/java/com/example");
        assertTrue(mainJava.mkdirs());
        File helperJava = new File(mainJava, "Helper.java");
        writeFile(helperJava, "package com.example;\npublic class Helper {}\n");

        List<File> ktSources = ProjectScanner.listKotlinSources(projectRoot);
        assertEquals(1, ktSources.size());
        assertEquals(mainKt.getAbsolutePath(), ktSources.get(0).getAbsolutePath());

        List<File> javaSources = ProjectScanner.listJavaSources(projectRoot);
        assertEquals(1, javaSources.size());
        assertEquals(helperJava.getAbsolutePath(), javaSources.get(0).getAbsolutePath());
    }

    @Test
    public void testListTestKotlinSources() throws IOException {
        File projectRoot = tempFolder.newFolder("test-kt-project");
        File testKotlin = new File(projectRoot, "src/test/kotlin/com/example");
        assertTrue(testKotlin.mkdirs());
        File testKt = new File(testKotlin, "MainTest.kt");
        writeFile(testKt, "package com.example\nclass MainTest\n");

        List<File> testKtSources = ProjectScanner.listTestKotlinSources(projectRoot);
        assertEquals(1, testKtSources.size());
        assertEquals(testKt.getAbsolutePath(), testKtSources.get(0).getAbsolutePath());
    }

    private static void writeFile(File file, String content) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }
}
