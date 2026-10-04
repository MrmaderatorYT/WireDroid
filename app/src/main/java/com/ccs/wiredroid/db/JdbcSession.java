package com.ccs.wiredroid.db;

import android.content.Context;

import com.ccs.wiredroid.R;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

/**
 * A JDBC connection to MySQL/MariaDB or PostgreSQL.
 *
 * <p>Two rules keep this working on Android, both explained in
 * {@link DbDrivers}: the driver is constructed and called directly instead of
 * going through {@code DriverManager}, and every value is read with
 * {@code getString} so no driver-specific object (a {@code PGpoint}, say, which
 * extends the absent {@code java.awt.Point}) is ever instantiated.</p>
 */
final class JdbcSession extends DbSession {

    private final Context ctx;
    private final DbConnection config;
    private final Connection conn;

    /** The cursor a paged SELECT left open, plus the row read past its page. */
    private Statement pendingStatement;
    private ResultSet pendingResultSet;
    private List<String> pendingColumns;
    private String[] lookahead;

    private JdbcSession(Context ctx, DbConnection config, Connection conn) {
        this.ctx = ctx.getApplicationContext();
        this.config = config;
        this.conn = conn;
    }

    // Named connect() rather than open(): a static open() with this exact
    // signature would hide DbSession.open(), and a hiding static may not reduce
    // visibility — which is a compile error, and confusing even when it is not.
    static JdbcSession connect(Context ctx, DbConnection c, String password) throws Exception {
        Driver driver;
        try {
            driver = DbDrivers.instantiate(c.driver);
        } catch (Throwable t) {
            // A LinkageError here is a packaging problem, not an SSL one, so it
            // is reported as such rather than falling through to describe().
            throw new SQLException(ctx.getString(R.string.db_err_driver_load,
                    c.driver.label(ctx), DbDrivers.shortMessage(t)), t);
        }

        String url = DbDrivers.buildUrl(c);
        Properties props = DbDrivers.properties(c, password);

        // Anything the driver throws — including NoClassDefFoundError from the
        // TLS host-name verifier — is caught here and handed to describe(),
        // which is the only place that knows how to phrase it.
        Connection conn;
        try {
            conn = driver.connect(url, props);
        } catch (Throwable t) {
            if (t instanceof Exception) throw (Exception) t;
            throw new SQLException(DbDrivers.shortMessage(t), t);
        }
        if (conn == null) {
            throw new SQLException(ctx.getString(R.string.db_err_driver_rejected,
                    c.driver.label(ctx)));
        }
        return new JdbcSession(ctx, c, conn);
    }

    // ── Schema ──────────────────────────────────────────────────────────────

    @Override
    public List<TableRef> listTables() throws Exception {
        List<TableRef> out = new ArrayList<>();
        DatabaseMetaData md = conn.getMetaData();
        // MySQL puts the database in the catalog slot; PostgreSQL puts every
        // schema of the connected database in the schema slot.
        String catalog = config.driver == DbDrivers.Kind.MYSQL ? config.database : null;
        try (ResultSet rs = md.getTables(catalog, null, "%",
                new String[]{"TABLE", "VIEW"})) {
            while (rs.next()) {
                String schema = rs.getString("TABLE_SCHEM");
                if (isSystemSchema(schema)) continue;
                out.add(new TableRef(
                        rs.getString("TABLE_CAT"),
                        schema,
                        rs.getString("TABLE_NAME"),
                        rs.getString("TABLE_TYPE")));
            }
        }
        out.sort((a, b) -> a.display().compareToIgnoreCase(b.display()));
        return out;
    }

    private static boolean isSystemSchema(String schema) {
        if (schema == null) return false;
        String s = schema.toLowerCase(Locale.ROOT);
        return s.equals("pg_catalog") || s.equals("information_schema")
                || s.startsWith("pg_toast") || s.startsWith("pg_temp");
    }

    @Override
    public List<ColumnInfo> listColumns(TableRef table) throws Exception {
        DatabaseMetaData md = conn.getMetaData();
        Set<String> primaryKeys = new HashSet<>();
        try (ResultSet rs = md.getPrimaryKeys(table.catalog, table.schema, table.name)) {
            while (rs.next()) primaryKeys.add(rs.getString("COLUMN_NAME"));
        } catch (SQLException ignored) {
            // Some drivers refuse getPrimaryKeys on views; the column list is
            // still worth showing without the key marks.
        }

        List<ColumnInfo> out = new ArrayList<>();
        try (ResultSet rs = md.getColumns(table.catalog, table.schema, table.name, "%")) {
            while (rs.next()) {
                String name = rs.getString("COLUMN_NAME");
                String type = rs.getString("TYPE_NAME");
                int size = rs.getInt("COLUMN_SIZE");
                if (size > 0 && type != null && !type.contains("(")) {
                    String t = type.toLowerCase(Locale.ROOT);
                    if (t.contains("char") || t.contains("binary")) {
                        type = type + "(" + size + ")";
                    }
                }
                boolean nullable = "YES".equalsIgnoreCase(rs.getString("IS_NULLABLE"));
                out.add(new ColumnInfo(name, type, nullable, primaryKeys.contains(name)));
            }
        }
        return out;
    }

    @Override
    public List<ForeignKey> listForeignKeys(TableRef table) {
        List<ForeignKey> out = new ArrayList<>();
        try (ResultSet rs = conn.getMetaData()
                .getImportedKeys(table.catalog, table.schema, table.name)) {
            while (rs.next()) {
                out.add(new ForeignKey(
                        rs.getString("FKCOLUMN_NAME"),
                        rs.getString("PKTABLE_NAME"),
                        rs.getString("PKCOLUMN_NAME")));
            }
        } catch (Throwable ignored) {
            // Not every driver implements getImportedKeys; an unlinked table is
            // a better outcome than a failed diagram.
        }
        return out;
    }

    @Override
    public String qualify(TableRef table) {
        char q = config.driver == DbDrivers.Kind.MYSQL ? '`' : '"';
        StringBuilder sb = new StringBuilder();
        if (table.schema != null && !table.schema.isEmpty()
                && config.driver != DbDrivers.Kind.MYSQL) {
            sb.append(q).append(table.schema).append(q).append('.');
        }
        sb.append(q).append(table.name).append(q);
        return sb.toString();
    }

    // ── Statements ──────────────────────────────────────────────────────────

    @Override
    public QueryResult execute(String sql, int limit) throws Exception {
        closePending();
        long start = System.nanoTime();

        Statement st = conn.createStatement();
        try {
            st.setQueryTimeout(DbDrivers.QUERY_TIMEOUT_SECONDS);
        } catch (SQLException ignored) {
            // Not every driver honours it; the socket timeout is the backstop.
        }

        boolean hasResultSet;
        try {
            // execute() is what tells rows from a count. Sniffing the SQL text
            // for a leading SELECT gets CTEs and RETURNING clauses wrong.
            hasResultSet = st.execute(sql);
        } catch (Exception e) {
            closeQuietly(st);
            throw e;
        }

        if (!hasResultSet) {
            int count = st.getUpdateCount();
            closeQuietly(st);
            return QueryResult.ofUpdate(count, elapsedMs(start));
        }

        pendingStatement = st;
        pendingResultSet = st.getResultSet();
        ResultSetMetaData md = pendingResultSet.getMetaData();
        int columnCount = md.getColumnCount();
        pendingColumns = new ArrayList<>(columnCount);
        for (int i = 1; i <= columnCount; i++) {
            String label = md.getColumnLabel(i);
            pendingColumns.add(label == null || label.isEmpty() ? md.getColumnName(i) : label);
        }

        List<String[]> rows = readPage(limit);
        boolean more = lookahead != null;
        if (!more) closePending();
        // A server-side cursor cannot say how many rows it holds without
        // draining it, so the total stays unknown until the last page.
        return QueryResult.ofGrid(pendingColumnsOrEmpty(), rows, more,
                more ? QueryResult.UNKNOWN_TOTAL : rows.size(), elapsedMs(start));
    }

    @Override
    public QueryResult fetchMore(int limit) throws Exception {
        if (pendingResultSet == null) {
            return QueryResult.ofGrid(pendingColumnsOrEmpty(), new ArrayList<>(),
                    false, QueryResult.UNKNOWN_TOTAL, 0);
        }
        long start = System.nanoTime();
        List<String[]> rows = readPage(limit);
        boolean more = lookahead != null;
        if (!more) closePending();
        return QueryResult.ofGrid(pendingColumnsOrEmpty(), rows, more,
                QueryResult.UNKNOWN_TOTAL, elapsedMs(start));
    }

    private List<String> pendingColumnsOrEmpty() {
        return pendingColumns == null ? new ArrayList<>() : new ArrayList<>(pendingColumns);
    }

    /**
     * Reads up to {@code limit} rows, then one more. That extra row is what
     * makes "is there another page?" an answer rather than a guess; it is kept
     * as {@link #lookahead} and becomes the first row of the next page.
     */
    private List<String[]> readPage(int limit) throws SQLException {
        List<String[]> rows = new ArrayList<>();
        if (lookahead != null) {
            rows.add(lookahead);
            lookahead = null;
        }
        while (rows.size() < limit && pendingResultSet.next()) {
            rows.add(readRow());
        }
        if (rows.size() >= limit && pendingResultSet.next()) {
            lookahead = readRow();
        }
        return rows;
    }

    private String[] readRow() throws SQLException {
        int n = pendingColumns.size();
        String[] cells = new String[n];
        for (int i = 0; i < n; i++) {
            try {
                String v = pendingResultSet.getString(i + 1);
                // wasNull() after the read is the only reliable NULL test; a
                // driver may return null for an empty CLOB too.
                cells[i] = pendingResultSet.wasNull() ? null : clip(v);
            } catch (Exception e) {
                // One column the driver cannot render as text must not lose the
                // other columns of the row.
                cells[i] = ctx.getString(R.string.db_cell_unreadable);
            }
        }
        return cells;
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    // ── Lifecycle ───────────────────────────────────────────────────────────

    private void closePending() {
        lookahead = null;
        if (pendingResultSet != null) {
            try {
                pendingResultSet.close();
            } catch (Exception ignored) {
            }
            pendingResultSet = null;
        }
        if (pendingStatement != null) {
            closeQuietly(pendingStatement);
            pendingStatement = null;
        }
    }

    private static void closeQuietly(Statement st) {
        try {
            st.close();
        } catch (Exception ignored) {
        }
    }

    @Override
    public void close() {
        closePending();
        pendingColumns = null;
        try {
            conn.close();
        } catch (Exception ignored) {
        }
    }
}
