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
 * Highlighting for {@code .properties} files — {@code gradle.properties},
 * {@code local.properties} and Java resource bundles. Keys, the separator and
 * values each get their own colour; {@code #} and {@code !} start comments.
 */
public class PropertiesLanguage implements Language {

    private final PropertiesAnalyzeManager manager = new PropertiesAnalyzeManager();

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
        // Property names are project-specific; suggesting keywords would only add noise.
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

    private static class PropertiesAnalyzeManager extends SimpleAnalyzeManager<Object> {

        @Override
        protected Styles analyze(StringBuilder text, Delegate<Object> delegate) {
            MappedSpans.Builder builder = new MappedSpans.Builder();

            long styleKey       = TextStyle.makeStyle(EditorColorScheme.ATTRIBUTE_NAME);
            long styleValue     = TextStyle.makeStyle(EditorColorScheme.LITERAL);
            long styleComment   = TextStyle.makeStyle(EditorColorScheme.COMMENT, 0, false, true, false);
            long styleOperator  = TextStyle.makeStyle(EditorColorScheme.OPERATOR);
            long styleNormal    = TextStyle.makeStyle(EditorColorScheme.TEXT_NORMAL);

            int len = text.length();
            int line = 0;
            int i = 0;

            while (i <= len) {
                if (delegate.isCancelled()) return null;

                int lineStart = i;
                int lineEnd = i;
                while (lineEnd < len && text.charAt(lineEnd) != '\n') lineEnd++;

                highlightLine(text, lineStart, lineEnd, line, builder,
                        styleKey, styleValue, styleComment, styleOperator, styleNormal);

                builder.determine(line);
                line++;
                i = lineEnd + 1;
                if (lineEnd >= len) break;
            }

            return new Styles(builder.build());
        }

        private void highlightLine(StringBuilder text, int start, int end, int line,
                                   MappedSpans.Builder builder, long styleKey, long styleValue,
                                   long styleComment, long styleOperator, long styleNormal) {
            int i = start;
            while (i < end && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) i++;
            if (i >= end) {
                builder.addIfNeeded(line, 0, styleNormal);
                return;
            }

            char first = text.charAt(i);
            if (first == '#' || first == '!') {
                builder.addIfNeeded(line, 0, styleComment);
                return;
            }

            // Key runs up to the first unescaped '=', ':' or whitespace.
            int sep = -1;
            for (int k = i; k < end; k++) {
                char c = text.charAt(k);
                if (c == '\\') { k++; continue; }
                if (c == '=' || c == ':') { sep = k; break; }
                if (c == ' ' || c == '\t') {
                    // Whitespace separates only when no '=' or ':' follows on the line.
                    int probe = k;
                    while (probe < end && (text.charAt(probe) == ' ' || text.charAt(probe) == '\t')) probe++;
                    if (probe < end && (text.charAt(probe) == '=' || text.charAt(probe) == ':')) {
                        sep = probe;
                    } else {
                        sep = k;
                    }
                    break;
                }
            }

            builder.addIfNeeded(line, i - start, styleKey);
            if (sep < 0) return;

            builder.addIfNeeded(line, sep - start, styleOperator);
            int valueStart = sep + 1;
            if (valueStart < end) {
                builder.addIfNeeded(line, valueStart - start, styleValue);
            }
        }
    }
}
