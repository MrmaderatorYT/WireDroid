package com.ccs.wiredroid.ui.tools;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.ccs.wiredroid.R;
import com.ccs.wiredroid.ui.Dialogs;
import com.ccs.wiredroid.util.AppTheme;
import com.ccs.wiredroid.util.Colors;
import com.google.android.material.button.MaterialButton;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Standalone helper dialogs: a regex tester and a Base64/URL converter with Material Design 3.
 */
public final class DeveloperToolDialogs {

    private DeveloperToolDialogs() {}

    /**
     * Live regex tester: shows every match as the pattern or subject is typed,
     * and reports a syntax error rather than swallowing it.
     */
    public static void showRegexTester(Activity activity, AppTheme theme) {
        LinearLayout layout = column(activity, theme);

        EditText pattern = field(activity, theme, activity.getString(R.string.tool_regex_hint), 1);
        EditText subject = field(activity, theme, activity.getString(R.string.tool_regex_text_hint), 3);
        TextView result = output(activity, theme);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(activity, 10));

        layout.addView(pattern, lp);
        layout.addView(subject, lp);
        layout.addView(result, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextWatcher watcher = new SimpleTextWatcher(() ->
                result.setText(describeMatches(activity, pattern.getText().toString(),
                        subject.getText().toString())));
        pattern.addTextChangedListener(watcher);
        subject.addTextChangedListener(watcher);

        AlertDialog dialog = Dialogs.rounded(activity)
                .setTitle(R.string.tool_regex_title)
                .setView(layout)
                .setPositiveButton(android.R.string.ok, null)
                .show();
        Dialogs.style(dialog, theme);
    }

    /** Runs the pattern and formats the outcome, including the failure cases. */
    private static CharSequence describeMatches(Activity activity, String regex, String text) {
        if (regex.isEmpty()) return "";
        try {
            Matcher matcher = Pattern.compile(regex).matcher(text);
            StringBuilder out = new StringBuilder();
            int count = 0;
            while (matcher.find()) {
                count++;
                out.append(activity.getString(R.string.tool_regex_match, count, matcher.group()))
                        .append('\n');
                if (matcher.end() == matcher.start() && matcher.end() >= text.length()) break;
                if (count > 500) {
                    out.append(activity.getString(R.string.tool_regex_truncated));
                    break;
                }
            }
            return count == 0 ? activity.getString(R.string.tool_regex_no_match) : out.toString();
        } catch (PatternSyntaxException e) {
            return activity.getString(R.string.tool_regex_invalid, e.getDescription());
        } catch (Exception e) {
            return activity.getString(R.string.tool_regex_invalid, String.valueOf(e.getMessage()));
        }
    }

    /** Base64 and percent-encoding, in both directions. */
    public static void showEncoder(Activity activity, AppTheme theme) {
        LinearLayout layout = column(activity, theme);

        EditText input = field(activity, theme, activity.getString(R.string.tool_encoder_input), 3);
        TextView output = output(activity, theme);

        LinearLayout.LayoutParams fieldLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fieldLp.setMargins(0, 0, 0, dp(activity, 10));
        layout.addView(input, fieldLp);

        layout.addView(buttonRow(activity,
                button(activity, theme, activity.getString(R.string.tool_encoder_encode), () ->
                        output.setText(run(activity, () -> Base64.encodeToString(
                                input.getText().toString().getBytes(StandardCharsets.UTF_8),
                                Base64.NO_WRAP)))),
                button(activity, theme, activity.getString(R.string.tool_encoder_decode), () ->
                        output.setText(run(activity, () -> new String(
                                Base64.decode(input.getText().toString(), Base64.DEFAULT),
                                StandardCharsets.UTF_8))))));

        layout.addView(buttonRow(activity,
                button(activity, theme, activity.getString(R.string.tool_encoder_url_encode), () ->
                        output.setText(run(activity, () ->
                                URLEncoder.encode(input.getText().toString(), "UTF-8")))),
                button(activity, theme, activity.getString(R.string.tool_encoder_url_decode), () ->
                        output.setText(run(activity, () ->
                                URLDecoder.decode(input.getText().toString(), "UTF-8"))))));

        LinearLayout.LayoutParams outputLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        outputLp.setMargins(0, dp(activity, 8), 0, dp(activity, 4));
        layout.addView(output, outputLp);

        MaterialButton btnCopy = button(activity, theme, activity.getString(R.string.label_copy), () -> {
            CharSequence txt = output.getText();
            if (txt != null && txt.length() > 0) {
                ClipboardManager cm = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("Output", txt));
                    Toast.makeText(activity, R.string.toast_copied_clipboard, Toast.LENGTH_SHORT).show();
                }
            }
        });
        layout.addView(btnCopy, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        AlertDialog dialog = Dialogs.rounded(activity)
                .setTitle(R.string.tool_encoder_title)
                .setView(layout)
                .setPositiveButton(android.R.string.ok, null)
                .show();
        Dialogs.style(dialog, theme);
    }

    /** A conversion that may throw; failures become a readable line, not a crash. */
    private interface Conversion {
        String apply() throws UnsupportedEncodingException, IllegalArgumentException;
    }

    private static String run(Activity activity, Conversion conversion) {
        try {
            return conversion.apply();
        } catch (IllegalArgumentException e) {
            return activity.getString(R.string.tool_encoder_invalid, String.valueOf(e.getMessage()));
        } catch (Exception e) {
            return activity.getString(R.string.tool_encoder_failed, String.valueOf(e.getMessage()));
        }
    }

    // ── view helpers ─────────────────────────────────────────────────────────

    private static LinearLayout column(Activity activity, AppTheme theme) {
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        if (theme != null) {
            layout.setBackgroundColor(theme.bg);
        }
        int pad = dp(activity, 16);
        layout.setPadding(pad, pad, pad, pad);
        return layout;
    }

    private static EditText field(Activity activity, AppTheme theme, String hint, int minLines) {
        EditText field = new EditText(activity);
        field.setHint(hint);
        if (theme != null) {
            field.setTextColor(theme.text);
            field.setHintTextColor(theme.textDim);
            GradientDrawable sBg = new GradientDrawable();
            sBg.setColor(Colors.blend(theme.consoleBg, theme.bg, 0.4f));
            sBg.setCornerRadius(dp(activity, 8));
            sBg.setStroke(dp(activity, 1), theme.separator);
            field.setBackground(sBg);
        }
        int pad = dp(activity, 12);
        field.setPadding(pad, pad, pad, pad);
        if (minLines > 1) {
            field.setMinLines(minLines);
            field.setGravity(Gravity.TOP | Gravity.START);
        }
        return field;
    }

    private static TextView output(Activity activity, AppTheme theme) {
        TextView view = new TextView(activity);
        if (theme != null) {
            view.setTextColor(theme.accent);
            GradientDrawable sBg = new GradientDrawable();
            sBg.setColor(theme.consoleBg);
            sBg.setCornerRadius(dp(activity, 8));
            sBg.setStroke(dp(activity, 1), theme.separator);
            view.setBackground(sBg);
        }
        int pad = dp(activity, 12);
        view.setPadding(pad, pad, pad, pad);
        view.setTextIsSelectable(true);
        view.setMinimumHeight(dp(activity, 60));
        return view;
    }

    private static MaterialButton button(Activity activity, AppTheme theme, String text, Runnable action) {
        MaterialButton button = new MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        button.setText(text);
        button.setTextSize(12.5f);
        button.setCornerRadius(dp(activity, 8));
        button.setLetterSpacing(0.01f);
        button.setInsetTop(0);
        button.setInsetBottom(0);
        if (theme != null) {
            button.setTextColor(theme.accent);
            button.setStrokeColor(ColorStateList.valueOf(theme.separator));
            button.setStrokeWidth(dp(activity, 1));
            button.setBackgroundColor(Colors.blend(theme.toolbar, theme.bg, 0.35f));
            button.setRippleColor(ColorStateList.valueOf((theme.accent & 0x00FFFFFF) | 0x33000000));
        }
        button.setOnClickListener(v -> action.run());
        return button;
    }

    private static LinearLayout buttonRow(Activity activity, View... buttons) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(activity, 2), 0, dp(activity, 6));
        for (int i = 0; i < buttons.length; i++) {
            View button = buttons[i];
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) {
                lp.setMarginStart(dp(activity, 8));
            }
            button.setLayoutParams(lp);
            row.addView(button);
        }
        return row;
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    /** {@link TextWatcher} boilerplate reduced to the one callback we use. */
    private static final class SimpleTextWatcher implements TextWatcher {
        private final Runnable onChange;

        SimpleTextWatcher(Runnable onChange) {
            this.onChange = onChange;
        }

        @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
        @Override public void onTextChanged(CharSequence s, int a, int b, int c) { onChange.run(); }
        @Override public void afterTextChanged(Editable s) {}
    }
}
