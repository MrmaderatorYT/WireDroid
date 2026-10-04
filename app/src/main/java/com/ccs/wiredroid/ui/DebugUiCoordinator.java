package com.ccs.wiredroid.ui;

import android.app.Activity;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.ccs.wiredroid.R;
import com.ccs.wiredroid.debug.CallStackAdapter;
import com.ccs.wiredroid.debug.DebugBridge;
import com.ccs.wiredroid.debug.DebugEditorDecorator;
import com.ccs.wiredroid.debug.DebugVariable;
import com.ccs.wiredroid.debug.DebuggerController;
import com.ccs.wiredroid.debug.ExpressionEvaluator;
import com.ccs.wiredroid.debug.VariablesTreeAdapter;
import com.ccs.wiredroid.debug.WatchExpression;
import com.ccs.wiredroid.ui.panels.BottomPanel;
import com.ccs.wiredroid.ui.panels.BottomPanelController;
import com.ccs.wiredroid.util.AppTheme;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.github.rosemoe.sora.widget.CodeEditor;

/**
 * Manages the Debugger UI, toolbar buttons, debug console, call stack & variables panels,
 * watches, breakpoints, and expression evaluation.
 */
public final class DebugUiCoordinator {

    public interface Callback {
        DebugEditorDecorator getActiveEditorDecorator();
        AppTheme getTheme();
        File findFileInProject(String fileName);
        void openFile(File file);
        void switchBottomPanel(int mode);
        void setRunning(boolean running);
        BottomPanelController getPanelController();
    }

    private final Activity activity;
    private final Callback callback;
    /** The panes and what they hold; a live view, not a snapshot. */
    private final EditorWorkspace ws;

    /** False until the ViewStubs holding the debugger UI have been inflated. */
    private boolean debugUiInflated;
    /** Last theme handed to applyTheme, re-applied to views inflated later. */
    private AppTheme lastTheme;

    private View debugToolbar;
    private TextView debugLocation;
    private TextView tabDebug;
    private TextView tabDebugConsole;
    private ScrollView debugConsoleScroll;
    private TextView debugConsoleOutput;
    private View debuggerSplitPanel;
    private TextView variablesOutput;
    private RecyclerView variablesRecycler;
    private VariablesTreeAdapter variablesAdapter;
    private TextView callStackOutput;
    private RecyclerView callStackRecycler;
    private CallStackAdapter callStackAdapter;
    private LinearLayout watchesContainer;
    private TextView watchesOutput;

    private boolean isDebugging = false;
    private final List<WatchExpression> watchExpressions = new ArrayList<>();
    private DebuggerController.DebugListener debugListener;

    public DebugUiCoordinator(Activity activity, EditorWorkspace ws, Callback callback) {
        this.activity = activity;
        this.callback = callback;
        this.ws = ws;
    }

    /** The two tabs exist from the start; the panels wait for {@link #ensureDebugUi()}. */
    public void bind() {
        tabDebug            = activity.findViewById(R.id.tabDebug);
        tabDebugConsole     = activity.findViewById(R.id.tabDebugConsole);

        // Stays eager: this is what tells us a session has started, and it has
        // to be listening before there is anything to show.
        setupDebugController();
    }

    /**
     * Inflates the debugger's three view groups the first time a session needs
     * them. They sit behind ViewStubs because they start hidden and most
     * sessions never start a debugger, yet they used to be inflated on every
     * cold start.
     */
    private void ensureDebugUi() {
        if (debugUiInflated) return;
        debugUiInflated = true;
        inflateStub(R.id.stubDebugToolbar);
        inflateStub(R.id.stubDebugConsole);
        inflateStub(R.id.stubDebuggerPanel);

        debugToolbar        = activity.findViewById(R.id.debugToolbar);
        debugLocation       = activity.findViewById(R.id.debugLocation);
        debugConsoleScroll  = activity.findViewById(R.id.debugConsoleScroll);
        debugConsoleOutput  = activity.findViewById(R.id.debugConsoleOutput);
        debuggerSplitPanel  = activity.findViewById(R.id.debuggerSplitPanel);
        variablesOutput     = activity.findViewById(R.id.variablesOutput);
        variablesRecycler   = activity.findViewById(R.id.variablesRecycler);
        callStackOutput     = activity.findViewById(R.id.callStackOutput);
        callStackRecycler   = activity.findViewById(R.id.callStackRecycler);
        watchesContainer    = activity.findViewById(R.id.watchesContainer);
        watchesOutput       = activity.findViewById(R.id.watchesOutput);

        setupDebugToolbar();
        setupDebugAdapters();
        if (lastTheme != null) applyTheme(lastTheme);
    }

    private void inflateStub(int stubId) {
        android.view.ViewStub stub = activity.findViewById(stubId);
        if (stub != null) stub.inflate();
    }

    public void applyTheme(@NonNull AppTheme theme) {
        lastTheme = theme;
        if (debugConsoleScroll != null) debugConsoleScroll.setBackgroundColor(theme.consoleBg);
        if (debugConsoleOutput != null) debugConsoleOutput.setTextColor(theme.consoleText);
        if (variablesOutput != null) variablesOutput.setTextColor(theme.consoleText);
        if (variablesRecycler != null) variablesRecycler.setBackgroundColor(theme.consoleBg);
        if (callStackOutput != null) callStackOutput.setTextColor(theme.consoleText);
        if (callStackRecycler != null) callStackRecycler.setBackgroundColor(theme.consoleBg);
        if (watchesContainer != null) watchesContainer.setBackgroundColor(theme.consoleBg);
        if (watchesOutput != null) watchesOutput.setTextColor(theme.consoleText);

        if (variablesAdapter != null) variablesAdapter.setTheme(theme);
        if (callStackAdapter != null) callStackAdapter.setTheme(theme);
    }

    public void cleanup() {
        if (debugListener != null) {
            DebuggerController.getInstance().removeListener(debugListener);
        }
    }

    private void setupDebugToolbar() {
        if (debugToolbar == null) return;
        TextView btnStepOver = debugToolbar.findViewById(R.id.btnDebugStepOver);
        TextView btnStepInto = debugToolbar.findViewById(R.id.btnDebugStepInto);
        TextView btnStepOut = debugToolbar.findViewById(R.id.btnDebugStepOut);
        TextView btnResume = debugToolbar.findViewById(R.id.btnDebugResume);
        TextView btnStop = debugToolbar.findViewById(R.id.btnDebugStop);
        TextView btnEval = debugToolbar.findViewById(R.id.btnDebugEvaluate);

        if (btnStepOver != null) btnStepOver.setOnClickListener(v -> DebuggerController.getInstance().stepOver());
        if (btnStepInto != null) btnStepInto.setOnClickListener(v -> DebuggerController.getInstance().stepInto());
        if (btnStepOut != null) btnStepOut.setOnClickListener(v -> DebuggerController.getInstance().stepOut());
        if (btnResume != null) btnResume.setOnClickListener(v -> DebuggerController.getInstance().resume());
        if (btnStop != null) btnStop.setOnClickListener(v -> stopDebug());
        if (btnEval != null) btnEval.setOnClickListener(v -> {
            CodeEditor activeEditor = ws.activeEditor;
            if (activeEditor != null) {
                CharSequence selected = activeEditor.getText().subSequence(
                        activeEditor.getCursor().getLeftColumn(),
                        activeEditor.getCursor().getRightColumn());
                if (selected != null && selected.length() > 0) {
                    evaluateExpression(selected.toString());
                    return;
                }
            }
            showEvaluateDialog();
        });

        TextView btnToggleBP = debugToolbar.findViewById(R.id.btnToggleBreakpoint);
        if (btnToggleBP != null) btnToggleBP.setOnClickListener(v -> toggleBreakpointAtCursor());

        View btnAddWatch = activity.findViewById(R.id.btnAddWatch);
        if (btnAddWatch != null) btnAddWatch.setOnClickListener(v -> showAddWatchDialog());
    }

    private void setupDebugController() {
        debugListener = new DebuggerController.DebugListener() {
            @Override
            public void onBreakpointHit(DebuggerController.DebugEvent event) {
                activity.runOnUiThread(() -> {
                    isDebugging = true;
                    updateDebugToolbar(true);
                    String loc = event.className.replace('/', '.')
                            + "." + event.methodName + ":" + event.line;
                    if (debugLocation != null) debugLocation.setText(loc);

                    highlightDebugLine(event.line);

                    updateVariablesPanel(event);
                    updateCallStackPanel(event);
                    updateWatchesPanel(event);

                    callback.switchBottomPanel(BottomPanel.DEBUG.mode);
                });
            }

            @Override
            public void onDebugOutput(String text) {
                activity.runOnUiThread(() -> appendDebugConsole(text, 0xFFAAAAAA));
            }

            @Override
            public void onDebugError(String text) {
                activity.runOnUiThread(() -> appendDebugConsole("Error: " + text, 0xFFCF4444));
            }

            @Override
            public void onDebugStarted() {
                activity.runOnUiThread(() -> {
                    isDebugging = true;
                    updateDebugToolbar(true);
                    if (debugConsoleOutput != null) debugConsoleOutput.setText("");
                    showDebugTabs(true);
                    callback.switchBottomPanel(BottomPanel.DEBUG_CONSOLE.mode);
                });
            }

            @Override
            public void onDebugEnded() {
                activity.runOnUiThread(() -> {
                    isDebugging = false;
                    updateDebugToolbar(false);
                    appendDebugConsole("Debug session ended.", 0xFF888888);
                    clearDebugHighlight();
                });
            }
        };
        DebuggerController.getInstance().addListener(debugListener);
    }

    private void setupDebugAdapters() {
        AppTheme theme = callback.getTheme();
        variablesAdapter = new VariablesTreeAdapter();
        if (theme != null) variablesAdapter.setTheme(theme);
        if (variablesRecycler != null) {
            variablesRecycler.setLayoutManager(new LinearLayoutManager(activity));
            variablesRecycler.setAdapter(variablesAdapter);
        }
        callStackAdapter = new CallStackAdapter();
        if (theme != null) callStackAdapter.setTheme(theme);
        if (callStackRecycler != null) {
            callStackRecycler.setLayoutManager(new LinearLayoutManager(activity));
            callStackRecycler.setAdapter(callStackAdapter);
            callStackAdapter.setListener(frame -> {
                String simple = frame.getClassName();
                int dot = simple.lastIndexOf('.');
                if (dot >= 0) simple = simple.substring(dot + 1);
                File f = callback.findFileInProject(simple + ".java");
                if (f != null) {
                    callback.openFile(f);
                }
            });
        }
    }

    public void updateDebugToolbar(boolean active) {
        if (active) ensureDebugUi();
        if (debugToolbar != null) {
            debugToolbar.setVisibility(active ? View.VISIBLE : View.GONE);
        }
    }

    public void showDebugTabs(boolean show) {
        if (show) ensureDebugUi();
        BottomPanelController ctrl = callback.getPanelController();
        if (ctrl != null) ctrl.setDebugSessionActive(show);
    }

    public void clearDebugConsole() {
        ensureDebugUi();
        if (debugConsoleOutput != null) {
            debugConsoleOutput.setText("");
        }
    }

    public void appendDebugConsole(String text, int color) {
        ensureDebugUi();
        if (debugConsoleOutput == null) return;
        SpannableString sp = new SpannableString(text + "\n");
        sp.setSpan(new ForegroundColorSpan(color), 0, sp.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        debugConsoleOutput.append(sp);
        if (debugConsoleScroll != null) {
            debugConsoleScroll.post(() -> debugConsoleScroll.fullScroll(View.FOCUS_DOWN));
        }
    }

    public void updateVariablesPanel(DebuggerController.DebugEvent event) {
        ensureDebugUi();
        if (variablesAdapter == null) return;
        if (event.variables == null || event.variables.isEmpty()) {
            variablesAdapter.setVariables(null);
            return;
        }
        variablesAdapter.setVariables(event.variables);
    }

    public void updateCallStackPanel(DebuggerController.DebugEvent event) {
        ensureDebugUi();
        if (callStackAdapter == null) return;
        callStackAdapter.setFrames(event.callStack);
    }

    public void updateWatchesPanel(DebuggerController.DebugEvent event) {
        ensureDebugUi();
        if (watchesOutput == null) return;
        if (watchExpressions.isEmpty()) {
            watchesOutput.setText(activity.getString(R.string.debug_no_watch));
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (WatchExpression w : watchExpressions) {
            w.evaluate(event);
            String status = w.isError() ? "✗ " : "  ";
            sb.append(status).append(w.getExpression())
              .append(" = ").append(w.getLastResult()).append("\n");
        }
        watchesOutput.setText(sb.toString());
    }

    public void highlightDebugLine(int line) {
        CodeEditor activeEditor = ws.activeEditor;
        if (activeEditor != null && line > 0) {
            activeEditor.setSelection(line - 1, 0);
        }
        DebugEditorDecorator decorator = callback.getActiveEditorDecorator();
        if (decorator != null) {
            decorator.highlightLine(line);
        }
    }

    public void clearDebugHighlight() {
        DebugEditorDecorator decorator = callback.getActiveEditorDecorator();
        if (decorator != null) {
            decorator.clearHighlight();
        }
    }

    public void refreshBreakpointMarkers() {
        DebugEditorDecorator decorator = callback.getActiveEditorDecorator();
        if (decorator == null) return;
        DebuggerController ctrl = DebuggerController.getInstance();
        Set<Integer> lines = ctrl.getBreakpoints().keySet();
        Set<Integer> cond = ctrl.getConditionalBreakpointLines();
        decorator.refreshBreakpoints(lines, cond);
    }

    public void toggleBreakpointAtCursor() {
        CodeEditor activeEditor = ws.activeEditor;
        if (activeEditor == null) return;
        int line = activeEditor.getCursor().getLeftLine() + 1;
        DebuggerController ctrl = DebuggerController.getInstance();
        ctrl.toggleBreakpoint(line);
        boolean has = ctrl.hasBreakpoint(line);
        Toast.makeText(activity, has ? activity.getString(R.string.debug_breakpoint_set, line) : activity.getString(R.string.debug_breakpoint_removed, line),
                Toast.LENGTH_SHORT).show();
        refreshBreakpointMarkers();
    }

    public void showBreakpointEditorDialog(int line) {
        DebuggerController ctrl = DebuggerController.getInstance();
        String current = ctrl.getBreakpointCondition(line);

        final EditText input = new EditText(activity);
        input.setHint(R.string.breakpoint_condition_hint);
        input.setSingleLine(false);
        input.setMinLines(2);
        input.setTextSize(13);
        if (current != null) input.setText(current);
        input.setPadding(48, 24, 48, 24);

        com.ccs.wiredroid.ui.Dialogs.rounded(activity)
                .setTitle(activity.getString(R.string.breakpoint_dialog_title, line))
                .setView(input)
                .setPositiveButton(R.string.breakpoint_set_button, (d, w) -> {
                    String cond = input.getText().toString().trim();
                    ctrl.setBreakpoint(line, cond.isEmpty() ? null : cond);
                    Toast.makeText(activity,
                            cond.isEmpty() ? "● Unconditional BP at line " + line
                                    : "● Conditional BP at line " + line + ": " + cond,
                            Toast.LENGTH_SHORT).show();
                    refreshBreakpointMarkers();
                })
                .setNegativeButton(R.string.dialog_cancel, null)
                .setNeutralButton(ctrl.hasBreakpoint(line) ? activity.getString(R.string.breakpoint_remove_button) : activity.getString(R.string.close_button), (d, w) -> {
                    if (ctrl.hasBreakpoint(line)) {
                        ctrl.toggleBreakpoint(line);
                        Toast.makeText(activity, activity.getString(R.string.debug_breakpoint_removed, line),
                                Toast.LENGTH_SHORT).show();
                        refreshBreakpointMarkers();
                    }
                })
                .show();
        input.requestFocus();
    }

    public void showEvaluateDialog() {
        DebuggerController ctrl = DebuggerController.getInstance();
        if (!ctrl.isPaused()) return;

        EditText input = new EditText(activity);
        input.setHint(R.string.debug_evaluate_hint);
        input.setSingleLine(true);

        CodeEditor activeEditor = ws.activeEditor;
        if (activeEditor != null) {
            CharSequence selected = activeEditor.getText().subSequence(
                    activeEditor.getCursor().getLeftColumn(),
                    activeEditor.getCursor().getRightColumn());
            if (selected != null && selected.length() > 0) {
                input.setText(selected.toString());
                input.selectAll();
            }
        }

        com.ccs.wiredroid.ui.Dialogs.rounded(activity)
                .setTitle(R.string.dialog_debug_evaluate_title)
                .setView(input)
                .setPositiveButton(R.string.dialog_apply, (d, w) -> {
                    String expr = input.getText().toString().trim();
                    if (expr.isEmpty()) return;

                    evaluateExpression(expr);
                })
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }

    public void evaluateExpression(String expr) {
        DebuggerController ctrl = DebuggerController.getInstance();
        if (!ctrl.isPaused()) return;

        String[] names = DebugBridge.getCurrentLocalNames();
        String[] types = DebugBridge.getCurrentLocalTypes();
        Object[] locals = DebugBridge.getCurrentLocals();

        List<DebugVariable> vars = new ArrayList<>();
        if (names != null && locals != null) {
            for (int i = 0; i < names.length; i++) {
                vars.add(new DebugVariable(
                        names[i],
                        types != null && i < types.length ? types[i] : "unknown",
                        i < locals.length ? locals[i] : null
                ));
            }
        }

        ExpressionEvaluator evaluator = new ExpressionEvaluator(vars);
        ExpressionEvaluator.EvalResult result = evaluator.evaluate(expr);

        String output = expr + " = " + result.value;
        appendDebugConsole(output, result.isError ? 0xFFCF4444 : 0xFF499C54);
        callback.switchBottomPanel(BottomPanel.DEBUG.mode);
    }

    public void addWatch(String expr) {
        if (expr != null && !expr.trim().isEmpty()) {
            watchExpressions.add(new WatchExpression(expr.trim()));
        }
    }

    public void showAddWatchDialog() {
        EditText input = new EditText(activity);
        input.setHint(R.string.debug_watch_hint);
        input.setSingleLine(true);

        com.ccs.wiredroid.ui.Dialogs.rounded(activity)
                .setTitle(R.string.dialog_debug_watch_title)
                .setView(input)
                .setPositiveButton(R.string.dialog_apply, (d, w) -> {
                    String expr = input.getText().toString().trim();
                    if (!expr.isEmpty()) {
                        watchExpressions.add(new WatchExpression(expr));
                        Toast.makeText(activity, "Watch added: " + expr, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }

    public void stopDebug() {
        DebuggerController.getInstance().stopDebug();
        isDebugging = false;
        callback.setRunning(false);
        updateDebugToolbar(false);
        showDebugTabs(false);
    }

    public boolean isDebugging() {
        return isDebugging;
    }

    /**
     * Shows or hides the variables/call-stack panel.
     *
     * <p>Hiding must not inflate: the panel controller hides every inactive
     * panel on startup, and asking the getter for a view was enough to build
     * the whole debugger UI on a screen that is not debugging anything.</p>
     */
    public void setDebuggerPanelVisible(boolean visible) {
        if (visible) {
            ensureDebugUi();
            checkAndApplyJavaSeNote();
        }
        if (debuggerSplitPanel != null) {
            debuggerSplitPanel.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    /** Companion to {@link #setDebuggerPanelVisible(boolean)} for the console. */
    public void setDebugConsoleVisible(boolean visible) {
        if (visible) {
            ensureDebugUi();
            checkAndApplyJavaSeNote();
        }
        View debugConsoleRoot = activity.findViewById(R.id.debugConsoleRoot);
        if (debugConsoleRoot != null) {
            debugConsoleRoot.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
        if (debugConsoleScroll != null) {
            debugConsoleScroll.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    public void refreshJavaSeNotes(boolean isJavaSe) {
        TextView note1 = activity.findViewById(R.id.debuggerJavaSeNote);
        if (note1 != null) note1.setVisibility(isJavaSe ? View.VISIBLE : View.GONE);
        TextView note2 = activity.findViewById(R.id.debugConsoleJavaSeNote);
        if (note2 != null) note2.setVisibility(isJavaSe ? View.VISIBLE : View.GONE);
    }

    private void checkAndApplyJavaSeNote() {
        try {
            File activeFile = ws != null ? ws.activeFile() : null;
            File projectRoot = null;
            if (activeFile != null) {
                File dir = activeFile.getParentFile();
                while (dir != null && !new File(dir, ".wiredroid").exists() && !new File(dir, "pom.xml").exists() && !new File(dir, ".project").exists()) {
                    dir = dir.getParentFile();
                }
                projectRoot = dir;
            }
            String currentJdk = com.ccs.wiredroid.project.ProjectJdk.forOpenProject(activity);
            boolean isJavaSe = (projectRoot == null || com.ccs.wiredroid.project.ProjectRuntime.isJavaSe(projectRoot))
                    && ("21".equals(currentJdk) || com.ccs.wiredroid.tools.compilers.JavaVersions.feature(currentJdk) >= 21);
            refreshJavaSeNotes(isJavaSe);
        } catch (Throwable ignored) {}
    }

    public View getDebuggerSplitPanel() {
        return debuggerSplitPanel;
    }

    public ScrollView getDebugConsoleScroll() {
        return debugConsoleScroll;
    }
}
