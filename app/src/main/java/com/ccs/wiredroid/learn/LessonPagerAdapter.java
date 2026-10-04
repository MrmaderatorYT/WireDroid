package com.ccs.wiredroid.learn;

import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.ccs.wiredroid.util.AppTheme;

import java.util.List;

/**
 * One lesson per page.
 *
 * <p>Each page is its own vertically scrolling list, so a lesson keeps its own
 * scroll position while the pager keeps three of them alive either side. A
 * fragment-based pager would give the same effect at the cost of a fragment
 * lifecycle this screen has no other use for.</p>
 */
public class LessonPagerAdapter extends RecyclerView.Adapter<LessonPagerAdapter.PageHolder> {

    /** Handed to every page so a Run button behaves the same wherever it is. */
    public interface SnippetRunner {
        void run(LessonBlock block, LessonBlockAdapter.RunCallback callback);
    }

    public interface PlaygroundOpener {
        void openPlayground(LessonBlock block);
    }

    private final List<Lesson> lessons;
    private final AppTheme theme;
    private final SnippetRunner runner;
    private final PlaygroundOpener playgroundOpener;
    private int language;

    public LessonPagerAdapter(List<Lesson> lessons, AppTheme theme, int language,
                              SnippetRunner runner, PlaygroundOpener playgroundOpener) {
        this.lessons = lessons;
        this.theme = theme;
        this.language = language;
        this.runner = runner;
        this.playgroundOpener = playgroundOpener;
    }

    @NonNull
    @Override
    public PageHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        RecyclerView rv = new RecyclerView(parent.getContext());
        rv.setLayoutParams(new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        rv.setLayoutManager(new LinearLayoutManager(parent.getContext()));
        float density = parent.getResources().getDisplayMetrics().density;
        rv.setPadding(0, Math.round(6 * density), 0, Math.round(16 * density));
        rv.setClipToPadding(false);
        rv.setBackgroundColor(theme.bg);
        return new PageHolder(rv);
    }

    @Override
    public void onBindViewHolder(@NonNull PageHolder holder, int position) {
        Lesson lesson = lessons.get(position);
        holder.list.setAdapter(new LessonBlockAdapter(
                lesson.content(language), theme, runner::run,
                playgroundOpener != null ? playgroundOpener::openPlayground : null));
    }

    @Override
    public int getItemCount() {
        return lessons.size();
    }

    static class PageHolder extends RecyclerView.ViewHolder {
        final RecyclerView list;

        PageHolder(RecyclerView itemView) {
            super(itemView);
            this.list = itemView;
        }
    }
}
