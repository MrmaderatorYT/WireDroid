package com.ccs.wiredroid.ui;

import android.app.Activity;
import android.content.Context;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;

import com.ccs.wiredroid.util.AppTheme;
import com.ccs.wiredroid.util.Colors;

import java.util.ArrayList;
import java.util.List;

/**
 * A context menu that opens where it was asked for.
 *
 * <p>What a desktop IDE shows on a right-click and what a phone shows on a long
 * press: a small card of actions next to the finger or the cursor, rather than a
 * dialog in the middle of the screen. A dialog is the wrong shape for this —
 * it dims everything, arrives from nowhere, and loses the connection to the row
 * or button that was pressed.</p>
 *
 * <p>Built as a {@link PopupWindow} rather than Android's own
 * {@code PopupMenu} because that one takes its colours from the platform theme
 * and ignores the app's, which is the whole reason the app draws its own
 * chrome everywhere else.</p>
 *
 * <p>Use it as a builder:</p>
 * <pre>
 * AnchoredMenu.with(activity, theme)
 *         .title(file.getName())
 *         .item("Open", () -&gt; open(file))
 *         .separator()
 *         .danger("Delete", () -&gt; delete(file))
 *         .showAt(row, x, y);
 * </pre>
 */
public final class AnchoredMenu {

    /** One row: a label, an optional leading glyph or icon, and what it does. */
    private static final class Entry {
        final String glyph;
        final int iconRes;
        /**
         * An icon that is already a Drawable rather than a resource.
         *
         * <p>For pictures the app draws itself — the country flags in the
         * language picker are painted on a canvas, not stored as files, and
         * there is no resource id to give.</p>
         */
        final android.graphics.drawable.Drawable icon;
        final String label;
        final String hint;
        final Runnable action;
        final boolean danger;
        final boolean checked;
        final boolean enabled;

        Entry(String glyph, int iconRes, android.graphics.drawable.Drawable icon, String label,
              String hint, Runnable action, boolean danger, boolean checked, boolean enabled) {
            this.glyph = glyph;
            this.iconRes = iconRes;
            this.icon = icon;
            this.label = label;
            this.hint = hint;
            this.action = action;
            this.danger = danger;
            this.checked = checked;
            this.enabled = enabled;
        }
    }

    private final Activity activity;
    private final AppTheme theme;
    private final List<Object> rows = new ArrayList<>();
    private String title;
    private int minWidthDp = 180;
    private PopupWindow window;

    private AnchoredMenu(Activity activity, AppTheme theme) {
        this.activity = activity;
        this.theme = theme;
    }

    public static AnchoredMenu with(Activity activity, AppTheme theme) {
        return new AnchoredMenu(activity, theme);
    }

    /** A dimmed heading — usually what the menu is about, such as a file name. */
    public AnchoredMenu title(String text) {
        this.title = text;
        return this;
    }

    public AnchoredMenu item(String label, Runnable action) {
        return item((String) null, label, action);
    }

    public AnchoredMenu item(String glyph, String label, Runnable action) {
        rows.add(new Entry(glyph, 0, null, label, null, action, false, false, true));
        return this;
    }

    public AnchoredMenu item(int iconRes, String label, Runnable action) {
        rows.add(new Entry(null, iconRes, null, label, null, action, false, false, true));
        return this;
    }

    /** With a right-aligned hint, for a shortcut or the current value. */
    public AnchoredMenu item(String glyph, String label, String hint, Runnable action) {
        rows.add(new Entry(glyph, 0, null, label, hint, action, false, false, true));
        return this;
    }

    public AnchoredMenu item(int iconRes, String label, String hint, Runnable action) {
        rows.add(new Entry(null, iconRes, null, label, hint, action, false, false, true));
        return this;
    }

    /** Shown as selected, the way a radio group's current choice is. */
    public AnchoredMenu checkable(String label, boolean checked, Runnable action) {
        return checkable((String) null, label, checked, action);
    }

    public AnchoredMenu checkable(String glyph, String label, boolean checked, Runnable action) {
        return checkable(glyph, label, null, checked, action);
    }

    public AnchoredMenu checkable(String glyph, String label, String hint, boolean checked, Runnable action) {
        rows.add(new Entry(glyph, 0, null, label, hint, action, false, checked, true));
        return this;
    }

    /** Selected or not, with a picture the caller has drawn itself. */
    public AnchoredMenu checkable(android.graphics.drawable.Drawable icon, String label,
                                  boolean checked, Runnable action) {
        rows.add(new Entry(null, 0, icon, label, null, action, false, checked, true));
        return this;
    }

    public AnchoredMenu item(android.graphics.drawable.Drawable icon, String label, Runnable action) {
        rows.add(new Entry(null, 0, icon, label, null, action, false, false, true));
        return this;
    }

    /** Drawn in the error colour: deleting, discarding, anything unrecoverable. */
    public AnchoredMenu danger(String glyph, String label, Runnable action) {
        rows.add(new Entry(glyph, 0, null, label, null, action, true, false, true));
        return this;
    }

    public AnchoredMenu danger(int iconRes, String label, Runnable action) {
        rows.add(new Entry(null, iconRes, null, label, null, action, true, false, true));
        return this;
    }

    /** Present but unavailable, so the menu keeps the same shape either way. */
    public AnchoredMenu disabled(String label) {
        rows.add(new Entry(null, 0, null, label, null, null, false, false, false));
        return this;
    }

    public AnchoredMenu custom(View view) {
        if (view != null) rows.add(view);
        return this;
    }

    public AnchoredMenu separator() {
        if (!rows.isEmpty() && !(rows.get(rows.size() - 1) instanceof Boolean)) {
            rows.add(Boolean.TRUE);
        }
        return this;
    }

    public AnchoredMenu minWidth(int dp) {
        this.minWidthDp = dp;
        return this;
    }

    /** True when there is nothing to show, so a caller can skip opening it. */
    public boolean isEmpty() {
        for (Object row : rows) {
            if (row instanceof Entry || row instanceof View) return false;
        }
        return true;
    }

    /**
     * Opens the menu under a view — for a button that owns a menu.
     *
     * <p>Left-aligned with the anchor and just below it, which is where a menu
     * bar's menus belong.</p>
     */
    public void showBelow(View anchor) {
        if (isEmpty()) return;
        int[] location = new int[2];
        anchor.getLocationOnScreen(location);
        show(anchor, location[0], location[1] + anchor.getHeight());
    }

    /**
     * Opens the menu at a point inside a view — for a press or a right-click.
     *
     * @param x horizontal offset of the press inside {@code anchor}
     * @param y vertical offset of the press inside {@code anchor}
     */
    public void showAt(View anchor, float x, float y) {
        if (isEmpty()) return;
        int[] location = new int[2];
        anchor.getLocationOnScreen(location);
        show(anchor, location[0] + (int) x, location[1] + (int) y);
    }

    /** Opens the menu above a view, for something sitting on a bottom bar. */
    public void showAbove(View anchor) {
        if (isEmpty()) return;
        int[] location = new int[2];
        anchor.getLocationOnScreen(location);
        show(anchor, location[0], location[1], true);
    }

    private void show(View anchor, int screenX, int screenY) {
        show(anchor, screenX, screenY, false);
    }

    private void show(View anchor, int screenX, int screenY, boolean above) {
        // One at a time, as a menu bar behaves: the second title closes the first.
        dismissOpen();
        View content = buildContent();
        // Not focusable, and that is the whole trick. A window taking focus is
        // what makes Android re-decide whether the system bars belong on screen,
        // and it decides yes — so hiding them again afterwards only turns the
        // problem into a half-second flash of the navigation bar. A menu needs
        // touches, not focus: nothing in it is typed into.
        window = new PopupWindow(content,
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, false);
        window.setClippingEnabled(true);
        // A transparent background rather than none: without a background the
        // window swallows no touches and never closes on an outside tap.
        window.setBackgroundDrawable(new ColorDrawable(0x00000000));
        window.setOutsideTouchable(true);
        window.setTouchable(true);
        // Touch-modal, so a tap outside closes the menu and stops there. A
        // non-focusable popup is not modal by default, and the same tap would
        // otherwise also land on whatever is underneath — opening the project
        // whose menu was just dismissed, for instance.
        window.setTouchModal(true);
        window.setElevation(dp(12));
        // Carried into the window at add time rather than applied after it
        // appears, so there is no frame in which the bars were asked for.
        com.ccs.wiredroid.util.FullScreenHelper.markImmersive(activity, content);

        Rect visible = new Rect();
        anchor.getRootView().getWindowVisibleDisplayFrame(visible);
        int margin = dp(8);

        // A long menu is capped and scrolls inside the card. Measured against
        // the space actually available, not the raw screen: a fourteen-item File
        // menu otherwise runs from the status bar to the navigation bar.
        int maxHeight = visible.height() - margin * 2;
        content.measure(View.MeasureSpec.UNSPECIFIED,
                View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST));
        int width = Math.max(content.getMeasuredWidth(), dp(minWidthDp));
        int height = Math.min(content.getMeasuredHeight(), maxHeight);
        window.setWidth(width);
        window.setHeight(height);

        // Kept on screen: a menu opened near the right edge or low down would
        // otherwise be cut off, which is exactly where the last row of a list is.
        int x = Math.min(screenX, visible.right - width - margin);
        x = Math.max(x, visible.left + margin);
        int y = above ? screenY - height : screenY;
        if (y + height > visible.bottom - margin) {
            y = Math.max(visible.top + margin, screenY - height);
        }
        y = Math.max(y, visible.top + margin);

        window.setOnDismissListener(() -> {
            if (open == this) open = null;
        });
        window.showAtLocation(anchor.getRootView(), Gravity.NO_GRAVITY, x, y);
        open = this;
    }

    public void dismiss() {
        if (window != null && window.isShowing()) window.dismiss();
        if (open == this) open = null;
    }

    /**
     * The menu currently on screen, if any.
     *
     * <p>Only one is ever open — opening a second closes the first — so a single
     * slot is enough. It exists because a non-focusable window never sees the
     * back key: the activity does, and asks here whether there is a menu to
     * close before it does anything else.</p>
     */
    private static AnchoredMenu open;

    /** Closes the open menu. @return true when there was one, so back is consumed. */
    public static boolean dismissOpen() {
        if (open == null) return false;
        open.dismiss();
        return true;
    }

    private View buildContent() {
        int surface = theme != null ? theme.toolbar : 0xFF3C3F41;
        int text = theme != null ? theme.text : 0xFFDFE1E5;
        int dim = theme != null ? theme.textDim : 0xFF808080;
        int accent = theme != null ? theme.accent : 0xFF3574F0;
        int error = theme != null ? theme.errorText : 0xFFE74C3C;
        int separator = theme != null ? theme.separator : 0x33888888;

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(6), 0, dp(6));
        GradientDrawable card = new GradientDrawable();
        card.setColor(surface);
        card.setCornerRadius(dp(12));
        card.setStroke(dp(1), separator);
        box.setBackground(card);

        if (title != null && !title.isEmpty()) {
            TextView heading = new TextView(activity);
            heading.setText(title);
            heading.setTextColor(dim);
            heading.setTextSize(11);
            heading.setPadding(dp(16), dp(6), dp(16), dp(6));
            heading.setSingleLine(true);
            heading.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            box.addView(heading);
            box.addView(separatorView(separator));
        }

        for (Object row : rows) {
            if (row instanceof Boolean) {
                box.addView(separatorView(separator));
                continue;
            }
            if (row instanceof View) {
                box.addView((View) row);
                continue;
            }
            Entry entry = (Entry) row;
            box.addView(rowView(entry, text, dim, accent, error, surface));
        }

        // Long menus scroll instead of running off the screen; short ones keep
        // their natural height, so the card hugs its content.
        ScrollView scroller = new ScrollView(activity);
        scroller.addView(box);
        scroller.setClipToOutline(true);
        scroller.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroller;
    }

    private View rowView(Entry entry, int text, int dim, int accent, int error, int surface) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(11), dp(16), dp(11));

        if (entry.icon != null) {
            // Left in its own colours: a flag tinted grey is not a flag.
            android.widget.ImageView icon = new android.widget.ImageView(activity);
            icon.setImageDrawable(entry.icon);
            icon.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(26), dp(17));
            lp.rightMargin = dp(10);
            row.addView(icon, lp);
        } else if (entry.iconRes != 0) {
            android.widget.ImageView icon = new android.widget.ImageView(activity);
            icon.setImageResource(entry.iconRes);
            icon.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
            icon.setColorFilter(entry.danger ? error : dim, android.graphics.PorterDuff.Mode.SRC_IN);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(18), dp(18));
            lp.rightMargin = dp(8);
            row.addView(icon, lp);
        } else if (entry.glyph != null && !entry.glyph.isEmpty()) {
            TextView glyph = new TextView(activity);
            glyph.setText(entry.glyph);
            glyph.setTextSize(13);
            glyph.setTextColor(entry.danger ? error : dim);
            glyph.setWidth(dp(26));
            row.addView(glyph);
        } else if (entry.checked) {
            TextView tick = new TextView(activity);
            tick.setText("✓");
            tick.setTextSize(13);
            tick.setTextColor(accent);
            tick.setWidth(dp(26));
            row.addView(tick);
        } else if (hasGlyphsOrChecks() && !hasIcons()) {
            // Keeps the labels of a mixed menu on one vertical line.
            TextView spacer = new TextView(activity);
            spacer.setWidth(dp(26));
            row.addView(spacer);
        }

        TextView label = new TextView(activity);
        label.setText(entry.label);
        label.setTextSize(14);
        label.setTextColor(!entry.enabled ? dim : entry.danger ? error : entry.checked ? accent : text);
        label.setSingleLine(true);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(label, labelLp);

        if (entry.hint != null && !entry.hint.isEmpty()) {
            TextView hint = new TextView(activity);
            hint.setText(entry.hint);
            hint.setTextSize(11);
            hint.setTextColor(dim);
            hint.setPadding(dp(16), 0, 0, 0);
            row.addView(hint);
        }

        // The leading slot is taken by the icon, so the tick goes at the end —
        // otherwise a selected row would look the same as an unselected one.
        if (entry.checked && (entry.icon != null || entry.iconRes != 0)) {
            TextView tick = new TextView(activity);
            tick.setText("✓");
            tick.setTextSize(13);
            tick.setTextColor(accent);
            tick.setPadding(dp(12), 0, 0, 0);
            row.addView(tick);
        }

        if (entry.enabled && entry.action != null) {
            row.setBackground(new RippleDrawable(
                    ColorStateList.valueOf(Colors.blend(surface, accent, 0.35f)),
                    null, null));
            row.setOnClickListener(v -> {
                // Closed first: an action that opens a dialog would otherwise
                // leave this card floating over it.
                dismiss();
                entry.action.run();
            });
        }
        return row;
    }

    /** True when any row carries a picture, so the glyph column is not in use. */
    private boolean hasIcons() {
        for (Object row : rows) {
            if (row instanceof Entry && (((Entry) row).icon != null || ((Entry) row).iconRes != 0)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasGlyphsOrChecks() {
        for (Object row : rows) {
            if (!(row instanceof Entry)) continue;
            Entry entry = (Entry) row;
            if (entry.iconRes != 0 || (entry.glyph != null && !entry.glyph.isEmpty()) || entry.checked) return true;
        }
        return false;
    }

    private View separatorView(int color) {
        View line = new View(activity);
        line.setBackgroundColor(color);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1) / 2));
        lp.topMargin = dp(5);
        lp.bottomMargin = dp(5);
        line.setLayoutParams(lp);
        return line;
    }

    private int dp(int value) {
        return (int) (value * activity.getResources().getDisplayMetrics().density);
    }

    /**
     * Remembers where a view was last touched, for a menu opened by a gesture
     * that carries no coordinates of its own.
     *
     * <p>{@code OnLongClickListener} and {@code OnContextClickListener} are both
     * told that something happened but not where, and a menu that ignores that
     * lands in the corner instead of under the finger.</p>
     */
    public static final class TouchPoint implements View.OnTouchListener {
        public float x;
        public float y;

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            x = event.getX();
            y = event.getY();
            return false;
        }

        /** Attaches to a view and returns itself, for use in one expression. */
        public static TouchPoint track(View view) {
            TouchPoint point = new TouchPoint();
            view.setOnTouchListener(point);
            return point;
        }
    }
}
