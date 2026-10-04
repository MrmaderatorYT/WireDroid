package com.ccs.wiredroid;

import android.app.Application;
import com.google.android.material.color.DynamicColors;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;
import com.ccs.wiredroid.util.AppPreferences;

public class WireDroidApplication extends Application {
    private com.ccs.wiredroid.profiler.PerformanceMonitor performanceMonitor;

    @Override
    public void onCreate() {
        super.onCreate();
        performanceMonitor = com.ccs.wiredroid.profiler.PerformanceMonitor.init(this);
        String language = new AppPreferences(this).getAppLanguage();
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(language));
        // Apply Material You Dynamic Colors across the app on Android 12+
        DynamicColors.applyToActivitiesIfAvailable(this);
        com.ccs.wiredroid.util.StartupTrace.install(this);
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (performanceMonitor != null) performanceMonitor.onTrimMemory(level);
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        if (performanceMonitor != null) performanceMonitor.onLowMemory();
    }
}
