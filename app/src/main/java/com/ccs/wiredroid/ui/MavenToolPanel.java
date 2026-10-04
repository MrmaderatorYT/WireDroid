package com.ccs.wiredroid.ui;

import android.app.Activity;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.ccs.wiredroid.R;
import com.ccs.wiredroid.maven.MavenPlugins;
import com.ccs.wiredroid.maven.PomModel;
import com.ccs.wiredroid.project.BuildSystem;
import com.ccs.wiredroid.util.AppTheme;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * The build tool window, as a right-hand drawer.
 *
 * <p>Serves Maven and Gradle from one panel, in each one's own vocabulary: a
 * Gradle project sees tasks it could type at a terminal, not Maven's fixed
 * lifecycle. The commands underneath are the same on-device builder either way.</p>
 *
 *
 * <p>These commands used to live several taps deep in an overflow list, mixed in
 * with everything else the app can do. A build tool is not an occasional action;
 * it is a panel you keep open while you work, which is why every desktop IDE
 * gives it one. The tree mirrors that layout so the muscle memory transfers.</p>
 */
public final class MavenToolPanel {

    public interface Callback {
        File getProjectDir();
        AppTheme getTheme();
        /** Runs a Maven lifecycle phase by name. */
        void runPhase(String phase);
        void syncDependencies();
        void closeDrawer();
    }

    /**
     * A lifecycle phase and whether this app can actually carry it out.
     *
     * <p>All nine of Maven's phases are listed, in Maven's order, because a
     * short list would read as "this IDE has fewer phases" rather than "this
     * builder implements fewer". The three it cannot run are shown dimmed and
     * say why when tapped — an honest gap beats a hidden one.</p>
     */
    private static final class Phase {
        /** What the row says — the build system's own vocabulary. */
        final String name;
        /** What the delegate is asked to run; Maven phase names internally. */
        final String command;
        final boolean runnable;
        final int unsupportedReason;

        Phase(String name, String command) {
            this(name, command, true, 0);
        }

        Phase(String name, String command, boolean runnable, int unsupportedReason) {
            this.name = name;
            this.command = command;
            this.runnable = runnable;
            this.unsupportedReason = unsupportedReason;
        }
    }

    private static final Phase[] MAVEN_LIFECYCLE = {
            new Phase("clean", "clean"),
            new Phase("validate", "validate"),
            new Phase("compile", "compile"),
            new Phase("test", "test"),
            new Phase("package", "package"),
            new Phase("verify", "verify", false, R.string.maven_phase_no_verify),
            new Phase("install", "install"),
            new Phase("site", "site", false, R.string.maven_phase_no_site),
            new Phase("deploy", "deploy", false, R.string.maven_phase_no_deploy),
    };

    /**
     * Gradle has tasks, not a fixed lifecycle, so the rows carry task names a
     * Gradle user would type. They drive the same on-device builder underneath —
     * the difference is vocabulary, and showing Maven's to a Gradle project was
     * simply wrong.
     */
    private static final Phase[] GRADLE_TASKS = {
            new Phase("clean", "clean"),
            new Phase("compileJava", "compile"),
            new Phase("compileTestJava", "test-compile"),
            new Phase("test", "test"),
            new Phase("jar", "package"),
            new Phase("publishToMavenLocal", "install"),
            new Phase("javadoc", "javadoc", false, R.string.gradle_task_no_javadoc),
            new Phase("publish", "publish", false, R.string.gradle_task_no_publish),
    };

    private final Activity activity;
    private final Callback callback;

    private final View root;
    private final TextView title;
    private final LinearLayout tree;

    /** Which branches are open, kept across rebuilds so a refresh is not a reset. */
    private final List<String> expanded = new ArrayList<>();

    public MavenToolPanel(Activity activity, View drawerRoot, Callback callback) {
        this.activity = activity;
        this.callback = callback;
        this.root = drawerRoot;
        this.title = drawerRoot.findViewById(R.id.mavenPanelTitle);
        this.tree = drawerRoot.findViewById(R.id.mavenPanelTree);

        expanded.add("lifecycle");

        View reload = drawerRoot.findViewById(R.id.mavenPanelReload);
        if (reload != null) reload.setOnClickListener(v -> callback.syncDependencies());
        View refresh = drawerRoot.findViewById(R.id.mavenPanelRefresh);
        if (refresh != null) refresh.setOnClickListener(v -> rebuild());
    }

    /**
     * Recolours the panel. Deliberately does not rebuild the tree.
     *
     * <p>Rebuilding parses the build file and reads the plugin declarations —
     * file work on the main thread. Theme application happens while a project is
     * opening, so doing it here stalled the very moment the editor should be
     * appearing. The tree is rebuilt when the drawer is opened, which is the
     * only time anyone can see it.</p>
     */
    public void applyTheme(AppTheme theme) {
        if (root != null) root.setBackgroundColor(theme.bg);
        if (title != null) title.setTextColor(theme.text);
    }

    /** Re-reads the project and redraws the tree. Cheap enough to call on open. */
    public void rebuild() {
        if (tree == null) return;
        AppTheme theme = callback.getTheme();
        tree.removeAllViews();

        File dir = callback.getProjectDir();
        if (dir == null || !BuildSystem.isBuildable(dir)) {
            if (title != null) title.setText(R.string.maven_panel_title);
            tree.addView(hint(activity.getString(R.string.maven_panel_no_project), theme));
            return;
        }

        PomModel pom = null;
        List<String> warnings = new ArrayList<>();
        try {
            BuildSystem.Model model = BuildSystem.model(dir);
            pom = model.pom;
            warnings.addAll(model.warnings);
        } catch (Throwable ignored) {
            // An unreadable pom still leaves the lifecycle usable — clean in
            // particular is what you reach for when the build is broken.
        }

        BuildSystem.Kind kind = BuildSystem.detect(dir);
        boolean gradle = kind == BuildSystem.Kind.GRADLE;
        boolean ant = kind == BuildSystem.Kind.ANT;

        if (title != null) {
            String name = pom != null && pom.artifactId != null ? pom.artifactId : dir.getName();
            // The build system is named next to the project, because the panel
            // looks identical otherwise and the vocabulary below depends on it.
            title.setText(name + "  ·  " + BuildSystem.displayName(BuildSystem.detect(dir)));
        }

        branch("lifecycle", activity.getString(gradle
                        ? R.string.gradle_branch_tasks
                        : R.string.maven_branch_lifecycle), theme, () -> {
            for (Phase p : (gradle ? GRADLE_TASKS : MAVEN_LIFECYCLE)) {
                tree.addView(phaseRow(p, theme));
            }
        });

        final PomModel model = pom;

        // Ant has no plugins, dependencies or repositories to show — those are
        // Maven and Gradle notions, and three branches reading "(0)" told an Ant
        // user nothing. What its build file does have is targets, and what it
        // depends on is the jars sitting in the folders the script scans.
        if (ant) {
            buildAntBranches(dir, theme, warnings);
            return;
        }

        // Each build system keeps its plugins somewhere different; reading a pom
        // for a Gradle project is why this branch always said (0).
        List<String> plugins = new ArrayList<>();
        if (gradle) {
            plugins.addAll(com.ccs.wiredroid.project.GradlePlugins.of(dir));
        } else {
            for (MavenPlugins.Plugin p : MavenPlugins.of(dir)) plugins.add(p.display());
        }
        branch("plugins", activity.getString(R.string.maven_branch_plugins)
                + " (" + plugins.size() + ")", theme, () -> {
            if (plugins.isEmpty()) {
                tree.addView(leaf(activity.getString(R.string.maven_none_declared), theme, true, null));
            } else {
                for (String p : plugins) tree.addView(leaf(p, theme, false, null));
            }
        });

        int depCount = model == null ? 0 : model.dependencies.size();
        branch("dependencies", activity.getString(R.string.maven_branch_dependencies)
                + " (" + depCount + ")", theme, () -> {
            if (model == null || model.dependencies.isEmpty()) {
                tree.addView(leaf(activity.getString(R.string.maven_none_declared), theme, true, null));
                return;
            }
            for (PomModel.MavenDependency d : model.dependencies) {
                String label = d.artifactId + (d.version == null || d.version.isEmpty()
                        ? "" : ":" + model.resolveProperty(d.version));
                if (d.scope != null && !d.scope.isEmpty() && !"compile".equals(d.scope)) {
                    label = label + "  (" + d.scope + ")";
                }
                tree.addView(leaf(label, theme, false, null));
            }
        });

        int repoCount = model == null ? 0 : model.repositories.size();
        branch("repositories", activity.getString(R.string.maven_branch_repositories)
                + " (" + repoCount + ")", theme, () -> {
            if (model == null || model.repositories.isEmpty()) {
                // Maven Central is implicit; saying so is more useful than an
                // empty branch that looks like a parse failure.
                tree.addView(leaf(activity.getString(gradle
                        ? R.string.gradle_repos_none
                        : R.string.maven_central_implicit), theme, true, null));
                return;
            }
            for (PomModel.MavenRepository r : model.repositories) {
                tree.addView(leaf(r.url != null ? r.url : String.valueOf(r.id), theme, false, null));
            }
        });

        for (String w : warnings) {
            TextView tv = leaf("⚠ " + w, theme, true, null);
            tv.setTextColor(theme.errorText);
            tree.addView(tv);
        }
    }

    /**
     * The branches an Ant project actually has.
     *
     * <p>The targets are listed rather than made runnable: running one needs
     * Ant itself, and the lifecycle rows above already do the work through the
     * on-device pipeline. A row that looked clickable and did nothing would be
     * worse than a row that plainly reports.</p>
     */
    private void buildAntBranches(File dir, AppTheme theme, List<String> warnings) {
        com.ccs.wiredroid.ant.AntBuildParser.Result parsed = null;
        try {
            parsed = com.ccs.wiredroid.ant.AntBuildParser.parse(dir);
        } catch (Throwable ignored) {
            // The title and lifecycle are already drawn; a build file that will
            // not parse still leaves those usable.
        }

        final List<String> targets = parsed == null
                ? new ArrayList<String>() : new ArrayList<>(parsed.targets);
        final String defaultTarget = parsed == null ? null : parsed.defaultTarget;
        branch("ant_targets", activity.getString(R.string.ant_branch_targets)
                + " (" + targets.size() + ")", theme, () -> {
            if (targets.isEmpty()) {
                tree.addView(leaf(activity.getString(R.string.maven_none_declared), theme, true, null));
                return;
            }
            for (String target : targets) {
                boolean isDefault = target.equals(defaultTarget);
                tree.addView(leaf(isDefault
                        ? activity.getString(R.string.ant_target_default, target)
                        : target, theme, false, null));
            }
        });

        final List<File> jars = com.ccs.wiredroid.project.LocalLibraries.list(dir);
        branch("ant_jars", activity.getString(R.string.maven_branch_dependencies)
                + " (" + jars.size() + ")", theme, () -> {
            if (jars.isEmpty()) {
                tree.addView(leaf(activity.getString(R.string.ant_no_jars), theme, true, null));
                return;
            }
            for (File jar : jars) {
                tree.addView(leaf(jar.getName(), theme, false, null));
            }
        });

        for (String w : warnings) {
            TextView tv = leaf("⚠ " + w, theme, true, null);
            tv.setTextColor(theme.errorText);
            tree.addView(tv);
        }
    }

    // ── Row builders ────────────────────────────────────────────────────────

    private void branch(String key, String label, AppTheme theme, Runnable children) {
        boolean open = expanded.contains(key);
        TextView header = row((open ? "▾  " : "▸  ") + label, theme, 8);
        header.setTextColor(theme.text);
        header.setOnClickListener(v -> {
            if (!expanded.remove(key)) expanded.add(key);
            rebuild();
        });
        tree.addView(header);
        if (open) children.run();
    }

    private TextView phaseRow(Phase p, AppTheme theme) {
        TextView tv = row(p.name, theme, 34);
        tv.setTextColor(p.runnable ? theme.text : theme.textDim);
        tv.setOnClickListener(v -> {
            if (!p.runnable) {
                Toast.makeText(activity, p.unsupportedReason, Toast.LENGTH_LONG).show();
                return;
            }
            callback.closeDrawer();
            callback.runPhase(p.command);
        });
        return tv;
    }

    private TextView leaf(String label, AppTheme theme, boolean dim, View.OnClickListener onClick) {
        TextView tv = row(label, theme, 34);
        tv.setTextColor(dim ? theme.textDim : theme.text);
        if (onClick != null) tv.setOnClickListener(onClick);
        return tv;
    }

    private TextView hint(String text, AppTheme theme) {
        TextView tv = row(text, theme, 8);
        tv.setTextColor(theme.textDim);
        tv.setSingleLine(false);
        return tv;
    }

    private TextView row(String text, AppTheme theme, int indentDp) {
        TextView tv = new TextView(activity);
        tv.setText(text);
        tv.setTextSize(13f);
        tv.setSingleLine(true);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        tv.setGravity(Gravity.CENTER_VERTICAL);
        tv.setPadding(dp(indentDp), dp(9), dp(12), dp(9));
        tv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TypedValue ripple = new TypedValue();
        if (activity.getTheme().resolveAttribute(
                android.R.attr.selectableItemBackground, ripple, true)) {
            tv.setBackgroundResource(ripple.resourceId);
        }
        return tv;
    }

    private int dp(int v) {
        return Math.round(v * activity.getResources().getDisplayMetrics().density);
    }
}
