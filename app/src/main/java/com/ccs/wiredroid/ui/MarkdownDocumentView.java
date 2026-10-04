package com.ccs.wiredroid.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.method.LinkMovementMethod;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.ccs.wiredroid.R;
import com.ccs.wiredroid.util.AppTheme;
import com.ccs.wiredroid.util.MarkdownBlockParser;
import com.ccs.wiredroid.util.MarkdownRenderer;

import java.util.ArrayList;
import java.util.List;

/**
 * A rendered Markdown document, wherever one is needed.
 *
 * <p>Extracted from the separate preview screen that used to build this inline;
 * that screen is gone, and the preview now lives in an editor pane. It
 * is now shown in two places — full screen, and inside a split editor pane — and
 * two copies of a renderer this particular would not have stayed the same for
 * long: the table view, the code blocks and the block splitting all have to
 * agree, and only one of the copies would have been fixed each time.</p>
 *
 * <p>Parsing and inline rendering happen off the main thread; only building the
 * views is posted back, because that is the part that must be.</p>
 */
public class MarkdownDocumentView extends ScrollView {

    private final LinearLayout document;
    private final Handler main = new Handler(Looper.getMainLooper());
    private AppTheme theme;
    private Typeface typeface;

    public MarkdownDocumentView(Context context) {
        super(context);
        setFillViewport(true);
        setSmoothScrollingEnabled(true);
        setOverScrollMode(OVER_SCROLL_IF_CONTENT_SCROLLS);

        document = new LinearLayout(context);
        document.setOrientation(LinearLayout.VERTICAL);
        addView(document, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /**
     * Renders {@code markdown}, replacing whatever was shown.
     *
     * @param sidePadding horizontal padding in px — a full screen wants generous
     *                    margins, a split pane has no width to spare
     */
    public void setMarkdown(@NonNull String markdown, @NonNull AppTheme theme,
                            @Nullable Typeface typeface, int sidePadding) {
        this.theme = theme;
        this.typeface = typeface;
        setBackgroundColor(theme.bg);
        document.setBackgroundColor(theme.bg);
        document.setPadding(sidePadding, dp(12), sidePadding, dp(40));

        document.removeAllViews();
        TextView loading = new TextView(getContext());
        loading.setText(R.string.label_analyzing);
        loading.setTextColor(theme.textDim);
        loading.setTextSize(13);
        loading.setGravity(android.view.Gravity.CENTER);
        loading.setPadding(0, dp(32), 0, dp(32));
        document.addView(loading);

        final boolean dark = theme.dark;
        new Thread(() -> {
            List<MarkdownBlockParser.Part> parts = MarkdownBlockParser.split(markdown);
            List<SpannableStringBuilder> rendered = new ArrayList<>(parts.size());
            for (MarkdownBlockParser.Part part : parts) {
                rendered.add((part.code || part.table)
                        ? null : MarkdownRenderer.render(part.text, dark, 15, typeface));
            }
            main.post(() -> build(parts, rendered));
        }, "md-render").start();
    }

    private void build(List<MarkdownBlockParser.Part> parts,
                       List<SpannableStringBuilder> rendered) {
        if (!isAttachedToWindow()) return;
        document.removeAllViews();
        for (int i = 0; i < parts.size(); i++) {
            MarkdownBlockParser.Part part = parts.get(i);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (part.code) {
                lp.setMargins(0, dp(8), 0, dp(12));
                document.addView(new CodeBlockView(getContext(), part.text, part.language), lp);
            } else if (part.table) {
                lp.setMargins(0, dp(8), 0, dp(12));
                document.addView(new MarkdownTableView(getContext(), part.text, theme, typeface), lp);
            } else {
                lp.setMargins(0, dp(2), 0, dp(6));
                document.addView(paragraph(rendered.get(i)), lp);
            }
        }
    }

    private TextView paragraph(SpannableStringBuilder text) {
        TextView view = new TextView(getContext());
        view.setText(text);
        view.setTextSize(15);
        view.setLineSpacing(dp(3), 1.35f);
        view.setTextColor(theme.text);
        view.setTextIsSelectable(true);
        view.setLinksClickable(true);
        view.setMovementMethod(LinkMovementMethod.getInstance());
        view.setHighlightColor((theme.accent & 0x00FFFFFF) | 0x44000000);
        view.setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY);
        view.setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL);
        view.setElegantTextHeight(true);
        return view;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
