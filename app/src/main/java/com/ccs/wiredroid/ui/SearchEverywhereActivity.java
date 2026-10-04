package com.ccs.wiredroid.ui;

import com.ccs.wiredroid.R;
import com.ccs.wiredroid.project.ProjectScanner;
import com.ccs.wiredroid.util.AppPreferences;
import com.ccs.wiredroid.util.AppTheme;
import com.ccs.wiredroid.util.Colors;
import com.ccs.wiredroid.util.FullScreenHelper;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Search Everywhere — the Shift+Shift screen: one field over classes, methods,
 * files and the app's own settings.
 *
 * <p>The project index is built once off the main thread from
 * {@link ProjectScanner} and cached statically per project root, so reopening
 * the screen (or rotating) is instant. Declaration scanning is regex-based, not
 * a real parser: fast, forgiving of broken code, and good enough to jump.</p>
 *
 * <p>Matching is IntelliJ-flavoured: exact prefix beats camel-hump
 * ({@code mA} → {@code MainActivity}) beats plain substring, and the matched
 * characters are highlighted in the row.</p>
 */
public class SearchEverywhereActivity extends AppCompatActivity {

    private static final String EXTRA_PROJECT_ROOT = "project_root";

    /**
     * Mirrors {@code MainActivity.REQ_GLOBAL_SEARCH}. That handler already reads
     * the {@code file_path} / {@code line_number} extras and opens the file at
     * the line, which is exactly the contract this screen returns.
     */
    private static final int REQUEST_CODE = 4006;
    private static final String RESULT_FILE_PATH = "file_path";
    private static final String RESULT_LINE_NUMBER = "line_number";

    // ── Index bounds: a huge repo must never hang the screen ───────────
    private static final int MAX_FILES = 4000;
    private static final long MAX_FILE_BYTES = 512L * 1024L;
    private static final int MAX_LINES_PER_FILE = 6000;
    private static final int MAX_LINE_LENGTH = 400;
    private static final int MAX_DECLS_PER_FILE = 500;
    private static final int MAX_DECLS_TOTAL = 20000;

    private static final int GROUP_LIMIT = 20;
    private static final int DEBOUNCE_MS = 140;

    private static final int KIND_CLASS = 0;
    private static final int KIND_METHOD = 1;
    private static final int KIND_FILE = 2;
    private static final int KIND_SETTING = 3;

    private static final int SCORE_PREFIX = 3000;
    private static final int SCORE_CAMEL = 2000;
    private static final int SCORE_SUBSTRING = 1000;
    private static final int SCORE_ALIAS = 400;

    /** {@code public final class Foo}, {@code data class Bar}, {@code object Baz}. */
    private static final Pattern TYPE_DECL = Pattern.compile(
            "^[\\w@\\s]*?\\b(class|interface|enum|record|object)\\s+([A-Za-z_$][\\w$]*)");

    /** Java method or constructor. Groups: modifiers, return type, name. */
    private static final Pattern JAVA_METHOD = Pattern.compile(
            "^((?:(?:public|protected|private|static|final|abstract|synchronized|native|default|strictfp)\\s+)*)"
                    + "(?:<[^>]{0,100}>\\s*)?"
                    + "([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*(?:<[^>]{0,150}>)?(?:\\[\\s*\\])*\\s+)?"
                    + "([A-Za-z_$][\\w$]*)\\s*\\(");

    /** Kotlin {@code fun}, including a receiver ({@code fun File.ext()}). */
    private static final Pattern KOTLIN_FUN = Pattern.compile(
            "^[\\w@\\s]*?\\bfun\\s+(?:<[^>]{0,100}>\\s*)?(?:[A-Za-z_][\\w.<>?]*\\.)?([A-Za-z_][\\w]*)\\s*\\(");

    /** Words that would otherwise look like a method name to the regex. */
    private static final List<String> NOT_A_NAME = java.util.Arrays.asList(
            "if", "for", "while", "switch", "catch", "return", "new", "do", "else",
            "try", "throw", "assert", "super", "this", "synchronized", "instanceof",
            "case", "yield", "when", "sealed", "record");

    /** Index cache — keyed by project root, kept across screen instances. */
    private static volatile Index sCache;

    private AppPreferences prefs;
    private AppTheme theme;
    private Typeface mono;

    private File projectRoot;
    private Index index;
    private volatile List<Entry> settingsEntries;

    private EditText etQuery;
    private TextView statusText;
    private ProgressBar progress;
    private RecyclerView recycler;
    private ResultsAdapter adapter;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final AtomicInteger searchSeq = new AtomicInteger();
    private Runnable pendingSearch;

    public static void launch(Context context, File projectDir) {
        Intent i = new Intent(context, SearchEverywhereActivity.class);
        if (projectDir != null) i.putExtra(EXTRA_PROJECT_ROOT, projectDir.getAbsolutePath());
        if (context instanceof Activity) {
            ((Activity) context).startActivityForResult(i, REQUEST_CODE);
        } else {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(i);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        prefs = new AppPreferences(this);
        theme = AppTheme.byId(prefs.getThemeId(), prefs);
        // The full-screen themes, like every other activity. A Dialog theme makes
        // the window float and dims whatever is behind it, which reads as a dark
        // backdrop around the search screen even when the app is in light mode.
        setTheme(theme.dark ? R.style.Theme_WireDroid : R.style.Theme_WireDroid_Light);

        super.onCreate(savedInstanceState);
        mono = prefs.resolveTypeface();
        setContentView(buildRoot());
        FullScreenHelper.enable(this);
        // No getWindow().setLayout() here. Shrinking the window to a fraction of
        // the screen leaves the black window background showing on every side,
        // which reads as a dialog floating over a destroyed activity rather than
        // as a screen. This is a full screen like the other thirty-one.

        String rootPath = getIntent().getStringExtra(EXTRA_PROJECT_ROOT);
        projectRoot = rootPath != null ? new File(rootPath) : null;

        if (projectRoot == null || !projectRoot.isDirectory()) {
            // Settings are still searchable without a project.
            index = Index.empty();
            progress.setVisibility(View.GONE);
            statusText.setText(R.string.se_no_project);
        } else {
            startIndexing(projectRoot);
        }

        focusQueryField();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (pendingSearch != null) ui.removeCallbacks(pendingSearch);
        io.shutdownNow();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    // ── UI ─────────────────────────────────────────────────────────────

    private View buildRoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(theme.bg);

        Toolbar toolbar = new Toolbar(this);
        toolbar.setBackgroundColor(theme.toolbar);
        toolbar.setTitle(R.string.se_title);
        toolbar.setTitleTextColor(theme.text);
        toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
        toolbar.setNavigationOnClickListener(v -> finish());
        root.addView(toolbar);

        LinearLayout queryRow = new LinearLayout(this);
        queryRow.setOrientation(LinearLayout.HORIZONTAL);
        queryRow.setBackgroundColor(theme.toolbar);
        queryRow.setGravity(Gravity.CENTER_VERTICAL);
        queryRow.setPadding(dp(8), dp(4), dp(8), dp(6));

        etQuery = new EditText(this);
        etQuery.setHint(R.string.se_hint);
        etQuery.setHintTextColor(theme.textDim);
        etQuery.setTextColor(theme.text);
        android.graphics.drawable.GradientDrawable qBg = new android.graphics.drawable.GradientDrawable();
        qBg.setColor(Colors.blend(theme.consoleBg, theme.bg, 0.35f));
        qBg.setCornerRadius(dp(6));
        qBg.setStroke(dp(1), theme.separator);
        etQuery.setBackground(qBg);
        etQuery.setTextSize(15);
        etQuery.setSingleLine(true);
        etQuery.setPadding(dp(12), dp(10), dp(12), dp(10));
        etQuery.setContentDescription(getString(R.string.se_a11y_field));
        etQuery.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { scheduleSearch(); }
        });
        queryRow.addView(etQuery, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView clear = new TextView(this);
        clear.setText(R.string.se_clear);
        clear.setTextColor(theme.accent);
        clear.setTextSize(13);
        clear.setPadding(dp(14), dp(10), dp(10), dp(10));
        clear.setBackgroundResource(android.R.drawable.list_selector_background);
        clear.setContentDescription(getString(R.string.se_a11y_clear));
        clear.setOnClickListener(v -> etQuery.setText(""));
        queryRow.addView(clear);

        root.addView(queryRow);

        View sep = new View(this);
        sep.setBackgroundColor(theme.separator);
        root.addView(sep, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        root.addView(progress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        statusText = new TextView(this);
        statusText.setTextColor(theme.textDim);
        statusText.setTextSize(11);
        statusText.setPadding(dp(12), dp(6), dp(12), dp(6));
        statusText.setText(R.string.se_indexing);
        root.addView(statusText);

        recycler = new RecyclerView(this);
        recycler.setBackgroundColor(theme.bg);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ResultsAdapter();
        recycler.setAdapter(adapter);
        root.addView(recycler, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        return root;
    }

    private void focusQueryField() {
        etQuery.requestFocus();
        etQuery.postDelayed(() -> {
            InputMethodManager imm =
                    (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(etQuery, InputMethodManager.SHOW_IMPLICIT);
        }, 150);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    // ── Indexing ───────────────────────────────────────────────────────

    private void startIndexing(File root) {
        Index cached = sCache;
        if (cached != null && cached.matches(root)) {
            index = cached;
            onIndexReady();
            return;
        }

        progress.setVisibility(View.VISIBLE);
        statusText.setText(R.string.se_indexing);

        io.execute(() -> {
            Index built;
            try {
                built = buildIndex(root);
            } catch (Throwable t) {
                built = null;
            }
            Index result = built;
            ui.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (result == null) {
                    index = Index.empty();
                    progress.setVisibility(View.GONE);
                    statusText.setText(R.string.se_index_failed);
                    return;
                }
                sCache = result;
                index = result;
                onIndexReady();
            });
        });
    }

    private void onIndexReady() {
        progress.setVisibility(View.GONE);
        int cls = index.classes.size();
        int mth = index.methods.size();
        statusText.setText(index.truncated
                ? getString(R.string.se_index_truncated, index.files.size(), cls, mth)
                : getString(R.string.se_index_ready, index.files.size(), cls, mth));
        // Typing while the index was building is honoured as soon as it lands.
        if (query().isEmpty()) {
            adapter.submit(Collections.emptyList());
        } else {
            runSearch(query());
        }
    }

    /** Walks the project via {@link ProjectScanner} and pulls declarations out. */
    private Index buildIndex(File root) {
        List<Entry> classes = new ArrayList<>();
        List<Entry> methods = new ArrayList<>();
        List<Entry> files = new ArrayList<>();

        List<FileTreeNode> nodes = ProjectScanner.listIdeaStyleTree(root, null);
        int rootLen = root.getAbsolutePath().length();
        int scanned = 0;
        boolean truncated = false;
        int declCount = 0;

        for (FileTreeNode node : nodes) {
            if (node.directory) continue;
            if (scanned >= MAX_FILES) { truncated = true; break; }
            File f = node.path;
            String rel = relativize(rootLen, f);
            scanned++;

            files.add(new Entry(KIND_FILE, f.getName(), rel, f, 0, rel));

            if (declCount < MAX_DECLS_TOTAL) {
                String name = f.getName().toLowerCase(Locale.ROOT);
                boolean java = name.endsWith(".java");
                boolean kotlin = name.endsWith(".kt");
                if ((java || kotlin) && f.length() <= MAX_FILE_BYTES) {
                    declCount += scanDeclarations(f, rel, java, classes, methods);
                }
            }

            if ((scanned % 250) == 0) {
                int done = scanned;
                ui.post(() -> {
                    if (!isFinishing() && progress.getVisibility() == View.VISIBLE) {
                        statusText.setText(getString(R.string.se_indexing_progress, done));
                    }
                });
            }
        }

        Comparator<Entry> byName = (a, b) -> a.name.compareToIgnoreCase(b.name);
        classes.sort(byName);
        methods.sort(byName);
        files.sort(byName);
        return new Index(root.getAbsolutePath(), classes, methods, files, truncated);
    }

    /** @return how many declarations were recorded for this file */
    private int scanDeclarations(File f, String rel, boolean java,
                                 List<Entry> classes, List<Entry> methods) {
        String content;
        try {
            byte[] bytes = Files.readAllBytes(f.toPath());
            content = new String(bytes, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return 0;
        }

        int found = 0;
        String[] lines = content.split("\n", MAX_LINES_PER_FILE + 1);
        int limit = Math.min(lines.length, MAX_LINES_PER_FILE);
        boolean inBlockComment = false;

        for (int i = 0; i < limit && found < MAX_DECLS_PER_FILE; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            if (inBlockComment) {
                if (line.contains("*/")) inBlockComment = false;
                continue;
            }
            if (line.startsWith("//") || line.startsWith("*")) continue;
            if (line.startsWith("/*")) {
                if (!line.contains("*/")) inBlockComment = true;
                continue;
            }
            if (line.length() > MAX_LINE_LENGTH) line = line.substring(0, MAX_LINE_LENGTH);

            int lineNo = i + 1;

            Matcher type = TYPE_DECL.matcher(line);
            if (type.find()) {
                String name = type.group(2);
                if (name != null && !NOT_A_NAME.contains(name)) {
                    classes.add(new Entry(KIND_CLASS, name, rel, f, lineNo, rel));
                    found++;
                    continue;
                }
            }

            String method = java ? javaMethodName(line) : kotlinFunName(line);
            if (method != null) {
                methods.add(new Entry(KIND_METHOD, method, rel + ":" + lineNo, f, lineNo, rel));
                found++;
            }
        }
        return found;
    }

    private static String javaMethodName(String line) {
        Matcher m = JAVA_METHOD.matcher(line);
        if (!m.find()) return null;
        String mods = m.group(1);
        String type = m.group(2);
        String name = m.group(3);
        if (name == null || NOT_A_NAME.contains(name)) return null;
        boolean hasMods = mods != null && !mods.trim().isEmpty();
        if (!hasMods && type == null) return null;          // a call, not a declaration
        if (type != null) {
            String head = type.trim();
            int dot = head.indexOf('.');
            if (dot > 0) head = head.substring(0, dot);
            if (NOT_A_NAME.contains(head)) return null;     // `new Foo(`, `return f(`
        }
        // A declaration opens a body, ends abstractly, or wraps its parameters.
        char last = line.charAt(line.length() - 1);
        if (last != '{' && last != ';' && last != ',' && last != '(' && last != ')') return null;
        return name;
    }

    private static String kotlinFunName(String line) {
        Matcher m = KOTLIN_FUN.matcher(line);
        if (!m.find()) return null;
        String name = m.group(1);
        return (name == null || NOT_A_NAME.contains(name)) ? null : name;
    }

    private static String relativize(int rootLen, File f) {
        String abs = f.getAbsolutePath();
        if (abs.length() > rootLen) {
            String rel = abs.substring(rootLen);
            return rel.startsWith("/") ? rel.substring(1) : rel;
        }
        return f.getName();
    }

    // ── Settings entries ───────────────────────────────────────────────

    /**
     * Built on the search thread the first time a query needs it. Nothing on
     * screen shows the settings list, and the thirty-odd string lookups below
     * are not worth holding up the window for.
     */
    private List<Entry> settingsEntries() {
        List<Entry> cached = settingsEntries;
        if (cached == null) {
            cached = buildSettingsEntries();
            settingsEntries = cached;
        }
        return cached;
    }

    private List<Entry> buildSettingsEntries() {
        List<Entry> out = new ArrayList<>();
        String appearance = getString(R.string.se_sec_appearance);
        String editor = getString(R.string.se_sec_editor);
        String compiler = getString(R.string.se_sec_compiler);
        String panels = getString(R.string.se_sec_panels);
        String power = getString(R.string.se_sec_power);

        addSetting(out, R.string.se_set_theme, appearance, R.string.se_set_theme_kw);
        addSetting(out, R.string.se_set_custom_colors, appearance, R.string.se_set_custom_colors_kw);
        addSetting(out, R.string.se_set_font_family, editor, R.string.se_set_font_family_kw);
        addSetting(out, R.string.se_set_font_size, editor, R.string.se_set_font_size_kw);
        addSetting(out, R.string.se_set_tab_size, editor, R.string.se_set_tab_size_kw);
        addSetting(out, R.string.se_set_line_spacing, editor, 0);
        addSetting(out, R.string.se_set_line_numbers, editor, 0);
        addSetting(out, R.string.se_set_word_wrap, editor, 0);
        addSetting(out, R.string.se_set_auto_save, editor, 0);
        addSetting(out, R.string.se_set_format_on_save, editor, 0);
        addSetting(out, R.string.se_set_minimap, editor, R.string.se_set_minimap_kw);
        addSetting(out, R.string.se_set_ast, editor, R.string.se_set_ast_kw);
        addSetting(out, R.string.se_set_java_target, compiler, R.string.se_set_java_target_kw);
        addSetting(out, R.string.se_set_ndk, compiler, R.string.se_set_ndk_kw);
        addSetting(out, R.string.se_set_verbose, compiler, R.string.se_set_verbose_kw);
        addSetting(out, R.string.se_set_panels, panels, R.string.se_set_panels_kw);
        addSetting(out, R.string.se_set_power_saving, power, R.string.se_set_power_saving_kw);
        return out;
    }

    private void addSetting(List<Entry> out, int labelRes, String section, int keywordsRes) {
        String label = getString(labelRes);
        String keywords = keywordsRes == 0 ? section : section + " " + getString(keywordsRes);
        out.add(new Entry(KIND_SETTING, label, section, null, 0, keywords));
    }

    // ── Search ─────────────────────────────────────────────────────────

    private String query() {
        return etQuery.getText().toString().trim();
    }

    private void scheduleSearch() {
        if (pendingSearch != null) ui.removeCallbacks(pendingSearch);
        final String q = query();
        if (q.isEmpty()) {
            searchSeq.incrementAndGet();
            adapter.submit(Collections.emptyList());
            statusText.setText(index == null ? getString(R.string.se_indexing)
                    : getString(R.string.se_prompt));
            return;
        }
        pendingSearch = () -> runSearch(q);
        ui.postDelayed(pendingSearch, DEBOUNCE_MS);
    }

    private void runSearch(String q) {
        if (index == null) return;      // still indexing; onIndexReady replays it
        final int token = searchSeq.incrementAndGet();
        final Index snapshot = index;
        statusText.setText(R.string.se_searching);

        io.execute(() -> {
            List<Row> rows = new ArrayList<>();
            int total = 0;
            total += collect(rows, q, snapshot.classes, R.string.se_group_classes);
            total += collect(rows, q, snapshot.methods, R.string.se_group_methods);
            total += collect(rows, q, snapshot.files, R.string.se_group_files);
            total += collect(rows, q, settingsEntries(), R.string.se_group_settings);
            final int shown = total;
            ui.post(() -> {
                if (token != searchSeq.get() || isFinishing()) return;
                adapter.submit(rows);
                statusText.setText(rows.isEmpty()
                        ? getString(R.string.se_no_results, q)
                        : getString(R.string.se_results, shown));
            });
        });
    }

    /**
     * Scores one group and appends its header, capped rows and overflow note.
     *
     * @return how many matches were actually rendered
     */
    private int collect(List<Row> rows, String q, List<Entry> pool, int headerRes) {
        List<Hit> hits = new ArrayList<>();
        for (Entry e : pool) {
            Hit h = score(q, e);
            if (h != null) hits.add(h);
        }
        if (hits.isEmpty()) return 0;
        hits.sort((a, b) -> {
            if (a.score != b.score) return Integer.compare(b.score, a.score);
            return a.entry.name.compareToIgnoreCase(b.entry.name);
        });

        int shown = Math.min(GROUP_LIMIT, hits.size());
        rows.add(Row.header(getString(headerRes)));
        for (int i = 0; i < shown; i++) rows.add(Row.item(hits.get(i)));
        if (hits.size() > shown) {
            rows.add(Row.note(getString(R.string.se_showing_n_of_m, shown, hits.size())));
        }
        return shown;
    }

    private static Hit score(String q, Entry e) {
        Match m = match(q, e.name);
        if (m != null) return new Hit(e, m.score, m.positions);
        if (e.alias != null && containsIgnoreCase(e.alias, q)) {
            return new Hit(e, SCORE_ALIAS - Math.min(e.name.length(), 200), null);
        }
        return null;
    }

    // ── Fuzzy matching ─────────────────────────────────────────────────

    private static final class Match {
        final int score;
        final int[] positions;
        Match(int score, int[] positions) { this.score = score; this.positions = positions; }
    }

    /** Exact prefix &gt; camel hump &gt; substring, with the matched offsets. */
    private static Match match(String query, String text) {
        if (text == null || query.isEmpty()) return null;
        int n = text.length(), m = query.length();
        if (m > n) return null;

        if (text.regionMatches(true, 0, query, 0, m)) {
            int[] pos = new int[m];
            for (int i = 0; i < m; i++) pos[i] = i;
            return new Match(SCORE_PREFIX - Math.min(n, 200), pos);
        }

        int[] camel = camelMatch(query, text);
        if (camel != null) {
            int gaps = 0;
            for (int i = 1; i < camel.length; i++) {
                if (camel[i] != camel[i - 1] + 1) gaps++;
            }
            return new Match(SCORE_CAMEL - camel[0] * 3 - gaps * 6 - Math.min(n, 200) / 4, camel);
        }

        int idx = indexOfIgnoreCase(text, query);
        if (idx >= 0) {
            int[] pos = new int[m];
            for (int i = 0; i < m; i++) pos[i] = idx + i;
            return new Match(SCORE_SUBSTRING - idx * 3 - Math.min(n, 200) / 4, pos);
        }
        return null;
    }

    /**
     * Camel-hump subsequence: every query character lands either on a hump
     * ({@code MainActivity} → M, A) or right after the previous match, so
     * {@code mA} hits {@code MainActivity} and {@code oCr} hits {@code onCreate}.
     */
    private static int[] camelMatch(String q, String text) {
        int m = q.length(), n = text.length();
        int[] pos = new int[m];
        int next = 0, last = -2;

        for (int qi = 0; qi < m; qi++) {
            char qc = q.charAt(qi);
            if (Character.isWhitespace(qc)) return null;
            boolean upper = Character.isUpperCase(qc);
            int found = -1;

            // A lowercase char prefers continuing the current run, an uppercase
            // one prefers the next hump — that is what makes mA ≠ ma.
            if (!upper && last >= 0 && next < n && eqIgnoreCase(text.charAt(next), qc)) {
                found = next;
            }
            if (found < 0) {
                for (int i = next; i < n; i++) {
                    if (isHump(text, i) && eqIgnoreCase(text.charAt(i), qc)) { found = i; break; }
                }
            }
            if (found < 0 && upper && last >= 0 && next < n
                    && eqIgnoreCase(text.charAt(next), qc)) {
                found = next;
            }
            if (found < 0) return null;

            pos[qi] = found;
            last = found;
            next = found + 1;
        }
        return pos;
    }

    private static boolean isHump(String text, int i) {
        if (i == 0) return true;
        char c = text.charAt(i);
        char p = text.charAt(i - 1);
        if (!Character.isLetterOrDigit(p)) return true;
        if (Character.isUpperCase(c) && !Character.isUpperCase(p)) return true;
        return Character.isDigit(c) && !Character.isDigit(p);
    }

    private static boolean eqIgnoreCase(char a, char b) {
        return a == b || Character.toLowerCase(a) == Character.toLowerCase(b);
    }

    private static int indexOfIgnoreCase(String text, String q) {
        return text.toLowerCase(Locale.ROOT).indexOf(q.toLowerCase(Locale.ROOT));
    }

    private static boolean containsIgnoreCase(String text, String q) {
        return indexOfIgnoreCase(text, q) >= 0;
    }

    // ── Model ──────────────────────────────────────────────────────────

    private static final class Entry {
        final int kind;
        final String name;
        final String detail;
        final File file;
        final int line;
        /** Secondary searchable text (path, keywords) — never highlighted. */
        final String alias;

        Entry(int kind, String name, String detail, File file, int line, String alias) {
            this.kind = kind;
            this.name = name;
            this.detail = detail;
            this.file = file;
            this.line = line;
            this.alias = alias;
        }
    }

    private static final class Hit {
        final Entry entry;
        final int score;
        final int[] positions;
        Hit(Entry entry, int score, int[] positions) {
            this.entry = entry;
            this.score = score;
            this.positions = positions;
        }
    }

    private static final class Index {
        final String rootPath;
        final List<Entry> classes;
        final List<Entry> methods;
        final List<Entry> files;
        final boolean truncated;

        Index(String rootPath, List<Entry> classes, List<Entry> methods,
              List<Entry> files, boolean truncated) {
            this.rootPath = rootPath;
            this.classes = classes;
            this.methods = methods;
            this.files = files;
            this.truncated = truncated;
        }

        static Index empty() {
            return new Index("", Collections.emptyList(), Collections.emptyList(),
                    Collections.emptyList(), false);
        }

        boolean matches(File root) {
            return root != null && rootPath.equals(root.getAbsolutePath());
        }
    }

    private static final class Row {
        static final int TYPE_HEADER = 0;
        static final int TYPE_ITEM = 1;
        static final int TYPE_NOTE = 2;

        final int type;
        final String text;
        final Hit hit;

        private Row(int type, String text, Hit hit) {
            this.type = type;
            this.text = text;
            this.hit = hit;
        }

        static Row header(String t) { return new Row(TYPE_HEADER, t, null); }
        static Row note(String t)   { return new Row(TYPE_NOTE, t, null); }
        static Row item(Hit h)      { return new Row(TYPE_ITEM, null, h); }
    }

    // ── Adapter ────────────────────────────────────────────────────────

    private final class ResultsAdapter extends RecyclerView.Adapter<ResultsAdapter.VH> {

        private List<Row> rows = new ArrayList<>();

        void submit(List<Row> list) {
            rows = list != null ? list : new ArrayList<>();
            notifyDataSetChanged();
        }

        @Override
        public int getItemCount() { return rows.size(); }

        @Override
        public int getItemViewType(int position) { return rows.get(position).type; }

        @Override
        public VH onCreateViewHolder(ViewGroup parent, int viewType) {
            if (viewType == Row.TYPE_ITEM) {
                LinearLayout row = new LinearLayout(SearchEverywhereActivity.this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(dp(14), dp(8), dp(12), dp(8));
                row.setBackgroundResource(android.R.drawable.list_selector_background);
                row.setLayoutParams(new RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

                TextView title = new TextView(SearchEverywhereActivity.this);
                title.setTextColor(theme.text);
                title.setTextSize(14);
                title.setTypeface(mono);
                title.setSingleLine(true);
                row.addView(title);

                TextView sub = new TextView(SearchEverywhereActivity.this);
                sub.setTextColor(theme.textDim);
                sub.setTextSize(11);
                sub.setSingleLine(true);
                sub.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
                row.addView(sub);

                return new VH(row, title, sub);
            }

            TextView tv = new TextView(SearchEverywhereActivity.this);
            tv.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            if (viewType == Row.TYPE_HEADER) {
                tv.setTextColor(theme.accent);
                tv.setTextSize(11);
                tv.setTypeface(mono, Typeface.BOLD);
                tv.setAllCaps(true);
                tv.setBackgroundColor(Colors.blend(theme.bg, theme.toolbar, 0.75f));
                tv.setPadding(dp(12), dp(6), dp(12), dp(6));
            } else {
                tv.setTextColor(theme.textDim);
                tv.setTextSize(10);
                tv.setPadding(dp(16), dp(2), dp(12), dp(8));
            }
            return new VH(tv, tv, null);
        }

        @Override
        public void onBindViewHolder(VH h, int position) {
            Row row = rows.get(position);
            if (row.type != Row.TYPE_ITEM) {
                h.title.setText(row.text);
                h.itemView.setOnClickListener(null);
                h.itemView.setClickable(false);
                return;
            }

            Entry e = row.hit.entry;
            h.title.setText(highlight(e.name, row.hit.positions));
            if (h.sub != null) h.sub.setText(e.detail);
            h.itemView.setContentDescription(
                    getString(R.string.se_a11y_result, e.name, e.detail));
            h.itemView.setClickable(true);
            h.itemView.setOnClickListener(v -> open(e));
        }

        private CharSequence highlight(String name, int[] positions) {
            SpannableStringBuilder ssb = new SpannableStringBuilder(name);
            if (positions != null) {
                for (int p : positions) {
                    if (p < 0 || p >= name.length()) continue;
                    ssb.setSpan(new ForegroundColorSpan(theme.accent), p, p + 1,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    ssb.setSpan(new StyleSpan(Typeface.BOLD), p, p + 1,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
            return ssb;
        }

        final class VH extends RecyclerView.ViewHolder {
            final TextView title;
            final TextView sub;
            VH(View itemView, TextView title, TextView sub) {
                super(itemView);
                this.title = title;
                this.sub = sub;
            }
        }
    }

    /** A file result goes back to the editor; a setting opens Settings. */
    private void open(Entry e) {
        if (e.kind == KIND_SETTING) {
            // Carries the project through, so settings opened from here offers
            // the project-scoped entries the editor's own route offers.
            Intent settings = new Intent(this, SettingsActivity.class);
            if (projectRoot != null) {
                settings.putExtra(SettingsActivity.EXTRA_PROJECT_ROOT,
                        projectRoot.getAbsolutePath());
            }
            startActivity(settings);
            finish();
            return;
        }
        if (e.file == null) return;
        Intent data = new Intent();
        data.putExtra(RESULT_FILE_PATH, e.file.getAbsolutePath());
        data.putExtra(RESULT_LINE_NUMBER, Math.max(1, e.line));
        setResult(RESULT_OK, data);
        finish();
    }
}
