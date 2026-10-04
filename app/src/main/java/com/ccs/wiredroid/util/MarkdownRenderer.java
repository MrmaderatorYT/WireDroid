package com.ccs.wiredroid.util;

import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.LineBackgroundSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.UnderlineSpan;
import android.text.style.URLSpan;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Нативний високоякісний рендерер Markdown через SpannableStringBuilder.
 * Підтримує:
 * - Заголовки (H1-H6) з пропорційними шрифтами та роздільниками
 * - GitHub-style сповіщення (Callouts / Alerts: [!NOTE], [!TIP], [!IMPORTANT], [!WARNING], [!CAUTION])
 * - Таблиці з ідеальним вирівнюванням колонок та моноширинною сіткою
 * - Task-списки (☐ / ☑) та вкладені нумеровані/марковані списки
 * - Inline-код, <kbd>клавіші</kbd>, ==підсвітку==, форматування (bold, italic, strikethrough)
 * - Клікабельні посилання ([text](url)) та автоматичні URL-адреси
 * - LaTeX / Math формули ($...$ та $$...$$) з розширеною Unicode-типографікою
 * - Повну адаптацію до темної та світлої теми
 */
public final class MarkdownRenderer {

    // Dark Theme Palette
    private static final int COLOR_TEXT_DARK = 0xFFDFE1E5;
    private static final int COLOR_HEADING_DARK = 0xFFFFFFFF;
    private static final int COLOR_BOLD_DARK = 0xFFFFFFFF;
    private static final int COLOR_CODE_BG_DARK = 0xFF2B2D30;
    private static final int COLOR_CODE_TEXT_DARK = 0xFFFFC66D;
    private static final int COLOR_LINK_DARK = 0xFF589DF6;
    private static final int COLOR_BLOCKQUOTE_DARK = 0xFF9DA0A8;
    private static final int COLOR_LIST_DARK = 0xFFA9B7C6;
    private static final int COLOR_HR_DARK = 0xFF43454A;
    private static final int COLOR_IMAGE_DARK = 0xFF6A8759;
    private static final int COLOR_HIGHLIGHT_DARK = 0x55E5C07B;
    private static final int COLOR_KBD_BG_DARK = 0xFF393B40;

    // Light Theme Palette
    private static final int COLOR_TEXT_LIGHT = 0xFF24292F;
    private static final int COLOR_HEADING_LIGHT = 0xFF0969DA;
    private static final int COLOR_BOLD_LIGHT = 0xFF1F2328;
    private static final int COLOR_CODE_BG_LIGHT = 0xFFEFF1F3;
    private static final int COLOR_CODE_TEXT_LIGHT = 0xFF0550AE;
    private static final int COLOR_LINK_LIGHT = 0xFF0969DA;
    private static final int COLOR_BLOCKQUOTE_LIGHT = 0xFF57606A;
    private static final int COLOR_LIST_LIGHT = 0xFF24292F;
    private static final int COLOR_HR_LIGHT = 0xFFD0D7DE;
    private static final int COLOR_IMAGE_LIGHT = 0xFF1A7F37;
    private static final int COLOR_HIGHLIGHT_LIGHT = 0x66FFF59D;
    private static final int COLOR_KBD_BG_LIGHT = 0xFFEAECEF;

    // Callout Alert Colors
    private static final int ALERT_NOTE = 0xFF2196F3;       // Blue
    private static final int ALERT_TIP = 0xFF4CAF50;        // Green
    private static final int ALERT_IMPORTANT = 0xFF9C27B0;  // Purple
    private static final int ALERT_WARNING = 0xFFFF9800;    // Amber
    private static final int ALERT_CAUTION = 0xFFF44336;    // Red

    private MarkdownRenderer() {}

    @NonNull
    public static SpannableStringBuilder render(@NonNull String markdown, boolean dark, @Nullable Typeface tf) {
        return render(markdown, dark, 16, tf);
    }

    @NonNull
    public static SpannableStringBuilder renderInline(@NonNull String text, boolean dark, @Nullable Typeface tf) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        appendInlineFormatted(sb, text, dark, tf);
        return sb;
    }

    @NonNull
    public static SpannableStringBuilder render(@NonNull String markdown, boolean dark, int baseFontSize, @Nullable Typeface tf) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        String[] lines = markdown.split("\n", -1);

        int i = 0;
        while (i < lines.length) {
            String line = lines[i];

            // 1. Fenced Code Block (```lang ... ```)
            if (trimStart(line).startsWith("```")) {
                String fence = trimStart(line);
                String lang = fence.length() > 3 ? fence.substring(3).trim() : "";
                StringBuilder code = new StringBuilder();
                i++;
                while (i < lines.length && !trimStart(lines[i]).startsWith("```")) {
                    code.append(lines[i]).append("\n");
                    i++;
                }
                if (code.length() > 0 && code.charAt(code.length() - 1) == '\n') {
                    code.deleteCharAt(code.length() - 1);
                }
                appendCodeBlock(sb, code.toString(), lang, dark, baseFontSize, tf);
                endBlock(sb);
                i++;
                continue;
            }

            // 2. Empty line
            if (line.trim().isEmpty()) {
                endBlock(sb);
                i++;
                continue;
            }

            // 3. Horizontal rule (---, ***, ___)
            if (isHorizontalRule(line)) {
                appendHR(sb, dark);
                endBlock(sb);
                i++;
                continue;
            }

            // 4. Headings (# H1 - ###### H6)
            int headingLevel = getHeadingLevel(line);
            if (headingLevel > 0) {
                String headingText = line.substring(headingLevel).trim();
                appendHeading(sb, headingText, headingLevel, dark, baseFontSize, tf);
                endBlock(sb);
                i++;
                continue;
            }

            // 5. Blockquotes and GitHub Alerts (> [!NOTE], etc.)
            if (trimStart(line).startsWith(">")) {
                List<String> quoteLines = new ArrayList<>();
                while (i < lines.length && trimStart(lines[i]).startsWith(">")) {
                    String raw = trimStart(lines[i]);
                    String content = raw.length() > 1 && raw.charAt(1) == ' '
                            ? raw.substring(2) : raw.substring(1);
                    quoteLines.add(content);
                    i++;
                }
                appendBlockquoteGroup(sb, quoteLines, dark, baseFontSize, tf);
                endBlock(sb);
                continue;
            }

            // 6. Tables (| col1 | col2 |)
            if (isTableRow(line)) {
                List<String> tableRows = new ArrayList<>();
                while (i < lines.length && isTableRow(lines[i])) {
                    tableRows.add(lines[i].trim());
                    i++;
                }
                appendTable(sb, tableRows, dark, baseFontSize, tf);
                endBlock(sb);
                continue;
            }

            // 7. Task list item (- [ ] or - [x])
            if (isTaskListItem(line)) {
                String content = getTaskListContent(line);
                boolean checked = line.contains("[x]") || line.contains("[X]");
                appendTaskListItem(sb, content, dark, baseFontSize, checked, getIndentLevel(line), tf);
                sb.append("\n");
                i++;
                continue;
            }

            // 8. Unordered list item (- , * , + )
            if (isUnorderedListItem(line)) {
                String content = getUnorderedListContent(line);
                appendUnorderedListItem(sb, content, dark, getIndentLevel(line), tf);
                sb.append("\n");
                i++;
                continue;
            }

            // 9. Ordered list item (1. , 2. )
            if (isOrderedListItem(line)) {
                String content = getOrderedListContent(line);
                String marker = getOrderedListMarker(line);
                appendOrderedListItem(sb, content, marker, dark, getIndentLevel(line), tf);
                sb.append("\n");
                i++;
                continue;
            }

            // 10. Regular Paragraph
            appendParagraph(sb, line, dark, baseFontSize, tf);
            sb.append("\n");
            i++;
        }

        // Clean up excessive trailing newlines
        while (sb.length() > 0 && sb.charAt(sb.length() - 1) == '\n') {
            sb.delete(sb.length() - 1, sb.length());
        }

        return sb;
    }

    /**
     * Closes a block with exactly one blank line after it.
     *
     * <p>Every block used to append {@code "\n\n"} of its own and the blank line
     * that follows it in the source added a third, so a heading and the paragraph
     * under it were separated by three empty lines. Normalising here instead of
     * at each call site means the gap does not depend on how the author spaced
     * their source.</p>
     */
    private static void endBlock(SpannableStringBuilder sb) {
        int trailing = 0;
        for (int i = sb.length() - 1; i >= 0 && sb.charAt(i) == '\n'; i--) trailing++;
        while (trailing < 2) {
            sb.append('\n');
            trailing++;
        }
    }

    // ── Headings ──────────────────────────────────────────────────────────

    private static void appendHeading(SpannableStringBuilder sb, String text, int level,
                                       boolean dark, int baseFontSize, @Nullable Typeface tf) {
        int start = sb.length();
        appendInlineFormatted(sb, text, dark, tf);
        int end = sb.length();

        float scale;
        int color;
        switch (level) {
            case 1: scale = 1.55f; color = dark ? COLOR_HEADING_DARK : COLOR_HEADING_LIGHT; break;
            case 2: scale = 1.35f; color = dark ? COLOR_HEADING_DARK : COLOR_HEADING_LIGHT; break;
            case 3: scale = 1.2f;  color = dark ? COLOR_HEADING_DARK : COLOR_HEADING_LIGHT; break;
            case 4: scale = 1.1f;  color = dark ? COLOR_TEXT_DARK : COLOR_TEXT_LIGHT; break;
            case 5: scale = 1.0f;  color = dark ? COLOR_TEXT_DARK : COLOR_TEXT_LIGHT; break;
            default: scale = 0.9f; color = dark ? COLOR_BLOCKQUOTE_DARK : COLOR_BLOCKQUOTE_LIGHT; break;
        }

        sb.setSpan(new RelativeSizeSpan(scale), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new ForegroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        // H1 and H2 carry a rule underneath, as the HTML preview's stylesheet does.
        if (level == 1 || level == 2) {
            appendRuleLine(sb, dark ? COLOR_HR_DARK : COLOR_HR_LIGHT);
        }
    }

    /**
     * Appends a full-width horizontal rule.
     *
     * <p>Drawn, not typed. These rules used to be a run of forty {@code ─} (and
     * {@code ┈} for H2) characters, which is why they stopped partway across the
     * screen on a wide one, wrapped onto a second line on a narrow one, and made
     * an H2 look dashed. A {@link LineBackgroundSpan} gets the line's real left
     * and right edges, so the rule is exactly as wide as the text column.</p>
     */
    private static void appendRuleLine(SpannableStringBuilder sb, int color) {
        sb.append("\n");
        int start = sb.length();
        // The span needs a character to attach to; a no-break space reserves the
        // line without printing anything. Shrinking it keeps the rule a hairline
        // rather than a full text line of empty space.
        sb.append(" ");
        sb.setSpan(new RuleSpan(color), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new RelativeSizeSpan(0.45f), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    /** Paints one hairline across the full width of the line it covers. */
    private static final class RuleSpan implements LineBackgroundSpan {
        private final int color;
        private final float thickness;

        RuleSpan(int color) {
            this.color = color;
            this.thickness = Math.max(1f,
                    Resources.getSystem().getDisplayMetrics().density);
        }

        @Override
        public void drawBackground(@NonNull Canvas canvas, @NonNull Paint paint,
                                   int left, int right, int top, int baseline, int bottom,
                                   @NonNull CharSequence text, int start, int end,
                                   int lineNumber) {
            int oldColor = paint.getColor();
            Paint.Style oldStyle = paint.getStyle();
            paint.setColor(color);
            paint.setStyle(Paint.Style.FILL);
            float y = (top + bottom) / 2f;
            canvas.drawRect(left, y - thickness / 2f, right, y + thickness / 2f, paint);
            paint.setColor(oldColor);
            paint.setStyle(oldStyle);
        }
    }

    // ── Code Block (inline fallback if not parsed into Part) ─────────────

    private static void appendCodeBlock(SpannableStringBuilder sb, String code, String lang,
                                        boolean dark, int baseFontSize, @Nullable Typeface tf) {
        int start = sb.length();
        SpannableStringBuilder highlighted = CodeSnippetHighlighter.highlight(code, lang, dark, tf);
        sb.append(highlighted);
        int end = sb.length();

        sb.setSpan(new BackgroundColorSpan(dark ? COLOR_CODE_BG_DARK : COLOR_CODE_BG_LIGHT),
                start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new AbsoluteSizeSpan(Math.max(10, baseFontSize - 2), true),
                start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    // ── Blockquotes & GitHub Alerts ──────────────────────────────────────

    private static void appendBlockquoteGroup(SpannableStringBuilder sb, List<String> lines,
                                              boolean dark, int baseFontSize, @Nullable Typeface tf) {
        if (lines.isEmpty()) return;

        String first = lines.get(0).trim();
        int alertColor = 0;
        String alertTitle = null;
        String alertIcon = "";

        if (first.startsWith("[!NOTE]") || first.startsWith("[!INFO]")) {
            alertColor = ALERT_NOTE;
            alertTitle = "NOTE";
            alertIcon = "ℹ️ ";
        } else if (first.startsWith("[!TIP]")) {
            alertColor = ALERT_TIP;
            alertTitle = "TIP";
            alertIcon = "💡 ";
        } else if (first.startsWith("[!IMPORTANT]")) {
            alertColor = ALERT_IMPORTANT;
            alertTitle = "IMPORTANT";
            alertIcon = "❗ ";
        } else if (first.startsWith("[!WARNING]")) {
            alertColor = ALERT_WARNING;
            alertTitle = "WARNING";
            alertIcon = "⚠️ ";
        } else if (first.startsWith("[!CAUTION]") || first.startsWith("[!DANGER]")) {
            alertColor = ALERT_CAUTION;
            alertTitle = "CAUTION";
            alertIcon = "🛑 ";
        }

        int groupStart = sb.length();

        if (alertTitle != null) {
            // Alert Header
            int titleStart = sb.length();
            sb.append("▍ ").append(alertIcon).append(alertTitle).append("\n");
            sb.setSpan(new ForegroundColorSpan(alertColor), titleStart, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new StyleSpan(Typeface.BOLD), titleStart, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

            // Alert Body
            for (int l = 1; l < lines.size(); l++) {
                int lineStart = sb.length();
                sb.append("▍ ");
                appendInlineFormatted(sb, lines.get(l), dark, tf);
                if (l < lines.size() - 1) sb.append("\n");
                sb.setSpan(new ForegroundColorSpan(dark ? COLOR_TEXT_DARK : COLOR_TEXT_LIGHT),
                        lineStart + 2, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        } else {
            // Normal Blockquote
            for (int l = 0; l < lines.size(); l++) {
                int lineStart = sb.length();
                sb.append("▍ ");
                appendInlineFormatted(sb, lines.get(l), dark, tf);
                if (l < lines.size() - 1) sb.append("\n");
                sb.setSpan(new ForegroundColorSpan(dark ? COLOR_BLOCKQUOTE_DARK : COLOR_BLOCKQUOTE_LIGHT),
                        lineStart, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new StyleSpan(Typeface.ITALIC), lineStart + 2, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }

        sb.setSpan(new LeadingMarginSpan.Standard(baseFontSize / 2, 0),
                groupStart, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    // ── Lists ────────────────────────────────────────────────────────────

    private static void appendUnorderedListItem(SpannableStringBuilder sb, String text, boolean dark,
                                                int indent, @Nullable Typeface tf) {
        for (int i = 0; i < indent; i++) sb.append("    ");
        String bullet = indent == 0 ? "•  " : (indent == 1 ? "◦  " : "▪  ");
        int start = sb.length();
        sb.append(bullet);
        sb.setSpan(new ForegroundColorSpan(dark ? COLOR_LINK_DARK : COLOR_HEADING_LIGHT),
                start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        appendInlineFormatted(sb, text, dark, tf);
    }

    private static void appendOrderedListItem(SpannableStringBuilder sb, String text, String marker,
                                              boolean dark, int indent, @Nullable Typeface tf) {
        for (int i = 0; i < indent; i++) sb.append("    ");
        int start = sb.length();
        sb.append(marker).append("  ");
        sb.setSpan(new ForegroundColorSpan(dark ? COLOR_LINK_DARK : COLOR_HEADING_LIGHT),
                start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new StyleSpan(Typeface.BOLD), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        appendInlineFormatted(sb, text, dark, tf);
    }

    private static void appendTaskListItem(SpannableStringBuilder sb, String text, boolean dark,
                                           int baseFontSize, boolean checked, int indent, @Nullable Typeface tf) {
        for (int i = 0; i < indent; i++) sb.append("    ");

        int start = sb.length();
        sb.append(checked ? "☑  " : "☐  ");
        sb.setSpan(new ForegroundColorSpan(checked
                ? (dark ? 0xFF499C54 : 0xFF107C10)
                : (dark ? 0xFF808080 : 0xFF8C8C8C)),
                start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        start = sb.length();
        appendInlineFormatted(sb, text, dark, tf);
        if (checked) {
            sb.setSpan(new StrikethroughSpan(), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new ForegroundColorSpan(dark ? COLOR_BLOCKQUOTE_DARK : COLOR_BLOCKQUOTE_LIGHT),
                    start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    // ── Table (with exact column alignment and monospace grid) ────────────

    private static void appendTable(SpannableStringBuilder sb, List<String> rows, boolean dark,
                                    int baseFontSize, @Nullable Typeface tf) {
        if (rows.isEmpty()) return;

        List<String[]> parsedRows = new ArrayList<>();
        for (String row : rows) {
            if (row.matches("\\|[-:|\\s]+\\|")) continue; // Skip separator line
            parsedRows.add(parseTableRow(row));
        }

        if (parsedRows.isEmpty()) return;

        int numCols = 0;
        for (String[] r : parsedRows) numCols = Math.max(numCols, r.length);
        if (numCols == 0) return;

        int[] colWidths = new int[numCols];
        for (String[] r : parsedRows) {
            for (int c = 0; c < numCols; c++) {
                String val = c < r.length ? r[c].trim() : "";
                colWidths[c] = Math.max(colWidths[c], val.length());
            }
        }
        for (int c = 0; c < numCols; c++) {
            colWidths[c] = Math.max(3, colWidths[c]);
        }

        int tableStart = sb.length();

        // 1. Top border: ┌───┬───┐
        int borderStart = sb.length();
        sb.append("┌");
        for (int c = 0; c < numCols; c++) {
            sb.append("─".repeat(colWidths[c] + 2));
            if (c < numCols - 1) sb.append("┬");
        }
        sb.append("┐\n");
        sb.setSpan(new ForegroundColorSpan(dark ? COLOR_HR_DARK : COLOR_HR_LIGHT),
                borderStart, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        // 2. Header row
        String[] header = parsedRows.get(0);
        int headerStart = sb.length();
        sb.append("│");
        for (int c = 0; c < numCols; c++) {
            String cell = c < header.length ? header[c].trim() : "";
            sb.append(" ").append(cell);
            int pad = colWidths[c] - cell.length() + 1;
            if (pad > 0) sb.append(" ".repeat(pad));
            sb.append("│");
        }
        sb.append("\n");
        sb.setSpan(new ForegroundColorSpan(dark ? COLOR_HEADING_DARK : COLOR_HEADING_LIGHT),
                headerStart, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new StyleSpan(Typeface.BOLD), headerStart, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        // 3. Middle border: ├───┼───┤
        borderStart = sb.length();
        sb.append("├");
        for (int c = 0; c < numCols; c++) {
            sb.append("─".repeat(colWidths[c] + 2));
            if (c < numCols - 1) sb.append("┼");
        }
        sb.append("┤\n");
        sb.setSpan(new ForegroundColorSpan(dark ? COLOR_HR_DARK : COLOR_HR_LIGHT),
                borderStart, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        // 4. Data rows
        for (int r = 1; r < parsedRows.size(); r++) {
            String[] row = parsedRows.get(r);
            int rowStart = sb.length();
            sb.append("│");
            for (int c = 0; c < numCols; c++) {
                String cell = c < row.length ? row[c].trim() : "";
                sb.append(" ").append(cell);
                int pad = colWidths[c] - cell.length() + 1;
                if (pad > 0) sb.append(" ".repeat(pad));
                sb.append("│");
            }
            sb.append("\n");
            sb.setSpan(new ForegroundColorSpan(dark ? COLOR_TEXT_DARK : COLOR_TEXT_LIGHT),
                    rowStart, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        // 5. Bottom border: └───┴───┘
        borderStart = sb.length();
        sb.append("└");
        for (int c = 0; c < numCols; c++) {
            sb.append("─".repeat(colWidths[c] + 2));
            if (c < numCols - 1) sb.append("┴");
        }
        sb.append("┘");
        sb.setSpan(new ForegroundColorSpan(dark ? COLOR_HR_DARK : COLOR_HR_LIGHT),
                borderStart, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        // Enforce Monospace font and slightly smaller font size for the whole table
        if (tf != null) {
            sb.setSpan(new CustomTypefaceSpan(tf), tableStart, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        sb.setSpan(new RelativeSizeSpan(0.88f), tableStart, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private static String[] parseTableRow(String row) {
        String trimmed = row.trim();
        if (trimmed.startsWith("|")) trimmed = trimmed.substring(1);
        if (trimmed.endsWith("|")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        return trimmed.split("\\|", -1);
    }

    private static boolean isTableRow(String line) {
        String trimmed = line.trim();
        return trimmed.startsWith("|") && trimmed.endsWith("|") && trimmed.length() > 2;
    }

    // ── Horizontal Rule ──────────────────────────────────────────────────

    private static void appendHR(SpannableStringBuilder sb, boolean dark) {
        appendRuleLine(sb, dark ? COLOR_HR_DARK : COLOR_HR_LIGHT);
    }

    // ── Paragraph & Inline Formatting ────────────────────────────────────

    private static void appendParagraph(SpannableStringBuilder sb, String text, boolean dark,
                                        int baseFontSize, @Nullable Typeface tf) {
        appendInlineFormatted(sb, text, dark, tf);
    }

    private static void appendInlineFormatted(SpannableStringBuilder sb, String text,
                                              boolean dark, @Nullable Typeface tf) {
        int i = 0;
        int len = text.length();

        while (i < len) {
            char c = text.charAt(i);

            // 1. Inline Code: `...`
            if (c == '`') {
                int end = text.indexOf('`', i + 1);
                if (end > i) {
                    int start = sb.length();
                    sb.append(" ").append(text, i + 1, end).append(" ");
                    int codeEnd = sb.length();
                    if (tf != null) {
                        sb.setSpan(new CustomTypefaceSpan(tf), start, codeEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                    sb.setSpan(new ForegroundColorSpan(dark ? COLOR_CODE_TEXT_DARK : COLOR_CODE_TEXT_LIGHT),
                            start, codeEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    sb.setSpan(new BackgroundColorSpan(dark ? COLOR_CODE_BG_DARK : COLOR_CODE_BG_LIGHT),
                            start, codeEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    sb.setSpan(new RelativeSizeSpan(0.9f), start, codeEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 1;
                    continue;
                }
            }

            // 2. Keyboard tag: <kbd>...</kbd>
            if (text.startsWith("<kbd>", i)) {
                int end = text.indexOf("</kbd>", i + 5);
                if (end > i) {
                    int start = sb.length();
                    sb.append(" [ ").append(text, i + 5, end).append(" ] ");
                    if (tf != null) {
                        sb.setSpan(new CustomTypefaceSpan(tf), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                    sb.setSpan(new BackgroundColorSpan(dark ? COLOR_KBD_BG_DARK : COLOR_KBD_BG_LIGHT),
                            start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    sb.setSpan(new StyleSpan(Typeface.BOLD), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    sb.setSpan(new RelativeSizeSpan(0.85f), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 6;
                    continue;
                }
            }

            // 3. Highlight: ==...== or <mark>...</mark>
            if ((c == '=' && i + 1 < len && text.charAt(i + 1) == '=') || text.startsWith("<mark>", i)) {
                boolean isTag = text.startsWith("<mark>", i);
                int markLen = isTag ? 6 : 2;
                int end = isTag ? text.indexOf("</mark>", i + markLen) : text.indexOf("==", i + markLen);
                if (end > i) {
                    int start = sb.length();
                    sb.append(text, i + markLen, end);
                    sb.setSpan(new BackgroundColorSpan(dark ? COLOR_HIGHLIGHT_DARK : COLOR_HIGHLIGHT_LIGHT),
                            start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + (isTag ? 7 : 2);
                    continue;
                }
            }

            // 4. Bold+Italic: ***...*** or ___...___
            if (i + 2 < len && ((c == '*' && text.charAt(i + 1) == '*' && text.charAt(i + 2) == '*')
                    || (c == '_' && text.charAt(i + 1) == '_' && text.charAt(i + 2) == '_'))) {
                char marker = c;
                int end = findClosingMarker(text, i + 3, marker, 3);
                if (end > 0) {
                    int start = sb.length();
                    sb.append(text, i + 3, end);
                    sb.setSpan(new StyleSpan(Typeface.BOLD_ITALIC), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    sb.setSpan(new ForegroundColorSpan(dark ? COLOR_BOLD_DARK : COLOR_BOLD_LIGHT),
                            start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 3;
                    continue;
                }
            }

            // 5. Bold: **...** or __...__
            if (i + 1 < len && ((c == '*' && text.charAt(i + 1) == '*')
                    || (c == '_' && text.charAt(i + 1) == '_'))) {
                char marker = c;
                int end = findClosingMarker(text, i + 2, marker, 2);
                if (end > 0) {
                    int start = sb.length();
                    sb.append(text, i + 2, end);
                    sb.setSpan(new StyleSpan(Typeface.BOLD), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    sb.setSpan(new ForegroundColorSpan(dark ? COLOR_BOLD_DARK : COLOR_BOLD_LIGHT),
                            start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 2;
                    continue;
                }
            }

            // 6. Italic: *...* or _..._
            if ((c == '*' || c == '_') && i + 1 < len && !Character.isWhitespace(text.charAt(i + 1))) {
                int end = text.indexOf(c, i + 1);
                if (end > i + 1 && end < len && text.charAt(end - 1) != '\\' && text.charAt(end) != '\n') {
                    int start = sb.length();
                    sb.append(text, i + 1, end);
                    sb.setSpan(new StyleSpan(Typeface.ITALIC), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 1;
                    continue;
                }
            }

            // 7. Strikethrough: ~~...~~
            if (c == '~' && i + 1 < len && text.charAt(i + 1) == '~') {
                int end = text.indexOf("~~", i + 2);
                if (end > i + 1) {
                    int start = sb.length();
                    sb.append(text, i + 2, end);
                    sb.setSpan(new StrikethroughSpan(), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 2;
                    continue;
                }
            }

            // 8. Underline: <u>...</u>
            if (text.startsWith("<u>", i)) {
                int end = text.indexOf("</u>", i + 3);
                if (end > i) {
                    int start = sb.length();
                    sb.append(text, i + 3, end);
                    sb.setSpan(new UnderlineSpan(), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 4;
                    continue;
                }
            }

            // 9. Links: [title](url)
            if (c == '[') {
                int bracketEnd = text.indexOf(']', i + 1);
                if (bracketEnd > i && bracketEnd + 1 < len && text.charAt(bracketEnd + 1) == '(') {
                    int parenEnd = text.indexOf(')', bracketEnd + 2);
                    if (parenEnd > bracketEnd) {
                        String linkText = text.substring(i + 1, bracketEnd);
                        String url = text.substring(bracketEnd + 2, parenEnd).trim();
                        int start = sb.length();
                        sb.append(linkText);
                        sb.setSpan(new ForegroundColorSpan(dark ? COLOR_LINK_DARK : COLOR_LINK_LIGHT),
                                start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        sb.setSpan(new UnderlineSpan(), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        if (!url.isEmpty()) {
                            sb.setSpan(new URLSpan(url), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        }
                        i = parenEnd + 1;
                        continue;
                    }
                }
            }

            // 10. Images: ![alt](url)
            if (c == '!' && i + 1 < len && text.charAt(i + 1) == '[') {
                int bracketEnd = text.indexOf(']', i + 2);
                if (bracketEnd > i && bracketEnd + 1 < len && text.charAt(bracketEnd + 1) == '(') {
                    int parenEnd = text.indexOf(')', bracketEnd + 2);
                    if (parenEnd > bracketEnd) {
                        String alt = text.substring(i + 2, bracketEnd);
                        int start = sb.length();
                        sb.append("🖼 ").append(alt.isEmpty() ? "Image" : alt);
                        sb.setSpan(new ForegroundColorSpan(dark ? COLOR_IMAGE_DARK : COLOR_IMAGE_LIGHT),
                                start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        sb.setSpan(new StyleSpan(Typeface.ITALIC), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        i = parenEnd + 1;
                        continue;
                    }
                }
            }

            // 11. LaTeX Display Math: $$...$$
            if (c == '$' && i + 1 < len && text.charAt(i + 1) == '$' && (i == 0 || text.charAt(i - 1) != '\\')) {
                int end = text.indexOf("$$", i + 2);
                if (end > i) {
                    String math = text.substring(i + 2, end);
                    sb.append("\n  ");
                    renderLatex(sb, math, dark, tf);
                    sb.append("\n");
                    i = end + 2;
                    continue;
                }
            }

            // 12. LaTeX Inline Math: $...$
            if (c == '$' && (i == 0 || text.charAt(i - 1) != '\\')) {
                int end = text.indexOf('$', i + 1);
                if (end > i && (end + 1 >= len || text.charAt(end + 1) != '$')) {
                    String math = text.substring(i + 1, end);
                    renderLatex(sb, math, dark, tf);
                    i = end + 1;
                    continue;
                }
            }

            // 13. Auto-detected raw URL: https://... or http://...
            if (text.startsWith("https://", i) || text.startsWith("http://", i)) {
                int end = i;
                while (end < len && !Character.isWhitespace(text.charAt(end))
                        && text.charAt(end) != ')' && text.charAt(end) != ']' && text.charAt(end) != '>') {
                    end++;
                }
                String url = text.substring(i, end);
                int start = sb.length();
                sb.append(url);
                sb.setSpan(new ForegroundColorSpan(dark ? COLOR_LINK_DARK : COLOR_LINK_LIGHT),
                        start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new UnderlineSpan(), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new URLSpan(url), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                i = end;
                continue;
            }

            sb.append(c);
            i++;
        }
    }

    // ── LaTeX / Math Renderer with Unicode Conversion ─────────────────────

    private static void renderLatex(SpannableStringBuilder sb, String math, boolean dark, @Nullable Typeface tf) {
        int start = sb.length();
        int i = 0;
        int len = math.length();

        while (i < len) {
            char c = math.charAt(i);

            // LaTeX command: \alpha, \sum, \frac, etc.
            if (c == '\\') {
                i++;
                if (i >= len) break;
                int cmdStart = i;
                while (i < len && Character.isLetter(math.charAt(i))) i++;
                String cmd = math.substring(cmdStart, i);

                // Check Greek letters
                String greek = getGreekLetter(cmd);
                if (greek != null) {
                    sb.append(greek);
                    continue;
                }

                // Check Math symbols
                String symbol = getLatexSymbol(cmd);
                if (!symbol.isEmpty() && !symbol.startsWith("\\")) {
                    sb.append(symbol);
                    continue;
                }

                if (cmd.equals("frac") || cmd.equals("dfrac") || cmd.equals("tfrac")) {
                    String num = extractBraceGroup(math, i);
                    i += num != null ? num.length() + 2 : 0;
                    String den = extractBraceGroup(math, i);
                    i += den != null ? den.length() + 2 : 0;
                    sb.append("(").append(num != null ? num : "?").append(" / ")
                            .append(den != null ? den : "?").append(")");
                    continue;
                }

                if (cmd.equals("sqrt")) {
                    String arg = extractBraceGroup(math, i);
                    i += arg != null ? arg.length() + 2 : 0;
                    sb.append("√(").append(arg != null ? arg : "").append(")");
                    continue;
                }

                sb.append(symbol);
                continue;
            }

            // Superscript: ^2 or ^{10}
            if (c == '^') {
                i++;
                if (i < len && math.charAt(i) == '{') {
                    String group = extractBraceGroup(math, i - 1);
                    if (group != null) {
                        sb.append(toSuperscript(group));
                        i += group.length() + 2;
                        continue;
                    }
                } else if (i < len) {
                    sb.append(toSuperscript(String.valueOf(math.charAt(i))));
                    i++;
                    continue;
                }
            }

            // Subscript: _2 or _{10}
            if (c == '_') {
                i++;
                if (i < len && math.charAt(i) == '{') {
                    String group = extractBraceGroup(math, i - 1);
                    if (group != null) {
                        sb.append(toSubscript(group));
                        i += group.length() + 2;
                        continue;
                    }
                } else if (i < len) {
                    sb.append(toSubscript(String.valueOf(math.charAt(i))));
                    i++;
                    continue;
                }
            }

            // Ignore raw braces in math
            if (c == '{' || c == '}') {
                i++;
                continue;
            }

            sb.append(c);
            i++;
        }

        int end = sb.length();
        if (end > start) {
            if (tf != null) {
                sb.setSpan(new CustomTypefaceSpan(tf), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            sb.setSpan(new ForegroundColorSpan(dark ? 0xFF6897BB : 0xFF0550AE),
                    start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new StyleSpan(Typeface.ITALIC), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private static String extractBraceGroup(String str, int startIdx) {
        int open = str.indexOf('{', startIdx);
        if (open < 0) return null;
        int depth = 1;
        for (int i = open + 1; i < str.length(); i++) {
            char ch = str.charAt(i);
            if (ch == '{') depth++;
            else if (ch == '}') {
                depth--;
                if (depth == 0) return str.substring(open + 1, i);
            }
        }
        return null;
    }

    private static String toSuperscript(String text) {
        StringBuilder sb = new StringBuilder();
        for (char c : text.toCharArray()) {
            switch (c) {
                case '0': sb.append('⁰'); break;
                case '1': sb.append('¹'); break;
                case '2': sb.append('²'); break;
                case '3': sb.append('³'); break;
                case '4': sb.append('⁴'); break;
                case '5': sb.append('⁵'); break;
                case '6': sb.append('⁶'); break;
                case '7': sb.append('⁷'); break;
                case '8': sb.append('⁸'); break;
                case '9': sb.append('⁹'); break;
                case '+': sb.append('⁺'); break;
                case '-': sb.append('⁻'); break;
                case '=': sb.append('⁼'); break;
                case '(': sb.append('⁽'); break;
                case ')': sb.append('⁾'); break;
                case 'n': sb.append('ⁿ'); break;
                case 'i': sb.append('ⁱ'); break;
                case 'x': sb.append('ˣ'); break;
                default: sb.append('^').append(c); break;
            }
        }
        return sb.toString();
    }

    private static String toSubscript(String text) {
        StringBuilder sb = new StringBuilder();
        for (char c : text.toCharArray()) {
            switch (c) {
                case '0': sb.append('₀'); break;
                case '1': sb.append('₁'); break;
                case '2': sb.append('₂'); break;
                case '3': sb.append('₃'); break;
                case '4': sb.append('₄'); break;
                case '5': sb.append('₅'); break;
                case '6': sb.append('₆'); break;
                case '7': sb.append('₇'); break;
                case '8': sb.append('₈'); break;
                case '9': sb.append('₉'); break;
                case '+': sb.append('₊'); break;
                case '-': sb.append('₋'); break;
                case '=': sb.append('₌'); break;
                case '(': sb.append('₍'); break;
                case ')': sb.append('₎'); break;
                case 'a': sb.append('ₐ'); break;
                case 'e': sb.append('ₑ'); break;
                case 'i': sb.append('ᵢ'); break;
                case 'o': sb.append('ₒ'); break;
                case 'u': sb.append('ᵤ'); break;
                case 'x': sb.append('ₓ'); break;
                case 'n': sb.append('ₙ'); break;
                default: sb.append('_').append(c); break;
            }
        }
        return sb.toString();
    }

    private static String getLatexSymbol(String cmd) {
        switch (cmd) {
            case "sum": return "∑";
            case "prod": return "∏";
            case "int": return "∫";
            case "iint": return "∬";
            case "oint": return "∮";
            case "lim": return "lim";
            case "partial": return "∂";
            case "nabla": return "∇";
            case "infty": return "∞";
            case "pm": return "±";
            case "mp": return "∓";
            case "times": return "×";
            case "div": return "÷";
            case "cdot": return "·";
            case "leq": case "le": return "≤";
            case "geq": case "ge": return "≥";
            case "neq": case "ne": return "≠";
            case "approx": return "≈";
            case "equiv": return "≡";
            case "subset": return "⊂";
            case "supset": return "⊃";
            case "subseteq": return "⊆";
            case "supseteq": return "⊇";
            case "in": return "∈";
            case "notin": return "∉";
            case "cup": return "∪";
            case "cap": return "∩";
            case "emptyset": return "∅";
            case "forall": return "∀";
            case "exists": return "∃";
            case "neg": return "¬";
            case "land": return "∧";
            case "lor": return "∨";
            case "leftarrow": case "gets": return "←";
            case "rightarrow": case "to": return "→";
            case "leftrightarrow": return "↔";
            case "Leftarrow": return "⇐";
            case "Rightarrow": case "implies": return "⇒";
            case "Leftrightarrow": case "iff": return "⇔";
            default: return "\\" + cmd;
        }
    }

    private static String getGreekLetter(String cmd) {
        switch (cmd) {
            case "alpha": return "α";
            case "beta": return "β";
            case "gamma": return "γ";
            case "delta": return "δ";
            case "epsilon": case "varepsilon": return "ε";
            case "zeta": return "ζ";
            case "eta": return "η";
            case "theta": case "vartheta": return "θ";
            case "iota": return "ι";
            case "kappa": return "κ";
            case "lambda": return "λ";
            case "mu": return "μ";
            case "nu": return "ν";
            case "xi": return "ξ";
            case "omicron": return "ο";
            case "pi": case "varpi": return "π";
            case "rho": case "varrho": return "ρ";
            case "sigma": case "varsigma": return "σ";
            case "tau": return "τ";
            case "upsilon": return "υ";
            case "phi": case "varphi": return "φ";
            case "chi": return "χ";
            case "psi": return "ψ";
            case "omega": return "ω";
            case "Gamma": return "Γ";
            case "Delta": return "Δ";
            case "Theta": return "Θ";
            case "Lambda": return "Λ";
            case "Xi": return "Ξ";
            case "Pi": return "Π";
            case "Sigma": return "Σ";
            case "Phi": return "Φ";
            case "Psi": return "Ψ";
            case "Omega": return "Ω";
            default: return null;
        }
    }

    // ── Helper Checkers ──────────────────────────────────────────────────

    private static int findClosingMarker(String text, int start, char marker, int count) {
        for (int i = start; i <= text.length() - count; i++) {
            boolean match = true;
            for (int j = 0; j < count; j++) {
                if (text.charAt(i + j) != marker) {
                    match = false;
                    break;
                }
            }
            if (match) return i;
        }
        return -1;
    }

    private static int getHeadingLevel(String line) {
        int level = 0;
        for (int i = 0; i < line.length() && i < 6; i++) {
            if (line.charAt(i) == '#') level++;
            else break;
        }
        if (level > 0 && level < line.length() && line.charAt(level) == ' ') return level;
        return 0;
    }

    private static boolean isHorizontalRule(String line) {
        String trimmed = line.trim();
        if (trimmed.length() < 3) return false;
        char c = trimmed.charAt(0);
        if (c != '-' && c != '*' && c != '_') return false;
        int count = 0;
        for (int i = 0; i < trimmed.length(); i++) {
            if (trimmed.charAt(i) == c) count++;
            else if (trimmed.charAt(i) != ' ') return false;
        }
        return count >= 3;
    }

    private static boolean isUnorderedListItem(String line) {
        String trimmed = trimStart(line);
        return (trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ "));
    }

    private static String getUnorderedListContent(String line) {
        String trimmed = trimStart(line);
        if (trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ")) {
            return trimmed.substring(2);
        }
        return trimmed;
    }

    private static boolean isOrderedListItem(String line) {
        String trimmed = trimStart(line);
        int dot = trimmed.indexOf('.');
        if (dot <= 0 || dot > 4 || dot + 1 >= trimmed.length()
                || trimmed.charAt(dot + 1) != ' ') return false;
        for (int i = 0; i < dot; i++) {
            if (!Character.isDigit(trimmed.charAt(i))) return false;
        }
        return true;
    }

    private static String getOrderedListContent(String line) {
        String trimmed = trimStart(line);
        int dot = trimmed.indexOf('.');
        return dot > 0 ? trimmed.substring(dot + 2) : trimmed;
    }

    private static String getOrderedListMarker(String line) {
        String trimmed = trimStart(line);
        int dot = trimmed.indexOf('.');
        return dot > 0 ? trimmed.substring(0, dot + 1) : "1.";
    }

    private static int getIndentLevel(String line) {
        int spaces = 0;
        for (int i = 0; i < line.length(); i++) {
            if (line.charAt(i) == ' ') spaces++;
            else if (line.charAt(i) == '\t') spaces += 4;
            else break;
        }
        // Two spaces is one level: that is the content offset of "- ", and the
        // most common way people nest. Four spaces under a two-space parent is
        // still one level, as it is on GitHub, so both house styles nest once.
        return spaces < 2 ? 0 : Math.min(4, 1 + (spaces - 2) / 4);
    }

    private static String trimStart(String s) {
        int i = 0;
        while (i < s.length() && s.charAt(i) == ' ') i++;
        return s.substring(i);
    }

    private static boolean isTaskListItem(String line) {
        String trimmed = trimStart(line);
        return (trimmed.startsWith("- [ ] ") || trimmed.startsWith("- [x] ")
                || trimmed.startsWith("- [X] ") || trimmed.startsWith("* [ ] ")
                || trimmed.startsWith("* [x] ") || trimmed.startsWith("* [X] "));
    }

    private static String getTaskListContent(String line) {
        String trimmed = trimStart(line);
        if (trimmed.startsWith("- [ ] ") || trimmed.startsWith("* [ ] ")
                || trimmed.startsWith("- [x] ") || trimmed.startsWith("- [X] ")
                || trimmed.startsWith("* [x] ") || trimmed.startsWith("* [X] ")) {
            return trimmed.substring(6);
        }
        return trimmed;
    }
}
