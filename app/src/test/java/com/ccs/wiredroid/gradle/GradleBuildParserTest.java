package com.ccs.wiredroid.gradle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.ccs.wiredroid.maven.PomModel;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileWriter;

/**
 * Gradle projects are read into the same {@link PomModel} the Maven side uses,
 * so everything downstream does not need to care which build system it is. That
 * translation is the part worth testing.
 */
public class GradleBuildParserTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File projectWith(String fileName, String body) throws Exception {
        File root = tmp.newFolder("proj" + System.nanoTime());
        try (FileWriter w = new FileWriter(new File(root, fileName))) {
            w.write(body);
        }
        return root;
    }

    private static final String GROOVY =
            "plugins {\n"
            + "    id 'java'\n"
            + "    id 'application'\n"
            + "}\n"
            + "group = 'com.example.g'\n"
            + "version = '3.1.4'\n"
            + "\n"
            + "java {\n"
            + "    sourceCompatibility = JavaVersion.VERSION_17\n"
            + "}\n"
            + "\n"
            + "application {\n"
            + "    mainClass = 'com.example.g.Main'\n"
            + "}\n"
            + "\n"
            + "dependencies {\n"
            + "    implementation 'com.google.code.gson:gson:2.10.1'\n"
            + "    testImplementation 'junit:junit:4.13.2'\n"
            + "}\n";

    @Test
    public void readsCoordinatesFromGroovyDsl() throws Exception {
        GradleBuildParser.Result r = GradleBuildParser.parse(projectWith("build.gradle", GROOVY));
        assertEquals("com.example.g", r.pom.groupId);
        assertEquals("3.1.4", r.pom.version);
        assertTrue("the Groovy DSL should not be reported as Kotlin", !r.kotlinDsl);
    }

    @Test
    public void readsDependencies() throws Exception {
        GradleBuildParser.Result r = GradleBuildParser.parse(projectWith("build.gradle", GROOVY));
        assertTrue("expected both dependencies, got " + r.pom.dependencies.size(),
                r.pom.dependencies.size() >= 2);
    }

    @Test
    public void findsTheMainClassSoRunHasSomethingToLaunch() throws Exception {
        GradleBuildParser.Result r = GradleBuildParser.parse(projectWith("build.gradle", GROOVY));
        assertEquals("com.example.g.Main", r.pom.mainClass);
    }

    @Test
    public void kotlinDslIsRecognised() throws Exception {
        String kts = "plugins { java }\n"
                + "group = \"com.example.k\"\n"
                + "version = \"1.0\"\n"
                + "dependencies {\n"
                + "    implementation(\"com.google.code.gson:gson:2.10.1\")\n"
                + "}\n";
        GradleBuildParser.Result r = GradleBuildParser.parse(projectWith("build.gradle.kts", kts));
        assertTrue("build.gradle.kts must be flagged as the Kotlin DSL", r.kotlinDsl);
        assertEquals("com.example.k", r.pom.groupId);
    }

    @Test
    public void aProjectWithNoBuildScriptStillGivesAModel() throws Exception {
        // parseOrDefault is what the UI calls; it must never leave the caller
        // without a model, or every screen that reads one has to null-check.
        File empty = tmp.newFolder("empty" + System.nanoTime());
        GradleBuildParser.Result r = GradleBuildParser.parseOrDefault(empty);
        assertTrue(r.pom != null);
    }

    @Test
    public void nonsenseScriptDoesNotThrow() throws Exception {
        GradleBuildParser.Result r = GradleBuildParser.parseOrDefault(
                projectWith("build.gradle", "}}}} not gradle at all ((("));
        assertTrue(r.pom != null);
    }
}
