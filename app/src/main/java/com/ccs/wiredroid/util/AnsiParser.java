package com.ccs.wiredroid.util;

import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.text.style.UnderlineSpan;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts ANSI escape sequences into styled Android {@link CharSequence}s.
 *
 * <p>Supports:
 * <ul>
 *   <li>16 standard and bright colors (SGR 30-37, 90-97)</li>
 *   <li>Background colors (SGR 40-47, 100-107)</li>
 *   <li>256-color lookups (SGR 38;5;n and 48;5;n)</li>
 *   <li>24-bit TrueColor (SGR 38;2;r;g;b and 48;2;r;g;b)</li>
 *   <li>Text styles: Bold (SGR 1), Italic (SGR 3), Underline (SGR 4)</li>
 *   <li>Reset sequence (SGR 0)</li>
 *   <li>Stripping of every other escape sequence — cursor moves, clear line,
 *       hide cursor, window title</li>
 * </ul>
 */
public final class AnsiParser {

    /**
     * Every escape sequence, not just the well-formed colour ones.
     *
     * <p>The alternatives are, in order: a CSI sequence with its full parameter
     * grammar — private markers included, so {@code ESC[?25l} (hide cursor, which
     * any progress bar emits) is recognised rather than left on screen as a
     * literal {@code [?25l}; an OSC sequence, which is how a program sets a
     * window title; a two-character escape; and finally a bare {@code ESC}, so a
     * chunk that split mid-sequence leaves nothing visible behind.</p>
     */
    private static final Pattern ANSI_PATTERN = Pattern.compile(
            "\\x1B(?:"
                    + "\\[([0-?]*)([ -/]*)([@-~])"
                    + "|\\][^\\x07\\x1B]*(?:\\x07|\\x1B\\\\)?"
                    + "|[@-Z\\\\-_]"
                    + "|)");

    /** Group indices into {@link #ANSI_PATTERN}; non-null for the CSI alternative only. */
    private static final int CSI_PARAMS = 1;
    private static final int CSI_INTERMEDIATES = 2;
    private static final int CSI_FINAL = 3;

    private static final Pattern SGR_PARAMS = Pattern.compile("[0-9;]*");

    // Standard 16 ANSI colors tuned for IDE console readability
    private static final int[] STANDARD_COLORS = {
            0xFF21252B, // 0: Black
            0xFFE06C75, // 1: Red
            0xFF98C379, // 2: Green
            0xFFE5C07B, // 3: Yellow
            0xFF61AFEF, // 4: Blue
            0xFFC678DD, // 5: Magenta
            0xFF56B6C2, // 6: Cyan
            0xFFABB2BF, // 7: White
            0xFF5C6370, // 8: Bright Black (Gray)
            0xFFBE5046, // 9: Bright Red
            0xFF7AA860, // 10: Bright Green
            0xFFD19A66, // 11: Bright Yellow / Orange
            0xFF4FA6ED, // 12: Bright Blue
            0xFFB454D1, // 13: Bright Magenta
            0xFF46A2AE, // 14: Bright Cyan
            0xFFFFFFFF  // 15: Bright White
    };

    /**
     * Colour and style, carried across calls.
     *
     * <p>The console appends a line at a time, so a program that turns on red
     * and then prints three lines before resetting sends the code in the first
     * call only. Parsing each line from a clean slate coloured just that first
     * line. A {@code null} foreground means "whatever the caller's default
     * colour is", so a theme change still reaches plain text.</p>
     */
    public static final class State {
        @Nullable public Integer fg;
        @Nullable public Integer bg;
        public boolean bold;
        public boolean italic;
        public boolean underline;

        public void reset() {
            fg = null;
            bg = null;
            bold = false;
            italic = false;
            underline = false;
        }

        boolean isPlain() {
            return fg == null && bg == null && !bold && !italic && !underline;
        }
    }

    private AnsiParser() {}

    /**
     * Fast check to see if text contains escape sequences without allocating regex objects.
     */
    public static boolean containsAnsi(CharSequence text) {
        if (text == null) return false;
        int len = text.length();
        for (int i = 0; i < len; i++) {
            if (text.charAt(i) == 0x1B) return true;
        }
        return false;
    }

    /**
     * Strips all ANSI escape sequences from text, leaving clean human-readable output.
     */
    @NonNull
    public static String stripAnsi(String text) {
        if (text == null) return "";
        if (!containsAnsi(text)) return text;
        return ANSI_PATTERN.matcher(text).replaceAll("");
    }

    /**
     * Converts a string containing ANSI escape codes into a styled {@link CharSequence}.
     * With no sequences present, returns a {@link SpannableString} in {@code defaultColor}.
     */
    @NonNull
    public static CharSequence parse(String text, int defaultColor) {
        return parse(text, defaultColor, null);
    }

    /**
     * As {@link #parse(String, int)}, continuing the style an earlier call left set.
     *
     * @param state carried style, updated in place; {@code null} to parse this
     *              text on its own
     */
    @NonNull
    public static CharSequence parse(String text, int defaultColor, @Nullable State state) {
        if (text == null) return "";
        if (!containsAnsi(text) && (state == null || state.isPlain())) {
            SpannableString ss = new SpannableString(text);
            ss.setSpan(new ForegroundColorSpan(defaultColor), 0, text.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            return ss;
        }

        State style = state != null ? state : new State();
        SpannableStringBuilder builder = new SpannableStringBuilder();
        Matcher matcher = ANSI_PATTERN.matcher(text);

        int activeStyleStart = 0;
        int lastEnd = 0;

        while (matcher.find()) {
            if (matcher.start() > lastEnd) {
                builder.append(text, lastEnd, matcher.start());
            }
            lastEnd = matcher.end();

            if (!isSgr(matcher)) {
                // Cursor moves, clear line, hide cursor, window title: dropped.
                continue;
            }

            // Everything appended since the last code wears the style that was
            // in force while it arrived, so close that run before changing it.
            int upTo = builder.length();
            if (upTo > activeStyleStart) {
                applyStyles(builder, activeStyleStart, upTo, defaultColor, style);
            }
            activeStyleStart = upTo;
            applySgr(parseCodes(matcher.group(CSI_PARAMS)), style);
        }

        if (lastEnd < text.length()) {
            builder.append(text, lastEnd, text.length());
        }

        int end = builder.length();
        if (end > activeStyleStart) {
            applyStyles(builder, activeStyleStart, end, defaultColor, style);
        }

        return builder;
    }

    /**
     * Whether this match is a colour request rather than some other escape.
     *
     * <p>{@code ESC[?25l} also ends in a letter; feeding its {@code ?25} to the
     * colour table produced nonsense, so the private-marker and intermediate
     * bytes have to be checked, not just the final one.</p>
     */
    private static boolean isSgr(Matcher matcher) {
        String finalByte = matcher.group(CSI_FINAL);
        if (!"m".equals(finalByte)) return false;
        String intermediates = matcher.group(CSI_INTERMEDIATES);
        if (intermediates != null && !intermediates.isEmpty()) return false;
        String params = matcher.group(CSI_PARAMS);
        return params == null || SGR_PARAMS.matcher(params).matches();
    }

    /**
     * Folds one SGR sequence's parameters into {@code style}.
     *
     * <p>Package-private so the state machine can be exercised on its own: the
     * spans around it need a real Android text layer, this does not.</p>
     */
    static void applySgr(int[] codes, State style) {
        for (int i = 0; i < codes.length; i++) {
            int code = codes[i];
            if (code == 0) {
                style.reset();
            } else if (code == 1) {
                style.bold = true;
            } else if (code == 3) {
                style.italic = true;
            } else if (code == 4) {
                style.underline = true;
            } else if (code == 22) {
                style.bold = false;
            } else if (code == 23) {
                style.italic = false;
            } else if (code == 24) {
                style.underline = false;
            } else if (code >= 30 && code <= 37) {
                style.fg = STANDARD_COLORS[code - 30];
            } else if (code == 39) {
                style.fg = null;
            } else if (code >= 40 && code <= 47) {
                style.bg = STANDARD_COLORS[code - 40];
            } else if (code == 49) {
                style.bg = null;
            } else if (code >= 90 && code <= 97) {
                style.fg = STANDARD_COLORS[code - 90 + 8];
            } else if (code >= 100 && code <= 107) {
                style.bg = STANDARD_COLORS[code - 100 + 8];
            } else if ((code == 38 || code == 48) && i + 1 < codes.length) {
                int mode = codes[++i];
                Integer extended = null;
                if (mode == 5 && i + 1 < codes.length) {
                    extended = color256(codes[++i]);
                } else if (mode == 2 && i + 3 < codes.length) {
                    int r = clampColor(codes[++i]);
                    int g = clampColor(codes[++i]);
                    int b = clampColor(codes[++i]);
                    extended = 0xFF000000 | (r << 16) | (g << 8) | b;
                }
                if (extended != null) {
                    if (code == 38) style.fg = extended;
                    else style.bg = extended;
                }
            }
        }
    }

    private static void applyStyles(SpannableStringBuilder builder, int start, int end,
                                    int defaultColor, State style) {
        if (start >= end || start < 0 || end > builder.length()) return;

        int fg = style.fg != null ? style.fg : defaultColor;
        builder.setSpan(new ForegroundColorSpan(fg), start, end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        if (style.bg != null) {
            builder.setSpan(new BackgroundColorSpan(style.bg), start, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        if (style.bold && style.italic) {
            builder.setSpan(new StyleSpan(Typeface.BOLD_ITALIC), start, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        } else if (style.bold) {
            builder.setSpan(new StyleSpan(Typeface.BOLD), start, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        } else if (style.italic) {
            builder.setSpan(new StyleSpan(Typeface.ITALIC), start, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        if (style.underline) {
            builder.setSpan(new UnderlineSpan(), start, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    static int[] parseCodes(String s) {
        if (s == null || s.isEmpty()) return new int[]{0};
        String[] parts = s.split(";");
        int[] res = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                res[i] = parts[i].isEmpty() ? 0 : Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                res[i] = 0;
            }
        }
        return res;
    }

    private static int color256(int index) {
        if (index >= 0 && index < 16) {
            return STANDARD_COLORS[index];
        }
        if (index >= 16 && index <= 231) {
            // 6x6x6 cube
            int code = index - 16;
            int r = (code / 36) * 51;
            int g = ((code % 36) / 6) * 51;
            int b = (code % 6) * 51;
            return 0xFF000000 | (r << 16) | (g << 8) | b;
        }
        if (index >= 232 && index <= 255) {
            // Grayscale
            int gray = 8 + (index - 232) * 10;
            return 0xFF000000 | (gray << 16) | (gray << 8) | gray;
        }
        return STANDARD_COLORS[7];
    }

    private static int clampColor(int val) {
        return Math.max(0, Math.min(255, val));
    }
}
