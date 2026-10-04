package com.ccs.wiredroid.ui;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;

import com.ccs.wiredroid.R;
import com.ccs.wiredroid.tools.compilers.ProjectCompiler;
import com.ccs.wiredroid.util.AppTheme;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One JShell session: one scrollback, one input field, one history.
 *
 * <p>Every instance owns a private scratch directory and a private worker
 * thread, so several sessions can live side by side (see
 * {@link SplitTerminalActivity}) without one overwriting another's class files
 * or spilling output into the wrong pane. Only {@code android.jar} is shared —
 * it is 60 MB of read-only asset, and extracting a copy per pane would be
 * absurd.</p>
 */
public final class JShellPanelManager {

    /** Names the scratch directory of each live session apart from the others. */
    private static final AtomicInteger SESSION_IDS = new AtomicInteger();

    /** Two sessions must not race to extract the shared {@code android.jar}. */
    private static final Object ANDROID_JAR_LOCK = new Object();

    private static final String MAIN_CLASS = "JShellMain";

    /** Notified when the session starts or finishes running a command. */
    public interface StateListener {
        void onBusyChanged(boolean busy);
    }

    private final Activity activity;
    private final View panel;
    private final TextView tab;
    private final ScrollView scrollView;
    private final TextView consoleOutput;
    private final EditText inputField;

    private final File sessionDir;
    private final ExecutorService worker;
    private final AtomicInteger pending = new AtomicInteger();

    private final List<String> history = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private StateListener stateListener;

    /**
     * Output already shown to the user.
     *
     * <p>A snippet is executed by replaying the whole history, so each run
     * reprints everything the earlier lines printed. Remembering the previous
     * run lets us show only what the newest line added.</p>
     */
    private String shownOutput = "";

    /** For callers that have no tab header — the split terminal labels its own panes. */
    public JShellPanelManager(Activity activity, View panel, ScrollView scrollView,
                              TextView consoleOutput, EditText inputField) {
        this(activity, panel, null, scrollView, consoleOutput, inputField);
    }

    public JShellPanelManager(Activity activity, View panel, TextView tab, ScrollView scrollView, TextView consoleOutput, EditText inputField) {
        this.activity = activity;
        this.panel = panel;
        this.tab = tab;
        this.scrollView = scrollView;
        this.consoleOutput = consoleOutput;
        this.inputField = inputField;

        this.sessionDir = new File(new File(activity.getCacheDir(), "jshell_cache"),
                "session_" + SESSION_IDS.incrementAndGet());
        this.worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "jshell-" + sessionDir.getName());
            t.setDaemon(true);
            return t;
        });

        appendOutput(activity.getString(R.string.terminal_welcome));

        this.inputField.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE ||
                (event != null && event.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER && event.getAction() == android.view.KeyEvent.ACTION_DOWN)) {
                String code = this.inputField.getText().toString();
                if (!code.isEmpty()) {
                    this.inputField.setText("");
                    executeCode(code);
                }
                return true;
            }
            return false;
        });
    }

    public void applyTheme(AppTheme theme) {
        if (panel != null) panel.setBackgroundColor(theme.consoleBg);
        if (consoleOutput != null) consoleOutput.setTextColor(theme.consoleText);
        if (inputField != null) {
            inputField.setTextColor(theme.text);
            inputField.setHintTextColor(theme.textDim);
            inputField.setBackgroundColor(theme.toolbar);
        }
    }

    public View getPanel() {
        return panel;
    }

    public TextView getTab() {
        return tab;
    }

    public TextView getConsoleOutput() {
        return consoleOutput;
    }

    public EditText getInputField() {
        return inputField;
    }

    public void setVisibility(int visibility) {
        panel.setVisibility(visibility);
    }

    public void setStateListener(StateListener listener) {
        this.stateListener = listener;
    }

    /** True while a command is running or waiting its turn in this session. */
    public boolean isBusy() {
        return pending.get() > 0;
    }

    /** Stops the worker and drops the scratch directory. The session is unusable afterwards. */
    public void release() {
        stateListener = null;
        worker.shutdownNow();
        final File dir = sessionDir;
        Thread cleanup = new Thread(() -> deleteRecursive(dir), "jshell-cleanup");
        cleanup.setDaemon(true);
        cleanup.start();
    }

    private void appendOutput(String text) {
        handler.post(() -> {
            consoleOutput.append(text);
            scrollView.post(() -> scrollView.fullScroll(ScrollView.FOCUS_DOWN));
        });
    }

    private void executeCode(String code) {
        String trimmed = code.trim();
        appendOutput(activity.getString(R.string.terminal_prompt_echo, code));

        // Handle internal JShell commands
        if (trimmed.startsWith("/")) {
            handleInternalCommand(trimmed);
            return;
        }

        synchronized (history) {
            history.add(code);
        }
        pending.incrementAndGet();
        setBusy(true);

        try {
            worker.execute(() -> {
                try {
                    runSnippet();
                } catch (Exception e) {
                    dropLastHistoryEntry();
                    appendOutput(activity.getString(R.string.terminal_error, String.valueOf(e.getMessage())));
                } finally {
                    setBusy(pending.decrementAndGet() > 0);
                }
            });
        } catch (RuntimeException rejected) {           // session already released
            setBusy(pending.decrementAndGet() > 0);
        }
    }

    /** Compiles the whole history into one class and runs it, printing only the new output. */
    private void runSnippet() throws Exception {
        File sharedRoot = new File(activity.getCacheDir(), "jshell_cache");
        if (!sessionDir.isDirectory() && !sessionDir.mkdirs() && !sessionDir.isDirectory()) {
            throw new IOException(activity.getString(
                    R.string.terminal_error_scratch_dir, sessionDir.getAbsolutePath()));
        }

        List<String> snapshot;
        synchronized (history) {
            snapshot = new ArrayList<>(history);
        }

        StringBuilder src = new StringBuilder();
        src.append("import java.util.*;\nimport java.io.*;\nimport java.math.*;\n");
        src.append("public class ").append(MAIN_CLASS).append(" {\n");
        src.append("  public static void main(String[] args) throws Exception {\n");
        for (String line : snapshot) {
            if (!line.trim().endsWith(";") && !line.trim().endsWith("}") && !line.trim().endsWith("{")) {
                src.append("    ").append(line).append(";\n");
            } else {
                src.append("    ").append(line).append("\n");
            }
        }
        src.append("  }\n}\n");

        File srcFile = new File(sessionDir, MAIN_CLASS + ".java");
        ProjectCompiler.writeUtf8Public(srcFile, src.toString());

        File androidJar;
        synchronized (ANDROID_JAR_LOCK) {
            androidJar = ProjectCompiler.ensureAndroidJarPublic(activity, sharedRoot);
        }

        String ecjErr = ProjectCompiler.compileEcjPublic(androidJar, null, sessionDir, "1.8", srcFile);
        if (ecjErr != null) {
            dropLastHistoryEntry();
            appendOutput(activity.getString(R.string.terminal_error, ecjErr));
            return;
        }

        File classFile = ProjectCompiler.findClassFilePublic(sessionDir, MAIN_CLASS);
        if (classFile == null) {
            dropLastHistoryEntry();
            appendOutput(activity.getString(R.string.terminal_error_no_class));
            return;
        }

        File dexDir = new File(sessionDir, "dex");
        if (!dexDir.exists()) {
            dexDir.mkdirs();
        } else {
            File[] oldFiles = dexDir.listFiles();
            if (oldFiles != null) for (File f : oldFiles) f.delete();
        }

        ProjectCompiler.runD8DexPublic(androidJar, dexDir, classFile);

        ProjectCompiler.debugRunDex(activity, MAIN_CLASS, dexDir, sessionDir, null, new ProjectCompiler.Callback() {
            @Override public void onProgress(String msg) { appendOutput(msg + "\n"); }
            @Override public void onResult(String res) { printRunOutput(res); }
            @Override public void onProblems(java.util.List<com.ccs.wiredroid.analysis.ProblemItem> problems) {}
        });
    }

    /**
     * Prints what this run added on top of the previous one.
     *
     * <p>Replaying the history reprints the older lines' output; showing all of
     * it again after every command would be unreadable. When the new output is
     * not an extension of the old one — a snippet that threw, or one that prints
     * the time — there is nothing to trim and everything is shown.</p>
     */
    private void printRunOutput(String res) {
        String full = res == null ? "" : res;
        String fresh = full.startsWith(shownOutput) ? full.substring(shownOutput.length()) : full;
        shownOutput = full;
        if (!fresh.isEmpty()) {
            appendOutput(fresh.endsWith("\n") ? fresh : fresh + "\n");
        }
        appendOutput("\n");
    }

    private void dropLastHistoryEntry() {
        synchronized (history) {
            if (!history.isEmpty()) history.remove(history.size() - 1);
        }
    }

    private void setBusy(boolean busy) {
        handler.post(() -> {
            StateListener listener = stateListener;
            if (listener != null) listener.onBusyChanged(busy);
        });
    }

    private void handleInternalCommand(String cmd) {
        switch (cmd) {
            case "/help":
                appendOutput(activity.getString(R.string.terminal_help));
                break;
            case "/clear":
                consoleOutput.setText("");
                appendOutput(activity.getString(R.string.terminal_cleared));
                break;
            case "/reset":
                synchronized (history) {
                    history.clear();
                }
                shownOutput = "";
                appendOutput(activity.getString(R.string.terminal_reset_done));
                break;
            case "/history": {
                List<String> snapshot;
                synchronized (history) {
                    snapshot = new ArrayList<>(history);
                }
                if (snapshot.isEmpty()) {
                    appendOutput(activity.getString(R.string.terminal_history_empty));
                } else {
                    appendOutput(activity.getString(R.string.terminal_history_header));
                    for (int i = 0; i < snapshot.size(); i++) {
                        appendOutput(activity.getString(R.string.terminal_history_entry, i + 1, snapshot.get(i)));
                    }
                    appendOutput("\n");
                }
                break;
            }
            default:
                appendOutput(activity.getString(R.string.terminal_unknown_command, cmd));
                break;
        }
    }

    private static void deleteRecursive(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteRecursive(child);
        file.delete();
    }
}
