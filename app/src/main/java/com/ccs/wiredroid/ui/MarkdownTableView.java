package com.ccs.wiredroid.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableStringBuilder;
import android.text.method.LinkMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.ccs.wiredroid.util.AppTheme;
import com.ccs.wiredroid.util.Colors;
import com.ccs.wiredroid.util.MarkdownRenderer;

import java.util.ArrayList;
import java.util.List;

/**
 * A Markdown table drawn the way IntelliJ's preview draws one: a continuous
 * grid, a shaded header, zebra-striped body rows and per-column alignment.
 *
 * <p>The grid is <em>painted</em> rather than assembled. The previous version
 * built it out of one-pixel {@code View}s inserted between the cells, which is
 * the obvious approach and does not work: a child of a {@link TableRow} sized
 * {@code MATCH_PARENT} does not grow to the row's height, and the horizontal
 * rules were themselves table rows, so every vertical line stopped at each one.
 * What reached the screen was a field of short disconnected strokes that read as
 * literal {@code |} characters — the table looked like unrendered source.</p>
 *
 * <p>Painting the lines in {@link #dispatchDraw} from the children's own
 * measured bounds makes each one a single stroke across the whole table, so the
 * grid closes at every intersection no matter how tall a row grows.</p>
 */
public class MarkdownTableView extends LinearLayout {

    private final AppTheme theme;
    private final Typeface tf;

    public MarkdownTableView(Context context, String tableMarkdown, AppTheme theme,
                             @Nullable Typeface tf) {
        super(context);
        this.theme = theme;
        this.tf = tf;
        setOrientation(LinearLayout.VERTICAL);

        boolean dark = theme != null && theme.dark;
        int borderColor = theme != null ? theme.separator : (dark ? 0xFF393B40 : 0xFFD0D7DE);

        GradientDrawable boxBg = new GradientDrawable();
        boxBg.setColor(theme != null ? theme.bg : (dark ? 0xFF1E1F22 : 0xFFFFFFFF));
        boxBg.setStroke(dp(1), borderColor);
        boxBg.setCornerRadius(dp(6));
        setBackground(boxBg);
        setClipToOutline(true);

        HorizontalScrollView hScroll = new HorizontalScrollView(context);
        hScroll.setFillViewport(true);
        hScroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        hScroll.setHorizontalScrollBarEnabled(false);

        GridTable table = new GridTable(context, borderColor);
        // Narrow tables fill the width instead of huddling on the left, which is
        // what the HTML preview does; wide ones overflow and the scroller takes over.
        table.setStretchAllColumns(true);
        buildTable(context, tableMarkdown, table, dark);

        hScroll.addView(table, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(hScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void buildTable(Context context, String markdown, GridTable table, boolean dark) {
        if (markdown == null || markdown.trim().isEmpty()) return;

        List<String[]> rows = new ArrayList<>();
        for (String line : markdown.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            rows.add(parseRow(trimmed));
        }
        if (rows.isEmpty()) return;

        int numCols = 0;
        for (String[] row : rows) numCols = Math.max(numCols, row.length);
        if (numCols == 0) return;

        int[] alignments = new int[numCols];
        for (int i = 0; i < numCols; i++) alignments[i] = Gravity.START;

        int dataStartRow = 1;
        if (rows.size() > 1 && isDelimiterRow(rows.get(1))) {
            String[] delimiterCols = rows.get(1);
            for (int c = 0; c < Math.min(numCols, delimiterCols.length); c++) {
                String d = delimiterCols[c].trim();
                boolean startColon = d.startsWith(":");
                boolean endColon = d.endsWith(":");
                if (startColon && endColon) alignments[c] = Gravity.CENTER;
                else if (endColon) alignments[c] = Gravity.END;
                else alignments[c] = Gravity.START;
            }
            dataStartRow = 2;
        }

        int headerBg = theme != null
                ? Colors.blend(theme.toolbar, theme.bg, 0.55f)
                : (dark ? 0xFF1E1F22 : 0xFFEAEEF2);
        int headerTextColor = dark ? 0xFFFFFFFF : 0xFF1F2328;
        int cellTextColor = theme != null ? theme.text : (dark ? 0xFFDFE1E5 : 0xFF24292F);

        String[] header = rows.get(0);
        TableRow trHeader = new TableRow(context);
        trHeader.setBackgroundColor(headerBg);
        for (int c = 0; c < numCols; c++) {
            trHeader.addView(createCellTextView(context,
                    c < header.length ? header[c] : "", true, headerTextColor, alignments[c], dark));
        }
        table.addView(trHeader);
        table.setHeaderRowCount(1);

        int dataRowIndex = 0;
        for (int r = dataStartRow; r < rows.size(); r++) {
            String[] row = rows.get(r);
            TableRow tr = new TableRow(context);
            tr.setBackgroundColor(dataRowIndex % 2 == 1
                    ? (dark ? 0x14FFFFFF : 0x09000000)
                    : Color.TRANSPARENT);
            for (int c = 0; c < numCols; c++) {
                tr.addView(createCellTextView(context,
                        c < row.length ? row[c] : "", false, cellTextColor, alignments[c], dark));
            }
            table.addView(tr);
            dataRowIndex++;
        }
    }

    private TextView createCellTextView(Context context, String text, boolean isHeader,
                                        int textColor, int alignGravity, boolean dark) {
        TextView tv = new TextView(context);
        // The typeface still reaches the inline renderer, so a `code` span inside
        // a cell stays fixed-width — but the cell itself is proportional. Letting
        // the editor's monospace font style whole cells is a large part of why
        // these tables looked like raw source rather than a rendered table.
        SpannableStringBuilder formatted = MarkdownRenderer.renderInline(text.trim(), dark, tf);
        tv.setText(formatted);
        tv.setTextSize(isHeader ? 13.5f : 13f);
        tv.setTextColor(textColor);
        tv.setTypeface(isHeader ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        tv.setGravity(alignGravity | Gravity.CENTER_VERTICAL);
        tv.setPadding(dp(13), dp(9), dp(13), dp(9));
        tv.setMinimumWidth(dp(56));
        tv.setTextIsSelectable(!isHeader);
        tv.setLinksClickable(true);
        tv.setMovementMethod(LinkMovementMethod.getInstance());
        return tv;
    }

    /**
     * A {@link TableLayout} that paints its own grid.
     *
     * <p>Each line is one stroke spanning the full width or height of the table,
     * taken from where the children actually ended up, so the grid stays closed
     * however the rows are measured.</p>
     */
    private static final class GridTable extends TableLayout {

        private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float hairline;
        private int headerRowCount;

        GridTable(Context context, int lineColor) {
            super(context);
            hairline = Math.max(1f, context.getResources().getDisplayMetrics().density);
            grid.setStyle(Paint.Style.STROKE);
            grid.setColor(lineColor);
            setWillNotDraw(false);
        }

        void setHeaderRowCount(int count) {
            headerRowCount = count;
        }

        @Override
        protected void dispatchDraw(@NonNull Canvas canvas) {
            super.dispatchDraw(canvas);
            int rowCount = getChildCount();
            if (rowCount == 0) return;

            int width = getWidth();
            int height = getHeight();

            // Column boundaries come from the first row: TableLayout has already
            // equalised the columns, so one row describes all of them.
            View first = getChildAt(0);
            if (first instanceof TableRow) {
                TableRow row = (TableRow) first;
                grid.setStrokeWidth(hairline);
                for (int c = 0; c < row.getChildCount() - 1; c++) {
                    float x = row.getChildAt(c).getRight() + row.getLeft();
                    canvas.drawLine(x, 0, x, height, grid);
                }
            }

            for (int r = 0; r < rowCount - 1; r++) {
                View child = getChildAt(r);
                // The rule closing the header is heavier, as in the HTML preview.
                grid.setStrokeWidth(r < headerRowCount ? hairline * 1.5f : hairline);
                float y = child.getBottom();
                canvas.drawLine(0, y, width, y, grid);
            }
        }
    }

    private String[] parseRow(String row) {
        String trimmed = row.trim();
        if (trimmed.startsWith("|")) trimmed = trimmed.substring(1);
        if (trimmed.endsWith("|")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        String[] cols = trimmed.split("\\|", -1);
        for (int i = 0; i < cols.length; i++) cols[i] = cols[i].trim();
        return cols;
    }

    private boolean isDelimiterRow(String[] cols) {
        if (cols == null || cols.length == 0) return false;
        for (String col : cols) {
            String trimmed = col.trim();
            if (trimmed.isEmpty()) continue;
            String stripped = trimmed.replaceAll("[|:\\- \\t]", "");
            if (!stripped.isEmpty() || !trimmed.contains("-")) return false;
        }
        return true;
    }

    private int dp(float val) {
        return (int) (val * getResources().getDisplayMetrics().density + 0.5f);
    }
}
