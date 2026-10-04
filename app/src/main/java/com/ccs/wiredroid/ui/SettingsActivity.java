package com.ccs.wiredroid.ui;
import com.ccs.wiredroid.BuildConfig;
import com.ccs.wiredroid.R;
import com.ccs.wiredroid.util.AppPreferences;
import com.ccs.wiredroid.util.Abi;
import com.ccs.wiredroid.util.AppTheme;
import com.ccs.wiredroid.util.PowerSavingManager;
import com.ccs.wiredroid.util.FullScreenHelper;
import com.ccs.wiredroid.util.CustomFontManager;
import com.ccs.wiredroid.tools.compilers.JavaVersions;
import com.ccs.wiredroid.ui.panels.PanelLayoutEditor;
import com.ccs.wiredroid.tools.compilers.NdkManager;
import com.ccs.wiredroid.util.Colors;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;
import androidx.appcompat.widget.Toolbar;
import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextWatcher;
import android.text.style.BackgroundColorSpan;

import java.util.List;
import java.util.Locale;
import java.io.File;

/**
 * Розгорнутий екран налаштувань: тема, шрифт, поведінка редактора та компілятор.
 * UI будується програмно, щоб теми застосовувались миттєво без перезапуску.
 */
public class SettingsActivity extends AppCompatActivity {

    /** Absolute path of the project the caller has open; absent when there is none. */
    public static final String EXTRA_PROJECT_ROOT = "settings_project_root";

    public static final String EXTRA_CHANGED = "changed";
    private static final int REQ_CUSTOM_FONT_FILE = 7001;

    private AppPreferences prefs;
    private AppTheme theme;

    private LinearLayout customColorsSection;
    private TextView themeNameLabel;
    private EditText customFontUrlInput;
    private TextView customFontStatusView, customFontDownloadButton,
            customFontChooseButton, customFontRemoveButton;

    private View bgSwatch, fgSwatch, accentSwatch, toolbarSwatch,
            consoleBgSwatch, keywordSwatch, stringSwatch, commentSwatch;

    private String initialThemeId;
    private int initialCustomBg, initialCustomToolbar, initialCustomFg, initialCustomAccent,
            initialCustomConsoleBg, initialCustomKeyword, initialCustomString, initialCustomComment;
    private int initialFontSize, initialFontFamily, initialTabSize;
    private float initialLineSpacing;
    private boolean initialLineNumbers, initialWordWrap;
    private boolean initialAutoSave, initialFormatOnSave, initialMinimap, initialAstHighlighting;
    private boolean initialVerboseLogging, initialLiveProblems, initialAutoSearch;
    private boolean initialPerfAst, initialPerfLive, initialPerfAutoSave, initialPerfFormat,
            initialPerfMinimap, initialPerfSearch, initialPerfVerbose;
    private boolean initialPsAst, initialPsLive, initialPsAutoSave, initialPsFormat,
            initialPsMinimap, initialPsSearch, initialPsVerbose;
    private int initialPowerSavingMode, initialPowerThreshold, initialPowerInterval,
            initialAutoSaveDelay;
    private boolean initialPowerRespectSystem, initialPowerReduceAnimations;
    private boolean initialIndentGuides;
    private String initialKeyBarSymbols;
    private String initialCustomFontPath;
    private int initialCustomFontVersion;

    // ── Search State ──────────────────────────────────────────
    private static final class TextMatch {
        final TextView view;
        final CharSequence originalText;
        final int start;
        final int end;

        TextMatch(TextView view, CharSequence originalText, int start, int end) {
            this.view = view;
            this.originalText = originalText;
            this.start = start;
            this.end = end;
        }
    }

    private final java.util.List<TextMatch> searchMatches = new java.util.ArrayList<>();
    private int currentMatchIndex = -1;
    private ScrollView settingsScrollView;
    private LinearLayout settingsContentLayout;
    private LinearLayout searchBarLayout;
    private EditText searchInputField;
    private TextView searchCountView;
    private boolean isSearchActive = false;
    private final java.util.Map<TextView, CharSequence> originalTexts = new java.util.HashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        prefs = new AppPreferences(this);
        theme = AppTheme.byId(prefs.getThemeId(), prefs);
        setTheme(theme.dark ? R.style.Theme_WireDroid : R.style.Theme_WireDroid_Light);

        initialThemeId = prefs.getThemeId();
        initialCustomBg = prefs.getCustomBg();
        initialCustomToolbar = prefs.getCustomToolbar();
        initialCustomFg = prefs.getCustomFg();
        initialCustomAccent = prefs.getCustomAccent();
        initialCustomConsoleBg = prefs.getCustomConsoleBg();
        initialCustomKeyword = prefs.getCustomKeyword();
        initialCustomString = prefs.getCustomString();
        initialCustomComment = prefs.getCustomComment();
        initialFontSize = prefs.getFontSize();
        initialFontFamily = prefs.getFontFamily();
        initialTabSize = prefs.getTabSize();
        initialLineSpacing = prefs.getLineSpacing();
        initialLineNumbers = prefs.isLineNumbers();
        initialWordWrap = prefs.isWordWrap();
        initialAutoSave = prefs.isAutoSave();
        initialFormatOnSave = prefs.isFormatOnSave();
        initialMinimap = prefs.isMinimap();
        initialAstHighlighting = prefs.isAstHighlighting();
        initialVerboseLogging = prefs.isVerboseLoggingEnabled();
        initialLiveProblems = prefs.isLiveProblemsEnabled();
        initialAutoSearch = prefs.isAutoSearchEnabled();
        initialPerfAst = prefs.isPerfAstHighlighting();
        initialPerfLive = prefs.isPerfLiveProblems();
        initialPerfAutoSave = prefs.isPerfAutoSave();
        initialPerfFormat = prefs.isPerfFormatOnSave();
        initialPerfMinimap = prefs.isPerfMinimap();
        initialPerfSearch = prefs.isPerfAutoSearch();
        initialPerfVerbose = prefs.isPerfVerboseLogging();
        initialPsAst = prefs.isPsAstHighlighting();
        initialPsLive = prefs.isPsLiveProblems();
        initialPsAutoSave = prefs.isPsAutoSave();
        initialPsFormat = prefs.isPsFormatOnSave();
        initialPsMinimap = prefs.isPsMinimap();
        initialPsSearch = prefs.isPsAutoSearch();
        initialPsVerbose = prefs.isPsVerboseLogging();
        initialPowerSavingMode = prefs.getPowerSavingMode();
        initialPowerThreshold = prefs.getPowerSavingBatteryThreshold();
        initialPowerInterval = prefs.getPowerSavingScanIntervalSec();
        initialPowerRespectSystem = prefs.isPowerSavingRespectSystem();
        initialPowerReduceAnimations = prefs.isPowerSavingReduceAnimations();
        initialIndentGuides = prefs.isIndentGuides();
        initialKeyBarSymbols = prefs.getKeyBarSymbols();
        initialAutoSaveDelay = prefs.getAutoSaveDelayMs();
        initialCustomFontPath = prefs.getCustomFontPath();
        initialCustomFontVersion = prefs.getCustomFontVersion();

        super.onCreate(savedInstanceState);
        getWindow().setWindowAnimations(0);
        getOnBackPressedDispatcher().addCallback(this,
                new androidx.activity.OnBackPressedCallback(true) {
                    @Override
                    public void handleOnBackPressed() {
                        if (AnchoredMenu.dismissOpen()) return;
                        if (isSearchActive) {
                            closeSearch();
                            return;
                        }
                        setEnabled(false);
                        getOnBackPressedDispatcher().onBackPressed();
                        setEnabled(true);
                    }
                });
        setContentView(buildRoot());
        FullScreenHelper.enable(this);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CUSTOM_FONT_FILE || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;

        setCustomFontControlsBusy(true);
        if (customFontStatusView != null) {
            customFontStatusView.setText(R.string.settings_custom_font_importing);
        }
        CustomFontManager.importLocal(this, uri, new CustomFontManager.Callback() {
            @Override public void onProgress(int percent) {
                if (customFontStatusView != null) {
                    customFontStatusView.setText(getString(
                            R.string.settings_custom_font_importing_progress, percent));
                }
            }

            @Override public void onSuccess(File file) {
                if (customFontUrlInput != null) customFontUrlInput.setText("");
                activateCustomFont(file, "");
            }

            @Override public void onError(String message) {
                showCustomFontError(message);
            }
        });
    }

    @Override
    public void onBackPressed() {
        boolean changed = !initialThemeId.equals(prefs.getThemeId())
                || initialCustomBg != prefs.getCustomBg()
                || initialCustomToolbar != prefs.getCustomToolbar()
                || initialCustomFg != prefs.getCustomFg()
                || initialCustomAccent != prefs.getCustomAccent()
                || initialCustomConsoleBg != prefs.getCustomConsoleBg()
                || initialCustomKeyword != prefs.getCustomKeyword()
                || initialCustomString != prefs.getCustomString()
                || initialCustomComment != prefs.getCustomComment()
                || initialFontSize != prefs.getFontSize()
                || initialFontFamily != prefs.getFontFamily()
                || initialTabSize != prefs.getTabSize()
                || initialLineSpacing != prefs.getLineSpacing()
                || initialLineNumbers != prefs.isLineNumbers()
                || initialWordWrap != prefs.isWordWrap()
                || initialAutoSave != prefs.isAutoSave()
                || initialFormatOnSave != prefs.isFormatOnSave()
                || initialMinimap != prefs.isMinimap()
                || initialAstHighlighting != prefs.isAstHighlighting()
                || initialVerboseLogging != prefs.isVerboseLoggingEnabled()
                || initialLiveProblems != prefs.isLiveProblemsEnabled()
                || initialAutoSearch != prefs.isAutoSearchEnabled()
                || initialPerfAst != prefs.isPerfAstHighlighting()
                || initialPerfLive != prefs.isPerfLiveProblems()
                || initialPerfAutoSave != prefs.isPerfAutoSave()
                || initialPerfFormat != prefs.isPerfFormatOnSave()
                || initialPerfMinimap != prefs.isPerfMinimap()
                || initialPerfSearch != prefs.isPerfAutoSearch()
                || initialPerfVerbose != prefs.isPerfVerboseLogging()
                || initialPsAst != prefs.isPsAstHighlighting()
                || initialPsLive != prefs.isPsLiveProblems()
                || initialPsAutoSave != prefs.isPsAutoSave()
                || initialPsFormat != prefs.isPsFormatOnSave()
                || initialPsMinimap != prefs.isPsMinimap()
                || initialPsSearch != prefs.isPsAutoSearch()
                || initialPsVerbose != prefs.isPsVerboseLogging()
                || initialPowerSavingMode != prefs.getPowerSavingMode()
                || initialPowerThreshold != prefs.getPowerSavingBatteryThreshold()
                || initialPowerInterval != prefs.getPowerSavingScanIntervalSec()
                || initialPowerRespectSystem != prefs.isPowerSavingRespectSystem()
                || initialPowerReduceAnimations != prefs.isPowerSavingReduceAnimations()
                || initialIndentGuides != prefs.isIndentGuides()
                || !sameText(initialKeyBarSymbols, prefs.getKeyBarSymbols())
                || initialAutoSaveDelay != prefs.getAutoSaveDelayMs()
                || !sameText(initialCustomFontPath, prefs.getCustomFontPath())
                || initialCustomFontVersion != prefs.getCustomFontVersion();
        setResult(Activity.RESULT_OK, getIntent().putExtra(EXTRA_CHANGED, changed));
        super.onBackPressed();
    }

    // ══════════════════════════════════════════════════════════
    //  UI
    // ══════════════════════════════════════════════════════════

    private View buildRoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(theme.bg);

        Toolbar toolbar = new Toolbar(this);
        toolbar.setBackgroundColor(theme.toolbar);
        toolbar.setTitle(getString(R.string.menu_settings));
        toolbar.setTitleTextColor(theme.text);
        toolbar.setNavigationIcon(R.drawable.ic_back);
        toolbar.setNavigationOnClickListener(v -> onBackPressed());
        toolbar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        // Search action in toolbar
        android.widget.ImageView searchBtn = new android.widget.ImageView(this);
        searchBtn.setImageResource(R.drawable.ic_search);
        searchBtn.setColorFilter(theme.text, android.graphics.PorterDuff.Mode.SRC_IN);
        searchBtn.setPadding(dp(12), dp(12), dp(12), dp(12));
        searchBtn.setContentDescription(getString(R.string.settings_search_hint));
        android.util.TypedValue tv = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true);
        if (tv.resourceId != 0) searchBtn.setBackgroundResource(tv.resourceId);
        Toolbar.LayoutParams tlp = new Toolbar.LayoutParams(
                dp(44), dp(44), Gravity.END | Gravity.CENTER_VERTICAL);
        tlp.setMarginEnd(dp(4));
        searchBtn.setLayoutParams(tlp);
        searchBtn.setOnClickListener(v -> toggleSearch());
        toolbar.addView(searchBtn);

        root.addView(toolbar);

        // Word-like search / find bar
        searchBarLayout = buildSearchBar();
        searchBarLayout.setVisibility(View.GONE);
        root.addView(searchBarLayout);

        settingsScrollView = new ScrollView(this);
        settingsScrollView.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(settingsScrollView);

        settingsContentLayout = new LinearLayout(this);
        settingsContentLayout.setOrientation(LinearLayout.VERTICAL);
        settingsContentLayout.setPadding(dp(16), dp(8), dp(16), dp(24));
        settingsScrollView.addView(settingsContentLayout);

        // Only the first section is on screen when Settings opens. The other
        // seven — about a hundred views between them — used to be built before
        // the first frame could draw, which is the whole reason this screen took
        // so long to appear on a 32-bit build. There they are attached a section
        // at a time instead; a 64-bit process builds the screen as it always did.
        settingsContentLayout.addView(buildAppearanceSection());
        if (Abi.is32Bit()) {
            attachRemainingSections(settingsContentLayout);
        } else {
            for (java.util.concurrent.Callable<View> section : remainingSections()) {
                try {
                    settingsContentLayout.addView(section.call());
                } catch (Exception e) {
                    android.util.Log.w("Settings", "section skipped", e);
                }
            }
        }

        return root;
    }

    /**
     * Adds the off-screen sections one per message loop turn.
     *
     * <p>A plain {@link Handler} post rather than {@code content.post()}: the
     * latter runs from the view's attach queue, which happens inside the first
     * traversal and so would land before the first frame — exactly what this is
     * trying to get out of the way. Ordinary messages yield to the Choreographer's
     * async frame callback, so a frame is drawn between each section.</p>
     */
    /**
     * The sections below the fold, in the order they appear.
     *
     * <p>One list, so the staged path and the plain one cannot drift apart —
     * adding a section here adds it to both.</p>
     */
    private java.util.List<java.util.concurrent.Callable<View>> remainingSections() {
        java.util.List<java.util.concurrent.Callable<View>> sections = new java.util.ArrayList<>();
        sections.add(this::buildCustomColorsSection);
        sections.add(this::buildEditorSection);

        sections.add(this::buildToolbarSection);
        sections.add(this::buildCompilerSection);
        sections.add(this::buildFilesSection);
        sections.add(this::buildPowerSavingSection);
        sections.add(this::buildResetButton);
        // Support and version stay last: the version line is what people are told
        // to read out in a bug report, so it should sit at a predictable end of
        // the screen rather than move as sections are added above it.
        sections.add(this::buildAboutSection);
        return sections;
    }

    private java.util.ArrayDeque<java.util.concurrent.Callable<View>> pendingSections;

    private void attachRemainingSections(final LinearLayout content) {
        pendingSections = new java.util.ArrayDeque<>(remainingSections());

        final Handler ui = new Handler(Looper.getMainLooper());
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (isFinishing() || isDestroyed() || pendingSections == null) return;
                java.util.concurrent.Callable<View> next = pendingSections.poll();
                if (next == null) return;
                try {
                    content.addView(next.call());
                } catch (Exception e) {
                    // A section that cannot build should cost its own row, not
                    // the rest of the screen.
                    android.util.Log.w("Settings", "section skipped", e);
                }
                if (!pendingSections.isEmpty()) ui.post(this);
            }
        });
    }

    private void ensureRemainingSectionsAttached() {
        if (pendingSections == null || pendingSections.isEmpty() || settingsContentLayout == null) return;
        while (!pendingSections.isEmpty()) {
            java.util.concurrent.Callable<View> next = pendingSections.poll();
            if (next != null) {
                try {
                    settingsContentLayout.addView(next.call());
                } catch (Exception e) {
                    android.util.Log.w("Settings", "section skipped in search", e);
                }
            }
        }
    }

    // ── Appearance / Theme ────────────────────────────────────

    private View buildAppearanceSection() {
        LinearLayout section = newSection(getString(R.string.settings_section_theme));

        section.addView(buildLanguageSetting());

        section.addView(buildHint(getString(R.string.settings_theme_presets_hint)));

        section.addView(buildSwitch(getString(R.string.settings_theme_follow_system),
                getString(R.string.settings_theme_follow_system_desc),
                prefs.isAutoSystemTheme(), v -> {
                    prefs.setAutoSystemTheme(v);
                    // The label and the highlighted card both depend on which
                    // mode is active, so the section is rebuilt rather than
                    // patched in place.
                    smoothRecreate();
                }));
        section.addView(subtitle(getString(prefs.isAutoSystemTheme()
                ? (prefs.isSystemInNightMode()
                        ? R.string.settings_theme_follow_hint_night
                        : R.string.settings_theme_follow_hint_day)
                : R.string.settings_theme_follow_hint_off)));

        themeNameLabel = subtitle(AppTheme.displayName(prefs.getThemeId()));
        section.addView(themeNameLabel);

        HorizontalScrollView hscroll = new HorizontalScrollView(this);
        hscroll.setHorizontalScrollBarEnabled(false);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(8), 0, dp(8));

        // About three cards fit across a phone; on the staged path the rest are
        // off to the right and are added once the screen is up, so opening
        // Settings does not pay for eleven theme previews nobody has scrolled to.
        final AppTheme[] presets = AppTheme.presets();
        final int inlineCards = Abi.is32Bit() ? Math.min(3, presets.length) : presets.length;
        for (int i = 0; i < inlineCards; i++) {
            row.addView(buildThemeCard(presets[i]));
        }
        if (inlineCards == presets.length) {
            // Кастомний пресет
            row.addView(buildCustomThemeCard());
        } else {
            new Handler(Looper.getMainLooper()).post(() -> {
                if (isFinishing() || isDestroyed()) return;
                for (int i = inlineCards; i < presets.length; i++) {
                    row.addView(buildThemeCard(presets[i]));
                }
                // Кастомний пресет
                row.addView(buildCustomThemeCard());
            });
        }

        hscroll.addView(row);
        section.addView(hscroll);
        return section;
    }

    private View buildLanguageSetting() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(10), 0, dp(6));
        box.addView(settingLabel(getString(R.string.settings_language),
                getString(R.string.settings_language_desc)));
        LanguageAdapter.Item[] items = {
                // The one entry that is not a language's own name, so it is the
                // one that has to be translated.
                new LanguageAdapter.Item("", getString(R.string.lang_auto_detect), "auto"),
                new LanguageAdapter.Item("en", "English", "gb"),
                new LanguageAdapter.Item("uk", "Українська", "ua"),
                new LanguageAdapter.Item("pl", "Polski", "pl"),
                new LanguageAdapter.Item("de", "Deutsch", "de"),
                new LanguageAdapter.Item("fr", "Français", "fr"),
                new LanguageAdapter.Item("it", "Italiano", "it"),
                new LanguageAdapter.Item("es", "Español", "es"),
                new LanguageAdapter.Item("pt-BR", "Português (Brasil)", "br"),
                new LanguageAdapter.Item("ro", "Română", "ro"),
                new LanguageAdapter.Item("cs", "Čeština", "cz"),
                new LanguageAdapter.Item("sk", "Slovenčina", "sk"),
                new LanguageAdapter.Item("nl", "Nederlands", "nl"),
                new LanguageAdapter.Item("da", "Dansk", "dk"),
                new LanguageAdapter.Item("sv", "Svenska", "se"),
                new LanguageAdapter.Item("nb", "Norsk bokmål", "no"),
                new LanguageAdapter.Item("fi", "Suomi", "fi"),
                new LanguageAdapter.Item("is", "Íslenska", "is"),
                new LanguageAdapter.Item("et", "Eesti", "ee"),
                new LanguageAdapter.Item("lv", "Latviešu", "lv"),
                new LanguageAdapter.Item("ga", "Gaeilge", "ie"),
                // Both languages of Scotland fly the same flag, as the three
                // Nigerian ones below share theirs.
                new LanguageAdapter.Item("gd", "Gàidhlig", "sct"),
                new LanguageAdapter.Item("sco", "Scots", "sct"),
                new LanguageAdapter.Item("el", "Ελληνικά", "gr"),
                new LanguageAdapter.Item("hy", "Հայերեն", "am"),
                new LanguageAdapter.Item("tr", "Türkçe", "tr"),
                new LanguageAdapter.Item("az", "Azərbaycanca", "az"),
                new LanguageAdapter.Item("ka", "ქართული", "ge"),
                new LanguageAdapter.Item("ar", "العربية", "sa"),
                new LanguageAdapter.Item("hi", "हिन्दी", "in"),
                new LanguageAdapter.Item("zh-rCN", "简体中文", "cn"),
                new LanguageAdapter.Item("ja", "日本語", "jp"),
                new LanguageAdapter.Item("ko", "한국어", "kr"),
                new LanguageAdapter.Item("vi", "Tiếng Việt", "vi"),
                new LanguageAdapter.Item("ta", "தமிழ்", "in"),
                new LanguageAdapter.Item("ms", "Bahasa Melayu", "my"),
                new LanguageAdapter.Item("in", "Bahasa Indonesia", "id"),
                new LanguageAdapter.Item("fil", "Filipino", "ph"),
                new LanguageAdapter.Item("ha", "Hausa", "ng"),
                new LanguageAdapter.Item("ig", "Igbo", "ng"),
                new LanguageAdapter.Item("yo", "Yoruba", "ng")
        };
        String[] tags = new String[items.length];
        String[] labels = new String[items.length];
        for (int i = 0; i < items.length; i++) {
            tags[i] = items[i].tag;
            labels[i] = items[i].label;
        }

        String current = prefs.getAppLanguage();
        int selected = 0;
        for (int i = 0; i < tags.length; i++) {
            if (tags[i].equalsIgnoreCase(current)) selected = i;
        }

        final int[] sel = { selected };
        final TextView dropdownBtn = newDropdownButton(labels, sel[0], anchor -> {
            AnchoredMenu menu = AnchoredMenu.with(this, theme);
            int ink = theme != null ? theme.text : 0xFF202020;
            for (int i = 0; i < items.length; i++) {
                final int idx = i;
                // The flag, not just the name: it is what the eye finds in a
                // list of twenty-seven languages, several written in scripts
                // the reader may not know.
                menu.checkable(LanguageAdapter.flag(items[idx].country, ink),
                        labels[idx], idx == sel[0], () -> {
                    sel[0] = idx;
                    if (!tags[idx].equalsIgnoreCase(prefs.getAppLanguage())) {
                        prefs.setAppLanguage(tags[idx]);
                        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tags[idx]));
                    }
                });
            }
            menu.showBelow(anchor);
        });
        // The chosen flag on the closed button too, so the current language is
        // readable at a glance without opening anything.
        android.graphics.drawable.Drawable currentFlag =
                LanguageAdapter.flag(items[selected].country,
                        theme != null ? theme.text : 0xFF202020);
        currentFlag.setBounds(0, 0, dp(24), dp(16));
        dropdownBtn.setCompoundDrawables(currentFlag, null, null, null);
        dropdownBtn.setCompoundDrawablePadding(dp(10));
        box.addView(dropdownBtn);
        return box;
    }

    private View buildThemeCard(AppTheme preset) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(110), dp(96));
        lp.setMargins(0, 0, dp(10), 0);
        card.setLayoutParams(lp);

        boolean selected = preset.id.equals(prefs.getThemeId());
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(preset.bg);
        bg.setStroke(selected ? dp(2) : dp(1),
                selected ? preset.accent : theme.separator);
        bg.setCornerRadius(dp(8));
        card.setBackground(bg);
        card.setPadding(dp(8), dp(8), dp(8), dp(8));

        // Mini-preview rows
        View r1 = swatchRow(preset.editorKeyword, preset.editorString);
        View r2 = swatchRow(preset.editorComment, preset.text);
        card.addView(r1);
        card.addView(spacer(dp(4)));
        card.addView(r2);

        TextView name = new TextView(this);
        name.setText(AppTheme.displayName(preset.id));
        name.setTextColor(preset.text);
        name.setTextSize(11);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = dp(8);
        name.setLayoutParams(nlp);
        name.setGravity(Gravity.CENTER);
        card.addView(name);

        card.setContentDescription(getString(R.string.a11y_settings_theme_card, AppTheme.displayName(preset.id)));
        card.setOnClickListener(v -> {
            prefs.setThemeId(preset.id);
            theme = AppTheme.byId(preset.id, prefs);
            smoothRecreate();
        });
        return card;
    }

    private View buildCustomThemeCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(110), dp(96));
        lp.setMargins(0, 0, dp(10), 0);
        card.setLayoutParams(lp);

        boolean selected = AppTheme.ID_CUSTOM.equals(prefs.getThemeId());
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(prefs.getCustomBg());
        bg.setStroke(selected ? dp(2) : dp(1),
                selected ? prefs.getCustomAccent() : theme.separator);
        bg.setCornerRadius(dp(8));
        card.setBackground(bg);
        card.setPadding(dp(8), dp(8), dp(8), dp(8));

        View r1 = swatchRow(prefs.getCustomKeyword(), prefs.getCustomString());
        View r2 = swatchRow(prefs.getCustomComment(), prefs.getCustomFg());
        card.addView(r1);
        card.addView(spacer(dp(4)));
        card.addView(r2);

        TextView name = new TextView(this);
        name.setText(getString(R.string.settings_theme_custom));
        name.setTextColor(prefs.getCustomFg());
        name.setTextSize(11);
        name.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = dp(8);
        name.setLayoutParams(nlp);
        card.addView(name);

        card.setContentDescription(getString(R.string.a11y_settings_custom_theme_card));
        card.setOnClickListener(v -> {
            prefs.setThemeId(AppTheme.ID_CUSTOM);
            theme = AppTheme.byId(AppTheme.ID_CUSTOM, prefs);
            smoothRecreate();
        });
        return card;
    }

    private View swatchRow(int c1, int c2) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        View a = new View(this);
        a.setBackgroundColor(c1);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(0, dp(10), 1f);
        ap.setMargins(0, 0, dp(2), 0);
        a.setLayoutParams(ap);

        View b = new View(this);
        b.setBackgroundColor(c2);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, dp(10), 2f);
        b.setLayoutParams(bp);

        row.addView(a);
        row.addView(b);
        return row;
    }

    // ── Custom Colors ─────────────────────────────────────────

    private View buildCustomColorsSection() {
        customColorsSection = newSection(getString(R.string.settings_section_custom_colors));
        boolean isCustom = AppTheme.ID_CUSTOM.equals(prefs.getThemeId());
        customColorsSection.setVisibility(isCustom ? View.VISIBLE : View.GONE);

        customColorsSection.addView(buildHint(getString(R.string.settings_custom_colors_hint)));

        bgSwatch       = colorPickerRow(getString(R.string.settings_color_bg), getString(R.string.settings_color_bg_desc), prefs.getCustomBg(),        c -> { prefs.setCustomBg(c);        refreshCustomTheme(); });
        toolbarSwatch  = colorPickerRow(getString(R.string.settings_color_toolbar), getString(R.string.settings_color_toolbar_desc), prefs.getCustomToolbar(),   c -> { prefs.setCustomToolbar(c);   refreshCustomTheme(); });
        fgSwatch       = colorPickerRow(getString(R.string.settings_color_fg), getString(R.string.settings_color_fg_desc), prefs.getCustomFg(),        c -> { prefs.setCustomFg(c);        refreshCustomTheme(); });
        accentSwatch   = colorPickerRow(getString(R.string.settings_color_accent), getString(R.string.settings_color_accent_desc), prefs.getCustomAccent(),    c -> { prefs.setCustomAccent(c);    refreshCustomTheme(); });
        consoleBgSwatch= colorPickerRow(getString(R.string.settings_color_console_bg), getString(R.string.settings_color_console_bg_desc), prefs.getCustomConsoleBg(), c -> { prefs.setCustomConsoleBg(c); refreshCustomTheme(); });
        keywordSwatch  = colorPickerRow(getString(R.string.settings_color_keyword), getString(R.string.settings_color_keyword_desc), prefs.getCustomKeyword(),   c -> { prefs.setCustomKeyword(c);   refreshCustomTheme(); });
        stringSwatch   = colorPickerRow(getString(R.string.settings_color_string), getString(R.string.settings_color_string_desc), prefs.getCustomString(),    c -> { prefs.setCustomString(c);    refreshCustomTheme(); });
        commentSwatch  = colorPickerRow(getString(R.string.settings_color_comment), getString(R.string.settings_color_comment_desc), prefs.getCustomComment(),   c -> { prefs.setCustomComment(c);   refreshCustomTheme(); });

        return customColorsSection;
    }

    private void refreshCustomTheme() {
        if (AppTheme.ID_CUSTOM.equals(prefs.getThemeId())) {
            theme = AppTheme.byId(AppTheme.ID_CUSTOM, prefs);
        }
        smoothRecreate();
    }

    /**
     * Перезапускає Activity без миготіння (flash/blink).
     * Вимикає анімацію переходу і одразу відновлює з новою темою.
     */
    private void smoothRecreate() {
        overridePendingTransition(0, 0);
        recreate();
        overridePendingTransition(0, 0);
    }

    private View colorPickerRow(String label, int currentColor, ColorChosen onChosen) {
        return colorPickerRow(label, null, currentColor, onChosen);
    }

    private View colorPickerRow(String label, String description, int currentColor, ColorChosen onChosen) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));
        android.util.TypedValue tv = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        if (tv.resourceId != 0) row.setBackgroundResource(tv.resourceId);
        row.setClickable(true);
        row.setFocusable(true);

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        text.setLayoutParams(tlp);
        TextView t = label(label);
        t.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        text.addView(t);
        if (description != null && !description.isEmpty()) {
            text.addView(buildHint(description));
        }

        View swatch = new View(this);
        GradientDrawable d = new GradientDrawable();
        d.setColor(currentColor);
        d.setCornerRadius(dp(4));
        d.setStroke(dp(1), theme.separator);
        swatch.setBackground(d);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(40), dp(24));
        slp.setMargins(dp(8), 0, dp(8), 0);
        swatch.setLayoutParams(slp);

        TextView hex = new TextView(this);
        hex.setText(toHex(currentColor));
        hex.setTextColor(theme.textDim);
        hex.setTextSize(11);
        hex.setTypeface(new AppPreferences(this).resolveTypeface());

        row.addView(text);
        row.addView(swatch);
        row.addView(hex);

        row.setContentDescription(getString(R.string.a11y_settings_color_picker, label));
        row.setOnClickListener(v -> showColorDialog(label, currentColor, c -> {
            d.setColor(c);
            hex.setText(toHex(c));
            onChosen.onColor(c);
        }));

        customColorsSection.addView(row);
        return swatch;
    }

    private interface ColorChosen { void onColor(int color); }

    private void showColorDialog(String title, int initial, ColorChosen cb) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        box.setPadding(p, p, p, p);

        // Кольорове прев'ю
        View preview = new View(this);
        GradientDrawable pd = new GradientDrawable();
        pd.setColor(initial);
        pd.setCornerRadius(dp(6));
        pd.setStroke(dp(1), theme.separator);
        preview.setBackground(pd);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40));
        plp.bottomMargin = dp(8);
        preview.setLayoutParams(plp);
        box.addView(preview);

        final int[] rgb = { Color.red(initial), Color.green(initial), Color.blue(initial) };

        EditText hexInput = new EditText(this);
        hexInput.setHint("#RRGGBB");
        hexInput.setText(toHex(initial));
        hexInput.setTypeface(new AppPreferences(this).resolveTypeface());
        hexInput.setTextColor(theme.text);
        hexInput.setHintTextColor(theme.textDim);
        hexInput.setFilters(new InputFilter[] { new InputFilter.LengthFilter(7) });
        hexInput.setInputType(InputType.TYPE_CLASS_TEXT);
        hexInput.setContentDescription(getString(R.string.a11y_settings_hex_input));
        box.addView(hexInput);

        SeekBar rs = newColorSeek(rgb[0]);
        rs.setContentDescription(getString(R.string.a11y_settings_color_r));
        SeekBar gs = newColorSeek(rgb[1]);
        gs.setContentDescription(getString(R.string.a11y_settings_color_g));
        SeekBar bs = newColorSeek(rgb[2]);
        bs.setContentDescription(getString(R.string.a11y_settings_color_b));
        TextView rl = colorSeekLabel("R", rgb[0]);
        TextView gl = colorSeekLabel("G", rgb[1]);
        TextView bl = colorSeekLabel("B", rgb[2]);
        box.addView(rl); box.addView(rs);
        box.addView(gl); box.addView(gs);
        box.addView(bl); box.addView(bs);

        SeekBar.OnSeekBarChangeListener listener = new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                if (s == rs) { rgb[0] = progress; rl.setText("R: " + progress); }
                if (s == gs) { rgb[1] = progress; gl.setText("G: " + progress); }
                if (s == bs) { rgb[2] = progress; bl.setText("B: " + progress); }
                int c = Color.rgb(rgb[0], rgb[1], rgb[2]);
                pd.setColor(c);
                hexInput.setText(toHex(c));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        };
        rs.setOnSeekBarChangeListener(listener);
        gs.setOnSeekBarChangeListener(listener);
        bs.setOnSeekBarChangeListener(listener);

        hexInput.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) tryParseHex(hexInput, rs, gs, bs, pd);
        });

        // Палітра пресетних кольорів
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(8);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        glp.topMargin = dp(8);
        grid.setLayoutParams(glp);
        for (int c : PRESET_PALETTE) {
            View dot = new View(this);
            GradientDrawable dd = new GradientDrawable();
            dd.setColor(c);
            dd.setCornerRadius(dp(3));
            dd.setStroke(dp(1), theme.separator);
            dot.setBackground(dd);
            GridLayout.LayoutParams gp = new GridLayout.LayoutParams();
            gp.width = dp(28);
            gp.height = dp(28);
            gp.setMargins(dp(2), dp(2), dp(2), dp(2));
            dot.setLayoutParams(gp);
            dot.setOnClickListener(view -> {
                rs.setProgress(Color.red(c));
                gs.setProgress(Color.green(c));
                bs.setProgress(Color.blue(c));
            });
            grid.addView(dot);
        }
        box.addView(grid);

        newRoundedDialog()
                .setTitle(title)
                .setView(box)
                .setPositiveButton(R.string.dialog_apply, (d, w) -> {
                    tryParseHex(hexInput, rs, gs, bs, pd);
                    cb.onColor(Color.rgb(rgb[0], rgb[1], rgb[2]));
                })
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }

    private static final int[] PRESET_PALETTE = {
            0xFF000000, 0xFF1E1E1E, 0xFF2B2B2B, 0xFF3C3F41,
            0xFF808080, 0xFFBBBBBB, 0xFFEEEEEE, 0xFFFFFFFF,
            0xFFFF6B6B, 0xFFE74C3C, 0xFFD13438, 0xFFBF616A,
            0xFFFFA500, 0xFFE6A23C, 0xFFCC7832, 0xFFCCC77A,
            0xFF6A8759, 0xFF499C54, 0xFF50FA7B, 0xFFA6E22E,
            0xFF4A86C8, 0xFF1F6FEB, 0xFF268BD2, 0xFF88C0D0,
            0xFF6897BB, 0xFFAE81FF, 0xFFBD93F9, 0xFFB48EAD,
            0xFFFF79C6, 0xFFE6DB74, 0xFFF1FA8C, 0xFF859900
    };

    private SeekBar newColorSeek(int v) {
        SeekBar s = new SeekBar(this);
        s.setMax(255);
        s.setProgress(v);
        return s;
    }

    private TextView colorSeekLabel(String comp, int v) {
        TextView t = new TextView(this);
        t.setText(comp + ": " + v);
        t.setTextColor(theme.text);
        t.setTextSize(11);
        return t;
    }

    private void tryParseHex(EditText input, SeekBar r, SeekBar g, SeekBar b, GradientDrawable preview) {
        try {
            String s = input.getText().toString().trim();
            if (!s.startsWith("#")) s = "#" + s;
            int c = Color.parseColor(s);
            r.setProgress(Color.red(c));
            g.setProgress(Color.green(c));
            b.setProgress(Color.blue(c));
            preview.setColor(c);
        } catch (Exception ignored) {
        }
    }

    // ── Editor ────────────────────────────────────────────────

    private View buildEditorSection() {
        LinearLayout section = newSection(getString(R.string.settings_section_editor));

        // Font family
        section.addView(settingLabel(getString(R.string.settings_font_family),
                getString(R.string.settings_font_family_desc)));
        final boolean customFontActive = CustomFontManager.isInstalled(this, prefs.getCustomFontPath());
        String[] builtInFonts = new String[] {
                getString(R.string.font_monospace),
                getString(R.string.font_sans),
                getString(R.string.font_serif),
                getString(R.string.font_default),
                getString(R.string.font_jetbrains),
                getString(R.string.font_fira_code),
                getString(R.string.font_source_code),
                getString(R.string.font_dejavu_mono),
                getString(R.string.font_roboto_mono)
        };
        String[] fontOptions = customFontActive
                ? prependCustomFontOption(builtInFonts)
                : builtInFonts;
        int initialFontPos = customFontActive ? 0 : prefs.getFontFamily();
        final int[] fontSel = { Math.max(0, initialFontPos) };
        final TextView fontDropdown = newDropdownButton(fontOptions, fontSel[0], anchor -> {
            AnchoredMenu menu = AnchoredMenu.with(this, theme);
            for (int i = 0; i < fontOptions.length; i++) {
                final int idx = i;
                menu.checkable(fontOptions[idx], idx == fontSel[0], () -> {
                    fontSel[0] = idx;
                    if (customFontActive && idx == 0) return;
                    int family = customFontActive ? idx - 1 : idx;
                    prefs.setCustomFontPath(null);
                    prefs.setCustomFontUrl("");
                    prefs.setFontFamily(family);
                });
            }
            menu.showBelow(anchor);
        });
        section.addView(fontDropdown);
        section.addView(buildCustomFontControls());

        // Font size
        TextView sizeLabel = label(getString(R.string.settings_font_size_n, prefs.getFontSize()));
        section.addView(sizeLabel);
        section.addView(buildHint(getString(R.string.settings_font_size_desc)));
        SeekBar sizeBar = new SeekBar(this);
        sizeBar.setContentDescription(getString(R.string.a11y_settings_font_size));
        sizeBar.setMax(24); // 8..32
        sizeBar.setProgress(prefs.getFontSize() - 8);
        sizeBar.setOnSeekBarChangeListener(simpleSeek(progress -> {
            int sz = progress + 8;
            sizeLabel.setText(getString(R.string.settings_font_size_n, sz));
            prefs.setFontSize(sz);
        }));
        section.addView(sizeBar);

        // Tab size
        TextView tabLabel = label(getString(R.string.settings_tab_size_n, prefs.getTabSize()));
        section.addView(tabLabel);
        section.addView(buildHint(getString(R.string.settings_tab_size_desc)));
        SeekBar tabBar = new SeekBar(this);
        tabBar.setContentDescription(getString(R.string.a11y_settings_tab_size));
        tabBar.setMax(7); // 1..8
        tabBar.setProgress(prefs.getTabSize() - 1);
        tabBar.setOnSeekBarChangeListener(simpleSeek(progress -> {
            int t = progress + 1;
            tabLabel.setText(getString(R.string.settings_tab_size_n, t));
            prefs.setTabSize(t);
        }));
        section.addView(tabBar);

        // Line spacing
        TextView lsLabel = label(getString(R.string.settings_line_spacing_n,
                String.format(Locale.US, "%.1f", prefs.getLineSpacing())));
        section.addView(lsLabel);
        section.addView(buildHint(getString(R.string.settings_line_spacing_desc)));
        SeekBar lsBar = new SeekBar(this);
        lsBar.setContentDescription(getString(R.string.a11y_settings_line_spacing));
        lsBar.setMax(20); // 1.0..3.0 step 0.1
        lsBar.setProgress((int) ((prefs.getLineSpacing() - 1f) * 10));
        lsBar.setOnSeekBarChangeListener(simpleSeek(progress -> {
            float ls = 1f + progress / 10f;
            lsLabel.setText(getString(R.string.settings_line_spacing_n,
                    String.format(Locale.US, "%.1f", ls)));
            prefs.setLineSpacing(ls);
        }));
        section.addView(lsBar);

        section.addView(buildSwitch(getString(R.string.settings_line_numbers),
                getString(R.string.settings_line_numbers_desc),
                prefs.isLineNumbers(), prefs::setLineNumbers));
        section.addView(buildSwitch(getString(R.string.settings_word_wrap),
                getString(R.string.settings_word_wrap_desc),
                prefs.isWordWrap(), prefs::setWordWrap));
        section.addView(buildSwitch(getString(R.string.settings_auto_save),
                getString(R.string.settings_auto_save_desc),
                prefs.isAutoSave(), prefs::setAutoSave));
        TextView autoSaveDelayLabel = label(getString(R.string.settings_auto_save_delay_n,
                formatDelay(prefs.getAutoSaveDelayMs())));
        section.addView(autoSaveDelayLabel);
        SeekBar autoSaveDelay = new SeekBar(this);
        autoSaveDelay.setMax(50); // 0..5000 ms, 100 ms steps
        autoSaveDelay.setProgress(prefs.getAutoSaveDelayMs() / 100);
        autoSaveDelay.setContentDescription(getString(R.string.a11y_settings_auto_save_delay));
        autoSaveDelay.setOnSeekBarChangeListener(simpleSeek(progress -> {
            int delay = progress * 100;
            prefs.setAutoSaveDelayMs(delay);
            autoSaveDelayLabel.setText(getString(R.string.settings_auto_save_delay_n,
                    formatDelay(delay)));
        }));
        section.addView(autoSaveDelay);
        section.addView(buildHint(getString(R.string.settings_auto_save_delay_hint)));
        section.addView(buildSwitch(getString(R.string.settings_minimap),
                getString(R.string.settings_minimap_desc),
                prefs.isMinimap(), prefs::setMinimap));

        section.addView(buildSwitch(getString(R.string.settings_auto_close_pairs),
                getString(R.string.settings_auto_close_pairs_desc),
                prefs.isAutoClosePairs(), prefs::setAutoClosePairs));

        section.addView(buildSwitch(getString(R.string.settings_highlight_current_line),
                getString(R.string.settings_highlight_current_line_desc),
                prefs.isHighlightCurrentLine(), prefs::setHighlightCurrentLine));

        section.addView(buildSwitch(getString(R.string.settings_indent_guides),
                getString(R.string.settings_indent_guides_desc),
                prefs.isIndentGuides(), prefs::setIndentGuides));

        section.addView(buildSwitch(getString(R.string.settings_bracket_highlight),
                getString(R.string.settings_bracket_highlight_desc),
                prefs.isBracketHighlight(), prefs::setBracketHighlight));

        section.addView(buildSwitch(getString(R.string.settings_contextual_symbols),
                getString(R.string.settings_contextual_symbols_desc),
                prefs.isContextualSymbols(), prefs::setContextualSymbols));

        section.addView(settingLabel(getString(R.string.settings_keyboard_shortcuts),
                getString(R.string.settings_keyboard_shortcuts_desc)));
        addShortcutEditor(section, getString(R.string.settings_shortcut_duplicate_line), "duplicate_line", "Ctrl+D");
        addShortcutEditor(section, getString(R.string.settings_shortcut_toggle_comment), "toggle_comment", "Ctrl+/");
        addShortcutEditor(section, getString(R.string.settings_shortcut_save), "save", "Ctrl+S");
        addShortcutEditor(section, getString(R.string.settings_shortcut_find), "find", "Ctrl+F");
        addShortcutEditor(section, getString(R.string.settings_shortcut_run), "run", "Ctrl+R");
        addShortcutEditor(section, getString(R.string.settings_shortcut_delete_line), "delete_line", "Ctrl+Y");
        TextView resetShortcuts = actionText(getString(R.string.settings_shortcut_reset), theme.accent);
        resetShortcuts.setOnClickListener(v -> { prefs.resetShortcuts(); recreate(); });
        section.addView(resetShortcuts);

        // Key bar symbols
        section.addView(settingLabel(getString(R.string.settings_key_bar_symbols),
                getString(R.string.settings_key_bar_symbols_desc)));
        TextView keySymbolsSummary = subtitle(prefs.getKeyBarSymbols());
        section.addView(keySymbolsSummary);
        TextView editKeySymbolsBtn = actionText(getString(R.string.ps_a11y_edit, getString(R.string.settings_key_bar_symbols)), theme.accent);
        editKeySymbolsBtn.setOnClickListener(v -> {
            EditText input = new EditText(this);
            input.setText(prefs.getKeyBarSymbols());
            input.setHint(R.string.settings_key_bar_symbols_hint);
            input.setTextColor(theme.text);
            input.setHintTextColor(theme.textDim);
            int p = dp(16);
            input.setPadding(p, p, p, p);
            new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle(R.string.settings_key_bar_symbols_dialog_title)
                    .setView(input)
                    .setPositiveButton(android.R.string.ok, (d, w) -> {
                        String val = input.getText().toString().trim();
                        if (val.isEmpty()) val = AppPreferences.DEFAULT_KEY_BAR_SYMBOLS;
                        prefs.setKeyBarSymbols(val);
                        keySymbolsSummary.setText(val);
                    })
                    .setNeutralButton(R.string.settings_compiler_warnings_default, (d, w) -> {
                        prefs.setKeyBarSymbols(AppPreferences.DEFAULT_KEY_BAR_SYMBOLS);
                        keySymbolsSummary.setText(AppPreferences.DEFAULT_KEY_BAR_SYMBOLS);
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        });
        section.addView(editKeySymbolsBtn);

        return section;
    }

    private void addShortcutEditor(LinearLayout section, String title, String action, String def) {
        TextView row = actionText(title + "  —  " + prefs.getShortcut(action, def), theme.text);
        row.setOnClickListener(v -> {
            final String[] value = { prefs.getShortcut(action, def) };
            TextView capture = new TextView(this);
            capture.setText("Press the new shortcut…"); capture.setTextColor(theme.text);
            capture.setFocusableInTouchMode(true); capture.requestFocus();
            capture.setOnKeyListener((view, code, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN || code == KeyEvent.KEYCODE_UNKNOWN) return true;
                StringBuilder s = new StringBuilder();
                if (event.isCtrlPressed()) s.append("Ctrl+");
                if (event.isAltPressed()) s.append("Alt+");
                if (event.isShiftPressed()) s.append("Shift+");
                String name = KeyEvent.keyCodeToString(code).replace("KEYCODE_", "");
                value[0] = s.append(name).toString(); capture.setText(value[0]); return true;
            });
            new androidx.appcompat.app.AlertDialog.Builder(this).setTitle(title)
                    .setView(capture).setPositiveButton(android.R.string.ok, (d,w) -> {
                        if (value[0].contains("+")) prefs.setShortcut(action, value[0]);
                    }).setNegativeButton(android.R.string.cancel, null).show();
        });
        section.addView(row);
    }

    private String[] prependCustomFontOption(String[] builtInFonts) {
        String path = prefs.getCustomFontPath();
        String name = path == null ? "" : new File(path).getName();
        String[] options = new String[builtInFonts.length + 1];
        options[0] = getString(R.string.settings_custom_font_option, name);
        System.arraycopy(builtInFonts, 0, options, 1, builtInFonts.length);
        return options;
    }

    private View buildCustomFontControls() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(4), 0, 0);
        box.setClipChildren(false);

        box.addView(settingLabel(getString(R.string.settings_custom_font),
                getString(R.string.settings_custom_font_desc)));

        customFontUrlInput = new EditText(this);
        customFontUrlInput.setSingleLine(true);
        customFontUrlInput.setText(prefs.getCustomFontUrl());
        customFontUrlInput.setHint(R.string.settings_custom_font_url_hint);
        customFontUrlInput.setTextColor(theme.text);
        customFontUrlInput.setHintTextColor(theme.textDim);
        customFontUrlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        customFontUrlInput.setContentDescription(getString(R.string.a11y_settings_custom_font_url));
        box.addView(customFontUrlInput);

        customFontDownloadButton = actionText(getString(R.string.settings_custom_font_download), theme.accent);
        LinearLayout.LayoutParams downloadParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        downloadParams.topMargin = dp(6);
        downloadParams.bottomMargin = dp(4);
        customFontDownloadButton.setLayoutParams(downloadParams);
        box.addView(customFontDownloadButton);

        LinearLayout secondaryActions = new LinearLayout(this);
        secondaryActions.setOrientation(LinearLayout.HORIZONTAL);
        secondaryActions.setGravity(Gravity.CENTER_VERTICAL);
        secondaryActions.setClipChildren(false);
        LinearLayout.LayoutParams secondaryParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        secondaryParams.topMargin = dp(4);
        secondaryParams.bottomMargin = dp(6);
        secondaryActions.setLayoutParams(secondaryParams);

        customFontChooseButton = actionText(getString(R.string.settings_custom_font_choose), theme.accent);
        LinearLayout.LayoutParams chooseParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        customFontChooseButton.setLayoutParams(chooseParams);
        secondaryActions.addView(customFontChooseButton);

        customFontRemoveButton = actionText(getString(R.string.settings_custom_font_remove), theme.errorText);
        LinearLayout.LayoutParams clearParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clearParams.leftMargin = dp(8);
        customFontRemoveButton.setLayoutParams(clearParams);
        customFontRemoveButton.setVisibility(CustomFontManager.isInstalled(this, prefs.getCustomFontPath())
                ? View.VISIBLE : View.GONE);
        secondaryActions.addView(customFontRemoveButton);
        box.addView(secondaryActions);

        customFontStatusView = subtitle(customFontStatus());
        box.addView(customFontStatusView);

        customFontDownloadButton.setOnClickListener(v -> {
            String url = customFontUrlInput.getText().toString().trim();
            if (!url.toLowerCase(Locale.US).startsWith("https://")) {
                customFontStatusView.setText(R.string.settings_custom_font_https_required);
                return;
            }
            setCustomFontControlsBusy(true);
            customFontStatusView.setText(getString(R.string.settings_custom_font_downloading, 0));
            CustomFontManager.download(this, url, new CustomFontManager.Callback() {
                @Override public void onProgress(int percent) {
                    if (customFontStatusView != null) {
                        customFontStatusView.setText(getString(
                                R.string.settings_custom_font_downloading, percent));
                    }
                }

                @Override public void onSuccess(File file) {
                    activateCustomFont(file, url);
                }

                @Override public void onError(String message) {
                    showCustomFontError(message);
                }
            });
        });

        customFontChooseButton.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {
                    "font/ttf", "font/otf", "font/collection", "application/octet-stream"
            });
            startActivityForResult(intent, REQ_CUSTOM_FONT_FILE);
        });

        customFontRemoveButton.setOnClickListener(v -> {
            CustomFontManager.clearManagedFont(this);
            prefs.setCustomFontPath(null);
            prefs.setCustomFontUrl("");
            prefs.bumpCustomFontVersion();
            customFontRemoveButton.setVisibility(View.GONE);
            customFontStatusView.setText(R.string.settings_custom_font_none);
        });
        return box;
    }

    private void setCustomFontControlsBusy(boolean busy) {
        if (customFontDownloadButton != null) customFontDownloadButton.setEnabled(!busy);
        if (customFontChooseButton != null) customFontChooseButton.setEnabled(!busy);
        if (customFontRemoveButton != null) customFontRemoveButton.setEnabled(!busy);
    }

    private void activateCustomFont(File file, String sourceUrl) {
        prefs.setCustomFontPath(file.getAbsolutePath());
        prefs.setCustomFontUrl(sourceUrl);
        prefs.bumpCustomFontVersion();
        if (customFontStatusView != null) {
            customFontStatusView.setText(getString(
                    R.string.settings_custom_font_downloaded, file.getName()));
        }
        if (customFontRemoveButton != null) {
            customFontRemoveButton.setVisibility(View.VISIBLE);
        }
        setCustomFontControlsBusy(false);
    }

    private void showCustomFontError(String message) {
        if (customFontStatusView != null) {
            customFontStatusView.setText(getString(R.string.settings_custom_font_error, message));
        }
        setCustomFontControlsBusy(false);
    }

    private String customFontStatus() {
        String path = prefs.getCustomFontPath();
        if (CustomFontManager.isInstalled(this, path)) {
            return getString(R.string.settings_custom_font_active, new File(path).getName());
        }
        return getString(R.string.settings_custom_font_none);
    }

    /** Small dim caption under a setting, explaining what it changes. */
    private View buildHint(String text) {
        TextView hint = new TextView(this);
        hint.setText(text);
        hint.setTextColor(theme.textDim);
        hint.setTextSize(11);
        hint.setPadding(dp(4), 0, dp(4), dp(8));
        return hint;
    }

    // ── Bottom panel layout ───────────────────────────────────

    /** Order and visibility of the Run / Problems / Bytecode / … tab strip. */
    private View buildPanelLayoutSection() {
        LinearLayout section = newSection(getString(R.string.settings_section_panels));
        section.addView(buildHint(getString(R.string.settings_panels_hint)));

        PanelLayoutEditor editor = new PanelLayoutEditor(this, theme, prefs);
        section.addView(editor.getView());

        TextView reset = new TextView(this);
        reset.setText(R.string.settings_panels_reset);
        reset.setTextColor(theme.accent);
        reset.setTextSize(13);
        reset.setPadding(dp(4), dp(10), dp(4), dp(4));
        reset.setOnClickListener(v -> {
            editor.reset();
            Toast.makeText(this, R.string.settings_panels_reset_done, Toast.LENGTH_SHORT).show();
        });
        section.addView(reset);

        return section;
    }

    /**
     * Which Kotlin language version the bundled compiler targets.
     *
     * <p>Sits in the compiler section beside the Java level, because it is the
     * same kind of choice: not which compiler runs, but which language version
     * it compiles as.</p>
     */
    private View buildKotlinVersionControls() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        box.addView(settingLabel(getString(R.string.settings_kotlin_language),
                getString(R.string.settings_kotlin_language_desc)));

        java.util.List<String> versions =
                com.ccs.wiredroid.tools.compilers.KotlinVersions.selectable();
        String[] labels = new String[versions.size()];
        for (int i = 0; i < versions.size(); i++) {
            labels[i] = com.ccs.wiredroid.tools.compilers.KotlinVersions.label(
                    versions.get(i), getString(R.string.settings_kotlin_language_default));
        }

        String current = prefs.getKotlinLanguageVersion();
        int selected = Math.max(0, versions.indexOf(current));
        final int[] kotlinSel = { selected };
        final TextView[] kotlinDropdownRef = new TextView[1];
        kotlinDropdownRef[0] = newDropdownButton(labels, kotlinSel[0], anchor -> {
            AnchoredMenu menu = AnchoredMenu.with(this, theme);
            for (int i = 0; i < versions.size(); i++) {
                final int idx = i;
                menu.checkable(labels[idx], idx == kotlinSel[0], () -> {
                    kotlinSel[0] = idx;
                    prefs.setKotlinLanguageVersion(versions.get(idx));
                    if (kotlinDropdownRef[0] != null) {
                        kotlinDropdownRef[0].setText(labels[idx] + "  ▾");
                    }
                });
            }
            menu.showBelow(anchor);
        });
        box.addView(kotlinDropdownRef[0]);
        return box;
    }

    /**
     * Version pickers for the JVM languages that ship inside the app.
     *
     * <p>The bundled version is marked, because it is the one that needs no
     * network: picking any other means a download the first time that language
     * runs.</p>
     */
    private View buildLanguageVersionControls() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        for (com.ccs.wiredroid.langrt.JvmLanguage language
                : com.ccs.wiredroid.langrt.JvmLanguage.values()) {
            box.addView(settingLabel(
                    getString(R.string.settings_language_version, language.displayName()),
                    getString(R.string.settings_language_version_desc)));

            java.util.List<String> versions =
                    com.ccs.wiredroid.langrt.LanguageRuntimes.selectableVersions(this, language);
            String bundled =
                    com.ccs.wiredroid.langrt.LanguageRuntimes.bundledVersion(this, language);
            String[] labels = new String[versions.size()];
            for (int i = 0; i < versions.size(); i++) {
                labels[i] = versions.get(i).equals(bundled)
                        ? getString(R.string.settings_language_version_bundled, versions.get(i))
                        : getString(R.string.settings_language_version_download, versions.get(i));
            }

            String current = prefs.getLanguageVersion(language.id);
            final int[] langVerSel = { Math.max(0, versions.indexOf(current)) };
            final com.ccs.wiredroid.langrt.JvmLanguage current_language = language;
            final TextView[] langDropdownRef = new TextView[1];
            langDropdownRef[0] = newDropdownButton(labels, langVerSel[0], anchor -> {
                AnchoredMenu menu = AnchoredMenu.with(this, theme);
                for (int i = 0; i < versions.size(); i++) {
                    final int idx = i;
                    menu.checkable(labels[idx], idx == langVerSel[0], () -> {
                        langVerSel[0] = idx;
                        prefs.setLanguageVersion(current_language.id, versions.get(idx));
                        if (langDropdownRef[0] != null) {
                            langDropdownRef[0].setText(labels[idx] + "  ▾");
                        }
                    });
                }
                menu.showBelow(anchor);
            });
            box.addView(langDropdownRef[0]);
        }
        return box;
    }

    // ── Toolbar ───────────────────────────────────────────────

    /**
     * Which buttons the top bar shows.
     *
     * <p>Every one of these also lives in the searchable menu, so switching one
     * off hides the button without taking the action away.</p>
     */
    private View buildToolbarSection() {
        LinearLayout section = newSection(getString(R.string.settings_section_toolbar));
        section.addView(buildHint(getString(R.string.settings_toolbar_hint)));

        section.addView(buildSwitch(getString(R.string.menu_undo),
                prefs.isToolbarUndo(), prefs::setToolbarUndo));
        section.addView(buildSwitch(getString(R.string.menu_redo),
                prefs.isToolbarRedo(), prefs::setToolbarRedo));
        section.addView(buildSwitch(getString(R.string.menu_find_replace),
                prefs.isToolbarFind(), prefs::setToolbarFind));
        section.addView(buildSwitch(getString(R.string.menu_format_code),
                prefs.isToolbarFormat(), prefs::setToolbarFormat));
        section.addView(buildSwitch(getString(R.string.menu_ai_chat),
                prefs.isToolbarAiChat(), prefs::setToolbarAiChat));

        return section;
    }

    // ── Compiler ──────────────────────────────────────────────

    private View buildCompilerSection() {
        LinearLayout section = newSection("Embedded · C/C++");
        section.addView(buildHint("Clang · C++17 · Arduino GPIO API. Основний формат — .cpp; файли .c, .h, .hpp та Arduino-скетчі .ino доступні в редакторі. Вбудований компілятор на ARM64 Android; встановлювати JDK не потрібно."));
        section.addView(buildHint("Монтаж усіх деталей Fritzing та цифрова GPIO-модель усіх плат. Запуск до 8 програм одночасно. CPU, SDK виробників і прошивання фізичних плат ще не реалізовані."));
        return section;
    }

    // ── Files & Projects ─────────────────────────────────────

    private View buildFilesSection() {
        LinearLayout section = newSection(getString(R.string.settings_section_files));

        // The structure screen is where a project's name, package and language
        // level are changed. It used to be reachable only from the editor's
        // menu, which is not where anyone looks for a project setting.
        //
        // Shown only when a project is actually open: from the welcome screen
        // there is nothing to edit, and offering the entry there meant editing
        // the previously opened project by accident.
        final File structureTarget = currentProjectDir();
        if (structureTarget != null) {
            section.addView(settingLabel(getString(R.string.settings_open_project_structure),
                    getString(R.string.settings_open_project_structure_desc)));
            TextView openStructure = actionText(
                    getString(R.string.settings_open_project_structure), theme.accent);
            openStructure.setOnClickListener(v ->
                    ProjectStructureActivity.launch(this, structureTarget));
            section.addView(openStructure);
        }

        section.addView(buildSwitch(getString(R.string.settings_show_dotfiles),
                getString(R.string.settings_show_dotfiles_desc),
                prefs.isShowDotfiles(), prefs::setShowDotfiles));

        section.addView(buildSwitch(getString(R.string.settings_confirm_file_delete),
                getString(R.string.settings_confirm_file_delete_desc),
                prefs.isConfirmFileDelete(), prefs::setConfirmFileDelete));

        // Search excludes
        section.addView(settingLabel(getString(R.string.settings_search_excludes),
                getString(R.string.settings_search_excludes_desc)));
        EditText excludesInput = new EditText(this);
        excludesInput.setSingleLine(true);
        excludesInput.setText(prefs.getSearchExcludes());
        excludesInput.setTextColor(theme.text);
        excludesInput.setHintTextColor(theme.textDim);
        excludesInput.setTextSize(13);
        excludesInput.setInputType(InputType.TYPE_CLASS_TEXT);
        excludesInput.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) prefs.setSearchExcludes(excludesInput.getText().toString().trim());
        });
        section.addView(excludesInput);
        section.addView(buildHint(getString(R.string.settings_search_excludes_hint)));

        // Default project path
        section.addView(settingLabel(getString(R.string.settings_default_project_path),
                getString(R.string.settings_default_project_path_desc)));
        String currentPath = prefs.getDefaultProjectPath();
        TextView pathLabel = subtitle(currentPath != null ? currentPath
                : getString(R.string.settings_default_project_path_internal));
        section.addView(pathLabel);

        EditText pathInput = new EditText(this);
        pathInput.setSingleLine(true);
        pathInput.setText(currentPath != null ? currentPath : "");
        pathInput.setHint(getString(R.string.settings_default_project_path_internal));
        pathInput.setTextColor(theme.text);
        pathInput.setHintTextColor(theme.textDim);
        pathInput.setTextSize(13);
        pathInput.setInputType(InputType.TYPE_CLASS_TEXT);
        pathInput.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                String text = pathInput.getText().toString().trim();
                prefs.setDefaultProjectPath(text.isEmpty() ? null : text);
                pathLabel.setText(text.isEmpty()
                        ? getString(R.string.settings_default_project_path_internal) : text);
            }
        });
        section.addView(pathInput);

        return section;
    }

    // ── Power Saving ───────────────────────────────────────

    private View buildPowerSavingSection() {
        LinearLayout section = newSection(getString(R.string.settings_section_power_saving));

        TextView desc = new TextView(this);
        desc.setText(getString(R.string.settings_power_saving_desc));
        desc.setTextColor(theme.textDim);
        desc.setTextSize(11);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.bottomMargin = dp(8);
        desc.setLayoutParams(dlp);
        section.addView(desc);
        section.addView(buildHint(getString(R.string.settings_power_saving_mode_desc)));

        String[] modes = {
                getString(R.string.settings_power_saving_auto),
                getString(R.string.settings_power_saving_disabled),
                getString(R.string.settings_power_saving_performance),
                getString(R.string.settings_power_saving_always)
        };
        Spinner sp = newSpinner(modes);
        sp.setContentDescription(getString(R.string.a11y_settings_power_saving));
        sp.setSelection(prefs.getPowerSavingMode());
        sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                prefs.setPowerSavingMode(position);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        section.addView(sp);

        section.addView(buildSwitch(getString(R.string.settings_power_saving_follow_system),
                getString(R.string.settings_power_saving_follow_system_desc),
                prefs.isPowerSavingRespectSystem(), prefs::setPowerSavingRespectSystem));

        TextView thresholdLabel = label(getString(R.string.settings_power_saving_threshold_n,
                prefs.getPowerSavingBatteryThreshold()));
        section.addView(thresholdLabel);
        section.addView(buildHint(getString(R.string.settings_power_saving_threshold_desc)));
        SeekBar threshold = new SeekBar(this);
        threshold.setMax(70); // 10..80%
        threshold.setProgress(prefs.getPowerSavingBatteryThreshold() - 10);
        threshold.setContentDescription(getString(R.string.a11y_settings_power_threshold));
        threshold.setOnSeekBarChangeListener(simpleSeek(progress -> {
            int value = progress + 10;
            prefs.setPowerSavingBatteryThreshold(value);
            thresholdLabel.setText(getString(R.string.settings_power_saving_threshold_n, value));
        }));
        section.addView(threshold);

        TextView intervalLabel = label(getString(R.string.settings_power_saving_interval_n,
                prefs.getPowerSavingScanIntervalSec()));
        section.addView(intervalLabel);
        section.addView(buildHint(getString(R.string.settings_power_saving_interval_desc)));
        SeekBar interval = new SeekBar(this);
        interval.setMax(285); // 15..300 seconds
        interval.setProgress(prefs.getPowerSavingScanIntervalSec() - 15);
        interval.setContentDescription(getString(R.string.a11y_settings_power_interval));
        interval.setOnSeekBarChangeListener(simpleSeek(progress -> {
            int value = progress + 15;
            prefs.setPowerSavingScanIntervalSec(value);
            intervalLabel.setText(getString(R.string.settings_power_saving_interval_n, value));
        }));
        section.addView(interval);

        section.addView(buildSwitch(getString(R.string.settings_power_saving_reduce_animations),
                getString(R.string.settings_power_saving_reduce_animations_desc),
                prefs.isPowerSavingReduceAnimations(), prefs::setPowerSavingReduceAnimations));

        section.addView(buildHint(getString(R.string.settings_power_saving_highlight_hint)));

        section.addView(buildPowerProfileEditor());

        return section;
    }

    private View buildPowerProfileEditor() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.topMargin = dp(14);
        card.setLayoutParams(cardParams);
        GradientDrawable cardBackground = new GradientDrawable();
        cardBackground.setColor(theme.toolbar);
        cardBackground.setStroke(dp(1), theme.separator);
        cardBackground.setCornerRadius(dp(10));
        card.setBackground(cardBackground);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams tabsParams = new LinearLayout.LayoutParams(0, dp(40), 1f);
        tabs.setLayoutParams(tabsParams);

        TextView performanceTab = profileTab(getString(R.string.settings_profile_performance));
        TextView savingTab = profileTab(getString(R.string.settings_profile_power_saving));
        tabs.addView(performanceTab, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        tabs.addView(savingTab, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        controls.addView(tabs);

        TextView reset = new TextView(this);
        reset.setText(getString(R.string.settings_profile_reset));
        reset.setTextColor(theme.errorText);
        reset.setTextSize(11);
        reset.setGravity(Gravity.CENTER);
        reset.setPadding(dp(10), 0, dp(10), 0);
        LinearLayout.LayoutParams resetParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(40));
        resetParams.leftMargin = dp(8);
        reset.setLayoutParams(resetParams);
        GradientDrawable resetBackground = new GradientDrawable();
        resetBackground.setColor(Color.TRANSPARENT);
        resetBackground.setStroke(dp(1), theme.errorText);
        resetBackground.setCornerRadius(dp(8));
        reset.setBackground(resetBackground);
        controls.addView(reset);
        card.addView(controls);

        TextView hint = (TextView) buildHint("");
        card.addView(hint);

        LinearLayout options = new LinearLayout(this);
        options.setOrientation(LinearLayout.VERTICAL);
        card.addView(options);

        final boolean[] performanceSelected = { true };
        Runnable refresh = () -> {
            boolean performance = performanceSelected[0];
            styleProfileTab(performanceTab, performance, true);
            styleProfileTab(savingTab, !performance, false);
            hint.setText(getString(performance
                    ? R.string.settings_power_saving_performance_hint
                    : R.string.settings_power_saving_mode_hint));
            populatePowerProfileOptions(options, performance);
        };

        performanceTab.setOnClickListener(v -> {
            performanceSelected[0] = true;
            refresh.run();
        });
        savingTab.setOnClickListener(v -> {
            performanceSelected[0] = false;
            refresh.run();
        });
        reset.setOnClickListener(v -> newRoundedDialog()
                .setTitle(R.string.settings_profile_reset)
                .setMessage(performanceSelected[0]
                        ? R.string.settings_profile_reset_performance_confirm
                        : R.string.settings_profile_reset_saving_confirm)
                .setPositiveButton(R.string.dialog_apply, (dialog, which) -> {
                    resetPowerProfile(performanceSelected[0]);
                    refresh.run();
                    Toast.makeText(this, R.string.settings_profile_reset_done,
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(R.string.dialog_cancel, null)
                .show());

        refresh.run();
        return card;
    }

    private TextView profileTab(String title) {
        TextView tab = new TextView(this);
        tab.setText(title);
        tab.setTextSize(12);
        tab.setGravity(Gravity.CENTER);
        tab.setPadding(dp(8), 0, dp(8), 0);
        tab.setClickable(true);
        tab.setFocusable(true);
        tab.setContentDescription(title);
        return tab;
    }

    private void styleProfileTab(TextView tab, boolean selected, boolean left) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(selected ? theme.accent : Color.TRANSPARENT);
        background.setStroke(dp(1), selected ? theme.accent : theme.separator);
        float radius = dp(8);
        background.setCornerRadii(left
                ? new float[] { radius, radius, 0, 0, 0, 0, radius, radius }
                : new float[] { 0, 0, radius, radius, radius, radius, 0, 0 });
        tab.setBackground(background);
        tab.setTextColor(selected ? theme.bg : theme.textDim);
        tab.setSelected(selected);
    }

    private void populatePowerProfileOptions(LinearLayout options, boolean performance) {
        options.removeAllViews();
        if (performance) {
            options.addView(buildSwitch(getString(R.string.settings_profile_auto_save),
                    getString(R.string.settings_profile_auto_save_desc),
                    prefs.isPerfAutoSave(), prefs::setPerfAutoSave));
            options.addView(buildSwitch(getString(R.string.settings_profile_minimap),
                    getString(R.string.settings_profile_minimap_desc),
                    prefs.isPerfMinimap(), prefs::setPerfMinimap));
            options.addView(buildSwitch(getString(R.string.settings_profile_auto_search),
                    getString(R.string.settings_profile_auto_search_desc),
                    prefs.isPerfAutoSearch(), prefs::setPerfAutoSearch));
            options.addView(buildSwitch(getString(R.string.settings_profile_gutter),
                    getString(R.string.settings_profile_gutter_desc),
                    prefs.isPerfGitGutter(), prefs::setPerfGitGutter));
            options.addView(buildSwitch(getString(R.string.settings_profile_verbose_logging),
                    getString(R.string.settings_profile_verbose_logging_desc),
                    prefs.isPerfVerboseLogging(), prefs::setPerfVerboseLogging));
        } else {
            options.addView(buildSwitch(getString(R.string.settings_profile_auto_save),
                    getString(R.string.settings_profile_auto_save_desc),
                    prefs.isPsAutoSave(), prefs::setPsAutoSave));
            options.addView(buildSwitch(getString(R.string.settings_profile_minimap),
                    getString(R.string.settings_profile_minimap_desc),
                    prefs.isPsMinimap(), prefs::setPsMinimap));
            options.addView(buildSwitch(getString(R.string.settings_profile_auto_search),
                    getString(R.string.settings_profile_auto_search_desc),
                    prefs.isPsAutoSearch(), prefs::setPsAutoSearch));
            options.addView(buildSwitch(getString(R.string.settings_profile_gutter),
                    getString(R.string.settings_profile_gutter_desc),
                    prefs.isPsGitGutter(), prefs::setPsGitGutter));
            options.addView(buildSwitch(getString(R.string.settings_profile_verbose_logging),
                    getString(R.string.settings_profile_verbose_logging_desc),
                    prefs.isPsVerboseLogging(), prefs::setPsVerboseLogging));
        }
    }

    private void resetPowerProfile(boolean performance) {
        if (performance) {
            prefs.setPerfAstHighlighting(true);
            prefs.setPerfLiveProblems(true);
            prefs.setPerfAutoSave(true);
            prefs.setPerfFormatOnSave(true);
            prefs.setPerfMinimap(true);
            prefs.setPerfAutoSearch(true);
            prefs.setPerfVerboseLogging(true);
        } else {
            prefs.setPsAstHighlighting(false);
            prefs.setPsLiveProblems(false);
            prefs.setPsAutoSave(false);
            prefs.setPsFormatOnSave(false);
            prefs.setPsMinimap(false);
            prefs.setPsAutoSearch(false);
            prefs.setPsVerboseLogging(false);
        }
    }

    private View buildResetButton() {
        TextView btn = new TextView(this);
        btn.setText(getString(R.string.settings_reset_defaults));
        btn.setTextColor(theme.errorText);
        btn.setTextSize(13);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dp(16), dp(14), dp(16), dp(14));
        btn.setContentDescription(getString(R.string.a11y_settings_reset));
        GradientDrawable d = new GradientDrawable();
        d.setColor(0x00000000);
        d.setStroke(dp(1), theme.errorText);
        d.setCornerRadius(dp(6));
        btn.setBackground(d);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(24);
        btn.setLayoutParams(lp);
        btn.setOnClickListener(v -> newRoundedDialog()
                .setTitle(R.string.settings_reset_defaults)
                .setMessage(R.string.settings_reset_confirm)
                .setPositiveButton(R.string.dialog_apply, (di, w) -> resetDefaults())
                .setNegativeButton(R.string.dialog_cancel, null)
                .show());
        return btn;
    }

    /** The project the editor has open, or null when there is none. */
    /**
     * The project this screen is editing, or null when it was opened without one.
     *
     * <p>Only what the caller passed. It used to read the stored project root,
     * which is the last project opened — so Project Structure, reached from the
     * welcome screen where nothing is open, silently edited whatever had been
     * open before.</p>
     */
    private File currentProjectDir() {
        String root = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_PROJECT_ROOT);
        if (root == null || root.isEmpty()) return null;
        File dir = new File(root);
        return dir.isDirectory() ? dir : null;
    }

    private void resetDefaults() {
        // Залишаємо project_root, скидаємо все інше.
        String root = prefs.getProjectRoot();
        CustomFontManager.clearManagedFont(this);
        prefs.raw().edit().clear().apply();
        if (root != null) prefs.setProjectRoot(root);
        Toast.makeText(this, R.string.settings_reset_done, Toast.LENGTH_SHORT).show();
        smoothRecreate();
    }

    private void deleteRecursive(File fileOrDirectory) {
        if (fileOrDirectory.isDirectory()) {
            File[] files = fileOrDirectory.listFiles();
            if (files != null) {
                for (File child : files) {
                    deleteRecursive(child);
                }
            }
        }
        fileOrDirectory.delete();
    }

    // ══════════════════════════════════════════════════════════
    //  UI helpers
    // ══════════════════════════════════════════════════════════

    // ── About: support contact and version ────────────────────

    /** Support address shown in settings and used as the mailto: recipient. */
    private static final String SUPPORT_EMAIL = "cybercraftstudiopro@gmail.com";
    private static final String SOURCE_URL = "https://github.com/MrmaderatorYT/WireDroid";

    /**
     * Last section of the settings screen: a way to get in touch, and the exact
     * version string. Both rows are tappable — the version copies itself, because
     * the usual reason to look at it is to paste it into a bug report.
     */
    private View buildAboutSection() {
        LinearLayout section = newSection(getString(R.string.settings_section_about));

        TextView support = label(getString(R.string.settings_support_email));
        support.setTextColor(theme.accent);
        support.setOnClickListener(v -> openSupportEmail());
        section.addView(support);
        section.addView(subtitle(SUPPORT_EMAIL));
        section.addView(buildHint(getString(R.string.settings_support_email_hint)));

        TextView source = label(getString(R.string.settings_source_code));
        source.setTextColor(theme.accent);
        source.setOnClickListener(v -> openSourceRepository());
        // Long-press copies instead: a device with no browser, or one where the
        // link opens in something useless, should still be able to hand the
        // address to someone.
        source.setOnLongClickListener(v -> {
            copyToClipboard(SOURCE_URL);
            Toast.makeText(this, R.string.settings_source_code_copied, Toast.LENGTH_SHORT).show();
            return true;
        });
        section.addView(source);
        section.addView(subtitle(SOURCE_URL));
        section.addView(buildHint(getString(R.string.settings_source_code_hint)));

        TextView version = label(getString(R.string.settings_version,
                BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE));
        version.setOnClickListener(v -> {
            copyToClipboard(getString(R.string.settings_version,
                    BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE));
            Toast.makeText(this, R.string.settings_version_copied, Toast.LENGTH_SHORT).show();
        });
        section.addView(version);

        return section;
    }

    private void openSourceRepository() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(SOURCE_URL)));
        } catch (android.content.ActivityNotFoundException e) {
            // Same reasoning as the support address: an address on the clipboard
            // beats an error message about a missing browser.
            copyToClipboard(SOURCE_URL);
            Toast.makeText(this, R.string.settings_source_code_copied, Toast.LENGTH_LONG).show();
        }
    }

    private void openSupportEmail() {
        String subject = getString(R.string.settings_support_subject, BuildConfig.VERSION_NAME);
        String body = getString(R.string.settings_support_body,
                BuildConfig.VERSION_NAME,
                BuildConfig.VERSION_CODE,
                android.os.Build.VERSION.RELEASE,
                android.os.Build.VERSION.SDK_INT,
                android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL);

        // ACTION_SENDTO with a mailto: URI resolves only against real mail clients;
        // ACTION_SEND would also offer chat apps, which cannot reach this address.
        Intent mail = new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"));
        mail.putExtra(Intent.EXTRA_EMAIL, new String[]{SUPPORT_EMAIL});
        mail.putExtra(Intent.EXTRA_SUBJECT, subject);
        mail.putExtra(Intent.EXTRA_TEXT, body);

        try {
            startActivity(mail);
        } catch (android.content.ActivityNotFoundException e) {
            // No mail client: copying the address is more use than an error toast.
            copyToClipboard(SUPPORT_EMAIL);
            Toast.makeText(this, R.string.settings_support_no_client, Toast.LENGTH_LONG).show();
        }
    }

    private void copyToClipboard(String text) {
        android.content.ClipboardManager cb = (android.content.ClipboardManager)
                getSystemService(CLIPBOARD_SERVICE);
        if (cb != null) {
            cb.setPrimaryClip(android.content.ClipData.newPlainText("WireDroid", text));
        }
    }

    private LinearLayout newSection(String title) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(16);
        box.setLayoutParams(lp);

        TextView header = new TextView(this);
        header.setText(title);
        header.setTextColor(theme.accent);
        header.setTextSize(11);
        header.setLetterSpacing(0.08f);
        header.setAllCaps(true);
        header.setPadding(0, 0, 0, dp(8));
        box.addView(header);

        View sep = new View(this);
        sep.setBackgroundColor(theme.separator);
        sep.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        box.addView(sep);

        return box;
    }

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(theme.text);
        t.setTextSize(13);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        t.setLayoutParams(lp);
        return t;
    }

    /** A static setting title followed by a short explanation. */
    private View settingLabel(String title, String description) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(label(title));
        if (description != null && !description.isEmpty()) {
            box.addView(buildHint(description));
        }
        return box;
    }

    private TextView subtitle(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(theme.textDim);
        t.setTextSize(12);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        t.setLayoutParams(lp);
        return t;
    }

    private TextView actionText(String text, int color) {
        TextView action = new TextView(this);
        action.setText(text);
        action.setTextColor(color);
        action.setTextSize(13);
        action.setGravity(Gravity.CENTER);
        action.setPadding(dp(12), dp(10), dp(12), dp(10));
        action.setClickable(true);
        action.setFocusable(true);
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.TRANSPARENT);
        background.setStroke(dp(1), color);
        background.setCornerRadius(dp(6));
        action.setBackground(background);
        return action;
    }

    private TextView newDropdownButton(String[] items, int selected, java.util.function.Consumer<View> onOpen) {
        TextView btn = new TextView(this);
        String label = (selected >= 0 && selected < items.length) ? items[selected] : "";
        btn.setText(label + "  ▾");
        btn.setTextColor(theme.text);
        btn.setTextSize(13);
        btn.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        btn.setPadding(dp(12), dp(10), dp(12), dp(10));
        btn.setClickable(true);
        btn.setFocusable(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Colors.blend(theme.bg, theme.text, 0.05f));
        bg.setStroke(dp(1), theme.separator);
        bg.setCornerRadius(dp(6));
        btn.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        btn.setLayoutParams(lp);
        btn.setOnClickListener(v -> onOpen.accept(btn));
        return btn;
    }

    private Spinner newSpinner(String[] items) {
        Spinner sp = new Spinner(this);
        ArrayAdapter<String> ad = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, items) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView v = (TextView) super.getView(position, convertView, parent);
                v.setTextColor(theme.text);
                return v;
            }
        };
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(ad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        sp.setLayoutParams(lp);
        return sp;
    }

    private View buildSwitch(String title, boolean initial, BoolSetter setter) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(8);
        row.setLayoutParams(rlp);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(theme.text);
        t.setTextSize(13);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        t.setLayoutParams(tlp);

        Switch sw = new Switch(this);
        sw.setContentDescription(title);
        sw.setChecked(initial);
        sw.setOnCheckedChangeListener((CompoundButton b, boolean v) -> setter.set(v));

        row.addView(t);
        row.addView(sw);
        return row;
    }

    /** Switch variant that keeps the explanation visually attached to its control. */
    private View buildSwitch(String title, String description, boolean initial, BoolSetter setter) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(buildSwitch(title, initial, setter));
        if (description != null && !description.isEmpty()) {
            box.addView(buildHint(description));
        }
        return box;
    }

    private interface BoolSetter { void set(boolean v); }

    private String formatDelay(int delayMs) {
        if (delayMs <= 0) return getString(R.string.settings_auto_save_delay_immediate);
        if (delayMs % 1000 == 0) return (delayMs / 1000) + " s";
        return String.format(Locale.US, "%.1f s", delayMs / 1000f);
    }

    private SeekBar.OnSeekBarChangeListener simpleSeek(IntConsumer cb) {
        return new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int progress, boolean fromUser) { cb.accept(progress); }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        };
    }

    private interface IntConsumer { void accept(int v); }

    private View spacer(int h) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, h));
        return v;
    }

    private static String toHex(int color) {
        return String.format(Locale.US, "#%06X", 0xFFFFFF & color);
    }

    private static boolean sameText(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    /** @see Dialogs#rounded */
    private com.google.android.material.dialog.MaterialAlertDialogBuilder newRoundedDialog() {
        return Dialogs.rounded(this);
    }

    // ══════════════════════════════════════════════════════════
    //  In-Settings Search (Word-like find with navigation & highlight)
    // ══════════════════════════════════════════════════════════

    private LinearLayout buildSearchBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Colors.blend(theme.toolbar, theme.bg, 0.5f));
        bar.setPadding(dp(12), dp(6), dp(12), dp(6));

        // Search icon
        android.widget.ImageView icon = new android.widget.ImageView(this);
        icon.setImageResource(R.drawable.ic_search);
        icon.setColorFilter(theme.textDim, android.graphics.PorterDuff.Mode.SRC_IN);
        LinearLayout.LayoutParams icLp = new LinearLayout.LayoutParams(dp(18), dp(18));
        icLp.setMarginEnd(dp(8));
        bar.addView(icon, icLp);

        // Edit text
        searchInputField = new EditText(this);
        searchInputField.setHint(R.string.settings_search_hint);
        searchInputField.setHintTextColor(theme.textDim);
        searchInputField.setTextColor(theme.text);
        searchInputField.setTextSize(13);
        searchInputField.setSingleLine(true);
        searchInputField.setBackground(null);
        searchInputField.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        searchInputField.setInputType(InputType.TYPE_CLASS_TEXT);
        LinearLayout.LayoutParams editLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        bar.addView(searchInputField, editLp);

        // Counter label e.g. "1 of 5"
        searchCountView = new TextView(this);
        searchCountView.setTextSize(11);
        searchCountView.setTextColor(theme.textDim);
        searchCountView.setPadding(dp(4), 0, dp(6), 0);
        searchCountView.setText(R.string.settings_search_no_matches);
        bar.addView(searchCountView);

        // Up arrow (Previous)
        android.widget.ImageView prevBtn = new android.widget.ImageView(this);
        prevBtn.setImageResource(R.drawable.ic_arrow_up);
        prevBtn.setColorFilter(theme.text, android.graphics.PorterDuff.Mode.SRC_IN);
        prevBtn.setContentDescription(getString(R.string.settings_search_prev));
        prevBtn.setPadding(dp(6), dp(6), dp(6), dp(6));
        android.util.TypedValue tv = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true);
        if (tv.resourceId != 0) prevBtn.setBackgroundResource(tv.resourceId);
        prevBtn.setOnClickListener(v -> navigateMatch(-1));
        bar.addView(prevBtn, new LinearLayout.LayoutParams(dp(32), dp(32)));

        // Down arrow (Next)
        android.widget.ImageView nextBtn = new android.widget.ImageView(this);
        nextBtn.setImageResource(R.drawable.ic_arrow_down);
        nextBtn.setColorFilter(theme.text, android.graphics.PorterDuff.Mode.SRC_IN);
        nextBtn.setContentDescription(getString(R.string.settings_search_next));
        nextBtn.setPadding(dp(6), dp(6), dp(6), dp(6));
        if (tv.resourceId != 0) nextBtn.setBackgroundResource(tv.resourceId);
        nextBtn.setOnClickListener(v -> navigateMatch(1));
        bar.addView(nextBtn, new LinearLayout.LayoutParams(dp(32), dp(32)));

        // Close button
        android.widget.ImageView closeBtn = new android.widget.ImageView(this);
        closeBtn.setImageResource(R.drawable.ic_close);
        closeBtn.setColorFilter(theme.textDim, android.graphics.PorterDuff.Mode.SRC_IN);
        closeBtn.setContentDescription(getString(R.string.settings_search_close));
        closeBtn.setPadding(dp(6), dp(6), dp(6), dp(6));
        if (tv.resourceId != 0) closeBtn.setBackgroundResource(tv.resourceId);
        closeBtn.setOnClickListener(v -> closeSearch());
        bar.addView(closeBtn, new LinearLayout.LayoutParams(dp(32), dp(32)));

        searchInputField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                executeSearch(s != null ? s.toString().trim() : "");
            }
        });

        searchInputField.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
                    || (event != null && event.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER)) {
                navigateMatch(1);
                return true;
            }
            return false;
        });

        return bar;
    }

    private void toggleSearch() {
        if (isSearchActive) {
            closeSearch();
        } else {
            openSearch();
        }
    }

    private void openSearch() {
        isSearchActive = true;
        ensureRemainingSectionsAttached();
        if (searchBarLayout != null) {
            searchBarLayout.setVisibility(View.VISIBLE);
        }
        if (searchInputField != null) {
            searchInputField.requestFocus();
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(searchInputField, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
            String current = searchInputField.getText().toString().trim();
            if (!current.isEmpty()) {
                executeSearch(current);
            }
        }
    }

    private void closeSearch() {
        isSearchActive = false;
        if (searchBarLayout != null) {
            searchBarLayout.setVisibility(View.GONE);
        }
        if (searchInputField != null) {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(searchInputField.getWindowToken(), 0);
        }
        clearSearchHighlights();
    }

    private void clearSearchHighlights() {
        searchMatches.clear();
        currentMatchIndex = -1;
        for (java.util.Map.Entry<TextView, CharSequence> entry : originalTexts.entrySet()) {
            TextView tv = entry.getKey();
            if (tv != null) {
                tv.setText(entry.getValue());
            }
        }
        originalTexts.clear();
        if (searchCountView != null) {
            searchCountView.setText(R.string.settings_search_no_matches);
        }
    }

    private void executeSearch(String query) {
        clearSearchHighlights();
        if (query.isEmpty() || settingsContentLayout == null) return;

        java.util.List<TextView> textViews = new java.util.ArrayList<>();
        collectTextViews(settingsContentLayout, textViews);

        String lowerQuery = query.toLowerCase(Locale.getDefault());

        for (TextView tv : textViews) {
            CharSequence cs = tv.getText();
            if (cs == null || cs.length() == 0) continue;
            String text = cs.toString();
            String lowerText = text.toLowerCase(Locale.getDefault());

            int index = 0;
            boolean hasMatchInView = false;
            while ((index = lowerText.indexOf(lowerQuery, index)) >= 0) {
                if (!hasMatchInView) {
                    originalTexts.put(tv, cs);
                    hasMatchInView = true;
                }
                searchMatches.add(new TextMatch(tv, cs, index, index + lowerQuery.length()));
                index += lowerQuery.length();
            }
        }

        if (!searchMatches.isEmpty()) {
            currentMatchIndex = 0;
            updateHighlights();
            scrollToMatch(searchMatches.get(0));
        } else {
            currentMatchIndex = -1;
            if (searchCountView != null) {
                searchCountView.setText(R.string.settings_search_no_matches);
            }
        }
    }

    private void navigateMatch(int delta) {
        if (searchMatches.isEmpty()) return;
        currentMatchIndex = (currentMatchIndex + delta + searchMatches.size()) % searchMatches.size();
        updateHighlights();
        scrollToMatch(searchMatches.get(currentMatchIndex));
    }

    private void updateHighlights() {
        if (searchCountView != null) {
            if (searchMatches.isEmpty()) {
                searchCountView.setText(R.string.settings_search_no_matches);
            } else {
                searchCountView.setText(getString(R.string.settings_search_matches,
                        currentMatchIndex + 1, searchMatches.size()));
            }
        }

        // Distinct colors like in Word: Yellow for all other matches, Orange for the active match
        final int COLOR_OTHER_MATCH = 0x88FDD835; // translucent yellow
        final int COLOR_ACTIVE_MATCH = 0xCCFF6D00; // intense orange

        // Group matches by TextView
        java.util.Map<TextView, java.util.List<TextMatch>> byView = new java.util.HashMap<>();
        for (TextMatch match : searchMatches) {
            java.util.List<TextMatch> list = byView.computeIfAbsent(match.view, k -> new java.util.ArrayList<>());
            list.add(match);
        }

        TextMatch activeMatch = (currentMatchIndex >= 0 && currentMatchIndex < searchMatches.size())
                ? searchMatches.get(currentMatchIndex) : null;

        for (java.util.Map.Entry<TextView, java.util.List<TextMatch>> entry : byView.entrySet()) {
            TextView tv = entry.getKey();
            CharSequence orig = originalTexts.get(tv);
            if (orig == null) orig = tv.getText();
            SpannableString spannable = new SpannableString(orig);

            for (TextMatch m : entry.getValue()) {
                boolean isActive = (m == activeMatch);
                spannable.setSpan(
                        new BackgroundColorSpan(isActive ? COLOR_ACTIVE_MATCH : COLOR_OTHER_MATCH),
                        m.start,
                        m.end,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            tv.setText(spannable);
        }
    }

    private void scrollToMatch(TextMatch match) {
        if (match == null || match.view == null || settingsScrollView == null) return;
        match.view.post(() -> {
            int[] pos = new int[2];
            int[] scrollPos = new int[2];
            match.view.getLocationOnScreen(pos);
            settingsScrollView.getLocationOnScreen(scrollPos);

            int viewYInScroll = pos[1] - scrollPos[1] + settingsScrollView.getScrollY();
            int targetY = Math.max(0, viewYInScroll - dp(80)); // keep some padding from top
            settingsScrollView.smoothScrollTo(0, targetY);
        });
    }

    private void collectTextViews(View root, java.util.List<TextView> out) {
        if (root == null || root.getVisibility() != View.VISIBLE) return;
        if (root == searchBarLayout) return; // skip search bar controls
        if (root instanceof TextView) {
            TextView tv = (TextView) root;
            if (!(tv instanceof EditText) && tv.getText() != null && tv.getText().length() > 0) {
                out.add(tv);
            }
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectTextViews(group.getChildAt(i), out);
            }
        }
    }

    public static void launch(Activity host, int requestCode) {
        launch(host, requestCode, null);
    }

    /**
     * Opens settings, telling it which project is open.
     *
     * <p>Pass null from anywhere with no project — the welcome screen, most
     * notably. Settings then leaves out the entries that only mean something
     * inside a project, rather than quietly acting on whichever one happened to
     * be opened last.</p>
     */
    public static void launch(Activity host, int requestCode, java.io.File projectDir) {
        android.content.Intent intent = new android.content.Intent(host, SettingsActivity.class);
        if (projectDir != null) intent.putExtra(EXTRA_PROJECT_ROOT, projectDir.getAbsolutePath());
        host.startActivityForResult(intent, requestCode);
    }
}
