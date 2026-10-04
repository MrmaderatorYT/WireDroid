package com.ccs.wiredroid.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import com.ccs.wiredroid.profiler.ProfilerBridge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FlameChartView extends View {

    private List<ProfilerBridge.MethodProfile> profiles = new ArrayList<>();
    private final List<FlameNode> nodes = new ArrayList<>();
    private final Map<String, FlameNode> nodeMap = new HashMap<>();

    private final Paint bgPaint = new Paint();
    private final Paint rectPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selectedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint headerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF hitRect = new RectF();

    private float offsetX = 0;
    private float offsetX2 = 0;
    private float scale = 1f;
    private FlameNode selectedNode = null;

    private ScaleGestureDetector scaleDetector;
    private GestureDetector gestureDetector;
    private long totalTimeNs = 0;

    private static final int[] COLORS = {
            0xFF4FC3F7, 0xFF81C784, 0xFFFFB74D, 0xFFE57373,
            0xFFBA68C8, 0xFFFFD54F, 0xFF4DB6AC, 0xFF90A4AE,
            0xFFA1887F, 0xFF9FA8DA, 0xFFAED581, 0xFFFF8A65,
    };

    private OnNodeSelectedListener listener;

    public interface OnNodeSelectedListener {
        void onNodeSelected(ProfilerBridge.MethodProfile profile);
    }

    public FlameChartView(Context context) {
        super(context);
        init();
    }

    public FlameChartView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public FlameChartView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setWillNotDraw(false);

        bgPaint.setColor(0xFF1E1E1E);
        bgPaint.setStyle(Paint.Style.FILL);

        rectPaint.setStyle(Paint.Style.FILL);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(1f);
        borderPaint.setColor(0x40000000);

        textPaint.setColor(0xFFEEEEEE);
        textPaint.setTextSize(24f);
        textPaint.setTextAlign(Paint.Align.LEFT);

        selectedPaint.setStyle(Paint.Style.STROKE);
        selectedPaint.setStrokeWidth(3f);
        selectedPaint.setColor(0xFFFFD700);

        headerPaint.setColor(0xFF333333);
        headerPaint.setStyle(Paint.Style.FILL);

        scaleDetector = new ScaleGestureDetector(getContext(), new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                float newScale = scale * detector.getScaleFactor();
                newScale = Math.max(0.3f, Math.min(5f, newScale));
                float focusX = detector.getFocusX();
                offsetX -= (focusX - offsetX) * (newScale / scale - 1);
                scale = newScale;
                invalidate();
                return true;
            }
        });

        gestureDetector = new GestureDetector(getContext(), new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
                offsetX -= distanceX;
                invalidate();
                return true;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                float x = (e.getX() - offsetX) / scale;
                float y = e.getY();
                selectedNode = findNodeAt(x, y);
                invalidate();
                if (listener != null && selectedNode != null) {
                    listener.onNodeSelected(selectedNode.profile);
                }
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (selectedNode != null) {
                    // Zoom to fit selected node
                    float nodeWidth = selectedNode.width * scale;
                    float targetScale = getWidth() / Math.max(nodeWidth, 100);
                    targetScale = Math.max(0.3f, Math.min(5f, targetScale));
                    offsetX = getWidth() / 2f - (selectedNode.x + selectedNode.width / 2) * targetScale;
                    scale = targetScale;
                    invalidate();
                }
                return true;
            }
        });
    }

    public void setOnNodeSelectedListener(OnNodeSelectedListener l) {
        this.listener = l;
    }

    public void applyTheme(com.ccs.wiredroid.util.AppTheme theme) {
        bgPaint.setColor(theme.consoleBg);
        headerPaint.setColor(theme.toolbar);
        textPaint.setColor(theme.textDim);
        invalidate();
    }

    public void setProfiles(List<ProfilerBridge.MethodProfile> profiles) {
        this.profiles = profiles != null ? new ArrayList<>(profiles) : new ArrayList<>();
        buildFlameChart();
        invalidate();
    }

    public void clear() {
        profiles.clear();
        nodes.clear();
        nodeMap.clear();
        selectedNode = null;
        invalidate();
    }

    public FlameNode getSelectedNode() { return selectedNode; }

    private void buildFlameChart() {
        nodes.clear();
        nodeMap.clear();

        if (profiles.isEmpty()) return;

        totalTimeNs = 0;
        for (ProfilerBridge.MethodProfile mp : profiles) {
            totalTimeNs += mp.totalTime.get();
        }
        if (totalTimeNs == 0) return;

        // Sort by total time descending
        Collections.sort(profiles, (a, b) -> Long.compare(b.totalTime.get(), a.totalTime.get()));

        float totalWidth = getWidth() > 0 ? getWidth() : 1200;
        float y = 40;
        float rowHeight = 32;
        float x = 0;

        // Simple treemap layout: each method gets width proportional to its time
        for (int i = 0; i < profiles.size(); i++) {
            ProfilerBridge.MethodProfile mp = profiles.get(i);
            float width = (float) mp.totalTime.get() / totalTimeNs * totalWidth;
            if (width < 2) width = 2;

            FlameNode node = new FlameNode(mp, x, y, width, rowHeight - 2);
            node.colorIndex = i % COLORS.length;
            nodes.add(node);
            nodeMap.put(mp.fullSignature(), node);

            x += width;
            if (x >= totalWidth) {
                x = 0;
                y += rowHeight;
            }
        }
    }

    private FlameNode findNodeAt(float x, float y) {
        for (FlameNode n : nodes) {
            if (x >= n.x && x <= n.x + n.width && y >= n.y && y <= n.y + n.height) {
                return n;
            }
        }
        return null;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawRect(0, 0, getWidth(), getHeight(), bgPaint);

        // Draw header
        canvas.drawRect(0, 0, getWidth(), 36, headerPaint);
        textPaint.setTextSize(20f);
        textPaint.setColor(headerPaint.getColor() == 0xFF333333 ? 0xFFAAAAAA : 0xFF888888);
        canvas.drawText("Flame Chart — click a block to see details", 8, 24, textPaint);

        canvas.save();
        canvas.translate(offsetX, 0);
        canvas.scale(scale, 1);

        for (FlameNode n : nodes) {
            drawNode(canvas, n);
        }

        canvas.restore();
    }

    private void drawNode(Canvas canvas, FlameNode node) {
        rectPaint.setColor(COLORS[node.colorIndex]);
        canvas.drawRect(node.x, node.y, node.x + node.width, node.y + node.height, rectPaint);
        canvas.drawRect(node.x, node.y, node.x + node.width, node.y + node.height, borderPaint);

        if (node == selectedNode) {
            canvas.drawRect(node.x - 1, node.y - 1,
                    node.x + node.width + 1, node.y + node.height + 1, selectedPaint);
        }

        // Draw text label if block is wide enough
        if (node.width * scale > 60) {
            String label = node.profile.shortSignature();
            float textWidth = textPaint.measureText(label);
            if (textWidth < node.width - 4) {
                textPaint.setTextSize(20f);
                textPaint.setColor(0xFF000000);
                canvas.drawText(label, node.x + 3, node.y + node.height - 8, textPaint);
            }
        }
    }

    public void zoomIn() {
        scale = Math.min(5f, scale * 1.3f);
        invalidate();
    }

    public void zoomOut() {
        scale = Math.max(0.3f, scale / 1.3f);
        invalidate();
    }

    public void fitToScreen() {
        if (nodes.isEmpty()) {
            offsetX = 0;
            scale = 1f;
            invalidate();
            return;
        }

        float minX = Float.MAX_VALUE, maxX = Float.MIN_VALUE;
        for (FlameNode n : nodes) {
            minX = Math.min(minX, n.x);
            maxX = Math.max(maxX, n.x + n.width);
        }

        float graphW = maxX - minX;
        if (graphW > 0) {
            scale = getWidth() / graphW;
            scale = Math.max(0.3f, Math.min(5f, scale));
        }
        offsetX = -minX * scale;
        invalidate();
    }

    public static class FlameNode {
        public final ProfilerBridge.MethodProfile profile;
        public final float x, y, width, height;
        public int colorIndex;

        public FlameNode(ProfilerBridge.MethodProfile profile, float x, float y, float width, float height) {
            this.profile = profile;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        boolean handled = scaleDetector.onTouchEvent(event);
        handled |= gestureDetector.onTouchEvent(event);
        return handled || super.onTouchEvent(event);
    }
}
