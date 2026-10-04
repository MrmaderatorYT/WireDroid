package com.ccs.wiredroid.tools.bytecode;

import com.ccs.wiredroid.util.AppPreferences;
import com.ccs.wiredroid.R;
import com.ccs.wiredroid.ui.Dialogs;
import com.ccs.wiredroid.util.FullScreenHelper;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.HorizontalScrollView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Повноцінний текстовий редактор байткоду: редагування інструкцій,
 * збереження, валідація, undo/redo, підсвітка синтаксису.
 */
public class BytecodeEditorActivity extends AppCompatActivity {

    private static final String TAG = "BytecodeEditor";
    private static final String EXTRA_FILE_PATH = "file_path";

    private EditText codeEditor;
    private TextView tvStatus;
    private TextView tvLineCol;
    private ScrollView scrollEditor;
    private String originalCode;
    private String filePath;
    private boolean isModified = false;
    private final List<String> undoStack = new ArrayList<>();
    private final List<String> redoStack = new ArrayList<>();

    public static void launch(Context context, String filePath) {
        Intent i = new Intent(context, BytecodeEditorActivity.class);
        i.putExtra(EXTRA_FILE_PATH, filePath);
        context.startActivity(i);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FullScreenHelper.enable(this);

        filePath = getIntent().getStringExtra(EXTRA_FILE_PATH);
        if (filePath == null) { finish(); return; }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF1E1E1E);

        // ── Toolbar ──
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setBackgroundColor(0xFF3C3F41);
        toolbar.setPadding(dp(8), dp(8), dp(8), dp(8));
        toolbar.setGravity(Gravity.CENTER_VERTICAL);

        TextView btnBack = new TextView(this);
        btnBack.setText("\u2190");
        btnBack.setTextColor(0xFFBBBBBB);
        btnBack.setTextSize(18);
        btnBack.setPadding(dp(8), dp(4), dp(8), dp(4));
        btnBack.setContentDescription(getString(R.string.a11y_bc_back));
        btnBack.setOnClickListener(v -> {
            if (isModified) {
                Dialogs.rounded(this)
                        .setTitle("Unsaved changes")
                        .setMessage("Save before exit?")
                        .setPositiveButton("Save", (d, w) -> { saveFile(); finish(); })
                        .setNegativeButton("Discard", (d, w) -> finish())
                        .setNeutralButton("Cancel", null)
                        .show();
            } else {
                finish();
            }
        });
        toolbar.addView(btnBack);

        TextView tvTitle = new TextView(this);
        tvTitle.setText(new File(filePath).getName() + " — Bytecode Editor");
        tvTitle.setTextColor(0xFFBBBBBB);
        tvTitle.setTextSize(14);
        tvTitle.setTypeface(new AppPreferences(this).resolveTypeface());
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        tvTitle.setLayoutParams(titleLp);
        toolbar.addView(tvTitle);

        TextView btnUndo = createToolButton("\u21A9");
        btnUndo.setContentDescription(getString(R.string.a11y_bc_undo));
        btnUndo.setOnClickListener(v -> undo());
        toolbar.addView(btnUndo);

        TextView btnRedo = createToolButton("\u21AA");
        btnRedo.setContentDescription(getString(R.string.a11y_bc_redo));
        btnRedo.setOnClickListener(v -> redo());
        toolbar.addView(btnRedo);

        TextView btnCopy = createToolButton("Copy");
        btnCopy.setContentDescription(getString(R.string.a11y_bc_copy));
        btnCopy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("bytecode", codeEditor.getText().toString()));
                Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
            }
        });
        toolbar.addView(btnCopy);

        TextView btnValidate = createToolButton("Check");
        btnValidate.setTextColor(0xFF499C54);
        btnValidate.setContentDescription(getString(R.string.a11y_bc_validate));
        btnValidate.setOnClickListener(v -> validate());
        toolbar.addView(btnValidate);

        TextView btnSave = createToolButton("Save");
        btnSave.setTextColor(0xFF4A86C8);
        btnSave.setContentDescription(getString(R.string.a11y_bc_save));
        btnSave.setOnClickListener(v -> saveFile());
        toolbar.addView(btnSave);

        root.addView(toolbar);

        // JVM assembly building blocks: keep the class/method structure one tap
        // away instead of making users remember the directive spelling.
        HorizontalScrollView structureScroll = new HorizontalScrollView(this);
        structureScroll.setHorizontalScrollBarEnabled(false);
        structureScroll.setBackgroundColor(0xFF252526);
        LinearLayout structureBar = new LinearLayout(this);
        structureBar.setOrientation(LinearLayout.HORIZONTAL);
        structureBar.setGravity(Gravity.CENTER_VERTICAL);
        structureBar.setPadding(dp(6), dp(4), dp(6), dp(4));
        structureScroll.addView(structureBar);
        addStructureButton(structureBar, "Class", ".class public MyClass\n.super java/lang/Object\n");
        addStructureButton(structureBar, "Field", ".field private value I\n");
        addStructureButton(structureBar, "Method", ".method public static method()V\n    .limit stack 2\n    .limit locals 1\n    return\n.end method\n");
        addStructureButton(structureBar, "Stack", ".limit stack 2\n");
        addStructureButton(structureBar, "Locals", ".limit locals 1\n");
        addStructureButton(structureBar, "Label", "L0:\n");
        addStructureButton(structureBar, "Branch", "goto L0\n");
        addStructureButton(structureBar, "Return", "return\n");
        root.addView(structureScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));

        // ── Line numbers + Code editor ──
        LinearLayout editorRow = new LinearLayout(this);
        editorRow.setOrientation(LinearLayout.HORIZONTAL);
        editorRow.setLayoutParams(new LinearLayout.LayoutParams(0, 0, 1));

        // Line numbers
        TextView lineNumbers = new TextView(this);
        lineNumbers.setId(android.R.id.text1);
        lineNumbers.setTextColor(0xFF606366);
        lineNumbers.setTextSize(12);
        lineNumbers.setTypeface(new AppPreferences(this).resolveTypeface());
        lineNumbers.setPadding(dp(8), dp(8), dp(8), dp(8));
        lineNumbers.setBackgroundColor(0xFF252526);
        lineNumbers.setGravity(Gravity.END);
        LinearLayout.LayoutParams lnLp = new LinearLayout.LayoutParams(dp(48), ViewGroup.LayoutParams.MATCH_PARENT);
        lineNumbers.setLayoutParams(lnLp);
        editorRow.addView(lineNumbers);

        // Code editor
        scrollEditor = new ScrollView(this);
        scrollEditor.setLayoutParams(new LinearLayout.LayoutParams(0, 0, 1));
        scrollEditor.setBackgroundColor(0xFF1E1E1E);

        codeEditor = new EditText(this);
        codeEditor.setTextColor(0xFFBBBBBB);
        codeEditor.setTextSize(12);
        codeEditor.setTypeface(new AppPreferences(this).resolveTypeface());
        codeEditor.setBackgroundColor(0xFF1E1E1E);
        codeEditor.setPadding(dp(8), dp(8), dp(8), dp(8));
        codeEditor.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        codeEditor.setHorizontallyScrolling(true);
        codeEditor.setSingleLine(false);
        codeEditor.setMinLines(20);
        codeEditor.setImeOptions(0);
        codeEditor.setRawInputType(0x00020001); // TYPE_CLASS_TEXT | TYPE_TEXT_FLAG_MULTI_LINE
        codeEditor.setContentDescription(getString(R.string.a11y_bc_code_editor));

        scrollEditor.addView(codeEditor);
        editorRow.addView(scrollEditor);
        root.addView(editorRow);

        // ── Status bar ──
        LinearLayout statusBar = new LinearLayout(this);
        statusBar.setOrientation(LinearLayout.HORIZONTAL);
        statusBar.setBackgroundColor(0xFF007ACC);
        statusBar.setPadding(dp(8), dp(4), dp(8), dp(4));
        statusBar.setGravity(Gravity.CENTER_VERTICAL);

        tvStatus = new TextView(this);
        tvStatus.setText("Loading…");
        tvStatus.setTextColor(0xFFFFFFFF);
        tvStatus.setTextSize(11);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        tvStatus.setLayoutParams(statusLp);
        statusBar.addView(tvStatus);

        tvLineCol = new TextView(this);
        tvLineCol.setText("Ln 1, Col 1");
        tvLineCol.setTextColor(0xFFFFFFFF);
        tvLineCol.setTextSize(11);
        statusBar.addView(tvLineCol);

        root.addView(statusBar);
        setContentView(root);

        // Load file
        loadFile(lineNumbers);

        // Line number updater
        codeEditor.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                isModified = !s.toString().equals(originalCode);
                tvStatus.setText(isModified ? "Modified" : "Saved");
                tvStatus.setTextColor(isModified ? 0xFFFFF176 : 0xFFFFFFFF);
                updateLineNumbers(lineNumbers);
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        // Cursor position
        codeEditor.setOnTouchListener((v, event) -> {
            v.post(() -> {
                int pos = codeEditor.getSelectionStart();
                String text = codeEditor.getText().toString();
                int line = 1, col = 1;
                for (int i = 0; i < pos && i < text.length(); i++) {
                    if (text.charAt(i) == '\n') { line++; col = 1; } else col++;
                }
                tvLineCol.setText("Ln " + line + ", Col " + col);
            });
            return false;
        });

        updateLineNumbers(lineNumbers);
    }

    private void loadFile(TextView lineNumbers) {
        File f = new File(filePath);
        // Читання файлу і прохід ASM по всіх інструкціях занадто повільні,
        // щоб тримати на них UI-потік до першого кадру.
        new Thread(() -> {
            String loaded = null;
            String failure = null;
            try {
                byte[] bytes = java.nio.file.Files.readAllBytes(f.toPath());
                loaded = f.getName().endsWith(".class")
                        ? BytecodeDisassembler.disassemble(bytes)  // .class — дизасемблюємо в текст
                        : new String(bytes, StandardCharsets.UTF_8);
            } catch (IOException e) {
                failure = e.getMessage();
            }
            final String text = loaded;
            final String error = failure;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (error != null) {
                    tvStatus.setText("Error: " + error);
                    tvStatus.setTextColor(0xFFFF6B6B);
                    Toast.makeText(this, "Error loading: " + error, Toast.LENGTH_LONG).show();
                    return;
                }
                originalCode = text;
                codeEditor.setText(text);
                codeEditor.setSelection(0);
                undoStack.clear();
                redoStack.clear();
                isModified = false;
                tvStatus.setText("Loaded");
                tvStatus.setTextColor(0xFFFFFFFF);
                // getLineCount() читає Layout, який з'явиться лише після верстки нового тексту.
                codeEditor.post(() -> updateLineNumbers(lineNumbers));
                Toast.makeText(this, "Loaded: " + f.getName(), Toast.LENGTH_SHORT).show();
            });
        }, "bytecode-load").start();
    }

    private void saveFile() {
        String code = codeEditor.getText().toString();
        File f = new File(filePath);

        if (f.getName().endsWith(".class")) {
            // Спроба скомпілювати ASM текст назад у .class
            saveClassFile(code, f);
        } else {
            // Текстовий файл
            saveTextFile(code, f);
        }
    }

    private void saveClassFile(String asmText, File outFile) {
        new Thread(() -> {
            try {
                // Парсимо ASM текст через Textifier → ClassNode не працює з текстом напряму
                // Тому зберігаємо як є (якщо файл був .class — попереджаємо)
                runOnUiThread(() -> {
                    Dialogs.rounded(this)
                            .setTitle("Bytecode Editor")
                            .setMessage("You are editing disassembled bytecode text.\n\n"
                                    + "To save as .class, the text must be valid ASM format.\n"
                                    + "Current changes will be saved as text (.asm).")
                            .setPositiveButton("Save as .asm", (d, w) -> {
                                File asmFile = new File(outFile.getParent(),
                                        outFile.getName().replace(".class", ".asm"));
                                saveTextFile(mediaPlayer_getText(), asmFile);
                            })
                            .setNegativeButton("Cancel", null)
                            .show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }, "bytecode-save").start();
    }

    private String mediaPlayer_getText() {
        return codeEditor.getText().toString();
    }

    private void saveTextFile(String content, File outFile) {
        try {
            FileOutputStream fos = new FileOutputStream(outFile);
            fos.write(content.getBytes(StandardCharsets.UTF_8));
            fos.close();
            originalCode = content;
            isModified = false;
            tvStatus.setText("Saved");
            tvStatus.setTextColor(0xFF499C54);
            Toast.makeText(this, "Saved: " + outFile.getName(), Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            Toast.makeText(this, "Save error: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void validate() {
        String code = codeEditor.getText().toString();
        String[] linesArr = code.split("\n", -1);
        java.util.Set<String> labels = new java.util.HashSet<>();
        java.util.List<String[]> jumps = new java.util.ArrayList<>();
        StringBuilder warnings = new StringBuilder();
        int errors = 0, methods = 0;
        boolean insideMethod = false, hasClass = false;
        for (int i = 0; i < linesArr.length; i++) {
            String line = linesArr[i].trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) continue;
            if (line.startsWith(".class ")) { hasClass = true; continue; }
            if (line.startsWith(".super ") || line.startsWith(".source ") || line.startsWith(".implements ")) continue;
            if (line.startsWith(".field ")) continue;
            if (line.startsWith(".method ")) {
                if (insideMethod) { errors += appendIssue(warnings, i + 1, "method started before previous .end method"); }
                insideMethod = true; methods++; continue;
            }
            if (line.equals(".end method")) {
                if (!insideMethod) errors += appendIssue(warnings, i + 1, ".end method without .method");
                insideMethod = false; continue;
            }
            if (line.startsWith(".limit ") || line.startsWith(".throws ") || line.startsWith(".annotation")) continue;
            if (line.endsWith(":")) {
                String label = line.substring(0, line.length() - 1).trim();
                if (!labels.add(label)) errors += appendIssue(warnings, i + 1, "duplicate label '" + label + "'");
                continue;
            }
            if (!insideMethod) {
                errors += appendIssue(warnings, i + 1, "instruction outside a method");
                continue;
            }
            String[] parts = line.split("\\s+", 2);
            String op = parts[0].toLowerCase(Locale.ROOT);
            if (!BytecodeHighlighter.isOpcode(op)) {
                errors += appendIssue(warnings, i + 1, "unknown JVM opcode '" + op + "'");
            } else if ((op.startsWith("if") || op.startsWith("goto") || op.startsWith("jsr")) && parts.length > 1) {
                String label = parts[1].trim().split("[,\\s]", 2)[0];
                if (!label.isEmpty()) jumps.add(new String[]{String.valueOf(i + 1), label});
            }
        }
        if (!hasClass) errors += appendIssue(warnings, 1, "missing .class declaration");
        if (insideMethod) errors += appendIssue(warnings, linesArr.length, "missing .end method");
        if (methods == 0) errors += appendIssue(warnings, 1, "no .method block found");
        for (String[] jump : jumps) if (!labels.contains(jump[1]))
            errors += appendIssue(warnings, Integer.parseInt(jump[0]), "undefined label '" + jump[1] + "'");
        int lines = linesArr.length;

        if (errors == 0) {
            Toast.makeText(this, "✓ " + lines + " lines — no issues found", Toast.LENGTH_SHORT).show();
            tvStatus.setText("✓ Valid (" + lines + " lines)");
            tvStatus.setTextColor(0xFF499C54);
        } else {
            tvStatus.setText("⚠ " + errors + " warning(s)");
            tvStatus.setTextColor(0xFFFFF176);
            Dialogs.rounded(this)
                    .setTitle("Validation: " + errors + " warning(s)")
                    .setMessage(warnings.toString())
                    .setPositiveButton("OK", null)
                    .show();
        }
    }

    private int appendIssue(StringBuilder out, int line, String message) {
        out.append("Line ").append(line).append(": ").append(message).append('\n');
        return 1;
    }

    private void addStructureButton(LinearLayout bar, String title, String snippet) {
        TextView button = createToolButton(title);
        button.setTextSize(11);
        button.setPadding(dp(10), dp(5), dp(10), dp(5));
        button.setBackgroundResource(android.R.drawable.list_selector_background);
        button.setOnClickListener(v -> insertStructure(snippet));
        bar.addView(button);
    }

    private void insertStructure(String snippet) {
        if (codeEditor == null) return;
        android.text.Editable editable = codeEditor.getText();
        int start = Math.max(0, Math.min(codeEditor.getSelectionStart(), editable.length()));
        int end = Math.max(start, Math.min(codeEditor.getSelectionEnd(), editable.length()));
        String insertion = snippet;
        if (start > 0 && editable.charAt(start - 1) != '\n' && !snippet.startsWith(" ")) insertion = "\n" + insertion;
        if (end < editable.length() && editable.charAt(end) != '\n' && !insertion.endsWith("\n")) insertion += "\n";
        editable.replace(start, end, insertion);
        int caret = start + insertion.length();
        codeEditor.setSelection(Math.max(start, caret - (insertion.endsWith("\n") ? 1 : 0)));
        codeEditor.requestFocus();
    }

    private void undo() {
        if (undoStack.isEmpty()) return;
        redoStack.add(codeEditor.getText().toString());
        String prev = undoStack.remove(undoStack.size() - 1);
        codeEditor.setText(prev);
        codeEditor.setSelection(Math.min(prev.length(), codeEditor.length()));
    }

    private void redo() {
        if (redoStack.isEmpty()) return;
        undoStack.add(codeEditor.getText().toString());
        String next = redoStack.remove(redoStack.size() - 1);
        codeEditor.setText(next);
        codeEditor.setSelection(Math.min(next.length(), codeEditor.length()));
    }

    private void updateLineNumbers(TextView tv) {
        int lines = codeEditor.getLineCount();
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= lines; i++) {
            sb.append(i).append("\n");
        }
        tv.setText(sb.toString());
    }

    private TextView createToolButton(String text) {
        TextView btn = new TextView(this);
        btn.setText(text);
        btn.setTextColor(0xFFBBBBBB);
        btn.setTextSize(11);
        btn.setPadding(dp(10), dp(4), dp(10), dp(4));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(4));
        btn.setLayoutParams(lp);
        return btn;
    }

    @Override
    public void onBackPressed() {
        if (isModified) {
            Dialogs.rounded(this)
                    .setTitle("Unsaved changes")
                    .setPositiveButton("Save", (d, w) -> { saveFile(); finish(); })
                    .setNegativeButton("Discard", (d, w) -> finish())
                    .setNeutralButton("Cancel", null)
                    .show();
        } else {
            super.onBackPressed();
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private static class StandardCharsets {
        static java.nio.charset.Charset UTF_8 = java.nio.charset.StandardCharsets.UTF_8;
    }
}
