package com.ccs.wiredroid.util;

import android.graphics.Color;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Форматування JSON та XML для перегляду з підсвіткою.
 */
public final class JsonXmlFormatter {

    private static final int COLOR_KEY = 0xFFA9B7C6;
    private static final int COLOR_STRING = 0xFF6A8759;
    private static final int COLOR_NUMBER = 0xFF6897BB;
    private static final int COLOR_BOOLEAN = 0xFFCC7832;
    private static final int COLOR_NULL = 0xFFCC7832;
    private static final int COLOR_BRACE = 0xFFA9B7C6;
    private static final int COLOR_TAG = 0xFFCC7832;
    private static final int COLOR_ATTR_NAME = 0xFFBABABA;
    private static final int COLOR_ATTR_VALUE = 0xFF6A8759;
    private static final int COLOR_COMMENT = 0xFF808080;
    private static final int COLOR_TEXT = 0xFFA9B7C6;

    private JsonXmlFormatter() {}

    @NonNull
    public static SpannableStringBuilder formatJson(@NonNull String json) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        try {
            String pretty = prettyPrintJson(json);
            colorizeJson(pretty, sb);
        } catch (Exception e) {
            sb.append(json);
        }
        return sb;
    }

    @NonNull
    public static SpannableStringBuilder formatXml(@NonNull String xml) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        try {
            String pretty = prettyPrintXml(xml);
            colorizeXml(pretty, sb);
        } catch (Exception e) {
            sb.append(xml);
        }
        return sb;
    }

    public static boolean isJsonFile(@Nullable String fileName) {
        return fileName != null && fileName.toLowerCase().endsWith(".json");
    }

    public static boolean isXmlFile(@Nullable String fileName) {
        return fileName != null && (fileName.toLowerCase().endsWith(".xml")
                || fileName.toLowerCase().endsWith(".gradle")
                || fileName.toLowerCase().endsWith(".html")
                || fileName.toLowerCase().endsWith(".svg"));
    }

    // ── JSON pretty print ────────────────────────────────────

    private static String prettyPrintJson(String json) {
        StringBuilder out = new StringBuilder();
        int indent = 0;
        boolean inString = false;
        boolean escaped = false;

        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);

            if (escaped) {
                out.append(c);
                escaped = false;
                continue;
            }
            if (c == '\\' && inString) {
                out.append(c);
                escaped = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                out.append(c);
                continue;
            }
            if (inString) {
                out.append(c);
                continue;
            }

            switch (c) {
                case '{':
                case '[':
                    out.append(c);
                    out.append('\n');
                    indent++;
                    appendIndent(out, indent);
                    break;
                case '}':
                case ']':
                    out.append('\n');
                    indent--;
                    appendIndent(out, indent);
                    out.append(c);
                    break;
                case ',':
                    out.append(c);
                    out.append('\n');
                    appendIndent(out, indent);
                    break;
                case ':':
                    out.append(c);
                    out.append(' ');
                    break;
                case ' ':
                case '\t':
                case '\n':
                case '\r':
                    break;
                default:
                    out.append(c);
                    break;
            }
        }
        return out.toString();
    }

    private static void appendIndent(StringBuilder sb, int indent) {
        for (int i = 0; i < indent; i++) {
            sb.append("  ");
        }
    }

    // ── JSON colorize ────────────────────────────────────────

    private static void colorizeJson(String json, SpannableStringBuilder sb) {
        int i = 0;
        int len = json.length();
        int line = 0;

        while (i < len) {
            char c = json.charAt(i);

            if (c == '\n') {
                sb.append(c);
                i++;
                continue;
            }

            if (c == '"') {
                int start = sb.length();
                sb.append(c);
                i++;
                boolean escaped = false;
                while (i < len) {
                    char ch = json.charAt(i);
                    sb.append(ch);
                    if (escaped) {
                        escaped = false;
                    } else if (ch == '\\') {
                        escaped = true;
                    } else if (ch == '"') {
                        i++;
                        break;
                    }
                    i++;
                }

                int j = start + 1;
                while (j < sb.length() - 1 && sb.charAt(j) != '"') j++;
                if (j < sb.length() - 1 && sb.charAt(j) == '"') {
                    boolean isKey = false;
                    int scan = i;
                    while (scan < len) {
                        char s = json.charAt(scan);
                        if (s == ':') { isKey = true; break; }
                        if (s == '}' || s == ']' || s == ',') break;
                        scan++;
                    }
                    if (isKey) {
                        sb.setSpan(new ForegroundColorSpan(COLOR_KEY), start, sb.length(),
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    } else {
                        sb.setSpan(new ForegroundColorSpan(COLOR_STRING), start, sb.length(),
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                }
                continue;
            }

            if (c == '-' || Character.isDigit(c)) {
                int start = sb.length();
                while (i < len && (Character.isDigit(json.charAt(i)) || json.charAt(i) == '.'
                        || json.charAt(i) == '-' || json.charAt(i) == 'e' || json.charAt(i) == 'E'
                        || json.charAt(i) == '+')) {
                    sb.append(json.charAt(i));
                    i++;
                }
                sb.setSpan(new ForegroundColorSpan(COLOR_NUMBER), start, sb.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                continue;
            }

            if (c == 't' && i + 3 < len && json.substring(i, i + 4).equals("true")) {
                int start = sb.length();
                sb.append("true");
                sb.setSpan(new ForegroundColorSpan(COLOR_BOOLEAN), start, sb.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                i += 4;
                continue;
            }
            if (c == 'f' && i + 4 < len && json.substring(i, i + 5).equals("false")) {
                int start = sb.length();
                sb.append("false");
                sb.setSpan(new ForegroundColorSpan(COLOR_BOOLEAN), start, sb.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                i += 5;
                continue;
            }
            if (c == 'n' && i + 3 < len && json.substring(i, i + 4).equals("null")) {
                int start = sb.length();
                sb.append("null");
                sb.setSpan(new ForegroundColorSpan(COLOR_NULL), start, sb.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                i += 4;
                continue;
            }

            if (c == '{' || c == '}' || c == '[' || c == ']') {
                sb.append(c);
                sb.setSpan(new ForegroundColorSpan(COLOR_BRACE), sb.length() - 1, sb.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                i++;
                continue;
            }

            sb.append(c);
            i++;
        }
    }

    // ── XML pretty print ─────────────────────────────────────

    private static String prettyPrintXml(String xml) {
        StringBuilder out = new StringBuilder();
        int indent = 0;
        int i = 0;
        int len = xml.length();

        while (i < len) {
            if (xml.charAt(i) == '<') {
                if (i + 1 < len && xml.charAt(i + 1) == '/') {
                    indent--;
                    if (indent < 0) indent = 0;
                }

                int tagEnd = xml.indexOf('>', i);
                if (tagEnd < 0) tagEnd = len - 1;
                String tag = xml.substring(i, tagEnd + 1);

                if (tag.startsWith("<?") || tag.startsWith("<!")) {
                    appendIndent(out, indent);
                    out.append(tag).append('\n');
                    i = tagEnd + 1;
                    continue;
                }

                boolean selfClosing = tag.endsWith("/>");
                appendIndent(out, indent);
                out.append(tag).append('\n');

                if (!selfClosing && !tag.startsWith("</")) {
                    indent++;
                }
                i = tagEnd + 1;
            } else {
                int tagStart = xml.indexOf('<', i);
                if (tagStart < 0) tagStart = len;
                String text = xml.substring(i, tagStart).trim();
                if (!text.isEmpty()) {
                    appendIndent(out, indent);
                    out.append(text).append('\n');
                }
                i = tagStart;
            }
        }
        return out.toString();
    }

    // ── XML colorize ─────────────────────────────────────────

    private static void colorizeXml(String xml, SpannableStringBuilder sb) {
        int i = 0;
        int len = xml.length();

        while (i < len) {
            char c = xml.charAt(i);

            if (c == '<') {
                int tagEnd = xml.indexOf('>', i);
                if (tagEnd < 0) tagEnd = len - 1;
                String tag = xml.substring(i, tagEnd + 1);

                if (tag.startsWith("<!--")) {
                    int commentEnd = xml.indexOf("-->", i);
                    if (commentEnd < 0) commentEnd = len;
                    int start = sb.length();
                    sb.append(xml.substring(i, commentEnd + 3));
                    sb.setSpan(new ForegroundColorSpan(COLOR_COMMENT), start, sb.length(),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = commentEnd + 3;
                    continue;
                }

                int start = sb.length();
                sb.append(tag);
                colorizeTag(tag, start, sb);
                i = tagEnd + 1;
                continue;
            }

            int nextTag = xml.indexOf('<', i);
            if (nextTag < 0) nextTag = len;
            String text = xml.substring(i, nextTag).trim();
            if (!text.isEmpty()) {
                int start = sb.length();
                sb.append(text);
                sb.setSpan(new ForegroundColorSpan(COLOR_TEXT), start, sb.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            i = nextTag;
        }
    }

    private static void colorizeTag(String tag, int startOffset, SpannableStringBuilder sb) {
        int i = startOffset;
        int len = sb.length();
        if (i >= len) return;

        if (sb.charAt(i) == '<') {
            sb.setSpan(new ForegroundColorSpan(COLOR_TAG), i, i + 1,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            i++;
        }

        if (i < len && sb.charAt(i) == '/') {
            i++;
        }

        int nameStart = i;
        while (i < len && !Character.isWhitespace(sb.charAt(i)) && sb.charAt(i) != '>'
                && sb.charAt(i) != '/') {
            i++;
        }
        if (i > nameStart) {
            sb.setSpan(new ForegroundColorSpan(COLOR_TAG), nameStart, i,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        while (i < len) {
            char ch = sb.charAt(i);
            if (ch == '=' && i + 1 < len) {
                int attrStart = i;
                while (attrStart > nameStart && sb.charAt(attrStart - 1) != ' '
                        && sb.charAt(attrStart - 1) != '\n') {
                    attrStart--;
                }
                if (attrStart < i) {
                    sb.setSpan(new ForegroundColorSpan(COLOR_ATTR_NAME), attrStart, i,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                i++;
                if (i < len && sb.charAt(i) == '"') {
                    int qStart = i;
                    i++;
                    while (i < len && sb.charAt(i) != '"') i++;
                    if (i < len) i++;
                    sb.setSpan(new ForegroundColorSpan(COLOR_ATTR_VALUE), qStart, i,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            } else {
                i++;
            }
        }
    }
}
