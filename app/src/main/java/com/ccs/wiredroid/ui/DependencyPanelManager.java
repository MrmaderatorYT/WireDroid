package com.ccs.wiredroid.ui;

import android.app.Activity;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.ccs.wiredroid.R;
import com.ccs.wiredroid.project.ProjectManager;
import com.ccs.wiredroid.tools.bytecode.DependencyModel;
import com.ccs.wiredroid.util.AppTheme;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class DependencyPanelManager {

    public interface Callback {
        void runOnUiThread(@NonNull Runnable r);
        ProjectManager getProjectManager();
        AppTheme getTheme();
    }

    private final Activity activity;
    private final Callback callback;

    /** False until the ViewStub holding this panel has been inflated. */
    private boolean panelInflated;
    /** Last theme handed to applyTheme, re-applied to views inflated later. */
    private AppTheme lastTheme;

    private View panel;
    private TextView tab;
    private DependencyGraphView graphView;
    private TextView status;
    private DependencyModel model;
    private File modelProjectDir;
    private boolean toolbarInitialized = false;
    private final ExecutorService analysisExecutor = Executors.newSingleThreadExecutor();
    private Future<?> analysisTask;
    private int refreshGeneration;

    public DependencyPanelManager(Activity activity, Callback callback) {
        this.activity = activity;
        this.callback = callback;
    }

    public void bind() {
        tab       = activity.findViewById(R.id.tabDeps);
    }

    /**
     * Inflates the panel the first time it is actually opened.
     *
     * <p>It lives behind a {@code ViewStub} because it starts hidden and most
     * sessions never open it, yet it used to be inflated on every cold start
     * along with every other bottom panel.</p>
     */
    private void ensurePanel() {
        if (panelInflated) return;
        panelInflated = true;
        android.view.ViewStub stub = activity.findViewById(R.id.stubDepsPanel);
        if (stub != null) stub.inflate();
        panel     = activity.findViewById(R.id.depsPanel);
        graphView = activity.findViewById(R.id.depsGraphView);
        status    = activity.findViewById(R.id.depsStatus);
        if (lastTheme != null) applyTheme(lastTheme);
    }

    public void applyTheme(@NonNull AppTheme theme) {
        lastTheme = theme;
        if (panel != null) panel.setBackgroundColor(theme.consoleBg);
        if (status != null) status.setTextColor(theme.textDim);
        View depsToolbar = activity.findViewById(R.id.depsToolbar);
        if (depsToolbar != null) depsToolbar.setBackgroundColor(theme.toolbar);
        View depsRefresh = activity.findViewById(R.id.depsRefresh);
        if (depsRefresh != null) ((TextView) depsRefresh).setTextColor(theme.accent);
        View depsFit = activity.findViewById(R.id.depsFitToScreen);
        if (depsFit != null) ((TextView) depsFit).setTextColor(theme.text);

        styleZoomButton(activity.findViewById(R.id.depsZoomIn), theme);
        styleZoomButton(activity.findViewById(R.id.depsZoomOut), theme);

        if (graphView != null) {
            graphView.setColors(theme.accent, 0xFFFFA500, 0xFF4CAF50, 0xFF666666, theme.text, theme.consoleBg);
        }
    }

    private void styleZoomButton(View v, AppTheme theme) {
        if (v instanceof TextView) {
            TextView tv = (TextView) v;
            tv.setTextColor(theme.text);
            android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
            gd.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            gd.setColor(theme.toolbar);
            gd.setStroke(2, theme.separator);
            tv.setBackground(gd);
        }
    }

    public void updateTabStyle(boolean active, @NonNull AppTheme theme, int activeBg) {
        if (tab != null) {
            tab.setBackgroundColor(active ? activeBg : theme.toolbar);
            tab.setTextColor(active ? theme.accent : theme.textDim);
        }
    }

    public void setVisibility(boolean visible) {
        if (visible) ensurePanel();
        if (panel != null) panel.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    public void refresh() {
        refresh(false);
    }

    private void refresh(boolean force) {
        ensurePanel();
        if (graphView == null || callback.getProjectManager() == null) return;

        if (!toolbarInitialized) {
            toolbarInitialized = true;

            View depsRefresh = activity.findViewById(R.id.depsRefresh);
            View depsZoomIn = activity.findViewById(R.id.depsZoomIn);
            View depsZoomOut = activity.findViewById(R.id.depsZoomOut);
            View depsFit = activity.findViewById(R.id.depsFitToScreen);

            if (depsRefresh != null) depsRefresh.setOnClickListener(v -> refresh(true));
            if (depsZoomIn != null) depsZoomIn.setOnClickListener(v -> graphView.zoomIn());
            if (depsZoomOut != null) depsZoomOut.setOnClickListener(v -> graphView.zoomOut());
            if (depsFit != null) depsFit.setOnClickListener(v -> graphView.fitToScreen());
        }

        File projectDir = callback.getProjectManager().getProjectDir();
        if (projectDir == null) {
            if (status != null) status.setText(R.string.toast_no_project);
            return;
        }
        if (!force && model != null && projectDir.equals(modelProjectDir)) return;
        if (analysisTask != null && !analysisTask.isDone()) {
            if (!force) return;
            analysisTask.cancel(true);
        }

        if (status != null) status.setText(R.string.label_analyzing);
        final int generation = ++refreshGeneration;
        analysisTask = analysisExecutor.submit(() -> {
            try {
                DependencyModel m = new DependencyModel();

                File outDir = new File(projectDir, "out");
                File targetDir = new File(projectDir, "target");
                File buildDir = new File(projectDir, "build");

                if (outDir.isDirectory()) m.analyzeDirectory(outDir);
                if (targetDir.isDirectory()) m.analyzeDirectory(targetDir);
                if (buildDir.isDirectory()) m.analyzeDirectory(buildDir);

                callback.runOnUiThread(() -> {
                    if (generation != refreshGeneration) return;
                    model = m;
                    modelProjectDir = projectDir;
                    if (graphView != null) {
                        graphView.setModel(m);
                    }
                    if (status != null) {
                        status.setText(m.getProjectClasses().size() + " classes, "
                                + m.getEdges().size() + " edges");
                    }
                });
            } catch (Exception e) {
                callback.runOnUiThread(() -> {
                    if (generation != refreshGeneration) return;
                    if (status != null) status.setText("Error: " + e.getMessage());
                });
            }
        });
    }

    public View getPanel() { return panel; }
    public TextView getTab() { return tab; }
    public DependencyModel getModel() { return model; }
}
