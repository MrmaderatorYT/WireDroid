package com.ccs.wiredroid.ui;
import com.ccs.wiredroid.R;
import com.ccs.wiredroid.util.AppPreferences;
import com.ccs.wiredroid.util.AppTheme;
import com.ccs.wiredroid.util.FullScreenHelper;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Database Inspector: browse SQLite databases, view tables, run SQL queries.
 * Pure Android UI (no WebView).
 */
public class DatabaseInspectorActivity extends AppCompatActivity {

    private static final String EXTRA_DB_PATH = "db_path";

    private AppPreferences prefs;
    private AppTheme theme;
    private SQLiteDatabase db;
    private File dbFile;

    private LinearLayout tablesList;
    private LinearLayout resultContainer;
    private EditText sqlInput;
    private TextView statusText;
    private TextView tableNameHeader;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    public static void launch(Context context, File dbFile) {
        Intent i = new Intent(context, DatabaseInspectorActivity.class);
        i.putExtra(EXTRA_DB_PATH, dbFile.getAbsolutePath());
        context.startActivity(i);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        prefs = new AppPreferences(this);
        theme = AppTheme.byId(prefs.getThemeId(), prefs);
        setTheme(theme.dark ? R.style.Theme_WireDroid : R.style.Theme_WireDroid_Light);

        super.onCreate(savedInstanceState);
        try {
            setContentView(buildRoot());
        } catch (Exception e) {
            android.util.Log.e("DBInspector", "buildRoot failed", e);
            LinearLayout fallback = new LinearLayout(this);
            fallback.setOrientation(LinearLayout.VERTICAL);
            fallback.setBackgroundColor(0xFFFFFFFF);
            TextView errTv = new TextView(this);
            errTv.setText("Error: " + e.getMessage());
            errTv.setTextColor(0xFFFF0000);
            errTv.setPadding(16, 16, 16, 16);
            fallback.addView(errTv);
            setContentView(fallback);
            return;
        }
        FullScreenHelper.enable(this);

        String path = getIntent().getStringExtra(EXTRA_DB_PATH);
        if (path == null) {
            Toast.makeText(this, R.string.toast_no_database, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        dbFile = new File(path);
        openDatabase();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (db != null && db.isOpen()) db.close();
        io.shutdownNow();
    }

    private View buildRoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(theme.bg);

        Toolbar toolbar = new Toolbar(this);
        toolbar.setBackgroundColor(theme.toolbar);
        toolbar.setTitle("Database Inspector");
        toolbar.setTitleTextColor(theme.text);
        toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
        toolbar.setNavigationOnClickListener(v -> finish());
        root.addView(toolbar);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.HORIZONTAL);
        content.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Left panel: table list
        LinearLayout leftPanel = new LinearLayout(this);
        leftPanel.setOrientation(LinearLayout.VERTICAL);
        leftPanel.setBackgroundColor(theme.consoleBg);
        leftPanel.setLayoutParams(new LinearLayout.LayoutParams(dp(200), ViewGroup.LayoutParams.MATCH_PARENT));

        TextView tablesLabel = new TextView(this);
        tablesLabel.setText(R.string.label_tables);
        tablesLabel.setTextColor(theme.textDim);
        tablesLabel.setTextSize(11);
        tablesLabel.setTypeface(prefs.resolveTypeface());
        tablesLabel.setPadding(dp(8), dp(6), dp(8), dp(6));
        leftPanel.addView(tablesLabel);

        ScrollView tablesScroll = new ScrollView(this);
        tablesScroll.setFillViewport(true);
        tablesList = new LinearLayout(this);
        tablesList.setOrientation(LinearLayout.VERTICAL);
        tablesScroll.addView(tablesList);
        leftPanel.addView(tablesScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        content.addView(leftPanel);

        // Divider
        View divider = new View(this);
        divider.setBackgroundColor(theme.separator);
        content.addView(divider, new LinearLayout.LayoutParams(1, ViewGroup.LayoutParams.MATCH_PARENT));

        // Right panel: SQL + results
        LinearLayout rightPanel = new LinearLayout(this);
        rightPanel.setOrientation(LinearLayout.VERTICAL);
        rightPanel.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));

        // SQL input
        sqlInput = new EditText(this);
        sqlInput.setHint("Enter SQL query...");
        sqlInput.setHintTextColor(theme.textDim);
        sqlInput.setTextColor(theme.text);
        sqlInput.setBackgroundColor(theme.consoleBg);
        sqlInput.setTypeface(prefs.resolveTypeface());
        sqlInput.setTextSize(12);
        sqlInput.setMinLines(2);
        sqlInput.setMaxLines(4);
        sqlInput.setPadding(dp(8), dp(6), dp(8), dp(6));
        sqlInput.setContentDescription(getString(R.string.a11y_db_sql_input));

        LinearLayout sqlRow = new LinearLayout(this);
        sqlRow.setOrientation(LinearLayout.HORIZONTAL);
        sqlRow.setPadding(dp(4), dp(4), dp(4), dp(4));
        sqlRow.addView(sqlInput, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView runBtn = createButton("▶ Run", theme.successText);
        runBtn.setContentDescription(getString(R.string.a11y_db_run));
        runBtn.setOnClickListener(v -> executeQuery());
        sqlRow.addView(runBtn);

        TextView clearBtn = createButton("Clear", theme.textDim);
        clearBtn.setContentDescription(getString(R.string.a11y_db_clear));
        clearBtn.setOnClickListener(v -> sqlInput.setText(""));
        sqlRow.addView(clearBtn);

        rightPanel.addView(sqlRow);

        // Table name header
        tableNameHeader = new TextView(this);
        tableNameHeader.setTextColor(theme.accent);
        tableNameHeader.setTextSize(12);
        tableNameHeader.setTypeface(prefs.resolveTypeface());
        tableNameHeader.setPadding(dp(8), dp(4), dp(8), dp(4));
        rightPanel.addView(tableNameHeader);

        // Result area
        ScrollView resultScroll = new ScrollView(this);
        resultScroll.setFillViewport(true);
        resultContainer = new LinearLayout(this);
        resultContainer.setOrientation(LinearLayout.VERTICAL);
        resultContainer.setPadding(dp(4), dp(4), dp(4), dp(4));
        resultScroll.addView(resultContainer);
        rightPanel.addView(resultScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        content.addView(rightPanel);
        root.addView(content);

        // Status bar
        statusText = new TextView(this);
        statusText.setBackgroundColor(theme.consoleBg);
        statusText.setTextColor(theme.textDim);
        statusText.setTextSize(10);
        statusText.setPadding(dp(8), dp(4), dp(8), dp(4));
        root.addView(statusText);

        return root;
    }

    private void openDatabase() {
        statusText.setText("Opening: " + dbFile.getName());

        io.execute(() -> {
            try {
                db = SQLiteDatabase.openDatabase(
                        dbFile.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
                ui.post(() -> {
                    statusText.setText(String.format("%s — %d KB",
                            dbFile.getName(), dbFile.length() / 1024));
                    loadTables();
                });
            } catch (Exception e) {
                ui.post(() -> {
                    statusText.setText("Error: " + e.getMessage());
                    Toast.makeText(this, getString(R.string.toast_cannot_open_db, e.getMessage()),
                            Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void loadTables() {
        ui.post(() -> tablesList.removeAllViews());

        io.execute(() -> {
            List<String> tables = new ArrayList<>();
            try (Cursor c = db.rawQuery(
                    "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name", null)) {
                while (c.moveToNext()) {
                    tables.add(c.getString(0));
                }
            }

            ui.post(() -> {
                if (tables.isEmpty()) {
                    TextView empty = new TextView(this);
                    empty.setText(R.string.label_no_tables);
                    empty.setTextColor(theme.textDim);
                    empty.setPadding(dp(8), dp(12), dp(8), dp(0));
                    tablesList.addView(empty);
                    return;
                }

                for (String table : tables) {
                    TextView item = new TextView(this);
                    item.setText(table);
                    item.setTextColor(theme.accent);
                    item.setTextSize(12);
                    item.setTypeface(prefs.resolveTypeface());
                    item.setPadding(dp(8), dp(6), dp(8), dp(6));
                    item.setBackgroundResource(android.R.drawable.list_selector_background);
                    item.setContentDescription(getString(R.string.a11y_db_table_item, table));
                    item.setOnClickListener(v -> browseTable(table));
                    tablesList.addView(item);
                }

                statusText.setText(String.format(Locale.US, "%s — %d tables",
                        dbFile.getName(), tables.size()));
            });
        });
    }

    private void browseTable(String tableName) {
        tableNameHeader.setText(tableName);
        sqlInput.setText("SELECT * FROM " + tableName + " LIMIT 100");
        executeQuery();
    }

    private void executeQuery() {
        String sql = sqlInput.getText().toString().trim();
        if (sql.isEmpty()) {
            Toast.makeText(this, R.string.toast_enter_sql, Toast.LENGTH_SHORT).show();
            return;
        }

        resultContainer.removeAllViews();
        statusText.setText(R.string.label_executing);

        io.execute(() -> {
            long start = System.currentTimeMillis();
            try {
                boolean isSelect = sql.trim().toUpperCase(Locale.ROOT).startsWith("SELECT")
                        || sql.trim().toUpperCase(Locale.ROOT).startsWith("PRAGMA")
                        || sql.trim().toUpperCase(Locale.ROOT).startsWith("EXPLAIN");

                if (isSelect) {
                    try (Cursor cursor = db.rawQuery(sql, null)) {
                        long elapsed = System.currentTimeMillis() - start;
                        int colCount = cursor.getColumnCount();
                        int rowCount = cursor.getCount();

                        String[] columnNames = new String[colCount];
                        for (int i = 0; i < colCount; i++) {
                            columnNames[i] = cursor.getColumnName(i);
                        }

                        java.util.List<String[]> rows = new ArrayList<>();
                        while (cursor.moveToNext()) {
                            String[] cells = new String[colCount];
                            for (int i = 0; i < colCount; i++) {
                                cells[i] = cursor.isNull(i) ? null : cursor.getString(i);
                            }
                            rows.add(cells);
                        }

                        int finalRowCount = rowCount;
                        ui.post(() -> {
                            LinearLayout headerRow = new LinearLayout(this);
                            headerRow.setOrientation(LinearLayout.HORIZONTAL);
                            headerRow.setBackgroundColor(theme.toolbar);
                            headerRow.setPadding(dp(4), dp(4), dp(4), dp(4));

                            for (String name : columnNames) {
                                TextView col = new TextView(this);
                                col.setText(name);
                                col.setTextColor(theme.accent);
                                col.setTextSize(11);
                                col.setTypeface(prefs.resolveTypeface(), Typeface.BOLD);
                                col.setPadding(dp(6), dp(2), dp(6), dp(2));
                                col.setMaxLines(1);
                                headerRow.addView(col);
                            }
                            resultContainer.addView(headerRow);

                            int rowIdx = 0;
                            for (String[] cells : rows) {
                                LinearLayout dataRow = new LinearLayout(this);
                                dataRow.setOrientation(LinearLayout.HORIZONTAL);
                                dataRow.setBackgroundColor(rowIdx % 2 == 0 ? theme.consoleBg : theme.bg);
                                dataRow.setPadding(dp(4), dp(2), dp(4), dp(2));

                                for (String value : cells) {
                                    TextView cell = new TextView(this);
                                    if (value == null) {
                                        cell.setText(R.string.label_null);
                                        cell.setTextColor(theme.textDim);
                                    } else {
                                        if (value.length() > 80) {
                                            value = value.substring(0, 77) + "...";
                                        }
                                        cell.setText(value);
                                        cell.setTextColor(theme.text);
                                    }
                                    cell.setTextSize(11);
                                    cell.setTypeface(prefs.resolveTypeface());
                                    cell.setPadding(dp(6), dp(1), dp(6), dp(1));
                                    cell.setMaxLines(1);
                                    cell.setHorizontallyScrolling(true);
                                    dataRow.addView(cell);
                                }

                                resultContainer.addView(dataRow);
                                rowIdx++;
                            }

                            statusText.setText(String.format(Locale.US,
                                    "%d rows, %d cols — %d ms", finalRowCount, colCount, elapsed));
                        });
                    }
                } else {
                    // Non-select: INSERT, UPDATE, DELETE, CREATE, etc.
                    db.execSQL(sql);
                    long elapsed = System.currentTimeMillis() - start;
                    ui.post(() -> {
                        TextView msg = new TextView(this);
                        msg.setText("Query executed successfully (" + elapsed + " ms)");
                        msg.setTextColor(theme.successText);
                        msg.setTextSize(12);
                        msg.setPadding(dp(8), dp(8), dp(8), dp(8));
                        resultContainer.addView(msg);
                        statusText.setText("Done — " + elapsed + " ms");
                    });
                    // Refresh tables if schema changed
                    loadTables();
                }
            } catch (Exception e) {
                long elapsed = System.currentTimeMillis() - start;
                ui.post(() -> {
                    TextView err = new TextView(this);
                    err.setText("Error: " + e.getMessage());
                    err.setTextColor(theme.errorText);
                    err.setTextSize(12);
                    err.setTypeface(prefs.resolveTypeface());
                    err.setPadding(dp(8), dp(8), dp(8), dp(8));
                    resultContainer.addView(err);
                    statusText.setText("Error — " + elapsed + " ms");
                });
            }
        });
    }

    private TextView createButton(String text, int color) {
        TextView btn = new TextView(this);
        btn.setText(text);
        btn.setTextColor(color);
        btn.setTextSize(12);
        btn.setTypeface(prefs.resolveTypeface(), Typeface.BOLD);
        btn.setPadding(dp(12), dp(8), dp(12), dp(8));
        btn.setBackgroundResource(android.R.drawable.list_selector_background);
        return btn;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    /** @see Dialogs#rounded */
    private com.google.android.material.dialog.MaterialAlertDialogBuilder newRoundedDialog() {
        return Dialogs.rounded(this);
    }
}
