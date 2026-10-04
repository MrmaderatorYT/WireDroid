package com.ccs.wiredroid.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

import com.ccs.wiredroid.analysis.ProblemItem;

import java.util.ArrayList;
import java.util.List;

/**
 * The strip of marks down the right edge showing where the problems are.
 *
 * <p>A file is longer than a screen, so the count in the Problems panel says how
 * many there are but not <em>where</em>. This maps the whole file onto the
 * height of the editor: a red tick a third of the way down means an error a
 * third of the way through the file, whether or not that part is on screen.</p>
 *
 * <p>Tapping a mark jumps to its line. Everywhere else the touch is declined so
 * it falls through to the editor underneath — the strip is 8dp wide and sits
 * over the text, and swallowing scrolls along that edge would be worse than the
 * feature is worth.</p>
 */
public class ErrorStripeOverlay extends View {

    /** One mark: which line, and how loud. */
    private static final class Mark {
        final int line;
        final ProblemItem.Severity severity;

        Mark(int line, ProblemItem.Severity severity) {
            this.line = line;
            this.severity = severity;
        }
    }

    public interface Listener {
        /** A mark was tapped; {@code line} is 0-based. */
        void onStripeLineSelected(int line);
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mark = new RectF();
    private final List<Mark> marks = new ArrayList<>();

    private int lineCount = 1;
    private int errorColor = 0xFFE05252;
    private int warningColor = 0xFFD8A02E;
    private int infoColor = 0xFF5C9BD6;
    @Nullable private Listener listener;

    public ErrorStripeOverlay(Context context) {
        super(context);
    }

    public ErrorStripeOverlay(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public void setListener(@Nullable Listener listener) {
        this.listener = listener;
    }

    /** Colours come from the theme so the strip matches the Problems panel. */
    public void setColors(int error, int warning, int info) {
        this.errorColor = error;
        this.warningColor = warning;
        this.infoColor = info;
        invalidate();
    }

    /**
     * Replaces the marks.
     *
     * @param problems what the analyzer found, in any order
     * @param lines    how many lines the file has, which sets the scale
     */
    public void setProblems(@Nullable List<ProblemItem> problems, int lines) {
        marks.clear();
        this.lineCount = Math.max(1, lines);
        if (problems != null) {
            for (ProblemItem problem : problems) {
                if (problem == null || problem.line <= 0) continue;
                marks.add(new Mark(problem.line - 1, problem.severity));
            }
        }
        setVisibility(marks.isEmpty() ? GONE : VISIBLE);
        invalidate();
    }

    public void clear() {
        setProblems(null, 1);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (marks.isEmpty()) return;
        float width = getWidth();
        float height = getHeight();
        if (width <= 0 || height <= 0) return;

        // A minimum height, because one line out of three thousand would
        // otherwise round to nothing and the mark would not be there at all.
        float thickness = Math.max(2f, getResources().getDisplayMetrics().density * 2f);
        float inset = width * 0.2f;

        for (Mark m : marks) {
            float y = height * ((float) m.line / lineCount);
            mark.set(inset, y, width - inset, y + thickness);
            paint.setColor(colorOf(m.severity));
            canvas.drawRoundRect(mark, thickness * 0.5f, thickness * 0.5f, paint);
        }
    }

    private int colorOf(ProblemItem.Severity severity) {
        if (severity == ProblemItem.Severity.ERROR) return errorColor;
        if (severity == ProblemItem.Severity.WARNING) return warningColor;
        if (severity == ProblemItem.Severity.SECURITY) return errorColor;
        return infoColor;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (marks.isEmpty() || listener == null) return false;
        if (event.getAction() != MotionEvent.ACTION_DOWN) return false;

        float height = getHeight();
        if (height <= 0) return false;
        // Nearest mark within a finger's reach of the tap, so a 2px line is
        // still hittable; anything further away is not meant for the strip.
        float tolerance = getResources().getDisplayMetrics().density * 12f;
        Mark nearest = null;
        float best = Float.MAX_VALUE;
        for (Mark m : marks) {
            float y = height * ((float) m.line / lineCount);
            float distance = Math.abs(y - event.getY());
            if (distance < best) {
                best = distance;
                nearest = m;
            }
        }
        if (nearest == null || best > tolerance) return false;
        listener.onStripeLineSelected(nearest.line);
        return true;
    }
}
