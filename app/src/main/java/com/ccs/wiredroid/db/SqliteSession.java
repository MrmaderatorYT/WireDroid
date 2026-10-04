package com.ccs.wiredroid.db;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;

import com.ccs.wiredroid.R;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A local SQLite file, opened with the platform's own engine.
 *
 * <p>There is no SQLite JDBC driver in the app and there does not need to be:
 * {@code android.database.sqlite} is already linked into every process, which
 * is the same route the read-only
 * {@link com.ccs.wiredroid.ui.DatabaseInspectorActivity} takes. This class adds
 * paging and write support on top so a SQLite file behaves like any other
 * connection in this client.</p>
 *
 * <p>The file is opened read-write when the filesystem allows it and read-only
 * otherwise; {@link #isReadOnly()} lets the UI say which happened instead of
 * letting a write fail mysteriously later.</p>
 */
final class SqliteSession extends DbSession {

    private final Context ctx;
    private final SQLiteDatabase db;
    private final boolean readOnly;

    private Cursor pendingCursor;
    private List<String> pendingColumns;
    /** Rows handed to the UI so far from {@link #pendingCursor}. */
    private int rowsRead;

    private SqliteSession(Context ctx, SQLiteDatabase db, boolean readOnly) {
        this.ctx = ctx.getApplicationContext();
        this.db = db;
        this.readOnly = readOnly;
    }

    // connect(), not open() — see the note on JdbcSession.connect.
    static SqliteSession connect(Context ctx, DbConnection c) throws Exception {
        File file = new File(c.database);
        if (!file.isFile()) {
            throw new FileNotFoundException(
                    ctx.getString(R.string.db_err_file_missing, c.database));
        }
        boolean readOnly = !file.canWrite();
        SQLiteDatabase db = SQLiteDatabase.openDatabase(file.getAbsolutePath(), null,
                readOnly ? SQLiteDatabase.OPEN_READONLY : SQLiteDatabase.OPEN_READWRITE);
        return new SqliteSession(ctx, db, readOnly);
    }

    @Override
    public boolean isReadOnly() {
        return readOnly;
    }

    // ── Schema ──────────────────────────────────────────────────────────────

    @Override
    public List<TableRef> listTables() {
        List<TableRef> out = new ArrayList<>();
        try (Cursor c = db.rawQuery(
                "SELECT name, type FROM sqlite_master WHERE type IN ('table','view')"
                        + " AND name NOT LIKE 'sqlite_%' ORDER BY type, name", null)) {
            while (c.moveToNext()) {
                out.add(new TableRef(null, null, c.getString(0),
                        "view".equalsIgnoreCase(c.getString(1)) ? "VIEW" : "TABLE"));
            }
        }
        return out;
    }

    @Override
    public List<ColumnInfo> listColumns(TableRef table) {
        List<ColumnInfo> out = new ArrayList<>();
        // PRAGMA takes an identifier, not a bound parameter, so the name is
        // quoted the SQLite way: double quotes, embedded quotes doubled.
        String pragma = "PRAGMA table_info(\"" + table.name.replace("\"", "\"\"") + "\")";
        try (Cursor c = db.rawQuery(pragma, null)) {
            int iName = c.getColumnIndex("name");
            int iType = c.getColumnIndex("type");
            int iNotNull = c.getColumnIndex("notnull");
            int iPk = c.getColumnIndex("pk");
            while (c.moveToNext()) {
                out.add(new ColumnInfo(
                        iName >= 0 ? c.getString(iName) : null,
                        iType >= 0 ? c.getString(iType) : null,
                        iNotNull < 0 || c.getInt(iNotNull) == 0,
                        iPk >= 0 && c.getInt(iPk) > 0));
            }
        }
        return out;
    }

    @Override
    public List<ForeignKey> listForeignKeys(TableRef table) {
        List<ForeignKey> out = new ArrayList<>();
        String pragma = "PRAGMA foreign_key_list(\"" + table.name.replace("\"", "\"\"") + "\")";
        try (Cursor c = db.rawQuery(pragma, null)) {
            int iTable = c.getColumnIndex("table");
            int iFrom = c.getColumnIndex("from");
            int iTo = c.getColumnIndex("to");
            while (c.moveToNext()) {
                String target = iTable >= 0 ? c.getString(iTable) : null;
                if (target == null) continue;
                // "to" is null when the reference targets the primary key
                // implicitly, which is the common shorthand.
                out.add(new ForeignKey(
                        iFrom >= 0 ? c.getString(iFrom) : null,
                        target,
                        iTo >= 0 ? c.getString(iTo) : null));
            }
        } catch (Throwable ignored) {
            // An older file or a view: no keys rather than no diagram.
        }
        return out;
    }

    @Override
    public String qualify(TableRef table) {
        return "\"" + table.name.replace("\"", "\"\"") + "\"";
    }

    // ── Statements ──────────────────────────────────────────────────────────

    @Override
    public QueryResult execute(String sql, int limit) throws Exception {
        closePending();
        long start = System.nanoTime();
        String head = firstKeyword(sql);

        if (isRead(head)) {
            pendingCursor = db.rawQuery(sql, null);
            String[] names = pendingCursor.getColumnNames();
            pendingColumns = new ArrayList<>(names.length);
            for (String n : names) pendingColumns.add(n);

            // Unlike a JDBC cursor, SQLite knows the row count up front, so
            // "showing N of M" can be exact here.
            int total = pendingCursor.getCount();
            List<String[]> rows = readPage(limit);
            boolean more = rowsRead < total;
            if (!more) closePending();
            return QueryResult.ofGrid(pendingColumnsOrEmpty(), rows, more, total,
                    elapsedMs(start));
        }

        if (readOnly) {
            throw new IllegalStateException(ctx.getString(R.string.db_err_readonly));
        }

        if (isCounted(head)) {
            try (SQLiteStatement st = db.compileStatement(sql)) {
                int count = "insert".equals(head) || "replace".equals(head)
                        ? (st.executeInsert() >= 0 ? 1 : 0)
                        : st.executeUpdateDelete();
                return QueryResult.ofUpdate(count, elapsedMs(start));
            }
        }

        db.execSQL(sql);
        return QueryResult.ofUpdate(QueryResult.NO_UPDATE_COUNT, elapsedMs(start));
    }

    @Override
    public QueryResult fetchMore(int limit) {
        if (pendingCursor == null) {
            return QueryResult.ofGrid(pendingColumnsOrEmpty(), new ArrayList<>(),
                    false, QueryResult.UNKNOWN_TOTAL, 0);
        }
        long start = System.nanoTime();
        int total = pendingCursor.getCount();
        List<String[]> rows = readPage(limit);
        boolean more = rowsRead < total;
        if (!more) closePending();
        return QueryResult.ofGrid(pendingColumnsOrEmpty(), rows, more, total, elapsedMs(start));
    }

    private List<String> pendingColumnsOrEmpty() {
        return pendingColumns == null ? new ArrayList<>() : new ArrayList<>(pendingColumns);
    }

    private List<String[]> readPage(int limit) {
        List<String[]> rows = new ArrayList<>();
        while (rows.size() < limit && pendingCursor.moveToNext()) {
            rows.add(readRow());
        }
        rowsRead += rows.size();
        return rows;
    }

    private String[] readRow() {
        int n = pendingColumns.size();
        String[] cells = new String[n];
        for (int i = 0; i < n; i++) {
            try {
                switch (pendingCursor.getType(i)) {
                    case Cursor.FIELD_TYPE_NULL:
                        cells[i] = null;
                        break;
                    case Cursor.FIELD_TYPE_BLOB:
                        // getString on a BLOB throws; report the size instead.
                        byte[] blob = pendingCursor.getBlob(i);
                        cells[i] = ctx.getString(R.string.db_cell_blob,
                                blob == null ? 0 : blob.length);
                        break;
                    default:
                        cells[i] = clip(pendingCursor.getString(i));
                }
            } catch (Exception e) {
                cells[i] = ctx.getString(R.string.db_cell_unreadable);
            }
        }
        return cells;
    }

    /** The leading keyword, lower-cased, with comments and blanks skipped. */
    private static String firstKeyword(String sql) {
        String s = sql.trim();
        while (true) {
            if (s.startsWith("--")) {
                int nl = s.indexOf('\n');
                if (nl < 0) return "";
                s = s.substring(nl + 1).trim();
            } else if (s.startsWith("/*")) {
                int end = s.indexOf("*/");
                if (end < 0) return "";
                s = s.substring(end + 2).trim();
            } else {
                break;
            }
        }
        int i = 0;
        while (i < s.length() && Character.isLetter(s.charAt(i))) i++;
        return s.substring(0, i).toLowerCase(Locale.ROOT);
    }

    private static boolean isRead(String keyword) {
        return keyword.equals("select") || keyword.equals("with")
                || keyword.equals("pragma") || keyword.equals("explain")
                || keyword.equals("values");
    }

    private static boolean isCounted(String keyword) {
        return keyword.equals("insert") || keyword.equals("replace")
                || keyword.equals("update") || keyword.equals("delete");
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    // ── Lifecycle ───────────────────────────────────────────────────────────

    private void closePending() {
        rowsRead = 0;
        if (pendingCursor != null) {
            try {
                pendingCursor.close();
            } catch (Exception ignored) {
            }
            pendingCursor = null;
        }
    }

    @Override
    public void close() {
        closePending();
        pendingColumns = null;
        try {
            if (db.isOpen()) db.close();
        } catch (Exception ignored) {
        }
    }
}
