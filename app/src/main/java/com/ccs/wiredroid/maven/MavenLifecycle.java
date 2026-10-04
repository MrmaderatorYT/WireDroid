package com.ccs.wiredroid.maven;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Maven lifecycle phases: process-resources, clean, install.
 */
public final class MavenLifecycle {

    public interface Log {
        void onLine(String line);
    }

    private MavenLifecycle() {}

    // ─── process-resources ─────────────────────────────────────────────────

    /**
     * Copy {@code src/main/resources} → {@code target/classes} (preserving directory structure).
     * Non-binary files are copied as-is; existing files are overwritten.
     * @return number of files copied
     */
    public static int processResources(File projectRoot, Log log) {
        File srcDir = MavenPaths.mainResourcesDir(projectRoot);
        File destDir = MavenPaths.targetClassesDir(projectRoot);

        if (!srcDir.exists() || !srcDir.isDirectory()) {
            if (log != null) log.onLine("No src/main/resources — skip process-resources");
            return 0;
        }

        destDir.mkdirs();
        int count = copyDirectoryRecursive(srcDir, destDir, log);
        if (log != null) log.onLine("process-resources: " + count + " file(s) copied to target/classes");
        return count;
    }

    /**
     * Copy {@code src/test/resources} → {@code target/test-classes}.
     * @return number of files copied
     */
    public static int processTestResources(File projectRoot, Log log) {
        File srcDir = MavenPaths.testResourcesDir(projectRoot);
        File destDir = MavenPaths.targetTestClassesDir(projectRoot);

        if (!srcDir.exists() || !srcDir.isDirectory()) {
            if (log != null) log.onLine("No src/test/resources — skip process-test-resources");
            return 0;
        }

        destDir.mkdirs();
        int count = copyDirectoryRecursive(srcDir, destDir, log);
        if (log != null) log.onLine("process-test-resources: " + count + " file(s) copied to target/test-classes");
        return count;
    }

    private static int copyDirectoryRecursive(File src, File dest, Log log) {
        int count = 0;
        File[] files = src.listFiles();
        if (files == null) return 0;
        for (File f : files) {
            File target = new File(dest, f.getName());
            if (f.isDirectory()) {
                target.mkdirs();
                count += copyDirectoryRecursive(f, target, log);
            } else {
                try {
                    Files.copy(f.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    count++;
                } catch (IOException e) {
                    if (log != null) log.onLine("Warning: failed to copy " + f.getName() + ": " + e.getMessage());
                }
            }
        }
        return count;
    }

    // ─── clean ──────────────────────────────────────────────────────────────

    /**
     * Delete the {@code target/} directory (target/classes, target/test-classes, and any JARs).
     */
    public static void clean(File projectRoot, Log log) {
        File target = MavenPaths.targetDir(projectRoot);
        if (target.exists()) {
            deleteRecursive(target);
            if (log != null) log.onLine("clean: deleted target/");
        } else {
            if (log != null) log.onLine("clean: target/ does not exist — nothing to clean");
        }
    }

    // ─── install ───────────────────────────────────────────────────────────

    /**
     * Install the built JAR into the local Maven repository at {@code .wiredroid/local-repo/}
     * with the standard Maven layout: {@code groupId/artifactId/version/artifactId-version.jar}
     *
     * @return the installed JAR file, or null if the source JAR does not exist
     */
    public static File install(File projectRoot, PomModel pom, Log log) {
        // Find the built JAR
        File targetDir = MavenPaths.targetDir(projectRoot);
        String jarName = pom.artifactId + "-" + pom.version + ".jar";
        File sourceJar = new File(targetDir, jarName);

        if (!sourceJar.exists()) {
            // Also try with .SNAPSHOT or other naming
            File[] jars = targetDir.listFiles((dir, name) -> name.endsWith(".jar"));
            if (jars != null && jars.length > 0) {
                sourceJar = jars[0]; // take the first JAR
            }
        }

        if (!sourceJar.exists()) {
            if (log != null) log.onLine("install: no JAR found in target/ — run package first");
            return null;
        }

        // Install into local repo
        File localRepo = MavenPaths.installRepoDir(projectRoot);
        String repoPath = pom.groupId.replace('.', '/') + "/" + pom.artifactId + "/" + pom.version;
        File destDir = new File(localRepo, repoPath);
        destDir.mkdirs();
        File destJar = new File(destDir, pom.artifactId + "-" + pom.version + ".jar");

        try {
            Files.copy(sourceJar.toPath(), destJar.toPath(), StandardCopyOption.REPLACE_EXISTING);
            if (log != null) log.onLine("install: " + destJar.getAbsolutePath());
            return destJar;
        } catch (IOException e) {
            if (log != null) log.onLine("install failed: " + e.getMessage());
            return null;
        }
    }

    // ─── Utility ────────────────────────────────────────────────────────────

    private static void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] ch = f.listFiles();
            if (ch != null) {
                for (File c : ch) {
                    deleteRecursive(c);
                }
            }
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
