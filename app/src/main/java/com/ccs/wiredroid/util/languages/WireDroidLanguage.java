package com.ccs.wiredroid.util.languages;

import android.content.Context;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.ccs.wiredroid.util.JavaReflectionCompletion;
import java.io.File;

import io.github.rosemoe.sora.lang.completion.CompletionHelper;
import io.github.rosemoe.sora.lang.completion.CompletionPublisher;
import io.github.rosemoe.sora.langs.java.JavaLanguage;
import io.github.rosemoe.sora.text.CharPosition;
import io.github.rosemoe.sora.text.ContentReference;
import io.github.rosemoe.sora.util.MyCharacter;
import io.github.rosemoe.sora.widget.SymbolPairMatch;

/**
 * Java з Rosemoe + рефлексія методів після "." та класи з проєкту / import.
 */
public class WireDroidLanguage extends JavaLanguage {

    private final Context appContext;
    private File projectRoot;

    private static final SymbolPairMatch SYMBOL_PAIRS = new SymbolPairMatch() {{
        putPair('{', new SymbolPairMatch.SymbolPair("{", "}"));
        putPair('(', new SymbolPairMatch.SymbolPair("(", ")"));
        putPair('[', new SymbolPairMatch.SymbolPair("[", "]"));
        putPair('<', new SymbolPairMatch.SymbolPair("<", ">"));
        putPair('"', new SymbolPairMatch.SymbolPair("\"", "\""));
        putPair('\'', new SymbolPairMatch.SymbolPair("'", "'"));
    }};

    public WireDroidLanguage(@NonNull Context context, @Nullable File projectRoot) {
        this.appContext = context.getApplicationContext();
        this.projectRoot = projectRoot;
    }

    public void setProjectRoot(@Nullable File projectRoot) {
        this.projectRoot = projectRoot;
    }

    @Override
    public SymbolPairMatch getSymbolPairs() {
        return SYMBOL_PAIRS;
    }

    @Override
    public void requireAutoComplete(@NonNull ContentReference content, @NonNull CharPosition position,
                                    @NonNull CompletionPublisher publisher, @NonNull Bundle extraArguments) {
        publisher.setComparator(null);
        if (!JavaReflectionCompletion.isMemberAccess(content, position)) {
        // Identifiers and keywords are not members; after a dot they are not
        // valid at all, and being published first they were what Tab accepted.
            super.requireAutoComplete(content, position, publisher, extraArguments);
        }
        try {
            String prefix = CompletionHelper.computePrefix(content, position, MyCharacter::isJavaIdentifierPart);
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
    }

    /** Enter between a bracket pair opens the block. */
    private final io.github.rosemoe.sora.lang.smartEnter.NewlineHandler[] newlineHandlers =
            { new BracePairNewlineHandler(useTab()) };

    @Override
    public io.github.rosemoe.sora.lang.smartEnter.NewlineHandler[] getNewlineHandlers() {
        return newlineHandlers;
    }
}
