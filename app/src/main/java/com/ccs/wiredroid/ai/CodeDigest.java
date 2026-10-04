package com.ccs.wiredroid.ai;

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
 * A short description of what each source file is about.
 *
 * <p>Semantic search cannot send a whole project to a model — a mid-sized one
 * is millions of characters. It does not need to: what a file is <em>for</em>
 * is carried almost entirely by its name, its imports and its method names. So
 * each file is reduced to those, and the model reasons over the shape of the
 * project rather than its text.</p>
 */
public final class CodeDigest {

    /** Roughly the budget one request can carry comfortably. */
    public static final int MAX_CHARS = 60_000;
    public static final int MAX_FILES = 400;

    private static final Pattern TYPE = Pattern.compile(
            "\\b(?:class|interface|enum|record)\\s+([A-Za-z_$][\\w$]*)");
    private static final Pattern METHOD = Pattern.compile(
            "(?:public|protected|private)\\s+(?:static\\s+|final\\s+|abstract\\s+|synchronized\\s+)*"
                    + "[\\w$.<>\\[\\],\\s?]{1,80}?\\s+([A-Za-z_$][\\w$]*)\\s*\\(");
    private static final Pattern IMPORT = Pattern.compile("(?m)^\\s*import\\s+(?:static\\s+)?([\\w.$]+)");

    /** Imports that say nothing about purpose and only spend budget. */
    private static final Pattern DULL_IMPORT = Pattern.compile(
            "^(java\\.lang|java\\.util(?!\\.concurrent)|androidx\\.annotation)\\b.*");

    public static final class Entry {
        public final File file;
        public final String summary;

        Entry(File file, String summary) {
            this.file = file;
            this.summary = summary;
        }
    }

    private CodeDigest() {
    }

    public static List<Entry> of(File root) {
        List<Entry> out = new ArrayList<>();
        if (root == null || !root.isDirectory()) return out;
        List<File> sources = new ArrayList<>();
        collect(root, sources, 0);

        int budget = MAX_CHARS;
        for (File f : sources) {
            if (out.size() >= MAX_FILES || budget <= 0) break;
            String summary = summarise(root, f);
            if (summary == null) continue;
            budget -= summary.length();
            out.add(new Entry(f, summary));
        }
        return out;
    }

    /** One line per file: path, declared types, notable imports, method names. */
    private static String summarise(File root, File f) {
        String content;
        try {
            if (f.length() > 512 * 1024) return null;
            content = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }

        Set<String> types = new LinkedHashSet<>();
        Matcher m = TYPE.matcher(content);
        while (m.find() && types.size() < 6) types.add(m.group(1));

        Set<String> imports = new LinkedHashSet<>();
        m = IMPORT.matcher(content);
        while (m.find() && imports.size() < 14) {
            String imp = m.group(1);
            if (!DULL_IMPORT.matcher(imp).matches()) imports.add(imp);
        }

        Set<String> methods = new LinkedHashSet<>();
        m = METHOD.matcher(content);
        while (m.find() && methods.size() < 20) methods.add(m.group(1));

        if (types.isEmpty() && methods.isEmpty()) return null;

        StringBuilder sb = new StringBuilder();
        sb.append(relative(root, f));
        if (!types.isEmpty()) sb.append(" | types: ").append(String.join(",", types));
        if (!methods.isEmpty()) sb.append(" | methods: ").append(String.join(",", methods));
        if (!imports.isEmpty()) sb.append(" | uses: ").append(String.join(",", imports));
        return sb.append('\n').toString();
    }

    public static String relative(File root, File f) {
        String rootPath = root.getAbsolutePath();
        String path = f.getAbsolutePath();
        return path.startsWith(rootPath) ? path.substring(rootPath.length() + 1) : path;
    }

    private static void collect(File dir, List<File> out, int depth) {
        if (depth > 12 || out.size() >= MAX_FILES * 2) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File f : children) {
            if (f.isDirectory()) {
                String n = f.getName();
                if (n.startsWith(".") || n.equals("build") || n.equals("target")
                        || n.equals("node_modules")) continue;
                collect(f, out, depth + 1);
            } else {
                String n = f.getName();
                // The assistant is asked about whatever is in the project, so a
                // Scala or Clojure file it cannot see is a question it cannot
                // answer.
                if (n.endsWith(".java") || n.endsWith(".kt")
                        || com.ccs.wiredroid.util.languages.LanguageFiles.isKnown(n)) {
                    out.add(f);
                }
            }
        }
    }
}
