package com.ccs.wiredroid.ui;

import android.widget.FrameLayout;

import com.ccs.wiredroid.debug.BookmarkOverlay;
import com.ccs.wiredroid.debug.BreakpointOverlay;

import io.github.rosemoe.sora.widget.CodeEditor;

/**
 * The editors on screen and what each of them is showing.
 *
 * <p>This is the state {@code MainActivity} grew around. Measured before it was
 * pulled out: {@code activeEditor} was named 154 times in that one file,
 * {@code tabsAdapter} 121, and the twenty fields here 584 times between them.
 * That is why extracting <em>methods</em> from that class never worked — the
 * least-coupled forty of them still needed fourteen collaborators, because
 * anything that touches a file needs half of this.</p>
 *
 * <p>Deliberately a plain holder, not a manager. The fields are package-visible
 * and the behaviour stays where it already is, so pulling them together changes
 * nothing at runtime and cannot change behaviour. What it buys is a name: the
 * controllers that currently take a whole {@code MainActivity} can be handed
 * this instead, and each field can then be closed off one at a time behind an
 * accessor without another five-hundred-site edit.</p>
 *
 * <p>One instance per activity, created with it and living as long as it does.
 * Nothing here is touched off the main thread.</p>
 */
final class EditorWorkspace {

    /** Left-hand editor; the only one until the view is split. */
    CodeEditor editor;
    /** Right-hand editor, present in the layout but hidden until then. */
    CodeEditor editor2;
    /**
     * Whichever of the two has focus. Aliases one of the fields above rather
     * than holding a third editor, so identity comparisons against
     * {@code editor} are the normal way to ask which side is active.
     */
    CodeEditor activeEditor;

    /** The file each pane is showing, or null for an empty pane. */
    FileTab leftTab;
    FileTab rightTab;

    /**
     * The tab strip's model — one per pane, as in IntelliJ.
     *
     * <p>It used to be a single list shared by both editors, which meant the
     * strip could not say which pane a file was open in: splitting did not add
     * a tab anywhere, and unsplitting had to be asked for through the same
     * long-press menu that had created the split. With a list per pane the
     * strip carries that state itself — a file opened beside moves into the
     * second strip, and closing the last tab there is what ends the split.</p>
     */
    TabsAdapter tabsLeft;
    TabsAdapter tabsRight;

    /**
     * The tab list of the pane that has focus.
     *
     * <p>Written as a call rather than a field kept in step with
     * {@code activeEditor}: almost every caller means "the tabs of the pane the
     * user is in", and deriving it makes that automatic instead of something
     * each of the hundred-odd call sites has to be trusted to maintain.</p>
     */
    TabsAdapter tabs() {
        return activeEditor == editor2 ? tabsRight : tabsLeft;
    }

    /** The tab list belonging to a particular pane. */
    TabsAdapter tabsFor(CodeEditor pane) {
        return pane == editor2 ? tabsRight : tabsLeft;
    }

    /** Every open tab across both panes, left strip first. */
    java.util.List<FileTab> allTabs() {
        java.util.List<FileTab> all = new java.util.ArrayList<>();
        if (tabsLeft != null) all.addAll(tabsLeft.getTabs());
        if (tabsRight != null) all.addAll(tabsRight.getTabs());
        return all;
    }

    /** Whether the second pane is showing. */
    boolean isSplitActive = false;

    /** The frames the two editors sit in; used for the active-pane border. */
    FrameLayout wrapperEditor1;
    FrameLayout wrapperEditor2;

    /** Per-pane minimaps, hidden in portrait and when the setting is off. */
    MinimapView minimapView1;
    MinimapView minimapView2;

    /**
     * The things drawn on top of each pane. One of each per editor, for the same
     * reason the editors come in pairs — they belong to a pane, not to the screen.
     */
    BreakpointOverlay breakpointOverlay1;
    BreakpointOverlay breakpointOverlay2;
    BookmarkOverlay bookmarkOverlay1;
    BookmarkOverlay bookmarkOverlay2;
    InlayHintsOverlay inlayOverlay1;
    InlayHintsOverlay inlayOverlay2;

    /** Debug decoration for whichever pane is active; replaced when focus moves. */
    com.ccs.wiredroid.debug.DebugEditorDecorator activeEditorDecorator;

    /** The draggable handle between the two panes, shown only while split. */
    android.view.View editorDivider;

    /**
     * What the handle actually draws.
     *
     * <p>Held separately because the handle itself is finger-wide and stays
     * transparent — painting the whole of it would put a 14dp bar between the
     * panes instead of a seam.</p>
     */
    android.view.View editorDividerLine;
    android.view.View editorDividerGrip;

    /**
     * Set while the app is writing into an editor itself, so the text-changed
     * listeners can tell a programmatic edit from something the user typed and
     * not treat it as an unsaved change.
     */
    boolean isProgrammaticChange = false;

    /** The tab belonging to the pane that has focus, or null. */
    FileTab activeTab() {
        return activeEditor == editor ? leftTab : rightTab;
    }

    /** The file in the pane that has focus, or null when that pane is empty. */
    java.io.File activeFile() {
        FileTab tab = activeTab();
        return tab != null ? tab.file : null;
    }
}
