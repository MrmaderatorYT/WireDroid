package com.ccs.wiredroid.ui;
import com.ccs.wiredroid.R;
import com.ccs.wiredroid.util.AppTheme;

import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Project drawer tree. Directories show a disclosure triangle and can be
 * expanded or collapsed; files show a type icon.
 */
public class FileTreeAdapter extends RecyclerView.Adapter<FileTreeAdapter.FileViewHolder> {

    public interface NodeListener {
        void onNodeClicked(FileTreeNode node);
        void onNodeLongClicked(FileTreeNode node);
    }

    private final List<FileTreeNode> nodes = new ArrayList<>();
    private NodeListener listener;
    private File activeFile;
    private AppTheme theme;
    /** Paths the user has marked read-only, drawn with a padlock. */
    private java.util.Set<String> readOnlyPaths = java.util.Collections.emptySet();

    public void setTheme(AppTheme theme) {
        this.theme = theme;
        notifyDataSetChanged();
    }

    public void setNodeListener(NodeListener listener) {
        this.listener = listener;
    }

    public void setNodes(List<FileTreeNode> newNodes) {
        nodes.clear();
        if (newNodes != null) nodes.addAll(newNodes);
        notifyDataSetChanged();
    }

    public void setActiveFile(File file) {
        activeFile = file;
        notifyDataSetChanged();
    }

    public File getActiveFile() {
        return activeFile;
    }

    /** Marks these absolute paths with a padlock in the tree. */
    public void setReadOnlyPaths(java.util.Set<String> paths) {
        this.readOnlyPaths = paths != null ? paths : java.util.Collections.emptySet();
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public FileViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_file_tree, parent, false);
        return new FileViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull FileViewHolder holder, int position) {
        FileTreeNode node = nodes.get(position);
        String name = node.shortName();

        if (node.directory) {
            holder.chevron.setText(node.hasChildren ? (node.expanded ? "▾" : "▸") : "");
            holder.chevron.setTextColor(theme != null ? theme.textDim : 0xFF9E9E9E);
            holder.icon.setText(node.expanded ? "📂" : "📁");
            holder.icon.setVisibility(View.VISIBLE);
            holder.kindIcon.setVisibility(View.GONE);
            holder.fileName.setText(name);
            holder.fileName.setTextColor(theme != null ? theme.textDim : 0xFF9E9E9E);
        } else {
            holder.chevron.setText("");
            applyFileIcon(holder, node);
            boolean locked = readOnlyPaths.contains(node.path.getAbsolutePath());
            holder.fileName.setText(locked ? name + "  🔒" : name);
            boolean active = node.path.equals(activeFile);
            if (theme != null) {
                holder.fileName.setTextColor(active ? theme.text : theme.textDim);
            } else {
                holder.fileName.setTextColor(active ? 0xFFBBBBBB : 0xFF9E9E9E);
            }
        }

        int depthPx = (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 12f * node.depth,
                holder.itemView.getResources().getDisplayMetrics());
        holder.itemView.setPaddingRelative(depthPx + (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 8f, holder.itemView.getResources().getDisplayMetrics()),
                holder.itemView.getPaddingTop(),
                holder.itemView.getPaddingEnd(),
                holder.itemView.getPaddingBottom());

        boolean activeRow = !node.directory && node.path.equals(activeFile);
        int activeRowBg = theme != null ? (0x33000000 | (theme.accent & 0x00FFFFFF)) : 0x334A86C8;
        holder.itemView.setBackgroundColor(activeRow ? activeRowBg : 0x00000000);

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onNodeClicked(node);
        });
        holder.itemView.setOnLongClickListener(v -> {
            if (listener != null) listener.onNodeLongClicked(node);
            return true;
        });
    }

    /**
     * Draws either the type's icon or the extension emoji.
     *
     * <p>The kind is only known once the file has been read, so the emoji is
     * what a row shows first; the callback swaps it in when the read lands. The
     * position is re-checked there because the holder may have been recycled
     * onto a different file by then.</p>
     */
    private void applyFileIcon(FileViewHolder holder, FileTreeNode node) {
        com.ccs.wiredroid.util.ClassKind kind =
                com.ccs.wiredroid.util.ClassKind.of(node.path, k -> {
                    int at = nodes.indexOf(node);
                    if (at >= 0) notifyItemChanged(at);
                });
        if (kind != null) {
            holder.kindIcon.setImageDrawable(kind.icon(holder.itemView.getContext(), node.path));
            holder.kindIcon.setVisibility(View.VISIBLE);
            holder.icon.setVisibility(View.GONE);
        } else {
            holder.icon.setText(iconFor(node.shortName()));
            holder.icon.setVisibility(View.VISIBLE);
            holder.kindIcon.setVisibility(View.GONE);
        }
    }

    /** Icon for a file, chosen by extension. */
    private static String iconFor(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".java")) return "☕";
        if (lower.endsWith(".kt") || lower.endsWith(".kts")) return "🇰";
        String byLanguage = com.ccs.wiredroid.util.languages.LanguageFiles.iconFor(lower);
        if (byLanguage != null) return byLanguage;
        if (lower.endsWith(".class")) return "🅒";
        if (lower.endsWith(".gradle") || lower.endsWith(".gradle.kts")) return "🐘";
        if (lower.equals("pom.xml")) return "🅜";
        if (lower.endsWith(".xml") || lower.endsWith(".html") || lower.endsWith(".htm")) return "📜";
        if (lower.endsWith(".properties")) return "⚙";
        if (lower.endsWith(".json")) return "🧩";
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) return "📝";
        if (lower.endsWith(".c") || lower.endsWith(".cpp") || lower.endsWith(".h")
                || lower.endsWith(".hpp")) return "🛠️";
        if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".bmp")
                || lower.endsWith(".svg")) return "🖼️";
        if (lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".webm")
                || lower.endsWith(".mov") || lower.endsWith(".avi")) return "🎬";
        if (lower.endsWith(".mp3") || lower.endsWith(".ogg") || lower.endsWith(".opus")
                || lower.endsWith(".flac") || lower.endsWith(".wav")) return "🎵";
        if (lower.endsWith(".jar") || lower.endsWith(".zip") || lower.endsWith(".apk")
                || lower.endsWith(".aar")) return "📦";
        if (lower.endsWith(".db") || lower.endsWith(".sqlite") || lower.endsWith(".sqlite3")) return "🗄️";
        if (lower.endsWith(".sh") || lower.endsWith(".bash")) return "⌨️";
        if (lower.endsWith(".so") || lower.endsWith(".dex") || lower.endsWith(".bin")) return "⬢";
        return "📄";
    }

    @Override
    public int getItemCount() {
        return nodes.size();
    }

    static class FileViewHolder extends RecyclerView.ViewHolder {
        final TextView chevron;
        final TextView icon;
        final android.widget.ImageView kindIcon;
        final TextView fileName;

        FileViewHolder(View view) {
            super(view);
            chevron = view.findViewById(R.id.nodeChevron);
            icon = view.findViewById(R.id.nodeIcon);
            kindIcon = view.findViewById(R.id.nodeKindIcon);
            fileName = view.findViewById(R.id.fileName);
        }
    }
}
