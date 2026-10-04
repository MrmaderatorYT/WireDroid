package com.ccs.wiredroid.ui;

import android.app.Activity;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.ccs.wiredroid.R;
import com.ccs.wiredroid.analysis.ProblemItem;
import com.ccs.wiredroid.analysis.ProblemsAdapter;
import com.ccs.wiredroid.project.BuildSystem;
import com.ccs.wiredroid.project.ProjectManager;
import com.ccs.wiredroid.ui.panels.BottomPanel;
import com.ccs.wiredroid.tools.compilers.ProjectCompiler;
import com.ccs.wiredroid.maven.MavenDependencyResolver;
import com.ccs.wiredroid.maven.PomModel;
import com.ccs.wiredroid.util.AppTheme;

import java.util.List;

/**
 * Drives the build lifecycle for both Maven and Gradle projects — the Gradle
 * build script is read into the same {@link PomModel} the Maven pipeline uses.
 */
public final class MavenActionDelegate {

    public interface Callback {
        void runOnUiThread(@NonNull Runnable r);
        ProjectManager getProjectManager();
        AppTheme getTheme();
        void appendConsole(String text, int color);
        void switchBottomPanel(int panel);
        void setConsoleText(String text);
        void setProblemsItems(List<ProblemItem> items);
        void saveCurrentToActiveTab();
        Activity getActivity();
    }

    // Panel ids come from BottomPanel; they used to be copied here as 0 and 1.
    private static final int PANEL_RUN = BottomPanel.RUN.mode;
    private static final int PANEL_PROBLEMS = BottomPanel.PROBLEMS.mode;

    private final Callback callback;
    private volatile boolean syncInProgress = false;

    public MavenActionDelegate(Callback callback) {
        this.callback = callback;
    }

    public boolean isSyncInProgress() { return syncInProgress; }

    public void syncDependencies() {
        ProjectManager pm = callback.getProjectManager();
        if (pm == null || !BuildSystem.isBuildable(pm.getProjectDir())) {
            Toast.makeText(callback.getActivity(), R.string.toast_no_build_script, Toast.LENGTH_SHORT).show();
            return;
        }
        if (syncInProgress) return;
        syncInProgress = true;
        callback.switchBottomPanel(PANEL_RUN);
        callback.setConsoleText("");
        BuildSystem.Kind kind = BuildSystem.detect(pm.getProjectDir());
        callback.appendConsole(callback.getActivity().getString(
                R.string.console_sync_build, BuildSystem.displayName(kind)), callback.getTheme().textDim);
        new Thread(() -> {
            try {
                BuildSystem.Model model = BuildSystem.model(pm.getProjectDir());
                reportWarnings(model);
                MavenDependencyResolver.resolve(pm.getProjectDir(), model.pom, line ->
                        callback.runOnUiThread(() -> callback.appendConsole("   " + line, callback.getTheme().textDim)));
                callback.runOnUiThread(() -> {
                    syncInProgress = false;
                    callback.appendConsole(callback.getActivity().getString(R.string.console_sync_done), callback.getTheme().successText);
                    Toast.makeText(callback.getActivity(), R.string.toast_sync_done, Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                callback.runOnUiThread(() -> {
                    syncInProgress = false;
                    Toast.makeText(callback.getActivity(), e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        }, "maven-sync").start();
    }

    public void mavenCompile() { runMavenCommand("compile", "compileJava", true); }
    public void mavenPackage() { runMavenCommand("package", "build", true); }
    public void mavenTestCompile() { runMavenCommand("test-compile", "testClasses", true); }
    public void mavenTestRun() { runMavenCommand("test", "test", true); }
    public void mavenClean() { runMavenCommand("clean", "clean", false); }

    /**
     * Maven's validate phase: check the project is well formed.
     *
     * <p>Parsing the build model is exactly that check here — it is what surfaces
     * a malformed pom, an unresolvable property or a missing coordinate — so the
     * phase reports the model's warnings instead of pretending to do more.</p>
     */
    public void mavenValidate() {
        ProjectManager pm = callback.getProjectManager();
        if (pm == null || !BuildSystem.isBuildable(pm.getProjectDir())) {
            Toast.makeText(callback.getActivity(), R.string.toast_build_script_required, Toast.LENGTH_SHORT).show();
            return;
        }
        callback.switchBottomPanel(PANEL_RUN);
        callback.setConsoleText("");
        AppTheme theme = callback.getTheme();
        try {
            BuildSystem.Model model = BuildSystem.model(pm.getProjectDir());
            reportWarnings(model);
            PomModel pom = model.pom;
            callback.appendConsole(pom.groupId + ":" + pom.artifactId + ":" + pom.version, theme.text);
            callback.appendConsole(callback.getActivity().getString(
                    R.string.maven_validate_deps, pom.dependencies.size()), theme.textDim);
            callback.appendConsole(callback.getActivity().getString(
                    model.warnings.isEmpty() ? R.string.maven_validate_ok : R.string.maven_validate_warned),
                    model.warnings.isEmpty() ? theme.successText : theme.errorText);
        } catch (Exception e) {
            callback.appendConsole("validate failed: " + e.getMessage(), theme.errorText);
        }
    }

    /** Runs a lifecycle phase by its Maven name; unknown phases are ignored. */
    public void runPhase(String phase) {
        switch (phase) {
            case "clean":        mavenClean(); break;
            case "validate":     mavenValidate(); break;
            case "compile":      mavenCompile(); break;
            case "test-compile": mavenTestCompile(); break;
            case "test":         mavenTestRun(); break;
            case "package":      mavenPackage(); break;
            case "install":      mavenInstall(); break;
            default: break;
        }
    }
    public void mavenInstall() { runMavenCommand("install", "publishToMavenLocal", true); }

    /**
     * @param cmd         the Maven lifecycle phase, also the internal dispatch key
     * @param gradleTask  the equivalent Gradle task name, shown when the project uses Gradle
     * @param needsPom    whether the phase needs a parsed build model
     */
    private void runMavenCommand(String cmd, String gradleTask, boolean needsPom) {
        ProjectManager pm = callback.getProjectManager();
        if (pm == null || !BuildSystem.isBuildable(pm.getProjectDir())) {
            Toast.makeText(callback.getActivity(), R.string.toast_build_script_required, Toast.LENGTH_SHORT).show();
            return;
        }
        callback.saveCurrentToActiveTab();
        callback.switchBottomPanel(PANEL_RUN);
        callback.setConsoleText("");

        BuildSystem.Kind kind = BuildSystem.detect(pm.getProjectDir());
        // Names the tool the project is written for, not one that runs here:
        // the work is always done by the on-device pipeline.
        String tool = kind == BuildSystem.Kind.GRADLE ? "gradle"
                : kind == BuildSystem.Kind.ANT ? "ant" : "mvn";
        String task = kind == BuildSystem.Kind.GRADLE ? gradleTask : cmd;
        callback.appendConsole(callback.getActivity().getString(R.string.console_build_task, tool, task),
                callback.getTheme().textDim);

        try {
            PomModel pom = null;
            if (needsPom) {
                BuildSystem.Model model = BuildSystem.model(pm.getProjectDir());
                reportWarnings(model);
                pom = model.pom;
            }
            ProjectCompiler.Callback cc = new ProjectCompiler.Callback() {
                @Override public void onProgress(String msg) {
                    callback.appendConsole("   " + msg, callback.getTheme().textDim);
                }
                @Override public void onResult(String output) {
                    callback.appendConsole(output, callback.getTheme().consoleText);
                }
                @Override public void onProblems(List<ProblemItem> problems) {
                    if (problems != null && !problems.isEmpty()) {
                        callback.setProblemsItems(problems);
                        callback.switchBottomPanel(PANEL_PROBLEMS);
                    }
                }
            };
            switch (cmd) {
                case "compile": ProjectCompiler.mavenCompile(callback.getActivity(), pm.getProjectDir(), pom, cc); break;
                case "package": ProjectCompiler.mavenPackage(callback.getActivity(), pm.getProjectDir(), pom, cc); break;
                case "test-compile": ProjectCompiler.mavenTestCompile(callback.getActivity(), pm.getProjectDir(), pom, cc); break;
                case "test":
                    if (com.ccs.wiredroid.project.ProjectRuntime.isJavaSe(pm.getProjectDir())) {
                        ProjectCompiler.javaSeTestRun(callback.getActivity(), pm.getProjectDir(), pom, null, cc);
                    } else {
                        ProjectCompiler.mavenTestRun(callback.getActivity(), pm.getProjectDir(), pom, cc);
                    }
                    break;
                case "clean": ProjectCompiler.mavenClean(callback.getActivity(), pm.getProjectDir(), cc); break;
                case "install": ProjectCompiler.mavenInstall(callback.getActivity(), pm.getProjectDir(), pom, cc); break;
            }
        } catch (Exception e) {
            Toast.makeText(callback.getActivity(), e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /** Surfaces anything the Gradle parser had to skip, so it is never silent. */
    private void reportWarnings(BuildSystem.Model model) {
        if (model.warnings.isEmpty()) return;
        for (String w : model.warnings) {
            callback.runOnUiThread(() ->
                    callback.appendConsole("   ⚠ " + w, callback.getTheme().errorText));
        }
    }
}
