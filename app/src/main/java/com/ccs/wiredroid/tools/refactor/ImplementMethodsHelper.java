package com.ccs.wiredroid.tools.refactor;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Works out which methods a class still owes its interfaces and superclass.
 *
 * <p>Two ways of finding a supertype, tried in order. If it is declared
 * somewhere in the project, its source is read and the signatures are taken
 * exactly as written — generics, parameter names and {@code throws} clauses all
 * survive. If it is not (typically a JDK type such as {@code Comparator}), it is
 * loaded reflectively and its type parameters are substituted from the
 * declaration site, so {@code implements Comparator<String>} yields
 * {@code compare(String, String)} rather than the erased {@code Object} form.</p>
 */
public final class ImplementMethodsHelper {

    /** Packages tried when an unqualified supertype has no matching import. */
    private static final String[] IMPLICIT_PACKAGES = {
            "java.lang.", "java.util.", "java.util.function.", "java.util.concurrent.",
            "java.io.", "android.view.View$", "android.content."
    };

    private ImplementMethodsHelper() {
    }

    /** A method that has to be written, already rendered. */
    public static final class Stub {
        public final String name;
        public final String display;
        public final String code;

        Stub(String name, String display, String code) {
            this.name = name;
            this.display = display;
            this.code = code;
        }
    }

    /** Everything needed to place generated code inside the right type. */
    public static final class TypeDecl {
        public String name = "";
        public String kind = "class";
        public int headerStart = -1;
        public int bodyStart = -1;          // index just after '{'
        public int bodyEnd = -1;            // index of the matching '}'
        public final List<String> supertypes = new ArrayList<>();
        public final List<String> typeParams = new ArrayList<>();
    }

    // ── Entry point ─────────────────────────────────────────────────────────

    /**
     * @return the methods declared by supertypes of the first type in
     *         {@code source} that the type does not yet define, in declaration
     *         order; empty when it already satisfies them all
     */
    public static List<Stub> missingMethods(String source, File currentFile, File projectRoot) {
        String clean = blankOut(source);
        TypeDecl self = firstType(clean, source);
        if (self == null || self.supertypes.isEmpty()) return new ArrayList<>();

        Set<String> already = declaredSignatures(clean, self);
        Map<String, Stub> found = new LinkedHashMap<>();
        Set<String> visited = new HashSet<>();

        for (String supertype : self.supertypes) {
            collect(supertype, source, currentFile, projectRoot, already, found, visited, 0);
        }
        return new ArrayList<>(found.values());
    }

    /** The type the generated code belongs in — used to place the insertion. */
    public static TypeDecl primaryType(String source) {
        return firstType(blankOut(source), source);
    }

    // ── Walking the hierarchy ───────────────────────────────────────────────

    private static void collect(String usage, String contextSource, File currentFile, File projectRoot,
                                Set<String> already, Map<String, Stub> found,
                                Set<String> visited, int depth) {
        if (depth > 8) return;                          // parsed hierarchies can loop
        String rawName = erase(usage);
        String simple = simpleName(rawName);
        if (simple.isEmpty() || !visited.add(simple)) return;

        List<String> args = typeArguments(usage);

        // Declared in this very file (a nested or sibling type)?
        String declSource = contextSource;
        TypeDecl decl = findType(blankOut(contextSource), contextSource, simple);
        if (decl == null) {
            File file = locateSource(simple, projectRoot, currentFile);
            if (file != null) {
                String text = read(file);
                if (text != null) {
                    decl = findType(blankOut(text), text, simple);
                    declSource = text;
                }
            }
        }

        if (decl != null) {
            Map<String, String> subst = substitution(decl.typeParams, args);
            for (Signature s : abstractMethods(blankOut(declSource), decl)) {
                addStub(s.substituted(subst), already, found);
            }
            for (String parent : decl.supertypes) {
                collect(applySubstitution(parent, subst), declSource, currentFile, projectRoot,
                        already, found, visited, depth + 1);
            }
            return;
        }

        reflectively(simple, rawName, args, contextSource, already, found);
    }

    private static void addStub(Signature s, Set<String> already, Map<String, Stub> found) {
        String key = s.key();
        if (already.contains(key) || found.containsKey(key) || isInheritedFromObject(key)) return;
        found.put(key, new Stub(s.name, s.display(), s.render()));
    }

    /**
     * {@code Comparator} and friends redeclare {@code equals} as abstract, but
     * every class already inherits an implementation from {@code Object}, so
     * offering it would be noise.
     */
    private static boolean isInheritedFromObject(String key) {
        return key.equals("equals/1") || key.equals("hashCode/0") || key.equals("toString/0");
    }

    // ── Reflection fallback ─────────────────────────────────────────────────

    private static void reflectively(String simple, String rawName, List<String> args,
                                     String contextSource, Set<String> already,
                                     Map<String, Stub> found) {
        Class<?> cls = loadClass(simple, rawName, contextSource);
        if (cls == null) return;

        TypeVariable<?>[] vars = cls.getTypeParameters();
        Map<String, String> subst = new HashMap<>();
        for (int i = 0; i < vars.length && i < args.size(); i++) {
            subst.put(vars[i].getName(), args.get(i).trim());
        }

        List<Method> methods = new ArrayList<>(Arrays.asList(cls.getMethods()));
        methods.sort((a, b) -> a.getName().compareTo(b.getName()));
        for (Method m : methods) {
            if (!Modifier.isAbstract(m.getModifiers()) || m.isSynthetic()) continue;
            Signature s = new Signature();
            s.name = m.getName();
            s.returnType = render(m.getGenericReturnType(), subst);
            Type[] params = m.getGenericParameterTypes();
            for (int i = 0; i < params.length; i++) {
                s.paramTypes.add(render(params[i], subst));
                s.paramNames.add(argName(render(params[i], subst), i));
            }
            for (Class<?> ex : m.getExceptionTypes()) s.thrown.add(ex.getSimpleName());
            addStub(s, already, found);
        }
    }

    private static Class<?> loadClass(String simple, String rawName, String contextSource) {
        List<String> candidates = new ArrayList<>();
        if (rawName.contains(".")) candidates.add(rawName);
        // An explicit import wins over any guess.
        java.util.regex.Matcher im = java.util.regex.Pattern
                .compile("(?m)^\\s*import\\s+(?:static\\s+)?([\\w.$]*\\." + java.util.regex.Pattern.quote(simple) + ")\\s*;")
                .matcher(contextSource);
        if (im.find()) candidates.add(im.group(1));
        for (String pkg : IMPLICIT_PACKAGES) candidates.add(pkg + simple);

        for (String fqn : candidates) {
            try {
                return Class.forName(fqn, false, ImplementMethodsHelper.class.getClassLoader());
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** Renders a reflective type back to source form, applying type arguments. */
    private static String render(Type t, Map<String, String> subst) {
        if (t instanceof Class<?>) {
            Class<?> c = (Class<?>) t;
            if (c.isArray()) return render(c.getComponentType(), subst) + "[]";
            return c.getSimpleName();
        }
        if (t instanceof TypeVariable) {
            String n = ((TypeVariable<?>) t).getName();
            String mapped = subst.get(n);
            return mapped != null ? mapped : "Object";
        }
        if (t instanceof java.lang.reflect.ParameterizedType) {
            java.lang.reflect.ParameterizedType p = (java.lang.reflect.ParameterizedType) t;
            StringBuilder sb = new StringBuilder(render(p.getRawType(), subst)).append('<');
            Type[] as = p.getActualTypeArguments();
            for (int i = 0; i < as.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(render(as[i], subst));
            }
            return sb.append('>').toString();
        }
        if (t instanceof java.lang.reflect.GenericArrayType) {
            return render(((java.lang.reflect.GenericArrayType) t).getGenericComponentType(), subst) + "[]";
        }
        if (t instanceof java.lang.reflect.WildcardType) {
            Type[] upper = ((java.lang.reflect.WildcardType) t).getUpperBounds();
            return upper.length > 0 && upper[0] != Object.class ? render(upper[0], subst) : "Object";
        }
        return "Object";
    }

    // ── Source parsing ──────────────────────────────────────────────────────

    /** A parsed method signature, source-form throughout. */
    private static final class Signature {
        String name = "";
        String returnType = "void";
        final List<String> paramTypes = new ArrayList<>();
        final List<String> paramNames = new ArrayList<>();
        final List<String> thrown = new ArrayList<>();

        String key() {
            return name + "/" + paramTypes.size();
        }

        String display() {
            StringBuilder sb = new StringBuilder(name).append('(');
            for (int i = 0; i < paramTypes.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(simpleName(paramTypes.get(i)));
            }
            return sb.append(") : ").append(simpleName(returnType)).toString();
        }

        Signature substituted(Map<String, String> subst) {
            if (subst.isEmpty()) return this;
            Signature s = new Signature();
            s.name = name;
            s.returnType = applySubstitution(returnType, subst);
            for (String p : paramTypes) s.paramTypes.add(applySubstitution(p, subst));
            s.paramNames.addAll(paramNames);
            s.thrown.addAll(thrown);
            return s;
        }

        String render() {
            StringBuilder sb = new StringBuilder();
            sb.append("    @Override\n    public ").append(returnType).append(' ').append(name).append('(');
            for (int i = 0; i < paramTypes.size(); i++) {
                if (i > 0) sb.append(", ");
                String pn = i < paramNames.size() ? paramNames.get(i) : argName(paramTypes.get(i), i);
                sb.append(paramTypes.get(i)).append(' ').append(pn);
            }
            sb.append(')');
            if (!thrown.isEmpty()) sb.append(" throws ").append(String.join(", ", thrown));
            sb.append(" {\n        // TODO: implement ").append(name).append('\n');
            String def = defaultValue(returnType);
            if (def != null) sb.append("        return ").append(def).append(";\n");
            sb.append("    }\n");
            return sb.toString();
        }
    }

    private static String defaultValue(String type) {
        switch (type) {
            case "void": return null;
            case "boolean": return "false";
            case "char": return "'\\0'";
            case "byte": case "short": case "int": return "0";
            case "long": return "0L";
            case "float": return "0f";
            case "double": return "0d";
            default: return "null";
        }
    }

    private static String argName(String type, int index) {
        String base = simpleName(erase(type)).replace("[]", "");
        if (base.isEmpty() || !Character.isLetter(base.charAt(0))) return "arg" + index;
        String name = Character.toLowerCase(base.charAt(0)) + base.substring(1);
        if (isKeyword(name)) name = name + index;
        return index == 0 ? name : name + index;
    }

    private static boolean isKeyword(String s) {
        switch (s) {
            case "int": case "long": case "float": case "double": case "boolean":
            case "char": case "byte": case "short": case "class": case "void":
                return true;
            default:
                return false;
        }
    }

    /** Methods the type must supply: all of an interface, the abstract ones of a class. */
    private static List<Signature> abstractMethods(String clean, TypeDecl decl) {
        List<Signature> out = new ArrayList<>();
        if (decl.bodyStart < 0 || decl.bodyEnd <= decl.bodyStart) return out;

        int depth = 0;
        int memberStart = decl.bodyStart;
        for (int i = decl.bodyStart; i < decl.bodyEnd; i++) {
            char c = clean.charAt(i);
            if (c == '{') {
                if (depth == 0) {
                    // A body: this member is implemented, so skip past it.
                    int close = matchBrace(clean, i, decl.bodyEnd);
                    if (close < 0) break;
                    i = close;
                    memberStart = i + 1;
                    continue;
                }
                depth++;
            } else if (c == '}') {
                depth--;
            } else if (c == ';' && depth == 0) {
                Signature s = parseSignature(clean.substring(memberStart, i), decl);
                if (s != null) out.add(s);
                memberStart = i + 1;
            }
        }
        return out;
    }

    /**
     * Turns one member declaration into a signature, or returns null when it is
     * not a method that needs implementing (a field, a constant, a constructor,
     * or a {@code default}/{@code static} interface method with its own body).
     */
    private static Signature parseSignature(String text, TypeDecl owner) {
        String decl = text.trim();
        if (decl.isEmpty()) return null;
        int paren = decl.indexOf('(');
        if (paren < 0) return null;                                  // a field
        int close = matchParen(decl, paren);
        if (close < 0) return null;

        String head = decl.substring(0, paren).trim();
        if (head.contains("=")) return null;
        // Strip annotations, which may themselves carry parentheses.
        head = head.replaceAll("@[\\w.]+(\\([^)]*\\))?", " ").trim();

        boolean isInterface = "interface".equals(owner.kind);
        if (head.matches("(?s).*\\b(default|static|private)\\b.*")) return null;
        if (!isInterface && !head.matches("(?s).*\\babstract\\b.*")) return null;

        head = head.replaceAll("\\b(public|protected|abstract|final|native|synchronized|strictfp)\\b", " ").trim();
        head = head.replaceAll("^<[^>]*>", "").trim();               // a generic method's own params

        int lastSpace = lastTopLevelSpace(head);
        if (lastSpace <= 0) return null;                             // a constructor has no return type
        Signature s = new Signature();
        s.returnType = head.substring(0, lastSpace).trim().replaceAll("\\s+", " ");
        s.name = head.substring(lastSpace + 1).trim();
        if (s.name.isEmpty() || !Character.isJavaIdentifierStart(s.name.charAt(0))) return null;
        if (s.name.equals(owner.name)) return null;

        String params = decl.substring(paren + 1, close).trim();
        for (String p : splitTopLevel(params, ',')) {
            String param = p.trim().replaceAll("@[\\w.]+(\\([^)]*\\))?", " ")
                    .replaceAll("\\bfinal\\b", " ").trim();
            if (param.isEmpty()) continue;
            int sp = lastTopLevelSpace(param);
            if (sp <= 0) {
                s.paramTypes.add(param);
                s.paramNames.add(argName(param, s.paramTypes.size() - 1));
            } else {
                s.paramTypes.add(param.substring(0, sp).trim().replaceAll("\\s+", " "));
                s.paramNames.add(param.substring(sp + 1).trim());
            }
        }

        String tail = decl.substring(close + 1);
        java.util.regex.Matcher tm = java.util.regex.Pattern.compile("\\bthrows\\s+([^;{]+)").matcher(tail);
        if (tm.find()) {
            for (String ex : splitTopLevel(tm.group(1), ',')) {
                String e = ex.trim();
                if (!e.isEmpty()) s.thrown.add(e);
            }
        }
        return s;
    }

    /** Name and arity of every method the class already writes out. */
    private static Set<String> declaredSignatures(String clean, TypeDecl decl) {
        Set<String> out = new HashSet<>();
        if (decl.bodyStart < 0) return out;
        int depth = 0;
        int memberStart = decl.bodyStart;
        for (int i = decl.bodyStart; i < decl.bodyEnd && i < clean.length(); i++) {
            char c = clean.charAt(i);
            if (c == '{') {
                if (depth == 0) {
                    record(clean.substring(memberStart, i), out);
                    int close = matchBrace(clean, i, decl.bodyEnd);
                    if (close < 0) break;
                    i = close;
                    memberStart = i + 1;
                    continue;
                }
                depth++;
            } else if (c == '}') {
                depth--;
            } else if (c == ';' && depth == 0) {
                record(clean.substring(memberStart, i), out);
                memberStart = i + 1;
            }
        }
        return out;
    }

    private static void record(String text, Set<String> out) {
        String decl = text.trim().replaceAll("@[\\w.]+(\\([^)]*\\))?", " ").trim();
        int paren = decl.indexOf('(');
        if (paren < 0) return;
        int close = matchParen(decl, paren);
        if (close < 0) return;
        String head = decl.substring(0, paren).trim();
        int sp = lastTopLevelSpace(head);
        String name = sp < 0 ? head : head.substring(sp + 1).trim();
        if (name.isEmpty() || !Character.isJavaIdentifierStart(name.charAt(0))) return;
        int count = 0;
        String params = decl.substring(paren + 1, close).trim();
        if (!params.isEmpty()) count = splitTopLevel(params, ',').size();
        out.add(name + "/" + count);
    }

    // ── Type declarations ───────────────────────────────────────────────────

    private static TypeDecl firstType(String clean, String source) {
        return findType(clean, source, null);
    }

    /**
     * Locates a type declaration by name, or the first one when {@code want} is
     * null. Scanning rather than matching one regex, because the header runs
     * from the keyword to the opening brace and may contain arbitrary generics.
     */
    private static TypeDecl findType(String clean, String source, String want) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\\b(class|interface|enum|record)\\s+([\\w$]+)")
                .matcher(clean);
        while (m.find()) {
            String name = m.group(2);
            if (want != null && !want.equals(name)) continue;
            int brace = clean.indexOf('{', m.end());
            if (brace < 0) continue;
            // A record's parameter list sits before the brace; skip over it.
            TypeDecl d = new TypeDecl();
            d.kind = m.group(1);
            d.name = name;
            d.headerStart = m.start();
            d.bodyStart = brace + 1;
            d.bodyEnd = matchBrace(clean, brace, clean.length());
            if (d.bodyEnd < 0) d.bodyEnd = clean.length();

            String header = source.substring(m.end(), brace);
            parseHeader(header, d);
            return d;
        }
        return null;
    }

    private static void parseHeader(String header, TypeDecl d) {
        String h = header.trim();
        if (h.startsWith("<")) {
            int close = matchAngle(h, 0);
            if (close > 0) {
                for (String tp : splitTopLevel(h.substring(1, close), ',')) {
                    String name = tp.trim().split("\\s+")[0].trim();
                    if (!name.isEmpty()) d.typeParams.add(name);
                }
                h = h.substring(close + 1);
            }
        }
        // A record's component list is not a supertype clause.
        int recordParams = h.indexOf('(');
        if ("record".equals(d.kind) && recordParams >= 0) {
            int close = matchParen(h, recordParams);
            if (close > 0) h = h.substring(0, recordParams) + h.substring(close + 1);
        }

        java.util.regex.Matcher ext = java.util.regex.Pattern.compile("\\bextends\\b(.*?)(?=\\bimplements\\b|$)", java.util.regex.Pattern.DOTALL).matcher(h);
        if (ext.find()) {
            for (String s : splitTopLevel(ext.group(1), ',')) {
                String t = s.trim();
                if (!t.isEmpty()) d.supertypes.add(t);
            }
        }
        java.util.regex.Matcher imp = java.util.regex.Pattern.compile("\\bimplements\\b(.*)$", java.util.regex.Pattern.DOTALL).matcher(h);
        if (imp.find()) {
            for (String s : splitTopLevel(imp.group(1), ',')) {
                String t = s.trim();
                if (!t.isEmpty()) d.supertypes.add(t);
            }
        }
    }

    // ── Finding a supertype's source on disk ────────────────────────────────

    private static File locateSource(String simple, File projectRoot, File currentFile) {
        List<File> roots = new ArrayList<>();
        if (projectRoot != null && projectRoot.isDirectory()) roots.add(projectRoot);
        if (currentFile != null && currentFile.getParentFile() != null) roots.add(currentFile.getParentFile());
        for (File root : roots) {
            File hit = search(root, simple + ".java", 0);
            if (hit != null) return hit;
        }
        return null;
    }

    private static File search(File dir, String fileName, int depth) {
        if (depth > 12 || dir == null) return null;
        File[] children = dir.listFiles();
        if (children == null) return null;
        for (File f : children) {
            if (f.isFile() && f.getName().equals(fileName)) return f;
        }
        for (File f : children) {
            if (!f.isDirectory()) continue;
            String n = f.getName();
            if (n.equals("build") || n.equals(".git") || n.equals(".gradle") || n.startsWith(".")) continue;
            File hit = search(f, fileName, depth + 1);
            if (hit != null) return hit;
        }
        return null;
    }

    private static String read(File f) {
        try {
            if (f.length() > 2 * 1024 * 1024) return null;
            return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }

    // ── Small text utilities ────────────────────────────────────────────────

    /**
     * Replaces comment and string content with spaces.
     *
     * <p>Offsets are preserved, so an index found in the blanked text addresses
     * the same character in the original — that is what lets the scanners above
     * search structure here and read text there.</p>
     */
    public static String blankOut(String src) {
        char[] out = src.toCharArray();
        int i = 0, n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                while (i < n && src.charAt(i) != '\n') out[i++] = ' ';
            } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                out[i++] = ' ';
                out[i++] = ' ';
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

    private static int matchBrace(String s, int open, int limit) {
        int depth = 0;
        for (int i = open; i < Math.min(limit, s.length()); i++) {
            char c = s.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return i;
        }
        return -1;
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

    private static int matchAngle(String s, int open) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '<') depth++;
            else if (c == '>' && --depth == 0) return i;
        }
        return -1;
    }

    /** Splits on a separator that sits outside any generic or parameter nesting. */
    private static List<String> splitTopLevel(String s, char sep) {
        List<String> out = new ArrayList<>();
        int angle = 0, paren = 0, start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '<') angle++;
            else if (c == '>') angle--;
            else if (c == '(') paren++;
            else if (c == ')') paren--;
            else if (c == sep && angle == 0 && paren == 0) {
                out.add(s.substring(start, i));
                start = i + 1;
            }
        }
        if (start < s.length()) out.add(s.substring(start));
        return out;
    }

    /** The space separating a type from the name that follows it. */
    private static int lastTopLevelSpace(String s) {
        int angle = 0;
        int found = -1;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '<') angle++;
            else if (c == '>') angle--;
            else if (Character.isWhitespace(c) && angle == 0) found = i;
        }
        return found;
    }

    private static String erase(String type) {
        int lt = type.indexOf('<');
        return (lt < 0 ? type : type.substring(0, lt)).trim();
    }

    private static String simpleName(String type) {
        String t = erase(type);
        int dot = t.lastIndexOf('.');
        return dot < 0 ? t : t.substring(dot + 1);
    }

    private static List<String> typeArguments(String usage) {
        int lt = usage.indexOf('<');
        if (lt < 0) return new ArrayList<>();
        int close = matchAngle(usage, lt);
        if (close < 0) return new ArrayList<>();
        List<String> out = new ArrayList<>();
        for (String s : splitTopLevel(usage.substring(lt + 1, close), ',')) out.add(s.trim());
        return out;
    }

    private static Map<String, String> substitution(List<String> params, List<String> args) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < params.size() && i < args.size(); i++) m.put(params.get(i), args.get(i));
        return m;
    }

    private static String applySubstitution(String type, Map<String, String> subst) {
        if (subst.isEmpty()) return type;
        String out = type;
        for (Map.Entry<String, String> e : subst.entrySet()) {
            out = out.replaceAll("\\b" + java.util.regex.Pattern.quote(e.getKey()) + "\\b",
                    java.util.regex.Matcher.quoteReplacement(e.getValue()));
        }
        return out;
    }
}
