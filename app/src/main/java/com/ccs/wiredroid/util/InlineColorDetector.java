package com.ccs.wiredroid.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class InlineColorDetector {

    private static final Pattern HEX_COLOR_PATTERN = Pattern.compile("#[0-9a-fA-F]{6}|#[0-9a-fA-F]{8}|0x[0-9a-fA-F]{6,8}");

    public static class ColorMatch {
        public String matchedText;
        public int startCol;
        public int endCol;
    }

    public static ColorMatch findColorAtColumn(String lineText, int column) {
        if (lineText == null || lineText.isEmpty()) return null;
        Matcher matcher = HEX_COLOR_PATTERN.matcher(lineText);
        while (matcher.find()) {
            int start = matcher.start();
            int end = matcher.end();
            if (column >= start && column <= end) {
                ColorMatch match = new ColorMatch();
                match.matchedText = matcher.group();
                match.startCol = start;
                match.endCol = end;
                return match;
            }
        }
        return null;
    }
}
