package com.ccs.wiredroid.gradle;

import com.ccs.wiredroid.maven.PomModel;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a Gradle build script into the same {@link PomModel} the Maven pipeline
 * consumes, so Gradle projects compile, run, package and test through the
 * existing toolchain without a Gradle daemon on the device.
 *
 * <p>Both DSLs are understood — Groovy ({@code build.gradle}) and Kotlin
 * ({@code build.gradle.kts}). Supported constructs:</p>
 * <ul>
 *   <li>{@code group} / {@code version} assignments</li>
 *   <li>{@code application { mainClass = ... }}, {@code mainClassName}</li>
 *   <li>{@code dependencies { }} in string-coordinate and map notation</li>
 *   <li>{@code repositories { }} including custom {@code maven { url ... }}</li>
 *   <li>variables from {@code ext}, {@code def}/{@code val}/{@code var} and
 *       {@code gradle.properties}, interpolated as {@code $x} / {@code ${x}}</li>
 *   <li>{@code exclude group:/module:} inside a dependency closure</li>
 * </ul>
 *
 * <p>Constructs that need a real Gradle runtime — {@code project(':x')},
 * {@code fileTree(...)}, custom tasks — are skipped and reported through
 * {@link Result#warnings} rather than failing the build.</p>
 */
public final class GradleBuildParser {

    /** Configurations that end up on the compile/runtime classpath. */
    private static final String[] COMPILE_CONFIGS = {
            "implementation", "api", "compile", "runtimeOnly", "runtime", "compileClasspath"
    };
    /** Configurations visible at compile time only. */
    private static final String[] PROVIDED_CONFIGS = {
            "compileOnly", "compileOnlyApi", "annotationProcessor", "provided"
    };
    /** Test-only configurations. */
    private static final String[] TEST_CONFIGS = {
            "testImplementation", "testApi", "testCompile", "testCompileOnly",
            "testRuntimeOnly", "testAnnotationProcessor"
    };

    private static final Pattern ASSIGN_GROUP = Pattern.compile(
            "(?m)^\\s*(?:project\\.)?group\\s*(?:=|\\s)\\s*[\"']([^\"']+)[\"']");
    private static final Pattern ASSIGN_VERSION = Pattern.compile(
            "(?m)^\\s*(?:project\\.)?version\\s*(?:=|\\s)\\s*[\"']([^\"']+)[\"']");
    /** {@code mainClass = "x"}, {@code mainClass.set("x")}, {@code mainClassName "x"}. */
    private static final Pattern MAIN_CLASS = Pattern.compile(
            "mainClass(?:Name)?\\s*(?:\\.set\\s*\\(\\s*|=\\s*|\\s+)[\"']([^\"']+)[\"']");
    /** {@code rootProject.name = 'x'} in settings.gradle. */
    private static final Pattern ROOT_PROJECT_NAME = Pattern.compile(
            "rootProject\\.name\\s*=\\s*[\"']([^\"']+)[\"']");
    /** {@code def x = 'v'}, {@code val x = "v"}, {@code var x = 'v'}, {@code ext.x = 'v'}. */
    private static final Pattern VAR_DEF = Pattern.compile(
            "(?m)^\\s*(?:def|val|var|ext\\.)\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*[\"']([^\"']*)[\"']");
    /** Bare {@code x = 'v'} — used inside {@code ext { }}. */
    private static final Pattern BARE_ASSIGN = Pattern.compile(
            "(?m)^\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*[\"']([^\"']*)[\"']");
    private static final Pattern MAVEN_URL = Pattern.compile(
            "url\\s*(?:=|\\s)?\\s*(?:uri\\s*\\(\\s*)?[\"']([^\"']+)[\"']");
    private static final Pattern EXCLUDE_RULE = Pattern.compile(
            "exclude\\s*\\(?\\s*(?:group|module)\\s*[:=]");

    /** Parse outcome: the model plus anything that could not be represented. */
    public static final class Result {
        public final PomModel pom;
        public final List<String> warnings = new ArrayList<>();
        /** The build script that was read, or {@code null} for a synthesised model. */
        public final File buildFile;
        /** True when the Kotlin DSL was used. */
        public final boolean kotlinDsl;

        Result(PomModel pom, File buildFile, boolean kotlinDsl) {
            this.pom = pom;
            this.buildFile = buildFile;
            this.kotlinDsl = kotlinDsl;
        }
    }

    private GradleBuildParser() {}

    /**
     * Parses the Gradle build script of {@code projectRoot}.
     *
     * @throws IOException if the project has no build script or it cannot be read
     */
    public static Result parse(File projectRoot) throws IOException {
        File buildFile = GradlePaths.buildFile(projectRoot);
        if (buildFile == null) {
            throw new IOException("No build.gradle or build.gradle.kts in "
                    + (projectRoot != null ? projectRoot.getName() : "null"));
        }
        boolean kts = buildFile.getName().endsWith(".kts");
        String raw = readText(buildFile);
        String text = stripComments(raw);

        PomModel pom = new PomModel();
        Result result = new Result(pom, buildFile, kts);

        // ── Variables: gradle.properties first, then ext { }, then def/val/var.
        Map<String, String> vars = new LinkedHashMap<>();
        vars.putAll(readGradleProperties(projectRoot));
        String extBlock = blockBody(text, "ext");
        if (extBlock != null) {
            collect(BARE_ASSIGN, extBlock, vars);
        }
        collect(VAR_DEF, text, vars);
        pom.properties.putAll(vars);

        // ── Coordinates.
        pom.artifactId = readRootProjectName(projectRoot, projectRoot.getName());
        Matcher g = ASSIGN_GROUP.matcher(text);
        if (g.find()) pom.groupId = interpolate(g.group(1), vars);
        Matcher v = ASSIGN_VERSION.matcher(text);
        if (v.find()) pom.version = interpolate(v.group(1), vars);
        pom.packaging = "jar";

        // ── Main class: prefer the application block, fall back to a global match.
        String appBlock = blockBody(text, "application");
        Matcher mc = MAIN_CLASS.matcher(appBlock != null ? appBlock : text);
        if (mc.find()) pom.mainClass = interpolate(mc.group(1), vars);

        // ── Repositories.
        parseRepositories(blockBody(text, "repositories"), pom, vars);

        // ── Dependencies.
        String depBlock = blockBody(text, "dependencies");
        if (depBlock != null) {
            parseDependencies(depBlock, pom, vars, result.warnings);
        }

        return result;
    }

    /**
     * Parses the build script if there is one, otherwise returns a minimal model
     * derived from the directory name. Never throws — intended for callers that
     * only want a best-effort classpath.
     */
    public static Result parseOrDefault(File projectRoot) {
        try {
            return parse(projectRoot);
        } catch (Exception e) {
            PomModel pom = new PomModel();
            pom.artifactId = projectRoot != null ? projectRoot.getName() : "app";
            Result r = new Result(pom, null, false);
            r.warnings.add("Could not read build script: " + e.getMessage());
            return r;
        }
    }

    // ─── Dependencies ───────────────────────────────────────────────────────

    private static void parseDependencies(String body, PomModel pom,
                                          Map<String, String> vars, List<String> warnings) {
        String[] lines = body.split("\n");
        PomModel.MavenDependency last = null;

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;

            // `exclude group: 'x', module: 'y'` belongs to the dependency above it.
            if (last != null && EXCLUDE_RULE.matcher(trimmed).find()) {
                PomModel.MavenDependency ex = new PomModel.MavenDependency();
                ex.groupId = firstNamedValue(trimmed, "group");
                ex.artifactId = firstNamedValue(trimmed, "module");
                if (ex.artifactId == null) ex.artifactId = firstNamedValue(trimmed, "name");
                if (ex.groupId == null) ex.groupId = "*";
                if (ex.artifactId == null) ex.artifactId = "*";
                last.exclusions.add(ex);
                continue;
            }

            String config = leadingConfiguration(trimmed);
            if (config == null) continue;

            String args = trimmed.substring(config.length()).trim();
            // Drop a trailing closure so `implementation('a:b:c') { exclude … }`
            // parses. Done before the parentheses so a `${…}` inside a GString is
            // not mistaken for the closure's opening brace.
            args = stripTrailingClosure(args);
            // Strip the wrapping parentheses of `implementation(...)`.
            if (args.startsWith("(")) {
                int close = args.lastIndexOf(')');
                args = close > 0 ? args.substring(1, close) : args.substring(1);
                args = args.trim();
            }

            if (args.isEmpty()) continue;

            String unsupported = unsupportedNotation(args);
            if (unsupported != null) {
                warnings.add(unsupported + " — skipped (needs a Gradle daemon): " + trimmed);
                last = null;
                continue;
            }

            PomModel.MavenDependency dep = parseCoordinate(args, vars);
            if (dep == null) {
                warnings.add("Unrecognised dependency notation — skipped: " + trimmed);
                last = null;
                continue;
            }
            dep.scope = mavenScope(config);
            pom.dependencies.add(dep);
            last = dep;
        }
    }

    /**
     * Builds a dependency from either string-coordinate notation
     * ({@code 'g:a:v'}) or map notation ({@code group: 'g', name: 'a', ...}).
     *
     * @return the dependency, or {@code null} if neither form applies
     */
    private static PomModel.MavenDependency parseCoordinate(String args, Map<String, String> vars) {
        // `platform('g:a:v')` / `enforcedPlatform(...)` — unwrap; the coordinate
        // inside is a BOM, which we treat as a plain dependency for versioning.
        Matcher wrapper = Pattern.compile(
                "^(?:platform|enforcedPlatform|testFixtures)\\s*\\(\\s*(.*?)\\s*\\)\\s*$")
                .matcher(args);
        if (wrapper.matches()) {
            args = wrapper.group(1).trim();
        }

        if (looksLikeMapNotation(args)) {
            String group = firstNamedValue(args, "group");
            String name = firstNamedValue(args, "name");
            String version = firstNamedValue(args, "version");
            if (group == null || name == null) return null;
            PomModel.MavenDependency d = new PomModel.MavenDependency();
            d.groupId = interpolate(group, vars);
            d.artifactId = interpolate(name, vars);
            d.version = version != null ? interpolate(version, vars) : null;
            String classifier = firstNamedValue(args, "classifier");
            if (classifier != null) d.classifier = interpolate(classifier, vars);
            String ext = firstNamedValue(args, "ext");
            if (ext != null) d.type = interpolate(ext, vars);
            return d;
        }

        // String coordinate. `'a:b:' + version` is flattened first; otherwise the
        // first quoted literal on the line is the coordinate.
        String coordinate = flattenConcatenation(args, vars);
        if (coordinate == null) {
            Matcher lit = Pattern.compile("[\"']([^\"']+)[\"']").matcher(args);
            if (!lit.find()) return null;
            coordinate = interpolate(lit.group(1), vars);
        }
        coordinate = coordinate.trim();

        // `g:a:v@aar` — the @-suffix is the packaging type.
        String type = null;
        int at = coordinate.indexOf('@');
        if (at > 0) {
            type = coordinate.substring(at + 1).trim();
            coordinate = coordinate.substring(0, at).trim();
        }

        String[] parts = coordinate.split(":");
        if (parts.length < 2) return null;

        PomModel.MavenDependency d = new PomModel.MavenDependency();
        d.groupId = parts[0].trim();
        d.artifactId = parts[1].trim();
        if (parts.length >= 3 && !parts[2].trim().isEmpty()) d.version = parts[2].trim();
        if (parts.length >= 4 && !parts[3].trim().isEmpty()) d.classifier = parts[3].trim();
        if (type != null && !type.isEmpty()) d.type = type;
        if (d.groupId.isEmpty() || d.artifactId.isEmpty()) return null;
        return d;
    }

    /**
     * Removes a trailing closure from a dependency line, e.g.
     * {@code ('a:b:c') { exclude … }} → {@code ('a:b:c')}. Braces inside string
     * literals — notably GString interpolation like {@code "${version}"} — are
     * left alone.
     */
    static String stripTrailingClosure(String args) {
        boolean inString = false;
        char quote = 0;
        for (int i = 0; i < args.length(); i++) {
            char c = args.charAt(i);
            if (inString) {
                if (c == '\\') { i++; continue; }
                if (c == quote) inString = false;
                continue;
            }
            if (c == '"' || c == '\'') { inString = true; quote = c; continue; }
            if (c == '{') return args.substring(0, i).trim();
        }
        return args.trim();
    }

    /**
     * Flattens a coordinate assembled by string concatenation, the common Groovy
     * idiom {@code 'group:name:' + versionVariable}.
     *
     * @return the joined coordinate, or {@code null} when {@code args} is not a
     *         pure concatenation of literals and known variables
     */
    static String flattenConcatenation(String args, Map<String, String> vars) {
        List<String> parts = splitTopLevel(args);
        if (parts.size() < 2) return null;

        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            String p = part.trim();
            if (p.isEmpty()) return null;
            char first = p.charAt(0);
            if ((first == '"' || first == '\'') && p.length() >= 2
                    && p.charAt(p.length() - 1) == first) {
                out.append(interpolate(p.substring(1, p.length() - 1), vars));
                continue;
            }
            if (p.matches("[A-Za-z_][A-Za-z0-9_.]*")) {
                String value = vars.get(p);
                if (value == null) {
                    int dot = p.lastIndexOf('.');
                    if (dot > 0) value = vars.get(p.substring(dot + 1));
                }
                if (value == null) return null;      // unknown variable — give up
                out.append(value);
                continue;
            }
            return null;                             // a call or expression, not a literal
        }
        return out.toString();
    }

    /** Splits on {@code +} that sits outside string literals and brackets. */
    private static List<String> splitTopLevel(String args) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inString = false;
        char quote = 0;
        int depth = 0;
        for (int i = 0; i < args.length(); i++) {
            char c = args.charAt(i);
            if (inString) {
                current.append(c);
                if (c == '\\' && i + 1 < args.length()) { current.append(args.charAt(++i)); continue; }
                if (c == quote) inString = false;
                continue;
            }
            if (c == '"' || c == '\'') { inString = true; quote = c; current.append(c); continue; }
            if (c == '(' || c == '[') { depth++; current.append(c); continue; }
            if (c == ')' || c == ']') { depth--; current.append(c); continue; }
            if (c == '+' && depth == 0) { parts.add(current.toString()); current.setLength(0); continue; }
            current.append(c);
        }
        parts.add(current.toString());
        return parts;
    }

    /** True when the argument list uses {@code key: value} or {@code key = value} pairs. */
    private static boolean looksLikeMapNotation(String args) {
        return Pattern.compile("\\b(?:group|name|version)\\s*[:=]\\s*[\"']").matcher(args).find();
    }

    /** Extracts {@code key: 'value'} or {@code key = "value"} from an argument list. */
    private static String firstNamedValue(String args, String key) {
        Matcher m = Pattern.compile("\\b" + Pattern.quote(key)
                + "\\s*[:=]\\s*[\"']([^\"']*)[\"']").matcher(args);
        return m.find() ? m.group(1) : null;
    }

    /**
     * Names the Gradle notation that cannot be resolved without a daemon,
     * or {@code null} when the argument list is resolvable.
     */
    private static String unsupportedNotation(String args) {
        if (Pattern.compile("^\\s*project\\s*\\(").matcher(args).find()) return "project(...)";
        if (Pattern.compile("^\\s*fileTree\\s*\\(").matcher(args).find()) return "fileTree(...)";
        if (Pattern.compile("^\\s*files\\s*\\(").matcher(args).find()) return "files(...)";
        if (Pattern.compile("^\\s*gradleApi\\s*\\(").matcher(args).find()) return "gradleApi()";
        if (Pattern.compile("^\\s*localGroovy\\s*\\(").matcher(args).find()) return "localGroovy()";
        if (Pattern.compile("^\\s*libs\\.").matcher(args).find()) return "version catalog (libs.*)";
        return null;
    }

    /**
     * The configuration name a dependency line starts with, or {@code null} when
     * the line is not a dependency declaration.
     */
    private static String leadingConfiguration(String line) {
        String best = null;
        for (String[] group : new String[][]{TEST_CONFIGS, PROVIDED_CONFIGS, COMPILE_CONFIGS}) {
            for (String config : group) {
                if (!line.startsWith(config)) continue;
                // Must be followed by a delimiter, so `implementationFoo` is not a hit.
                if (line.length() > config.length()) {
                    char next = line.charAt(config.length());
                    if (next != ' ' && next != '\t' && next != '(' && next != '\'' && next != '"') {
                        continue;
                    }
                }
                // Longest match wins: testImplementation over implementation.
                if (best == null || config.length() > best.length()) best = config;
            }
        }
        return best;
    }

    /** Maps a Gradle configuration onto the closest Maven scope. */
    private static String mavenScope(String config) {
        for (String c : TEST_CONFIGS) {
            if (c.equals(config)) return "test";
        }
        for (String c : PROVIDED_CONFIGS) {
            if (c.equals(config)) return "provided";
        }
        return "compile";
    }

    // ─── Repositories ───────────────────────────────────────────────────────

    private static void parseRepositories(String body, PomModel pom, Map<String, String> vars) {
        boolean sawAny = false;
        if (body != null) {
            if (body.contains("mavenCentral")) {
                sawAny = true;
                addRepo(pom, "central", "https://repo1.maven.org/maven2");
            }
            if (body.contains("google")) {
                sawAny = true;
                addRepo(pom, "google", "https://maven.google.com");
            }
            if (body.contains("gradlePluginPortal")) {
                sawAny = true;
                addRepo(pom, "gradle-plugin-portal", "https://plugins.gradle.org/m2");
            }
            if (body.contains("mavenLocal")) {
                sawAny = true; // resolved from the on-device cache, no URL needed
            }
            Matcher m = MAVEN_URL.matcher(body);
            int i = 0;
            while (m.find()) {
                String url = interpolate(m.group(1), vars).trim();
                if (url.isEmpty()) continue;
                while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
                sawAny = true;
                addRepo(pom, "gradle-" + (i++), url);
            }
        }
        if (!sawAny) {
            // Gradle needs an explicit repositories block, but a project without
            // one still has to resolve JUnit and friends somewhere.
            addRepo(pom, "central", "https://repo1.maven.org/maven2");
        }
    }

    private static void addRepo(PomModel pom, String id, String url) {
        for (PomModel.MavenRepository r : pom.repositories) {
            if (url.equals(r.url)) return;
        }
        PomModel.MavenRepository repo = new PomModel.MavenRepository();
        repo.id = id;
        repo.url = url;
        pom.repositories.add(repo);
    }

    // ─── Text helpers ───────────────────────────────────────────────────────

    /**
     * Returns the body of {@code name { ... }} with balanced braces, or
     * {@code null} when there is no such block. Nested blocks are preserved.
     */
    static String blockBody(String text, String name) {
        Matcher header = Pattern.compile("(?m)^\\s*" + Pattern.quote(name)
                + "\\s*(?:\\{|\\(\\s*\\)\\s*\\{)").matcher(text);
        if (!header.find()) return null;
        int open = text.indexOf('{', header.start());
        if (open < 0) return null;

        int depth = 0;
        boolean inString = false;
        char quote = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (c == '\\') { i++; continue; }
                if (c == quote) inString = false;
                continue;
            }
            if (c == '"' || c == '\'') { inString = true; quote = c; continue; }
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return text.substring(open + 1, i);
            }
        }
        // Unbalanced script — return what we have rather than nothing.
        return text.substring(open + 1);
    }

    /** Removes {@code //} and {@code /* *}{@code /} comments, keeping string literals intact. */
    static String stripComments(String src) {
        StringBuilder out = new StringBuilder(src.length());
        boolean inLine = false, inBlock = false, inString = false;
        char quote = 0;

        for (int i = 0; i < src.length(); i++) {
            char c = src.charAt(i);
            char next = i + 1 < src.length() ? src.charAt(i + 1) : '\0';

            if (inLine) {
                if (c == '\n') { inLine = false; out.append(c); }
                continue;
            }
            if (inBlock) {
                if (c == '*' && next == '/') { inBlock = false; i++; }
                else if (c == '\n') out.append(c); // keep line numbers stable
                continue;
            }
            if (inString) {
                out.append(c);
                if (c == '\\' && next != '\0') { out.append(next); i++; continue; }
                if (c == quote) inString = false;
                continue;
            }
            if (c == '"' || c == '\'') { inString = true; quote = c; out.append(c); continue; }
            if (c == '/' && next == '/') { inLine = true; continue; }
            if (c == '/' && next == '*') { inBlock = true; i++; continue; }
            out.append(c);
        }
        return out.toString();
    }

    /** Substitutes {@code $name} and {@code ${name}} from {@code vars}. */
    static String interpolate(String value, Map<String, String> vars) {
        if (value == null || value.indexOf('$') < 0) return value;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '$') { out.append(c); continue; }

            if (i + 1 < value.length() && value.charAt(i + 1) == '{') {
                int close = value.indexOf('}', i + 2);
                if (close < 0) { out.append(c); continue; }
                String key = value.substring(i + 2, close).trim();
                out.append(lookup(key, vars, "${" + key + "}"));
                i = close;
                continue;
            }
            int j = i + 1;
            while (j < value.length()
                    && (Character.isLetterOrDigit(value.charAt(j)) || value.charAt(j) == '_')) {
                j++;
            }
            if (j == i + 1) { out.append(c); continue; }
            String key = value.substring(i + 1, j);
            out.append(lookup(key, vars, "$" + key));
            i = j - 1;
        }
        return out.toString();
    }

    /** Resolves a variable, also accepting {@code project.x} and {@code rootProject.x} forms. */
    private static String lookup(String key, Map<String, String> vars, String fallback) {
        String direct = vars.get(key);
        if (direct != null) return direct;
        int dot = key.lastIndexOf('.');
        if (dot > 0) {
            String tail = vars.get(key.substring(dot + 1));
            if (tail != null) return tail;
        }
        return fallback;
    }

    private static void collect(Pattern pattern, String text, Map<String, String> into) {
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            String key = m.group(1);
            if (isReservedName(key)) continue;
            if (!into.containsKey(key)) into.put(key, m.group(2));
        }
    }

    /** Names that are project properties, not user variables. */
    private static boolean isReservedName(String key) {
        String k = key.toLowerCase(Locale.ROOT);
        return k.equals("group") || k.equals("version") || k.equals("description")
                || k.equals("archivesbasename") || k.equals("mainclass") || k.equals("mainclassname");
    }

    private static Map<String, String> readGradleProperties(File projectRoot) {
        Map<String, String> out = new LinkedHashMap<>();
        File f = GradlePaths.propertiesFile(projectRoot);
        if (!f.isFile()) return out;
        Properties props = new Properties();
        try (InputStream in = new FileInputStream(f)) {
            props.load(in);
        } catch (Exception ignored) {
            return out;
        }
        for (String name : props.stringPropertyNames()) {
            out.put(name, props.getProperty(name));
        }
        return out;
    }

    private static String readRootProjectName(File projectRoot, String fallback) {
        File settings = GradlePaths.settingsFile(projectRoot);
        if (settings == null) return fallback;
        try {
            Matcher m = ROOT_PROJECT_NAME.matcher(stripComments(readText(settings)));
            if (m.find()) return m.group(1);
        } catch (Exception ignored) {
        }
        return fallback;
    }

    private static String readText(File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }
}
