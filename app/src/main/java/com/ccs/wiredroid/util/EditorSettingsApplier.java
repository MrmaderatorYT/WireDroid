package com.ccs.wiredroid.util;

import android.graphics.Typeface;

import io.github.rosemoe.sora.widget.CodeEditor;

/**
 * Застосовує користувацькі налаштування (шрифт, тема, поведінка) на {@link CodeEditor}.
 */
public final class EditorSettingsApplier {

    private EditorSettingsApplier() {}

    public static void apply(CodeEditor editor, AppPreferences prefs, AppTheme theme) {
        if (editor == null) return;
        editor.setColorScheme(theme.buildEditorScheme());
        editor.setTextSize(prefs.getFontSize());
        // One face for both, so the renderer measures text against a single
        // Typeface instead of two equal-but-separate ones.
        Typeface font = prefs.resolveTypeface();
        editor.setTypefaceText(font);
        editor.setTypefaceLineNumber(font);
        editor.setLineNumberEnabled(prefs.isLineNumbers());
        editor.setWordwrap(prefs.isWordWrap());
        editor.setTabWidth(prefs.getTabSize());
        editor.setLineSpacing(0f, prefs.getLineSpacing());
        editor.setLineNumberMarginLeft(12 * editor.getContext().getResources().getDisplayMetrics().density);
        // Was hardcoded on, so there was no way to turn it off; it is a
        // separate aid from the indent guides and gets its own switch.
        editor.setHighlightBracketPair(prefs == null || prefs.isBracketHighlight());
        boolean indentGuides = prefs != null && prefs.isIndentGuides();
        editor.setBlockLineEnabled(indentGuides);
        editor.setHighlightCurrentBlock(indentGuides);
        if (prefs != null && prefs.isFontLigaturesEnabled()) {
            editor.setFontFeatureSettings("\"liga\" 1, \"calt\" 1");
        }
    }
}
