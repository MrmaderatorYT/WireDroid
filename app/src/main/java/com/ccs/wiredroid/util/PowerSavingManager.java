package com.ccs.wiredroid.util;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Zero-overhead dynamic power saving and battery monitor.
 *
 * <p>Instead of polling the system or executing expensive IPC binder round-trips
 * on every keystroke, this manager listens to system power/battery broadcasts and
 * thermal status changes. Queries like {@link #isPowerSavingActive()} are instant
 * in-memory O(1) reads with zero IPC latency and zero object allocations.</p>
 */
public final class PowerSavingManager {
    public static final int MODE_AUTO = 0;
    public static final int MODE_DISABLED = 1;
    public static final int MODE_ALWAYS_PERFORMANCE = 2;
    public static final int MODE_ALWAYS_SAVING = 3;

    public interface Listener {
        void onPowerSavingStateChanged(boolean powerSavingActive);
    }

    private final AppPreferences prefs;
    private final Context context;
    private final PowerManager powerManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    private volatile boolean cachedSaving;
    private volatile boolean cachedCharging;
    private volatile int cachedBatteryLevel = 100;
    private volatile boolean cachedSystemPowerSave;
    private volatile boolean cachedThermal;
    private volatile boolean receiverRegistered;

    private final BroadcastReceiver powerReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String action = intent.getAction();
            if (Intent.ACTION_BATTERY_CHANGED.equals(action)) {
                updateBatteryFromIntent(intent);
            } else if (PowerManager.ACTION_POWER_SAVE_MODE_CHANGED.equals(action)
                    || Intent.ACTION_POWER_CONNECTED.equals(action)
                    || Intent.ACTION_POWER_DISCONNECTED.equals(action)) {
                recomputeState();
            }
        }
    };

    private Object thermalListener;

    public PowerSavingManager(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = new AppPreferences(context);
        this.powerManager = (PowerManager) this.context.getSystemService(Context.POWER_SERVICE);

        // Initial sample
        sampleInitialState();
        registerReceivers();
    }

    public void addListener(Listener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(Listener listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    private void registerReceivers() {
        if (receiverRegistered) return;
        try {
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_BATTERY_CHANGED);
            filter.addAction(Intent.ACTION_POWER_CONNECTED);
            filter.addAction(Intent.ACTION_POWER_DISCONNECTED);
            filter.addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
            context.registerReceiver(powerReceiver, filter);
            receiverRegistered = true;
        } catch (Throwable ignored) {}

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && powerManager != null) {
            try {
                PowerManager.OnThermalStatusChangedListener tl = status -> {
                    cachedThermal = status >= PowerManager.THERMAL_STATUS_MODERATE;
                    recomputeState();
                };
                powerManager.addThermalStatusListener(tl);
                thermalListener = tl;
            } catch (Throwable ignored) {}
        }
    }

    public void destroy() {
        if (receiverRegistered) {
            try {
                context.unregisterReceiver(powerReceiver);
            } catch (Throwable ignored) {}
            receiverRegistered = false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && powerManager != null && thermalListener != null) {
            try {
                powerManager.removeThermalStatusListener((PowerManager.OnThermalStatusChangedListener) thermalListener);
            } catch (Throwable ignored) {}
            thermalListener = null;
        }
        listeners.clear();
    }

    private void sampleInitialState() {
        try {
            Intent battery = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (battery != null) {
                updateBatteryFromIntent(battery);
            } else {
                recomputeState();
            }
        } catch (Throwable t) {
            recomputeState();
        }
    }

    private void updateBatteryFromIntent(Intent battery) {
        int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        cachedCharging = status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;

        int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        if (level >= 0 && scale > 0) {
            cachedBatteryLevel = (int) ((level / (float) scale) * 100);
        }

        recomputeState();
    }

    private synchronized void recomputeState() {
        if (powerManager != null && prefs.isPowerSavingRespectSystem()) {
            try {
                cachedSystemPowerSave = powerManager.isPowerSaveMode();
            } catch (Throwable ignored) {}
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && powerManager != null) {
            try {
                cachedThermal = powerManager.getCurrentThermalStatus() >= PowerManager.THERMAL_STATUS_MODERATE;
            } catch (Throwable ignored) {}
        }

        boolean previous = cachedSaving;
        int mode = getMode();
        boolean newSaving;
        switch (mode) {
            case MODE_DISABLED:
            case MODE_ALWAYS_PERFORMANCE:
                newSaving = false;
                break;
            case MODE_ALWAYS_SAVING:
                newSaving = true;
                break;
            case MODE_AUTO:
            default:
                newSaving = cachedSystemPowerSave
                        || (!cachedCharging && cachedBatteryLevel < prefs.getPowerSavingBatteryThreshold())
                        || cachedThermal;
                break;
        }

        cachedSaving = newSaving;
        if (previous != newSaving) {
            notifyListeners(newSaving);
        }
    }

    private void notifyListeners(boolean active) {
        mainHandler.post(() -> {
            for (Listener l : listeners) {
                try {
                    l.onPowerSavingStateChanged(active);
                } catch (Throwable ignored) {}
            }
        });
    }

    public int getMode() {
        return prefs.getPowerSavingMode();
    }

    public void setMode(int mode) {
        prefs.setPowerSavingMode(mode);
        recomputeState();
    }

    public void invalidate() {
        recomputeState();
    }

    public boolean isPowerSavingActive() {
        com.ccs.wiredroid.profiler.PerformanceMonitor monitor =
                com.ccs.wiredroid.profiler.PerformanceMonitor.get();
        if (monitor != null && monitor.isLowMemoryDevice()) return true;
        int mode = getMode();
        switch (mode) {
            case MODE_DISABLED:
            case MODE_ALWAYS_PERFORMANCE:
                return false;
            case MODE_ALWAYS_SAVING:
                return true;
            case MODE_AUTO:
            default:
                return cachedSaving;
        }
    }

    public boolean isPerformanceMode() {
        int mode = getMode();
        if (mode == MODE_ALWAYS_PERFORMANCE) {
            return true;
        }
        if (mode == MODE_AUTO) {
            return !cachedSaving;
        }
        return false;
    }

    public int getProblemsScanIntervalMs() {
        if (isPowerSavingActive()) {
            return prefs.getPowerSavingScanIntervalSec() * 1000;
        }
        if (isPerformanceMode()) {
            return 1_200;
        }
        return 2_500;
    }

    public boolean shouldAutoSave() {
        if (isPowerSavingActive()) {
            return prefs.isPsAutoSave();
        }
        return isPerformanceMode() ? prefs.isPerfAutoSave() : prefs.isAutoSave();
    }

    public boolean shouldAutoSearch() {
        if (isPowerSavingActive()) {
            return prefs.isPsAutoSearch();
        }
        return isPerformanceMode() ? prefs.isPerfAutoSearch() : prefs.isAutoSearchEnabled();
    }

    public boolean shouldFormatOnSave() {
        if (isPowerSavingActive()) {
            return prefs.isPsFormatOnSave();
        }
        return isPerformanceMode() ? prefs.isPerfFormatOnSave() : prefs.isFormatOnSave();
    }

    public boolean shouldLogVerbose() {
        if (isPowerSavingActive()) {
            return prefs.isPsVerboseLogging();
        }
        return isPerformanceMode() ? prefs.isPerfVerboseLogging() : prefs.isVerboseLoggingEnabled();
    }

    public boolean shouldReduceAnimations() {
        return isPowerSavingActive() && prefs.isPowerSavingReduceAnimations();
    }

    public boolean shouldUseMinimap() {
        if (!prefs.isMinimap()) {
            return false;
        }
        if (isPowerSavingActive()) {
            return prefs.isPsMinimap();
        }
        return isPerformanceMode() ? prefs.isPerfMinimap() : prefs.isMinimap();
    }

    public boolean shouldUseAstHighlighting() {
        if (!prefs.isAstHighlighting()) {
            return false;
        }
        if (isPowerSavingActive()) {
            return prefs.isPsAstHighlighting();
        }
        return isPerformanceMode() ? prefs.isPerfAstHighlighting() : prefs.isAstHighlighting();
    }

    public boolean shouldRunInlayHints() {
        if (!prefs.isInlayHintsEnabled()) return false;
        if (isPowerSavingActive()) return prefs.isPsInlayHints();
        return isPerformanceMode() ? prefs.isPerfInlayHints() : true;
    }

    public boolean shouldRunGitGutter() {
        if (isPowerSavingActive()) return prefs.isPsGitGutter();
        return isPerformanceMode() ? prefs.isPerfGitGutter() : true;
    }

    public int getLiveMetricsIntervalMs() {
        if (isPowerSavingActive()) return 2000;
        return isPerformanceMode() ? 500 : 1000;
    }

    public boolean shouldRunTodoScan() {
        return shouldRunLiveProblems();
    }

    public boolean shouldRunLiveProblems() {
        if (isPowerSavingActive()) {
            return prefs.isPsLiveProblems();
        }
        return isPerformanceMode() ? prefs.isPerfLiveProblems() : prefs.isLiveProblemsEnabled();
    }

    public boolean isCharging() {
        return cachedCharging;
    }

    public int getBatteryLevel() {
        return cachedBatteryLevel;
    }

    public boolean isSystemPowerSaveMode() {
        return cachedSystemPowerSave;
    }

    public boolean isThermalPressure() {
        return cachedThermal;
    }
}
