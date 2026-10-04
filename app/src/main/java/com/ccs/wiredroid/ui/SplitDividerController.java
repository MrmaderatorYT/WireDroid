package com.ccs.wiredroid.ui;

import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Makes the seam between the two editor panes draggable.
 *
 * <p>A fixed 1:1 split is only right by accident. One pane usually holds the
 * file being read and the other the file being edited, and on a phone-width
 * screen half of it is not enough for either — so the panes have to be
 * resizable, and the seam is the only place to grab.</p>
 *
 * <p>The split is stored and applied as <em>weights</em>, never as pixel widths.
 * A ratio survives the container changing size — rotation, the keyboard opening,
 * the file drawer closing — where two fixed widths would either overflow or
 * leave a gap.</p>
 */
final class SplitDividerController {

    /** Nothing narrower than this is usable, so neither pane may go below it. */
    private static final int MIN_PANE_DP = 96;

    /** Two taps closer together than this count as a double tap. */
    private static final long DOUBLE_TAP_MS = 300;

    private int lineIdle;
    private int gripIdle;
    private int activeColor;

    private final View handle;
    private final View line;
    private final View grip;
    private final View container;
    private final View leftPane;
    private final View rightPane;
    @Nullable private final View leftTabs;
    @Nullable private final View rightTabs;
    private final RatioStore store;

    /** Where the resized split is remembered between runs. */
    interface RatioStore {
        float getSplitRatio();
        void setSplitRatio(float ratio);
    }

    /**
     * @param handle    the view that is grabbed
     * @param line      the hairline it draws, coloured on grab
     * @param grip      the mark in the middle that says it can be moved
     * @param container the row holding both panes; its width is what gets divided
     * @param leftTabs  tab strip above {@code leftPane}, resized in step, or {@code null}
     */
    SplitDividerController(@NonNull View handle,
                           @Nullable View line,
                           @Nullable View grip,
                           @NonNull View container,
                           @NonNull View leftPane,
                           @NonNull View rightPane,
                           @Nullable View leftTabs,
                           @Nullable View rightTabs,
                           @NonNull RatioStore store) {
        this.handle = handle;
        this.line = line;
        this.grip = grip;
        this.container = container;
        this.leftPane = leftPane;
        this.rightPane = rightPane;
        this.leftTabs = leftTabs;
        this.rightTabs = rightTabs;
        this.store = store;
        bind();
    }

    private void bind() {
        handle.setOnTouchListener(new View.OnTouchListener() {
            private float startX;
            private int startLeftWidth;
            private int paneSpace;
            private boolean dragging;
            private long lastDownAt;
            private boolean moved;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN: {
                        startX = event.getRawX();
                        startLeftWidth = leftPane.getWidth();
                        paneSpace = startLeftWidth + rightPane.getWidth();
                        dragging = paneSpace > 2 * minPanePx();
                        moved = false;
                        // The tab strips scroll horizontally and the editor
                        // consumes drags of its own; without this the first
                        // sideways movement is stolen before it arrives here.
                        if (v.getParent() != null) {
                            v.getParent().requestDisallowInterceptTouchEvent(true);
                        }
                        v.setPressed(true);
                        setHandleColor(true);
                        return true;
                    }
                    case MotionEvent.ACTION_MOVE: {
                        if (!dragging) return true;
                        float travel = event.getRawX() - startX;
                        // Below the system's slop this is still a tap, and the
                        // split must not twitch under a finger that meant to
                        // double-tap it back to centre.
                        if (!moved && Math.abs(travel) < touchSlop()) return true;
                        moved = true;
                        float delta = isRtl() ? -travel : travel;
                        apply(clampToRatio(startLeftWidth + delta, paneSpace));
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL: {
                        v.setPressed(false);
                        setHandleColor(false);
                        if (v.getParent() != null) {
                            v.getParent().requestDisallowInterceptTouchEvent(false);
                        }
                        long now = event.getEventTime();
                        if (!moved && event.getActionMasked() == MotionEvent.ACTION_UP) {
                            // A tap that went nowhere. Two of them centre the
                            // split again, which is the only way back out of a
                            // pane dragged down to its minimum.
                            if (now - lastDownAt < DOUBLE_TAP_MS) {
                                reset();
                                lastDownAt = 0;
                                return true;
                            }
                            lastDownAt = now;
                            return true;
                        }
                        if (dragging && moved) {
                            store.setSplitRatio(currentRatio());
                        }
                        return true;
                    }
                    default:
                        return false;
                }
            }
        });
    }

    /**
     * Colours the seam, and picks the colour it flashes while held.
     *
     * <p>Owned here rather than in the caller's theme pass because the grab
     * highlight has to restore the idle colour afterwards, which means one place
     * has to know both.</p>
     */
    void applyTheme(int separator, int textDim, int accent) {
        lineIdle = separator;
        gripIdle = textDim;
        activeColor = accent;
        setHandleColor(false);
    }

    private void setHandleColor(boolean held) {
        if (line != null) line.setBackgroundColor(held ? activeColor : lineIdle);
        if (grip != null) grip.setBackgroundColor(held ? activeColor : gripIdle);
    }

    /** Puts the panes back at the stored ratio; call when the split opens. */
    void applyStoredRatio() {
        apply(sane(store.getSplitRatio()));
    }

    /** Back to an even split, and remembered as such. */
    void reset() {
        apply(0.5f);
        store.setSplitRatio(0.5f);
    }

    private float clampToRatio(float leftWidthPx, int space) {
        return ratioFor(leftWidthPx, space, minPanePx());
    }

    private float currentRatio() {
        return ratioFor(leftPane.getWidth(), leftPane.getWidth() + rightPane.getWidth(),
                minPanePx());
    }

    /**
     * Turns a pixel width for the left pane into a ratio both panes can live with.
     *
     * <p>Clamped in pixels rather than as a fraction because the floor is a
     * physical one: 96dp is 27% of a small phone and 12% of a tablet.</p>
     *
     * <p>Static and free of any {@link View} so the geometry can be checked
     * without a device — it is the part of a drag that is worth being sure
     * about, and the part a screenshot would not tell you much about anyway.</p>
     *
     * @param space total width available to both panes, excluding the handle
     */
    static float ratioFor(float leftWidthPx, int space, int minPanePx) {
        if (space <= 0) return 0.5f;
        if (space < 2 * minPanePx) {
            // No room to honour the floor on both sides; splitting the
            // difference beats pinning one pane shut.
            return 0.5f;
        }
        float clamped = Math.max(minPanePx, Math.min(space - minPanePx, leftWidthPx));
        return sane(clamped / space);
    }

    /** Guards against a stored value from a future version, or a divide-by-zero NaN. */
    static float sane(float ratio) {
        if (Float.isNaN(ratio)) return 0.5f;
        return Math.max(0.05f, Math.min(0.95f, ratio));
    }

    /** The floor, in pixels, for a given screen density. Visible for testing. */
    static int minPanePx(float density) {
        return Math.round(MIN_PANE_DP * density);
    }

    private void apply(float leftFraction) {
        setWeights(leftPane, rightPane, leftFraction);
        // The strips have to move with the panes, or a tab stops sitting over
        // the editor it opens in.
        setWeights(leftTabs, rightTabs, leftFraction);
        container.requestLayout();
    }

    private static void setWeights(@Nullable View left, @Nullable View right, float leftFraction) {
        if (left == null || right == null) return;
        if (!(left.getLayoutParams() instanceof LinearLayout.LayoutParams)
                || !(right.getLayoutParams() instanceof LinearLayout.LayoutParams)) {
            return;
        }
        LinearLayout.LayoutParams llp = (LinearLayout.LayoutParams) left.getLayoutParams();
        LinearLayout.LayoutParams rlp = (LinearLayout.LayoutParams) right.getLayoutParams();
        llp.width = 0;
        rlp.width = 0;
        llp.weight = leftFraction;
        rlp.weight = 1f - leftFraction;
        left.setLayoutParams(llp);
        right.setLayoutParams(rlp);
    }

    private boolean isRtl() {
        return container.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
    }

    private int minPanePx() {
        return minPanePx(handle.getResources().getDisplayMetrics().density);
    }

    private int touchSlop() {
        return ViewConfiguration.get(handle.getContext()).getScaledTouchSlop();
    }
}
