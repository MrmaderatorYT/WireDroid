package com.ccs.wiredroid.db;

import com.ccs.wiredroid.uml.UmlGraph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a live schema into the same graph a class diagram is drawn from.
 *
 * <p>A table is a box, a column is a row inside it, a foreign key is a line —
 * the shapes a class diagram already knows how to lay out and paint. Reusing
 * that stack means the schema view inherits its routing, panning and theming
 * for free, and the two diagrams look like they belong to one program.</p>
 */
public final class DbSchemaGraph {

    /** Past this many tables the picture stops being readable, so it stops. */
    public static final int MAX_TABLES = 40;

    private DbSchemaGraph() {
    }

    public static final class Result {
        public final UmlGraph graph;
        /** Tables left out because of {@link #MAX_TABLES}, for an honest note. */
        public final int omitted;
        public final int links;

        Result(UmlGraph graph, int omitted, int links) {
            this.graph = graph;
            this.omitted = omitted;
            this.links = links;
        }
    }

    public static Result build(DbSession session) throws Exception {
        UmlGraph graph = UmlGraph.empty();

        List<DbSession.TableRef> all = session.listTables();
        List<DbSession.TableRef> shown = new ArrayList<>();
        for (DbSession.TableRef t : all) {
            if (shown.size() >= MAX_TABLES) break;
            shown.add(t);
        }

        // Boxes first: a foreign key can point at a table that comes later in
        // the list, so nothing can be joined until every box exists.
        Map<String, UmlGraph.Type> byName = new LinkedHashMap<>();
        for (DbSession.TableRef t : shown) {
            // A view is badged as an interface: not a real thing you can write
            // to, which is the same distinction the interface badge carries.
            UmlGraph.Type box = graph.addType(t.display(),
                    t.isView() ? UmlGraph.Kind.INTERFACE : UmlGraph.Kind.CLASS);
            byName.put(key(t.name), box);
            byName.put(key(t.display()), box);

            try {
                for (DbSession.ColumnInfo c : session.listColumns(t)) {
                    String name = c.primaryKey ? c.name + " ★" : c.name;
                    String type = c.type == null ? "" : c.type.toLowerCase(java.util.Locale.ROOT);
                    if (!c.nullable && !c.primaryKey) type = type + " !";
                    graph.addMember(box, name, type, false);
                }
            } catch (Throwable ignored) {
                // One unreadable table should not cost the whole diagram.
            }
        }

        int links = 0;
        for (DbSession.TableRef t : shown) {
            UmlGraph.Type from = byName.get(key(t.name));
            if (from == null) continue;
            try {
                for (DbSession.ForeignKey fk : session.listForeignKeys(t)) {
                    UmlGraph.Type to = byName.get(key(fk.toTable));
                    if (to == null) continue;
                    graph.addRelation(from, to, UmlGraph.Link.ASSOCIATION,
                            label(fk));
                    links++;
                }
            } catch (Throwable ignored) {
            }
        }

        return new Result(graph, Math.max(0, all.size() - shown.size()), links);
    }

    /** The column doing the pointing, which is what you want to read off a line. */
    private static String label(DbSession.ForeignKey fk) {
        if (fk.fromColumn == null) return "";
        return fk.fromColumn;
    }

    /** Table names are case-insensitive across the engines involved. */
    private static String key(String name) {
        return name == null ? "" : name.toLowerCase(java.util.Locale.ROOT);
    }
}
