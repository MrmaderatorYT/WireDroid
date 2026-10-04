package com.ccs.wiredroid.debug;

import android.util.Log;

import java.util.Set;

import io.github.rosemoe.sora.widget.CodeEditor;

public final class DebugEditorDecorator {

    private static final String TAG = "BpOverlay";

    private final CodeEditor editor;
    private final BreakpointOverlay overlay;

    public DebugEditorDecorator(CodeEditor editor, BreakpointOverlay overlay) {
        this.editor = editor;
        this.overlay = overlay;
        Log.d(TAG, "ctor: editor=" + (editor != null) + " overlay=" + (overlay != null));
        if (overlay != null) overlay.setEditor(editor);
    }

    public void refreshBreakpoints(Set<Integer> bpLines, Set<Integer> conditionalLines) {
        Log.d(TAG, "refreshBreakpoints: overlay=" + (overlay != null) + " bpLines=" + (bpLines != null ? bpLines.size() : "null") + " lines=" + bpLines);
        if (overlay != null) {
            overlay.setBreakpoints(bpLines, conditionalLines);
        }
    }

    public void highlightLine(int line) {
        if (overlay != null) overlay.setHighlightedLine(line);
    }

    public void clearHighlight() {
        if (overlay != null) overlay.clearHighlight();
    }
}
