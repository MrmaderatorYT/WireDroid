package com.ccs.wiredroid.util;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Works out which arguments deserve to be labelled with parameter inlay hints.
 *
 * <p>Supports:</p>
 * <ul>
 *   <li>Constructors and methods across the entire project</li>
 *   <li>Inherited parameter names from extended superclasses</li>
 *   <li>Package-private and interface declarations</li>
 *   <li>Literals, enum constants (e.g. {@code Consumption.HERBIVORES}), and {@code new} expressions</li>
 *   <li>Built-in signatures for common assertions and standard Java APIs</li>
 * </ul>
 */
public final class InlayHints {

    /** A label to draw at a position in the document. */
    public static final class Hint {
        /** Zero-based, matching the editor's rows. */
        public final int line;
        public final int column;
        public final String name;

        public Hint(int line, int column, String name) {
            this.line = line;
            this.column = column;
            this.name = name;
        }
    }

    /** Declarations, so a call can be matched to the parameter names it should show. */
    public static final class Index {
        /** name + "/" + arity → parameter names. */
        private final Map<String, List<String>> byKey = new HashMap<>();
        /** Subclass → Superclass mapping for inheriting parameter names. */
        private final Map<String, String> superClasses = new HashMap<>();

        public int size() {
            return byKey.size();
        }

        public void add(String name, List<String> params) {
            if (name == null || params == null || params.isEmpty()) return;
            String key = name + "/" + params.size();
            List<String> existing = byKey.get(key);
            if (existing == null) {
                byKey.put(key, params);
            }
        }

        public void recordInheritance(String subClass, String superClass) {
            if (subClass != null && superClass != null && !subClass.equals(superClass)) {
                superClasses.put(subClass, superClass);
            }
        }

        public List<String> get(String name, int arity) {
            String key = name + "/" + arity;
            List<String> params = byKey.get(key);
            if (params != null) return params;

            // Check if name is a constructor of a subclass inheriting from a superclass
            String current = name;
            Set<String> visited = new HashSet<>();
            while (current != null && visited.add(current)) {
                String parent = superClasses.get(current);
                if (parent == null) break;
                List<String> inherited = byKey.get(parent + "/" + arity);
                if (inherited != null) return inherited;
                current = parent;
            }
            return null;
        }
    }

    private static final Pattern METHOD_OR_CTOR_DECL = Pattern.compile(
            "(?:(?:public|protected|private|static|final|abstract|synchronized|default|native)\\s+)*"
                    + "(?:<[^>]{0,120}>\\s*)?"
                    + "(?:[\\w$.<>\\[\\],\\s?]{1,120}?\\s+)?"
                    + "([A-Za-z_$][\\w$]*)\\s*\\(([^)]{0,400})\\)\\s*(?:throws\\s+[A-Za-z0-9_$,\\s]+)?\\s*[{;]");

    private static final Pattern CLASS_EXTENDS = Pattern.compile(
            "\\b(?:class|interface|enum|record)\\s+([A-Za-z0-9_$]+)(?:<[^>]+>)?\\s+extends\\s+([A-Za-z0-9_$.]+)");

    /** A call: an identifier followed by an open parenthesis. */
    private static final Pattern CALL = Pattern.compile("([A-Za-z_$][\\w$]*)\\s*\\(");

    private static final Set<String> NOT_CALLS = new HashSet<>(java.util.Arrays.asList(
            "if", "for", "while", "switch", "catch", "synchronized", "return",
            "this", "try", "do", "else", "assert", "case"));

    private InlayHints() {
    }

    // ── Indexing ────────────────────────────────────────────────────────────

    /**
     * Reads every source under {@code root} once and populates signatures.
     */
    public static Index index(File root, int maxFiles) {
        Index index = new Index();
        populateBuiltinSignatures(index);
        if (root == null || !root.isDirectory()) return index;
        List<File> files = new ArrayList<>();
        collect(root, files, maxFiles, 0);
        for (File f : files) {
            try {
                addSource(index, new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            } catch (Throwable ignored) {}
        }
        return index;
    }

    private static void populateBuiltinSignatures(Index index) {
        // JUnit 4 / 5 Assertions
        index.add("assertEquals", java.util.Arrays.asList("expected", "actual"));
        index.add("assertEquals", java.util.Arrays.asList("expected", "actual", "message"));
        index.add("assertNotEquals", java.util.Arrays.asList("unexpected", "actual"));
        index.add("assertNotEquals", java.util.Arrays.asList("unexpected", "actual", "message"));
        index.add("assertTrue", java.util.Arrays.asList("condition"));
        index.add("assertTrue", java.util.Arrays.asList("condition", "message"));
        index.add("assertFalse", java.util.Arrays.asList("condition"));
        index.add("assertFalse", java.util.Arrays.asList("condition", "message"));
        index.add("assertNull", java.util.Arrays.asList("actual"));
        index.add("assertNull", java.util.Arrays.asList("actual", "message"));
        index.add("assertNotNull", java.util.Arrays.asList("actual"));
        index.add("assertNotNull", java.util.Arrays.asList("actual", "message"));
        index.add("assertSame", java.util.Arrays.asList("expected", "actual"));
        index.add("assertNotSame", java.util.Arrays.asList("unexpected", "actual"));
        index.add("assertArrayEquals", java.util.Arrays.asList("expected", "actual"));
        index.add("assertThrows", java.util.Arrays.asList("expectedType", "executable"));

        // Standard Collections & Strings
        index.add("substring", java.util.Arrays.asList("beginIndex", "endIndex"));
        index.add("charAt", java.util.Arrays.asList("index"));
        index.add("indexOf", java.util.Arrays.asList("str", "fromIndex"));
        index.add("replace", java.util.Arrays.asList("target", "replacement"));
        index.add("set", java.util.Arrays.asList("index", "element"));
        index.add("get", java.util.Arrays.asList("index"));
        index.add("add", java.util.Arrays.asList("index", "element"));
        index.add("put", java.util.Arrays.asList("key", "value"));
        index.add("format", java.util.Arrays.asList("format", "args"));
    }

    /** Adds declarations from one source file. */
    public static void addSource(Index index, String source) {
        if (source == null) return;

        // 1. Record inheritance hierarchies
        Matcher em = CLASS_EXTENDS.matcher(source);
        while (em.find()) {
            String subClass = em.group(1);
            String superClass = em.group(2);
            int dot = superClass.lastIndexOf('.');
            if (dot >= 0) superClass = superClass.substring(dot + 1);
            index.recordInheritance(subClass, superClass);
        }

        // 2. Record methods and constructors
        Matcher m = METHOD_OR_CTOR_DECL.matcher(source);
        while (m.find()) {
            String name = m.group(1);
            if (NOT_CALLS.contains(name)) continue;
            List<String> params = parameterNames(m.group(2));
            if (params == null || params.isEmpty()) continue;
            index.add(name, params);
        }
    }

    private static List<String> parameterNames(String raw) {
        String params = raw.trim();
        if (params.isEmpty()) return null;
        List<String> names = new ArrayList<>();
        for (String p : splitTopLevel(params)) {
            String one = p.trim().replaceAll("@[\\w.]+(\\([^)]*\\))?", " ")
                    .replaceAll("\\bfinal\\b", " ").trim();
            if (one.isEmpty()) return null;
            if (one.contains("...")) return null;
            int space = lastTopLevelSpace(one);
            if (space <= 0) return null;
            String name = one.substring(space + 1).trim();
            if (name.isEmpty() || !Character.isJavaIdentifierStart(name.charAt(0))) return null;
            names.add(name);
        }
        return names;
    }

    private static void collect(File dir, List<File> out, int max, int depth) {
        if (depth > 12 || out.size() >= max) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File f : children) {
            if (out.size() >= max) return;
            if (f.isDirectory()) {
                String n = f.getName();
                if (n.startsWith(".") || n.equals("build") || n.equals("target")) continue;
                collect(f, out, max, depth + 1);
            } else if (f.getName().endsWith(".java")) {
                out.add(f);
            }
        }
    }

    // ── Generating Hints for Editor ──────────────────────────────────────────

    public static List<Hint> forSource(String source, Index index, int maxHints) {
        List<Hint> out = new ArrayList<>();
        if (source == null || index == null || index.size() == 0) return out;

        String clean = blankOut(source);
        int[] lineStarts = lineStarts(source);

        Matcher m = CALL.matcher(clean);
        while (m.find() && out.size() < maxHints) {
            String name = m.group(1);
            if (NOT_CALLS.contains(name)) continue;

            // Check if this is preceded by "new" (a constructor call) or is a normal call
            int before = m.start() - 1;
            while (before >= 0 && Character.isWhitespace(clean.charAt(before))) before--;
            boolean isNew = false;
            if (before >= 2 && clean.substring(before - 2, before + 1).equals("new")) {
                int preNew = before - 3;
                if (preNew < 0 || !Character.isJavaIdentifierPart(clean.charAt(preNew))) {
                    isNew = true;
                }
            }

            // If not a "new" constructor and preceded by identifier (like "void foo("), it's a declaration -> skip
            if (!isNew && before >= 0 && Character.isJavaIdentifierPart(clean.charAt(before))) {
                continue;
            }

            int open = m.end() - 1;
            int close = matchParen(clean, open);
            if (close < 0) continue;

            List<int[]> args = argumentSpans(clean, open + 1, close);
            if (args.isEmpty()) continue;
            List<String> params = index.get(name, args.size());
            if (params == null) continue;

            for (int i = 0; i < args.size() && i < params.size() && out.size() < maxHints; i++) {
                String argument = source.substring(args.get(i)[0], args.get(i)[1]).trim();
                String paramName = params.get(i);
                if (!isDeservingHint(argument, paramName)) continue;
                int[] pos = positionOf(lineStarts, args.get(i)[0]);
                out.add(new Hint(pos[0], pos[1], paramName));
            }
        }
        return out;
    }

    private static boolean isDeservingHint(String argument, String paramName) {
        if (argument == null || argument.isEmpty()) return false;
        // Don't add redundant hint if argument matches parameter name
        if (argument.equalsIgnoreCase(paramName) || argument.endsWith("." + paramName)) return false;

        char c = argument.charAt(0);
        // String & char literals
        if (c == '"' || c == '\'') return true;
        // Booleans & null
        if (argument.equals("true") || argument.equals("false") || argument.equals("null")) return true;
        // Numbers
        if (c == '-' || c == '+' || Character.isDigit(c)) {
            return argument.matches("[+-]?[\\d._xXa-fA-F]+[LlFfDd]?");
        }
        // Enum constants or static fields (e.g. Consumption.HERBIVORES, Color.RED)
        if (argument.contains(".") && Character.isUpperCase(c)) return true;
        // New object or array instantiations
        if (argument.startsWith("new ") || argument.startsWith("new\t") || argument.startsWith("new\n")) return true;
        // Nested method calls or complex expressions
        if (argument.contains("(") || argument.contains("->") || argument.contains("::")) return true;

        return false;
    }

    private static List<int[]> argumentSpans(String clean, int from, int to) {
        List<int[]> out = new ArrayList<>();
        int depth = 0;
        int start = from;
        for (int i = from; i < to; i++) {
            char c = clean.charAt(i);
            if (c == '(' || c == '[' || c == '{') depth++;
            else if (c == ')' || c == ']' || c == '}') depth--;
            else if (c == ',' && depth == 0) {
                out.add(new int[]{start, i});
                start = i + 1;
            }
        }
        if (start < to) out.add(new int[]{start, to});
        if (out.size() == 1 && clean.substring(out.get(0)[0], out.get(0)[1]).trim().isEmpty()) {
            out.clear();
        }
        for (int[] span : out) {
            while (span[0] < span[1] && Character.isWhitespace(clean.charAt(span[0]))) span[0]++;
        }
        return out;
    }

    // ── Text Parsing Utilities ───────────────────────────────────────────────

    private static int[] lineStarts(String source) {
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 0; i < source.length(); i++) {
            if (source.charAt(i) == '\n') starts.add(i + 1);
        }
        int[] out = new int[starts.size()];
        for (int i = 0; i < out.length; i++) out[i] = starts.get(i);
        return out;
    }

    private static int[] positionOf(int[] lineStarts, int offset) {
        int lo = 0, hi = lineStarts.length - 1, line = 0;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (lineStarts[mid] <= offset) { line = mid; lo = mid + 1; } else { hi = mid - 1; }
        }
        return new int[]{line, offset - lineStarts[line]};
    }

    private static int matchParen(String s, int open) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')' && --depth == 0) return i;
        }
        return -1;
    }

    private static String blankOut(String src) {
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
                i++;
                while (i < n && src.charAt(i) != c) {
                    if (src.charAt(i) == '\\' && i + 1 < n) { out[i++] = ' '; }
                    if (i < n && src.charAt(i) != '\n') out[i] = ' ';
                    i++;
                }
                if (i < n) i++;
            } else {
                i++;
            }
        }
        return new String(out);
    }

    private static List<String> splitTopLevel(String s) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '<' || c == '(' || c == '[') depth++;
            else if (c == '>' || c == ')' || c == ']') depth--;
            else if (c == ',' && depth == 0) {
                out.add(s.substring(start, i));
                start = i + 1;
            }
        }
        if (start < s.length()) out.add(s.substring(start));
        return out;
    }

    private static int lastTopLevelSpace(String s) {
        int depth = 0;
        int last = -1;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '<' || c == '(' || c == '[') depth++;
            else if (c == '>' || c == ')' || c == ']') depth--;
            else if (Character.isWhitespace(c) && depth == 0) last = i;
        }
        return last;
    }
}
