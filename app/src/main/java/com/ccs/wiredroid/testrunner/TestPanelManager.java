package com.ccs.wiredroid.testrunner;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.ccs.wiredroid.R;
import com.ccs.wiredroid.util.AppTheme;

/**
 * Manages the Visual Test Runner panel within the bottom run panel.
 */
public final class TestPanelManager {

    public interface Callback {
        void onNavigateToSource(@Nullable String fileName, int lineNumber);
        void onToggleView(boolean showTree);
    }

    private final View container;
    private final View consoleScrollView;
    private final Callback callback;

    private ImageView summaryIcon;
    private TextView summaryText;
    private TextView btnFilterAll;
    private TextView btnFilterFailed;
    private TextView btnToggleConsole;
    private TextView emptyView;
    private RecyclerView recycler;
    private TestTreeAdapter adapter;

    private boolean isTreeVisible = false;

    public TestPanelManager(@NonNull View container,
                            @NonNull View consoleScrollView,
                            @NonNull Callback callback) {
        this.container = container;
        this.consoleScrollView = consoleScrollView;
        this.callback = callback;
        initViews();
    }

    private void initViews() {
        Context context = container.getContext();
        summaryIcon = container.findViewById(R.id.testSummaryIcon);
        summaryText = container.findViewById(R.id.testSummaryText);
        btnFilterAll = container.findViewById(R.id.btnTestFilterAll);
        btnFilterFailed = container.findViewById(R.id.btnTestFilterFailed);
        btnToggleConsole = container.findViewById(R.id.btnToggleConsole);
        emptyView = container.findViewById(R.id.testEmptyView);
        recycler = container.findViewById(R.id.testRecycler);

        adapter = new TestTreeAdapter();
        adapter.setNavigationListener((fileName, lineNumber) -> {
            if (callback != null) {
                callback.onNavigateToSource(fileName, lineNumber);
            }
        });

        recycler.setLayoutManager(new LinearLayoutManager(context));
        recycler.setAdapter(adapter);

        // Counts, from zero: the labels are format strings, and left unformatted
        // the buttons literally read "All (%1$d)".
        btnFilterAll.setText(context.getString(R.string.test_runner_filter_all, 0));
        btnFilterFailed.setText(context.getString(R.string.test_runner_filter_failed, 0));

        btnFilterAll.setOnClickListener(v -> {
            adapter.setFilterFailedOnly(false);
            updateFilterButtons();
        });

        btnFilterFailed.setOnClickListener(v -> {
            adapter.setFilterFailedOnly(true);
            updateFilterButtons();
        });

        btnToggleConsole.setOnClickListener(v -> {
            if (isTreeVisible) {
                showConsole();
            } else {
                showTree();
            }
        });
    }

    public void displayReport(@NonNull TestReportParser.ParsedReport report) {
        Context context = container.getContext();

        if (report.items.isEmpty()) {
            emptyView.setVisibility(View.VISIBLE);
            recycler.setVisibility(View.GONE);
        } else {
            emptyView.setVisibility(View.GONE);
            recycler.setVisibility(View.VISIBLE);
            adapter.setReport(report.items);
        }

        if (report.failedTests == 0) {
            summaryIcon.setImageResource(R.drawable.ic_test_pass);
            summaryText.setText(context.getString(R.string.test_runner_all_passed,
                    report.passedTests, report.durationMs));
            summaryText.setTextColor(0xFF4CAF50);
        } else {
            summaryIcon.setImageResource(R.drawable.ic_test_fail);
            summaryText.setText(context.getString(R.string.test_runner_tests_failed,
                    report.failedTests, report.totalTests, report.durationMs));
            summaryText.setTextColor(0xFFF44336);
        }

        btnFilterAll.setText(context.getString(R.string.test_runner_filter_all, report.totalTests));
        btnFilterFailed.setText(context.getString(R.string.test_runner_filter_failed, report.failedTests));
        updateFilterButtons();

        showTree();
    }

    public void displayRawOutput(@NonNull String output) {
        TestReportParser.ParsedReport report = TestReportParser.fromOutputText(output);
        if (report.totalTests > 0 || !report.items.isEmpty()) {
            displayReport(report);
        }
    }

    public void showTree() {
        isTreeVisible = true;
        container.setVisibility(View.VISIBLE);
        consoleScrollView.setVisibility(View.GONE);
        btnToggleConsole.setText(R.string.test_runner_tab_console);
        if (callback != null) callback.onToggleView(true);
    }

    public void showConsole() {
        isTreeVisible = false;
        container.setVisibility(View.GONE);
        consoleScrollView.setVisibility(View.VISIBLE);
        btnToggleConsole.setText(R.string.test_runner_tab_tree);
        if (callback != null) callback.onToggleView(false);
    }

    public boolean isShowingTree() {
        return isTreeVisible;
    }

    private void updateFilterButtons() {
        boolean failedOnly = adapter.isFilterFailedOnly();
        btnFilterAll.setAlpha(failedOnly ? 0.6f : 1.0f);
        btnFilterFailed.setAlpha(failedOnly ? 1.0f : 0.6f);
    }

    public void applyTheme(@Nullable AppTheme theme) {
        if (theme == null) return;
        container.setBackgroundColor(theme.consoleBg);
        View summaryBar = container.findViewById(R.id.testSummaryBar);
        if (summaryBar != null) {
            summaryBar.setBackgroundColor(theme.toolbar);
        }
        if (btnFilterAll != null) btnFilterAll.setTextColor(theme.accent);
        if (btnFilterFailed != null) btnFilterFailed.setTextColor(theme.errorText);
        if (btnToggleConsole != null) btnToggleConsole.setTextColor(theme.text);
        if (emptyView != null) emptyView.setTextColor(theme.textDim);
        // The rows are inflated per item, so the adapter has to carry the theme
        // rather than have it set once on a view here.
        if (adapter != null) adapter.setTheme(theme);
    }
}
