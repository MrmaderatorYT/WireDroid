package com.ccs.wiredroid.git;

import com.ccs.wiredroid.R;
import com.ccs.wiredroid.util.AppPreferences;
import com.ccs.wiredroid.util.AppTheme;
import com.ccs.wiredroid.util.FullScreenHelper;

import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Візуальний переглядач Git diff з підсвіткою змін.
 * Показує unified diff з кольоровим кодуванням: зелений — додано, червоний — видалено.
 */
public class GitDiffActivity extends AppCompatActivity {

    public static final String EXTRA_PROJECT_DIR = "project_dir";
    public static final String EXTRA_FILE_PATH  = "file_path";
    public static final String EXTRA_DIFF_TEXT  = "diff_text";
    public static final String EXTRA_TITLE      = "title";

    private AppPreferences prefs;
    private AppTheme theme;
    private File projectDir;
    private String filePath;
    private String diffText;
    private String title;

    private LinearLayout contentBox;
    private TextView headerInfo;
    private TextView headerStats;
    private Typeface mono;

    /** Позиція та лічильники рядків, які переживають паузу між пачками рендеру. */
    private int renderIndex, renderOldLine, renderNewLine, renderToken;

    private static final int FIRST_ROWS = 80;
    private static final int NEXT_ROWS  = 50;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private static final int COLOR_ADDED_BG     = 0x3300CC66;
    private static final int COLOR_REMOVED_BG   = 0x33FF4444;
    private static final int COLOR_HEADER_BG    = 0x334488FF;
    private static final int COLOR_ADDED_TEXT   = 0xFF66BB6A;
    private static final int COLOR_REMOVED_TEXT = 0xFFEF5350;
    private static final int COLOR_HEADER_TEXT  = 0xFF42A5F5;
    private static final int COLOR_CONTEXT     = 0xFF888888;
    private static final int COLOR_LINE_NUM    = 0xFF666666;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        prefs = new AppPreferences(this);
        theme = AppTheme.byId(prefs.getThemeId(), prefs);
        setTheme(theme.dark ? R.style.Theme_WireDroid : R.style.Theme_WireDroid_Light);

        super.onCreate(savedInstanceState);
        mono = prefs.resolveTypeface();

        String dir = getIntent().getStringExtra(EXTRA_PROJECT_DIR);
        if (dir != null) projectDir = new File(dir);
        filePath = getIntent().getStringExtra(EXTRA_FILE_PATH);
        diffText = getIntent().getStringExtra(EXTRA_DIFF_TEXT);
        title = getIntent().getStringExtra(EXTRA_TITLE);
        if (title == null) title = "Diff";

        setContentView(buildRoot());
        FullScreenHelper.enable(this);

        if (diffText != null && !diffText.isEmpty()) {
            renderDiff(diffText);
        } else if (projectDir != null) {
            loadDiff();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);
        io.shutdownNow();
    }

    private View buildRoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(theme.consoleBg);

        Toolbar toolbar = new Toolbar(this);
        toolbar.setBackgroundColor(theme.toolbar);
        toolbar.setTitle(title);
        toolbar.setTitleTextColor(theme.text);
        toolbar.setNavigationIcon(R.drawable.ic_back);
        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        root.addView(toolbar);

        // Info header
        LinearLayout infoRow = new LinearLayout(this);
        infoRow.setOrientation(LinearLayout.HORIZONTAL);
        infoRow.setPadding(dp(12), dp(6), dp(12), dp(6));
        infoRow.setBackgroundColor(theme.toolbar);

        headerInfo = new TextView(this);
        headerInfo.setTextColor(theme.text);
        headerInfo.setTextSize(11);
        headerInfo.setTypeface(mono);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        headerInfo.setLayoutParams(nlp);
        infoRow.addView(headerInfo);

        headerStats = new TextView(this);
        headerStats.setTextColor(theme.textDim);
        headerStats.setTextSize(11);
        infoRow.addView(headerStats);

        root.addView(infoRow);

        View sep = new View(this);
        sep.setBackgroundColor(theme.separator);
        sep.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        root.addView(sep);

        // Scrollable content
        ScrollView sv = new ScrollView(this);
        HorizontalScrollView hsv = new HorizontalScrollView(this);
        contentBox = new LinearLayout(this);
        contentBox.setOrientation(LinearLayout.VERTICAL);
        contentBox.setPadding(dp(4), dp(4), dp(4), dp(4));
        hsv.addView(contentBox);
        sv.addView(hsv);
        sv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(sv);

        return root;
    }

    private void loadDiff() {
        headerInfo.setText(getString(R.string.git_diff_loading));
        // Розбиття на рядки лишається у фоні разом зі сканом — на великому репозиторії
        // сам split коштує помітно більше за нуль.
        doBackground(() -> {
            String diff = GitManager.diffWorkingTree(projectDir);
            return diff.isEmpty() ? new String[0] : diff.split("\n");
        }, lines -> {
            if (lines.length == 0) {
                headerInfo.setText(getString(R.string.git_diff_no_changes));
            } else {
                renderDiff(lines);
            }
        }, e -> headerInfo.setText(getString(R.string.git_diff_error)));
    }

    private void renderDiff(String diff) {
        renderDiff(diff.split("\n"));
    }

    private void renderDiff(String[] lines) {
        contentBox.removeAllViews();

        int added = 0, removed = 0, files = 0;

        StringBuilder fileHeader = new StringBuilder();
        for (String line : lines) {
            if (line.startsWith("diff --git")) {
                files++;
                String path = line.substring(line.indexOf("b/") + 2);
                fileHeader.append(path).append("\n");
            } else if (line.startsWith("+") && !line.startsWith("+++")) {
                added++;
            } else if (line.startsWith("-") && !line.startsWith("---")) {
                removed++;
            }
        }

        String info = fileHeader.toString().trim();
        if (filePath != null) info = filePath;
        headerInfo.setText(info);
        headerStats.setText(String.format("+%d -%d  %d files", added, removed, files));

        renderIndex = 0;
        renderOldLine = 0;
        renderNewLine = 0;
        renderToken++;
        renderRows(lines, FIRST_ROWS, renderToken);
    }

    /**
     * Викладає рядки diff пачками: кожен рядок — це п'ять View, тож увесь файл
     * за один прохід затримав би перший кадр на секунду й більше.
     */
    private void renderRows(String[] lines, int limit, int token) {
        if (token != renderToken || isFinishing() || isDestroyed()) return;

        int i = renderIndex;
        int oldLine = renderOldLine, newLine = renderNewLine;
        int end = Math.min(lines.length, i + limit);

        for (; i < end; i++) {
            String line = lines[i];
            if (line.startsWith("diff --git")) {
                // File header
                String path = line.substring(line.indexOf("b/") + 2);
                contentBox.addView(makeFileHeader(path));
            } else if (line.startsWith("@@")) {
                // Hunk header
                contentBox.addView(makeHunkHeader(line));
                oldLine = parseOldStart(line);
                newLine = parseNewStart(line);
            } else if (line.startsWith("+")) {
                contentBox.addView(makeDiffLine("+", line, COLOR_ADDED_BG, COLOR_ADDED_TEXT, oldLine, newLine));
                newLine++;
            } else if (line.startsWith("-")) {
                contentBox.addView(makeDiffLine("-", line, COLOR_REMOVED_BG, COLOR_REMOVED_TEXT, oldLine, newLine));
                oldLine++;
            } else if (line.startsWith(" ")) {
                contentBox.addView(makeDiffLine(" ", line, 0, COLOR_CONTEXT, oldLine, newLine));
                oldLine++;
                newLine++;
            } else if (line.startsWith("new file") || line.startsWith("deleted file") ||
                       line.startsWith("rename") || line.startsWith("similarity")) {
                contentBox.addView(makeMetaLine(line));
            }
        }

        renderIndex = i;
        renderOldLine = oldLine;
        renderNewLine = newLine;

        if (i < lines.length) ui.post(() -> renderRows(lines, NEXT_ROWS, token));
    }

    private int parseOldStart(String line) {
        try {
            int start = line.indexOf("-") + 1;
            int comma = line.indexOf(",", start);
            if (comma < 0) comma = line.indexOf(" ", start);
            return Integer.parseInt(line.substring(start, comma));
        } catch (Exception e) { return 0; }
    }

    private int parseNewStart(String line) {
        try {
            int start = line.indexOf("+") + 1;
            int comma = line.indexOf(",", start);
            if (comma < 0) comma = line.indexOf(" ", start);
            return Integer.parseInt(line.substring(start, comma));
        } catch (Exception e) { return 0; }
    }

    private View makeFileHeader(String path) {
        TextView t = new TextView(this);
        t.setText("📄 " + path);
        t.setTextColor(theme.accent);
        t.setTextSize(13);
        t.setTypeface(mono, Typeface.BOLD);
        t.setPadding(dp(8), dp(8), dp(8), dp(4));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        t.setLayoutParams(lp);
        return t;
    }

    private View makeHunkHeader(String line) {
        TextView t = new TextView(this);
        t.setText(line);
        t.setTextColor(COLOR_HEADER_TEXT);
        t.setTextSize(11);
        t.setTypeface(mono);
        t.setBackgroundColor(COLOR_HEADER_BG);
        t.setPadding(dp(4), dp(2), dp(4), dp(2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(2);
        t.setLayoutParams(lp);
        return t;
    }

    private View makeDiffLine(String prefix, String line, int bgColor, int textColor,
                               int oldNum, int newNum) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        if (bgColor != 0) row.setBackgroundColor(bgColor);

        // Line numbers
        TextView numOld = new TextView(this);
        numOld.setText(String.format("%4d", oldNum));
        numOld.setTextColor(COLOR_LINE_NUM);
        numOld.setTextSize(10);
        numOld.setTypeface(mono);
        numOld.setPadding(dp(4), 0, dp(4), 0);
        numOld.setMinWidth(dp(36));
        numOld.setGravity(Gravity.END);
        row.addView(numOld);

        TextView numNew = new TextView(this);
        numNew.setText(String.format("%4d", newNum));
        numNew.setTextColor(COLOR_LINE_NUM);
        numNew.setTextSize(10);
        numNew.setTypeface(mono);
        numNew.setPadding(dp(4), 0, dp(4), 0);
        numNew.setMinWidth(dp(36));
        numNew.setGravity(Gravity.END);
        row.addView(numNew);

        // Prefix (+/-/space)
        TextView pfx = new TextView(this);
        pfx.setText(prefix);
        pfx.setTextColor(textColor);
        pfx.setTextSize(11);
        pfx.setTypeface(mono, Typeface.BOLD);
        pfx.setPadding(dp(4), 0, dp(2), 0);
        pfx.setMinWidth(dp(14));
        row.addView(pfx);

        // Content
        TextView content = new TextView(this);
        String text = line.length() > 1 ? line.substring(1) : "";
        content.setText(text);
        content.setTextColor(textColor);
        content.setTextSize(11);
        content.setTypeface(mono);
        content.setPadding(dp(2), 0, dp(8), 0);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        content.setLayoutParams(clp);
        row.addView(content);

        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        row.setLayoutParams(rlp);
        return row;
    }

    private View makeMetaLine(String line) {
        TextView t = new TextView(this);
        t.setText(line);
        t.setTextColor(theme.textDim);
        t.setTextSize(10);
        t.setTypeface(mono);
        t.setPadding(dp(8), dp(2), dp(8), dp(2));
        return t;
    }

    // ── Helpers ───────────────────────────────────────────

    private interface IoTask<T> { T run() throws Exception; }
    private interface OnSuccess<T> { void onResult(T result); }
    private interface OnError { void onError(Throwable t); }

    private <T> void doBackground(IoTask<T> task, OnSuccess<T> onSuccess, OnError onError) {
        io.execute(() -> {
            try {
                final T result = task.run();
                ui.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    try { onSuccess.onResult(result); } catch (Throwable ignored) {}
                });
            } catch (Throwable t) {
                ui.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    onError.onError(t);
                });
            }
        });
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    // ── Launch ────────────────────────────────────────────

    public static void launch(Context ctx, File projectDir) {
        Intent i = new Intent(ctx, GitDiffActivity.class);
        i.putExtra(EXTRA_PROJECT_DIR, projectDir.getAbsolutePath());
        i.putExtra(EXTRA_TITLE, "Diff — " + projectDir.getName());
        ctx.startActivity(i);
    }

    public static void launchFile(Context ctx, File projectDir, String filePath) {
        Intent i = new Intent(ctx, GitDiffActivity.class);
        i.putExtra(EXTRA_PROJECT_DIR, projectDir.getAbsolutePath());
        i.putExtra(EXTRA_FILE_PATH, filePath);
        i.putExtra(EXTRA_TITLE, "Diff — " + filePath);
        ctx.startActivity(i);
    }

    public static void launchText(Context ctx, String diffText, String title) {
        Intent i = new Intent(ctx, GitDiffActivity.class);
        i.putExtra(EXTRA_DIFF_TEXT, diffText);
        i.putExtra(EXTRA_TITLE, title != null ? title : "Diff");
        ctx.startActivity(i);
    }
}
