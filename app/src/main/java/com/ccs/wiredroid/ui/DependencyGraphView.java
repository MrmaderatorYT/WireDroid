package com.ccs.wiredroid.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import com.ccs.wiredroid.tools.bytecode.DependencyModel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

public class DependencyGraphView extends View {

    private static final int MAX_VISIBLE_NODES = 600;
    private static final int MAX_VISIBLE_EDGES = 2400;
    private static final DashPathEffect DASH_IMPLEMENTS = new DashPathEffect(new float[]{10, 6}, 0);
    private static final DashPathEffect DASH_LEGEND = new DashPathEffect(new float[]{8, 4}, 0);

    private final Object dataLock = new Object();
    private final List<GraphNode> nodes = new ArrayList<>();
    private final List<GraphEdge> edges = new ArrayList<>();
    private final Map<String, GraphNode> nodeMap = new HashMap<>();
    private final Set<GraphNode> connectedToSelected = new HashSet<>();

    private final ExecutorService layoutExecutor = Executors.newSingleThreadExecutor();
    private final AtomicInteger layoutGeneration = new AtomicInteger();
    private Future<?> layoutTask;

    private DependencyModel model;
    private String filterPackage = null;

    private final Paint nodePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint nodeStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edgeArrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bgPaint = new Paint();
    private final Paint selectedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cardBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cardTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Path arrowPath = new Path();
    private final RectF labelRect = new RectF();
    private final RectF cardRect = new RectF();

    private float offsetX = 0;
    private float offsetY = 0;
    private float scale = 1f;

    private GraphNode selectedNode = null;
    private GraphNode draggedNode = null;
    private boolean isDraggingNode = false;

    private int nodeColorDefault = 0xFF3574F0;
    private int nodeColorSelected = 0xFFFFA500;
    private int nodeColorPackage = 0xFF4CAF50;
    private int edgeColor = 0xFF666666;
    private int textColor = 0xFFEEEEEE;
    private int bgColor = 0xFF1E1E1E;

    private ScaleGestureDetector scaleDetector;
    private GestureDetector gestureDetector;

    public DependencyGraphView(Context context) {
        super(context);
        init();
    }

    public DependencyGraphView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public DependencyGraphView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setWillNotDraw(false);

        nodePaint.setStyle(Paint.Style.FILL);
        nodePaint.setColor(nodeColorDefault);

        nodeStrokePaint.setStyle(Paint.Style.STROKE);
        nodeStrokePaint.setStrokeWidth(2f);
        nodeStrokePaint.setColor(0xFF555555);

        textPaint.setColor(textColor);
        textPaint.setTextSize(20f);
        textPaint.setTextAlign(Paint.Align.CENTER);

        labelBgPaint.setColor(0xCC1A1A1A);
        labelBgPaint.setStyle(Paint.Style.FILL);

        cardTextPaint.setColor(textColor);
        cardTextPaint.setTextSize(20f);
        cardTextPaint.setTextAlign(Paint.Align.LEFT);

        cardBgPaint.setColor(0xEE2A2A2A);
        cardBgPaint.setStyle(Paint.Style.FILL);

        edgePaint.setStyle(Paint.Style.STROKE);
        edgePaint.setStrokeWidth(1.8f);
        edgePaint.setColor(edgeColor);

        edgeArrowPaint.setStyle(Paint.Style.FILL);
        edgeArrowPaint.setColor(edgeColor);

        selectedPaint.setStyle(Paint.Style.STROKE);
        selectedPaint.setStrokeWidth(4.5f);
        selectedPaint.setColor(nodeColorSelected);

        bgPaint.setColor(bgColor);

        scaleDetector = new ScaleGestureDetector(getContext(), new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                float newScale = scale * detector.getScaleFactor();
                newScale = Math.max(0.12f, Math.min(3.5f, newScale));
                float focusX = detector.getFocusX();
                float focusY = detector.getFocusY();
                offsetX -= (focusX - offsetX) * (newScale / scale - 1);
                offsetY -= (focusY - offsetY) * (newScale / scale - 1);
                scale = newScale;
                invalidate();
                return true;
            }
        });

        gestureDetector = new GestureDetector(getContext(), new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
                if (!isDraggingNode) {
                    offsetX -= distanceX;
                    offsetY -= distanceY;
                    invalidate();
                    return true;
                }
                return false;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                float x = (e.getX() - offsetX) / scale;
                float y = (e.getY() - offsetY) / scale;
                synchronized (dataLock) {
                    selectedNode = findNodeAt(x, y);
                    updateConnectedSet();
                }
                invalidate();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                synchronized (dataLock) {
                    if (selectedNode != null) {
                        offsetX = getWidth() / 2f - selectedNode.x * scale;
                        offsetY = getHeight() / 2f - selectedNode.y * scale;
                        scale = Math.max(scale, 1.0f);
                        invalidate();
                    }
                }
                return true;
            }
        });
    }

    private void updateConnectedSet() {
        connectedToSelected.clear();
        if (selectedNode == null) return;
        connectedToSelected.add(selectedNode);
        for (GraphEdge ge : edges) {
            if (ge.from == selectedNode) {
                connectedToSelected.add(ge.to);
            } else if (ge.to == selectedNode) {
                connectedToSelected.add(ge.from);
            }
        }
    }

    public void setModel(DependencyModel model) {
        this.model = model;
        triggerAsyncLayout();
    }

    public void setFilterPackage(String packageName) {
        this.filterPackage = packageName;
        triggerAsyncLayout();
    }

    public void setColors(int nodeColor, int selectedColor, int pkgColor, int edge, int text, int bg) {
        this.nodeColorDefault = nodeColor;
        this.nodeColorSelected = selectedColor;
        this.nodeColorPackage = pkgColor;
        this.edgeColor = edge;
        this.textColor = text;
        this.bgColor = bg;

        nodePaint.setColor(nodeColor);
        selectedPaint.setColor(selectedColor);
        edgePaint.setColor(edge);
        edgeArrowPaint.setColor(edge);
        textPaint.setColor(text);
        cardTextPaint.setColor(text);
        cardBgPaint.setColor(bg == 0xFFFFFFFF || bg == 0xFFF5F5F5 ? 0xEEEDEDED : 0xEE2A2A2A);
        labelBgPaint.setColor(bg == 0xFFFFFFFF || bg == 0xFFF5F5F5 ? 0xDDFFFFFF : 0xDD1E1E1E);
        bgPaint.setColor(bg);
        invalidate();
    }

    public GraphNode getSelectedNode() {
        synchronized (dataLock) {
            return selectedNode;
        }
    }

    private void triggerAsyncLayout() {
        final DependencyModel m = this.model;
        final String pkg = this.filterPackage;
        final int generation = layoutGeneration.incrementAndGet();
        if (layoutTask != null) layoutTask.cancel(true);
        if (m == null) {
            clearGraph();
            return;
        }

        layoutTask = layoutExecutor.submit(() -> buildGraphAndLayout(m, pkg, generation));
    }

    private void buildGraphAndLayout(DependencyModel m, String filterPkg, int generation) {
        List<DependencyModel.ClassNode> classes = new ArrayList<>();
        for (DependencyModel.ClassNode cn : m.getProjectClasses()) {
            if (filterPkg == null || filterPkg.isEmpty() || cn.packageName.startsWith(filterPkg)) {
                classes.add(cn);
            }
        }
        if (classes.isEmpty()) {
            postClearGraph(generation);
            return;
        }

        Map<String, Integer> inDegrees = new HashMap<>(classes.size() * 2);
        Map<String, Integer> outDegrees = new HashMap<>(classes.size() * 2);
        for (DependencyModel.ClassNode cn : classes) {
            inDegrees.put(cn.name, 0);
            outDegrees.put(cn.name, 0);
        }
        List<DependencyModel.DependencyEdge> modelEdges = m.getEdges();
        for (DependencyModel.DependencyEdge edge : modelEdges) {
            if (outDegrees.containsKey(edge.from) && inDegrees.containsKey(edge.to)) {
                outDegrees.put(edge.from, outDegrees.get(edge.from) + 1);
                inDegrees.put(edge.to, inDegrees.get(edge.to) + 1);
            }
        }
        classes.sort(Comparator
                .comparingInt((DependencyModel.ClassNode cn) ->
                        inDegrees.get(cn.name) + outDegrees.get(cn.name))
                .reversed()
                .thenComparing(cn -> cn.name));
        if (classes.size() > MAX_VISIBLE_NODES) {
            classes = new ArrayList<>(classes.subList(0, MAX_VISIBLE_NODES));
        }

        Map<String, List<DependencyModel.ClassNode>> packageMap = new LinkedHashMap<>();
        for (DependencyModel.ClassNode cn : classes) {
            packageMap.computeIfAbsent(cn.packageName, k -> new ArrayList<>()).add(cn);
        }

        List<GraphNode> builtNodes = new ArrayList<>();
        Map<String, GraphNode> builtMap = new HashMap<>();
        Paint measurePaint = new Paint(textPaint);

        int numPackages = packageMap.size();
        int largestPackage = 1;
        for (List<DependencyModel.ClassNode> packageClasses : packageMap.values()) {
            largestPackage = Math.max(largestPackage, packageClasses.size());
        }
        int packageColumns = (int) Math.ceil(Math.sqrt(numPackages));
        float packageSpacing = 2f * (120f * (float) Math.sqrt(largestPackage) + 180f);
        int pkgIndex = 0;

        for (Map.Entry<String, List<DependencyModel.ClassNode>> entry : packageMap.entrySet()) {
            List<DependencyModel.ClassNode> pkgClasses = entry.getValue();
            int packageColumn = pkgIndex % packageColumns;
            int packageRow = pkgIndex / packageColumns;
            float pkgCenterX = (packageColumn - (packageColumns - 1) / 2f) * packageSpacing;
            float pkgCenterY = (packageRow - (numPackages / (float) packageColumns - 1f) / 2f)
                    * packageSpacing;

            int classIndex = 0;
            for (DependencyModel.ClassNode cn : pkgClasses) {
                String displayName = abbreviate(cn.simpleName, 20);
                float textWidth = measurePaint.measureText(displayName);
                int inDeg = inDegrees.get(cn.name);
                int outDeg = outDegrees.get(cn.name);

                float visualRadius = 18f + Math.min(18f, (float) Math.sqrt(inDeg + outDeg) * 4f);
                float collisionRadius = Math.max(visualRadius + 22f, (textWidth + 28f) / 2f);

                float spiralR = 120f * (float) Math.sqrt(classIndex) + 60f;
                float spiralAngle = classIndex * 2.4f;
                float initX = pkgCenterX + spiralR * (float) Math.cos(spiralAngle);
                float initY = pkgCenterY + spiralR * (float) Math.sin(spiralAngle);

                GraphNode node = new GraphNode(cn.name, cn.simpleName, cn.packageName,
                        displayName, initX, initY, visualRadius, collisionRadius, textWidth);
                node.layoutIndex = builtNodes.size();
                node.packageIndex = pkgIndex;
                node.pkgCenterX = pkgCenterX;
                node.pkgCenterY = pkgCenterY;
                node.inDegree = inDeg;
                node.outDegree = outDeg;
                node.isRoot = (inDeg == 0 && outDeg > 0);

                builtNodes.add(node);
                builtMap.put(cn.name, node);
                classIndex++;
            }
            pkgIndex++;
        }

        List<GraphEdge> builtEdges = buildVisibleEdges(modelEdges, builtMap);

        int iterations = builtNodes.size() <= 150 ? 80 : builtNodes.size() <= 350 ? 45 : 24;
        runObsidianPhysicsSimulation(builtNodes, builtEdges, null, iterations);
        if (Thread.currentThread().isInterrupted() || generation != layoutGeneration.get()) return;

        post(() -> {
            if (generation != layoutGeneration.get()) return;
            synchronized (dataLock) {
                this.nodes.clear();
                this.nodes.addAll(builtNodes);
                this.edges.clear();
                this.edges.addAll(builtEdges);
                this.nodeMap.clear();
                this.nodeMap.putAll(builtMap);
                this.selectedNode = null;
                this.draggedNode = null;
                this.connectedToSelected.clear();
            }
            fitToScreen();
            invalidate();
        });
    }

    private List<GraphEdge> buildVisibleEdges(List<DependencyModel.DependencyEdge> modelEdges,
                                              Map<String, GraphNode> builtMap) {
        List<GraphEdge> result = new ArrayList<>(Math.min(MAX_VISIBLE_EDGES, modelEdges.size()));
        appendVisibleEdges(modelEdges, builtMap, result, true);
        appendVisibleEdges(modelEdges, builtMap, result, false);
        return result;
    }

    private void appendVisibleEdges(List<DependencyModel.DependencyEdge> modelEdges,
                                    Map<String, GraphNode> builtMap, List<GraphEdge> result,
                                    boolean hierarchyOnly) {
        if (result.size() >= MAX_VISIBLE_EDGES) return;
        for (DependencyModel.DependencyEdge edge : modelEdges) {
            boolean hierarchy = edge.type == DependencyModel.DependencyType.EXTENDS
                    || edge.type == DependencyModel.DependencyType.IMPLEMENTS;
            if (hierarchy != hierarchyOnly) continue;
            GraphNode from = builtMap.get(edge.from);
            GraphNode to = builtMap.get(edge.to);
            if (from != null && to != null && from != to) {
                result.add(new GraphEdge(from, to, edge.type));
                if (result.size() >= MAX_VISIBLE_EDGES) return;
            }
        }
    }

    private void postClearGraph(int generation) {
        post(() -> {
            if (generation == layoutGeneration.get()) clearGraph();
        });
    }

    private void clearGraph() {
        synchronized (dataLock) {
            nodes.clear();
            edges.clear();
            nodeMap.clear();
            selectedNode = null;
            draggedNode = null;
            connectedToSelected.clear();
        }
        invalidate();
    }

    private static String abbreviate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) return value;
        return value.substring(0, maxLength - 2) + "..";
    }

    private void runObsidianPhysicsSimulation(List<GraphNode> nList, List<GraphEdge> eList,
                                              GraphNode pinnedNode, int iterations) {
        int nCount = nList.size();
        if (nCount <= 1) return;

        float cellSize = 320f;
        Map<Long, List<GraphNode>> grid = new HashMap<>(nCount * 2);
        float collisionPadding = 26f;

        for (int iter = 0; iter < iterations; iter++) {
            if (Thread.currentThread().isInterrupted()) return;
            float progress = (float) iter / (float) iterations;
            float damping = Math.max(0.15f, 1.0f - progress * 0.85f);

            grid.clear();
            for (GraphNode n : nList) {
                long key = gridKey(n.x, n.y, cellSize);
                grid.computeIfAbsent(key, k -> new ArrayList<>(8)).add(n);
            }

            for (int i = 0; i < nCount; i++) {
                GraphNode a = nList.get(i);

                int cx = (int) Math.floor(a.x / cellSize);
                int cy = (int) Math.floor(a.y / cellSize);

                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        long k = (((long) (cx + dx)) << 32) | (((long) (cy + dy)) & 0xFFFFFFFFL);
                        List<GraphNode> cellNodes = grid.get(k);
                        if (cellNodes == null) continue;

                        for (GraphNode b : cellNodes) {
                            if (b.layoutIndex <= a.layoutIndex) continue;
                            float diffX = b.x - a.x;
                            float diffY = b.y - a.y;
                            float distSq = diffX * diffX + diffY * diffY;
                            if (distSq < cellSize * cellSize) {
                                float dist = (float) Math.sqrt(distSq);
                                if (dist < 1f) { dist = 1f; diffX = 1f; diffY = 0f; }
                                float rep = (18000f / dist) * 0.035f * damping;
                                float nx = diffX / dist;
                                float ny = diffY / dist;
                                if (a != pinnedNode) {
                                    a.x -= nx * rep;
                                    a.y -= ny * rep;
                                }
                                if (b != pinnedNode) {
                                    b.x += nx * rep;
                                    b.y += ny * rep;
                                }
                            }
                        }
                    }
                }

                a.x -= a.x * 0.0015f;
                a.y -= a.y * 0.0015f;

                a.x += (a.pkgCenterX - a.x) * 0.004f;
                a.y += (a.pkgCenterY - a.y) * 0.004f;
            }

            for (GraphEdge ge : eList) {
                GraphNode a = ge.from;
                GraphNode b = ge.to;
                float diffX = b.x - a.x;
                float diffY = b.y - a.y;
                float dist = (float) Math.sqrt(diffX * diffX + diffY * diffY);
                if (dist < 1f) continue;

                float targetDist = a.collisionRadius + b.collisionRadius + 80f;
                if (dist > targetDist) {
                    float pull = Math.min((dist - targetDist) * 0.035f, 25f) * damping;
                    float nx = diffX / dist;
                    float ny = diffY / dist;

                    if (a != pinnedNode) {
                        a.x += nx * pull;
                        a.y += ny * pull;
                    }
                    if (b != pinnedNode) {
                        b.x -= nx * pull;
                        b.y -= ny * pull;
                    }
                }
            }

            int relaxPasses = (iter == iterations - 1) ? 4 : 1;
            for (int pass = 0; pass < relaxPasses; pass++) {
                grid.clear();
                for (GraphNode n : nList) {
                    long key = gridKey(n.x, n.y, cellSize);
                    grid.computeIfAbsent(key, k -> new ArrayList<>(8)).add(n);
                }

                for (int i = 0; i < nCount; i++) {
                    GraphNode a = nList.get(i);
                    int cx = (int) Math.floor(a.x / cellSize);
                    int cy = (int) Math.floor(a.y / cellSize);

                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dy = -1; dy <= 1; dy++) {
                            long k = (((long) (cx + dx)) << 32) | (((long) (cy + dy)) & 0xFFFFFFFFL);
                            List<GraphNode> cellNodes = grid.get(k);
                            if (cellNodes == null) continue;

                            for (GraphNode b : cellNodes) {
                                if (b.layoutIndex <= a.layoutIndex) continue;
                                float diffX = b.x - a.x;
                                float diffY = b.y - a.y;
                                float distSq = diffX * diffX + diffY * diffY;
                                float minDist = a.collisionRadius + b.collisionRadius + collisionPadding;
                                float minDistSq = minDist * minDist;

                                if (distSq < minDistSq) {
                                    float dist = (float) Math.sqrt(distSq);
                                    if (dist < 0.001f) {
                                        diffX = (float) Math.random() - 0.5f;
                                        diffY = (float) Math.random() - 0.5f;
                                        dist = 0.001f;
                                    }
                                    float overlap = (minDist - dist);
                                    float nx = diffX / dist;
                                    float ny = diffY / dist;

                                    if (a == pinnedNode) {
                                        b.x += nx * overlap;
                                        b.y += ny * overlap;
                                    } else if (b == pinnedNode) {
                                        a.x -= nx * overlap;
                                        a.y -= ny * overlap;
                                    } else {
                                        a.x -= nx * overlap * 0.5f;
                                        a.y -= ny * overlap * 0.5f;
                                        b.x += nx * overlap * 0.5f;
                                        b.y += ny * overlap * 0.5f;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private static long gridKey(float x, float y, float cellSize) {
        int cx = (int) Math.floor(x / cellSize);
        int cy = (int) Math.floor(y / cellSize);
        return (((long) cx) << 32) | (((long) cy) & 0xFFFFFFFFL);
    }

    private GraphNode findNodeAt(float x, float y) {
        for (int i = nodes.size() - 1; i >= 0; i--) {
            GraphNode n = nodes.get(i);
            float dx = x - n.x;
            float dy = y - n.y;
            if (dx * dx + dy * dy <= (n.collisionRadius + 8f) * (n.collisionRadius + 8f)) {
                return n;
            }
        }
        return null;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        boolean scaleHandled = scaleDetector.onTouchEvent(event);
        if (scaleDetector.isInProgress()) {
            isDraggingNode = false;
            draggedNode = null;
            return true;
        }

        float graphX = (event.getX() - offsetX) / scale;
        float graphY = (event.getY() - offsetY) / scale;

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                synchronized (dataLock) {
                    GraphNode hit = findNodeAt(graphX, graphY);
                    if (hit != null) {
                        draggedNode = hit;
                        isDraggingNode = true;
                        selectedNode = hit;
                        updateConnectedSet();
                        invalidate();
                        return true;
                    }
                }
                isDraggingNode = false;
                draggedNode = null;
                break;

            case MotionEvent.ACTION_MOVE:
                if (isDraggingNode && draggedNode != null) {
                    synchronized (dataLock) {
                        draggedNode.x = graphX;
                        draggedNode.y = graphY;
                    }
                    invalidate();
                    return true;
                }
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                isDraggingNode = false;
                draggedNode = null;
                break;
        }

        boolean gestureHandled = gestureDetector.onTouchEvent(event);
        return scaleHandled || gestureHandled || super.onTouchEvent(event);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawRect(0, 0, getWidth(), getHeight(), bgPaint);

        synchronized (dataLock) {
            if (nodes.isEmpty()) return;

            canvas.save();
            canvas.translate(offsetX, offsetY);
            canvas.scale(scale, scale);

            float viewL = -offsetX / scale - 140f;
            float viewT = -offsetY / scale - 140f;
            float viewR = (getWidth() - offsetX) / scale + 140f;
            float viewB = (getHeight() - offsetY) / scale + 140f;

            boolean hasSelection = selectedNode != null;

            for (GraphEdge ge : edges) {
                GraphNode from = ge.from;
                GraphNode to = ge.to;
                float minX = Math.min(from.x, to.x);
                float maxX = Math.max(from.x, to.x);
                float minY = Math.min(from.y, to.y);
                float maxY = Math.max(from.y, to.y);

                if (maxX < viewL || minX > viewR || maxY < viewT || minY > viewB) {
                    continue;
                }

                drawEdge(canvas, ge, hasSelection);
            }

            for (GraphNode n : nodes) {
                if (n.x + n.collisionRadius < viewL || n.x - n.collisionRadius > viewR
                        || n.y + n.collisionRadius < viewT || n.y - n.collisionRadius > viewB) {
                    continue;
                }

                drawNode(canvas, n, hasSelection);
            }

            canvas.restore();

            drawLegend(canvas);
            drawSelectedNodeCard(canvas);
        }
    }

    private void drawNode(Canvas canvas, GraphNode node, boolean hasSelection) {
        boolean isSelected = (node == selectedNode);
        boolean isHighlighted = isSelected || connectedToSelected.contains(node);

        int alpha = (hasSelection && !isHighlighted) ? 40 : 255;
        nodePaint.setAlpha(alpha);
        nodeStrokePaint.setAlpha(alpha);
        textPaint.setAlpha(alpha);
        labelBgPaint.setAlpha((hasSelection && !isHighlighted) ? 30 : 220);

        if (isSelected) {
            canvas.drawCircle(node.x, node.y, node.visualRadius + 7f, selectedPaint);
        }

        if (node.isRoot) {
            nodePaint.setColor(nodeColorPackage);
        } else {
            nodePaint.setColor(nodeColorDefault);
        }
        nodePaint.setAlpha(alpha);

        canvas.drawCircle(node.x, node.y, node.visualRadius, nodePaint);
        canvas.drawCircle(node.x, node.y, node.visualRadius, nodeStrokePaint);

        if (scale >= 0.28f) {
            float labelY = node.y + node.visualRadius + 18f;
            float textHalfW = (node.textWidth > 0 ? node.textWidth : 80f) / 2f + 8f;
            labelRect.set(node.x - textHalfW, labelY - 14f, node.x + textHalfW, labelY + 6f);

            canvas.drawRoundRect(labelRect, 6f, 6f, labelBgPaint);
            canvas.drawText(node.displayName, node.x, labelY, textPaint);
        }
    }

    private void drawEdge(Canvas canvas, GraphEdge edge, boolean hasSelection) {
        boolean isConnected = (selectedNode != null) && (edge.from == selectedNode || edge.to == selectedNode);
        int alpha = (!hasSelection) ? 180 : (isConnected ? 255 : 25);
        float strokeW = isConnected ? 3.5f : 1.8f;

        float dx = edge.to.x - edge.from.x;
        float dy = edge.to.y - edge.from.y;
        float dist = (float) Math.sqrt(dx * dx + dy * dy);
        if (dist < 1f) return;

        float nx = dx / dist;
        float ny = dy / dist;

        float startX = edge.from.x + nx * edge.from.visualRadius;
        float startY = edge.from.y + ny * edge.from.visualRadius;
        float endX = edge.to.x - nx * edge.to.visualRadius;
        float endY = edge.to.y - ny * edge.to.visualRadius;

        switch (edge.type) {
            case EXTENDS:
                edgePaint.setColor(0xFF569CD6);
                edgePaint.setStrokeWidth(strokeW * 1.3f);
                edgePaint.setPathEffect(null);
                break;
            case IMPLEMENTS:
                edgePaint.setColor(0xFF6A9955);
                edgePaint.setStrokeWidth(strokeW);
                edgePaint.setPathEffect(DASH_IMPLEMENTS);
                break;
            case USES:
            default:
                edgePaint.setColor(0xFF888888);
                edgePaint.setStrokeWidth(strokeW);
                edgePaint.setPathEffect(null);
                break;
        }

        edgePaint.setAlpha(alpha);
        edgeArrowPaint.setColor(edgePaint.getColor());
        edgeArrowPaint.setAlpha(alpha);

        canvas.drawLine(startX, startY, endX, endY, edgePaint);
        edgePaint.setPathEffect(null);

        if (scale >= 0.32f) {
            float arrowLen = 13f;
            float arrowAngle = 0.42f;
            float angle = (float) Math.atan2(ny, nx);

            arrowPath.reset();
            arrowPath.moveTo(endX, endY);
            arrowPath.lineTo(
                    endX - arrowLen * (float) Math.cos(angle - arrowAngle),
                    endY - arrowLen * (float) Math.sin(angle - arrowAngle)
            );
            arrowPath.lineTo(
                    endX - arrowLen * (float) Math.cos(angle + arrowAngle),
                    endY - arrowLen * (float) Math.sin(angle + arrowAngle)
            );
            arrowPath.close();
            canvas.drawPath(arrowPath, edgeArrowPaint);
        }
    }

    private void drawSelectedNodeCard(Canvas canvas) {
        if (selectedNode == null) return;

        float cardW = Math.min(getWidth() - 32f, 320f);
        float cardH = 74f;
        float cardX = 16f;
        float cardY = 16f;

        cardRect.set(cardX, cardY, cardX + cardW, cardY + cardH);
        canvas.drawRoundRect(cardRect, 12f, 12f, cardBgPaint);

        cardTextPaint.setTextSize(20f);
        cardTextPaint.setFakeBoldText(true);
        canvas.drawText(selectedNode.displayName, cardX + 14f, cardY + 26f, cardTextPaint);

        cardTextPaint.setTextSize(15f);
        cardTextPaint.setFakeBoldText(false);
        String pkg = selectedNode.packageName.isEmpty() ? "(default package)" : selectedNode.packageName;
        if (pkg.length() > 32) pkg = pkg.substring(0, 30) + "..";
        canvas.drawText("pkg: " + pkg, cardX + 14f, cardY + 46f, cardTextPaint);

        String stats = "Depends on: " + selectedNode.outDegree + "  |  Used by: " + selectedNode.inDegree;
        canvas.drawText(stats, cardX + 14f, cardY + 64f, cardTextPaint);
    }

    private void drawLegend(Canvas canvas) {
        float x = 16;
        float y = getHeight() - 76;

        cardTextPaint.setTextSize(18f);
        cardTextPaint.setFakeBoldText(false);
        cardTextPaint.setTextAlign(Paint.Align.LEFT);

        nodePaint.setColor(nodeColorDefault);
        nodePaint.setAlpha(255);
        canvas.drawCircle(x + 8, y + 8, 8, nodePaint);
        canvas.drawText("Class", x + 24, y + 14, cardTextPaint);

        y += 22;
        edgePaint.setColor(0xFF569CD6);
        edgePaint.setStrokeWidth(3f);
        edgePaint.setAlpha(255);
        canvas.drawLine(x, y + 4, x + 20, y + 4, edgePaint);
        canvas.drawText("Extends", x + 28, y + 10, cardTextPaint);

        y += 22;
        edgePaint.setColor(0xFF6A9955);
        edgePaint.setStrokeWidth(2f);
        edgePaint.setPathEffect(DASH_LEGEND);
        edgePaint.setAlpha(255);
        canvas.drawLine(x, y + 4, x + 20, y + 4, edgePaint);
        edgePaint.setPathEffect(null);
        canvas.drawText("Implements", x + 28, y + 10, cardTextPaint);
    }

    public void zoomIn() {
        zoomBy(1.3f);
    }

    public void zoomOut() {
        zoomBy(1f / 1.3f);
    }

    public void zoomBy(float factor) {
        float newScale = Math.max(0.12f, Math.min(3.5f, scale * factor));
        if (newScale == scale) return;
        float focusX = getWidth() / 2f;
        float focusY = getHeight() / 2f;
        offsetX -= (focusX - offsetX) * (newScale / scale - 1);
        offsetY -= (focusY - offsetY) * (newScale / scale - 1);
        scale = newScale;
        invalidate();
    }

    public void fitToScreen() {
        synchronized (dataLock) {
            if (nodes.isEmpty()) return;

            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
            for (GraphNode n : nodes) {
                minX = Math.min(minX, n.x - n.collisionRadius);
                minY = Math.min(minY, n.y - n.collisionRadius);
                maxX = Math.max(maxX, n.x + n.collisionRadius);
                maxY = Math.max(maxY, n.y + n.collisionRadius);
            }

            float graphW = Math.max(100f, maxX - minX + 160f);
            float graphH = Math.max(100f, maxY - minY + 160f);
            float viewW = getWidth() > 0 ? getWidth() : 800f;
            float viewH = getHeight() > 0 ? getHeight() : 600f;

            float scaleX = viewW / graphW;
            float scaleY = viewH / graphH;
            scale = Math.min(scaleX, scaleY) * 0.88f;
            scale = Math.max(0.12f, Math.min(3f, scale));

            offsetX = (viewW - (minX + maxX) * scale) / 2f;
            offsetY = (viewH - (minY + maxY) * scale) / 2f;
            invalidate();
        }
    }

    public static class GraphNode {
        public final String fullName;
        public final String simpleName;
        public final String packageName;
        public final String displayName;
        public float x, y;
        public float visualRadius;
        public float collisionRadius;
        public float textWidth;
        public boolean isRoot;
        public int packageIndex;
        public float pkgCenterX, pkgCenterY;
        public int inDegree;
        public int outDegree;
        public int layoutIndex;

        public GraphNode(String fullName, String simpleName, String packageName,
                         String displayName, float x, float y, float visualRadius,
                         float collisionRadius, float textWidth) {
            this.fullName = fullName;
            this.simpleName = simpleName;
            this.packageName = packageName;
            this.displayName = displayName;
            this.x = x;
            this.y = y;
            this.visualRadius = visualRadius;
            this.collisionRadius = collisionRadius;
            this.textWidth = textWidth;
        }
    }

    public static class GraphEdge {
        public final GraphNode from;
        public final GraphNode to;
        public final DependencyModel.DependencyType type;

        public GraphEdge(GraphNode from, GraphNode to, DependencyModel.DependencyType type) {
            this.from = from;
            this.to = to;
            this.type = type;
        }
    }
}
