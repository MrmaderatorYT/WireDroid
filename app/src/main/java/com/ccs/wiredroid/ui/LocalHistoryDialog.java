package com.ccs.wiredroid.ui;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.ccs.wiredroid.R;
import com.ccs.wiredroid.git.GitDiffActivity;
import com.ccs.wiredroid.util.AppTheme;
import com.ccs.wiredroid.util.LocalHistoryDiff;
import com.ccs.wiredroid.util.LocalHistoryManager;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** Timeline, compare, preview and surgical restore UI for local history. */
public final class LocalHistoryDialog {
    public interface OnRevertListener { void onRevert(String content); }
    private LocalHistoryDialog() {}

    public static void show(Context context, AppTheme theme, File file, OnRevertListener listener) {
        show(context, theme, file, readCurrent(file), listener);
    }

    public static void show(Context context, AppTheme theme, File file, String current,
                            OnRevertListener listener) {
        if (context == null || file == null) return;
        LocalHistoryManager.prune(context, file);
        List<LocalHistoryManager.HistoryEntry> history = LocalHistoryManager.getHistory(context, file);
        List<String> items = new ArrayList<>();
        items.add("＋ " + context.getString(R.string.history_named_snapshot));
        if (history.size() >= 2) items.add("⇄ " + context.getString(R.string.history_compare_versions));
        for (LocalHistoryManager.HistoryEntry entry : history) {
            items.add((entry.named ? "★ " : "• ") + entry.formattedTime + "  " + entry.label);
        }
        Dialogs.rounded(context).setTitle(context.getString(R.string.history_timeline_title, file.getName()))
                .setItems(items.toArray(new String[0]), (dialog, which) -> {
                    if (which == 0) { createNamed(context, theme, file, current, listener); return; }
                    int offset = history.size() >= 2 ? 2 : 1;
                    if (history.size() >= 2 && which == 1) {
                        selectComparison(context, history); return;
                    }
                    showEntryActions(context, theme, file, current, history.get(which - offset), listener);
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private static void createNamed(Context context, AppTheme theme, File file, String current,
                                    OnRevertListener listener) {
        EditText input = new EditText(context);
        input.setHint(R.string.history_snapshot_name_hint);
        if (theme != null) { input.setTextColor(theme.text); input.setHintTextColor(theme.textDim); }
        Dialogs.rounded(context).setTitle(R.string.history_named_snapshot).setView(input)
                .setPositiveButton(R.string.dialog_create, (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) return;
                    LocalHistoryManager.saveNamedSnapshot(context, file, current, name);
                    Toast.makeText(context, R.string.history_snapshot_created, Toast.LENGTH_SHORT).show();
                    show(context, theme, file, current, listener);
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private static void showEntryActions(Context context, AppTheme theme, File file, String current,
                                         LocalHistoryManager.HistoryEntry entry,
                                         OnRevertListener listener) {
        String[] actions = { context.getString(R.string.history_preview_diff),
                context.getString(R.string.history_view_content),
                context.getString(R.string.history_restore_all),
                context.getString(R.string.history_restore_fragment),
                context.getString(R.string.history_delete_snapshot) };
        Dialogs.rounded(context).setTitle(entry.formattedTime + "  " + entry.label)
                .setItems(actions, (d, which) -> {
                    if (which == 0) showDiff(context, current, entry.content,
                            "Current " + file.getName(), entry.formattedTime);
                    else if (which == 1) showText(context, theme, entry.content,
                            entry.formattedTime + "  " + entry.label);
                    else if (which == 2) {
                        LocalHistoryManager.saveSnapshot(context, file, current, "Before restore");
                        listener.onRevert(entry.content);
                        Toast.makeText(context, R.string.history_restored, Toast.LENGTH_SHORT).show();
                    } else if (which == 3) restoreFragment(context, file, current, entry, listener);
                    else confirmDelete(context, theme, file, current, entry, listener);
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private static void restoreFragment(Context context, File file, String current,
                                        LocalHistoryManager.HistoryEntry entry,
                                        OnRevertListener listener) {
        List<LocalHistoryDiff.Hunk> hunks = LocalHistoryDiff.hunks(current, entry.content);
        if (hunks.isEmpty()) {
            Toast.makeText(context, R.string.history_no_differences, Toast.LENGTH_SHORT).show(); return;
        }
        String[] labels = new String[hunks.size()];
        for (int i = 0; i < hunks.size(); i++) labels[i] = hunks.get(i).title();
        Dialogs.rounded(context).setTitle(R.string.history_choose_fragment).setItems(labels, (d, which) -> {
            LocalHistoryManager.saveSnapshot(context, file, current, "Before fragment restore");
            listener.onRevert(LocalHistoryDiff.restoreHunk(current, entry.content, hunks.get(which)));
            Toast.makeText(context, R.string.history_fragment_restored, Toast.LENGTH_SHORT).show();
        }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private static void selectComparison(Context context,
                                         List<LocalHistoryManager.HistoryEntry> history) {
        String[] labels = labels(history);
        Dialogs.rounded(context).setTitle(R.string.history_compare_from).setItems(labels,
                (firstDialog, first) -> Dialogs.rounded(context).setTitle(R.string.history_compare_to)
                        .setItems(labels, (secondDialog, second) -> {
                            if (first == second) {
                                Toast.makeText(context, R.string.history_choose_different, Toast.LENGTH_SHORT).show();
                                return;
                            }
                            LocalHistoryManager.HistoryEntry a = history.get(first), b = history.get(second);
                            showDiff(context, a.content, b.content, a.formattedTime + " " + a.label,
                                    b.formattedTime + " " + b.label);
                        }).setNegativeButton(android.R.string.cancel, null).show())
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    private static String[] labels(List<LocalHistoryManager.HistoryEntry> history) {
        String[] labels = new String[history.size()];
        for (int i = 0; i < history.size(); i++) {
            LocalHistoryManager.HistoryEntry e = history.get(i);
            labels[i] = (e.named ? "★ " : "") + e.formattedTime + "  " + e.label;
        }
        return labels;
    }

    private static void showDiff(Context context, String a, String b, String aName, String bName) {
        String diff = LocalHistoryDiff.unifiedDiff(a, b, aName, bName);
        if (context instanceof Activity) GitDiffActivity.launchText(context, diff,
                context.getString(R.string.history_diff_title));
        else showText(context, null, diff, context.getString(R.string.history_diff_title));
    }

    private static void showText(Context context, AppTheme theme, String content, String title) {
        ScrollView scroll = new ScrollView(context);
        TextView text = new TextView(context);
        text.setText(content); text.setTextSize(12); text.setTypeface(Typeface.MONOSPACE);
        text.setTextIsSelectable(true);
        int padding = Math.round(16 * context.getResources().getDisplayMetrics().density);
        text.setPadding(padding, padding, padding, padding);
        if (theme != null) { text.setTextColor(theme.text); scroll.setBackgroundColor(theme.bg); }
        scroll.addView(text);
        Dialogs.rounded(context).setTitle(title).setView(scroll)
                .setPositiveButton(android.R.string.ok, null).show();
    }

    private static void confirmDelete(Context context, AppTheme theme, File file, String current,
                                      LocalHistoryManager.HistoryEntry entry,
                                      OnRevertListener listener) {
        Dialogs.rounded(context).setTitle(R.string.history_delete_snapshot)
                .setMessage(context.getString(R.string.history_delete_confirm, entry.label))
                .setPositiveButton(R.string.history_delete_snapshot, (d, w) -> {
                    LocalHistoryManager.deleteSnapshot(entry);
                    show(context, theme, file, current, listener);
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private static String readCurrent(File file) {
        try { return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8); }
        catch (Exception ignored) { return ""; }
    }
}
