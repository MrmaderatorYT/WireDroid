package com.ccs.wiredroid.editor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Finds the blocks a long file can be collapsed into.
 *
 * <p>A 2000-line class is mostly scrolling. Folding the bodies away leaves the
 * signatures, which is the view you want when looking for a method rather than
 * reading one.</p>
 *
 * <p>Braces, not a parser. A real AST would know a lambda from a class body, but
 * it would also need the file to compile — and a file being edited usually does
 * not. Brace matching survives half-typed code, which is when folding is most
 * useful. Comments, strings and character literals are skipped so a {@code "}"}
 * inside a string does not close anything.</p>
 *
 * <p>Kept free of editor types so the rules can be tested against plain
 * strings.</p>
 */
public final class FoldingRegions {

    /** Where the text lines come from; {@code Content} and a String array both fit. */
    public interface LineSource {
        CharSequence lineAt(int index);
    }

    /**
     * One collapsible block.
     *
     * <p>{@code start} stays on screen — it holds the signature. Everything from
     * {@code start + 1} through {@code end} is what disappears, closing brace
     * included, so a folded method occupies exactly one row.</p>
     */
    public static final class Region {
        public final int start;
        public final int end;

        Region(int start, int end) {
            this.start = start;
            this.end = end;
        }

        /** Lines hidden when this region is collapsed. */
        public int hiddenLines() {
            return end - start;
        }

        public boolean contains(int line) {
            return line >= start && line <= end;
        }

        @Override
        public String toString() {
            return "[" + start + ".." + end + "]";
        }
    }

    /** Below this a fold saves nothing: a header plus one hidden line. */
    private static final int MIN_HIDDEN_LINES = 2;

    /** Guard against a generated file with a brace on every line. */
    private static final int MAX_LINES = 200_000;

    private FoldingRegions() {}

    /** Convenience for tests and for whole strings. */
    public static List<Region> scan(String[] lines) {
        return scan(index -> lines[index], lines.length);
    }

    /**
     * Every foldable block in the file, outermost first at each start line.
     *
     * <p>Nested blocks are all reported — folding a class and folding one method
     * inside it are different requests, and which one the user meant is decided
     * later, by where the caret is.</p>
     */
    public static List<Region> scan(LineSource source, int lineCount) {
        List<Region> regions = new ArrayList<>();
        if (source == null || lineCount <= 0 || lineCount > MAX_LINES) return regions;

        // Lines on which the still-open braces were opened.
        int[] openLines = new int[64];
        int depth = 0;

        boolean inBlockComment = false;
        int blockCommentStart = -1;

        for (int line = 0; line < lineCount; line++) {
            CharSequence text = source.lineAt(line);
            if (text == null) continue;
            int length = text.length();

            for (int i = 0; i < length; i++) {
                char c = text.charAt(i);

                if (inBlockComment) {
                    if (c == '*' && i + 1 < length && text.charAt(i + 1) == '/') {
                        inBlockComment = false;
                        if (line - blockCommentStart >= MIN_HIDDEN_LINES) {
                            regions.add(new Region(blockCommentStart, line));
                        }
                        i++;
                    }
                    continue;
                }

                if (c == '/' && i + 1 < length) {
                    char next = text.charAt(i + 1);
                    if (next == '/') break;                 // rest of the line is a comment
                    if (next == '*') {
                        inBlockComment = true;
                        blockCommentStart = line;
                        i++;
                        continue;
                    }
                }

                if (c == '"' || c == '\'') {
                    i = skipQuoted(text, i, c);
                    continue;
                }

                if (c == '{') {
                    if (depth == openLines.length) {
                        int[] bigger = new int[depth * 2];
                        System.arraycopy(openLines, 0, bigger, 0, depth);
                        openLines = bigger;
                    }
                    openLines[depth++] = line;
                } else if (c == '}' && depth > 0) {
                    int opened = openLines[--depth];
                    if (line - opened >= MIN_HIDDEN_LINES) {
                        regions.add(new Region(opened, line));
                    }
                }
            }
        }

        // Outermost first among regions sharing a start line, so "fold the block
        // at the caret" picks the whole thing rather than its first statement.
        Collections.sort(regions, (a, b) ->
                a.start != b.start ? Integer.compare(a.start, b.start) : Integer.compare(b.end, a.end));
        return regions;
    }

    /**
     * Index of the closing quote, or the end of the line for an unterminated one.
     *
     * <p>An unterminated string is normal mid-edit; treating the rest of the line
     * as string is what every editor does, and it keeps a stray brace after the
     * quote from opening a block that will never close.</p>
     */
    private static int skipQuoted(CharSequence text, int start, char quote) {
        int length = text.length();
        for (int i = start + 1; i < length; i++) {
            char c = text.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == quote) {
                return i;
            }
        }
        return length;
    }

    /**
     * The tightest region to fold when the caret is on this line.
     *
     * <p>A region starting exactly here wins — the caret is on a signature and
     * that block is plainly what was meant. Otherwise the innermost enclosing
     * block is folded, which is what folding does from inside a method body.</p>
     */
    public static Region regionFor(List<Region> regions, int line) {
        Region startingHere = null;
        Region enclosing = null;
        for (Region region : regions) {
            if (region.start == line) {
                // Outermost comes first after sorting; keep it.
                if (startingHere == null) startingHere = region;
            } else if (region.contains(line)) {
                if (enclosing == null || region.start > enclosing.start) enclosing = region;
            }
        }
        return startingHere != null ? startingHere : enclosing;
    }

    /**
     * The regions to fold for "collapse everything", one level in from the top.
     *
     * <p>Folding literally every region would fold the class itself and leave one
     * line on screen. What is wanted is the members: the outermost regions inside
     * the outermost region.</p>
     */
    public static List<Region> membersOf(List<Region> regions) {
        List<Region> outermost = new ArrayList<>();
        for (Region region : regions) {
            boolean nested = false;
            for (Region other : regions) {
                if (other != region && other.start < region.start && other.end >= region.end) {
                    nested = true;
                    break;
                }
            }
            if (!nested) outermost.add(region);
        }
        if (outermost.size() != 1) return outermost;

        // A single top-level block is the class body; fold what is inside it.
        Region top = outermost.get(0);
        List<Region> members = new ArrayList<>();
        for (Region region : regions) {
            if (region == top || !top.contains(region.start)) continue;
            boolean nested = false;
            for (Region other : regions) {
                if (other != top && other != region
                        && other.start < region.start && other.end >= region.end) {
                    nested = true;
                    break;
                }
            }
            if (!nested) members.add(region);
        }
        return members.isEmpty() ? outermost : members;
    }
}
