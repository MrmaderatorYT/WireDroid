package com.ccs.wiredroid.profiler;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Debug;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Process-wide performance telemetry with bounded, local-only snapshots. */
public final class PerformanceMonitor {
    private static final long SNAPSHOT_INTERVAL_MS = 5 * 60_000L;
    private static final long RETENTION_MS = 30L * 24 * 60 * 60_000L;
    private static final int MAX_SNAPSHOTS = 144;
    private static volatile PerformanceMonitor instance;

    public static PerformanceMonitor init(Context context) {
        if (instance == null) synchronized (PerformanceMonitor.class) {
            if (instance == null) instance = new PerformanceMonitor(context.getApplicationContext());
        }
        return instance;
    }

    public static PerformanceMonitor get() { return instance; }

    public static final class Timer implements AutoCloseable {
        private final PerformanceMonitor owner;
        private final String name;
        private final long started = SystemClock.elapsedRealtimeNanos();
        private boolean closed;
        private Timer(PerformanceMonitor owner, String name) { this.owner = owner; this.name = name; }
        @Override public void close() {
            if (!closed) {
                closed = true;
                owner.recordDuration(name, SystemClock.elapsedRealtimeNanos() - started);
            }
        }
    }

    private static final class Metric {
        final AtomicLong count = new AtomicLong();
        final AtomicLong totalNs = new AtomicLong();
        final AtomicLong maxNs = new AtomicLong();
    }

    private final Context context;
    private final long processStartedMs = System.currentTimeMillis();
    private final long processStartedElapsed = SystemClock.elapsedRealtime();
    private final long allocationBaseline;
    private final long energyBaseline;
    private final File snapshotsFile;
    private final boolean declaredLowRam;
    private final ConcurrentHashMap<String, Metric> timings = new ConcurrentHashMap<>();
    private final AtomicInteger queuedTasks = new AtomicInteger();
    private final AtomicInteger activeTasks = new AtomicInteger();
    private final AtomicInteger peakQueuedTasks = new AtomicInteger();
    private volatile boolean systemLowMemory;
    private volatile long lastSnapshotAt;
    private final Handler snapshotHandler = new Handler(Looper.getMainLooper());
    private final Runnable periodicSnapshot = new Runnable() {
        @Override public void run() {
            snapshot("periodic");
            snapshotHandler.postDelayed(this, SNAPSHOT_INTERVAL_MS);
        }
    };

    private PerformanceMonitor(Context context) {
        this.context = context;
        allocationBaseline = allocatedBytes();
        energyBaseline = energyNwh();
        snapshotsFile = new File(context.getFilesDir(), "performance-snapshots.jsonl");
        ActivityManager activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        declaredLowRam = activityManager != null && activityManager.isLowRamDevice();
        pruneSnapshots();
        snapshotHandler.postDelayed(periodicSnapshot, SNAPSHOT_INTERVAL_MS);
    }

    public Timer timer(String name) { return new Timer(this, name); }

    public void recordDuration(String name, long durationNs) {
        if (name == null || durationNs < 0) return;
        Metric metric = timings.computeIfAbsent(name, ignored -> new Metric());
        metric.count.incrementAndGet();
        metric.totalNs.addAndGet(durationNs);
        metric.maxNs.accumulateAndGet(durationNs, Math::max);
        snapshotIfDue();
    }

    public void taskQueued() {
        int count = queuedTasks.incrementAndGet();
        peakQueuedTasks.accumulateAndGet(count, Math::max);
    }

    public void taskStarted() {
        queuedTasks.updateAndGet(value -> Math.max(0, value - 1));
        activeTasks.incrementAndGet();
    }

    public void taskFinished() { activeTasks.updateAndGet(value -> Math.max(0, value - 1)); }

    public void onTrimMemory(int level) {
        systemLowMemory = level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW;
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) snapshot("memory-critical");
    }

    public void onLowMemory() {
        systemLowMemory = true;
        snapshot("onLowMemory");
    }

    public boolean isLowMemoryDevice() {
        Runtime runtime = Runtime.getRuntime();
        double pressure = (runtime.totalMemory() - runtime.freeMemory()) / (double) runtime.maxMemory();
        return declaredLowRam || systemLowMemory || runtime.maxMemory() <= 256L * 1024 * 1024 || pressure >= 0.82;
    }

    public synchronized void snapshot(String reason) {
        try {
            JSONObject json = currentSnapshot(reason);
            try (FileOutputStream output = new FileOutputStream(snapshotsFile, true)) {
                output.write((json.toString() + "\n").getBytes(StandardCharsets.UTF_8));
            }
            lastSnapshotAt = SystemClock.elapsedRealtime();
            pruneSnapshots();
        } catch (Exception ignored) {}
    }

    private void snapshotIfDue() {
        if (SystemClock.elapsedRealtime() - lastSnapshotAt >= SNAPSHOT_INTERVAL_MS) snapshot("periodic");
    }

    public JSONObject currentSnapshot(String reason) throws Exception {
        Runtime runtime = Runtime.getRuntime();
        JSONObject json = new JSONObject();
        json.put("timestamp", System.currentTimeMillis());
        json.put("reason", reason);
        json.put("uptimeMs", SystemClock.elapsedRealtime() - processStartedElapsed);
        json.put("heapUsedBytes", runtime.totalMemory() - runtime.freeMemory());
        json.put("heapMaxBytes", runtime.maxMemory());
        json.put("allocatedBytes", Math.max(0, allocatedBytes() - allocationBaseline));
        json.put("batteryPercent", batteryPercent());
        json.put("batteryTemperatureC", batteryTemperature());
        json.put("thermalStatus", thermalStatus());
        long energy = energyNwh();
        json.put("energyConsumedNwh", energy > 0 && energyBaseline > 0
                ? Math.max(0, energyBaseline - energy) : JSONObject.NULL);
        json.put("queuedTasks", queuedTasks.get());
        json.put("activeTasks", activeTasks.get());
        json.put("peakQueuedTasks", peakQueuedTasks.get());
        json.put("lowMemoryMode", isLowMemoryDevice());
        JSONObject durationJson = new JSONObject();
        for (Map.Entry<String, Metric> entry : timings.entrySet()) {
            Metric metric = entry.getValue();
            JSONObject value = new JSONObject();
            long count = metric.count.get();
            value.put("count", count);
            value.put("totalMs", metric.totalNs.get() / 1_000_000.0);
            value.put("averageMs", count == 0 ? 0 : metric.totalNs.get() / 1_000_000.0 / count);
            value.put("maxMs", metric.maxNs.get() / 1_000_000.0);
            durationJson.put(entry.getKey(), value);
        }
        json.put("timings", durationJson);
        return json;
    }

    public String report() {
        Runtime runtime = Runtime.getRuntime();
        long used = runtime.totalMemory() - runtime.freeMemory();
        long energy = energyNwh();
        StringBuilder report = new StringBuilder();
        report.append("PERFORMANCE OVERVIEW\n")
                .append("Startup / uptime: ").append(SystemClock.elapsedRealtime() - processStartedElapsed).append(" ms\n")
                .append(String.format(Locale.US, "Heap: %.1f / %.1f MB\n", used / 1048576.0, runtime.maxMemory() / 1048576.0))
                .append(String.format(Locale.US, "Allocations: %.1f MB\n", Math.max(0, allocatedBytes() - allocationBaseline) / 1048576.0))
                .append("Battery: ").append(batteryPercent()).append("%, ")
                .append(String.format(Locale.US, "%.1f °C", batteryTemperature())).append('\n')
                .append("Thermal status: ").append(thermalStatus()).append('\n')
                .append("Battery energy used: ").append(energy > 0 && energyBaseline > 0
                        ? String.format(Locale.US, "%.3f mWh", Math.max(0, energyBaseline - energy) / 1_000_000.0)
                        : "not exposed by device").append('\n')
                .append("Background queue: ").append(queuedTasks.get()).append(" queued, ")
                .append(activeTasks.get()).append(" active, peak ").append(peakQueuedTasks.get()).append('\n')
                .append("Low-memory mode: ").append(isLowMemoryDevice() ? "ACTIVE" : "inactive").append("\n\nTIMINGS\n");
        List<Map.Entry<String, Metric>> entries = new ArrayList<>(timings.entrySet());
        entries.sort((a, b) -> Long.compare(b.getValue().totalNs.get(), a.getValue().totalNs.get()));
        for (Map.Entry<String, Metric> entry : entries) {
            Metric metric = entry.getValue();
            long count = metric.count.get();
            report.append(String.format(Locale.US, "%-24s %5d × avg %7.2f ms, max %7.2f ms\n",
                    entry.getKey(), count, count == 0 ? 0 : metric.totalNs.get() / 1_000_000.0 / count,
                    metric.maxNs.get() / 1_000_000.0));
        }
        report.append("\nSaved snapshots: ").append(snapshotCount()).append(" (30-day retention)");
        return report.toString();
    }

    public int snapshotCount() {
        int count = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(snapshotsFile), StandardCharsets.UTF_8))) {
            while (reader.readLine() != null) count++;
        } catch (Exception ignored) {}
        return count;
    }

    private synchronized void pruneSnapshots() {
        if (!snapshotsFile.isFile()) return;
        ArrayDeque<String> kept = new ArrayDeque<>();
        long cutoff = System.currentTimeMillis() - RETENTION_MS;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(snapshotsFile), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                try {
                    if (new JSONObject(line).optLong("timestamp") >= cutoff) {
                        kept.addLast(line);
                        while (kept.size() > MAX_SNAPSHOTS) kept.removeFirst();
                    }
                } catch (Exception ignored) {}
            }
            try (FileOutputStream output = new FileOutputStream(snapshotsFile, false)) {
                for (String value : kept) output.write((value + "\n").getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {}
    }

    private static long allocatedBytes() {
        try {
            String value = Debug.getRuntimeStat("art.gc.bytes-allocated");
            return value == null ? 0 : Long.parseLong(value);
        } catch (Exception ignored) { return 0; }
    }

    private int batteryPercent() {
        Intent battery = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery == null) return -1;
        int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        return level < 0 || scale <= 0 ? -1 : Math.round(level * 100f / scale);
    }

    private float batteryTemperature() {
        Intent battery = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        return battery == null ? Float.NaN
                : battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f;
    }

    private int thermalStatus() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return -1;
        PowerManager manager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return manager == null ? -1 : manager.getCurrentThermalStatus();
    }

    private long energyNwh() {
        try {
            BatteryManager manager = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
            return manager == null ? -1 : manager.getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER);
        } catch (Exception ignored) { return -1; }
    }
}
