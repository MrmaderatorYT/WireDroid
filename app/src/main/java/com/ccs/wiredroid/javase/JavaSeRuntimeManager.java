package com.ccs.wiredroid.javase;

import android.content.Context;
import android.os.Build;
import android.os.StatFs;
import android.system.Os;

import com.ccs.wiredroid.R;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Locale;

/** Installs the pinned OpenJDK Mobile 21 runtime bundled with the app. */
public final class JavaSeRuntimeManager {

    static final String RUNTIME_BUILD = "655141c7aed79be8db0a7227faa90e88b3b7e021";

    private static final String PAYLOAD_DIR = "toolchains/java-se/";
    private static final Payload UNIVERSAL = new Payload(
            "universal.tar.xz", 23_515_864L,
            "fddfc0b8a8e8b56be8efbf3f413ac787a0d7b96d711cb5cb59573076d299a0c7");
    private static final Payload ARM = new Payload(
            "bin-arm.tar.xz", 4_318_124L,
            "c18520eb81d78532f6af2158d3a574d0bb5923f9a46c3d51b2f1ff1c96bd1e5c");
    private static final Payload ARM64 = new Payload(
            "bin-arm64.tar.xz", 5_397_948L,
            "fe948068b5fec393b2b081d2c550f15777ba2829f36ae9931e2b5fdda2ec087f");
    private static final Payload X86 = new Payload(
            "bin-x86.tar.xz", 5_625_296L,
            "ddb77f5fb27ab62b8a6d316a74aa2752df7242992f86878d0a86c2d90ca4f288");
    private static final Payload X86_64 = new Payload(
            "bin-x86_64.tar.xz", 6_434_740L,
            "77de3c0e8a4ab578c2ba177a52be83a3a2b1aa227cb2d88d90c32b94dd0c8865");

    private static final String RUNTIME_DIRECTORY = "openjdk-21-mobile";
    private static final String INSTALL_MARKER = ".wiredroid-runtime";
    private static final String BOOTSTRAP_ASSET = "java-se-bootstrap.jar";
    private static final String BOOTSTRAP_FILE = "java-se-bootstrap.jar";
    private static final long MIN_FREE_BYTES = 360L * 1024L * 1024L;
    private static final long MAX_EXTRACTED_BYTES = 260L * 1024L * 1024L;
    private static final Object INSTALL_LOCK = new Object();

    public interface Progress {
        void onProgress(Stage stage, int percent);
    }

    public enum Stage {
        VERIFYING,
        INSTALLING,
        READY
    }

    private JavaSeRuntimeManager() {}

    public static File ensureInstalled(Context context, Progress progress) throws IOException {
        Context app = context.getApplicationContext();
        synchronized (INSTALL_LOCK) {
            File runtime = runtimeHome(app);
            if (isValid(runtime)) {
                notify(progress, Stage.READY, 100);
                return runtime;
            }

            Payload binpack = payloadForDevice();
            File base = runtimeBase(app);
            if (!base.isDirectory() && !base.mkdirs()) {
                throw new IOException("Cannot create " + base);
            }
            long free = new StatFs(base.getAbsolutePath()).getAvailableBytes();
            if (free < MIN_FREE_BYTES) {
                throw new IOException(app.getString(R.string.javase_runtime_no_space,
                        MIN_FREE_BYTES / 1024L / 1024L));
            }

            File staging = new File(base, RUNTIME_DIRECTORY + ".installing");
            deleteRecursive(staging);
            if (!staging.mkdirs()) throw new IOException("Cannot create " + staging);

            try {
                notify(progress, Stage.VERIFYING, 0);
                verifyPayload(app, UNIVERSAL);
                verifyPayload(app, binpack);
                notify(progress, Stage.VERIFYING, 100);

                notify(progress, Stage.INSTALLING, 0);
                installPayload(app, staging, binpack, progress);
                writeMarker(staging);
                if (!isValid(staging)) {
                    throw new IOException(app.getString(R.string.javase_runtime_invalid));
                }

                deleteRecursive(runtime);
                if (!staging.renameTo(runtime)) {
                    throw new IOException("Cannot activate " + runtime);
                }
                notify(progress, Stage.READY, 100);
                return runtime;
            } finally {
                if (!isValid(runtime)) deleteRecursive(staging);
            }
        }
    }

    public static File ensureBootstrapJar(Context context) throws IOException {
        File base = runtimeBase(context.getApplicationContext());
        if (!base.isDirectory() && !base.mkdirs()) {
            throw new IOException("Cannot create " + base);
        }
        File jar = new File(base, BOOTSTRAP_FILE);
        long assetLength = -1;
        try (android.content.res.AssetFileDescriptor afd = context.getAssets().openFd(BOOTSTRAP_ASSET)) {
            assetLength = afd.getLength();
        } catch (Throwable ignored) {}

        if (jar.isFile() && jar.length() > 1024L * 1024L && (assetLength <= 0 || jar.length() == assetLength)) {
            return jar;
        }
        File part = new File(base, BOOTSTRAP_FILE + ".part");
        try (InputStream in = context.getAssets().open(BOOTSTRAP_ASSET);
             BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(part))) {
            copy(in, out, 32L * 1024L * 1024L);
        }
        deleteRecursive(jar);
        if (!part.renameTo(jar)) {
            deleteRecursive(part);
            throw new IOException("Cannot install Java SE compiler bridge");
        }
        return jar;
    }

    public static File runtimeHome(Context context) {
        return new File(runtimeBase(context.getApplicationContext()), RUNTIME_DIRECTORY);
    }

    public static boolean isInstalled(Context context) {
        return isValid(runtimeHome(context));
    }

    private static File runtimeBase(Context context) {
        return new File(context.getNoBackupFilesDir(), "java-se");
    }

    private static boolean isValid(File runtime) {
        if (runtime == null || !runtime.isDirectory()) return false;
        File marker = new File(runtime, INSTALL_MARKER);
        if (!marker.isFile()) return false;
        try (FileInputStream in = new FileInputStream(marker)) {
            byte[] bytes = new byte[(int) Math.min(marker.length(), 256)];
            int count = in.read(bytes);
            if (count <= 0 || !RUNTIME_BUILD.equals(
                    new String(bytes, 0, count, StandardCharsets.UTF_8).trim())) return false;
        } catch (IOException e) {
            return false;
        }
        return new File(runtime, "release").isFile()
                && new File(runtime, "lib/modules").isFile()
                && new File(runtime, "lib/libjli.so").isFile()
                && (new File(runtime, "lib/server/libjvm.so").isFile()
                || new File(runtime, "lib/client/libjvm.so").isFile());
    }

    private static Payload payloadForDevice() throws IOException {
        for (String abi : Build.SUPPORTED_ABIS) {
            switch (abi) {
                case "arm64-v8a": return ARM64;
                case "armeabi-v7a": return ARM;
                case "x86_64": return X86_64;
                case "x86": return X86;
                default: break;
            }
        }
        String abi = Build.SUPPORTED_ABIS.length == 0 ? "unknown" : Build.SUPPORTED_ABIS[0];
        throw new IOException("Unsupported Java SE architecture: " + abi);
    }

    private static void installPayload(Context context, File destination, Payload binpack,
                                       Progress progress) throws IOException {
        extractTarAsset(context, UNIVERSAL, destination, 0, 75, progress);
        extractTarAsset(context, binpack, destination, 75, 100, progress);
    }

    private static void extractTarAsset(Context context, Payload payload, File destination,
                                        int startPercent, int endPercent, Progress progress)
            throws IOException {
        long archiveSize = Math.max(1L, payload.size);
        long extracted = 0;
        byte[] buffer = new byte[32 * 1024];
        Path root = destination.toPath().toAbsolutePath().normalize();

        try (CountingInputStream raw = new CountingInputStream(new BufferedInputStream(
                     context.getAssets().open(PAYLOAD_DIR + payload.fileName)));
             XZCompressorInputStream xz = new XZCompressorInputStream(raw, true);
             TarArchiveInputStream tar = new TarArchiveInputStream(xz)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextTarEntry()) != null) {
                Path outputPath = root.resolve(entry.getName()).normalize();
                if (!outputPath.startsWith(root)) {
                    throw new IOException("Unsafe runtime path: " + entry.getName());
                }
                File output = outputPath.toFile();
                if (entry.isDirectory()) {
                    if (!output.isDirectory() && !output.mkdirs()) {
                        throw new IOException("Cannot create " + output);
                    }
                } else if (entry.isSymbolicLink()) {
                    createSafeSymlink(root, outputPath, entry.getLinkName());
                } else if (entry.isLink()) {
                    throw new IOException("Hard links are not supported in the runtime payload");
                } else if (entry.isFile()) {
                    File parent = output.getParentFile();
                    if (!parent.isDirectory() && !parent.mkdirs()) {
                        throw new IOException("Cannot create " + parent);
                    }
                    try (BufferedOutputStream out = new BufferedOutputStream(
                            new FileOutputStream(output))) {
                        long remaining = entry.getSize();
                        while (remaining > 0) {
                            int count = tar.read(buffer, 0,
                                    (int) Math.min(buffer.length, remaining));
                            if (count < 0) throw new IOException("Truncated " + entry.getName());
                            out.write(buffer, 0, count);
                            remaining -= count;
                            extracted += count;
                            if (extracted > MAX_EXTRACTED_BYTES) {
                                throw new IOException("Runtime payload exceeded the size limit");
                            }
                        }
                    }
                    try {
                        Os.chmod(output.getAbsolutePath(), entry.getMode() & 0777);
                    } catch (Throwable ignored) {
                        output.setExecutable((entry.getMode() & 0111) != 0, false);
                    }
                }
                int span = endPercent - startPercent;
                int percent = startPercent + (int) Math.min(span,
                        raw.getCount() * span / archiveSize);
                notify(progress, Stage.INSTALLING, percent);
            }
        }
    }

    private static void createSafeSymlink(Path root, Path link, String target) throws IOException {
        Path targetPath = Paths.get(target);
        if (targetPath.isAbsolute()
                || !link.getParent().resolve(targetPath).normalize().startsWith(root)) {
            throw new IOException("Unsafe runtime symlink: " + link + " -> " + target);
        }
        File parent = link.toFile().getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Cannot create " + parent);
        }
        if (java.nio.file.Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            if (!java.nio.file.Files.isSymbolicLink(link)) {
                throw new IOException("Cannot replace non-symlink runtime path " + link);
            }
            java.nio.file.Files.delete(link);
        }
        try {
            Os.symlink(target, link.toString());
        } catch (Exception e) {
            throw new IOException("Cannot create runtime symlink " + link, e);
        }
    }

    private static void writeMarker(File runtime) throws IOException {
        try (FileOutputStream out = new FileOutputStream(new File(runtime, INSTALL_MARKER))) {
            out.write((RUNTIME_BUILD + "\n").getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void verifyPayload(Context context, Payload payload) throws IOException {
        String actual;
        try (InputStream in = new BufferedInputStream(
                context.getAssets().open(PAYLOAD_DIR + payload.fileName))) {
            actual = sha256(in);
        }
        if (!payload.sha256.equals(actual)) {
            throw new IOException(context.getString(R.string.javase_runtime_checksum_failed));
        }
    }

    private static String sha256(InputStream in) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = in.read(buffer)) != -1) digest.update(buffer, 0, count);
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest.digest()) {
                result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            }
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static long copy(InputStream in, java.io.OutputStream out, long limit)
            throws IOException {
        byte[] buffer = new byte[32 * 1024];
        long total = 0;
        int count;
        while ((count = in.read(buffer)) != -1) {
            total += count;
            if (total > limit) throw new IOException("Asset exceeded the size limit");
            out.write(buffer, 0, count);
        }
        return total;
    }

    private static void notify(Progress progress, Stage stage, int percent) {
        if (progress != null) progress.onProgress(stage, Math.max(0, Math.min(100, percent)));
    }

    private static final class Payload {
        final String fileName;
        final long size;
        final String sha256;

        Payload(String fileName, long size, String sha256) {
            this.fileName = fileName;
            this.size = size;
            this.sha256 = sha256;
        }
    }

    private static final class CountingInputStream extends FilterInputStream {
        private long count;

        CountingInputStream(InputStream in) {
            super(in);
        }

        @Override public int read() throws IOException {
            int value = super.read();
            if (value >= 0) count++;
            return value;
        }

        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = super.read(buffer, offset, length);
            if (read > 0) count += read;
            return read;
        }

        long getCount() {
            return count;
        }
    }

    private static void deleteRecursive(File file) {
        if (file == null || !java.nio.file.Files.exists(file.toPath(),
                java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
        if (java.nio.file.Files.isDirectory(file.toPath(),
                java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursive(child);
            }
        }
        file.delete();
    }
}
