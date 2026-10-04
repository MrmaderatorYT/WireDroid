package com.ccs.wiredroid.util.languages;

import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.UnderlineSpan;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.github.rosemoe.sora.lang.EmptyLanguage;
import io.github.rosemoe.sora.lang.Language;
import io.github.rosemoe.sora.lang.QuickQuoteHandler;
import io.github.rosemoe.sora.lang.analysis.AnalyzeManager;
import io.github.rosemoe.sora.lang.analysis.SimpleAnalyzeManager;
import io.github.rosemoe.sora.lang.completion.CompletionPublisher;
import io.github.rosemoe.sora.lang.format.Formatter;
import io.github.rosemoe.sora.lang.smartEnter.NewlineHandler;
import io.github.rosemoe.sora.lang.styling.MappedSpans;
import io.github.rosemoe.sora.lang.styling.Styles;
import io.github.rosemoe.sora.lang.styling.TextStyle;
import io.github.rosemoe.sora.text.CharPosition;
import io.github.rosemoe.sora.text.ContentReference;
import io.github.rosemoe.sora.widget.SymbolPairMatch;
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme;

/**
 * Markdown language support with syntax highlighting.
 */
public class MarkdownLanguage implements Language {

    private final MarkdownAnalyzeManager manager;

    public MarkdownLanguage() {
        manager = new MarkdownAnalyzeManager();
    }

    @NonNull
    @Override
    public AnalyzeManager getAnalyzeManager() {
        return manager;
    }

    @Nullable
    @Override
    public QuickQuoteHandler getQuickQuoteHandler() {
        return null;
    }

    @Override
    public void destroy() {
    }

    @Override
    public int getInterruptionLevel() {
        return INTERRUPTION_LEVEL_STRONG;
    }

    @Override
    public void requireAutoComplete(@NonNull ContentReference content, @NonNull CharPosition position,
                                    @NonNull CompletionPublisher publisher, @NonNull Bundle extraArguments) {
    }

    @Override
    public int getIndentAdvance(@NonNull ContentReference text, int line, int column) {
        return 0;
    }

    @Override
    public boolean useTab() {
        return false;
    }

    @NonNull
    @Override
    public Formatter getFormatter() {
        return EmptyLanguage.EmptyFormatter.INSTANCE;
    }

    @Override
    public SymbolPairMatch getSymbolPairs() {
        return new SymbolPairMatch.DefaultSymbolPairs();
    }

    @Override
    public NewlineHandler[] getNewlineHandlers() {
        return new NewlineHandler[0];
    }

    private static class MarkdownAnalyzeManager extends SimpleAnalyzeManager<Object> {

        private static final long STYLE_HEADING = TextStyle.makeStyle(EditorColorScheme.KEYWORD, 0, true, false, false);
        private static final long STYLE_BOLD = TextStyle.makeStyle(EditorColorScheme.KEYWORD, 0, true, false, false);
        private static final long STYLE_ITALIC = TextStyle.makeStyle(EditorColorScheme.KEYWORD, 0, false, true, false);
        private static final long STYLE_CODE = TextStyle.makeStyle(EditorColorScheme.LITERAL);
        private static final long STYLE_CODE_BLOCK = TextStyle.makeStyle(EditorColorScheme.LITERAL, 0, false, false, false);
        private static final long STYLE_LINK = TextStyle.makeStyle(EditorColorScheme.IDENTIFIER_VAR);
        private static final long STYLE_LINK_TEXT = TextStyle.makeStyle(EditorColorScheme.IDENTIFIER_VAR, 0, false, false, true);
        private static final long STYLE_LIST_MARKER = TextStyle.makeStyle(EditorColorScheme.OPERATOR);
        private static final long STYLE_BLOCKQUOTE = TextStyle.makeStyle(EditorColorScheme.COMMENT, 0, false, true, false);
        private static final long STYLE_HR = TextStyle.makeStyle(EditorColorScheme.OPERATOR);
        private static final long STYLE_NORMAL = TextStyle.makeStyle(EditorColorScheme.TEXT_NORMAL);
        private static final long STYLE_LATEX_CMD = TextStyle.makeStyle(EditorColorScheme.KEYWORD);
        private static final long STYLE_LATEX_MATH = TextStyle.makeStyle(EditorColorScheme.OPERATOR);

        private static final java.util.HashSet<String> JAVA_KEYWORDS = new java.util.HashSet<>(java.util.Arrays.asList(
                "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
                "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
                "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
                "interface", "long", "native", "new", "package", "private", "protected", "public",
                "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
                "throw", "throws", "transient", "try", "void", "volatile", "while", "true", "false", "null",
                "var", "record", "yield", "sealed", "non-sealed", "permits", "when",
                "exports", "module", "opens", "provides", "requires", "to", "uses", "with", "transitive"));

        private static final java.util.HashSet<String> PYTHON_KEYWORDS = new java.util.HashSet<>(java.util.Arrays.asList(
                "def", "class", "return", "if", "elif", "else", "for", "while", "in", "not", "and", "or",
                "import", "from", "as", "try", "except", "finally", "raise", "with", "yield", "lambda",
                "pass", "break", "continue", "del", "global", "nonlocal", "assert", "True", "False", "None",
                "print", "self", "async", "await"));

        private static final java.util.HashSet<String> KOTLIN_KEYWORDS = new java.util.HashSet<>(java.util.Arrays.asList(
                "fun", "val", "var", "class", "interface", "object", "when", "is", "in", "as", "typealias",
                "if", "else", "for", "while", "do", "return", "break", "continue", "throw", "try", "catch",
                "finally", "this", "super", "true", "false", "null", "package", "import", "constructor",
                "init", "companion", "data", "sealed", "enum", "annotation", "open", "abstract", "final",
                "override", "private", "protected", "public", "internal", "suspend", "inline", "external",
                "operator", "infix", "by", "get", "set", "field", "it", "out", "where", "const"));

        private static final java.util.HashSet<String> JS_KEYWORDS = new java.util.HashSet<>(java.util.Arrays.asList(
                "const", "let", "var", "function", "return", "if", "else", "for", "while", "do", "switch",
                "case", "break", "continue", "new", "this", "class", "extends", "super", "import", "export",
                "default", "try", "catch", "finally", "throw", "async", "await", "yield", "typeof", "instanceof",
                "void", "delete", "in", "of", "true", "false", "null", "undefined", "console", "Math", "JSON"));

        @Override
        protected Styles analyze(StringBuilder text, Delegate<Object> delegate) {
            MappedSpans.Builder builder = new MappedSpans.Builder();
            int len = text.length();
            int line = 0;
            int lineStart = 0;
            boolean inCodeBlock = false;
            String codeLang = "";
            int codeBlockStartLine = -1;

            for (int i = 0; i < len; ) {
                if (delegate.isCancelled()) return null;

                char c = text.charAt(i);

                if (c == '\n') {
                    builder.determine(line);
                    line++;
                    lineStart = i + 1;
                    i++;
                    continue;
                }

                int col = i - lineStart;

                // Fenced code block opening: ```lang
                if (!inCodeBlock && c == '`' && i + 2 < len && text.charAt(i + 1) == '`' && text.charAt(i + 2) == '`') {
                    inCodeBlock = true;
                    codeBlockStartLine = line;
                    i += 3;
                    // Read language tag
                    int langStart = i;
                    while (i < len && text.charAt(i) != '\n') i++;
                    codeLang = text.substring(langStart, i).trim().toLowerCase(java.util.Locale.ROOT);
                    builder.addIfNeeded(line, 0, STYLE_CODE_BLOCK);
                    continue;
                }

                // Fenced code block closing: ```
                if (inCodeBlock && c == '`' && i + 2 < len && text.charAt(i + 1) == '`' && text.charAt(i + 2) == '`') {
                    inCodeBlock = false;
                    i += 3;
                    while (i < len && text.charAt(i) != '\n') i++;
                    builder.addIfNeeded(line, 0, STYLE_CODE_BLOCK);
                    continue;
                }

                // Inside code block — apply language-specific highlighting
                if (inCodeBlock) {
                    long langStyle = getCodeBlockStyle(codeLang, text, i, line, col, builder);
                    builder.addIfNeeded(line, col, langStyle);
                    i++;
                    continue;
                }

                // Heading: # ...
                if (c == '#' && (i == 0 || text.charAt(i - 1) == '\n')) {
                    int start = i;
                    while (i < len && text.charAt(i) != '\n') i++;
                    builder.addIfNeeded(line, col, STYLE_HEADING);
                    continue;
                }

                // Horizontal rule: --- or *** or ___
                if ((c == '-' || c == '*' || c == '_') && (i == 0 || text.charAt(i - 1) == '\n')) {
                    int j = i;
                    int count = 0;
                    while (j < len && (text.charAt(j) == '-' || text.charAt(j) == '*' || text.charAt(j) == '_')) {
                        count++;
                        j++;
                    }
                    if (count >= 3 && j < len && text.charAt(j) == '\n') {
                        builder.addIfNeeded(line, col, STYLE_HR);
                        i = j;
                        continue;
                    }
                }

                // List marker: - * + 1. 
                if ((c == '-' || c == '*' || c == '+') && (i == 0 || text.charAt(i - 1) == '\n')
                        && i + 1 < len && text.charAt(i + 1) == ' ') {
                    builder.addIfNeeded(line, col, STYLE_LIST_MARKER);
                    i += 2;
                    continue;
                }
                if (Character.isDigit(c) && (i == 0 || text.charAt(i - 1) == '\n')) {
                    int j = i;
                    while (j < len && Character.isDigit(text.charAt(j))) j++;
                    if (j < len && text.charAt(j) == '.' && j + 1 < len && text.charAt(j + 1) == ' ') {
                        builder.addIfNeeded(line, col, STYLE_LIST_MARKER);
                        i = j + 2;
                        continue;
                    }
                }

                // Blockquote: >
                if (c == '>' && (i == 0 || text.charAt(i - 1) == '\n')) {
                    builder.addIfNeeded(line, col, STYLE_BLOCKQUOTE);
                    i++;
                    while (i < len && text.charAt(i) != '\n') i++;
                    continue;
                }

                // Inline code: `...`
                if (c == '`') {
                    int start = i;
                    i++;
                    while (i < len && text.charAt(i) != '`' && text.charAt(i) != '\n') i++;
                    if (i < len && text.charAt(i) == '`') i++;
                    builder.addIfNeeded(line, col, STYLE_CODE);
                    continue;
                }

                // Bold+italic: ***...*** or ___...___
                if ((c == '*' && i + 2 < len && text.charAt(i + 1) == '*' && text.charAt(i + 2) == '*')
                        || (c == '_' && i + 2 < len && text.charAt(i + 1) == '_' && text.charAt(i + 2) == '_')) {
                    char marker = c;
                    int start = i;
                    i += 3;
                    while (i < len) {
                        if (text.charAt(i) == marker && i + 2 < len
                                && text.charAt(i + 1) == marker && text.charAt(i + 2) == marker) {
                            i += 3;
                            break;
                        }
                        i++;
                    }
                    builder.addIfNeeded(line, col, STYLE_BOLD);
                    continue;
                }

                // Bold: **...** or __...__
                if ((c == '*' && i + 1 < len && text.charAt(i + 1) == '*')
                        || (c == '_' && i + 1 < len && text.charAt(i + 1) == '_')) {
                    char marker = c;
                    int start = i;
                    i += 2;
                    while (i < len) {
                        if (text.charAt(i) == marker && i + 1 < len && text.charAt(i + 1) == marker) {
                            i += 2;
                            break;
                        }
                        i++;
                    }
                    builder.addIfNeeded(line, col, STYLE_BOLD);
                    continue;
                }

                // Italic: *...* or _..._
                if (c == '*' || c == '_') {
                    char marker = c;
                    int start = i;
                    i++;
                    while (i < len) {
                        if (text.charAt(i) == marker) {
                            i++;
                            break;
                        }
                        if (text.charAt(i) == '\n') break;
                        i++;
                    }
                    builder.addIfNeeded(line, col, STYLE_ITALIC);
                    continue;
                }

                // Link: [text](url)
                if (c == '[') {
                    int bracketEnd = -1;
                    for (int j = i + 1; j < len; j++) {
                        if (text.charAt(j) == ']') {
                            bracketEnd = j;
                            break;
                        }
                    }
                    if (bracketEnd > 0 && bracketEnd + 1 < len && text.charAt(bracketEnd + 1) == '(') {
                        int parenEnd = -1;
                        for (int j = bracketEnd + 2; j < len; j++) {
                            if (text.charAt(j) == ')') {
                                parenEnd = j;
                                break;
                            }
                        }
                        if (parenEnd > 0) {
                            builder.addIfNeeded(line, col, STYLE_LINK_TEXT);
                            i = parenEnd + 1;
                            continue;
                        }
                    }
                    i++;
                    continue;
                }

                // Image: ![alt](url)
                if (c == '!' && i + 1 < len && text.charAt(i + 1) == '[') {
                    builder.addIfNeeded(line, col, STYLE_LINK);
                    int j = i + 2;
                    while (j < len && text.charAt(j) != ']') j++;
                    if (j < len && j + 1 < len && text.charAt(j + 1) == '(') {
                        j++;
                        while (j < len && text.charAt(j) != ')') j++;
                        if (j < len) j++;
                    }
                    i = j;
                    continue;
                }

                // LaTeX inline: $...$
                if (c == '$' && (i == 0 || text.charAt(i - 1) != '\\')) {
                    int end = findDollarClose(text, i + 1);
                    if (end > 0) {
                        builder.addIfNeeded(line, col, STYLE_LATEX_MATH);
                        i = end + 1;
                        continue;
                    }
                }

                // LaTeX command: \command
                if (c == '\\') {
                    builder.addIfNeeded(line, col, STYLE_LATEX_CMD);
                    i++;
                    if (i < len && Character.isLetter(text.charAt(i))) {
                        while (i < len && Character.isLetter(text.charAt(i))) i++;
                    }
                    continue;
                }

                if (Character.isWhitespace(c)) {
                    builder.addIfNeeded(line, col, STYLE_NORMAL);
                    i++;
                    continue;
                }

                builder.addIfNeeded(line, col, STYLE_NORMAL);
                i++;
            }

            builder.determine(line);
            return new Styles(builder.build());
        }

        private static int findDollarClose(StringBuilder text, int start) {
            for (int i = start; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '$' && (i == 0 || text.charAt(i - 1) != '\\')) return i;
                if (c == '\n') return -1;
            }
            return -1;
        }

        private long getCodeBlockStyle(String lang, StringBuilder text, int pos, int line, int col,
                                        MappedSpans.Builder builder) {
            if (lang.isEmpty()) return STYLE_CODE_BLOCK;

            char c = text.charAt(pos);

            // Common tokens for all languages
            if (c == '/' && pos + 1 < text.length() && text.charAt(pos + 1) == '/') {
                return TextStyle.makeStyle(EditorColorScheme.COMMENT);
            }
            if (c == '/' && pos + 1 < text.length() && text.charAt(pos + 1) == '*') {
                return TextStyle.makeStyle(EditorColorScheme.COMMENT);
            }
            if (c == '"' || c == '\'') {
                return TextStyle.makeStyle(EditorColorScheme.LITERAL);
            }
            if (c == '#') {
                return TextStyle.makeStyle(EditorColorScheme.COMMENT);
            }

            // Extract word at current position
            if (Character.isLetter(c) || c == '_') {
                int start = pos;
                int end = pos + 1;
                while (end < text.length() && (Character.isLetterOrDigit(text.charAt(end)) || text.charAt(end) == '_')) end++;
                String word = text.substring(start, end);

                if (lang.startsWith("java")) {
                    return JAVA_KEYWORDS.contains(word) ? STYLE_HEADING : STYLE_NORMAL;
                } else if (lang.equals("kotlin") || lang.equals("kt")) {
                    return KOTLIN_KEYWORDS.contains(word) ? STYLE_HEADING : STYLE_NORMAL;
                } else if (lang.equals("python") || lang.equals("py")) {
                    return PYTHON_KEYWORDS.contains(word) ? STYLE_HEADING : STYLE_NORMAL;
                } else if (lang.equals("javascript") || lang.equals("js") || lang.equals("typescript") || lang.equals("ts")) {
                    return JS_KEYWORDS.contains(word) ? STYLE_HEADING : STYLE_NORMAL;
                } else if (lang.equals("json")) {
                    return STYLE_NORMAL;
                }
            }

            // Numbers
            if (Character.isDigit(c)) {
                return TextStyle.makeStyle(EditorColorScheme.LITERAL);
            }

            return STYLE_CODE_BLOCK;
        }
    }
}
