package com.ccs.wiredroid.gradle;

import java.io.File;

/**
 * Locates the files that define a Gradle project.
 *
 * <p>Sources follow the Maven convention ({@code src/main/java}), which is why
 * the shared compile pipeline handles both build systems. Note that compiled
 * output goes to {@code target/} for either one — the on-device compiler has a
 * single output layout — so {@code build/} stays empty in Gradle projects.</p>
 */
public final class GradlePaths {

    private GradlePaths() {}

    /** {@code build.gradle} (Groovy DSL). */
    public static File buildFileGroovy(File projectRoot) {
        return new File(projectRoot, "build.gradle");
    }

    /** {@code build.gradle.kts} (Kotlin DSL). */
    public static File buildFileKotlin(File projectRoot) {
        return new File(projectRoot, "build.gradle.kts");
    }

    /**
     * The build script actually present in this project, preferring the Groovy
     * DSL when both exist (that is what Gradle itself does).
     *
     * @return the existing build script, or {@code null} if the project has none
     */
    public static File buildFile(File projectRoot) {
        if (projectRoot == null) return null;
        File groovy = buildFileGroovy(projectRoot);
        if (groovy.isFile()) return groovy;
        File kotlin = buildFileKotlin(projectRoot);
        if (kotlin.isFile()) return kotlin;
        return null;
    }

    /** {@code settings.gradle} or {@code settings.gradle.kts}, whichever exists. */
    public static File settingsFile(File projectRoot) {
        if (projectRoot == null) return null;
        File groovy = new File(projectRoot, "settings.gradle");
        if (groovy.isFile()) return groovy;
        File kotlin = new File(projectRoot, "settings.gradle.kts");
        if (kotlin.isFile()) return kotlin;
        return null;
    }

    /** {@code gradle.properties} */
    public static File propertiesFile(File projectRoot) {
        return new File(projectRoot, "gradle.properties");
    }

    /** True when this directory carries a Gradle build script. */
    public static boolean isGradleProject(File projectRoot) {
        return buildFile(projectRoot) != null;
    }

    /** {@code build/} — written by a real Gradle run, not by the on-device compiler. */
    public static File buildDir(File projectRoot) {
        return new File(projectRoot, "build");
    }
}
