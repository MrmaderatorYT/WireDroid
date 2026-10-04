package com.ccs.wiredroid.ui;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.ccs.wiredroid.R;
import com.ccs.wiredroid.git.GitGutterComputer;
import com.ccs.wiredroid.util.AppTheme;
import com.ccs.wiredroid.util.Colors;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.rosemoe.sora.text.Content;
import io.github.rosemoe.sora.widget.CodeEditor;

/**
 * Keeps each editor's {@link GitGutterOverlay} in step with the buffer.
 *
 * <p>Optimized with snapshot hashing and single background worker to prevent CPU thrashing.</p>
 */
public final class GitGutterController {

    /** Quiet period after the last edit before a recompute is worth doing. */
    private static final long DEBOUNCE_MS = 450L;

    /** One thread for the whole process: diffs are serialized, never piled up. */
    private static final ExecutorService WORKER =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "git-gutter");
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            });

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Binding> bindings = new ArrayList<>(2);
    private AppTheme theme;

    public GitGutterController(Context context, AppTheme theme) {
        this.context = context;
        this.theme = theme;
    }

    private static final class Binding {
        final CodeEditor editor;
        final GitGutterOverlay overlay;
        final AtomicInteger generation = new AtomicInteger();
        File file;
        Runnable pending;
        int lastSnapshotHash;

        Binding(CodeEditor editor, GitGutterOverlay overlay) {
            this.editor = editor;
            this.overlay = overlay;
        }
    }

    // ── Wiring ────────────────────────────────────────────────

    public void bind(CodeEditor editor, GitGutterOverlay overlay) {
        if (editor == null || overlay == null) return;
        for (Binding b : bindings) {
            if (b.editor == editor) return;
        }
        Binding binding = new Binding(editor, overlay);
        overlay.setEditor(editor);
        overlay.setOnHunkClickListener(hunk -> showHunkDialog(binding, hunk));
        bindings.add(binding);
        applyTheme(theme);
    }

    /** Points an editor at a new file and recomputes immediately. */
    public void setFile(CodeEditor editor, File file) {
        Binding b = find(editor);
        if (b == null) return;
        b.file = file;
        b.lastSnapshotHash = 0;
        b.overlay.clearHunks();
        cancelPending(b);
        if (file == null) return;
        run(b);
    }

    /** Debounced recompute — safe to call on every keystroke. */
    public void schedule(CodeEditor editor) {
        Binding b = find(editor);
        if (b == null || b.file == null) return;
        cancelPending(b);
        b.pending = () -> {
            b.pending = null;
            run(b);
        };
        main.postDelayed(b.pending, DEBOUNCE_MS);
    }

    /** Cancels any debounced recomputes waiting on the main handler. */
    public void cancelAllPending() {
        for (Binding b : bindings) {
            cancelPending(b);
        }
    }

    /** Recomputes every bound editor — used after saves and Git operations. */
    public void refreshAll() {
        for (Binding b : bindings) {
            if (b.file != null) {
                b.lastSnapshotHash = 0;
                cancelPending(b);
                run(b);
            }
        }
    }

    /** Repaints without recomputing — used while scrolling. */
    public void invalidate(CodeEditor editor) {
        Binding b = find(editor);
        if (b != null) b.overlay.postInvalidate();
    }

    public void applyTheme(AppTheme t) {
        this.theme = t;
        int added = 0xFF4CAF50;
        int modified = 0xFF3F8CD0;
        int deleted = 0xFFE05252;
        if (t != null) {
            // Nudge each mark towards the editor background so it reads as
            // chrome rather than as content, on light and dark alike.
            added = Colors.blend(added, t.bg, t.dark ? 0.12f : 0.05f);
            modified = Colors.blend(modified, t.bg, t.dark ? 0.12f : 0.05f);
            deleted = Colors.blend(t.errorText, t.bg, t.dark ? 0.10f : 0.05f);
        }
        for (Binding b : bindings) {
            b.overlay.setColors(added, modified, deleted);
        }
    }

    // ── Recompute ─────────────────────────────────────────────

    private void cancelPending(Binding b) {
        if (b.pending != null) {
            main.removeCallbacks(b.pending);
            b.pending = null;
        }
    }

    private void run(Binding b) {
        final File file = b.file;
        if (file == null) return;

        // Snapshot on the main thread: sora's Content is not safe to read from
        // the worker while the user is typing into it.
        final String snapshot;
        try {
            Content text = b.editor.getText();
            if (text == null) return;
            snapshot = text.toString();
        } catch (Throwable ignored) {
            return;
        }

        final int hash = snapshot.hashCode();
        if (b.lastSnapshotHash == hash && b.lastSnapshotHash != 0) {
            return; // unchanged, skip diffing
        }

        b.lastSnapshotHash = hash;
        final int generation = b.generation.incrementAndGet();

        WORKER.execute(() -> {
            final List<GitGutterComputer.Hunk> hunks = GitGutterComputer.compute(file, snapshot);
            main.post(() -> {
                if (b.generation.get() != generation) return;   // superseded
                if (b.file != file) return;                     // file switched
                b.overlay.setHunks(hunks);
            });
        });
    }

    private Binding find(CodeEditor editor) {
        for (Binding b : bindings) {
            if (b.editor == editor) return b;
        }
        return null;
    }

    // ── Hunk dialog ───────────────────────────────────────────

    private void showHunkDialog(Binding b, GitGutterComputer.Hunk hunk) {
        if (hunk == null) return;
        String title;
        switch (hunk.type) {
            case GitGutterComputer.TYPE_ADDED:
                title = context.getString(R.string.gutter_hunk_added, hunk.startLine + 1);
                break;
            case GitGutterComputer.TYPE_DELETED:
                title = context.getString(R.string.gutter_hunk_deleted, hunk.startLine + 1);
                break;
            default:
                title = context.getString(R.string.gutter_hunk_modified, hunk.startLine + 1);
                break;
        }

        String body = hunk.originalText();
        if (body.isEmpty()) body = context.getString(R.string.gutter_hunk_no_original);

        boolean canRevert = b.editor.isEditable();
        var builder = Dialogs.rounded(context)
                .setTitle(title)
                .setMessage(body)
                .setNegativeButton(R.string.gutter_close, null);
        if (canRevert) {
            builder.setPositiveButton(R.string.gutter_revert_hunk, (d, w) -> revert(b, hunk));
        }
        builder.show();
    }

    private void revert(Binding b, GitGutterComputer.Hunk hunk) {
        try {
            Content content = b.editor.getText();
            replaceLines(content, hunk.startLine, hunk.endLine, hunk.originalLines);
            Toast.makeText(context, R.string.gutter_reverted, Toast.LENGTH_SHORT).show();
            refreshAll();
        } catch (Throwable t) {
            Toast.makeText(context, R.string.gutter_revert_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private static void replaceLines(Content content, int start, int end, List<String> newLines) {
        int lineCount = content.getLineCount();
        int s = Math.max(0, Math.min(start, lineCount));
        int e = Math.max(s, Math.min(end, lineCount));

        StringBuilder sb = new StringBuilder();
        for (String line : newLines) sb.append(line).append('\n');

        if (e < lineCount) {
            content.replace(s, 0, e, 0, sb);
            return;
        }

        if (sb.length() > 0) sb.setLength(sb.length() - 1);
        int lastLine = lineCount - 1;
        if (s > lastLine) {
            if (sb.length() == 0) return;
            content.insert(lastLine, content.getColumnCount(lastLine), "\n" + sb);
        } else {
            content.replace(s, 0, lastLine, content.getColumnCount(lastLine), sb);
        }
    }
}
