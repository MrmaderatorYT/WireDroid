package com.ccs.wiredroid.util;

import android.app.Activity;
import android.app.Application;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;

import com.ccs.wiredroid.BuildConfig;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * Times how long each screen spends in {@code onCreate}, {@code onStart} and
 * {@code onResume}, and prints one line per screen under the {@code Startup}
 * tag.
 *
 * <p>Metrics are retained locally by {@code PerformanceMonitor}; logcat output
 * is restricted to debug builds. The pre/post callbacks are API 29+; below that the timings
 * are simply not collected rather than being reported wrong.</p>
 */
public final class StartupTrace {

    private static final String TAG = "Startup";

    private StartupTrace() {
    }

    /**
     * A stopwatch for the inside of one screen's {@code onCreate}.
     *
     * <p>{@link #install} says which screen is slow; this says which part of it.
     * Marks are printed as one line under the same {@code Startup} tag when
     * {@link Phases#done()} is called. Outside a debug build every method here
     * does nothing, so call sites can stay in place.</p>
     *
     * <pre>
     * Phases p = StartupTrace.phases("MainActivity");
     * setContentView(...);  p.mark("setContentView");
     * bindViews();          p.mark("bindViews");
     * ...                   p.done();
     * </pre>
     */
    public static Phases phases(@NonNull String screen) {
        return BuildConfig.DEBUG ? new Phases(screen) : NO_PHASES;
    }

    private static final Phases NO_PHASES = new Phases(null);

    public static final class Phases {

        private final String screen;
        private final StringBuilder marks;
        private final long start;
        private long last;

        private Phases(@Nullable String screen) {
            this.screen = screen;
            this.marks = screen == null ? null : new StringBuilder();
            this.start = screen == null ? 0L : SystemClock.uptimeMillis();
            this.last = this.start;
        }

        /** Records the time since the previous mark under {@code name}. */
        public void mark(@NonNull String name) {
            if (marks == null) return;
            long now = SystemClock.uptimeMillis();
            marks.append(' ').append(name).append('=').append(now - last);
            last = now;
        }

        /** Prints every mark plus the total. */
        public void done() {
            if (marks == null) return;
            Log.i(TAG, screen + " phases:" + marks
                    + " TOTAL=" + (SystemClock.uptimeMillis() - start) + "ms");
        }
    }

    public static void install(@NonNull Application app) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return;
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {

            private final Map<Activity, long[]> marks = new HashMap<>();

            /** [preCreate, postCreate, preStart, postStart, preResume]. */
            private long[] slot(Activity a) {
                long[] m = marks.get(a);
                if (m == null) {
                    m = new long[5];
                    marks.put(a, m);
                }
                return m;
            }

            @Override
            public void onActivityPreCreated(@NonNull Activity a, @Nullable Bundle s) {
                slot(a)[0] = SystemClock.uptimeMillis();
            }

            // Deliberately onActivityPostCreated, not onActivityCreated: the
            // latter is dispatched from inside Activity.onCreate's super call,
            // so it fires before setContentView rather than after onCreate has
            // finished, and reports a few milliseconds of preamble as if it
            // were the whole thing.
            @Override
            public void onActivityPostCreated(@NonNull Activity a, @Nullable Bundle s) {
                slot(a)[1] = SystemClock.uptimeMillis();
            }

            @Override
            public void onActivityCreated(@NonNull Activity a, @Nullable Bundle s) {
            }

            @Override
            public void onActivityPreStarted(@NonNull Activity a) {
                slot(a)[2] = SystemClock.uptimeMillis();
            }

            @Override
            public void onActivityPostStarted(@NonNull Activity a) {
                slot(a)[3] = SystemClock.uptimeMillis();
            }

            @Override
            public void onActivityStarted(@NonNull Activity a) {
            }

            @Override
            public void onActivityPreResumed(@NonNull Activity a) {
                slot(a)[4] = SystemClock.uptimeMillis();
            }

            @Override
            public void onActivityPostResumed(@NonNull Activity a) {
                long[] m = marks.get(a);
                if (m == null || m[0] == 0L) return;
                long now = SystemClock.uptimeMillis();
                if (BuildConfig.DEBUG) {
                    Log.i(TAG, String.format(java.util.Locale.ROOT,
                            "%-26s onCreate=%5dms onStart=%4dms onResume=%4dms other=%4dms total=%5dms",
                            a.getClass().getSimpleName(),
                            m[1] - m[0], m[3] - m[2], now - m[4],
                            (m[2] - m[1]) + (m[4] - m[3]), now - m[0]));
                }
                com.ccs.wiredroid.profiler.PerformanceMonitor monitor =
                        com.ccs.wiredroid.profiler.PerformanceMonitor.get();
                if (monitor != null) {
                    monitor.recordDuration("startup." + a.getClass().getSimpleName(),
                            (now - m[0]) * 1_000_000L);
                }
                marks.remove(a);
            }

            @Override public void onActivityResumed(@NonNull Activity a) { }
            @Override public void onActivityPaused(@NonNull Activity a) { }
            @Override public void onActivityStopped(@NonNull Activity a) { }
            @Override public void onActivitySaveInstanceState(@NonNull Activity a, @NonNull Bundle s) { }
            @Override public void onActivityDestroyed(@NonNull Activity a) { marks.remove(a); }
        });
    }
}
