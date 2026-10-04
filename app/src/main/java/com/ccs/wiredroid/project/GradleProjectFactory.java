package com.ccs.wiredroid.project;

import com.ccs.wiredroid.maven.MavenPaths;
import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

/**
 * Creates a Gradle-based Java project with standard layout.
 * <pre>
 *   ProjectName/
 *     build.gradle
 *     settings.gradle
 *     src/main/java/&lt;package&gt;/App.java
 *     src/main/resources/
 *     src/test/java/&lt;package&gt;/AppTest.java
 *     src/test/resources/
 * </pre>
 */
public final class GradleProjectFactory {

    private GradleProjectFactory() {}

    public static File create(Context context, String projectName, String groupId) throws IOException {
        return create(context, projectName, groupId, null);
    }

    /**
     * @param javaTarget release the generated script declares, e.g. {@code "1.8"}
     *                   or {@code "21"}; {@code null} falls back to the global
     *                   setting, which is what a caller with no chooser wants
     */
    public static File create(Context context, String projectName, String groupId,
                              String javaTarget) throws IOException {
        if (projectName == null || projectName.trim().isEmpty()) {
            throw new IllegalArgumentException("empty name");
        }
        String safe = projectName.trim().replaceAll("[^a-zA-Z0-9_-]", "_");
        File root = MavenPaths.projectDir(context, safe);
        if (root.exists()) {
            throw new IOException("Project already exists: " + safe);
        }

        String gid = (groupId != null && !groupId.trim().isEmpty())
                ? groupId.trim().replaceAll("\\s+", "")
                : "com.ccs." + safe.toLowerCase().replace('-', '_');

        String pkgPath = gid.replace('.', File.separatorChar);
        File mainJavaPkg = new File(root, "src/main/java/" + pkgPath);
        File testJavaPkg = new File(root, "src/test/java/" + pkgPath);
        mainJavaPkg.mkdirs();
        testJavaPkg.mkdirs();

        new File(root, "src/main/resources").mkdirs();
        new File(root, "src/test/resources").mkdirs();

        // build.gradle — source/target level follows the configured ECJ target so
        // the on-device compiler and the script agree.
        String mainClass = gid + ".App";
        writeUtf8(new File(root, "build.gradle"),
                buildScript(context, gid, mainClass, null, null, null, javaTarget));

        // settings.gradle
        writeUtf8(new File(root, "settings.gradle"),
                "rootProject.name = '" + safe + "'\n");

        // gradle.properties
        writeUtf8(new File(root, "gradle.properties"),
                "# Project-wide Gradle properties.\n"
                + "# Values here are also visible to the WireDroid build script parser.\n"
                + "org.gradle.jvmargs=-Xmx1g\n");

        // App.java
        writeUtf8(new File(mainJavaPkg, "App.java"),
                "package " + gid + ";\n"
                + "\n"
                + "public class App {\n"
                + "    public static void main(String[] args) {\n"
                + "        System.out.println(\"Hello from \" + App.class.getPackage().getName());\n"
                + "    }\n"
                + "}\n");

        // AppTest.java
        writeUtf8(new File(testJavaPkg, "AppTest.java"),
                "package " + gid + ";\n"
                + "\n"
                + "import org.junit.Test;\n"
                + "import static org.junit.Assert.*;\n"
                + "\n"
                + "public class AppTest {\n"
                + "    @Test\n"
                + "    public void smoke() {\n"
                + "        assertTrue(true);\n"
                + "    }\n"
                + "}\n");

        // .gitignore — `target/` is listed too because the on-device compiler
        // writes class output there.
        writeUtf8(new File(root, ".gitignore"),
                "build/\n"
                + "target/\n"
                + ".gradle/\n"
                + ".wiredroid/\n"
                + "*.class\n"
                + "*.jar\n"
                + "!gradle/wrapper/gradle-wrapper.jar\n");

        return root;
    }

    /**
     * The {@code build.gradle} this app writes, for a new project or an imported one.
     *
     * <p>An imported repository does not have the layout {@link #create} lays
     * down: its sources may sit in {@code src/}, its jars in {@code lib/}, and
     * it may have no class with a {@code main} at all. Those three differences
     * are the parameters; everything else — the Java level, the repository, the
     * JUnit dependency — is the same script, written once here so a change to
     * it reaches new and imported projects alike.</p>
     *
     * @param mainClass    fully qualified main class, or {@code null} to leave
     *                     out the {@code application} plugin entirely rather
     *                     than name a class that does not exist
     * @param mainSrcDirs  main source roots relative to the project, or
     *                     {@code null}/empty for the conventional layout
     * @param testSrcDirs  test source roots, same convention
     * @param libJars      jar paths relative to the project, for repositories
     *                     that ship their dependencies
     */
    public static String buildScript(Context context, String groupId, String mainClass,
                                     java.util.List<String> mainSrcDirs,
                                     java.util.List<String> testSrcDirs,
                                     java.util.List<String> libJars) {
        return buildScript(context, groupId, mainClass, mainSrcDirs, testSrcDirs, libJars, null);
    }

    /**
     * @param javaTarget release the {@code java { }} block declares, or
     *                   {@code null} to follow the global setting — the
     *                   importer has no chooser and wants exactly that
     */
    public static String buildScript(Context context, String groupId, String mainClass,
                                     java.util.List<String> mainSrcDirs,
                                     java.util.List<String> testSrcDirs,
                                     java.util.List<String> libJars,
                                     String javaTarget) {
        String javaVersion = javaVersionConstant(context, javaTarget);
        boolean runnable = mainClass != null && !mainClass.trim().isEmpty();

        StringBuilder sb = new StringBuilder();
        sb.append("plugins {\n")
          .append("    id 'java'\n");
        if (runnable) sb.append("    id 'application'\n");
        sb.append("}\n\n");

        sb.append("group = '").append(groupId).append("'\n")
          .append("version = '1.0-SNAPSHOT'\n\n");

        sb.append("java {\n")
          .append("    sourceCompatibility = JavaVersion.").append(javaVersion).append("\n")
          .append("    targetCompatibility = JavaVersion.").append(javaVersion).append("\n")
          .append("}\n\n");

        String sourceSets = GradleScripts.sourceSetsBlock("java", mainSrcDirs, testSrcDirs);
        if (sourceSets != null) sb.append(sourceSets).append("\n");

        if (runnable) {
            sb.append("application {\n")
              .append("    mainClass = '").append(mainClass).append("'\n")
              .append("}\n\n");
        }

        sb.append("repositories {\n")
          .append("    mavenCentral()\n")
          .append("}\n\n");

        sb.append("ext {\n")
          .append("    junitVersion = '4.13.2'\n")
          .append("}\n\n");

        sb.append("dependencies {\n");
        sb.append(GradleScripts.fileDependencies(libJars));
        sb.append("    testImplementation \"junit:junit:${junitVersion}\"\n")
          .append("}\n");
        return sb.toString();
    }

    /**
     * Maps a release onto a {@code JavaVersion} constant: the one chosen for
     * this project, else the configured ECJ target. {@code gradleConstant}
     * clamps through {@code effective()}, so a level the bundled toolchain
     * cannot emit still produces a constant it can.
     */
    private static String javaVersionConstant(Context context, String javaTarget) {
        if (javaTarget != null && !javaTarget.trim().isEmpty()) {
            return com.ccs.wiredroid.tools.compilers.JavaVersions.gradleConstant(javaTarget);
        }
        String target;
        try {
            target = new com.ccs.wiredroid.util.AppPreferences(context).getJavaTarget();
        } catch (Exception e) {
            target = com.ccs.wiredroid.util.AppPreferences.JAVA_8;
        }
        return com.ccs.wiredroid.tools.compilers.JavaVersions.gradleConstant(target);
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
