package com.ccs.wiredroid.tools.compilers;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
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
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Downloads and installs the ARM64 NDK r29 toolchain on demand.
 *
 * <p>The archive used to ship inside the APK, split into six asset parts. At 344 MiB
 * of already-compressed payload it cannot: an App Bundle's base module has a hard
 * 500 MB compressed ceiling, and the archive alone spent most of it. It is fetched
 * from its upstream release instead, so the cost is paid once, by the fraction of
 * users who actually build C++, rather than by every install.</p>
 *
 * <p>Everything that made the bundled copy trustworthy is kept. The download is
 * pinned to a SHA-256 and a byte count and is verified before a single file is
 * extracted, so a truncated transfer or a re-uploaded release asset fails loudly
 * instead of producing a toolchain that miscompiles. Extraction still goes to a
 * staging directory and is promoted by one rename, so an interrupted install
 * cannot leave a half-populated NDK behind that {@link #isValid} would accept.</p>
 *
 * <p>Two details are worth knowing before changing anything here. The archive is
 * kept under {@code files/ndk/}, next to the installation it feeds, for two
 * reasons: the free-space check then measures the filesystem the extraction will
 * actually use, and the Settings "remove" action already deletes that whole
 * directory, so an abandoned download cannot outlive the feature. And a partial
 * download is deliberately kept between attempts — the upstream host answers
 * {@code Range} requests, and asking someone on mobile data to re-send 344 MiB
 * because a tunnel dropped is not a real option.</p>
 */
public final class NdkManager {

    private static final String NDK_DIR_NAME = "android-ndk-r29";
    private static final String HOST_ABI = "arm64-v8a";
    private static final String CLANG_TARGET = "aarch64-linux-android26";
    private static final String PACKAGED_CLANG = "libwiredroid_clang.so";
    private static final String PACKAGED_LLD = "libwiredroid_lld.so";
    private static final String COMPILER_LINK_DIR = "ndk-exec";

    /** Upstream release; see assets/toolchains/README.txt for provenance. */
    private static final String ARCHIVE_URL =
            "https://github.com/lzhiyong/termux-ndk/releases/download/android-ndk/"
                    + "android-ndk-r29-aarch64.tar.xz";
    private static final String ARCHIVE_SHA256 =
            "02e10e4ddfe8deaeb0bd0cf29d04c981ed5bc8a5d6b560ebb9e7661f472d684b";
    private static final long ARCHIVE_BYTES = 360_538_712L;
    private static final String ARCHIVE_NAME = "android-ndk-r29-aarch64.tar.xz";

    /**
     * Stamped into the installed tree so a toolchain left by an older build, or by
     * a different upstream archive, is reinstalled rather than trusted. Derived
     * from the archive hash so the two can never drift apart.
     */
    private static final String NDK_BUILD = "r29-" + ARCHIVE_SHA256;
    private static final String INSTALL_MARKER = ".wiredroid-ndk";

    private static final long ARCHIVE_UNCOMPRESSED_BYTES = 1_853_972_480L;
    private static final long MAX_EXTRACTED_BYTES = 2_100_000_000L;
    /** Headroom for the extracted tree alone; the archive is accounted separately. */
    private static final long MIN_FREE_BYTES = 2_200L * 1024L * 1024L;

    /**
     * The progress bar is one bar across three phases, so each phase owns a slice
     * of it and the bar only ever moves forward. The split follows wall-clock:
     * downloading dominates, hashing 344 MiB off internal storage is seconds.
     */
    private static final int DOWNLOAD_END_PERCENT = 65;
    private static final int VERIFY_END_PERCENT = 70;

    private static final Object INSTALL_LOCK = new Object();
    private static final AtomicBoolean CANCELLED = new AtomicBoolean();

    /**
     * Whether this device can run the NDK at all.
     *
     * <p>The archive holds Linux/aarch64 host binaries — a clang that runs on
     * the device — so an x86 Chromebook or emulator cannot execute a byte of it.
     * Asked before anything is offered, rather than after 344 MiB has been
     * downloaded onto a device that can only fail to use it. The bundled TCC is
     * compiled from source for every ABI and is unaffected.</p>
     */
    public static boolean isSupportedOnThisDevice() {
        for (String abi : android.os.Build.SUPPORTED_ABIS) {
            if (HOST_ABI.equals(abi)) return true;
        }
        return false;
    }

    /** The architecture this device reports, for a message that names it. */
    public static String deviceAbi() {
        return android.os.Build.SUPPORTED_ABIS.length == 0
                ? "unknown" : android.os.Build.SUPPORTED_ABIS[0];
    }

    public interface NdkInstallCallback {
        void onProgress(String message, int percent);
        void onSuccess();
        void onError(String error);

        /**
         * The user stopped the install. Distinct from {@link #onError} because a
         * deliberate cancel is not a failure and must not raise a dialog.
         */
        default void onCancelled() {}
    }

    private NdkManager() {}

    public static boolean isSupportedDevice() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false;
        for (String abi : Build.SUPPORTED_ABIS) {
            if ("arm64-v8a".equals(abi)) return true;
        }
        return false;
    }

    public static boolean isNdkInstalled(Context context) {
        return isValid(getNdkDir(context))
                && getPackagedTool(context, PACKAGED_CLANG).canExecute()
                && getPackagedTool(context, PACKAGED_LLD).canExecute();
    }

    public static File getNdkDir(Context context) {
        return new File(context.getFilesDir(), "ndk/" + NDK_DIR_NAME);
    }

    public static File getClangPath(Context context) {
        return new File(getCompilerLinkDir(context), "clang++");
    }

    public static File getLldPath(Context context) {
        return new File(getCompilerLinkDir(context), "ld.lld");
    }

    public static File getLlvmPrebuiltDir(Context context) {
        return new File(getNdkDir(context),
                "toolchains/llvm/prebuilt/linux-aarch64");
    }

    public static String getClangTarget() {
        return CLANG_TARGET;
    }

    /** Download size, for the confirmation the user sees before it starts. */
    public static long getDownloadBytes() {
        return ARCHIVE_BYTES;
    }

    /** How much of the download is already on disk from an earlier attempt. */
    public static long getDownloadedBytes(Context context) {
        File archive = archiveFile(context.getApplicationContext());
        long have = archive.isFile() ? archive.length() : 0L;
        return have > ARCHIVE_BYTES ? 0L : have;
    }

    public static void prepareCompilerLaunchers(Context context) throws IOException {
        File linkDir = getCompilerLinkDir(context);
        if (!linkDir.isDirectory() && !linkDir.mkdirs()) {
            throw new IOException("Cannot create " + linkDir);
        }
        replaceCompilerLink(getClangPath(context),
                getPackagedTool(context, PACKAGED_CLANG));
        replaceCompilerLink(getLldPath(context),
                getPackagedTool(context, PACKAGED_LLD));
    }

    /**
     * Fetches the toolchain if needed and installs it. Returns immediately; every
     * callback arrives on the main thread.
     */
    public static void installNdk(Context context, NdkInstallCallback callback) {
        Context app = context.getApplicationContext();
        if (!isSupportedOnThisDevice()) {
            callback.onError(app.getString(R.string.ndk_unsupported_arch, deviceAbi()));
            return;
        }
        CANCELLED.set(false);
        new Thread(() -> install(app, callback), "ndk-install").start();
    }

    /**
     * Asks a running install to stop at the next chunk boundary.
     *
     * <p>A partly downloaded archive survives so the next attempt resumes; a partly
     * extracted tree does not, because only a completed extraction is promoted out
     * of staging.</p>
     */
    public static void cancelInstall() {
        CANCELLED.set(true);
    }

    private static void install(Context context, NdkInstallCallback callback) {
        Handler main = new Handler(Looper.getMainLooper());
        synchronized (INSTALL_LOCK) {
            File ndkRoot = new File(context.getFilesDir(), "ndk");
            File stagingRoot = new File(ndkRoot, ".installing");
            File stagingNdk = new File(stagingRoot, NDK_DIR_NAME);
            File target = getNdkDir(context);
            File archive = archiveFile(context);
            try {
                if (!isSupportedDevice()) {
                    throw new IOException(context.getString(R.string.ndk_bundled_unsupported));
                }
                if (isValid(target)) {
                    main.post(callback::onSuccess);
                    return;
                }
                if (!ndkRoot.isDirectory() && !ndkRoot.mkdirs()) {
                    throw new IOException("Cannot create " + ndkRoot);
                }

                // What is still to be fetched counts towards the space needed, so a
                // resumed install is not refused for room it no longer requires.
                long alreadyFetched = archive.isFile() && archive.length() <= ARCHIVE_BYTES
                        ? archive.length() : 0L;
                long needed = MIN_FREE_BYTES + Math.max(0L, ARCHIVE_BYTES - alreadyFetched);
                long free = new StatFs(ndkRoot.getAbsolutePath()).getAvailableBytes();
                if (free < needed) {
                    throw new IOException(context.getString(R.string.ndk_bundled_no_space,
                            needed / 1024L / 1024L));
                }

                downloadArchive(context, archive, main, callback);
                verifyArchive(context, archive, main, callback);

                deleteRecursive(stagingRoot);
                if (!stagingRoot.mkdirs()) {
                    throw new IOException("Cannot create " + stagingRoot);
                }
                extractArchive(context, archive, stagingRoot, main, callback);
                writeMarker(stagingNdk);
                if (!isValid(stagingNdk)) {
                    throw new IOException("NDK is incomplete after extraction");
                }

                deleteRecursive(target);
                if (!stagingNdk.renameTo(target)) {
                    throw new IOException("Cannot activate " + target);
                }
                deleteRecursive(stagingRoot);
                // The toolchain is installed; 344 MiB of archive is now dead weight.
                deleteRecursive(archive);
                main.post(callback::onSuccess);
            } catch (Cancelled cancelled) {
                deleteRecursive(stagingRoot);
                main.post(callback::onCancelled);
            } catch (Exception error) {
                deleteRecursive(stagingRoot);
                String message = error.getMessage();
                if (message == null || message.trim().isEmpty()) {
                    message = error.getClass().getSimpleName();
                }
                String finalMessage = message;
                main.post(() -> callback.onError(finalMessage));
            }
        }
    }

    private static void downloadArchive(Context context, File archive, Handler main,
                                        NdkInstallCallback callback) throws IOException {
        long have = archive.isFile() ? archive.length() : 0L;
        if (have > ARCHIVE_BYTES) {
            // Longer than the pinned length: not a prefix of what we want.
            deleteRecursive(archive);
            have = 0L;
        }
        if (have == ARCHIVE_BYTES) {
            postDownload(context, main, callback, have);
            return;
        }
        checkCancelled();

        HttpURLConnection connection =
                (HttpURLConnection) new URL(ARCHIVE_URL).openConnection();
        connection.setConnectTimeout(20_000);
        connection.setReadTimeout(120_000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "WireDroid-NDK/1.0");
        boolean append = have > 0L;
        if (append) connection.setRequestProperty("Range", "bytes=" + have + "-");
        try {
            int code = connection.getResponseCode();
            if (code == 416) {
                // The range is past the end: the leftover cannot belong to this
                // asset. Start clean rather than splice mismatched halves.
                deleteRecursive(archive);
                throw new IOException(context.getString(R.string.ndk_download_restart));
            }
            if (append && code == HttpURLConnection.HTTP_OK) {
                // The range was ignored, so the body is the whole file again.
                append = false;
                have = 0L;
            } else if (code != HttpURLConnection.HTTP_OK
                    && code != HttpURLConnection.HTTP_PARTIAL) {
                throw new IOException(context.getString(R.string.ndk_download_http, code));
            }

            long copied = have;
            long lastReported = -1L;
            byte[] buffer = new byte[64 * 1024];
            postDownload(context, main, callback, copied);
            try (BufferedInputStream in = new BufferedInputStream(connection.getInputStream());
                 BufferedOutputStream out = new BufferedOutputStream(
                         new FileOutputStream(archive, append))) {
                int count;
                while ((count = in.read(buffer)) != -1) {
                    checkCancelled();
                    copied += count;
                    if (copied > ARCHIVE_BYTES) {
                        throw new IOException(context.getString(R.string.ndk_download_too_large));
                    }
                    out.write(buffer, 0, count);
                    // A report per megabyte: enough to look alive, not enough to
                    // flood the main thread with 5500 Handler posts.
                    if (copied - lastReported >= 1024L * 1024L) {
                        lastReported = copied;
                        postDownload(context, main, callback, copied);
                    }
                }
            }
            if (copied != ARCHIVE_BYTES) {
                throw new IOException(context.getString(R.string.ndk_download_incomplete));
            }
            postDownload(context, main, callback, copied);
        } finally {
            connection.disconnect();
        }
    }

    /**
     * Hashes the archive before anything is extracted from it.
     *
     * <p>A mismatch deletes the file: it is either a corrupt resume or an upstream
     * asset that was replaced, and in both cases the bytes on disk are worthless,
     * so keeping them would make every later attempt fail the same way.</p>
     */
    private static void verifyArchive(Context context, File archive, Handler main,
                                      NdkInstallCallback callback) throws IOException {
        postProgress(main, callback, context.getString(R.string.ndk_verifying),
                DOWNLOAD_END_PERCENT);
        String digest;
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(archive))) {
                int count;
                while ((count = in.read(buffer)) != -1) {
                    checkCancelled();
                    sha256.update(buffer, 0, count);
                }
            }
            StringBuilder hex = new StringBuilder(64);
            for (byte value : sha256.digest()) {
                hex.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            }
            digest = hex.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
        if (!ARCHIVE_SHA256.equals(digest)) {
            deleteRecursive(archive);
            throw new IOException(context.getString(R.string.ndk_download_checksum_failed));
        }
        postProgress(main, callback, context.getString(R.string.ndk_verifying),
                VERIFY_END_PERCENT);
    }

    private static void extractArchive(Context context, File archive, File destination,
                                       Handler main, NdkInstallCallback callback)
            throws IOException {
        Path root = destination.toPath().toAbsolutePath().normalize();
        byte[] buffer = new byte[64 * 1024];
        long extracted = 0;
        int lastPercent = -1;

        try (InputStream raw = new FileInputStream(archive);
             XZCompressorInputStream xz = new XZCompressorInputStream(
                     new BufferedInputStream(raw), true);
             TarArchiveInputStream tar = new TarArchiveInputStream(xz)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextTarEntry()) != null) {
                checkCancelled();
                Path outputPath = root.resolve(entry.getName()).normalize();
                if (!outputPath.startsWith(root)) {
                    throw new IOException("Unsafe NDK path: " + entry.getName());
                }
                File output = outputPath.toFile();
                if (entry.isDirectory()) {
                    if (!output.isDirectory() && !output.mkdirs()) {
                        throw new IOException("Cannot create " + output);
                    }
                } else if (entry.isSymbolicLink()) {
                    createSafeSymlink(root, outputPath, entry.getLinkName());
                } else if (entry.isLink()) {
                    throw new IOException("Hard links are not supported in the NDK payload");
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
                                throw new IOException("NDK payload exceeded the size limit");
                            }
                        }
                    }
                    try {
                        Os.chmod(output.getAbsolutePath(), entry.getMode() & 0777);
                    } catch (Throwable ignored) {
                        output.setExecutable((entry.getMode() & 0111) != 0, false);
                    }
                }

                int percent = VERIFY_END_PERCENT + (int) Math.min(
                        100L - VERIFY_END_PERCENT,
                        tar.getBytesRead() * (100L - VERIFY_END_PERCENT)
                                / ARCHIVE_UNCOMPRESSED_BYTES);
                if (percent != lastPercent) {
                    lastPercent = percent;
                    postProgress(main, callback,
                            context.getString(R.string.settings_ndk_installing), percent);
                }
            }
        }
        postProgress(main, callback,
                context.getString(R.string.settings_ndk_installing), 100);
    }

    private static void createSafeSymlink(Path root, Path link, String target)
            throws IOException {
        Path targetPath = Paths.get(target);
        if (targetPath.isAbsolute()
                || !link.getParent().resolve(targetPath).normalize().startsWith(root)) {
            throw new IOException("Unsafe NDK symlink: " + link + " -> " + target);
        }
        File parent = link.toFile().getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Cannot create " + parent);
        }
        if (java.nio.file.Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            if (!java.nio.file.Files.isSymbolicLink(link)) {
                throw new IOException("Cannot replace non-symlink NDK path " + link);
            }
            java.nio.file.Files.delete(link);
        }
        try {
            Os.symlink(target, link.toString());
        } catch (Exception error) {
            throw new IOException("Cannot create NDK symlink " + link, error);
        }
    }

    private static File archiveFile(Context context) {
        return new File(new File(context.getFilesDir(), "ndk"), ARCHIVE_NAME + ".part");
    }

    private static File getCompilerLinkDir(Context context) {
        return new File(context.getFilesDir(), COMPILER_LINK_DIR);
    }

    private static File getPackagedTool(Context context, String name) {
        return new File(context.getApplicationInfo().nativeLibraryDir, name);
    }

    private static void replaceCompilerLink(File link, File target) throws IOException {
        if (!target.isFile() || !target.canExecute()) {
            throw new IOException("Packaged compiler is unavailable: " + target);
        }
        Path linkPath = link.toPath();
        if (java.nio.file.Files.exists(linkPath,
                java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            if (!java.nio.file.Files.isSymbolicLink(linkPath)) {
                throw new IOException("Cannot replace non-symlink compiler path " + link);
            }
            java.nio.file.Files.delete(linkPath);
        }
        try {
            Os.symlink(target.getAbsolutePath(), link.getAbsolutePath());
        } catch (Exception error) {
            throw new IOException("Cannot create compiler launcher " + link, error);
        }
    }

    private static boolean isValid(File ndk) {
        if (ndk == null || !ndk.isDirectory()) return false;
        File marker = new File(ndk, INSTALL_MARKER);
        if (!marker.isFile()) return false;
        try {
            String value = new String(java.nio.file.Files.readAllBytes(marker.toPath()),
                    StandardCharsets.UTF_8).trim();
            if (!NDK_BUILD.equals(value)) return false;
        } catch (IOException error) {
            return false;
        }
        File clang = new File(ndk,
                "toolchains/llvm/prebuilt/linux-aarch64/bin/clang++");
        return clang.isFile() && clang.canExecute();
    }

    private static void writeMarker(File ndk) throws IOException {
        if (!ndk.isDirectory()) throw new IOException("NDK root is missing: " + ndk);
        try (FileOutputStream out = new FileOutputStream(new File(ndk, INSTALL_MARKER))) {
            out.write((NDK_BUILD + "\n").getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void checkCancelled() throws Cancelled {
        if (CANCELLED.get()) throw new Cancelled();
    }

    private static void postDownload(Context context, Handler main,
                                     NdkInstallCallback callback, long copied) {
        String message = context.getString(R.string.ndk_downloading,
                megabytes(copied), megabytes(ARCHIVE_BYTES));
        int percent = (int) (copied * DOWNLOAD_END_PERCENT / ARCHIVE_BYTES);
        postProgress(main, callback, message, percent);
    }

    private static void postProgress(Handler main, NdkInstallCallback callback,
                                     String message, int percent) {
        int bounded = Math.max(0, Math.min(100, percent));
        main.post(() -> callback.onProgress(message, bounded));
    }

    private static String megabytes(long bytes) {
        return (bytes / (1024L * 1024L)) + " MB";
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

    /** Thrown to unwind out of the download or extraction when the user cancels. */
    private static final class Cancelled extends IOException {
        Cancelled() {
            super("cancelled");
        }
    }
}
