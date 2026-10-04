package com.ccs.wiredroid.project;

import com.ccs.wiredroid.maven.PomModel;
import com.ccs.wiredroid.maven.PomParser;
import com.ccs.wiredroid.maven.MavenPaths;
import java.io.File;
import java.io.IOException;

/**
 * Шляхи до package у src/main/java за {@code groupId} з pom.xml або build.gradle.
 */
public final class ProjectLayoutHelper {

    private ProjectLayoutHelper() {}

    /** Каталог пакета для нових класів: src/main/java/&lt;groupId з крапок у слеші&gt;. */
    public static File mainJavaPackageDir(File projectRoot) throws IOException {
        String g = mainPackageName(projectRoot);
        String rel = g.replace('.', File.separatorChar);
        File dir = new File(MavenPaths.mainJavaDir(projectRoot), rel);
        dir.mkdirs();
        return dir;
    }

    /**
     * Package directory for a new source file, chosen by its extension.
     *
     * <p>A {@code .kt} file belongs under {@code src/main/kotlin} and everything
     * else under {@code src/main/java}. Routing on the extension rather than on
     * a per-project "language" flag is what makes a mixed project work: adding
     * one Kotlin file to a Java project puts it in the right root without
     * reclassifying the project, and vice versa.</p>
     */
    public static File mainSourcePackageDir(File projectRoot, String fileName) throws IOException {
        if (!isKotlinSource(fileName)) {
            return mainJavaPackageDir(projectRoot);
        }
        String rel = mainPackageName(projectRoot).replace('.', File.separatorChar);
        File dir = new File(MavenPaths.mainKotlinDir(projectRoot), rel);
        dir.mkdirs();
        return dir;
    }

    public static boolean isKotlinSource(String fileName) {
        return fileName != null && fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".kt");
    }

    /**
     * The root package for new sources: {@code groupId} from pom.xml, else
     * {@code group} from the Gradle script, else the package already present
     * on disk, else {@code com.ccs}.
     */
    public static String mainPackageName(File projectRoot) throws IOException {
        File pom = MavenPaths.pomFile(projectRoot);
        if (pom.isFile()) {
            try {
                PomModel model = PomParser.parse(pom);
                if (model.groupId != null && !model.groupId.isEmpty()) return model.groupId;
            } catch (Exception ignored) {
            }
        }
        if (com.ccs.wiredroid.gradle.GradlePaths.isGradleProject(projectRoot)) {
            try {
                PomModel model = com.ccs.wiredroid.gradle.GradleBuildParser
                        .parseOrDefault(projectRoot).pom;
                if (model.groupId != null && !model.groupId.isEmpty()) return model.groupId;
            } catch (Exception ignored) {
            }
        }
        String onDisk = deepestSinglePackage(MavenPaths.mainJavaDir(projectRoot));
        if (onDisk == null) {
            // A Kotlin-only project has no src/main/java to read the package from.
            onDisk = deepestSinglePackage(MavenPaths.mainKotlinDir(projectRoot));
        }
        return onDisk != null ? onDisk : "com.ccs";
    }

    /**
     * Walks the single-child directory chain under a source root to recover the
     * existing package, e.g. {@code com/example/app} → {@code com.example.app}.
     *
     * <p>Public because an imported repository has no build script to read the
     * package from — the directory chain is the only evidence there is. See
     * {@link ProjectLayoutDetector}.</p>
     *
     * @return the dotted package name, or {@code null} if the layout is ambiguous
     */
    public static String deepestSinglePackage(File sourceRoot) {
        if (sourceRoot == null || !sourceRoot.isDirectory()) return null;
        StringBuilder pkg = new StringBuilder();
        File current = sourceRoot;
        for (int depth = 0; depth < 16; depth++) {
            File[] children = current.listFiles(File::isDirectory);
            if (children == null || children.length != 1) break;
            if (pkg.length() > 0) pkg.append('.');
            pkg.append(children[0].getName());
            current = children[0];
        }
        return pkg.length() > 0 ? pkg.toString() : null;
    }

    public static String packageNameForDir(File projectRoot, File folder) {
        // All four source roots are equivalent here: the package is whatever path
        // remains below whichever root contains the folder.
        File[] roots = {
                MavenPaths.mainJavaDir(projectRoot),
                MavenPaths.testJavaDir(projectRoot),
                MavenPaths.mainKotlinDir(projectRoot),
                MavenPaths.testKotlinDir(projectRoot)
        };
        String folderPath = folder.getAbsolutePath();
        for (File root : roots) {
            String rootPath = root.getAbsolutePath();
            if (!folderPath.startsWith(rootPath)) continue;
            String rel = folderPath.substring(rootPath.length());
            if (rel.startsWith(File.separator)) {
                rel = rel.substring(1);
            }
            if (rel.isEmpty()) return "";
            return rel.replace(File.separatorChar, '.');
        }
        return "";
    }
}
