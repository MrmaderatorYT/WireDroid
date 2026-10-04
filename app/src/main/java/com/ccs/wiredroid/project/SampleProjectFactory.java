package com.ccs.wiredroid.project;

import android.content.Context;

import com.ccs.wiredroid.maven.MavenPaths;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

/**
 * Creates ready-to-run Java sample projects from {@link SampleRegistry}.
 */
public final class SampleProjectFactory {

    private SampleProjectFactory() {}

    public static File create(Context context, String projectName, String sampleId, String javaTarget) throws IOException {
        SampleRegistry.SampleItem sample = SampleRegistry.getOrDefault(sampleId);
        String safeName = (projectName == null || projectName.trim().isEmpty())
                ? sample.defaultProjectName
                : ProjectTemplates.sanitizeName(projectName);

        File root = MavenPaths.projectDir(context, safeName);
        if (root.exists()) {
            // Find non-colliding name
            int counter = 1;
            while (root.exists()) {
                root = MavenPaths.projectDir(context, safeName + "_" + counter);
                counter++;
            }
        }
        root.mkdirs();

        String target = (javaTarget != null && !javaTarget.trim().isEmpty())
                ? javaTarget : ProjectJdk.defaultForNewProject(context);

        String gid = "com.example.sample";
        String pkgPath = gid.replace('.', File.separatorChar);
        File mainJavaPkg = new File(root, "src/main/java/" + pkgPath);
        mainJavaPkg.mkdirs();
        new File(root, "src/main/resources").mkdirs();
        new File(root, "target/classes").mkdirs();

        // Write pom.xml
        String pom = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"\n"
                + "         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n"
                + "         xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd\">\n"
                + "    <modelVersion>4.0.0</modelVersion>\n\n"
                + "    <groupId>" + gid + "</groupId>\n"
                + "    <artifactId>" + safeName.toLowerCase() + "</artifactId>\n"
                + "    <version>1.0-SNAPSHOT</version>\n\n"
                + "    <properties>\n"
                + "        <maven.compiler.source>" + target + "</maven.compiler.source>\n"
                + "        <maven.compiler.target>" + target + "</maven.compiler.target>\n"
                + "        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>\n"
                + "    </properties>\n"
                + "</project>\n";
        writeUtf8(new File(root, "pom.xml"), pom);

        // Write sample source code
        File mainJavaFile = new File(mainJavaPkg, sample.mainClassName + ".java");
        writeUtf8(mainJavaFile, sample.sourceCode);

        return root;
    }

    private static void writeUtf8(File target, String content) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try (OutputStreamWriter writer = new OutputStreamWriter(
                new FileOutputStream(target), StandardCharsets.UTF_8)) {
            writer.write(content);
        }
    }
}
