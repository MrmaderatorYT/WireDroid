package com.ccs.wiredroid.project;

import com.ccs.wiredroid.maven.MavenPaths;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Creates a Gradle-based Kotlin/JVM project.
 *
 * <pre>
 *   ProjectName/
 *     build.gradle
 *     settings.gradle
 *     src/main/kotlin/&lt;package&gt;/Main.kt
 *     src/main/resources/
 *     src/test/kotlin/&lt;package&gt;/MainTest.kt
 *     src/test/resources/
 * </pre>
 *
 * <p>Sources go in {@code src/main/kotlin}, which is where the Kotlin Gradle
 * plugin and every desktop IDE expect them. WireDroid's own compiler does not
 * care about the directory — it dispatches on the {@code .kt} extension — so the
 * layout is chosen for the tools that will read the project later.</p>
 *
 * <p>The plugin version in the generated script is pinned to
 * {@link #KOTLIN_VERSION}, the compiler bundled in the app, so that building the
 * project on a desktop produces the same language level it does here.</p>
 */
public final class KotlinProjectFactory {

    /**
     * Must track {@code kotlin-compiler-embeddable} in app/build.gradle. A
     * generated script promising a version the on-device compiler cannot deliver
     * would compile on a desktop and fail here, which is the worse direction for
     * the mismatch to point.
     */
    public static final String KOTLIN_VERSION = "2.0.21";

    /**
     * Versions the new-project screen offers, the bundled one first.
     *
     * <p>This is the version written into the generated script, not a choice of
     * compiler — the app has one, and it is {@link #KOTLIN_VERSION}. Picking an
     * older one is for a project that has to build against it elsewhere.</p>
     */
    public static java.util.List<String> selectableVersions() {
        return java.util.Arrays.asList(KOTLIN_VERSION, "2.0.20", "1.9.24", "1.8.22");
    }

    /**
     * The version a generated script should declare.
     *
     * <p>Held per call rather than read from the constant, so a project created
     * against an older Kotlin says so in its own build file.</p>
     */
    private static final ThreadLocal<String> SCRIPT_VERSION = new ThreadLocal<>();

    public static void useVersionForScript(String version) {
        SCRIPT_VERSION.set(version == null || version.trim().isEmpty()
                ? KOTLIN_VERSION : version.trim());
    }

    /** Whether a Kotlin Gradle plugin of this version has the compilerOptions DSL. */
    static boolean usesCompilerOptionsDsl(String version) {
        if (version == null) return true;
        int dot = version.indexOf('.');
        if (dot <= 0) return true;
        try {
            return Integer.parseInt(version.substring(0, dot)) >= 2;
        } catch (NumberFormatException e) {
            return true;
        }
    }

    private static String scriptVersion() {
        String version = SCRIPT_VERSION.get();
        return version == null ? KOTLIN_VERSION : version;
    }

    private KotlinProjectFactory() {}

    public static File create(Context context, String projectName, String groupId)
            throws IOException {
        return create(context, projectName, groupId, null);
    }

    /**
     * @param javaTarget release the generated script declares, e.g. {@code "1.8"}
     *                   or {@code "21"}; {@code null} falls back to the global
     *                   setting
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
                : "com.ccs." + safe.toLowerCase(Locale.ROOT).replace('-', '_');

        String pkgPath = gid.replace('.', File.separatorChar);
        File mainKotlinPkg = new File(MavenPaths.mainKotlinDir(root), pkgPath);
        File testKotlinPkg = new File(MavenPaths.testKotlinDir(root), pkgPath);
        mainKotlinPkg.mkdirs();
        testKotlinPkg.mkdirs();

        new File(root, "src/main/resources").mkdirs();
        new File(root, "src/test/resources").mkdirs();

        // Kotlin compiles to a class named after the file: Main.kt -> MainKt.
        String mainClass = gid + ".MainKt";

        writeUtf8(new File(root, "build.gradle"),
                buildScript(context, gid, mainClass, null, null, null, javaTarget));

        writeUtf8(new File(root, "settings.gradle"),
                "rootProject.name = '" + safe + "'\n");

        writeUtf8(new File(root, "gradle.properties"),
                "# Project-wide Gradle properties.\n"
                + "# Values here are also visible to the WireDroid build script parser.\n"
                + "org.gradle.jvmargs=-Xmx1g\n"
                + "kotlin.code.style=official\n");

        writeUtf8(new File(mainKotlinPkg, "Main.kt"),
                "package " + gid + "\n"
                + "\n"
                + "fun main() {\n"
                + "    println(\"Hello from " + gid + "\")\n"
                + "}\n");

        writeUtf8(new File(testKotlinPkg, "MainTest.kt"),
                "package " + gid + "\n"
                + "\n"
                + "import kotlin.test.Test\n"
                + "import kotlin.test.assertTrue\n"
                + "\n"
                + "class MainTest {\n"
                + "    @Test\n"
                + "    fun smoke() {\n"
                + "        assertTrue(true)\n"
                + "    }\n"
                + "}\n");

        // target/ is listed because the on-device compiler writes class output
        // there even for a Gradle-shaped project.
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
     * The {@code build.gradle} this app writes for a Kotlin/JVM project, whether
     * it was created here or cloned from somewhere else.
     *
     * <p>See {@link GradleProjectFactory#buildScript} for why the imported case
     * shares the template instead of carrying its own.</p>
     *
     * @param mainClass   fully qualified main class, or {@code null} to leave out
     *                    the {@code application} plugin
     * @param mainSrcDirs main source roots relative to the project, or
     *                    {@code null}/empty for the conventional layout
     * @param testSrcDirs test source roots, same convention
     * @param libJars     jar paths relative to the project
     */
    public static String buildScript(Context context, String groupId, String mainClass,
                                     java.util.List<String> mainSrcDirs,
                                     java.util.List<String> testSrcDirs,
                                     java.util.List<String> libJars) {
        return buildScript(context, groupId, mainClass, mainSrcDirs, testSrcDirs, libJars, null);
    }

    /**
     * @param javaLevel release the script declares, or {@code null} to follow
     *                  the global setting. Named apart from the local
     *                  {@code javaTarget} below, which holds the constant name
     *                  rather than the level.
     */
    public static String buildScript(Context context, String groupId, String mainClass,
                                     java.util.List<String> mainSrcDirs,
                                     java.util.List<String> testSrcDirs,
                                     java.util.List<String> libJars,
                                     String javaLevel) {
        String javaTarget = javaVersionConstant(context, javaLevel);
        String jvmTarget = jvmTarget(context, javaLevel);
        boolean runnable = mainClass != null && !mainClass.trim().isEmpty();

        StringBuilder sb = new StringBuilder();
        sb.append("plugins {\n")
          .append("    id 'org.jetbrains.kotlin.jvm' version '").append(scriptVersion()).append("'\n");
        if (runnable) sb.append("    id 'application'\n");
        sb.append("}\n\n");

        sb.append("group = '").append(groupId).append("'\n")
          .append("version = '1.0-SNAPSHOT'\n\n");

        sb.append("// Both compilers must name the same bytecode level. The Kotlin\n")
          .append("// plugin fails the build outright when compileJava and compileKotlin\n")
          .append("// disagree, and compileJava otherwise silently follows whatever JDK\n")
          .append("// happens to run Gradle.\n")
          .append("java {\n")
          .append("    sourceCompatibility = JavaVersion.").append(javaTarget).append("\n")
          .append("    targetCompatibility = JavaVersion.").append(javaTarget).append("\n")
          .append("}\n\n");

        // The JvmTarget enum arrived with the 2.0 plugin. A script that
        // declares an older Kotlin has to use the string form, or it fails to
        // configure on the machine it was written for.
        if (usesCompilerOptionsDsl(scriptVersion())) {
            sb.append("kotlin {\n")
              .append("    compilerOptions {\n")
              .append("        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_")
              .append(jvmTarget).append("\n")
              .append("    }\n")
              .append("}\n\n");
        } else {
            sb.append("tasks.withType(org.jetbrains.kotlin.gradle.tasks.KotlinCompile).configureEach {\n")
              .append("    kotlinOptions {\n")
              .append("        jvmTarget = '").append(jvmTarget.replace('_', '.')).append("'\n")
              .append("    }\n")
              .append("}\n\n");
        }

        // The Kotlin plugin's own source set compiles the Java in it as well, so
        // one block covers a mixed project.
        String sourceSets = GradleScripts.sourceSetsBlock("kotlin", mainSrcDirs, testSrcDirs);
        if (sourceSets != null) sb.append(sourceSets).append("\n");

        if (runnable) {
            sb.append("application {\n")
              .append("    // Main.kt compiles to the class MainKt.\n")
              .append("    mainClass = '").append(mainClass).append("'\n")
              .append("}\n\n");
        }

        sb.append("repositories {\n")
          .append("    mavenCentral()\n")
          .append("}\n\n");

        sb.append("dependencies {\n");
        sb.append(GradleScripts.fileDependencies(libJars));
        sb.append("    implementation 'org.jetbrains.kotlin:kotlin-stdlib:")
          .append(scriptVersion()).append("'\n")
          .append("    testImplementation 'org.jetbrains.kotlin:kotlin-test:")
          .append(KOTLIN_VERSION).append("'\n")
          .append("}\n\n");

        sb.append("test {\n")
          .append("    useJUnitPlatform()\n")
          .append("}\n");
        return sb.toString();
    }

    /**
     * The configured Java target as a {@code JvmTarget} enum constant name.
     *
     * <p>Two traps live here. Kotlin has no JVM target below 1.8, so anything
     * older is raised rather than written into a script Gradle would reject. And
     * the constant for 8 is {@code JVM_1_8}, not {@code JVM_8} — only 9 and above
     * dropped the {@code 1_} prefix, matching how the JDK itself renamed its
     * releases.</p>
     */
    private static String jvmTarget(Context context, String javaLevel) {
        // kotlinJvmTargets returns the candidates best-first; the head is the
        // level the chosen Java target maps onto, already spelled the way the
        // JvmTarget enum spells it. It clamps through effective(), so a level
        // below 1.8 cannot produce a JvmTarget Kotlin does not have.
        java.util.List<String> candidates =
                com.ccs.wiredroid.tools.compilers.JavaVersions
                        .kotlinJvmTargets(javaTargetCode(context, javaLevel));
        String name = candidates.isEmpty() ? "JVM_1_8" : candidates.get(0);
        return name.substring("JVM_".length());
    }

    /** Gradle {@code JavaVersion} constant matching the chosen target. */
    private static String javaVersionConstant(Context context, String javaLevel) {
        return com.ccs.wiredroid.tools.compilers.JavaVersions
                .gradleConstant(javaTargetCode(context, javaLevel));
    }

    /** The chosen level, else the configured one. */
    private static String javaTargetCode(Context context, String javaLevel) {
        if (javaLevel != null && !javaLevel.trim().isEmpty()) return javaLevel;
        try {
            return new com.ccs.wiredroid.util.AppPreferences(context).getJavaTarget();
        } catch (Exception e) {
            return com.ccs.wiredroid.util.AppPreferences.JAVA_8;
        }
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
