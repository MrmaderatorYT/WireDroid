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
import java.util.Locale;
import java.util.Set;

public class SqlLanguage implements Language {
    private final SqlAnalyzeManager manager = new SqlAnalyzeManager();
    private final IdentifierAutoComplete autoComplete = new IdentifierAutoComplete(
            new String[]{"SELECT","FROM","WHERE","INSERT","CREATE","TABLE","INDEX","JOIN","GROUP","ORDER","UPDATE","DELETE","DROP","ALTER"});

    @NonNull @Override public AnalyzeManager getAnalyzeManager() { return manager; }
    @Nullable @Override public QuickQuoteHandler getQuickQuoteHandler() { return null; }
    @Override public void destroy() {}
    @Override public int getInterruptionLevel() { return INTERRUPTION_LEVEL_STRONG; }
    @Override public void requireAutoComplete(@NonNull ContentReference c, @NonNull CharPosition p, @NonNull CompletionPublisher pub, @NonNull Bundle e) {
        String prefix = io.github.rosemoe.sora.lang.completion.CompletionHelper.computePrefix(c, p, Character::isJavaIdentifierPart);
        autoComplete.requireAutoComplete(c, p, prefix, pub, null);
    }
    @Override public int getIndentAdvance(@NonNull ContentReference t, int l, int c) { return 0; }
    @Override public boolean useTab() { return false; }
    @NonNull @Override public Formatter getFormatter() { return EmptyLanguage.EmptyFormatter.INSTANCE; }
    @Override public SymbolPairMatch getSymbolPairs() { return new SymbolPairMatch.DefaultSymbolPairs(); }
    @Override public NewlineHandler[] getNewlineHandlers() { return new NewlineHandler[0]; }

    private static class SqlAnalyzeManager extends SimpleAnalyzeManager<Object> {
        private static final Set<String> KW_SET = new HashSet<>();
        static {
            String[] kws = {"SELECT","FROM","WHERE","INSERT","INTO","VALUES","UPDATE","SET","DELETE",
                    "CREATE","TABLE","DROP","ALTER","INDEX","GROUP","BY","ORDER","HAVING","JOIN",
                    "INNER","LEFT","RIGHT","ON","AS","AND","OR","NOT","IN","LIKE","BETWEEN","IS",
                    "NULL","EXISTS","DISTINCT","COUNT","SUM","AVG","MIN","MAX","CASE","WHEN",
                    "THEN","ELSE","END","LIMIT","OFFSET","PRIMARY","KEY","UNIQUE","INTEGER",
                    "VARCHAR","TEXT","REAL","PRAGMA","EXPLAIN","ASC","DESC","UNION","ALL"};
            for (String k : kws) KW_SET.add(k.toLowerCase(Locale.ROOT));
        }

        @Override protected Styles analyze(StringBuilder text, Delegate<Object> delegate) {
            MappedSpans.Builder builder = new MappedSpans.Builder();
            int line = 0, lineStart = 0;
            int len = text.length();
            long kwStyle = TextStyle.makeStyle(EditorColorScheme.KEYWORD, 0, true, false, false);
            long strStyle = TextStyle.makeStyle(EditorColorScheme.LITERAL);
            long cmtStyle = TextStyle.makeStyle(EditorColorScheme.COMMENT, 0, false, true, false);
            long numStyle = TextStyle.makeStyle(EditorColorScheme.LITERAL);

            for (int i = 0; i < len; ) {
                if (delegate.isCancelled()) return null;
                char c = text.charAt(i);
                if (c == '\n') { builder.determine(line); line++; lineStart = i + 1; i++; continue; }
                int col = i - lineStart;

                if (c == '-' && i + 1 < len && text.charAt(i + 1) == '-') {
                    while (i < len && text.charAt(i) != '\n') i++;
                    continue;
                }
                if (c == '/' && i + 1 < len && text.charAt(i + 1) == '*') {
                    builder.addIfNeeded(line, col, cmtStyle);
                    i += 2;
                    while (i < len && !(text.charAt(i) == '*' && i + 1 < len && text.charAt(i + 1) == '/')) i++;
                    i += 2;
                    continue;
                }
                if (c == '\'') {
                    builder.addIfNeeded(line, col, strStyle);
                    i++;
                    while (i < len && text.charAt(i) != '\'') { if (text.charAt(i) == '\\' && i+1<len) i++; i++; }
                    if (i < len) i++;
                    continue;
                }
                if (Character.isDigit(c)) {
                    builder.addIfNeeded(line, col, numStyle);
                    while (i < len && (Character.isDigit(text.charAt(i)) || text.charAt(i) == '.')) i++;
                    continue;
                }
                if (Character.isLetter(c) || c == '_') {
                    int start = i;
                    while (i < len && (Character.isLetterOrDigit(text.charAt(i)) || text.charAt(i) == '_')) i++;
                    String word = text.substring(start, i).toLowerCase(Locale.ROOT);
                    if (KW_SET.contains(word)) builder.addIfNeeded(line, col, kwStyle);
                    continue;
                }
                i++;
            }
            builder.determine(line);
            return new Styles(builder.build());
        }
    }
}
