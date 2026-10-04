package com.ccs.wiredroid.analysis;

import android.os.Handler;
import android.os.Looper;
import android.os.Process;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Event-driven and dirty-tracked scheduler for Live Problems analysis.
 *
 * <p>Instead of unconditionally spawning threads and recompiling unchanged editor buffers
 * every few seconds in the background, this scheduler tracks the content hash of the open
 * editor buffer and timestamps of project files. If the user is idle and nothing has changed,
 * the scheduler remains completely dormant, consuming 0% CPU and zero battery.</p>
 */
public final class LiveProblemsScheduler {

    public interface Sources {
        /** Editor text for active Java file. */
        String getEditorText();

        /** Open .java file or null. */
        File getActiveJavaFile();

        File getProjectRoot();

        /** Skip during run / heavy build / disabled power saving. */
        boolean shouldSkipScan();

        /** If power saving is active, analyzer skips disk scan of the rest of the workspace. */
        default boolean isPowerSavingActive() {
            return false;
        }
    }

    public interface OnProblems {
        void onProblems(java.util.List<ProblemItem> items);
    }

    private static final long FIRST_DELAY_MS = 800L;
    /** Floor on the debounce, so a misconfigured interval cannot analyse per keystroke. */
    private static final long MIN_DEBOUNCE_MS = 300L;

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(() -> {
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            } catch (Throwable ignored) {}
            r.run();
        }, "live-problems-worker");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final android.content.Context appContext;
    private final Sources sources;
    private final OnProblems callback;
    private long intervalMs = 10_000L;

    private volatile boolean stopped = true;
    private volatile boolean scanning;
    private volatile boolean forceDirty;

    private int lastTextHash;
    private long lastActiveFileMod;
    private String lastActivePath = "";
    private String lastRootPath = "";

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (stopped) return;
            if (sources.shouldSkipScan() || scanning) return;

            final String text = sources.getEditorText();
            final File active = sources.getActiveJavaFile();
            final File root = sources.getProjectRoot();
            final boolean activeOnly = sources.isPowerSavingActive();

            final int textHash = text != null ? text.hashCode() : 0;
            final long activeMod = (active != null && active.exists()) ? active.lastModified() : 0L;
            final String activePath = active != null ? active.getAbsolutePath() : "";
            final String rootPath = root != null ? root.getAbsolutePath() : "";

            // Smart dirty check: if nothing changed since last run, do not burn CPU or invoke ECJ
            if (!forceDirty
                    && textHash == lastTextHash
                    && activeMod == lastActiveFileMod
                    && activePath.equals(lastActivePath)
                    && rootPath.equals(lastRootPath)) {
                return;
            }

            scanning = true;
            forceDirty = false;
            lastTextHash = textHash;
            lastActiveFileMod = activeMod;
            lastActivePath = activePath;
            lastRootPath = rootPath;

            WORKER.execute(() -> {
                try {
                    java.util.List<ProblemItem> list =
                            ProblemsWorkspaceAnalyzer.analyze(appContext, root, text, active, activeOnly, null);
                    mainHandler.post(() -> {
                        scanning = false;
                        if (!stopped) callback.onProblems(list);
                    });
                } catch (Throwable t) {
                    mainHandler.post(() -> scanning = false);
                }
            });
        }
    };

    public LiveProblemsScheduler(android.content.Context context, Sources sources, OnProblems callback) {
        this.appContext = context.getApplicationContext();
        this.sources = sources;
        this.callback = callback;
    }

    public void setInterval(long ms) {
        this.intervalMs = Math.max(MIN_DEBOUNCE_MS, ms);
    }

    /** Forces the next scheduled run to perform a full re-analysis even if hash matches. */
    public void invalidate() {
        forceDirty = true;
        lastTextHash = 0;
    }

    /** Schedules a debounced scan (e.g. after user finishes typing or changes tab). */
    public void scheduleScan(long delayMs) {
        if (stopped) return;
        mainHandler.removeCallbacks(tick);
        mainHandler.postDelayed(tick, Math.max(100L, delayMs));
    }

    /** Triggers a scan after the debounce the current power profile asks for. */
    public void scheduleScan() {
        scheduleScan(intervalMs);
    }

    public void start() {
        stopped = false;
        scheduleScan(FIRST_DELAY_MS);
    }

    public void stop() {
        stopped = true;
        scanning = false;
        mainHandler.removeCallbacks(tick);
    }
}
