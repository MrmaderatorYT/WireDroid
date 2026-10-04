package com.ccs.wiredroid.analysis;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds {@code TODO} and {@code FIXME} notes in a project's sources.
 *
 * <p>The TODO panel used to ask {@link StaticAnalyzer} for problems and then
 * keep the ones whose message mentioned TODO. No rule ever produced such a
 * message, so the panel was permanently empty while the code was full of
 * them — this is the pass that actually looks.</p>
 *
 * <p>A tag counts only after a comment marker, which is what keeps the word
 * "TODO" inside a string or an identifier out of the list. That is a line-level
 * rule rather than real parsing: a tag inside a multi-line string that happens
 * to start its line with {@code *} is a false positive, and one worth taking
 * over the cost of lexing every file on every keystroke.</p>
 */
public final class TodoScanner {

    /**
     * A comment marker, then the tag, then whatever the note says.
     *
     * <p>Covers the markers the supported languages use: {@code //} and
     * {@code /*} for the C family, {@code *} for continuation lines inside a
     * block comment, {@code #} for scripts and properties, {@code ;} for
     * Clojure, {@code <!--} for XML and {@code --} for SQL.</p>
     */
    private static final Pattern TAG = Pattern.compile(
            "(?://+|/\\*+|\\*+|#+|;+|<!--|--)\\s*(TODO|FIXME)\\b\\s*(?:\\([^)]*\\))?\\s*:?\\s*(.*)",
            Pattern.CASE_INSENSITIVE);

    /** Enough to work through; a list longer than this is not read, it is scrolled past. */
    private static final int MAX_ITEMS = 500;
    /** Generated or vendored sources run to megabytes and hold nobody's notes. */
    private static final long MAX_FILE_BYTES = 2L * 1024 * 1024;

    private TodoScanner() {}

    /** Notes in the given files, in the order the files were given. */
    public static List<ProblemItem> scan(List<File> sources) {
        List<ProblemItem> found = new ArrayList<>();
        if (sources == null) return found;
        for (File file : sources) {
            if (found.size() >= MAX_ITEMS) break;
            scanFile(file, found);
        }
        return found;
    }

    private static void scanFile(File file, List<ProblemItem> into) {
        if (file == null || !file.isFile() || file.length() > MAX_FILE_BYTES) return;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            int number = 0;
            while ((line = reader.readLine()) != null) {
                number++;
                if (into.size() >= MAX_ITEMS) return;
                ProblemItem item = itemIn(line, file, number);
                if (item != null) into.add(item);
            }
        } catch (IOException ignored) {
            // A file that cannot be read has no notes to contribute; the rest of
            // the project still does.
        }
    }

    /**
     * The note on one line, or null when there is none.
     *
     * <p>The message is spelled {@code TAG: text} because that is the shape the
     * panel's adapter splits on.</p>
     */
    static ProblemItem itemIn(String line, File file, int number) {
        if (line == null || line.length() > 2000) return null;
        Matcher matcher = TAG.matcher(line);
        if (!matcher.find()) return null;
        String tag = matcher.group(1).toUpperCase(java.util.Locale.ROOT);
        String text = matcher.group(2) == null ? "" : matcher.group(2).trim();
        // Trailing comment punctuation is part of the syntax, not of the note.
        while (text.endsWith("*/") || text.endsWith("-->")) {
            text = text.substring(0, text.length() - (text.endsWith("*/") ? 2 : 3)).trim();
        }
        if (text.isEmpty()) text = tag;
        ProblemItem.Severity severity = "FIXME".equals(tag)
                ? ProblemItem.Severity.WARNING : ProblemItem.Severity.INFO;
        return new ProblemItem(severity, tag + ": " + text, file, number);
    }
}
