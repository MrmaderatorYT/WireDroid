package com.ccs.wiredroid.ant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.ccs.wiredroid.project.BuildSystem;
import com.ccs.wiredroid.project.LocalLibraries;
import com.ccs.wiredroid.project.ProjectScanner;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * Reading an Ant build file into the model the compiler already understands.
 *
 * <p>Ant prescribes no layout, so almost everything the pipeline needs — where
 * the sources are, which Java level, what is on the classpath, what to run — is
 * only knowable from the script. Each of those is a way an Ant project would
 * otherwise build empty or not at all.</p>
 */
public class AntBuildParserTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private File write(String path, String body) throws Exception {
        File f = new File(tmp.getRoot(), path);
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), body.getBytes(StandardCharsets.UTF_8));
        return f;
    }

    private AntBuildParser.Result parse(String buildXml) throws Exception {
        write("build.xml", buildXml);
        return AntBuildParser.parse(tmp.getRoot());
    }

    @Test
    public void projectNameBecomesTheArtifact() throws Exception {
        AntBuildParser.Result r = parse(
                "<project name=\"demo\" default=\"jar\" basedir=\".\"></project>");
        assertEquals("demo", r.pom.artifactId);
        assertEquals("jar", r.defaultTarget);
    }

    @Test
    public void javacTellsWhereTheSourcesAre() throws Exception {
        write("source/com/example/App.java", "package com.example; class App {}");
        AntBuildParser.Result r = parse(
                "<project name=\"demo\">"
              + "  <property name=\"src.dir\" value=\"source\"/>"
              + "  <target name=\"compile\">"
              + "    <javac srcdir=\"${src.dir}\" destdir=\"out\" source=\"17\" target=\"17\"/>"
              + "  </target>"
              + "</project>");
        assertEquals(new File(tmp.getRoot(), "source"), r.sourceDir);
        assertEquals(new File(tmp.getRoot(), "out"), r.outputDir);
        assertEquals("17", r.pom.properties.get("maven.compiler.source"));
        assertEquals("17", r.pom.properties.get("maven.compiler.target"));
    }

    @Test
    public void theCompilerLooksWhereTheScriptSaid() throws Exception {
        // Without this the scanner only knows src/main/java, finds nothing, and
        // the project compiles to an empty jar without saying why.
        write("source/com/example/App.java", "package com.example; class App {}");
        parse("<project name=\"demo\">"
            + "  <target name=\"compile\"><javac srcdir=\"source\" destdir=\"out\"/></target>"
            + "</project>");
        List<File> sources = ProjectScanner.listJavaSources(tmp.getRoot());
        assertEquals(1, sources.size());
        assertEquals("App.java", sources.get(0).getName());
    }

    @Test
    public void propertiesAreImmutableAsAntMakesThem() throws Exception {
        // Ant keeps the first value a name is given; a later <property> for the
        // same name is ignored rather than overwriting it.
        AntBuildParser.Result r = parse(
                "<project name=\"demo\">"
              + "  <property name=\"out\" value=\"first\"/>"
              + "  <property name=\"out\" value=\"second\"/>"
              + "</project>");
        assertEquals("first", r.pom.properties.get("out"));
    }

    @Test
    public void propertyFilesAreLoaded() throws Exception {
        write("build.properties", "app.version=2.5\nlib.dir=vendor\n");
        AntBuildParser.Result r = parse(
                "<project name=\"demo\">"
              + "  <property file=\"build.properties\"/>"
              + "  <property name=\"version\" value=\"${app.version}\"/>"
              + "</project>");
        assertEquals("2.5", r.pom.properties.get("app.version"));
        assertEquals("2.5", r.pom.version);
    }

    @Test
    public void aMissingPropertyFileIsNotAFailure() throws Exception {
        // Scripts routinely offer an optional overrides file; Ant carries on
        // when it is absent, and a build that refused to load would be useless.
        AntBuildParser.Result r = parse(
                "<project name=\"demo\"><property file=\"nope.properties\"/></project>");
        assertEquals("demo", r.pom.artifactId);
    }

    @Test
    public void unknownPlaceholdersAreLeftAlone() throws Exception {
        AntBuildParser.Result r = parse(
                "<project name=\"demo\"><property name=\"a\" value=\"${missing}/x\"/></project>");
        assertEquals("${missing}/x", r.pom.properties.get("a"));
    }

    @Test
    public void mainClassComesFromTheJarManifest() throws Exception {
        AntBuildParser.Result r = parse(
                "<project name=\"demo\">"
              + "  <target name=\"run\"><java classname=\"com.example.Other\"/></target>"
              + "  <target name=\"jar\">"
              + "    <jar destfile=\"out/demo.jar\">"
              + "      <manifest><attribute name=\"Main-Class\" value=\"com.example.Main\"/></manifest>"
              + "    </jar>"
              + "  </target>"
              + "</project>");
        assertEquals("the manifest is the stronger statement",
                "com.example.Main", r.pom.mainClass);
    }

    @Test
    public void aRunTargetNamesTheMainClassWhenThereIsNoManifest() throws Exception {
        AntBuildParser.Result r = parse(
                "<project name=\"demo\">"
              + "  <target name=\"run\"><java classname=\"com.example.App\" fork=\"true\"/></target>"
              + "</project>");
        assertEquals("com.example.App", r.pom.mainClass);
    }

    @Test
    public void jarFoldersBecomeTheClasspath() throws Exception {
        write("vendor/thing.jar", "not really a jar");
        AntBuildParser.Result r = parse(
                "<project name=\"demo\">"
              + "  <property name=\"lib.dir\" value=\"vendor\"/>"
              + "  <path id=\"cp\"><fileset dir=\"${lib.dir}\" includes=\"**/*.jar\"/></path>"
              + "</project>");
        assertTrue(r.libraryDirs.contains(new File(tmp.getRoot(), "vendor")));

        // An Ant project has no coordinates, so those folders are its whole
        // classpath and have to reach the compiler like any carried library.
        List<File> jars = LocalLibraries.list(tmp.getRoot());
        assertEquals(1, jars.size());
        assertEquals("thing.jar", jars.get(0).getName());
    }

    @Test
    public void tasksNeedingRealAntAreReportedNotFailed() throws Exception {
        AntBuildParser.Result r = parse(
                "<project name=\"demo\">"
              + "  <taskdef name=\"custom\" classname=\"x.Y\"/>"
              + "  <target name=\"x\"><exec executable=\"ls\"/></target>"
              + "</project>");
        assertEquals("demo", r.pom.artifactId);
        String joined = String.join("|", r.warnings);
        assertTrue(joined, joined.contains("taskdef"));
        assertTrue(joined, joined.contains("exec"));
    }

    @Test
    public void ivyIsCalledOutBecauseItsJarsWillBeMissing() throws Exception {
        write("ivy.xml", "<ivy-module version=\"2.0\"/>");
        AntBuildParser.Result r = parse("<project name=\"demo\"></project>");
        assertTrue(String.join("|", r.warnings).contains("ivy.xml"));
    }

    @Test
    public void targetsAreListedInOrder() throws Exception {
        AntBuildParser.Result r = parse(
                "<project name=\"demo\" default=\"jar\">"
              + "  <target name=\"clean\"/><target name=\"compile\"/><target name=\"jar\"/>"
              + "</project>");
        assertEquals(java.util.Arrays.asList("clean", "compile", "jar"), r.targets);
    }

    @Test
    public void aBrokenScriptFailsWithAReadableMessage() throws Exception {
        write("build.xml", "<project name=\"demo\"");
        try {
            AntBuildParser.parse(tmp.getRoot());
            org.junit.Assert.fail("expected an IOException");
        } catch (java.io.IOException e) {
            assertTrue(String.valueOf(e.getMessage()), e.getMessage().contains("build.xml"));
        }
    }

    // ── Detection ────────────────────────────────────────────────────────────

    @Test
    public void aBuildXmlMakesTheProjectBuildable() throws Exception {
        write("build.xml", "<project name=\"demo\"/>");
        assertEquals(BuildSystem.Kind.ANT, BuildSystem.detect(tmp.getRoot()));
        assertTrue(BuildSystem.isBuildable(tmp.getRoot()));
        assertEquals("Ant", BuildSystem.displayName(BuildSystem.Kind.ANT));
        assertEquals("build.xml", BuildSystem.buildScript(tmp.getRoot()).getName());
        assertEquals(BuildSystem.Kind.ANT, BuildSystem.model(tmp.getRoot()).kind);
    }

    @Test
    public void aNewerBuildScriptWinsOverALeftoverBuildXml() throws Exception {
        // A project migrated to Maven or Gradle often keeps its old build.xml;
        // the script being maintained is the one to read.
        write("build.xml", "<project name=\"old\"/>");
        write("build.gradle", "group = 'com.example'");
        assertEquals(BuildSystem.Kind.GRADLE, BuildSystem.detect(tmp.getRoot()));
        write("pom.xml", "<project><artifactId>new</artifactId></project>");
        assertEquals(BuildSystem.Kind.MAVEN, BuildSystem.detect(tmp.getRoot()));
    }

    @Test
    public void aDirectoryWithNoScriptIsStillNone() throws Exception {
        assertEquals(BuildSystem.Kind.NONE, BuildSystem.detect(tmp.getRoot()));
        assertFalse(AntPaths.isAntProject(tmp.getRoot()));
        assertNull(AntPaths.sourceDir(tmp.getRoot()));
        assertTrue(AntPaths.libraryDirs(tmp.getRoot()).isEmpty());
    }

    @Test
    public void conventionalFoldersAnswerWhenTheScriptDoesNot() throws Exception {
        write("build.xml", "<project name=\"demo\"/>");
        write("src/App.java", "class App {}");
        write("lib/dep.jar", "x");
        assertEquals(new File(tmp.getRoot(), "src"), AntPaths.sourceDir(tmp.getRoot()));
        assertEquals(java.util.Collections.singletonList(new File(tmp.getRoot(), "lib")),
                AntPaths.libraryDirs(tmp.getRoot()));
    }

    @Test
    public void importingAnAntProjectLeavesItAsAnt() throws Exception {
        // The importer writes a build.gradle for a project that has no build
        // system. An Ant project has one, and generating a second script would
        // change which one the app reads.
        write("build.xml", "<project name=\"legacy\"><target name=\"compile\">"
                + "<javac srcdir=\"src\" destdir=\"out\"/></target></project>");
        write("src/App.java", "class App {}");

        com.ccs.wiredroid.project.ImportedLayout layout =
                com.ccs.wiredroid.project.ProjectLayoutDetector.detect(tmp.getRoot());
        com.ccs.wiredroid.project.ImportedProjectConfigurator.Outcome outcome =
                com.ccs.wiredroid.project.ImportedProjectConfigurator.configure(null, layout);

        if (com.ccs.wiredroid.BuildConfig.EMBEDDED_EDITION) {
            assertEquals(java.util.Collections.singletonList(com.ccs.wiredroid.wire.WireProject.SCENE),
                    outcome.generated);
            com.ccs.wiredroid.wire.WireProject.validateScene(
                    com.ccs.wiredroid.wire.WireProject.readFile(
                            new File(tmp.getRoot(), com.ccs.wiredroid.wire.WireProject.SCENE), 1024 * 1024));
        } else {
            assertTrue("nothing should have been generated", outcome.generated.isEmpty());
        }
        assertEquals("class App {}", new String(java.nio.file.Files.readAllBytes(
                new File(tmp.getRoot(), "src/App.java").toPath()), java.nio.charset.StandardCharsets.UTF_8));
        assertFalse(new File(tmp.getRoot(), "build.gradle").exists());
        assertEquals(BuildSystem.Kind.ANT, BuildSystem.detect(tmp.getRoot()));
    }
}
