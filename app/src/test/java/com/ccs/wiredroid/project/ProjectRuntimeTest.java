package com.ccs.wiredroid.project;

import static org.junit.Assert.assertEquals;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class ProjectRuntimeTest {

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void projectWithoutMetadataDefaultsToArt() throws Exception {
        File project = temporaryFolder.newFolder("old-project");
        assertEquals(ProjectRuntime.Mode.ART, ProjectRuntime.resolve(project));
    }

    @Test
    public void javaSeModeRoundTripsThroughProjectMetadata() throws Exception {
        File project = temporaryFolder.newFolder("java-se-project");
        ProjectRuntime.set(project, ProjectRuntime.Mode.JAVA_SE_21);

        assertEquals(ProjectRuntime.Mode.JAVA_SE_21, ProjectRuntime.resolve(project));
        assertEquals("mode=java-se-21\n", new String(Files.readAllBytes(
                new File(project, ".wiredroid/runtime.properties").toPath()),
                StandardCharsets.UTF_8));
    }

    @Test
    public void unknownModeFallsBackToArt() throws Exception {
        File project = temporaryFolder.newFolder("future-project");
        File metadata = new File(project, ".wiredroid/runtime.properties");
        metadata.getParentFile().mkdirs();
        Files.write(metadata.toPath(), "mode=java-se-99\n".getBytes(StandardCharsets.UTF_8));

        assertEquals(ProjectRuntime.Mode.ART, ProjectRuntime.resolve(project));
    }
}
