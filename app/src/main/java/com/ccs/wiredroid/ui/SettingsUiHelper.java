package com.ccs.wiredroid.ui;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.ccs.wiredroid.R;
import com.ccs.wiredroid.util.AppPreferences;
import com.ccs.wiredroid.util.AppTheme;

import java.util.Locale;

/**
 * Reusable programmatic UI widgets and helpers for SettingsActivity.
 */
public final class SettingsUiHelper {

    public interface BoolSetter {
        void set(boolean v);
    }

    public interface IntConsumer {
        void accept(int v);
    }

    public interface ColorChosen {
        void onColor(int color);
    }

    private SettingsUiHelper() {}

    public static int dp(Context context, int v) {
        return (int) (v * context.getResources().getDisplayMetrics().density);
    }

    public static String toHex(int color) {
        return String.format(Locale.US, "#%06X", 0xFFFFFF & color);
    }

    public static boolean sameText(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    public static View spacer(Context context, int h) {
        View v = new View(context);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, h));
        return v;
    }

    public static LinearLayout newSection(Context context, AppTheme theme, String title) {
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(context, 16);
        box.setLayoutParams(lp);

        TextView header = new TextView(context);
        header.setText(title);
        header.setTextColor(theme.accent);
        header.setTextSize(11);
        header.setLetterSpacing(0.08f);
        header.setAllCaps(true);
        header.setPadding(0, 0, 0, dp(context, 8));
        box.addView(header);

        View sep = new View(context);
        sep.setBackgroundColor(theme.separator);
        sep.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 1)));
        box.addView(sep);

        return box;
    }

    public static TextView label(Context context, AppTheme theme, String s) {
        TextView t = new TextView(context);
        t.setText(s);
        t.setTextColor(theme.text);
        t.setTextSize(13);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(context, 10);
        t.setLayoutParams(lp);
        return t;
    }

    public static TextView subtitle(Context context, AppTheme theme, String s) {
        TextView t = new TextView(context);
        t.setText(s);
        t.setTextColor(theme.textDim);
        t.setTextSize(12);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(context, 4);
        t.setLayoutParams(lp);
        return t;
    }

    public static View buildHint(Context context, AppTheme theme, String text) {
        TextView hint = new TextView(context);
        hint.setText(text);
        hint.setTextColor(theme.textDim);
        hint.setTextSize(11);
        hint.setPadding(dp(context, 4), 0, dp(context, 4), dp(context, 8));
        return hint;
    }

    public static View settingLabel(Context context, AppTheme theme, String title, String description) {
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(label(context, theme, title));
        if (description != null && !description.isEmpty()) {
            box.addView(buildHint(context, theme, description));
        }
        return box;
    }

    public static TextView actionText(Context context, String text, int color) {
        TextView action = new TextView(context);
        action.setText(text);
        action.setTextColor(color);
        action.setTextSize(13);
        action.setGravity(Gravity.CENTER);
        action.setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10));
        action.setClickable(true);
        action.setFocusable(true);
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.TRANSPARENT);
        background.setStroke(dp(context, 1), color);
        background.setCornerRadius(dp(context, 6));
        action.setBackground(background);
        return action;
    }

    public static Spinner newSpinner(Context context, AppTheme theme, String[] items) {
        Spinner sp = new Spinner(context);
        ArrayAdapter<String> ad = new ArrayAdapter<String>(context,
                android.R.layout.simple_spinner_item, items) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView v = (TextView) super.getView(position, convertView, parent);
                v.setTextColor(theme.text);
                return v;
            }
        };
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(ad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(context, 4);
        sp.setLayoutParams(lp);
        return sp;
    }

    public static View buildSwitch(Context context, AppTheme theme, String title, boolean initial, BoolSetter setter) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(context, 8);
        row.setLayoutParams(rlp);

        TextView t = new TextView(context);
        t.setText(title);
        t.setTextColor(theme.text);
        t.setTextSize(13);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        t.setLayoutParams(tlp);

        Switch sw = new Switch(context);
        sw.setContentDescription(title);
        sw.setChecked(initial);
        sw.setOnCheckedChangeListener((CompoundButton b, boolean v) -> setter.set(v));

        row.addView(t);
        row.addView(sw);
        return row;
    }

    public static View buildSwitch(Context context, AppTheme theme, String title, String description, boolean initial, BoolSetter setter) {
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(buildSwitch(context, theme, title, initial, setter));
        if (description != null && !description.isEmpty()) {
            box.addView(buildHint(context, theme, description));
        }
        return box;
    }

    public static SeekBar.OnSeekBarChangeListener simpleSeek(IntConsumer cb) {
        return new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int progress, boolean fromUser) { cb.accept(progress); }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        };
    }

    public static View swatchRow(Context context, int c1, int c2) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);

        View a = new View(context);
        a.setBackgroundColor(c1);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(0, dp(context, 10), 1f);
        ap.setMargins(0, 0, dp(context, 2), 0);
        a.setLayoutParams(ap);

        View b = new View(context);
        b.setBackgroundColor(c2);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, dp(context, 10), 2f);
        b.setLayoutParams(bp);

        row.addView(a);
        row.addView(b);
        return row;
    }

    public static View colorPickerRow(Activity activity, AppTheme theme, LinearLayout container,
                                      String labelText, String description, int currentColor, ColorChosen onChosen) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(activity, 6), 0, dp(activity, 6));
        android.util.TypedValue tv = new android.util.TypedValue();
        activity.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        if (tv.resourceId != 0) row.setBackgroundResource(tv.resourceId);
        row.setClickable(true);
        row.setFocusable(true);

        LinearLayout text = new LinearLayout(activity);
        text.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        text.setLayoutParams(tlp);
        TextView t = label(activity, theme, labelText);
        t.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        text.addView(t);
        if (description != null && !description.isEmpty()) {
            text.addView(buildHint(activity, theme, description));
        }

        View swatch = new View(activity);
        GradientDrawable d = new GradientDrawable();
        d.setColor(currentColor);
        d.setCornerRadius(dp(activity, 4));
        d.setStroke(dp(activity, 1), theme.separator);
        swatch.setBackground(d);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(activity, 40), dp(activity, 24));
        slp.setMargins(dp(activity, 8), 0, dp(activity, 8), 0);
        swatch.setLayoutParams(slp);

        TextView hex = new TextView(activity);
        hex.setText(toHex(currentColor));
        hex.setTextColor(theme.textDim);
        hex.setTextSize(11);
        hex.setTypeface(new AppPreferences(activity).resolveTypeface());

        row.addView(text);
        row.addView(swatch);
        row.addView(hex);

        row.setContentDescription(activity.getString(R.string.a11y_settings_color_picker, labelText));
        row.setOnClickListener(v -> showColorDialog(activity, theme, labelText, currentColor, c -> {
            d.setColor(c);
            hex.setText(toHex(c));
            onChosen.onColor(c);
        }));

        if (container != null) container.addView(row);
        return swatch;
    }

    public static void showColorDialog(Activity activity, AppTheme theme, String title, int initial, ColorChosen cb) {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = dp(activity, 16);
        box.setPadding(p, p, p, p);

        // Preview
        View preview = new View(activity);
        GradientDrawable pd = new GradientDrawable();
        pd.setColor(initial);
        pd.setCornerRadius(dp(activity, 6));
        pd.setStroke(dp(activity, 1), theme.separator);
        preview.setBackground(pd);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 40));
        plp.bottomMargin = dp(activity, 8);
        preview.setLayoutParams(plp);
        box.addView(preview);

        final int[] rgb = { Color.red(initial), Color.green(initial), Color.blue(initial) };

        EditText hexInput = new EditText(activity);
        hexInput.setHint("#RRGGBB");
        hexInput.setText(toHex(initial));
        hexInput.setTypeface(new AppPreferences(activity).resolveTypeface());
        hexInput.setTextColor(theme.text);
        hexInput.setHintTextColor(theme.textDim);
        hexInput.setFilters(new InputFilter[] { new InputFilter.LengthFilter(7) });
        hexInput.setInputType(InputType.TYPE_CLASS_TEXT);
        hexInput.setContentDescription(activity.getString(R.string.a11y_settings_hex_input));
        box.addView(hexInput);

        SeekBar rs = newColorSeek(activity, rgb[0]);
        rs.setContentDescription(activity.getString(R.string.a11y_settings_color_r));
        SeekBar gs = newColorSeek(activity, rgb[1]);
        gs.setContentDescription(activity.getString(R.string.a11y_settings_color_g));
        SeekBar bs = newColorSeek(activity, rgb[2]);
        bs.setContentDescription(activity.getString(R.string.a11y_settings_color_b));

        Runnable updatePreview = () -> {
            int c = Color.rgb(rgb[0], rgb[1], rgb[2]);
            pd.setColor(c);
            String h = toHex(c);
            if (!hexInput.getText().toString().equalsIgnoreCase(h)) {
                hexInput.setText(h);
                hexInput.setSelection(h.length());
            }
        };

        rs.setOnSeekBarChangeListener(simpleSeek(v -> { rgb[0] = v; updatePreview.run(); }));
        gs.setOnSeekBarChangeListener(simpleSeek(v -> { rgb[1] = v; updatePreview.run(); }));
        bs.setOnSeekBarChangeListener(simpleSeek(v -> { rgb[2] = v; updatePreview.run(); }));

        box.addView(label(activity, theme, "R: " + rgb[0]));
        box.addView(rs);
        box.addView(label(activity, theme, "G: " + rgb[1]));
        box.addView(gs);
        box.addView(label(activity, theme, "B: " + rgb[2]));
        box.addView(bs);

        hexInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(android.text.Editable s) {
                String str = s.toString().trim();
                if (str.length() == 7 && str.startsWith("#")) {
                    try {
                        int parsed = Color.parseColor(str);
                        rgb[0] = Color.red(parsed);
                        rgb[1] = Color.green(parsed);
                        rgb[2] = Color.blue(parsed);
                        rs.setProgress(rgb[0]);
                        gs.setProgress(rgb[1]);
                        bs.setProgress(rgb[2]);
                        pd.setColor(parsed);
                    } catch (Exception ignored) {}
                }
            }
        });

        Dialogs.rounded(activity)
                .setTitle(title)
                .setView(box)
                .setPositiveButton(R.string.dialog_apply, (d, w) -> {
                    cb.onColor(Color.rgb(rgb[0], rgb[1], rgb[2]));
                })
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }

    private static SeekBar newColorSeek(Context context, int initial) {
        SeekBar s = new SeekBar(context);
        s.setMax(255);
        s.setProgress(initial);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(context, 4);
        s.setLayoutParams(lp);
        return s;
    }
}
