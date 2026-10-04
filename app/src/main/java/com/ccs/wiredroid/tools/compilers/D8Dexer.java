package com.ccs.wiredroid.tools.compilers;

import android.content.Context;
import android.os.Build;

import com.android.tools.r8.D8;
import com.android.tools.r8.D8Command;
import com.android.tools.r8.OutputMode;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;

/**
 * Handles Google D8 dexing and Android framework jar caching.
 */
public final class D8Dexer {

    private D8Dexer() {}

    /**
     * The unpacked {@code android.jar}, the compiler's bootclasspath.
     *
     * <p>Re-unpacked whenever the app has been updated, not only when the cached
     * copy is missing. The jar is patched at build time — stubs for
     * {@code LambdaMetafactory} and {@code StringConcatFactory} live in it — so a
     * copy left over from an older install silently keeps compiling against the
     * old one, and a fix shipped in an update never arrives.</p>
     */
    public static File ensureAndroidJar(Context context, File cacheDir) throws Exception {
        if (cacheDir != null && !cacheDir.exists() && !cacheDir.mkdirs()) {
            throw new IOException("Cannot create directory: " + cacheDir.getAbsolutePath());
        }
        File androidJar = new File(cacheDir, "android.jar");
        File stamp = new File(cacheDir, "android.jar.build");
        String build = appBuildId(context);
        boolean stale = !androidJar.exists()
                || androidJar.length() == 0L
                || !build.equals(readText(stamp));
        if (stale) {
            if (androidJar.exists() && !androidJar.delete()) {
                throw new IOException("Cannot replace invalid android.jar");
            }
            try (InputStream is = context.getAssets().open("android.jar");
                 FileOutputStream fos = new FileOutputStream(androidJar)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) != -1) {
                    fos.write(buf, 0, n);
                }
            }
            writeText(stamp, build);
        }
        if (!androidJar.isFile() || androidJar.length() == 0L) {
            throw new IOException("android.jar missing or empty: " + androidJar.getAbsolutePath());
        }
        return androidJar;
    }

    /**
     * Identifies this build of the app.
     *
     * <p>The install time is part of it, so a debug build reinstalled without a
     * version bump — the normal case while developing — still invalidates.</p>
     */
    private static String appBuildId(Context context) {
        try {
            android.content.pm.PackageInfo info = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            return info.versionName + ":" + info.lastUpdateTime;
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static String readText(File file) {
        if (file == null || !file.isFile()) return null;
        try (InputStream in = new java.io.FileInputStream(file)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[256];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return null;
        }
    }

    private static void writeText(File file, String text) {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // A missing stamp only costs one extra unpack next time.
        }
    }

    public static void runD8Dex(File androidJar, File dexDir, File classFile) throws Exception {
        runD8Dex(androidJar, dexDir, Collections.singletonList(classFile));
    }

    public static void runD8Dex(File androidJar, File dexDir, List<File> classFiles) throws Exception {
        File[] old = dexDir.listFiles();
        if (old != null) {
            for (File f : old) f.delete();
        }
        D8Command.Builder builder = D8Command.builder()
                .addLibraryFiles(androidJar.toPath())
                .setOutput(dexDir.toPath(), OutputMode.DexIndexed)
                .setMinApiLevel(Build.VERSION.SDK_INT);
        for (File f : classFiles) {
            builder.addProgramFiles(f.toPath());
        }
        D8.run(builder.build());
    }

    public static void cleanupOldDexDirs(Context context) {
        try {
            File cacheDir = context.getCacheDir();
            File[] dirs = cacheDir.listFiles((f) -> f.isDirectory() && f.getName().startsWith("maven_dex_"));
            if (dirs == null || dirs.length <= 2) return;
            java.util.Arrays.sort(dirs, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            for (int i = 2; i < dirs.length; i++) {
                deleteRecursive(dirs[i]);
            }
        } catch (Exception ignored) {}
    }

    private static void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] c = f.listFiles();
            if (c != null) for (File child : c) deleteRecursive(child);
        }
        f.delete();
    }
}
