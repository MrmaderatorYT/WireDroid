package com.ccs.wiredroid.ui;

import android.app.Activity;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.ccs.wiredroid.R;
import com.ccs.wiredroid.project.ProjectManager;
import com.ccs.wiredroid.tools.refactor.RefactoringHelper;
import com.ccs.wiredroid.util.AppTheme;

import java.io.File;

import io.github.rosemoe.sora.widget.CodeEditor;

public final class RefactorController {

    /**
     * What this needs from the screen around it, minus anything about the
     * editors — that arrives as an {@link EditorWorkspace}. Six of these used to
     * be getters for the panes and their tabs, which is the whole reason a
     * refactoring dialog had a twelve-member view of the activity.
     */
    public interface Callback {
        ProjectManager getProjectManager();
        AppTheme getTheme();
        void runOnUiThread(@NonNull Runnable r);
        void refreshProblemsAsync();
        void reloadTab(FileTab tab);
        int dp(int v);
    }

    private final Activity activity;
    private final Callback callback;
    /** The panes and what they hold; a live view, not a snapshot. */
    private final EditorWorkspace ws;

    public RefactorController(Activity activity, EditorWorkspace ws, Callback callback) {
        this.activity = activity;
        this.ws = ws;
        this.callback = callback;
    }

    public void showDialog() {
        CodeEditor ed = ws.activeEditor;
        if (ed == null) return;

        io.github.rosemoe.sora.text.Cursor cursor = ed.getCursor();
        int line = cursor.getLeftLine();
        int col = cursor.getLeftColumn();
        AppTheme theme = callback.getTheme();

        String lineText = "";
        try { lineText = ed.getText().getLine(line).toString(); } catch (Exception ignored) {}

        String selectedText = "";
        try {
            io.github.rosemoe.sora.text.Content content = ed.getText();
            int leftLine = cursor.getLeftLine();
            int leftCol = cursor.getLeftColumn();
            int rightLine = cursor.getRightLine();
            int rightCol = cursor.getRightColumn();
            if (leftLine == rightLine) {
                selectedText = content.getLine(leftLine).subSequence(leftCol, rightCol).toString().trim();
            } else {
                StringBuilder sb = new StringBuilder();
                sb.append(content.getLine(leftLine).subSequence(leftCol, content.getLine(leftLine).length()));
                for (int i = leftLine + 1; i < rightLine; i++) sb.append("\n").append(content.getLine(i));
                if (rightLine < content.getLineCount()) sb.append("\n").append(content.getLine(rightLine).subSequence(0, rightCol));
                selectedText = sb.toString().trim();
            }
        } catch (Exception ignored) {}

        if (selectedText.isEmpty()) selectedText = extractWordAtCursor(lineText, col);

        String finalSelectedText = selectedText;
        String[] items = {
                activity.getString(R.string.refactor_menu_generate),
                activity.getString(R.string.refactor_menu_implement_methods),
                activity.getString(R.string.refactor_menu_organize_imports),
                activity.getString(R.string.refactor_menu_rename_symbol, selectedText),
                activity.getString(R.string.refactor_menu_rename_package),
                "Extract Method",
                "Extract Variable",
                "Inline Method",
                "Find Usages of '" + selectedText + "'"
        };

        androidx.appcompat.app.AlertDialog dialog = Dialogs.rounded(activity)
                .setTitle(activity.getString(R.string.refactor_dialog_title))
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0: showGenerateDialog(); break;
                        case 1: showImplementMethodsDialog(); break;
                        case 2: organizeImports(); break;
                        case 3: showRenameDialog(finalSelectedText); break;
                        case 4: showRenamePackageDialog(); break;
                        case 5: showExtractMethodDialog(); break;
                        case 6: showExtractVariableDialog(); break;
                        case 7: showInlineDialog(finalSelectedText); break;
                        case 8: showFindUsagesDialog(finalSelectedText); break;
                    }
                })
                .show();
        Dialogs.style(dialog, theme);
    }

    public void showRenamePackageDialog() {
        ProjectManager pm = callback.getProjectManager();
        if (pm == null || pm.getProjectDir() == null) return;

        // Resolved into a local that is never reassigned, so the dialog lambda
        // below can capture it: a variable written inside a try block is not
        // effectively final and cannot cross into a lambda.
        String resolved = "";
        try {
            resolved = com.ccs.wiredroid.project.ProjectLayoutHelper.mainPackageName(pm.getProjectDir());
        } catch (Exception ignored) {}
        final String currentPkg = resolved;
        AppTheme theme = callback.getTheme();

        EditText input = new EditText(activity);
        input.setText(currentPkg);
        if (theme != null) {
            input.setTextColor(theme.text);
            input.setHintTextColor(theme.textDim);
            android.graphics.drawable.GradientDrawable sBg = new android.graphics.drawable.GradientDrawable();
            sBg.setColor(com.ccs.wiredroid.util.Colors.blend(theme.consoleBg, theme.bg, 0.4f));
            sBg.setCornerRadius(callback.dp(8));
            sBg.setStroke(callback.dp(1), theme.separator);
            input.setBackground(sBg);
            int pad = callback.dp(14);
            input.setPadding(pad, pad, pad, pad);
        } else {
            input.setPadding(48, 24, 48, 24);
        }

        androidx.appcompat.app.AlertDialog dialog = Dialogs.rounded(activity)
                .setTitle(activity.getString(R.string.refactor_safe_rename_package))
                .setMessage(activity.getString(R.string.refactor_enter_package_name))
                .setView(input)
                .setPositiveButton(activity.getString(R.string.refactor_rename), (d, w) -> {
                    String finalPkg = currentPkg;
                    String newPkg = input.getText().toString().trim();
                    if (!newPkg.isEmpty()) {
                        snapshotBeforeRefactor("Before rename package");
                        boolean ok = com.ccs.wiredroid.util.PackageRenameHelper.renamePackage(pm.getProjectDir(), finalPkg, newPkg);
                        if (ok) {
                            Toast.makeText(activity,
                                    activity.getString(R.string.refactor_package_renamed, newPkg), Toast.LENGTH_SHORT).show();
                            callback.refreshProblemsAsync();
                        }
                    }
                })
                .setNegativeButton(activity.getString(R.string.refactor_cancel), null)
                .show();
        Dialogs.style(dialog, theme);
    }

    public void reloadTab(FileTab tab) {
        if (tab == null || tab.file == null || !tab.file.exists()) return;
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(tab.file.toPath());
            String content = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            CodeEditor ed = (tab == ws.leftTab) ? ws.editor : ws.editor2;
            if (ed != null) ed.setText(content);
        } catch (Exception ignored) {}
    }

    private String extractWordAtCursor(String lineText, int col) {
        if (lineText.isEmpty() || col > lineText.length()) return "";
        int start = col;
        while (start > 0 && Character.isJavaIdentifierPart(lineText.charAt(start - 1))) start--;
        int end = col;
        while (end < lineText.length() && Character.isJavaIdentifierPart(lineText.charAt(end))) end++;
        return lineText.substring(start, end);
    }

    private void showRenameDialog(String currentName) {
        AppTheme theme = callback.getTheme();
        EditText input = new EditText(activity);
        input.setText(currentName);
        input.setHint(R.string.refactor_rename_hint);
        input.setTextColor(theme.text);
        input.setHintTextColor(theme.textDim);
        input.setSelection(currentName.length());
        int pad = callback.dp(16);
        input.setPadding(pad, pad, pad, pad);

        androidx.appcompat.app.AlertDialog dialog = Dialogs.rounded(activity)
                .setTitle(R.string.refactor_rename_title)
                .setView(input)
                .setPositiveButton("Rename", (d, w) -> {
                    String newName = input.getText().toString().trim();
                    if (newName.isEmpty() || newName.equals(currentName)) return;
                    if (!RefactoringHelper.isValidIdentifier(newName)) {
                        Toast.makeText(activity, activity.getString(R.string.toast_invalid_identifier), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    File projectRoot = callback.getProjectManager() != null ? callback.getProjectManager().getProjectDir() : null;
                    snapshotBeforeRefactor("Before rename symbol");
                    RefactoringHelper.renameSymbolAsync(activity, projectRoot, currentName, newName, result -> {
                        Toast.makeText(activity, result.summary, Toast.LENGTH_LONG).show();
                        if (result.filesChanged > 0) {
                            callback.refreshProblemsAsync();
                            callback.reloadTab(ws.tabs().getActiveTab());
                        }
                    });
                })
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
        Dialogs.style(dialog, theme);
    }

    private void showExtractMethodDialog() {
        CodeEditor ed = ws.activeEditor;
        if (ed == null) return;
        AppTheme theme = callback.getTheme();
        io.github.rosemoe.sora.text.Cursor cursor = ed.getCursor();
        int selStartLine = cursor.getLeftLine();
        int selEndLine = cursor.getRightLine();

        if (selStartLine == selEndLine && selStartLine == cursor.getRightLine()) {
            Toast.makeText(activity, activity.getString(R.string.toast_select_code_block), Toast.LENGTH_SHORT).show();
            return;
        }

        EditText input = new EditText(activity);
        input.setHint(R.string.refactor_extract_method_hint);
        input.setTextColor(theme.text);
        input.setHintTextColor(theme.textDim);
        int pad = callback.dp(16);
        input.setPadding(pad, pad, pad, pad);

        androidx.appcompat.app.AlertDialog dialog = Dialogs.rounded(activity)
                .setTitle(R.string.refactor_extract_method_title)
                .setView(input)
                .setPositiveButton("Extract", (d, w) -> {
                    String methodName = input.getText().toString().trim();
                    if (methodName.isEmpty()) return;
                    if (!RefactoringHelper.isValidIdentifier(methodName)) {
                        Toast.makeText(activity, activity.getString(R.string.toast_invalid_identifier), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String source = ed.getText().toString();
                    snapshotBeforeRefactor("Before extract method");
                    RefactoringHelper.extractMethodAsync(activity, source, selStartLine, selEndLine, methodName, result ->
                            Toast.makeText(activity, result.summary, Toast.LENGTH_SHORT).show());
                })
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
        Dialogs.style(dialog, theme);
    }

    private void showExtractVariableDialog() {
        CodeEditor ed = ws.activeEditor;
        if (ed == null) return;
        AppTheme theme = callback.getTheme();
        io.github.rosemoe.sora.text.Cursor cursor = ed.getCursor();

        String selectedText = "";
        try {
            io.github.rosemoe.sora.text.Content content = ed.getText();
            int leftLine = cursor.getLeftLine();
            int leftCol = cursor.getLeftColumn();
            int rightLine = cursor.getRightLine();
            int rightCol = cursor.getRightColumn();
            if (leftLine == rightLine) {
                selectedText = content.getLine(leftLine).subSequence(leftCol, rightCol).toString().trim();
            } else {
                StringBuilder sb = new StringBuilder();
                sb.append(content.getLine(leftLine).subSequence(leftCol, content.getLine(leftLine).length()));
                for (int i = leftLine + 1; i < rightLine; i++) sb.append("\n").append(content.getLine(i));
                if (rightLine < content.getLineCount()) sb.append("\n").append(content.getLine(rightLine).subSequence(0, rightCol));
                selectedText = sb.toString().trim();
            }
        } catch (Exception ignored) {}

        if (selectedText.isEmpty()) {
            Toast.makeText(activity, activity.getString(R.string.toast_select_expression), Toast.LENGTH_SHORT).show();
            return;
        }

        EditText input = new EditText(activity);
        input.setHint(R.string.refactor_extract_variable_hint);
        input.setTextColor(theme.text);
        input.setHintTextColor(theme.textDim);
        int pad = callback.dp(16);
        input.setPadding(pad, pad, pad, pad);

        androidx.appcompat.app.AlertDialog dialog = Dialogs.rounded(activity)
                .setTitle(R.string.refactor_extract_variable_title)
                .setView(input)
                .setPositiveButton("Extract", (d, w) -> {
                    String varName = input.getText().toString().trim();
                    if (varName.isEmpty()) return;
                    if (!RefactoringHelper.isValidIdentifier(varName)) {
                        Toast.makeText(activity, activity.getString(R.string.toast_invalid_identifier), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String source = ed.getText().toString();
                    snapshotBeforeRefactor("Before extract variable");
                    RefactoringHelper.extractVariableAsync(source, cursor.getLeftLine(),
                            cursor.getLeftColumn(), cursor.getRightColumn(), varName,
                            result -> Toast.makeText(activity, result.summary, Toast.LENGTH_SHORT).show());
                })
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
        Dialogs.style(dialog, theme);
    }

    private void showInlineDialog(String methodName) {
        CodeEditor ed = ws.activeEditor;
        if (ed == null || methodName.isEmpty()) return;
        AppTheme theme = callback.getTheme();

        androidx.appcompat.app.AlertDialog dialog = Dialogs.rounded(activity)
                .setTitle(R.string.refactor_inline_title)
                .setMessage("Inline method '" + methodName + "'?")
                .setPositiveButton("Inline", (d, w) -> {
                    String source = ed.getText().toString();
                    snapshotBeforeRefactor("Before inline method");
                    RefactoringHelper.inlineMethodAsync(activity, source, methodName,
                            result -> Toast.makeText(activity, result.summary, Toast.LENGTH_SHORT).show());
                })
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
        Dialogs.style(dialog, theme);
    }

    private void showFindUsagesDialog(String symbolName) {
        if (symbolName.isEmpty() || callback.getProjectManager() == null) return;
        AppTheme theme = callback.getTheme();

        File projectRoot = callback.getProjectManager().getProjectDir();
        new Thread(() -> {
            java.util.List<RefactoringHelper.UsageLocation> usages = RefactoringHelper.findUsages(projectRoot, symbolName);
            callback.runOnUiThread(() -> {
                if (usages.isEmpty()) {
                    Toast.makeText(activity, activity.getString(R.string.toast_no_usages, symbolName), Toast.LENGTH_SHORT).show();
                    return;
                }
                StringBuilder sb = new StringBuilder();
                sb.append(usages.size()).append(" usages of '").append(symbolName).append("':\n\n");
                for (int i = 0; i < Math.min(usages.size(), 20); i++) {
                    RefactoringHelper.UsageLocation u = usages.get(i);
                    sb.append(u.file.getName()).append(":").append(u.line).append("  ").append(u.lineContent.trim()).append("\n");
                }
                if (usages.size() > 20) sb.append("... and ").append(usages.size() - 20).append(" more");

                androidx.appcompat.app.AlertDialog dialog = Dialogs.rounded(activity)
                        .setTitle("Usages of '" + symbolName + "'")
                        .setMessage(sb.toString())
                        .setPositiveButton("OK", null)
                        .show();
                Dialogs.style(dialog, theme);
            });
        }).start();
    }

    public void organizeImports() {
        CodeEditor ed = ws.activeEditor;
        if (ed == null) return;
        String source = ed.getText().toString();
        String organized = com.ccs.wiredroid.util.AutoImportHelper.organizeImports(source);
        if (!organized.equals(source)) {
            snapshotBeforeRefactor("Before organize imports");
            ed.setText(organized);
            Toast.makeText(activity, R.string.refactor_imports_organized, Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(activity, R.string.refactor_imports_already_organized, Toast.LENGTH_SHORT).show();
        }
    }

    public static class FieldInfo {
        public final String type;
        public final String name;
        public FieldInfo(String type, String name) { this.type = type; this.name = name; }
    }

    /**
     * The instance fields of the primary type.
     *
     * <p>Scoped to that type's own body at brace depth zero, so a nested class's
     * fields and any local variable stay out of it. Static members are skipped:
     * a constant has no business in a constructor, a getter or an equals.</p>
     */
    private java.util.List<FieldInfo> parseFields(String source) {
        java.util.List<FieldInfo> fields = new java.util.ArrayList<>();
        String clean = com.ccs.wiredroid.tools.refactor.ImplementMethodsHelper.blankOut(source);
        com.ccs.wiredroid.tools.refactor.ImplementMethodsHelper.TypeDecl decl =
                com.ccs.wiredroid.tools.refactor.ImplementMethodsHelper.primaryType(source);

        int from = (decl != null && decl.bodyStart >= 0) ? decl.bodyStart : 0;
        int to = (decl != null && decl.bodyEnd > from) ? decl.bodyEnd : clean.length();

        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "^\\s*((?:(?:public|protected|private|static|final|transient|volatile)\\s+)*)"
                        + "([\\w$.<>\\[\\],\\s?]+?)\\s+([\\w$]+)\\s*(?:=.*)?$",
                java.util.regex.Pattern.DOTALL);

        int depth = 0, start = from;
        for (int i = from; i < to && i < clean.length(); i++) {
            char c = clean.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                if (--depth == 0) start = i + 1;
            } else if (c == ';' && depth == 0) {
                String member = source.substring(start, i);
                start = i + 1;
                if (member.indexOf('(') >= 0) continue;            // a method or constructor
                java.util.regex.Matcher m = p.matcher(member.trim());
                if (!m.matches()) continue;
                if (m.group(1).contains("static")) continue;
                String type = m.group(2).trim().replaceAll("\\s+", " ");
                if (type.isEmpty() || type.equals("return")) continue;
                fields.add(new FieldInfo(type, m.group(3)));
            }
        }
        return fields;
    }

    private String capitalize(String str) {
        if (str == null || str.isEmpty()) return "";
        return Character.toUpperCase(str.charAt(0)) + str.substring(1);
    }

    /**
     * Places generated members at the end of the primary type's body.
     *
     * <p>Not before the last {@code }} in the file: with a nested class, an enum
     * after the main type, or a trailing comment holding a brace, that lands the
     * code in the wrong place — or outside every type.</p>
     */
    private void insertCodeIntoClass(CodeEditor ed, String codeToInsert) {
        String source = ed.getText().toString();
        snapshotBeforeRefactor("Before generate code");
        int at = -1;
        try {
            com.ccs.wiredroid.tools.refactor.ImplementMethodsHelper.TypeDecl decl =
                    com.ccs.wiredroid.tools.refactor.ImplementMethodsHelper.primaryType(source);
            if (decl != null && decl.bodyEnd > 0 && decl.bodyEnd <= source.length()) at = decl.bodyEnd;
        } catch (Throwable ignored) {
        }
        if (at < 0) at = source.lastIndexOf('}');
        if (at >= 0) {
            ed.setText(source.substring(0, at) + "\n" + codeToInsert + source.substring(at));
        } else {
            ed.setText(source + "\n" + codeToInsert);
        }
    }

    private void snapshotBeforeRefactor(String label) {
        FileTab tab = ws.tabs().getActiveTab();
        if (tab == null || tab.file == null || ws.activeEditor == null) return;
        com.ccs.wiredroid.util.LocalHistoryManager.saveSnapshot(
                activity, tab.file, ws.activeEditor.getText().toString(), label);
    }

    public void showGenerateDialog() {
        CodeEditor ed = ws.activeEditor;
        if (ed == null) return;
        AppTheme theme = callback.getTheme();

        String[] options = {
                "Constructor…",
                activity.getString(R.string.generate_constructor_no_arg),
                "Getters",
                "Setters",
                "Getters and Setters",
                "toString()",
                "equals() and hashCode()"
        };

        androidx.appcompat.app.AlertDialog dialog = Dialogs.rounded(activity)
                .setTitle("Generate Boilerplate")
                .setItems(options, (d, which) -> {
                    String source = ed.getText().toString();
                    java.util.List<FieldInfo> fields = parseFields(source);

                    if (which == 1) {
                        // No-arg constructor directly
                        generateBoilerplate(ed, which, source, java.util.Collections.emptyList());
                        return;
                    }

                    if ((which >= 2 && which <= 4) && fields.isEmpty()) {
                        Toast.makeText(activity, R.string.generate_no_fields, Toast.LENGTH_SHORT).show();
                        return;
                    }

                    if (fields.isEmpty()) {
                        generateBoilerplate(ed, which, source, java.util.Collections.emptyList());
                        return;
                    }

                    boolean allowEmpty = (which == 0 || which == 5 || which == 6);
                    pickFields(fields, allowEmpty, chosen -> generateBoilerplate(ed, which, source, chosen));
                })
                .show();
        Dialogs.style(dialog, theme);
    }

    private interface FieldsChosen {
        void onChosen(java.util.List<FieldInfo> fields);
    }

    /**
     * Asks which fields the generated members should cover.
     *
     * <p>All ticked to begin with: covering every field is the common case, and
     * unticking two is less work than ticking eight.</p>
     */
    private void pickFields(java.util.List<FieldInfo> fields, boolean allowEmpty, FieldsChosen then) {
        AppTheme theme = callback.getTheme();
        CharSequence[] labels = new CharSequence[fields.size()];
        boolean[] checked = new boolean[fields.size()];
        for (int i = 0; i < fields.size(); i++) {
            labels[i] = fields.get(i).name + " : " + fields.get(i).type;
            checked[i] = true;
        }
        androidx.appcompat.app.AlertDialog dialog = Dialogs.rounded(activity)
                .setTitle(R.string.generate_pick_fields_title)
                .setMultiChoiceItems(labels, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton(R.string.implement_generate, (d, w) -> {
                    java.util.List<FieldInfo> chosen = new java.util.ArrayList<>();
                    for (int i = 0; i < fields.size(); i++) {
                        if (checked[i]) chosen.add(fields.get(i));
                    }
                    if (chosen.isEmpty() && !allowEmpty) {
                        Toast.makeText(activity, R.string.generate_none_selected, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    then.onChosen(chosen);
                })
                .setNeutralButton(R.string.generate_select_none, (d, w) -> {
                    if (allowEmpty) {
                        then.onChosen(new java.util.ArrayList<>());
                    } else {
                        Toast.makeText(activity, R.string.generate_none_selected, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        Dialogs.style(dialog, theme);
    }

    private void generateBoilerplate(CodeEditor ed, int which, String source,
                                     java.util.List<FieldInfo> fields) {
        com.ccs.wiredroid.tools.refactor.ImplementMethodsHelper.TypeDecl decl =
                com.ccs.wiredroid.tools.refactor.ImplementMethodsHelper.primaryType(source);
        String className = (decl != null && !decl.name.isEmpty()) ? decl.name : "MyClass";

        StringBuilder code = new StringBuilder();
        switch (which) {
            case 0:
                code.append(buildConstructor(className, fields));
                break;

            case 1:
                code.append(buildConstructor(className, java.util.Collections.emptyList()));
                break;

            case 2:
                for (FieldInfo f : fields) code.append(getter(f));
                break;

            case 3:
                for (FieldInfo f : fields) code.append(setter(f));
                break;

            case 4:
                for (FieldInfo f : fields) code.append(getter(f)).append(setter(f));
                break;

            case 5:
                code.append(buildToString(className, fields));
                break;

            case 6:
                code.append(buildEqualsAndHashCode(className, fields));
                break;
        }

        insertCodeIntoClass(ed, code.toString());
        callback.refreshProblemsAsync();
        Toast.makeText(activity, R.string.generate_done, Toast.LENGTH_SHORT).show();
    }

    public static String buildConstructor(String className, java.util.List<FieldInfo> fields) {
        StringBuilder code = new StringBuilder();
        code.append("    public ").append(className).append("(");
        if (fields != null) {
            for (int i = 0; i < fields.size(); i++) {
                if (i > 0) code.append(", ");
                code.append(fields.get(i).type).append(" ").append(fields.get(i).name);
            }
        }
        code.append(") {\n");
        if (fields != null) {
            for (FieldInfo f : fields) {
                code.append("        this.").append(f.name).append(" = ").append(f.name).append(";\n");
            }
        }
        code.append("    }\n\n");
        return code.toString();
    }

    public static String buildToString(String className, java.util.List<FieldInfo> fields) {
        StringBuilder code = new StringBuilder();
        code.append("    @Override\n    public String toString() {\n");
        if (fields == null || fields.isEmpty()) {
            code.append("        return \"").append(className).append("{}\";\n    }\n\n");
            return code.toString();
        }
        code.append("        return \"").append(className).append("{\" +\n");
        for (int i = 0; i < fields.size(); i++) {
            FieldInfo f = fields.get(i);
            code.append("                \"").append(i > 0 ? ", " : "").append(f.name);
            if (f.type.equals("String") || f.type.equals("char") || f.type.equals("Character")) {
                code.append("='\" + ").append(f.name).append(" + '\\'' +\n");
            } else if (f.type.endsWith("[][]")) {
                code.append("=\" + java.util.Arrays.deepToString(").append(f.name).append(") +\n");
            } else if (f.type.endsWith("[]")) {
                code.append("=\" + java.util.Arrays.toString(").append(f.name).append(") +\n");
            } else {
                code.append("=\" + ").append(f.name).append(" +\n");
            }
        }
        code.append("                '}';\n    }\n\n");
        return code.toString();
    }

    public static String buildEqualsAndHashCode(String className, java.util.List<FieldInfo> fields) {
        StringBuilder code = new StringBuilder();
        code.append("    @Override\n    public boolean equals(Object o) {\n")
            .append("        if (this == o) return true;\n")
            .append("        if (o == null || getClass() != o.getClass()) return false;\n");

        if (fields == null || fields.isEmpty()) {
            code.append("        return true;\n    }\n\n")
                .append("    @Override\n    public int hashCode() {\n")
                .append("        return 0;\n    }\n\n");
            return code.toString();
        }

        code.append("        ").append(className).append(" that = (").append(className).append(") o;\n");
        for (FieldInfo f : fields) {
            if (isPrimitive(f.type) && !f.type.equals("float") && !f.type.equals("double")) {
                code.append("        if (").append(f.name).append(" != that.").append(f.name)
                    .append(") return false;\n");
            } else if (f.type.equals("float") || f.type.equals("double")) {
                String box = f.type.equals("float") ? "Float" : "Double";
                code.append("        if (").append(box).append(".compare(").append(f.name)
                    .append(", that.").append(f.name).append(") != 0) return false;\n");
            } else if (f.type.endsWith("[][]")) {
                code.append("        if (!java.util.Arrays.deepEquals(").append(f.name)
                    .append(", that.").append(f.name).append(")) return false;\n");
            } else if (f.type.endsWith("[]")) {
                code.append("        if (!java.util.Arrays.equals(").append(f.name)
                    .append(", that.").append(f.name).append(")) return false;\n");
            } else {
                code.append("        if (!java.util.Objects.equals(").append(f.name)
                    .append(", that.").append(f.name).append(")) return false;\n");
            }
        }
        code.append("        return true;\n    }\n\n")
            .append("    @Override\n    public int hashCode() {\n")
            .append("        return java.util.Objects.hash(");
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) code.append(", ");
            FieldInfo f = fields.get(i);
            if (f.type.endsWith("[][]")) {
                code.append("java.util.Arrays.deepHashCode(").append(f.name).append(")");
            } else if (f.type.endsWith("[]")) {
                code.append("java.util.Arrays.hashCode(").append(f.name).append(")");
            } else {
                code.append(f.name);
            }
        }
        code.append(");\n    }\n\n");
        return code.toString();
    }

    private String getter(FieldInfo f) {
        String name = (f.type.equals("boolean") ? "is" : "get") + capitalize(f.name);
        return "    public " + f.type + " " + name + "() {\n"
                + "        return " + f.name + ";\n    }\n\n";
    }

    private String setter(FieldInfo f) {
        return "    public void set" + capitalize(f.name) + "(" + f.type + " " + f.name + ") {\n"
                + "        this." + f.name + " = " + f.name + ";\n    }\n\n";
    }

    private static boolean isPrimitive(String type) {
        switch (type) {
            case "boolean": case "byte": case "short": case "char":
            case "int": case "long": case "float": case "double":
                return true;
            default:
                return false;
        }
    }

    private void showImplementMethodsDialog() {
        CodeEditor ed = ws.activeEditor;
        if (ed == null) return;

        String source = ed.getText().toString();
        FileTab tab = ws.tabs().getActiveTab();
        File currentFile = tab != null ? tab.file : null;
        ProjectManager pm = callback.getProjectManager();
        File root = pm != null ? pm.getProjectDir() : null;

        final java.util.List<com.ccs.wiredroid.tools.refactor.ImplementMethodsHelper.Stub> stubs;
        try {
            stubs = com.ccs.wiredroid.tools.refactor.ImplementMethodsHelper
                    .missingMethods(source, currentFile, root);
        } catch (Throwable t) {
            Toast.makeText(activity, activity.getString(R.string.implement_failed, String.valueOf(t.getMessage())),
                    Toast.LENGTH_LONG).show();
            return;
        }

        if (stubs.isEmpty()) {
            Toast.makeText(activity, R.string.implement_nothing_missing, Toast.LENGTH_SHORT).show();
            return;
        }

        // Everything is ticked by default: implementing all of them is the usual
        // intent, and unticking is cheaper than hunting for the one that matters.
        AppTheme theme = callback.getTheme();
        CharSequence[] labels = new CharSequence[stubs.size()];
        boolean[] checked = new boolean[stubs.size()];
        for (int i = 0; i < stubs.size(); i++) {
            labels[i] = stubs.get(i).display;
            checked[i] = true;
        }

        androidx.appcompat.app.AlertDialog dialog = Dialogs.rounded(activity)
                .setTitle(R.string.implement_pick_title)
                .setMultiChoiceItems(labels, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton(R.string.implement_generate, (d, w) -> {
                    StringBuilder code = new StringBuilder();
                    int count = 0;
                    for (int i = 0; i < stubs.size(); i++) {
                        if (!checked[i]) continue;
                        if (count > 0) code.append('\n');
                        code.append(stubs.get(i).code);
                        count++;
                    }
                    if (count == 0) return;
                    insertCodeIntoClass(ed, code.toString());
                    callback.refreshProblemsAsync();
                    Toast.makeText(activity,
                            activity.getResources().getQuantityString(R.plurals.implement_generated, count, count),
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        Dialogs.style(dialog, theme);
    }
}
