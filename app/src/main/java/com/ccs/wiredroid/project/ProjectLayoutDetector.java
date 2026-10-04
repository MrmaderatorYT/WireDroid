package com.ccs.wiredroid.project;

import com.ccs.wiredroid.gradle.GradlePaths;
import com.ccs.wiredroid.maven.MavenPaths;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Works out what a cloned repository is, without asking the user.
 *
 * <p>The order is fixed and matters, because repositories are rarely one clean
 * thing: a Gradle project that was once an Eclipse project still has its
 * {@code .classpath} lying around, and a Maven project may carry a
 * {@code build.gradle} for one subdirectory. Checking build scripts before
 * IDE metadata, and IDE metadata before loose sources, resolves each of those
 * the way the repository's own maintainers would.</p>
 *
 * <ol>
 *   <li>{@code pom.xml} → Maven</li>
 *   <li>{@code build.gradle} / {@code .kts} / {@code settings.gradle} → Gradle</li>
 *   <li>{@code .project} + {@code .classpath} → Eclipse</li>
 *   <li>a source directory or loose sources → plain sources</li>
 * </ol>
 */
public final class ProjectLayoutDetector {

    /** Directories that are never a project's own sources. */
    private static final List<String> IGNORED_DIRS = Arrays.asList(
            ".git", ".gradle", ".idea", ".wiredroid", "build", "target", "out",
            "bin", "node_modules", "gradle", ".github");

    /** Where loose sources live when there is no build system to ask. */
    private static final List<String> CANDIDATE_SOURCE_DIRS = Arrays.asList(
            "src/main/java", "src/main/kotlin", "src", "source", "sources", "java", "app/src");

    private static final Pattern MAVEN_MODULE =
            Pattern.compile("<module>\\s*([^<]+?)\\s*</module>");
    private static final Pattern GRADLE_INCLUDE =
            Pattern.compile("^\\s*include\\b(.*)$", Pattern.MULTILINE);
    private static final Pattern QUOTED =
            Pattern.compile("[\"']([^\"']+)[\"']");
    private static final Pattern JAVA_MAIN =
            Pattern.compile("static\\s+(?:final\\s+)?void\\s+main\\s*\\(");
    private static final Pattern KOTLIN_MAIN =
            Pattern.compile("^\\s*fun\\s+main\\s*\\(", Pattern.MULTILINE);
    private static final Pattern PACKAGE_DECL =
            Pattern.compile("^\\s*package\\s+([A-Za-z_][A-Za-z0-9_.]*)", Pattern.MULTILINE);

    /** Enough of a file to find its package and a {@code main}; sources are not novels. */
    private static final int HEAD_BYTES = 8 * 1024;
    /** Cap on the number of sources scanned for a main class on a phone. */
    private static final int MAX_SCANNED_SOURCES = 400;

    private ProjectLayoutDetector() {}

    public static ImportedLayout detect(File root) {
        if (root == null || !root.isDirectory()) {
            return new ImportedLayout(root, ImportedLayout.Kind.UNKNOWN, false);
        }

        if (MavenPaths.pomFile(root).isFile()) {
            ImportedLayout layout = new ImportedLayout(root, ImportedLayout.Kind.MAVEN,
                    hasKotlinSources(root));
            collectMavenModules(root, layout);
            return layout;
        }

        if (isGradleRoot(root)) {
            ImportedLayout layout = new ImportedLayout(root, ImportedLayout.Kind.GRADLE,
                    isKotlinGradleProject(root));
            collectGradleModules(root, layout);
            return layout;
        }

        if (EclipseClasspath.isEclipseProject(root)) {
            EclipseClasspath cp = EclipseClasspath.parse(EclipseClasspath.classpathFile(root));
            if (!cp.isEmpty()) {
                ImportedLayout layout = new ImportedLayout(root, ImportedLayout.Kind.ECLIPSE,
                        hasKotlinSources(root));
                layout.mainSourceRoots.addAll(cp.sourceRoots);
                layout.testSourceRoots.addAll(cp.testSourceRoots);
                layout.libJars.addAll(cp.libs);
                layout.warnings.addAll(cp.unsupported);
                if (layout.mainSourceRoots.isEmpty() && !layout.testSourceRoots.isEmpty()) {
                    // A .classpath with only test roots still needs a main root to
                    // compile against; treat the first as the main one.
                    layout.mainSourceRoots.add(layout.testSourceRoots.remove(0));
                }
                inferSources(layout);
                return layout;
            }
            // A .classpath with nothing usable in it — fall through to the
            // directory scan, which is what the user would do by eye.
        }

        String sourceRoot = findSourceRoot(root);
        if (sourceRoot != null) {
            ImportedLayout layout = new ImportedLayout(root, ImportedLayout.Kind.PLAIN_SOURCES,
                    hasKotlinSources(root));
            layout.mainSourceRoots.add(sourceRoot);
            File testDir = new File(root, "test");
            if (!sourceRoot.equals("test") && containsSources(testDir, 6)) {
                layout.testSourceRoots.add("test");
            }
            inferSources(layout);
            return layout;
        }

        return new ImportedLayout(root, ImportedLayout.Kind.UNKNOWN, false);
    }

    // ── Maven ───────────────────────────────────────────────────────────────

    private static void collectMavenModules(File root, ImportedLayout layout) {
        List<ImportedLayout.Module> found = new ArrayList<>();
        String pom = readHead(MavenPaths.pomFile(root), 64 * 1024);
        if (pom != null) {
            Matcher m = MAVEN_MODULE.matcher(pom);
            while (m.find()) {
                String name = m.group(1).trim();
                File dir = new File(root, name);
                if (dir.isDirectory() && MavenPaths.pomFile(dir).isFile()) {
                    found.add(new ImportedLayout.Module(name, dir, ImportedLayout.Kind.MAVEN));
                }
            }
        }
        if (found.isEmpty()) {
            // A repository that simply holds several projects side by side has
            // no aggregator pom to read, but the same question to answer.
            File[] children = root.listFiles(File::isDirectory);
            if (children != null) {
                Arrays.sort(children, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
                for (File child : children) {
                    if (isIgnored(child.getName())) continue;
                    if (MavenPaths.pomFile(child).isFile()) {
                        found.add(new ImportedLayout.Module(child.getName(), child,
                                ImportedLayout.Kind.MAVEN));
                    }
                }
            }
        }
        if (found.size() > 1) layout.modules.addAll(found);
    }

    // ── Gradle ──────────────────────────────────────────────────────────────

    /** {@code settings.gradle} alone is a Gradle project, even with no build script. */
    private static boolean isGradleRoot(File root) {
        return GradlePaths.isGradleProject(root) || GradlePaths.settingsFile(root) != null;
    }

    private static void collectGradleModules(File root, ImportedLayout layout) {
        File settings = GradlePaths.settingsFile(root);
        if (settings == null) return;
        String text = readHead(settings, 64 * 1024);
        if (text == null) return;

        List<ImportedLayout.Module> found = new ArrayList<>();
        Matcher includes = GRADLE_INCLUDE.matcher(text);
        while (includes.find()) {
            Matcher names = QUOTED.matcher(includes.group(1));
            while (names.find()) {
                String path = names.group(1).trim();
                if (path.isEmpty()) continue;
                String relative = path.replace(':', '/');
                while (relative.startsWith("/")) relative = relative.substring(1);
                if (relative.isEmpty()) continue;
                File dir = new File(root, relative);
                if (!dir.isDirectory()) continue;
                ImportedLayout.Kind kind = GradlePaths.isGradleProject(dir)
                        ? ImportedLayout.Kind.GRADLE : ImportedLayout.Kind.PLAIN_SOURCES;
                found.add(new ImportedLayout.Module(relative, dir, kind));
            }
        }
        if (found.size() > 1) layout.modules.addAll(found);
    }

    private static boolean isKotlinGradleProject(File root) {
        if (MavenPaths.mainKotlinDir(root).isDirectory()) return true;
        File script = GradlePaths.buildFile(root);
        String text = script == null ? null : readHead(script, 64 * 1024);
        if (text != null) {
            if (text.contains("org.jetbrains.kotlin")
                    || text.contains("kotlin(\"jvm\")")
                    || text.contains("kotlin('jvm')")
                    || text.matches("(?s).*\\bid\\s+['\"]kotlin['\"].*")
                    || text.matches("(?s).*apply\\s+plugin:\\s*['\"]kotlin.*")) {
                return true;
            }
        }
        return hasKotlinSources(root);
    }

    // ── Sources ─────────────────────────────────────────────────────────────

    /** True when Kotlin sources outnumber nothing at all — any {@code .kt} counts. */
    private static boolean hasKotlinSources(File root) {
        if (MavenPaths.mainKotlinDir(root).isDirectory()) return true;
        int[] counts = countSources(root, 5, new int[]{0, 0}, 0);
        return counts[1] > 0 && counts[1] >= counts[0];
    }

    /** @return {@code {javaFiles, kotlinFiles}} */
    private static int[] countSources(File dir, int depth, int[] counts, int visited) {
        if (depth < 0 || counts[0] + counts[1] > 500) return counts;
        File[] children = dir.listFiles();
        if (children == null) return counts;
        for (File child : children) {
            if (child.isDirectory()) {
                if (isIgnored(child.getName())) continue;
                countSources(child, depth - 1, counts, visited + 1);
            } else {
                String name = child.getName().toLowerCase(Locale.ROOT);
                if (name.endsWith(".java")) counts[0]++;
                else if (name.endsWith(".kt")) counts[1]++;
            }
        }
        return counts;
    }

    /**
     * The directory holding the sources, relative to the project.
     *
     * @return a relative path, {@code "."} for sources sitting at the root, or
     *         {@code null} when there are no sources at all
     */
    private static String findSourceRoot(File root) {
        for (String candidate : CANDIDATE_SOURCE_DIRS) {
            File dir = new File(root, candidate.replace('/', File.separatorChar));
            if (containsSources(dir, 8)) return candidate;
        }
        if (containsSources(root, 0)) return ".";
        // Last resort: a single non-ignored directory that holds sources, which
        // is how repositories that wrap their code in one folder look.
        File[] children = root.listFiles(File::isDirectory);
        if (children != null) {
            Arrays.sort(children, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            for (File child : children) {
                if (isIgnored(child.getName())) continue;
                if (containsSources(child, 8)) return child.getName();
            }
        }
        return null;
    }

    private static boolean containsSources(File dir, int depth) {
        if (dir == null || !dir.isDirectory()) return false;
        File[] children = dir.listFiles();
        if (children == null) return false;
        for (File child : children) {
            if (child.isFile()) {
                String name = child.getName().toLowerCase(Locale.ROOT);
                if (name.endsWith(".java") || name.endsWith(".kt")) return true;
            }
        }
        if (depth <= 0) return false;
        for (File child : children) {
            if (child.isDirectory() && !isIgnored(child.getName())
                    && containsSources(child, depth - 1)) {
                return true;
            }
        }
        return false;
    }

    /** Fills in the package and main class for a project with no build script. */
    private static void inferSources(ImportedLayout layout) {
        if (layout.mainSourceRoots.isEmpty()) return;
        File sourceRoot = resolve(layout.root, layout.mainSourceRoots.get(0));

        // The package is the single-child directory chain under the source root —
        // com/example/app and nothing else means the package is com.example.app.
        String pkg = ProjectLayoutHelper.deepestSinglePackage(sourceRoot);

        List<File> sources = new ArrayList<>();
        collectSources(sourceRoot, 8, sources);
        for (File source : sources) {
            String head = readHead(source, HEAD_BYTES);
            if (head == null) continue;
            boolean kotlin = source.getName().toLowerCase(Locale.ROOT).endsWith(".kt");
            boolean hasMain = kotlin
                    ? KOTLIN_MAIN.matcher(head).find()
                    : JAVA_MAIN.matcher(head).find();
            Matcher pkgMatch = PACKAGE_DECL.matcher(head);
            String declared = pkgMatch.find() ? pkgMatch.group(1) : null;
            if (pkg == null && declared != null) pkg = declared;
            if (hasMain && layout.mainClass == null) {
                layout.mainClass = mainClassName(source, declared, kotlin);
            }
            if (pkg != null && layout.mainClass != null) break;
        }
        layout.packageName = pkg;
    }

    /** Kotlin's {@code Main.kt} compiles to the class {@code MainKt}. */
    private static String mainClassName(File source, String declaredPackage, boolean kotlin) {
        String simple = source.getName();
        int dot = simple.lastIndexOf('.');
        if (dot > 0) simple = simple.substring(0, dot);
        if (kotlin) simple = simple + "Kt";
        return declaredPackage == null || declaredPackage.isEmpty()
                ? simple : declaredPackage + "." + simple;
    }

    private static void collectSources(File dir, int depth, List<File> out) {
        if (dir == null || !dir.isDirectory() || depth < 0 || out.size() >= MAX_SCANNED_SOURCES) {
            return;
        }
        File[] children = dir.listFiles();
        if (children == null) return;
        Arrays.sort(children, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (File child : children) {
            if (out.size() >= MAX_SCANNED_SOURCES) return;
            if (child.isFile()) {
                String name = child.getName().toLowerCase(Locale.ROOT);
                if (name.endsWith(".java") || name.endsWith(".kt")) out.add(child);
            }
        }
        for (File child : children) {
            if (child.isDirectory() && !isIgnored(child.getName())) {
                collectSources(child, depth - 1, out);
            }
        }
    }

    static File resolve(File root, String relative) {
        if (relative == null || relative.isEmpty() || ".".equals(relative)) return root;
        return new File(root, relative.replace('/', File.separatorChar));
    }

    private static boolean isIgnored(String name) {
        return IGNORED_DIRS.contains(name.toLowerCase(Locale.ROOT));
    }

    /** Reads at most {@code limit} bytes as UTF-8, or {@code null} if unreadable. */
    private static String readHead(File file, int limit) {
        if (file == null || !file.isFile()) return null;
        try (Reader reader = new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8)) {
            char[] buffer = new char[limit];
            int read = 0;
            while (read < limit) {
                int n = reader.read(buffer, read, limit - read);
                if (n < 0) break;
                read += n;
            }
            return new String(buffer, 0, Math.max(read, 0));
        } catch (IOException e) {
            return null;
        }
    }
}
