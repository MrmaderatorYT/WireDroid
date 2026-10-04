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

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class TodoAdapter extends RecyclerView.Adapter<TodoAdapter.VH> {

    public interface Listener {
        void onTodoClicked(TodoItem item);
    }

    private final List<TodoItem> items = new ArrayList<>();
    private final List<TodoItem> allItems = new ArrayList<>();
    private Listener listener;
    private AppTheme theme;

    public void setTheme(AppTheme theme) {
        this.theme = theme;
        notifyDataSetChanged();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setItems(List<ProblemItem> problems) {
        allItems.clear();
        if (problems != null) {
            for (ProblemItem p : problems) {
                if (p.message != null &&
                    (p.message.contains("TODO") || p.message.contains("FIXME"))) {
                    String tag = p.message.contains("FIXME") ? "FIXME" : "TODO";
                    String text = extractCommentText(p.message);
                    allItems.add(new TodoItem(p.file, p.line, tag, text, p.severity));
                }
            }
        }
        items.clear();
        items.addAll(allItems);
        notifyDataSetChanged();
    }

    public void filter(String query) {
        items.clear();
        if (query == null || query.trim().isEmpty()) {
            items.addAll(allItems);
        } else {
            String q = query.toLowerCase();
            for (TodoItem item : allItems) {
                if (item.text.toLowerCase().contains(q)
                        || item.file != null && item.file.getName().toLowerCase().contains(q)
                        || item.tag.toLowerCase().contains(q)) {
                    items.add(item);
                }
            }
        }
        notifyDataSetChanged();
    }

    private String extractCommentText(String message) {
        // Message format: "TODO: do something" or "FIXME: fix this"
        int idx = message.indexOf(':');
        if (idx >= 0 && idx < message.length() - 1) {
            return message.substring(idx + 1).trim();
        }
        return message;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_todo, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        TodoItem item = items.get(position);
        Context ctx = h.itemView.getContext();

        h.tag.setText(item.tag);
        int fixmeColor = theme != null ? (theme.dark ? 0xFFFFB74D : 0xFFF57C00) : 0xFFFFB74D;
        int todoColor = theme != null ? theme.accent : 0xFF64B5F6;
        h.tag.setTextColor("FIXME".equals(item.tag) ? fixmeColor : todoColor);

        String loc = item.file != null ? item.file.getName() : "?";
        if (item.line > 0) loc += ":" + item.line;
        h.location.setText(loc);
        h.location.setTextColor(theme != null ? theme.textDim : 0xFF808080);

        h.message.setText(item.text);
        h.message.setTextColor(theme != null ? theme.text : 0xFFA9B7C6);

        h.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onTodoClicked(item);
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    public int getTodoCount() {
        int count = 0;
        for (TodoItem item : allItems) {
            if ("TODO".equals(item.tag)) count++;
        }
        return count;
    }

    public int getFixmeCount() {
        int count = 0;
        for (TodoItem item : allItems) {
            if ("FIXME".equals(item.tag)) count++;
        }
        return count;
    }

    /** Snapshot of the currently visible rows, including any active text filter. */
    public List<TodoItem> getVisibleItems() {
        return new ArrayList<>(items);
    }

    static class VH extends RecyclerView.ViewHolder {
        final TextView tag;
        final TextView location;
        final TextView message;

        VH(View v) {
            super(v);
            tag = v.findViewById(R.id.todoTag);
            location = v.findViewById(R.id.todoLocation);
            message = v.findViewById(R.id.todoMessage);
        }
    }

    public static class TodoItem {
        public final File file;
        public final int line;
        public final String tag;
        public final String text;
        public final ProblemItem.Severity severity;

        public TodoItem(File file, int line, String tag, String text, ProblemItem.Severity severity) {
            this.file = file;
            this.line = line;
            this.tag = tag;
            this.text = text;
            this.severity = severity;
        }
    }
}
