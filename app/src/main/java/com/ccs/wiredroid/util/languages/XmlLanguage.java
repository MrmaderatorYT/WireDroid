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
 * XML language support class for Sora Editor.
 * Provides syntax highlighting for XML elements, tags, attributes, and comments.
 */
public class XmlLanguage implements Language {

    private final XmlAnalyzeManager manager;

    public XmlLanguage() {
        manager = new XmlAnalyzeManager();
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

    private static class XmlAnalyzeManager extends SimpleAnalyzeManager<Object> {
        @Override
        protected Styles analyze(StringBuilder text, Delegate<Object> delegate) {
            MappedSpans.Builder builder = new MappedSpans.Builder();
            int line = 0;
            int lineStartIdx = 0;
            int len = text.length();

            long styleNormal = TextStyle.makeStyle(EditorColorScheme.TEXT_NORMAL);
            long styleKeyword = TextStyle.makeStyle(EditorColorScheme.KEYWORD, 0, true, false, false); // Tag name
            long styleAttr = TextStyle.makeStyle(EditorColorScheme.IDENTIFIER_NAME); // Attr name
            long styleLiteral = TextStyle.makeStyle(EditorColorScheme.LITERAL); // Attr value / Entity
            long styleComment = TextStyle.makeStyle(EditorColorScheme.COMMENT, 0, false, true, false); // Comment
            long styleOperator = TextStyle.makeStyle(EditorColorScheme.OPERATOR); // Delimiters <, >, =

            boolean inComment = false;
            boolean inTag = false;

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

                if (inComment) {
                    builder.addIfNeeded(line, col, styleComment);
                    if (c == '-' && i + 2 < len && text.charAt(i + 1) == '-' && text.charAt(i + 2) == '>') {
                        inComment = false;
                        i += 3;
                    } else {
                        i++;
                    }
                    continue;
                }

                // Comment start check
                if (c == '<' && i + 3 < len && text.charAt(i + 1) == '!' && text.charAt(i + 2) == '-' && text.charAt(i + 3) == '-') {
                    inComment = true;
                    builder.addIfNeeded(line, col, styleComment);
                    i += 4;
                    continue;
                }

                if (inTag) {
                    if (c == '>') {
                        inTag = false;
                        builder.addIfNeeded(line, col, styleOperator);
                        i++;
                        continue;
                    }

                    if (c == '/' && i + 1 < len && text.charAt(i + 1) == '>') {
                        inTag = false;
                        builder.addIfNeeded(line, col, styleOperator);
                        i += 2;
                        continue;
                    }

                    if (c == '?' && i + 1 < len && text.charAt(i + 1) == '>') {
                        inTag = false;
                        builder.addIfNeeded(line, col, styleOperator);
                        i += 2;
                        continue;
                    }

                    if (c == '=' || c == '/') {
                        builder.addIfNeeded(line, col, styleOperator);
                        i++;
                        continue;
                    }

                    if (c == '"' || c == '\'') {
                        char quote = c;
                        builder.addIfNeeded(line, col, styleLiteral);
                        i++;
                        while (i < len && text.charAt(i) != '\n' && text.charAt(i) != quote) {
                            i++;
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

                    // Attribute name or tag name (if first word after <)
                    if (Character.isJavaIdentifierStart(c) || c == ':' || c == '-') {
                        int start = i;
                        i++;
                        while (i < len && (Character.isJavaIdentifierPart(text.charAt(i)) || text.charAt(i) == ':' || text.charAt(i) == '-' || text.charAt(i) == '.')) {
                            i++;
                        }
                        // We check if it is the tag name. Usually tag name is right after '<' or '</'
                        boolean isTagName = false;
                        int idx = start - 1;
                        while (idx >= lineStartIdx && Character.isWhitespace(text.charAt(idx))) {
                            idx--;
                        }
                        if (idx >= lineStartIdx && (text.charAt(idx) == '<' || text.charAt(idx) == '/' || text.charAt(idx) == '?')) {
                            isTagName = true;
                        }

                        if (isTagName) {
                            builder.addIfNeeded(line, col, styleKeyword);
                        } else {
                            builder.addIfNeeded(line, col, styleAttr);
                        }
                        continue;
                    }

                    builder.addIfNeeded(line, col, styleNormal);
                    i++;
                } else {
                    // Outside tag
                    if (c == '<') {
                        inTag = true;
                        builder.addIfNeeded(line, col, styleOperator);
                        i++;
                        continue;
                    }

                    if (c == '&') {
                        builder.addIfNeeded(line, col, styleLiteral);
                        i++;
                        while (i < len && text.charAt(i) != ';' && text.charAt(i) != '\n' && !Character.isWhitespace(text.charAt(i))) {
                            i++;
                        }
                        if (i < len && text.charAt(i) == ';') {
                            i++;
                        }
                        continue;
                    }

                    builder.addIfNeeded(line, col, styleNormal);
                    i++;
                }
            }

            builder.determine(line);
            return new Styles(builder.build());
        }
    }
}
