package com.ccs.wiredroid.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.ccs.wiredroid.R;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public class ColorPickerDialog {

    public interface OnColorSelectedListener {
        void onColorSelected(String hexColor, int colorInt);
    }

    public static void show(Context context, String initialHex, OnColorSelectedListener listener) {
        int initialColor = parseColorSafely(initialHex, Color.RED);
        float[] hsv = new float[3];
        Color.colorToHSV(initialColor, hsv);

        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 32, 48, 32);

        // Preview square
        View previewView = new View(context);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 120);
        previewParams.setMargins(0, 0, 0, 32);
        previewView.setLayoutParams(previewParams);
        
        GradientDrawable previewBg = new GradientDrawable();
        previewBg.setColor(initialColor);
        previewBg.setCornerRadius(16);
        previewView.setBackground(previewBg);
        layout.addView(previewView);

        // Hex input
        EditText hexInput = new EditText(context);
        hexInput.setHint("#FF0000");
        hexInput.setText(String.format("#%06X", (0xFFFFFF & initialColor)));
        hexInput.setGravity(Gravity.CENTER);
        layout.addView(hexInput);

        // Hue slider
        TextView hueLabel = new TextView(context);
        hueLabel.setText("Hue");
        layout.addView(hueLabel);

        SeekBar hueSeekBar = new SeekBar(context);
        hueSeekBar.setMax(360);
        hueSeekBar.setProgress((int) hsv[0]);
        layout.addView(hueSeekBar);

        // Saturation slider
        TextView satLabel = new TextView(context);
        satLabel.setText("Saturation");
        layout.addView(satLabel);

        SeekBar satSeekBar = new SeekBar(context);
        satSeekBar.setMax(100);
        satSeekBar.setProgress((int) (hsv[1] * 100));
        layout.addView(satSeekBar);

        // Value/Brightness slider
        TextView valLabel = new TextView(context);
        valLabel.setText("Brightness");
        layout.addView(valLabel);

        SeekBar valSeekBar = new SeekBar(context);
        valSeekBar.setMax(100);
        valSeekBar.setProgress((int) (hsv[2] * 100));
        layout.addView(valSeekBar);

        final int[] currentColor = {initialColor};

        Runnable updateColor = () -> {
            float h = hueSeekBar.getProgress();
            float s = satSeekBar.getProgress() / 100f;
            float v = valSeekBar.getProgress() / 100f;
            currentColor[0] = Color.HSVToColor(new float[]{h, s, v});
            previewBg.setColor(currentColor[0]);
            String hexStr = String.format("#%06X", (0xFFFFFF & currentColor[0]));
            if (!hexInput.hasFocus()) {
                hexInput.setText(hexStr);
            }
        };

        SeekBar.OnSeekBarChangeListener seekListener = new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) { updateColor.run(); }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        };

        hueSeekBar.setOnSeekBarChangeListener(seekListener);
        satSeekBar.setOnSeekBarChangeListener(seekListener);
        valSeekBar.setOnSeekBarChangeListener(seekListener);

        hexInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                try {
                    int parsed = Color.parseColor(s.toString().trim());
                    currentColor[0] = parsed;
                    previewBg.setColor(parsed);
                } catch (Exception ignored) {}
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        com.ccs.wiredroid.ui.Dialogs.rounded(context)
                .setTitle("Color Picker")
                .setView(layout)
                .setPositiveButton("Apply", (dialog, which) -> {
                    String hexStr = String.format("#%06X", (0xFFFFFF & currentColor[0]));
                    if (listener != null) listener.onColorSelected(hexStr, currentColor[0]);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static int parseColorSafely(String hex, int fallback) {
        if (hex == null || hex.trim().isEmpty()) return fallback;
        try {
            return Color.parseColor(hex.trim());
        } catch (Exception e) {
            return fallback;
        }
    }
}
