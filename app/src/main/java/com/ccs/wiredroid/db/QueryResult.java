package com.ccs.wiredroid.db;

import java.util.ArrayList;
import java.util.List;

/**
 * The outcome of one statement.
 *
 * <p>A statement either produced a grid or it produced a count; the two are
 * kept apart by {@link #grid} so the UI never has to guess from the SQL text
 * what {@code execute()} already told us.</p>
 */
public final class QueryResult {

    /** Rows are fetched in pages of this size; a grid never holds more at once. */
    public static final int PAGE_SIZE = 500;

    /** {@code -1} where the back end cannot tell us the total cheaply. */
    public static final int UNKNOWN_TOTAL = -1;
    /** {@code -1} where a statement returned neither rows nor a count. */
    public static final int NO_UPDATE_COUNT = -1;

    public final boolean grid;
    /** Column labels; empty when {@link #grid} is false. */
    public final List<String> columns;
    /** One entry per row; a {@code null} cell is a SQL NULL, not an empty string. */
    public final List<String[]> rows;
    /** Rows remain unread in the open cursor. */
    public final boolean more;
    /** Total rows the statement would yield, or {@link #UNKNOWN_TOTAL}. */
    public final int totalRows;
    public final int updateCount;
    public final long durationMs;

    private QueryResult(boolean grid, List<String> columns, List<String[]> rows,
                        boolean more, int totalRows, int updateCount, long durationMs) {
        this.grid = grid;
        this.columns = columns == null ? new ArrayList<>() : columns;
        this.rows = rows == null ? new ArrayList<>() : rows;
        this.more = more;
        this.totalRows = totalRows;
        this.updateCount = updateCount;
        this.durationMs = durationMs;
    }

    public static QueryResult ofGrid(List<String> columns, List<String[]> rows,
                                     boolean more, int totalRows, long durationMs) {
        return new QueryResult(true, columns, rows, more, totalRows,
                NO_UPDATE_COUNT, durationMs);
    }

    public static QueryResult ofUpdate(int updateCount, long durationMs) {
        return new QueryResult(false, null, null, false, UNKNOWN_TOTAL,
                updateCount, durationMs);
    }
}
