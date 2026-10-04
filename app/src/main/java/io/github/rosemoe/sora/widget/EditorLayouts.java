package io.github.rosemoe.sora.widget;

import io.github.rosemoe.sora.widget.layout.Layout;

/**
 * Reaches the editor's layout field, which sora keeps to its own package.
 *
 * <p>{@code CodeEditor.layout} is package-private and there is no setter, so a
 * layout that hides folded lines cannot be installed from outside. This class
 * sits in sora's package to get at the field — a declared package, not
 * reflection, so the compiler still checks it and an upstream rename becomes a
 * build error instead of a crash on a user's phone.</p>
 *
 * <p>Nothing else here depends on sora internals. If a future version of the
 * editor exposes a real hook, this file is the only one that has to go.</p>
 */
public final class EditorLayouts {

    private EditorLayouts() {}

    /** Replaces the layout the editor draws through. */
    public static void install(CodeEditor editor, Layout layout) {
        editor.layout = layout;
    }
}
