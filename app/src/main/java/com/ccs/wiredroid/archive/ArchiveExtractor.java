package com.ccs.wiredroid.archive;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Unpacks a project archive into a directory.
 *
 * <p>One entry point for every supported format so callers do not branch on the
 * extension, and so the safety rules are written once. Those rules are:</p>
 * <ul>
 *   <li>every destination path is resolved and checked to be inside the target
 *       directory, which stops the traversal trick where an entry is named
 *       {@code ../../databases/x} ("zip slip") — it applies equally to tar and
 *       rar, both of which happily store such names;</li>
 *   <li>links and device nodes are skipped rather than recreated;</li>
 *   <li>totals are capped, so a small archive cannot expand into a full disk.</li>
 * </ul>
 *
 * <p>Anything skipped is reported in {@link Result#skipped} instead of being
 * swallowed, so the UI can tell the user what did not make it.</p>
 */
public final class ArchiveExtractor {

    /** Refuse to write more than this in total (a "zip bomb" guard). */
    private static final long MAX_TOTAL_BYTES = 4L * 1024 * 1024 * 1024;

    /** Refuse an archive claiming more entries than any real project has. */
    private static final int MAX_ENTRIES = 200_000;

    private static final int BUFFER = 64 * 1024;

    /** Progress reporting; called from the extraction thread. */
    public interface Listener {
        /**
         * @param entryName  path being written
         * @param entryIndex 0-based position in the archive
         * @param bytesTotal bytes written so far across all entries
         */
        void onEntry(String entryName, int entryIndex, long bytesTotal);
    }

    /** What an extraction produced. */
    public static final class Result {
        public final ArchiveFormat format;
        public final int fileCount;
        public final int directoryCount;
        public final long bytesWritten;
        /** Entries deliberately not written, with the reason. */
        public final List<String> skipped;

        Result(ArchiveFormat format, int fileCount, int directoryCount,
               long bytesWritten, List<String> skipped) {
            this.format = format;
            this.fileCount = fileCount;
            this.directoryCount = directoryCount;
            this.bytesWritten = bytesWritten;
            this.skipped = skipped;
        }
    }

    /** Thrown when the format is recognised but cannot be unpacked here. */
    public static final class UnsupportedFormatException extends IOException {
        public final ArchiveFormat format;

        UnsupportedFormatException(ArchiveFormat format, String message) {
            super(message);
            this.format = format;
        }
    }

    private ArchiveExtractor() {}

    /**
     * Extracts {@code archive} into {@code destDir}, creating it if needed.
     *
     * @throws UnsupportedFormatException for a known-but-unreadable container
     * @throws IOException                on malformed input or a write failure
     */
    public static Result extract(File archive, File destDir, Listener listener) throws IOException {
        ArchiveFormat format = ArchiveFormat.detect(archive);
        if (!destDir.exists() && !destDir.mkdirs()) {
            throw new IOException("Cannot create " + destDir.getAbsolutePath());
        }
        File root = destDir.getCanonicalFile();

        switch (format) {
            case ZIP:
                return extractZip(archive, root, listener);
            case TAR:
                try (InputStream in = new BufferedInputStream(new FileInputStream(archive), BUFFER)) {
                    return extractTar(in, root, ArchiveFormat.TAR, listener);
                }
            case TAR_GZ:
                try (InputStream in = new GZIPInputStream(
                        new BufferedInputStream(new FileInputStream(archive), BUFFER), BUFFER)) {
                    return extractTar(in, root, ArchiveFormat.TAR_GZ, listener);
                }
            case GZIP:
                return extractSingleGzip(archive, root, listener);
            case RAR:
                return RarSupport.extract(archive, root, listener);
            case BZIP2:
                throw new UnsupportedFormatException(format, "bzip2");
            case XZ:
                throw new UnsupportedFormatException(format, "xz");
            case SEVEN_ZIP:
                throw new UnsupportedFormatException(format, "7z");
            default:
                throw new UnsupportedFormatException(format, archive.getName());
        }
    }

    /** Formats {@link #extract} understands, for building a file-picker filter. */
    public static String[] supportedExtensions() {
        return new String[]{"zip", "jar", "tar", "tar.gz", "tgz", "gz", "rar"};
    }

    // ── per-format readers ───────────────────────────────────────────────────

    private static Result extractZip(File archive, File root, Listener listener) throws IOException {
        Counters counters = new Counters();
        try (ZipInputStream zip = new ZipInputStream(
                new BufferedInputStream(new FileInputStream(archive), BUFFER))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                counters.checkEntryCount();
                File target = resolve(root, entry.getName(), counters);
                if (target == null) continue;

                if (entry.isDirectory()) {
                    mkdirs(target);
                    counters.directories++;
                } else {
                    writeFile(zip, target, counters);
                    notifyEntry(listener, entry.getName(), counters);
                }
                zip.closeEntry();
            }
        }
        return counters.toResult(ArchiveFormat.ZIP);
    }

    private static Result extractTar(InputStream in, File root, ArchiveFormat format,
                                     Listener listener) throws IOException {
        Counters counters = new Counters();
        TarReader tar = new TarReader(in);
        TarReader.Entry entry;
        byte[] buffer = new byte[BUFFER];

        while ((entry = tar.next()) != null) {
            counters.checkEntryCount();
            if (entry.isSpecial()) {
                counters.skip(entry.name, "link or device node");
                continue;
            }
            File target = resolve(root, entry.name, counters);
            if (target == null) continue;

            if (entry.isDirectory()) {
                mkdirs(target);
                counters.directories++;
                continue;
            }
            mkdirs(target.getParentFile());
            try (OutputStream out = new BufferedOutputStream(new FileOutputStream(target), BUFFER)) {
                int n;
                while ((n = tar.read(buffer, 0, buffer.length)) > 0) {
                    out.write(buffer, 0, n);
                    counters.addBytes(n);
                }
            }
            counters.files++;
            applyExecutableBit(target, entry.mode);
            notifyEntry(listener, entry.name, counters);
        }
        return counters.toResult(format);
    }

    /** A lone .gz holds exactly one file, named after the archive. */
    private static Result extractSingleGzip(File archive, File root, Listener listener)
            throws IOException {
        Counters counters = new Counters();
        String name = ArchiveFormat.stripExtension(archive.getName());
        if (name.isEmpty()) name = "extracted";
        File target = resolve(root, name, counters);
        if (target == null) return counters.toResult(ArchiveFormat.GZIP);

        mkdirs(target.getParentFile());
        try (InputStream in = new GZIPInputStream(
                new BufferedInputStream(new FileInputStream(archive), BUFFER), BUFFER)) {
            writeFile(in, target, counters);
        }
        notifyEntry(listener, name, counters);
        return counters.toResult(ArchiveFormat.GZIP);
    }

    // ── shared helpers, also used by RarSupport ──────────────────────────────

    /**
     * Maps an archive-relative name onto a real file inside {@code root}.
     *
     * @return the target, or {@code null} when the entry must be skipped
     */
    static File resolve(File root, String rawName, Counters counters) throws IOException {
        String name = rawName == null ? "" : rawName.replace('\\', '/').trim();
        while (name.startsWith("/")) name = name.substring(1);
        if (name.isEmpty() || name.equals(".") || name.equals("..")) {
            counters.skip(rawName, "empty path");
            return null;
        }
        // Windows drive letters ("C:/x") would otherwise resolve oddly.
        if (name.length() > 1 && name.charAt(1) == ':') {
            counters.skip(rawName, "absolute path");
            return null;
        }

        File target = new File(root, name).getCanonicalFile();
        // Both sides canonical. Comparing a canonicalised target against a raw
        // root fails whenever the destination is reached through a symlink — on a
        // Mac /var is a link to /private/var — and then every single entry looks
        // like an escape and the archive extracts to nothing.
        String rootPath = root.getCanonicalPath();
        String prefix = rootPath + File.separator;
        if (!target.getPath().equals(rootPath) && !target.getPath().startsWith(prefix)) {
            counters.skip(rawName, "path escapes the destination folder");
            return null;
        }
        return target;
    }

    static void writeFile(InputStream in, File target, Counters counters) throws IOException {
        mkdirs(target.getParentFile());
        byte[] buffer = new byte[BUFFER];
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(target), BUFFER)) {
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
                counters.addBytes(n);
            }
        }
        counters.files++;
    }

    /** Creates {@code dir} and its parents, failing loudly instead of silently. */
    public static void mkdirs(File dir) throws IOException {
        if (dir == null || dir.isDirectory()) return;
        if (!dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("Cannot create " + dir.getAbsolutePath());
        }
    }

    static void notifyEntry(Listener listener, String name, Counters counters) {
        if (listener != null) {
            listener.onEntry(name, counters.files + counters.directories - 1, counters.bytes);
        }
    }

    /** Keeps the executable bit on scripts and gradlew, which tar records. */
    private static void applyExecutableBit(File file, int mode) {
        if ((mode & 0111) != 0) {
            //noinspection ResultOfMethodCallIgnored
            file.setExecutable(true, (mode & 0011) == 0);
        }
    }

    /** Running totals plus the limits that make extraction safe to run unattended. */
    static final class Counters {
        int files;
        int directories;
        long bytes;
        final List<String> skipped = new ArrayList<>();

        void addBytes(int n) throws IOException {
            bytes += n;
            if (bytes > MAX_TOTAL_BYTES) {
                throw new IOException("Archive expands past "
                        + (MAX_TOTAL_BYTES / (1024 * 1024 * 1024)) + " GB; refusing to continue");
            }
        }

        void checkEntryCount() throws IOException {
            if (files + directories + skipped.size() > MAX_ENTRIES) {
                throw new IOException("Archive has more than " + MAX_ENTRIES + " entries");
            }
        }

        void skip(String name, String reason) {
            if (skipped.size() < 100) skipped.add(name + " — " + reason);
        }

        Result toResult(ArchiveFormat format) {
            return new Result(format, files, directories, bytes, skipped);
        }
    }
}
