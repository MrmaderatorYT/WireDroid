package com.ccs.wiredroid.ui;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.widget.Toast;

import com.ccs.wiredroid.R;
import com.ccs.wiredroid.maven.MavenPaths;
import com.ccs.wiredroid.util.AppPreferences;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

/**
 * Entry point for files handed over by other apps — a file manager's "open
 * with", a mail attachment, a share sheet.
 *
 * <p>Kept separate from {@link MainActivity} because the two have different
 * jobs: MainActivity always works inside a project, while an incoming file may
 * be anywhere or nowhere at all. This activity's only task is to turn whatever
 * arrived into a real path plus a sensible project root, then hand over and
 * finish, so it never appears in the back stack.</p>
 *
 * <p>Resolution runs in three steps:</p>
 * <ol>
 *   <li>a {@code file://} URI, or a {@code content://} one that exposes a
 *       readable path, is used where it lies;</li>
 *   <li>otherwise the bytes are copied into a scratch workspace, because a
 *       content URI grants no lasting access and an editor that cannot save is
 *       worse than one that says where it put the copy;</li>
 *   <li>the enclosing project is found by walking up for a build file, so
 *       opening one source from a checkout brings the whole project with it.</li>
 * </ol>
 */
public class FileOpenActivity extends Activity {

    /** Absolute path of the file MainActivity should open on start. */
    public static final String EXTRA_OPEN_FILE = "com.ccs.wiredroid.OPEN_FILE";

    /** Where copies of non-file URIs land, inside the normal workspace. */
    private static final String SCRATCH_FOLDER = "Opened files";

    /** Markers that identify a project root when walking up from a file. */
    private static final String[] PROJECT_MARKERS = {
            "pom.xml", "build.gradle", "build.gradle.kts",
            "settings.gradle", "settings.gradle.kts", ".git"
    };

    /** Give up walking up before reaching the storage root. */
    private static final int MAX_WALK_UP = 12;

    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Uri uri = incomingUri(getIntent());
        if (uri == null) {
            fail(getString(R.string.open_with_no_file));
            return;
        }
        // Copying can block, and this activity has no UI of its own to show.
        new Thread(() -> {
            try {
                File file = resolveToFile(uri);
                File projectRoot = findProjectRoot(file);
                main.post(() -> handOff(file, projectRoot));
            } catch (Exception e) {
                String message = e.getMessage();
                main.post(() -> fail(message == null ? e.getClass().getSimpleName() : message));
            }
        }, "open-with").start();
    }

    /** Pulls the target out of VIEW/EDIT/SEND intents alike. */
    private static Uri incomingUri(Intent intent) {
        if (intent == null) return null;
        Uri data = intent.getData();
        if (data != null) return data;
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            return intent.getParcelableExtra(Intent.EXTRA_STREAM);
        }
        return null;
    }

    private File resolveToFile(Uri uri) throws IOException {
        if (ContentResolver.SCHEME_FILE.equals(uri.getScheme())) {
            String path = uri.getPath();
            if (path == null) throw new IOException("Empty file path");
            File file = new File(path);
            if (!file.isFile()) throw new IOException("No such file: " + path);
            if (file.canRead()) return file;
            // A path we can see but not read still copies fine via the resolver.
        }
        return copyIntoScratch(uri);
    }

    /**
     * Copies the URI's bytes into the workspace under its own name.
     *
     * <p>The name is kept because the extension decides how the editor treats
     * the file, and a collision is resolved with a counter rather than by
     * overwriting something the user opened earlier.</p>
     */
    private File copyIntoScratch(Uri uri) throws IOException {
        String name = sanitise(displayName(uri));
        File scratch = new File(MavenPaths.getWireDroidBase(this), SCRATCH_FOLDER);
        if (!scratch.isDirectory() && !scratch.mkdirs()) {
            throw new IOException("Cannot create " + scratch.getAbsolutePath());
        }

        File target = new File(scratch, name);
        int counter = 2;
        while (target.exists()) {
            int dot = name.lastIndexOf('.');
            String stem = dot > 0 ? name.substring(0, dot) : name;
            String ext = dot > 0 ? name.substring(dot) : "";
            target = new File(scratch, stem + "_" + counter++ + ext);
        }

        try (InputStream in = getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(target)) {
            if (in == null) throw new IOException("Cannot read the selected file");
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        }
        return target;
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String name = cursor.getString(index);
                    if (name != null && !name.trim().isEmpty()) return name;
                }
            }
        } catch (Exception ignored) {}

        String last = uri.getLastPathSegment();
        if (last != null) {
            int slash = last.lastIndexOf('/');
            if (slash >= 0) last = last.substring(slash + 1);
            if (!last.isEmpty() && last.indexOf('.') > 0) return last;
        }
        return "opened.txt";
    }

    private static String sanitise(String name) {
        String cleaned = name.replaceAll("[/\\\\:*?\"<>|]", "_").trim();
        if (cleaned.isEmpty() || cleaned.equals(".") || cleaned.equals("..")) return "opened.txt";
        return cleaned.length() > 120 ? cleaned.substring(cleaned.length() - 120) : cleaned;
    }

    /**
     * Walks up from {@code file} looking for a build file, then for a
     * {@code src} directory. Falls back to the containing folder, which at
     * least gives the file tree something to show.
     */
    private static File findProjectRoot(File file) {
        File dir = file.getParentFile();
        if (dir == null) return file.getAbsoluteFile().getParentFile();

        File srcAncestor = null;
        File cursor = dir;
        for (int depth = 0; depth < MAX_WALK_UP && cursor != null; depth++) {
            for (String marker : PROJECT_MARKERS) {
                if (new File(cursor, marker).exists()) return cursor;
            }
            // src/main/java/... is the other reliable shape, but a build file
            // higher up wins, so remember it and keep climbing.
            if (srcAncestor == null && new File(cursor, "src").isDirectory()) {
                srcAncestor = cursor;
            }
            cursor = cursor.getParentFile();
        }
        return srcAncestor != null ? srcAncestor : dir;
    }

    private void handOff(File file, File projectRoot) {
        // MainActivity reads its project from preferences on start.
        getSharedPreferences(AppPreferences.PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putString("project_root", projectRoot.getAbsolutePath())
                .apply();
        new AppPreferences(this).addRecentProject(projectRoot.getAbsolutePath());

        Intent intent = new Intent(this, MainActivity.class);
        intent.putExtra(EXTRA_OPEN_FILE, file.getAbsolutePath());
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
        finish();
    }

    private void fail(String message) {
        Toast.makeText(this, getString(R.string.open_with_failed, message), Toast.LENGTH_LONG).show();
        finish();
    }

    /** True for names this app claims in the manifest, used by the file tree too. */
    public static boolean isClaimedSource(String fileName) {
        if (fileName == null) return false;
        String lower = fileName.toLowerCase(Locale.ROOT);
        String[] claimed = {".java", ".kt", ".kts", ".jar", ".class", ".gradle", ".pom"};
        for (String ext : claimed) {
            if (lower.endsWith(ext)) return true;
        }
        return false;
    }
}
