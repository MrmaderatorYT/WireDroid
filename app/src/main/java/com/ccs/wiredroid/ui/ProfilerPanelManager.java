package com.ccs.wiredroid.ui;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.ccs.wiredroid.R;
import com.ccs.wiredroid.analysis.ProblemItem;
import com.ccs.wiredroid.profiler.ProfilerBridge;
import com.ccs.wiredroid.profiler.ProfilerInstrumenter;
import com.ccs.wiredroid.profiler.PerformanceMonitor;
import com.ccs.wiredroid.tools.compilers.ProjectCompiler;
import com.ccs.wiredroid.util.AppPreferences;
import com.ccs.wiredroid.ui.panels.BottomPanel;
import com.ccs.wiredroid.util.AppTheme;
import com.ccs.wiredroid.util.PowerSavingManager;

import java.io.File;
import java.util.List;

public final class ProfilerPanelManager {

    public interface Callback {
        void saveCurrentToActiveTab();
        boolean isRunning();
        void setRunning(boolean running);
        String getEditorText();
        void runOnUiThread(@NonNull Runnable r);
        void appendConsole(String text, int color);
        void switchBottomPanel(int panel);
        void showToast(String msg);
        PowerSavingManager getPowerSaving();
        AppTheme getTheme();
        AppPreferences getAppPrefs();
    }

    // Panel id comes from BottomPanel; it used to be copied here as 0.
    private static final int PANEL_RUN = BottomPanel.RUN.mode;

    private final Activity activity;
    private final Callback callback;
    /** The panes and what they hold; a live view, not a snapshot. */
    private final EditorWorkspace ws;

    /** False until the ViewStub holding this panel has been inflated. */
    private boolean panelInflated;
    /** Last theme handed to applyTheme, re-applied to views inflated later. */
    private AppTheme lastTheme;

    private View panel;
    private TextView tab;
    private FlameChartView flameChartView;
    private TextView status;
    private ScrollView detailScroll;
    private TextView detail;
    private boolean profilingEnabled = false;
    private boolean liveMode = true;
    private final Handler refreshHandler = new Handler(Looper.getMainLooper());
    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            if (profilingEnabled && liveMode) {
                refreshResults();
                long interval = callback.getPowerSaving().isPowerSavingActive() ? 2000 : 500;
                refreshHandler.postDelayed(this, interval);
            }
        }
    };

    public ProfilerPanelManager(Activity activity, EditorWorkspace ws, Callback callback) {
        this.activity = activity;
        this.callback = callback;
        this.ws = ws;
    }

    /** The tab exists from the start; the panel itself waits for {@link #ensurePanel()}. */
    public void bind() {
        tab = activity.findViewById(R.id.tabProfiler);
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
        android.view.ViewStub stub = activity.findViewById(R.id.stubProfilerPanel);
        if (stub != null) stub.inflate();
        panel       = activity.findViewById(R.id.profilerPanel);
        flameChartView = activity.findViewById(R.id.flameChartView);
        status      = activity.findViewById(R.id.profilerStatus);
        detailScroll = activity.findViewById(R.id.profilerDetailScroll);
        detail      = activity.findViewById(R.id.profilerDetail);

        liveMode = !callback.getPowerSaving().isPowerSavingActive();

        View profilerRefresh = activity.findViewById(R.id.profilerRefresh);
        View profilerZoomIn = activity.findViewById(R.id.profilerZoomIn);
        View profilerZoomOut = activity.findViewById(R.id.profilerZoomOut);
        View profilerFit = activity.findViewById(R.id.profilerFit);
        View profilerRunBtn = activity.findViewById(R.id.profilerRunBtn);
        TextView profilerLiveToggle = activity.findViewById(R.id.profilerLiveToggle);
        View profilerMetrics = activity.findViewById(R.id.profilerMetrics);
        View profilerSnapshot = activity.findViewById(R.id.profilerSnapshot);

        if (profilerRefresh != null) profilerRefresh.setOnClickListener(v -> refreshResults());
        if (profilerZoomIn != null) profilerZoomIn.setOnClickListener(v -> flameChartView.zoomIn());
        if (profilerZoomOut != null) profilerZoomOut.setOnClickListener(v -> flameChartView.zoomOut());
        if (profilerFit != null) profilerFit.setOnClickListener(v -> flameChartView.fitToScreen());
        if (profilerRunBtn != null) profilerRunBtn.setOnClickListener(v -> runWithProfiler());
        if (profilerMetrics != null) profilerMetrics.setOnClickListener(v -> showPerformanceMetrics());
        if (profilerSnapshot != null) profilerSnapshot.setOnClickListener(v -> {
            PerformanceMonitor monitor = PerformanceMonitor.get();
            if (monitor != null) {
                monitor.snapshot("manual");
                Toast.makeText(activity, "Performance snapshot saved", Toast.LENGTH_SHORT).show();
                showPerformanceMetrics();
            }
        });

        if (profilerLiveToggle != null) {
            updateLiveToggleUI(profilerLiveToggle);
            profilerLiveToggle.setOnClickListener(v -> {
                liveMode = !liveMode;
                updateLiveToggleUI(profilerLiveToggle);
                if (liveMode && profilingEnabled) {
                    refreshHandler.removeCallbacks(refreshRunnable);
                    refreshHandler.post(refreshRunnable);
                    if (callback.getPowerSaving().isPowerSavingActive()) {
                        Toast.makeText(activity, "⚠ Live mode active — higher battery usage", Toast.LENGTH_SHORT).show();
                    }
                } else {
                    refreshHandler.removeCallbacks(refreshRunnable);
                }
            });
        }

        flameChartView.setOnNodeSelectedListener(profile -> {
            if (detailScroll != null && detail != null) {
                detailScroll.setVisibility(View.VISIBLE);
                StringBuilder sb = new StringBuilder();
                sb.append("Method: ").append(profile.fullSignature()).append("\n");
                sb.append("Class:  ").append(profile.className).append("\n");
                sb.append("─────────────────────────────\n");
                sb.append(String.format(java.util.Locale.US, "Total time:  %.3f ms\n", profile.getTotalTimeMs()));
                sb.append(String.format(java.util.Locale.US, "Avg time:    %.3f ms\n", profile.getAvgTimeMs()));
                sb.append(String.format(java.util.Locale.US, "Max time:    %.3f ms\n", profile.getMaxTimeMs()));
                sb.append(String.format(java.util.Locale.US, "Invocations: %d\n", profile.invocationCount.get()));
                sb.append("─────────────────────────────\n");
                long total = ProfilerBridge.getTotalTime();
                double pct = total > 0 ? (profile.totalTime.get() * 100.0 / total) : 0;
                sb.append(String.format(java.util.Locale.US, "Share: %.1f%% of total\n", pct));
                detail.setText(sb.toString());
            }
        });
        if (lastTheme != null) applyTheme(lastTheme);
    }

    public void applyTheme(@NonNull AppTheme theme) {
        lastTheme = theme;
        if (panel != null) panel.setBackgroundColor(theme.consoleBg);
        if (status != null) status.setTextColor(theme.textDim);
        View profilerToolbar = activity.findViewById(R.id.profilerToolbar);
        if (profilerToolbar != null) profilerToolbar.setBackgroundColor(theme.toolbar);
        View profilerRefresh = activity.findViewById(R.id.profilerRefresh);
        if (profilerRefresh != null) ((TextView) profilerRefresh).setTextColor(theme.accent);
        View profilerZoomIn = activity.findViewById(R.id.profilerZoomIn);
        if (profilerZoomIn != null) ((TextView) profilerZoomIn).setTextColor(theme.text);
        View profilerZoomOut = activity.findViewById(R.id.profilerZoomOut);
        if (profilerZoomOut != null) ((TextView) profilerZoomOut).setTextColor(theme.text);
        View profilerFit = activity.findViewById(R.id.profilerFit);
        if (profilerFit != null) ((TextView) profilerFit).setTextColor(theme.text);
        View profilerRunBtn = activity.findViewById(R.id.profilerRunBtn);
        if (profilerRunBtn != null) ((TextView) profilerRunBtn).setTextColor(theme.accent);
        View profilerMetrics = activity.findViewById(R.id.profilerMetrics);
        if (profilerMetrics != null) ((TextView) profilerMetrics).setTextColor(theme.text);
        View profilerSnapshot = activity.findViewById(R.id.profilerSnapshot);
        if (profilerSnapshot != null) ((TextView) profilerSnapshot).setTextColor(theme.text);
        if (detailScroll != null) detailScroll.setBackgroundColor(theme.consoleBg);
        if (detail != null) detail.setTextColor(theme.consoleText);
        if (flameChartView != null) {
            flameChartView.setBackgroundColor(theme.consoleBg);
            flameChartView.applyTheme(theme);
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

    public void startLiveRefresh() {
        refreshHandler.removeCallbacks(refreshRunnable);
        if (liveMode) refreshHandler.post(refreshRunnable);
    }

    public void stopLiveRefresh() {
        refreshHandler.removeCallbacks(refreshRunnable);
    }

    public void refreshResults() {
        if (flameChartView == null || status == null) return;
        java.util.List<ProfilerBridge.MethodProfile> results = ProfilerBridge.getResults();
        if (results.isEmpty()) {
            status.setText(profilingEnabled ? "Collecting data..." : "No data — run code with profiler");
            if (!profilingEnabled) flameChartView.clear();
            if (detailScroll != null && !profilingEnabled) detailScroll.setVisibility(View.GONE);
            return;
        }
        results.sort((a, b) -> Long.compare(b.totalTime.get(), a.totalTime.get()));
        flameChartView.setProfiles(results);
        long totalNs = ProfilerBridge.getTotalTime();
        double totalMs = totalNs / 1_000_000.0;
        String liveIndicator = (profilingEnabled && liveMode) ? " ●LIVE" : "";
        status.setText(String.format(java.util.Locale.US,
                "%d methods, %.1f ms total%s", results.size(), totalMs, liveIndicator));
    }

    private void updateLiveToggleUI(TextView toggle) {
        if (liveMode) {
            toggle.setText("● Live");
            toggle.setTextColor(0xFF499C54);
        } else {
            toggle.setText("○ Paused");
            toggle.setTextColor(0xFF888888);
        }
    }

    private void showPerformanceMetrics() {
        PerformanceMonitor monitor = PerformanceMonitor.get();
        if (monitor == null || detailScroll == null || detail == null || flameChartView == null) return;
        detail.setText(monitor.report());
        detailScroll.setVisibility(View.VISIBLE);
        flameChartView.setVisibility(View.GONE);
        status.setText(monitor.isLowMemoryDevice()
                ? "Low-memory mode active" : "Process performance metrics");
        detailScroll.setOnClickListener(v -> {
            detailScroll.setVisibility(View.GONE);
            flameChartView.setVisibility(View.VISIBLE);
        });
    }

    private void runWithProfiler() {
        if (callback.isRunning()) return;
        FileTab activeTab = ws.tabs().getActiveTab();
        if (activeTab == null) {
            callback.showToast(activity.getString(R.string.toast_no_file_open));
            return;
        }
        boolean isKotlin = activeTab.file.getName().endsWith(".kt");
        if (!activeTab.file.getName().endsWith(".java") && !isKotlin) {
            callback.showToast("Profiler only supports Java and Kotlin files");
            return;
        }

        callback.saveCurrentToActiveTab();
        liveMode = !callback.getPowerSaving().isPowerSavingActive();
        callback.setRunning(true);
        profilingEnabled = true;
        callback.switchBottomPanel(PANEL_RUN);
        callback.appendConsole("Profiling: " + activeTab.file.getName(), callback.getTheme().textDim);

        String source = callback.getEditorText();
        AppTheme theme = callback.getTheme();

        com.ccs.wiredroid.tools.compilers.RunCancellation.newWorker(() -> {
            try {
                String className = ProjectCompiler.extractClassNamePublic(source);
                File cacheDir = new File(activity.getCacheDir(), "profile_compile_cache");
                if (cacheDir.exists()) {
                    File[] oldFiles = cacheDir.listFiles();
                    if (oldFiles != null) {
                        for (File f : oldFiles) {
                            if (f.getName().equals("android.jar")) continue;
                            f.delete();
                        }
                    }
                }
                if (!cacheDir.exists()) cacheDir.mkdirs();

                File androidJar = ProjectCompiler.ensureAndroidJarPublic(activity, cacheDir);
                File srcFile = new File(cacheDir, className + (isKotlin ? ".kt" : ".java"));
                ProjectCompiler.writeUtf8Public(srcFile, source);

                callback.runOnUiThread(() -> callback.appendConsole("   " + activity.getString(R.string.label_analyzing), theme.textDim));
                if (isKotlin) {
                    java.util.List<File> kClasses = ProjectCompiler.compileKotlinPublic(
                            srcFile, cacheDir, cacheDir, androidJar, className,
                            new ProjectCompiler.Callback() {
                                @Override public void onProgress(String msg) {
                                    callback.runOnUiThread(() -> callback.appendConsole("   " + msg, theme.textDim));
                                }
                                @Override public void onResult(String res) {}
                                @Override public void onProblems(java.util.List<com.ccs.wiredroid.analysis.ProblemItem> problems) {}
                            }, activity
                    );
                    if (kClasses == null || kClasses.isEmpty()) {
                        callback.setRunning(false);
                        profilingEnabled = false;
                        callback.runOnUiThread(() -> {
                            stopLiveRefresh();
                            callback.appendConsole(activity.getString(R.string.toast_compile_error), theme.errorText);
                        });
                        return;
                    }
                } else {
                    String ecjErr = ProjectCompiler.compileEcjPublic(
                            androidJar, null, cacheDir,
                            callback.getAppPrefs().getJavaTarget(), srcFile);
                    if (ecjErr != null) {
                        callback.setRunning(false);
                        profilingEnabled = false;
                        callback.runOnUiThread(() -> {
                            stopLiveRefresh();
                            callback.appendConsole(activity.getString(R.string.toast_compile_error), theme.errorText);
                        });
                        return;
                    }
                }

                File classFile = ProjectCompiler.findClassFilePublic(cacheDir, className);
                if (classFile == null) {
                    callback.setRunning(false);
                    profilingEnabled = false;
                    callback.runOnUiThread(() -> {
                        stopLiveRefresh();
                        callback.appendConsole(activity.getString(R.string.toast_error), theme.errorText);
                    });
                    return;
                }

                callback.runOnUiThread(() -> callback.appendConsole("   " + activity.getString(R.string.label_analyzing), theme.textDim));
                ProfilerBridge.reset();
                ProfilerBridge.start();
                ProfilerInstrumenter.instrumentDirectory(cacheDir); // Instrument ALL generated classes, not just main class

                File dexDir = new File(cacheDir, "profile_dex");
                if (!dexDir.exists()) dexDir.mkdirs();
                else {
                    File[] oldFiles = dexDir.listFiles();
                    if (oldFiles != null) for (File f : oldFiles) f.delete();
                }

                callback.runOnUiThread(() -> callback.appendConsole("   " + activity.getString(R.string.label_analyzing), theme.textDim));
                // Recursively, and in a single D8 pass: the compiler writes into
                // package folders, and each D8 invocation clears the output first.
                java.util.List<File> allClasses =
                        com.ccs.wiredroid.profiler.ProfilerInstrumenter.listClassFiles(cacheDir);
                if (allClasses.isEmpty()) {
                    callback.setRunning(false);
                    profilingEnabled = false;
                    callback.runOnUiThread(() -> {
                        stopLiveRefresh();
                        callback.appendConsole(activity.getString(R.string.profiler_no_classes),
                                theme.errorText);
                    });
                    return;
                }
                ProjectCompiler.runD8DexPublic(androidJar, dexDir, allClasses);

                String fqClassName = classFile.getAbsolutePath()
                        .substring(cacheDir.getAbsolutePath().length() + 1)
                        .replace(".class", "")
                        .replace('/', '.');

                callback.runOnUiThread(() -> callback.appendConsole("   " + activity.getString(R.string.label_analyzing), theme.textDim));
                callback.runOnUiThread(() -> startLiveRefresh());

                ProjectCompiler.debugRunDex(
                        activity, fqClassName, dexDir, cacheDir, null,
                        new ProjectCompiler.Callback() {
                            @Override public void onProgress(String msg) {}
                            @Override
                            public void onResult(String output) {
                                ProfilerBridge.stop();
                                callback.setRunning(false);
                                profilingEnabled = false;
                                callback.runOnUiThread(() -> stopLiveRefresh());
                                callback.runOnUiThread(() -> {
                                    callback.appendConsole("", theme.accent);
                                    callback.appendConsole("══════════════════════════════", theme.accent);
                                    if (output != null && !output.trim().isEmpty()) {
                                        boolean err = output.startsWith("Compilation Error")
                                                || output.startsWith("Execution Exception")
                                                || output.startsWith("System Error")
                                                || output.startsWith("Error:");
                                        callback.appendConsole(output.trim(), err ? theme.errorText : theme.consoleText);
                                    }
                                    boolean failed = output != null && (output.startsWith("Compilation Error")
                                            || output.startsWith("Execution Exception")
                                            || output.startsWith("System Error")
                                            || output.startsWith("Error:"));
                                    if (failed) {
                                        callback.appendConsole("\n" + activity.getString(R.string.profiler_failed),
                                                theme.errorText);
                                    } else {
                                        callback.appendConsole("\n" + activity.getString(R.string.profiler_complete),
                                                theme.successText);
                                        refreshResults();
                                    }
                                });
                            }
                            @Override public void onProblems(List<ProblemItem> problems) {}
                        });
            } catch (Exception e) {
                callback.setRunning(false);
                profilingEnabled = false;
                callback.runOnUiThread(() -> {
                    stopLiveRefresh();
                    callback.appendConsole("Profiler error: " + e.getMessage(), theme.errorText);
                });
            }
        }, "ProfilerRunner").start();
    }

    public View getPanel() { return panel; }
    public TextView getTab() { return tab; }
}
