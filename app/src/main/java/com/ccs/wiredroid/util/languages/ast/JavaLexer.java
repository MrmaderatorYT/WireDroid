package com.ccs.wiredroid.util.languages.ast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A complete Java lexer — the foundation the parser and highlighter sit on.
 *
 * <p>Unlike a regex scanner it tracks lexical state properly, so string escapes,
 * text blocks, nested-looking comments, and numeric literals with underscores or
 * hex exponents all tokenise correctly. Unterminated literals at end of file
 * produce a token rather than an error, because the editor is constantly looking
 * at half-typed code.</p>
 */
public final class JavaLexer {

    private static final Set<String> KEYWORDS = new HashSet<>(Arrays.asList(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
            "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
            "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
            "interface", "long", "native", "new", "package", "private", "protected", "public",
            "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
            "throw", "throws", "transient", "try", "void", "volatile", "while",
            // Literals are keywords for colouring purposes.
            "true", "false", "null",
            // Contextual keywords (Java 9+). Treated as keywords only where the
            // parser confirms the context; see JavaAstParser.
            "var", "record", "sealed", "permits", "yield", "non-sealed"
    ));

    /**
     * Contextual keywords that are ordinary identifiers elsewhere — a variable
     * may legally be called {@code record} or {@code yield}.
     */
    private static final Set<String> CONTEXTUAL = new HashSet<>(Arrays.asList(
            "var", "record", "sealed", "permits", "yield"
    ));

    /** Multi-character operators, longest first so greedy matching works. */
    private static final String[] OPERATORS = {
            ">>>=", "<<=", ">>=", ">>>", "...",
            "->", "::", "++", "--", "&&", "||", "==", "!=", "<=", ">=",
            "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "<<", ">>",
            "+", "-", "*", "/", "%", "=", "<", ">", "!", "~", "?", ":",
            "&", "|", "^", ";", ",", ".", "(", ")", "[", "]", "{", "}", "@"
    };

    private final CharSequence src;
    private final int length;
    private int pos;
    private int line;
    private int lineStart;

    public JavaLexer(CharSequence source) {
        this.src = source;
        this.length = source.length();
    }

    /** True when the word is a Java keyword (including contextual ones). */
    public static boolean isKeyword(String word) {
        return KEYWORDS.contains(word);
    }

    /** True when the word is only a keyword in certain positions. */
    public static boolean isContextualKeyword(String word) {
        return CONTEXTUAL.contains(word);
    }

    /**
     * Tokenises the whole input. Whitespace is skipped and produces no tokens;
     * the returned list always ends with an {@link JavaToken.Kind#EOF} token.
     */
    public List<JavaToken> tokenize() {
        List<JavaToken> out = new ArrayList<>(Math.max(16, length / 6));
        pos = 0;
        line = 0;
        lineStart = 0;

        while (pos < length) {
            char c = src.charAt(pos);

            if (c == '\n') {
                pos++;
                line++;
                lineStart = pos;
                continue;
            }
            if (c == ' ' || c == '\t' || c == '\r' || c == '\f') {
                pos++;
                continue;
            }

            if (c == '/' && pos + 1 < length) {
                char next = src.charAt(pos + 1);
                if (next == '/') { out.add(lineComment()); continue; }
                if (next == '*') { out.add(blockComment()); continue; }
            }

            if (c == '"') {
                boolean textBlock = pos + 2 < length
                        && src.charAt(pos + 1) == '"' && src.charAt(pos + 2) == '"';
                out.add(textBlock ? textBlock() : stringLiteral());
                continue;
            }

            if (c == '\'') { out.add(charLiteral()); continue; }

            if (Character.isDigit(c)
                    || (c == '.' && pos + 1 < length && Character.isDigit(src.charAt(pos + 1)))) {
                out.add(numberLiteral());
                continue;
            }

            if (Character.isJavaIdentifierStart(c)) { out.add(word()); continue; }

            out.add(operator());
        }

        out.add(new JavaToken(JavaToken.Kind.EOF, "", length, length, line, length - lineStart));
        return out;
    }

    // ─── Token readers ──────────────────────────────────────────────────────

    private JavaToken lineComment() {
        int start = pos, startLine = line, startCol = pos - lineStart;
        while (pos < length && src.charAt(pos) != '\n') pos++;
        return token(JavaToken.Kind.LINE_COMMENT, start, startLine, startCol);
    }

    private JavaToken blockComment() {
        int start = pos, startLine = line, startCol = pos - lineStart;
        boolean javadoc = pos + 2 < length && src.charAt(pos + 2) == '*'
                // `/**/` is an empty comment, not Javadoc.
                && !(pos + 3 < length && src.charAt(pos + 3) == '/');
        pos += 2;
        while (pos < length) {
            char c = src.charAt(pos);
            if (c == '\n') { pos++; line++; lineStart = pos; continue; }
            if (c == '*' && pos + 1 < length && src.charAt(pos + 1) == '/') { pos += 2; break; }
            pos++;
        }
        return token(javadoc ? JavaToken.Kind.JAVADOC : JavaToken.Kind.BLOCK_COMMENT,
                start, startLine, startCol);
    }

    private JavaToken stringLiteral() {
        int start = pos, startLine = line, startCol = pos - lineStart;
        pos++; // opening quote
        while (pos < length) {
            char c = src.charAt(pos);
            if (c == '\\' && pos + 1 < length) {
                if (src.charAt(pos + 1) == '\n') { pos += 2; line++; lineStart = pos; continue; }
                pos += 2;
                continue;
            }
            if (c == '\n') break;            // unterminated — stop at the line end
            pos++;
            if (c == '"') break;
        }
        return token(JavaToken.Kind.STRING, start, startLine, startCol);
    }

    private JavaToken textBlock() {
        int start = pos, startLine = line, startCol = pos - lineStart;
        pos += 3; // opening """
        while (pos < length) {
            char c = src.charAt(pos);
            if (c == '\\' && pos + 1 < length) { pos += 2; continue; }
            if (c == '\n') { pos++; line++; lineStart = pos; continue; }
            if (c == '"' && pos + 2 < length
                    && src.charAt(pos + 1) == '"' && src.charAt(pos + 2) == '"') {
                pos += 3;
                break;
            }
            pos++;
        }
        return token(JavaToken.Kind.TEXT_BLOCK, start, startLine, startCol);
    }

    private JavaToken charLiteral() {
        int start = pos, startLine = line, startCol = pos - lineStart;
        pos++; // opening quote
        while (pos < length) {
            char c = src.charAt(pos);
            if (c == '\\' && pos + 1 < length) { pos += 2; continue; }
            if (c == '\n') break;
            pos++;
            if (c == '\'') break;
        }
        return token(JavaToken.Kind.CHAR, start, startLine, startCol);
    }

    private JavaToken numberLiteral() {
        int start = pos, startLine = line, startCol = pos - lineStart;
        if (src.charAt(pos) == '0' && pos + 1 < length) {
            char radix = src.charAt(pos + 1);
            if (radix == 'x' || radix == 'X') { pos += 2; readHex(); return finishNumber(start, startLine, startCol); }
            if (radix == 'b' || radix == 'B') { pos += 2; readBinary(); return finishNumber(start, startLine, startCol); }
        }
        readDecimalDigits();
        if (pos < length && src.charAt(pos) == '.') {
            pos++;
            readDecimalDigits();
        }
        if (pos < length && (src.charAt(pos) == 'e' || src.charAt(pos) == 'E')) {
            pos++;
            if (pos < length && (src.charAt(pos) == '+' || src.charAt(pos) == '-')) pos++;
            readDecimalDigits();
        }
        return finishNumber(start, startLine, startCol);
    }

    /** Consumes a trailing type suffix ({@code L}, {@code f}, {@code d}). */
    private JavaToken finishNumber(int start, int startLine, int startCol) {
        if (pos < length) {
            char c = src.charAt(pos);
            if (c == 'L' || c == 'l' || c == 'f' || c == 'F' || c == 'd' || c == 'D') pos++;
        }
        return token(JavaToken.Kind.NUMBER, start, startLine, startCol);
    }

    private void readDecimalDigits() {
        while (pos < length && (Character.isDigit(src.charAt(pos)) || src.charAt(pos) == '_')) pos++;
    }

    private void readHex() {
        while (pos < length) {
            char c = src.charAt(pos);
            if (isHexDigit(c) || c == '_') { pos++; continue; }
            // Hex float: 0x1.8p3
            if (c == '.' ) { pos++; continue; }
            if (c == 'p' || c == 'P') {
                pos++;
                if (pos < length && (src.charAt(pos) == '+' || src.charAt(pos) == '-')) pos++;
                readDecimalDigits();
                return;
            }
            break;
        }
    }

    private void readBinary() {
        while (pos < length) {
            char c = src.charAt(pos);
            if (c == '0' || c == '1' || c == '_') { pos++; continue; }
            break;
        }
    }

    private static boolean isHexDigit(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private JavaToken word() {
        int start = pos, startLine = line, startCol = pos - lineStart;
        pos++;
        while (pos < length && Character.isJavaIdentifierPart(src.charAt(pos))) pos++;
        String text = src.subSequence(start, pos).toString();
        // Contextual keywords stay identifiers here; the parser promotes them
        // when the position actually calls for a keyword.
        JavaToken.Kind kind = (KEYWORDS.contains(text) && !CONTEXTUAL.contains(text))
                ? JavaToken.Kind.KEYWORD
                : JavaToken.Kind.IDENTIFIER;
        return new JavaToken(kind, text, start, pos, startLine, startCol);
    }

    private JavaToken operator() {
        int start = pos, startLine = line, startCol = pos - lineStart;
        for (String op : OPERATORS) {
            if (matches(op)) {
                pos += op.length();
                JavaToken.Kind kind = op.equals("@") ? JavaToken.Kind.AT : JavaToken.Kind.OPERATOR;
                return new JavaToken(kind, op, start, pos, startLine, startCol);
            }
        }
        // Unknown character — emit it so offsets stay contiguous.
        pos++;
        return token(JavaToken.Kind.OPERATOR, start, startLine, startCol);
    }

    private boolean matches(String op) {
        if (pos + op.length() > length) return false;
        for (int i = 0; i < op.length(); i++) {
            if (src.charAt(pos + i) != op.charAt(i)) return false;
        }
        return true;
    }

    private JavaToken token(JavaToken.Kind kind, int start, int startLine, int startCol) {
        return new JavaToken(kind, src.subSequence(start, pos).toString(),
                start, pos, startLine, startCol);
    }
}
