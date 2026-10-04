package com.ccs.wiredroid.ui;

import com.ccs.wiredroid.R;
import com.ccs.wiredroid.util.AppPreferences;
import com.ccs.wiredroid.util.AppTheme;
import com.ccs.wiredroid.util.CodeSnippetHighlighter;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Блок коду з підсвіткою синтаксису, заокругленими кутами, плавною прокруткою та кнопкою Copy.
 */
public class CodeBlockView extends LinearLayout {

    private final TextView tvCode;
    private final TextView tvCopy;
    private final TextView tvLang;

    public CodeBlockView(Context context, String code, String lang) {
        super(context);
        setOrientation(LinearLayout.VERTICAL);
        AppPreferences preferences = new AppPreferences(context);
        AppTheme theme = AppTheme.byId(preferences.getThemeId(), preferences);
        Typeface mono = preferences.resolveTypeface();
        boolean dark = theme != null && theme.dark;

        // Container with rounded corners and border
        GradientDrawable boxBg = new GradientDrawable();
        boxBg.setColor(theme.consoleBg);
        boxBg.setStroke(dp(1), theme.separator);
        boxBg.setCornerRadius(dp(8));
        setBackground(boxBg);
        setClipToOutline(true);

        LinearLayout inner = new LinearLayout(context);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setPadding(dp(12), dp(8), dp(12), dp(10));
        LinearLayout.LayoutParams innerLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        inner.setLayoutParams(innerLp);

        // Header row: Language badge + Lines count + Copy button
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, 0, 0, dp(6));

        // Language Pill / Badge
        tvLang = new TextView(context);
        String displayLang = (lang != null && !lang.trim().isEmpty())
                ? lang.trim().toUpperCase(java.util.Locale.ROOT) : "CODE";
        tvLang.setText(displayLang);
        tvLang.setTextColor(theme.accent);
        tvLang.setTextSize(10);
        tvLang.setTypeface(Typeface.create(mono, Typeface.BOLD));
        tvLang.setPadding(dp(8), dp(2), dp(8), dp(2));

        GradientDrawable langBadge = new GradientDrawable();
        langBadge.setColor((theme.accent & 0x00FFFFFF) | 0x22000000);
        langBadge.setCornerRadius(dp(4));
        tvLang.setBackground(langBadge);

        LinearLayout.LayoutParams langLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        header.addView(tvLang, langLp);

        // Spacer
        View spacer = new View(context);
        LinearLayout.LayoutParams spacerLp = new LinearLayout.LayoutParams(
                0, dp(1), 1f);
        header.addView(spacer, spacerLp);

        // Copy button with feedback
        tvCopy = new TextView(context);
        tvCopy.setText(R.string.label_copy);
        tvCopy.setTextColor(theme.textDim);
        tvCopy.setTextSize(11);
        tvCopy.setPadding(dp(8), dp(3), dp(8), dp(3));
        tvCopy.setContentDescription(context.getString(R.string.a11y_copy_code_block));
        tvCopy.setClickable(true);
        tvCopy.setFocusable(true);
        tvCopy.setGravity(Gravity.CENTER);

        GradientDrawable copyBtnBg = new GradientDrawable();
        copyBtnBg.setColor(0x00000000);
        copyBtnBg.setStroke(dp(1), theme.separator);
        copyBtnBg.setCornerRadius(dp(4));
        tvCopy.setBackground(copyBtnBg);

        tvCopy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("code", code));
                tvCopy.setText("✓ " + context.getString(R.string.toast_copied));
                tvCopy.setTextColor(dark ? 0xFF499C54 : 0xFF107C10);

                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    tvCopy.setText(R.string.label_copy);
                    tvCopy.setTextColor(theme.textDim);
                }, 2000L);
            }
        });
        header.addView(tvCopy);

        inner.addView(header);

        // Separator line
        View sep = new View(context);
        sep.setBackgroundColor(theme.separator);
        inner.addView(sep, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        // Code text view inside horizontal scroller
        tvCode = new TextView(context);
        SpannableStringBuilder highlighted = CodeSnippetHighlighter.highlight(code, lang, dark, mono);
        tvCode.setText(highlighted);
        tvCode.setTextColor(theme.text);
        tvCode.setTextSize(13);
        tvCode.setTypeface(mono);
        tvCode.setLineSpacing(dp(2), 1.25f);
        tvCode.setTextIsSelectable(true);
        tvCode.setBackgroundColor(0x00000000);
        tvCode.setHighlightColor((theme.accent & 0x00FFFFFF) | 0x44000000);

        HorizontalScrollView codeScroll = new HorizontalScrollView(context);
        codeScroll.setFillViewport(false);
        codeScroll.setHorizontalScrollBarEnabled(true);
        codeScroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        LinearLayout.LayoutParams codeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        codeLp.topMargin = dp(6);
        codeScroll.setLayoutParams(codeLp);
        codeScroll.addView(tvCode, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        inner.addView(codeScroll);

        addView(inner);
    }

    /** Застосовує власну підсвітку синтаксису якщо потрібно */
    public void setHighlighted(SpannableStringBuilder highlighted) {
        tvCode.setText(highlighted);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
