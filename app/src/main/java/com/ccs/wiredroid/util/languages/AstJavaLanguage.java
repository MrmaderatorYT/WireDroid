package com.ccs.wiredroid.util.languages;

import android.content.Context;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.ccs.wiredroid.util.JavaReflectionCompletion;
import com.ccs.wiredroid.util.languages.ast.AstJavaAnalyzeManager;

import java.io.File;

import io.github.rosemoe.sora.lang.EmptyLanguage;
import io.github.rosemoe.sora.lang.Language;
import io.github.rosemoe.sora.lang.QuickQuoteHandler;
import io.github.rosemoe.sora.lang.analysis.AnalyzeManager;
import io.github.rosemoe.sora.lang.completion.CompletionHelper;
import io.github.rosemoe.sora.lang.completion.CompletionPublisher;
import io.github.rosemoe.sora.lang.completion.IdentifierAutoComplete;
import io.github.rosemoe.sora.lang.format.Formatter;
import io.github.rosemoe.sora.lang.smartEnter.NewlineHandler;
import io.github.rosemoe.sora.langs.java.JavaQuoteHandler;
import io.github.rosemoe.sora.text.CharPosition;
import io.github.rosemoe.sora.text.ContentReference;
import io.github.rosemoe.sora.util.MyCharacter;
import io.github.rosemoe.sora.widget.SymbolPairMatch;

/**
 * Java support backed by the AST highlighter in
 * {@link com.ccs.wiredroid.util.languages.ast} instead of the line-based
 * tokenizer.
 *
 * <p>Because the analyzer knows the scope tree, the same identifier can be
 * coloured as a type, a method, a field, a parameter or a local depending on
 * where it appears — which a per-line pattern matcher cannot do. Completion
 * behaviour is unchanged: keywords, file-local declarations and reflection-based
 * member suggestions all still apply.</p>
 */
public class AstJavaLanguage implements Language {

    private static final String[] KEYWORDS = {
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
            "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
            "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
            "interface", "long", "native", "new", "package", "private", "protected", "public",
            "record", "return", "sealed", "short", "static", "strictfp", "super", "switch",
            "synchronized", "this", "throw", "throws", "transient", "try", "var", "void",
            "volatile", "while", "yield", "true", "false", "null"
    };

    private static final SymbolPairMatch SYMBOL_PAIRS = new SymbolPairMatch() {{
        putPair('{', new SymbolPairMatch.SymbolPair("{", "}"));
        putPair('(', new SymbolPairMatch.SymbolPair("(", ")"));
        putPair('[', new SymbolPairMatch.SymbolPair("[", "]"));
        putPair('<', new SymbolPairMatch.SymbolPair("<", ">"));
        putPair('"', new SymbolPairMatch.SymbolPair("\"", "\""));
        putPair('\'', new SymbolPairMatch.SymbolPair("'", "'"));
    }};

    private final AstJavaAnalyzeManager analyzer = new AstJavaAnalyzeManager();
    private final IdentifierAutoComplete autoComplete = new IdentifierAutoComplete(KEYWORDS);
    private final JavaQuoteHandler quoteHandler = new JavaQuoteHandler();
    private final Context appContext;
    private File projectRoot;

    public AstJavaLanguage(@NonNull Context context, @Nullable File projectRoot) {
        this.appContext = context.getApplicationContext();
        this.projectRoot = projectRoot;
    }

    public void setProjectRoot(@Nullable File projectRoot) {
        this.projectRoot = projectRoot;
    }

    @NonNull
    @Override
    public AnalyzeManager getAnalyzeManager() {
        return analyzer;
    }

    @Nullable
    @Override
    public QuickQuoteHandler getQuickQuoteHandler() {
        return quoteHandler;
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
        publisher.setComparator(null);
        String prefix = CompletionHelper.computePrefix(content, position, MyCharacter::isJavaIdentifierPart);
        // Resolved members first, plain identifiers after. The publisher keeps
        // insertion order, and the first item is what Tab accepts — so with the
        // old order Tab completed a word that merely appears in the buffer
        // rather than the method that was actually resolved for this type.
        try {
            JavaReflectionCompletion.contribute(appContext, projectRoot, content, position, prefix, publisher);
        } catch (io.github.rosemoe.sora.lang.completion.CompletionCancelledException cancelled) {
            // The next keystroke superseded this request. Control flow, not a
            // fault — logging it would print on almost every character typed.
        } catch (Throwable failure) {
            // Completion must never take the editor down with it, but a silent
            // catch here made a provider that throws look exactly like one that
            // had nothing to suggest, which is indistinguishable from the
            // feature being broken.
            if (com.ccs.wiredroid.BuildConfig.DEBUG) {
                android.util.Log.w("Completion", "member completion failed", failure);
            }
        }
        // Identifiers and keywords are not members; after a dot they are not
        // valid at all, and being published first they were what Tab accepted.
        if (!JavaReflectionCompletion.isMemberAccess(content, position)) {
            autoComplete.requireAutoComplete(content, position, prefix, publisher, analyzer.getIdentifiers());
            // Classes the file cannot name yet. Published last, so a name that
            // is already in scope is still what Tab takes; picking one of these
            // writes its import as part of the same edit.
            try {
                com.ccs.wiredroid.editor.ImportingCompletion.contribute(
                        content.getReference(), position, prefix, publisher);
            } catch (Throwable ignored) {
                // A missing suggestion must never break the ones that worked.
            }
        }
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
                if (c == '"' || c == '\'') { inString = true; quote = c; continue; }
                if (c == '{' || c == '(' || c == '[') depth++;
                else if (c == '}' || c == ')' || c == ']') depth--;
            }
            return Math.max(0, depth) * 4;
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
        return SYMBOL_PAIRS;
    }

    /** Enter between a bracket pair opens the block. */
    private final NewlineHandler[] newlineHandlers =
            { new BracePairNewlineHandler(useTab()) };

    @Override
    public NewlineHandler[] getNewlineHandlers() {
        return newlineHandlers;
    }
}
