package com.ccs.wiredroid.startup;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.startup.Initializer;

import com.ccs.wiredroid.util.AppPreferences;

import java.util.Collections;
import java.util.List;

/**
 * Ініціалізація налаштувань додатку при старті.
 * Виконується раніше за Activity.onCreate() для мінімізації холодного старту.
 */
public class PreferencesInitializer implements Initializer<AppPreferences> {

    @NonNull
    @Override
    public AppPreferences create(@NonNull Context context) {
        return new AppPreferences(context);
    }

    @NonNull
    @Override
    public List<Class<? extends Initializer<?>>> dependencies() {
        return Collections.emptyList();
    }
}
