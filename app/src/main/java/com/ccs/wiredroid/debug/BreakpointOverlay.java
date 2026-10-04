package com.ccs.wiredroid.debug;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;

import java.util.HashSet;
import java.util.Set;

import io.github.rosemoe.sora.widget.CodeEditor;

public class BreakpointOverlay extends View {

    private final Paint paint = new Paint();
    private CodeEditor editor;
    private final Set<Integer> breakpointLines = new HashSet<>();
    private final Set<Integer> conditionalLines = new HashSet<>();
    private int highlightedLine = -1;

    public BreakpointOverlay(Context context) {
        super(context);
        init();
    }

    public BreakpointOverlay(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public BreakpointOverlay(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setWillNotDraw(false);
        setClickable(false);
        setFocusable(false);
        setTranslationZ(10f);
    }

    private static final String TAG = "BpOverlay";

    public void setEditor(CodeEditor editor) {
        this.editor = editor;
        Log.d(TAG, "setEditor: editor=" + (editor != null) + " tag=" + getTag());
    }

    public void setBreakpoints(Set<Integer> lines, Set<Integer> condLines) {
        breakpointLines.clear();
        conditionalLines.clear();
        if (lines != null) breakpointLines.addAll(lines);
        if (condLines != null) conditionalLines.addAll(condLines);
        Log.d(TAG, "setBreakpoints: count=" + breakpointLines.size() + " lines=" + breakpointLines
                + " visible=" + getVisibility() + " w=" + getWidth() + " h=" + getHeight()
                + " attached=" + isAttachedToWindow());
        postInvalidate();
    }

    public void setHighlightedLine(int line) {
        highlightedLine = line;
        postInvalidate();
    }

    public void clearHighlight() {
        highlightedLine = -1;
        postInvalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (editor == null) return;

        int rowHeight = editor.getRowHeight();
        if (rowHeight <= 0) return;

        int firstVisible = editor.getFirstVisibleRow();
        int lastVisible = editor.getLastVisibleRow();
        float offsetY = editor.getOffsetY();
        float w = getWidth();

        // Draw debug line highlight first (below breakpoints)
        if (highlightedLine >= 0) {
            int row = highlightedLine - 1;
            if (row >= firstVisible && row <= lastVisible) {
                float top = editor.getRowTop(row) - offsetY;
                paint.setColor(0x40E04B4B);
                canvas.drawRect(0, top, w, top + rowHeight, paint);
            }
        }

        // Draw breakpoint row backgrounds
        int drawn = 0;
        for (int line1 : breakpointLines) {
            int row = line1 - 1;
            if (row < firstVisible || row > lastVisible) continue;

            boolean cond = conditionalLines.contains(line1);
            int color = cond ? 0x40FFC107 : 0x30E04B4B;
            paint.setColor(color);

            float top = editor.getRowTop(row) - offsetY;
            canvas.drawRect(0, top, w, top + rowHeight, paint);
            drawn++;
        }
        Log.d(TAG, "onDraw: drawn=" + drawn + " highlighted=" + highlightedLine + " rows=[" + firstVisible + "-" + lastVisible + "] bps=" + breakpointLines);
    }
}
