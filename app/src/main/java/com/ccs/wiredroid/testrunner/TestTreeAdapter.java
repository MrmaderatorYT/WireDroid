package com.ccs.wiredroid.testrunner;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.ccs.wiredroid.R;
import com.ccs.wiredroid.util.AppTheme;

import java.util.ArrayList;
import java.util.List;

/**
 * RecyclerView adapter that displays a collapsible tree of test suites and methods.
 */
public final class TestTreeAdapter extends RecyclerView.Adapter<TestTreeAdapter.TestViewHolder> {

    public interface OnTestNavigationListener {
        void onNavigate(@Nullable String sourceFileName, int lineNumber);
    }

    private final List<TestResultItem> allSuites = new ArrayList<>();
    private final List<TestResultItem> flattenedDisplayItems = new ArrayList<>();
    private boolean filterFailedOnly = false;
    private OnTestNavigationListener navigationListener;

    /**
     * Row colours, which the layout cannot supply.
     *
     * <p>The item XML can only name one palette, and the app ships a light
     * scheme as well — with the layout's own colours the test names came out
     * pale grey on a pale background.</p>
     */
    @Nullable private AppTheme theme;

    public void setNavigationListener(@Nullable OnTestNavigationListener listener) {
        this.navigationListener = listener;
    }

    public void setReport(@NonNull List<TestResultItem> suites) {
        allSuites.clear();
        allSuites.addAll(suites);
        rebuildDisplayItems();
    }

    public void setFilterFailedOnly(boolean failedOnly) {
        if (this.filterFailedOnly != failedOnly) {
            this.filterFailedOnly = failedOnly;
            rebuildDisplayItems();
        }
    }

    public boolean isFilterFailedOnly() {
        return filterFailedOnly;
    }

    public void setTheme(@Nullable AppTheme theme) {
        this.theme = theme;
        notifyDataSetChanged();
    }

    private void rebuildDisplayItems() {
        flattenedDisplayItems.clear();
        for (TestResultItem suite : allSuites) {
            if (filterFailedOnly && !suite.hasFailedChildren() && suite.status != TestResultItem.Status.FAILED) {
                continue;
            }
            flattenedDisplayItems.add(suite);
            if (suite.isExpanded) {
                for (TestResultItem method : suite.children) {
                    if (filterFailedOnly && method.status != TestResultItem.Status.FAILED) {
                        continue;
                    }
                    flattenedDisplayItems.add(method);
                }
            }
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public TestViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_test_tree, parent, false);
        return new TestViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull TestViewHolder holder, int position) {
        TestResultItem item = flattenedDisplayItems.get(position);
        holder.bind(item);
    }

    @Override
    public int getItemCount() {
        return flattenedDisplayItems.size();
    }

    final class TestViewHolder extends RecyclerView.ViewHolder {

        private final View indentView;
        private final ImageView expandIcon;
        private final ImageView statusIcon;
        private final TextView titleText;
        private final TextView durationText;
        private final LinearLayout errorBox;
        private final TextView errorMessage;
        private final TextView jumpLink;

        TestViewHolder(@NonNull View itemView) {
            super(itemView);
            indentView = itemView.findViewById(R.id.testIndentView);
            expandIcon = itemView.findViewById(R.id.testExpandIcon);
            statusIcon = itemView.findViewById(R.id.testStatusIcon);
            titleText = itemView.findViewById(R.id.testTitle);
            durationText = itemView.findViewById(R.id.testDuration);
            errorBox = itemView.findViewById(R.id.testErrorBox);
            errorMessage = itemView.findViewById(R.id.testErrorMessage);
            jumpLink = itemView.findViewById(R.id.testJumpLink);
        }

        void bind(TestResultItem item) {
            // Indentation
            LinearLayout.LayoutParams indentParams = (LinearLayout.LayoutParams) indentView.getLayoutParams();
            indentParams.width = item.isSuite ? 0 : dpToPx(20);
            indentView.setLayoutParams(indentParams);

            if (theme != null) {
                titleText.setTextColor(theme.text);
                durationText.setTextColor(theme.textDim);
                errorMessage.setTextColor(theme.errorText);
                jumpLink.setTextColor(theme.accent);
            }

            // Title and Duration
            titleText.setText(item.title);
            if (item.durationMs > 0) {
                durationText.setVisibility(View.VISIBLE);
                durationText.setText(item.durationMs + " ms");
            } else {
                durationText.setVisibility(View.GONE);
            }

            // Status Icon
            switch (item.status) {
                case PASSED:
                    statusIcon.setImageResource(R.drawable.ic_test_pass);
                    break;
                case FAILED:
                    statusIcon.setImageResource(R.drawable.ic_test_fail);
                    break;
                case SKIPPED:
                default:
                    statusIcon.setImageResource(R.drawable.ic_test_skip);
                    break;
            }

            // Expand icon for suites
            if (item.isSuite && !item.children.isEmpty()) {
                expandIcon.setVisibility(View.VISIBLE);
                expandIcon.setRotation(item.isExpanded ? 270f : 180f);
            } else {
                expandIcon.setVisibility(View.GONE);
            }

            // Error info box
            if (item.status == TestResultItem.Status.FAILED && item.errorMessage != null) {
                errorBox.setVisibility(View.VISIBLE);
                errorMessage.setText(item.errorMessage);
                if (item.sourceFile != null && item.errorLine > 0) {
                    jumpLink.setVisibility(View.VISIBLE);
                    jumpLink.setText(item.sourceFile + ":" + item.errorLine + " — "
                            + itemView.getContext().getString(R.string.test_runner_jump_to_source));
                } else {
                    jumpLink.setVisibility(View.GONE);
                }
            } else {
                errorBox.setVisibility(View.GONE);
            }

            // Click listener
            itemView.setOnClickListener(v -> {
                if (item.isSuite) {
                    item.isExpanded = !item.isExpanded;
                    rebuildDisplayItems();
                } else if (item.status == TestResultItem.Status.FAILED && navigationListener != null) {
                    navigationListener.onNavigate(item.sourceFile, item.errorLine);
                }
            });

            jumpLink.setOnClickListener(v -> {
                if (navigationListener != null) {
                    navigationListener.onNavigate(item.sourceFile, item.errorLine);
                }
            });
        }

        private int dpToPx(int dp) {
            float density = itemView.getContext().getResources().getDisplayMetrics().density;
            return Math.round(dp * density);
        }
    }
}
