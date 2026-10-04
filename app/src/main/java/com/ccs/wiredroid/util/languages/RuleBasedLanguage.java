package com.ccs.wiredroid.util.languages;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.github.rosemoe.sora.lang.EmptyLanguage;
import io.github.rosemoe.sora.lang.Language;
import io.github.rosemoe.sora.lang.QuickQuoteHandler;
import io.github.rosemoe.sora.lang.analysis.AnalyzeManager;
import io.github.rosemoe.sora.lang.analysis.SimpleAnalyzeManager;
import io.github.rosemoe.sora.lang.completion.CompletionHelper;
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

/**
 * An editor language built from a {@link LexicalRules} description.
 *
 * <p>Everything a language of this kind needs beyond its tokens — indentation
 * by bracket depth, bracket pairs, opening a block on Enter, completing words
 * already in the file — is the same for all of them, so it lives here once.</p>
 */
public abstract class RuleBasedLanguage implements Language {

    private final LexicalRules rules;
    private final RuleBasedAnalyzer analyzer;
    private final IdentifierAutoComplete autoComplete;
    private final NewlineHandler[] newlineHandlers;

    protected RuleBasedLanguage(LexicalRules rules, String[] keywords) {
        this.rules = rules;
        this.analyzer = new RuleBasedAnalyzer(rules);
        this.autoComplete = new IdentifierAutoComplete(keywords);
        this.newlineHandlers = new NewlineHandler[]{ new BracePairNewlineHandler(useTab()) };
    }

    /** The token rules this language was built from; for tests. */
    LexicalRules rulesForTesting() {
        return rules;
    }

    @NonNull
    @Override
    public AnalyzeManager getAnalyzeManager() {
        return analyzer;
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
                                    @NonNull CompletionPublisher publisher,
                                    @NonNull Bundle extraArguments) {
        // The language's own symbol rules, not Java's: a Clojure name may hold a
        // hyphen or a question mark, and computing the prefix with Java's idea
        // of a word would cut "empty?" down to "empty".
        String prefix = CompletionHelper.computePrefix(content, position, rules::isSymbolPart);
        autoComplete.requireAutoComplete(content, position, prefix, publisher, null);
    }

    @Override
    public int getIndentAdvance(@NonNull ContentReference text, int line, int column) {
        try {
            String lineText = text.getLine(line);
            int limit = Math.min(column, lineText.length());
            int depth = 0;
            boolean inString = false;
            char quote = 0;
            for (int i = 0; i < limit; i++) {
                char c = lineText.charAt(i);
                if (inString) {
                    if (c == '\\') { i++; continue; }
                    if (c == quote) inString = false;
                    continue;
                }
                if (c == '"' || (c == '\'' && rules.singleQuotedStrings)) {
                    inString = true;
                    quote = c;
                    continue;
                }
                if (c == '{' || c == '(' || c == '[') depth++;
                else if (c == '}' || c == ')' || c == ']') depth--;
            }
            return Math.max(0, depth) * 2;
        } catch (Exception e) {
            return 0;
        }
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

    /** Adapts {@link LexicalScanner} to the editor's span builder. */
    private static final class RuleBasedAnalyzer extends SimpleAnalyzeManager<Object> {

        private final LexicalRules rules;

        RuleBasedAnalyzer(LexicalRules rules) {
            this.rules = rules;
        }

        @Override
        protected Styles analyze(StringBuilder text, Delegate<Object> delegate) {
            MappedSpans.Builder builder = new MappedSpans.Builder();
            final boolean[] cancelled = { false };

            LexicalScanner.scan(text, rules, new LexicalScanner.Sink() {
                @Override
                public void span(int line, int column, LexicalScanner.Kind kind) {
                    builder.addIfNeeded(line, column, styleFor(kind));
                }

                @Override
                public void endLine(int line) {
                    builder.determine(line);
                }
            }, () -> {
                cancelled[0] = delegate.isCancelled();
                return cancelled[0];
            });

            // A cancelled scan has spans for only part of the file; handing that
            // back would paint the rest of it as plain text until the next edit.
            if (cancelled[0]) return null;
            return new Styles(builder.build());
        }

        private static long styleFor(LexicalScanner.Kind kind) {
            switch (kind) {
                case KEYWORD:
                    return TextStyle.makeStyle(EditorColorScheme.KEYWORD, 0, true, false, false);
                case LITERAL:
                    return TextStyle.makeStyle(EditorColorScheme.LITERAL);
                case COMMENT:
                    return TextStyle.makeStyle(EditorColorScheme.COMMENT, 0, false, true, false);
                case OPERATOR:
                    return TextStyle.makeStyle(EditorColorScheme.OPERATOR);
                default:
                    return TextStyle.makeStyle(EditorColorScheme.TEXT_NORMAL);
            }
        }
    }
}
