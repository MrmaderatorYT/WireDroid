package com.ccs.wiredroid.ui;

import com.ccs.wiredroid.util.Colors;
import com.ccs.wiredroid.R;
import com.ccs.wiredroid.util.AppPreferences;
import com.ccs.wiredroid.util.AppTheme;
import com.ccs.wiredroid.util.FullScreenHelper;
import com.ccs.wiredroid.maven.MavenPaths;
import com.ccs.wiredroid.maven.PomWriter;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.ccs.wiredroid.project.LocalLibraries;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public class LibraryManagerActivity extends AppCompatActivity {

    public static final String EXTRA_PROJECT_PATH = "project_path";

    private AppPreferences prefs;
    private AppTheme theme;
    private File projectRoot;

    private EditText etSearch;
    private TextView btnSearch;
    private TextView tvStatus;
    private FrameLayout contentArea;
    private RecyclerView rvResults;
    private LinearLayout placeholderLayout;
    private ResultsAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        prefs = new AppPreferences(this);
        theme = AppTheme.byId(prefs.getThemeId(), prefs);
        setTheme(theme.dark ? R.style.Theme_WireDroid : R.style.Theme_WireDroid_Light);

        super.onCreate(savedInstanceState);

        String path = getIntent().getStringExtra(EXTRA_PROJECT_PATH);
        if (path != null) {
            projectRoot = new File(path);
        }

        setContentView(buildRootLayout());
        FullScreenHelper.enable(this);        showEmptyState(false);
    }

    private View buildRootLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(theme.bg);

        // Toolbar
        Toolbar toolbar = new Toolbar(this);
        toolbar.setBackgroundColor(theme.toolbar);
        toolbar.setTitle(getString(R.string.lib_title));
        toolbar.setTitleTextColor(theme.text);
        toolbar.setNavigationIcon(R.drawable.ic_back);
        Drawable nav = toolbar.getNavigationIcon();
        if (nav != null) nav.setColorFilter(theme.text, PorterDuff.Mode.SRC_IN);
        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        root.addView(toolbar);

        // Search Bar container
        LinearLayout searchBar = new LinearLayout(this);
        searchBar.setOrientation(LinearLayout.HORIZONTAL);
        searchBar.setGravity(Gravity.CENTER_VERTICAL);
        searchBar.setPadding(dp(16), dp(16), dp(16), dp(8));
        searchBar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Search EditText
        etSearch = new EditText(this);
        etSearch.setHint(getString(R.string.lib_search_hint));
        etSearch.setHintTextColor(theme.textDim);
        etSearch.setTextColor(theme.text);
        etSearch.setInputType(InputType.TYPE_CLASS_TEXT);
        etSearch.setSingleLine(true);
        etSearch.setTextSize(14);
        etSearch.setPadding(dp(12), dp(10), dp(12), dp(10));
        
        try {
            Drawable searchIcon = getResources().getDrawable(R.drawable.ic_search).mutate();
            searchIcon.setColorFilter(theme.textDim, PorterDuff.Mode.SRC_IN);
            etSearch.setCompoundDrawablesWithIntrinsicBounds(searchIcon, null, null, null);
            etSearch.setCompoundDrawablePadding(dp(8));
        } catch (Exception ignored) {}

        GradientDrawable editBg = new GradientDrawable();
        editBg.setColor(Colors.blend(theme.toolbar, theme.bg, 0.4f));
        editBg.setCornerRadius(dp(20)); // rounded pill style input
        editBg.setStroke(dp(1), theme.separator);
        etSearch.setBackground(editBg);

        LinearLayout.LayoutParams editLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        editLp.rightMargin = dp(10);
        etSearch.setLayoutParams(editLp);
        searchBar.addView(etSearch);

        // Search Button
        btnSearch = new TextView(this);
        btnSearch.setText(getString(R.string.lib_search));
        btnSearch.setTextColor(theme.text);
        btnSearch.setTextSize(14);
        btnSearch.setTypeface(Typeface.DEFAULT_BOLD);
        btnSearch.setGravity(Gravity.CENTER);
        btnSearch.setPadding(dp(20), dp(10), dp(20), dp(10));
        
        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setColor(theme.accent);
        btnBg.setCornerRadius(dp(20)); // pill button
        btnSearch.setBackground(btnBg);
        
        btnSearch.setClickable(true);
        btnSearch.setFocusable(true);
        btnSearch.setOnClickListener(v -> performSearch());
        searchBar.addView(btnSearch);

        root.addView(searchBar);

        // ── Local archives ──
        // Not every dependency has coordinates: a jar handed over by hand had no
        // way in at all before this.
        TextView btnLocal = new TextView(this);
        btnLocal.setText(R.string.lib_add_local);
        btnLocal.setTextColor(theme.accent);
        btnLocal.setTextSize(13);
        btnLocal.setPadding(dp(4), dp(10), dp(4), dp(2));
        android.util.TypedValue ripple = new android.util.TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)) {
            btnLocal.setBackgroundResource(ripple.resourceId);
        }
        btnLocal.setOnClickListener(v -> pickLocalArchive());
        root.addView(btnLocal);

        TextView localNote = new TextView(this);
        localNote.setText(R.string.lib_add_local_note);
        localNote.setTextColor(theme.textDim);
        localNote.setTextSize(11);
        localNote.setPadding(dp(4), 0, dp(4), dp(8));
        root.addView(localNote);

        // Status Label (e.g. Loading)
        tvStatus = new TextView(this);
        tvStatus.setTextColor(theme.textDim);
        tvStatus.setTextSize(13);
        tvStatus.setGravity(Gravity.CENTER);
        tvStatus.setPadding(dp(16), dp(8), dp(16), dp(8));
        tvStatus.setVisibility(View.GONE);
        root.addView(tvStatus);

        // Content Area (RecyclerView or Placeholder)
        contentArea = new FrameLayout(this);
        contentArea.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // The result list only exists once a search returns something; until
        // then the placeholder is the whole content area.
        contentArea.addView(buildPlaceholderLayout());

        root.addView(contentArea);

        return root;
    }

    private View buildPlaceholderLayout() {
        placeholderLayout = new LinearLayout(this);
        placeholderLayout.setOrientation(LinearLayout.VERTICAL);
        placeholderLayout.setGravity(Gravity.CENTER);
        placeholderLayout.setPadding(dp(32), dp(48), dp(32), dp(48));
        placeholderLayout.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        ImageView iv = new ImageView(this);
        try {
            Drawable d = getResources().getDrawable(R.drawable.ic_search).mutate();
            d.setColorFilter(Colors.blend(theme.textDim, theme.bg, 0.6f), PorterDuff.Mode.SRC_IN);
            iv.setImageDrawable(d);
        } catch (Exception ignored) {}
        LinearLayout.LayoutParams ivLp = new LinearLayout.LayoutParams(dp(72), dp(72));
        ivLp.bottomMargin = dp(16);
        iv.setLayoutParams(ivLp);
        placeholderLayout.addView(iv);

        TextView title = new TextView(this);
        title.setText(getString(R.string.lib_title));
        title.setTextColor(theme.text);
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        placeholderLayout.addView(title);

        TextView sub = new TextView(this);
        sub.setText(getString(R.string.lib_search_hint));
        sub.setTextColor(theme.textDim);
        sub.setTextSize(13);
        sub.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(8);
        sub.setLayoutParams(subLp);
        placeholderLayout.addView(sub);

        return placeholderLayout;
    }

    /** Builds the result list on first use; see {@link #buildRootLayout()}. */
    private RecyclerView ensureResults() {
        if (rvResults == null) {
            rvResults = new RecyclerView(this);
            rvResults.setLayoutManager(new LinearLayoutManager(this));
            rvResults.setPadding(0, dp(4), 0, dp(12));
            rvResults.setClipToPadding(false);
            rvResults.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            adapter = new ResultsAdapter();
            rvResults.setAdapter(adapter);
            contentArea.addView(rvResults, 0);
        }
        return rvResults;
    }

    private void showEmptyState(boolean noResults) {
        if (rvResults != null) rvResults.setVisibility(View.GONE);
        placeholderLayout.setVisibility(View.VISIBLE);
        
        if (placeholderLayout.getChildCount() >= 3) {
            TextView title = (TextView) placeholderLayout.getChildAt(1);
            TextView sub = (TextView) placeholderLayout.getChildAt(2);
            if (noResults) {
                title.setText(getString(R.string.lib_no_results));
                sub.setText(getString(R.string.lib_search_hint));
            } else {
                title.setText(getString(R.string.lib_title));
                sub.setText(getString(R.string.lib_search_hint));
            }
        }
    }

    private void showResultsState() {
        placeholderLayout.setVisibility(View.GONE);
        ensureResults().setVisibility(View.VISIBLE);
    }

    private static final int REQ_PICK_ARCHIVE = 4711;

    /**
     * Asks the system for a jar, war or aar.
     *
     * <p>The MIME filter is deliberately loose: providers disagree about what a
     * jar is — some say {@code application/java-archive}, some
     * {@code application/octet-stream}, some nothing at all — and a filter that
     * hides the file the user is looking at is worse than one that shows a few
     * extra. The extension is checked when the file arrives.</p>
     */
    private void pickLocalArchive() {
        if (projectRoot == null) {
            Toast.makeText(this, R.string.lib_local_no_project, Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/java-archive", "application/x-java-archive",
                "application/octet-stream", "application/zip", "*/*"});
        try {
            startActivityForResult(i, REQ_PICK_ARCHIVE);
        } catch (Exception e) {
            Toast.makeText(this, String.valueOf(e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_ARCHIVE || resultCode != RESULT_OK
                || data == null || data.getData() == null) {
            return;
        }
        final android.net.Uri uri = data.getData();
        tvStatus.setText(R.string.lib_local_importing);
        new Thread(() -> {
            LocalLibraries.Imported result =
                    LocalLibraries.importArchive(this, uri, projectRoot);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (result.error != null && result.isEmpty()) {
                    tvStatus.setText(getString(R.string.lib_local_failed, result.error));
                    tvStatus.setTextColor(theme.errorText);
                    return;
                }
                tvStatus.setTextColor(theme.successText);
                tvStatus.setText(getResources().getQuantityString(
                        R.plurals.lib_local_added, result.added.size(),
                        result.added.size(), String.join(", ", result.added)));
                registerWithBuildFile(result.added);
            });
        }, "lib-import").start();
    }

    /**
     * Records the jars in the build file where the build system has a way to say
     * it.
     *
     * <p>Gradle does: {@code implementation files('libs/x.jar')}. Maven has only
     * {@code <systemPath>}, which is deprecated and breaks {@code mvn install},
     * so a pom is left alone — the jar still compiles here because the on-device
     * classpath reads {@code libs/} directly, and that is said out loud rather
     * than left for the user to discover on another machine.</p>
     */
    private void registerWithBuildFile(java.util.List<String> jars) {
        if (jars.isEmpty()) return;
        File gradle = new File(projectRoot, "build.gradle");
        if (!gradle.isFile()) gradle = new File(projectRoot, "build.gradle.kts");
        if (!gradle.isFile()) {
            Toast.makeText(this, R.string.lib_local_maven_note, Toast.LENGTH_LONG).show();
            return;
        }
        try {
            java.util.List<String> paths = new java.util.ArrayList<>();
            for (String j : jars) paths.add(LocalLibraries.DIR_NAME + "/" + j);
            boolean written = com.ccs.wiredroid.gradle.GradleDependencyWriter.addFileDependencies(gradle, paths);
            if (written) {
                Toast.makeText(this, R.string.lib_local_gradle_written, Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, String.valueOf(e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    private void performSearch() {
        final String query = etSearch.getText().toString().trim();
        if (query.isEmpty()) return;

        btnSearch.setEnabled(false);
        tvStatus.setVisibility(View.VISIBLE);
        tvStatus.setText(getString(R.string.lib_searching));
        if (adapter != null) adapter.clear();

        new Thread(() -> {
            try {
                String encoded = URLEncoder.encode(query, "UTF-8");
                String urlStr = "https://search.maven.org/solrsearch/select?q=" + encoded + "&rows=30&wt=json";
                
                HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                conn.setRequestMethod("GET");
                
                int responseCode = conn.getResponseCode();
                if (responseCode == 200) {
                    InputStream in = conn.getInputStream();
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = in.read(buffer)) != -1) {
                        out.write(buffer, 0, len);
                    }
                    String json = out.toString("UTF-8");
                    conn.disconnect();

                    JSONObject rootObj = new JSONObject(json);
                    JSONObject responseObj = rootObj.optJSONObject("response");
                    final List<LibItem> items = new ArrayList<>();
                    if (responseObj != null) {
                        JSONArray docs = responseObj.optJSONArray("docs");
                        if (docs != null) {
                            for (int i = 0; i < docs.length(); i++) {
                                JSONObject doc = docs.getJSONObject(i);
                                LibItem item = new LibItem();
                                item.groupId = doc.optString("g");
                                item.artifactId = doc.optString("a");
                                item.version = doc.optString("latestVersion");
                                items.add(item);
                            }
                        }
                    }

                    runOnUiThread(() -> {
                        btnSearch.setEnabled(true);
                        tvStatus.setVisibility(View.GONE);
                        if (items.isEmpty()) {
                            showEmptyState(true);
                        } else {
                            showResultsState();
                            adapter.setItems(items);
                        }
                    });

                } else {
                    conn.disconnect();
                    throw new IOException("HTTP code " + responseCode);
                }

            } catch (Exception e) {
                runOnUiThread(() -> {
                    btnSearch.setEnabled(true);
                    tvStatus.setText(e.getMessage());
                });
            }
        }).start();
    }

    private void addLibrary(LibItem item) {
        if (projectRoot == null) return;
        File pomFile = com.ccs.wiredroid.project.BuildSystem.buildScript(projectRoot);
        if (pomFile == null || !pomFile.exists()) {
            Toast.makeText(this, R.string.toast_no_build_script, Toast.LENGTH_SHORT).show();
            return;
        }

        AlertDialog loadingDlg = newRoundedDialog()
                .setMessage(getString(R.string.lib_loading_versions))
                .setCancelable(false)
                .show();

        new Thread(() -> {
            try {
                String qStr = "g:\"" + item.groupId + "\" AND a:\"" + item.artifactId + "\"";
                String encoded = URLEncoder.encode(qStr, "UTF-8");
                String urlStr = "https://search.maven.org/solrsearch/select?q=" + encoded + "&core=gav&rows=100&wt=json";

                HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                conn.setRequestMethod("GET");

                int responseCode = conn.getResponseCode();
                if (responseCode == 200) {
                    InputStream in = conn.getInputStream();
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = in.read(buffer)) != -1) {
                        out.write(buffer, 0, len);
                    }
                    String json = out.toString("UTF-8");
                    conn.disconnect();

                    JSONObject rootObj = new JSONObject(json);
                    JSONObject responseObj = rootObj.optJSONObject("response");
                    final List<String> versions = new ArrayList<>();
                    if (responseObj != null) {
                        JSONArray docs = responseObj.optJSONArray("docs");
                        if (docs != null) {
                            for (int i = 0; i < docs.length(); i++) {
                                JSONObject doc = docs.getJSONObject(i);
                                String v = doc.optString("v");
                                if (!v.isEmpty() && !versions.contains(v)) {
                                    versions.add(v);
                                }
                            }
                        }
                    }

                    runOnUiThread(() -> {
                        loadingDlg.dismiss();
                        if (versions.isEmpty()) {
                            versions.add(item.version);
                        }
                        showVersionPickerDialog(item, versions, pomFile);
                    });

                } else {
                    conn.disconnect();
                    throw new IOException("HTTP code " + responseCode);
                }

            } catch (Exception e) {
                runOnUiThread(() -> {
                    loadingDlg.dismiss();
                    List<String> versions = new ArrayList<>();
                    versions.add(item.version);
                    showVersionPickerDialog(item, versions, pomFile);
                });
            }
        }).start();
    }

    private void showVersionPickerDialog(LibItem item, List<String> versions, File pomFile) {
        final String[] versionArray = versions.toArray(new String[0]);
        final int[] selected = {0};

        newRoundedDialog()
                .setTitle(getString(R.string.lib_select_version))
                .setSingleChoiceItems(versionArray, 0, (dialog, which) -> selected[0] = which)
                .setPositiveButton(R.string.dialog_apply, (dialog, which) -> {
                    String chosenVersion = versionArray[selected[0]];
                    performAddDependency(item.groupId, item.artifactId, chosenVersion, pomFile);
                })
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }

    /**
     * Writes the dependency into whichever build script the project uses —
     * {@code pom.xml} via {@link PomWriter}, or a Gradle script via
     * {@link com.ccs.wiredroid.gradle.GradleDependencyWriter}.
     */
    private void performAddDependency(String groupId, String artifactId, String version, File pomFile) {
        try {
            byte[] bytes = Files.readAllBytes(pomFile.toPath());
            String content = new String(bytes, StandardCharsets.UTF_8);
            String name = pomFile.getName();
            String updated;
            if (name.equals("pom.xml")) {
                updated = PomWriter.addDependency(content, groupId, artifactId, version);
            } else {
                updated = com.ccs.wiredroid.gradle.GradleDependencyWriter.addDependency(
                        content, groupId, artifactId, version, name.endsWith(".kts"));
                if (updated.equals(content)) {
                    Toast.makeText(this, getString(R.string.lib_already_present, artifactId),
                            Toast.LENGTH_SHORT).show();
                    return;
                }
            }
            Files.write(pomFile.toPath(), updated.getBytes(StandardCharsets.UTF_8));

            Toast.makeText(this, getString(R.string.lib_added, artifactId), Toast.LENGTH_SHORT).show();
            setResult(Activity.RESULT_OK);
            finish();
        } catch (Exception e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }


    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    /** @see Dialogs#rounded */
    private com.google.android.material.dialog.MaterialAlertDialogBuilder newRoundedDialog() {
        return Dialogs.rounded(this);
    }

    public static void launch(Activity host, File projectPath, int requestCode) {
        Intent intent = new Intent(host, LibraryManagerActivity.class);
        intent.putExtra(EXTRA_PROJECT_PATH, projectPath.getAbsolutePath());
        host.startActivityForResult(intent, requestCode);
    }

    // ── FrameLayout custom subclass helper for matching package layout ────
    private static class FrameLayout extends android.widget.FrameLayout {
        public FrameLayout(Context context) { super(context); }
    }

    // ── Adapter & ViewHolders ─────────────────────────────────

    private static class LibItem {
        String groupId;
        String artifactId;
        String version;
    }

    private class ResultsAdapter extends RecyclerView.Adapter<ResultsAdapter.ViewHolder> {
        private final List<LibItem> items = new ArrayList<>();

        public void setItems(List<LibItem> items) {
            this.items.clear();
            this.items.addAll(items);
            notifyDataSetChanged();
        }

        public void clear() {
            this.items.clear();
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout card = new LinearLayout(parent.getContext());
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setPadding(dp(12), dp(12), dp(12), dp(12));
            
            RecyclerView.LayoutParams cardLp = new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cardLp.setMargins(dp(16), dp(6), dp(16), dp(6));
            card.setLayoutParams(cardLp);

            GradientDrawable cardBg = new GradientDrawable();
            cardBg.setColor(Colors.blend(theme.toolbar, theme.bg, 0.5f));
            cardBg.setCornerRadius(dp(8));
            card.setBackground(cardBg);

            TypedValue tv = new TypedValue();
            parent.getContext().getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
            if (tv.resourceId != 0) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    card.setForeground(parent.getContext().getResources().getDrawable(tv.resourceId, parent.getContext().getTheme()));
                }
            }

            // Left: Avatar with first letter of artifactId
            TextView avatar = new TextView(parent.getContext());
            avatar.setGravity(Gravity.CENTER);
            avatar.setTextSize(15);
            avatar.setTypeface(Typeface.DEFAULT_BOLD);
            avatar.setTextColor(Color.WHITE);
            
            GradientDrawable avatarBg = new GradientDrawable();
            avatarBg.setShape(GradientDrawable.OVAL);
            avatarBg.setColor(theme.accent);
            avatar.setBackground(avatarBg);

            LinearLayout.LayoutParams avatarLp = new LinearLayout.LayoutParams(dp(36), dp(36));
            avatarLp.rightMargin = dp(12);
            avatar.setLayoutParams(avatarLp);
            card.addView(avatar);

            // Center: Info texts (Artifact ID, Group ID)
            LinearLayout info = new LinearLayout(parent.getContext());
            info.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            info.setLayoutParams(infoLp);

            TextView name = new TextView(parent.getContext());
            name.setTextSize(14);
            name.setTypeface(Typeface.DEFAULT_BOLD);
            name.setTextColor(theme.text);
            info.addView(name);

            TextView group = new TextView(parent.getContext());
            group.setTextSize(11);
            group.setTextColor(theme.textDim);
            LinearLayout.LayoutParams groupLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            groupLp.topMargin = dp(2);
            group.setLayoutParams(groupLp);
            info.addView(group);

            card.addView(info);

            // Right: Version badge pill
            TextView badge = new TextView(parent.getContext());
            badge.setPadding(dp(8), dp(4), dp(8), dp(4));
            badge.setTextSize(11);
            badge.setTypeface(Typeface.DEFAULT_BOLD);
            badge.setTextColor(theme.successText);
            
            GradientDrawable badgeBg = new GradientDrawable();
            badgeBg.setColor(Colors.blend(theme.successText, theme.bg, 0.85f));
            badgeBg.setCornerRadius(dp(12));
            badge.setBackground(badgeBg);

            LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            badgeLp.leftMargin = dp(8);
            badge.setLayoutParams(badgeLp);
            card.addView(badge);

            return new ViewHolder(card, avatar, name, group, badge);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            LibItem item = items.get(position);
            holder.tvName.setText(item.artifactId);
            holder.tvGroup.setText(item.groupId);
            
            String firstLetter = item.artifactId != null && !item.artifactId.isEmpty() 
                    ? item.artifactId.substring(0, 1).toUpperCase() 
                    : "L";
            holder.tvAvatar.setText(firstLetter);
            
            // Set random/different colors for avatar backgrounds based on artifact ID hash to look amazing!
            int hash = item.artifactId != null ? Math.abs(item.artifactId.hashCode()) : 0;
            int color;
            switch (hash % 5) {
                case 0: color = theme.accent; break;
                case 1: color = theme.successText; break;
                case 2: color = 0xFFE6A23C; break; // Yellow/orange
                case 3: color = 0xFFE74C3C; break; // Red/orange
                case 4: 
                default: color = 0xFF9B59B6; break; // Purple
            }
            GradientDrawable avatarBg = (GradientDrawable) holder.tvAvatar.getBackground();
            avatarBg.setColor(color);
            
            holder.tvBadge.setText(item.version);
            holder.itemView.setOnClickListener(v -> addLibrary(item));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            final TextView tvAvatar;
            final TextView tvName;
            final TextView tvGroup;
            final TextView tvBadge;

            ViewHolder(View itemView, TextView avatar, TextView name, TextView group, TextView badge) {
                super(itemView);
                this.tvAvatar = avatar;
                this.tvName = name;
                this.tvGroup = group;
                this.tvBadge = badge;
            }
        }
    }
}
