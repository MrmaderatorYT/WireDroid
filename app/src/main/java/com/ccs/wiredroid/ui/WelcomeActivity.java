package com.ccs.wiredroid.ui;

import com.ccs.wiredroid.util.Colors;
import com.ccs.wiredroid.R;
import com.ccs.wiredroid.util.AppPreferences;
import com.ccs.wiredroid.util.AppTheme;
import com.ccs.wiredroid.util.FullScreenHelper;
import com.ccs.wiredroid.maven.MavenPaths;
import com.ccs.wiredroid.archive.ArchiveExtractor;
import com.ccs.wiredroid.project.ImportedLayout;
import com.ccs.wiredroid.project.ImportedProjectConfigurator;
import com.ccs.wiredroid.project.PlaygroundProjectFactory;
import com.ccs.wiredroid.project.ProjectImporter;
import com.ccs.wiredroid.project.ProjectJdk;
import com.ccs.wiredroid.project.ProjectTemplates;
import com.ccs.wiredroid.project.RepoUrl;
import com.ccs.wiredroid.project.RepositoryImporter;
import com.ccs.wiredroid.maven.MavenProjectFactory;
import com.ccs.wiredroid.project.GradleProjectFactory;
import com.ccs.wiredroid.project.KotlinProjectFactory;
import com.ccs.wiredroid.tools.bytecode.BytecodeProjectFactory;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class WelcomeActivity extends AppCompatActivity {

    private AppPreferences appPrefs;
    private AppTheme theme;

    private EditText etSearchProjects;
    private Button btnNewProject;
    private Button btnOpenProject;
    private Button btnCloneRepo;
    private View layoutEmptyProjects;
    private RecyclerView rvRecentProjects;

    private RecentProjectsAdapter adapter;
    private final List<String> allRecentPaths = new ArrayList<>();
    private final List<String> filteredPaths = new ArrayList<>();
    /** False until the first disk scan has come back; see updateEmptyStateVisibility. */
    private boolean recentsLoaded;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_ESCAPE) {
                if (AnchoredMenu.dismissOpen()) return true;
            }
            if (event.isCtrlPressed() && !event.isAltPressed()) {
                switch (event.getKeyCode()) {
                    case KeyEvent.KEYCODE_N:
                        NewProjectActivity.launchForResult(this, REQ_NEW_PROJECT);
                        return true;
                    case KeyEvent.KEYCODE_O:
                        showOpenProjectMenu(btnOpenProject);
                        return true;
                    case KeyEvent.KEYCODE_L:
                        etSearchProjects.requestFocus();
                        etSearchProjects.selectAll();
                        return true;
                    default:
                        break;
                }
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        appPrefs = new AppPreferences(this);
        theme = AppTheme.byId(appPrefs.getThemeId(), appPrefs);
        setTheme(theme.dark ? R.style.Theme_WireDroid : R.style.Theme_WireDroid_Light);

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_welcome);

        // A project's context menu takes no focus, so back arrives here; while
        // one is open, back means "close it" rather than "leave the screen".
        getOnBackPressedDispatcher().addCallback(this,
                new androidx.activity.OnBackPressedCallback(true) {
                    @Override
                    public void handleOnBackPressed() {
                        if (AnchoredMenu.dismissOpen()) return;
                        setEnabled(false);
                        getOnBackPressedDispatcher().onBackPressed();
                        setEnabled(true);
                    }
                });
        FullScreenHelper.enable(this);

        bindViews();
        applyThemeStyles();
        setupRecentProjects();
        setupActions();

        // Request MANAGE_EXTERNAL_STORAGE permission if needed (Android 11+).
        // After the first frame: this resolves a canonical path and asks the
        // system whether the permission is held, and if it is not, it puts a
        // modal dialog up — which reads better over a drawn screen than over a
        // blank one.
        ui.post(() -> {
            if (isFinishing() || isDestroyed()) return;
            requestStoragePermission();
        });

        // If launched with an action to immediately show new project creation dialog
        if ("ACTION_NEW_PROJECT".equals(getIntent().getAction())) {
            NewProjectActivity.launchForResult(this, REQ_NEW_PROJECT);
        }
    }

    private void bindViews() {
        etSearchProjects = findViewById(R.id.etSearchProjects);
        btnNewProject = findViewById(R.id.btnNewProject);
        btnOpenProject = findViewById(R.id.btnOpenProject);
        btnCloneRepo = findViewById(R.id.btnCloneRepo);
        layoutEmptyProjects = findViewById(R.id.layoutEmptyProjects);
        rvRecentProjects = findViewById(R.id.rvRecentProjects);
    }

    private void applyThemeStyles() {
        // Root
        View rootLayout = findViewById(R.id.welcome_root);
        if (rootLayout == null) {
            rootLayout = findViewById(android.R.id.content);
        }
        if (rootLayout != null) {
            rootLayout.setBackgroundColor(theme.bg);
        }

        // Navigation Bar / Sidebar
        View sidebarLayout = findViewById(R.id.welcome_sidebar);
        if (sidebarLayout == null) {
            sidebarLayout = findViewById(R.id.sidebarProjects) != null ?
                    (View) findViewById(R.id.sidebarProjects).getParent() : null;
        }
        if (sidebarLayout != null) {
            sidebarLayout.setBackgroundColor(theme.toolbar);
        }

        // App title & version
        TextView tvAppName = findViewById(R.id.tvAppName);
        if (tvAppName != null) tvAppName.setTextColor(theme.text);
        TextView tvAppVersion = findViewById(R.id.tvAppVersion);
        if (tvAppVersion != null) {
            tvAppVersion.setTextColor(theme.textDim);
            // Same string the package manager would return, minus the binder
            // round trip to ask for it.
            tvAppVersion.setText(com.ccs.wiredroid.BuildConfig.VERSION_NAME);
        }

        // Search field
        if (etSearchProjects != null) {
            etSearchProjects.setBackgroundColor(Colors.blend(theme.toolbar, theme.bg, 0.2f));
            etSearchProjects.setTextColor(theme.text);
            etSearchProjects.setHintTextColor(theme.textDim);
        }

        // Buttons (using ColorStateList for compatibility with MaterialButton)
        if (btnNewProject != null) {
            btnNewProject.setBackgroundTintList(android.content.res.ColorStateList.valueOf(theme.accent));
            btnNewProject.setTextColor(Color.WHITE);
        }
        if (btnOpenProject != null) {
            btnOpenProject.setBackgroundTintList(android.content.res.ColorStateList.valueOf(theme.toolbar));
            btnOpenProject.setTextColor(theme.text);
        }
        if (btnCloneRepo != null) {
            btnCloneRepo.setBackgroundTintList(android.content.res.ColorStateList.valueOf(theme.toolbar));
            btnCloneRepo.setTextColor(theme.textDim);
        }

        // Sidebar items
        TextView sidebarProjects = findViewById(R.id.sidebarProjects);
        if (sidebarProjects != null) {
            float density = getResources().getDisplayMetrics().density;
            GradientDrawable gd = new GradientDrawable();
            gd.setColor(theme.accent);
            gd.setCornerRadius(6f * density); // 6dp corner radius
            sidebarProjects.setBackground(gd);
            sidebarProjects.setTextColor(Color.WHITE);
        }
        TextView sidebarMaterials = findViewById(R.id.sidebarMaterials);
        if (sidebarMaterials != null) {
            sidebarMaterials.setText("Приклади схем");
            sidebarMaterials.setTextColor(theme.textDim);
            sidebarMaterials.setOnClickListener(v -> com.ccs.wiredroid.wire.WireExamples.show(this,file->openProject(file.getAbsolutePath())));
        }
        TextView sidebarCredits = findViewById(R.id.sidebarCredits);
        if (sidebarCredits != null) sidebarCredits.setTextColor(theme.textDim);

        android.widget.ImageView sidebarSettings = findViewById(R.id.sidebarSettings);
        if (sidebarSettings != null) sidebarSettings.setColorFilter(theme.textDim);

        // Empty state
        TextView tvEmptyTitle = findViewById(R.id.tvEmptyTitle);
        if (tvEmptyTitle != null) tvEmptyTitle.setTextColor(theme.textDim);
        TextView tvEmptySubtitle = findViewById(R.id.tvEmptySubtitle);
        if (tvEmptySubtitle != null) tvEmptySubtitle.setTextColor(theme.textDim);
    }

    /**
     * Attaches the recent-projects list and (re)loads it.
     *
     * <p>The load stats every remembered path and may list the projects folder,
     * which is disk work — it used to run inline in {@code onCreate}, so the
     * first screen of the app waited on storage before it could draw. The list
     * now arrives a moment after the screen does. Callers may invoke this as
     * often as they like; the adapter is created once and reused.</p>
     */
    private void setupRecentProjects() {
        if (adapter == null) {
            adapter = new RecentProjectsAdapter(filteredPaths, theme, this::openProject,
                    new ProjectOptionsListener() {
                        @Override public void onOptionsBelow(String path, View anchor) {
                            projectMenu(path).showBelow(anchor);
                        }
                        @Override public void onOptionsAt(String path, View row, float x, float y) {
                            projectMenu(path).showAt(row, x, y);
                        }
                    });
            rvRecentProjects.setLayoutManager(new LinearLayoutManager(this));
            rvRecentProjects.setAdapter(adapter);
        }
        updateEmptyStateVisibility();

        io.execute(() -> {
            final List<String> found = new ArrayList<>();
            List<String> saved = appPrefs.getRecentProjects();
            for (String path : saved) {
                File file = new File(path);
                if (com.ccs.wiredroid.wire.WireProject.isCircuit(file)) {
                    found.add(path);
                } else {
                    appPrefs.removeRecentProject(path);
                }
            }

            // If list is empty, default it to our base folder items
            if (found.isEmpty()) {
                File base = MavenPaths.getWireDroidBase(this);
                File[] dirs = base.listFiles();
                if (dirs != null) {
                    for (File d : dirs) {
                        if (com.ccs.wiredroid.wire.WireProject.isCircuit(d)) {
                            found.add(d.getAbsolutePath());
                            appPrefs.addRecentProject(d.getAbsolutePath());
                        }
                    }
                }
            }

            ui.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                allRecentPaths.clear();
                allRecentPaths.addAll(found);
                recentsLoaded = true;
                // Re-apply whatever is in the search box, so a query typed while
                // the scan was running is not silently thrown away.
                filterProjects(etSearchProjects == null ? "" : etSearchProjects.getText().toString());
            });
        });
    }

    private void setupActions() {
        etSearchProjects.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                filterProjects(s.toString());
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        
        View btnSettings = findViewById(R.id.sidebarSettings);
        if (btnSettings != null) {
            btnSettings.setOnClickListener(v -> {
                Intent intent = new Intent(this, SettingsActivity.class);
                startActivity(intent);
            });
        }

        btnNewProject.setOnClickListener(v -> NewProjectActivity.launchForResult(this, REQ_NEW_PROJECT));
        btnOpenProject.setOnClickListener(v -> showOpenProjectMenu(btnOpenProject));
        btnCloneRepo.setOnClickListener(v -> showCloneRepoDialog());

        View sidebarCredits = findViewById(R.id.sidebarCredits);
        if (sidebarCredits != null) {
            sidebarCredits.setOnClickListener(v ->
                    startActivity(new Intent(this, CreditsActivity.class)));
        }
    }

    private final SharedPreferences.OnSharedPreferenceChangeListener themeChangeListener = (sharedPreferences, key) -> {
        if ("theme_id".equals(key) || key.startsWith("custom_")) {
            theme = AppTheme.byId(appPrefs.getThemeId(), appPrefs);
            setTheme(theme.dark ? R.style.Theme_WireDroid : R.style.Theme_WireDroid_Light);
            recreate();
        }
    };

    @Override
    protected void onStart() {
        super.onStart();
        appPrefs.raw().registerOnSharedPreferenceChangeListener(themeChangeListener);
        // Refresh preferences and redraw in case theme changed inside SettingsActivity
        AppTheme currentTheme = AppTheme.byId(appPrefs.getThemeId(), appPrefs);
        if (!currentTheme.id.equals(theme.id) || currentTheme.bg != theme.bg) {
            theme = currentTheme;
            setTheme(theme.dark ? R.style.Theme_WireDroid : R.style.Theme_WireDroid_Light);
            recreate();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        appPrefs.raw().unregisterOnSharedPreferenceChangeListener(themeChangeListener);
    }

    @Override
    protected void onResume() {
        super.onResume();
    }

    private static final int REQ_STORAGE_PERMISSION = 9999;

    /** Result code for {@link NewProjectActivity}; 2001/2002 are the importers. */
    private static final int REQ_NEW_PROJECT = 2003;

    private void requestStoragePermission() {
        // Only request if we actually need external storage access
        // If project is on internal storage, skip permission request
        String savedPath = appPrefs.getProjectRoot();
        if (savedPath != null) {
            try {
                String internalPath = getFilesDir().getCanonicalPath();
                if (savedPath.startsWith(internalPath)) {
                    return; // Project is on internal storage, no permission needed
                }
            } catch (Exception ignored) {}
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            if (!android.os.Environment.isExternalStorageManager()) {
                newRoundedDialog()
                        .setTitle(R.string.permission_storage_title)
                        .setMessage(R.string.permission_storage_message)
                        .setPositiveButton(R.string.permission_storage_open_settings, (d, w) -> {
                            openStorageSettings();
                        })
                        .setNegativeButton(R.string.permission_storage_exit, (d, w) -> finish())
                        .setCancelable(false)
                        .show();
            }
        }
    }

    private void openStorageSettings() {
        try {
            // Try to open app-specific storage settings
            Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
            intent.setData(android.net.Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            try {
                // Fallback: open all files access settings
                Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                startActivity(intent);
            } catch (Exception e2) {
                // Last resort: open app settings
                try {
                    Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    intent.setData(android.net.Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                } catch (Exception e3) {
                    Toast.makeText(this, R.string.toast_cannot_open_settings, Toast.LENGTH_LONG).show();
                }
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_NEW_PROJECT) {
            if (resultCode == RESULT_OK && data != null) {
                String scratch = data.getStringExtra(NewProjectActivity.EXTRA_SCRATCH_PATH);
                if (scratch != null) {
                    // No project to open, and none needed: the editor takes the
                    // file through the same extra a system "open with" uses, and
                    // whatever project was last used stays selected.
                    openScratch(scratch);
                    return;
                }
                String path = data.getStringExtra(NewProjectActivity.EXTRA_PROJECT_PATH);
                // openProject stores the root, records it as recent and hands
                // over to MainActivity — the same path a recent entry takes.
                if (path != null) openProject(path);
            }
            return;
        }
        if (requestCode == REQ_STORAGE_PERMISSION) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                if (!android.os.Environment.isExternalStorageManager()) {
                    newRoundedDialog()
                            .setTitle(R.string.permission_storage_denied_title)
                            .setMessage(R.string.permission_storage_denied_message)
                            .setPositiveButton(R.string.permission_storage_try_again, (d, w) -> requestStoragePermission())
                            .setNegativeButton(R.string.permission_storage_exit, (d, w) -> finish())
                            .setCancelable(false)
                            .show();
                }
            }
        } else if (requestCode == REQUEST_IMPORT_ARCHIVE && resultCode == RESULT_OK && data != null) {
            runImport(data.getData(), true);
        } else if (requestCode == REQUEST_IMPORT_FOLDER && resultCode == RESULT_OK && data != null) {
            runImport(data.getData(), false);
        }
    }

    /** Shared progress/reporting shell around {@link ProjectImporter}. */
    private void runImport(android.net.Uri uri, boolean isArchive) {
        if (uri == null) return;
        android.app.ProgressDialog pd = new android.app.ProgressDialog(this);
        com.ccs.wiredroid.util.FullScreenHelper.keepImmersive(pd);
        pd.setMessage(getString(isArchive
                ? R.string.import_stage_reading : R.string.import_stage_copying));
        pd.setCancelable(false);
        pd.show();

        ProjectImporter.Callback callback = new ProjectImporter.Callback() {
            @Override public void onProgress(String message) {
                if (pd.isShowing()) pd.setMessage(message);
            }

            @Override
            public void onSuccess(File projectRoot, ArchiveExtractor.Result summary) {
                dismiss(pd);
                appPrefs.addRecentProject(projectRoot.getAbsolutePath());
                setupRecentProjects();
                showImportSummary(projectRoot, summary);
            }

            @Override public void onFailure(String message) {
                dismiss(pd);
                newRoundedDialog()
                        .setTitle(R.string.import_error_title)
                        .setMessage(message)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            }
        };

        if (isArchive) {
            ProjectImporter.importArchive(this, uri, callback);
        } else {
            ProjectImporter.importFolder(this, uri, callback);
        }
    }

    private void dismiss(android.app.ProgressDialog pd) {
        if (pd.isShowing() && !isFinishing()) {
            try { pd.dismiss(); } catch (IllegalArgumentException ignored) {}
        }
    }

    /**
     * Reports what landed. Anything the extractor refused (a path escaping the
     * destination, a symlink) is named here rather than passed over in silence.
     */
    private void showImportSummary(File projectRoot, ArchiveExtractor.Result summary) {
        if (summary == null || summary.skipped.isEmpty()) {
            Toast.makeText(this, getString(R.string.import_done_title), Toast.LENGTH_SHORT).show();
            openProject(projectRoot.getAbsolutePath());
            return;
        }
        StringBuilder message = new StringBuilder(getString(R.string.import_done_summary,
                summary.fileCount, summary.directoryCount, formatSize(summary.bytesWritten)));
        message.append("\n\n").append(getString(R.string.import_done_skipped, summary.skipped.size()));
        for (String entry : summary.skipped) {
            message.append("\n• ").append(entry);
        }
        newRoundedDialog()
                .setTitle(R.string.import_done_title)
                .setMessage(message.toString())
                .setPositiveButton(R.string.welcome_project_options_open,
                        (d, w) -> openProject(projectRoot.getAbsolutePath()))
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) {
            return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024));
        }
        return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private void filterProjects(String query) {
        filteredPaths.clear();
        if (query.isEmpty()) {
            filteredPaths.addAll(allRecentPaths);
        } else {
            String q = query.toLowerCase(Locale.ROOT);
            for (String path : allRecentPaths) {
                File f = new File(path);
                if (f.getName().toLowerCase(Locale.ROOT).contains(q)) {
                    filteredPaths.add(path);
                }
            }
        }
        adapter.notifyDataSetChanged();
        updateEmptyStateVisibility();
    }

    private void updateEmptyStateVisibility() {
        if (filteredPaths.isEmpty() && !recentsLoaded) {
            // Nothing to show yet only because the scan has not come back;
            // "you have no projects" would be a lie for those few frames.
            layoutEmptyProjects.setVisibility(View.GONE);
            rvRecentProjects.setVisibility(View.GONE);
            return;
        }
        if (filteredPaths.isEmpty()) {
            layoutEmptyProjects.setVisibility(View.VISIBLE);
            rvRecentProjects.setVisibility(View.GONE);
        } else {
            layoutEmptyProjects.setVisibility(View.GONE);
            rvRecentProjects.setVisibility(View.VISIBLE);
        }
    }

    /** Opens a freshly created scratch file in the editor, project untouched. */
    private void openScratch(String path) {
        Intent intent = new Intent(this, MainActivity.class);
        intent.putExtra(FileOpenActivity.EXTRA_OPEN_FILE, path);
        startActivity(intent);
        finish();
    }

    private void openProject(String path) {
        if (com.ccs.wiredroid.wire.WireProject.isCircuit(new File(path))) {
            appPrefs.setProjectRoot(path);
            appPrefs.addRecentProject(path);
            com.ccs.wiredroid.wire.WireActivity.launch(this, new File(path), false);
        } else {
            Toast.makeText(this, "Оберіть embedded-проєкт WireDroid зі схемою circuit.wire.json", Toast.LENGTH_LONG).show();
        }
    }

    /**
     * The actions for one project row.
     *
     * <p>A menu rather than a dialog: it belongs to the row that was pressed and
     * opens beside it, instead of dimming the screen and appearing in the
     * middle. Deleting from disk still asks first — that one is not undoable.</p>
     */
    private AnchoredMenu projectMenu(String path) {
        final File file = new File(path);
        // Drawn icons rather than text glyphs and an emoji: the emoji came from
        // the system font, so it sat at a different weight and colour from the
        // rest of the row and ignored the theme entirely.
        return AnchoredMenu.with(this, theme)
                .title(file.getName())
                .item(R.drawable.ic_folder, getString(R.string.welcome_project_options_open),
                        () -> openProject(path))
                .item(R.drawable.ic_close, getString(R.string.welcome_project_options_remove), () -> {
                    appPrefs.removeRecentProject(path);
                    setupRecentProjects();
                })
                .separator()
                .danger(R.drawable.ic_trash, getString(R.string.welcome_project_options_delete),
                        () -> confirmDeleteProject(file, path));
    }

    private void confirmDeleteProject(File file, String path) {
        newRoundedDialog()
                .setTitle(R.string.welcome_delete_project_title)
                .setMessage(getString(R.string.welcome_delete_project_message, file.getName()))
                .setPositiveButton(R.string.dialog_delete, (d, w) -> {
                    deleteRecursive(file);
                    appPrefs.removeRecentProject(path);
                    setupRecentProjects();
                })
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }

    private void showOpenProjectMenu(View anchor) {
        AnchoredMenu.with(this, theme)
                .item(R.drawable.ic_folder, getString(R.string.import_option_folder), this::importFolder)
                .item(R.drawable.ic_archive, getString(R.string.import_option_archive), this::importArchive)
                .showBelow(anchor);
    }

    private static final int REQUEST_IMPORT_ARCHIVE = 2001;
    private static final int REQUEST_IMPORT_FOLDER = 2002;

    private void importArchive() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        String[] mimeTypes = {
                "application/zip", "application/x-zip-compressed", "application/java-archive",
                "application/x-tar", "application/x-gtar", "application/gzip",
                "application/x-gzip", "application/x-compressed-tar",
                "application/vnd.rar", "application/x-rar-compressed",
                "application/octet-stream"
        };
        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
        startActivityForResult(intent, REQUEST_IMPORT_ARCHIVE);
    }

    private void importFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        startActivityForResult(intent, REQUEST_IMPORT_FOLDER);
    }

    // ── Repository import ────────────────────────────────────────────────────
    //
    // Clone, then work out what was cloned and give it a build script if it
    // needs one. The parsing lives in RepoUrl, the detection in
    // ProjectLayoutDetector and the generation in ImportedProjectConfigurator;
    // what is left here is the dialogs and the thread hopping.

    private void showCloneRepoDialog() {
        int pad = dp(12);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad, pad, pad);

        EditText etUrl = newEditForDialog(getString(R.string.gh_url_hint));
        EditText etName = newEditForDialog(getString(R.string.gh_name_hint));
        EditText etUser = newEditForDialog(getString(R.string.gh_user_hint));
        EditText etToken = newEditForDialog(getString(R.string.gh_token_hint));
        etToken.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        box.addView(etUrl);
        box.addView(etName);
        box.addView(etUser);
        box.addView(etToken);

        newRoundedDialog()
                .setTitle(R.string.gh_import_title)
                .setMessage(R.string.gh_import_message)
                .setView(box)
                .setPositiveButton(R.string.gh_import_button, (d, w) -> startRepositoryImport(
                        etUrl.getText().toString(),
                        etName.getText().toString().trim(),
                        etUser.getText().toString().trim(),
                        etToken.getText().toString().trim()))
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }

    private void startRepositoryImport(String rawUrl, String folderName, String user, String token) {
        if (rawUrl == null || rawUrl.trim().isEmpty()) {
            Toast.makeText(this, R.string.gh_url_required, Toast.LENGTH_SHORT).show();
            return;
        }
        RepoUrl repo = RepoUrl.parse(rawUrl);
        if (repo == null) {
            newRoundedDialog()
                    .setTitle(R.string.gh_invalid_url_title)
                    .setMessage(R.string.gh_invalid_url_message)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }

        String name = folderName.isEmpty()
                ? repo.suggestedFolderName()
                : folderName.replaceAll("[^a-zA-Z0-9._-]", "_");
        File dest = new File(MavenPaths.getWireDroidBase(this), name);
        if (dest.isDirectory()) {
            String[] existing = dest.list();
            if (existing != null && existing.length > 0) {
                newRoundedDialog()
                        .setTitle(R.string.gh_import_title)
                        .setMessage(getString(R.string.gh_folder_exists, name))
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
                return;
            }
        }

        final java.util.concurrent.atomic.AtomicBoolean cancel =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        android.app.ProgressDialog pd = new android.app.ProgressDialog(this);
        com.ccs.wiredroid.util.FullScreenHelper.keepImmersive(pd);
        pd.setTitle(getString(R.string.gh_progress_title, repo.slug()));
        pd.setMessage(getString(R.string.gh_progress_preparing));
        pd.setCancelable(false);
        // Cloning a repository over a phone connection is exactly the operation
        // people need to be able to abandon.
        pd.setButton(android.content.DialogInterface.BUTTON_NEGATIVE,
                getString(R.string.dialog_cancel), (d, w) -> cancel.set(true));
        pd.show();

        final String username = user.isEmpty() ? null : user;
        final String password = token.isEmpty() ? null : token;

        io.execute(() -> {
            try {
                ImportedLayout layout = RepositoryImporter.cloneAndDetect(
                        repo, dest, username, password, cancel,
                        task -> ui.post(() -> {
                            if (pd.isShowing()) {
                                pd.setMessage(getString(R.string.gh_progress_task, task));
                            }
                        }));
                ui.post(() -> {
                    dismiss(pd);
                    onRepositoryCloned(repo, layout);
                });
            } catch (RepositoryImporter.CancelledException e) {
                ui.post(() -> {
                    dismiss(pd);
                    Toast.makeText(this, R.string.gh_cancelled, Toast.LENGTH_SHORT).show();
                    setupRecentProjects();
                });
            } catch (Throwable e) {
                android.util.Log.e("RepositoryImport", "Import failed", e);
                ui.post(() -> {
                    dismiss(pd);
                    newRoundedDialog()
                            .setTitle(R.string.gh_failed_title)
                            .setMessage(describeError(e))
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                });
            }
        });
    }

    /** The clone succeeded; pick a module if there is a choice, then configure. */
    private void onRepositoryCloned(RepoUrl repo, ImportedLayout layout) {
        appPrefs.addRecentProject(layout.root.getAbsolutePath());
        setupRecentProjects();

        if (!layout.isMultiModule()) {
            configureAndSummarize(repo, layout.root, layout);
            return;
        }

        final List<ImportedLayout.Module> modules = layout.modules;
        String[] names = new String[modules.size() + 1];
        names[0] = getString(R.string.gh_module_root);
        for (int i = 0; i < modules.size(); i++) {
            names[i + 1] = modules.get(i).name;
        }
        newRoundedDialog()
                .setTitle(R.string.gh_modules_title)
                .setMessage(getString(R.string.gh_modules_message, modules.size()))
                .setItems(names, (d, which) -> {
                    if (which == 0) {
                        configureAndSummarize(repo, layout.root, layout);
                    } else {
                        // A module is a project in its own right: detect it afresh
                        // rather than assume it looks like its parent.
                        configureAndSummarize(repo, modules.get(which - 1).dir, null);
                    }
                })
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }

    /** Generates whatever the project is missing, then reports what was found. */
    private void configureAndSummarize(RepoUrl repo, File dir, ImportedLayout known) {
        android.app.ProgressDialog pd = new android.app.ProgressDialog(this);
        com.ccs.wiredroid.util.FullScreenHelper.keepImmersive(pd);
        pd.setMessage(getString(R.string.gh_progress_configuring));
        pd.setCancelable(false);
        pd.show();

        io.execute(() -> {
            ImportedLayout layout = known != null ? known : RepositoryImporter.detect(dir);
            ImportedProjectConfigurator.Outcome outcome;
            String error = null;
            try {
                outcome = ImportedProjectConfigurator.configure(this, layout);
            } catch (Exception e) {
                outcome = null;
                error = describeError(e);
            }
            final ImportedProjectConfigurator.Outcome result = outcome;
            final String failure = error;
            ui.post(() -> {
                dismiss(pd);
                if (result == null) {
                    newRoundedDialog()
                            .setTitle(R.string.gh_failed_title)
                            .setMessage(failure)
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                    return;
                }
                showDetectionSummary(repo, layout, result);
            });
        });
    }

    private void showDetectionSummary(RepoUrl repo, ImportedLayout layout,
                                      ImportedProjectConfigurator.Outcome outcome) {
        newRoundedDialog()
                .setTitle(R.string.gh_detected_title)
                .setMessage(describeLayout(repo, layout, outcome))
                .setPositiveButton(R.string.gh_open,
                        (d, w) -> openProject(layout.root.getAbsolutePath()))
                .setNegativeButton(R.string.gh_keep, null)
                .show();
    }

    /** Plain prose about what the repository turned out to be. */
    private String describeLayout(RepoUrl repo, ImportedLayout layout,
                                  ImportedProjectConfigurator.Outcome outcome) {
        if (com.ccs.wiredroid.BuildConfig.EMBEDDED_EDITION) {
            return "Embedded-проєкт: " + layout.root.getName()
                    + "\nДодайте плату до схеми й оберіть наявний .cpp/.ino в інспекторі. Файли репозиторію збережені. Збірка SDK виробника й прошивання ще не підтримуються.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(getString(R.string.gh_detected_line, getString(kindLabel(layout.kind))));
        if (layout.kind != ImportedLayout.Kind.UNKNOWN) {
            sb.append('\n').append(getString(R.string.gh_detail_language,
                    getString(layout.kotlin ? R.string.gh_language_kotlin
                            : R.string.gh_language_java)));
        }
        if (repo != null && repo.branch != null) {
            sb.append('\n').append(getString(R.string.gh_detail_branch, repo.branch));
        }
        if (layout.isMultiModule()) {
            sb.append('\n').append(getString(R.string.gh_detail_modules, layout.modules.size()));
        }
        if (layout.packageName != null) {
            sb.append('\n').append(getString(R.string.gh_detail_package, layout.packageName));
        }
        if (layout.mainClass != null) {
            sb.append('\n').append(getString(R.string.gh_detail_main_class, layout.mainClass));
        }
        if (!layout.mainSourceRoots.isEmpty()) {
            sb.append('\n').append(getString(R.string.gh_detail_source_roots,
                    join(layout.mainSourceRoots)));
        }
        if (!layout.testSourceRoots.isEmpty()) {
            sb.append('\n').append(getString(R.string.gh_detail_test_roots,
                    join(layout.testSourceRoots)));
        }
        if (!layout.libJars.isEmpty()) {
            sb.append('\n').append(getString(R.string.gh_detail_libs, join(layout.libJars)));
        }
        sb.append('\n');
        if (outcome.wroteAnything()) {
            sb.append('\n').append(getString(R.string.gh_detail_generated,
                    join(outcome.generated)));
        } else if (layout.isBuildable()) {
            sb.append('\n').append(getString(R.string.gh_detail_ready));
        }
        if (!outcome.warnings.isEmpty()) {
            sb.append('\n').append(getString(R.string.gh_detail_unsupported,
                    join(outcome.warnings)));
        }
        return sb.toString();
    }

    private static int kindLabel(ImportedLayout.Kind kind) {
        switch (kind) {
            case MAVEN:   return R.string.gh_kind_maven;
            case GRADLE:  return R.string.gh_kind_gradle;
            case ECLIPSE: return R.string.gh_kind_eclipse;
            case PLAIN_SOURCES: return R.string.gh_kind_plain;
            default:      return R.string.gh_kind_unknown;
        }
    }

    private static String join(List<String> values) {
        StringBuilder sb = new StringBuilder();
        for (String value : values) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(value);
        }
        return sb.toString();
    }

    private static String describeError(Throwable e) {
        String message = e.getMessage();
        if (message == null || message.trim().isEmpty()) {
            message = e.getClass().getSimpleName();
        }
        Throwable cause = e.getCause();
        if (cause != null && cause.getMessage() != null
                && !cause.getMessage().equals(e.getMessage())) {
            message = message + "\n" + cause.getMessage();
        }
        return message;
    }

    private void showNewMavenProjectDialog() {
        String[] projectTypes = {
            getString(R.string.project_type_maven),
            getString(R.string.project_type_gradle),
            getString(R.string.project_type_kotlin),
            getString(R.string.project_type_bytecode),
            getString(R.string.project_type_playground),
            getString(R.string.project_type_samples)
        };

        newRoundedDialog()
                .setTitle(R.string.dialog_new_project_title)
                .setItems(projectTypes, (dialog, which) -> {
                    if (which == 0) {
                        showMavenProjectForm();
                    } else if (which == 1) {
                        showGradleProjectForm();
                    } else if (which == 2) {
                        showKotlinProjectForm();
                    } else if (which == 3) {
                        showBytecodeProjectForm();
                    } else if (which == 4) {
                        createPlaygroundProject();
                    } else if (which == 5) {
                        Intent intent = new Intent(this, NewProjectActivity.class);
                        intent.putExtra("initial_template", ProjectTemplates.Template.SAMPLES.ordinal());
                        startActivityForResult(intent, REQ_NEW_PROJECT);
                    }
                })
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }

    private void createPlaygroundProject() {
        Toast.makeText(this, getString(R.string.welcome_creating_playground), Toast.LENGTH_SHORT).show();
        io.execute(() -> {
            try {
                File root = PlaygroundProjectFactory.create(this);
                ui.post(() -> {
                    appPrefs.setProjectRoot(root.getAbsolutePath());
                    appPrefs.addRecentProject(root.getAbsolutePath());
                    Toast.makeText(this, getString(R.string.welcome_playground_created), Toast.LENGTH_SHORT).show();
                    openProject(root.getAbsolutePath());
                });
            } catch (Exception e) {
                ui.post(() -> Toast.makeText(this, getString(R.string.welcome_error, e.getMessage()),
                        Toast.LENGTH_LONG).show());
            }
        });
    }

    private void showMavenProjectForm() {
        int pad = dp(12);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad, pad, pad);

        EditText etName = newEditForDialog(getString(R.string.welcome_project_name_hint));
        EditText etGroup = newEditForDialog(getString(R.string.welcome_project_group_hint));
        EditText etArtifact = newEditForDialog(getString(R.string.welcome_project_artifact_hint));
        JdkPicker jdk = new JdkPicker(this, theme, ProjectJdk.defaultForNewProject(this));

        box.addView(etName);
        box.addView(etGroup);
        box.addView(etArtifact);
        box.addView(jdk.getView());

        newRoundedDialog()
                .setTitle(R.string.dialog_new_maven_title)
                .setMessage(R.string.dialog_new_maven_message)
                .setView(box)
                .setPositiveButton(R.string.dialog_create, (d, w) -> {
                    String name = etName.getText().toString().trim();
                    if (name.isEmpty()) return;
                    String group = etGroup.getText().toString().trim();
                    String artifact = etArtifact.getText().toString().trim();
                    try {
                        File root = MavenProjectFactory.create(this, name, group, artifact,
                                jdk.selectedCode());
                        appPrefs.setProjectRoot(root.getAbsolutePath());
                        appPrefs.addRecentProject(root.getAbsolutePath());

                        Intent intent = new Intent(this, MainActivity.class);
                        startActivity(intent);
                        finish();
                    } catch (Exception e) {
                        Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }

    private void showGradleProjectForm() {
        int pad = dp(12);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad, pad, pad);

        EditText etName = newEditForDialog(getString(R.string.welcome_project_name_hint));
        EditText etGroup = newEditForDialog(getString(R.string.welcome_project_group_hint));
        JdkPicker jdk = new JdkPicker(this, theme, ProjectJdk.defaultForNewProject(this));

        box.addView(etName);
        box.addView(etGroup);
        box.addView(jdk.getView());

        newRoundedDialog()
                .setTitle(R.string.dialog_new_gradle_title)
                .setMessage(R.string.dialog_new_gradle_message)
                .setView(box)
                .setPositiveButton(R.string.dialog_create, (d, w) -> {
                    String name = etName.getText().toString().trim();
                    if (name.isEmpty()) return;
                    String group = etGroup.getText().toString().trim();
                    try {
                        File root = GradleProjectFactory.create(this, name, group);
                        // The template bakes in the global default because it is shared
                        // with the importer, which has no chooser; the choice made here
                        // replaces it in the file it was just written to.
                        ProjectJdk.set(this, root, jdk.selectedCode());
                        appPrefs.setProjectRoot(root.getAbsolutePath());
                        appPrefs.addRecentProject(root.getAbsolutePath());

                        Intent intent = new Intent(this, MainActivity.class);
                        startActivity(intent);
                        finish();
                    } catch (Exception e) {
                        Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }

    private void showKotlinProjectForm() {
        int pad = dp(12);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad, pad, pad);

        EditText etName = newEditForDialog(getString(R.string.welcome_project_name_hint));
        EditText etGroup = newEditForDialog(getString(R.string.welcome_project_group_hint));
        JdkPicker jdk = new JdkPicker(this, theme, ProjectJdk.defaultForNewProject(this));

        box.addView(etName);
        box.addView(etGroup);
        box.addView(jdk.getView());

        newRoundedDialog()
                .setTitle(R.string.dialog_new_kotlin_title)
                .setMessage(getString(R.string.dialog_new_kotlin_message,
                        KotlinProjectFactory.KOTLIN_VERSION))
                .setView(box)
                .setPositiveButton(R.string.dialog_create, (d, w) -> {
                    String name = etName.getText().toString().trim();
                    if (name.isEmpty()) return;
                    String group = etGroup.getText().toString().trim();
                    try {
                        File root = KotlinProjectFactory.create(this, name, group);
                        // Rewrites sourceCompatibility, targetCompatibility and the
                        // Kotlin jvmTarget together — the plugin fails the build when
                        // compileJava and compileKotlin name different levels.
                        ProjectJdk.set(this, root, jdk.selectedCode());
                        appPrefs.setProjectRoot(root.getAbsolutePath());
                        appPrefs.addRecentProject(root.getAbsolutePath());

                        Intent intent = new Intent(this, MainActivity.class);
                        startActivity(intent);
                        finish();
                    } catch (Exception e) {
                        Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }

    private void showBytecodeProjectForm() {
        int pad = dp(12);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad, pad, pad);

        EditText etName = newEditForDialog(getString(R.string.welcome_project_name_bytecode_hint));

        box.addView(etName);

        newRoundedDialog()
                .setTitle(R.string.dialog_new_bytecode_title)
                .setMessage(R.string.dialog_new_bytecode_message)
                .setView(box)
                .setPositiveButton(R.string.dialog_create, (d, w) -> {
                    String name = etName.getText().toString().trim();
                    if (name.isEmpty()) return;
                    try {
                        File root = BytecodeProjectFactory.create(this, name);
                        appPrefs.setProjectRoot(root.getAbsolutePath());
                        appPrefs.addRecentProject(root.getAbsolutePath());

                        Intent intent = new Intent(this, MainActivity.class);
                        startActivity(intent);
                        finish();
                    } catch (Exception e) {
                        Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }

    private EditText newEditForDialog(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(theme.textDim);
        e.setTextColor(theme.text);
        e.setBackgroundColor(Colors.blend(theme.bg, theme.text, 0.05f));
        e.setPadding(32, 16, 32, 16);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        e.setLayoutParams(lp);
        return e;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    /** @see Dialogs#rounded */
    private com.google.android.material.dialog.MaterialAlertDialogBuilder newRoundedDialog() {
        return Dialogs.rounded(this);
    }


    private boolean deleteRecursive(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        return file.delete();
    }

    // ── Adapter & ViewHolder implementation ──────────────────

    private interface ProjectClickListener {
        void onClick(String path);
    }

    /**
     * Opens a row's action menu, either under the ⋮ button or where the row was
     * pressed — a right-click and a long press both mean "here".
     */
    private interface ProjectOptionsListener {
        void onOptionsBelow(String path, View anchor);
        void onOptionsAt(String path, View row, float x, float y);
    }

    private static class RecentProjectsAdapter extends RecyclerView.Adapter<RecentProjectsAdapter.ViewHolder> {

        private final List<String> paths;
        private final AppTheme theme;
        private final ProjectClickListener clickListener;
        private final ProjectOptionsListener optionsListener;

        RecentProjectsAdapter(List<String> paths, AppTheme theme, ProjectClickListener clickListener, ProjectOptionsListener optionsListener) {
            this.paths = paths;
            this.theme = theme;
            this.clickListener = clickListener;
            this.optionsListener = optionsListener;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_recent_project, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            String path = paths.get(position);
            File file = new File(path);
            holder.projectName.setText(file.getName());
            holder.projectName.setTextColor(theme.text);
            holder.projectPath.setText(path);
            holder.projectPath.setTextColor(theme.textDim);
            holder.btnProjectOptions.setTextColor(theme.textDim);

            String name = file.getName();
            String avatarText = name.substring(0, Math.min(name.length(), 2)).toUpperCase(Locale.ROOT);
            holder.projectAvatar.setText(avatarText);

            // Give a random colored background to the avatar card
            int[] colors = {0xFF3574F0, 0xFFE74C3C, 0xFF2ECC71, 0xFFF1C40F, 0xFF9B59B6, 0xFF1ABC9C, 0xFFE67E22};
            int color = colors[Math.abs(name.hashCode()) % colors.length];
            GradientDrawable d = new GradientDrawable();
            d.setCornerRadius(18f); // fully rounded
            d.setColor(color);
            holder.projectAvatar.setBackground(d);

            holder.itemView.setOnClickListener(v -> clickListener.onClick(path));
            holder.btnProjectOptions.setOnClickListener(v ->
                    optionsListener.onOptionsBelow(path, holder.btnProjectOptions));

            // A mouse right-click, and a long press for a finger, open the same
            // menu as the ⋮ button. With a mouse — a Chromebook, a tablet with a
            // trackpad, DeX — right-click is the gesture people try first, and
            // the row used to have no answer to it.
            holder.itemView.setOnContextClickListener(v -> {
                optionsListener.onOptionsAt(path, holder.itemView,
                        holder.touch.x, holder.touch.y);
                return true;
            });
            holder.itemView.setOnLongClickListener(v -> {
                optionsListener.onOptionsAt(path, holder.itemView,
                        holder.touch.x, holder.touch.y);
                return true;
            });
        }

        @Override
        public int getItemCount() {
            return paths.size();
        }

        static class ViewHolder extends RecyclerView.ViewHolder {
            TextView projectAvatar;
            TextView projectName;
            TextView projectPath;
            TextView btnProjectOptions;
            /** Where this row was last touched, so its menu opens there. */
            final AnchoredMenu.TouchPoint touch;

            ViewHolder(@NonNull View view) {
                super(view);
                projectAvatar = view.findViewById(R.id.projectAvatar);
                projectName = view.findViewById(R.id.projectName);
                projectPath = view.findViewById(R.id.projectPath);
                btnProjectOptions = view.findViewById(R.id.btnProjectOptions);
                touch = AnchoredMenu.TouchPoint.track(view);
            }
        }
    }
}
