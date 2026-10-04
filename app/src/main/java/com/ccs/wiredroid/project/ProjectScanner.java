package com.ccs.wiredroid.project;

import com.ccs.wiredroid.ui.FileTreeNode;
import com.ccs.wiredroid.maven.MavenPaths;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class ProjectScanner {

    private ProjectScanner() {}

    public static List<File> listJavaSources(File projectRoot) {
        List<File> out = new ArrayList<>();
        File main = MavenPaths.mainJavaDir(projectRoot);
        // An Ant script says where its sources are, and it is usually not
        // src/main/java. Asked before the loose-files fallback, because that
        // fallback would find nothing and the project would compile empty.
        File ant = main.exists() ? null : com.ccs.wiredroid.ant.AntPaths.sourceDir(projectRoot);
        if (main.exists()) {
            collectJavaRecursive(main, out);
        } else if (ant != null) {
            collectJavaRecursive(ant, out);
        } else {
            File[] files = projectRoot.listFiles();
            if (files != null) {
                Arrays.sort(files, Comparator.comparing(File::getName));
                for (File f : files) {
                    if (f.isFile() && f.getName().endsWith(".java")) out.add(f);
                }
            }
        }
        out.sort(Comparator.comparing(File::getAbsolutePath));
        return out;
    }

    /**
     * Every source file the editor recognises, whatever the language.
     *
     * <p>Tells an empty project apart from one whose sources simply are not
     * Java. A Scala project has no {@code src/main/java}, and answering "no
     * files" for it made the app write a Java starter into a project that
     * already had a main of its own.</p>
     */
    public static List<File> listAllSources(File projectRoot) {
        List<File> out = new ArrayList<>();
        collectExtRecursive(projectRoot, out, ".ino", ".cpp", ".c", ".h", ".hpp", ".cc", ".cxx");
        out.sort(Comparator.comparing(File::getAbsolutePath));
        return out;
    }

    public static List<File> listKotlinSources(File projectRoot) {
        List<File> out = new ArrayList<>();
        File kotlinDir = MavenPaths.mainKotlinDir(projectRoot);
        if (kotlinDir.exists()) {
            collectExtRecursive(kotlinDir, out, ".kt");
        }
        File mainJava = MavenPaths.mainJavaDir(projectRoot);
        if (mainJava.exists()) {
            collectExtRecursive(mainJava, out, ".kt");
        }
        if (!kotlinDir.exists() && !mainJava.exists()) {
            File[] files = projectRoot.listFiles();
            if (files != null) {
                Arrays.sort(files, Comparator.comparing(File::getName));
                for (File f : files) {
                    if (f.isFile() && f.getName().endsWith(".kt")) out.add(f);
                }
            }
        }
        out.sort(Comparator.comparing(File::getAbsolutePath));
        return out;
    }

    public static List<File> listTestSources(File projectRoot) {
        List<File> out = new ArrayList<>();
        Set<String> visited = new HashSet<>();

        File[] possibleTestDirs = new File[] {
                MavenPaths.testJavaDir(projectRoot),
                new File(projectRoot, "test/java"),
                new File(projectRoot, "test/src"),
                new File(projectRoot, "test"),
                new File(projectRoot, "tests"),
                new File(projectRoot, "src/test")
        };

        for (File dir : possibleTestDirs) {
            if (dir.exists() && dir.isDirectory()) {
                List<File> dirSources = new ArrayList<>();
                collectJavaRecursive(dir, dirSources);
                for (File f : dirSources) {
                    if (visited.add(f.getAbsolutePath())) {
                        out.add(f);
                    }
                }
            }
        }

        out.sort(Comparator.comparing(File::getAbsolutePath));
        return out;
    }

    public static List<File> listTestKotlinSources(File projectRoot) {
        List<File> out = new ArrayList<>();
        Set<String> visited = new HashSet<>();

        File[] possibleTestDirs = new File[] {
                MavenPaths.testKotlinDir(projectRoot),
                MavenPaths.testJavaDir(projectRoot),
                new File(projectRoot, "test/kotlin"),
                new File(projectRoot, "test/java"),
                new File(projectRoot, "test"),
                new File(projectRoot, "tests"),
                new File(projectRoot, "src/test")
        };

        for (File dir : possibleTestDirs) {
            if (dir.exists() && dir.isDirectory()) {
                List<File> dirSources = new ArrayList<>();
                collectExtRecursive(dir, dirSources, ".kt");
                for (File f : dirSources) {
                    if (visited.add(f.getAbsolutePath())) {
                        out.add(f);
                    }
                }
            }
        }

        out.sort(Comparator.comparing(File::getAbsolutePath));
        return out;
    }

    public static boolean hasTestSources(File projectRoot) {
        if (projectRoot == null || !projectRoot.isDirectory()) return false;
        return !listTestSources(projectRoot).isEmpty() || !listTestKotlinSources(projectRoot).isEmpty();
    }

    /**
     * Checks whether a file or code snippet represents a unit test class.
     */
    public static boolean isTestFile(File file, String sourceCode) {
        if (file != null) {
            String path = file.getAbsolutePath().replace('\\', '/');
            // Only the part of the path above the source directory can name a
            // source set; below it are package folders, and a package called
            // "test" is not a test source root. Matching "/test/" anywhere made
            // every file in a com.example.test package run as a test.
            int cut = Math.max(path.lastIndexOf("/java/"), path.lastIndexOf("/kotlin/"));
            String sourceRoot = cut >= 0 ? path.substring(0, cut + 1) : path;
            if (sourceRoot.contains("/src/test/") || sourceRoot.contains("/src/androidTest/")
                    || sourceRoot.contains("/test/") || sourceRoot.contains("/tests/")) {
                return true;
            }
            String name = file.getName();
            if (name.endsWith("Test.java") || name.endsWith("Tests.java")
                    || name.endsWith("TestCase.java") || name.endsWith("IT.java")
                    || name.endsWith("Test.kt") || name.endsWith("Tests.kt")) {
                return true;
            }
        }
        if (sourceCode != null && !sourceCode.trim().isEmpty()) {
            if (sourceCode.contains("@Test")
                    || sourceCode.contains("@org.junit.Test")
                    || sourceCode.contains("@org.junit.jupiter.api.Test")
                    || sourceCode.contains("@ParameterizedTest")
                    || sourceCode.contains("@RepeatedTest")
                    || sourceCode.contains("extends TestCase")
                    || sourceCode.contains("extends junit.framework.TestCase")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Дерево проєкту у стилі IDEA з усіма розкритими папками.
     *
     * @deprecated краще {@link #listIdeaStyleTree(File, Set)} — воно показує лише
     *             вміст розкритих каталогів.
     */
    @Deprecated
    public static List<FileTreeNode> listIdeaStyleTree(File projectRoot) {
        return listIdeaStyleTree(projectRoot, null);
    }

    /**
     * Дерево проєкту у стилі IDEA: build-скрипт, файли кореня, далі DFS по
     * {@code src/} — але спускається лише в ті каталоги, шляхи яких є в
     * {@code expandedPaths}.
     *
     * @param expandedPaths абсолютні шляхи розкритих каталогів; {@code null}
     *                      означає «розкрити все» (стара поведінка)
     */
    public static List<FileTreeNode> listIdeaStyleTree(File projectRoot, Set<String> expandedPaths) {
        List<FileTreeNode> out = new ArrayList<>();

        // Build scripts first — they are what a project is usually opened for.
        File pom = MavenPaths.pomFile(projectRoot);
        if (pom.exists()) {
            out.add(new FileTreeNode(pom, 0, false));
        }
        for (String buildScript : new String[]{
                "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts"}) {
            File f = new File(projectRoot, buildScript);
            if (f.isFile()) out.add(new FileTreeNode(f, 0, false));
        }

        // Root-level files (non-Java, like .html, .css, .js, .http, .sql, .svg, .md, .kt)
        File[] rootFiles = projectRoot.listFiles();
        if (rootFiles != null) {
            Arrays.sort(rootFiles, Comparator.comparing(File::getName));
            for (File f : rootFiles) {
                if (f.isFile() && !shouldSkipFile(f)) {
                    out.add(new FileTreeNode(f, 0, false));
                }
            }
        }

        // Source roots and any other visible top-level directory.
        List<File> topDirs = new ArrayList<>();
        if (rootFiles != null) {
            for (File f : rootFiles) {
                if (f.isDirectory() && !shouldSkip(f)) topDirs.add(f);
            }
            topDirs.sort(Comparator.comparing(File::getName));
        }
        for (File dir : topDirs) {
            walkTree(out, dir, 0, expandedPaths);
        }
        return out;
    }

    private static boolean shouldSkipFile(File f) {
        String name = f.getName();
        return name.startsWith(".")
                || name.equals("pom.xml")
                || name.equals("build.gradle") || name.equals("build.gradle.kts")
                || name.equals("settings.gradle") || name.equals("settings.gradle.kts");
    }

    /**
     * Adds {@code dir} and, when it is expanded, its contents.
     *
     * @param expandedPaths {@code null} expands everything
     */
    private static void walkTree(List<FileTreeNode> out, File dir, int depth,
                                 Set<String> expandedPaths) {
        File[] ch = dir.listFiles();
        boolean hasChildren = false;
        if (ch != null) {
            for (File f : ch) {
                if (!shouldSkip(f)) { hasChildren = true; break; }
            }
        }
        boolean expanded = expandedPaths == null
                || expandedPaths.contains(dir.getAbsolutePath());

        out.add(new FileTreeNode(dir, depth, true, expanded, hasChildren));
        if (!expanded || ch == null) return;

        Arrays.sort(ch, Comparator.comparing(File::getName));
        // Directories first, then files — the usual project-view ordering.
        for (File f : ch) {
            if (shouldSkip(f) || !f.isDirectory()) continue;
            walkTree(out, f, depth + 1, expandedPaths);
        }
        for (File f : ch) {
            if (shouldSkip(f) || f.isDirectory()) continue;
            out.add(new FileTreeNode(f, depth + 1, false));
        }
    }

    /** Build output and tool metadata are noise in the project view. */
    private static boolean shouldSkip(File f) {
        String n = f.getName();
        if (!f.isDirectory()) return false;
        // Hidden directories are skipped wholesale, matching shouldSkipFile.
        if (n.startsWith(".")) return true;
        return "target".equals(n) || "build".equals(n) || "out".equals(n) || "bin".equals(n);
    }

    /**
     * Каталоги, які варто розкрити при першому відкритті проєкту: {@code src} і
     * далі по ланцюжку одиничних підкаталогів до пакета з файлами.
     *
     * @return абсолютні шляхи каталогів
     */
    public static Set<String> defaultExpandedPaths(File projectRoot) {
        Set<String> out = new LinkedHashSet<>();
        if (projectRoot == null || !projectRoot.isDirectory()) return out;

        File[] top = projectRoot.listFiles();
        if (top == null) return out;
        for (File dir : top) {
            if (!dir.isDirectory() || shouldSkip(dir)) continue;
            if ("src".equals(dir.getName())) {
                out.add(dir.getAbsolutePath());
                expandSingleChildChain(dir, out, 0);
                continue;
            }
            // A module folder (Gradle multi-project) holds its own src/.
            File moduleSrc = new File(dir, "src");
            if (moduleSrc.isDirectory()) {
                out.add(dir.getAbsolutePath());
                out.add(moduleSrc.getAbsolutePath());
                expandSingleChildChain(moduleSrc, out, 0);
            }
        }
        return out;
    }

    /** Follows a chain of single-subdirectory folders, e.g. main/java/com/example. */
    private static void expandSingleChildChain(File dir, Set<String> out, int depth) {
        if (depth > 24) return;
        File[] ch = dir.listFiles();
        if (ch == null) return;
        List<File> dirs = new ArrayList<>();
        for (File f : ch) {
            if (f.isDirectory() && !shouldSkip(f)) dirs.add(f);
        }
        // Fan-out means the user should choose; stop expanding there.
        if (dirs.size() != 1) {
            for (File f : dirs) out.add(f.getAbsolutePath());
            return;
        }
        out.add(dirs.get(0).getAbsolutePath());
        expandSingleChildChain(dirs.get(0), out, depth + 1);
    }

    /** Усі каталоги проєкту — для «розкрити все». */
    public static Set<String> allDirectories(File projectRoot) {
        Set<String> out = new LinkedHashSet<>();
        collectDirs(projectRoot, out, 0);
        return out;
    }

    private static void collectDirs(File dir, Set<String> out, int depth) {
        if (depth > 32 || dir == null) return;
        File[] ch = dir.listFiles();
        if (ch == null) return;
        for (File f : ch) {
            if (f.isDirectory() && !shouldSkip(f)) {
                out.add(f.getAbsolutePath());
                collectDirs(f, out, depth + 1);
            }
        }
    }

    /** Застаріле плоске дерево — для сумісності; краще {@link #listIdeaStyleTree}. */
    public static List<File> listTreeFiles(File projectRoot) {
        List<File> out = new ArrayList<>();
        File pom = MavenPaths.pomFile(projectRoot);
        if (pom.exists()) out.add(pom);

        List<File> all = new ArrayList<>();
        collectExtRecursive(projectRoot, all, ".java", ".xml");
        all.sort(Comparator.comparing(f -> relativePath(projectRoot, f)));
        out.addAll(all);
        return out;
    }

    private static void collectJavaRecursive(File dir, List<File> out) {
        File[] ch = dir.listFiles();
        if (ch == null) return;
        Arrays.sort(ch, Comparator.comparing(File::getName));
        for (File f : ch) {
            if (f.isDirectory()) collectJavaRecursive(f, out);
            else if (f.getName().endsWith(".java")) out.add(f);
        }
    }

    private static void collectExtRecursive(File dir, List<File> out, String... exts) {
        File[] ch = dir.listFiles();
        if (ch == null) return;
        for (File f : ch) {
            if (f.isDirectory()) {
                String n = f.getName();
                if (".wiredroid".equals(n) || "target".equals(n)) continue;
                collectExtRecursive(f, out, exts);
            } else {
                String name = f.getName();
                for (String ext : exts) {
                    if (name.endsWith(ext)) {
                        out.add(f);
                        break;
                    }
                }
            }
        }
    }

    static String relativePath(File root, File file) {
        String rp = file.getAbsolutePath().substring(root.getAbsolutePath().length());
        if (rp.startsWith("/")) rp = rp.substring(1);
        return rp;
    }
}
