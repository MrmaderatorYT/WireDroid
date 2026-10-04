package com.ccs.wiredroid.util;

import com.ccs.wiredroid.project.ProjectScanner;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.rosemoe.sora.text.Content;
import io.github.rosemoe.sora.text.ContentReference;
import io.github.rosemoe.sora.text.Cursor;

/**
 * Автододавання import-ів для Java.
 * Аналізує використані класи та пропонує додати відсутні імпорти.
 */
public final class AutoImportHelper {

    private static final Pattern P_IMPORT = Pattern.compile(
            "^\\s*import\\s+(?:static\\s+)?([\\w.*]+)\\s*;", Pattern.MULTILINE);
    private static final Pattern P_PACKAGE = Pattern.compile(
            "^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);
    private static final Pattern P_CLASS_DECL = Pattern.compile(
            "\\b(?:public\\s+)?(?:abstract\\s+)?(?:final\\s+)?class\\s+(\\w+)");
    private static final Pattern P_INTERFACE_DECL = Pattern.compile(
            "\\b(?:public\\s+)?(?:abstract\\s+)?interface\\s+(\\w+)");
    private static final Pattern P_ENUM_DECL = Pattern.compile(
            "\\b(?:public\\s+)?enum\\s+(\\w+)");

    private static final Set<String> JAVA_KEYWORDS = new HashSet<>(Arrays.asList(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
            "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
            "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
            "interface", "long", "native", "new", "package", "private", "protected", "public",
            "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
            "throw", "throws", "transient", "try", "void", "volatile", "while", "true", "false",
            "null", "var", "record", "yield", "sealed", "non-sealed", "permits", "when",
            "exports", "module", "opens", "provides", "requires", "to", "uses", "with", "transitive"
    ));

    private static final Set<String> PRIMITIVE_TYPES = new HashSet<>(Arrays.asList(
            "int", "long", "short", "byte", "char", "float", "double", "boolean", "void"
    ));

    private static final Set<String> JAVA_LANG_TYPES = new HashSet<>(Arrays.asList(
            "String", "Integer", "Long", "Short", "Byte", "Character", "Float", "Double",
            "Boolean", "Object", "Class", "System", "Math", "Thread", "Runnable",
            "Exception", "RuntimeException", "Throwable", "Error",
            "StringBuilder", "StringBuffer", "StringJoiner",
            "ArrayList", "LinkedList", "HashMap", "HashSet", "TreeMap", "TreeSet",
            "LinkedHashMap", "LinkedHashSet", "Vector", "Hashtable", "Stack",
            "Collections", "Arrays", "Objects", "Optional",
            "List", "Map", "Set", "Queue", "Deque", "Collection",
            "Iterator", "Iterable", "Comparable", "Comparator",
            "File", "IOException", "FileInputStream", "FileOutputStream",
            "BufferedReader", "BufferedWriter", "InputStreamReader", "OutputStreamWriter",
            "PrintStream", "PrintWriter", "Scanner",
            "Path", "Paths", "Files", "StandardCharsets",
            "Date", "Calendar", "LocalDate", "LocalTime", "LocalDateTime",
            "SimpleDateFormat", "DateFormat", "TimeZone",
            "Random", "UUID", "Enum", "Annotation",
            "Override", "SuppressWarnings", "Deprecated",
            "FunctionalInterface", "Supplier", "Consumer", "Function", "Predicate",
            "CompletableFuture", "Future", "Callable", "Executors",
            "AtomicInteger", "AtomicLong", "AtomicBoolean", "ThreadLocal",
            "ConcurrentHashMap", "CountDownLatch", "Semaphore", "Lock", "ReentrantLock"
    ));

    private static final Map<String, String> COMMON_IMPORTS = new HashMap<>();
    static {
        COMMON_IMPORTS.put("ArrayList", "java.util.ArrayList");
        COMMON_IMPORTS.put("LinkedList", "java.util.LinkedList");
        COMMON_IMPORTS.put("HashMap", "java.util.HashMap");
        COMMON_IMPORTS.put("HashSet", "java.util.HashSet");
        COMMON_IMPORTS.put("TreeMap", "java.util.TreeMap");
        COMMON_IMPORTS.put("TreeSet", "java.util.TreeSet");
        COMMON_IMPORTS.put("LinkedHashMap", "java.util.LinkedHashMap");
        COMMON_IMPORTS.put("LinkedHashSet", "java.util.LinkedHashSet");
        COMMON_IMPORTS.put("List", "java.util.List");
        COMMON_IMPORTS.put("Map", "java.util.Map");
        COMMON_IMPORTS.put("Set", "java.util.Set");
        COMMON_IMPORTS.put("Queue", "java.util.Queue");
        COMMON_IMPORTS.put("Deque", "java.util.Deque");
        COMMON_IMPORTS.put("Collection", "java.util.Collection");
        COMMON_IMPORTS.put("Collections", "java.util.Collections");
        COMMON_IMPORTS.put("Arrays", "java.util.Arrays");
        COMMON_IMPORTS.put("Objects", "java.util.Objects");
        COMMON_IMPORTS.put("Optional", "java.util.Optional");
        COMMON_IMPORTS.put("Iterator", "java.util.Iterator");
        COMMON_IMPORTS.put("Iterable", "java.lang.Iterable");
        COMMON_IMPORTS.put("Comparable", "java.lang.Comparable");
        COMMON_IMPORTS.put("Comparator", "java.util.Comparator");
        COMMON_IMPORTS.put("UUID", "java.util.UUID");
        COMMON_IMPORTS.put("Random", "java.util.Random");

        COMMON_IMPORTS.put("File", "java.io.File");
        COMMON_IMPORTS.put("IOException", "java.io.IOException");
        COMMON_IMPORTS.put("FileInputStream", "java.io.FileInputStream");
        COMMON_IMPORTS.put("FileOutputStream", "java.io.FileOutputStream");
        COMMON_IMPORTS.put("BufferedReader", "java.io.BufferedReader");
        COMMON_IMPORTS.put("BufferedWriter", "java.io.BufferedWriter");
        COMMON_IMPORTS.put("InputStreamReader", "java.io.InputStreamReader");
        COMMON_IMPORTS.put("OutputStreamWriter", "java.io.OutputStreamWriter");
        COMMON_IMPORTS.put("PrintStream", "java.io.PrintStream");
        COMMON_IMPORTS.put("PrintWriter", "java.io.PrintWriter");
        COMMON_IMPORTS.put("Scanner", "java.util.Scanner");
        COMMON_IMPORTS.put("InputStream", "java.io.InputStream");
        COMMON_IMPORTS.put("OutputStream", "java.io.OutputStream");
        COMMON_IMPORTS.put("Reader", "java.io.Reader");
        COMMON_IMPORTS.put("Writer", "java.io.Writer");

        COMMON_IMPORTS.put("Path", "java.nio.file.Path");
        COMMON_IMPORTS.put("Paths", "java.nio.file.Paths");
        COMMON_IMPORTS.put("Files", "java.nio.file.Files");
        COMMON_IMPORTS.put("StandardCharsets", "java.nio.charset.StandardCharsets");

        COMMON_IMPORTS.put("Date", "java.util.Date");
        COMMON_IMPORTS.put("Calendar", "java.util.Calendar");
        COMMON_IMPORTS.put("LocalDate", "java.time.LocalDate");
        COMMON_IMPORTS.put("LocalTime", "java.time.LocalTime");
        COMMON_IMPORTS.put("LocalDateTime", "java.time.LocalDateTime");
        COMMON_IMPORTS.put("SimpleDateFormat", "java.text.SimpleDateFormat");

        COMMON_IMPORTS.put("CompletableFuture", "java.util.concurrent.CompletableFuture");
        COMMON_IMPORTS.put("Future", "java.util.concurrent.Future");
        COMMON_IMPORTS.put("Callable", "java.util.concurrent.Callable");
        COMMON_IMPORTS.put("Executors", "java.util.concurrent.Executors");
        COMMON_IMPORTS.put("ExecutorService", "java.util.concurrent.ExecutorService");

        COMMON_IMPORTS.put("AtomicInteger", "java.util.concurrent.atomic.AtomicInteger");
        COMMON_IMPORTS.put("AtomicLong", "java.util.concurrent.atomic.AtomicLong");
        COMMON_IMPORTS.put("ConcurrentHashMap", "java.util.concurrent.ConcurrentHashMap");

        COMMON_IMPORTS.put("StringBuilder", "java.lang.StringBuilder");
        COMMON_IMPORTS.put("StringBuffer", "java.lang.StringBuffer");

        COMMON_IMPORTS.put("Math", "java.lang.Math");
        COMMON_IMPORTS.put("System", "java.lang.System");
        COMMON_IMPORTS.put("Thread", "java.lang.Thread");
        COMMON_IMPORTS.put("Runnable", "java.lang.Runnable");

        COMMON_IMPORTS.put("Exception", "java.lang.Exception");
        COMMON_IMPORTS.put("RuntimeException", "java.lang.RuntimeException");
        COMMON_IMPORTS.put("Throwable", "java.lang.Throwable");

        COMMON_IMPORTS.put("Override", "java.lang.Override");
        COMMON_IMPORTS.put("SuppressWarnings", "java.lang.SuppressWarnings");
        COMMON_IMPORTS.put("Deprecated", "java.lang.Deprecated");
    }

    public interface ImportSuggestionCallback {
        void onSuggestionsReady(List<ImportSuggestion> suggestions);
    }

    public static class ImportSuggestion {
        public final String className;
        public final String fullImport;
        public final int lineIndex;

        public ImportSuggestion(String className, String fullImport, int lineIndex) {
            this.className = className;
            this.fullImport = fullImport;
            this.lineIndex = lineIndex;
        }
    }

    private AutoImportHelper() {}

    /**
     * Аналізує поточний код та знаходить класи, які використовуються але не імпортовані.
     */
    public static void analyzeAndSuggest(@NonNull Context context,
                                          @Nullable File projectRoot,
                                          @NonNull Content content,
                                          @NonNull ImportSuggestionCallback callback) {
        new Thread(() -> {
            try {
                String source = content.toString();
                List<ImportSuggestion> suggestions = findMissingImports(context, projectRoot, source);
                new Handler(Looper.getMainLooper()).post(() -> callback.onSuggestionsReady(suggestions));
            } catch (Exception e) {
                new Handler(Looper.getMainLooper()).post(() -> callback.onSuggestionsReady(new ArrayList<>()));
            }
        }).start();
    }

    public static List<ImportSuggestion> findMissingImports(@NonNull Context context,
                                                             @Nullable File projectRoot,
                                                             @NonNull String source) {
        List<ImportSuggestion> result = new ArrayList<>();

        List<String> existingImports = parseImports(source);
        Set<String> importedSimpleNames = new HashSet<>();
        for (String imp : existingImports) {
            int dot = imp.lastIndexOf('.');
            if (dot >= 0) {
                importedSimpleNames.add(imp.substring(dot + 1));
            }
        }

        String currentPackage = parsePackage(source);
        Set<String> declaredClasses = findDeclaredClasses(source);

        Set<String> usedClassNames = extractUsedClassNames(source);

        ClassLoader cl = context.getClassLoader();

        for (String className : usedClassNames) {
            if (importedSimpleNames.contains(className)) continue;
            if (declaredClasses.contains(className)) continue;
            if (currentPackage != null) {
                try {
                    Class.forName(currentPackage + "." + className, false, cl);
                    continue;
                } catch (ClassNotFoundException | NoClassDefFoundError ignored) {
                }
            }

            String fqcn = resolveClass(className, existingImports, currentPackage, cl, projectRoot);
            if (fqcn != null && isImportable(fqcn)) {
                int insertLine = findImportInsertLine(source);
                result.add(new ImportSuggestion(className, fqcn, insertLine));
            }
        }

        return result;
    }

    /**
     * Whether an import statement for this type would be legal and useful.
     *
     * <p>Two kinds are neither. A type with no package lives in the unnamed
     * package, which Java forbids importing — writing {@code import Shape;}
     * turns a working file into one that will not compile. And everything
     * directly in {@code java.lang} is already in scope, so importing it only
     * adds lines nobody wants to read.</p>
     */
    private static boolean isImportable(String fqcn) {
        int firstDot = fqcn.indexOf('.');
        if (firstDot <= 0) return false;
        return !(fqcn.startsWith("java.lang.")
                && fqcn.indexOf('.', "java.lang.".length()) < 0);
    }

    /**
     * Автоматично додає всі запропоновані імпорти в код.
     */
    public static String addImportsAuto(@NonNull String source, @NonNull List<ImportSuggestion> suggestions) {
        if (suggestions.isEmpty()) return source;

        StringBuilder sb = new StringBuilder(source);
        int offset = 0;

        for (ImportSuggestion s : suggestions) {
            String importLine = "import " + s.fullImport + ";\n";
            int insertPos = findInsertPosition(sb.toString());
            sb.insert(insertPos, importLine);
        }

        return sb.toString();
    }

    /**
     * Додає один конкретний імпорт.
     */
    public static String addSingleImport(@NonNull String source, @NonNull String fullImport) {
        String importLine = "import " + fullImport + ";\n";
        int insertPos = findInsertPosition(source);
        StringBuilder sb = new StringBuilder(source);
        sb.insert(insertPos, importLine);
        return sb.toString();
    }

    private static int findInsertPosition(String source) {
        Pattern p = Pattern.compile("^\\s*import\\s+", Pattern.MULTILINE);
        Matcher m = p.matcher(source);
        int lastImportEnd = -1;
        while (m.find()) {
            lastImportEnd = m.end();
            int lineEnd = source.indexOf('\n', m.start());
            if (lineEnd >= 0) lastImportEnd = lineEnd + 1;
        }
        if (lastImportEnd >= 0) {
            return lastImportEnd;
        }

        Pattern pPkg = Pattern.compile("^\\s*package\\s+[\\w.]+\\s*;", Pattern.MULTILINE);
        Matcher mPkg = pPkg.matcher(source);
        if (mPkg.find()) {
            int lineEnd = source.indexOf('\n', mPkg.end());
            return lineEnd >= 0 ? lineEnd + 1 : mPkg.end() + 1;
        }

        return 0;
    }

    /**
     * The simple-name to fully-qualified table this class resolves against.
     *
     * <p>Exposed so completion can offer the same classes it would later import,
     * instead of keeping a second list that drifts from this one.</p>
     */
    public static Map<String, String> commonImports() {
        return java.util.Collections.unmodifiableMap(COMMON_IMPORTS);
    }

    private static String resolveClass(String className, List<String> imports, String pkg,
                                        ClassLoader cl, File projectRoot) {
        if (COMMON_IMPORTS.containsKey(className)) {
            return COMMON_IMPORTS.get(className);
        }

        for (String imp : imports) {
            if (imp.endsWith("." + className)) {
                return imp;
            }
            if (imp.endsWith(".*")) {
                String base = imp.substring(0, imp.length() - 1) + className;
                try {
                    Class.forName(base, false, cl);
                    return base;
                } catch (ClassNotFoundException | NoClassDefFoundError ignored) {
                }
            }
        }

        try {
            Class.forName("java.lang." + className, false, cl);
            return "java.lang." + className;
        } catch (ClassNotFoundException | NoClassDefFoundError ignored) {
        }

        if (projectRoot != null) {
            String found = searchProjectForClass(projectRoot, className);
            if (found != null) return found;
        }

        String[] packages = {
            "java.util", "java.io", "java.nio", "java.nio.file", "java.nio.charset",
            "java.time", "java.text", "java.util.concurrent", "java.util.concurrent.atomic",
            "java.util.regex", "java.math", "java.net", "java.security",
            "android.os", "android.content", "android.view", "android.widget",
            "android.graphics", "android.app", "android.util"
        };
        for (String p : packages) {
            try {
                Class.forName(p + "." + className, false, cl);
                return p + "." + className;
            } catch (ClassNotFoundException | NoClassDefFoundError ignored) {
            }
        }

        return null;
    }

    /**
     * @return the qualified name of a project type, or null when there is none
     *         that can be named in an import — including a match that turns out
     *         to sit in the unnamed package, which Java forbids importing.
     *         Such a match is skipped rather than ending the search, so a
     *         same-named type in a real package elsewhere still wins.
     */
    @Nullable
    private static String searchProjectForClass(File root, String className) {
        List<File> sources = ProjectScanner.listJavaSources(root);
        for (File f : sources) {
            try {
                String content = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                String pkg = parsePackage(content);
                if (pkg == null || pkg.isEmpty()) continue;
                if (declares(content, className)) return pkg + "." + className;
            } catch (IOException ignored) {
            }
        }
        return null;
    }

    /** Whether the source declares a class, interface or enum of this name. */
    private static boolean declares(String content, String className) {
        for (Pattern p : new Pattern[]{P_CLASS_DECL, P_INTERFACE_DECL, P_ENUM_DECL}) {
            Matcher m = p.matcher(content);
            while (m.find()) {
                if (m.group(1).equals(className)) return true;
            }
        }
        return false;
    }

    static Set<String> extractUsedClassNames(String source) {
        Set<String> used = new HashSet<>();
        String cleaned = removeCommentsAndStrings(source);

        Pattern pIdent = Pattern.compile("\\b([A-Z][A-Za-z0-9_]*)\\b");
        Matcher m = pIdent.matcher(cleaned);
        while (m.find()) {
            String name = m.group(1);
            if (!JAVA_KEYWORDS.contains(name) && !PRIMITIVE_TYPES.contains(name)) {
                used.add(name);
            }
        }

        Pattern pNew = Pattern.compile("\\bnew\\s+([A-Z][A-Za-z0-9_]*)");
        Matcher mNew = pNew.matcher(cleaned);
        while (mNew.find()) {
            used.add(mNew.group(1));
        }

        Pattern pExtends = Pattern.compile("\\bextends\\s+([A-Z][A-Za-z0-9_]*)");
        Matcher mExt = pExtends.matcher(cleaned);
        while (mExt.find()) {
            used.add(mExt.group(1));
        }

        Pattern pImplements = Pattern.compile("\\bimplements\\s+([A-Z][A-Za-z0-9_]*)");
        Matcher mImp = pImplements.matcher(cleaned);
        while (mImp.find()) {
            used.add(mImp.group(1));
        }

        Pattern pThrows = Pattern.compile("\\bthrows\\s+([A-Z][A-Za-z0-9_]*)");
        Matcher mThr = pThrows.matcher(cleaned);
        while (mThr.find()) {
            used.add(mThr.group(1));
        }

        Pattern pCatch = Pattern.compile("\\bcatch\\s*\\(\\s*([A-Z][A-Za-z0-9_]*)");
        Matcher mCat = pCatch.matcher(cleaned);
        while (mCat.find()) {
            used.add(mCat.group(1));
        }

        used.remove("Main");
        used.remove("App");

        return used;
    }

    private static String removeCommentsAndStrings(String source) {
        StringBuilder sb = new StringBuilder(source.length());
        int i = 0;
        int len = source.length();
        while (i < len) {
            char c = source.charAt(i);
            if (c == '/' && i + 1 < len) {
                char next = source.charAt(i + 1);
                if (next == '/') {
                    while (i < len && source.charAt(i) != '\n') i++;
                    continue;
                } else if (next == '*') {
                    i += 2;
                    while (i < len && !(source.charAt(i) == '*' && i + 1 < len && source.charAt(i + 1) == '/')) {
                        if (source.charAt(i) == '\n') sb.append('\n');
                        i++;
                    }
                    i += 2;
                    continue;
                }
            }
            if (c == '"') {
                if (i + 2 < len && source.charAt(i + 1) == '"' && source.charAt(i + 2) == '"') {
                    i += 3;
                    while (i < len && !(source.charAt(i) == '"' && i + 2 < len
                            && source.charAt(i + 1) == '"' && source.charAt(i + 2) == '"')) {
                        i++;
                    }
                    i += 3;
                    continue;
                }
                i++;
                while (i < len && source.charAt(i) != '"') {
                    if (source.charAt(i) == '\\') i++;
                    i++;
                }
                i++;
                continue;
            }
            if (c == '\'') {
                i++;
                while (i < len && source.charAt(i) != '\'') {
                    if (source.charAt(i) == '\\') i++;
                    i++;
                }
                i++;
                continue;
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    /**
     * Removes unused imports from Java source code.
     */
    public static String optimizeImports(String source) {
        if (source == null || source.isEmpty()) return source;

        List<String> imports = parseImports(source);
        if (imports.isEmpty()) return source;

        // Strip imports, comments, and strings to inspect actual code usages
        String[] lines = source.split("\n", -1);
        StringBuilder cleanCode = new StringBuilder();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("import ") || trimmed.startsWith("//")) {
                continue;
            }
            cleanCode.append(line).append("\n");
        }
        
        // Remove block comments and string literals
        String codeBody = cleanCode.toString().replaceAll("/\\*.*?\\*/", " ").replaceAll("\".*?\"", " ");

        Set<String> unusedImports = new HashSet<>();
        for (String imp : imports) {
            if (imp.endsWith(".*")) continue; // Keep wildcard imports
            int lastDot = imp.lastIndexOf('.');
            if (lastDot < 0) continue;
            String simpleName = imp.substring(lastDot + 1);

            // Check if simple name exists in code body as an identifier
            Pattern wordPattern = Pattern.compile("\\b" + Pattern.quote(simpleName) + "\\b");
            if (!wordPattern.matcher(codeBody).find()) {
                unusedImports.add(imp);
            }
        }

        if (unusedImports.isEmpty()) return source;

        // Rebuild code without unused imports
        StringBuilder result = new StringBuilder();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("import ")) {
                Matcher m = P_IMPORT.matcher(trimmed);
                if (m.find()) {
                    String imp = m.group(1);
                    if (unusedImports.contains(imp)) {
                        continue; // Skip unused import line
                    }
                }
            }
            result.append(line).append("\n");
        }

        String resStr = result.toString();
        if (!source.endsWith("\n") && resStr.endsWith("\n")) {
            resStr = resStr.substring(0, resStr.length() - 1);
        }
        return resStr;
    }

    /**
     * Removes unused imports and sorts remaining imports alphabetically.
     */
    public static String organizeImports(String source) {
        String optimized = optimizeImports(source);
        if (optimized == null || optimized.isEmpty()) return optimized;

        String[] lines = optimized.split("\n", -1);
        List<String> importLines = new ArrayList<>();
        int firstImportIndex = -1;
        int lastImportIndex = -1;

        for (int i = 0; i < lines.length; i++) {
            if (lines[i].trim().startsWith("import ")) {
                if (firstImportIndex == -1) firstImportIndex = i;
                lastImportIndex = i;
                importLines.add(lines[i].trim());
            }
        }

        if (importLines.size() <= 1 || firstImportIndex == -1) return optimized;

        java.util.Collections.sort(importLines);

        StringBuilder result = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i == firstImportIndex) {
                for (String impLine : importLines) {
                    result.append(impLine).append("\n");
                }
            } else if (i > firstImportIndex && i <= lastImportIndex && lines[i].trim().startsWith("import ")) {
                continue; // Skip replaced import line
            } else {
                result.append(lines[i]).append("\n");
            }
        }

        String resStr = result.toString();
        if (!source.endsWith("\n") && resStr.endsWith("\n")) {
            resStr = resStr.substring(0, resStr.length() - 1);
        }
        return resStr;
    }

    private static Set<String> findDeclaredClasses(String source) {
        Set<String> declared = new HashSet<>();
        Matcher m = P_CLASS_DECL.matcher(source);
        while (m.find()) declared.add(m.group(1));
        m = P_INTERFACE_DECL.matcher(source);
        while (m.find()) declared.add(m.group(1));
        m = P_ENUM_DECL.matcher(source);
        while (m.find()) declared.add(m.group(1));
        return declared;
    }

    private static int findImportInsertLine(String source) {
        String[] lines = source.split("\n", -1);
        int lastImportLine = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].trim().startsWith("import ")) {
                lastImportLine = i;
            }
        }
        if (lastImportLine >= 0) return lastImportLine + 1;

        for (int i = 0; i < lines.length; i++) {
            if (lines[i].trim().startsWith("package ")) {
                return i + 1;
            }
        }
        return 0;
    }

    static List<String> parseImports(String source) {
        List<String> out = new ArrayList<>();
        Matcher m = P_IMPORT.matcher(source);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    static String parsePackage(String source) {
        Matcher m = P_PACKAGE.matcher(source);
        return m.find() ? m.group(1) : null;
    }
}
