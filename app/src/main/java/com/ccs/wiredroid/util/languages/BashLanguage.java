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
 * Bash language support class for Sora Editor.
 * Highlighting for shell scripts (.sh, .bash).
 */
public class BashLanguage implements Language {

    private final BashAnalyzeManager manager;
    private final IdentifierAutoComplete autoComplete;

    private static final String[] BASH_KEYWORDS = {
        "if", "then", "else", "elif", "fi", "for", "in", "while", "until", "do", "done",
        "case", "esac", "select", "function", "time", "echo", "exit", "local", "return",
        "read", "export", "alias", "declare", "typeset", "readonly", "set", "unset",
        "shift", "break", "continue", "eval", "exec", "source", "printf", "test"
    };

    public BashLanguage() {
        manager = new BashAnalyzeManager();
        autoComplete = new IdentifierAutoComplete(BASH_KEYWORDS);
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

    @Override
    public NewlineHandler[] getNewlineHandlers() {
        return new NewlineHandler[0];
    }

    private static class BashAnalyzeManager extends SimpleAnalyzeManager<Object> {
        private static final Set<String> KEYWORDS_SET = new HashSet<>();
        static {
            for (String kw : BASH_KEYWORDS) {
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
            long styleVar = TextStyle.makeStyle(EditorColorScheme.IDENTIFIER_VAR, 0, false, false, false); // Bash variables
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

                if (c == '#') {
                    builder.addIfNeeded(line, col, styleComment);
                    while (i < len && text.charAt(i) != '\n') {
                        i++;
                    }
                    continue;
                }

                if (c == '$') {
                    builder.addIfNeeded(line, col, styleVar);
                    i++;
                    if (i < len && text.charAt(i) == '{') {
                        i++;
                        while (i < len && text.charAt(i) != '\n' && text.charAt(i) != '}') {
                            i++;
                        }
                        if (i < len && text.charAt(i) == '}') {
                            i++;
                        }
                    } else {
                        while (i < len && (Character.isJavaIdentifierPart(text.charAt(i)) || text.charAt(i) == '?')) {
                            i++;
                        }
                    }
                    continue;
                }

                if (c == '"' || c == '\'') {
                    char quote = c;
                    builder.addIfNeeded(line, col, styleLiteral);
                    i++;
                    while (i < len && text.charAt(i) != '\n' && text.charAt(i) != quote) {
                        if (text.charAt(i) == '\\' && i + 1 < len) {
                            i += 2;
                        } else {
                            i++;
                        }
                    }
                    if (i < len && text.charAt(i) == quote) {
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
                    while (i < len && Character.isDigit(text.charAt(i))) {
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
