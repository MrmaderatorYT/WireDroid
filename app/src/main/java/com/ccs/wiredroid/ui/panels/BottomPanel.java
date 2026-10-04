package com.ccs.wiredroid.ui.panels;

import com.ccs.wiredroid.R;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The tabs along the bottom of the editor.
 *
 * <p>One place that knows every panel: its numeric mode (which used to be a
 * {@code private static final int} repeated across three classes), the key its
 * position is saved under, the label the settings screen shows, and how its tab
 * is tinted when active. Adding a panel means adding a constant here rather
 * than editing four files and hoping the numbers still line up.</p>
 *
 * <p>Order in this enum is the factory default; the user's own order lives in
 * preferences, keyed by {@link #key} so that inserting a panel later does not
 * scramble a saved layout.</p>
 */
public enum BottomPanel {

    RUN(0, "run", R.string.tab_run, Accent.SUCCESS, false),
    PROBLEMS(1, "problems", R.string.tab_problems, Accent.TEXT, false),
    BYTECODE(2, "bytecode", R.string.tab_bytecode, Accent.ACCENT, false),
    DEBUG(3, "debug", R.string.tab_debug, Accent.ACCENT, true),
    DEBUG_CONSOLE(4, "debug_console", R.string.tab_debug_console, Accent.ACCENT, true),
    CALL_GRAPH(5, "call_graph", R.string.tab_call_graph, Accent.ACCENT, false),
    BOOKMARKS(6, "bookmarks", R.string.tab_bookmarks, Accent.GOLD, false),
    DEPS(7, "deps", R.string.tab_deps, Accent.ACCENT, false),
    PROFILER(8, "profiler", R.string.tab_profiler, Accent.ACCENT, false),
    TODO(9, "todo", R.string.tab_todo, Accent.ACCENT, false),
    CONSOLE(10, "console", R.string.tab_console, Accent.ACCENT, false);

    /** Which theme colour marks the active tab. */
    public enum Accent { SUCCESS, TEXT, ACCENT, GOLD }

    /** Legacy numeric id, still passed around as {@code switchBottomPanel(int)}. */
    public final int mode;
    /** Stable identifier for the saved order; never change a published one. */
    public final String key;
    /** Label shown in the settings list (the tab itself may use a glyph). */
    public final int labelRes;
    public final Accent accent;
    /** Debug panels only appear while a debug session is running. */
    public final boolean debugOnly;

    BottomPanel(int mode, String key, int labelRes, Accent accent, boolean debugOnly) {
        this.mode = mode;
        this.key = key;
        this.labelRes = labelRes;
        this.accent = accent;
        this.debugOnly = debugOnly;
    }

    /** Looks up a panel by its numeric mode, or {@code null}. */
    public static BottomPanel byMode(int mode) {
        for (BottomPanel panel : values()) {
            if (panel.mode == mode) return panel;
        }
        return null;
    }

    /** Looks up a panel by its saved key, or {@code null} for a stale entry. */
    public static BottomPanel byKey(String key) {
        if (key == null) return null;
        for (BottomPanel panel : values()) {
            if (panel.key.equals(key)) return panel;
        }
        return null;
    }

    /** Factory ordering, as a list of keys. */
    public static List<String> defaultOrder() {
        List<String> keys = new ArrayList<>(values().length);
        for (BottomPanel panel : values()) keys.add(panel.key);
        return keys;
    }

    /**
     * Turns a saved key list into panels: unknown keys (from an older or newer
     * build) are dropped, and panels missing from the list are appended in
     * factory order so a new tab still shows up after an update.
     */
    public static List<BottomPanel> resolveOrder(List<String> savedKeys) {
        List<BottomPanel> ordered = new ArrayList<>(values().length);
        if (savedKeys != null) {
            for (String key : savedKeys) {
                BottomPanel panel = byKey(key);
                if (panel != null && !ordered.contains(panel)) ordered.add(panel);
            }
        }
        for (BottomPanel panel : values()) {
            if (!ordered.contains(panel)) ordered.add(panel);
        }
        return Collections.unmodifiableList(ordered);
    }

    /** Panels the user may hide — the debug pair is driven by the session instead. */
    public static List<BottomPanel> hideable() {
        List<BottomPanel> out = new ArrayList<>();
        for (BottomPanel panel : values()) {
            if (!panel.debugOnly) out.add(panel);
        }
        return out;
    }

    /** Panels that must always remain reachable, whatever the saved settings say. */
    public static List<BottomPanel> alwaysVisible() {
        return Arrays.asList(RUN, PROBLEMS);
    }
}
