package com.ccs.wiredroid.util;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Splits Markdown into regular text and fenced code blocks for native rendering. */
public final class MarkdownBlockParser {

    private MarkdownBlockParser() {}

    public static final class Part {
        public final boolean code;
        public final boolean table;
        @NonNull public final String text;
        @NonNull public final String language;

        public Part(boolean code, boolean table, @NonNull String text, @NonNull String language) {
            this.code = code;
            this.table = table;
            this.text = text;
            this.language = language;
        }

        public Part(boolean code, @NonNull String text, @NonNull String language) {
            this(code, false, text, language);
        }
    }

    /** Splits Markdown into regular text, fenced code blocks, and tables for native rendering. */
    @NonNull
    public static List<Part> split(@NonNull String markdown) {
        if (markdown.isEmpty()) return Collections.emptyList();

        List<Part> parts = new ArrayList<>();
        String[] lines = markdown.split("\n", -1);
        StringBuilder current = new StringBuilder();
        boolean inCode = false;
        String language = "";

        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            String trimmed = trimStart(line);

            if (trimmed.startsWith("```")) {
                if (inCode) {
                    addPart(parts, true, false, current, language);
                    current.setLength(0);
                    inCode = false;
                    language = "";
                } else {
                    addPart(parts, false, false, current, "");
                    current.setLength(0);
                    inCode = true;
                    language = trimmed.substring(3).trim();
                }
                i++;
                continue;
            }

            if (inCode) {
                if (current.length() > 0) current.append('\n');
                current.append(line);
                i++;
                continue;
            }

            // Table detection (when not in code)
            if (isTableStart(lines, i)) {
                addPart(parts, false, false, current, "");
                current.setLength(0);

                StringBuilder tableText = new StringBuilder();
                while (i < lines.length && isTableRow(lines[i])) {
                    if (tableText.length() > 0) tableText.append('\n');
                    tableText.append(lines[i].trim());
                    i++;
                }
                addPart(parts, false, true, tableText, "");
                continue;
            }

            if (current.length() > 0) current.append('\n');
            current.append(line);
            i++;
        }

        addPart(parts, inCode, false, current, language);
        return parts;
    }

    private static void addPart(List<Part> parts, boolean code, boolean table, StringBuilder text,
                                String language) {
        if (text.length() == 0) return;
        parts.add(new Part(code, table, text.toString(), code ? language : ""));
    }

    private static String trimStart(String value) {
        int i = 0;
        while (i < value.length() && Character.isWhitespace(value.charAt(i))) i++;
        return value.substring(i);
    }

    private static boolean isTableRow(String line) {
        if (line == null) return false;
        String trimmed = line.trim();
        return trimmed.length() >= 2 && trimmed.contains("|")
                && (trimmed.startsWith("|") || trimmed.endsWith("|") || trimmed.contains(" | "));
    }

    private static boolean isTableStart(String[] lines, int index) {
        if (index >= lines.length) return false;
        if (!isTableRow(lines[index])) return false;
        if (index + 1 < lines.length && isTableDelimiter(lines[index + 1])) {
            return true;
        }
        if (index + 2 < lines.length && isTableRow(lines[index + 1]) && isTableDelimiter(lines[index + 2])) {
            return true;
        }
        return false;
    }

    private static boolean isTableDelimiter(String line) {
        if (line == null) return false;
        String trimmed = line.trim();
        if (trimmed.isEmpty()) return false;
        String stripped = trimmed.replaceAll("[|:\\- \\t]", "");
        return stripped.isEmpty() && trimmed.contains("-") && trimmed.contains("|");
    }
}
