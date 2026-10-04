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
 * C/C++ language support class for Sora Editor.
 * Provides syntax highlighting and basic keyword auto-completion.
 */
public class CppLanguage implements Language {

    private final CppAnalyzeManager manager;
    private final IdentifierAutoComplete autoComplete;

    private static final String[] CPP_KEYWORDS = {
        "alignas", "alignof", "and", "and_eq", "asm", "atomic_cancel", "atomic_commit", 
        "atomic_noexcept", "auto", "bitand", "bitor", "bool", "break", "case", "catch", 
        "char", "char8_t", "char16_t", "char32_t", "class", "compl", "concept", "const", 
        "consteval", "constexpr", "constinit", "const_cast", "continue", "co_await", 
        "co_return", "co_yield", "decltype", "default", "delete", "do", "double", 
        "dynamic_cast", "else", "enum", "explicit", "export", "extern", "false", "float", 
        "for", "friend", "goto", "if", "inline", "int", "long", "mutable", "namespace", 
        "new", "noexcept", "not", "not_eq", "nullptr", "operator", "or", "or_eq", 
        "private", "protected", "public", "reflexpr", "register", "reinterpret_cast", 
        "requires", "return", "short", "signed", "sizeof", "static", "static_assert", 
        "static_cast", "struct", "switch", "template", "this", "thread_local", "throw", 
        "true", "try", "typedef", "typeid", "typename", "union", "unsigned", "using", 
        "virtual", "void", "volatile", "wchar_t", "while", "xor", "xor_eq"
    };

    public CppLanguage() {
        manager = new CppAnalyzeManager();
        autoComplete = new IdentifierAutoComplete(CPP_KEYWORDS);
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

    private static class CppAnalyzeManager extends SimpleAnalyzeManager<Object> {
        private static final Set<String> KEYWORDS_SET = new HashSet<>();
        static {
            for (String kw : CPP_KEYWORDS) {
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
            long stylePreprocessor = TextStyle.makeStyle(EditorColorScheme.KEYWORD, 0, false, true, false);

            boolean inBlockComment = false;

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

                if (inBlockComment) {
                    builder.addIfNeeded(line, col, styleComment);
                    if (c == '*' && i + 1 < len && text.charAt(i + 1) == '/') {
                        inBlockComment = false;
                        i += 2;
                    } else {
                        i++;
                    }
                    continue;
                }

                if (c == '/' && i + 1 < len && text.charAt(i + 1) == '*') {
                    inBlockComment = true;
                    builder.addIfNeeded(line, col, styleComment);
                    i += 2;
                    continue;
                }

                if (c == '/' && i + 1 < len && text.charAt(i + 1) == '/') {
                    builder.addIfNeeded(line, col, styleComment);
                    while (i < len && text.charAt(i) != '\n') {
                        i++;
                    }
                    continue;
                }

                if (c == '#') {
                    builder.addIfNeeded(line, col, stylePreprocessor);
                    i++;
                    while (i < len && text.charAt(i) != '\n' && text.charAt(i) != ' ' && text.charAt(i) != '\t') {
                        i++;
                    }
                    continue;
                }

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
                    while (i < len && (Character.isDigit(text.charAt(i)) || text.charAt(i) == '.' 
                            || text.charAt(i) == 'f' || text.charAt(i) == 'F' || text.charAt(i) == 'L' || text.charAt(i) == 'l'
                            || text.charAt(i) == 'u' || text.charAt(i) == 'U' || text.charAt(i) == 'x' || text.charAt(i) == 'X')) {
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
