package com.ccs.wiredroid.analysis;
import com.ccs.wiredroid.R;
import com.ccs.wiredroid.util.AppTheme;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class ProblemsAdapter extends RecyclerView.Adapter<ProblemsAdapter.VH> {

    public interface Listener {
        void onProblemClicked(ProblemItem item);
    }

    /** Everything the last analysis produced. */
    private final List<ProblemItem> all = new ArrayList<>();
    /** The subset the filter lets through — what the list actually shows. */
    private final List<ProblemItem> items = new ArrayList<>();
    /** Severities the user has switched off. Empty means show everything. */
    private final java.util.EnumSet<ProblemItem.Severity> hidden =
            java.util.EnumSet.noneOf(ProblemItem.Severity.class);
    private Listener listener;
    private AppTheme theme;
    private final Context appContext;

    /** The file in the editor, when the list is narrowed to it. */
    private String scopePath;
    private boolean scopeToCurrentFile;

    /**
     * How many rows the list shows. A two-thousand-file project produces tens of
     * thousands of findings and nobody scrolls those; past this point the list is
     * cut and a final row says how many were left out.
     *
     * <p>The cut happens after filtering, not before. Cutting first meant the
     * severity counts described the cut copy rather than the project, and a
     * severity that fell past the cut could not be recovered by unticking the
     * others.</p>
     */
    private static final int MAX_VISIBLE = 2000;

    public ProblemsAdapter(Context context) {
        this.appContext = context != null ? context.getApplicationContext() : null;
    }

    public void setTheme(AppTheme theme) {
        this.theme = theme;
        notifyDataSetChanged();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setItems(List<ProblemItem> newItems) {
        all.clear();
        if (newItems != null) all.addAll(newItems);
        refilter();
    }

    /** Whether findings of this severity are currently listed. */
    public boolean isSeverityShown(ProblemItem.Severity severity) {
        return !hidden.contains(severity);
    }

    public void setSeverityShown(ProblemItem.Severity severity, boolean shown) {
        boolean changed = shown ? hidden.remove(severity) : hidden.add(severity);
        if (changed) refilter();
    }

    /**
     * Narrows the list to one file, or widens it back to the whole project.
     *
     * <p>The severity counts follow the scope. They have to: a count that
     * describes the project while the list shows one file is the same mismatch
     * that made "Info 1999" sit next to a note about 23788 more.</p>
     */
    public void setScopeToCurrentFile(boolean scoped) {
        if (scopeToCurrentFile == scoped) return;
        scopeToCurrentFile = scoped;
        refilter();
    }

    public boolean isScopedToCurrentFile() {
        return scopeToCurrentFile;
    }

    /** Points the scope at whatever the editor now holds. */
    public void setScopeFile(java.io.File file) {
        String path = file != null ? file.getAbsolutePath() : null;
        if (path == null ? scopePath == null : path.equals(scopePath)) return;
        scopePath = path;
        if (scopeToCurrentFile) refilter();
    }

    /** How many findings of this severity the current scope holds. */
    public int countOf(ProblemItem.Severity severity) {
        int n = 0;
        for (ProblemItem p : all) {
            if (p.severity == severity && inScope(p)) n++;
        }
        return n;
    }

    private boolean inScope(ProblemItem p) {
        if (!scopeToCurrentFile) return true;
        // A note about the list itself belongs to whatever is on screen.
        if (p.file == null) return true;
        return scopePath != null && scopePath.equals(p.file.getAbsolutePath());
    }

    private void refilter() {
        items.clear();
        int dropped = 0;
        for (ProblemItem p : all) {
            if (!inScope(p)) continue;
            if (hidden.contains(p.severity)) continue;
            if (items.size() < MAX_VISIBLE) {
                items.add(p);
            } else {
                dropped++;
            }
        }
        if (dropped > 0) {
            // Carries no file: it is a note about the list, not a finding in a
            // source file, and the row renders without a location because of it.
            items.add(new ProblemItem(ProblemItem.Severity.INFO,
                    appContext != null
                            ? appContext.getString(R.string.problems_truncated, dropped)
                            : dropped + " more findings not shown",
                    null, 0));
        }
        notifyDataSetChanged();
    }

    /**
     * What is on screen right now, not everything found — this is what "copy
     * panel output" should hand over, and it is the list the rows are bound to.
     */
    public List<ProblemItem> getItems() {
        return items;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_problem, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        ProblemItem p = items.get(position);
        Context ctx = h.itemView.getContext();
        String sev;
        int color;
        switch (p.severity) {
            case ERROR:
                sev = ctx.getString(R.string.problem_error);
                color = theme != null ? theme.errorText : 0xFFFF6B6B;
                break;
            case WARNING:
                sev = ctx.getString(R.string.problem_warn);
                color = theme != null ? (theme.dark ? 0xFFFFB74D : 0xFFF57C00) : 0xFFFFB74D;
                break;
            case SECURITY:
                // Shield glyph plus a yellow that is deliberately louder than the
                // warning amber, so a leaked credential does not read as one more
                // style nit in a list of eighty.
                sev = ctx.getString(R.string.problem_security);
                color = theme != null && !theme.dark ? 0xFFB8860B : 0xFFFFD54F;
                break;
            default:
                sev = ctx.getString(R.string.problem_info);
                color = theme != null ? theme.accent : 0xFF64B5F6;
                break;
        }
        h.severity.setText(sev);
        h.severity.setTextColor(color);
        if (p.file == null) {
            // A note about the list itself rather than a finding in a file, so
            // there is no location to show and the unknown-file placeholder would
            // just read as a defect.
            h.location.setVisibility(View.GONE);
        } else {
            String loc = p.file.getName();
            if (p.line > 0) loc += ":" + p.line;
            h.location.setVisibility(View.VISIBLE);
            h.location.setText(loc);
            h.location.setTextColor(theme != null ? theme.textDim : 0xFF808080);
        }
        h.message.setText(p.message);
        h.message.setTextColor(theme != null ? theme.text : 0xFFA9B7C6);
        h.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onProblemClicked(p);
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final TextView severity;
        final TextView location;
        final TextView message;

        VH(View v) {
            super(v);
            severity = v.findViewById(R.id.problemSeverity);
            location = v.findViewById(R.id.problemLocation);
            message = v.findViewById(R.id.problemMessage);
        }
    }
}
