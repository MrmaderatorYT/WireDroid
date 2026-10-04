package com.ccs.wiredroid.archive;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.Locale;

/**
 * The archive kinds the importer can tell apart.
 *
 * <p>Detection reads the file's own header rather than trusting the name — a
 * project exported from a phone browser is just as likely to arrive as
 * {@code project.zip.bin} as with the right suffix. The extension is only
 * consulted for the tar-inside-gzip case, which has no distinguishing magic of
 * its own, and as a last resort when the header is inconclusive.</p>
 *
 * <p>Formats we cannot unpack are still recognised, so the importer can say
 * "7-Zip archives aren't supported" instead of failing with a parse error
 * halfway through.</p>
 */
public enum ArchiveFormat {

    /** PKZIP container — also covers .jar, .aar, .apk and friends. */
    ZIP(true),
    /** Uncompressed tape archive. */
    TAR(true),
    /** Tar wrapped in gzip (.tar.gz, .tgz). */
    TAR_GZ(true),
    /** A single gzipped file that is not a tar. */
    GZIP(true),
    /** WinRAR container; only the RAR4 layout can be read. */
    RAR(true),

    /** Recognised but not supported — no bzip2 decoder is bundled. */
    BZIP2(false),
    /** Recognised but not supported — no LZMA decoder is bundled. */
    XZ(false),
    /** Recognised but not supported — no LZMA decoder is bundled. */
    SEVEN_ZIP(false),

    /** Header matched nothing known. */
    UNKNOWN(false);

    /** True when {@link ArchiveExtractor} can unpack this format. */
    public final boolean supported;

    ArchiveFormat(boolean supported) {
        this.supported = supported;
    }

    /** Reads the leading bytes of {@code file} and classifies it. */
    public static ArchiveFormat detect(File file) throws IOException {
        byte[] head = new byte[512];
        int read;
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            read = raf.read(head);
        }
        if (read <= 0) return UNKNOWN;
        return detect(head, read, file.getName());
    }

    /**
     * Classifies an already-read header.
     *
     * @param head  first bytes of the archive
     * @param len   how many of them are valid
     * @param name  file name, used only to separate .tar.gz from a plain .gz
     */
    public static ArchiveFormat detect(byte[] head, int len, String name) {
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);

        if (startsWith(head, len, 0x50, 0x4B, 0x03, 0x04)
                || startsWith(head, len, 0x50, 0x4B, 0x05, 0x06)   // empty archive
                || startsWith(head, len, 0x50, 0x4B, 0x07, 0x08)) { // spanned
            return ZIP;
        }
        if (startsWith(head, len, 0x1F, 0x8B)) {
            // Gzip carries no hint about its payload. The name is the only clue
            // available without decompressing, and .tgz/.tar.gz is the norm.
            return (lower.endsWith(".tar.gz") || lower.endsWith(".tgz")
                    || lower.endsWith(".tar.gzip")) ? TAR_GZ : GZIP;
        }
        if (startsWith(head, len, 0x52, 0x61, 0x72, 0x21, 0x1A, 0x07)) return RAR;
        if (startsWith(head, len, 0x42, 0x5A, 0x68)) return BZIP2;
        if (startsWith(head, len, 0xFD, 0x37, 0x7A, 0x58, 0x5A, 0x00)) return XZ;
        if (startsWith(head, len, 0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C)) return SEVEN_ZIP;

        // Tar has no leading magic; ustar sits at offset 257 and older archives
        // have nothing at all, so fall back to validating the header checksum.
        if (len >= 265 && head[257] == 'u' && head[258] == 's' && head[259] == 't'
                && head[260] == 'a' && head[261] == 'r') {
            return TAR;
        }
        if (len >= 512 && TarReader.looksLikeHeader(head)) return TAR;

        // Nothing matched. A correct extension still beats giving up.
        if (lower.endsWith(".zip") || lower.endsWith(".jar") || lower.endsWith(".aar")) return ZIP;
        if (lower.endsWith(".tar")) return TAR;
        if (lower.endsWith(".rar")) return RAR;
        return UNKNOWN;
    }

    /** Strips the archive suffix so an import can name the folder after it. */
    public static String stripExtension(String fileName) {
        if (fileName == null || fileName.isEmpty()) return "";
        String lower = fileName.toLowerCase(Locale.ROOT);
        String[] suffixes = {
                ".tar.gz", ".tar.gzip", ".tar.bz2", ".tar.xz", ".tgz", ".tbz2", ".txz",
                ".zip", ".jar", ".aar", ".tar", ".rar", ".gz", ".7z", ".bz2", ".xz"
        };
        for (String s : suffixes) {
            if (lower.endsWith(s)) return fileName.substring(0, fileName.length() - s.length());
        }
        return fileName;
    }

    /** Reads up to 512 bytes without consuming them, for stream-based callers. */
    static int peek(InputStream in, byte[] buffer) throws IOException {
        int total = 0;
        while (total < buffer.length) {
            int n = in.read(buffer, total, buffer.length - total);
            if (n < 0) break;
            total += n;
        }
        return total;
    }

    private static boolean startsWith(byte[] data, int len, int... magic) {
        if (len < magic.length) return false;
        for (int i = 0; i < magic.length; i++) {
            if ((data[i] & 0xFF) != magic[i]) return false;
        }
        return true;
    }
}
