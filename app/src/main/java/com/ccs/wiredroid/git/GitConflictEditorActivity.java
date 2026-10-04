package com.ccs.wiredroid.git;

import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.ccs.wiredroid.R;
import com.ccs.wiredroid.util.AppPreferences;
import com.ccs.wiredroid.util.AppTheme;
import com.ccs.wiredroid.util.FullScreenHelper;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Three-way conflict editor: Current | Incoming | editable Result. */
public final class GitConflictEditorActivity extends AppCompatActivity {
    public static final String EXTRA_PROJECT_DIR = "project_dir";
    public static final String EXTRA_PATH = "path";

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private AppTheme theme;
    private File projectDir;
    private String path;
    private EditText current;
    private EditText incoming;
    private EditText result;

    @Override protected void onCreate(Bundle state) {
        AppPreferences preferences = new AppPreferences(this);
        theme = AppTheme.byId(preferences.getThemeId(), preferences);
        setTheme(theme.dark ? R.style.Theme_WireDroid : R.style.Theme_WireDroid_Light);
        super.onCreate(state);
        String root = getIntent().getStringExtra(EXTRA_PROJECT_DIR);
        path = getIntent().getStringExtra(EXTRA_PATH);
        if (root == null || path == null) { finish(); return; }
        projectDir = new File(root);
        setContentView(buildUi(preferences.resolveTypeface()));
        FullScreenHelper.enable(this);
        load();
    }

    private View buildUi(Typeface mono) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(theme.bg);
        Toolbar bar = new Toolbar(this);
        bar.setTitle(getString(R.string.git_conflict_title, path));
        bar.setTitleTextColor(theme.text);
        bar.setBackgroundColor(theme.toolbar);
        bar.setNavigationIcon(R.drawable.ic_back);
        bar.setNavigationOnClickListener(v -> finish());
        root.addView(bar, new LinearLayout.LayoutParams(-1, dp(48)));

        HorizontalScrollView horizontal = new HorizontalScrollView(this);
        LinearLayout columns = new LinearLayout(this);
        columns.setOrientation(LinearLayout.HORIZONTAL);
        current = column(columns, R.string.git_conflict_current, false, mono);
        incoming = column(columns, R.string.git_conflict_incoming, false, mono);
        result = column(columns, R.string.git_conflict_result, true, mono);
        horizontal.addView(columns, new ViewGroup.LayoutParams(dp(1080), -1));
        root.addView(horizontal, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.END);
        actions.setPadding(dp(8), dp(6), dp(8), dp(6));
        actions.addView(button(R.string.git_conflict_use_current, v -> result.setText(current.getText())));
        actions.addView(button(R.string.git_conflict_use_incoming, v -> result.setText(incoming.getText())));
        actions.addView(button(R.string.git_conflict_save_resolved, v -> save()));
        root.addView(actions);
        return root;
    }

    private EditText column(LinearLayout parent, int title, boolean editable, Typeface mono) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(4), dp(4), dp(4), dp(4));
        TextView label = new TextView(this);
        label.setText(title);
        label.setTextColor(editable ? theme.accent : theme.textDim);
        label.setGravity(Gravity.CENTER);
        label.setPadding(0, dp(6), 0, dp(6));
        box.addView(label);
        EditText editor = new EditText(this);
        editor.setTextColor(theme.text);
        editor.setBackgroundColor(theme.consoleBg);
        editor.setTypeface(mono);
        editor.setTextSize(12);
        editor.setGravity(Gravity.TOP | Gravity.START);
        editor.setPadding(dp(8), dp(8), dp(8), dp(8));
        editor.setSingleLine(false);
        editor.setHorizontallyScrolling(true);
        editor.setEnabled(editable);
        editor.setTextIsSelectable(true);
        box.addView(editor, new LinearLayout.LayoutParams(-1, 0, 1));
        parent.addView(box, new LinearLayout.LayoutParams(dp(360), -1));
        return editor;
    }

    private TextView button(int label, View.OnClickListener listener) {
        TextView view = new TextView(this);
        view.setText(label);
        view.setTextColor(theme.accent);
        view.setPadding(dp(12), dp(10), dp(12), dp(10));
        view.setOnClickListener(listener);
        return view;
    }

    private void load() {
        io.execute(() -> {
            try {
                GitConflictResolver.ConflictFile file = GitConflictResolver.read(projectDir, path);
                ui.post(() -> {
                    current.setText(file.current);
                    incoming.setText(file.incoming);
                    result.setText(file.current);
                });
            } catch (Exception e) { ui.post(() -> error(e)); }
        });
    }

    private void save() {
        String text = result.getText().toString();
        io.execute(() -> {
            try {
                GitConflictResolver.resolve(projectDir, path, text);
                ui.post(() -> {
                    Toast.makeText(this, R.string.git_conflict_resolved, Toast.LENGTH_SHORT).show();
                    setResult(RESULT_OK);
                    finish();
                });
            } catch (Exception e) { ui.post(() -> error(e)); }
        });
    }

    private void error(Throwable error) {
        Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }
}
