package com.ccs.wiredroid.ui;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.ccs.wiredroid.util.AppTheme;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * Dialog construction shared across screens.
 *
 * <p>Shared dialog styling and creation helper.</p>
 */
public final class Dialogs {

    private Dialogs() {}

    /**
     * A Material dialog builder with the app's rounded styling.
     *
     * <p>Every dialog in the app comes from here, which is also where the
     * fullscreen fix belongs: a dialog window that takes focus makes Android
     * show the system bars again, over an editor that asked to be fullscreen.
     * Doing it in the builder means no screen has to remember to.</p>
     */
    public static MaterialAlertDialogBuilder rounded(Context context) {
        return new ImmersiveBuilder(context);
    }

    /**
     * Builds dialogs that leave the system bars hidden.
     *
     * <p>Only {@code create} is overridden: {@code show} builds through it, so
     * both routes are covered by the one hook.</p>
     */
    private static final class ImmersiveBuilder extends MaterialAlertDialogBuilder {
        ImmersiveBuilder(Context context) {
            super(context);
        }

        @Override
        public AlertDialog create() {
            AlertDialog dialog = super.create();
            com.ccs.wiredroid.util.FullScreenHelper.keepImmersive(dialog);
            return dialog;
        }
    }

    /**
     * Styles an AlertDialog instance to match the application's active theme:
     * sets window background with rounded corners and border, tints title, message, and action buttons.
     */
    public static void style(AlertDialog dialog, AppTheme theme) {
        if (dialog == null || theme == null) return;
        if (dialog.getWindow() != null) {
            float density = dialog.getContext().getResources().getDisplayMetrics().density;
            GradientDrawable gd = new GradientDrawable();
            gd.setColor(theme.bg);
            gd.setCornerRadius(16 * density);
            gd.setStroke((int) (1 * density), theme.separator);
            dialog.getWindow().setBackgroundDrawable(gd);
        }
        TextView titleView = dialog.findViewById(androidx.appcompat.R.id.alertTitle);
        if (titleView != null) {
            titleView.setTextColor(theme.text);
        }
        TextView messageView = dialog.findViewById(android.R.id.message);
        if (messageView != null) {
            messageView.setTextColor(theme.textDim);
        }
        Button posBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (posBtn != null) {
            posBtn.setTextColor(theme.accent);
        }
        Button negBtn = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
        if (negBtn != null) {
            negBtn.setTextColor(theme.accent);
        }
        Button neuBtn = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
        if (neuBtn != null) {
            neuBtn.setTextColor(theme.textDim);
        }
    }
}
