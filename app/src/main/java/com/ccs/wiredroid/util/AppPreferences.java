package com.ccs.wiredroid.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.view.KeyEvent;

/**
 * Централізований доступ до користувацьких налаштувань WireDroid.
 * Зберігає шрифти, тему, поведінку редактора та параметри компілятора.
 */
    public final class AppPreferences {

    public static final String PREFS_NAME = "com.ccs.wiredroid.prefs";

    // ── Editor ────────────────────────────────────────────────
    private static final String K_FONT_SIZE        = "font_size";
    private static final String K_FONT_FAMILY      = "font_family";
    private static final String K_TAB_SIZE         = "tab_size";
    private static final String K_LINE_NUMBERS     = "line_numbers";
    private static final String K_WORD_WRAP        = "word_wrap";
    private static final String K_LINE_SPACING_X10 = "line_spacing_x10"; // зберігаємо як ціле *10
    /** Share of the editor row given to the left pane, as an int *1000. */
    private static final String K_SPLIT_RATIO_X1000 = "split_ratio_x1000";
    private static final String K_AUTO_SAVE        = "auto_save";
    private static final String K_FORMAT_ON_SAVE   = "format_on_save";
    private static final String K_MINIMAP          = "minimap_enabled_v2";
    /**
     * Kotlin language version passed to the bundled compiler.
     *
     * <p>Empty means the compiler's own default. Stored as the version string
     * rather than an index, so adding a version to the list later cannot
     * silently change what an existing project compiles as.</p>
     */
    private static final String K_KOTLIN_LANG       = "kotlin_language_version";

    /**
     * Which toolbar buttons are shown.
     *
     * <p>Named per button rather than as one packed set, so a button added later
     * defaults to visible without having to migrate a stored value.</p>
     */
    private static final String K_TB_UNDO          = "toolbar_undo";
    private static final String K_TB_REDO          = "toolbar_redo";
    private static final String K_TB_DEBUG         = "toolbar_debug";
    private static final String K_TB_FIND          = "toolbar_find";
    private static final String K_TB_AI            = "toolbar_ai_chat";
    private static final String K_TB_FORMAT        = "tb_format";
    private static final String K_AST_HIGHLIGHT    = "ast_highlighting";
    private static final String K_LIVE_PROBLEMS    = "live_problems";
    private static final String K_DIAG_UNDERLINE   = "diag_underline";
    private static final String K_DIAG_LIGHTBULB   = "diag_lightbulb";
    private static final String K_AUTO_SEARCH      = "auto_search";
    private static final String K_PERF_AST         = "perf_ast";
    private static final String K_PERF_LIVE        = "perf_live_problems";
    private static final String K_PERF_AUTO_SAVE   = "perf_auto_save";
    private static final String K_PERF_FORMAT      = "perf_format_on_save";
    private static final String K_PERF_MINIMAP     = "perf_minimap";
    private static final String K_PERF_SEARCH      = "perf_auto_search";
    private static final String K_PERF_VERBOSE     = "perf_verbose_logging";
    private static final String K_PS_AST           = "ps_ast";
    private static final String K_PS_LIVE          = "ps_live_problems";
    private static final String K_PS_AUTO_SAVE     = "ps_auto_save";
    private static final String K_PS_FORMAT        = "ps_format_on_save";
    private static final String K_PS_MINIMAP       = "ps_minimap";
    private static final String K_PERF_INLAY       = "perf_inlay_hints";
    private static final String K_PS_INLAY         = "ps_inlay_hints";
    private static final String K_PERF_GUTTER      = "perf_git_gutter";
    private static final String K_PS_GUTTER        = "ps_git_gutter";
    private static final String K_PS_SEARCH        = "ps_auto_search";
    private static final String K_PS_VERBOSE       = "ps_verbose_logging";
    private static final String K_READ_ONLY_FILES  = "read_only_files";
    private static final String K_SHORTCUT_PREFIX  = "shortcut_";

    // ── Bottom panel tabs ─────────────────────────────────────
    private static final String K_PANEL_ORDER      = "panel_order";
    private static final String K_PANEL_HIDDEN     = "panel_hidden";
    private static final String K_RUN_METRICS      = "run_metrics_visible";

    // ── Theme ─────────────────────────────────────────────────
    private static final String K_THEME_ID         = "theme_id";
    private static final String K_THEME_DARK       = "theme_id_dark";
    private static final String K_THEME_LIGHT      = "theme_id_light";
    private static final String K_CUSTOM_BG        = "custom_bg";
    private static final String K_CUSTOM_FG        = "custom_fg";
    private static final String K_CUSTOM_ACCENT    = "custom_accent";
    private static final String K_CUSTOM_TOOLBAR   = "custom_toolbar";
    private static final String K_CUSTOM_CONSOLE_BG= "custom_console_bg";
    private static final String K_CUSTOM_KEYWORD   = "custom_keyword";
    private static final String K_CUSTOM_STRING    = "custom_string";
    private static final String K_CUSTOM_COMMENT   = "custom_comment";

    // ── Structure panel ───────────────────────────────────────
    private static final String K_STRUCT_SORT      = "structure_sort";
    private static final String K_STRUCT_FIELDS    = "structure_show_fields";
    private static final String K_STRUCT_NONPUBLIC = "structure_show_non_public";

    // ── Compiler ──────────────────────────────────────────────
    private static final String K_JAVA_TARGET      = "java_target";

    // ── Native code (JNI) ─────────────────────────────────────
    private static final String K_NATIVE_ENABLED   = "native_enabled";
    private static final String K_NATIVE_BACKEND   = "native_backend";

    // ── Misc ──────────────────────────────────────────────────
    private static final String K_PROJECT_ROOT     = "project_root";
    private static final String K_FILE_ENCODING    = "file_encoding";
    private static final String K_VERBOSE_LOGGING  = "verbose_logging";
    // ── Power Saving ─────────────────────────────────────────
    private static final String K_POWER_SAVING_MODE = "power_saving_mode";
    private static final String K_POWER_SAVING_THRESHOLD = "power_saving_battery_threshold";
    private static final String K_POWER_SAVING_RESPECT_SYSTEM = "power_saving_respect_system";
    private static final String K_POWER_SAVING_REDUCE_ANIMATIONS = "power_saving_reduce_animations";
    private static final String K_POWER_SAVING_SCAN_INTERVAL = "power_saving_scan_interval_sec";
    private static final String K_AUTO_SAVE_DELAY_MS = "auto_save_delay_ms";
    // ── AI Agent ─────────────────────────────────────────────
    private static final String K_INCLUSIVE_MODE = "inclusive_mode";
    private static final String K_CUSTOM_FONT_PATH = "custom_font_path";
    private static final String K_CUSTOM_FONT_URL = "custom_font_url";
    private static final String K_CUSTOM_FONT_VERSION = "custom_font_version";
    // ── First run ────────────────────────────────────────────
    // Once true, the licence notice is never shown again. Versioned so a future
    // change to the notice can ask again by bumping the key rather than by
    // silently reusing consent given to different wording.
    private static final String K_JAVA_LICENCE_ACCEPTED = "java_licence_accepted_v1";
    private static final String K_APP_LANGUAGE = "app_language";

    // ── v1.11.2 new settings ─────────────────────────────────
    private static final String K_AUTO_CLOSE_PAIRS     = "auto_close_pairs";
    private static final String K_HIGHLIGHT_LINE       = "highlight_current_line";
    private static final String K_INDENT_GUIDES        = "indent_guides";
    private static final String K_BRACKET_HIGHLIGHT    = "bracket_highlight";
    private static final String K_KEY_BAR_SYMBOLS      = "key_bar_symbols";
    private static final String K_CONTEXTUAL_SYMBOLS   = "contextual_symbols";
    public static final String DEFAULT_KEY_BAR_SYMBOLS = "{ } ( ) [ ] ; . = \" + - * / ▲ ▼ Tab";
    private static final String K_CLEAR_CONSOLE_ON_RUN = "clear_console_on_run";
    private static final String K_COMPILER_WARNINGS    = "compiler_warnings"; // 0=none, 1=default, 2=deprecation, 3=all
    private static final String K_SHOW_DOTFILES        = "show_dotfiles";
    private static final String K_TODO_SCOPE_FILE = "todo_scope_current_file";
    private static final String K_SEARCH_EXCLUDES      = "search_excludes";
    private static final String K_DEFAULT_PROJECT_PATH = "default_project_path";
    private static final String K_CONFIRM_FILE_DELETE  = "confirm_file_delete";

    // Family constants
    public static final int FONT_MONOSPACE     = 0;
    public static final int FONT_SANS          = 1;
    public static final int FONT_SERIF         = 2;
    public static final int FONT_DEFAULT       = 3;
    public static final int FONT_JETBRAINS     = 4;
    public static final int FONT_FIRA_CODE     = 5;
    public static final int FONT_SOURCE_CODE   = 6;
    public static final int FONT_DEJAVU_MONO   = 7;
    public static final int FONT_ROBOTO_MONO   = 8;

    // Java target constants (передаємо як ECJ -1.8 / -11).
    // Повний перелік випусків — у JavaVersions; тут лише ті, на які посилається код.
    public static final String JAVA_8  = "1.8";
    public static final String JAVA_11 = "11";
    public static final String JAVA_17 = "17";
    public static final String JAVA_21 = "21";

    private final SharedPreferences prefs;
    private final Context context;

    public AppPreferences(Context ctx) {
        this.context = ctx.getApplicationContext();
        this.prefs = this.context
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public SharedPreferences raw() {
        return prefs;
    }

    public String getShortcut(String action, String defaultValue) {
        return prefs.getString(K_SHORTCUT_PREFIX + action, defaultValue);
    }
    public void setShortcut(String action, String value) {
        prefs.edit().putString(K_SHORTCUT_PREFIX + action, value).apply();
    }
    public void resetShortcuts() {
        prefs.edit().remove(K_SHORTCUT_PREFIX + "duplicate_line")
                .remove(K_SHORTCUT_PREFIX + "toggle_comment")
                .remove(K_SHORTCUT_PREFIX + "save")
                .remove(K_SHORTCUT_PREFIX + "find")
                .remove(K_SHORTCUT_PREFIX + "run").remove(K_SHORTCUT_PREFIX + "delete_line").apply();
    }

    public boolean matchesShortcut(String action, String def, KeyEvent event) {
        String shortcut = getShortcut(action, def);
        StringBuilder s = new StringBuilder();
        if (event.isCtrlPressed()) s.append("Ctrl+");
        if (event.isAltPressed()) s.append("Alt+");
        if (event.isShiftPressed()) s.append("Shift+");
        s.append(KeyEvent.keyCodeToString(event.getKeyCode()).replace("KEYCODE_", ""));
        return shortcut.equalsIgnoreCase(s.toString());
    }

    /** Empty means follow the device language automatically. */
    public String getAppLanguage() { return prefs.getString(K_APP_LANGUAGE, ""); }
    public void setAppLanguage(String tag) {
        prefs.edit().putString(K_APP_LANGUAGE, tag == null ? "" : tag).apply();
    }

    // ── Editor ────────────────────────────────────────────────

    public int getFontSize()              { return prefs.getInt(K_FONT_SIZE, 14); }
    public void setFontSize(int v)        { prefs.edit().putInt(K_FONT_SIZE, v).apply(); }

    public int getFontFamily()            { return prefs.getInt(K_FONT_FAMILY, FONT_MONOSPACE); }
    public void setFontFamily(int v)      { prefs.edit().putInt(K_FONT_FAMILY, v).apply(); }

    public int getTabSize()               { return prefs.getInt(K_TAB_SIZE, 4); }
    public void setTabSize(int v)         { prefs.edit().putInt(K_TAB_SIZE, v).apply(); }

    public boolean isLineNumbers()        { return prefs.getBoolean(K_LINE_NUMBERS, true); }
    public void setLineNumbers(boolean v) { prefs.edit().putBoolean(K_LINE_NUMBERS, v).apply(); }

    public boolean isWordWrap()           { return prefs.getBoolean(K_WORD_WRAP, false); }
    public void setWordWrap(boolean v)    { prefs.edit().putBoolean(K_WORD_WRAP, v).apply(); }

    /** Повертає множник міжрядкового інтервалу (1.0 .. 2.0). */
    public float getLineSpacing() {
        int x10 = prefs.getInt(K_LINE_SPACING_X10, 12); // 1.2 за замовч.
        return x10 / 10f;
    }
    public void setLineSpacing(float v)   { prefs.edit().putInt(K_LINE_SPACING_X10, Math.round(v * 10)).apply(); }

    /**
     * Where the user last left the split, as the left pane's share of the row.
     *
     * <p>A ratio, not two widths: it has to mean the same thing after a rotation
     * or a drawer opening, and 0.5 by default is the even split the panes start
     * out at.</p>
     */
    public float getSplitRatio()          { return prefs.getInt(K_SPLIT_RATIO_X1000, 500) / 1000f; }
    public void setSplitRatio(float v)    { prefs.edit().putInt(K_SPLIT_RATIO_X1000, Math.round(v * 1000)).apply(); }

    public boolean isAutoSave()           { return prefs.getBoolean(K_AUTO_SAVE, false); }
    public void setAutoSave(boolean v)    { prefs.edit().putBoolean(K_AUTO_SAVE, v).apply(); }

    public boolean isFormatOnSave()       { return prefs.getBoolean(K_FORMAT_ON_SAVE, false); }
    public void setFormatOnSave(boolean v) { prefs.edit().putBoolean(K_FORMAT_ON_SAVE, v).apply(); }

    public boolean isToolbarUndo()        { return prefs.getBoolean(K_TB_UNDO, true); }
    public void setToolbarUndo(boolean v) { prefs.edit().putBoolean(K_TB_UNDO, v).apply(); }

    public boolean isToolbarRedo()        { return prefs.getBoolean(K_TB_REDO, true); }
    public void setToolbarRedo(boolean v) { prefs.edit().putBoolean(K_TB_REDO, v).apply(); }

    public boolean isToolbarDebug()        { return prefs.getBoolean(K_TB_DEBUG, true); }
    public void setToolbarDebug(boolean v) { prefs.edit().putBoolean(K_TB_DEBUG, v).apply(); }

    public boolean isToolbarFind()        { return prefs.getBoolean(K_TB_FIND, true); }
    public void setToolbarFind(boolean v) { prefs.edit().putBoolean(K_TB_FIND, v).apply(); }

    public boolean isToolbarFormat()      { return prefs.getBoolean(K_TB_FORMAT, true); }
    public void setToolbarFormat(boolean v) { prefs.edit().putBoolean(K_TB_FORMAT, v).apply(); }

    public boolean isToolbarAiChat()        { return prefs.getBoolean(K_TB_AI, true); }
    public void setToolbarAiChat(boolean v) { prefs.edit().putBoolean(K_TB_AI, v).apply(); }

    /**
     * Chosen version for one of the bundled JVM languages, keyed by its id.
     *
     * <p>Empty means the version that ships in the app, which is the one that
     * needs no network.</p>
     */
    public String getLanguageVersion(String languageId) {
        return prefs.getString("language_version_" + languageId, "");
    }

    public void setLanguageVersion(String languageId, String version) {
        prefs.edit().putString("language_version_" + languageId,
                version == null ? "" : version).apply();
    }

    public String getKotlinLanguageVersion() { return prefs.getString(K_KOTLIN_LANG, ""); }
    public void setKotlinLanguageVersion(String v) {
        prefs.edit().putString(K_KOTLIN_LANG, v == null ? "" : v).apply();
    }

    public boolean isMinimap()            { return prefs.getBoolean(K_MINIMAP, false); }
    public void setMinimap(boolean v)     { prefs.edit().putBoolean(K_MINIMAP, v).apply(); }

    public boolean isLiveProblemsEnabled() { return prefs.getBoolean(K_LIVE_PROBLEMS, true); }
    public void setLiveProblemsEnabled(boolean v) { prefs.edit().putBoolean(K_LIVE_PROBLEMS, v).apply(); }

    public boolean isDiagnosticsUnderline() { return prefs.getBoolean(K_DIAG_UNDERLINE, false); }
    public void setDiagnosticsUnderline(boolean v) { prefs.edit().putBoolean(K_DIAG_UNDERLINE, v).apply(); }

    public boolean isDiagnosticsLightbulb() { return prefs.getBoolean(K_DIAG_LIGHTBULB, true); }
    public void setDiagnosticsLightbulb(boolean v) { prefs.edit().putBoolean(K_DIAG_LIGHTBULB, v).apply(); }

    public boolean isAutoSearchEnabled()   { return prefs.getBoolean(K_AUTO_SEARCH, true); }
    public void setAutoSearchEnabled(boolean v) { prefs.edit().putBoolean(K_AUTO_SEARCH, v).apply(); }

    /**
     * AST-based Java highlighting: resolves each identifier through the scope
     * tree instead of matching patterns per line. Off falls back to the
     * line-tokenizer highlighter, which is cheaper on very large files.
     */
    public boolean isAstHighlighting()        { return prefs.getBoolean(K_AST_HIGHLIGHT, true); }
    public void setAstHighlighting(boolean v) { prefs.edit().putBoolean(K_AST_HIGHLIGHT, v).apply(); }

    public boolean isPerfAstHighlighting() { return prefs.getBoolean(K_PERF_AST, true); }
    public void setPerfAstHighlighting(boolean v) { prefs.edit().putBoolean(K_PERF_AST, v).apply(); }
    public boolean isPerfLiveProblems() { return prefs.getBoolean(K_PERF_LIVE, true); }
    public void setPerfLiveProblems(boolean v) { prefs.edit().putBoolean(K_PERF_LIVE, v).apply(); }
    public boolean isPerfAutoSave() { return prefs.getBoolean(K_PERF_AUTO_SAVE, true); }
    public void setPerfAutoSave(boolean v) { prefs.edit().putBoolean(K_PERF_AUTO_SAVE, v).apply(); }
    public boolean isPerfFormatOnSave() { return prefs.getBoolean(K_PERF_FORMAT, true); }
    public void setPerfFormatOnSave(boolean v) { prefs.edit().putBoolean(K_PERF_FORMAT, v).apply(); }
    public boolean isPerfMinimap() { return prefs.getBoolean(K_PERF_MINIMAP, true); }
    public void setPerfMinimap(boolean v) { prefs.edit().putBoolean(K_PERF_MINIMAP, v).apply(); }
    public boolean isPerfAutoSearch() { return prefs.getBoolean(K_PERF_SEARCH, true); }
    public void setPerfAutoSearch(boolean v) { prefs.edit().putBoolean(K_PERF_SEARCH, v).apply(); }
    public boolean isPerfVerboseLogging() { return prefs.getBoolean(K_PERF_VERBOSE, true); }
    public void setPerfVerboseLogging(boolean v) { prefs.edit().putBoolean(K_PERF_VERBOSE, v).apply(); }

    public boolean isPsAstHighlighting() { return prefs.getBoolean(K_PS_AST, false); }
    public void setPsAstHighlighting(boolean v) { prefs.edit().putBoolean(K_PS_AST, v).apply(); }
    public boolean isPsLiveProblems() { return prefs.getBoolean(K_PS_LIVE, false); }
    public void setPsLiveProblems(boolean v) { prefs.edit().putBoolean(K_PS_LIVE, v).apply(); }
    public boolean isPsAutoSave() { return prefs.getBoolean(K_PS_AUTO_SAVE, false); }
    public void setPsAutoSave(boolean v) { prefs.edit().putBoolean(K_PS_AUTO_SAVE, v).apply(); }
    public boolean isPsFormatOnSave() { return prefs.getBoolean(K_PS_FORMAT, false); }
    public void setPsFormatOnSave(boolean v) { prefs.edit().putBoolean(K_PS_FORMAT, v).apply(); }
    public boolean isPsMinimap() { return prefs.getBoolean(K_PS_MINIMAP, false); }
    public void setPsMinimap(boolean v) { prefs.edit().putBoolean(K_PS_MINIMAP, v).apply(); }

    // Parameter hints reindex the project on a debounce, so they are the most
    // expensive of the three and default off while saving power.
    public boolean isPerfInlayHints() { return prefs.getBoolean(K_PERF_INLAY, true); }
    public void setPerfInlayHints(boolean v) { prefs.edit().putBoolean(K_PERF_INLAY, v).apply(); }
    public boolean isPsInlayHints() { return prefs.getBoolean(K_PS_INLAY, false); }
    public void setPsInlayHints(boolean v) { prefs.edit().putBoolean(K_PS_INLAY, v).apply(); }

    public boolean isPerfGitGutter() { return prefs.getBoolean(K_PERF_GUTTER, true); }
    public void setPerfGitGutter(boolean v) { prefs.edit().putBoolean(K_PERF_GUTTER, v).apply(); }
    public boolean isPsGitGutter() { return prefs.getBoolean(K_PS_GUTTER, false); }
    public void setPsGitGutter(boolean v) { prefs.edit().putBoolean(K_PS_GUTTER, v).apply(); }
    public boolean isPsAutoSearch() { return prefs.getBoolean(K_PS_SEARCH, false); }
    public void setPsAutoSearch(boolean v) { prefs.edit().putBoolean(K_PS_SEARCH, v).apply(); }
    public boolean isPsVerboseLogging() { return prefs.getBoolean(K_PS_VERBOSE, false); }
    public void setPsVerboseLogging(boolean v) { prefs.edit().putBoolean(K_PS_VERBOSE, v).apply(); }

    // ── Read-only files ───────────────────────────────────────────────────

    /** Absolute paths the user has locked against editing. */
    public java.util.Set<String> getReadOnlyFiles() {
        java.util.Set<String> stored = prefs.getStringSet(K_READ_ONLY_FILES, null);
        return stored == null ? new java.util.HashSet<>() : new java.util.HashSet<>(stored);
    }

    public boolean isReadOnly(String absolutePath) {
        if (absolutePath == null) return false;
        return FileIdentity.locked(getReadOnlyFiles(),absolutePath);
    }

    /**
     * Marks a file read-only, or clears the mark.
     *
     * @return the state that is now stored
     */
    public boolean setReadOnly(String absolutePath, boolean readOnly) {
        if (absolutePath == null) return false;
        java.util.Set<String> set = getReadOnlyFiles();
        java.io.File target=new java.io.File(absolutePath);
        set.removeIf(path->FileIdentity.same(new java.io.File(path),target));
        if (readOnly) set.add(absolutePath);
        prefs.edit().putStringSet(K_READ_ONLY_FILES, set).apply();
        return readOnly;
    }

    // ── Bottom panel tabs ─────────────────────────────────────

    /**
     * Saved tab order as a list of {@code BottomPanel} keys.
     *
     * <p>Stored as one delimited string rather than a {@code StringSet},
     * because a set has no order — which is the entire point here.</p>
     *
     * @return the stored order, or an empty list to mean "factory order"
     */
    public java.util.List<String> getPanelOrder() {
        String raw = prefs.getString(K_PANEL_ORDER, null);
        if (raw == null || raw.isEmpty()) return new java.util.ArrayList<>();
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String key : raw.split(",")) {
            String trimmed = key.trim();
            if (!trimmed.isEmpty()) out.add(trimmed);
        }
        return out;
    }

    public void setPanelOrder(java.util.List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            prefs.edit().remove(K_PANEL_ORDER).apply();
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (String key : keys) {
            if (sb.length() > 0) sb.append(',');
            sb.append(key);
        }
        prefs.edit().putString(K_PANEL_ORDER, sb.toString()).apply();
    }

    /** Keys of panels the user switched off. */
    public java.util.Set<String> getHiddenPanels() {
        java.util.Set<String> stored = prefs.getStringSet(K_PANEL_HIDDEN, null);
        return stored == null ? new java.util.HashSet<>() : new java.util.HashSet<>(stored);
    }

    public void setHiddenPanels(java.util.Set<String> keys) {
        prefs.edit().putStringSet(K_PANEL_HIDDEN,
                keys == null ? new java.util.HashSet<>() : new java.util.HashSet<>(keys)).apply();
    }

    public boolean isRunMetricsVisible() {
        return prefs.getBoolean(K_RUN_METRICS, true);
    }

    public void setRunMetricsVisible(boolean visible) {
        prefs.edit().putBoolean(K_RUN_METRICS, visible).apply();
    }

    /** Drops any customisation, returning the tab strip to factory order. */
    public void resetPanelLayout() {
        prefs.edit().remove(K_PANEL_ORDER).remove(K_PANEL_HIDDEN).apply();
    }

    // ── Theme ─────────────────────────────────────────────────

    /**
     * The theme to draw with right now.
     *
     * <p>Resolved here rather than at the forty call sites: when the editor is
     * set to follow the system, the answer depends on whether Android is in
     * night mode, and every screen should get the same answer.</p>
     */
    public String getThemeId() {
        if (isAutoSystemTheme()) {
            return isSystemInNightMode() ? getDarkThemeId() : getLightThemeId();
        }
        return prefs.getString(K_THEME_ID, AppTheme.ID_DARCULA);
    }

    /**
     * Records a theme choice.
     *
     * <p>While following the system, a pick applies to the mode currently on
     * screen — choosing a light theme at noon should not be undone by nightfall.</p>
     */
    public void setThemeId(String v) {
        if (isAutoSystemTheme()) {
            prefs.edit().putString(isSystemInNightMode() ? K_THEME_DARK : K_THEME_LIGHT, v).apply();
            return;
        }
        prefs.edit().putString(K_THEME_ID, v).apply();
    }

    /** The manual choice, ignoring any system following. */
    public String getManualThemeId()      { return prefs.getString(K_THEME_ID, AppTheme.ID_DARCULA); }

    /**
     * The night-mode theme, defaulting to whatever was already chosen if that
     * happens to be a dark one.
     *
     * <p>Falling back to a fixed Darcula would silently replace the theme of
     * anyone upgrading who had picked Monokai or Dracula.</p>
     */
    public String getDarkThemeId() {
        String stored = prefs.getString(K_THEME_DARK, null);
        if (stored != null) return stored;
        String manual = getManualThemeId();
        return isDarkTheme(manual) ? manual : AppTheme.ID_DARCULA;
    }

    public void setDarkThemeId(String v)  { prefs.edit().putString(K_THEME_DARK, v).apply(); }

    public String getLightThemeId() {
        String stored = prefs.getString(K_THEME_LIGHT, null);
        if (stored != null) return stored;
        String manual = getManualThemeId();
        return isDarkTheme(manual) ? AppTheme.ID_VS_LIGHT : manual;
    }

    public void setLightThemeId(String v) { prefs.edit().putString(K_THEME_LIGHT, v).apply(); }

    private boolean isDarkTheme(String id) {
        try {
            AppTheme t = AppTheme.byId(id, this);
            return t != null && t.dark;
        } catch (Throwable t) {
            return true;
        }
    }

    /** Whether Android itself is currently in dark mode. */
    public boolean isSystemInNightMode() {
        try {
            int mode = context.getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            return mode == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable t) {
            return false;
        }
    }

    public int getCustomBg()              { return prefs.getInt(K_CUSTOM_BG, 0xFF2B2B2B); }
    public void setCustomBg(int v)        { prefs.edit().putInt(K_CUSTOM_BG, v).apply(); }

    public int getCustomFg()              { return prefs.getInt(K_CUSTOM_FG, 0xFFBBBBBB); }
    public void setCustomFg(int v)        { prefs.edit().putInt(K_CUSTOM_FG, v).apply(); }

    public int getCustomAccent()          { return prefs.getInt(K_CUSTOM_ACCENT, 0xFF4A86C8); }
    public void setCustomAccent(int v)    { prefs.edit().putInt(K_CUSTOM_ACCENT, v).apply(); }

    public int getCustomToolbar()         { return prefs.getInt(K_CUSTOM_TOOLBAR, 0xFF3C3F41); }
    public void setCustomToolbar(int v)   { prefs.edit().putInt(K_CUSTOM_TOOLBAR, v).apply(); }

    public int getCustomConsoleBg()       { return prefs.getInt(K_CUSTOM_CONSOLE_BG, 0xFF1E1E1E); }
    public void setCustomConsoleBg(int v) { prefs.edit().putInt(K_CUSTOM_CONSOLE_BG, v).apply(); }

    public int getCustomKeyword()         { return prefs.getInt(K_CUSTOM_KEYWORD, 0xFFCC7832); }
    public void setCustomKeyword(int v)   { prefs.edit().putInt(K_CUSTOM_KEYWORD, v).apply(); }

    public int getCustomString()          { return prefs.getInt(K_CUSTOM_STRING, 0xFF6A8759); }
    public void setCustomString(int v)    { prefs.edit().putInt(K_CUSTOM_STRING, v).apply(); }

    public int getCustomComment()         { return prefs.getInt(K_CUSTOM_COMMENT, 0xFF808080); }
    public void setCustomComment(int v)   { prefs.edit().putInt(K_CUSTOM_COMMENT, v).apply(); }

    // ── Structure panel ───────────────────────────────────────
    // Source order and both filters on reproduce the panel as it behaved
    // before sorting existed, so an upgrade changes nothing until asked.

    public static final int STRUCTURE_SORT_SOURCE     = 0;
    public static final int STRUCTURE_SORT_ALPHA      = 1;
    public static final int STRUCTURE_SORT_KIND       = 2;
    public static final int STRUCTURE_SORT_VISIBILITY = 3;

    public int getStructureSort()         { return prefs.getInt(K_STRUCT_SORT, STRUCTURE_SORT_SOURCE); }
    public void setStructureSort(int v)   { prefs.edit().putInt(K_STRUCT_SORT, v).apply(); }

    public boolean isStructureShowFields()          { return prefs.getBoolean(K_STRUCT_FIELDS, true); }
    public void setStructureShowFields(boolean v)   { prefs.edit().putBoolean(K_STRUCT_FIELDS, v).apply(); }

    public boolean isStructureShowNonPublic()        { return prefs.getBoolean(K_STRUCT_NONPUBLIC, true); }
    public void setStructureShowNonPublic(boolean v) { prefs.edit().putBoolean(K_STRUCT_NONPUBLIC, v).apply(); }

    // ── Compiler ──────────────────────────────────────────────

    /** Which compiler builds the .c and .cpp files found in a project. */
    public static final String NATIVE_TCC = "tcc";
    public static final String NATIVE_NDK = "ndk";

    /**
     * Off by default: most projects have no native sources, and a build that
     * silently reaches for a compiler is a build nobody can explain.
     */
    public boolean isNativeEnabled()      { return prefs.getBoolean(K_NATIVE_ENABLED, false); }
    public void setNativeEnabled(boolean v) {
        prefs.edit().putBoolean(K_NATIVE_ENABLED, v).apply();
    }

    public String getNativeBackend()      { return prefs.getString(K_NATIVE_BACKEND, NATIVE_TCC); }
    public void setNativeBackend(String v) {
        prefs.edit().putString(K_NATIVE_BACKEND, v).apply();
    }

    public String getJavaTarget()         { return prefs.getString(K_JAVA_TARGET, JAVA_8); }
    public void setJavaTarget(String v)   { prefs.edit().putString(K_JAVA_TARGET, v).apply(); }

    // ── Misc ──────────────────────────────────────────────────

    public String getProjectRoot()        { return prefs.getString(K_PROJECT_ROOT, null); }
    public void setProjectRoot(String v)  { prefs.edit().putString(K_PROJECT_ROOT, v).apply(); }

    public String getFileEncoding()       { return prefs.getString(K_FILE_ENCODING, "UTF-8 BOM"); }
    public void setFileEncoding(String v) { prefs.edit().putString(K_FILE_ENCODING, v).apply(); }

    public boolean isVerboseLoggingEnabled() { return prefs.getBoolean(K_VERBOSE_LOGGING, true); }
    public void setVerboseLoggingEnabled(boolean v) { prefs.edit().putBoolean(K_VERBOSE_LOGGING, v).apply(); }

    public int getPowerSavingMode() {
        int value = prefs.getInt(K_POWER_SAVING_MODE, 0);
        return Math.max(0, Math.min(3, value));
    }
    public void setPowerSavingMode(int v)    { prefs.edit().putInt(K_POWER_SAVING_MODE, Math.max(0, Math.min(3, v))).apply(); }

    /** Battery percentage at which Auto mode enters the saving profile. */
    public int getPowerSavingBatteryThreshold() {
        return Math.max(10, Math.min(80, prefs.getInt(K_POWER_SAVING_THRESHOLD, 30)));
    }
    public void setPowerSavingBatteryThreshold(int v) {
        prefs.edit().putInt(K_POWER_SAVING_THRESHOLD, Math.max(10, Math.min(80, v))).apply();
    }

    /** Whether Auto mode also follows Android's system Battery Saver switch. */
    public boolean isPowerSavingRespectSystem() {
        return prefs.getBoolean(K_POWER_SAVING_RESPECT_SYSTEM, true);
    }
    public void setPowerSavingRespectSystem(boolean v) {
        prefs.edit().putBoolean(K_POWER_SAVING_RESPECT_SYSTEM, v).apply();
    }

    /** Disable optional animations while the saving profile is active. */
    public boolean isPowerSavingReduceAnimations() {
        return prefs.getBoolean(K_POWER_SAVING_REDUCE_ANIMATIONS, true);
    }
    public void setPowerSavingReduceAnimations(boolean v) {
        prefs.edit().putBoolean(K_POWER_SAVING_REDUCE_ANIMATIONS, v).apply();
    }

    /** Live-problems interval in saving mode, clamped to a useful range. */
    public int getPowerSavingScanIntervalSec() {
        return Math.max(15, Math.min(300, prefs.getInt(K_POWER_SAVING_SCAN_INTERVAL, 60)));
    }
    public void setPowerSavingScanIntervalSec(int v) {
        prefs.edit().putInt(K_POWER_SAVING_SCAN_INTERVAL, Math.max(15, Math.min(300, v))).apply();
    }

    /** Debounce for auto-save; zero means save immediately. */
    public int getAutoSaveDelayMs() {
        return Math.max(0, Math.min(5000, prefs.getInt(K_AUTO_SAVE_DELAY_MS, 1200)));
    }
    public void setAutoSaveDelayMs(int v) {
        prefs.edit().putInt(K_AUTO_SAVE_DELAY_MS, Math.max(0, Math.min(5000, v))).apply();
    }

    // ── v1.11.2 new settings ─────────────────────────────────

    public boolean isAutoClosePairs()          { return prefs.getBoolean(K_AUTO_CLOSE_PAIRS, true); }
    public void setAutoClosePairs(boolean v)   { prefs.edit().putBoolean(K_AUTO_CLOSE_PAIRS, v).apply(); }

    public boolean isHighlightCurrentLine()         { return prefs.getBoolean(K_HIGHLIGHT_LINE, true); }
    public void setHighlightCurrentLine(boolean v)  { prefs.edit().putBoolean(K_HIGHLIGHT_LINE, v).apply(); }

    public boolean isClearConsoleOnRun()        { return prefs.getBoolean(K_CLEAR_CONSOLE_ON_RUN, true); }
    public void setClearConsoleOnRun(boolean v) { prefs.edit().putBoolean(K_CLEAR_CONSOLE_ON_RUN, v).apply(); }

    /** 0 = none, 1 = default, 2 = deprecation, 3 = all */
    public int getCompilerWarnings()           { return Math.max(0, Math.min(3, prefs.getInt(K_COMPILER_WARNINGS, 1))); }
    public void setCompilerWarnings(int v)     { prefs.edit().putInt(K_COMPILER_WARNINGS, Math.max(0, Math.min(3, v))).apply(); }

    /** TODO panel scope: the open file only, rather than the whole project. */
    public boolean isTodoScopeCurrentFile()    { return prefs.getBoolean(K_TODO_SCOPE_FILE, false); }
    public void setTodoScopeCurrentFile(boolean v) {
        prefs.edit().putBoolean(K_TODO_SCOPE_FILE, v).apply();
    }

    public boolean isShowDotfiles()            { return prefs.getBoolean(K_SHOW_DOTFILES, false); }
    public void setShowDotfiles(boolean v)     { prefs.edit().putBoolean(K_SHOW_DOTFILES, v).apply(); }

    public String getSearchExcludes()          { return prefs.getString(K_SEARCH_EXCLUDES, "build,.gradle,.idea,bin,target"); }
    public void setSearchExcludes(String v)    { prefs.edit().putString(K_SEARCH_EXCLUDES, v == null ? "" : v).apply(); }

    public String getDefaultProjectPath()      { return prefs.getString(K_DEFAULT_PROJECT_PATH, null); }
    public void setDefaultProjectPath(String v) {
        SharedPreferences.Editor editor = prefs.edit();
        if (v == null || v.trim().isEmpty()) editor.remove(K_DEFAULT_PROJECT_PATH);
        else editor.putString(K_DEFAULT_PROJECT_PATH, v);
        editor.apply();
    }

    public boolean isConfirmFileDelete()        { return prefs.getBoolean(K_CONFIRM_FILE_DELETE, true); }
    public void setConfirmFileDelete(boolean v) { prefs.edit().putBoolean(K_CONFIRM_FILE_DELETE, v).apply(); }

    public boolean isInclusiveMode()         { return prefs.getBoolean(K_INCLUSIVE_MODE, false); }
    public void setInclusiveMode(boolean v)  { prefs.edit().putBoolean(K_INCLUSIVE_MODE, v).apply(); }

    // ── First run ─────────────────────────────────────────────
    // Written with commit() rather than apply() by the splash screen: the very
    // next thing that happens is a screen transition, and an asynchronous write
    // that loses the race would show the notice a second time.

    public boolean isJavaLicenceAccepted() {
        return prefs.getBoolean(K_JAVA_LICENCE_ACCEPTED, false);
    }

    public void setJavaLicenceAccepted(boolean v) {
        prefs.edit().putBoolean(K_JAVA_LICENCE_ACCEPTED, v).commit();
    }

    public java.util.List<String> getRecentProjects() {
        String data = prefs.getString("recent_projects", "");
        if (data.isEmpty()) return new java.util.ArrayList<>();
        return new java.util.ArrayList<>(java.util.Arrays.asList(data.split("\\|")));
    }

    public void addRecentProject(String path) {
        if (path == null || path.isEmpty()) return;
        java.util.List<String> list = getRecentProjects();
        list.remove(path); // remove if exists to move to top
        list.add(0, path); // insert at top
        if (list.size() > 20) {
            list = list.subList(0, 20);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append("|");
            sb.append(list.get(i));
        }
        prefs.edit().putString("recent_projects", sb.toString()).apply();
    }

    public void removeRecentProject(String path) {
        java.util.List<String> list = getRecentProjects();
        if (list.remove(path)) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append("|");
                sb.append(list.get(i));
            }
            prefs.edit().putString("recent_projects", sb.toString()).apply();
        }
    }

    // ── Helpers ───────────────────────────────────────────────

    public boolean isAutoSystemTheme() { return prefs.getBoolean("auto_system_theme", true); }
    public void setAutoSystemTheme(boolean v) { prefs.edit().putBoolean("auto_system_theme", v).apply(); }

    public String getCustomFontPath() { return prefs.getString(K_CUSTOM_FONT_PATH, null); }
    public void setCustomFontPath(String v) {
        SharedPreferences.Editor editor = prefs.edit();
        if (v == null || v.trim().isEmpty()) editor.remove(K_CUSTOM_FONT_PATH);
        else editor.putString(K_CUSTOM_FONT_PATH, v);
        editor.apply();
    }

    public String getCustomFontUrl() { return prefs.getString(K_CUSTOM_FONT_URL, ""); }
    public void setCustomFontUrl(String v) {
        prefs.edit().putString(K_CUSTOM_FONT_URL, v == null ? "" : v.trim()).apply();
    }

    public int getCustomFontVersion() { return prefs.getInt(K_CUSTOM_FONT_VERSION, 0); }
    public void bumpCustomFontVersion() {
        prefs.edit().putInt(K_CUSTOM_FONT_VERSION, getCustomFontVersion() + 1).apply();
    }

    public boolean isFontLigaturesEnabled() { return prefs.getBoolean("font_ligatures", true); }

    public boolean isInlayHintsEnabled()  { return prefs.getBoolean("inlay_hints", true); }
    public void setInlayHintsEnabled(boolean v) {
        prefs.edit().putBoolean("inlay_hints", v).apply();
    }
    public void setFontLigaturesEnabled(boolean v) { prefs.edit().putBoolean("font_ligatures", v).apply(); }

    public String getProgramArgs() { return prefs.getString("program_args", ""); }
    public void setProgramArgs(String v) { prefs.edit().putString("program_args", v).apply(); }

    /**
     * Which problem severities the panel lists. Kept because a filter you have to
     * set again every launch is a filter you stop using.
     */
    /** Whether the Problems panel is narrowed to the file in the editor. */
    public boolean isProblemsScopeCurrentFile() {
        return prefs.getBoolean("problems_scope_current", false);
    }

    public void setProblemsScopeCurrentFile(boolean v) {
        prefs.edit().putBoolean("problems_scope_current", v).apply();
    }

    public boolean isProblemSeverityShown(String severityName) {
        return prefs.getBoolean("problems_show_" + severityName, true);
    }

    public void setProblemSeverityShown(String severityName, boolean shown) {
        prefs.edit().putBoolean("problems_show_" + severityName, shown).apply();
    }

    public String getEnvVars() { return prefs.getString("env_vars", ""); }
    public void setEnvVars(String v) { prefs.edit().putString("env_vars", v).apply(); }

    private static volatile Typeface cachedTypeface;
    private static volatile String cachedTypefaceKey;

    /**
     * The chosen editor font.
     *
     * <p>Cached because this is called about eighty-five times across the app,
     * much of it per table cell, per diff line and per search result — and the
     * custom-font branch below parses the font file from disk on every call.
     * The key covers everything the answer depends on, and
     * {@link #bumpCustomFontVersion()} moves it when the same path gets new
     * bytes, so replacing a font still takes effect.</p>
     */
    public Typeface resolveTypeface() {
        String key = getFontFamily() + "|" + getCustomFontPath() + "|" + getCustomFontVersion();
        Typeface hit = cachedTypeface;
        if (hit != null && key.equals(cachedTypefaceKey)) return hit;
        Typeface resolved = resolveTypefaceUncached();
        cachedTypeface = resolved;
        cachedTypefaceKey = key;
        return resolved;
    }

    private Typeface resolveTypefaceUncached() {
        String customPath = getCustomFontPath();
        if (customPath != null && !customPath.isEmpty()) {
            java.io.File fontFile = new java.io.File(customPath);
            if (fontFile.exists()) {
                try {
                    return Typeface.createFromFile(fontFile);
                } catch (Exception ignored) {}
            }
        }
        switch (getFontFamily()) {
            case FONT_SANS:        return Typeface.SANS_SERIF;
            case FONT_SERIF:       return Typeface.SERIF;
            case FONT_DEFAULT:     return Typeface.DEFAULT;
            case FONT_JETBRAINS:   return loadAssetFont("fonts/JetBrainsMono.ttf");
            case FONT_FIRA_CODE:   return loadAssetFont("fonts/FiraCode.ttf");
            case FONT_SOURCE_CODE: return loadAssetFont("fonts/SourceCodePro.ttf");
            case FONT_DEJAVU_MONO: return loadAssetFont("fonts/DejaVuSansMono.ttf");
            case FONT_ROBOTO_MONO: return loadAssetFont("fonts/RobotoMono.ttf");
            case FONT_MONOSPACE:
            default:               return Typeface.MONOSPACE;
        }
    }

    // Concurrent because the warm-up thread fills this before the first screen
    // asks for a font, while the UI thread reads it from every view that sets
    // one; a plain HashMap read during another thread's write can spin forever.
    private static final java.util.Map<String, Typeface> fontCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    private Typeface loadAssetFont(String path) {
        Typeface cached = fontCache.get(path);
        if (cached != null) return cached;
        try {
            Typeface tf = Typeface.createFromAsset(context.getAssets(), path);
            fontCache.put(path, tf);
            return tf;
        } catch (Exception e) {
            return Typeface.MONOSPACE;
        }
    }

    public boolean isBracketHighlight() { return prefs.getBoolean(K_BRACKET_HIGHLIGHT, true); }
    public void setBracketHighlight(boolean v) {
        prefs.edit().putBoolean(K_BRACKET_HIGHLIGHT, v).apply();
    }

    public boolean isIndentGuides() { return prefs.getBoolean(K_INDENT_GUIDES, true); }
    public void setIndentGuides(boolean v) { prefs.edit().putBoolean(K_INDENT_GUIDES, v).apply(); }

    /** Symbol row follows the open file's language instead of one fixed list. */
    public boolean isContextualSymbols() { return prefs.getBoolean(K_CONTEXTUAL_SYMBOLS, true); }
    public void setContextualSymbols(boolean v) {
        prefs.edit().putBoolean(K_CONTEXTUAL_SYMBOLS, v).apply();
    }

    public String getKeyBarSymbols() { return prefs.getString(K_KEY_BAR_SYMBOLS, DEFAULT_KEY_BAR_SYMBOLS); }
    public void setKeyBarSymbols(String v) { prefs.edit().putString(K_KEY_BAR_SYMBOLS, v != null ? v : DEFAULT_KEY_BAR_SYMBOLS).apply(); }
}
