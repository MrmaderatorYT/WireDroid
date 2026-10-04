package com.ccs.wiredroid.util;

import io.github.rosemoe.sora.widget.schemes.EditorColorScheme;
import io.github.rosemoe.sora.widget.schemes.SchemeDarcula;
import io.github.rosemoe.sora.widget.schemes.SchemeEclipse;
import io.github.rosemoe.sora.widget.schemes.SchemeGitHub;
import io.github.rosemoe.sora.widget.schemes.SchemeNotepadXX;
import io.github.rosemoe.sora.widget.schemes.SchemeVS2019;

/**
 * Палітра кольорів інтерфейсу + редактора.
 * <p>Може бути або одним з пресетів, або повністю кастомною темою.</p>
 */
public final class AppTheme {

    public static final String ID_DARCULA   = "darcula";
    public static final String ID_VS_LIGHT  = "vs_light";
    public static final String ID_GITHUB    = "github_light";
    public static final String ID_ECLIPSE   = "eclipse";
    public static final String ID_NOTEPAD   = "notepad";
    public static final String ID_MONOKAI   = "monokai";
    public static final String ID_SOLARIZED = "solarized_dark";
    public static final String ID_DRACULA   = "dracula";
    public static final String ID_NORD      = "nord";
    public static final String ID_OLED      = "oled_dark";
    public static final String ID_CUSTOM    = "custom";

    public final String id;
    public final boolean dark;
    /** UI-кольори, що накладаються на toolbar/console/status. */
    public final int bg;
    public final int toolbar;
    public final int text;
    public final int textDim;
    public final int accent;
    public final int consoleBg;
    public final int consoleText;
    public final int separator;
    public final int statusBar;
    public final int errorText;
    public final int successText;

    /** Кольори підсвітки коду (накладаються на схему). */
    public final int editorKeyword;
    public final int editorString;
    public final int editorComment;
    public final int editorNumber;

    /**
     * Семантичні кольори для AST-підсвітки: типи, методи, поля, анотації,
     * оператори. Якщо тема їх не задає — виводяться з базових кольорів,
     * тож будь-яка (в т.ч. користувацька) тема лишається консистентною.
     */
    public final int editorType;
    public final int editorFunction;
    public final int editorField;
    public final int editorAnnotation;
    public final int editorOperator;

    private AppTheme(Builder b) {
        this.id = b.id;
        this.dark = b.dark;
        this.bg = b.bg;
        this.toolbar = b.toolbar;
        this.text = b.text;
        this.textDim = b.textDim;
        this.accent = b.accent;
        this.consoleBg = b.consoleBg;
        this.consoleText = b.consoleText;
        this.separator = b.separator;
        this.statusBar = b.statusBar;
        this.errorText = b.errorText;
        this.successText = b.successText;
        this.editorKeyword = b.editorKeyword;
        this.editorString = b.editorString;
        this.editorComment = b.editorComment;
        this.editorNumber = b.editorNumber;
        // Unset semantic colours (0) fall back to derivations of the base palette
        // so every theme — including a user's custom one — stays readable.
        this.editorType = b.editorType != 0 ? b.editorType
                : Colors.blend(b.accent, b.text, 0.35f);
        this.editorFunction = b.editorFunction != 0 ? b.editorFunction
                : Colors.blend(b.editorNumber, b.text, 0.30f);
        this.editorField = b.editorField != 0 ? b.editorField
                : Colors.blend(b.editorNumber, b.accent, 0.40f);
        this.editorAnnotation = b.editorAnnotation != 0 ? b.editorAnnotation
                : Colors.blend(b.editorKeyword, b.editorComment, 0.40f);
        this.editorOperator = b.editorOperator != 0 ? b.editorOperator
                : Colors.blend(b.text, b.textDim, 0.35f);
    }

    /** Будує EditorColorScheme з огляду на цю тему. */
    public EditorColorScheme buildEditorScheme() {
        EditorColorScheme base;
        switch (id) {
            case ID_VS_LIGHT:  base = new SchemeVS2019(); break;
            case ID_GITHUB:    base = new SchemeGitHub(); break;
            case ID_ECLIPSE:   base = new SchemeEclipse(); break;
            case ID_NOTEPAD:   base = new SchemeNotepadXX(); break;
            case ID_DARCULA:
            case ID_MONOKAI:
            case ID_SOLARIZED:
            case ID_DRACULA:
            case ID_NORD:
            case ID_CUSTOM:
            default:           base = new SchemeDarcula(); break;
        }
        // Накладаємо ключові кольори з нашої теми.
        base.setColor(EditorColorScheme.WHOLE_BACKGROUND,        bg);
        base.setColor(EditorColorScheme.LINE_NUMBER_BACKGROUND,  bg);
        base.setColor(EditorColorScheme.CURRENT_LINE,            shade(bg, dark ? 0.06f : -0.04f));
        base.setColor(EditorColorScheme.TEXT_NORMAL,             text);
        base.setColor(EditorColorScheme.LINE_NUMBER,             textDim);
        base.setColor(EditorColorScheme.LINE_NUMBER_CURRENT,     accent);
        base.setColor(EditorColorScheme.SELECTION_HANDLE,        accent);
        base.setColor(EditorColorScheme.SELECTION_INSERT,        accent);
        base.setColor(EditorColorScheme.SELECTED_TEXT_BACKGROUND, withAlpha(accent, 0x55));
        base.setColor(EditorColorScheme.KEYWORD,                 editorKeyword);
        base.setColor(EditorColorScheme.LITERAL,                 editorString);
        base.setColor(EditorColorScheme.COMMENT,                 editorComment);
        base.setColor(EditorColorScheme.LINE_DIVIDER,            separator);
        // Semantic slots used by the AST highlighter and the Gradle/properties
        // languages. Without these the preset scheme's own colours leak through
        // and clash with a light UI theme.
        base.setColor(EditorColorScheme.IDENTIFIER_NAME,         editorType);
        base.setColor(EditorColorScheme.FUNCTION_NAME,           editorFunction);
        base.setColor(EditorColorScheme.IDENTIFIER_VAR,          editorField);
        base.setColor(EditorColorScheme.ANNOTATION,              editorAnnotation);
        base.setColor(EditorColorScheme.OPERATOR,                editorOperator);
        base.setColor(EditorColorScheme.ATTRIBUTE_NAME,          editorFunction);
        base.setColor(EditorColorScheme.ATTRIBUTE_VALUE,         editorType);
        base.setColor(EditorColorScheme.HTML_TAG,                editorKeyword);
        base.setColor(EditorColorScheme.BLOCK_LINE,               withAlpha(textDim, 0x33));
        base.setColor(EditorColorScheme.BLOCK_LINE_CURRENT,       withAlpha(accent, 0x99));
        return base;
    }

    private static int shade(int color, float amount) {
        int a = (color >>> 24) & 0xFF;
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        if (amount >= 0) {
            r = (int) (r + (255 - r) * amount);
            g = (int) (g + (255 - g) * amount);
            b = (int) (b + (255 - b) * amount);
        } else {
            float d = 1f + amount;
            r = (int) (r * d);
            g = (int) (g * d);
            b = (int) (b * d);
        }
        return (a << 24) | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
    }

    private static int withAlpha(int color, int alpha) {
        return (alpha << 24) | (color & 0x00FFFFFF);
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    /** Створює тему за id; для CUSTOM повертає тему зібрану з prefs. */
    public static AppTheme byId(String id, AppPreferences prefs) {
        if (id == null) id = ID_DARCULA;
        switch (id) {
            case ID_VS_LIGHT:  return vsLight();
            case ID_GITHUB:    return githubLight();
            case ID_ECLIPSE:   return eclipse();
            case ID_NOTEPAD:   return notepad();
            case ID_MONOKAI:   return monokai();
            case ID_SOLARIZED: return solarized();
            case ID_DRACULA:   return dracula();
            case ID_NORD:      return nord();
            case ID_OLED:      return oledDark();
            case ID_CUSTOM:    return custom(prefs);
            case ID_DARCULA:
            default:           return darcula();
        }
    }

    public static AppTheme[] presets() {
        return new AppTheme[] {
                darcula(), oledDark(), vsLight(), githubLight(), eclipse(),
                notepad(), monokai(), solarized(), dracula(), nord()
        };
    }

    private static AppTheme oledDark() {
        return new Builder(ID_OLED, true)
                .bg(0xFF000000).toolbar(0xFF000000).text(0xFFFFFFFF).textDim(0xFF999999)
                .accent(0xFF4A86C8).consoleBg(0xFF000000).consoleText(0xFFFFFFFF)
                .separator(0xFF222222).statusBar(0xFF000000)
                .errorText(0xFFFF5252).successText(0xFF4CAF50)
                .editorKeyword(0xFFCC7832).editorString(0xFF6A8759)
                .editorComment(0xFF808080).editorNumber(0xFF6897BB)
                .semantic(0xFF9876AA, 0xFFFFC66D, 0xFF9876AA, 0xFFBBB529, 0xFFA9B7C6)
                .build();
    }

    private static AppTheme darcula() {
        return new Builder(ID_DARCULA, true)
                .bg(0xFF2B2B2B).toolbar(0xFF3C3F41).text(0xFFBBBBBB).textDim(0xFF808080)
                .accent(0xFF4A86C8).consoleBg(0xFF1E1E1E).consoleText(0xFFA9B7C6)
                .separator(0xFF515151).statusBar(0xFF3C3F41)
                .errorText(0xFFFF6B6B).successText(0xFF499C54)
                .editorKeyword(0xFFCC7832).editorString(0xFF6A8759)
                .editorComment(0xFF808080).editorNumber(0xFF6897BB)
                .semantic(0xFF4EC9B0, 0xFFFFC66D, 0xFF9876AA, 0xFFBBB529, 0xFFA9B7C6)
                .build();
    }

    private static AppTheme vsLight() {
        return new Builder(ID_VS_LIGHT, false)
                .bg(0xFFFFFFFF).toolbar(0xFFE7E7E7).text(0xFF1E1E1E).textDim(0xFF606060)
                .accent(0xFF1F6FEB).consoleBg(0xFFF5F5F5).consoleText(0xFF222222)
                .separator(0xFFCCCCCC).statusBar(0xFFE7E7E7)
                .errorText(0xFFD13438).successText(0xFF107C10)
                .editorKeyword(0xFF0000FF).editorString(0xFFA31515)
                .editorComment(0xFF008000).editorNumber(0xFF09885A)
                .semantic(0xFF267F99, 0xFF795E26, 0xFF001080, 0xFF808000, 0xFF000000)
                .build();
    }

    private static AppTheme githubLight() {
        return new Builder(ID_GITHUB, false)
                .bg(0xFFFFFFFF).toolbar(0xFFF6F8FA).text(0xFF24292E).textDim(0xFF6A737D)
                .accent(0xFF0366D6).consoleBg(0xFFF6F8FA).consoleText(0xFF24292E)
                .separator(0xFFE1E4E8).statusBar(0xFFF6F8FA)
                .errorText(0xFFD73A49).successText(0xFF22863A)
                .editorKeyword(0xFFD73A49).editorString(0xFF032F62)
                .editorComment(0xFF6A737D).editorNumber(0xFF005CC5)
                .semantic(0xFF6F42C1, 0xFF6F42C1, 0xFFE36209, 0xFF6A737D, 0xFF24292E)
                .build();
    }

    private static AppTheme eclipse() {
        return new Builder(ID_ECLIPSE, false)
                .bg(0xFFFFFFFF).toolbar(0xFFEAEAEA).text(0xFF000000).textDim(0xFF505050)
                .accent(0xFF2A6CCC).consoleBg(0xFFFFFFFF).consoleText(0xFF000000)
                .separator(0xFFC4C4C4).statusBar(0xFFEAEAEA)
                .errorText(0xFFB00020).successText(0xFF2A8B2A)
                .editorKeyword(0xFF7F0055).editorString(0xFF2A00FF)
                .editorComment(0xFF3F7F5F).editorNumber(0xFF000000)
                .semantic(0xFF2A6CCC, 0xFF3F5FBF, 0xFF0000C0, 0xFF646464, 0xFF000000)
                .build();
    }

    private static AppTheme notepad() {
        return new Builder(ID_NOTEPAD, false)
                .bg(0xFFFAFAFA).toolbar(0xFFE0E0E0).text(0xFF111111).textDim(0xFF666666)
                .accent(0xFF1976D2).consoleBg(0xFFFFFFFF).consoleText(0xFF111111)
                .separator(0xFFCCCCCC).statusBar(0xFFE0E0E0)
                .errorText(0xFFD32F2F).successText(0xFF388E3C)
                .editorKeyword(0xFF0000FF).editorString(0xFF008080)
                .editorComment(0xFF008000).editorNumber(0xFFFF8000)
                .semantic(0xFF8000FF, 0xFF804000, 0xFF0080C0, 0xFF808000, 0xFF000080)
                .build();
    }

    private static AppTheme monokai() {
        return new Builder(ID_MONOKAI, true)
                .bg(0xFF272822).toolbar(0xFF1E1F1C).text(0xFFF8F8F2).textDim(0xFF888884)
                .accent(0xFFA6E22E).consoleBg(0xFF1B1C18).consoleText(0xFFF8F8F2)
                .separator(0xFF3E3D32).statusBar(0xFF1E1F1C)
                .errorText(0xFFF92672).successText(0xFFA6E22E)
                .editorKeyword(0xFFF92672).editorString(0xFFE6DB74)
                .editorComment(0xFF75715E).editorNumber(0xFFAE81FF)
                .semantic(0xFF66D9EF, 0xFFA6E22E, 0xFFFD971F, 0xFFAE81FF, 0xFFF92672)
                .build();
    }

    private static AppTheme solarized() {
        return new Builder(ID_SOLARIZED, true)
                .bg(0xFF002B36).toolbar(0xFF073642).text(0xFF93A1A1).textDim(0xFF586E75)
                .accent(0xFF268BD2).consoleBg(0xFF001E26).consoleText(0xFF93A1A1)
                .separator(0xFF073642).statusBar(0xFF073642)
                .errorText(0xFFDC322F).successText(0xFF859900)
                .editorKeyword(0xFF859900).editorString(0xFF2AA198)
                .editorComment(0xFF586E75).editorNumber(0xFFD33682)
                .semantic(0xFFB58900, 0xFF268BD2, 0xFFCB4B16, 0xFF6C71C4, 0xFF93A1A1)
                .build();
    }

    private static AppTheme dracula() {
        return new Builder(ID_DRACULA, true)
                .bg(0xFF282A36).toolbar(0xFF1E1F29).text(0xFFF8F8F2).textDim(0xFF6272A4)
                .accent(0xFFBD93F9).consoleBg(0xFF1A1B23).consoleText(0xFFF8F8F2)
                .separator(0xFF44475A).statusBar(0xFF1E1F29)
                .errorText(0xFFFF5555).successText(0xFF50FA7B)
                .editorKeyword(0xFFFF79C6).editorString(0xFFF1FA8C)
                .editorComment(0xFF6272A4).editorNumber(0xFFBD93F9)
                .semantic(0xFF8BE9FD, 0xFF50FA7B, 0xFFFFB86C, 0xFFFF79C6, 0xFFF8F8F2)
                .build();
    }

    private static AppTheme nord() {
        return new Builder(ID_NORD, true)
                .bg(0xFF2E3440).toolbar(0xFF3B4252).text(0xFFD8DEE9).textDim(0xFF7B8794)
                .accent(0xFF88C0D0).consoleBg(0xFF252A33).consoleText(0xFFD8DEE9)
                .separator(0xFF434C5E).statusBar(0xFF3B4252)
                .errorText(0xFFBF616A).successText(0xFFA3BE8C)
                .editorKeyword(0xFF81A1C1).editorString(0xFFA3BE8C)
                .editorComment(0xFF616E88).editorNumber(0xFFB48EAD)
                .semantic(0xFF8FBCBB, 0xFF88C0D0, 0xFFD08770, 0xFFB48EAD, 0xFFECEFF4)
                .build();
    }

    private static AppTheme custom(AppPreferences prefs) {
        int bg = prefs.getCustomBg();
        boolean dark = isDark(bg);
        return new Builder(ID_CUSTOM, dark)
                .bg(bg)
                .toolbar(prefs.getCustomToolbar())
                .text(prefs.getCustomFg())
                .textDim(Colors.blend(prefs.getCustomFg(), bg, 0.45f))
                .accent(prefs.getCustomAccent())
                .consoleBg(prefs.getCustomConsoleBg())
                .consoleText(prefs.getCustomFg())
                .separator(Colors.blend(prefs.getCustomFg(), bg, 0.65f))
                .statusBar(prefs.getCustomToolbar())
                .errorText(0xFFFF6B6B).successText(0xFF499C54)
                .editorKeyword(prefs.getCustomKeyword())
                .editorString(prefs.getCustomString())
                .editorComment(prefs.getCustomComment())
                .editorNumber(prefs.getCustomAccent())
                .build();
    }

    private static boolean isDark(int color) {
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        double l = (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0;
        return l < 0.5;
    }


    public static String displayName(String id) {
        switch (id) {
            case ID_VS_LIGHT:  return "Visual Studio Light";
            case ID_GITHUB:    return "GitHub Light";
            case ID_ECLIPSE:   return "Eclipse";
            case ID_NOTEPAD:   return "Notepad";
            case ID_MONOKAI:   return "Monokai";
            case ID_SOLARIZED: return "Solarized Dark";
            case ID_DRACULA:   return "Dracula";
            case ID_NORD:      return "Nord";
            case ID_CUSTOM:    return "Custom";
            case ID_DARCULA:
            default:           return "Darcula";
        }
    }

    private static final class Builder {
        final String id; final boolean dark;
        int bg, toolbar, text, textDim, accent, consoleBg, consoleText;
        int separator, statusBar, errorText, successText;
        int editorKeyword, editorString, editorComment, editorNumber;
        int editorType, editorFunction, editorField, editorAnnotation, editorOperator;
        Builder(String id, boolean dark) { this.id = id; this.dark = dark; }
        Builder bg(int v)            { this.bg = v; return this; }
        Builder toolbar(int v)       { this.toolbar = v; return this; }
        Builder text(int v)          { this.text = v; return this; }
        Builder textDim(int v)       { this.textDim = v; return this; }
        Builder accent(int v)        { this.accent = v; return this; }
        Builder consoleBg(int v)     { this.consoleBg = v; return this; }
        Builder consoleText(int v)   { this.consoleText = v; return this; }
        Builder separator(int v)     { this.separator = v; return this; }
        Builder statusBar(int v)     { this.statusBar = v; return this; }
        Builder errorText(int v)     { this.errorText = v; return this; }
        Builder successText(int v)   { this.successText = v; return this; }
        Builder editorKeyword(int v) { this.editorKeyword = v; return this; }
        Builder editorString(int v)  { this.editorString = v; return this; }
        Builder editorComment(int v) { this.editorComment = v; return this; }
        Builder editorNumber(int v)  { this.editorNumber = v; return this; }
        /** Semantic colours: types, methods, fields, annotations, operators. */
        Builder semantic(int type, int function, int field, int annotation, int operator) {
            this.editorType = type;
            this.editorFunction = function;
            this.editorField = field;
            this.editorAnnotation = annotation;
            this.editorOperator = operator;
            return this;
        }
        AppTheme build() { return new AppTheme(this); }
    }
}
