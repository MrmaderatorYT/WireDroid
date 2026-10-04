package com.ccs.wiredroid.util;

import org.eclipse.jgit.diff.DiffAlgorithm;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.diff.RawTextComparator;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Line-based local-history diffs and surgical hunk restoration. */
public final class LocalHistoryDiff {
    public static final class Hunk {
        public final int currentStart;
        public final int currentEnd;
        public final int snapshotStart;
        public final int snapshotEnd;

        Hunk(Edit edit) {
            currentStart = edit.getBeginA();
            currentEnd = edit.getEndA();
            snapshotStart = edit.getBeginB();
            snapshotEnd = edit.getEndB();
        }

        public String title() {
            return "Current " + range(currentStart, currentEnd)
                    + " ← snapshot " + range(snapshotStart, snapshotEnd);
        }

        private static String range(int start, int end) {
            if (start == end) return "after line " + start;
            return "lines " + (start + 1) + "–" + end;
        }
    }

    private LocalHistoryDiff() {}

    public static List<Hunk> hunks(String current, String snapshot) {
        RawText a = raw(current);
        RawText b = raw(snapshot);
        List<Edit> edits = DiffAlgorithm.getAlgorithm(DiffAlgorithm.SupportedAlgorithm.HISTOGRAM)
                .diff(RawTextComparator.DEFAULT, a, b);
        List<Hunk> result = new ArrayList<>(edits.size());
        for (Edit edit : edits) result.add(new Hunk(edit));
        return result;
    }

    public static String unifiedDiff(String current, String snapshot,
                                     String currentName, String snapshotName) {
        RawText a = raw(current);
        RawText b = raw(snapshot);
        StringBuilder out = new StringBuilder();
        out.append("--- ").append(currentName).append('\n');
        out.append("+++ ").append(snapshotName).append('\n');
        for (Hunk hunk : hunks(current, snapshot)) {
            out.append("@@ -").append(hunk.currentStart + 1).append(',')
                    .append(hunk.currentEnd - hunk.currentStart)
                    .append(" +").append(hunk.snapshotStart + 1).append(',')
                    .append(hunk.snapshotEnd - hunk.snapshotStart).append(" @@\n");
            for (int i = hunk.currentStart; i < hunk.currentEnd; i++) {
                out.append('-').append(line(a, i)).append('\n');
            }
            for (int i = hunk.snapshotStart; i < hunk.snapshotEnd; i++) {
                out.append('+').append(line(b, i)).append('\n');
            }
        }
        if (out.indexOf("@@") < 0) out.append(" No differences\n");
        return out.toString();
    }

    public static String restoreHunk(String current, String snapshot, Hunk hunk) {
        boolean trailingNewline = current != null && current.endsWith("\n");
        List<String> currentLines = lines(current);
        List<String> snapshotLines = lines(snapshot);
        if (hunk.currentStart < 0 || hunk.currentEnd > currentLines.size()
                || hunk.snapshotStart < 0 || hunk.snapshotEnd > snapshotLines.size()) {
            throw new IllegalArgumentException("History hunk no longer matches the current document");
        }
        List<String> result = new ArrayList<>(currentLines);
        result.subList(hunk.currentStart, hunk.currentEnd).clear();
        result.addAll(hunk.currentStart,
                snapshotLines.subList(hunk.snapshotStart, hunk.snapshotEnd));
        String joined = join(result);
        return trailingNewline && !joined.endsWith("\n") ? joined + "\n" : joined;
    }

    private static RawText raw(String value) {
        String normalized = value == null ? "" : value;
        if (!normalized.endsWith("\n")) normalized += "\n";
        return new RawText(normalized.getBytes(StandardCharsets.UTF_8));
    }

    private static String line(RawText text, int index) {
        return index >= 0 && index < text.size() ? text.getString(index) : "";
    }

    private static List<String> lines(String text) {
        String value = text == null ? "" : text;
        String[] split = value.split("\n", -1);
        List<String> result = new ArrayList<>();
        int length = split.length;
        if (value.endsWith("\n") && length > 0) length--;
        for (int i = 0; i < length; i++) result.add(split[i]);
        return result;
    }

    private static String join(List<String> lines) {
        return String.join("\n", lines);
    }
}
