package com.ccs.wiredroid.ui;

import com.ccs.wiredroid.uml.UmlGraph;
import com.ccs.wiredroid.uml.UmlLayout;
import com.ccs.wiredroid.util.AppTheme;
import com.ccs.wiredroid.util.Colors;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.io.File;

/**
 * Draws a class diagram: boxes with a badged header and a typed member list,
 * inheritance upward, associations sideways.
 *
 * <p>Everything is drawn in diagram coordinates and mapped to the screen by a
 * single pan/scale transform, so hit-testing only has to invert that transform
 * rather than track two coordinate systems.</p>
 */
public class UmlDiagramView extends View {

    /** Tapping a box opens its file. */
    public interface OnTypeTapped {
        void onTypeTapped(File file, String typeName);
    }

    // yFiles-like palette, kept literal rather than themed: a class diagram is
    // read as a document and stays legible when exported or screenshotted.
    private static final int BOX_FILL      = 0xFFFFFFFF;
    private static final int BOX_BORDER    = 0xFF9AA3AC;
    private static final int HEADER_FILL   = 0xFFE8F0DC;
    private static final int TEXT_MAIN     = 0xFF1A1A1A;
    private static final int TEXT_TYPE     = 0xFF5A6570;
    private static final int INHERIT_LINE  = 0xFF2A3F8F;
    private static final int REALIZE_LINE  = 0xFF2E7D32;
    private static final int ASSOC_LINE    = 0xFF37474F;
    private static final int BADGE_CLASS   = 0xFF4A86C8;
    private static final int BADGE_IFACE   = 0xFF43A047;
    private static final int BADGE_ABSTRACT= 0xFF8E5BB5;
    private static final int ICON_METHOD   = 0xFFD9534F;
    private static final int ICON_FIELD    = 0xFF8E5BB5;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bold = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tiny = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    private UmlGraph graph;
    private UmlLayout layout;
    private AppTheme theme;
    private OnTypeTapped tapListener;

    private float scale = 1f, panX = 0f, panY = 0f;
    private boolean framed;

    private final GestureDetector gestures;
    private final ScaleGestureDetector scaleGestures;

    public UmlDiagramView(Context context) {
        super(context);
        stroke.setStyle(Paint.Style.STROKE);
        fill.setStyle(Paint.Style.FILL);
        text.setTextSize(13f * density());
        bold.setTextSize(14f * density());
        bold.setFakeBoldText(true);
        tiny.setTextSize(10f * density());
        tiny.setColor(TEXT_TYPE);

        gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                panX -= dx; panY -= dy; invalidate();
                return true;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                UmlGraph.Type hit = typeAt(e.getX(), e.getY());
                if (hit != null && tapListener != null) tapListener.onTypeTapped(hit.file, hit.name);
                return true;
            }

            @Override public boolean onDoubleTap(MotionEvent e) { fit(); return true; }
        });
        scaleGestures = new ScaleGestureDetector(context,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(ScaleGestureDetector d) {
                        float factor = d.getScaleFactor();
                        float next = Math.max(0.2f, Math.min(4f, scale * factor));
                        // Zoom about the pinch centre, not the origin.
                        float fx = d.getFocusX(), fy = d.getFocusY();
                        panX = fx - (fx - panX) * (next / scale);
                        panY = fy - (fy - panY) * (next / scale);
                        scale = next;
                        invalidate();
                        return true;
                    }
                });
    }

    private float density() {
        return getResources().getDisplayMetrics().density;
    }

    public void setTheme(AppTheme t) {
        this.theme = t;
        invalidate();
    }

    public void setOnTypeTapped(OnTypeTapped l) {
        this.tapListener = l;
    }

    public void setGraph(UmlGraph g) {
        this.graph = g;
        this.layout = UmlLayout.of(g, (s, isBold) ->
                (isBold ? bold : text).measureText(s == null ? "" : s));
        framed = false;
        invalidate();
    }

    public UmlLayout layout() {
        return layout;
    }

    /** Scales the whole diagram into view with a margin. */
    public void fit() {
        if (layout == null || getWidth() == 0) return;
        float margin = 24f * density();
        float sx = (getWidth() - margin * 2) / Math.max(1f, layout.width);
        float sy = (getHeight() - margin * 2) / Math.max(1f, layout.height);
        // Floor at 0.12 rather than 0.2: a wide single-row diagram is still more
        // useful shrunk than cropped, and pinch can bring any part back up.
        scale = Math.max(0.12f, Math.min(1.6f, Math.min(sx, sy)));
        panX = (getWidth() - layout.width * scale) / 2f;
        panY = margin;
        framed = true;
        invalidate();
    }

    public void zoomIn() {
        zoomBy(1.25f);
    }

    public void zoomOut() {
        zoomBy(0.8f);
    }

    public void zoomBy(float factor) {
        if (getWidth() == 0 || getHeight() == 0) return;
        float next = Math.max(0.12f, Math.min(4f, scale * factor));
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        panX = cx - (cx - panX) * (next / scale);
        panY = cy - (cy - panY) * (next / scale);
        scale = next;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        // The first fit runs before the view has a size, so it has to be redone
        // once the real bounds arrive — otherwise the diagram keeps whatever
        // scale it guessed against a zero-width canvas.
        framed = false;
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleGestures.onTouchEvent(event);
        gestures.onTouchEvent(event);
        return true;
    }

    private UmlGraph.Type typeAt(float screenX, float screenY) {
        if (graph == null) return null;
        float x = (screenX - panX) / scale;
        float y = (screenY - panY) / scale;
        for (UmlGraph.Type t : graph.types()) {
            if (x >= t.x && x <= t.x + t.width && y >= t.y && y <= t.y + t.height) return t;
        }
        return null;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(theme != null ? theme.bg : 0xFFFAFAFA);
        if (graph == null || layout == null) return;
        if (!framed) fit();

        canvas.save();
        canvas.translate(panX, panY);
        canvas.scale(scale, scale);

        for (UmlLayout.Route r : layout.routes) drawEdge(canvas, r);
        for (UmlGraph.Type t : graph.types()) drawBox(canvas, t);

        canvas.restore();
    }

    // ── Edges ───────────────────────────────────────────────────────────────

    private void drawEdge(Canvas canvas, UmlLayout.Route route) {
        UmlGraph.Link link = route.relation.link;
        int colour = link == UmlGraph.Link.IMPLEMENTS ? REALIZE_LINE
                : link == UmlGraph.Link.EXTENDS ? INHERIT_LINE : ASSOC_LINE;
        stroke.setColor(colour);
        stroke.setStrokeWidth(1.6f);
        stroke.setPathEffect(link == UmlGraph.Link.IMPLEMENTS
                ? new DashPathEffect(new float[]{9f, 6f}, 0f) : null);

        path.reset();
        float[] first = route.points.get(0);
        path.moveTo(first[0], first[1]);
        for (int i = 1; i < route.points.size(); i++) {
            float[] p = route.points.get(i);
            path.lineTo(p[0], p[1]);
        }
        canvas.drawPath(path, stroke);
        stroke.setPathEffect(null);

        float[] end = route.points.get(route.points.size() - 1);
        float[] prev = route.points.get(route.points.size() - 2);
        if (link == UmlGraph.Link.ASSOCIATION) {
            float[] start = route.points.get(0);
            float[] afterStart = route.points.get(1);
            drawDiamond(canvas, start, afterStart, colour);
            drawMultiplicity(canvas, end, prev, route.relation.multiplicity);
        } else {
            drawTriangle(canvas, end, prev, colour);
        }
    }

    /** Hollow triangle at the parent end — the UML generalisation arrowhead. */
    private void drawTriangle(Canvas canvas, float[] tip, float[] from, int colour) {
        double a = Math.atan2(tip[1] - from[1], tip[0] - from[0]);
        float size = 11f;
        path.reset();
        path.moveTo(tip[0], tip[1]);
        path.lineTo(tip[0] - size * (float) Math.cos(a - 0.4), tip[1] - size * (float) Math.sin(a - 0.4));
        path.lineTo(tip[0] - size * (float) Math.cos(a + 0.4), tip[1] - size * (float) Math.sin(a + 0.4));
        path.close();
        fill.setColor(0xFFFFFFFF);
        canvas.drawPath(path, fill);
        stroke.setColor(colour);
        stroke.setStrokeWidth(1.6f);
        canvas.drawPath(path, stroke);
    }

    /** Filled diamond at the owning end — aggregation. */
    private void drawDiamond(Canvas canvas, float[] at, float[] towards, int colour) {
        double a = Math.atan2(towards[1] - at[1], towards[0] - at[0]);
        float len = 12f, half = 5.5f;
        float mx = at[0] + len * (float) Math.cos(a), my = at[1] + len * (float) Math.sin(a);
        float px = (float) Math.cos(a + Math.PI / 2) * half, py = (float) Math.sin(a + Math.PI / 2) * half;
        path.reset();
        path.moveTo(at[0], at[1]);
        path.lineTo(at[0] + (mx - at[0]) / 2 + px, at[1] + (my - at[1]) / 2 + py);
        path.lineTo(mx, my);
        path.lineTo(at[0] + (mx - at[0]) / 2 - px, at[1] + (my - at[1]) / 2 - py);
        path.close();
        fill.setColor(colour);
        canvas.drawPath(path, fill);
    }

    private void drawMultiplicity(Canvas canvas, float[] end, float[] from, String label) {
        if (label == null || label.isEmpty()) return;
        double a = Math.atan2(end[1] - from[1], end[0] - from[0]);
        float back = 16f, off = 10f;
        float x = end[0] - back * (float) Math.cos(a) - off * (float) Math.sin(a);
        float y = end[1] - back * (float) Math.sin(a) + off * (float) Math.cos(a);
        tiny.setColor(TEXT_TYPE);
        canvas.drawText(label, x, y, tiny);
    }

    // ── Boxes ───────────────────────────────────────────────────────────────

    private void drawBox(Canvas canvas, UmlGraph.Type t) {
        RectF box = new RectF(t.x, t.y, t.x + t.width, t.y + t.height);

        fill.setColor(BOX_FILL);
        canvas.drawRect(box, fill);

        RectF header = new RectF(t.x, t.y, t.x + t.width, t.y + UmlLayout.HEADER_H);
        fill.setColor(HEADER_FILL);
        canvas.drawRect(header, fill);

        stroke.setColor(BOX_BORDER);
        stroke.setStrokeWidth(1.2f);
        canvas.drawRect(box, stroke);
        canvas.drawLine(t.x, t.y + UmlLayout.HEADER_H, t.x + t.width, t.y + UmlLayout.HEADER_H, stroke);

        // Header: round badge then the type name.
        int badgeColour = t.kind == UmlGraph.Kind.INTERFACE ? BADGE_IFACE
                : t.kind == UmlGraph.Kind.ABSTRACT ? BADGE_ABSTRACT : BADGE_CLASS;
        float cx = t.x + 16f, cy = t.y + UmlLayout.HEADER_H / 2f;
        fill.setColor(badgeColour);
        canvas.drawCircle(cx, cy, 8f, fill);
        tiny.setColor(0xFFFFFFFF);
        String badge = UmlGraph.badge(t.kind);
        canvas.drawText(badge, cx - tiny.measureText(badge) / 2f, cy + 3.5f, tiny);

        bold.setColor(TEXT_MAIN);
        canvas.drawText(t.name, t.x + 30f, cy + 5f, bold);

        // Members: icon, name, then the type flush right.
        float y = t.y + UmlLayout.HEADER_H + UmlLayout.PAD_V + UmlLayout.ROW_H * 0.68f;
        for (UmlGraph.Member m : t.members) {
            fill.setColor(m.method ? ICON_METHOD : ICON_FIELD);
            canvas.drawCircle(t.x + 14f, y - 4f, 6f, fill);
            tiny.setColor(0xFFFFFFFF);
            String icon = m.method ? "m" : "p";
            canvas.drawText(icon, t.x + 14f - tiny.measureText(icon) / 2f, y - 1f, tiny);

            text.setColor(TEXT_MAIN);
            canvas.drawText(m.name, t.x + 26f, y, text);

            text.setColor(TEXT_TYPE);
            float tw = text.measureText(m.type);
            canvas.drawText(m.type, t.x + t.width - 10f - tw, y, text);

            y += UmlLayout.ROW_H;
        }
    }

    /** Kept so a themed background can still tint the empty canvas. */
    int surface() {
        return theme != null ? Colors.blend(theme.bg, Color.WHITE, 0.5f) : Color.WHITE;
    }
}
