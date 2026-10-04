package com.ccs.wiredroid.maven;

import androidx.annotation.Nullable;

/**
 * Reads and rewrites the project's own coordinates in a pom.
 *
 * <p>{@code <artifactId>} appears in a pom many times — once for the project and
 * again for every dependency, plugin and parent. A plain search-and-replace on
 * the value therefore edits whichever one happens to come first, and a project
 * that depends on an artifact of the same name would have both changed. So this
 * walks the tags and only touches a direct child of {@code <project>}.</p>
 *
 * <p>Text in, text out, rather than a DOM round-trip: the pom is a file the user
 * writes by hand, and re-serialising it would reflow their formatting and drop
 * their comments as a side effect of renaming one field.</p>
 */
public final class PomCoordinates {

    private PomCoordinates() {}

    /** The value of a top-level element, or null when the pom has none. */
    @Nullable
    public static String get(String xml, String tag) {
        int[] span = valueSpan(xml, tag);
        return span == null ? null : xml.substring(span[0], span[1]).trim();
    }

    /**
     * The pom with a top-level element's text replaced.
     *
     * @return the rewritten xml, or null when the pom has no such element —
     *         adding one is a different decision, and the caller may not want it
     */
    @Nullable
    public static String set(String xml, String tag, String value) {
        int[] span = valueSpan(xml, tag);
        if (span == null) return null;
        return xml.substring(0, span[0]) + escape(value) + xml.substring(span[1]);
    }

    /**
     * Start and end offsets of the text inside the first depth-1 {@code tag}.
     *
     * <p>Depth is counted from {@code <project>}, so an element of the same name
     * nested in {@code <parent>}, {@code <dependencies>} or {@code <build>} is
     * at depth 2 or deeper and is skipped.</p>
     */
    private static int[] valueSpan(String xml, String tag) {
        if (xml == null || tag == null) return null;
        int depth = 0;
        int i = 0;
        while (i < xml.length()) {
            int lt = xml.indexOf('<', i);
            if (lt < 0) break;

            if (xml.startsWith("<!--", lt)) {
                int end = xml.indexOf("-->", lt);
                i = end < 0 ? xml.length() : end + 3;
                continue;
            }
            if (xml.startsWith("<?", lt)) {
                int end = xml.indexOf("?>", lt);
                i = end < 0 ? xml.length() : end + 2;
                continue;
            }
            int gt = xml.indexOf('>', lt);
            if (gt < 0) break;

            String inner = xml.substring(lt + 1, gt).trim();
            boolean closing = inner.startsWith("/");
            boolean selfClosing = inner.endsWith("/");
            String name = inner;
            if (closing) name = name.substring(1);
            if (selfClosing) name = name.substring(0, name.length() - 1);
            int space = name.indexOf(' ');
            if (space > 0) name = name.substring(0, space);
            name = name.trim();

            if (closing) {
                depth--;
                i = gt + 1;
                continue;
            }
            if (selfClosing) {
                i = gt + 1;
                continue;
            }

            // depth 0 is <project> itself; its direct children are depth 1
            if (depth == 1 && name.equals(tag)) {
                int close = xml.indexOf("</" + tag, gt + 1);
                if (close < 0) return null;
                return new int[]{gt + 1, close};
            }
            depth++;
            i = gt + 1;
        }
        return null;
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
