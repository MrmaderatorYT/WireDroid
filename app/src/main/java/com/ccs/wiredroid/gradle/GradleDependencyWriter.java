package com.ccs.wiredroid.gradle;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Inserts a dependency into a Gradle build script, preserving the existing DSL
 * and indentation. Mirrors {@link com.ccs.wiredroid.maven.PomWriter} for Maven.
 */
public final class GradleDependencyWriter {

    private GradleDependencyWriter() {}

    /**
     * Adds {@code groupId:artifactId:version} to the {@code dependencies} block,
     * creating that block if the script has none.
     *
     * @param script    current build script text
     * @param kotlinDsl {@code true} for {@code build.gradle.kts} — emits
     *                  {@code implementation("…")} instead of {@code implementation '…'}
     * @return the updated script, or the original when the coordinate is already declared
     */
    public static String addDependency(String script, String groupId, String artifactId,
                                       String version, boolean kotlinDsl) {
        return addDependency(script, groupId, artifactId, version, "implementation", kotlinDsl);
    }

    /**
     * Adds a dependency under an explicit configuration
     * (e.g. {@code testImplementation}, {@code compileOnly}).
     */
    public static String addDependency(String script, String groupId, String artifactId,
                                       String version, String configuration, boolean kotlinDsl) {
        if (script == null) script = "";
        if (isAlreadyDeclared(script, groupId, artifactId)) return script;

        String coordinate = groupId + ":" + artifactId + ":" + version;
        String entry = kotlinDsl
                ? configuration + "(\"" + coordinate + "\")"
                : configuration + " '" + coordinate + "'";

        int open = dependenciesBraceIndex(script);
        if (open < 0) {
            // No dependencies block — append a fresh one.
            String nl = script.isEmpty() || script.endsWith("\n") ? "" : "\n";
            return script + nl + "\ndependencies {\n    " + entry + "\n}\n";
        }

        int close = matchingBrace(script, open);
        String indent = detectIndent(script, open, close);

        // Insert on its own line just before the closing brace, keeping that
        // brace's own line intact.
        int insertAt = close;
        while (insertAt > open && script.charAt(insertAt - 1) != '\n') insertAt--;

        String inserted = indent + entry + "\n";
        return script.substring(0, insertAt) + inserted + script.substring(insertAt);
    }

    /** True when this {@code groupId:artifactId} already appears in the script. */
    /**
     * Declares jars that live inside the project, so a build off-device sees
     * what the on-device classpath already picks up from {@code libs/}.
     *
     * @return true when the file was changed; false when every path was already
     *         declared, which is the normal result of importing the same jar twice
     */
    public static boolean addFileDependencies(java.io.File buildFile,
                                              java.util.List<String> paths) throws java.io.IOException {
        if (buildFile == null || !buildFile.isFile() || paths == null || paths.isEmpty()) {
            return false;
        }
        String script = new String(java.nio.file.Files.readAllBytes(buildFile.toPath()),
                java.nio.charset.StandardCharsets.UTF_8);
        String updated = script;
        boolean changed = false;
        for (String path : paths) {
            String entry = "implementation files('" + path + "')";
            // Compared against the path, not the whole line: the indentation and
            // the configuration name vary between generated and hand-written
            // scripts, and re-adding a jar would break the build twice over.
            if (updated.contains("files('" + path + "')")
                    || updated.contains("files(\"" + path + "\")")) {
                continue;
            }
            updated = insertIntoDependencies(updated, entry);
            changed = true;
        }
        if (!changed) return false;
        java.nio.file.Files.write(buildFile.toPath(),
                updated.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return true;
    }

    /** Puts one line inside the dependencies block, creating it if absent. */
    private static String insertIntoDependencies(String script, String entry) {
        String nl = script.contains("\r\n") ? "\r\n" : "\n";
        int open = dependenciesBraceIndex(script);
        if (open < 0) {
            return script + nl + nl + "dependencies {" + nl + "    " + entry + nl + "}" + nl;
        }
        int insert = open + 1;
        return script.substring(0, insert) + nl + "    " + entry + script.substring(insert);
    }

    public static boolean isAlreadyDeclared(String script, String groupId, String artifactId) {
        if (script == null) return false;
        String stripped = GradleBuildParser.stripComments(script);
        // String-coordinate form, any version.
        if (Pattern.compile(Pattern.quote(groupId) + "\\s*:\\s*"
                + Pattern.quote(artifactId) + "\\s*[:\"']").matcher(stripped).find()) {
            return true;
        }
        // Map form: group: 'g' … name: 'a' on one line.
        Matcher line = Pattern.compile("(?m)^.*\\bgroup\\s*[:=]\\s*[\"']"
                + Pattern.quote(groupId) + "[\"'].*$").matcher(stripped);
        while (line.find()) {
            if (Pattern.compile("\\bname\\s*[:=]\\s*[\"']" + Pattern.quote(artifactId) + "[\"']")
                    .matcher(line.group()).find()) {
                return true;
            }
        }
        return false;
    }

    /** Index of the {@code {} opening the top-level {@code dependencies} block, or -1. */
    private static int dependenciesBraceIndex(String script) {
        String stripped = GradleBuildParser.stripComments(script);
        Matcher m = Pattern.compile("(?m)^\\s*dependencies\\s*\\{").matcher(stripped);
        if (!m.find()) return -1;
        // Offsets survive stripComments because it preserves newlines and only
        // removes comment bodies, so re-find the brace in the original text.
        int approximate = stripped.indexOf('{', m.start());
        if (approximate < 0) return -1;
        Matcher real = Pattern.compile("(?m)^\\s*dependencies\\s*\\{").matcher(script);
        int last = -1;
        while (real.find()) {
            int brace = script.indexOf('{', real.start());
            if (brace < 0) continue;
            last = brace;
            if (brace >= approximate) return brace;
        }
        return last;
    }

    /** Index of the {@code }} closing the block opened at {@code open}. */
    private static int matchingBrace(String script, int open) {
        int depth = 0;
        boolean inString = false;
        char quote = 0;
        for (int i = open; i < script.length(); i++) {
            char c = script.charAt(i);
            if (inString) {
                if (c == '\\') { i++; continue; }
                if (c == quote) inString = false;
                continue;
            }
            if (c == '"' || c == '\'') { inString = true; quote = c; continue; }
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return script.length();
    }

    /** Reuses the indentation of the first existing entry, defaulting to 4 spaces. */
    private static String detectIndent(String script, int open, int close) {
        String body = script.substring(Math.min(open + 1, script.length()),
                Math.min(close, script.length()));
        for (String line : body.split("\n")) {
            if (line.trim().isEmpty()) continue;
            int i = 0;
            while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) i++;
            if (i > 0) return line.substring(0, i);
        }
        return "    ";
    }
}
