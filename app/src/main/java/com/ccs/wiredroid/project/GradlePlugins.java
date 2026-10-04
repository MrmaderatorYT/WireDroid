package com.ccs.wiredroid.project;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The plugins a Gradle build applies.
 *
 * <p>Counterpart to {@link com.ccs.wiredroid.maven.MavenPlugins}, which reads a
 * pom. Without it the tool panel showed a permanently empty Plugins branch for
 * every Gradle project — which reads as a broken parser rather than as a project
 * that applies none.</p>
 */
public final class GradlePlugins {

    /** {@code plugins { id 'java' version '1.0' }} — both quote styles. */
    private static final Pattern ID_BLOCK = Pattern.compile(
            "\\bid\\s*(?:\\(\\s*)?['\"]([\\w.\\-]+)['\"]\\s*\\)?"
                    + "(?:\\s*version\\s*(?:\\(\\s*)?['\"]([\\w.\\-]+)['\"])?");

    /** The older {@code apply plugin: 'java'} form, still common. */
    private static final Pattern APPLY = Pattern.compile(
            "\\bapply\\s+plugin\\s*:\\s*['\"]([\\w.\\-]+)['\"]");

    /** Kotlin DSL accessors: {@code kotlin("jvm")}, {@code `java-library`}. */
    private static final Pattern KOTLIN_DSL = Pattern.compile(
            "\\bkotlin\\s*\\(\\s*['\"]([\\w.\\-]+)['\"]\\s*\\)");
    private static final Pattern BACKTICKED = Pattern.compile("`([\\w\\-]+)`");

    private GradlePlugins() {
    }

    public static List<String> of(File projectRoot) {
        List<String> out = new ArrayList<>();
        if (projectRoot == null) return out;
        String script = read(new File(projectRoot, "build.gradle"));
        if (script == null) script = read(new File(projectRoot, "build.gradle.kts"));
        if (script == null) return out;

        String clean = stripCommentsAndStrings(script);
        Set<String> seen = new LinkedHashSet<>();

        String block = pluginsBlock(clean, script);
        if (block != null) {
            Matcher m = ID_BLOCK.matcher(block);
            while (m.find()) {
                String id = m.group(1);
                String version = m.group(2);
                seen.add(version == null || version.isEmpty() ? id : id + ":" + version);
            }
            Matcher k = KOTLIN_DSL.matcher(block);
            while (k.find()) seen.add("kotlin(\"" + k.group(1) + "\")");
            Matcher b = BACKTICKED.matcher(block);
            while (b.find()) seen.add(b.group(1));
        }

        // apply plugin: lines live outside the block, so they are searched whole.
        Matcher a = APPLY.matcher(script);
        while (a.find()) seen.add(a.group(1));

        out.addAll(seen);
        return out;
    }

    /**
     * The body of the top-level {@code plugins { … }}.
     *
     * <p>Located in the comment-stripped copy so a brace inside a comment cannot
     * end the block early, then sliced out of the original so the text returned
     * still carries real plugin ids.</p>
     */
    private static String pluginsBlock(String clean, String original) {
        Matcher m = Pattern.compile("(?m)^\\s*plugins\\s*\\{").matcher(clean);
        if (!m.find()) return null;
        int open = clean.indexOf('{', m.start());
        if (open < 0) return null;
        int depth = 0;
        for (int i = open; i < clean.length(); i++) {
            char c = clean.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) {
                return original.substring(open + 1, Math.min(i, original.length()));
            }
        }
        return null;
    }

    /** Comment and string bodies become spaces; offsets are preserved. */
    private static String stripCommentsAndStrings(String src) {
        char[] out = src.toCharArray();
        int i = 0, n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                while (i < n && src.charAt(i) != '\n') out[i++] = ' ';
            } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                out[i++] = ' ';
                if (i < n) out[i++] = ' ';
                while (i < n && !(src.charAt(i) == '*' && i + 1 < n && src.charAt(i + 1) == '/')) {
                    if (src.charAt(i) != '\n') out[i] = ' ';
                    i++;
                }
                if (i < n) out[i++] = ' ';
                if (i < n) out[i++] = ' ';
            } else if (c == '"' || c == '\'') {
                out[i++] = ' ';
                while (i < n && src.charAt(i) != c) {
                    if (src.charAt(i) == '\\' && i + 1 < n) out[i++] = ' ';
                    if (i < n) out[i++] = ' ';
                }
                if (i < n) out[i++] = ' ';
            } else {
                i++;
            }
        }
        return new String(out);
    }

    private static String read(File f) {
        try {
            if (!f.isFile() || f.length() > 1024 * 1024) return null;
            return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }
}
