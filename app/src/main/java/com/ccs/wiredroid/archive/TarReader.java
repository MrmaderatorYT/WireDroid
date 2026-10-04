package com.ccs.wiredroid.archive;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Streaming reader for tape archives.
 *
 * <p>Covers the three layouts that actually turn up in the wild: the original
 * v7 header, POSIX ustar, and the two extension schemes used when a value does
 * not fit its fixed-width field — GNU's {@code L}/{@code K} type flags and PAX
 * {@code x}/{@code g} records. Entries are returned in file order and each
 * one's data must be read (or skipped, which {@link #next()} does for you)
 * before the following header becomes available.</p>
 *
 * <p>Not a general-purpose tar library: hard links, symlinks and device nodes
 * are reported through {@link Entry#type} so the caller can skip them, since
 * none of them can be recreated meaningfully inside app storage.</p>
 */
public final class TarReader implements Closeable {

    /** Fixed tar block size; every header and every data run is padded to it. */
    private static final int BLOCK = 512;

    /** One archive member. */
    public static final class Entry {
        /** Path as recorded, always with {@code /} separators. */
        public final String name;
        /** Payload length in bytes; 0 for anything that is not a regular file. */
        public final long size;
        /** Raw ustar type flag: {@code '0'} regular, {@code '5'} directory, … */
        public final char type;
        /** Unix permission bits, or 0 when unreadable. */
        public final int mode;

        Entry(String name, long size, char type, int mode) {
            this.name = name;
            this.size = size;
            this.type = type;
            this.mode = mode;
        }

        public boolean isDirectory() {
            return type == '5' || name.endsWith("/");
        }

        public boolean isRegularFile() {
            return type == '0' || type == '\0' || type == '7';
        }

        /** Links and device nodes, which an extractor should decline to create. */
        public boolean isSpecial() {
            return type == '1' || type == '2' || type == '3' || type == '4' || type == '6';
        }
    }

    private final InputStream in;
    private final byte[] header = new byte[BLOCK];

    /** Bytes of the current entry the caller has not read yet. */
    private long remaining;
    /** Padding to discard once the current entry's data runs out. */
    private int padding;
    private boolean finished;

    public TarReader(InputStream in) {
        this.in = in;
    }

    /**
     * Advances to the next member, skipping any unread data of the current one.
     *
     * @return the entry, or {@code null} at end of archive
     */
    public Entry next() throws IOException {
        if (finished) return null;
        skipFully(remaining + padding);
        remaining = 0;
        padding = 0;

        String longName = null;
        Map<String, String> paxHeaders = null;

        while (true) {
            if (!readBlock()) {
                finished = true;
                return null;
            }
            if (isAllZeros(header)) {
                // A pair of zero blocks ends the archive; a lone one is padding
                // from a writer that stopped early, and is treated the same way.
                finished = true;
                return null;
            }
            if (!verifyChecksum(header)) {
                throw new IOException("Corrupt tar header (checksum mismatch)");
            }

            char type = (char) (header[156] & 0xFF);
            long size = parseSize();

            if (type == 'L') {                       // GNU long name
                longName = trimNul(new String(readData(size), StandardCharsets.UTF_8));
                continue;
            }
            if (type == 'K') {                       // GNU long link target
                readData(size);
                continue;
            }
            if (type == 'x' || type == 'X' || type == 'g') {   // PAX records
                Map<String, String> parsed = parsePax(readData(size));
                if (type == 'g') {
                    // Global records apply to the rest of the archive; we only
                    // need per-entry overrides, so they are read and dropped.
                    continue;
                }
                paxHeaders = parsed;
                continue;
            }
            if (type == 'V') {                       // GNU volume label, no payload
                continue;
            }

            String name = longName != null ? longName : readName();
            if (paxHeaders != null) {
                String paxPath = paxHeaders.get("path");
                if (paxPath != null) name = paxPath;
                String paxSize = paxHeaders.get("size");
                if (paxSize != null) {
                    try {
                        size = Long.parseLong(paxSize.trim());
                    } catch (NumberFormatException ignored) {}
                }
            }

            int mode = (int) parseOctal(header, 100, 8);
            remaining = (type == '5' || isSpecialType(type)) ? 0 : size;
            padding = (int) ((BLOCK - (remaining % BLOCK)) % BLOCK);
            return new Entry(normalize(name), remaining, type, mode);
        }
    }

    /** Reads up to {@code len} bytes of the current entry's payload. */
    public int read(byte[] buffer, int off, int len) throws IOException {
        if (remaining <= 0) return -1;
        int want = (int) Math.min(len, remaining);
        int n = in.read(buffer, off, want);
        if (n > 0) remaining -= n;
        return n;
    }

    @Override
    public void close() throws IOException {
        in.close();
    }

    /**
     * True when a 512-byte block parses as a tar header. Used by format
     * detection for pre-ustar archives, which carry no magic at all.
     */
    static boolean looksLikeHeader(byte[] block) {
        if (block.length < BLOCK) return false;
        if (isAllZeros(block)) return false;
        if (block[0] == 0) return false;
        return verifyChecksum(block);
    }

    // ── header decoding ──────────────────────────────────────────────────────

    /** @return false at a clean end of stream; throws on a half-read block */
    private boolean readBlock() throws IOException {
        int total = 0;
        while (total < BLOCK) {
            int n = in.read(header, total, BLOCK - total);
            if (n < 0) {
                if (total == 0) return false;
                throw new IOException("Truncated tar header");
            }
            total += n;
        }
        return true;
    }

    private String readName() {
        String name = trimNul(new String(header, 0, 100, StandardCharsets.UTF_8));
        // ustar splits long paths across a 155-byte prefix field.
        if (isUstar()) {
            String prefix = trimNul(new String(header, 345, 155, StandardCharsets.UTF_8));
            if (!prefix.isEmpty()) name = prefix + "/" + name;
        }
        return name;
    }

    private boolean isUstar() {
        return header[257] == 'u' && header[258] == 's' && header[259] == 't'
                && header[260] == 'a' && header[261] == 'r';
    }

    private long parseSize() throws IOException {
        // GNU writes sizes above 8 GB as base-256 with the high bit set.
        if ((header[124] & 0x80) != 0) {
            long value = header[124] & 0x7F;
            for (int i = 125; i < 136; i++) {
                value = (value << 8) | (header[i] & 0xFF);
            }
            return value;
        }
        long size = parseOctal(header, 124, 12);
        if (size < 0) throw new IOException("Negative entry size in tar header");
        return size;
    }

    private byte[] readData(long size) throws IOException {
        if (size < 0 || size > 8 * 1024 * 1024) {
            throw new IOException("Implausible tar metadata block: " + size + " bytes");
        }
        byte[] data = new byte[(int) size];
        int total = 0;
        while (total < data.length) {
            int n = in.read(data, total, data.length - total);
            if (n < 0) throw new IOException("Truncated tar metadata block");
            total += n;
        }
        skipFully((BLOCK - (size % BLOCK)) % BLOCK);
        return data;
    }

    /** Parses {@code "<len> <key>=<value>\n"} records. */
    private static Map<String, String> parsePax(byte[] data) {
        Map<String, String> out = new HashMap<>();
        int pos = 0;
        while (pos < data.length) {
            int space = pos;
            while (space < data.length && data[space] != ' ') space++;
            if (space >= data.length) break;
            int length;
            try {
                length = Integer.parseInt(new String(data, pos, space - pos, StandardCharsets.US_ASCII));
            } catch (NumberFormatException e) {
                break;
            }
            if (length <= 0 || pos + length > data.length) break;
            String record = new String(data, space + 1, pos + length - space - 2,
                    StandardCharsets.UTF_8);
            int eq = record.indexOf('=');
            if (eq > 0) out.put(record.substring(0, eq), record.substring(eq + 1));
            pos += length;
        }
        return out;
    }

    private static long parseOctal(byte[] block, int offset, int length) {
        long value = 0;
        boolean any = false;
        for (int i = offset; i < offset + length; i++) {
            int c = block[i] & 0xFF;
            if (c == 0 || c == ' ') {
                if (any) break;
                continue;
            }
            if (c < '0' || c > '7') return any ? value : 0;
            value = (value << 3) + (c - '0');
            any = true;
        }
        return value;
    }

    private static boolean verifyChecksum(byte[] block) {
        long stored = parseOctal(block, 148, 8);
        if (stored == 0) return false;
        long signed = 0;
        long unsigned = 0;
        for (int i = 0; i < BLOCK; i++) {
            // The checksum field itself counts as spaces.
            int b = (i >= 148 && i < 156) ? ' ' : (block[i] & 0xFF);
            unsigned += b;
            signed += (i >= 148 && i < 156) ? ' ' : block[i];
        }
        return stored == unsigned || stored == signed;
    }

    private static boolean isAllZeros(byte[] block) {
        for (int i = 0; i < BLOCK && i < block.length; i++) {
            if (block[i] != 0) return false;
        }
        return true;
    }

    private static boolean isSpecialType(char type) {
        return type == '1' || type == '2' || type == '3' || type == '4' || type == '6';
    }

    private static String trimNul(String s) {
        int end = s.indexOf('\0');
        return end >= 0 ? s.substring(0, end) : s;
    }

    private static String normalize(String name) {
        return name.replace('\\', '/');
    }

    private void skipFully(long count) throws IOException {
        long left = count;
        byte[] scratch = null;
        while (left > 0) {
            long skipped = in.skip(left);
            if (skipped > 0) {
                left -= skipped;
                continue;
            }
            // skip() may legitimately return 0 on a decompressing stream.
            if (scratch == null) scratch = new byte[4096];
            int n = in.read(scratch, 0, (int) Math.min(scratch.length, left));
            if (n < 0) return;
            left -= n;
        }
    }
}
