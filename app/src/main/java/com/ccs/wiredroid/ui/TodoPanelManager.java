package com.ccs.wiredroid.ui;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.ccs.wiredroid.R;
import com.ccs.wiredroid.analysis.ProblemItem;
import com.ccs.wiredroid.analysis.TodoAdapter;
import com.ccs.wiredroid.project.ProjectManager;
import com.ccs.wiredroid.project.ProjectScanner;
import com.ccs.wiredroid.util.AppTheme;

import java.io.File;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class TodoPanelManager {

    public interface Callback {
        void onTodoItemClicked(File file, int line);
        void runOnUiThread(@NonNull Runnable r);
        ProjectManager getProjectManager();
        AppTheme getTheme();
        /** The file on screen, or null when no tab is open; used by the File scope. */
        File getCurrentFile();
    }

    private static final ExecutorService TODO_WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(() -> {
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            } catch (Throwable ignored) {}
            r.run();
        }, "todo-worker");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    private final Activity activity;
    private final Callback callback;

    /** False until the ViewStub holding this panel has been inflated. */
    private boolean panelInflated;
    /** Last theme handed to applyTheme, re-applied to views inflated later. */
    private AppTheme lastTheme;
    private final TodoAdapter adapter;

    private View panel;
    private TextView tab;
    private RecyclerView recycler;
    private EditText search;
    private TextView status;
    private TextView scopeProject;
    private TextView scopeFile;

    /**
     * Whether the list covers only the open file.
     *
     * <p>Remembered between sessions: someone who works file by file means it
     * every time, and re-picking the scope on every launch is the kind of small
     * tax an IDE should not charge.</p>
     */
    private boolean currentFileOnly;

    private boolean autoRefreshPending = false;
    private boolean dirtyWhileHidden = false;
    private final Handler autoRefreshHandler = new Handler(Looper.getMainLooper());
    private final Runnable autoRefreshRunnable = () -> {
        if (autoRefreshPending) {
            autoRefreshPending = false;
            if (isPanelVisible()) {
                refresh();
            } else {
                dirtyWhileHidden = true;
            }
        }
    };

    public TodoPanelManager(Activity activity, Callback callback) {
        this.activity = activity;
        this.callback = callback;
        this.adapter = new TodoAdapter();
        this.currentFileOnly =
                new com.ccs.wiredroid.util.AppPreferences(activity).isTodoScopeCurrentFile();
    }

    /** The tab exists from the start; the panel inflates on first open. */
    public void bind() {
        tab = activity.findViewById(R.id.tabTodo);
    }

    private boolean isPanelVisible() {
        return panel != null && panel.getVisibility() == View.VISIBLE;
    }

    /**
     * Inflates the panel the first time it is opened. It sits behind a ViewStub
     * because it starts hidden and most sessions never open it, yet it used to
     * be inflated on every cold start along with every other bottom panel.
     */
    private void ensurePanel() {
        if (panelInflated) return;
        panelInflated = true;
        android.view.ViewStub stub = activity.findViewById(R.id.stubTodoPanel);
        if (stub != null) stub.inflate();
        panel = activity.findViewById(R.id.todoPanel);
        recycler = activity.findViewById(R.id.todoRecycler);
        search = activity.findViewById(R.id.todoSearch);
        status = activity.findViewById(R.id.todoStatus);

        adapter.setTheme(callback.getTheme());
        adapter.setListener(item -> {
            if (item.file != null && item.file.exists()) {
                callback.onTodoItemClicked(item.file, item.line);
            }
        });

        if (recycler != null) {
            recycler.setLayoutManager(new LinearLayoutManager(activity));
            recycler.setAdapter(adapter);
        }
        if (search != null) {
            search.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void afterTextChanged(Editable s) {
                    adapter.filter(s != null ? s.toString() : "");
                }
            });
        }
        scopeProject = activity.findViewById(R.id.todoScopeProject);
        scopeFile = activity.findViewById(R.id.todoScopeFile);
        if (scopeProject != null) scopeProject.setOnClickListener(v -> setCurrentFileOnly(false));
        if (scopeFile != null) scopeFile.setOnClickListener(v -> setCurrentFileOnly(true));
        paintScope();

        View refreshBtn = activity.findViewById(R.id.todoRefresh);
        if (refreshBtn != null) refreshBtn.setOnClickListener(v -> refresh());
        if (lastTheme != null) applyTheme(lastTheme);
    }

    public void applyTheme(@NonNull AppTheme theme) {
        lastTheme = theme;
        if (panel != null) panel.setBackgroundColor(theme.consoleBg);
        if (recycler != null) recycler.setBackgroundColor(theme.consoleBg);
        if (search != null) {
            search.setTextColor(theme.consoleText);
            search.setHintTextColor(theme.textDim);
        }
        if (status != null) {
            status.setTextColor(theme.textDim);
            status.setBackgroundColor(theme.consoleBg);
        }
        View todoToolbar = panel != null ? panel.findViewById(R.id.todoToolbar) : null;
        if (todoToolbar != null) todoToolbar.setBackgroundColor(theme.toolbar);
        View todoToolbarSep = panel != null ? panel.findViewById(R.id.todoToolbarSep) : null;
        if (todoToolbarSep != null) todoToolbarSep.setBackgroundColor(theme.separator);
        View todoDivider = panel != null ? panel.findViewById(R.id.todoDivider) : null;
        if (todoDivider != null) todoDivider.setBackgroundColor(theme.separator);

        View todoRefresh = panel != null ? panel.findViewById(R.id.todoRefresh) : null;
        if (todoRefresh != null) ((TextView) todoRefresh).setTextColor(theme.accent);
        View scopeSep = panel != null ? panel.findViewById(R.id.todoScopeSep) : null;
        if (scopeSep != null) scopeSep.setBackgroundColor(theme.separator);
        paintScope();
        adapter.setTheme(theme);
    }

    public void updateTabStyle(boolean active, @NonNull AppTheme theme, int activeBg) {
        if (tab != null) {
            tab.setBackgroundColor(active ? activeBg : theme.toolbar);
            tab.setTextColor(active ? theme.accent : theme.textDim);
        }
    }

    public void setVisibility(boolean visible) {
        if (visible) {
            ensurePanel();
            if (dirtyWhileHidden || adapter.getItemCount() == 0) {
                dirtyWhileHidden = false;
                refresh();
            }
        }
        if (panel != null) panel.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    public void scheduleAutoRefresh() {
        if (!isPanelVisible()) {
            dirtyWhileHidden = true;
            return;
        }
        autoRefreshPending = true;
        autoRefreshHandler.removeCallbacks(autoRefreshRunnable);
        autoRefreshHandler.postDelayed(autoRefreshRunnable, 1500);
    }

    public void cancelAutoRefresh() {
        autoRefreshPending = false;
        autoRefreshHandler.removeCallbacks(autoRefreshRunnable);
    }

    public void refresh() {
        ensurePanel();
        if (adapter == null || status == null) return;
        ProjectManager pm = callback.getProjectManager();
        if (pm == null) return;

        // Read on the main thread: the scope is a view's state, and the worker
        // must not go looking at which tab is open.
        final boolean fileOnly = currentFileOnly;
        final File openFile = fileOnly ? callback.getCurrentFile() : null;

        TODO_WORKER.execute(() -> {
            File root = pm.getProjectDir();
            if (root == null && !fileOnly) {
                callback.runOnUiThread(() -> {
                    adapter.setItems(new ArrayList<>());
                    status.setText(R.string.toast_no_project);
                });
                return;
            }
            if (fileOnly && openFile == null) {
                callback.runOnUiThread(() -> {
                    adapter.setItems(new ArrayList<>());
                    status.setText(R.string.todo_scope_no_file);
                });
                return;
            }

            // Every language, and a pass that actually looks for the tags. This
            // used to run the static analyzer and keep whatever mentioned TODO,
            // which was nothing: no rule emits such a message, so the panel was
            // always empty.
            java.util.List<File> sources = fileOnly
                    ? java.util.Collections.singletonList(openFile)
                    : ProjectScanner.listAllSources(root);
            java.util.List<ProblemItem> allProblems =
                    com.ccs.wiredroid.analysis.TodoScanner.scan(sources);

            int todoCount = 0;
            int fixmeCount = 0;
            for (ProblemItem p : allProblems) {
                if (p.message != null && p.message.contains("TODO")) todoCount++;
                if (p.message != null && p.message.contains("FIXME")) fixmeCount++;
            }
            final int finalTodoCount = todoCount;
            final int finalFixmeCount = fixmeCount;

            callback.runOnUiThread(() -> {
                adapter.setItems(allProblems);
                // The file is named in file scope, so an empty list reads as
                // "none in this file" rather than "none anywhere".
                status.setText(finalTodoCount + " TODO, " + finalFixmeCount + " FIXME"
                        + (fileOnly ? "  ·  " + openFile.getName() : ""));
                if (search != null && !search.getText().toString().isEmpty()) {
                    adapter.filter(search.getText().toString());
                }
            });
        });
    }

    /** Text representation of the currently visible TODO/FIXME rows for clipboard export. */
    public String getCopyText() {
        StringBuilder out = new StringBuilder();
        File root = callback.getProjectManager() == null
                ? null : callback.getProjectManager().getProjectDir();
        for (TodoAdapter.TodoItem item : adapter.getVisibleItems()) {
            String path = item.file == null ? "?" : item.file.getPath();
            if (root != null && item.file != null) {
                String rootPath = root.getAbsolutePath();
                String fullPath = item.file.getAbsolutePath();
                if (fullPath.startsWith(rootPath + File.separator)) {
                    path = fullPath.substring(rootPath.length() + 1);
                }
            }
            out.append(item.tag).append(' ').append(path);
            if (item.line > 0) out.append(':').append(item.line);
            out.append(" — ").append(item.text == null ? "" : item.text).append('\n');
        }
        return out.toString().trim();
    }

    /**
     * Rescans when the tab changed and the list is showing one file.
     *
     * <p>Silent in project scope, where the same list already holds for every
     * file, and while the panel is hidden.</p>
     */
    public void onCurrentFileChanged() {
        if (!currentFileOnly || !isPanelVisible()) return;
        refresh();
    }

    /** Switches scope and reruns the scan; a no-op when the scope is unchanged. */
    private void setCurrentFileOnly(boolean fileOnly) {
        if (currentFileOnly == fileOnly) return;
        currentFileOnly = fileOnly;
        new com.ccs.wiredroid.util.AppPreferences(activity).setTodoScopeCurrentFile(fileOnly);
        paintScope();
        refresh();
    }

    /** The selected half in the accent colour, the other dimmed. */
    private void paintScope() {
        AppTheme theme = lastTheme != null ? lastTheme : callback.getTheme();
        if (theme == null) return;
        if (scopeProject != null) {
            scopeProject.setTextColor(currentFileOnly ? theme.textDim : theme.accent);
        }
        if (scopeFile != null) {
            scopeFile.setTextColor(currentFileOnly ? theme.accent : theme.textDim);
        }
    }

    public View getPanel() { return panel; }
    public TextView getTab() { return tab; }
}
