package com.ccs.wiredroid.util;

import io.github.rosemoe.sora.text.LineSeparator;

/**
 * Reading and rewriting the line endings of a file.
 *
 * <p>The editor keeps a separator per line, so a file opened with CRLF stays
 * CRLF through an edit and a save. That also means a file can hold more than
 * one kind at once — which is what {@link #detect} reports, and what the status
 * bar has to be able to say.</p>
 *
 * <p>Deliberately over the plain text rather than over the editor's line model:
 * the same answer is needed for text that is not in an editor at all, and the
 * rules are short enough that a second reading of them is not a risk.</p>
 */
public final class LineSeparators {

    private LineSeparators() {}

    /** What a file uses, including the case where it is not consistent. */
    public static final class Detected {
        /** The dominant separator, or {@link LineSeparator#LF} for a file with no line breaks. */
        public final LineSeparator dominant;
        /** Whether more than one kind of separator appears. */
        public final boolean mixed;
        /** Whether the text has any line break at all. */
        public final boolean any;

        Detected(LineSeparator dominant, boolean mixed, boolean any) {
            this.dominant = dominant;
            this.mixed = mixed;
            this.any = any;
        }
    }

    /**
     * The separators a text uses.
     *
     * <p>A lone {@code \r} counts as CR only when no {@code \n} follows it, so a
     * CRLF file is never read as "CR and LF mixed".</p>
     */
    public static Detected detect(CharSequence text) {
        if (text == null) return new Detected(LineSeparator.LF, false, false);
        int lf = 0, cr = 0, crlf = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\r') {
                if (i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    crlf++;
                    i++;
                } else {
                    cr++;
                }
            } else if (c == '\n') {
                lf++;
            }
        }
        int kinds = (lf > 0 ? 1 : 0) + (cr > 0 ? 1 : 0) + (crlf > 0 ? 1 : 0);
        if (kinds == 0) return new Detected(LineSeparator.LF, false, false);

        LineSeparator dominant;
        if (crlf >= lf && crlf >= cr) dominant = LineSeparator.CRLF;
        else if (lf >= cr) dominant = LineSeparator.LF;
        else dominant = LineSeparator.CR;
        return new Detected(dominant, kinds > 1, true);
    }

    /**
     * The same text with every line ending replaced by {@code target}.
     *
     * <p>Every existing ending is recognised first, so converting a mixed file
     * makes it consistent rather than converting only the endings that happen to
     * match one pattern.</p>
     */
    public static String convert(String text, LineSeparator target) {
        if (text == null || text.isEmpty()) return text;
        String to = target.getContent();
        StringBuilder out = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\r') {
                if (i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                out.append(to);
            } else if (c == '\n') {
                out.append(to);
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** The short name shown in the status bar: {@code LF}, {@code CRLF}, {@code CR}. */
    public static String shortName(LineSeparator separator) {
        switch (separator) {
            case CRLF: return "CRLF";
            case CR:   return "CR";
            default:   return "LF";
        }
    }

    /** The order the picker offers, and the order its labels are in. */
    public static final LineSeparator[] CHOICES =
            { LineSeparator.LF, LineSeparator.CRLF, LineSeparator.CR };

    /** Index of a separator in {@link #CHOICES}, or 0 when it is not one of them. */
    public static int indexOf(LineSeparator separator) {
        for (int i = 0; i < CHOICES.length; i++) {
            if (CHOICES[i] == separator) return i;
        }
        return 0;
    }
}
