package com.ccs.wiredroid.git;

import com.ccs.wiredroid.util.Colors;
import com.ccs.wiredroid.R;
import com.ccs.wiredroid.ui.Dialogs;
import com.ccs.wiredroid.util.AppPreferences;
import com.ccs.wiredroid.util.AppTheme;
import com.ccs.wiredroid.util.FullScreenHelper;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Git UI: статус, коміт, історія, гілки та remote.
 * Будується програмно у поточній темі додатку.
 */
public class GitActivity extends AppCompatActivity {

    public static final String EXTRA_PROJECT_DIR = "project_dir";

    private static final int TAB_STATUS   = 0;
    private static final int TAB_COMMIT   = 1;
    private static final int TAB_LOG      = 2;
    private static final int TAB_BRANCHES = 3;
    private static final int TAB_REMOTE   = 4;
    private static final int TAB_DIFF     = 5;
    private static final int TAB_FORGE    = 6;

    private AppPreferences prefs;
    private AppTheme theme;
    /** Resolved once: the file/commit/branch lists below set it on every row. */
    private Typeface mono;
    private GitCredentialsStore creds;

    private File projectDir;
    private int  activeTab = TAB_STATUS;

    // Header
    private TextView headerBranch;
    private TextView headerStats;

    // Tab bar
    private TextView tStatus, tCommit, tLog, tBranches, tRemote, tDiff, tForge;
    private FrameLayout panel;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        prefs = new AppPreferences(this);
        theme = AppTheme.byId(prefs.getThemeId(), prefs);
        setTheme(theme.dark ? R.style.Theme_WireDroid : R.style.Theme_WireDroid_Light);

        super.onCreate(savedInstanceState);
        creds = new GitCredentialsStore(this);
        mono = prefs.resolveTypeface();

        String dir = getIntent().getStringExtra(EXTRA_PROJECT_DIR);
        if (dir == null) { finish(); return; }
        projectDir = new File(dir);

        setContentView(buildRoot());
        FullScreenHelper.enable(this);
        if (!GitManager.isGitRepo(projectDir)) {
            showInitOrCloneScreen();
        } else {
            switchTab(TAB_STATUS);
            refreshHeader();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    // ══════════════════════════════════════════════════════════
    //  Root layout
    // ══════════════════════════════════════════════════════════

    private View buildRoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(theme.bg);

        Toolbar toolbar = new Toolbar(this);
        toolbar.setBackgroundColor(theme.toolbar);
        toolbar.setTitle(R.string.git_activity_title);
        toolbar.setTitleTextColor(theme.text);
        toolbar.setNavigationIcon(R.drawable.ic_back);
        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        root.addView(toolbar);

        // Header
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(16), dp(8), dp(16), dp(8));
        header.setBackgroundColor(theme.toolbar);
        headerBranch = new TextView(this);
        headerBranch.setTextColor(theme.text);
        headerBranch.setTextSize(13);
        headerBranch.setTypeface(mono);
        headerStats = new TextView(this);
        headerStats.setTextColor(theme.textDim);
        headerStats.setTextSize(11);
        header.addView(headerBranch);
        header.addView(headerStats);
        root.addView(header);

        View sep = new View(this);
        sep.setBackgroundColor(theme.separator);
        sep.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        root.addView(sep);

        // Tabs
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setBackgroundColor(theme.toolbar);
        tStatus   = makeTab(getString(R.string.git_tab_status),   TAB_STATUS);
        tCommit   = makeTab(getString(R.string.git_tab_commit),   TAB_COMMIT);
        tLog      = makeTab(getString(R.string.git_tab_log),      TAB_LOG);
        tBranches = makeTab(getString(R.string.git_tab_branches), TAB_BRANCHES);
        tRemote   = makeTab(getString(R.string.git_tab_remote),   TAB_REMOTE);
        tDiff     = makeTab(getString(R.string.git_tab_diff),     TAB_DIFF);
        tForge    = makeTab(getString(R.string.git_tab_forge),    TAB_FORGE);
        tabs.addView(tStatus);
        tabs.addView(tCommit);
        tabs.addView(tLog);
        tabs.addView(tBranches);
        tabs.addView(tRemote);
        tabs.addView(tDiff);
        tabs.addView(tForge);
        hs.addView(tabs);
        root.addView(hs);

        View sep2 = new View(this);
        sep2.setBackgroundColor(theme.separator);
        sep2.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        root.addView(sep2);

        panel = new FrameLayout(this);
        panel.setBackgroundColor(theme.consoleBg);
        panel.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(panel);

        return root;
    }

    private TextView makeTab(String text, int id) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(theme.textDim);
        t.setTextSize(12);
        t.setPadding(dp(14), dp(10), dp(14), dp(10));
        t.setOnClickListener(v -> switchTab(id));
        return t;
    }

    private void switchTab(int id) {
        activeTab = id;
        int active   = Colors.blend(theme.toolbar, theme.bg, 0.4f);
        int inactive = theme.toolbar;
        tStatus.setBackgroundColor(id == TAB_STATUS ? active : inactive);
        tCommit.setBackgroundColor(id == TAB_COMMIT ? active : inactive);
        tLog.setBackgroundColor(id == TAB_LOG ? active : inactive);
        tBranches.setBackgroundColor(id == TAB_BRANCHES ? active : inactive);
        tRemote.setBackgroundColor(id == TAB_REMOTE ? active : inactive);
        tDiff.setBackgroundColor(id == TAB_DIFF ? active : inactive);
        tForge.setBackgroundColor(id == TAB_FORGE ? active : inactive);

        tStatus.setTextColor(id == TAB_STATUS ? theme.accent : theme.textDim);
        tCommit.setTextColor(id == TAB_COMMIT ? theme.accent : theme.textDim);
        tLog.setTextColor(id == TAB_LOG ? theme.accent : theme.textDim);
        tBranches.setTextColor(id == TAB_BRANCHES ? theme.accent : theme.textDim);
        tRemote.setTextColor(id == TAB_REMOTE ? theme.accent : theme.textDim);
        tDiff.setTextColor(id == TAB_DIFF ? theme.accent : theme.textDim);
        tForge.setTextColor(id == TAB_FORGE ? theme.accent : theme.textDim);

        panel.removeAllViews();
        switch (id) {
            case TAB_STATUS:   panel.addView(buildStatusPanel());   break;
            case TAB_COMMIT:   panel.addView(buildCommitPanel());   break;
            case TAB_LOG:      panel.addView(buildLogPanel());      break;
            case TAB_BRANCHES: panel.addView(buildBranchesPanel()); break;
            case TAB_REMOTE:   panel.addView(buildRemotePanel());   break;
            case TAB_DIFF:     panel.addView(buildDiffPanel());     break;
            case TAB_FORGE:    panel.addView(buildForgePanel());    break;
        }
    }

    // ══════════════════════════════════════════════════════════
    //  Init / Clone screen
    // ══════════════════════════════════════════════════════════

    private void showInitOrCloneScreen() {
        panel.removeAllViews();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(24), dp(48), dp(24), dp(24));

        TextView t = new TextView(this);
        t.setText(getString(R.string.git_no_repo, projectDir.getAbsolutePath()));
        t.setTextColor(theme.text);
        t.setTextSize(13);
        t.setGravity(Gravity.CENTER);
        box.addView(t);

        box.addView(spacer(dp(16)));

        TextView btnInit = primaryButton(getString(R.string.git_init));
        btnInit.setOnClickListener(v -> doBackground(
                () -> { GitManager.init(projectDir); return null; },
                ok  -> { Toast.makeText(this, R.string.git_init_done, Toast.LENGTH_SHORT).show();
                         switchTab(TAB_STATUS); refreshHeader(); },
                this::showError));
        box.addView(btnInit);

        box.addView(spacer(dp(8)));

        TextView btnClone = secondaryButton(getString(R.string.git_clone_into_dir));
        btnClone.setOnClickListener(v -> showCloneIntoCurrentDialog());
        box.addView(btnClone);

        box.addView(spacer(dp(8)));
        TextView btnCreateGitHub = secondaryButton("Create on GitHub");
        btnCreateGitHub.setOnClickListener(v -> showCreateOnGitHubDialog(true));
        box.addView(btnCreateGitHub);

        box.addView(spacer(dp(8)));
        TextView btnCreateGitLab = secondaryButton("Create on GitLab");
        btnCreateGitLab.setOnClickListener(v -> showCreateOnGitLabDialog(true));
        box.addView(btnCreateGitLab);

        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        panel.addView(sv);

        if (headerBranch != null) {
            headerBranch.setText("—");
            headerStats.setText(getString(R.string.git_no_repo_short));
        }
    }

    private void showCloneIntoCurrentDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        box.setPadding(p, p, p, p);

        EditText url = newEdit(getString(R.string.git_url_hint));
        EditText user = newEdit(getString(R.string.git_user_hint));
        EditText token = newEdit(getString(R.string.git_token_hint));
        token.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        CheckBox save = new CheckBox(this);
        save.setText(getString(R.string.git_save_creds));
        save.setTextColor(theme.text);
        box.addView(url);
        box.addView(user);
        box.addView(token);
        box.addView(save);

        Dialogs.rounded(this)
                .setTitle(R.string.git_clone)
                .setMessage(R.string.git_clone_message)
                .setView(box)
                .setPositiveButton(R.string.git_clone, (d, w) -> {
                    String u = url.getText().toString().trim();
                    if (!GitManager.isValidGitUrl(u)) {
                        Toast.makeText(this, R.string.git_invalid_url, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String un = user.getText().toString().trim();
                    String tk = token.getText().toString();
                    if (save.isChecked()) creds.save(u, un, tk);
                    // Клонуємо у тимчасову папку, потім переносимо вміст у projectDir
                    File tmp = new File(getCacheDir(), "git_clone_" + System.currentTimeMillis());
                    doBackground(() -> {
                        if (tmp.exists()) deleteRecursive(tmp);
                        GitManager.clone(u, tmp, un, tk, null);
                        // Переносимо у projectDir (зберігаючи його шлях)
                        if (!projectDir.exists()) projectDir.mkdirs();
                        moveAllChildren(tmp, projectDir);
                        deleteRecursive(tmp);
                        return null;
                    }, ok -> {
                        Toast.makeText(this, R.string.git_clone_done, Toast.LENGTH_SHORT).show();
                        switchTab(TAB_STATUS);
                        refreshHeader();
                    }, this::showError);
                })
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }
    private void showCreateOnGitHubDialog(boolean doInitLocal) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        box.setPadding(p, p, p, p);

        EditText name = newEdit("Repository Name");
        name.setText(projectDir.getName());
        
        CheckBox isPrivate = new CheckBox(this);
        isPrivate.setText("Private Repository");
        isPrivate.setTextColor(theme.text);
        
        EditText token = newEdit(getString(R.string.git_token_hint));
        token.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        token.setText(creds.token("https://github.com/")); // Try to load existing GitHub token
        
        CheckBox save = new CheckBox(this);
        save.setText(getString(R.string.git_save_creds));
        save.setTextColor(theme.text);
        save.setChecked(true);
        
        box.addView(name);
        box.addView(isPrivate);
        box.addView(spacer(dp(8)));
        box.addView(token);
        box.addView(save);

        Dialogs.rounded(this)
                .setTitle("Create on GitHub")
                .setView(box)
                .setPositiveButton("Create", (d, w) -> {
                    String repoName = name.getText().toString().trim();
                    String tk = token.getText().toString().trim();
                    boolean priv = isPrivate.isChecked();
                    if (repoName.isEmpty() || tk.isEmpty()) {
                        Toast.makeText(this, "Name and Token are required", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (save.isChecked()) {
                        // Dummy URL just to store the GitHub token
                        creds.save("https://github.com/", "", tk);
                    }
                    
                    doBackground(() -> {
                        String cloneUrl = GitHubApiClient.createRepo(repoName, tk, priv);
                        if (doInitLocal) {
                            GitManager.init(projectDir);
                        }
                        GitManager.setRemoteOrigin(projectDir, cloneUrl);
                        // Save the token for the specific repo URL so pull/push works seamlessly
                        creds.save(cloneUrl, "", tk);
                        return cloneUrl;
                    }, url -> {
                        Toast.makeText(this, "Created: " + url, Toast.LENGTH_LONG).show();
                        switchTab(TAB_STATUS);
                        refreshHeader();
                    }, this::showError);
                })
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }

    private void showCreateOnGitLabDialog(boolean doInitLocal) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        box.setPadding(p, p, p, p);

        EditText etBaseUrl = newEdit("GitLab URL (e.g. https://gitlab.com)");
        etBaseUrl.setText("https://gitlab.com");

        EditText name = newEdit("Project Name");
        name.setText(projectDir.getName());

        CheckBox isPrivate = new CheckBox(this);
        isPrivate.setText("Private Project");
        isPrivate.setTextColor(theme.text);

        EditText token = newEdit("Personal Access Token");
        token.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        token.setText(creds.token("https://gitlab.com/"));

        CheckBox save = new CheckBox(this);
        save.setText(getString(R.string.git_save_creds));
        save.setTextColor(theme.text);
        save.setChecked(true);

        box.addView(etBaseUrl);
        box.addView(name);
        box.addView(isPrivate);
        box.addView(spacer(dp(8)));
        box.addView(token);
        box.addView(save);

        Dialogs.rounded(this)
                .setTitle("Create on GitLab")
                .setView(box)
                .setPositiveButton("Create", (d, w) -> {
                    String baseUrl = etBaseUrl.getText().toString().trim();
                    String repoName = name.getText().toString().trim();
                    String tk = token.getText().toString().trim();
                    boolean priv = isPrivate.isChecked();
                    if (repoName.isEmpty() || tk.isEmpty()) {
                        Toast.makeText(this, "Name and Token are required", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (save.isChecked()) {
                        creds.save(baseUrl, "", tk);
                    }

                    doBackground(() -> {
                        GitLabApiClient client = new GitLabApiClient(baseUrl, tk);
                        String cloneUrl = client.createProject(repoName, priv);
                        if (doInitLocal) {
                            GitManager.init(projectDir);
                        }
                        GitManager.setRemoteOrigin(projectDir, cloneUrl);
                        creds.save(cloneUrl, "", tk);
                        return cloneUrl;
                    }, url -> {
                        Toast.makeText(this, "Created: " + url, Toast.LENGTH_LONG).show();
                        switchTab(TAB_STATUS);
                        refreshHeader();
                    }, this::showError);
                })
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }


    // ══════════════════════════════════════════════════════════
    //  Status panel
    // ══════════════════════════════════════════════════════════

    private View buildStatusPanel() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(8), dp(12), dp(8));

        TextView loading = new TextView(this);
        loading.setText(getString(R.string.git_loading));
        loading.setTextColor(theme.textDim);
        loading.setTextSize(12);
        box.addView(loading);

        ScrollView sv = new ScrollView(this);
        sv.addView(box);

        doBackground(() -> GitManager.status(projectDir), s -> {
            box.removeAllViews();
            if (s.isEmpty()) {
                TextView ok = new TextView(this);
                ok.setText(getString(R.string.git_clean));
                ok.setTextColor(theme.successText);
                ok.setTextSize(13);
                ok.setPadding(0, dp(16), 0, 0);
                ok.setGravity(Gravity.CENTER);
                box.addView(ok);
            } else {
                addStatusGroup(box, getString(R.string.git_grp_staged), unionAll(s.added, s.changed, s.removed),
                        theme.successText, true);
                addStatusGroup(box, getString(R.string.git_grp_modified), unionAll(s.modified, s.missing),
                        theme.accent, false);
                addStatusGroup(box, getString(R.string.git_grp_untracked), s.untracked,
                        theme.textDim, false);
                addStatusGroup(box, getString(R.string.git_grp_conflict), s.conflicting,
                        theme.errorText, false, true);

                if (!s.conflicting.isEmpty() && GitManager.isRebasing(projectDir)) {
                    box.addView(spacer(dp(8)));
                    TextView continueRebase = secondaryButton(getString(R.string.git_rebase_continue));
                    continueRebase.setOnClickListener(v -> doBackground(
                            () -> GitManager.continueRebase(projectDir),
                            output -> { showOutputDialog(getString(R.string.git_rebase), output);
                                switchTab(TAB_STATUS); }, this::showError));
                    box.addView(continueRebase);
                    box.addView(spacer(dp(6)));
                    TextView abortRebase = secondaryButton(getString(R.string.git_rebase_abort));
                    abortRebase.setOnClickListener(v -> Dialogs.rounded(this)
                            .setTitle(R.string.git_rebase_abort)
                            .setMessage(R.string.git_rebase_abort_confirm)
                            .setPositiveButton(R.string.git_rebase_abort, (d, w) -> doBackground(
                                    () -> GitManager.abortRebase(projectDir),
                                    output -> { showOutputDialog(getString(R.string.git_rebase), output);
                                        switchTab(TAB_STATUS); }, this::showError))
                            .setNegativeButton(R.string.dialog_cancel, null).show());
                    box.addView(abortRebase);
                }

                box.addView(spacer(dp(12)));

                TextView btnAddAll = primaryButton(getString(R.string.git_stage_all));
                btnAddAll.setOnClickListener(v -> doBackground(() -> {
                    GitManager.addAll(projectDir); return null;
                }, ok -> switchTab(TAB_STATUS), this::showError));
                box.addView(btnAddAll);
            }
        }, this::showError);

        return sv;
    }

    private void addStatusGroup(LinearLayout box, String title, Set<String> files,
                                int color, boolean staged) {
        addStatusGroup(box, title, files, color, staged, false);
    }

    private void addStatusGroup(LinearLayout box, String title, Set<String> files,
                                int color, boolean staged, boolean conflicts) {
        if (files == null || files.isEmpty()) return;
        TextView t = new TextView(this);
        t.setText(title + " (" + files.size() + ")");
        t.setTextColor(color);
        t.setTextSize(12);
        t.setLetterSpacing(0.06f);
        t.setAllCaps(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        t.setLayoutParams(lp);
        box.addView(t);

        for (String f : files) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(4), 0, dp(4));

            TextView name = new TextView(this);
            name.setText(f);
            name.setTextColor(theme.text);
            name.setTextSize(12);
            name.setTypeface(mono);
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            name.setLayoutParams(nlp);
            if (conflicts) {
                name.setTextColor(theme.errorText);
                name.setOnClickListener(v -> {
                    Intent intent = new Intent(this, GitConflictEditorActivity.class);
                    intent.putExtra(GitConflictEditorActivity.EXTRA_PROJECT_DIR,
                            projectDir.getAbsolutePath());
                    intent.putExtra(GitConflictEditorActivity.EXTRA_PATH, f);
                    startActivity(intent);
                });
            }
            row.addView(name);

            TextView act = new TextView(this);
            act.setText(conflicts ? getString(R.string.git_conflict_result)
                    : staged ? getString(R.string.git_unstage) : getString(R.string.git_stage));
            act.setTextColor(theme.accent);
            act.setTextSize(11);
            act.setPadding(dp(8), dp(4), dp(8), dp(4));
            act.setOnClickListener(v -> {
                if (conflicts) {
                    name.performClick();
                } else {
                    doBackground(() -> {
                        if (staged) GitManager.unstagePath(projectDir, f);
                        else GitManager.addPath(projectDir, f);
                        return null;
                    }, ok -> switchTab(TAB_STATUS), this::showError);
                }
            });
            row.addView(act);

            box.addView(row);
        }
    }

    @SafeVarargs
    private final Set<String> unionAll(Set<String>... sets) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        for (Set<String> s : sets) if (s != null) out.addAll(s);
        return out;
    }

    // ══════════════════════════════════════════════════════════
    //  Commit panel
    // ══════════════════════════════════════════════════════════

    private View buildCommitPanel() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(12), dp(12), dp(12));

        TextView lbl = new TextView(this);
        lbl.setText(getString(R.string.git_commit_message));
        lbl.setTextColor(theme.text);
        lbl.setTextSize(12);
        box.addView(lbl);

        EditText msg = new EditText(this);
        msg.setHint(R.string.git_commit_hint);
        msg.setHintTextColor(theme.textDim);
        msg.setTextColor(theme.text);
        msg.setBackgroundColor(Colors.blend(theme.bg, theme.toolbar, 0.5f));
        msg.setPadding(dp(8), dp(8), dp(8), dp(8));
        msg.setMinLines(3);
        msg.setGravity(Gravity.TOP | Gravity.START);
        msg.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.topMargin = dp(6);
        msg.setLayoutParams(mlp);
        box.addView(msg);

        // Author identity
        box.addView(spacer(dp(12)));
        TextView whoLbl = new TextView(this);
        whoLbl.setText(getString(R.string.git_author_identity));
        whoLbl.setTextColor(theme.textDim);
        whoLbl.setTextSize(11);
        box.addView(whoLbl);
        EditText name = newEdit(getString(R.string.git_author_name));
        name.setText(creds.authorName());
        EditText email = newEdit(getString(R.string.git_author_email));
        email.setText(creds.authorEmail());
        box.addView(name);
        box.addView(email);

        box.addView(spacer(dp(12)));

        CheckBox stageAll = new CheckBox(this);
        stageAll.setText(getString(R.string.git_stage_all_first));
        stageAll.setTextColor(theme.text);
        stageAll.setChecked(true);
        box.addView(stageAll);

        box.addView(spacer(dp(8)));

        TextView btnCommit = primaryButton(getString(R.string.git_commit));
        btnCommit.setOnClickListener(v -> {
            String m = msg.getText().toString().trim();
            if (m.isEmpty()) {
                Toast.makeText(this, R.string.git_empty_message, Toast.LENGTH_SHORT).show();
                return;
            }
            String n = name.getText().toString().trim();
            String e = email.getText().toString().trim();
            creds.saveAuthor(n, e);
            boolean addAll = stageAll.isChecked();
            doBackground(() -> {
                if (addAll) GitManager.addAll(projectDir);
                return GitManager.commit(projectDir, m, n, e);
            }, c -> {
                Toast.makeText(this, getString(R.string.git_commit_done,
                        c == null ? "?" : c.getName().substring(0, 7)),
                        Toast.LENGTH_SHORT).show();
                msg.setText("");
                refreshHeader();
            }, this::showError);
        });
        box.addView(btnCommit);

        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        return sv;
    }

    // ══════════════════════════════════════════════════════════
    //  Log panel
    // ══════════════════════════════════════════════════════════

    private View buildLogPanel() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(12), dp(12), dp(12));

        TextView loading = new TextView(this);
        loading.setText(getString(R.string.git_loading));
        loading.setTextColor(theme.textDim);
        loading.setTextSize(12);
        box.addView(loading);

        ScrollView sv = new ScrollView(this);
        sv.addView(box);

        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
        doBackground(() -> {
            if (!GitManager.hasHead(projectDir)) return new ArrayList<GitManager.CommitInfo>();
            return GitManager.log(projectDir, 100);
        }, list -> {
            box.removeAllViews();
            if (list.isEmpty()) {
                TextView t = new TextView(this);
                t.setText(getString(R.string.git_no_commits));
                t.setTextColor(theme.textDim);
                t.setTextSize(13);
                t.setGravity(Gravity.CENTER);
                t.setPadding(0, dp(24), 0, 0);
                box.addView(t);
                return;
            }
            for (GitManager.CommitInfo c : list) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(0, dp(8), 0, dp(8));

                TextView header = new TextView(this);
                header.setText(c.shortHash + "  " + c.message);
                header.setTextColor(theme.text);
                header.setTextSize(12);
                header.setTypeface(mono);
                row.addView(header);

                TextView meta = new TextView(this);
                meta.setText(c.author + " · " + fmt.format(new Date(c.timeMs)));
                meta.setTextColor(theme.textDim);
                meta.setTextSize(10);
                row.addView(meta);

                box.addView(row);

                View sep = new View(this);
                sep.setBackgroundColor(theme.separator);
                sep.setLayoutParams(new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
                box.addView(sep);
            }
        }, this::showError);

        return sv;
    }

    // ══════════════════════════════════════════════════════════
    //  Branches panel
    // ══════════════════════════════════════════════════════════

    private View buildBranchesPanel() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(12), dp(12), dp(12));

        TextView loading = new TextView(this);
        loading.setText(getString(R.string.git_loading));
        loading.setTextColor(theme.textDim);
        loading.setTextSize(12);
        box.addView(loading);

        ScrollView sv = new ScrollView(this);
        sv.addView(box);

        TextView btnNew = secondaryButton(getString(R.string.git_branch_new));
        btnNew.setOnClickListener(v -> showCreateBranchDialog());

        doBackground(() -> GitManager.branches(projectDir), list -> {
            box.removeAllViews();
            box.addView(btnNew);
            box.addView(operationButton(R.string.git_merge, () -> promptRevision(
                    R.string.git_merge, value -> GitManager.merge(projectDir, value))));
            box.addView(operationButton(R.string.git_rebase, () -> promptRevision(
                    R.string.git_rebase, value -> GitManager.rebase(projectDir, value))));
            box.addView(operationButton(R.string.git_cherry_pick, () -> promptRevision(
                    R.string.git_cherry_pick, value -> GitManager.cherryPick(projectDir, value))));
            box.addView(operationButton(R.string.git_stashes, this::showStashesDialog));
            box.addView(operationButton(R.string.git_tags, this::showTagsDialog));
            box.addView(spacer(dp(12)));

            if (list.isEmpty()) {
                TextView t = new TextView(this);
                t.setText(getString(R.string.git_no_branches));
                t.setTextColor(theme.textDim);
                t.setTextSize(13);
                box.addView(t);
                return;
            }
            for (GitManager.BranchInfo b : list) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(0, dp(8), 0, dp(8));

                TextView name = new TextView(this);
                String label = (b.current ? "● " : "  ") + b.name + (b.remote ? "  (remote)" : "");
                name.setText(label);
                name.setTextColor(b.current ? theme.successText : theme.text);
                name.setTextSize(12);
                name.setTypeface(mono);
                LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                name.setLayoutParams(nlp);
                row.addView(name);

                if (!b.current && !b.remote) {
                    TextView delete = new TextView(this);
                    delete.setText("×");
                    delete.setTextColor(theme.errorText);
                    delete.setTextSize(16);
                    delete.setPadding(dp(8), 0, dp(8), 0);
                    delete.setOnClickListener(v -> doBackground(() -> {
                        GitManager.deleteBranch(projectDir, b.name); return null;
                    }, ok -> switchTab(TAB_BRANCHES), this::showError));
                    row.addView(delete);
                }

                row.setOnClickListener(v -> {
                    if (b.current) return;
                    doBackground(() -> {
                        GitManager.checkoutBranch(projectDir, b.name); return null;
                    }, ok -> {
                        switchTab(TAB_BRANCHES);
                        refreshHeader();
                        Toast.makeText(this, getString(R.string.git_checkout_done, b.name),
                                Toast.LENGTH_SHORT).show();
                    }, this::showError);
                });
                box.addView(row);
            }
        }, this::showError);

        return sv;
    }

    private void showCreateBranchDialog() {
        EditText input = newEdit(getString(R.string.git_branch_name_hint));
        Dialogs.rounded(this)
                .setTitle(R.string.git_branch_new)
                .setView(input)
                .setPositiveButton(R.string.dialog_create, (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) return;
                    doBackground(() -> {
                        GitManager.createBranch(projectDir, name);
                        GitManager.checkoutBranch(projectDir, name);
                        return null;
                    }, ok -> {
                        switchTab(TAB_BRANCHES);
                        refreshHeader();
                    }, this::showError);
                })
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }

    private interface RevisionOperation { String run(String revision) throws Exception; }

    private TextView operationButton(int label, Runnable action) {
        TextView button = secondaryButton(getString(label));
        button.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(6);
        button.setLayoutParams(params);
        return button;
    }

    private void promptRevision(int title, RevisionOperation operation) {
        EditText input = newEdit(getString(R.string.git_revision_hint));
        Dialogs.rounded(this).setTitle(title).setView(input)
                .setPositiveButton(title, (dialog, which) -> {
                    String value = input.getText().toString().trim();
                    if (value.isEmpty()) return;
                    doBackground(() -> operation.run(value), output -> {
                        showOutputDialog(getString(title), output);
                        refreshHeader();
                        switchTab(TAB_STATUS);
                    }, this::showError);
                })
                .setNegativeButton(R.string.dialog_cancel, null).show();
    }

    private void showStashesDialog() {
        doBackground(() -> GitManager.stashes(projectDir), stashes -> {
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(dp(12), dp(8), dp(12), dp(8));
            EditText message = newEdit(getString(R.string.git_stash_message_hint));
            box.addView(message);
            TextView create = primaryButton(getString(R.string.git_stash_create));
            create.setOnClickListener(v -> doBackground(
                    () -> GitManager.createStash(projectDir, message.getText().toString()),
                    ignored -> showStashesDialog(), this::showError));
            box.addView(create);
            for (int i = 0; i < stashes.size(); i++) {
                GitManager.StashInfo stash = stashes.get(i);
                int index = i;
                TextView row = secondaryButton("stash@{" + i + "}  " + stash.shortId + "  " + stash.message);
                row.setOnClickListener(v -> Dialogs.rounded(this)
                        .setTitle(stash.message)
                        .setItems(new String[]{getString(R.string.git_stash_apply),
                                        getString(R.string.git_stash_drop)},
                                (d, selected) -> doBackground(() -> {
                                    if (selected == 0) GitManager.applyStash(projectDir, stash.id);
                                    else GitManager.dropStash(projectDir, index);
                                    return null;
                                }, ignored -> switchTab(TAB_STATUS), this::showError))
                        .show());
                box.addView(row);
            }
            new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle(R.string.git_stashes).setView(box)
                    .setNegativeButton(R.string.dialog_cancel, null).show();
        }, this::showError);
    }

    private void showTagsDialog() {
        doBackground(() -> GitManager.tags(projectDir), tags -> {
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(dp(12), dp(8), dp(12), dp(8));
            EditText name = newEdit(getString(R.string.git_tag_name_hint));
            EditText message = newEdit(getString(R.string.git_tag_message_hint));
            box.addView(name);
            box.addView(message);
            TextView create = primaryButton(getString(R.string.git_tag_create));
            create.setOnClickListener(v -> doBackground(() -> {
                GitManager.createTag(projectDir, name.getText().toString().trim(),
                        message.getText().toString());
                return null;
            }, ignored -> showTagsDialog(), this::showError));
            box.addView(create);
            for (GitManager.TagInfo tag : tags) {
                TextView row = secondaryButton(tag.name + "  " +
                        (tag.objectId.length() > 7 ? tag.objectId.substring(0, 7) : tag.objectId));
                row.setOnLongClickListener(v -> {
                    doBackground(() -> { GitManager.deleteTag(projectDir, tag.name); return null; },
                            ignored -> showTagsDialog(), this::showError);
                    return true;
                });
                box.addView(row);
            }
            new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle(R.string.git_tags).setView(box)
                    .setMessage(R.string.git_tag_delete_hint)
                    .setNegativeButton(R.string.dialog_cancel, null).show();
        }, this::showError);
    }

    // ══════════════════════════════════════════════════════════
    //  Remote panel
    // ══════════════════════════════════════════════════════════

    private View buildRemotePanel() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(12), dp(12), dp(12));

        String currentUrl = "";
        try { currentUrl = GitManager.remoteOriginUrl(projectDir); } catch (Exception ignored) {}
        if (currentUrl == null) currentUrl = "";

        TextView lbl = new TextView(this);
        lbl.setText(getString(R.string.git_remote_origin));
        lbl.setTextColor(theme.text);
        lbl.setTextSize(12);
        box.addView(lbl);

        EditText url = newEdit(getString(R.string.git_url_hint));
        url.setText(currentUrl);
        box.addView(url);

        box.addView(spacer(dp(8)));

        EditText user = newEdit(getString(R.string.git_user_hint));
        user.setText(creds.username(currentUrl));
        EditText token = newEdit(getString(R.string.git_token_hint));
        token.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        token.setText(creds.token(currentUrl));
        box.addView(user);
        box.addView(token);

        TextView hint = new TextView(this);
        hint.setText(getString(R.string.git_token_hint_long));
        hint.setTextColor(theme.textDim);
        hint.setTextSize(10);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = dp(4);
        hint.setLayoutParams(hlp);
        box.addView(hint);

        box.addView(spacer(dp(16)));

        TextView btnSave = secondaryButton(getString(R.string.git_save_remote));
        btnSave.setOnClickListener(v -> {
            String u = url.getText().toString().trim();
            if (!GitManager.isValidGitUrl(u)) {
                Toast.makeText(this, R.string.git_invalid_url, Toast.LENGTH_SHORT).show();
                return;
            }
            String un = user.getText().toString().trim();
            String tk = token.getText().toString();
            creds.save(u, un, tk);
            doBackground(() -> { GitManager.setRemoteOrigin(projectDir, u); return null; },
                    ok -> Toast.makeText(this, R.string.git_saved, Toast.LENGTH_SHORT).show(),
                    this::showError);
        });
        box.addView(btnSave);

        box.addView(spacer(dp(8)));
        
        TextView btnCreateGitHub = secondaryButton("Create new on GitHub");
        btnCreateGitHub.setOnClickListener(v -> showCreateOnGitHubDialog(false)); // doInitLocal=false since repo exists
        box.addView(btnCreateGitHub);

        box.addView(spacer(dp(8)));

        TextView btnPull = primaryButton(getString(R.string.git_pull));
        btnPull.setOnClickListener(v -> {
            String u = url.getText().toString().trim();
            String un = user.getText().toString().trim();
            String tk = token.getText().toString();
            doBackground(() -> GitManager.pull(projectDir, un, tk),
                    out -> {
                        showOutputDialog(getString(R.string.git_pull), out);
                        refreshHeader();
                    },
                    this::showError);
        });
        box.addView(btnPull);

        box.addView(spacer(dp(8)));

        TextView btnPush = primaryButton(getString(R.string.git_push));
        btnPush.setOnClickListener(v -> {
            String u = url.getText().toString().trim();
            String un = user.getText().toString().trim();
            String tk = token.getText().toString();
            if (!u.isEmpty()) {
                doBackground(() -> {
                    GitManager.setRemoteOrigin(projectDir, u);
                    creds.save(u, un, tk);
                    return GitManager.push(projectDir, un, tk);
                }, out -> {
                    showOutputDialog(getString(R.string.git_push), out);
                    refreshHeader();
                }, this::showError);
            }
        });
        box.addView(btnPush);

        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        return sv;
    }

    // ══════════════════════════════════════════════════════════
    //  Diff panel
    // ══════════════════════════════════════════════════════════

    private View buildDiffPanel() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(8), dp(12), dp(8));

        TextView loading = new TextView(this);
        loading.setText(getString(R.string.git_diff_loading));
        loading.setTextColor(theme.textDim);
        loading.setTextSize(12);
        box.addView(loading);

        ScrollView sv = new ScrollView(this);
        sv.addView(box);

        doBackground(() -> {
            List<String> files = GitManager.changedFiles(projectDir);
            String diff = GitManager.diffWorkingTree(projectDir);
            return new Object[]{files, diff};
        }, result -> {
            @SuppressWarnings("unchecked")
            List<String> files = (List<String>) ((Object[]) result)[0];
            String diff = (String) ((Object[]) result)[1];
            box.removeAllViews();

            if (files.isEmpty()) {
                TextView ok = new TextView(this);
                ok.setText(getString(R.string.git_diff_no_changes));
                ok.setTextColor(theme.successText);
                ok.setTextSize(13);
                ok.setGravity(Gravity.CENTER);
                ok.setPadding(0, dp(16), 0, 0);
                box.addView(ok);
                return;
            }

            // Button to open full diff viewer
            TextView btnFullDiff = primaryButton(getString(R.string.git_diff_open_full));
            btnFullDiff.setOnClickListener(v ->
                    GitDiffActivity.launch(this, projectDir));
            box.addView(btnFullDiff);
            box.addView(spacer(dp(8)));

            // Show file list with +/- stats
            for (String f : files) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(4), dp(6), dp(4), dp(6));

                TextView name = new TextView(this);
                name.setText(f);
                name.setTextColor(theme.text);
                name.setTextSize(12);
                name.setTypeface(mono);
                LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                name.setLayoutParams(nlp);
                row.addView(name);

                // Inline stats
                TextView stats = new TextView(this);
                stats.setTypeface(mono);
                stats.setTextSize(10);
                stats.setPadding(dp(8), 0, dp(8), 0);
                row.addView(stats);

                // View diff button
                TextView viewBtn = new TextView(this);
                viewBtn.setText("→");
                viewBtn.setTextColor(theme.accent);
                viewBtn.setTextSize(14);
                viewBtn.setPadding(dp(4), 0, dp(4), 0);
                viewBtn.setOnClickListener(v ->
                        GitDiffActivity.launchFile(this, projectDir, f));
                row.addView(viewBtn);

                box.addView(row);

                View sep = new View(this);
                sep.setBackgroundColor(theme.separator);
                sep.setLayoutParams(new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
                box.addView(sep);

                // Load stats in background
                doBackground(() -> GitManager.diffStats(projectDir, f), s -> {
                    stats.setText("+" + s[0] + " -" + s[1]);
                    if (s[0] > 0 && s[1] == 0) stats.setTextColor(theme.successText);
                    else if (s[1] > 0 && s[0] == 0) stats.setTextColor(theme.errorText);
                    else stats.setTextColor(theme.accent);
                }, e -> stats.setText(""));
            }
        }, this::showError);

        return sv;
    }

    // ══════════════════════════════════════════════════════════
    //  GitHub / GitLab forge
    // ══════════════════════════════════════════════════════════

    private View buildForgePanel() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(10), dp(12), dp(10));
        TextView description = new TextView(this);
        description.setText(R.string.git_forge_description);
        description.setTextColor(theme.textDim);
        description.setTextSize(11);
        box.addView(description);
        box.addView(spacer(dp(10)));
        TextView prs = primaryButton(getString(R.string.git_pull_requests));
        prs.setOnClickListener(v -> loadForgeItems(true));
        box.addView(prs);
        box.addView(spacer(dp(7)));
        TextView create = secondaryButton(getString(R.string.git_pr_create));
        create.setOnClickListener(v -> showCreatePullRequestDialog());
        box.addView(create);
        box.addView(spacer(dp(7)));
        TextView issues = secondaryButton(getString(R.string.git_issues));
        issues.setOnClickListener(v -> loadForgeItems(false));
        box.addView(issues);
        box.addView(spacer(dp(7)));
        TextView ci = secondaryButton(getString(R.string.git_ci_status));
        ci.setOnClickListener(v -> loadPipelines());
        box.addView(ci);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);
        return scroll;
    }

    private GitForgeClient forgeClient() throws Exception {
        String origin = GitManager.remoteOriginUrl(projectDir);
        return new GitForgeClient(origin, creds.token(origin));
    }

    private void loadForgeItems(boolean pullRequests) {
        doBackground(() -> {
            GitForgeClient client = forgeClient();
            return new Object[]{client.provider(), pullRequests ? client.pullRequests() : client.issues()};
        }, result -> {
            @SuppressWarnings("unchecked")
            List<GitForgeClient.Item> items = (List<GitForgeClient.Item>) result[1];
            LinearLayout list = forgeListContainer();
            if (items.isEmpty()) list.addView(emptyLabel(getString(R.string.git_forge_empty)));
            for (GitForgeClient.Item item : items) list.addView(forgeItemRow(item, pullRequests));
            panel.removeAllViews();
            ScrollView scroll = new ScrollView(this);
            scroll.addView(list);
            panel.addView(scroll);
        }, this::showError);
    }

    private LinearLayout forgeListContainer() {
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(12), dp(10), dp(12), dp(10));
        TextView back = secondaryButton(getString(R.string.git_forge_back));
        back.setOnClickListener(v -> switchTab(TAB_FORGE));
        list.addView(back);
        list.addView(spacer(dp(8)));
        return list;
    }

    private TextView forgeItemRow(GitForgeClient.Item item, boolean reviewable) {
        TextView row = secondaryButton("#" + item.number + "  " + item.title + "\n" +
                item.state + " · " + item.author);
        row.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        row.setOnClickListener(v -> showForgeItem(item, reviewable));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(7);
        row.setLayoutParams(params);
        return row;
    }

    private TextView emptyLabel(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(theme.textDim);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(8), dp(24), dp(8), dp(24));
        return view;
    }

    private void showForgeItem(GitForgeClient.Item item, boolean reviewable) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(8), dp(12), dp(8));
        TextView body = new TextView(this);
        body.setText(item.body == null || item.body.isEmpty() ? getString(R.string.git_no_description) : item.body);
        body.setTextColor(theme.text);
        body.setTextIsSelectable(true);
        box.addView(body);
        box.addView(spacer(dp(8)));
        TextView open = secondaryButton(getString(R.string.git_open_browser));
        open.setOnClickListener(v -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(item.url))));
        box.addView(open);
        if (reviewable) {
            TextView diff = secondaryButton(getString(R.string.git_pr_view_diff));
            diff.setOnClickListener(v -> doBackground(
                    () -> forgeClient().pullRequestDiff(item.number),
                    text -> GitDiffActivity.launchText(this, text,
                            "#" + item.number + " " + item.title), this::showError));
            box.addView(diff);
            EditText comment = newEdit(getString(R.string.git_review_comment_hint));
            comment.setSingleLine(false);
            comment.setMinLines(3);
            box.addView(comment);
            TextView send = primaryButton(getString(R.string.git_review_send));
            send.setOnClickListener(v -> doBackground(() -> {
                forgeClient().addReviewComment(item.number, comment.getText().toString().trim());
                return null;
            }, ignored -> Toast.makeText(this, R.string.git_review_sent, Toast.LENGTH_SHORT).show(),
                    this::showError));
            box.addView(send);

            TextView inlineTitle = new TextView(this);
            inlineTitle.setText(R.string.git_inline_comment);
            inlineTitle.setTextColor(theme.textDim);
            inlineTitle.setPadding(0, dp(12), 0, 0);
            box.addView(inlineTitle);
            EditText path = newEdit(getString(R.string.git_inline_path_hint));
            EditText line = newEdit(getString(R.string.git_inline_line_hint));
            line.setInputType(InputType.TYPE_CLASS_NUMBER);
            EditText commit = newEdit(getString(R.string.git_inline_commit_hint));
            EditText inlineBody = newEdit(getString(R.string.git_review_comment_hint));
            inlineBody.setSingleLine(false);
            inlineBody.setMinLines(2);
            box.addView(path); box.addView(line); box.addView(commit); box.addView(inlineBody);
            TextView sendInline = secondaryButton(getString(R.string.git_inline_send));
            sendInline.setOnClickListener(v -> {
                int lineNumber;
                try { lineNumber = Integer.parseInt(line.getText().toString()); }
                catch (NumberFormatException e) { Toast.makeText(this,
                        R.string.git_inline_invalid_line, Toast.LENGTH_SHORT).show(); return; }
                int finalLine = lineNumber;
                doBackground(() -> {
                    forgeClient().addInlineComment(item.number, inlineBody.getText().toString().trim(),
                            path.getText().toString().trim(), finalLine,
                            commit.getText().toString().trim());
                    return null;
                }, ignored -> Toast.makeText(this, R.string.git_review_sent,
                        Toast.LENGTH_SHORT).show(), this::showError);
            });
            box.addView(sendInline);
        }
        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);
        Dialogs.rounded(this).setTitle("#" + item.number + " " + item.title)
                .setView(scroll).setNegativeButton(R.string.dialog_cancel, null).show();
    }

    private void showCreatePullRequestDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(8), dp(12), dp(8));
        EditText title = newEdit(getString(R.string.git_pr_title_hint));
        EditText source = newEdit(getString(R.string.git_pr_source_hint));
        try { source.setText(GitManager.currentBranch(projectDir)); } catch (Exception ignored) {}
        EditText target = newEdit(getString(R.string.git_pr_target_hint));
        target.setText("main");
        EditText body = newEdit(getString(R.string.git_pr_body_hint));
        body.setSingleLine(false);
        body.setMinLines(3);
        box.addView(title); box.addView(source); box.addView(target); box.addView(body);
        Dialogs.rounded(this).setTitle(R.string.git_pr_create).setView(box)
                .setPositiveButton(R.string.dialog_create, (d, w) -> doBackground(
                        () -> forgeClient().createPullRequest(title.getText().toString().trim(),
                                body.getText().toString(), source.getText().toString().trim(),
                                target.getText().toString().trim()),
                        item -> showForgeItem(item, true), this::showError))
                .setNegativeButton(R.string.dialog_cancel, null).show();
    }

    private void loadPipelines() {
        doBackground(() -> forgeClient().pipelines(), pipelines -> {
            LinearLayout list = forgeListContainer();
            if (pipelines.isEmpty()) list.addView(emptyLabel(getString(R.string.git_forge_empty)));
            for (GitForgeClient.Pipeline pipeline : pipelines) {
                TextView row = secondaryButton(pipeline.name + "\n" + pipeline.status + " · " + pipeline.branch);
                row.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                row.setOnClickListener(v -> startActivity(
                        new Intent(Intent.ACTION_VIEW, Uri.parse(pipeline.url))));
                list.addView(row);
                list.addView(spacer(dp(7)));
            }
            panel.removeAllViews();
            ScrollView scroll = new ScrollView(this);
            scroll.addView(list);
            panel.addView(scroll);
        }, this::showError);
    }

    // ══════════════════════════════════════════════════════════
    //  Helpers
    // ══════════════════════════════════════════════════════════

    private void refreshHeader() {
        if (!GitManager.isGitRepo(projectDir)) {
            headerBranch.setText(getString(R.string.git_no_repo_short));
            headerStats.setText("");
            return;
        }
        doBackground(() -> {
            String br = GitManager.currentBranch(projectDir);
            int ahead  = GitManager.aheadCount(projectDir);
            int behind = GitManager.behindCount(projectDir);
            return new String[] { br, String.valueOf(ahead), String.valueOf(behind) };
        }, arr -> {
            headerBranch.setText("⎇ " + arr[0]);
            int ahead = Integer.parseInt(arr[1]);
            int behind = Integer.parseInt(arr[2]);
            String stats = projectDir.getName();
            if (ahead > 0)  stats += "  ↑" + ahead;
            if (behind > 0) stats += "  ↓" + behind;
            headerStats.setText(stats);
        }, e -> { /* мовчки ігноруємо */ });
    }

    private void showOutputDialog(String title, String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(theme.text);
        t.setTextSize(11);
        t.setTypeface(mono);
        t.setPadding(dp(16), dp(16), dp(16), dp(16));
        ScrollView sv = new ScrollView(this);
        sv.addView(t);
        Dialogs.rounded(this)
                .setTitle(title)
                .setView(sv)
                .setPositiveButton(R.string.dialog_apply, null)
                .show();
    }

    private void showError(Throwable t) {
        String msg = t.getMessage();
        if (msg == null) msg = t.getClass().getSimpleName();
        Dialogs.rounded(this)
                .setTitle(R.string.git_error)
                .setMessage(msg)
                .setPositiveButton(R.string.dialog_apply, null)
                .show();
    }

    // ── Background runner ─────────────────────────────────────

    private interface IoTask<T> { T run() throws Exception; }
    private interface OnSuccess<T> { void onResult(T result); }
    private interface OnError { void onError(Throwable t); }

    private <T> void doBackground(IoTask<T> task, OnSuccess<T> onSuccess, OnError onError) {
        io.execute(() -> {
            try {
                final T result = task.run();
                ui.post(() -> { try { onSuccess.onResult(result); } catch (Throwable ignored) {} });
            } catch (Throwable t) {
                ui.post(() -> onError.onError(t));
            }
        });
    }

    // ── UI helpers ────────────────────────────────────────────

    private EditText newEdit(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(theme.textDim);
        e.setTextColor(theme.text);
        e.setBackgroundColor(Colors.blend(theme.bg, theme.toolbar, 0.5f));
        e.setPadding(dp(8), dp(8), dp(8), dp(8));
        e.setSingleLine(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        e.setLayoutParams(lp);
        return e;
    }

    private TextView primaryButton(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(13);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(16), dp(12), dp(16), dp(12));
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(theme.accent);
        d.setCornerRadius(dp(6));
        t.setBackground(d);
        t.setClickable(true);
        t.setFocusable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        t.setLayoutParams(lp);
        return t;
    }

    private TextView secondaryButton(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(theme.accent);
        t.setTextSize(13);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(16), dp(12), dp(16), dp(12));
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(0x00000000);
        d.setStroke(dp(1), theme.accent);
        d.setCornerRadius(dp(6));
        t.setBackground(d);
        t.setClickable(true);
        t.setFocusable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        t.setLayoutParams(lp);
        return t;
    }

    private View spacer(int h) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, h));
        return v;
    }


    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private static void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] ch = f.listFiles();
            if (ch != null) for (File c : ch) deleteRecursive(c);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private static void moveAllChildren(File src, File dst) {
        File[] ch = src.listFiles();
        if (ch == null) return;
        for (File c : ch) {
            File t = new File(dst, c.getName());
            if (t.exists()) deleteRecursive(t);
            //noinspection ResultOfMethodCallIgnored
            c.renameTo(t);
        }
    }

    public static void launch(Context ctx, File projectDir) {
        android.content.Intent i = new android.content.Intent(ctx, GitActivity.class);
        i.putExtra(EXTRA_PROJECT_DIR, projectDir.getAbsolutePath());
        ctx.startActivity(i);
    }
}
