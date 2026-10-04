package com.ccs.wiredroid.ui.panels;

import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.ccs.wiredroid.R;
import com.ccs.wiredroid.util.AppPreferences;
import com.ccs.wiredroid.util.AppTheme;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The settings control for arranging the bottom tab strip.
 *
 * <p>A list of rows, each carrying the panel's name, a checkbox for whether it
 * appears, and up/down buttons. Deliberately not drag-and-drop: the list is
 * short, buttons stay usable one-handed on a phone, and they work with
 * TalkBack, which a drag handle does not.</p>
 *
 * <p>Run and Problems cannot be hidden — the editor would have nowhere to
 * report a failed compile — so their checkboxes are shown ticked and disabled
 * rather than silently ignoring a change.</p>
 */
public final class PanelLayoutEditor {

    private final Context context;
    private final AppTheme theme;
    private final AppPreferences prefs;
    private final LinearLayout list;

    private final List<BottomPanel> order = new ArrayList<>();
    private final Set<BottomPanel> hidden = new HashSet<>();

    public PanelLayoutEditor(Context context, AppTheme theme, AppPreferences prefs) {
        this.context = context;
        this.theme = theme;
        this.prefs = prefs;
        this.list = new LinearLayout(context);
        this.list.setOrientation(LinearLayout.VERTICAL);

        for (BottomPanel panel : BottomPanel.resolveOrder(prefs.getPanelOrder())) {
            if (!panel.debugOnly) order.add(panel);
        }
        for (String key : prefs.getHiddenPanels()) {
            BottomPanel panel = BottomPanel.byKey(key);
            if (panel != null) hidden.add(panel);
        }
        rebuild();
    }

    /** The editor's root view, ready to add to a settings section. */
    public View getView() {
        return list;
    }

    /** Discards the user's arrangement and redraws in factory order. */
    public void reset() {
        prefs.resetPanelLayout();
        order.clear();
        for (BottomPanel panel : BottomPanel.values()) {
            if (!panel.debugOnly) order.add(panel);
        }
        hidden.clear();
        rebuild();
    }

    // ── internals ────────────────────────────────────────────────────────────

    private void rebuild() {
        list.removeAllViews();
        for (int i = 0; i < order.size(); i++) {
            list.addView(buildRow(order.get(i), i));
        }
    }

    private View buildRow(BottomPanel panel, int position) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(2), 0, dp(2));

        CheckBox visible = new CheckBox(context);
        boolean pinned = BottomPanel.alwaysVisible().contains(panel);
        visible.setChecked(pinned || !hidden.contains(panel));
        visible.setEnabled(!pinned);
        visible.setButtonTintList(android.content.res.ColorStateList.valueOf(theme.accent));
        visible.setContentDescription(context.getString(
                R.string.settings_panel_visible_a11y, label(panel)));
        visible.setOnCheckedChangeListener((button, checked) -> {
            if (checked) hidden.remove(panel);
            else hidden.add(panel);
            save();
        });
        row.addView(visible);

        TextView name = new TextView(context);
        name.setText(label(panel));
        name.setTextColor(pinned ? theme.textDim : theme.text);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        name.setLayoutParams(nameLp);
        row.addView(name);

        row.addView(arrowButton("▲", position > 0, () -> move(position, position - 1),
                context.getString(R.string.settings_panel_move_up_a11y, label(panel))));
        row.addView(arrowButton("▼", position < order.size() - 1, () -> move(position, position + 1),
                context.getString(R.string.settings_panel_move_down_a11y, label(panel))));

        return row;
    }

    private TextView arrowButton(String glyph, boolean enabled, Runnable action, String description) {
        TextView button = new TextView(context);
        button.setText(glyph);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        button.setTextColor(enabled ? theme.accent : theme.separator);
        button.setPadding(dp(14), dp(6), dp(14), dp(6));
        button.setContentDescription(description);
        button.setEnabled(enabled);
        if (enabled) button.setOnClickListener(v -> action.run());
        return button;
    }

    private void move(int from, int to) {
        if (to < 0 || to >= order.size()) return;
        BottomPanel moved = order.remove(from);
        order.add(to, moved);
        save();
        rebuild();
    }

    private void save() {
        List<String> keys = new ArrayList<>(order.size());
        for (BottomPanel panel : order) keys.add(panel.key);
        prefs.setPanelOrder(keys);

        Set<String> hiddenKeys = new HashSet<>();
        for (BottomPanel panel : hidden) {
            if (!BottomPanel.alwaysVisible().contains(panel)) hiddenKeys.add(panel.key);
        }
        prefs.setHiddenPanels(hiddenKeys);
    }

    private String label(BottomPanel panel) {
        // The bookmarks tab shows a star; spell it out in a settings list.
        int res = panel == BottomPanel.BOOKMARKS ? R.string.tab_bookmarks_name : panel.labelRes;
        return context.getString(res).trim();
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
