package com.ccs.wiredroid.project;

import android.content.Context;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Jars a project carries with it, in {@code libs/}.
 *
 * <p>Not every dependency comes from a repository. A jar handed over by a
 * lecturer, a driver a vendor emails, a build from another machine — none of
 * these have coordinates to declare, and until now there was no way to put one
 * on the classpath at all. The folder convention is the one Android Studio and
 * Gradle already use, so a project that leaves this app stays buildable.</p>
 */
public final class LocalLibraries {

    public static final String DIR_NAME = "libs";

    /** What an import produced, for a report the user can act on. */
    public static final class Imported {
        public final List<String> added = new ArrayList<>();
        public final List<String> skipped = new ArrayList<>();
        public String error;

        public boolean isEmpty() {
            return added.isEmpty();
        }
    }

    private LocalLibraries() {
    }

    public static File dir(File projectRoot) {
        return new File(projectRoot, DIR_NAME);
    }

    /** Every jar the project carries, sorted so the classpath is reproducible. */
    public static List<File> list(File projectRoot) {
        List<File> out = new ArrayList<>();
        if (projectRoot == null) return out;
        addJars(dir(projectRoot), out);
        // An Ant project has no coordinates to resolve: the folders its script
        // scans for jars are its entire classpath, so they count as carried
        // libraries too.
        for (File libDir : com.ccs.wiredroid.ant.AntPaths.libraryDirs(projectRoot)) {
            addJars(libDir, out);
        }
        return out;
    }

    private static void addJars(File dir, List<File> out) {
        if (dir == null) return;
        File[] files = dir.listFiles(
                (d, name) -> name.toLowerCase(Locale.ROOT).endsWith(".jar"));
        if (files == null) return;
        Arrays.sort(files);
        for (File jar : files) {
            if (!out.contains(jar)) out.add(jar);
        }
    }

    /**
     * Copies a chosen archive into the project.
     *
     * <p>A {@code .jar} is copied as it is. A {@code .war} and an {@code .aar}
     * are containers rather than libraries: the classes a compiler needs sit in
     * jars inside them, so those are lifted out. Anything else is refused by
     * name rather than copied and left to fail later at compile time.</p>
     */
    public static Imported importArchive(Context context, Uri uri, File projectRoot) {
        Imported result = new Imported();
        if (projectRoot == null) {
            result.error = "no project";
            return result;
        }
        String name = displayName(context, uri);
        String lower = name.toLowerCase(Locale.ROOT);

        File libs = dir(projectRoot);
        if (!libs.isDirectory() && !libs.mkdirs()) {
            result.error = "cannot create " + libs.getAbsolutePath();
            return result;
        }

        try {
            if (lower.endsWith(".jar")) {
                File dest = uniqueDest(libs, name);
                copy(context, uri, dest);
                result.added.add(dest.getName());
                return result;
            }
            if (lower.endsWith(".war") || lower.endsWith(".aar")) {
                // A temporary copy, because a content Uri is not a file and the
                // zip reader needs random access.
                File staged = new File(context.getCacheDir(), "import-" + System.nanoTime());
                copy(context, uri, staged);
                try {
                    extractJars(staged, libs, result);
                } finally {
                    staged.delete();
                }
                if (result.isEmpty() && result.error == null) {
                    result.error = "no jars inside " + name;
                }
                return result;
            }
            result.error = "unsupported: " + name;
            return result;
        } catch (Exception e) {
            result.error = String.valueOf(e.getMessage());
            return result;
        }
    }

    /**
     * Pulls the libraries out of a war or an aar.
     *
     * <p>A war keeps them in {@code WEB-INF/lib/}; an aar keeps its own code in
     * {@code classes.jar} at the root. Directories inside are ignored — a
     * compiler wants jars, and {@code WEB-INF/classes} is a tree, not one.</p>
     */
    private static void extractJars(File archive, File libs, Imported result) throws IOException {
        try (ZipFile zip = new ZipFile(archive)) {
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                if (e.isDirectory()) continue;
                String entryName = e.getName();
                String lower = entryName.toLowerCase(Locale.ROOT);
                if (!lower.endsWith(".jar")) continue;

                boolean fromWar = lower.startsWith("web-inf/lib/");
                boolean isAarClasses = lower.equals("classes.jar");
                if (!fromWar && !isAarClasses) {
                    result.skipped.add(entryName);
                    continue;
                }

                String simple = entryName.substring(entryName.lastIndexOf('/') + 1);
                if (isAarClasses) {
                    // Every aar names it classes.jar, so the archive's own name
                    // is the only thing that tells two of them apart.
                    String base = archive.getName();
                    simple = base.replaceFirst("(?i)\\.aar$", "") + "-classes.jar";
                }
                File dest = uniqueDest(libs, simple);
                try (InputStream in = zip.getInputStream(e)) {
                    writeTo(in, dest);
                }
                result.added.add(dest.getName());
            }
        }
    }

    /** Never silently replaces an existing jar; a build depends on both. */
    private static File uniqueDest(File libs, String name) {
        File dest = new File(libs, name);
        if (!dest.exists()) return dest;
        String base = name.replaceFirst("(?i)\\.jar$", "");
        for (int i = 2; i < 100; i++) {
            File candidate = new File(libs, base + "-" + i + ".jar");
            if (!candidate.exists()) return candidate;
        }
        return dest;
    }

    private static void copy(Context context, Uri uri, File dest) throws IOException {
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("cannot open " + uri);
            writeTo(in, dest);
        }
    }

    private static void writeTo(InputStream in, File dest) throws IOException {
        try (OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    private static String displayName(Context context, Uri uri) {
        try (android.database.Cursor c = context.getContentResolver()
                .query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) {
                    String n = c.getString(i);
                    if (n != null && !n.isEmpty()) return n;
                }
            }
        } catch (Exception ignored) {
            // A provider that answers nothing still has a path to fall back on.
        }
        String path = uri.getLastPathSegment();
        return path == null ? "library.jar" : path.substring(path.lastIndexOf('/') + 1);
    }
}
