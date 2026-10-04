package com.ccs.wiredroid.uml;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The classes of a project and how they relate, parsed from source.
 *
 * <p>Regex, not a parser. A real one would need the whole type system to resolve
 * anything, and this only has to be right about declarations — the shape of a
 * class header, its fields and its method signatures. Bodies are skipped
 * entirely, which is what keeps the field and method patterns from matching
 * local variables and calls.</p>
 */
public final class UmlGraph {

    /** What the box header shows, and how it is badged. */
    public enum Kind { CLASS, ABSTRACT, INTERFACE, ENUM, RECORD }

    /** How two boxes are joined. */
    public enum Link {
        /** {@code extends} — solid line, hollow arrowhead. */
        EXTENDS,
        /** {@code implements} — dashed line, hollow arrowhead. */
        IMPLEMENTS,
        /** A field whose type is another class here — diamond at the owner. */
        ASSOCIATION
    }

    public static final class Member {
        public final String name;
        public final String type;
        public final boolean method;

        Member(String name, String type, boolean method) {
            this.name = name;
            this.type = type;
            this.method = method;
        }
    }

    public static final class Type {
        public final String name;
        public final Kind kind;
        public final File file;
        public final List<Member> members = new ArrayList<>();
        /** Raw names as written; resolved against the project in {@link #relations}. */
        final List<String> supertypes = new ArrayList<>();
        final Map<String, String> fieldTypes = new LinkedHashMap<>();

        // Filled by the layout pass.
        public float x, y, width, height;
        /** Inheritance depth; roots are 0. */
        public int layer;

        Type(String name, Kind kind, File file) {
            this.name = name;
            this.kind = kind;
            this.file = file;
        }
    }

    public static final class Relation {
        public final Type from;
        public final Type to;
        public final Link link;
        /** Shown near the target end: {@code "1"} or {@code "*"}. Never null. */
        public final String multiplicity;

        Relation(Type from, Type to, Link link, String multiplicity) {
            this.from = from;
            this.to = to;
            this.link = link;
            this.multiplicity = multiplicity;
        }
    }

    // ── Patterns ────────────────────────────────────────────────────────────

    private static final Pattern TYPE_DECL = Pattern.compile(
            "(?m)^[ \\t]*(?:@\\w+(?:\\([^)]*\\))?[ \\t]*)*"
                    + "(?:public|protected|private)?[ \\t]*(?:static[ \\t]+)?(?:final[ \\t]+)?"
                    + "(?:(abstract)[ \\t]+)?(?:sealed[ \\t]+|non-sealed[ \\t]+)?"
                    + "(class|interface|enum|record)[ \\t]+([A-Za-z_$][\\w$]*)"
                    + "(?:[ \\t]*<[^>]*>)?(?:[ \\t]*\\([^)]*\\))?"
                    + "(?:[ \\t]+extends[ \\t]+([^{]+?))?"
                    + "(?:[ \\t]+implements[ \\t]+([^{]+?))?[ \\t]*\\{");

    /** Kotlin: {@code class Foo : Bar(), Baz}. */
    private static final Pattern KT_DECL = Pattern.compile(
            "(?m)^[ \\t]*(?:@\\w+[ \\t]*)*(?:public|internal|private|protected)?[ \\t]*"
                    + "(?:(abstract|open|sealed|data)[ \\t]+)?(class|interface|object)[ \\t]+"
                    + "([A-Za-z_][\\w]*)(?:[ \\t]*<[^>]*>)?(?:[ \\t]*\\([^)]*\\))?"
                    + "(?:[ \\t]*:[ \\t]*([^{]+))?");

    private static final Pattern FIELD = Pattern.compile(
            "(?m)^[ \\t]*(?:@\\w+(?:\\([^)]*\\))?[ \\t]*)*"
                    + "(public|protected|private)[ \\t]+(?:static[ \\t]+)?(?:final[ \\t]+)?(?:volatile[ \\t]+|transient[ \\t]+)?"
                    + "([A-Za-z_$][\\w$.]*(?:[ \\t]*<[^;=()]*>)?(?:[ \\t]*\\[[ \\t]*\\])*)[ \\t]+"
                    + "([A-Za-z_$][\\w$]*)[ \\t]*(?:=[^;]*)?;");

    private static final Pattern METHOD = Pattern.compile(
            "(?m)^[ \\t]*(?:@\\w+(?:\\([^)]*\\))?[ \\t]*)*"
                    + "(public|protected|private)[ \\t]+(?:static[ \\t]+)?(?:final[ \\t]+|abstract[ \\t]+|synchronized[ \\t]+|default[ \\t]+)*"
                    + "(?:<[^>]+>[ \\t]*)?"
                    + "([A-Za-z_$][\\w$.]*(?:[ \\t]*<[^(]*>)?(?:[ \\t]*\\[[ \\t]*\\])*)[ \\t]+"
                    + "([A-Za-z_$][\\w$]*)[ \\t]*\\([^)]*\\)[^;{]*[;{]");

    /** Collection types whose element type carries the real association. */
    private static final Pattern COLLECTION = Pattern.compile(
            "^(?:java\\.util\\.)?(List|ArrayList|Set|HashSet|LinkedHashSet|Collection|Queue|Deque"
                    + "|Iterable|Map|HashMap|LinkedHashMap|TreeMap)[ \\t]*<(.+)>$");

    private final Map<String, Type> types = new LinkedHashMap<>();
    private final List<Relation> relations = new ArrayList<>();

    private UmlGraph() {
    }

    public Collection<Type> types() {
        return types.values();
    }

    public List<Relation> relations() {
        return relations;
    }

    // ── Building one by hand ────────────────────────────────────────────────
    //
    // The parser above is one producer of a graph; a database schema is
    // another. Both draw through the same layout and view, so anything that can
    // describe boxes and the lines between them can reuse the whole stack.

    /** An empty graph, to be filled with {@link #addType} and {@link #addRelation}. */
    public static UmlGraph empty() {
        return new UmlGraph();
    }

    /**
     * Parses a PlantUML script into a visual graph.
     */
    public static UmlGraph fromPlantUml(String plantUml) {
        if (plantUml == null) return empty();
        UmlGraph g = new UmlGraph();

        // 1. Strip block comments /' ... '/ and line comments ' ...
        String cleaned = plantUml.replaceAll("/'[\\s\\S]*?'/", "")
                .replaceAll("(?m)^[ \\t]*'[^\\n]*", "");

        // 2. Match type definitions:
        Pattern typeDefPattern = Pattern.compile(
                "(?m)^[ \\t]*(?:(abstract)[ \\t]+)?(class|interface|enum|record)[ \\t]+([A-Za-z0-9_]+)"
                        + "(?:[ \\t]+extends[ \\t]+([A-Za-z0-9_.]+))?"
                        + "(?:[ \\t]+implements[ \\t]+([A-Za-z0-9_, \\t]+))?"
                        + "(?:[ \\t]*\\{([\\s\\S]*?)\\})?");

        Matcher tm = typeDefPattern.matcher(cleaned);
        while (tm.find()) {
            boolean isAbs = tm.group(1) != null;
            String kindStr = tm.group(2).toLowerCase(Locale.ROOT);
            String typeName = tm.group(3);
            Kind kind = isAbs ? Kind.ABSTRACT :
                    "interface".equals(kindStr) ? Kind.INTERFACE :
                    "enum".equals(kindStr) ? Kind.ENUM :
                    "record".equals(kindStr) ? Kind.RECORD : Kind.CLASS;

            Type t = g.addType(typeName, kind);
            String extendsClause = tm.group(4);
            if (extendsClause != null && !extendsClause.trim().isEmpty()) {
                g.addSupertypes(t, extendsClause.trim());
            }
            String implClause = tm.group(5);
            if (implClause != null && !implClause.trim().isEmpty()) {
                g.addSupertypes(t, implClause.trim());
            }

            String body = tm.group(6);
            if (body != null) {
                String[] lines = body.split("\\r?\\n");
                for (String line : lines) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("'") || line.equals("{") || line.equals("}")) continue;
                    if (line.startsWith("+") || line.startsWith("-") || line.startsWith("#") || line.startsWith("~")) {
                        line = line.substring(1).trim();
                    }
                    if (line.isEmpty()) continue;

                    if (line.contains("(") && line.contains(")")) {
                        if (line.contains(":")) {
                            String[] parts = line.split(":", 2);
                            String name = parts[0].trim();
                            String ret = parts.length > 1 ? parts[1].trim() : "void";
                            t.members.add(new Member(name, ret, true));
                        } else {
                            int parenIdx = line.indexOf('(');
                            String beforeParen = line.substring(0, parenIdx).trim();
                            int spaceIdx = beforeParen.lastIndexOf(' ');
                            if (spaceIdx > 0) {
                                String ret = beforeParen.substring(0, spaceIdx).trim();
                                String name = beforeParen.substring(spaceIdx + 1) + line.substring(parenIdx);
                                t.members.add(new Member(name, ret, true));
                            } else {
                                t.members.add(new Member(line, "void", true));
                            }
                        }
                    } else {
                        if (line.contains(":")) {
                            String[] parts = line.split(":", 2);
                            String name = parts[0].trim();
                            String ftype = parts.length > 1 ? parts[1].trim() : "Object";
                            t.members.add(new Member(name, ftype, false));
                            t.fieldTypes.put(name, ftype);
                        } else {
                            int spaceIdx = line.lastIndexOf(' ');
                            if (spaceIdx > 0) {
                                String ftype = line.substring(0, spaceIdx).trim();
                                String name = line.substring(spaceIdx + 1).replace(";", "").trim();
                                t.members.add(new Member(name, ftype, false));
                                t.fieldTypes.put(name, ftype);
                            } else {
                                t.members.add(new Member(line.replace(";", ""), "", false));
                            }
                        }
                    }
                }
            }
        }

        // 3. Standalone relations
        Pattern relPattern = Pattern.compile(
                "(?m)^[ \\t]*([A-Za-z0-9_]+)[ \\t]*(?:\"([^\"]*)\")?[ \\t]*"
                        + "(--\\|>|<\\|--|\\.\\.\\|>|<\\|\\.\\.|-->|<--|--|\\*--|--\\*|o--|--o)[ \\t]*"
                        + "(?:\"([^\"]*)\")?[ \\t]*([A-Za-z0-9_]+)(?:[ \\t]*:[ \\t]*([^\\n]*))?");

        Matcher rm = relPattern.matcher(cleaned);
        while (rm.find()) {
            String left = rm.group(1);
            String leftMult = rm.group(2) != null ? rm.group(2) : "";
            String arrow = rm.group(3);
            String rightMult = rm.group(4) != null ? rm.group(4) : "";
            String right = rm.group(5);

            Type leftType = g.types.get(left);
            if (leftType == null) leftType = g.addType(left, Kind.CLASS);
            Type rightType = g.types.get(right);
            if (rightType == null) rightType = g.addType(right, Kind.CLASS);

            if (arrow.equals("--|>")) {
                g.addRelation(leftType, rightType, Link.EXTENDS, rightMult);
            } else if (arrow.equals("<|--")) {
                g.addRelation(rightType, leftType, Link.EXTENDS, leftMult);
            } else if (arrow.equals("..|>")) {
                g.addRelation(leftType, rightType, Link.IMPLEMENTS, rightMult);
            } else if (arrow.equals("<|..")) {
                g.addRelation(rightType, leftType, Link.IMPLEMENTS, leftMult);
            } else if (arrow.equals("-->") || arrow.equals("--") || arrow.equals("*--") || arrow.equals("o--")) {
                g.addRelation(leftType, rightType, Link.ASSOCIATION, rightMult.isEmpty() ? leftMult : rightMult);
            } else if (arrow.equals("<--") || arrow.equals("--*") || arrow.equals("--o")) {
                g.addRelation(rightType, leftType, Link.ASSOCIATION, leftMult.isEmpty() ? rightMult : leftMult);
            }
        }

        g.link();
        return g;
    }

    /**
     * Adds a box, or returns the existing one of that name.
     *
     * <p>Names are the identity here, exactly as they are for parsed types.</p>
     */
    public Type addType(String name, Kind kind) {
        Type existing = types.get(name);
        if (existing != null) return existing;
        Type t = new Type(name, kind, null);
        types.put(name, t);
        return t;
    }

    /** Adds a row inside a box: a field when {@code method} is false. */
    public void addMember(Type owner, String name, String type, boolean method) {
        if (owner != null) owner.members.add(new Member(name, type, method));
    }

    /** Joins two boxes. Both must already belong to this graph. */
    public void addRelation(Type from, Type to, Link link, String multiplicity) {
        if (from == null || to == null || from == to) return;
        relations.add(new Relation(from, to, link, multiplicity == null ? "" : multiplicity));
    }

    /**
     * Parses every source file under {@code root}.
     *
     * @param maxTypes stop after this many; a large repository is unreadable as a
     *                 diagram long before it is slow to draw, so the cap is a
     *                 legibility limit rather than a performance one
     */
    public static UmlGraph scan(File root, int maxTypes) {
        UmlGraph g = new UmlGraph();
        List<File> files = new ArrayList<>();
        collect(root, files);
        for (File f : files) {
            if (g.types.size() >= maxTypes) break;
            try {
                String src = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                if (f.getName().endsWith(".kt")) g.parseKotlin(src, f);
                else g.parseJava(src, f);
            } catch (Exception ignored) {
                // An unreadable or malformed file costs its own box, nothing more.
            }
        }
        g.link();
        return g;
    }

    private static void collect(File dir, List<File> out) {
        if (dir == null) return;
        if (dir.isDirectory()) {
            String n = dir.getName();
            if (n.equals("build") || n.equals("target") || n.equals("out")
                    || n.equals("bin") || n.startsWith(".")) return;
            File[] kids = dir.listFiles();
            if (kids != null) for (File k : kids) collect(k, out);
        } else {
            String n = dir.getName();
            if ((n.endsWith(".java") || n.endsWith(".kt"))
                    && !n.equals("package-info.java") && !n.equals("module-info.java")) {
                out.add(dir);
            }
        }
    }

    // ── Parsing ─────────────────────────────────────────────────────────────

    private void parseJava(String src, File file) {
        String code = stripCommentsAndStrings(src);
        Matcher m = TYPE_DECL.matcher(code);
        while (m.find()) {
            String kw = m.group(2);
            Kind kind = "interface".equals(kw) ? Kind.INTERFACE
                    : "enum".equals(kw) ? Kind.ENUM
                    : "record".equals(kw) ? Kind.RECORD
                    : m.group(1) != null ? Kind.ABSTRACT : Kind.CLASS;
            Type t = new Type(m.group(3), kind, file);
            addSupertypes(t, m.group(4));
            addSupertypes(t, m.group(5));

            // Members are taken from this declaration's body only, so a nested or
            // sibling type in the same file cannot donate its members to this box.
            String body = bodyAfter(code, m.end() - 1);
            Matcher fm = FIELD.matcher(body);
            while (fm.find()) {
                String type = normalise(fm.group(2));
                t.members.add(new Member(fm.group(3), type, false));
                t.fieldTypes.put(fm.group(3), type);
            }
            Matcher mm = METHOD.matcher(body);
            while (mm.find()) {
                String ret = normalise(mm.group(2));
                if (ret.equals(t.name)) continue;   // a constructor, not a method
                t.members.add(new Member(mm.group(3) + "()", ret, true));
            }
            types.putIfAbsent(t.name, t);
        }
    }

    private void parseKotlin(String src, File file) {
        String code = stripCommentsAndStrings(src);
        Matcher m = KT_DECL.matcher(code);
        while (m.find()) {
            String mod = m.group(1);
            String kw = m.group(2);
            Kind kind = "interface".equals(kw) ? Kind.INTERFACE
                    : "abstract".equals(mod) || "sealed".equals(mod) ? Kind.ABSTRACT
                    : Kind.CLASS;
            Type t = new Type(m.group(3), kind, file);
            addSupertypes(t, m.group(4));
            types.putIfAbsent(t.name, t);
        }
    }

    private void addSupertypes(Type t, String raw) {
        if (raw == null) return;
        for (String part : splitTopLevel(raw)) {
            String s = part.trim().replaceAll("<.*>", "").replaceAll("\\(.*\\)", "");
            int dot = s.lastIndexOf('.');
            if (dot >= 0) s = s.substring(dot + 1);
            if (!s.isEmpty()) t.supertypes.add(s);
        }
    }

    /** Splits on commas that are not inside generic brackets. */
    private static List<String> splitTopLevel(String raw) {
        List<String> out = new ArrayList<>();
        int depth = 0, start = 0;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '<' || c == '(') depth++;
            else if (c == '>' || c == ')') depth--;
            else if (c == ',' && depth == 0) {
                out.add(raw.substring(start, i));
                start = i + 1;
            }
        }
        out.add(raw.substring(start));
        return out;
    }

    /** Body between the brace at {@code open} and its match. */
    private static String bodyAfter(String code, int open) {
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return code.substring(open + 1, i);
            }
        }
        return code.substring(Math.min(open + 1, code.length()));
    }

    /**
     * Blanks out comments and string literals.
     *
     * <p>Replaced with spaces rather than removed so that every offset in the
     * result still refers to the same character in the original — {@link
     * #bodyAfter} counts braces by index and would drift otherwise. Without this
     * a brace inside a string closes a class body early.</p>
     */
    private static String stripCommentsAndStrings(String src) {
        char[] out = src.toCharArray();
        int i = 0, n = out.length;
        while (i < n) {
            char c = out[i];
            if (c == '/' && i + 1 < n && out[i + 1] == '/') {
                while (i < n && out[i] != '\n') out[i++] = ' ';
            } else if (c == '/' && i + 1 < n && out[i + 1] == '*') {
                while (i < n && !(out[i] == '*' && i + 1 < n && out[i + 1] == '/')) {
                    if (out[i] != '\n') out[i] = ' ';
                    i++;
                }
                if (i < n) out[i++] = ' ';
                if (i < n) out[i++] = ' ';
            } else if (c == '"' || c == '\'') {
                char quote = c;
                out[i++] = ' ';
                while (i < n && out[i] != quote) {
                    if (out[i] == '\\' && i + 1 < n) out[i++] = ' ';
                    if (i < n && out[i] != '\n') out[i] = ' ';
                    i++;
                }
                if (i < n) out[i++] = ' ';
            } else {
                i++;
            }
        }
        return new String(out);
    }

    private static String normalise(String type) {
        return type.replaceAll("\\s+", "").replaceAll("^.*\\.", "");
    }

    // ── Relations ───────────────────────────────────────────────────────────

    private void link() {
        Set<String> seen = new LinkedHashSet<>();
        for (Type t : types.values()) {
            for (String sup : t.supertypes) {
                Type target = types.get(sup);
                if (target == null || target == t) continue;   // outside the project
                Link kind = target.kind == Kind.INTERFACE ? Link.IMPLEMENTS : Link.EXTENDS;
                if (seen.add(t.name + ">" + target.name + kind)) {
                    relations.add(new Relation(t, target, kind, ""));
                }
            }
        }
        for (Type t : types.values()) {
            for (Map.Entry<String, String> e : t.fieldTypes.entrySet()) {
                String raw = e.getValue();
                String elem = raw;
                String mult = "1";
                if (elem.endsWith("[]")) {
                    elem = elem.substring(0, elem.length() - 2);
                    mult = "*";
                } else {
                    Matcher cm = COLLECTION.matcher(elem);
                    if (cm.matches()) {
                        // For a Map the value type is the interesting end.
                        String args = cm.group(2);
                        List<String> parts = splitTopLevel(args);
                        elem = normalise(parts.get(parts.size() - 1));
                        mult = "*";
                    }
                }
                Type target = types.get(elem);
                if (target == null || target == t) continue;
                // An inheritance edge already says these two are related; a field
                // of the parent's type on top of it would just be a second line
                // between the same pair.
                if (hasInheritance(t, target)) continue;
                if (seen.add(t.name + "~" + target.name)) {
                    relations.add(new Relation(t, target, Link.ASSOCIATION, mult));
                }
            }
        }
    }

    private boolean hasInheritance(Type a, Type b) {
        for (Relation r : relations) {
            if (r.link == Link.ASSOCIATION) continue;
            if ((r.from == a && r.to == b) || (r.from == b && r.to == a)) return true;
        }
        return false;
    }

    /** Badge letter for the header: C, I, A, E or R. */
    public static String badge(Kind kind) {
        switch (kind) {
            case INTERFACE: return "I";
            case ABSTRACT:  return "A";
            case ENUM:      return "E";
            case RECORD:    return "R";
            default:        return "C";
        }
    }

    public static String kindLabel(Kind kind) {
        return kind.name().toLowerCase(Locale.ROOT);
    }
}
