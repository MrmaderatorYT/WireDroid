package com.ccs.wiredroid.tools.compilers;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * On-device C compiler powered by TCC (Tiny C Compiler).
 * Compiles C source code to shared libraries (.so) for JNI use.
 *
 * Usage:
 *   NativeCompiler.init(context);  // once, extracts headers
 *   String err = NativeCompiler.compileToSharedLib(src, outPath, NativeCompiler.getIncludePath());
 *   if (err == null) { /* success * / }
 */
public final class NativeCompiler {

    private static final String TAG = "NativeCompiler";
    private static final String INCLUDE_ASSET_DIR = "tcc_include";

    private static String sIncludePath;
    private static boolean sInitialized;
    private static boolean sNativeLoaded;

    static {
        try {
            System.loadLibrary("native_compiler");
            sNativeLoaded = true;
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load native_compiler library", e);
            sNativeLoaded = false;
        }
    }

    private NativeCompiler() {}

    /**
     * Initialize the compiler: extract bundled C headers from assets.
     * Must be called once before any compilation (typically in Application or Activity).
     */
    public static void init(Context context) {
        if (sInitialized) return;
        try {
            File includeDir = new File(context.getFilesDir(), "tcc_include");
            extractAssetDir(context.getAssets(), INCLUDE_ASSET_DIR, includeDir);
            sIncludePath = includeDir.getAbsolutePath();
            sInitialized = true;
            Log.d(TAG, "Initialized. Include path: " + sIncludePath);
        } catch (IOException e) {
            Log.e(TAG, "Failed to extract TCC headers", e);
        }
    }

    /** Returns the path to extracted C headers, or null if not initialized. */
    public static String getIncludePath() {
        return sIncludePath;
    }

    /**
     * Compile C source code string to a shared library (.so).
     *
     * @param sourceCode  C source code as a string
     * @param outputPath  absolute path for the output .so file
     * @param includePath path to directory containing C headers (jni.h etc.)
     * @return null on success, error message string on failure
     */
    public static native String compileToSharedLib(String sourceCode, String outputPath, String includePath);

    /**
     * Compile a C source file to a shared library (.so).
     *
     * @param sourcePath  absolute path to the .c source file
     * @param outputPath  absolute path for the output .so file
     * @param includePath path to directory containing C headers
     * @return null on success, error message string on failure
     */
    public static native String compileFile(String sourcePath, String outputPath, String includePath);

    /** Check whether the native compiler is available. */
    public static native boolean isAvailable();

    /** Returns true if the native library was loaded successfully. */
    public static boolean isLoaded() {
        return sNativeLoaded;
    }

    // ── Asset extraction ──────────────────────────────────────────

    private static void extractAssetDir(AssetManager am, String assetDir, File destDir) throws IOException {
        if (!destDir.exists() && !destDir.mkdirs()) {
            throw new IOException("Cannot create directory: " + destDir);
        }

        String[] files = am.list(assetDir);
        if (files == null || files.length == 0) {
            // It might be a file, not a directory
            return;
        }

        for (String name : files) {
            String assetPath = assetDir + "/" + name;
            File destFile = new File(destDir, name);

            // Try listing — if it has children, it's a subdirectory
            String[] children = am.list(assetPath);
            if (children != null && children.length > 0) {
                extractAssetDir(am, assetPath, destFile);
            } else {
                extractAssetFile(am, assetPath, destFile);
            }
        }
    }

    private static void extractAssetFile(AssetManager am, String assetPath, File destFile) throws IOException {
        try (InputStream in = am.open(assetPath);
             OutputStream out = new FileOutputStream(destFile)) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
        }
    }
}
