package com.ccs.wiredroid.util;

import com.ccs.wiredroid.maven.MavenPaths;
import com.ccs.wiredroid.project.ProjectScanner;
import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.rosemoe.sora.lang.completion.CompletionItemKind;
import io.github.rosemoe.sora.lang.completion.CompletionPublisher;
import io.github.rosemoe.sora.lang.completion.SimpleCompletionItem;
import io.github.rosemoe.sora.lang.completion.SimpleSnippetCompletionItem;
import io.github.rosemoe.sora.lang.completion.SnippetDescription;
import io.github.rosemoe.sora.lang.completion.snippet.parser.CodeSnippetParser;

import io.github.rosemoe.sora.text.CharPosition;
import io.github.rosemoe.sora.text.ContentReference;

/**
 * Intelligent Java auto-completion engine supporting:
 * <ul>
 *   <li>Inherited methods & fields across extended classes and interfaces</li>
 *   <li>Project source indexing with full class hierarchy traversal</li>
 *   <li>SDK & library reflection for built-in classes</li>
 *   <li>{@code this}, {@code super}, local variables, parameters, and static class receivers</li>
 *   <li>Snippets, auto-imports, and project class suggestions</li>
 * </ul>
 */
public final class JavaReflectionCompletion {

    private static final Pattern P_PACKAGE = Pattern.compile(
            "^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);
    private static final Pattern P_CLASS = Pattern.compile(
            "\\b(?:public\\s+|protected\\s+|private\\s+|abstract\\s+|final\\s+|static\\s+)*(?:class|interface|enum|record)\\s+([A-Za-z0-9_$]+)");
    private static final Pattern P_IMPORT = Pattern.compile(
            "^\\s*import\\s+(?:static\\s+)?([\\w.*]+)\\s*;", Pattern.MULTILINE);

    private static final Set<String> JAVA_KEYWORDS = new HashSet<>(Arrays.asList(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
            "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
            "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
            "interface", "long", "native", "new", "package", "private", "protected", "public",
            "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
            "throw", "throws", "transient", "try", "void", "volatile", "while", "true", "false", "null",
            "var", "record", "yield", "sealed", "non-sealed", "permits", "when",
            "exports", "module", "opens", "provides", "requires", "to", "uses", "with", "transitive"));

    // ── Builtin Popular Classes ────────────────────────────────────────────────
    private static final String[][] BUILTIN_CLASSES = {
            {"System", "java.lang.System"},
            {"String", "java.lang.String"},
            {"Integer", "java.lang.Integer"},
            {"Double", "java.lang.Double"},
            {"Long", "java.lang.Long"},
            {"Boolean", "java.lang.Boolean"},
            {"Character", "java.lang.Character"},
            {"Math", "java.lang.Math"},
            {"Object", "java.lang.Object"},
            {"Class", "java.lang.Class"},
            {"Thread", "java.lang.Thread"},
            {"Runnable", "java.lang.Runnable"},
            {"Exception", "java.lang.Exception"},
            {"RuntimeException", "java.lang.RuntimeException"},
            {"NullPointerException", "java.lang.NullPointerException"},
            {"IllegalArgumentException", "java.lang.IllegalArgumentException"},
            {"StringBuilder", "java.lang.StringBuilder"},
            {"StringBuffer", "java.lang.StringBuffer"},
            {"Override", "java.lang.Override"},
            {"SuppressWarnings", "java.lang.SuppressWarnings"},
            {"Comparable", "java.lang.Comparable"},
            {"Iterable", "java.lang.Iterable"},
            {"Number", "java.lang.Number"},
            {"Void", "java.lang.Void"},
            {"Package", "java.lang.Package"},
            {"StackTraceElement", "java.lang.StackTraceElement"},
            {"AutoCloseable", "java.lang.AutoCloseable"},
            {"Cloneable", "java.lang.Cloneable"},
            {"Readable", "java.lang.Readable"},

            // java.util
            {"ArrayList", "java.util.ArrayList"},
            {"LinkedList", "java.util.LinkedList"},
            {"HashMap", "java.util.HashMap"},
            {"LinkedHashMap", "java.util.LinkedHashMap"},
            {"TreeMap", "java.util.TreeMap"},
            {"HashSet", "java.util.HashSet"},
            {"TreeSet", "java.util.TreeSet"},
            {"LinkedHashSet", "java.util.LinkedHashSet"},
            {"Collections", "java.util.Collections"},
            {"Arrays", "java.util.Arrays"},
            {"List", "java.util.List"},
            {"Map", "java.util.Map"},
            {"Set", "java.util.Set"},
            {"Queue", "java.util.Queue"},
            {"Deque", "java.util.Deque"},
            {"Stack", "java.util.Stack"},
            {"Vector", "java.util.Vector"},
            {"Hashtable", "java.util.Hashtable"},
            {"Date", "java.util.Date"},
            {"Calendar", "java.util.Calendar"},
            {"GregorianCalendar", "java.util.GregorianCalendar"},
            {"Locale", "java.util.Locale"},
            {"Random", "java.util.Random"},
            {"UUID", "java.util.UUID"},
            {"Optional", "java.util.Optional"},
            {"Objects", "java.util.Objects"},

            // java.io & nio
            {"File", "java.io.File"},
            {"InputStream", "java.io.InputStream"},
            {"OutputStream", "java.io.OutputStream"},
            {"FileInputStream", "java.io.FileInputStream"},
            {"FileOutputStream", "java.io.FileOutputStream"},
            {"BufferedReader", "java.io.BufferedReader"},
            {"BufferedWriter", "java.io.BufferedWriter"},
            {"FileReader", "java.io.FileReader"},
            {"FileWriter", "java.io.FileWriter"},
            {"PrintStream", "java.io.PrintStream"},
            {"PrintWriter", "java.io.PrintWriter"},
            {"ByteArrayOutputStream", "java.io.ByteArrayOutputStream"},
            {"ByteArrayInputStream", "java.io.ByteArrayInputStream"},
            {"IOException", "java.io.IOException"},
            {"Path", "java.nio.file.Path"},
            {"Paths", "java.nio.file.Paths"},
            {"Files", "java.nio.file.Files"},
            {"StandardCharsets", "java.nio.charset.StandardCharsets"},

            // java.time
            {"LocalDate", "java.time.LocalDate"},
            {"LocalTime", "java.time.LocalTime"},
            {"LocalDateTime", "java.time.LocalDateTime"},
            {"Instant", "java.time.Instant"},
            {"Duration", "java.time.Duration"},
            {"Period", "java.time.Period"},
            {"DateTimeFormatter", "java.time.DateTimeFormatter"},

            // java.util.concurrent
            {"ExecutorService", "java.util.concurrent.ExecutorService"},
            {"Executors", "java.util.concurrent.Executors"},
            {"Future", "java.util.concurrent.Future"},
            {"Callable", "java.util.concurrent.Callable"},
            {"ConcurrentHashMap", "java.util.concurrent.ConcurrentHashMap"},
            {"AtomicInteger", "java.util.concurrent.atomic.AtomicInteger"},
            {"AtomicLong", "java.util.concurrent.atomic.AtomicLong"},
            {"CountDownLatch", "java.util.concurrent.CountDownLatch"},
            {"Semaphore", "java.util.concurrent.Semaphore"},
            {"ReentrantLock", "java.util.concurrent.locks.ReentrantLock"}
    };

    private static final String[][] COMMON_SNIPPETS = {
            {"sout",  "System.out.println($1);",        "Print to stdout"},
            {"soutv", "System.out.println(\"$1 = \" + $1);", "Print variable with name"},
            {"serr",  "System.err.println($1);",        "Print to stderr"},
            {"printf", "System.out.printf(\"$1%n\", $2);", "Formatted print"},
            {"if",      "if ($1) {\n    $2\n}",                                   "If statement"},
            {"ife",     "if ($1) {\n    $2\n} else {\n    $3\n}",                 "If-else statement"},
            {"ifnn",    "if ($1 != null) {\n    $2\n}",                           "If not null check"},
            {"ifeq",    "if ($1.equals($2)) {\n    $3\n}",                        "If equals check"},
            {"while",   "while ($1) {\n    $2\n}",                                "While loop"},
            {"for",     "for ($1; $2; $3) {\n    $4\n}",                          "For loop"},
            {"fori",    "for (int ${1:i} = 0; ${1:i} < $2; ${1:i}++) {\n    $3\n}", "Indexed for loop"},
            {"foreach", "for (${1:Type} ${2:item} : ${3:iterable}) {\n    $4\n}",  "Enhanced for-each loop"},
            {"switch",  "switch ($1) {\n    case $2:\n        $3\n        break;\n    default:\n        break;\n}", "Switch statement"},
            {"try",     "try {\n    $1\n} catch (Exception ${2:e}) {\n    $3\n}",              "Try-catch block"},
            {"tryc",    "try {\n    $1\n} catch (Exception ${2:e}) {\n    $3\n}",              "Try-catch block"},
            {"tryf",    "try {\n    $1\n} finally {\n    $2\n}",                          "Try-finally block"},
            {"trycf",   "try {\n    $1\n} catch (Exception ${2:e}) {\n    $3\n} finally {\n    $4\n}",  "Try-catch-finally"},
            {"tryr",    "try ($1) {\n    $2\n}",                                  "Try-with-resources"},
            {"main",  "public static void main(String[] args) {\n    $1\n}",   "Main method"},
            {"psvm",  "public static void main(String[] args) {\n    $1\n}",   "public static void main"},
            {"cw",    "public class $1 {\n    $2\n}",                             "New public class"},
            {"ab",    "abstract class $1 {\n    $2\n}",                           "Abstract class"},
            {"itf",   "public interface $1 {\n    $2\n}",                         "Public interface"},
            {"enum",  "enum $1 {\n    $2\n}",                                     "Enum declaration"},
            {"ann",   "@interface $1 {\n    $2\n}",                               "Annotation interface"},
            {"ctor",  "public $1() {\n    $2\n}",                                "Constructor"},
            {"m",     "public void $1() {\n    $2\n}",                           "Public void method"},
            {"ms",    "public static void $1() {\n    $2\n}",                    "Static void method"},
            {"mf",    "public int $1() {\n    return $2;\n}",                            "Method returning int"},
            {"new",    "new $1($2)",                     "New object instance"},
            {"newa",   "new $1[$2]",                    "New array"},
            {"return", "return $1;",                   "Return statement"},
            {"throw",  "throw new $1($2);",              "Throw exception"},
            {"throws", "throws $1",                    "Throws declaration"},
            {"lambda",  "($1) -> {\n    $2\n}",                                                     "Lambda expression"},
            {"stream",  ".stream().filter($1).map($2).collect(Collectors.toList())",        "Stream pipeline"},
            {"list",   "new ArrayList<>()",          "New ArrayList"},
            {"map",    "new HashMap<>()",            "New HashMap"},
            {"set",    "new HashSet<>()",            "New HashSet"},
            {"coll",   "Collections.$1",               "Collections utility"},
            {"opt",   "Optional.ofNullable($1).orElse($2)",             "Optional wrap"},
            {"sync",       "synchronized ($1) {\n    $2\n}",                                              "Synchronized block"},
            {"inst",       "instanceof $1",                                                      "Instanceof check"},
            {"sleep",      "Thread.sleep($1);",                                                   "Thread sleep"},
            {"run",        "new Thread(() -> {\n    $1\n}).start();",                                    "New thread"},
            {"runnable",   "Runnable ${1:r} = () -> {\n    $2\n};",                                           "Runnable lambda"},
            {"ovr",  "@Override\n",                    "Override annotation"},
            {"dep",  "@Deprecated\n",                  "Deprecated annotation"},
            {"sup",  "@SuppressWarnings(\"$1\")\n",  "Suppress warnings"}
    };

    // ── Data Models ────────────────────────────────────────────────────────────

    public static final class MethodItem {
        public final String name;
        public final String returnType;
        public final String signatureLabel;
        public final int paramCount;
        public final boolean isStatic;
        public final String declaringClass;

        public MethodItem(String name, String returnType, String signatureLabel, int paramCount, boolean isStatic, String declaringClass) {
            this.name = name;
            this.returnType = returnType;
            this.signatureLabel = signatureLabel;
            this.paramCount = paramCount;
            this.isStatic = isStatic;
            this.declaringClass = declaringClass;
        }
    }

    public static final class FieldItem {
        public final String name;
        public final String type;
        public final boolean isStatic;
        public final String declaringClass;

        public FieldItem(String name, String type, boolean isStatic, String declaringClass) {
            this.name = name;
            this.type = type;
            this.isStatic = isStatic;
            this.declaringClass = declaringClass;
        }
    }

    public static final class ClassDeclaration {
        public final String simpleName;
        public final String packageName;
        public final String superClassName;
        public final List<String> interfaces = new ArrayList<>();
        public final List<MethodItem> methods = new ArrayList<>();
        public final List<FieldItem> fields = new ArrayList<>();

        public ClassDeclaration(String simpleName, String packageName, String superClassName) {
            this.simpleName = simpleName;
            this.packageName = packageName;
            this.superClassName = superClassName;
        }
    }

    private JavaReflectionCompletion() {}

    /**
     * Whether the caret sits after a {@code .}, completing a member.
     *
     * <p>Callers use this to leave the identifier and keyword providers out.
     * {@code rng.new} is not something anyone can write, but those providers do
     * not know about the dot, so their items were mixed in with the resolved
     * methods — and being added first, a keyword is what Tab accepted.</p>
     */
    public static boolean isMemberAccess(@NonNull ContentReference contentRef,
                                         @NonNull CharPosition position) {
        try {
            String line = contentRef.getLine(position.line);
            int col = Math.min(position.column, line.length());
            int i = col - 1;
            while (i >= 0 && Character.isJavaIdentifierPart(line.charAt(i))) i--;
            return i >= 0 && line.charAt(i) == '.';
        } catch (Exception e) {
            return false;
        }
    }

    public static void contribute(@NonNull Context appContext, @Nullable File projectRoot,
                                  @NonNull ContentReference contentRef, @NonNull CharPosition position,
                                  @NonNull String prefix, @NonNull CompletionPublisher publisher) {
        publisher.checkCancelled();
        ContentReference cref = contentRef;
        String fullSource = cref.getReference().toString();
        ClassLoader cl = appContext.getClassLoader();

        String line = cref.getLine(position.line);
        int col = Math.min(position.column, line.length());
        String beforeCursor = line.substring(0, col);
        int lastDot = beforeCursor.lastIndexOf('.');

        if (lastDot >= 0) {
            String beforeDot = beforeCursor.substring(0, lastDot).trim();
            String receiver = lastIdentifier(beforeDot);
            if (receiver != null && !receiver.isEmpty()) {
                List<String> imports = parseImports(fullSource);
                String pkg = parsePackage(fullSource);
                String typeName = findDeclaredType(fullSource, receiver);
                if (typeName == null) {
                    if (Character.isUpperCase(receiver.charAt(0))) {
                        typeName = receiver; // Static receiver, e.g. "Math", "Collections", "Lion"
                    }
                }

                if (typeName != null) {
                    addMemberCompletions(typeName, imports, pkg, fullSource, projectRoot, cl, prefix, publisher);
                }
            }
            return;
        }

        if (prefix.isEmpty()) return;

        // Members of the class being edited, inherited ones included.
        //
        // Only the "receiver." form used to reach member resolution, so a method
        // called with no receiver — which is how you call anything you inherit,
        // and anything of your own — was never offered. In a subclass of Animal,
        // getName() simply did not exist as far as completion was concerned.
        // The chain walk that finds it already existed; nothing called it here.
        String enclosing = enclosingTypeName(fullSource, offsetOf(cref, position));
        if (enclosing != null) {
            addMemberCompletions(enclosing, parseImports(fullSource), parsePackage(fullSource),
                    fullSource, projectRoot, cl, prefix, publisher);
        }
        publisher.checkCancelled();

        // Snippets
        addSnippetItems(prefix, publisher);
        publisher.checkCancelled();

        // Import-и: конкретні + wildcard-и
        addImportItems(importsFlat(fullSource), prefix, publisher);
        addWildcardImportItems(fullSource, prefix, publisher, cl);
        publisher.checkCancelled();

        // Проєктні класи
        if (projectRoot != null) {
            addProjectClassItems(projectRoot, prefix, publisher);
        }
        publisher.checkCancelled();

        // Maven dependency classes
        if (projectRoot != null) {
            addMavenDependencyItems(projectRoot, prefix, publisher);
        }
        publisher.checkCancelled();

        // Вбудовані Java-бібліотеки
        addBuiltinClassItems(prefix, publisher);
    }

    /**
     * Character offset of the cursor.
     *
     * <p>{@code CharPosition} carries one, but it is only filled in on positions
     * the editor built from an index; a position assembled from a line and column
     * leaves it at -1. Counting the lines is the fallback rather than trusting a
     * field that is sometimes a sentinel.</p>
     */
    private static int offsetOf(ContentReference cref, CharPosition position) {
        int index = position.index;
        if (index >= 0) return index;
        int offset = 0;
        for (int i = 0; i < position.line; i++) {
            offset += cref.getLine(i).length() + 1;
        }
        return offset + position.column;
    }

    // ── Member & Extended Hierarchy Resolution ────────────────────────────────

    private static void addMemberCompletions(
            String typeName,
            List<String> imports,
            String currentPkg,
            String currentSource,
            File projectRoot,
            ClassLoader cl,
            String prefix,
            CompletionPublisher pub) {

        pub.checkCancelled();
        String pl = prefix.toLowerCase(Locale.ROOT);

        Map<String, MethodItem> collectedMethods = new LinkedHashMap<>();
        Map<String, FieldItem> collectedFields = new LinkedHashMap<>();
        Set<String> visitedTypes = new HashSet<>();

        resolveMembersRecursive(typeName, imports, currentPkg, currentSource, projectRoot, cl, visitedTypes, collectedMethods, collectedFields);

        // Sort methods alphabetically by name
        List<MethodItem> methodList = new ArrayList<>(collectedMethods.values());
        methodList.sort((a, b) -> a.name.compareToIgnoreCase(b.name));

        int count = 0;
        for (MethodItem m : methodList) {
            pub.checkCancelled();
            if (!m.name.toLowerCase(Locale.ROOT).startsWith(pl)) continue;

            String insert = m.name + "(";
            SimpleCompletionItem it = new SimpleCompletionItem(m.signatureLabel, m.returnType, prefix.length(), insert);
            it.kind(CompletionItemKind.Method);
            pub.addItem(it);
            count++;
            if (count >= 150) break;
        }

        // Add fields if matching
        for (FieldItem f : collectedFields.values()) {
            pub.checkCancelled();
            if (!f.name.toLowerCase(Locale.ROOT).startsWith(pl)) continue;

            SimpleCompletionItem it = new SimpleCompletionItem(f.name, f.type, prefix.length(), f.name);
            it.kind(CompletionItemKind.Field);
            pub.addItem(it);
            count++;
            if (count >= 200) break;
        }
    }

    private static void resolveMembersRecursive(
            String typeName,
            List<String> imports,
            String currentPkg,
            String currentSource,
            File projectRoot,
            ClassLoader cl,
            Set<String> visited,
            Map<String, MethodItem> methodsOut,
            Map<String, FieldItem> fieldsOut) {

        if (typeName == null) return;
        typeName = stripGenerics(typeName);
        if (typeName.isEmpty() || !visited.add(typeName)) return;

        // 1. Try parsing class from current source buffer
        ClassDeclaration decl = null;
        if (currentSource != null) {
            decl = parseClassDeclarationFromSource(currentSource, typeName);
        }

        // 2. If not found in current buffer, search project .java files
        if (decl == null && projectRoot != null) {
            File sourceFile = findProjectSourceFile(projectRoot, typeName, imports, currentPkg);
            if (sourceFile != null && sourceFile.exists()) {
                try {
                    String source = new String(Files.readAllBytes(sourceFile.toPath()), StandardCharsets.UTF_8);
                    decl = parseClassDeclarationFromSource(source, typeName);
                } catch (Throwable ignored) {}
            }
        }

        // 3. If found in source code, add its members and follow its extends / implements chain
        if (decl != null) {
            for (MethodItem m : decl.methods) {
                String sig = m.name + "/" + m.paramCount;
                if (!methodsOut.containsKey(sig)) {
                    methodsOut.put(sig, m);
                }
            }
            for (FieldItem f : decl.fields) {
                if (!fieldsOut.containsKey(f.name)) {
                    fieldsOut.put(f.name, f);
                }
            }

            // Follow superclass (extends)
            if (decl.superClassName != null && !decl.superClassName.isEmpty()) {
                resolveMembersRecursive(decl.superClassName, imports, decl.packageName, null, projectRoot, cl, visited, methodsOut, fieldsOut);
            } else if (!"Object".equals(decl.simpleName) && !"java.lang.Object".equals(decl.simpleName)) {
                // Default implicit superclass is java.lang.Object
                resolveMembersRecursive("Object", imports, null, null, projectRoot, cl, visited, methodsOut, fieldsOut);
            }

            // Follow interfaces (implements)
            for (String iface : decl.interfaces) {
                resolveMembersRecursive(iface, imports, decl.packageName, null, projectRoot, cl, visited, methodsOut, fieldsOut);
            }
            return;
        }

        // 4. If not in project sources, resolve via reflection on runtime / SDK classpath
        Class<?> cls = resolveTypeToClass(typeName, imports, currentPkg, cl);
        if (cls != null) {
            Reflected reflected = reflect(cls);
            for (Map.Entry<String, MethodItem> e : reflected.methods.entrySet()) {
                methodsOut.putIfAbsent(e.getKey(), e.getValue());
            }
            for (Map.Entry<String, FieldItem> e : reflected.fields.entrySet()) {
                fieldsOut.putIfAbsent(e.getKey(), e.getValue());
            }
        }
    }

    /** Members of one loaded class, built once. */
    private static final class Reflected {
        final Map<String, MethodItem> methods;
        final Map<String, FieldItem> fields;
        Reflected(Map<String, MethodItem> methods, Map<String, FieldItem> fields) {
            this.methods = methods;
            this.fields = fields;
        }
    }

    /**
     * Reflected members of a class, remembered for the life of the process.
     *
     * <p>Completion runs on every keystroke, and {@link Class#getMethods()}
     * builds a fresh array each call — for {@code String} that is nearly a
     * hundred {@code Method} objects and their signature strings, rebuilt per
     * letter typed. A loaded class cannot gain members, so the answer is
     * computed once and shared.</p>
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, Reflected> REFLECTED =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static Reflected reflect(Class<?> cls) {
        Reflected hit = REFLECTED.get(cls.getName());
        if (hit != null) return hit;

        Map<String, MethodItem> methods = new LinkedHashMap<>();
        Map<String, FieldItem> fields = new LinkedHashMap<>();
        for (Method m : cls.getMethods()) {
            if (!Modifier.isPublic(m.getModifiers())) continue;
            String sig = m.getName() + "/" + m.getParameterCount();
            if (!methods.containsKey(sig)) {
                methods.put(sig, new MethodItem(m.getName(), formatReturnType(m),
                        formatReflectMethodLabel(m), m.getParameterCount(),
                        Modifier.isStatic(m.getModifiers()), cls.getSimpleName()));
            }
        }
        for (Field f : cls.getFields()) {
            if (!Modifier.isPublic(f.getModifiers())) continue;
            fields.putIfAbsent(f.getName(), new FieldItem(f.getName(),
                    simpleType(f.getType().getSimpleName()),
                    Modifier.isStatic(f.getModifiers()), cls.getSimpleName()));
        }
        Reflected built = new Reflected(java.util.Collections.unmodifiableMap(methods),
                java.util.Collections.unmodifiableMap(fields));
        REFLECTED.put(cls.getName(), built);
        return built;
    }

    /**
     * The project's Java sources, remembered for a few seconds.
     *
     * <p>{@link ProjectScanner#listJavaSources} walks the whole source tree, and
     * member resolution asks for it once per type it has to follow. Completion
     * runs on a keystroke, so on a large project that was a directory walk per
     * letter typed. The tree does not change between two letters; three seconds
     * is short enough that a newly created file shows up almost at once.</p>
     */
    private static final long SOURCES_TTL_MS = 3_000L;
    private static volatile String cachedSourcesRoot;
    private static volatile long cachedSourcesAt;
    private static volatile List<File> cachedSources;

    private static List<File> projectSources(File projectRoot) {
        String key = projectRoot.getAbsolutePath();
        // nanoTime, not SystemClock: this class is otherwise free of Android
        // types, which is what lets the resolution logic be tested on the JVM.
        long now = System.nanoTime() / 1_000_000L;
        List<File> hit = cachedSources;
        if (hit != null && key.equals(cachedSourcesRoot) && now - cachedSourcesAt < SOURCES_TTL_MS) {
            return hit;
        }
        List<File> fresh = ProjectScanner.listJavaSources(projectRoot);
        cachedSources = fresh;
        cachedSourcesRoot = key;
        cachedSourcesAt = now;
        return fresh;
    }

    /**
     * The type whose body contains {@code offset}, or null.
     *
     * <p>Used to answer "what can I call here with no receiver". The innermost
     * declaration that opens before the cursor is close enough: nesting deeper
     * than one level is rare in the files this runs on, and being wrong picks a
     * neighbouring class rather than failing.</p>
     */
    @Nullable
    static String enclosingTypeName(String source, int offset) {
        if (source == null || source.isEmpty()) return null;
        Matcher m = Pattern.compile(
                "\\b(?:class|interface|enum|record)\\s+([A-Za-z0-9_$]+)").matcher(source);
        String best = null;
        while (m.find()) {
            if (m.start() >= offset) break;
            best = m.group(1);
        }
        return best;
    }

    @Nullable
    private static File findProjectSourceFile(File projectRoot, String typeName, List<String> imports, String currentPkg) {
        if (projectRoot == null || !projectRoot.isDirectory()) return null;

        String simpleName = typeName;
        int dot = simpleName.lastIndexOf('.');
        if (dot >= 0) simpleName = simpleName.substring(dot + 1);

        String fileName = simpleName + ".java";
        List<File> sources = projectSources(projectRoot);

        // 1. Exact match in imported package or wildcard import
        for (String imp : imports) {
            if (imp.endsWith("." + simpleName)) {
                String relPath = imp.replace('.', File.separatorChar) + ".java";
                for (File f : sources) {
                    if (f.getAbsolutePath().endsWith(relPath)) return f;
                }
            }
            if (imp.endsWith(".*")) {
                String basePkg = imp.substring(0, imp.length() - 2);
                String relPath = basePkg.replace('.', File.separatorChar) + File.separator + fileName;
                for (File f : sources) {
                    if (f.getAbsolutePath().endsWith(relPath)) return f;
                }
            }
        }

        // 2. Same package
        if (currentPkg != null && !currentPkg.isEmpty()) {
            String relPath = currentPkg.replace('.', File.separatorChar) + File.separator + fileName;
            for (File f : sources) {
                if (f.getAbsolutePath().endsWith(relPath)) return f;
            }
        }

        // 3. Fallback: match by file name anywhere in project
        for (File f : sources) {
            if (f.getName().equals(fileName)) return f;
        }

        return null;
    }

    @Nullable
    static ClassDeclaration parseClassDeclarationFromSource(String source, String targetTypeName) {
        if (source == null || source.isEmpty()) return null;

        String pkg = parsePackage(source);
        String targetSimple = targetTypeName;
        int dot = targetSimple.lastIndexOf('.');
        if (dot >= 0) targetSimple = targetSimple.substring(dot + 1);

        Pattern classPattern = Pattern.compile(
                "\\b(?:class|interface|enum|record)\\s+([A-Za-z0-9_$]+)(?:<[^>]+>)?(?:\\s+extends\\s+([A-Za-z0-9_$.<>,\\s]+?))?(?:\\s+implements\\s+([A-Za-z0-9_$.<>,\\s]+?))?\\s*\\{");

        Matcher cm = classPattern.matcher(source);
        ClassDeclaration targetDecl = null;

        while (cm.find()) {
            String className = cm.group(1);
            if (targetSimple.isEmpty() || className.equals(targetSimple)) {
                String superName = cm.group(2);
                if (superName != null) {
                    superName = stripGenerics(superName.trim());
                }
                ClassDeclaration decl = new ClassDeclaration(className, pkg, superName);
                String ifaces = cm.group(3);
                if (ifaces != null) {
                    for (String iface : ifaces.split(",")) {
                        String clean = stripGenerics(iface.trim());
                        if (!clean.isEmpty()) decl.interfaces.add(clean);
                    }
                }
                targetDecl = decl;
                break;
            }
        }

        if (targetDecl == null) {
            // The type is not declared in this source. Saying so lets the caller
            // fall through to the project's files and then to reflection.
            //
            // This used to invent an empty declaration under the requested name
            // and then fill it with every method and field found in the buffer.
            // So "random." resolved java.util.Random to the class being edited
            // and offered its members — reflection was never reached, and no
            // library type ever completed.
            return null;
        }

        // Parse methods
        Pattern methodPattern = Pattern.compile(
                "(?:(?:public|protected|private|static|final|abstract|synchronized|default|native)\\s+)*"
                        + "(?:<[^>]+>\\s*)?"
                        + "([A-Za-z0-9_$.\\[\\]<>]+)\\s+([A-Za-z0-9_$]+)\\s*\\(([^)]*)\\)\\s*(?:throws\\s+[A-Za-z0-9_$,\\s]+)?\\s*[{;]");

        Matcher mm = methodPattern.matcher(source);
        while (mm.find()) {
            String returnType = mm.group(1).trim();
            String methodName = mm.group(2).trim();
            String rawParams = mm.group(3).trim();

            if (JAVA_KEYWORDS.contains(methodName) || "if".equals(methodName) || "while".equals(methodName)
                    || "for".equals(methodName) || "switch".equals(methodName) || "catch".equals(methodName)) {
                continue;
            }
            if (methodName.equals(targetDecl.simpleName)) continue; // skip constructor

            List<String> paramTypes = new ArrayList<>();
            List<String> paramNames = new ArrayList<>();
            if (!rawParams.isEmpty()) {
                for (String p : rawParams.split(",")) {
                    String part = p.trim().replaceAll("@[\\w.]+(?:\\([^)]*\\))?", "").replaceAll("\\bfinal\\b", "").trim();
                    if (part.isEmpty()) continue;
                    int space = part.lastIndexOf(' ');
                    if (space > 0) {
                        paramTypes.add(simpleType(part.substring(0, space).trim()));
                        paramNames.add(part.substring(space + 1).trim());
                    } else {
                        paramTypes.add(simpleType(part));
                        paramNames.add("");
                    }
                }
            }

            StringBuilder label = new StringBuilder();
            label.append(methodName).append('(');
            for (int i = 0; i < paramTypes.size(); i++) {
                if (i > 0) label.append(", ");
                label.append(paramTypes.get(i));
                if (i < paramNames.size() && !paramNames.get(i).isEmpty()) {
                    label.append(' ').append(paramNames.get(i));
                }
            }
            label.append(')');

            String fullMatch = mm.group(0);
            boolean isStatic = fullMatch.contains("static ");
            targetDecl.methods.add(new MethodItem(methodName, simpleType(returnType), label.toString(), paramTypes.size(), isStatic, targetDecl.simpleName));
        }

        // Parse fields
        Pattern fieldPattern = Pattern.compile(
                "(?:(?:public|protected|private|static|final|volatile|transient)\\s+)+"
                        + "([A-Za-z0-9_$.\\[\\]<>]+)\\s+([A-Za-z0-9_$]+)\\s*(?:=|;)");
        Matcher fm = fieldPattern.matcher(source);
        while (fm.find()) {
            String fieldType = fm.group(1).trim();
            String fieldName = fm.group(2).trim();
            if (!JAVA_KEYWORDS.contains(fieldName)) {
                boolean isStatic = fm.group(0).contains("static ");
                targetDecl.fields.add(new FieldItem(fieldName, simpleType(fieldType), isStatic, targetDecl.simpleName));
            }
        }

        return targetDecl;
    }

    private static String simpleType(String type) {
        if (type == null) return "";
        type = type.trim();
        int dot = type.lastIndexOf('.');
        if (dot >= 0) type = type.substring(dot + 1);
        return type;
    }

    // ── Helper Resolvers ───────────────────────────────────────────────────────

    @Nullable
    static String lastIdentifier(String expr) {
        if (expr == null) return null;
        expr = expr.trim();
        if (expr.isEmpty()) return null;
        int end = expr.length();
        int i = end - 1;
        while (i >= 0 && Character.isWhitespace(expr.charAt(i))) i--;
        end = i + 1;
        while (i >= 0 && (Character.isJavaIdentifierPart(expr.charAt(i)) || expr.charAt(i) == '.' || expr.charAt(i) == '[' || expr.charAt(i) == ']')) i--;
        String token = expr.substring(i + 1, end);
        int ld = token.lastIndexOf('.');
        if (ld >= 0) token = token.substring(ld + 1);
        int bracket = token.indexOf('[');
        if (bracket >= 0) token = token.substring(0, bracket);
        return token.trim();
    }

    @Nullable
    static String parsePackage(String source) {
        Matcher m = P_PACKAGE.matcher(source);
        return m.find() ? m.group(1) : null;
    }

    static List<String> parseImports(String source) {
        List<String> out = new ArrayList<>();
        Matcher m = P_IMPORT.matcher(source);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    @Nullable
    static String findDeclaredType(String source, String varName) {
        if ("this".equals(varName)) {
            Matcher m = Pattern.compile("\\bclass\\s+([A-Za-z0-9_$]+)").matcher(source);
            return m.find() ? m.group(1) : null;
        }
        if ("super".equals(varName)) {
            Matcher m = Pattern.compile("\\bclass\\s+[A-Za-z0-9_$]+(?:<[^>]+>)?\\s+extends\\s+([A-Za-z0-9_$.]+)").matcher(source);
            return m.find() ? stripGenerics(m.group(1)) : null;
        }

        // 1. Check var initialization: var name = new Type(...) or var name = expression
        Pattern varNewDecl = Pattern.compile(
                "\\bvar\\s+" + Pattern.quote(varName) + "\\s*=\\s*new\\s+([A-Za-z0-9_$.]+)");
        Matcher vm = varNewDecl.matcher(source);
        if (vm.find()) {
            return stripGenerics(vm.group(1));
        }

        // 2. Check standard variable/field/parameter declaration: Type name
        Pattern decl = Pattern.compile(
                "(?:^|[;{}\\n\\(,])\\s*(?:@[\\w.]+(?:\\([^)]*\\))?\\s+)*(?:public|private|protected|static|final|volatile|transient|\\s)*"
                        + "([A-Za-z_][\\w.]*(?:<[^>]+>)?(?:\\[\\])?)\\s+" + Pattern.quote(varName) + "\\b");
        Matcher m = decl.matcher(source);
        String last = null;
        while (m.find()) {
            String rawType = m.group(1).trim();
            if (rawType.endsWith("[]")) {
                rawType = rawType.substring(0, rawType.length() - 2).trim();
            }
            String t = stripGenerics(rawType);
            if (!JAVA_KEYWORDS.contains(t) && !isPrimitiveLike(t)) {
                last = t;
            }
        }
        return last;
    }

    private static boolean isPrimitiveLike(String t) {
        return "int".equals(t) || "long".equals(t) || "short".equals(t) || "byte".equals(t)
                || "char".equals(t) || "float".equals(t) || "double".equals(t) || "boolean".equals(t)
                || "void".equals(t);
    }

    static String stripGenerics(String t) {
        if (t == null) return null;
        int idx = t.indexOf('<');
        return idx > 0 ? t.substring(0, idx).trim() : t.trim();
    }

    @Nullable
    static Class<?> resolveTypeToClass(String typeName, List<String> imports, String pkg, ClassLoader cl) {
        if (typeName == null) return null;
        typeName = stripGenerics(typeName);
        if (typeName.isEmpty()) return null;

        if (typeName.contains(".")) {
            try {
                return Class.forName(typeName, false, cl);
            } catch (ClassNotFoundException | NoClassDefFoundError e) {
                return null;
            }
        }
        for (String imp : imports) {
            if (imp.endsWith("." + typeName)) {
                try { return Class.forName(imp, false, cl); }
                catch (ClassNotFoundException | NoClassDefFoundError ignored) {}
            }
            if (imp.endsWith(".*")) {
                String base = imp.substring(0, imp.length() - 1) + typeName;
                try { return Class.forName(base, false, cl); }
                catch (ClassNotFoundException | NoClassDefFoundError ignored) {}
            }
        }
        if (pkg != null && !pkg.isEmpty()) {
            try { return Class.forName(pkg + "." + typeName, false, cl); }
            catch (ClassNotFoundException | NoClassDefFoundError ignored) {}
        }
        try { return Class.forName("java.lang." + typeName, false, cl); }
        catch (ClassNotFoundException | NoClassDefFoundError ignored) {}
        try { return Class.forName("java.util." + typeName, false, cl); }
        catch (ClassNotFoundException | NoClassDefFoundError ignored) {}
        try { return Class.forName("java.io." + typeName, false, cl); }
        catch (ClassNotFoundException | NoClassDefFoundError ignored) {}

        return null;
    }

    private static String formatReflectMethodLabel(Method m) {
        StringBuilder sb = new StringBuilder();
        sb.append(m.getName()).append('(');
        Class<?>[] p = m.getParameterTypes();
        Parameter[] params = null;
        try { params = m.getParameters(); } catch (Throwable ignored) {}

        for (int i = 0; i < p.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(simpleParamType(p[i]));
            if (params != null && i < params.length && params[i].isNamePresent()) {
                sb.append(' ').append(params[i].getName());
            }
        }
        sb.append(')');
        return sb.toString();
    }

    private static String simpleParamType(Class<?> c) {
        if (c.isArray()) return simpleParamType(c.getComponentType()) + "[]";
        return c.getSimpleName();
    }

    private static String formatReturnType(Method m) {
        Class<?> r = m.getReturnType();
        if (r == void.class) return "void";
        if (r.isArray()) return simpleParamType(r.getComponentType()) + "[]";
        return r.getSimpleName();
    }

    // ── Snippets and Static Catalogues ─────────────────────────────────────────

    private static void addSnippetItems(String prefix, CompletionPublisher pub) {
        pub.checkCancelled();
        String pl = prefix.toLowerCase(Locale.ROOT);
        for (String[] snippet : COMMON_SNIPPETS) {
            if (snippet[0].startsWith(pl)) {
                try {
                    String desc = snippet.length > 2 ? snippet[2] : snippet[1];
                    SnippetDescription sDesc = new SnippetDescription(
                            prefix.length(),
                            CodeSnippetParser.parse(snippet[1]),
                            true
                    );
                    SimpleSnippetCompletionItem it = new SimpleSnippetCompletionItem(snippet[0], desc, sDesc);
                    it.kind(CompletionItemKind.Snippet);
                    pub.addItem(it);
                } catch (Exception ignored) {}
            }
        }
    }

    private static void addBuiltinClassItems(String prefix, CompletionPublisher pub) {
        pub.checkCancelled();
        String pl = prefix.toLowerCase(Locale.ROOT);
        int count = 0;
        for (String[] cls : BUILTIN_CLASSES) {
            if (cls[0].toLowerCase(Locale.ROOT).startsWith(pl)) {
                SimpleCompletionItem it = new SimpleCompletionItem(
                        cls[0], cls[1], prefix.length(), cls[0]);
                it.kind(CompletionItemKind.Class);
                pub.addItem(it);
                count++;
                if (count >= 60) break;
            }
        }
    }

    private static void addWildcardImportItems(String fullSource, String prefix,
                                                CompletionPublisher pub, ClassLoader cl) {
        pub.checkCancelled();
        String pl = prefix.toLowerCase(Locale.ROOT);
        List<String> wildcards = new ArrayList<>();
        Matcher m = P_IMPORT.matcher(fullSource);
        while (m.find()) {
            String imp = m.group(1);
            if (imp.endsWith(".*")) {
                wildcards.add(imp.substring(0, imp.length() - 2));
            }
        }
        if (wildcards.isEmpty()) return;

        Set<String> seen = new HashSet<>();
        int count = 0;
        for (String wcPkg : wildcards) {
            for (String[] cls : BUILTIN_CLASSES) {
                if (count >= 80) return;
                String fqName = cls[1];
                if (fqName.startsWith(wcPkg + ".")) {
                    String simple = cls[0];
                    if (!simple.toLowerCase(Locale.ROOT).startsWith(pl)) continue;
                    if (!seen.add(simple)) continue;
                    SimpleCompletionItem it = new SimpleCompletionItem(
                            simple, fqName, prefix.length(), simple);
                    it.kind(CompletionItemKind.Class);
                    pub.addItem(it);
                    count++;
                }
            }
            resolveWildcardPackage(wcPkg, prefix, pub, cl, seen);
        }
    }

    private static void addMavenDependencyItems(File projectRoot, String prefix,
                                                CompletionPublisher pub) {
        pub.checkCancelled();
        String pl = prefix.toLowerCase(Locale.ROOT);
        File repoDir = MavenPaths.localRepoDir(projectRoot);
        if (!repoDir.isDirectory()) return;

        File[] jars = repoDir.listFiles((dir, name) -> name.endsWith(".jar"));
        if (jars == null || jars.length == 0) return;

        Set<String> seen = new HashSet<>();
        int count = 0;
        for (File jar : jars) {
            if (count >= 100) return;
            pub.checkCancelled();
            try {
                JarFile jf = new JarFile(jar, true);
                Enumeration<JarEntry> entries = jf.entries();
                while (entries.hasMoreElements() && count < 100) {
                    JarEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (name.endsWith(".class") && !name.contains("$")
                            && !name.startsWith("META-INF/")) {
                        String fqn = name.substring(0, name.length() - 6).replace('/', '.');
                        int dot = fqn.lastIndexOf('.');
                        String simple = dot >= 0 ? fqn.substring(dot + 1) : fqn;
                        if (!simple.toLowerCase(Locale.ROOT).startsWith(pl)) continue;
                        if (!seen.add(fqn)) continue;
                        SimpleCompletionItem it = new SimpleCompletionItem(
                                simple + "  [" + jar.getName() + "]", fqn, prefix.length(), simple);
                        it.kind(CompletionItemKind.Class);
                        pub.addItem(it);
                        count++;
                    }
                }
                jf.close();
            } catch (IOException ignored) {}
        }
    }

    private static void resolveWildcardPackage(String pkgName, String prefix,
                                                CompletionPublisher pub, ClassLoader cl,
                                                Set<String> seen) {
        String[] subPkgs = {"", ".nio", ".nio.charset", ".nio.file",
                ".time", ".time.format", ".time.temporal", ".time.zone",
                ".concurrent", ".concurrent.atomic", ".concurrent.locks",
                ".function", ".stream", ".stream.collectors"};
        for (String sub : subPkgs) {
            String fullPkg = pkgName + sub;
            try {
                String[] probeClasses = {"List", "Map", "Set", "Collection", "Iterator",
                        "Optional", "Stream", "Collectors", "HashMap", "ArrayList",
                        "LinkedList", "TreeMap", "TreeSet", "HashSet",
                        "LinkedHashMap", "LinkedHashSet", "Arrays", "Collections",
                        "Objects", "Comparator", "Function", "Predicate", "Consumer",
                        "Supplier", "Runnable", "Callable", "Future",
                        "ExecutorService", "Executors", "ConcurrentHashMap",
                        "AtomicInteger", "AtomicLong", "ReentrantLock",
                        "Semaphore", "CountDownLatch", "LocalDate", "LocalTime",
                        "LocalDateTime", "Instant", "Duration", "Period",
                        "ZonedDateTime", "DateTimeFormatter", "ZoneId",
                        "ByteBuffer", "CharBuffer", "Files", "Path", "Paths",
                        "StandardCharsets", "Charset"};
                for (String probe : probeClasses) {
                    if (seen.contains(probe)) continue;
                    try {
                        Class<?> c = Class.forName(fullPkg + "." + probe, false, cl);
                        String simple = c.getSimpleName();
                        if (!simple.toLowerCase(Locale.ROOT).startsWith(
                                prefix.toLowerCase(Locale.ROOT))) continue;
                        if (!seen.add(simple)) continue;
                        SimpleCompletionItem it = new SimpleCompletionItem(
                                simple, c.getName(), prefix.length(), simple);
                        it.kind(CompletionItemKind.Class);
                        pub.addItem(it);
                    } catch (ClassNotFoundException ignored) {}
                }
            } catch (Throwable ignored) {}
        }
    }

    private static List<String> importsFlat(String source) {
        List<String> out = new ArrayList<>();
        Matcher m = P_IMPORT.matcher(source);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    private static void addImportItems(List<String> imports, String prefix, CompletionPublisher pub) {
        if (prefix.isEmpty()) return;
        pub.checkCancelled();
        Set<String> seen = new HashSet<>();
        String pl = prefix.toLowerCase(Locale.ROOT);
        for (String imp : imports) {
            if (imp.endsWith(".*")) continue;
            int dot = imp.lastIndexOf('.');
            String simple = dot >= 0 ? imp.substring(dot + 1) : imp;
            if (!simple.toLowerCase(Locale.ROOT).startsWith(pl)) continue;
            if (!seen.add(imp)) continue;
            SimpleCompletionItem it = new SimpleCompletionItem(
                    simple, imp, prefix.length(), simple);
            it.kind(CompletionItemKind.Class);
            pub.addItem(it);
            if (seen.size() > 80) break;
        }
    }

    private static void addProjectClassItems(File projectRoot, String prefix, CompletionPublisher pub) {
        pub.checkCancelled();
        if (prefix.isEmpty()) return;
        List<File> files = ProjectScanner.listJavaSources(projectRoot);
        String pl = prefix.toLowerCase(Locale.ROOT);
        Set<String> seen = new HashSet<>();
        for (File f : files) {
            pub.checkCancelled();
            try {
                String s = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                String pkg = parsePackage(s);
                Matcher mc = P_CLASS.matcher(s);
                while (mc.find()) {
                    String cn = mc.group(1);
                    String fq = (pkg == null || pkg.isEmpty()) ? cn : pkg + "." + cn;
                    if (!cn.toLowerCase(Locale.ROOT).startsWith(pl)
                            && !fq.toLowerCase(Locale.ROOT).startsWith(pl)) continue;
                    if (!seen.add(fq)) continue;
                    SimpleCompletionItem it = new SimpleCompletionItem(
                            fq, f.getName(), prefix.length(), cn);
                    it.kind(CompletionItemKind.Class);
                    pub.addItem(it);
                    if (seen.size() > 120) return;
                }
            } catch (IOException ignored) {}
        }
    }
}
