package com.ccs.wiredroid.editor;

import java.io.File;

/**
 * What to do with a file that is too big to treat like source.
 *
 * <p>Highlighting costs time proportional to the text, and so does every pass
 * that runs after it — the outline, the live problems, the minimap. On a
 * generated file, a bundled dataset or a log, all of that is spent producing
 * colour nobody asked for, and the editor stops responding while it happens.</p>
 *
 * <p>So size decides how much the editor does. The thresholds are deliberately
 * generous: a real source file almost never crosses them, and the point is to
 * stay out of the way until something is clearly not source.</p>
 */
public final class LargeFilePolicy {

    /** How much of the editor a file gets. */
    public enum Mode {
        /** Normal source: highlighting, analysis, editing. */
        FULL,
        /** Plain text and editable, but no highlighting or analysis. */
        PLAIN,
        /** Plain text, opened read-only; too big to edit safely on a phone. */
        READ_ONLY
    }

    /** Past this, the file is read off the main thread with a spinner. */
    public static final long ASYNC_READ_BYTES = 256L * 1024;

    /** Past this, highlighting is dropped. Any real source file is far below. */
    public static final long PLAIN_BYTES = 2L * 1024 * 1024;

    /**
     * Past this, the file opens read-only.
     *
     * <p>Not a guess at what the editor can render — it is what an edit would
     * cost: every keystroke rewrites the buffer, and a save writes the lot back.
     * A file this size is being looked at, not worked on.</p>
     */
    public static final long READ_ONLY_BYTES = 12L * 1024 * 1024;

    private LargeFilePolicy() {}

    public static Mode modeFor(long bytes) {
        if (bytes >= READ_ONLY_BYTES) return Mode.READ_ONLY;
        if (bytes >= PLAIN_BYTES) return Mode.PLAIN;
        return Mode.FULL;
    }

    public static Mode modeFor(File file) {
        return file == null || !file.isFile() ? Mode.FULL : modeFor(file.length());
    }

    /** True when the read should be moved off the main thread. */
    public static boolean readsAsync(File file) {
        return file != null && file.isFile() && file.length() >= ASYNC_READ_BYTES;
    }

    /** True when highlighting and the passes behind it are switched off. */
    public static boolean isPlain(File file) {
        return modeFor(file) != Mode.FULL;
    }

    /** A size a person can read: "1.4 MB", not "1468006". */
    public static String describeSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return Math.round(bytes / 1024.0) + " KB";
        return String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
