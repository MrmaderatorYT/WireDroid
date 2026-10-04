package com.ccs.wiredroid.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import com.ccs.wiredroid.git.GitGutterComputer;

import java.util.ArrayList;
import java.util.List;

import io.github.rosemoe.sora.widget.CodeEditor;

/**
 * Paints VS Code style change bars in the strip left of the line numbers.
 *
 * <p>The view covers the whole editor so it can use the editor's own row
 * geometry, but it only claims touches that land on a marker: every other
 * pointer event is declined from {@link #onTouchEvent}, so it falls straight
 * through to the {@link CodeEditor} underneath and text selection keeps
 * working exactly as before.</p>
 */
public class GitGutterOverlay extends View {

    /** Painted width of the change bar. */
    private static final float BAR_WIDTH_DP = 4f;
    /** Touch slop around the bar — 4dp is honest to look at but impossible to hit. */
    private static final float HIT_WIDTH_DP = 22f;

    public interface OnHunkClickListener {
        void onHunkClicked(GitGutterComputer.Hunk hunk);
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path wedge = new Path();

    private CodeEditor editor;
    private List<GitGutterComputer.Hunk> hunks = new ArrayList<>();
    private OnHunkClickListener listener;

    private int addedColor = 0xFF4CAF50;
    private int modifiedColor = 0xFF3F8CD0;
    private int deletedColor = 0xFFE05252;

    private float downX = -1f;
    private float downY = -1f;

    public GitGutterOverlay(Context context) {
        super(context);
        init();
    }

    public GitGutterOverlay(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public GitGutterOverlay(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setWillNotDraw(false);
        // Deliberately not clickable: a clickable View swallows every DOWN and
        // the editor below would stop seeing taps.
        setClickable(false);
        setFocusable(false);
        setTranslationZ(11f);
        paint.setStyle(Paint.Style.FILL);
    }

    public void setEditor(CodeEditor editor) {
        this.editor = editor;
    }

    public void setOnHunkClickListener(OnHunkClickListener l) {
        this.listener = l;
    }

    public void setColors(int added, int modified, int deleted) {
        this.addedColor = added;
        this.modifiedColor = modified;
        this.deletedColor = deleted;
        postInvalidate();
    }

    /** Replaces the marks. Safe from any thread. */
    public void setHunks(List<GitGutterComputer.Hunk> newHunks) {
        this.hunks = newHunks == null ? new ArrayList<>() : newHunks;
        postInvalidate();
    }

    public void clearHunks() {
        setHunks(null);
    }

    public boolean hasHunks() {
        return !hunks.isEmpty();
    }

    // ── Drawing ───────────────────────────────────────────────

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (editor == null || hunks.isEmpty()) return;
        try {
            int rowHeight = editor.getRowHeight();
            if (rowHeight <= 0) return;

            int firstVisible = editor.getFirstVisibleRow();
            int lastVisible = editor.getLastVisibleRow();
            float offsetY = editor.getOffsetY();
            float density = getResources().getDisplayMetrics().density;
            float barWidth = BAR_WIDTH_DP * density;

            for (GitGutterComputer.Hunk h : hunks) {
                if (h.type == GitGutterComputer.TYPE_DELETED) {
                    int row = h.startLine;
                    if (row < firstVisible - 1 || row > lastVisible + 1) continue;
                    float top = editor.getRowTop(Math.max(0, row)) - offsetY;
                    drawDeletionWedge(canvas, top, barWidth, density);
                    continue;
                }
                int from = Math.max(h.startLine, firstVisible);
                int to = Math.min(h.endLine - 1, lastVisible);
                if (from > to) continue;
                float top = editor.getRowTop(from) - offsetY;
                float bottom = editor.getRowTop(to) - offsetY + rowHeight;
                paint.setColor(h.type == GitGutterComputer.TYPE_ADDED ? addedColor : modifiedColor);
                canvas.drawRect(0, top, barWidth, bottom, paint);
            }
        } catch (Throwable ignored) {
            // Editor geometry can be queried mid-relayout; a missing frame of
            // decoration is preferable to taking the window down.
        }
    }

    /** A small triangle wedged between the two lines that used to surround the deletion. */
    private void drawDeletionWedge(Canvas canvas, float boundaryY, float barWidth, float density) {
        float h = 5f * density;
        float w = barWidth * 2.2f;
        wedge.reset();
        wedge.moveTo(0, boundaryY - h);
        wedge.lineTo(w, boundaryY);
        wedge.lineTo(0, boundaryY + h);
        wedge.close();
        paint.setColor(deletedColor);
        canvas.drawPath(wedge, paint);
    }

    // ── Touch ─────────────────────────────────────────────────

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (editor == null || hunks.isEmpty() || listener == null) return false;

        float density = getResources().getDisplayMetrics().density;
        float hitWidth = HIT_WIDTH_DP * density;

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (event.getX() > hitWidth) return false;
                if (hunkAt(event.getY()) == null) return false;
                downX = event.getX();
                downY = event.getY();
                return true;
            case MotionEvent.ACTION_UP: {
                if (downY < 0) return false;
                float slop = 12f * density;
                boolean moved = Math.abs(event.getX() - downX) > slop
                        || Math.abs(event.getY() - downY) > slop;
                GitGutterComputer.Hunk h = moved ? null : hunkAt(downY);
                downX = downY = -1f;
                if (h != null) {
                    listener.onHunkClicked(h);
                    return true;
                }
                return false;
            }
            case MotionEvent.ACTION_CANCEL:
                downX = downY = -1f;
                return false;
            default:
                return downY >= 0;
        }
    }

    /** @return the hunk whose band contains {@code y}, or null */
    private GitGutterComputer.Hunk hunkAt(float y) {
        try {
            int row = rowAt(y);
            if (row < 0) return null;
            for (GitGutterComputer.Hunk h : hunks) {
                if (h.coversRow(row)) return h;
            }
            // A deletion wedge sits on a boundary — accept the neighbouring row too.
            for (GitGutterComputer.Hunk h : hunks) {
                if (h.type == GitGutterComputer.TYPE_DELETED
                        && (h.startLine == row || h.startLine == row + 1)) return h;
            }
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private int rowAt(float y) {
        int rowHeight = editor.getRowHeight();
        if (rowHeight <= 0) return -1;
        int firstVisible = editor.getFirstVisibleRow();
        float offsetY = editor.getOffsetY();
        float firstTop = editor.getRowTop(firstVisible) - offsetY;
        int row = firstVisible + (int) Math.floor((y - firstTop) / rowHeight);
        return row < 0 ? -1 : row;
    }
}
