package com.ccs.wiredroid.tools.compilers;

import android.content.Context;
import android.util.Log;

import com.ccs.wiredroid.R;
import com.ccs.wiredroid.util.AppPreferences;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Handles C and C++ source compilation for projects using TCC and NDK Clang.
 */
public final class NativeBuildHelper {

    private static final int MAX_COMPILER_OUTPUT_BYTES = 4 * 1024 * 1024;

    private NativeBuildHelper() {}

    public static File findLatestJniLibsDir(Context context) {
        File[] cacheFiles = context.getCacheDir().listFiles();
        if (cacheFiles == null) return null;
        File newest = null;
        long newestTime = 0;
        for (File f : cacheFiles) {
            if (f.isDirectory() && f.getName().startsWith("jni_libs_")) {
                long mod = f.lastModified();
                if (mod >= newestTime) {
                    newestTime = mod;
                    newest = f;
                }
            }
        }
        return newest;
    }

    public static void cleanupOldJniLibs(Context context) {
        File[] cacheFiles = context.getCacheDir().listFiles();
        if (cacheFiles != null) {
            for (File f : cacheFiles) {
                if (f.getName().startsWith("jni_libs_")) {
                    deleteRecursive(f);
                }
            }
        }
    }

    public static File compileNativeSources(Context context, File projectRoot, ProjectCompiler.Callback callback) {
        File jniLibsDir = new File(context.getCacheDir(), "jni_libs_" + System.currentTimeMillis());
        if (!jniLibsDir.exists()) jniLibsDir.mkdirs();

        File srcMain = new File(projectRoot, "src/main");
        File cppDir = new File(srcMain, "cpp");
        File jniDir = new File(srcMain, "jni");
        List<File> nativeCSources = new ArrayList<>();
        List<File> nativeCppSources = new ArrayList<>();
        if (cppDir.exists() && cppDir.isDirectory()) {
            File[] files = cppDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.getName().endsWith(".c")) nativeCSources.add(f);
                    else if (f.getName().endsWith(".cpp") || f.getName().endsWith(".cxx")) nativeCppSources.add(f);
                }
            }
        }
        if (jniDir.exists() && jniDir.isDirectory()) {
            File[] files = jniDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.getName().endsWith(".c")) nativeCSources.add(f);
                    else if (f.getName().endsWith(".cpp") || f.getName().endsWith(".cxx")) nativeCppSources.add(f);
                }
            }
        }

        if (nativeCSources.isEmpty() && nativeCppSources.isEmpty()) {
            return null;
        }

        if (!com.ccs.wiredroid.project.ProjectNative.isEnabled(context, projectRoot)) {
            ProjectCompiler.postProgress(callback, context.getString(R.string.native_disabled_skipped));
            return null;
        }
        boolean useNdk = AppPreferences.NATIVE_NDK.equals(
                com.ccs.wiredroid.project.ProjectNative.backend(context, projectRoot));
        if (useNdk && !NdkManager.isSupportedOnThisDevice()) {
            // The NDK's own binaries are aarch64; on anything else the project
            // still builds, with the C compiler that is compiled for this
            // device. Said out loud, because the project asked for the other one.
            ProjectCompiler.postProgress(callback, context.getString(
                    R.string.ndk_unsupported_arch, NdkManager.deviceAbi()));
            useNdk = false;
        }

        ProjectCompiler.postProgress(callback,
                context.getString(R.string.native_compiling_sources));

        if (!useNdk && !nativeCppSources.isEmpty()) {
            for (File cpp : nativeCppSources) {
                ProjectCompiler.postProgress(callback, context.getString(
                        R.string.native_tcc_cannot_cpp, cpp.getName()));
            }
        }
        if (useNdk) {
            nativeCppSources.addAll(nativeCSources);
            nativeCSources.clear();
        }

        // Compile C sources with TCC
        if (!nativeCSources.isEmpty()) {
            NativeCompiler.init(context);
            if (!NativeCompiler.isLoaded() || !NativeCompiler.isAvailable()) {
                ProjectCompiler.postProgress(callback, context.getString(R.string.ndk_warning_c_not_available));
                return null;
            }
            String includePath = NativeCompiler.getIncludePath();
            for (File nativeSrc : nativeCSources) {
                String simpleName = nativeSrc.getName();
                String baseName = simpleName.substring(0, simpleName.lastIndexOf('.'));
                String libName = "lib" + baseName + ".so";
                File destSo = new File(jniLibsDir, libName);
                String sourceCode;
                try {
                    sourceCode = new String(Files.readAllBytes(nativeSrc.toPath()), StandardCharsets.UTF_8);
                } catch (IOException ex) {
                    ProjectCompiler.postProgress(callback, context.getString(R.string.ndk_warning_cannot_read, simpleName, ex.getMessage()));
                    continue;
                }
                String error = NativeCompiler.compileToSharedLib(sourceCode, destSo.getAbsolutePath(), includePath);
                if (error != null) {
                    ProjectCompiler.postProgress(callback, context.getString(R.string.ndk_warning_c_error, simpleName, error));
                    continue;
                }
                destSo.setExecutable(true, false);
                ProjectCompiler.postProgress(callback, context.getString(R.string.ndk_compiled_tcc, simpleName, libName));
            }
        }

        // Compile C++ sources with NDK
        if (!nativeCppSources.isEmpty()) {
            if (!NdkManager.isNdkInstalled(context)) {
                ProjectCompiler.postProgress(callback,
                        context.getString(R.string.ndk_warning_cpp_requires_bundled_ndk));
                return null;
            }
            try {
                NdkManager.prepareCompilerLaunchers(context);
            } catch (IOException error) {
                ProjectCompiler.postProgress(callback, context.getString(
                        R.string.ndk_warning_cpp_failed,
                        nativeCppSources.get(0).getName(), error.getMessage()));
                return null;
            }
            File clang = NdkManager.getClangPath(context);
            File lld = NdkManager.getLldPath(context);
            File llvm = NdkManager.getLlvmPrebuiltDir(context);
            for (File nativeSrc : nativeCppSources) {
                String simpleName = nativeSrc.getName();
                String baseName = simpleName.substring(0, simpleName.lastIndexOf('.'));
                String libName = "lib" + baseName + ".so";
                File destSo = new File(jniLibsDir, libName);
                List<String> cmd = new ArrayList<>();
                cmd.add(clang.getAbsolutePath());
                cmd.add("--target=" + NdkManager.getClangTarget());
                cmd.add("--sysroot=" + new File(llvm, "sysroot").getAbsolutePath());
                cmd.add("-resource-dir");
                cmd.add(new File(llvm, "lib/clang/21").getAbsolutePath());
                cmd.add("--ld-path=" + lld.getAbsolutePath());
                cmd.add("-shared");
                cmd.add("-fPIC");
                cmd.add("-static-libstdc++");
                cmd.add("-o");
                cmd.add(destSo.getAbsolutePath());
                cmd.add(nativeSrc.getAbsolutePath());
                try {
                    ProcessBuilder pb = new ProcessBuilder(cmd);
                    pb.directory(projectRoot);
                    pb.environment().put("TMPDIR", context.getCacheDir().getAbsolutePath());
                    pb.environment().put("HOME", context.getFilesDir().getAbsolutePath());
                    pb.redirectErrorStream(true);
                    Process p = pb.start();
                    String output = readCompilerOutput(p.getInputStream());
                    p.waitFor();
                    if (p.exitValue() != 0) {
                        destSo.delete();
                        ProjectCompiler.postProgress(callback, context.getString(R.string.ndk_warning_cpp_error, simpleName, output));
                        continue;
                    }
                    destSo.setExecutable(true, false);
                    ProjectCompiler.postProgress(callback, context.getString(R.string.ndk_compiled_clang, simpleName, libName));
                } catch (Exception e) {
                    destSo.delete();
                    ProjectCompiler.postProgress(callback, context.getString(R.string.ndk_warning_cpp_failed, simpleName, e.getMessage()));
                }
            }
        }

        return jniLibsDir;
    }

    private static String readCompilerOutput(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        boolean truncated = false;
        int count;
        while ((count = input.read(buffer)) != -1) {
            int remaining = MAX_COMPILER_OUTPUT_BYTES - output.size();
            if (remaining > 0) {
                output.write(buffer, 0, Math.min(count, remaining));
            }
            if (count > remaining) truncated = true;
        }
        String text = new String(output.toByteArray(), StandardCharsets.UTF_8);
        return truncated ? text + "\n[compiler output truncated]" : text;
    }

    private static void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] c = f.listFiles();
            if (c != null) for (File child : c) deleteRecursive(child);
        }
        f.delete();
    }
}
