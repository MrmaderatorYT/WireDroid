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
 * JSON language support class for Sora Editor.
 * Colors JSON keys differently from string values, highlights keywords and brackets.
 */
public class JsonLanguage implements Language {

    private final JsonAnalyzeManager manager;
    private final IdentifierAutoComplete autoComplete;

    private static final String[] JSON_KEYWORDS = { "true", "false", "null" };

    public JsonLanguage() {
        manager = new JsonAnalyzeManager();
        autoComplete = new IdentifierAutoComplete(JSON_KEYWORDS);
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
        return newlineHandlers;
    }

    private static class JsonAnalyzeManager extends SimpleAnalyzeManager<Object> {
        private static final Set<String> KEYWORDS_SET = new HashSet<>();
        static {
            for (String kw : JSON_KEYWORDS) {
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
            long styleKey = TextStyle.makeStyle(EditorColorScheme.IDENTIFIER_NAME, 0, false, false, false); // JSON Key
            long styleLiteral = TextStyle.makeStyle(EditorColorScheme.LITERAL); // JSON String Value / Number
            long styleKeyword = TextStyle.makeStyle(EditorColorScheme.KEYWORD, 0, true, false, false); // true, false, null
            long styleOperator = TextStyle.makeStyle(EditorColorScheme.OPERATOR); // {}, [], :, ,

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

                if (c == '"') {
                    // Find end of string
                    int start = i;
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

                    // Check if it is followed by ':' to determine if it is a JSON Key
                    boolean isKey = false;
                    int peek = i;
                    while (peek < len && Character.isWhitespace(text.charAt(peek))) {
                        peek++;
                    }
                    if (peek < len && text.charAt(peek) == ':') {
                        isKey = true;
                    }

                    builder.addIfNeeded(line, col, isKey ? styleKey : styleLiteral);
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

                if (Character.isDigit(c) || c == '-') {
                    builder.addIfNeeded(line, col, styleLiteral);
                    i++;
                    while (i < len && (Character.isDigit(text.charAt(i)) || text.charAt(i) == '.' 
                            || text.charAt(i) == 'e' || text.charAt(i) == 'E' || text.charAt(i) == '+' || text.charAt(i) == '-')) {
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

    /** Enter between a bracket pair opens the block. */
    private final NewlineHandler[] newlineHandlers =
            { new BracePairNewlineHandler(useTab()) };
}
