package com.ccs.wiredroid.ui;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A brace-depth scan that is just good enough to name the method the caret is
 * sitting in, and to list the members of the file for the breadcrumb dropdown.
 *
 * <p>This is not a parser and must not become one: it tracks nesting depth,
 * skips comments and string literals, and matches declaration lines with a
 * couple of regexes. Java and Kotlin both go through the same pass — Kotlin
 * declarations simply start with {@code fun} / {@code val} / {@code var}.</p>
 */
public final class MemberOutline {

    private MemberOutline() {}

    /** What sort of declaration this is. Declared in "sort by kind" order. */
    public enum Kind { FIELD, CONSTRUCTOR, METHOD }

    /** Declared access. Declared widest-first, which is "sort by visibility" order. */
    public enum Visibility { PUBLIC, PROTECTED, PACKAGE_PRIVATE, PRIVATE }

    /** A declaration worth showing in the breadcrumb dropdown. */
    public static final class Member {
        /** Display label, e.g. {@code onCreate()} or {@code tabsAdapter}. */
        public final String label;
        /** Declaration line, 0-based. */
        public final int line;
        /** Last line of the body, 0-based inclusive. Equals {@link #line} for fields. */
        public int endLine;
        public final boolean method;
        public final Kind kind;
        /**
         * Access as written. A missing keyword means package-private in Java,
         * public in Kotlin and public inside a Java interface.
         */
        public final Visibility visibility;

        Member(String label, int line, Kind kind, Visibility visibility) {
            this.label = label;
            this.line = line;
            this.endLine = line;
            this.kind = kind;
            this.visibility = visibility;
            // Constructors counted as methods before kinds existed, and the
            // breadcrumb's enclosing() still leans on that meaning.
            this.method = kind != Kind.FIELD;
        }
    }

    /** Files past this many lines are skipped — the outline is not worth the scan. */
    private static final int MAX_LINES = 40000;

    private static final Pattern JAVA_METHOD = Pattern.compile(
            "^\\s*(?:@\\w+(?:\\([^)]*\\))?\\s+)*"
                    + "(?:(?:public|protected|private|static|final|abstract|synchronized"
                    + "|native|default|strictfp|transient)\\s+)*"
                    + "(?:<[^<>]*>\\s*)?"
                    + "(?:[\\w$.<>:&*\\[\\],?\\s]+?\\s+)?"
                    + "([\\w$]+)\\s*\\(");

    private static final Pattern KOTLIN_METHOD = Pattern.compile(
            "^\\s*(?:@\\w+(?:\\([^)]*\\))?\\s+)*"
                    + "(?:(?:public|private|protected|internal|open|override|abstract|final"
                    + "|inline|suspend|operator|infix|tailrec|external)\\s+)*"
                    + "fun\\s+(?:<[^<>]*>\\s*)?(?:[\\w$.<>]+\\.)?([\\w$]+)\\s*\\(");

    private static final Pattern KOTLIN_FIELD = Pattern.compile(
            "^\\s*(?:@\\w+(?:\\([^)]*\\))?\\s+)*"
                    + "(?:(?:public|private|protected|internal|open|override|const|lateinit"
                    + "|abstract|final)\\s+)*"
                    + "(?:val|var)\\s+([\\w$]+)\\b");

    private static final Pattern JAVA_FIELD = Pattern.compile(
            "^\\s*(?:@\\w+(?:\\([^)]*\\))?\\s+)*"
                    + "(?:(?:public|protected|private|static|final|volatile|transient)\\s+)+"
                    + "[\\w$.<>:&*\\[\\],?\\s]+?\\s+([\\w$]+)\\s*(?:=[^;]*)?;\\s*$");

    /**
     * A type declaration. Never emitted as a member — it is matched only so the
     * scan can tell whether unqualified members are implicitly public.
     */
    private static final Pattern TYPE_DECL = Pattern.compile(
            "^\\s*(?:@\\w+(?:\\([^)]*\\))?\\s+)*"
                    + "(?:(?:public|protected|private|static|final|abstract|sealed|non-sealed"
                    + "|strictfp|open|internal|data|inner|annotation|value)\\s+)*"
                    + "(@?interface|class|enum|record|object)\\s+[\\w$]");

    /** Type parameters sit between the modifiers and the name; they are not a return type. */
    private static final Pattern TYPE_PARAMS = Pattern.compile("<[^<>]*>");

    /** Java keywords that may precede a name without being its return type. */
    private static final List<String> JAVA_MODIFIERS = java.util.Arrays.asList(
            "public", "protected", "private", "static", "final", "abstract",
            "synchronized", "native", "default", "strictfp", "transient");

    /** Statement keywords that look like a call but never start a declaration. */
    private static final List<String> NOT_A_NAME = java.util.Arrays.asList(
            "if", "for", "while", "switch", "catch", "synchronized", "return",
            "new", "else", "do", "try", "throw", "assert", "super", "this",
            "when", "yield", "record");

    /** True when the extension is one this scanner understands. */
    public static boolean supports(String fileNameLower) {
        return fileNameLower != null
                && (fileNameLower.endsWith(".java") || fileNameLower.endsWith(".kt")
                || fileNameLower.endsWith(".kts") || fileNameLower.endsWith(".ino")
                || fileNameLower.endsWith(".cpp") || fileNameLower.endsWith(".c") || fileNameLower.endsWith(".h")
                || fileNameLower.endsWith(".hpp") || fileNameLower.endsWith(".cc") || fileNameLower.endsWith(".cxx"));
    }

    private static boolean isKotlin(String fileNameLower) {
        return fileNameLower != null
                && (fileNameLower.endsWith(".kt") || fileNameLower.endsWith(".kts"));
    }

    /**
     * Scans the whole buffer. Intended to be called off the main thread.
     *
     * @return declarations in source order, never null
     */
    public static List<Member> scan(CharSequence text, String fileNameLower) {
        if (text == null || text.length() == 0) return Collections.emptyList();
        try {
            return scanUnsafe(text, isKotlin(fileNameLower));
        } catch (Throwable ignored) {
            return Collections.emptyList();
        }
    }

    private static List<Member> scanUnsafe(CharSequence text, boolean kotlin) {
        String[] lines = splitLines(text);
        if (lines.length > MAX_LINES) return Collections.emptyList();

        List<Member> out = new ArrayList<>();
        Deque<Open> open = new ArrayDeque<>();

        int depth = 0;
        boolean inBlockComment = false;
        // Interface members carry no access keyword and are public regardless.
        boolean topLevelInterface = false;

        for (int i = 0; i < lines.length; i++) {
            String raw = lines[i];
            int depthAtStart = depth;
            boolean wasInComment = inBlockComment;

            // Strip comments and literals so braces inside them do not count.
            State st = new State(depth, inBlockComment);
            String code = stripNonCode(raw, st);
            depth = st.depth;
            inBlockComment = st.inBlockComment;

            String trimmed = wasInComment && inBlockComment ? "" : code.trim();
            if (!trimmed.isEmpty()) {
                // Only depth 0 matters: members are collected no deeper than
                // depth 1, so they all belong to the outermost type.
                if (depthAtStart == 0) {
                    Matcher td = TYPE_DECL.matcher(trimmed);
                    if (td.find() && td.start() == 0) {
                        topLevelInterface = td.group(1).endsWith("interface");
                    }
                }
                Member m = match(trimmed, i, kotlin, depthAtStart, topLevelInterface);
                if (m != null) {
                    // A declaration with no body (abstract or interface method)
                    // ends where the next declaration begins.
                    while (!open.isEmpty() && !open.peek().entered
                            && open.peek().declDepth >= depthAtStart) {
                        Open stale = open.pop();
                        stale.member.endLine = Math.max(stale.member.line, i - 1);
                    }
                    out.add(m);
                    if (m.method) open.push(new Open(m, depthAtStart));
                }
            }

            // Close every body whose closing brace landed on this line.
            closeFinished(open, depth, i);
        }

        // Anything still open runs to the end of the file.
        while (!open.isEmpty()) {
            open.pop().member.endLine = lines.length - 1;
        }
        return out;
    }

    private static void closeFinished(Deque<Open> open, int depth, int line) {
        while (!open.isEmpty()) {
            Open top = open.peek();
            if (!top.entered) {
                if (depth > top.declDepth) top.entered = true;
                else break;
            }
            if (top.entered && depth <= top.declDepth) {
                open.pop().member.endLine = line;
            } else {
                break;
            }
        }
    }

    private static final class Open {
        final Member member;
        final int declDepth;
        boolean entered;

        Open(Member member, int declDepth) {
            this.member = member;
            this.declDepth = declDepth;
        }
    }

    /**
     * A class body sits at depth 1 — anything deeper is a statement inside a
     * method, or a member of an anonymous inner class. Neither belongs in a
     * breadcrumb that claims to list "the members of this class".
     */
    private static final int MAX_METHOD_DEPTH = 1;
    private static final int MAX_FIELD_DEPTH = 1;

    private static Member match(String raw, int line, boolean kotlin, int depth,
                                boolean topLevelInterface) {
        String trimmed = stripLeadingAnnotations(raw);
        if (trimmed.isEmpty()) return null;
        if (trimmed.charAt(0) == '}') return null;
        if (NOT_A_NAME.contains(firstToken(trimmed))) return null;

        boolean implicitlyPublic = topLevelInterface && depth == MAX_METHOD_DEPTH;

        if (kotlin) {
            if (depth <= MAX_METHOD_DEPTH) {
                Matcher km = KOTLIN_METHOD.matcher(trimmed);
                if (km.find() && km.start() == 0) {
                    return new Member(km.group(1) + "()", line, Kind.METHOD,
                            visibilityOf(trimmed.substring(0, km.start(1)), true, true));
                }
            }
            if (depth <= MAX_FIELD_DEPTH) {
                Matcher kf = KOTLIN_FIELD.matcher(trimmed);
                if (kf.find() && kf.start() == 0) {
                    return new Member(kf.group(1), line, Kind.FIELD,
                            visibilityOf(trimmed.substring(0, kf.start(1)), true, true));
                }
            }
            return null;
        }

        if (depth <= MAX_METHOD_DEPTH) {
            Matcher jm = JAVA_METHOD.matcher(trimmed);
            if (jm.find() && jm.start() == 0) {
                String name = jm.group(1);
                String prefix = trimmed.substring(0, jm.start(1));
                boolean bare = jm.start(1) == 0;          // no return type or modifier
                if (!NOT_A_NAME.contains(name)
                        && looksLikeSignature(trimmed)
                        && (!bare || looksLikeConstructor(trimmed))) {
                    Kind kind = hasNoReturnType(prefix) ? Kind.CONSTRUCTOR : Kind.METHOD;
                    return new Member(name + "()", line, kind,
                            visibilityOf(prefix, false, implicitlyPublic));
                }
            }
        }
        if (depth <= MAX_FIELD_DEPTH) {
            Matcher jf = JAVA_FIELD.matcher(trimmed);
            if (jf.find() && jf.start() == 0) {
                return new Member(jf.group(1), line, Kind.FIELD,
                        visibilityOf(trimmed.substring(0, jf.start(1)), false, implicitlyPublic));
            }
        }
        return null;
    }

    /**
     * Reads the access keyword out of the text ahead of a declaration's name.
     *
     * <p>The declaration patterns already step over these modifiers, but adding
     * capturing groups to them would renumber the groups {@link #match} relies
     * on, so the prefix is re-read here as plain tokens instead. Whole tokens
     * only — a type named {@code private_cache} is not the keyword.</p>
     */
    private static Visibility visibilityOf(String prefix, boolean kotlin,
                                           boolean implicitlyPublic) {
        int i = 0;
        int n = prefix.length();
        while (i < n) {
            if (!isWordChar(prefix.charAt(i))) {
                i++;
                continue;
            }
            int start = i;
            while (i < n && isWordChar(prefix.charAt(i))) i++;
            String word = prefix.substring(start, i);
            if ("public".equals(word)) return Visibility.PUBLIC;
            if ("protected".equals(word)) return Visibility.PROTECTED;
            if ("private".equals(word)) return Visibility.PRIVATE;
            // Kotlin's module-wide access has no Java twin; package-private is
            // the nearest neighbour and keeps it out of the public bucket.
            if (kotlin && "internal".equals(word)) return Visibility.PACKAGE_PRIVATE;
        }
        return implicitlyPublic ? Visibility.PUBLIC : Visibility.PACKAGE_PRIVATE;
    }

    /**
     * True when nothing but modifiers and type parameters precede the name — a
     * Java declaration with no return type, which is to say a constructor.
     */
    private static boolean hasNoReturnType(String prefix) {
        String rest = TYPE_PARAMS.matcher(stripLeadingAnnotations(prefix.trim()))
                .replaceAll(" ");
        int i = 0;
        int n = rest.length();
        while (i < n) {
            if (!isWordChar(rest.charAt(i))) {
                i++;
                continue;
            }
            int start = i;
            while (i < n && isWordChar(rest.charAt(i))) i++;
            if (!JAVA_MODIFIERS.contains(rest.substring(start, i))) return false;
        }
        return true;
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    /** Drops any {@code @Annotation} / {@code @Annotation(...)} prefixes. */
    private static String stripLeadingAnnotations(String s) {
        String cur = s;
        while (!cur.isEmpty() && cur.charAt(0) == '@') {
            int i = 1;
            int n = cur.length();
            while (i < n && (Character.isLetterOrDigit(cur.charAt(i))
                    || cur.charAt(i) == '_' || cur.charAt(i) == '$' || cur.charAt(i) == '.')) {
                i++;
            }
            if (i < n && cur.charAt(i) == '(') {
                int nest = 0;
                while (i < n) {
                    char c = cur.charAt(i);
                    if (c == '(') nest++;
                    else if (c == ')' && --nest == 0) { i++; break; }
                    i++;
                }
            }
            String next = cur.substring(Math.min(i, cur.length())).trim();
            if (next.equals(cur)) break;
            cur = next;
        }
        return cur;
    }

    /** The leading identifier of a line, or "" when it does not start with one. */
    private static String firstToken(String trimmed) {
        int i = 0;
        int n = trimmed.length();
        while (i < n && (Character.isLetterOrDigit(trimmed.charAt(i))
                || trimmed.charAt(i) == '_' || trimmed.charAt(i) == '$')) {
            i++;
        }
        return trimmed.substring(0, i);
    }

    /**
     * A declaration line ends in a body brace, a {@code throws} clause, an
     * abstract {@code ;}, or a dangling parameter list. A call statement ends
     * with {@code ;} right after the closing paren — reject those.
     */
    private static boolean looksLikeSignature(String trimmed) {
        String end = trimmed.trim();
        if (end.endsWith("{")) return true;
        if (end.endsWith(",")) return true;
        if (end.endsWith("(")) return true;
        if (end.endsWith(")")) return true;
        if (end.contains(" throws ")) return true;
        // "void foo();" — abstract or interface method. "foo();" — a call.
        return end.endsWith(";") && end.indexOf(' ') >= 0 && end.indexOf(' ') < end.indexOf('(');
    }

    /**
     * A line that starts straight at {@code name(} is either a package-private
     * constructor or — far more often — a chained call such as
     * {@code newRoundedDialog()}. The declaration keeps its parameter list open
     * across the line break, or opens its body on the same line; the call does
     * neither.
     */
    private static boolean looksLikeConstructor(String trimmed) {
        int open = 0;
        int close = 0;
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c == '(') open++;
            else if (c == ')') close++;
        }
        if (open > close) return true;                       // parameters continue below
        String end = trimmed.trim();
        return end.endsWith("{") || end.contains(" throws ");
    }

    /** @return the innermost method containing {@code caretLine}, or null */
    public static Member enclosing(List<Member> members, int caretLine) {
        if (members == null || members.isEmpty()) return null;
        Member best = null;
        for (Member m : members) {
            if (!m.method) continue;
            if (m.line <= caretLine && caretLine <= m.endLine) {
                if (best == null || m.line >= best.line) best = m;
            }
        }
        return best;
    }

    // ── Lexing helpers ────────────────────────────────────────

    private static final class State {
        int depth;
        boolean inBlockComment;

        State(int depth, boolean inBlockComment) {
            this.depth = depth;
            this.inBlockComment = inBlockComment;
        }
    }

    /**
     * Removes comments and literals from one line and folds its braces into
     * {@code st.depth}. Returns the remaining code with literals blanked out.
     */
    private static String stripNonCode(String line, State st) {
        StringBuilder sb = new StringBuilder(line.length());
        int i = 0;
        int n = line.length();
        while (i < n) {
            char c = line.charAt(i);
            if (st.inBlockComment) {
                if (c == '*' && i + 1 < n && line.charAt(i + 1) == '/') {
                    st.inBlockComment = false;
                    i += 2;
                } else {
                    i++;
                }
                continue;
            }
            if (c == '/' && i + 1 < n && line.charAt(i + 1) == '/') break;
            if (c == '/' && i + 1 < n && line.charAt(i + 1) == '*') {
                st.inBlockComment = true;
                i += 2;
                continue;
            }
            if (c == '"' || c == '\'') {
                char quote = c;
                i++;
                while (i < n) {
                    char d = line.charAt(i);
                    if (d == '\\') {
                        i += 2;
                        continue;
                    }
                    i++;
                    if (d == quote) break;
                }
                sb.append(' ');
                continue;
            }
            if (c == '{') st.depth++;
            else if (c == '}') st.depth = Math.max(0, st.depth - 1);
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    private static String[] splitLines(CharSequence text) {
        List<String> lines = new ArrayList<>();
        int start = 0;
        int n = text.length();
        for (int i = 0; i < n; i++) {
            if (text.charAt(i) == '\n') {
                lines.add(text.subSequence(start, i).toString());
                start = i + 1;
                if (lines.size() > MAX_LINES) return lines.toArray(new String[0]);
            }
        }
        lines.add(text.subSequence(start, n).toString());
        return lines.toArray(new String[0]);
    }
}
