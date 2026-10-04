package com.ccs.wiredroid.util;

import android.app.Activity;
import android.graphics.Rect;
import android.os.Build;
import android.view.View;
import android.view.ViewTreeObserver;

import androidx.annotation.NonNull;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * Слухач зміни висоти клавіатури.
 * Викликає callback коли клавіатура з'являється/зникає та коли змінюється її висота.
 *
 * Використання:
 *   keyboardListener = new KeyboardHeightListener(activity, keyboardHeight -> {
 *       // keyboardHeight = 0 коли клавіатура прихована
 *       // keyboardHeight > 0 коли клавіатура видима
 *       editorContainer.setPadding(0, 0, 0, keyboardHeight);
 *   });
 *   keyboardListener.start();
 *   // в onStop:
 *   keyboardListener.stop();
 */
public final class KeyboardHeightListener {

    public interface OnKeyboardHeightChangedListener {
        void onKeyboardHeightChanged(int heightPx);
    }

    private final Activity activity;
    private final OnKeyboardHeightChangedListener listener;
    private int lastHeight = -1;
    private ViewTreeObserver.OnGlobalLayoutListener layoutListener;
    private boolean started = false;

    public KeyboardHeightListener(@NonNull Activity activity, @NonNull OnKeyboardHeightChangedListener listener) {
        this.activity = activity;
        this.listener = listener;
    }

    public void start() {
        if (started) return;
        started = true;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // API 30+ використовуємо WindowInsets
            View decorView = activity.getWindow().getDecorView();
            ViewCompat.setOnApplyWindowInsetsListener(decorView, (v, insets) -> {
                int height = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
                if (height != lastHeight) {
                    lastHeight = height;
                    listener.onKeyboardHeightChanged(height);
                }
                return insets;
            });
            // Тригер для першого визу
            decorView.requestApplyInsets();
        } else {
            // API < 30 використовуємо GlobalLayoutListener
            View rootView = activity.findViewById(android.R.id.content);
            layoutListener = () -> {
                Rect r = new Rect();
                rootView.getWindowVisibleDisplayFrame(r);
                int screenHeight = rootView.getRootView().getHeight();
                int height = screenHeight - r.bottom;
                if (height < 0) height = 0;
                if (height != lastHeight) {
                    lastHeight = height;
                    listener.onKeyboardHeightChanged(height);
                }
            };
            rootView.getViewTreeObserver().addOnGlobalLayoutListener(layoutListener);
        }
    }

    public void stop() {
        if (!started) return;
        started = false;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            View decorView = activity.getWindow().getDecorView();
            ViewCompat.setOnApplyWindowInsetsListener(decorView, null);
        } else {
            if (layoutListener != null) {
                View rootView = activity.findViewById(android.R.id.content);
                rootView.getViewTreeObserver().removeOnGlobalLayoutListener(layoutListener);
                layoutListener = null;
            }
        }
        lastHeight = -1;
    }

    public int getLastKeyboardHeight() {
        return lastHeight;
    }

    public boolean isKeyboardVisible() {
        return lastHeight > 0;
    }
}
