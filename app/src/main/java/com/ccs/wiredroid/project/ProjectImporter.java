package com.ccs.wiredroid.project;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;

import androidx.documentfile.provider.DocumentFile;

import com.ccs.wiredroid.R;
import com.ccs.wiredroid.archive.ArchiveExtractor;
import com.ccs.wiredroid.archive.ArchiveFormat;
import com.ccs.wiredroid.maven.MavenPaths;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Brings an outside project into the app's workspace, from an archive or a
 * document-tree folder.
 *
 * <p>Lifted out of {@code WelcomeActivity} so both entry points share one set
 * of rules: names are derived the same way, a half-finished import cleans up
 * after itself rather than leaving a broken project in the list, and the common
 * "archive contains a single top-level folder" shape is unwrapped so the user
 * opens the project rather than its wrapper.</p>
 *
 * <p>All work happens on a background thread; callbacks arrive on the main
 * looper.</p>
 */
public final class ProjectImporter {

    /** Import outcome, always delivered on the main thread. */
    public interface Callback {
        /** Human-readable stage, suitable for a progress dialog. */
        void onProgress(String message);

        /**
         * @param projectRoot folder to open
         * @param summary     what was unpacked, or {@code null} for a folder copy
         */
        void onSuccess(File projectRoot, ArchiveExtractor.Result summary);

        void onFailure(String message);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private ProjectImporter() {}

    /** Unpacks a picked archive into a new project folder. */
    public static void importArchive(Context context, Uri uri, Callback callback) {
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            File staged = null;
            File dest = null;
            try {
                String displayName = queryDisplayName(app, uri);
                post(callback, app.getString(R.string.import_stage_reading));

                // Copy to cache first: content URIs are not seekable, and both
                // format sniffing and the RAR reader need to look around.
                staged = stageToCache(app, uri, displayName);

                ArchiveFormat format = ArchiveFormat.detect(staged);
                if (!format.supported) {
                    post(callback, null, unsupportedMessage(app, format, displayName));
                    return;
                }

                String folderName = uniqueName(app, ArchiveFormat.stripExtension(displayName));
                dest = new File(MavenPaths.getWireDroidBase(app), folderName);

                post(callback, app.getString(R.string.import_stage_extracting, format.name()));
                ArchiveExtractor.Result result = ArchiveExtractor.extract(staged, dest,
                        (entryName, index, bytesTotal) -> {
                            if (index % 25 == 0) {
                                post(callback, app.getString(R.string.import_stage_entry, entryName));
                            }
                        });

                if (result.fileCount == 0 && result.directoryCount == 0) {
                    deleteRecursive(dest);
                    post(callback, null, app.getString(R.string.import_error_empty));
                    return;
                }

                File root = unwrapSingleFolder(dest);
                com.ccs.wiredroid.wire.WireProject.initializeImported(root);
                post(callback, root, result);
                dest = null;   // handed over; do not clean up
            } catch (ArchiveExtractor.UnsupportedFormatException e) {
                if (dest != null) deleteRecursive(dest);
                post(callback, null, unsupportedMessage(app, e.format, e.getMessage()));
            } catch (Exception e) {
                if (dest != null) deleteRecursive(dest);
                post(callback, null, describe(app, e));
            } finally {
                if (staged != null) {
                    //noinspection ResultOfMethodCallIgnored
                    staged.delete();
                }
            }
        }, "project-import").start();
    }

    /** Copies a picked document tree into a new project folder. */
    public static void importFolder(Context context, Uri treeUri, Callback callback) {
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            File dest = null;
            try {
                DocumentFile tree = DocumentFile.fromTreeUri(app, treeUri);
                if (tree == null || !tree.isDirectory()) {
                    post(callback, null, app.getString(R.string.import_error_folder_unreadable));
                    return;
                }
                String name = tree.getName();
                if (name == null || name.trim().isEmpty()) name = "ImportedFolder";

                dest = new File(MavenPaths.getWireDroidBase(app), uniqueName(app, name));
                ArchiveExtractor.mkdirs(dest);

                post(callback, app.getString(R.string.import_stage_copying));
                int[] copied = {0};
                copyTree(app, tree, dest, callback, copied);

                if (copied[0] == 0) {
                    deleteRecursive(dest);
                    post(callback, null, app.getString(R.string.import_error_empty));
                    return;
                }
                com.ccs.wiredroid.wire.WireProject.initializeImported(dest);
                post(callback, dest, null);
                dest = null;
            } catch (Exception e) {
                if (dest != null) deleteRecursive(dest);
                post(callback, null, describe(app, e));
            }
        }, "folder-import").start();
    }

    // ── internals ────────────────────────────────────────────────────────────

    private static File stageToCache(Context context, Uri uri, String displayName)
            throws IOException {
        File cacheDir = new File(context.getCacheDir(), "imports");
        //noinspection ResultOfMethodCallIgnored
        cacheDir.mkdirs();
        File staged = new File(cacheDir, "staged_" + System.currentTimeMillis());

        try (InputStream in = context.getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(staged)) {
            if (in == null) throw new IOException("Cannot open " + displayName);
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        }
        if (staged.length() == 0) throw new IOException("Selected file is empty");
        return staged;
    }

    private static String queryDisplayName(Context context, Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String name = cursor.getString(index);
                    if (name != null && !name.trim().isEmpty()) return name;
                }
            }
        } catch (Exception ignored) {}
        String last = uri.getLastPathSegment();
        return last != null && !last.isEmpty() ? last : "ImportedProject";
    }

    /** Sanitises the name and appends a counter until it is free. */
    private static String uniqueName(Context context, String rawName) {
        String cleaned = rawName == null ? "" : rawName.trim().replaceAll("[/\\\\:*?\"<>|]", "_");
        if (cleaned.isEmpty() || cleaned.startsWith(".")) {
            cleaned = "ImportedProject";
        }
        File base = MavenPaths.getWireDroidBase(context);
        File candidate = new File(base, cleaned);
        int suffix = 2;
        while (candidate.exists()) {
            candidate = new File(base, cleaned + "_" + suffix++);
        }
        return candidate.getName();
    }

    /**
     * Archives usually wrap the project in one folder named after the release.
     * Descending into it means the user lands on the build file, not a stub.
     */
    private static File unwrapSingleFolder(File dest) {
        File[] children = dest.listFiles();
        if (children == null) return dest;

        File onlyDir = null;
        for (File child : children) {
            String name = child.getName();
            // Metadata that archivers add does not count as real content.
            if (name.equals("__MACOSX") || name.equals(".DS_Store") || name.startsWith("._")) {
                continue;
            }
            if (child.isDirectory()) {
                if (onlyDir != null) return dest;   // more than one, keep the wrapper
                onlyDir = child;
            } else {
                return dest;                        // a loose file at the top level
            }
        }
        return onlyDir != null ? onlyDir : dest;
    }

    private static void copyTree(Context context, DocumentFile source, File destDir,
                                 Callback callback, int[] copied) throws IOException {
        for (DocumentFile file : source.listFiles()) {
            String name = file.getName();
            if (name == null || name.isEmpty()) continue;

            File target = new File(destDir, name.replaceAll("[/\\\\]", "_"));
            if (file.isDirectory()) {
                ArchiveExtractor.mkdirs(target);
                copyTree(context, file, target, callback, copied);
                continue;
            }
            try (InputStream in = context.getContentResolver().openInputStream(file.getUri());
                 OutputStream out = new FileOutputStream(target)) {
                if (in == null) continue;
                byte[] buffer = new byte[64 * 1024];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            }
            if (++copied[0] % 25 == 0) {
                post(callback, context.getString(R.string.import_stage_entry, name));
            }
        }
    }

    private static String unsupportedMessage(Context context, ArchiveFormat format, String detail) {
        String name;
        switch (format) {
            case BZIP2:     name = "bzip2 (.tar.bz2)"; break;
            case XZ:        name = "xz (.tar.xz)"; break;
            case SEVEN_ZIP: name = "7-Zip (.7z)"; break;
            default:        name = detail == null ? "?" : detail;
        }
        return context.getString(R.string.import_error_unsupported, name,
                joinSupported());
    }

    private static String joinSupported() {
        StringBuilder sb = new StringBuilder();
        for (String ext : ArchiveExtractor.supportedExtensions()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append('.').append(ext);
        }
        return sb.toString();
    }

    private static String describe(Context context, Exception e) {
        String message = e.getMessage();
        if (message == null || message.trim().isEmpty()) {
            message = e.getClass().getSimpleName();
        }
        return message;
    }

    private static void deleteRecursive(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteRecursive(child);
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    private static void post(Callback callback, String progress) {
        if (callback != null) MAIN.post(() -> callback.onProgress(progress));
    }

    private static void post(Callback callback, File root, Object payload) {
        if (callback == null) return;
        if (root == null) {
            final String message = String.valueOf(payload);
            MAIN.post(() -> callback.onFailure(message));
        } else {
            final ArchiveExtractor.Result summary =
                    payload instanceof ArchiveExtractor.Result ? (ArchiveExtractor.Result) payload : null;
            MAIN.post(() -> callback.onSuccess(root, summary));
        }
    }
}
