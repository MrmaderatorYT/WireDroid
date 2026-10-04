package com.ccs.wiredroid.db;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.PopupMenu;
import androidx.appcompat.widget.Toolbar;
import androidx.core.widget.CompoundButtonCompat;

import com.ccs.wiredroid.R;
import com.ccs.wiredroid.ui.Dialogs;
import com.ccs.wiredroid.util.AppPreferences;
import com.ccs.wiredroid.util.AppTheme;
import com.ccs.wiredroid.util.Colors;
import com.ccs.wiredroid.util.FullScreenHelper;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Database client: saved connections, a schema browser and a query editor for
 * SQLite files, MySQL/MariaDB and PostgreSQL servers.
 *
 * <p>Two panes live in one activity — the connection list and, once connected,
 * the workspace. All database work runs on a single-threaded executor, which
 * both keeps it off the main thread and serialises access to the one
 * {@link DbSession} a connection owns.</p>
 *
 * <p>Rows are read a page at a time ({@link QueryResult#PAGE_SIZE}) and rendered
 * into a real grid rather than concatenated into a TextView, so a careless
 * {@code SELECT *} over a large table costs one page of memory instead of the
 * whole table.</p>
 */
public class DbClientActivity extends AppCompatActivity {

    private static final int MENU_NEW = 1;
    private static final int MENU_DISCONNECT = 2;
    private static final int MENU_SCHEMA = 3;

    /** Slightly longer than the driver's own connect timeout, as a backstop. */
    private static final long CONNECT_WATCHDOG_MS =
            (DbDrivers.CONNECT_TIMEOUT_SECONDS + 8) * 1000L;

    private AppPreferences prefs;
    private AppTheme theme;
    private DbConnectionStore store;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private Toolbar toolbar;
    private FrameLayout paneHost;
    private LinearLayout connectionsPane;
    private LinearLayout workspacePane;
    private LinearLayout connectionsList;
    private TextView connectionsEmpty;
    private LinearLayout tablesList;
    private EditText sqlInput;
    private TextView summaryText;
    private TextView statusText;
    private FrameLayout resultArea;
    private HorizontalScrollView gridScroll;
    private LinearLayout gridColumn;
    private LinearLayout headerRow;
    private LinearLayout rowsBox;
    private ScrollView messageScroll;
    private TextView messageText;
    private TextView loadMoreButton;

    private DbSession session;
    private DbConnection current;
    /** Bumped on every connect attempt so a late or timed-out result is dropped. */
    private int connectToken;
    private boolean busy;

    /** Column widths in pixels, fixed after the first page so paging does not reflow. */
    private int[] columnWidths;
    private int loadedRows;

    public static void launch(Context context) {
        context.startActivity(new Intent(context, DbClientActivity.class));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        prefs = new AppPreferences(this);
        theme = AppTheme.byId(prefs.getThemeId(), prefs);
        setTheme(theme.dark ? R.style.Theme_WireDroid : R.style.Theme_WireDroid_Light);

        super.onCreate(savedInstanceState);
        store = new DbConnectionStore(this);
        try {
            setContentView(buildRoot());
        } catch (Exception e) {
            android.util.Log.e("DbClient", "buildRoot failed", e);
            LinearLayout fallback = new LinearLayout(this);
            fallback.setOrientation(LinearLayout.VERTICAL);
            fallback.setBackgroundColor(theme.bg);
            TextView err = new TextView(this);
            err.setText(getString(R.string.db_fatal_error, String.valueOf(e.getMessage())));
            err.setTextColor(theme.errorText);
            err.setPadding(dp(16), dp(16), dp(16), dp(16));
            fallback.addView(err);
            setContentView(fallback);
            return;
        }
        FullScreenHelper.enable(this);

        showConnectionsPane();
        refreshConnections();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Bump the token first: a connect still in flight will close its own
        // connection when it finds the token has moved on.
        connectToken++;
        DbSession dying = session;
        session = null;
        io.shutdownNow();
        if (dying != null) {
            // Not on the main thread, and not on the executor we just shut down.
            new Thread(dying::close, "db-close").start();
        }
    }

    @Override
    public void onBackPressed() {
        if (workspacePane != null && workspacePane.getVisibility() == View.VISIBLE) {
            disconnect();
            return;
        }
        super.onBackPressed();
    }

    // ── Layout ──────────────────────────────────────────────────────────────

    private View buildRoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(theme.bg);

        toolbar = new Toolbar(this);
        toolbar.setBackgroundColor(theme.toolbar);
        toolbar.setTitle(R.string.db_client_title);
        toolbar.setTitleTextColor(theme.text);
        toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
        toolbar.setNavigationOnClickListener(v -> onBackPressed());
        toolbar.setOnMenuItemClickListener(this::onMenuItem);
        root.addView(toolbar);

        paneHost = new FrameLayout(this);
        paneHost.addView(buildConnectionsPane());
        root.addView(paneHost, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        statusText = new TextView(this);
        statusText.setBackgroundColor(theme.consoleBg);
        statusText.setTextColor(theme.textDim);
        statusText.setTextSize(10);
        statusText.setMaxLines(3);
        statusText.setPadding(dp(8), dp(4), dp(8), dp(4));
        root.addView(statusText);

        return root;
    }

    private View buildConnectionsPane() {
        connectionsPane = new LinearLayout(this);
        connectionsPane.setOrientation(LinearLayout.VERTICAL);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(8), dp(8), dp(8), dp(8));

        connectionsEmpty = new TextView(this);
        connectionsEmpty.setText(R.string.db_no_connections);
        // Hidden until the saved list has actually been read, so an account
        // that has connections never sees "none" flash first.
        connectionsEmpty.setVisibility(View.GONE);
        connectionsEmpty.setTextColor(theme.textDim);
        connectionsEmpty.setTextSize(13);
        connectionsEmpty.setPadding(dp(8), dp(24), dp(8), dp(8));
        box.addView(connectionsEmpty);

        connectionsList = new LinearLayout(this);
        connectionsList.setOrientation(LinearLayout.VERTICAL);
        box.addView(connectionsList);

        scroll.addView(box);
        connectionsPane.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return connectionsPane;
    }

    /**
     * The workspace is built on the first connect attempt rather than with the
     * rest of the screen: it is invisible until then, and a multi-line editor
     * plus a result grid are the most expensive views here.
     */
    private void ensureWorkspacePane() {
        if (workspacePane == null) paneHost.addView(buildWorkspacePane());
    }

    private View buildWorkspacePane() {
        workspacePane = new LinearLayout(this);
        workspacePane.setOrientation(LinearLayout.VERTICAL);
        workspacePane.setVisibility(View.GONE);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.HORIZONTAL);

        // Left: schema
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setBackgroundColor(theme.consoleBg);

        TextView tablesLabel = new TextView(this);
        tablesLabel.setText(R.string.db_tables_label);
        tablesLabel.setTextColor(theme.textDim);
        tablesLabel.setTextSize(11);
        tablesLabel.setTypeface(prefs.resolveTypeface());
        tablesLabel.setPadding(dp(8), dp(6), dp(8), dp(6));
        left.addView(tablesLabel);

        ScrollView tablesScroll = new ScrollView(this);
        tablesScroll.setFillViewport(true);
        tablesList = new LinearLayout(this);
        tablesList.setOrientation(LinearLayout.VERTICAL);
        tablesScroll.addView(tablesList);
        left.addView(tablesScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        content.addView(left, new LinearLayout.LayoutParams(
                dp(190), ViewGroup.LayoutParams.MATCH_PARENT));

        View divider = new View(this);
        divider.setBackgroundColor(theme.separator);
        content.addView(divider, new LinearLayout.LayoutParams(
                1, ViewGroup.LayoutParams.MATCH_PARENT));

        // Right: SQL + results
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);

        LinearLayout sqlRow = new LinearLayout(this);
        sqlRow.setOrientation(LinearLayout.HORIZONTAL);
        sqlRow.setPadding(dp(4), dp(4), dp(4), dp(4));

        sqlInput = new EditText(this);
        sqlInput.setHint(R.string.db_sql_hint);
        sqlInput.setHintTextColor(theme.textDim);
        sqlInput.setTextColor(theme.text);
        sqlInput.setBackgroundColor(theme.consoleBg);
        sqlInput.setTypeface(prefs.resolveTypeface());
        sqlInput.setTextSize(12);
        sqlInput.setGravity(Gravity.TOP | Gravity.START);
        sqlInput.setMinLines(2);
        sqlInput.setMaxLines(5);
        sqlInput.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        sqlInput.setPadding(dp(8), dp(6), dp(8), dp(6));
        sqlInput.setContentDescription(getString(R.string.db_a11y_sql_input));
        sqlRow.addView(sqlInput, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView runButton = flatButton(getString(R.string.db_run), theme.successText);
        runButton.setContentDescription(getString(R.string.db_a11y_run));
        runButton.setOnClickListener(v -> runQuery());
        sqlRow.addView(runButton);

        TextView clearButton = flatButton(getString(R.string.db_clear), theme.textDim);
        clearButton.setContentDescription(getString(R.string.db_a11y_clear));
        clearButton.setOnClickListener(v -> sqlInput.setText(null));
        sqlRow.addView(clearButton);

        right.addView(sqlRow);

        summaryText = new TextView(this);
        summaryText.setTextColor(theme.accent);
        summaryText.setTextSize(11);
        summaryText.setTypeface(prefs.resolveTypeface());
        summaryText.setPadding(dp(8), dp(2), dp(8), dp(4));
        right.addView(summaryText);

        resultArea = new FrameLayout(this);

        gridScroll = new HorizontalScrollView(this);
        gridScroll.setFillViewport(true);
        gridColumn = new LinearLayout(this);
        gridColumn.setOrientation(LinearLayout.VERTICAL);
        headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setBackgroundColor(theme.toolbar);
        gridColumn.addView(headerRow);
        ScrollView rowsScroll = new ScrollView(this);
        rowsBox = new LinearLayout(this);
        rowsBox.setOrientation(LinearLayout.VERTICAL);
        rowsScroll.addView(rowsBox);
        gridColumn.addView(rowsScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 1f));
        gridScroll.addView(gridColumn, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        resultArea.addView(gridScroll);

        messageScroll = new ScrollView(this);
        messageScroll.setVisibility(View.GONE);
        messageText = new TextView(this);
        messageText.setTextSize(12);
        messageText.setTypeface(prefs.resolveTypeface());
        messageText.setTextIsSelectable(true);
        messageText.setPadding(dp(10), dp(10), dp(10), dp(10));
        messageScroll.addView(messageText);
        resultArea.addView(messageScroll);

        right.addView(resultArea, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        loadMoreButton = flatButton(getString(R.string.db_load_more, QueryResult.PAGE_SIZE),
                theme.accent);
        loadMoreButton.setGravity(Gravity.CENTER);
        loadMoreButton.setVisibility(View.GONE);
        loadMoreButton.setOnClickListener(v -> loadMore());
        right.addView(loadMoreButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        content.addView(right, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        workspacePane.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return workspacePane;
    }

    // ── Panes and menu ──────────────────────────────────────────────────────

    private void showConnectionsPane() {
        connectionsPane.setVisibility(View.VISIBLE);
        if (workspacePane != null) workspacePane.setVisibility(View.GONE);
        toolbar.setTitle(R.string.db_client_title);
        Menu menu = toolbar.getMenu();
        menu.clear();
        menu.add(0, MENU_NEW, 0, R.string.db_menu_new)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
    }

    private void showWorkspacePane() {
        ensureWorkspacePane();
        connectionsPane.setVisibility(View.GONE);
        workspacePane.setVisibility(View.VISIBLE);
        toolbar.setTitle(current == null ? getString(R.string.db_client_title) : current.name);
        Menu menu = toolbar.getMenu();
        menu.clear();
        menu.add(0, MENU_SCHEMA, 0, R.string.db_menu_schema)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        menu.add(0, MENU_DISCONNECT, 1, R.string.db_menu_disconnect)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
    }

    private boolean onMenuItem(MenuItem item) {
        if (item.getItemId() == MENU_NEW) {
            showEditor(null);
            return true;
        }
        if (item.getItemId() == MENU_SCHEMA) {
            if (session == null) {
                Toast.makeText(this, R.string.schema_no_connection, Toast.LENGTH_SHORT).show();
            } else {
                SchemaDiagramActivity.show(this, session);
            }
            return true;
        }
        if (item.getItemId() == MENU_DISCONNECT) {
            disconnect();
            return true;
        }
        return false;
    }

    // ── Connection list ─────────────────────────────────────────────────────

    private void refreshConnections() {
        // Reading the list decrypts every remembered password through the
        // Keystore, which is far too slow to sit on the main thread. The rows
        // already on screen stay put until the new ones are ready.
        io.execute(() -> {
            List<DbConnection> all = store.load();
            ui.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                connectionsList.removeAllViews();
                connectionsEmpty.setVisibility(all.isEmpty() ? View.VISIBLE : View.GONE);
                for (DbConnection c : all) {
                    connectionsList.addView(buildConnectionRow(c));
                }
            });
        });
    }

    private View buildConnectionRow(DbConnection c) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundColor(Colors.blend(theme.bg, theme.text, 0.05f));
        row.setPadding(dp(12), dp(10), dp(6), dp(10));

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);

        TextView name = new TextView(this);
        name.setText(c.name);
        name.setTextColor(theme.accent);
        name.setTextSize(15);
        name.setTypeface(prefs.resolveTypeface(), Typeface.BOLD);
        labels.addView(name);

        TextView sub = new TextView(this);
        sub.setText(describeConnection(c));
        sub.setTextColor(theme.textDim);
        sub.setTextSize(11);
        sub.setTypeface(prefs.resolveTypeface());
        labels.addView(sub);

        row.addView(labels, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView overflow = flatButton(getString(R.string.db_row_menu), theme.text);
        overflow.setContentDescription(getString(R.string.db_a11y_row_menu, c.name));
        overflow.setOnClickListener(v -> showRowMenu(v, c));
        row.addView(overflow);

        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(6);
        wrapper.setLayoutParams(lp);
        wrapper.addView(row);

        labels.setContentDescription(getString(R.string.db_a11y_connection, c.name));
        labels.setBackgroundResource(android.R.drawable.list_selector_background);
        labels.setOnClickListener(v -> beginConnect(c));
        return wrapper;
    }

    private String describeConnection(DbConnection c) {
        String driver = c.driver.label(this);
        if (c.driver.isFile()) {
            return getString(R.string.db_row_subtitle_file, driver, c.database);
        }
        if (c.user == null || c.user.isEmpty()) {
            return getString(R.string.db_row_subtitle_remote, driver, c.host, c.port, c.database);
        }
        return getString(R.string.db_row_subtitle_remote_user,
                driver, c.user, c.host, c.port, c.database);
    }

    private void showRowMenu(View anchor, DbConnection c) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, R.string.db_action_connect);
        menu.getMenu().add(0, 2, 1, R.string.db_action_edit);
        menu.getMenu().add(0, 3, 2, R.string.db_action_duplicate);
        menu.getMenu().add(0, 4, 3, R.string.db_action_delete);
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    beginConnect(c);
                    return true;
                case 2:
                    showEditor(c);
                    return true;
                case 3:
                    store.upsert(c.duplicate(getString(R.string.db_copy_suffix, c.name)));
                    refreshConnections();
                    return true;
                case 4:
                    confirmDelete(c);
                    return true;
                default:
                    return false;
            }
        });
        menu.show();
    }

    private void confirmDelete(DbConnection c) {
        Dialogs.rounded(this)
                .setTitle(R.string.db_delete_title)
                .setMessage(getString(R.string.db_delete_message, c.name))
                .setNegativeButton(R.string.db_action_cancel, null)
                .setPositiveButton(R.string.db_action_delete, (d, w) -> {
                    store.delete(c.id);
                    refreshConnections();
                })
                .show();
    }

    // ── Connection editor ───────────────────────────────────────────────────

    private void showEditor(DbConnection existing) {
        boolean isNew = existing == null;
        DbConnection draft = isNew ? new DbConnection() : existing.copy();
        final DbDrivers.Kind[] kind = {draft.driver};

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(8), dp(20), dp(8));

        EditText nameField = field(form, R.string.db_field_name, draft.name,
                InputType.TYPE_CLASS_TEXT);

        addLabel(form, R.string.db_field_driver);
        LinearLayout driverRow = new LinearLayout(this);
        driverRow.setOrientation(LinearLayout.HORIZONTAL);
        form.addView(driverRow);

        // Server-only fields collapse when SQLite is selected.
        LinearLayout remoteBox = new LinearLayout(this);
        remoteBox.setOrientation(LinearLayout.VERTICAL);
        form.addView(remoteBox);

        LinearLayout fileBox = new LinearLayout(this);
        fileBox.setOrientation(LinearLayout.VERTICAL);
        form.addView(fileBox);

        EditText hostField = field(remoteBox, R.string.db_field_host, draft.host,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        EditText portField = field(remoteBox, R.string.db_field_port,
                String.valueOf(draft.port), InputType.TYPE_CLASS_NUMBER);
        EditText databaseField = field(remoteBox, R.string.db_field_database, draft.database,
                InputType.TYPE_CLASS_TEXT);
        EditText userField = field(remoteBox, R.string.db_field_user, draft.user,
                InputType.TYPE_CLASS_TEXT);
        EditText passwordField = field(remoteBox, R.string.db_field_password, draft.password,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        CheckBox rememberBox = checkBox(remoteBox, R.string.db_remember_password,
                draft.savePassword);
        TextView rememberWarning = addHint(remoteBox, R.string.db_remember_password_warning);
        rememberWarning.setTextColor(theme.errorText);

        CheckBox sslBox = checkBox(remoteBox, R.string.db_field_ssl, draft.useSsl);
        addHint(remoteBox, R.string.db_ssl_warning);

        EditText extraField = field(remoteBox, R.string.db_field_extra, draft.extraParams,
                InputType.TYPE_CLASS_TEXT);
        extraField.setHint(R.string.db_field_extra_hint);
        extraField.setHintTextColor(theme.textDim);

        addLabel(fileBox, R.string.db_field_file);
        LinearLayout fileRow = new LinearLayout(this);
        fileRow.setOrientation(LinearLayout.HORIZONTAL);
        EditText fileField = styledEdit(draft.driver.isFile() ? draft.database : "",
                InputType.TYPE_CLASS_TEXT);
        fileRow.addView(fileField, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView browse = flatButton(getString(R.string.db_action_browse), theme.accent);
        browse.setOnClickListener(v -> showFileBrowser(startDirectory(fileField.getText().toString()),
                picked -> fileField.setText(picked.getAbsolutePath())));
        fileRow.addView(browse);
        fileBox.addView(fileRow);

        Runnable applyKind = () -> {
            boolean file = kind[0].isFile();
            remoteBox.setVisibility(file ? View.GONE : View.VISIBLE);
            fileBox.setVisibility(file ? View.VISIBLE : View.GONE);
        };

        List<TextView> driverChips = new ArrayList<>();
        for (DbDrivers.Kind k : DbDrivers.Kind.values()) {
            TextView chip = flatButton(k.label(this), theme.text);
            chip.setOnClickListener(v -> {
                if (kind[0] != k && !k.isFile()
                        && portField.getText().toString().trim()
                        .equals(String.valueOf(kind[0].defaultPort))) {
                    portField.setText(String.valueOf(k.defaultPort));
                }
                kind[0] = k;
                for (TextView other : driverChips) {
                    boolean on = other.getTag() == k;
                    other.setTextColor(on ? theme.accent : theme.textDim);
                    other.setBackgroundColor(on
                            ? Colors.blend(theme.bg, theme.accent, 0.20f)
                            : Colors.blend(theme.bg, theme.text, 0.06f));
                }
                applyKind.run();
            });
            chip.setTag(k);
            boolean on = k == kind[0];
            chip.setTextColor(on ? theme.accent : theme.textDim);
            chip.setBackgroundColor(on
                    ? Colors.blend(theme.bg, theme.accent, 0.20f)
                    : Colors.blend(theme.bg, theme.text, 0.06f));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.rightMargin = dp(4);
            driverRow.addView(chip, lp);
            driverChips.add(chip);
        }
        applyKind.run();

        ScrollView scroll = new ScrollView(this);
        scroll.addView(form);
        FrameLayout wrap = new FrameLayout(this);
        wrap.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(420)));

        AlertDialog dialog = Dialogs.rounded(this)
                .setTitle(isNew ? R.string.db_editor_new_title : R.string.db_editor_edit_title)
                .setView(wrap)
                .setNegativeButton(R.string.db_action_cancel, null)
                .setPositiveButton(R.string.db_action_save, null)
                .create();
        dialog.show();

        // Wired after show() so a validation failure does not dismiss the dialog.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            draft.name = nameField.getText().toString().trim();
            draft.driver = kind[0];
            if (draft.name.isEmpty()) {
                toast(getString(R.string.db_err_name_required));
                return;
            }
            if (draft.driver.isFile()) {
                draft.database = fileField.getText().toString().trim();
                if (draft.database.isEmpty()) {
                    toast(getString(R.string.db_err_file_required));
                    return;
                }
                draft.host = "";
                draft.user = "";
                draft.password = "";
                draft.savePassword = false;
                draft.useSsl = false;
                draft.port = 0;
            } else {
                draft.host = hostField.getText().toString().trim();
                if (draft.host.isEmpty()) {
                    toast(getString(R.string.db_err_host_required));
                    return;
                }
                int port;
                try {
                    port = Integer.parseInt(portField.getText().toString().trim());
                } catch (NumberFormatException e) {
                    port = -1;
                }
                if (port < 1 || port > 65535) {
                    toast(getString(R.string.db_err_port_invalid));
                    return;
                }
                draft.port = port;
                draft.database = databaseField.getText().toString().trim();
                if (draft.database.isEmpty()) {
                    toast(getString(R.string.db_err_database_required));
                    return;
                }
                draft.user = userField.getText().toString();
                draft.savePassword = rememberBox.isChecked();
                // Not remembering means nothing to remember: the field is
                // dropped here rather than left in the object to leak into JSON.
                draft.password = draft.savePassword
                        ? passwordField.getText().toString() : "";
                draft.useSsl = sslBox.isChecked();
                draft.extraParams = extraField.getText().toString().trim();
            }
            store.upsert(draft);
            refreshConnections();
            dialog.dismiss();
        });
    }

    private File startDirectory(String current) {
        if (current != null && !current.trim().isEmpty()) {
            File f = new File(current.trim());
            File parent = f.isDirectory() ? f : f.getParentFile();
            if (parent != null && parent.isDirectory()) return parent;
        }
        File external = Environment.getExternalStorageDirectory();
        if (external != null && external.canRead()) return external;
        return getFilesDir();
    }

    // ── File browser ────────────────────────────────────────────────────────

    private void showFileBrowser(File start, Consumer<File> onPick) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);
        FrameLayout wrap = new FrameLayout(this);
        wrap.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(380)));

        AlertDialog dialog = Dialogs.rounded(this)
                .setTitle(R.string.db_file_picker_title)
                .setView(wrap)
                .setNegativeButton(R.string.db_action_cancel, null)
                .create();
        populateBrowser(box, start, dialog, onPick);
        dialog.show();
    }

    private void populateBrowser(LinearLayout box, File dir, AlertDialog dialog,
                                 Consumer<File> onPick) {
        box.removeAllViews();
        dialog.setTitle(dir.getAbsolutePath());

        File parent = dir.getParentFile();
        if (parent != null && parent.canRead()) {
            box.addView(browserEntry(getString(R.string.db_file_picker_up), theme.accent,
                    v -> populateBrowser(box, parent, dialog, onPick)));
        }

        File[] children = dir.listFiles();
        if (children == null) {
            box.addView(browserEntry(getString(R.string.db_file_picker_unreadable),
                    theme.errorText, null));
            return;
        }
        Arrays.sort(children, Comparator
                .comparing((File f) -> !f.isDirectory())
                .thenComparing(f -> f.getName().toLowerCase(Locale.ROOT)));
        if (children.length == 0) {
            box.addView(browserEntry(getString(R.string.db_file_picker_empty),
                    theme.textDim, null));
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                box.addView(browserEntry(child.getName(), theme.accent,
                        v -> populateBrowser(box, child, dialog, onPick)));
            } else {
                box.addView(browserEntry(child.getName(), theme.text, v -> {
                    onPick.accept(child);
                    dialog.dismiss();
                }));
            }
        }
    }

    private TextView browserEntry(String label, int color, View.OnClickListener onClick) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(color);
        tv.setTextSize(13);
        tv.setTypeface(prefs.resolveTypeface());
        tv.setPadding(dp(10), dp(10), dp(10), dp(10));
        tv.setMaxLines(1);
        if (onClick != null) {
            tv.setBackgroundResource(android.R.drawable.list_selector_background);
            tv.setOnClickListener(onClick);
        }
        return tv;
    }

    // ── Connect / disconnect ────────────────────────────────────────────────

    private void beginConnect(DbConnection c) {
        if (c.driver.isFile() || c.savePassword) {
            connect(c, c.password);
            return;
        }
        promptPassword(c);
    }

    private void promptPassword(DbConnection c) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(8));
        EditText input = styledEdit("", InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(input);

        Dialogs.rounded(this)
                .setTitle(R.string.db_password_prompt_title)
                .setMessage(getString(R.string.db_password_prompt_message, c.name))
                .setView(box)
                .setNegativeButton(R.string.db_action_cancel, null)
                .setPositiveButton(R.string.db_action_connect,
                        (d, w) -> connect(c, input.getText().toString()))
                .show();
    }

    private void connect(DbConnection c, String password) {
        if (session != null) closeSessionAsync(session);
        session = null;
        current = c;
        final int token = ++connectToken;
        setStatus(getString(R.string.db_connecting, c.name));

        io.execute(() -> {
            DbSession opened = null;
            String error = null;
            try {
                opened = DbSession.open(this, c, password);
            } catch (Throwable t) {
                // Throwable, not Exception: a driver that touches a class
                // Android lacks throws NoClassDefFoundError, and that is
                // exactly the case the user needs explained.
                error = DbDrivers.describe(this, c, t);
            }
            final DbSession result = opened;
            final String message = error;
            ui.post(() -> {
                if (token != connectToken) {
                    // Timed out or superseded; do not leak the socket.
                    closeSessionAsync(result);
                    return;
                }
                if (result == null) {
                    setStatus(message);
                    showMessage(message, theme.errorText);
                    return;
                }
                session = result;
                showWorkspacePane();
                setStatus(getString(result.isReadOnly()
                                ? R.string.db_connected_readonly : R.string.db_connected,
                        describeConnection(c)));
                loadTables();
            });
        });

        ui.postDelayed(() -> {
            if (token == connectToken && session == null) {
                connectToken++;
                String message = getString(R.string.db_err_timeout,
                        DbDrivers.CONNECT_TIMEOUT_SECONDS, c.name);
                setStatus(message);
                showMessage(message, theme.errorText);
            }
        }, CONNECT_WATCHDOG_MS);
    }

    private void disconnect() {
        connectToken++;
        // Drop any session handed to the schema screen but not yet collected,
        // so it can never draw from a connection that is being closed.
        SchemaDiagramActivity.forget();
        closeSessionAsync(session);
        session = null;
        current = null;
        tablesList.removeAllViews();
        clearGrid();
        summaryText.setText(null);
        sqlInput.setText(null);
        showConnectionsPane();
        refreshConnections();
        setStatus(getString(R.string.db_disconnected));
    }

    private void closeSessionAsync(DbSession dying) {
        if (dying == null) return;
        // A close can block on the socket, so it never runs on the main thread
        // and never on the executor a query may still be occupying.
        new Thread(dying::close, "db-close").start();
    }

    // ── Schema ──────────────────────────────────────────────────────────────

    private void loadTables() {
        final DbSession s = session;
        if (s == null) return;
        tablesList.removeAllViews();
        setStatus(getString(R.string.db_loading_schema));
        io.execute(() -> {
            List<DbSession.TableRef> tables;
            String error = null;
            try {
                tables = s.listTables();
            } catch (Throwable t) {
                tables = new ArrayList<>();
                error = DbDrivers.describeSql(this, t);
            }
            final List<DbSession.TableRef> result = tables;
            final String message = error;
            ui.post(() -> {
                if (s != session) return;
                if (message != null) {
                    setStatus(message);
                    showMessage(message, theme.errorText);
                    return;
                }
                if (result.isEmpty()) {
                    TextView empty = new TextView(this);
                    empty.setText(R.string.db_no_tables);
                    empty.setTextColor(theme.textDim);
                    empty.setTextSize(12);
                    empty.setPadding(dp(8), dp(12), dp(8), dp(8));
                    tablesList.addView(empty);
                } else {
                    for (DbSession.TableRef t : result) {
                        tablesList.addView(buildTableRow(t));
                    }
                }
                setStatus(getString(R.string.db_tables_count, result.size()));
            });
        });
    }

    private View buildTableRow(DbSession.TableRef table) {
        TextView tv = new TextView(this);
        tv.setText(table.display());
        tv.setTextColor(table.isView() ? theme.textDim : theme.accent);
        tv.setTextSize(12);
        tv.setTypeface(prefs.resolveTypeface());
        tv.setPadding(dp(8), dp(7), dp(8), dp(7));
        tv.setMaxLines(1);
        tv.setBackgroundResource(android.R.drawable.list_selector_background);
        tv.setContentDescription(getString(R.string.db_a11y_table, table.display()));
        tv.setOnClickListener(v -> selectTable(table));
        return tv;
    }

    /**
     * Shows the table's columns and drops a starter SELECT into the editor. The
     * statement is not run — reading a table's shape should not also read its
     * contents.
     */
    private void selectTable(DbSession.TableRef table) {
        final DbSession s = session;
        if (s == null) return;
        sqlInput.setText(getString(R.string.db_select_template, s.qualify(table)));
        setStatus(getString(R.string.db_columns_of, table.display()));
        io.execute(() -> {
            List<DbSession.ColumnInfo> columns;
            String error = null;
            try {
                columns = s.listColumns(table);
            } catch (Throwable t) {
                columns = new ArrayList<>();
                error = DbDrivers.describeSql(this, t);
            }
            final List<DbSession.ColumnInfo> result = columns;
            final String message = error;
            ui.post(() -> {
                if (s != session) return;
                if (message != null) {
                    showMessage(message, theme.errorText);
                    return;
                }
                if (result.isEmpty()) {
                    showMessage(getString(R.string.db_no_columns, table.display()),
                            theme.textDim);
                    return;
                }
                List<String> headers = Arrays.asList(
                        getString(R.string.db_col_name),
                        getString(R.string.db_col_type),
                        getString(R.string.db_col_null),
                        getString(R.string.db_col_key));
                List<String[]> rows = new ArrayList<>(result.size());
                for (DbSession.ColumnInfo ci : result) {
                    rows.add(new String[]{
                            ci.name,
                            ci.type,
                            getString(ci.nullable ? R.string.db_col_yes : R.string.db_col_no),
                            getString(ci.primaryKey ? R.string.db_col_pk : R.string.db_col_blank)});
                }
                clearGrid();
                renderGrid(headers, rows, true);
                summaryText.setText(getString(R.string.db_columns_of, table.display()));
                loadMoreButton.setVisibility(View.GONE);
            });
        });
    }

    // ── Queries ─────────────────────────────────────────────────────────────

    private void runQuery() {
        final DbSession s = session;
        if (s == null) {
            toast(getString(R.string.db_err_no_session));
            return;
        }
        if (busy) return;
        final String sql = sqlInput.getText().toString().trim();
        if (sql.isEmpty()) {
            toast(getString(R.string.db_err_sql_empty));
            return;
        }
        busy = true;
        clearGrid();
        summaryText.setText(R.string.db_running);
        setStatus(getString(R.string.db_running));

        io.execute(() -> {
            QueryResult result = null;
            String error = null;
            try {
                result = s.execute(sql, QueryResult.PAGE_SIZE);
            } catch (Throwable t) {
                error = DbDrivers.describeSql(this, t);
            }
            final QueryResult r = result;
            final String message = error;
            ui.post(() -> {
                busy = false;
                if (s != session) return;
                if (message != null) {
                    summaryText.setText(null);
                    setStatus(message);
                    showMessage(message, theme.errorText);
                    return;
                }
                if (!r.grid) {
                    String text = r.updateCount >= 0
                            ? getString(R.string.db_rows_affected, r.updateCount, r.durationMs)
                            : getString(R.string.db_statement_ok, r.durationMs);
                    summaryText.setText(text);
                    setStatus(text);
                    showMessage(text, theme.successText);
                    // DDL may have added or dropped something.
                    loadTables();
                    return;
                }
                renderGrid(r.columns, r.rows, true);
                applySummary(r);
            });
        });
    }

    private void loadMore() {
        final DbSession s = session;
        if (s == null || busy) return;
        busy = true;
        loadMoreButton.setText(R.string.db_loading_more);
        io.execute(() -> {
            QueryResult result = null;
            String error = null;
            try {
                result = s.fetchMore(QueryResult.PAGE_SIZE);
            } catch (Throwable t) {
                error = DbDrivers.describeSql(this, t);
            }
            final QueryResult r = result;
            final String message = error;
            ui.post(() -> {
                busy = false;
                loadMoreButton.setText(getString(R.string.db_load_more, QueryResult.PAGE_SIZE));
                if (s != session) return;
                if (message != null) {
                    setStatus(message);
                    loadMoreButton.setVisibility(View.GONE);
                    return;
                }
                renderGrid(r.columns, r.rows, false);
                applySummary(r);
            });
        });
    }

    private void applySummary(QueryResult r) {
        String text;
        if (r.more) {
            String total = r.totalRows >= 0 ? String.valueOf(r.totalRows)
                    : getString(R.string.db_unknown_count);
            text = getString(R.string.db_result_partial, loadedRows, total,
                    r.columns.size(), r.durationMs);
        } else {
            text = getString(R.string.db_result_complete, loadedRows,
                    r.columns.size(), r.durationMs);
        }
        summaryText.setText(text);
        setStatus(text);
        loadMoreButton.setVisibility(r.more ? View.VISIBLE : View.GONE);
    }

    // ── Result grid ─────────────────────────────────────────────────────────

    private void clearGrid() {
        headerRow.removeAllViews();
        rowsBox.removeAllViews();
        columnWidths = null;
        loadedRows = 0;
        loadMoreButton.setVisibility(View.GONE);
        gridScroll.setVisibility(View.VISIBLE);
        messageScroll.setVisibility(View.GONE);
    }

    private void showMessage(String text, int color) {
        // A failed connect reports through here while the connections list is
        // still the visible pane, so the workspace views may not exist yet.
        ensureWorkspacePane();
        headerRow.removeAllViews();
        rowsBox.removeAllViews();
        loadMoreButton.setVisibility(View.GONE);
        gridScroll.setVisibility(View.GONE);
        messageScroll.setVisibility(View.VISIBLE);
        messageText.setText(text);
        messageText.setTextColor(color);
    }

    /**
     * Draws a page of rows. {@code fresh} rebuilds the header and fixes the
     * column widths; a later page appends rows at those same widths so paging
     * does not shuffle the grid under the reader.
     */
    private void renderGrid(List<String> columns, List<String[]> rows, boolean fresh) {
        gridScroll.setVisibility(View.VISIBLE);
        messageScroll.setVisibility(View.GONE);

        if (fresh) {
            headerRow.removeAllViews();
            rowsBox.removeAllViews();
            loadedRows = 0;
            columnWidths = measureColumns(columns, rows);
            for (int i = 0; i < columns.size(); i++) {
                TextView cell = gridCell(columns.get(i), theme.accent, columnWidths[i]);
                cell.setTypeface(prefs.resolveTypeface(), Typeface.BOLD);
                headerRow.addView(cell);
            }
        }
        if (columnWidths == null) return;

        for (String[] row : rows) {
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setBackgroundColor(loadedRows % 2 == 0 ? theme.bg : theme.consoleBg);
            for (int i = 0; i < columnWidths.length; i++) {
                final String value = i < row.length ? row[i] : null;
                TextView cell;
                if (value == null) {
                    // A SQL NULL and an empty string are different values and
                    // are drawn differently: [NULL] dimmed and italic, versus a
                    // genuinely blank cell.
                    cell = gridCell(getString(R.string.db_null), theme.textDim, columnWidths[i]);
                    cell.setTypeface(prefs.resolveTypeface(), Typeface.ITALIC);
                } else {
                    cell = gridCell(value, theme.text, columnWidths[i]);
                    final String column = i < headerRow.getChildCount()
                            ? ((TextView) headerRow.getChildAt(i)).getText().toString() : "";
                    cell.setOnClickListener(v -> showCell(column, value));
                }
                line.addView(cell);
            }
            rowsBox.addView(line);
            loadedRows++;
        }
    }

    private TextView gridCell(String text, int color, int width) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(color);
        tv.setTextSize(11);
        tv.setTypeface(prefs.resolveTypeface());
        tv.setPadding(dp(6), dp(3), dp(6), dp(3));
        tv.setMaxLines(1);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tv.setWidth(width);
        return tv;
    }

    /** Sizes each column from its header and its first page of values. */
    private int[] measureColumns(List<String> columns, List<String[]> rows) {
        Paint paint = new Paint();
        paint.setTypeface(prefs.resolveTypeface());
        paint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11,
                getResources().getDisplayMetrics()));
        float unit = paint.measureText("0");
        if (unit <= 0) unit = dp(7);

        int nullLength = getString(R.string.db_null).length();
        int[] widths = new int[columns.size()];
        for (int i = 0; i < columns.size(); i++) {
            int chars = columns.get(i) == null ? 4 : columns.get(i).length();
            for (String[] row : rows) {
                if (i >= row.length) continue;
                int len = row[i] == null ? nullLength : row[i].length();
                if (len > chars) chars = len;
                if (chars >= 32) break;
            }
            chars = Math.max(4, Math.min(32, chars));
            widths[i] = (int) (chars * unit) + dp(14);
        }
        return widths;
    }

    private void showCell(String column, String value) {
        Dialogs.rounded(this)
                .setTitle(column)
                .setMessage(value)
                .setNegativeButton(R.string.db_action_close, null)
                .setPositiveButton(R.string.db_action_copy, (d, w) -> {
                    ClipboardManager cm =
                            (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newPlainText(column, value));
                        toast(getString(R.string.db_copied));
                    }
                })
                .show();
    }

    // ── Small view helpers ──────────────────────────────────────────────────

    private TextView flatButton(String text, int color) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(color);
        tv.setTextSize(12);
        tv.setTypeface(prefs.resolveTypeface(), Typeface.BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(12), dp(9), dp(12), dp(9));
        tv.setBackgroundResource(android.R.drawable.list_selector_background);
        return tv;
    }

    private void addLabel(LinearLayout parent, int labelRes) {
        TextView tv = new TextView(this);
        tv.setText(labelRes);
        tv.setTextColor(theme.textDim);
        tv.setTextSize(11);
        tv.setPadding(0, dp(10), 0, dp(2));
        parent.addView(tv);
    }

    private TextView addHint(LinearLayout parent, int textRes) {
        TextView tv = new TextView(this);
        tv.setText(textRes);
        tv.setTextColor(theme.textDim);
        tv.setTextSize(10);
        tv.setPadding(dp(2), dp(2), dp(2), dp(6));
        parent.addView(tv);
        return tv;
    }

    private EditText field(LinearLayout parent, int labelRes, String value, int inputType) {
        addLabel(parent, labelRes);
        EditText edit = styledEdit(value, inputType);
        parent.addView(edit);
        return edit;
    }

    private EditText styledEdit(String value, int inputType) {
        EditText edit = new EditText(this);
        edit.setText(value == null ? "" : value);
        edit.setTextColor(theme.text);
        edit.setHintTextColor(theme.textDim);
        edit.setTextSize(13);
        edit.setSingleLine(true);
        edit.setInputType(inputType);
        edit.setBackgroundColor(Colors.blend(theme.bg, theme.text, 0.08f));
        edit.setPadding(dp(8), dp(8), dp(8), dp(8));
        return edit;
    }

    private CheckBox checkBox(LinearLayout parent, int labelRes, boolean checked) {
        CheckBox cb = new CheckBox(this);
        cb.setText(labelRes);
        cb.setTextColor(theme.text);
        cb.setTextSize(12);
        cb.setChecked(checked);
        CompoundButtonCompat.setButtonTintList(cb, ColorStateList.valueOf(theme.accent));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        parent.addView(cb, lp);
        return cb;
    }

    private void setStatus(String text) {
        if (statusText != null) statusText.setText(text);
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
