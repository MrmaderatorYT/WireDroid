package com.ccs.wiredroid.ui;
import com.ccs.wiredroid.R;
import com.ccs.wiredroid.util.AppPreferences;
import com.ccs.wiredroid.util.AppTheme;
import com.ccs.wiredroid.tools.bytecode.CallGraphModel;
import com.ccs.wiredroid.util.FullScreenHelper;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Call Graph visualization: shows which methods call which other methods.
 * Provides both caller/callee views for a selected method.
 */
public class CallGraphActivity extends AppCompatActivity {

    private static final String EXTRA_PROJECT_DIR = "project_dir";
    private static final String EXTRA_CLASS_NAME = "class_name";
    private static final String EXTRA_METHOD_NAME = "method_name";

    /** Rows added per pass; the rest follow on later frames so the list can already be drawn. */
    private static final int ROW_CHUNK = 50;

    private AppPreferences prefs;
    private AppTheme theme;
    private CallGraphModel model;
    private Typeface monoTypeface;

    private LinearLayout contentBox;
    private TextView statusText;
    private EditText searchInput;
    private TextView methodLabel;

    /** Sorted once off the UI thread and reused by every later render of the full list. */
    private List<CallGraphModel.MethodNode> projectMethods;
    /** Bumped whenever the content box is cleared, so queued row chunks know to stop. */
    private int renderToken;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    public static void launch(Context context, File projectDir) {
        Intent i = new Intent(context, CallGraphActivity.class);
        i.putExtra(EXTRA_PROJECT_DIR, projectDir.getAbsolutePath());
        context.startActivity(i);
    }

    public static void launch(Context context, File projectDir, String className, String methodName) {
        Intent i = new Intent(context, CallGraphActivity.class);
        i.putExtra(EXTRA_PROJECT_DIR, projectDir.getAbsolutePath());
        i.putExtra(EXTRA_CLASS_NAME, className);
        i.putExtra(EXTRA_METHOD_NAME, methodName);
        context.startActivity(i);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        prefs = new AppPreferences(this);
        theme = AppTheme.byId(prefs.getThemeId(), prefs);
        monoTypeface = prefs.resolveTypeface();
        setTheme(theme.dark ? R.style.Theme_WireDroid : R.style.Theme_WireDroid_Light);

        super.onCreate(savedInstanceState);
        setContentView(buildRoot());
        FullScreenHelper.enable(this);

        String dir = getIntent().getStringExtra(EXTRA_PROJECT_DIR);
        if (dir == null) {
            Toast.makeText(this, R.string.toast_no_project, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        statusText.setText(R.string.label_analyzing_project);
        io.execute(() -> {
            try {
                model = new CallGraphModel();
                File projectDir = new File(dir);
                model.analyzeDirectory(projectDir);

                // Signatures are rebuilt on every comparison, so the whole sort stays here
                // rather than on the frame that first shows the list.
                final List<CallGraphModel.MethodNode> sorted = model.findProjectMethods(null);
                sorted.sort((a, b) -> a.shortSignature().compareToIgnoreCase(b.shortSignature()));

                ui.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    projectMethods = sorted;
                    statusText.setText(String.format(Locale.US,
                            "Found %d methods, %d calls",
                            model.getAllMethods().size(), model.getEdges().size()));

                    String className = getIntent().getStringExtra(EXTRA_CLASS_NAME);
                    String methodName = getIntent().getStringExtra(EXTRA_METHOD_NAME);
                    if (className != null && methodName != null) {
                        selectMethodBySignature(className, methodName);
                    } else {
                        showAllMethods();
                    }
                });
            } catch (Exception e) {
                ui.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    statusText.setText("Error: " + e.getMessage());
                    Toast.makeText(this, getString(R.string.toast_analysis_failed, e.getMessage()),
                            Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    private View buildRoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(theme.bg);

        Toolbar toolbar = new Toolbar(this);
        toolbar.setBackgroundColor(theme.toolbar);
        toolbar.setTitle("Call Graph");
        toolbar.setTitleTextColor(theme.text);
        toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
        toolbar.setNavigationOnClickListener(v -> finish());
        root.addView(toolbar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Search bar
        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setBackgroundColor(theme.toolbar);
        searchRow.setPadding(dp(8), dp(6), dp(8), dp(6));

        searchInput = new EditText(this);
        searchInput.setHint("Search method...");
        searchInput.setHintTextColor(theme.textDim);
        searchInput.setTextColor(theme.text);
        android.graphics.drawable.GradientDrawable sBg = new android.graphics.drawable.GradientDrawable();
        sBg.setColor(com.ccs.wiredroid.util.Colors.blend(theme.consoleBg, theme.bg, 0.4f));
        sBg.setCornerRadius(dp(6));
        sBg.setStroke(dp(1), theme.separator);
        searchInput.setBackground(sBg);
        searchInput.setPadding(dp(12), dp(8), dp(12), dp(8));
        searchInput.setSingleLine(true);
        searchInput.setContentDescription(getString(R.string.a11y_call_graph_search));
        searchInput.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        searchInput.setOnEditorActionListener((v, actionId, event) -> {
            filterMethods(searchInput.getText().toString());
            return true;
        });
        searchRow.addView(searchInput);

        root.addView(searchRow);

        // Method label
        methodLabel = new TextView(this);
        methodLabel.setBackgroundColor(theme.toolbar);
        methodLabel.setTextColor(theme.accent);
        methodLabel.setTextSize(12);
        methodLabel.setTypeface(monoTypeface);
        methodLabel.setPadding(dp(12), dp(8), dp(12), dp(8));
        methodLabel.setText(R.string.label_select_method);
        root.addView(methodLabel);

        // Content
        ScrollView sv = new ScrollView(this);
        contentBox = new LinearLayout(this);
        contentBox.setOrientation(LinearLayout.VERTICAL);
        contentBox.setPadding(dp(12), dp(8), dp(12), dp(8));
        sv.addView(contentBox);
        root.addView(sv, new LinearLayout.LayoutParams(
                0, 0, 1));

        // Status
        statusText = new TextView(this);
        statusText.setBackgroundColor(theme.consoleBg);
        statusText.setTextColor(theme.textDim);
        statusText.setTextSize(11);
        statusText.setPadding(dp(12), dp(6), dp(12), dp(6));
        root.addView(statusText);

        return root;
    }

    private void showAllMethods() {
        contentBox.removeAllViews();
        renderToken++;

        List<CallGraphModel.MethodNode> methods = projectMethods;
        if (methods == null) {
            methods = model.findProjectMethods(null);
            methods.sort((a, b) -> a.shortSignature().compareToIgnoreCase(b.shortSignature()));
            projectMethods = methods;
        }

        if (methods.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.label_no_methods);
            empty.setTextColor(theme.textDim);
            empty.setPadding(0, dp(24), 0, 0);
            empty.setGravity(Gravity.CENTER);
            contentBox.addView(empty);
            return;
        }

        TextView header = new TextView(this);
        header.setText("Project Methods (" + methods.size() + ")");
        header.setTextColor(theme.text);
        header.setTextSize(13);
        header.setTypeface(null, Typeface.BOLD);
        header.setPadding(0, 0, 0, dp(8));
        contentBox.addView(header);

        addMethodItems(methods, 0, renderToken);
    }

    /**
     * Fills the list a chunk at a time. A project can hold thousands of methods and every
     * row is its own TextView, so the tail is queued for later frames instead of holding
     * the first draw hostage.
     */
    private void addMethodItems(List<CallGraphModel.MethodNode> methods, int from, int token) {
        int end = Math.min(methods.size(), from + ROW_CHUNK);
        for (int i = from; i < end; i++) {
            contentBox.addView(createMethodItem(methods.get(i)));
        }
        if (end < methods.size()) {
            final int next = end;
            ui.post(() -> {
                if (isFinishing() || isDestroyed() || token != renderToken) return;
                addMethodItems(methods, next, token);
            });
        }
    }

    private void filterMethods(String query) {
        contentBox.removeAllViews();
        renderToken++;
        if (query.isEmpty()) {
            showAllMethods();
            return;
        }

        String q = query.toLowerCase(Locale.ROOT);
        List<CallGraphModel.MethodNode> filtered = new ArrayList<>();
        for (CallGraphModel.MethodNode m : model.getAllMethods().values()) {
            if (m.shortSignature().toLowerCase(Locale.ROOT).contains(q)
                    || m.className.toLowerCase(Locale.ROOT).contains(q)) {
                filtered.add(m);
            }
        }
        filtered.sort((a, b) -> a.shortSignature().compareToIgnoreCase(b.shortSignature()));

        if (filtered.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No methods matching: " + query);
            empty.setTextColor(theme.textDim);
            empty.setPadding(0, dp(24), 0, 0);
            empty.setGravity(Gravity.CENTER);
            contentBox.addView(empty);
            return;
        }

        addMethodItems(filtered, 0, renderToken);
    }

    private TextView createMethodItem(CallGraphModel.MethodNode m) {
        TextView item = new TextView(this);
        item.setTextColor(theme.accent);
        item.setTextSize(12);
        item.setTypeface(monoTypeface);
        item.setPadding(dp(8), dp(6), dp(8), dp(6));

        SpannableStringBuilder sb = new SpannableStringBuilder();
        int start = sb.length();
        sb.append(m.shortSignature());
        sb.setSpan(new StyleSpan(Typeface.BOLD), start, sb.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        List<CallGraphModel.MethodNode> callees = model.getCallees(m);
        List<CallGraphModel.MethodNode> callers = model.getCallers(m);

        if (!callees.isEmpty() || !callers.isEmpty()) {
            sb.append(String.format(Locale.US, "  [%d calls, %d called-by]",
                    callees.size(), callers.size()));
        }

        item.setText(sb);
        item.setContentDescription(getString(R.string.a11y_call_graph_method, m.shortSignature()));
        item.setOnClickListener(v -> showMethodCallGraph(m));
        return item;
    }

    private void showMethodCallGraph(CallGraphModel.MethodNode method) {
        contentBox.removeAllViews();
        renderToken++;
        methodLabel.setText(method.fullSignature());

        List<CallGraphModel.MethodNode> callees = model.getCallees(method);
        List<CallGraphModel.MethodNode> callers = model.getCallers(method);

        // Callees section (methods this method calls)
        TextView calleeHeader = new TextView(this);
        calleeHeader.setText("Calls (" + callees.size() + ")");
        calleeHeader.setTextColor(theme.successText);
        calleeHeader.setTextSize(13);
        calleeHeader.setTypeface(null, Typeface.BOLD);
        calleeHeader.setPadding(0, 0, 0, dp(8));
        contentBox.addView(calleeHeader);

        if (callees.isEmpty()) {
            contentBox.addView(createInfoText("No method calls"));
        } else {
            for (CallGraphModel.MethodNode callee : callees) {
                contentBox.addView(createIndentedItem("→", callee));
            }
        }

        // Divider
        View divider = new View(this);
        divider.setBackgroundColor(theme.textDim);
        divider.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1));
        divider.setAlpha(0.3f);
        LinearLayout.LayoutParams divLp = (LinearLayout.LayoutParams) divider.getLayoutParams();
        divLp.topMargin = dp(12);
        divLp.bottomMargin = dp(12);
        contentBox.addView(divider);

        // Callers section (methods that call this method)
        TextView callerHeader = new TextView(this);
        callerHeader.setText("Called by (" + callers.size() + ")");
        callerHeader.setTextColor(theme.editorNumber != 0 ? theme.editorNumber : 0xFFFFA726);
        callerHeader.setTextSize(13);
        callerHeader.setTypeface(null, Typeface.BOLD);
        callerHeader.setPadding(0, 0, 0, dp(8));
        contentBox.addView(callerHeader);

        if (callers.isEmpty()) {
            contentBox.addView(createInfoText("No callers found"));
        } else {
            for (CallGraphModel.MethodNode caller : callers) {
                contentBox.addView(createIndentedItem("←", caller));
            }
        }
    }

    private void selectMethodBySignature(String className, String methodName) {
        String key = className + "." + methodName;
        for (CallGraphModel.MethodNode m : model.getAllMethods().values()) {
            if (m.fullSignature().contains(key) || m.shortSignature().contains(methodName)) {
                showMethodCallGraph(m);
                return;
            }
        }
        showAllMethods();
    }

    private TextView createIndentedItem(String arrow, CallGraphModel.MethodNode m) {
        TextView item = new TextView(this);
        item.setTextSize(12);
        item.setTypeface(monoTypeface);
        item.setPadding(dp(24), dp(4), dp(8), dp(4));

        SpannableStringBuilder sb = new SpannableStringBuilder();
        sb.append(arrow + " ");
        int start = sb.length();
        sb.append(m.shortSignature());
        sb.setSpan(new ForegroundColorSpan(theme.accent), start, sb.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        item.setText(sb);
        item.setOnClickListener(v -> showMethodCallGraph(m));
        return item;
    }

    private TextView createInfoText(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(theme.textDim);
        tv.setTextSize(12);
        tv.setPadding(dp(24), dp(4), dp(8), dp(4));
        return tv;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
