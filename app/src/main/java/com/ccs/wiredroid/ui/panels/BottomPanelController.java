package com.ccs.wiredroid.ui.panels;

import com.ccs.wiredroid.util.Colors;

import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.ccs.wiredroid.util.AppTheme;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Owns the bottom tab strip: which panel is showing, how each tab is tinted,
 * and the order and visibility the user chose in settings.
 *
 * <p>Previously each panel appeared in four places in {@code MainActivity} —
 * a click listener, a content-visibility line, a background-colour line and a
 * text-colour line — repeated in two nearly identical methods. Registering a
 * panel here replaces all of that, and the tab strip is rebuilt from the saved
 * order in the same pass.</p>
 *
 * <p>Reordering moves the existing tab views inside their parent rather than
 * inflating new ones, so listeners, content descriptions and any state a
 * manager class holds on its own tab survive untouched.</p>
 */
public final class BottomPanelController {

    /** Everything a panel needs to do beyond having a tab. */
    public interface Binding {
        /** Shows or hides the panel's content. */
        void setVisible(boolean visible);

        /** Called after the panel becomes active; refresh lazily here. */
        default void onShown() {}

        /**
         * Lets a manager that owns its tab style it itself.
         *
         * @return true when handled, false to apply the standard tinting
         */
        default boolean styleTab(boolean active, AppTheme theme, int activeBg) {
            return false;
        }
    }

    /** Notified after every switch, for chrome that depends on the active panel. */
    public interface OnPanelChanged {
        void onPanelChanged(BottomPanel panel);
    }

    private static final int GOLD = 0xFFFFD700;

    private final Map<BottomPanel, TextView> tabs = new EnumMap<>(BottomPanel.class);
    private final Map<BottomPanel, Binding> bindings = new EnumMap<>(BottomPanel.class);
    private Set<BottomPanel> hidden;

    private AppTheme theme;
    private BottomPanel active = BottomPanel.RUN;
    private boolean debugSessionActive;
    private OnPanelChanged listener;

    /**
     * @param theme  current theme; replace with {@link #applyTheme}
     * @param hidden panels the user switched off (debug panels are exempt)
     */
    public BottomPanelController(AppTheme theme, Set<BottomPanel> hidden) {
        this.theme = theme;
        this.hidden = hidden;
    }

    /** Registers a panel. A null tab means the layout does not carry it. */
    public void register(BottomPanel panel, TextView tab, Binding binding) {
        if (tab == null) return;
        tabs.put(panel, tab);
        bindings.put(panel, binding);
        tab.setOnClickListener(v -> select(panel));
    }

    public void setOnPanelChanged(OnPanelChanged listener) {
        this.listener = listener;
    }

    /** Replaces the hidden set, e.g. after returning from settings. */
    public void setHidden(Set<BottomPanel> hidden) {
        this.hidden = hidden == null ? java.util.Collections.emptySet() : hidden;
    }

    public BottomPanel active() {
        return active;
    }

    /** Numeric mode of the active panel, for the older {@code int} call sites. */
    public int activeMode() {
        return active.mode;
    }

    /**
     * Rearranges the tab strip to {@code orderedKeys} and applies visibility.
     *
     * <p>Safe to call at any time: tabs are detached and re-added in one pass,
     * so a partially applied order is never on screen.</p>
     */
    public void applyOrder(List<String> orderedKeys) {
        List<BottomPanel> order = BottomPanel.resolveOrder(orderedKeys);

        ViewGroup parent = null;
        List<View> views = new ArrayList<>();
        for (BottomPanel panel : order) {
            TextView tab = tabs.get(panel);
            if (tab == null) continue;
            ViewGroup tabParent = (ViewGroup) tab.getParent();
            if (tabParent == null) continue;
            // Every tab shares one strip; if that ever stops being true, keep
            // the first strip's order rather than shuffling views between them.
            if (parent == null) parent = tabParent;
            if (tabParent != parent) continue;
            views.add(tab);
        }
        if (parent == null) return;

        for (View view : views) parent.removeView(view);
        for (View view : views) parent.addView(view);

        applyVisibility();
    }

    /** Applies the hidden set and the debug-session state to every tab. */
    public void applyVisibility() {
        for (Map.Entry<BottomPanel, TextView> entry : tabs.entrySet()) {
            BottomPanel panel = entry.getKey();
            entry.getValue().setVisibility(isTabVisible(panel) ? View.VISIBLE : View.GONE);
        }
        // Never strand the user on a tab they can no longer see.
        if (!isTabVisible(active)) select(firstVisible());
    }

    private boolean isTabVisible(BottomPanel panel) {
        if (panel.debugOnly) return debugSessionActive;
        if (BottomPanel.alwaysVisible().contains(panel)) return true;
        return !hidden.contains(panel);
    }

    private BottomPanel firstVisible() {
        for (BottomPanel panel : BottomPanel.values()) {
            if (tabs.containsKey(panel) && isTabVisible(panel)) return panel;
        }
        return BottomPanel.RUN;
    }

    /** Shows or hides the debug-only tabs when a session starts or ends. */
    public void setDebugSessionActive(boolean active) {
        this.debugSessionActive = active;
        applyVisibility();
    }

    public void applyTheme(AppTheme theme) {
        this.theme = theme;
        restyle();
    }

    /** Switches to {@code panel}, or to the first visible tab if it is hidden. */
    public void select(BottomPanel panel) {
        if (panel == null) panel = BottomPanel.RUN;
        if (!tabs.containsKey(panel)) return;
        // Debug panels are selected programmatically when a session starts, so
        // they are allowed through even before applyVisibility catches up.
        if (!isTabVisible(panel) && !panel.debugOnly) panel = firstVisible();

        active = panel;
        for (Map.Entry<BottomPanel, Binding> entry : bindings.entrySet()) {
            Binding binding = entry.getValue();
            if (binding != null) binding.setVisible(entry.getKey() == active);
        }
        restyle();

        Binding activeBinding = bindings.get(active);
        if (activeBinding != null) activeBinding.onShown();
        if (listener != null) listener.onPanelChanged(active);
    }

    /** Convenience for the {@code int}-based call sites still in the codebase. */
    public void select(int mode) {
        select(BottomPanel.byMode(mode));
    }

    /** Repaints every tab for the current theme and selection. */
    public void restyle() {
        if (theme == null) return;
        int activeBg = Colors.blend(theme.toolbar, theme.bg, 0.4f);

        for (Map.Entry<BottomPanel, TextView> entry : tabs.entrySet()) {
            BottomPanel panel = entry.getKey();
            TextView tab = entry.getValue();
            boolean isActive = panel == active;

            Binding binding = bindings.get(panel);
            if (binding != null && binding.styleTab(isActive, theme, activeBg)) continue;

            tab.setBackgroundColor(isActive ? activeBg : theme.toolbar);
            tab.setTextColor(isActive ? accentColor(panel) : theme.textDim);
        }
    }

    private int accentColor(BottomPanel panel) {
        switch (panel.accent) {
            case SUCCESS: return theme.successText;
            case TEXT:    return theme.text;
            case GOLD:    return GOLD;
            default:      return theme.accent;
        }
    }

}
