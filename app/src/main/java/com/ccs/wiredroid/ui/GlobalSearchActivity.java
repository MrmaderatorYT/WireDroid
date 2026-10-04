package com.ccs.wiredroid.ui;

import com.ccs.wiredroid.R;
import com.ccs.wiredroid.util.FullScreenHelper;
import com.ccs.wiredroid.util.AppPreferences;
import com.ccs.wiredroid.util.AppTheme;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Пошук по всьому проєкту: шукає в усіх текстових файлах, показує результати
 * з назвою файлу, номером рядка та контекстом збігу.
 */
public class GlobalSearchActivity extends AppCompatActivity {

    private static final String EXTRA_PROJECT_ROOT = "project_root";
    private static final String EXTRA_QUERY = "query";

    private EditText etSearch;
    private RecyclerView resultsRecycler;
    private ProgressBar progressBar;
    private TextView statusText;
    private ResultsAdapter adapter;
    private File projectRoot;

    private int accentColor = 0xFF4A86C8;
    private int dimColor = 0xFF808080;
    private int textColor = 0xFFBBBBBB;
    private int bgColor = 0xFF2B2B2B;
    private int toolbarColor = 0xFF3C3F41;

    private AppTheme theme;

    public static void launch(Context context, File projectRoot) {
        Intent i = new Intent(context, GlobalSearchActivity.class);
        i.putExtra(EXTRA_PROJECT_ROOT, projectRoot.getAbsolutePath());
        context.startActivity(i);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        AppPreferences prefs = new AppPreferences(this);
        theme = AppTheme.byId(prefs.getThemeId(), prefs);
        setTheme(theme.dark ? R.style.Theme_WireDroid : R.style.Theme_WireDroid_Light);
        super.onCreate(savedInstanceState);

        String rootPath = getIntent().getStringExtra(EXTRA_PROJECT_ROOT);
        if (rootPath == null) { finish(); return; }
        projectRoot = new File(rootPath);
        if (!projectRoot.isDirectory()) { finish(); return; }

        applyColors();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bgColor);

        // Toolbar
        Toolbar toolbar = new Toolbar(this);
        toolbar.setBackgroundColor(toolbarColor);
        toolbar.setTitle(R.string.find_scope_project);
        toolbar.setTitleTextColor(textColor);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        // Search bar
        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setBackgroundColor(toolbarColor);
        searchRow.setPadding(dp(8), dp(4), dp(8), dp(4));
        searchRow.setGravity(Gravity.CENTER_VERTICAL);

        etSearch = new EditText(this);
        etSearch.setHint(R.string.find_scope_hint);
        etSearch.setHintTextColor(dimColor);
        etSearch.setTextColor(textColor);
        android.graphics.drawable.GradientDrawable editBg = new android.graphics.drawable.GradientDrawable();
        editBg.setColor(com.ccs.wiredroid.util.Colors.blend(theme.consoleBg, theme.bg, 0.4f));
        editBg.setCornerRadius(dp(6));
        editBg.setStroke(dp(1), theme.separator);
        etSearch.setBackground(editBg);
        etSearch.setTextSize(14);
        etSearch.setSingleLine(true);
        etSearch.setPadding(dp(12), dp(8), dp(12), dp(8));
        etSearch.setContentDescription(getString(R.string.a11y_global_search_field));
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        etSearch.setLayoutParams(searchLp);

        TextView btnSearch = new TextView(this);
        btnSearch.setText(R.string.lib_search);
        btnSearch.setTextColor(accentColor);
        btnSearch.setTextSize(14);
        btnSearch.setPadding(dp(16), dp(8), dp(16), dp(8));
        btnSearch.setGravity(Gravity.CENTER);
        btnSearch.setContentDescription(getString(R.string.a11y_global_search_button));
        android.graphics.drawable.GradientDrawable btnBg = new android.graphics.drawable.GradientDrawable();
        btnBg.setColor(com.ccs.wiredroid.util.Colors.blend(theme.toolbar, theme.bg, 0.3f));
        btnBg.setCornerRadius(dp(6));
        btnSearch.setBackground(btnBg);
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnLp.leftMargin = dp(8);
        btnSearch.setLayoutParams(btnLp);

        searchRow.addView(etSearch);
        searchRow.addView(btnSearch);

        // Status
        statusText = new TextView(this);
        statusText.setPadding(dp(12), dp(4), dp(12), dp(4));
        statusText.setTextSize(11);
        statusText.setTextColor(dimColor);
        statusText.setText("Type a query and tap Search");

        // Progress
        progressBar = new ProgressBar(this);
        progressBar.setIndeterminate(true);
        progressBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        progressBar.setLayoutParams(progressLp);

        // Results
        resultsRecycler = new RecyclerView(this);
        resultsRecycler.setBackgroundColor(bgColor);

        adapter = new ResultsAdapter();
        resultsRecycler.setLayoutManager(new LinearLayoutManager(this));
        resultsRecycler.setAdapter(adapter);

        root.addView(toolbar);
        root.addView(searchRow);
        root.addView(statusText);
        root.addView(progressBar);
        root.addView(resultsRecycler, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
        FullScreenHelper.enable(this);
        String initialQuery = getIntent().getStringExtra(EXTRA_QUERY);
        if (initialQuery != null && !initialQuery.isEmpty()) {
            etSearch.setText(initialQuery);
            performSearch(initialQuery);
        }

        btnSearch.setOnClickListener(v -> {
            String q = etSearch.getText().toString().trim();
            if (!q.isEmpty()) performSearch(q);
        });

        etSearch.setOnEditorActionListener((v, actionId, event) -> {
            String q = etSearch.getText().toString().trim();
            if (!q.isEmpty()) performSearch(q);
            return true;
        });
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    private void performSearch(String query) {
        progressBar.setVisibility(View.VISIBLE);
        statusText.setText("Searching...");
        resultsRecycler.setVisibility(View.GONE);

        new Thread(() -> {
            List<SearchResult> results = searchProject(projectRoot, query);
            runOnUiThread(() -> {
                progressBar.setVisibility(View.GONE);
                adapter.setResults(results);
                resultsRecycler.setVisibility(View.VISIBLE);
                statusText.setText(results.size() + " result(s) in "
                        + countUniqueFiles(results) + " file(s)");
            });
        }, "global-search").start();
    }

    private List<SearchResult> searchProject(File dir, String query) {
        List<SearchResult> results = new ArrayList<>();
        String queryLower = query.toLowerCase(Locale.ROOT);
        searchRecursive(dir, query, queryLower, results, 500);
        return results;
    }

    private void searchRecursive(File dir, String query, String queryLower,
                                  List<SearchResult> results, int limit) {
        if (results.size() >= limit) return;
        File[] children = dir.listFiles();
        if (children == null) return;

        for (File f : children) {
            if (results.size() >= limit) return;
            if (f.isDirectory()) {
                String name = f.getName();
                if (name.equals(".git") || name.equals("build") || name.equals(".gradle")
                        || name.equals(".idea") || name.equals("target")) {
                    continue;
                }
                searchRecursive(f, query, queryLower, results, limit);
            } else if (isSearchableFile(f)) {
                searchInFile(f, query, queryLower, results, limit);
            }
        }
    }

    private void searchInFile(File file, String query, String queryLower,
                               List<SearchResult> results, int limit) {
        try {
            byte[] bytes = Files.readAllBytes(file.toPath());
            if (bytes.length > 1024 * 1024) return;
            String content = new String(bytes, StandardCharsets.UTF_8);
            String contentLower = content.toLowerCase(Locale.ROOT);

            int lineNum = 1;
            int searchFrom = 0;
            while (results.size() < limit) {
                int idx = contentLower.indexOf(queryLower, searchFrom);
                if (idx == -1) break;

                int lineStart = content.lastIndexOf('\n', idx - 1) + 1;
                int lineEnd = content.indexOf('\n', idx);
                if (lineEnd == -1) lineEnd = content.length();
                String line = content.substring(lineStart, lineEnd).trim();
                if (line.isEmpty()) line = content.substring(lineStart, Math.min(lineStart + 100, content.length())).trim();

                results.add(new SearchResult(file, lineNum, idx, line, query));

                searchFrom = idx + query.length();
                lineNum = countNewlines(content, lineStart, searchFrom);
            }
        } catch (IOException ignored) {
        }
    }

    private int countNewlines(String text, int from, int to) {
        int count = 1;
        for (int i = from; i < to && i < text.length(); i++) {
            if (text.charAt(i) == '\n') count++;
        }
        return count;
    }

    private boolean isSearchableFile(File f) {
        String name = f.getName().toLowerCase(Locale.ROOT);
        return name.endsWith(".java") || name.endsWith(".kt") || name.endsWith(".xml")
                || name.endsWith(".json") || name.endsWith(".gradle") || name.endsWith(".md")
                || name.endsWith(".txt") || name.endsWith(".c") || name.endsWith(".cpp")
                || name.endsWith(".h") || name.endsWith(".hpp") || name.endsWith(".py")
                || name.endsWith(".sh") || name.endsWith(".html") || name.endsWith(".css")
                || name.endsWith(".js") || name.endsWith(".properties")
                || name.endsWith(".yaml") || name.endsWith(".yml")
                || com.ccs.wiredroid.util.languages.LanguageFiles.isKnown(name);
    }

    private int countUniqueFiles(List<SearchResult> results) {
        java.util.Set<String> files = new java.util.HashSet<>();
        for (SearchResult r : results) {
            files.add(r.file.getAbsolutePath());
        }
        return files.size();
    }

    private void applyColors() {
        try {
            AppPreferences prefs = new AppPreferences(this);
            AppTheme theme = AppTheme.byId(prefs.getThemeId(), prefs);
            accentColor = theme.accent;
            dimColor = theme.textDim;
            textColor = theme.text;
            bgColor = theme.bg;
            toolbarColor = theme.toolbar;
        } catch (Throwable ignored) {}
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    static class SearchResult {
        final File file;
        final int lineNumber;
        final int offset;
        final String lineText;
        final String query;

        SearchResult(File file, int lineNumber, int offset, String lineText, String query) {
            this.file = file;
            this.lineNumber = lineNumber;
            this.offset = offset;
            this.lineText = lineText;
            this.query = query;
        }
    }

    class ResultsAdapter extends RecyclerView.Adapter<ResultsAdapter.VH> {
        private List<SearchResult> results = new ArrayList<>();

        void setResults(List<SearchResult> list) {
            this.results = list != null ? list : new ArrayList<>();
            notifyDataSetChanged();
        }

        @Override
        public int getItemCount() { return results.size(); }

        @Override
        public VH onCreateViewHolder(ViewGroup parent, int viewType) {
            TextView tv = new TextView(GlobalSearchActivity.this);
            tv.setTextColor(theme != null ? theme.text : textColor);
            tv.setTextSize(12);
            tv.setTypeface(new AppPreferences(GlobalSearchActivity.this).resolveTypeface());
            tv.setPadding(dp(12), dp(8), dp(12), dp(8));
            tv.setBackgroundResource(android.R.drawable.list_selector_background);
            tv.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new VH(tv);
        }

        @Override
        public void onBindViewHolder(VH h, int position) {
            SearchResult r = results.get(position);
            String relPath = relativize(projectRoot, r.file);
            String header = relPath + ":" + r.lineNumber;

            SpannableStringBuilder ssb = new SpannableStringBuilder(header + "\n");
            ssb.setSpan(new StyleSpan(Typeface.BOLD), 0, header.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            ssb.setSpan(new ForegroundColorSpan(accentColor), 0, header.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

            int lineStart = header.length() + 1;
            String lineText = "  " + r.lineText;
            ssb.append(lineText);

            String queryLower = r.query.toLowerCase(Locale.ROOT);
            String lineLower = r.lineText.toLowerCase(Locale.ROOT);
            int matchIdx = lineLower.indexOf(queryLower);
            if (matchIdx >= 0) {
                int hlStart = lineStart + 2 + matchIdx;
                int hlEnd = hlStart + r.query.length();
                ssb.setSpan(new ForegroundColorSpan(accentColor), hlStart, hlEnd,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                ssb.setSpan(new StyleSpan(Typeface.BOLD), hlStart, hlEnd,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }

            h.tv.setText(ssb);
            h.tv.setContentDescription(getString(R.string.a11y_global_search_result,
                    relPath + ":" + r.lineNumber));

            h.tv.setOnClickListener(v -> {
                Intent data = new Intent();
                data.putExtra("file_path", r.file.getAbsolutePath());
                data.putExtra("line_number", r.lineNumber);
                setResult(RESULT_OK, data);
                finish();
            });
        }

        class VH extends RecyclerView.ViewHolder {
            final TextView tv;
            VH(TextView itemView) {
                super(itemView);
                tv = itemView;
            }
        }

        private String relativize(File root, File file) {
            try {
                return root.toPath().relativize(file.toPath()).toString();
            } catch (Throwable e) {
                return file.getName();
            }
        }
    }
}
