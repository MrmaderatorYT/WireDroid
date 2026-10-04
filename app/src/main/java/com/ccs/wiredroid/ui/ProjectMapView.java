package com.ccs.wiredroid.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

/**
 * The project map canvas: classes as circles, dependencies as lines, drawn
 * straight onto a {@link Canvas}.
 *
 * <p>Node radius grows with the number of incoming edges, so the classes half
 * the project depends on are the big ones. Colour comes from the package
 * bucket. Labels disappear below {@link #LABEL_MIN_SCALE} because at that zoom
 * they overlap into an unreadable smear.</p>
 *
 * <p>Positions are owned by the layout thread and handed over through
 * {@link #setPositions}; the view never computes them itself.</p>
 */
public class ProjectMapView extends View {

    /** Below this zoom labels overlap, so they are dropped entirely. */
    private static final float LABEL_MIN_SCALE = 0.45f;
    private static final float MIN_SCALE = 0.05f;
    private static final float MAX_SCALE = 5f;
    /** Alpha applied to everything that is not the highlighted neighbourhood. */
    private static final int DIM_ALPHA = 38;

    /** Reported back to the activity; the view itself only paints. */
    public interface Listener {
        void onNodeTap(int index);

        void onNodeLongPress(int index);

        void onSelectionCleared();
    }

    private final Paint nodePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelShadow = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float[] posX = new float[0];
    private float[] posY = new float[0];
    private float[] radius = new float[0];
    private int[] nodeColor = new int[0];
    private String[] labels = new String[0];
    private int[] edgeFrom = new int[0];
    private int[] edgeTo = new int[0];

    private int selected = -1;
    private boolean[] neighbour = new boolean[0];

    private float scale = 1f;
    private float offsetX = 0f;
    private float offsetY = 0f;
    private boolean pendingFit = false;

    private int colorEdge = 0x66888888;
    private int colorEdgeOut = 0xFF4A86C8;
    private int colorEdgeIn = 0xFF499C54;
    private int colorLabel = 0xFFBBBBBB;
    private int colorLabelBg = 0xFF2B2B2B;
    private int colorRing = 0xFFFFFFFF;

    private GestureDetector gestures;
    private ScaleGestureDetector scaleGestures;
    private Listener listener;

    public ProjectMapView(Context context) {
        super(context);
        init(context);
    }

    public ProjectMapView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        setWillNotDraw(false);
        setFocusable(true);

        nodePaint.setStyle(Paint.Style.FILL);
        ringPaint.setStyle(Paint.Style.STROKE);
        edgePaint.setStyle(Paint.Style.STROKE);
        edgePaint.setStrokeCap(Paint.Cap.ROUND);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setTypeface(Typeface.DEFAULT_BOLD);
        labelPaint.setTextSize(dp(11));
        labelShadow.setTextAlign(Paint.Align.CENTER);
        labelShadow.setTypeface(Typeface.DEFAULT_BOLD);
        labelShadow.setTextSize(dp(11));
        labelShadow.setStyle(Paint.Style.STROKE);
        labelShadow.setStrokeWidth(dp(2.5f));

        scaleGestures = new ScaleGestureDetector(context,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        zoomBy(detector.getScaleFactor(),
                                detector.getFocusX(), detector.getFocusY());
                        return true;
                    }
                });

        gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                offsetX -= dx;
                offsetY -= dy;
                invalidate();
                return true;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                int hit = nodeAt(e.getX(), e.getY());
                if (hit >= 0) {
                    if (listener != null) listener.onNodeTap(hit);
                } else if (selected >= 0) {
                    clearSelection();
                    if (listener != null) listener.onSelectionCleared();
                }
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                fitToScreen();
                return true;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                int hit = nodeAt(e.getX(), e.getY());
                if (hit < 0) return;
                select(hit);
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                if (listener != null) listener.onNodeLongPress(hit);
            }
        });
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setThemeColors(int edge, int edgeOut, int edgeIn, int label, int labelBg, int ring) {
        this.colorEdge = edge;
        this.colorEdgeOut = edgeOut;
        this.colorEdgeIn = edgeIn;
        this.colorLabel = label;
        this.colorLabelBg = labelBg;
        this.colorRing = ring;
        invalidate();
    }

    /**
     * Installs the graph. Positions arrive separately as the layout settles.
     *
     * @param radii   world-unit radii, already sized by incoming edge count
     */
    public void setGraph(String[] labels, int[] colors, float[] radii,
                         int[] edgeFrom, int[] edgeTo) {
        this.labels = labels;
        this.nodeColor = colors;
        this.radius = radii;
        this.edgeFrom = edgeFrom;
        this.edgeTo = edgeTo;
        this.posX = new float[labels.length];
        this.posY = new float[labels.length];
        this.neighbour = new boolean[labels.length];
        this.selected = -1;
        pendingFit = true;
        invalidate();
    }

    /** Called on the UI thread with a snapshot from the layout thread. */
    public void setPositions(float[] x, float[] y) {
        if (x.length != posX.length) return;
        System.arraycopy(x, 0, posX, 0, x.length);
        System.arraycopy(y, 0, posY, 0, y.length);
        invalidate();
    }

    public int nodeCount() {
        return posX.length;
    }

    public int getSelected() {
        return selected;
    }

    public void clearSelection() {
        selected = -1;
        java.util.Arrays.fill(neighbour, false);
        invalidate();
    }

    /** Highlights a node and its direct neighbours, dimming everything else. */
    public void select(int index) {
        if (index < 0 || index >= posX.length) return;
        selected = index;
        java.util.Arrays.fill(neighbour, false);
        neighbour[index] = true;
        for (int i = 0; i < edgeFrom.length; i++) {
            if (edgeFrom[i] == index) neighbour[edgeTo[i]] = true;
            else if (edgeTo[i] == index) neighbour[edgeFrom[i]] = true;
        }
        invalidate();
    }

    /** Direct neighbours of the highlighted node, or 0 when nothing is selected. */
    public int neighbourCount() {
        if (selected < 0) return 0;
        int count = 0;
        for (boolean b : neighbour) if (b) count++;
        return Math.max(0, count - 1);
    }

    /** Frames the whole graph, with a little breathing room. */
    public void fitToScreen() {
        if (posX.length == 0) return;
        if (getWidth() == 0 || getHeight() == 0) {
            pendingFit = true;
            return;
        }
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (int i = 0; i < posX.length; i++) {
            minX = Math.min(minX, posX[i] - radius[i]);
            maxX = Math.max(maxX, posX[i] + radius[i]);
            minY = Math.min(minY, posY[i] - radius[i]);
            maxY = Math.max(maxY, posY[i] + radius[i]);
        }
        float boundsW = Math.max(1f, maxX - minX);
        float boundsH = Math.max(1f, maxY - minY);
        float pad = dp(28);
        float fit = Math.min((getWidth() - 2 * pad) / boundsW,
                (getHeight() - 2 * pad) / boundsH);
        scale = clamp(fit, MIN_SCALE, 2f);
        float centerX = (minX + maxX) / 2f;
        float centerY = (minY + maxY) / 2f;
        offsetX = getWidth() / 2f - centerX * scale;
        offsetY = getHeight() / 2f - centerY * scale;
        pendingFit = false;
        invalidate();
    }

    private void zoomBy(float factor, float focusX, float focusY) {
        float next = clamp(scale * factor, MIN_SCALE, MAX_SCALE);
        float applied = next / scale;
        offsetX = focusX - (focusX - offsetX) * applied;
        offsetY = focusY - (focusY - offsetY) * applied;
        scale = next;
        invalidate();
    }

    private int nodeAt(float screenX, float screenY) {
        float worldX = (screenX - offsetX) / scale;
        float worldY = (screenY - offsetY) / scale;
        // Small circles are hard to hit; widen the target to a finger's width.
        float slopWorld = dp(10) / scale;
        int best = -1;
        float bestDist = Float.MAX_VALUE;
        for (int i = 0; i < posX.length; i++) {
            float dx = worldX - posX[i];
            float dy = worldY - posY[i];
            float dist = (float) Math.sqrt(dx * dx + dy * dy);
            float reach = radius[i] + slopWorld;
            if (dist <= reach && dist < bestDist) {
                bestDist = dist;
                best = i;
            }
        }
        return best;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (pendingFit) fitToScreen();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleGestures.onTouchEvent(event);
        gestures.onTouchEvent(event);
        return true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int n = posX.length;
        if (n == 0) return;

        canvas.save();
        canvas.translate(offsetX, offsetY);
        canvas.scale(scale, scale);

        // Edges. Stroke width is divided by the zoom so lines keep a constant
        // on-screen weight instead of vanishing when zoomed out.
        float stroke = Math.max(0.15f, dp(1.1f) / scale);
        for (int i = 0; i < edgeFrom.length; i++) {
            int a = edgeFrom[i];
            int b = edgeTo[i];
            if (selected >= 0) {
                if (a == selected) {
                    edgePaint.setColor(colorEdgeOut);
                    edgePaint.setAlpha(230);
                    edgePaint.setStrokeWidth(stroke * 2f);
                } else if (b == selected) {
                    edgePaint.setColor(colorEdgeIn);
                    edgePaint.setAlpha(230);
                    edgePaint.setStrokeWidth(stroke * 2f);
                } else {
                    edgePaint.setColor(colorEdge);
                    edgePaint.setAlpha(DIM_ALPHA / 2);
                    edgePaint.setStrokeWidth(stroke);
                }
            } else {
                edgePaint.setColor(colorEdge);
                edgePaint.setAlpha(110);
                edgePaint.setStrokeWidth(stroke);
            }
            canvas.drawLine(posX[a], posY[a], posX[b], posY[b], edgePaint);
        }

        // Nodes.
        float left = -offsetX / scale;
        float top = -offsetY / scale;
        float right = (getWidth() - offsetX) / scale;
        float bottom = (getHeight() - offsetY) / scale;
        for (int i = 0; i < n; i++) {
            float r = radius[i];
            if (posX[i] + r < left || posX[i] - r > right
                    || posY[i] + r < top || posY[i] - r > bottom) {
                continue;
            }
            nodePaint.setColor(nodeColor[i]);
            if (selected >= 0 && !neighbour[i]) nodePaint.setAlpha(DIM_ALPHA);
            else nodePaint.setAlpha(255);
            canvas.drawCircle(posX[i], posY[i], r, nodePaint);
            if (i == selected) {
                ringPaint.setColor(colorRing);
                ringPaint.setStrokeWidth(Math.max(0.4f, dp(2.5f) / scale));
                canvas.drawCircle(posX[i], posY[i], r + dp(3) / scale, ringPaint);
            }
        }
        canvas.restore();

        drawLabels(canvas);
    }

    /**
     * Labels live in screen space so text stays legible at any zoom. They are
     * skipped wholesale when zoomed out, except for the highlighted
     * neighbourhood, which stays readable because that is the point of it.
     */
    private void drawLabels(Canvas canvas) {
        boolean showAll = scale >= LABEL_MIN_SCALE;
        if (!showAll && selected < 0) return;

        float baseline = labelPaint.getTextSize();
        labelShadow.setColor(colorLabelBg);
        for (int i = 0; i < posX.length; i++) {
            boolean highlighted = selected >= 0 && neighbour[i];
            if (!showAll && !highlighted) continue;
            if (selected >= 0 && !highlighted) continue;

            float sx = posX[i] * scale + offsetX;
            float sy = posY[i] * scale + offsetY;
            if (sx < -dp(60) || sx > getWidth() + dp(60)
                    || sy < -dp(40) || sy > getHeight() + dp(40)) {
                continue;
            }
            float ty = sy + radius[i] * scale + baseline;
            String label = labels[i];
            labelPaint.setColor(colorLabel);
            labelPaint.setAlpha(255);
            canvas.drawText(label, sx, ty, labelShadow);
            canvas.drawText(label, sx, ty, labelPaint);
        }
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (Math.min(v, hi));
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
