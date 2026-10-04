package com.ccs.wiredroid.db;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.ccs.wiredroid.R;
import com.ccs.wiredroid.ui.UmlDiagramView;
import com.ccs.wiredroid.util.AppPreferences;
import com.ccs.wiredroid.util.AppTheme;
import com.ccs.wiredroid.util.FullScreenHelper;

/**
 * The connected database drawn as a graph of tables.
 *
 * <p>Takes no connection details of its own — it reads the session the client
 * already has open, so it can never show a schema the user is not looking at.
 * The session belongs to {@link DbClientActivity} and is not closed here.</p>
 */
public class SchemaDiagramActivity extends AppCompatActivity {

    /**
     * Handed over rather than passed through an Intent: a live JDBC session is
     * not serialisable, and reconnecting to draw a picture would ask for the
     * password again.
     */
    private static DbSession pending;

    public static void show(android.content.Context context, DbSession session) {
        pending = session;
        context.startActivity(new android.content.Intent(context, SchemaDiagramActivity.class));
    }

    private AppTheme theme;
    private UmlDiagramView canvas;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        AppPreferences prefs = new AppPreferences(this);
        theme = AppTheme.byId(prefs.getThemeId(), prefs);
        setTheme(theme.dark ? R.style.Theme_WireDroid : R.style.Theme_WireDroid_Light);
        super.onCreate(savedInstanceState);

        DbSession session = pending;
        pending = null;
        if (session == null) {
            Toast.makeText(this, R.string.schema_no_connection, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(theme.bg);

        Toolbar toolbar = new Toolbar(this);
        toolbar.setTitle(R.string.schema_title);
        toolbar.setBackgroundColor(theme.toolbar);
        toolbar.setTitleTextColor(theme.text);
        toolbar.setNavigationIcon(android.R.drawable.ic_menu_close_clear_cancel);
        toolbar.setNavigationOnClickListener(v -> finish());
        root.addView(toolbar);

        TextView note = new TextView(this);
        note.setTextColor(theme.textDim);
        note.setTextSize(12f);
        note.setPadding(dp(16), dp(8), dp(16), dp(8));
        root.addView(note);

        canvas = new UmlDiagramView(this);
        canvas.setTheme(theme);
        root.addView(canvas, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setBackgroundColor(theme.statusBar);
        Button fit = new Button(this);
        fit.setText(R.string.schema_fit);
        fit.setOnClickListener(v -> canvas.fit());
        controls.addView(fit);
        root.addView(controls);

        setContentView(root);
        FullScreenHelper.enable(this);

        // Reading a schema means a round trip per table, so it happens off the
        // main thread even for SQLite.
        note.setText(R.string.schema_loading);
        new Thread(() -> {
            DbSchemaGraph.Result result = null;
            String error = null;
            try {
                result = DbSchemaGraph.build(session);
            } catch (Throwable t) {
                error = String.valueOf(t.getMessage());
            }
            final DbSchemaGraph.Result ready = result;
            final String failed = error;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (ready == null) {
                    note.setText(getString(R.string.schema_failed, failed));
                    return;
                }
                int tables = ready.graph.types().size();
                if (tables == 0) {
                    note.setText(R.string.schema_empty);
                    return;
                }
                canvas.setGraph(ready.graph);
                StringBuilder summary = new StringBuilder(
                        getResources().getQuantityString(R.plurals.schema_tables, tables, tables));
                summary.append(" · ").append(getResources()
                        .getQuantityString(R.plurals.schema_links, ready.links, ready.links));
                if (ready.omitted > 0) {
                    summary.append(" · ").append(getString(R.string.schema_omitted, ready.omitted));
                }
                if (ready.links == 0) {
                    summary.append('\n').append(getString(R.string.schema_no_keys));
                }
                note.setText(summary.toString());
            });
        }, "schema-diagram").start();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // The session outlives this screen; only the reference is dropped.
        canvas = null;
    }

    /** Cleared when the client disconnects, so a stale session is never drawn. */
    public static void forget() {
        pending = null;
    }
}
