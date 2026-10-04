package com.ccs.wiredroid.project;

import android.content.Context;

import com.ccs.wiredroid.util.AppPreferences;

import java.io.File;

/**
 * Whether a project builds its C sources, and with which compiler.
 *
 * <p>Per project rather than per app, because it is a property of the code, not
 * of the person: one project has a JNI layer and another has a stray {@code .c}
 * that is documentation. A global switch forces the same answer on both, and the
 * cost of getting it wrong is either a silent skip or an expensive toolchain
 * setup nobody asked for.</p>
 *
 * <p>Stored the way {@link ProjectJdk} stores its fallback — a preference keyed
 * by the project path — so nothing has to be written into the user's build file
 * for a setting Maven and Gradle have no place for.</p>
 */
public final class ProjectNative {

    private static final String K_ENABLED = "project_native_enabled:";
    private static final String K_BACKEND = "project_native_backend:";

    private ProjectNative() {
    }

    /**
     * @return true when this project's native sources should be compiled;
     *         projects created before this setting existed fall back to the
     *         old global preference so their behaviour does not change
     */
    public static boolean isEnabled(Context context, File projectRoot) {
        AppPreferences prefs = new AppPreferences(context);
        if (projectRoot == null) return prefs.isNativeEnabled();
        String key = K_ENABLED + projectRoot.getAbsolutePath();
        if (!prefs.raw().contains(key)) return prefs.isNativeEnabled();
        return prefs.raw().getBoolean(key, false);
    }

    /** {@link AppPreferences#NATIVE_TCC} or {@link AppPreferences#NATIVE_NDK}. */
    public static String backend(Context context, File projectRoot) {
        AppPreferences prefs = new AppPreferences(context);
        if (projectRoot == null) return prefs.getNativeBackend();
        String stored = prefs.raw().getString(K_BACKEND + projectRoot.getAbsolutePath(), null);
        return stored != null ? stored : prefs.getNativeBackend();
    }

    public static void set(Context context, File projectRoot, boolean enabled, String backend) {
        if (projectRoot == null) return;
        String path = projectRoot.getAbsolutePath();
        new AppPreferences(context).raw().edit()
                .putBoolean(K_ENABLED + path, enabled)
                .putString(K_BACKEND + path,
                        AppPreferences.NATIVE_NDK.equals(backend)
                                ? AppPreferences.NATIVE_NDK : AppPreferences.NATIVE_TCC)
                .apply();
    }

    /** Forgets a project's answer, so a deleted path leaves nothing behind. */
    public static void forget(Context context, File projectRoot) {
        if (projectRoot == null) return;
        String path = projectRoot.getAbsolutePath();
        new AppPreferences(context).raw().edit()
                .remove(K_ENABLED + path)
                .remove(K_BACKEND + path)
                .apply();
    }
}
