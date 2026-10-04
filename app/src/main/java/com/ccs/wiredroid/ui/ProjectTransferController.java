package com.ccs.wiredroid.ui;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.widget.Toast;

import com.ccs.wiredroid.R;
import com.ccs.wiredroid.project.ProjectLayoutHelper;
import com.ccs.wiredroid.project.ProjectManager;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import io.github.rosemoe.sora.widget.CodeEditor;

/**
 * Encapsulates project file transfers, importing external files/media, Save As, and ZIP export.
 */
public final class ProjectTransferController {

    public static final int REQ_OPEN_FILE     = 4002;
    public static final int REQ_SAVE_AS       = 4003;
    public static final int REQ_EXPORT_PROJ   = 4004;
    public static final int REQ_PLAY_MEDIA    = 4007;
    public static final int REQ_IMPORT_FILES  = 4008;

    public interface Callback {
        ProjectManager getProjectManager();
        FileTreeAdapter getFileTreeAdapter();
        void refreshFileTree();
        void openFile(File file);
    }

    private final Activity activity;
    private final Callback callback;
    /** The panes and what they hold; a live view, not a snapshot. */
    private final EditorWorkspace ws;

    public ProjectTransferController(Activity activity, EditorWorkspace ws, Callback callback) {
        this.activity = activity;
        this.callback = callback;
        this.ws = ws;
    }

    public void importFilesToProject() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            activity.startActivityForResult(i, REQ_IMPORT_FILES);
        } catch (Exception e) {
            Toast.makeText(activity, e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    public void importFilesResult(Intent data) {
        handleImportFilesResult(data);
    }

    public void handleImportFilesResult(Intent data) {
        if (data == null) return;
        ProjectManager projectManager = callback.getProjectManager();
        if (projectManager == null || projectManager.getProjectDir() == null) {
            Toast.makeText(activity, R.string.toast_no_file_open, Toast.LENGTH_SHORT).show();
            return;
        }

        File targetDir = projectManager.getProjectDir();
        FileTreeAdapter fileTreeAdapter = callback.getFileTreeAdapter();
        if (fileTreeAdapter != null && fileTreeAdapter.getActiveFile() != null) {
            File active = fileTreeAdapter.getActiveFile();
            targetDir = active.isDirectory() ? active : active.getParentFile();
        }

        List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            ClipData clip = data.getClipData();
            for (int i = 0; i < clip.getItemCount(); i++) {
                uris.add(clip.getItemAt(i).getUri());
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }

        if (uris.isEmpty()) return;

        int imported = 0;
        for (Uri uri : uris) {
            try {
                String name = displayName(uri);
                if (name == null) name = "imported_" + System.currentTimeMillis();
                name = name.replaceAll("[^A-Za-z0-9._\\-]", "_");

                try (InputStream in = activity.getContentResolver().openInputStream(uri)) {
                    if (in == null) continue;
                    byte[] data2 = readAll(in);
                    File target = new File(targetDir, name);
                    try (FileOutputStream out = new FileOutputStream(target)) {
                        out.write(data2);
                    }
                    imported++;
                }
            } catch (Exception ignored) {}
        }

        callback.refreshFileTree();
        if (imported > 0) {
            Toast.makeText(activity, activity.getString(R.string.toast_imported_files, imported),
                    Toast.LENGTH_SHORT).show();
        }
    }

    public void pickFileToOpen() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            activity.startActivityForResult(i, REQ_OPEN_FILE);
        } catch (Exception e) {
            Toast.makeText(activity, e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    public void pickMediaFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("audio/*;video/*;image/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"audio/*", "video/*", "image/*"});
        try {
            activity.startActivityForResult(i, REQ_PLAY_MEDIA);
        } catch (Exception e) {
            Toast.makeText(activity, e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    public void openMediaPlayer(Uri uri) {
        try {
            String name = displayName(uri);
            if (name == null) name = "media";
            File cacheDir = new File(activity.getCacheDir(), "media_cache");
            if (!cacheDir.exists()) cacheDir.mkdirs();
            File mediaFile = new File(cacheDir, name);

            try (InputStream in = activity.getContentResolver().openInputStream(uri);
                 OutputStream out = new FileOutputStream(mediaFile)) {
                if (in == null) return;
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            }

            MediaPlayerActivity.launch(activity, mediaFile);
        } catch (Exception e) {
            Toast.makeText(activity, e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    public void importExternalJavaFile(Uri uri) {
        ProjectManager projectManager = callback.getProjectManager();
        if (projectManager == null || projectManager.getProjectDir() == null) return;

        String name = displayName(uri);
        if (name == null) name = "Imported.cpp";
        try (InputStream in = activity.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("Cannot open stream");
            byte[] data = readAll(in);
            String text = new String(data, StandardCharsets.UTF_8);
            String safe = name.replaceAll("[^A-Za-z0-9._-]", "_");
            if (!safe.contains(".")) safe += ".cpp";
            if (safe.endsWith(".java") || safe.endsWith(".kt") || safe.endsWith(".class") || safe.endsWith(".jar"))
                throw new IOException("WireDroid підтримує embedded C/C++ файли");
            File target;
            if (safe.endsWith(".java")) {
                String className = safe.substring(0, safe.length() - 5);
                File made = projectManager.createFile(className, text);
                if (made == null) {
                    File dir = projectManager.hasStandardLayout()
                            ? ProjectLayoutHelper.mainJavaPackageDir(projectManager.getProjectDir())
                            : projectManager.getProjectDir();
                    target = new File(dir, safe);
                    projectManager.writeFile(target, text);
                } else {
                    target = made;
                }
            } else {
                File dir = projectManager.getProjectDir();
                target = new File(dir, safe);
                projectManager.writeFile(target, text);
            }
            callback.refreshFileTree();
            callback.openFile(target);
            Toast.makeText(activity, activity.getString(R.string.toast_imported_to, target.getName()),
                    Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(activity, e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    public void saveCurrentAs() {
        TabsAdapter tabsAdapter = ws.tabs();
        if (tabsAdapter == null) return;
        FileTab tab = tabsAdapter.getActiveTab();
        if (tab == null || tab.file == null) {
            Toast.makeText(activity, R.string.toast_no_file_open, Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("text/x-java");
        i.putExtra(Intent.EXTRA_TITLE, tab.file.getName());
        try {
            activity.startActivityForResult(i, REQ_SAVE_AS);
        } catch (Exception e) {
            Toast.makeText(activity, e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    public void writeCurrentEditorToUri(Uri uri) {
        CodeEditor activeEditor = ws.activeEditor;
        if (activeEditor == null) return;
        try (OutputStream os = activity.getContentResolver().openOutputStream(uri)) {
            if (os == null) throw new IOException("Cannot open output");
            os.write(activeEditor.getText().toString().getBytes(StandardCharsets.UTF_8));
            String name = displayName(uri);
            Toast.makeText(activity, activity.getString(R.string.toast_export_done,
                    name != null ? name : uri.toString()), Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(activity, activity.getString(R.string.toast_export_failed, e.getMessage()),
                    Toast.LENGTH_SHORT).show();
        }
    }

    public void exportProjectAsZip() {
        ProjectManager projectManager = callback.getProjectManager();
        if (projectManager == null || projectManager.getProjectDir() == null) return;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/zip");
        i.putExtra(Intent.EXTRA_TITLE, projectManager.getProjectDir().getName() + ".zip");
        try {
            activity.startActivityForResult(i, REQ_EXPORT_PROJ);
        } catch (Exception e) {
            Toast.makeText(activity, e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    public void exportProjectToUri(Uri uri) {
        ProjectManager projectManager = callback.getProjectManager();
        if (projectManager == null || projectManager.getProjectDir() == null) return;
        new Thread(() -> {
            try (OutputStream os = activity.getContentResolver().openOutputStream(uri);
                 ZipOutputStream zos = new ZipOutputStream(os)) {
                if (os == null) throw new IOException("Cannot open output");
                File root = projectManager.getProjectDir();
                zipDir(root, root, zos);
                activity.runOnUiThread(() -> Toast.makeText(activity,
                        activity.getString(R.string.toast_export_done, root.getName() + ".zip"),
                        Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                activity.runOnUiThread(() -> Toast.makeText(activity,
                        activity.getString(R.string.toast_export_failed, e.getMessage()),
                        Toast.LENGTH_SHORT).show());
            }
        }, "export-zip").start();
    }

    private static void zipDir(File rootDir, File current, ZipOutputStream zos) throws IOException {
        File[] children = current.listFiles();
        if (children == null) return;
        for (File f : children) {
            String n = f.getName();
            if (f.isDirectory() && (n.equals("target") || n.equals(".gradle")
                    || n.equals(".idea") || n.equals("build"))) continue;
            if (f.isDirectory()) {
                zipDir(rootDir, f, zos);
            } else {
                String rel = rootDir.toPath().relativize(f.toPath()).toString().replace('\\', '/');
                zos.putNextEntry(new ZipEntry(rel));
                try (FileInputStream in = new FileInputStream(f)) {
                    byte[] buf = new byte[8192];
                    int n2;
                    while ((n2 = in.read(buf)) != -1) zos.write(buf, 0, n2);
                }
                zos.closeEntry();
            }
        }
    }

    public String displayName(Uri uri) {
        try (Cursor c = activity.getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return c.getString(idx);
            }
        } catch (Exception ignored) {}
        return null;
    }

    public static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BufferedInputStream bis = new BufferedInputStream(in);
        byte[] buf = new byte[8192];
        int n;
        while ((n = bis.read(buf)) != -1) out.write(buf, 0, n);
        return out.toByteArray();
    }
}
