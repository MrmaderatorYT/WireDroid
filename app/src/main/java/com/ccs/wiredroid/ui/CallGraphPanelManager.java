package com.ccs.wiredroid.ui;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.Editable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.ccs.wiredroid.R;
import com.ccs.wiredroid.tools.bytecode.CallGraphModel;
import com.ccs.wiredroid.util.AppTheme;

import java.io.File;
import java.util.List;
import java.util.Locale;

public final class CallGraphPanelManager {

    public interface Callback {
        File getProjectDir();
        AppTheme getTheme();
        void runOnUiThread(@NonNull Runnable r);
    }

    private final Activity activity;
    private final Callback callback;

    /** False until the ViewStub holding this panel has been inflated. */
    private boolean panelInflated;
    /** Last theme handed to applyTheme, re-applied to views inflated later. */
    private AppTheme lastTheme;

    private View root;
    private View toolbar;
    private LinearLayout content;
    private TextView status;
    private EditText search;
    private CallGraphModel model;
    private boolean loaded = false;

    public CallGraphPanelManager(Activity activity, Callback callback) {
        this.activity = activity;
        this.callback = callback;
    }

    /** Nothing to bind up front — the panel inflates on first open, see {@link #ensurePanel()}. */
    public void bind() {
    }

    /**
     * Inflates the panel the first time it is actually opened.
     *
     * <p>It lives behind a {@code ViewStub} because it starts hidden and most
     * sessions never open it, yet it used to be inflated on every cold start
     * along with every other bottom panel.</p>
     */
    private void ensurePanel() {
        if (panelInflated) return;
        panelInflated = true;
        android.view.ViewStub stub = activity.findViewById(R.id.stubCallGraphPanel);
        if (stub != null) stub.inflate();
        root    = activity.findViewById(R.id.callGraphRoot);
        toolbar = activity.findViewById(R.id.callGraphToolbar);
        content = activity.findViewById(R.id.callGraphContent);
        status  = activity.findViewById(R.id.callGraphStatus);
        search  = activity.findViewById(R.id.callGraphSearch);

        if (search != null && search.getTag() == null) {
            search.setTag("init");
            search.setOnEditorActionListener((v, actionId, event) -> {
                filter(search.getText().toString());
                return true;
            });
        }

        View refreshBtn = toolbar != null ? toolbar.findViewById(R.id.callGraphRefresh) : null;
        if (refreshBtn != null) refreshBtn.setOnClickListener(v -> refresh());
        if (lastTheme != null) applyTheme(lastTheme);
    }

    public void applyTheme(@NonNull AppTheme theme) {
        lastTheme = theme;
        if (root != null) root.setBackgroundColor(theme.consoleBg);
        if (toolbar != null) toolbar.setBackgroundColor(theme.toolbar);
        if (search != null) {
            search.setTextColor(theme.consoleText);
            search.setHintTextColor(theme.textDim);
        }
        if (status != null) {
            status.setBackgroundColor(theme.consoleBg);
            status.setTextColor(theme.textDim);
        }
    }

    public void setVisibility(boolean visible) {
        if (visible) ensurePanel();
        if (root != null) root.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    public void invalidate() { loaded = false; }

    public void refresh() {
        ensurePanel();
        if (content == null || status == null) return;

        File projectDir = callback.getProjectDir();
        if (projectDir == null) {
            status.setText(R.string.call_graph_no_project);
            content.removeAllViews();
            return;
        }

        if (model != null && loaded) {
            showList(model, "");
            return;
        }

        status.setText(R.string.call_graph_analyzing);
        content.removeAllViews();

        new Thread(() -> {
            try {
                CallGraphModel m = new CallGraphModel();
                m.analyzeDirectory(projectDir);
                model = m;
                loaded = true;
                callback.runOnUiThread(() -> {
                    status.setText(String.format(Locale.US,
                            "%d methods, %d edges", m.getAllMethods().size(), m.getEdges().size()));
                    showList(m, search != null ? search.getText().toString() : "");
                });
            } catch (Exception e) {
                callback.runOnUiThread(() -> status.setText("Error: " + e.getMessage()));
            }
        }).start();
    }

    private void filter(String query) {
        if (model != null) showList(model, query);
    }

    private void showList(CallGraphModel m, String query) {
        content.removeAllViews();
        AppTheme theme = callback.getTheme();
        boolean foundAny = false;

        // Group methods by class
        java.util.Map<String, java.util.List<CallGraphModel.MethodNode>> classMethods = new java.util.TreeMap<>();
        for (CallGraphModel.MethodNode node : m.getAllMethods().values()) {
            classMethods.computeIfAbsent(node.className, k -> new java.util.ArrayList<>()).add(node);
        }

        for (java.util.Map.Entry<String, java.util.List<CallGraphModel.MethodNode>> entry : classMethods.entrySet()) {
            String className = entry.getKey();
            java.util.List<CallGraphModel.MethodNode> methods = entry.getValue();

            LinearLayout classGroup = new LinearLayout(activity);
            classGroup.setOrientation(LinearLayout.VERTICAL);

            TextView classHeader = new TextView(activity);
            classHeader.setText(className);
            classHeader.setTextColor(theme.accent);
            classHeader.setTextSize(13);
            classHeader.setTypeface(null, Typeface.BOLD);
            classHeader.setPadding(16, 12, 16, 4);
            classGroup.addView(classHeader);

            boolean classHasMatch = false;
            for (CallGraphModel.MethodNode method : methods) {
                if (!query.isEmpty() && !method.shortSignature().toLowerCase(Locale.ROOT)
                        .contains(query.toLowerCase(Locale.ROOT))) continue;
                classHasMatch = true;
                foundAny = true;

                List<CallGraphModel.MethodNode> callees = m.getCallees(method);
                List<CallGraphModel.MethodNode> callers = m.getCallers(method);

                TextView methodItem = new TextView(activity);
                methodItem.setTextSize(12);
                methodItem.setTextColor(theme.text);
                methodItem.setPadding(32, 6, 16, 6);
                String info = "  " + method.methodName;
                if (!callees.isEmpty()) info += "  →" + callees.size();
                if (!callers.isEmpty()) info += "  ←" + callers.size();
                methodItem.setText(info);
                methodItem.setOnClickListener(v -> showDetail(method));
                classGroup.addView(methodItem);
            }

            if (classHasMatch) content.addView(classGroup);
        }

        if (!foundAny) {
            TextView empty = new TextView(activity);
            empty.setText(R.string.label_no_results);
            empty.setTextColor(theme.textDim);
            empty.setPadding(32, 32, 32, 32);
            empty.setGravity(Gravity.CENTER);
            content.addView(empty);
        }
    }

    private void showDetail(CallGraphModel.MethodNode method) {
        content.removeAllViews();
        AppTheme theme = callback.getTheme();
        if (search != null) search.setText(method.shortSignature());

        TextView header = new TextView(activity);
        header.setText(method.shortSignature());
        header.setTextColor(theme.accent);
        header.setTextSize(14);
        header.setTypeface(null, Typeface.BOLD);
        header.setPadding(16, 12, 16, 8);
        content.addView(header);

        List<CallGraphModel.MethodNode> callees = model.getCallees(method);
        TextView callsLabel = new TextView(activity);
        callsLabel.setText("Calls (" + callees.size() + "):");
        callsLabel.setTextColor(theme.textDim);
        callsLabel.setTextSize(11);
        callsLabel.setPadding(16, 8, 16, 4);
        content.addView(callsLabel);

        if (callees.isEmpty()) {
            TextView none = new TextView(activity);
            none.setText(R.string.label_none);
            none.setTextColor(theme.textDim);
            none.setTextSize(11);
            none.setPadding(32, 4, 16, 4);
            content.addView(none);
        } else {
            for (CallGraphModel.MethodNode callee : callees) content.addView(createItem("→", callee));
        }

        View div = new View(activity);
        div.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
        div.setBackgroundColor(callback.getTheme().toolbar);
        content.addView(div);

        List<CallGraphModel.MethodNode> callers = model.getCallers(method);
        TextView calledByLabel = new TextView(activity);
        calledByLabel.setText("Called by (" + callers.size() + "):");
        calledByLabel.setTextColor(theme.textDim);
        calledByLabel.setTextSize(11);
        calledByLabel.setPadding(16, 8, 16, 4);
        content.addView(calledByLabel);

        if (callers.isEmpty()) {
            TextView none = new TextView(activity);
            none.setText(R.string.label_none);
            none.setTextColor(theme.textDim);
            none.setTextSize(11);
            none.setPadding(32, 4, 16, 4);
            content.addView(none);
        } else {
            for (CallGraphModel.MethodNode caller : callers) content.addView(createItem("←", caller));
        }
    }

    private TextView createItem(String arrow, CallGraphModel.MethodNode m) {
        AppTheme theme = callback.getTheme();
        TextView item = new TextView(activity);
        item.setText("  " + arrow + " " + m.shortSignature());
        item.setTextColor(arrow.equals("→") ? 0xFF4CAF50 : 0xFFFF9800);
        item.setTextSize(11);
        item.setPadding(32, 4, 16, 4);
        item.setOnClickListener(v -> showDetail(m));
        return item;
    }

    public View getRoot() { return root; }
}
