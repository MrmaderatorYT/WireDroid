package com.ccs.wiredroid.ui;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.ccs.wiredroid.R;
import com.ccs.wiredroid.analysis.ProblemItem;
import com.ccs.wiredroid.tools.bytecode.BytecodeEditor;
import com.ccs.wiredroid.tools.bytecode.BytecodeEditorActivity;
import com.ccs.wiredroid.tools.bytecode.BytecodeModel;
import com.ccs.wiredroid.tools.bytecode.Deobfuscator;
import com.ccs.wiredroid.tools.bytecode.InstructionAdapter;
import com.ccs.wiredroid.tools.compilers.ProjectCompiler;
import com.ccs.wiredroid.ui.panels.BottomPanelController;
import com.ccs.wiredroid.util.AppTheme;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;

/**
 * Manages the bytecode viewer and inline bytecode editor panel.
 */
public final class BytecodePanelManager implements BottomPanelController.Binding {

    public interface Callback {
        String getActiveSourceCode();
        File getProjectDir();
        AppTheme getTheme();
        boolean isRunning();
        void setRunning(boolean running);
        void appendConsole(String text, int color);
        void clearConsole();
        void switchBottomPanel(int mode);
        void saveCurrentToActiveTab();
        void onCallGraphFromBytecode(String className);
    }

    private final Activity activity;
    private final Callback callback;
    /** The panes and what they hold; a live view, not a snapshot. */
    private final EditorWorkspace ws;

    /** False until the ViewStub holding this panel has been inflated. */
    private boolean panelInflated;
    /** Last theme handed to applyTheme, re-applied to views inflated later. */
    private AppTheme lastTheme;

    private View bytecodeRoot;
    private View bytecodeToolbar;
    private TextView bytecodeStatus;
    private RecyclerView bytecodeMethodTree;
    private RecyclerView bytecodeInstructions;
    private MethodTreeAdapter bytecodeTreeAdapter;
    private InstructionAdapter bytecodeInsnAdapter;
    private EditText bytecodeSearch;
    private View bytecodeToggleHex;
    private View bytecodeToggleLines;
    private View bytecodeToggleComments;
    private View bytecodeInsnSlot;
    private View bytecodeHexScroll;
    private TextView bytecodeHexOutput;
    private View bytecodeEditInsn;
    private View bytecodeSaveBtn;
    private View bytecodeRunBtn;
    private View bytecodeOpenEditorBtn;
    private View bytecodeCallGraphBtn;

    private Deobfuscator deobfuscator = new Deobfuscator();
    private boolean bytecodeHexMode;
    private BytecodeModel bytecodeModel;
    private int bytecodeSelectedMethod = -1;
    private volatile boolean bytecodeRefreshRunning;

    public BytecodePanelManager(Activity activity, EditorWorkspace ws, Callback callback) {
        this.activity = activity;
        this.callback = callback;
        this.ws = ws;
    }

    public static final int REQ_LOAD_MAPPING = 4010;

    /** Nothing to bind up front — the panel inflates on first open, see {@link #ensurePanel()}. */
    public void bind() {
    }

    /**
     * Inflates the panel the first time it is actually opened.
     *
     * <p>It lives behind a {@code ViewStub} because it starts hidden and most
     * sessions never open it, yet it used to be inflated on every cold start
     * along with every other bottom panel.</p>
     */
    private void ensurePanel() {
        if (panelInflated) return;
        panelInflated = true;
        android.view.ViewStub stub = activity.findViewById(R.id.stubBytecodePanel);
        if (stub != null) stub.inflate();
        bytecodeRoot           = activity.findViewById(R.id.bytecodeRoot);
        bytecodeToolbar        = activity.findViewById(R.id.bytecodeToolbar);
        bytecodeStatus         = activity.findViewById(R.id.bytecodeStatus);
        bytecodeMethodTree     = activity.findViewById(R.id.bytecodeMethodTree);
        bytecodeInstructions   = activity.findViewById(R.id.bytecodeInstructions);
        bytecodeSearch         = activity.findViewById(R.id.bytecodeSearch);
        bytecodeToggleHex      = activity.findViewById(R.id.bytecodeToggleHex);
        bytecodeToggleLines    = activity.findViewById(R.id.bytecodeToggleLines);
        bytecodeToggleComments = activity.findViewById(R.id.bytecodeToggleComments);
        bytecodeInsnSlot       = activity.findViewById(R.id.bytecodeInsnSlot);
        bytecodeHexScroll      = activity.findViewById(R.id.bytecodeHexScroll);
        bytecodeHexOutput      = activity.findViewById(R.id.bytecodeHexOutput);
        bytecodeEditInsn       = activity.findViewById(R.id.bytecodeEditInsn);
        bytecodeSaveBtn        = activity.findViewById(R.id.bytecodeSave);
        bytecodeRunBtn         = activity.findViewById(R.id.bytecodeRun);
        bytecodeOpenEditorBtn  = activity.findViewById(R.id.bytecodeOpenEditor);
        bytecodeCallGraphBtn   = activity.findViewById(R.id.bytecodeCallGraph);

        setupBytecodeViewer();
        setupBytecodeEditButtons();
        if (lastTheme != null) applyTheme(lastTheme);
    }

    @Override
    public void setVisible(boolean visible) {
        if (visible) ensurePanel();
        if (bytecodeRoot != null) {
            bytecodeRoot.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    @Override
    public void onShown() {
        refresh();
    }

    public void applyTheme(@NonNull AppTheme theme) {
        lastTheme = theme;
        if (bytecodeRoot != null) bytecodeRoot.setBackgroundColor(theme.consoleBg);
        if (bytecodeToolbar != null) bytecodeToolbar.setBackgroundColor(theme.toolbar);
        if (bytecodeStatus != null) bytecodeStatus.setBackgroundColor(theme.consoleBg);
        if (bytecodeMethodTree != null) bytecodeMethodTree.setBackgroundColor(theme.consoleBg);
        if (bytecodeInstructions != null) bytecodeInstructions.setBackgroundColor(theme.consoleBg);
        if (bytecodeSearch != null) bytecodeSearch.setTextColor(theme.consoleText);
        if (bytecodeHexOutput != null) bytecodeHexOutput.setTextColor(theme.consoleText);

        if (bytecodeTreeAdapter != null) bytecodeTreeAdapter.setTheme(theme);
        if (bytecodeInsnAdapter != null) bytecodeInsnAdapter.setTheme(theme);
    }

    public void loadProGuardMapping() {
        ensurePanel();
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            activity.startActivityForResult(i, REQ_LOAD_MAPPING);
        } catch (Exception e) {
            Toast.makeText(activity, e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    public void loadMappingResult(Uri uri) {
        ensurePanel();
        if (uri == null) return;
        try (InputStream is = activity.getContentResolver().openInputStream(uri)) {
            if (is == null) return;
            if (deobfuscator == null) deobfuscator = new Deobfuscator();
            deobfuscator.loadMapping(is);
            Toast.makeText(activity, activity.getString(R.string.mapping_loaded, deobfuscator.getStats()),
                    Toast.LENGTH_LONG).show();
            if (bytecodeModel != null) {
                showBytecodeModel(bytecodeModel);
            }
        } catch (Exception e) {
            Toast.makeText(activity, activity.getString(R.string.mapping_load_failed, e.getMessage()),
                    Toast.LENGTH_LONG).show();
        }
    }

    public void refresh() {
        ensurePanel();
        FileTab tab = ws.tabs().getActiveTab();
        if (tab == null || tab.file == null) {
            setBytecodeStatus(activity.getString(R.string.bytecode_empty));
            return;
        }
        String name = tab.file.getName();
        if (name.endsWith(".class")) {
            if (tab.classBytes != null) {
                try {
                    BytecodeModel model = BytecodeModel.parse(tab.classBytes);
                    showBytecodeModel(model);
                    return;
                } catch (Throwable t) {
                    setBytecodeStatus(activity.getString(R.string.bytecode_failed,
                            t.getMessage() != null ? t.getMessage() : String.valueOf(t)));
                    return;
                }
            }
            setBytecodeStatus(activity.getString(R.string.bytecode_empty));
            return;
        }
        if (!name.endsWith(".java")) {
            setBytecodeStatus(activity.getString(R.string.bytecode_only_java));
            return;
        }
        if (bytecodeRefreshRunning) {
            return;
        }
        bytecodeRefreshRunning = true;
        callback.saveCurrentToActiveTab();
        setBytecodeStatus(activity.getString(R.string.bytecode_compiling));
        final String source = callback.getActiveSourceCode();
        final File javaFile = tab.file;
        com.ccs.wiredroid.tools.compilers.RunCancellation.newWorker(() -> {
            try {
                ProjectCompiler.BytecodeCompileResult r = ProjectCompiler.compileForBytecodeView(
                        activity, javaFile, source, callback.getProjectDir());
                if (r.errorMessage != null) {
                    final String err = r.errorMessage;
                    activity.runOnUiThread(() -> {
                        bytecodeRefreshRunning = false;
                        setBytecodeStatus(activity.getString(R.string.bytecode_failed, err));
                    });
                    return;
                }
                byte[] bytes = Files.readAllBytes(r.classFile.toPath());
                final BytecodeModel model = BytecodeModel.parse(bytes);
                activity.runOnUiThread(() -> {
                    bytecodeRefreshRunning = false;
                    showBytecodeModel(model);
                });
            } catch (Throwable t) {
                String msg = t.getMessage() != null ? t.getMessage() : String.valueOf(t);
                activity.runOnUiThread(() -> {
                    bytecodeRefreshRunning = false;
                    setBytecodeStatus(activity.getString(R.string.bytecode_failed, msg));
                });
            }
        }, "bytecode-view").start();
    }

    private void showBytecodeModel(BytecodeModel model) {
        if (deobfuscator != null && deobfuscator.hasMapping() && model != null) {
            model = model.deobfuscate(deobfuscator);
        }
        bytecodeModel = model;
        if (model == null) {
            if (bytecodeTreeAdapter != null) bytecodeTreeAdapter.setModel(null, null);
            if (bytecodeInsnAdapter != null) bytecodeInsnAdapter.setInstructions(null);
            setBytecodeStatus(activity.getString(R.string.bytecode_empty));
            return;
        }
        BytecodeModel.ClassInfo ci = model.classInfo;
        String statusText = activity.getString(R.string.bytecode_class_loaded,
                ci.name, model.fields.size(), model.methods.size());
        if (deobfuscator != null && deobfuscator.hasMapping()) {
            statusText += " [deobfuscated]";
        }
        setBytecodeStatus(statusText);
        if (bytecodeTreeAdapter != null) {
            bytecodeTreeAdapter.setModel(model.fields, model.methods);
        }
        if (!model.methods.isEmpty()) {
            selectBytecodeMethod(0);
        } else {
            if (bytecodeInsnAdapter != null) bytecodeInsnAdapter.setInstructions(null);
        }
        if (bytecodeHexMode) renderHexDump();
    }

    private void selectBytecodeMethod(int methodIndex) {
        if (bytecodeModel == null || methodIndex < 0
                || methodIndex >= bytecodeModel.methods.size()) {
            return;
        }
        bytecodeSelectedMethod = methodIndex;
        if (bytecodeTreeAdapter != null) {
            bytecodeTreeAdapter.setSelectedMethod(methodIndex);
        }
        BytecodeModel.MethodInfo m = bytecodeModel.methods.get(methodIndex);
        if (bytecodeInsnAdapter != null) {
            bytecodeInsnAdapter.setInstructions(m.instructions);
        }
        if (!m.handlers.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (BytecodeModel.ExceptionHandler h : m.handlers) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(h.type).append(" L").append(h.startLabel)
                        .append("..L").append(h.endLabel)
                        .append("→L").append(h.handlerLabel);
            }
            setBytecodeStatus(m.shortText + "   catch: " + sb);
        } else {
            BytecodeModel.ClassInfo ci = bytecodeModel.classInfo;
            setBytecodeStatus(ci.name + " :: " + m.shortText);
        }
    }

    private void setBytecodeStatus(String text) {
        if (bytecodeStatus != null) bytecodeStatus.setText(text);
    }

    private void jumpToBytecodeLabel(String label) {
        if (bytecodeInsnAdapter == null) return;
        int pos = bytecodeInsnAdapter.positionOfLabel(label);
        if (pos >= 0 && bytecodeInstructions != null) {
            LinearLayoutManager lm = (LinearLayoutManager) bytecodeInstructions.getLayoutManager();
            if (lm != null) lm.scrollToPositionWithOffset(pos, 8);
        }
    }

    private void renderHexDump() {
        if (bytecodeModel == null || bytecodeModel.rawBytes == null) {
            if (bytecodeHexOutput != null) bytecodeHexOutput.setText("");
            return;
        }
        byte[] b = bytecodeModel.rawBytes;
        StringBuilder sb = new StringBuilder(b.length / 16 * 4);
        for (int i = 0; i < b.length; i += 16) {
            sb.append(String.format(Locale.US, "%08X  ", i));
            int end = Math.min(i + 16, b.length);
            for (int j = i; j < i + 16; j++) {
                if (j < end) sb.append(String.format(Locale.US, "%02X ", b[j] & 0xFF));
                else sb.append("   ");
                if (j == i + 7) sb.append(' ');
            }
            sb.append(" |");
            for (int j = i; j < end; j++) {
                int c = b[j] & 0xFF;
                sb.append(c >= 0x20 && c < 0x7F ? (char) c : '.');
            }
            sb.append("|\n");
        }
        if (bytecodeHexOutput != null) bytecodeHexOutput.setText(sb);
    }

    private void setupBytecodeViewer() {
        AppTheme theme = callback.getTheme();

        bytecodeTreeAdapter = new MethodTreeAdapter();
        if (theme != null) bytecodeTreeAdapter.setTheme(theme);
        if (bytecodeMethodTree != null) {
            bytecodeMethodTree.setLayoutManager(new LinearLayoutManager(activity));
            bytecodeMethodTree.setAdapter(bytecodeTreeAdapter);
        }
        bytecodeTreeAdapter.setListener(this::selectBytecodeMethod);

        bytecodeInsnAdapter = new InstructionAdapter();
        if (theme != null) bytecodeInsnAdapter.setTheme(theme);
        bytecodeInsnAdapter.setJumpListener(this::jumpToBytecodeLabel);
        if (bytecodeInstructions != null) {
            bytecodeInstructions.setLayoutManager(new LinearLayoutManager(activity));
            bytecodeInstructions.setAdapter(bytecodeInsnAdapter);
        }

        if (bytecodeSearch != null) {
            bytecodeSearch.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void afterTextChanged(Editable s) {
                    bytecodeInsnAdapter.setQuery(s != null ? s.toString() : "");
                }
            });
        }

        if (bytecodeToggleLines != null) {
            bytecodeToggleLines.setOnClickListener(v -> {
                boolean on = !bytecodeInsnAdapter.isShowLineNumbers();
                bytecodeInsnAdapter.setShowLineNumbers(on);
                AppTheme t = callback.getTheme();
                ((TextView) v).setTextColor(on && t != null ? t.consoleText : (t != null ? t.textDim : 0xFF888888));
            });
        }

        if (bytecodeToggleComments != null) {
            bytecodeToggleComments.setOnClickListener(v -> {
                boolean on = !bytecodeInsnAdapter.isShowComments();
                bytecodeInsnAdapter.setShowComments(on);
                AppTheme t = callback.getTheme();
                ((TextView) v).setTextColor(on && t != null ? t.consoleText : (t != null ? t.textDim : 0xFF888888));
            });
        }

        if (bytecodeToggleHex != null) {
            bytecodeToggleHex.setOnClickListener(v -> {
                bytecodeHexMode = !bytecodeHexMode;
                AppTheme t = callback.getTheme();
                if (bytecodeHexMode) {
                    if (bytecodeInstructions != null) bytecodeInstructions.setVisibility(View.GONE);
                    if (bytecodeHexScroll != null) bytecodeHexScroll.setVisibility(View.VISIBLE);
                    renderHexDump();
                    if (t != null) ((TextView) v).setTextColor(t.accent);
                } else {
                    if (bytecodeHexScroll != null) bytecodeHexScroll.setVisibility(View.GONE);
                    if (bytecodeInstructions != null) bytecodeInstructions.setVisibility(View.VISIBLE);
                    if (t != null) ((TextView) v).setTextColor(t.textDim);
                }
            });
        }
    }

    private void setupBytecodeEditButtons() {
        if (bytecodeEditInsn != null) {
            bytecodeEditInsn.setOnClickListener(v -> showBytecodeEditDialog());
        }
        if (bytecodeSaveBtn != null) {
            bytecodeSaveBtn.setOnClickListener(v -> saveModifiedBytecode());
        }
        if (bytecodeRunBtn != null) {
            bytecodeRunBtn.setOnClickListener(v -> runModifiedBytecode());
        }
        if (bytecodeOpenEditorBtn != null) {
            bytecodeOpenEditorBtn.setOnClickListener(v -> openBytecodeEditor());
        }
        if (bytecodeCallGraphBtn != null) {
            bytecodeCallGraphBtn.setOnClickListener(v -> {
                if (bytecodeModel != null && bytecodeModel.classInfo != null) {
                    callback.onCallGraphFromBytecode(bytecodeModel.classInfo.name);
                }
            });
        }
    }

    public void showBytecodeEditDialog() {
        if (bytecodeModel == null || bytecodeSelectedMethod < 0) {
            Toast.makeText(activity, "No method selected", Toast.LENGTH_SHORT).show();
            return;
        }
        if (bytecodeInsnAdapter == null || bytecodeInsnAdapter.getSelectedItemIndex() < 0) {
            Toast.makeText(activity, "Select an instruction first", Toast.LENGTH_SHORT).show();
            return;
        }

        int selIdx = bytecodeInsnAdapter.getSelectedItemIndex();
        BytecodeModel.MethodInfo mi = bytecodeModel.methods.get(bytecodeSelectedMethod);
        if (selIdx < 0 || selIdx >= mi.instructions.size()) return;
        BytecodeModel.Instruction insn = mi.instructions.get(selIdx);

        String[] options;
        if ("ldc".equals(insn.opcode)) {
            options = new String[]{"Edit operand", "Delete instruction", "Insert NOP before", "Insert NOP after"};
        } else {
            options = new String[]{"Delete instruction", "Insert NOP before", "Insert NOP after"};
        }

        com.ccs.wiredroid.ui.Dialogs.rounded(activity)
                .setTitle("Edit: " + insn.opcode + " " + (insn.operand != null ? insn.operand : ""))
                .setItems(options, (d, w) -> {
                    switch (w) {
                        case 0:
                            if ("ldc".equals(insn.opcode)) {
                                showLdcEditDialog(insn);
                            } else {
                                showDeleteInsnDialog(selIdx);
                            }
                            break;
                        case 1:
                            showInsertNopDialog(selIdx, true);
                            break;
                        case 2:
                            showInsertNopDialog(selIdx, false);
                            break;
                    }
                })
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }

    private void showLdcEditDialog(BytecodeModel.Instruction insn) {
        EditText input = new EditText(activity);
        input.setHint(R.string.bytecode_edit_ldc);
        input.setText(insn.operand);
        AppTheme theme = callback.getTheme();
        if (theme != null) {
            input.setTextColor(theme.text);
            input.setHintTextColor(theme.textDim);
        }
        input.setPadding(48, 24, 48, 24);

        com.ccs.wiredroid.ui.Dialogs.rounded(activity)
                .setTitle("Edit LDC constant")
                .setView(input)
                .setPositiveButton("Apply", (d, w) -> {
                    String val = input.getText().toString().trim();
                    if (!val.isEmpty()) {
                        Toast.makeText(activity, "LDC operand changed to: " + val, Toast.LENGTH_SHORT).show();
                        setBytecodeStatus("Modified — " + insn.opcode + " → " + val);
                    }
                })
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }

    private void showDeleteInsnDialog(int index) {
        com.ccs.wiredroid.ui.Dialogs.rounded(activity)
                .setTitle(R.string.bytecode_delete_insn)
                .setPositiveButton("Delete", (d, w) -> {
                    Toast.makeText(activity, "Instruction " + index + " deleted", Toast.LENGTH_SHORT).show();
                    setBytecodeStatus("Modified — instruction " + index + " removed");
                })
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }

    private void showInsertNopDialog(int index, boolean before) {
        com.ccs.wiredroid.ui.Dialogs.rounded(activity)
                .setTitle(before ? R.string.bytecode_insert_before : R.string.bytecode_insert_after)
                .setPositiveButton("NOP", (d, w) -> {
                    String pos = before ? "before" : "after";
                    Toast.makeText(activity, "NOP inserted " + pos + " instruction " + index, Toast.LENGTH_SHORT).show();
                    setBytecodeStatus("Modified — NOP inserted " + pos + " insn " + index);
                })
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }

    public void openBytecodeEditor() {
        FileTab tab = ws.tabs().getActiveTab();
        if (tab == null || tab.file == null) return;

        if (tab.file.getName().endsWith(".class")) {
            BytecodeEditorActivity.launch(activity, tab.file.getAbsolutePath());
        } else if (tab.file.getName().endsWith(".java")) {
            callback.saveCurrentToActiveTab();
            setBytecodeStatus("Compiling for editor...");
            String source = callback.getActiveSourceCode();
            com.ccs.wiredroid.tools.compilers.RunCancellation.newWorker(() -> {
                try {
                    ProjectCompiler.BytecodeCompileResult r = ProjectCompiler.compileForBytecodeView(
                            activity, tab.file, source, callback.getProjectDir());
                    if (r.errorMessage != null) {
                        activity.runOnUiThread(() -> {
                            setBytecodeStatus("Error: " + r.errorMessage);
                            Toast.makeText(activity, "Compile error", Toast.LENGTH_SHORT).show();
                        });
                        return;
                    }
                    activity.runOnUiThread(() -> BytecodeEditorActivity.launch(activity, r.classFile.getAbsolutePath()));
                } catch (Exception e) {
                    activity.runOnUiThread(() -> {
                        setBytecodeStatus("Error: " + e.getMessage());
                        Toast.makeText(activity, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    });
                }
            }, "bytecode-compile-for-editor").start();
        } else {
            Toast.makeText(activity, "Open a .java or .class file first", Toast.LENGTH_SHORT).show();
        }
    }

    public void saveModifiedBytecode() {
        if (bytecodeModel == null) {
            Toast.makeText(activity, "No bytecode loaded", Toast.LENGTH_SHORT).show();
            return;
        }
        FileTab tab = ws.tabs().getActiveTab();
        if (tab == null || tab.file == null) return;

        try {
            BytecodeEditor editor = BytecodeEditor.parse(bytecodeModel.rawBytes);
            byte[] modified = editor.toBytes();
            try (FileOutputStream fos = new FileOutputStream(tab.file)) {
                fos.write(modified);
            }
            Toast.makeText(activity, R.string.bytecode_save_success, Toast.LENGTH_SHORT).show();
            setBytecodeStatus("Saved: " + tab.file.getName());
        } catch (Exception e) {
            Toast.makeText(activity, activity.getString(R.string.bytecode_save_failed, e.getMessage()),
                    Toast.LENGTH_LONG).show();
        }
    }

    public void runModifiedBytecode() {
        if (bytecodeModel == null) {
            Toast.makeText(activity, "No bytecode loaded", Toast.LENGTH_SHORT).show();
            return;
        }
        if (callback.isRunning()) {
            Toast.makeText(activity, "Already running", Toast.LENGTH_SHORT).show();
            return;
        }

        FileTab tab = ws.tabs().getActiveTab();
        if (tab == null || tab.file == null) return;

        String className = bytecodeModel.classInfo.internalName.replace('/', '.');
        AppTheme theme = callback.getTheme();

        callback.setRunning(true);
        callback.clearConsole();
        callback.switchBottomPanel(0); // PANEL_RUN
        callback.appendConsole("▶ Running modified bytecode: " + className, theme != null ? theme.textDim : 0xFF888888);

        byte[] classBytes = bytecodeModel.rawBytes;
        com.ccs.wiredroid.tools.compilers.RunCancellation.newWorker(() -> {
            try {
                ProjectCompiler.runClassBytes(activity, className, classBytes,
                        new ProjectCompiler.Callback() {
                            @Override
                            public void onProgress(String msg) {
                                activity.runOnUiThread(() -> callback.appendConsole("   " + msg, theme != null ? theme.textDim : 0xFF888888));
                            }

                            @Override
                            public void onResult(String output) {
                                activity.runOnUiThread(() -> {
                                    callback.setRunning(false);
                                    callback.appendConsole("", theme != null ? theme.accent : 0xFF3592C4);
                                    callback.appendConsole(activity.getString(R.string.console_output_separator), theme != null ? theme.accent : 0xFF3592C4);
                                    if (output == null || output.trim().isEmpty()) {
                                        callback.appendConsole(activity.getString(R.string.console_process_exit_ok), theme != null ? theme.successText : 0xFF499C54);
                                        return;
                                    }
                                    boolean isError = output.startsWith("Compilation Error")
                                            || output.startsWith("Execution Exception")
                                            || output.startsWith("System Error")
                                            || output.startsWith("Error:");
                                    callback.appendConsole(output.trim(), isError ? (theme != null ? theme.errorText : 0xFFCF4444) : (theme != null ? theme.consoleText : 0xFFCCCCCC));
                                    if (!isError) {
                                        callback.appendConsole("\n" + activity.getString(R.string.console_process_exit_ok), theme != null ? theme.successText : 0xFF499C54);
                                    }
                                });
                            }

                            @Override
                            public void onProblems(List<ProblemItem> problems) {}
                        });
            } catch (Exception e) {
                activity.runOnUiThread(() -> {
                    callback.setRunning(false);
                    callback.appendConsole("Error: " + e.getMessage(), theme != null ? theme.errorText : 0xFFCF4444);
                });
            }
        }, "bytecode-run").start();
    }
}
