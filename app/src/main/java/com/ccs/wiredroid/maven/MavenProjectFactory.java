package com.ccs.wiredroid.maven;

import android.content.Context;

import com.ccs.wiredroid.project.ProjectJdk;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

/**
 * Створює Maven-проєкт зі стандартною структурою (як IntelliJ / Maven):
 * {@code pom.xml}, {@code src/main/java/&lt;package&gt;}, {@code src/main/resources},
 * {@code src/test/java/&lt;package&gt;}, {@code src/test/resources}, {@code target/...}.
 */
public final class MavenProjectFactory {

    private MavenProjectFactory() {}

    /** За замовчуванням: groupId {@code com.ccs.&lt;name&gt;}, artifactId — у нижньому регістрі. */
    public static File create(Context context, String projectName) throws IOException {
        String safe = sanitizeProjectName(projectName);
        String gid = "com.ccs." + safe.toLowerCase().replace('-', '_');
        return create(context, projectName, gid, safe.toLowerCase());
    }

    /**
     * @param projectName ім'я каталогу проєкту під WireDroid
     * @param groupId     повний Java package / Maven groupId, напр. {@code com.mycompany.app}
     * @param artifactId  Maven artifactId (літери, цифри, дефіс)
     */
    public static File create(Context context, String projectName, String groupId, String artifactId)
            throws IOException {
        return create(context, projectName, groupId, artifactId,
                ProjectJdk.defaultForNewProject(context));
    }

    /**
     * @param javaTarget release the pom declares, e.g. {@code "1.8"} or {@code "21"};
     *                   null or unrecognised falls back to the global default
     */
    public static File create(Context context, String projectName, String groupId, String artifactId,
                              String javaTarget) throws IOException {
        if (projectName == null || projectName.trim().isEmpty()) {
            throw new IllegalArgumentException("empty name");
        }
        String safeDir = sanitizeProjectName(projectName);
        File root = MavenPaths.projectDir(context, safeDir);
        if (root.exists()) {
            throw new IOException("Project already exists: " + safeDir);
        }

        String gid = normalizeGroupId(groupId, safeDir);
        String aid = artifactId == null || artifactId.trim().isEmpty()
                ? safeDir.toLowerCase()
                : sanitizeArtifactId(artifactId);

        String pkgPath = gid.replace('.', File.separatorChar);
        File mainJavaPkg = new File(root, "src/main/java/" + pkgPath);
        File testJavaPkg = new File(root, "src/test/java/" + pkgPath);
        mainJavaPkg.mkdirs();
        testJavaPkg.mkdirs();

        new File(root, "src/main/resources").mkdirs();
        new File(root, "src/main/resources/META-INF").mkdirs();
        new File(root, "src/test/resources").mkdirs();
        new File(root, "target/classes").mkdirs();
        new File(root, "target/test-classes").mkdirs();

        String mainClass = gid + ".App";

        writeUtf8(new File(root, "src/main/resources/application.properties"),
                "# WireDroid — src/main/resources\n"
                        + "app.name=" + aid + "\n");

        writeUtf8(new File(root, "src/test/resources/.gitkeep"), "");

        String pom = buildPomXml(gid, aid, mainClass, javaTarget);

        writeUtf8(new File(root, "pom.xml"), pom);

        String appJava = "package " + gid + ";\n\n"
                + "/**\n"
                + " * Головний клас програми (src/main/java).\n"
                + " */\n"
                + "public class App {\n"
                + "    public static void main(String[] args) {\n"
                + "        System.out.println(\"Hello from \" + App.class.getPackage().getName());\n"
                + "    }\n"
                + "}\n";

        writeUtf8(new File(mainJavaPkg, "App.java"), appJava);

        String appTest = "package " + gid + ";\n\n"
                + "import org.junit.Test;\n"
                + "import static org.junit.Assert.*;\n\n"
                + "/**\n"
                + " * Тести (src/test/java) — збірка через Maven Test у меню.\n"
                + " */\n"
                + "public class AppTest {\n\n"
                + "    @Test\n"
                + "    public void smoke() {\n"
                + "        assertTrue(true);\n"
                + "    }\n"
                + "}\n";

        writeUtf8(new File(testJavaPkg, "AppTest.java"), appTest);

        return root;
    }

    private static String buildPomXml(String groupId, String artifactId, String mainClass,
                                      String javaTarget) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"\n"
                + "         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n"
                + "         xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 "
                + "http://maven.apache.org/xsd/maven-4.0.0.xsd\">\n"
                + "    <modelVersion>4.0.0</modelVersion>\n"
                + "    <groupId>" + escapeXml(groupId) + "</groupId>\n"
                + "    <artifactId>" + escapeXml(artifactId) + "</artifactId>\n"
                + "    <version>1.0-SNAPSHOT</version>\n"
                + "    <packaging>jar</packaging>\n"
                + "    <name>" + escapeXml(artifactId) + "</name>\n"
                + "    <properties>\n"
                + ProjectJdk.pomCompilerProperties(javaTarget, "        ")
                + "        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>\n"
                + "        <mainClass>" + escapeXml(mainClass) + "</mainClass>\n"
                + "        <junit.version>4.13.2</junit.version>\n"
                + "    </properties>\n"
                + "    <dependencies>\n"
                + "        <dependency>\n"
                + "            <groupId>junit</groupId>\n"
                + "            <artifactId>junit</artifactId>\n"
                + "            <version>${junit.version}</version>\n"
                + "            <scope>test</scope>\n"
                + "        </dependency>\n"
                + "    </dependencies>\n"
                + "</project>\n";
    }

    private static String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String sanitizeProjectName(String name) {
        return name.trim().replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    private static String sanitizeArtifactId(String a) {
        return a.trim().replaceAll("[^a-zA-Z0-9_.-]", "-").toLowerCase();
    }

    private static String normalizeGroupId(String groupId, String fallbackDir) {
        if (groupId == null || groupId.trim().isEmpty()) {
            return "com.ccs." + fallbackDir.toLowerCase().replace('-', '_');
        }
        String g = groupId.trim().replaceAll("\\s+", "");
        if (!g.matches("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")) {
            return "com.ccs." + fallbackDir.toLowerCase().replace('-', '_');
        }
        return g;
    }

    private static void writeUtf8(File file, String content) throws IOException {
        File p = file.getParentFile();
        if (p != null) p.mkdirs();
        try (OutputStreamWriter w = new OutputStreamWriter(
                new FileOutputStream(file), StandardCharsets.UTF_8)) {
            w.write(content);
        }
    }
}
