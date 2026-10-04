package com.ccs.wiredroid.project;

import com.ccs.wiredroid.gradle.GradlePaths;
import com.ccs.wiredroid.maven.MavenPaths;
import com.ccs.wiredroid.maven.PomModel;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A read-only snapshot of everything the Project Structure screen displays.
 *
 * <p>Most of it comes from {@link BuildSystem}, which already normalises Maven
 * and Gradle into one {@link PomModel}. The rest needs a second read of the raw
 * build script, because neither parser keeps what this screen wants:
 * {@code PomParser} discards {@code <modules>} and the compiler-plugin
 * configuration, and {@code GradleBuildParser} never looks at
 * {@code sourceCompatibility}. Reading the text again is far cheaper than
 * widening two parsers the whole compile pipeline depends on.</p>
 *
 * <p>{@link #load(File)} walks the filesystem and must not run on the main
 * thread. Everything it cannot read degrades to a line in {@link #warnings}
 * rather than an exception, so a half-broken project still renders.</p>
 */
public final class ProjectStructure {

    /** A build script bigger than this is generated, not written; do not slurp it. */
    private static final int MAX_SCRIPT_BYTES = 2 * 1024 * 1024;

    /** Building thousands of rows would stall the screen; the remainder is counted instead. */
    private static final int MAX_LIBRARIES = 200;

    /** File counts stop here — the exact figure stops being interesting long before. */
    private static final int FILE_COUNT_CAP = 2000;

    /** Directories that are never modules, however they are laid out. */
    private static final Set<String> NON_MODULE_DIRS = new HashSet<>(
            Arrays.asList("src", "target", "build", "out", "bin", "gradle"));

    /** {@code <module>core</module>} in an aggregator POM. */
    private static final Pattern POM_MODULE =
            Pattern.compile("<module>\\s*([^<\\s][^<]*?)\\s*</module>");
    /** {@code <release>17</release>} inside the compiler plugin configuration. */
    private static final Pattern POM_RELEASE_TAG =
            Pattern.compile("<release>\\s*([0-9.]+)\\s*</release>");
    /** {@code <source>1.8</source>} inside the compiler plugin configuration. */
    private static final Pattern POM_SOURCE_TAG =
            Pattern.compile("<source>\\s*([0-9.]+)\\s*</source>");

    /** {@code include ':core', ':app'} in settings.gradle(.kts). */
    private static final Pattern GRADLE_INCLUDE_LINE =
            Pattern.compile("(?m)^\\s*include\\b(.*)$");
    private static final Pattern QUOTED = Pattern.compile("[\"']([^\"']+)[\"']");
    /**
     * {@code sourceCompatibility = JavaVersion.VERSION_17}, {@code = '1.8'},
     * {@code = 17}, and the parenthesis-free Groovy form. The leading {@code \s*}
     * backtracks so a bare space can serve as the separator.
     */
    private static final Pattern GRADLE_SOURCE_COMPAT = Pattern.compile(
            "sourceCompatibility\\s*(?:=|\\s)\\s*(?:JavaVersion\\.VERSION_)?[\"']?([0-9_.]+)[\"']?");
    /** {@code JavaLanguageVersion.of(17)} in a toolchain block. */
    private static final Pattern GRADLE_TOOLCHAIN =
            Pattern.compile("JavaLanguageVersion\\.of\\s*\\(\\s*([0-9]+)\\s*\\)");
    /** {@code options.release = 17} or {@code release.set(17)}. */
    private static final Pattern GRADLE_RELEASE =
            Pattern.compile("\\brelease\\s*(?:\\.set\\s*\\(\\s*|=\\s*)([0-9]+)");
    /** {@code <javac … source="17" …>} — release wins, then source, then target. */
    private static final Pattern ANT_JAVAC_LEVEL =
            Pattern.compile("\\b(release|source|target)\\s*=\\s*[\"']([0-9.]+)[\"']");

    // ── Value types ───────────────────────────────────────────────────────────

    /** The language level the build script states, and the key it states it under. */
    public static final class DeclaredLevel {
        /** As written, e.g. {@code "17"} or {@code "1.8"}. */
        public final String code;
        /** Where it was found, e.g. {@code "maven.compiler.source"}. */
        public final String key;

        DeclaredLevel(String code, String key) {
            this.code = code;
            this.key = key;
        }
    }

    /** A sub-project — named by the build script, present on disk, or both. */
    public static final class Module {
        public final String name;
        public final File dir;
        /** Path relative to the project root. */
        public final String relativePath;
        /** Named by {@code <module>} or {@code include}. */
        public final boolean declared;
        /** A directory carrying its own build script exists at {@link #dir}. */
        public final boolean present;

        Module(String name, File dir, String relativePath, boolean declared, boolean present) {
            this.name = name;
            this.dir = dir;
            this.relativePath = relativePath;
            this.declared = declared;
            this.present = present;
        }
    }

    /** One dependency as the build script declares it, plus its cached jar if any. */
    public static final class Library {
        public final String groupId;
        public final String artifactId;
        public final String version;
        /** Maven scope; {@code "compile"} when the script omits one. */
        public final String scope;
        /** The jar in the local repository, or {@code null} when nothing was fetched. */
        public final File jar;

        Library(String groupId, String artifactId, String version, String scope, File jar) {
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.version = version;
            this.scope = scope;
            this.jar = jar;
        }

        /** {@code group:artifact:version}, dropping an unresolved version. */
        public String coordinates() {
            String base = groupId + ":" + artifactId;
            return version.isEmpty() ? base : base + ":" + version;
        }
    }

    /** One of the roots the standard layout defines, present or not. */
    public static final class SourceRoot {
        public enum Kind {
            MAIN_JAVA, MAIN_KOTLIN, MAIN_RESOURCES,
            TEST_JAVA, TEST_KOTLIN, TEST_RESOURCES
        }

        public final Kind kind;
        public final File dir;
        /** Path relative to the project root. */
        public final String relativePath;
        public final boolean exists;
        /** Files below the root, hidden entries excluded. */
        public final int fileCount;
        /** True when counting stopped at the cap, so {@link #fileCount} is a floor. */
        public final boolean capped;

        SourceRoot(Kind kind, File dir, String relativePath,
                   boolean exists, int fileCount, boolean capped) {
            this.kind = kind;
            this.dir = dir;
            this.relativePath = relativePath;
            this.exists = exists;
            this.fileCount = fileCount;
            this.capped = capped;
        }
    }

    // ── Snapshot ──────────────────────────────────────────────────────────────

    public final File root;
    public final String name;
    public final BuildSystem.Kind buildSystem;
    /** The driving build script, or {@code null} for loose sources. */
    public final File buildScript;
    public final String groupId;
    public final String version;
    public final String packaging;
    public final String mainClass;
    /** {@code null} when the build script states no language level. */
    public final DeclaredLevel declaredLevel;
    public final List<Module> modules;
    public final List<Library> libraries;
    /** Dependencies beyond the display cap, counted but not listed. */
    public final int librariesOmitted;
    public final List<SourceRoot> sourceRoots;
    /** Anything the build-script parser could not represent. */
    public final List<String> warnings;

    /**
     * Reads {@code root}. Blocking; call from a background thread.
     *
     * @param root the project directory, or {@code null} for an empty snapshot
     */
    public static ProjectStructure load(File root) {
        return new ProjectStructure(root);
    }

    private ProjectStructure(File root) {
        List<String> warn = new ArrayList<>();
        BuildSystem.Kind kind = BuildSystem.detect(root);
        File script = BuildSystem.buildScript(root);

        PomModel pom = null;
        if (root != null && kind != BuildSystem.Kind.NONE) {
            try {
                BuildSystem.Model model = BuildSystem.model(root);
                pom = model.pom;
                warn.addAll(model.warnings);
            } catch (Exception e) {
                warn.add(String.valueOf(e.getMessage()));
            }
        }

        String scriptText = readText(script);
        List<Library> libs = findLibraries(root, pom);

        this.root = root;
        this.buildSystem = kind;
        this.buildScript = script;
        this.name = pickName(root, pom);
        this.groupId = pom == null ? null : trimToNull(pom.resolveProperty(pom.groupId));
        this.version = pom == null ? null : trimToNull(pom.resolveProperty(pom.version));
        this.packaging = pom == null ? null : trimToNull(pom.packaging);
        this.mainClass = pom == null ? null : trimToNull(pom.mainClass);
        this.declaredLevel = declaredLevel(kind, pom, scriptText);
        this.modules = Collections.unmodifiableList(findModules(root, kind, scriptText));
        this.librariesOmitted = Math.max(0, libs.size() - MAX_LIBRARIES);
        this.libraries = Collections.unmodifiableList(libs.size() > MAX_LIBRARIES
                ? new ArrayList<>(libs.subList(0, MAX_LIBRARIES))
                : libs);
        this.sourceRoots = Collections.unmodifiableList(findSourceRoots(root));
        this.warnings = Collections.unmodifiableList(warn);
    }

    // ── Language level ────────────────────────────────────────────────────────

    private static DeclaredLevel declaredLevel(BuildSystem.Kind kind, PomModel pom, String text) {
        switch (kind) {
            case MAVEN:  return mavenLevel(pom, text);
            case GRADLE: return gradleLevel(text);
            case ANT:    return antLevel(pom, text);
            default:     return null;
        }
    }

    /** {@code release} wins over {@code source}, exactly as Maven resolves it. */
    private static DeclaredLevel mavenLevel(PomModel pom, String pomText) {
        if (pom != null) {
            String[] keys = {"maven.compiler.release", "maven.compiler.source",
                             "java.version", "maven.compiler.target"};
            for (String key : keys) {
                String raw = pom.properties.get(key);
                if (raw == null) continue;
                String value = trimToNull(pom.resolveProperty(raw));
                // An unresolved ${...} means the property lives in a parent POM we
                // never fetched; showing the placeholder would be worse than nothing.
                if (value != null && !value.startsWith("${")) {
                    return new DeclaredLevel(value, key);
                }
            }
        }
        Matcher m = POM_RELEASE_TAG.matcher(pomText);
        if (m.find()) return new DeclaredLevel(m.group(1), "<release>");
        m = POM_SOURCE_TAG.matcher(pomText);
        if (m.find()) return new DeclaredLevel(m.group(1), "<source>");
        return null;
    }

    /**
     * The level an Ant script asks {@code <javac>} for.
     *
     * <p>The parser copies {@code source}, {@code target} and {@code release}
     * into the model under the Maven property names, so the model is asked
     * first and the script text is only the fallback.</p>
     */
    private static DeclaredLevel antLevel(PomModel pom, String scriptText) {
        if (pom != null) {
            String[] keys = {"maven.compiler.release", "maven.compiler.source",
                             "maven.compiler.target"};
            String[] shown = {"javac release", "javac source", "javac target"};
            for (int i = 0; i < keys.length; i++) {
                String value = trimToNull(pom.properties.get(keys[i]));
                if (value != null && !value.startsWith("${")) {
                    return new DeclaredLevel(value, shown[i]);
                }
            }
        }
        if (scriptText == null) return null;
        Matcher m = ANT_JAVAC_LEVEL.matcher(scriptText);
        if (m.find()) return new DeclaredLevel(m.group(2), "javac " + m.group(1));
        return null;
    }

    private static DeclaredLevel gradleLevel(String scriptText) {
        Matcher m = GRADLE_TOOLCHAIN.matcher(scriptText);
        if (m.find()) return new DeclaredLevel(m.group(1), "JavaLanguageVersion");
        m = GRADLE_SOURCE_COMPAT.matcher(scriptText);
        // VERSION_1_8 arrives as "1_8"; the rest of the app spells it "1.8".
        if (m.find()) return new DeclaredLevel(m.group(1).replace('_', '.'), "sourceCompatibility");
        m = GRADLE_RELEASE.matcher(scriptText);
        if (m.find()) return new DeclaredLevel(m.group(1), "release");
        return null;
    }

    // ── Modules ───────────────────────────────────────────────────────────────

    /**
     * Declared modules first, in build-script order, then any directory that
     * carries a build script without being declared. Both halves matter: a
     * declared module missing from disk and a stray module missing from the
     * script are each worth seeing.
     */
    private static List<Module> findModules(File root, BuildSystem.Kind kind, String scriptText) {
        Map<String, Module> byName = new LinkedHashMap<>();
        if (root == null) return new ArrayList<>(byName.values());

        for (String raw : declaredModuleNames(root, kind, scriptText)) {
            String moduleName = raw.trim();
            while (moduleName.startsWith(":")) moduleName = moduleName.substring(1);
            if (moduleName.isEmpty() || byName.containsKey(moduleName)) continue;
            File dir = new File(root, moduleName.replace(':', '/'));
            byName.put(moduleName, new Module(moduleName, dir, relativise(root, dir),
                    true, hasBuildScript(dir)));
        }

        File[] children = root.listFiles();
        if (children != null) {
            Arrays.sort(children, Comparator.comparing(File::getName));
            for (File child : children) {
                if (!child.isDirectory() || child.getName().startsWith(".")) continue;
                if (NON_MODULE_DIRS.contains(child.getName())) continue;
                if (!hasBuildScript(child) || byName.containsKey(child.getName())) continue;
                byName.put(child.getName(), new Module(child.getName(), child,
                        relativise(root, child), false, true));
            }
        }
        return new ArrayList<>(byName.values());
    }

    private static List<String> declaredModuleNames(File root, BuildSystem.Kind kind, String text) {
        List<String> out = new ArrayList<>();
        if (kind == BuildSystem.Kind.MAVEN) {
            Matcher m = POM_MODULE.matcher(text);
            while (m.find()) out.add(m.group(1));
        } else if (kind == BuildSystem.Kind.GRADLE) {
            String settings = readText(GradlePaths.settingsFile(root));
            Matcher line = GRADLE_INCLUDE_LINE.matcher(settings);
            while (line.find()) {
                Matcher quoted = QUOTED.matcher(line.group(1));
                while (quoted.find()) out.add(quoted.group(1));
            }
        }
        return out;
    }

    private static boolean hasBuildScript(File dir) {
        return dir != null && dir.isDirectory()
                && (MavenPaths.pomFile(dir).isFile() || GradlePaths.isGradleProject(dir));
    }

    // ── Libraries ─────────────────────────────────────────────────────────────

    private static List<Library> findLibraries(File root, PomModel pom) {
        List<Library> out = new ArrayList<>();
        if (pom == null) return out;
        for (PomModel.MavenDependency d : pom.dependencies) {
            String gid = trimToEmpty(pom.resolveProperty(d.groupId));
            String aid = trimToEmpty(pom.resolveProperty(d.artifactId));
            String ver = trimToEmpty(pom.resolveProperty(d.version));
            String scope = trimToEmpty(d.scope);
            if (scope.isEmpty()) scope = "compile";
            out.add(new Library(gid, aid, ver, scope, cachedJar(root, gid, aid, ver)));
        }
        return out;
    }

    /**
     * The artifact in the project's local repository. The layout mirrors Maven
     * Central — {@code group/path/artifact/version/artifact-version.jar} — which
     * is what {@code MavenDependencyResolver} writes.
     */
    private static File cachedJar(File root, String gid, String aid, String ver) {
        if (root == null || gid.isEmpty() || aid.isEmpty() || ver.isEmpty()) return null;
        if (ver.startsWith("${")) return null;
        File dir = new File(MavenPaths.localRepoDir(root),
                gid.replace('.', '/') + "/" + aid + "/" + ver);
        File jar = new File(dir, aid + "-" + ver + ".jar");
        return jar.isFile() ? jar : null;
    }

    // ── Source roots ──────────────────────────────────────────────────────────

    private static List<SourceRoot> findSourceRoots(File root) {
        List<SourceRoot> out = new ArrayList<>();
        if (root == null) return out;
        addRoot(out, root, SourceRoot.Kind.MAIN_JAVA, MavenPaths.mainJavaDir(root));
        addRoot(out, root, SourceRoot.Kind.MAIN_KOTLIN, MavenPaths.mainKotlinDir(root));
        addRoot(out, root, SourceRoot.Kind.MAIN_RESOURCES, MavenPaths.mainResourcesDir(root));
        addRoot(out, root, SourceRoot.Kind.TEST_JAVA, MavenPaths.testJavaDir(root));
        addRoot(out, root, SourceRoot.Kind.TEST_KOTLIN, MavenPaths.testKotlinDir(root));
        addRoot(out, root, SourceRoot.Kind.TEST_RESOURCES, MavenPaths.testResourcesDir(root));
        return out;
    }

    private static void addRoot(List<SourceRoot> out, File root, SourceRoot.Kind kind, File dir) {
        boolean exists = dir.isDirectory();
        int count = exists ? countFiles(dir, FILE_COUNT_CAP) : 0;
        out.add(new SourceRoot(kind, dir, relativise(root, dir),
                exists, count, count >= FILE_COUNT_CAP));
    }

    /** Iterative so a pathologically deep tree cannot blow the stack. */
    private static int countFiles(File dir, int cap) {
        int total = 0;
        Deque<File> stack = new ArrayDeque<>();
        stack.push(dir);
        while (!stack.isEmpty() && total < cap) {
            File[] children = stack.pop().listFiles();
            if (children == null) continue;
            for (File child : children) {
                if (child.getName().startsWith(".")) continue;
                if (child.isDirectory()) {
                    stack.push(child);
                } else if (++total >= cap) {
                    break;
                }
            }
        }
        return total;
    }

    // ── Small helpers ─────────────────────────────────────────────────────────

    private static String pickName(File root, PomModel pom) {
        if (pom != null) {
            String artifact = trimToNull(pom.artifactId);
            if (artifact != null) return artifact;
        }
        return root == null ? "" : root.getName();
    }

    private static String relativise(File root, File file) {
        if (root == null || file == null) return file == null ? "" : file.getAbsolutePath();
        String base = root.getAbsolutePath();
        String path = file.getAbsolutePath();
        return path.startsWith(base + "/") ? path.substring(base.length() + 1) : path;
    }

    private static String readText(File file) {
        if (file == null || !file.isFile() || file.length() > MAX_SCRIPT_BYTES) return "";
        try {
            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String trimToEmpty(String s) {
        return s == null ? "" : s.trim();
    }
}
