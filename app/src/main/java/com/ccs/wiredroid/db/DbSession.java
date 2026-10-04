package com.ccs.wiredroid.db;

import android.content.Context;

import java.util.List;
import java.util.Locale;

/**
 * An open database, whatever is behind it.
 *
 * <p>Every method here runs blocking I/O and must be called off the main
 * thread. A session owns a single connection and is not thread-safe: callers
 * serialise on one executor.</p>
 */
public abstract class DbSession {

    /** A table or view as {@link java.sql.DatabaseMetaData} reported it. */
    public static final class TableRef {
        public final String catalog;
        public final String schema;
        public final String name;
        /** {@code TABLE} or {@code VIEW}, verbatim from the metadata. */
        public final String type;

        public TableRef(String catalog, String schema, String name, String type) {
            this.catalog = catalog;
            this.schema = schema;
            this.name = name;
            this.type = type;
        }

        /** Schema-qualified where a schema exists, bare where it does not. */
        public String display() {
            return schema == null || schema.isEmpty() ? name : schema + "." + name;
        }

        public boolean isView() {
            return type != null && type.toUpperCase(Locale.ROOT).contains("VIEW");
        }
    }

    public static final class ColumnInfo {
        public final String name;
        public final String type;
        public final boolean nullable;
        public final boolean primaryKey;

        public ColumnInfo(String name, String type, boolean nullable, boolean primaryKey) {
            this.name = name;
            this.type = type;
            this.nullable = nullable;
            this.primaryKey = primaryKey;
        }
    }

    /** One column pointing at another table's column. */
    public static final class ForeignKey {
        public final String fromColumn;
        public final String toTable;
        public final String toColumn;

        public ForeignKey(String fromColumn, String toTable, String toColumn) {
            this.fromColumn = fromColumn;
            this.toTable = toTable;
            this.toColumn = toColumn;
        }
    }

    /** True when the underlying store refuses writes. */
    public boolean isReadOnly() {
        return false;
    }

    /**
     * The outgoing references of a table.
     *
     * <p>Empty by default: a driver that cannot report them should yield a
     * diagram of unconnected tables rather than an error.</p>
     */
    public List<ForeignKey> listForeignKeys(TableRef table) throws Exception {
        return new java.util.ArrayList<>();
    }

    public abstract List<TableRef> listTables() throws Exception;

    public abstract List<ColumnInfo> listColumns(TableRef table) throws Exception;

    /** Identifier quoting for the current dialect, used to build SELECTs. */
    public abstract String qualify(TableRef table);

    /**
     * Runs one statement and reads at most {@code limit} rows. Any cursor left
     * over from a previous call is closed first.
     */
    public abstract QueryResult execute(String sql, int limit) throws Exception;

    /** Reads the next {@code limit} rows from the cursor {@link #execute} left open. */
    public abstract QueryResult fetchMore(int limit) throws Exception;

    public abstract void close();

    /**
     * Opens a session. SQLite goes straight to {@code android.database.sqlite};
     * the two servers go through JDBC.
     */
    public static DbSession open(Context ctx, DbConnection c, String password) throws Exception {
        if (c.driver.isFile()) {
            return SqliteSession.connect(ctx, c);
        }
        return JdbcSession.connect(ctx, c, password);
    }

    /**
     * Long values are clipped on the way out of the driver: a single TEXT or
     * BLOB column can be megabytes, and nothing good happens when that is put
     * into a TextView.
     */
    static final int MAX_CELL_CHARS = 8192;

    static String clip(String v) {
        if (v == null || v.length() <= MAX_CELL_CHARS) return v;
        return v.substring(0, MAX_CELL_CHARS) + "…";
    }
}
