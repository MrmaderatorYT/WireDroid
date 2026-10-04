package com.ccs.wiredroid.uml;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Places the boxes and routes the edges.
 *
 * <p>Layered, the way a class diagram is normally read: a type sits one row
 * below whatever it extends, so inheritance always points upward and the
 * hierarchy can be followed without tracing lines. Association edges are ignored
 * when assigning rows — letting a field drag its owner down a level is what turns
 * these diagrams into spaghetti.</p>
 */
public final class UmlLayout {

    public static final float H_GAP = 44f;
    public static final float V_GAP = 96f;
    public static final float MIN_WIDTH = 190f;
    public static final float HEADER_H = 34f;
    public static final float ROW_H = 26f;
    public static final float PAD_V = 8f;

    /** One drawn edge, already reduced to points in diagram space. */
    public static final class Route {
        public final UmlGraph.Relation relation;
        public final List<float[]> points = new ArrayList<>();

        Route(UmlGraph.Relation relation) {
            this.relation = relation;
        }
    }

    public final List<Route> routes = new ArrayList<>();
    public float width, height;

    private UmlLayout() {
    }

    public static UmlLayout of(UmlGraph graph, MeasureText measure) {
        UmlLayout l = new UmlLayout();
        l.size(graph, measure);
        l.assignLayers(graph);
        l.place(graph);
        l.route(graph);
        return l;
    }

    /** Supplied by the view so box widths match the paint that will draw them. */
    public interface MeasureText {
        float width(String text, boolean bold);
    }

    // ── Sizing ──────────────────────────────────────────────────────────────

    private void size(UmlGraph graph, MeasureText measure) {
        for (UmlGraph.Type t : graph.types()) {
            float w = measure.width(t.name, true) + 56f;   // badge + padding
            for (UmlGraph.Member m : t.members) {
                // Name on the left, type on the right, never touching.
                w = Math.max(w, measure.width(m.name, false)
                        + measure.width(m.type, false) + 64f);
            }
            t.width = Math.max(MIN_WIDTH, w);
            t.height = HEADER_H + (t.members.isEmpty() ? 6f
                    : PAD_V * 2 + t.members.size() * ROW_H);
        }
    }

    // ── Layering ────────────────────────────────────────────────────────────

    private void assignLayers(UmlGraph graph) {
        Map<UmlGraph.Type, List<UmlGraph.Type>> parents = new HashMap<>();
        for (UmlGraph.Relation r : graph.relations()) {
            if (r.link == UmlGraph.Link.ASSOCIATION) continue;
            parents.computeIfAbsent(r.from, k -> new ArrayList<>()).add(r.to);
        }
        for (UmlGraph.Type t : graph.types()) {
            t.layer = depth(t, parents, new HashSet<>());
        }
    }

    /**
     * One deeper than the deepest supertype.
     *
     * @param visiting guards against a cycle in the parsed hierarchy — impossible
     *                 in valid Java, but the parser resolves by simple name, so
     *                 two unrelated classes sharing a name can close a loop
     */
    private int depth(UmlGraph.Type t, Map<UmlGraph.Type, List<UmlGraph.Type>> parents,
                      Set<UmlGraph.Type> visiting) {
        List<UmlGraph.Type> ps = parents.get(t);
        if (ps == null || ps.isEmpty() || !visiting.add(t)) return 0;
        int best = 0;
        for (UmlGraph.Type p : ps) best = Math.max(best, depth(p, parents, visiting) + 1);
        visiting.remove(t);
        return best;
    }

    // ── Placement ───────────────────────────────────────────────────────────

    private void place(UmlGraph graph) {
        Map<Integer, List<UmlGraph.Type>> rows = new LinkedHashMap<>();
        int maxLayer = 0;
        for (UmlGraph.Type t : graph.types()) {
            rows.computeIfAbsent(t.layer, k -> new ArrayList<>()).add(t);
            maxLayer = Math.max(maxLayer, t.layer);
        }

        // Order each row under its parents' average x, so edges stay short and
        // mostly untangled. One pass top-down is enough at these sizes; a full
        // crossing-minimisation would not visibly change a twenty-box diagram.
        Map<UmlGraph.Type, Float> centre = new HashMap<>();
        float y = 0f;
        for (int layer = 0; layer <= maxLayer; layer++) {
            List<UmlGraph.Type> row = rows.get(layer);
            if (row == null) continue;
            if (layer > 0) {
                row.sort((a, b) -> Float.compare(
                        barycentre(a, graph, centre), barycentre(b, graph, centre)));
            }
            float x = 0f, tallest = 0f;
            for (UmlGraph.Type t : row) {
                t.x = x;
                t.y = y;
                centre.put(t, x + t.width / 2f);
                x += t.width + H_GAP;
                tallest = Math.max(tallest, t.height);
            }
            width = Math.max(width, x - H_GAP);
            y += tallest + V_GAP;
        }
        height = Math.max(0f, y - V_GAP);

        // Centre every row, so the hierarchy reads as a tree rather than as
        // everything jammed against the left edge.
        for (List<UmlGraph.Type> row : rows.values()) {
            if (row.isEmpty()) continue;
            UmlGraph.Type last = row.get(row.size() - 1);
            float rowWidth = last.x + last.width - row.get(0).x;
            float shift = (width - rowWidth) / 2f;
            for (UmlGraph.Type t : row) {
                t.x += shift;
                centre.put(t, t.x + t.width / 2f);
            }
        }
    }

    private float barycentre(UmlGraph.Type t, UmlGraph graph, Map<UmlGraph.Type, Float> centre) {
        float sum = 0f;
        int n = 0;
        for (UmlGraph.Relation r : graph.relations()) {
            if (r.link == UmlGraph.Link.ASSOCIATION || r.from != t) continue;
            Float c = centre.get(r.to);
            if (c != null) { sum += c; n++; }
        }
        return n == 0 ? Float.MAX_VALUE : sum / n;
    }

    // ── Routing ─────────────────────────────────────────────────────────────

    private void route(UmlGraph graph) {
        for (UmlGraph.Relation r : graph.relations()) {
            Route route = new Route(r);
            if (r.link == UmlGraph.Link.ASSOCIATION) {
                sideRoute(route, r.from, r.to);
            } else {
                verticalRoute(route, r.from, r.to);
            }
            routes.add(route);
        }
    }

    /** Child bottom-up to parent: out of the top edge, across, into the bottom. */
    private void verticalRoute(Route route, UmlGraph.Type child, UmlGraph.Type parent) {
        float cx = child.x + child.width / 2f;
        float px = parent.x + parent.width / 2f;
        float cy = child.y;
        float py = parent.y + parent.height;
        if (py > cy) {                       // parent below: leave from the bottom
            cy = child.y + child.height;
            py = parent.y;
        }
        float mid = (cy + py) / 2f;
        route.points.add(new float[]{cx, cy});
        route.points.add(new float[]{cx, mid});
        route.points.add(new float[]{px, mid});
        route.points.add(new float[]{px, py});
    }

    /** Association: straight between the facing sides, or around if stacked. */
    private void sideRoute(Route route, UmlGraph.Type from, UmlGraph.Type to) {
        float fx = from.x + from.width / 2f, fy = from.y + from.height / 2f;
        float tx = to.x + to.width / 2f, ty = to.y + to.height / 2f;
        if (Math.abs(tx - fx) >= Math.abs(ty - fy)) {
            float x1 = tx > fx ? from.x + from.width : from.x;
            float x2 = tx > fx ? to.x : to.x + to.width;
            route.points.add(new float[]{x1, fy});
            route.points.add(new float[]{(x1 + x2) / 2f, fy});
            route.points.add(new float[]{(x1 + x2) / 2f, ty});
            route.points.add(new float[]{x2, ty});
        } else {
            float y1 = ty > fy ? from.y + from.height : from.y;
            float y2 = ty > fy ? to.y : to.y + to.height;
            route.points.add(new float[]{fx, y1});
            route.points.add(new float[]{fx, (y1 + y2) / 2f});
            route.points.add(new float[]{tx, (y1 + y2) / 2f});
            route.points.add(new float[]{tx, y2});
        }
    }

    public static Collection<UmlGraph.Type> sorted(UmlGraph g) {
        List<UmlGraph.Type> all = new ArrayList<>(g.types());
        all.sort((a, b) -> a.layer != b.layer ? Integer.compare(a.layer, b.layer)
                : Float.compare(a.x, b.x));
        return all;
    }
}
