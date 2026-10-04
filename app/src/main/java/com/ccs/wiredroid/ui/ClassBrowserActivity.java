package com.ccs.wiredroid.ui;

import com.ccs.wiredroid.util.Colors;
import com.ccs.wiredroid.R;
import com.ccs.wiredroid.util.AppPreferences;
import com.ccs.wiredroid.util.AppTheme;
import com.ccs.wiredroid.util.FullScreenHelper;
import com.ccs.wiredroid.maven.MavenPaths;
import com.ccs.wiredroid.tools.bytecode.ClassDecompiler;
import com.ccs.wiredroid.tools.bytecode.BytecodeEditorActivity;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.Filter;
import android.widget.Filterable;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Browser for built-in JDK classes (android.jar) and Maven dependency classes.
 * Allows searching, viewing bytecode, and inserting import statements.
 */
public class ClassBrowserActivity extends AppCompatActivity {

    public static final String EXTRA_PROJECT_PATH = "project_path";
    public static final String EXTRA_INSERT_IMPORT = "insert_import";
    public static final String RESULT_FILE_PATH = "file_path";
    public static final String RESULT_CLASS_NAME = "class_name";

    private AppPreferences prefs;
    private AppTheme theme;
    private File projectRoot;

    private EditText etSearch;
    private TextView tvStatus;
    private RecyclerView rvResults;
    private LinearLayout placeholderLayout;
    private ClassListAdapter adapter;
    private Handler uiHandler;

    private final List<ClassItem> allClasses = new ArrayList<>();
    private boolean loading = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        prefs = new AppPreferences(this);
        theme = AppTheme.byId(prefs.getThemeId(), prefs);
        setTheme(theme.dark ? R.style.Theme_WireDroid : R.style.Theme_WireDroid_Light);

        super.onCreate(savedInstanceState);
        uiHandler = new Handler(Looper.getMainLooper());

        String path = getIntent().getStringExtra(EXTRA_PROJECT_PATH);
        if (path != null) {
            projectRoot = new File(path);
        }

        setContentView(buildRootLayout());
        FullScreenHelper.enable(this);
        showEmptyState(false);

        loadClassesAsync();
    }

    private View buildRootLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(theme.bg);

        // Toolbar
        Toolbar toolbar = new Toolbar(this);
        toolbar.setBackgroundColor(theme.toolbar);
        toolbar.setTitle(R.string.class_browser_title);
        toolbar.setTitleTextColor(theme.text);
        toolbar.setNavigationIcon(R.drawable.ic_back);
        Drawable nav = toolbar.getNavigationIcon();
        if (nav != null) nav.setColorFilter(theme.text, PorterDuff.Mode.SRC_IN);
        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        root.addView(toolbar);

        // Search Bar
        LinearLayout searchBar = new LinearLayout(this);
        searchBar.setOrientation(LinearLayout.HORIZONTAL);
        searchBar.setGravity(Gravity.CENTER_VERTICAL);
        searchBar.setPadding(dp(16), dp(16), dp(16), dp(8));

        etSearch = new EditText(this);
        etSearch.setHint(R.string.class_browser_hint);
        etSearch.setHintTextColor(theme.textDim);
        etSearch.setTextColor(theme.text);
        etSearch.setInputType(InputType.TYPE_CLASS_TEXT);
        etSearch.setSingleLine(true);
        etSearch.setTextSize(14);
        etSearch.setPadding(dp(12), dp(10), dp(12), dp(10));
        etSearch.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        etSearch.setContentDescription(getString(R.string.a11y_class_browser_search));

        try {
            Drawable searchIcon = getResources().getDrawable(R.drawable.ic_search).mutate();
            searchIcon.setColorFilter(theme.textDim, PorterDuff.Mode.SRC_IN);
            etSearch.setCompoundDrawablesWithIntrinsicBounds(searchIcon, null, null, null);
            etSearch.setCompoundDrawablePadding(dp(8));
        } catch (Exception ignored) {}

        GradientDrawable editBg = new GradientDrawable();
        editBg.setColor(Colors.blend(theme.toolbar, theme.bg, 0.4f));
        editBg.setCornerRadius(dp(20));
        editBg.setStroke(dp(1), theme.separator);
        etSearch.setBackground(editBg);

        LinearLayout.LayoutParams editLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        editLp.rightMargin = dp(10);
        etSearch.setLayoutParams(editLp);
        searchBar.addView(etSearch);

        TextView btnClear = new TextView(this);
        btnClear.setText(R.string.class_browser_clear);
        btnClear.setTextColor(theme.text);
        btnClear.setTextSize(13);
        btnClear.setTypeface(Typeface.DEFAULT_BOLD);
        btnClear.setGravity(Gravity.CENTER);
        btnClear.setPadding(dp(16), dp(10), dp(16), dp(10));
        btnClear.setContentDescription(getString(R.string.a11y_class_browser_clear));

        GradientDrawable clearBg = new GradientDrawable();
        clearBg.setColor(theme.toolbar);
        clearBg.setCornerRadius(dp(20));
        clearBg.setStroke(dp(1), theme.separator);
        btnClear.setBackground(clearBg);
        btnClear.setOnClickListener(v -> etSearch.setText(""));
        searchBar.addView(btnClear);

        root.addView(searchBar);

        // Status
        tvStatus = new TextView(this);
        tvStatus.setTextColor(theme.textDim);
        tvStatus.setTextSize(13);
        tvStatus.setGravity(Gravity.CENTER);
        tvStatus.setPadding(dp(16), dp(8), dp(16), dp(8));
        tvStatus.setVisibility(View.GONE);
        root.addView(tvStatus);

        // Content
        FrameLayout contentArea = new FrameLayout(this);
        contentArea.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        rvResults = new RecyclerView(this);
        rvResults.setLayoutManager(new LinearLayoutManager(this));
        rvResults.setPadding(0, dp(4), 0, dp(12));
        rvResults.setClipToPadding(false);

        adapter = new ClassListAdapter();
        rvResults.setAdapter(adapter);
        contentArea.addView(rvResults);

        contentArea.addView(buildPlaceholderLayout());
        root.addView(contentArea);

        // Text change → filter
        etSearch.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                adapter.getFilter().filter(s);
            }
            @Override public void afterTextChanged(android.text.Editable s) {}
        });

        // Keyboard search action
        etSearch.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) imm.hideSoftInputFromWindow(etSearch.getWindowToken(), 0);
                adapter.getFilter().filter(etSearch.getText());
                return true;
            }
            return false;
        });

        return root;
    }

    private View buildPlaceholderLayout() {
        placeholderLayout = new LinearLayout(this);
        placeholderLayout.setOrientation(LinearLayout.VERTICAL);
        placeholderLayout.setGravity(Gravity.CENTER);
        placeholderLayout.setPadding(dp(32), dp(48), dp(32), dp(48));
        placeholderLayout.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView title = new TextView(this);
        title.setText(R.string.class_browser_loading);
        title.setTextColor(theme.textDim);
        title.setTextSize(15);
        title.setGravity(Gravity.CENTER);
        placeholderLayout.addView(title);

        return placeholderLayout;
    }

    private void showEmptyState(boolean noResults) {
        rvResults.setVisibility(View.GONE);
        placeholderLayout.setVisibility(View.VISIBLE);
        if (placeholderLayout.getChildCount() >= 1) {
            TextView title = (TextView) placeholderLayout.getChildAt(0);
            if (noResults) {
                title.setText(R.string.class_browser_no_results);
            } else if (loading) {
                title.setText(R.string.class_browser_loading);
            } else {
                title.setText(R.string.class_browser_hint);
            }
        }
    }

    private void showResultsState() {
        placeholderLayout.setVisibility(View.GONE);
        rvResults.setVisibility(View.VISIBLE);
    }

    private void loadClassesAsync() {
        loading = true;
        showEmptyState(false);
        tvStatus.setVisibility(View.VISIBLE);
        tvStatus.setText(R.string.class_browser_scanning);

        new Thread(() -> {
            List<ClassItem> classes = new ArrayList<>();

            // 1. Scan android.jar from cache
            try {
                File cacheDir = new File(getCacheDir(), "jars");
                cacheDir.mkdirs();
                File androidJar = new File(cacheDir, "android.jar");
                if (!androidJar.exists() || androidJar.length() == 0) {
                    try (InputStream is = getAssets().open("android.jar");
                         FileOutputStream fos = new FileOutputStream(androidJar)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = is.read(buf)) != -1) {
                            fos.write(buf, 0, n);
                        }
                    }
                }
                if (androidJar.exists() && androidJar.length() > 0) {
                    scanJar(androidJar, "android.jar", classes);
                }
            } catch (Exception e) {
                // android.jar not available — log to logcat for debugging
                android.util.Log.e("ClassBrowser", "Failed to scan android.jar", e);
            }

            // 2. Scan Maven dependency JARs
            if (projectRoot != null) {
                File repoDir = MavenPaths.localRepoDir(projectRoot);
                if (repoDir.exists()) {
                    scanJarDir(repoDir, classes);
                }
            }

            final int count = classes.size();
            Collections.sort(classes, (a, b) -> a.fqn.compareToIgnoreCase(b.fqn));

            uiHandler.post(() -> {
                loading = false;
                allClasses.clear();
                allClasses.addAll(classes);
                adapter.setItems(allClasses);
                tvStatus.setVisibility(View.GONE);
                etSearch.setHint(getString(R.string.class_browser_hint_count, allClasses.size()));
                if (allClasses.isEmpty()) {
                    showEmptyState(true);
                } else {
                    showResultsState();
                }
                android.util.Log.i("ClassBrowser", "Loaded " + count + " classes");
            });
        }, "class-browser-scan").start();
    }

    private void scanJarDir(File dir, List<ClassItem> out) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                scanJarDir(f, out);
            } else if (f.getName().endsWith(".jar")) {
                scanJar(f, f.getName(), out);
            }
        }
    }

    private void scanJar(File jarFile, String source, List<ClassItem> out) {
        try (JarFile jf = new JarFile(jarFile)) {
            Enumeration<JarEntry> entries = jf.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.endsWith(".class") && !name.endsWith("module-info.class")) {
                    String fqn = name.replace('/', '.').substring(0, name.length() - 6);
                    // skip internal/synthetic
                    if (fqn.contains("$")) continue;
                    out.add(new ClassItem(fqn, source));
                }
            }
        } catch (IOException ignored) {}
    }

    private void showClassDetail(ClassItem item) {
        File jarFile;
        if ("android.jar".equals(item.source)) {
            jarFile = new File(getCacheDir(), "jars/android.jar");
        } else {
            jarFile = findJarFile(item.source);
        }

        if (jarFile == null || !jarFile.exists()) {
            Toast.makeText(this, R.string.class_browser_jar_not_found, Toast.LENGTH_SHORT).show();
            return;
        }

        String entryName = item.fqn.replace('.', '/') + ".class";

        new Thread(() -> {
            try (JarFile jf = new JarFile(jarFile)) {
                JarEntry entry = jf.getJarEntry(entryName);
                if (entry == null) {
                    uiHandler.post(() -> Toast.makeText(this, R.string.class_browser_class_not_found, Toast.LENGTH_SHORT).show());
                    return;
                }

                try (InputStream is = jf.getInputStream(entry)) {
                    byte[] bytes = readAllBytes(is);

                    // Generate decompiled source with JavaDoc
                    String decompiled = ClassDecompiler.decompile(bytes);

                    // Write to cache file
                    File outDir = new File(getCacheDir(), "decompiled");
                    outDir.mkdirs();
                    String fileName = getSimpleName(item.fqn) + ".java";
                    File outFile = new File(outDir, fileName);
                    try (FileOutputStream fos = new FileOutputStream(outFile)) {
                        fos.write(decompiled.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    }

                    // Return result to MainActivity
                    Intent result = new Intent();
                    result.putExtra(RESULT_FILE_PATH, outFile.getAbsolutePath());
                    result.putExtra(RESULT_CLASS_NAME, item.fqn);
                    setResult(RESULT_OK, result);
                    uiHandler.post(this::finish);
                }
            } catch (Exception e) {
                uiHandler.post(() -> Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }, "class-decompile").start();
    }

    private void openBytecode(ClassItem item, File jarFile) {
        String entryName = item.fqn.replace('.', '/') + ".class";

        new Thread(() -> {
            try (JarFile jf = new JarFile(jarFile)) {
                JarEntry entry = jf.getJarEntry(entryName);
                if (entry == null) return;

                try (InputStream is = jf.getInputStream(entry)) {
                    byte[] bytes = readAllBytes(is);

                    File outDir = new File(getCacheDir(), "bytecode");
                    outDir.mkdirs();
                    File classFile = new File(outDir, getSimpleName(item.fqn) + ".class");
                    try (FileOutputStream fos = new FileOutputStream(classFile)) {
                        fos.write(bytes);
                    }

                    uiHandler.post(() -> BytecodeEditorActivity.launch(this, classFile.getAbsolutePath()));
                }
            } catch (Exception e) {
                uiHandler.post(() -> Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }, "bytecode-open").start();
    }

    private File findJarFile(String name) {
        if (projectRoot == null) return null;
        File repoDir = MavenPaths.localRepoDir(projectRoot);
        return findJarRecursive(repoDir, name);
    }

    private File findJarRecursive(File dir, String name) {
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File f : files) {
            if (f.isDirectory()) {
                File found = findJarRecursive(f, name);
                if (found != null) return found;
            } else if (f.getName().equals(name)) {
                return f;
            }
        }
        return null;
    }

    private static byte[] readAllBytes(InputStream is) throws IOException {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        byte[] tmp = new byte[8192];
        int n;
        while ((n = is.read(tmp)) != -1) {
            buf.write(tmp, 0, n);
        }
        return buf.toByteArray();
    }

    private static String getPackage(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot > 0 ? fqn.substring(0, dot) : "";
    }

    private static String getSimpleName(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot >= 0 ? fqn.substring(dot + 1) : fqn;
    }

    private static String accessStr(int access) {
        StringBuilder sb = new StringBuilder();
        if ((access & org.objectweb.asm.Opcodes.ACC_PUBLIC) != 0) sb.append("public ");
        if ((access & org.objectweb.asm.Opcodes.ACC_PROTECTED) != 0) sb.append("protected ");
        if ((access & org.objectweb.asm.Opcodes.ACC_PRIVATE) != 0) sb.append("private ");
        if ((access & org.objectweb.asm.Opcodes.ACC_STATIC) != 0) sb.append("static ");
        if ((access & org.objectweb.asm.Opcodes.ACC_FINAL) != 0) sb.append("final ");
        if ((access & org.objectweb.asm.Opcodes.ACC_ABSTRACT) != 0) sb.append("abstract ");
        if ((access & org.objectweb.asm.Opcodes.ACC_INTERFACE) != 0) sb.append("interface ");
        if ((access & org.objectweb.asm.Opcodes.ACC_ENUM) != 0) sb.append("enum ");
        return sb.toString();
    }

    private static String formatDesc(String desc) {
        if (desc == null) return "";
        int idx = desc.indexOf('(');
        if (idx < 0) return " " + desc;
        return desc.substring(idx);
    }


    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    public static void launch(Activity host, String projectPath) {
        Intent intent = new Intent(host, ClassBrowserActivity.class);
        intent.putExtra(EXTRA_PROJECT_PATH, projectPath);
        host.startActivityForResult(intent, 9001);
    }

    private static class FrameLayout extends android.widget.FrameLayout {
        public FrameLayout(Context context) { super(context); }
    }

    // ── Data model ──
    static class ClassItem {
        final String fqn;
        final String source;

        ClassItem(String fqn, String source) {
            this.fqn = fqn;
            this.source = source;
        }
    }

    // ── Adapter ──
    private class ClassListAdapter extends RecyclerView.Adapter<ClassListAdapter.VH> implements Filterable {
        private List<ClassItem> items = new ArrayList<>();
        private List<ClassItem> filtered = new ArrayList<>();

        void setItems(List<ClassItem> data) {
            items = new ArrayList<>(data);
            filtered = new ArrayList<>(data);
            notifyDataSetChanged();
        }

        @Override
        public int getItemCount() {
            return filtered.size();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout row = new LinearLayout(parent.getContext());
            row.setOrientation(LinearLayout.VERTICAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), dp(10), dp(16), dp(10));

            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(dp(12), dp(2), dp(12), dp(2));
            row.setLayoutParams(lp);

            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Colors.blend(theme.toolbar, theme.bg, 0.5f));
            bg.setCornerRadius(dp(6));
            row.setBackground(bg);

            TypedValue tv = new TypedValue();
            parent.getContext().getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
            if (tv.resourceId != 0 && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                row.setForeground(parent.getContext().getResources().getDrawable(tv.resourceId, parent.getContext().getTheme()));
            }

            TextView className = new TextView(parent.getContext());
            className.setTextSize(14);
            className.setTypeface(new AppPreferences(className.getContext()).resolveTypeface());
            className.setTextColor(theme.text);
            row.addView(className);

            TextView sourceLabel = new TextView(parent.getContext());
            sourceLabel.setTextSize(10);
            sourceLabel.setTextColor(theme.textDim);
            LinearLayout.LayoutParams srcLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            srcLp.topMargin = dp(2);
            sourceLabel.setLayoutParams(srcLp);
            row.addView(sourceLabel);

            return new VH(row, className, sourceLabel);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            ClassItem item = filtered.get(position);
            String simpleName = getSimpleName(item.fqn);
            String pkg = getPackage(item.fqn);

            SpannableString span = new SpannableString(item.fqn);
            if (!pkg.isEmpty()) {
                int pkgEnd = pkg.length();
                span.setSpan(new ForegroundColorSpan(theme.textDim), 0, pkgEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                span.setSpan(new ForegroundColorSpan(theme.accent), pkgEnd + 1, pkgEnd + 1 + simpleName.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }

            holder.tvClassName.setText(span);
            holder.tvSource.setText(item.source);
            holder.itemView.setContentDescription(getString(R.string.a11y_class_item, item.fqn));
            holder.itemView.setOnClickListener(v -> showClassDetail(item));
        }

        @NonNull
        @Override
        public Filter getFilter() {
            return new Filter() {
                @Override
                protected FilterResults performFiltering(CharSequence constraint) {
                    FilterResults results = new FilterResults();
                    if (constraint == null || constraint.length() == 0) {
                        results.values = new ArrayList<>(items);
                        results.count = items.size();
                    } else {
                        String query = constraint.toString().toLowerCase(Locale.ROOT);
                        List<ClassItem> filtered = new ArrayList<>();
                        for (ClassItem item : items) {
                            if (item.fqn.toLowerCase(Locale.ROOT).contains(query)) {
                                filtered.add(item);
                            }
                        }
                        results.values = filtered;
                        results.count = filtered.size();
                    }
                    return results;
                }

                @Override
                @SuppressWarnings("unchecked")
                protected void publishResults(CharSequence constraint, FilterResults results) {
                    filtered = (List<ClassItem>) results.values;
                    notifyDataSetChanged();
                    if (filtered.isEmpty()) {
                        showEmptyState(true);
                    } else {
                        showResultsState();
                    }
                    tvStatus.setVisibility(View.GONE);
                    tvStatus.setText(getString(R.string.class_browser_found, filtered.size()));
                }
            };
        }

        class VH extends RecyclerView.ViewHolder {
            final TextView tvClassName;
            final TextView tvSource;

            VH(View itemView, TextView className, TextView source) {
                super(itemView);
                tvClassName = className;
                tvSource = source;
            }
        }
    }
}
