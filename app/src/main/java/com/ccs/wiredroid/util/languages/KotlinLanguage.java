package com.ccs.wiredroid.util.languages;

import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import io.github.rosemoe.sora.lang.EmptyLanguage;
import io.github.rosemoe.sora.lang.Language;
import io.github.rosemoe.sora.lang.QuickQuoteHandler;
import io.github.rosemoe.sora.lang.analysis.AnalyzeManager;
import io.github.rosemoe.sora.lang.analysis.SimpleAnalyzeManager;
import io.github.rosemoe.sora.lang.completion.CompletionPublisher;
import io.github.rosemoe.sora.lang.completion.IdentifierAutoComplete;
import io.github.rosemoe.sora.lang.format.Formatter;
import io.github.rosemoe.sora.lang.smartEnter.NewlineHandler;
import io.github.rosemoe.sora.lang.styling.MappedSpans;
import io.github.rosemoe.sora.lang.styling.Styles;
import io.github.rosemoe.sora.lang.styling.TextStyle;
import io.github.rosemoe.sora.text.CharPosition;
import io.github.rosemoe.sora.text.ContentReference;
import io.github.rosemoe.sora.widget.SymbolPairMatch;
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme;

import java.util.HashSet;
import java.util.Set;

/**
 * Kotlin language support class for Sora Editor.
 * Provides lexical highlight for Kotlin files (.kt).
 */
public class KotlinLanguage implements Language {

    private final KotlinAnalyzeManager manager;
    private final IdentifierAutoComplete autoComplete;

    private static final String[] KOTLIN_KEYWORDS = {
        "package", "import", "class", "interface", "fun", "val", "var", "if", "else", "when", 
        "is", "in", "for", "while", "do", "return", "break", "continue", "object", "this", 
        "super", "throw", "try", "catch", "finally", "true", "false", "null", "typealias", 
        "as", "constructor", "delegate", "open", "public", "private", "protected", "internal", 
        "abstract", "final", "override", "companion", "init", "enum", "annotation", "data", 
        "sealed", "lateinit", "tailrec", "vararg", "infix", "inline", "external", "operator", 
        "suspend", "by", "get", "set", "field", "it", "out", "where", "const"
    };

    public KotlinLanguage() {
        manager = new KotlinAnalyzeManager();
        autoComplete = new IdentifierAutoComplete(KOTLIN_KEYWORDS);
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
        String prefix = io.github.rosemoe.sora.lang.completion.CompletionHelper.computePrefix(
                content, position, Character::isJavaIdentifierPart);
        autoComplete.requireAutoComplete(content, position, prefix, publisher, null);
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

    /** Enter between a bracket pair opens the block. */
    private final NewlineHandler[] newlineHandlers =
            { new BracePairNewlineHandler(useTab()) };

    @Override
    public NewlineHandler[] getNewlineHandlers() {
        return newlineHandlers;
    }

    private static class KotlinAnalyzeManager extends SimpleAnalyzeManager<Object> {
        private static final Set<String> KEYWORDS_SET = new HashSet<>();
        static {
            for (String kw : KOTLIN_KEYWORDS) {
                KEYWORDS_SET.add(kw);
            }
        }

        @Override
        protected Styles analyze(StringBuilder text, Delegate<Object> delegate) {
            MappedSpans.Builder builder = new MappedSpans.Builder();
            int line = 0;
            int lineStartIdx = 0;
            int len = text.length();

            long styleNormal = TextStyle.makeStyle(EditorColorScheme.TEXT_NORMAL);
            long styleKeyword = TextStyle.makeStyle(EditorColorScheme.KEYWORD, 0, true, false, false);
            long styleLiteral = TextStyle.makeStyle(EditorColorScheme.LITERAL);
            long styleComment = TextStyle.makeStyle(EditorColorScheme.COMMENT, 0, false, true, false);
            long styleOperator = TextStyle.makeStyle(EditorColorScheme.OPERATOR);

            for (int i = 0; i < len; ) {
                if (delegate.isCancelled()) {
                    return null;
                }

                char c = text.charAt(i);

                if (c == '\n') {
                    builder.determine(line);
                    line++;
                    lineStartIdx = i + 1;
                    i++;
                    continue;
                }

                int col = i - lineStartIdx;

                // Handle single line comment
                if (c == '/' && i + 1 < len && text.charAt(i + 1) == '/') {
                    builder.addIfNeeded(line, col, styleComment);
                    while (i < len && text.charAt(i) != '\n') {
                        i++;
                    }
                    continue;
                }

                // Handle multi-line comment
                if (c == '/' && i + 1 < len && text.charAt(i + 1) == '*') {
                    builder.addIfNeeded(line, col, styleComment);
                    i += 2;
                    while (i < len && !delegate.isCancelled()) {
                        if (text.charAt(i) == '\n') {
                            builder.determine(line);
                            line++;
                            lineStartIdx = i + 1;
                        }
                        if (text.charAt(i) == '*' && i + 1 < len && text.charAt(i + 1) == '/') {
                            i += 2;
                            break;
                        }
                        i++;
                    }
                    continue;
                }

                // Handle Kotlin annotations (@Annotation)
                if (c == '@') {
                    builder.addIfNeeded(line, col, styleKeyword);
                    i++;
                    while (i < len && Character.isJavaIdentifierPart(text.charAt(i))) {
                        i++;
                    }
                    continue;
                }

                // Handle multi-line triple quoted string """
                if (c == '"' && i + 2 < len && text.charAt(i + 1) == '"' && text.charAt(i + 2) == '"') {
                    builder.addIfNeeded(line, col, styleLiteral);
                    i += 3;
                    while (i < len && !delegate.isCancelled()) {
                        if (text.charAt(i) == '\n') {
                            builder.determine(line);
                            line++;
                            lineStartIdx = i + 1;
                        }
                        if (text.charAt(i) == '"' && i + 2 < len && text.charAt(i + 1) == '"' && text.charAt(i + 2) == '"') {
                            i += 3;
                            break;
                        }
                        i++;
                    }
                    continue;
                }

                // Handle standard double quoted string literal "..."
                if (c == '"') {
                    builder.addIfNeeded(line, col, styleLiteral);
                    i++;
                    while (i < len && text.charAt(i) != '\n' && text.charAt(i) != '"') {
                        if (text.charAt(i) == '\\' && i + 1 < len) {
                            i += 2;
                        } else {
                            i++;
                        }
                    }
                    if (i < len && text.charAt(i) == '"') {
                        i++;
                    }
                    continue;
                }

                // Handle single quoted character literal '...'
                if (c == '\'') {
                    builder.addIfNeeded(line, col, styleLiteral);
                    i++;
                    while (i < len && text.charAt(i) != '\n' && text.charAt(i) != '\'') {
                        if (text.charAt(i) == '\\' && i + 1 < len) {
                            i += 2;
                        } else {
                            i++;
                        }
                    }
                    if (i < len && text.charAt(i) == '\'') {
                        i++;
                    }
                    continue;
                }

                if (Character.isWhitespace(c)) {
                    builder.addIfNeeded(line, col, styleNormal);
                    i++;
                    continue;
                }

                if (Character.isJavaIdentifierStart(c)) {
                    int start = i;
                    i++;
                    while (i < len && Character.isJavaIdentifierPart(text.charAt(i))) {
                        i++;
                    }
                    String word = text.substring(start, i);
                    if (KEYWORDS_SET.contains(word)) {
                        builder.addIfNeeded(line, col, styleKeyword);
                    } else {
                        builder.addIfNeeded(line, col, styleNormal);
                    }
                    continue;
                }

                if (Character.isDigit(c)) {
                    builder.addIfNeeded(line, col, styleLiteral);
                    i++;
                    while (i < len && (Character.isDigit(text.charAt(i)) || text.charAt(i) == 'L' || text.charAt(i) == 'f' || text.charAt(i) == 'u' || text.charAt(i) == 'x' || (text.charAt(i) >= 'a' && text.charAt(i) <= 'f') || (text.charAt(i) >= 'A' && text.charAt(i) <= 'F') || text.charAt(i) == '.')) {
                        i++;
                    }
                    continue;
                }

                builder.addIfNeeded(line, col, styleOperator);
                i++;
            }

            builder.determine(line);
            return new Styles(builder.build());
        }
    }
}
