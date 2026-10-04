package com.ccs.wiredroid.util.languages;

/**
 * Walks source text and reports where each kind of token begins.
 *
 * <p>Deliberately free of the editor: it reports line, column and kind through
 * a sink, so the rules can be tested against plain strings. The traps worth
 * testing are all here — a comment marker inside a string, a quote inside a
 * comment, an unterminated literal at end of file — and none of them are
 * reachable through the editor's analyzer interface.</p>
 */
public final class LexicalScanner {

    /** What the highlighter should colour a run of text as. */
    public enum Kind {
        NORMAL, KEYWORD, LITERAL, COMMENT, OPERATOR
    }

    /** Receives the start of every token run, in order. */
    public interface Sink {
        void span(int line, int column, Kind kind);

        /** The scanner has finished the given line. */
        void endLine(int line);
    }

    /** Lets a long scan give up when the editor supersedes it. */
    public interface Cancel {
        boolean isCancelled();
    }

    private LexicalScanner() {}

    public static void scan(CharSequence text, LexicalRules rules, Sink sink, Cancel cancel) {
        if (text == null || rules == null || sink == null) return;
        int length = text.length();
        int line = 0;
        int lineStart = 0;
        int i = 0;

        while (i < length) {
            if (cancel != null && cancel.isCancelled()) return;
            char c = text.charAt(i);

            if (c == '\n') {
                sink.endLine(line);
                line++;
                i++;
                lineStart = i;
                continue;
            }

            int column = i - lineStart;

            if (rules.lineComment != null && matches(text, i, rules.lineComment)) {
                sink.span(line, column, Kind.COMMENT);
                while (i < length && text.charAt(i) != '\n') i++;
                continue;
            }

            if (rules.blockCommentStart != null && matches(text, i, rules.blockCommentStart)) {
                sink.span(line, column, Kind.COMMENT);
                i += rules.blockCommentStart.length();
                while (i < length) {
                    if (cancel != null && cancel.isCancelled()) return;
                    if (text.charAt(i) == '\n') {
                        sink.endLine(line);
                        line++;
                        lineStart = i + 1;
                    } else if (matches(text, i, rules.blockCommentEnd)) {
                        i += rules.blockCommentEnd.length();
                        break;
                    }
                    i++;
                }
                continue;
            }

            if (rules.annotations && c == '@' && i + 1 < length
                    && Character.isJavaIdentifierStart(text.charAt(i + 1))) {
                sink.span(line, column, Kind.KEYWORD);
                i++;
                while (i < length && Character.isJavaIdentifierPart(text.charAt(i))) i++;
                continue;
            }

            if (rules.tripleQuotedStrings && matches(text, i, "\"\"\"")) {
                sink.span(line, column, Kind.LITERAL);
                i += 3;
                while (i < length) {
                    if (cancel != null && cancel.isCancelled()) return;
                    if (text.charAt(i) == '\n') {
                        sink.endLine(line);
                        line++;
                        lineStart = i + 1;
                    } else if (matches(text, i, "\"\"\"")) {
                        i += 3;
                        break;
                    }
                    i++;
                }
                continue;
            }

            if (c == '"') {
                sink.span(line, column, Kind.LITERAL);
                i = skipQuoted(text, i, '"');
                continue;
            }

            if (c == '\'') {
                // A quote means a string in Groovy and a character in Scala, and
                // the difference decides whether a newline ends it.
                if (rules.singleQuotedStrings) {
                    sink.span(line, column, Kind.LITERAL);
                    i = skipQuoted(text, i, '\'');
                    continue;
                }
                if (rules.charLiteral == LexicalRules.CharLiteral.QUOTED) {
                    sink.span(line, column, Kind.LITERAL);
                    i = skipQuoted(text, i, '\'');
                    continue;
                }
            }

            if (rules.charLiteral == LexicalRules.CharLiteral.BACKSLASH && c == '\\') {
                sink.span(line, column, Kind.LITERAL);
                int afterBackslash = i + 1;
                i = afterBackslash;
                // Named characters — \newline, \space — are words, not one letter.
                while (i < length && rules.isSymbolPart(text.charAt(i))) i++;
                // A punctuation character, \( or \\, names itself.
                if (i == afterBackslash && i < length) i++;
                continue;
            }

            if (rules.keywordSigil != 0 && c == rules.keywordSigil
                    && i + 1 < length && rules.isSymbolPart(text.charAt(i + 1))) {
                sink.span(line, column, Kind.LITERAL);
                i++;
                while (i < length && rules.isSymbolPart(text.charAt(i))) i++;
                continue;
            }

            if (Character.isWhitespace(c)) {
                sink.span(line, column, Kind.NORMAL);
                i++;
                continue;
            }

            if (Character.isDigit(c)) {
                sink.span(line, column, Kind.LITERAL);
                i++;
                while (i < length && isNumberPart(text.charAt(i))) i++;
                continue;
            }

            if (rules.isSymbolStart(c)) {
                int start = i;
                i++;
                while (i < length && rules.isSymbolPart(text.charAt(i))) i++;
                String word = text.subSequence(start, i).toString();
                sink.span(line, column, rules.keywords.contains(word) ? Kind.KEYWORD : Kind.NORMAL);
                continue;
            }

            sink.span(line, column, Kind.OPERATOR);
            i++;
        }
        sink.endLine(line);
    }

    /**
     * Index just past a quoted run that started at {@code from}.
     *
     * <p>Stops at a newline as well as at the closing quote: an unterminated
     * literal must not swallow the rest of the file, which is what typing an
     * opening quote does on every keystroke until the pair is closed.</p>
     */
    private static int skipQuoted(CharSequence text, int from, char quote) {
        int length = text.length();
        int i = from + 1;
        while (i < length && text.charAt(i) != '\n' && text.charAt(i) != quote) {
            if (text.charAt(i) == '\\' && i + 1 < length) i += 2;
            else i++;
        }
        if (i < length && text.charAt(i) == quote) i++;
        return i;
    }

    private static boolean isNumberPart(char c) {
        return Character.isDigit(c) || c == '.' || c == '_'
                || c == 'x' || c == 'X' || c == 'e' || c == 'E'
                || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')
                || c == 'L' || c == 'l' || c == 'f' || c == 'F'
                || c == 'd' || c == 'D' || c == 'M' || c == 'N' || c == 'G';
    }

    private static boolean matches(CharSequence text, int at, String token) {
        if (token == null || at + token.length() > text.length()) return false;
        for (int i = 0; i < token.length(); i++) {
            if (text.charAt(at + i) != token.charAt(i)) return false;
        }
        return true;
    }
}
