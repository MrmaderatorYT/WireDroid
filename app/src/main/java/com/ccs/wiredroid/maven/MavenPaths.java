package com.ccs.wiredroid.maven;

import android.content.Context;
import android.os.Environment;

import java.io.File;

/**
 * Path utilities for Maven project layout:
 * {@code Documents/WireDroid/<project>/src/main/java}, {@code target/classes}, etc.
 */
public final class MavenPaths {

    private MavenPaths() {}

    public static File getWireDroidBase(Context context) {
        // Try external storage first, fallback to internal
        try {
            File doc = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
            if (doc.exists() || doc.mkdirs()) {
                // Test write access
                File test = new File(doc, ".wiredroid_test");
                if (test.createNewFile()) {
                    test.delete();
                    return new File(doc, "WireDroid");
                }
            }
        } catch (Exception ignored) {}
        // Fallback to internal storage
        File internal = new File(context.getFilesDir(), "WireDroid");
        internal.mkdirs();
        return internal;
    }

    public static File projectDir(Context context, String projectName) {
        return new File(getWireDroidBase(context), projectName);
    }

    /** Local artifact cache: {@code .wiredroid/repository/...} */
    public static File localRepoDir(File projectRoot) {
        return new File(projectRoot, ".wiredroid/repository");
    }

    /** Maven-style local install repo: {@code .wiredroid/local-repo/...} */
    public static File installRepoDir(File projectRoot) {
        return new File(projectRoot, ".wiredroid/local-repo");
    }

    /** {@code target/} root */
    public static File targetDir(File projectRoot) {
        return new File(projectRoot, "target");
    }

    /** {@code target/classes} */
    public static File targetClassesDir(File projectRoot) {
        return new File(projectRoot, "target/classes");
    }

    /** {@code target/test-classes} */
    public static File targetTestClassesDir(File projectRoot) {
        return new File(projectRoot, "target/test-classes");
    }

    /** {@code pom.xml} */
    public static File pomFile(File projectRoot) {
        return new File(projectRoot, "pom.xml");
    }

    /** {@code src/main/java} */
    public static File mainJavaDir(File projectRoot) {
        return new File(projectRoot, "src/main/java");
    }

    /** {@code src/test/java} */
    public static File testJavaDir(File projectRoot) {
        return new File(projectRoot, "src/test/java");
    }

    /**
     * {@code src/main/kotlin} — the source root the Kotlin Gradle plugin uses.
     *
     * <p>Kotlin sources live beside Java rather than inside {@code src/main/java},
     * so a project opened later in a desktop IDE has the layout that IDE expects.
     * Nothing forces a project to have both; {@code exists()} is the test for
     * whether a project actually uses this root.</p>
     */
    public static File mainKotlinDir(File projectRoot) {
        return new File(projectRoot, "src/main/kotlin");
    }

    /** {@code src/test/kotlin} */
    public static File testKotlinDir(File projectRoot) {
        return new File(projectRoot, "src/test/kotlin");
    }

    /** {@code src/main/resources} */
    public static File mainResourcesDir(File projectRoot) {
        return new File(projectRoot, "src/main/resources");
    }

    /** {@code src/test/resources} */
    public static File testResourcesDir(File projectRoot) {
        return new File(projectRoot, "src/test/resources");
    }
}
