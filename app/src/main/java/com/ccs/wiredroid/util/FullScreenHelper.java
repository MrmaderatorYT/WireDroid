package com.ccs.wiredroid.util;

import android.app.Activity;
import android.os.Build;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;

/**
 * Утиліта для повноекранного immersive mode.
 * Підтримує API 26–35+ (включно з edge-to-edge на API 35).
 */
public final class FullScreenHelper {

    private FullScreenHelper() {}

    public static void enable(Activity activity) {
        if (activity == null || activity.getWindow() == null) return;
        try {
            Window window = activity.getWindow();

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowInsetsController ctrl = window.getInsetsController();
                if (ctrl != null) {
                    ctrl.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                    ctrl.setSystemBarsBehavior(
                            WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
                // API 35+: edge-to-edge is enforced — ensure content draws behind system bars
                if (Build.VERSION.SDK_INT >= 35) {
                    window.setDecorFitsSystemWindows(false);
                }
            } else {
                View decor = window.getDecorView();
                if (decor != null) decor.setSystemUiVisibility(LEGACY_IMMERSIVE_FLAGS);
            }

            // API 28+: display cutout in short edges (for notches)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.getAttributes().layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            }

            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } catch (Exception ignored) {}
    }

    /**
     * Keeps the system bars hidden when a dialog opens.
     *
     * <p>Every dialog is its own window, and a window that takes focus makes
     * Android re-decide whether the bars belong on screen. It decides yes,
     * because the dialog never asked for immersive mode — so the status and
     * navigation bars slide in over a fullscreen editor and stay there.</p>
     *
     * <p>The fix is the order of three steps: add the window without focus, so
     * nothing is re-decided; copy the activity's own bar state onto it; then
     * give the focus back and tell the window manager about it. Without that
     * last update the dialog is shown but cannot be typed into.</p>
     */
    public static void keepImmersive(final android.app.Dialog dialog) {
        if (dialog == null || dialog.getWindow() == null) return;
        final Window window = dialog.getWindow();
        try {
            window.setFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
            // Set on the attributes, not on a view: these are read when the
            // window is added, so its very first frame is already immersive.
            window.getAttributes().systemUiVisibility = LEGACY_IMMERSIVE_FLAGS;
            if (Build.VERSION.SDK_INT >= 35) window.setDecorFitsSystemWindows(false);
            dialog.setOnShowListener(d -> {
                try {
                    hideBars(window, dialog.getContext());
                    window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
                    android.view.WindowManager manager = window.getWindowManager();
                    if (manager != null) {
                        manager.updateViewLayout(window.getDecorView(), window.getAttributes());
                    }
                } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {}
    }

    /**
     * Marks a view as immersive before the window holding it is added.
     *
     * <p>For a popup, which has no {@code Window} to configure — only the view it
     * was handed. Applied up front rather than after the window appears: fixing
     * it afterwards is what produces a flash of the bars instead of no bars.</p>
     *
     * <p>Pairs with a non-focusable popup. Focus is what makes Android re-decide
     * about the bars in the first place; this covers the older devices where the
     * flags on the view still matter.</p>
     */
    public static void markImmersive(Activity activity, View content) {
        if (content == null) return;
        try {
            int flags = LEGACY_IMMERSIVE_FLAGS;
            if (activity != null && activity.getWindow() != null
                    && activity.getWindow().getDecorView() != null) {
                // The activity's own state, so a screen that is deliberately not
                // fullscreen does not get dragged into it.
                flags = activity.getWindow().getDecorView().getSystemUiVisibility();
            }
            content.setSystemUiVisibility(flags);
        } catch (Exception ignored) {}
    }

    /** Puts one window into the same state {@link #enable} puts an activity. */
    private static void hideBars(Window window, android.content.Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController ctrl = window.getInsetsController();
            if (ctrl != null) {
                ctrl.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                ctrl.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
            return;
        }
        View decor = window.getDecorView();
        if (decor != null) decor.setSystemUiVisibility(LEGACY_IMMERSIVE_FLAGS);
    }

    /** The pre-API-30 way of saying the same thing, kept in one place. */
    private static final int LEGACY_IMMERSIVE_FLAGS =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            | View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
}
