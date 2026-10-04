package com.ccs.wiredroid.ui;

import android.app.Activity;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.ccs.wiredroid.R;
import com.ccs.wiredroid.debug.BookmarkManager;
import com.ccs.wiredroid.util.AppTheme;

import java.io.File;
import java.util.List;

public final class BookmarkUiController {

    public interface Callback {
        void onBookmarkClicked(File file, int line);
        AppTheme getTheme();
    }

    private final Activity activity;
    private final Callback callback;
    private RecyclerView recycler;

    public BookmarkUiController(Activity activity, Callback callback) {
        this.activity = activity;
        this.callback = callback;
    }

    public void bind() {
        recycler = activity.findViewById(R.id.bookmarksRecycler);
    }

    public void setVisibility(boolean visible) {
        if (recycler != null) recycler.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    // Tab styling used to live here as well as in MainActivity, and the two
    // disagreed on the active colour. BottomPanelController now owns it.

    public void refreshList() {
        if (recycler == null) return;
        BookmarkManager bm = BookmarkManager.getInstance(activity);
        List<BookmarkManager.BookmarkEntry> all = bm.getAllBookmarks();
        AppTheme theme = callback.getTheme();

        if (all.isEmpty()) {
            recycler.setLayoutManager(new LinearLayoutManager(activity));
            recycler.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                @Override public int getItemCount() { return 1; }
                @Override public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
                    return new RecyclerView.ViewHolder(new TextView(parent.getContext())) {};
                }
                @Override public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
                    ((TextView) holder.itemView).setText(R.string.bookmark_none);
                    ((TextView) holder.itemView).setTextColor(theme.textDim);
                    ((TextView) holder.itemView).setPadding(32, 32, 32, 32);
                }
            });
            return;
        }

        recycler.setLayoutManager(new LinearLayoutManager(activity));
        recycler.setAdapter(new RecyclerView.Adapter<BookmarkVH>() {
            @Override public int getItemCount() { return all.size(); }
            @Override public BookmarkVH onCreateViewHolder(ViewGroup parent, int viewType) {
                TextView tv = new TextView(parent.getContext());
                tv.setLayoutParams(new RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, (int)(48 * activity.getResources().getDisplayMetrics().density)));
                tv.setPadding(32, 16, 32, 16);
                tv.setTextSize(13);
                tv.setGravity(Gravity.CENTER_VERTICAL);
                return new BookmarkVH(tv);
            }
            @Override public void onBindViewHolder(BookmarkVH holder, int position) {
                BookmarkManager.BookmarkEntry e = all.get(position);
                String name = new File(e.filePath).getName();
                ((TextView) holder.itemView).setText("★ " + name + ":" + e.line);
                ((TextView) holder.itemView).setTextColor(theme != null ? theme.accent : 0xFFFFD700);
                holder.itemView.setOnClickListener(v -> callback.onBookmarkClicked(new File(e.filePath), e.line));
            }
        });
    }

    public RecyclerView getRecycler() { return recycler; }

    static class BookmarkVH extends RecyclerView.ViewHolder {
        BookmarkVH(View itemView) { super(itemView); }
    }
}
