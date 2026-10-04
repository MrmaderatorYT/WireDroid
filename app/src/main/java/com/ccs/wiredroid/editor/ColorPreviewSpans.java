package com.ccs.wiredroid.editor;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds colour literals in a line so the editor can draw them.
 *
 * <p>{@code #3574F0} is six characters that mean nothing until you paste them
 * somewhere that renders. A small filled square beside the literal answers
 * "which blue is that" without leaving the file.</p>
 *
 * <p>Kept as pure text matching, with no editor types in the signature, so the
 * rules are testable on their own — which matters, because the interesting part
 * is what must <em>not</em> match: a six-digit hex number is a colour, an
 * eight-digit git hash is not.</p>
 */
public final class ColorPreviewSpans {

    /** One literal and the colour it denotes. */
    public static final class Swatch {
        /** Column of the literal's first character. */
        public final int start;
        /** Column just past its last character. */
        public final int end;
        /** Fully opaque ARGB, ready for a Paint. */
        public final int color;

        Swatch(int start, int end, int color) {
            this.start = start;
            this.end = end;
            this.color = color;
        }
    }

    /**
     * {@code #RGB}, {@code #RRGGBB} and {@code #AARRGGBB}, in a string or not.
     *
     * <p>Bounded on both sides so a longer hex run is skipped whole: the last
     * six characters of a commit id are not a colour, and highlighting them as
     * one is worse than showing nothing.</p>
     */
    private static final Pattern HEX = Pattern.compile(
            "(?<![0-9A-Za-z_#])#([0-9a-fA-F]{8}|[0-9a-fA-F]{6}|[0-9a-fA-F]{3})(?![0-9A-Za-z_])");

    /** {@code Color.parseColor("#RRGGBB")} — the same literal behind a call. */
    private static final Pattern PARSE_COLOR = Pattern.compile(
            "parseColor\\s*\\(\\s*\"(#[0-9a-fA-F]{3,8})\"\\s*\\)");

    /** {@code 0xFF3574F0} as written in Java. */
    private static final Pattern HEX_LITERAL = Pattern.compile(
            "(?<![0-9A-Za-z_])0[xX]([0-9a-fA-F]{8}|[0-9a-fA-F]{6})(?![0-9A-Za-z_])");

    private ColorPreviewSpans() {}

    /** Every colour literal on one line, in the order they appear. */
    public static List<Swatch> findIn(String line) {
        List<Swatch> found = new ArrayList<>();
        if (line == null || line.isEmpty() || line.length() > 2000) return found;

        Matcher parse = PARSE_COLOR.matcher(line);
        while (parse.find()) {
            Integer color = parseHex(parse.group(1).substring(1));
            if (color != null) found.add(new Swatch(parse.start(1), parse.end(1), color));
        }

        Matcher hex = HEX.matcher(line);
        while (hex.find()) {
            // Skipped when it is the literal inside a parseColor already taken.
            if (overlaps(found, hex.start())) continue;
            Integer color = parseHex(hex.group(1));
            if (color != null) found.add(new Swatch(hex.start(), hex.end(), color));
        }

        Matcher literal = HEX_LITERAL.matcher(line);
        while (literal.find()) {
            if (overlaps(found, literal.start())) continue;
            Integer color = parseHex(literal.group(1));
            if (color != null) found.add(new Swatch(literal.start(), literal.end(), color));
        }

        found.sort((a, b) -> Integer.compare(a.start, b.start));
        return found;
    }

    private static boolean overlaps(List<Swatch> found, int start) {
        for (Swatch s : found) {
            if (start >= s.start - 1 && start < s.end) return true;
        }
        return false;
    }

    /**
     * Turns 3, 6 or 8 hex digits into an opaque colour.
     *
     * <p>Alpha is dropped rather than honoured: the square is there to identify
     * the hue, and a nearly transparent swatch identifies nothing.</p>
     */
    static Integer parseHex(String digits) {
        try {
            if (digits.length() == 3) {
                // Packed by hand rather than through android.graphics.Color, so
                // this class stays plain Java and can be unit tested without a
                // device — the framework's Color throws on a bare JVM.
                int r = Integer.parseInt(digits.substring(0, 1).repeat(2), 16);
                int g = Integer.parseInt(digits.substring(1, 2).repeat(2), 16);
                int b = Integer.parseInt(digits.substring(2, 3).repeat(2), 16);
                return 0xFF000000 | (r << 16) | (g << 8) | b;
            }
            String rgb = digits.length() == 8 ? digits.substring(2) : digits;
            if (rgb.length() != 6) return null;
            return 0xFF000000 | (int) Long.parseLong(rgb, 16);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
