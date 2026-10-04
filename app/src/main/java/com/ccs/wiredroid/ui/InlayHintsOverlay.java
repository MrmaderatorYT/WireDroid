package com.ccs.wiredroid.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import com.ccs.wiredroid.util.AppTheme;
import com.ccs.wiredroid.util.InlayHints;

import java.util.ArrayList;
import java.util.List;

import io.github.rosemoe.sora.widget.CodeEditor;

/**
 * Parameter names for the literal arguments on a line, shown at its end.
 *
 * <p>Not in front of each argument, which is where an IDE on a desktop puts
 * them: that requires the editor to reserve horizontal space mid-line, and
 * sora-editor 0.23.6 does not — its {@code InlayHint} class exists but no
 * renderer consumes it. Drawing over the arguments instead would put the label
 * on top of the very value it describes.</p>
 *
 * <p>So the names are collected to the right of the code, in reading order,
 * where they answer the same question — what are these literals — without
 * covering anything. A separate view rather than a text span, because a span
 * would be part of the document: it would shift columns, land in the clipboard
 * and confuse the cursor.</p>
 */
public class InlayHintsOverlay extends View {

    private CodeEditor editor;
    private final List<InlayHints.Hint> hints = new ArrayList<>();

    private final Paint chip = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();

    private int chipColor = 0xFF1E1E1E;
    private int textColor = 0xFF808080;

    public InlayHintsOverlay(Context context) {
        this(context, null);
    }

    public InlayHintsOverlay(Context context, AttributeSet attrs) {
        super(context, attrs);
        chip.setStyle(Paint.Style.FILL);
        // Purely decorative, and always behind the caret the user is aiming at.
        setClickable(false);
        setFocusable(false);
    }

    public void setEditor(CodeEditor editor) {
        this.editor = editor;
        if (editor != null) {
            editor.subscribeEvent(io.github.rosemoe.sora.event.ScrollEvent.class, (event, subs) -> postInvalidate());
        }
        invalidate();
    }

    public void applyTheme(AppTheme theme) {
        if (theme == null) return;
        textColor = theme.editorComment;
        // Opaque, because on a narrow screen the label is pinned to the right
        // edge and may sit over code; a translucent chip would blend the two
        // into something unreadable.
        chipColor = 0xFF000000 | theme.bg;
        invalidate();
    }

    public void setHints(List<InlayHints.Hint> next) {
        hints.clear();
        if (next != null) hints.addAll(next);
        postInvalidate();
    }

    public void clear() {
        hints.clear();
        postInvalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (editor == null || hints.isEmpty()) return;

        int rowHeight = editor.getRowHeight();
        if (rowHeight <= 0) return;

        int first = editor.getFirstVisibleRow();
        int last = editor.getLastVisibleRow();
        float offsetY = editor.getOffsetY();
        // getCharOffsetX already adds the text region offset and subtracts the
        // horizontal scroll, so it is a view coordinate as it stands. The gutter
        // is only needed to know where text begins, for clipping.
        float gutter = editor.measureTextRegionOffset();

        text.setTextSize(editor.getTextSizePx() * 0.78f);
        text.setColor(textColor);
        float density = getResources().getDisplayMetrics().density;
        float pad = 3f * density;
        float radius = 3f * density;
        float gap = 12f * density;

        // One label per line, in the order the arguments appear.
        java.util.LinkedHashMap<Integer, StringBuilder> byLine = new java.util.LinkedHashMap<>();
        for (InlayHints.Hint h : hints) {
            if (h.line < first || h.line > last) continue;
            StringBuilder sb = byLine.get(h.line);
            if (sb == null) {
                sb = new StringBuilder();
                byLine.put(h.line, sb);
            }
            if (sb.length() > 0) sb.append(", ");
            sb.append(h.name);
        }

        for (java.util.Map.Entry<Integer, StringBuilder> e : byLine.entrySet()) {
            int row = e.getKey();
            String label = e.getValue().toString();
            float w = text.measureText(label);

            float x;
            try {
                int endColumn = editor.getText().getColumnCount(row);
                x = editor.getCharOffsetX(row, endColumn) + gap;
            } catch (Throwable t) {
                // The document changed under a stale hint list; skip the line
                // rather than let a repaint throw.
                continue;
            }
            // On a phone the end of the line is usually past the right edge, so
            // the label is pulled back into view. It then covers the tail of a
            // long line — the lesser cost, since a hint nobody can see is worth
            // nothing at all.
            float rightmost = getWidth() - w - pad * 2;
            if (x > rightmost) x = rightmost;
            if (x < gutter) continue;
            float top = editor.getRowTop(row) - offsetY;
            float centre = top + rowHeight / 2f;
            float textY = centre - (text.descent() + text.ascent()) / 2f;

            box.set(x - pad, centre - rowHeight * 0.36f,
                    x + w + pad, centre + rowHeight * 0.36f);
            chip.setColor(chipColor);
            canvas.drawRoundRect(box, radius, radius, chip);
            canvas.drawText(label, x, textY, text);
        }
    }
}
