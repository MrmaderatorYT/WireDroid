package com.ccs.wiredroid.ui;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.text.Editable;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.drawerlayout.widget.DrawerLayout;

import com.ccs.wiredroid.R;
import com.ccs.wiredroid.project.ProjectManager;
import com.ccs.wiredroid.project.ProjectScanner;
import com.ccs.wiredroid.util.AppTheme;
import com.ccs.wiredroid.util.FileTemplates;
import com.ccs.wiredroid.project.ProjectLayoutHelper;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class FileTreeController {

    /** One thread, so scans queue instead of racing; daemon so it never holds the process open. */
    private final java.util.concurrent.ExecutorService treeScanner =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "file-tree-scan");
                t.setDaemon(true);
                return t;
            });
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    /** Bumped per request; a result whose token is stale is dropped. */
    private int treeRefreshToken;

    public interface Callback {
        void onFileOpened(File file);
        void onRefreshNeeded();
        AppTheme getTheme();
        ProjectManager getProjectManager();
        FileTreeAdapter getFileTreeAdapter();
        DrawerLayout getDrawerLayout();
        int dp(int v);
    }

    private final Activity activity;
    private final Callback callback;
    /** The panes and what they hold; a live view, not a snapshot. */
    private final EditorWorkspace ws;
    private File copiedFile;
    private File pendingArchiveFolder;
    private com.ccs.wiredroid.project.TreeExpansionState expansion;
    /** Project the current {@link #expansion} belongs to. */
    private File expansionRoot;

    public FileTreeController(Activity activity, EditorWorkspace ws, Callback callback) {
        this.activity = activity;
        this.callback = callback;
        this.ws = ws;
    }

    /**
     * Expansion state for the open project, recreated when the project changes.
     *
     * @return the state, or {@code null} when no project is open
     */
    public com.ccs.wiredroid.project.TreeExpansionState expansionState() {
        ProjectManager pm = callback.getProjectManager();
        if (pm == null || pm.getProjectDir() == null) return null;
        File root = pm.getProjectDir();
        if (expansion == null || expansionRoot == null || !expansionRoot.equals(root)) {
            expansion = new com.ccs.wiredroid.project.TreeExpansionState(activity, root);
            expansionRoot = root;
        }
        return expansion;
    }

    /**
     * Rebuilds the drawer's file tree.
     *
     * <p>The scan walks the project directory, which is disk work that grows
     * with the project: on a two-thousand-file project it was a fifth of a
     * second, paid on the main thread while the editor screen was trying to
     * appear. It now runs on a worker and only the adapter update comes back to
     * the UI thread.</p>
     *
     * <p>The expansion set is copied before it is handed over, because the
     * caller may toggle a folder while the scan is still running. A token drops
     * results from a scan that a newer one has already superseded, so a burst of
     * toggles leaves the tree showing the last one rather than whichever
     * finished last.</p>
     */
    public void refreshFileTree() {
        ProjectManager pm = callback.getProjectManager();
        if (pm == null) return;
        final File dir = pm.getProjectDir();
        if (dir == null) return;
        com.ccs.wiredroid.project.TreeExpansionState state = expansionState();
        final java.util.Set<String> expanded = state == null
                ? null : new java.util.HashSet<>(state.expandedPaths());
        final int token = ++treeRefreshToken;
        treeScanner.execute(() -> {
            final List<FileTreeNode> nodes =
                    ProjectScanner.listIdeaStyleTree(dir, expanded);
            ui.post(() -> {
                if (token != treeRefreshToken) return;
                FileTreeAdapter adapter = callback.getFileTreeAdapter();
                if (adapter != null) adapter.setNodes(nodes);
            });
        });
    }

    /** Opens or closes a folder in the drawer and redraws the tree. */
    public void toggleFolder(File folder) {
        com.ccs.wiredroid.project.TreeExpansionState state = expansionState();
        if (state == null) return;
        state.toggle(folder);
        refreshFileTree();
    }

    /**
     * Opens every ancestor of {@code file} so it is visible. The tree is only
     * rebuilt when the expansion actually changed, so opening several files from
     * the same folder does not redraw repeatedly.
     */
    public void revealInTree(File file) {
        com.ccs.wiredroid.project.TreeExpansionState state = expansionState();
        if (state == null) return;
        if (state.revealFile(file)) refreshFileTree();
    }

    public void expandAll() {
        com.ccs.wiredroid.project.TreeExpansionState state = expansionState();
        if (state == null) return;
        state.expandAll();
        refreshFileTree();
    }

    public void collapseAll() {
        com.ccs.wiredroid.project.TreeExpansionState state = expansionState();
        if (state == null) return;
        state.collapseAll();
        refreshFileTree();
    }

    public void showNewFileDialog() {
        ProjectManager pm = callback.getProjectManager();
        if (pm != null) showNewFileInFolderDialog(pm.getProjectDir());
    }

    public void showFolderContextMenu(File folder) {
        List<String> optionsList = new ArrayList<>();
        optionsList.add(activity.getString(R.string.tree_expand_all_in_folder));
        optionsList.add(activity.getString(R.string.tree_collapse_all_in_folder));
        optionsList.add(activity.getString(R.string.menu_create_file));
        optionsList.add(activity.getString(R.string.menu_create_folder));
        if (copiedFile != null && copiedFile.exists()) {
            optionsList.add(activity.getString(R.string.dialog_folder_context_paste));
        }
        optionsList.add(activity.getString(R.string.dialog_folder_context_archive));
        optionsList.add(activity.getString(R.string.dialog_file_context_rename));
        optionsList.add(activity.getString(R.string.dialog_file_context_delete));
        optionsList.add(activity.getString(R.string.menu_open_in_external));

        String[] options = optionsList.toArray(new String[0]);
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
                .setTitle(folder.getName())
                .setItems(options, (dialog, which) -> {
                    String selected = options[which];
                    if (selected.equals(activity.getString(R.string.tree_expand_all_in_folder))) {
                        com.ccs.wiredroid.project.TreeExpansionState state = expansionState();
                        if (state != null) {
                            state.expandRecursively(folder);
                            refreshFileTree();
                        }
                    } else if (selected.equals(activity.getString(R.string.tree_collapse_all_in_folder))) {
                        com.ccs.wiredroid.project.TreeExpansionState state = expansionState();
                        if (state != null) {
                            state.collapseRecursively(folder);
                            refreshFileTree();
                        }
                    } else if (selected.equals(activity.getString(R.string.menu_create_file))) {
                        showNewFileInFolderDialog(folder);
                    } else if (selected.equals(activity.getString(R.string.menu_create_folder))) {
                        showNewFolderInFolderDialog(folder);
                    } else if (selected.equals(activity.getString(R.string.dialog_folder_context_paste))) {
                        pasteFileToFolder(folder);
                    } else if (selected.equals(activity.getString(R.string.dialog_folder_context_archive))) {
                        createArchiveFromFolder(folder);
                    } else if (selected.equals(activity.getString(R.string.dialog_file_context_rename))) {
                        showRenameDialog(folder);
                    } else if (selected.equals(activity.getString(R.string.dialog_file_context_delete))) {
                        showDeleteDialog(folder);
                    } else if (selected.equals(activity.getString(R.string.menu_open_in_external))) {
                        openInExternalApp(folder);
                    }
                })
                .show();
    }

    public void showFileContextMenu(File file) {
        String[] options = {
                activity.getString(R.string.dialog_file_context_open),
                activity.getString(R.string.menu_open_in_hex),
                activity.getString(R.string.dialog_file_context_rename),
                activity.getString(R.string.dialog_file_context_copy),
                activity.getString(R.string.dialog_file_context_delete),
                activity.getString(R.string.menu_open_in_external),
                "Local History"
        };
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
                .setTitle(file.getName())
                .setItems(options, (dialog, which) -> {
                    switch (which) {
                        case 0: callback.onFileOpened(file); break;
                        case 1: HexEditorActivity.launch(activity, file); break;
                        case 2: showRenameDialog(file); break;
                        case 3: copiedFile = file; Toast.makeText(activity, R.string.toast_file_copied, Toast.LENGTH_SHORT).show(); break;
                        case 4: showDeleteDialog(file); break;
                        case 5: openInExternalApp(file); break;
                        case 6:
                            LocalHistoryDialog.show(activity, callback.getTheme(), file, content -> {
                                try {
                                    if (callback.getProjectManager() != null) {
                                        callback.getProjectManager().writeFile(file, content);
                                    } else {
                                        java.nio.file.Files.write(file.toPath(), content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                                    }
                                    callback.onFileOpened(file);
                                } catch (Exception e) {
                                    Toast.makeText(activity, "Error reverting file: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                                }
                            });
                            break;
                    }
                })
                .show();
    }

    private void openInExternalApp(File file) {
        try {
            android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(
                    activity,
                    activity.getPackageName() + ".fileprovider",
                    file
            );
            android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_VIEW);
            String mimeType = "*/*";
            String name = file.getName().toLowerCase(java.util.Locale.ROOT);
            if (file.isDirectory()) mimeType = "resource/folder";
            else if (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png")) mimeType = "image/*";
            else if (name.endsWith(".mp4") || name.endsWith(".avi")) mimeType = "video/*";
            else if (name.endsWith(".mp3") || name.endsWith(".wav")) mimeType = "audio/*";
            else if (name.endsWith(".apk")) mimeType = "application/vnd.android.package-archive";
            else if (name.endsWith(".pdf")) mimeType = "application/pdf";
            

            intent.setDataAndType(uri, mimeType);
            intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(android.content.Intent.createChooser(
                    intent, activity.getString(R.string.menu_open_in_external)));
        } catch (Exception e) {
            Toast.makeText(activity, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void showNewFileInFolderDialog(File folder) {
        String[] names = FileTemplates.getDisplayNames();
        String[] keys = FileTemplates.getKeys();

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.menu_create_file)
                .setItems(names, (dialog, which) -> {
                    String key = keys[which];
                    String[] tpl = FileTemplates.get(key);
                    if (tpl == null) return;
                    showNewFileNameDialog(folder, key, tpl[0]);
                })
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }

    private void showNewFileNameDialog(File folder, String templateKey, String templateName) {
        ProjectManager pm = callback.getProjectManager();
        EditText input = newEditText("");
        input.setHint("main." + templateKey);

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
                .setTitle(templateName)
                .setView(input)
                .setPositiveButton(R.string.dialog_create, (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) return;
                    String className = name.contains(".") ? name : name + "." + templateKey;
                    String[] tpl = FileTemplates.get(templateKey);
                    String template = "";
                    if (tpl != null) {
                        template = FileTemplates.format(tpl[1], className);
                    }
                    if (pm != null && pm.hasStandardLayout() && templateKey.equals(FileTemplates.KEY_CLASS)) {
                        try {
                            String pkg = com.ccs.wiredroid.project.ProjectLayoutHelper.mainPackageName(pm.getProjectDir());
                            template = "package " + pkg + ";\n\n" + template;
                        } catch (Exception ignored) {}
                    }
                    try {
                        if (className.contains("/") || className.contains("\\") || className.equals("..")) throw new IOException("Некоректна назва файлу");
                        File f = new File(folder, className);
                        if (!f.getCanonicalFile().getParentFile().equals(folder.getCanonicalFile())) throw new IOException("Файл за межами папки");
                        if (!f.createNewFile()) f = null;
                        if (f != null) com.ccs.wiredroid.wire.WireProject.write(f, template);
                        if (f != null) {
                            refreshFileTree();
                            callback.onFileOpened(f);
                            DrawerLayout drawer = callback.getDrawerLayout();
                            if (drawer != null) drawer.closeDrawer(Gravity.START);
                        } else {
                            Toast.makeText(activity, activity.getString(R.string.toast_file_exists, className), Toast.LENGTH_SHORT).show();
                        }
                    } catch (IOException e) {
                        Toast.makeText(activity, activity.getString(R.string.toast_error_prefix, e.getMessage()), Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }

    private void showNewFolderInFolderDialog(File folder) {
        EditText input = newEditText("");
        input.setHint(R.string.dialog_create_folder_hint);

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.dialog_create_folder_title)
                .setView(input)
                .setPositiveButton(R.string.dialog_create, (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) return;
                    File sub = new File(folder, name);
                    if (sub.exists()) {
                        Toast.makeText(activity, R.string.folder_already_exists, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (sub.mkdirs()) {
                        refreshFileTree();
                    } else {
                        Toast.makeText(activity, R.string.folder_create_failed, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }

    public void showRenameDialog(File file) {
        ProjectManager pm = callback.getProjectManager();
        TabsAdapter tabs = ws.tabs();
        EditText input = newEditText("");
        String currentNameWithoutExt = file.isDirectory() ? file.getName() :
                (file.getName().endsWith(".java") ? file.getName().substring(0, file.getName().length() - 5) : file.getName());
        input.setText(currentNameWithoutExt);

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.dialog_rename)
                .setView(input)
                .setPositiveButton(R.string.dialog_rename, (d, w) -> {
                    String newName = input.getText().toString().trim();
                    if (newName.isEmpty() || newName.equals(currentNameWithoutExt)) return;
                    File parent = file.getParentFile() != null ? file.getParentFile() : (pm != null ? pm.getProjectDir() : null);
                    if (parent == null) return;
                    File newFile;
                    if (file.isDirectory()) {
                        newFile = new File(parent, newName);
                    } else {
                        if (file.getName().endsWith(".java") && !newName.endsWith(".java") && !newName.contains(".")) {
                            newFile = new File(parent, newName + ".java");
                        } else {
                            newFile = new File(parent, newName);
                        }
                    }
                    int tabIdx = tabs != null ? tabs.indexOfFile(file) : -1;
                    if (tabIdx >= 0) callback.onRefreshNeeded();
                    if (file.renameTo(newFile)) {
                        if (tabIdx >= 0 && tabs != null) tabs.removeTab(tabIdx);
                        refreshFileTree();
                        if (newFile.isFile()) callback.onFileOpened(newFile);
                    } else {
                        Toast.makeText(activity, R.string.toast_rename_failed, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }

    public void showDeleteDialog(File file) {
        ProjectManager pm = callback.getProjectManager();
        TabsAdapter tabs = ws.tabs();
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.dialog_delete_file_title)
                .setMessage(activity.getString(R.string.dialog_delete_file_message, file.getName()))
                .setPositiveButton(R.string.dialog_delete, (d, w) -> {
                    int tabIdx = tabs != null ? tabs.indexOfFile(file) : -1;
                    if (tabIdx >= 0) callback.onRefreshNeeded();
                    if (pm != null) pm.deleteFile(file);
                    refreshFileTree();
                })
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }

    public void pasteFileToFolder(File folder) {
        if (copiedFile == null || !copiedFile.exists()) {
            copiedFile = null;
            return;
        }
        File dest = new File(folder, copiedFile.getName());
        if (dest.exists()) {
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.toast_file_exists_title)
                    .setMessage(activity.getString(R.string.toast_file_exists, copiedFile.getName()))
                    .setPositiveButton(R.string.dialog_overwrite, (d, w) -> doPasteFile(folder, dest))
                    .setNegativeButton(R.string.dialog_cancel, null)
                    .show();
        } else {
            doPasteFile(folder, dest);
        }
    }

    private void doPasteFile(File folder, File dest) {
        try {
            java.io.InputStream in = new java.io.FileInputStream(copiedFile);
            java.io.OutputStream out = new java.io.FileOutputStream(dest);
            byte[] buf = new byte[4096];
            int len;
            while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
            in.close();
            out.close();
            Toast.makeText(activity, activity.getString(R.string.toast_file_pasted, folder.getName()), Toast.LENGTH_SHORT).show();
            refreshFileTree();
        } catch (IOException e) {
            Toast.makeText(activity, activity.getString(R.string.toast_error_prefix, e.getMessage()), Toast.LENGTH_SHORT).show();
        }
    }

    public void createArchiveFromFolder(File folder) {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/zip");
        i.putExtra(Intent.EXTRA_TITLE, folder.getName() + ".zip");
        pendingArchiveFolder = folder;
        try {
            activity.startActivityForResult(i, 4009);
        } catch (Exception e) {
            Toast.makeText(activity, e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    public void archiveFolderToUri(Uri uri) {
        File folder = pendingArchiveFolder;
        if (folder == null || !folder.exists()) return;
        new Thread(() -> {
            try (java.io.OutputStream os = activity.getContentResolver().openOutputStream(uri);
                 java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(os)) {
                if (os == null) throw new IOException("Cannot open output");
                zipDir(folder, folder, zos);
                activity.runOnUiThread(() -> Toast.makeText(activity,
                        activity.getString(R.string.toast_archive_created, folder.getName() + ".zip"),
                        Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                activity.runOnUiThread(() -> Toast.makeText(activity, e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void zipDir(File root, File current, java.util.zip.ZipOutputStream zos) throws IOException {
        File[] files = current.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                zipDir(root, f, zos);
            } else {
                String path = root.toURI().relativize(f.toURI()).getPath();
                java.io.FileInputStream fis = new java.io.FileInputStream(f);
                zos.putNextEntry(new java.util.zip.ZipEntry(path));
                byte[] buf = new byte[4096];
                int len;
                while ((len = fis.read(buf)) > 0) zos.write(buf, 0, len);
                fis.close();
                zos.closeEntry();
            }
        }
    }

    private EditText newEditText(String hint) {
        EditText input = new EditText(activity);
        input.setText(hint);
        input.setTextColor(callback.getTheme().text);
        input.setHintTextColor(callback.getTheme().textDim);
        int pad = callback.dp(16);
        input.setPadding(pad, pad, pad, pad);
        return input;
    }
}
