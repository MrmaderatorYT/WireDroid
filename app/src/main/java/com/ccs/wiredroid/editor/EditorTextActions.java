package com.ccs.wiredroid.editor;

import com.ccs.wiredroid.util.languages.LanguageFiles;

import java.io.File;
import java.util.Locale;

import io.github.rosemoe.sora.text.Content;
import io.github.rosemoe.sora.text.Cursor;
import io.github.rosemoe.sora.widget.CodeEditor;

/**
 * The line-level edits every IDE has and a text editor does not.
 *
 * <p>Duplicating a line, moving one up or down, commenting a block out: each is
 * a handful of {@link Content} calls, and each is wrong in a different way if
 * the selection, the last line or the indentation is not thought about. They are
 * here rather than in the activity because the activity is already long and none
 * of this needs it — a {@link CodeEditor} is the whole context.</p>
 *
 * <p>Every method that changes more than one thing wraps the change in a batch
 * edit, so undo takes it back in one press rather than several.</p>
 */
public final class EditorTextActions {

    private EditorTextActions() {}

    // ── Line actions ──────────────────────────────────────────

    /**
     * Copies the caret's line (or every line the selection touches) below itself.
     *
     * <p>The caret stays on the copy, which is where the next edit is going.</p>
     */
    public static void duplicateLines(CodeEditor editor) {
        Content text = content(editor);
        if (text == null) return;
        Cursor cursor = text.getCursor();
        int first = cursor.getLeftLine();
        int last = cursor.getRightLine();

        StringBuilder block = new StringBuilder();
        for (int line = first; line <= last; line++) {
            block.append(text.getLineString(line));
            block.append('\n');
        }

        text.beginBatchEdit();
        try {
            // Inserted at the start of the line after the block, so the copy
            // lands below without disturbing the original's own line ending.
            int insertLine = last + 1;
            if (insertLine >= text.getLineCount()) {
                // No line after the last one: make the break part of the insert.
                text.insert(last, text.getColumnCount(last), "\n" + block.substring(0, block.length() - 1));
            } else {
                text.insert(insertLine, 0, block.toString());
            }
        } finally {
            text.endBatchEdit();
        }
        int offset = last - first + 1;
        editor.setSelection(Math.min(first + offset, text.getLineCount() - 1), cursor.getLeftColumn());
    }

    /** Swaps the caret's line with the one above; the selection travels with it. */
    public static void moveLinesUp(CodeEditor editor) {
        Content text = content(editor);
        if (text == null) return;
        Cursor cursor = text.getCursor();
        int first = cursor.getLeftLine();
        if (first == 0) return;
        moveBlock(editor, text, first, cursor.getRightLine(), -1);
    }

    /** Swaps the caret's line with the one below. */
    public static void moveLinesDown(CodeEditor editor) {
        Content text = content(editor);
        if (text == null) return;
        Cursor cursor = text.getCursor();
        int last = cursor.getRightLine();
        if (last >= text.getLineCount() - 1) return;
        moveBlock(editor, text, cursor.getLeftLine(), last, 1);
    }

    /**
     * Moves a run of lines by one position.
     *
     * <p>Done as "take the neighbour out, put it back on the other side" rather
     * than rewriting the block: the block may be long, and the neighbour is one
     * line whichever way it goes.</p>
     */
    private static void moveBlock(CodeEditor editor, Content text, int first, int last, int direction) {
        int neighbour = direction < 0 ? first - 1 : last + 1;
        String moved = text.getLineString(neighbour);
        int caretLine = text.getCursor().getLeftLine();
        int caretColumn = text.getCursor().getLeftColumn();

        text.beginBatchEdit();
        try {
            if (direction < 0) {
                // Delete the line above, reinsert it after the block.
                text.delete(neighbour, 0, neighbour + 1, 0);
                int target = last;                       // shifted up by the delete
                insertLine(text, target, moved);
            } else {
                text.delete(neighbour, 0, neighbour + 1, 0);
                insertLine(text, first, moved);
            }
        } finally {
            text.endBatchEdit();
        }
        int newLine = Math.max(0, Math.min(caretLine + direction, text.getLineCount() - 1));
        editor.setSelection(newLine, Math.min(caretColumn, text.getColumnCount(newLine)));
    }

    /** Puts {@code line} back as a whole line at {@code at}, last line included. */
    private static void insertLine(Content text, int at, String line) {
        if (at >= text.getLineCount()) {
            int lastLine = text.getLineCount() - 1;
            text.insert(lastLine, text.getColumnCount(lastLine), "\n" + line);
        } else {
            text.insert(at, 0, line + "\n");
        }
    }

    // ── Comments ──────────────────────────────────────────────

    /**
     * Comments the selected lines out, or uncomments them if they already are.
     *
     * <p>The whole block follows the first non-blank line: if that one is
     * commented, everything is uncommented, and otherwise everything is
     * commented. Mixed blocks therefore end up uniformly commented, which is
     * what pressing the key twice in a row should be able to undo exactly.</p>
     *
     * <p>The marker goes at the first non-blank column of the shallowest line,
     * so the block keeps its shape instead of every marker landing at column
     * zero.</p>
     */
    public static void toggleLineComment(CodeEditor editor, File file) {
        Content text = content(editor);
        if (text == null) return;
        String marker = lineCommentMarker(file);
        if (marker == null) return;

        Cursor cursor = text.getCursor();
        int first = cursor.getLeftLine();
        int last = cursor.getRightLine();

        boolean uncomment = true;
        int indent = Integer.MAX_VALUE;
        boolean anyContent = false;
        for (int line = first; line <= last; line++) {
            String content = text.getLineString(line);
            String trimmed = content.trim();
            if (trimmed.isEmpty()) continue;
            anyContent = true;
            indent = Math.min(indent, content.indexOf(trimmed.charAt(0)));
            if (!trimmed.startsWith(marker)) uncomment = false;
        }
        if (!anyContent) return;
        if (indent == Integer.MAX_VALUE) indent = 0;

        text.beginBatchEdit();
        try {
            for (int line = first; line <= last; line++) {
                String content = text.getLineString(line);
                String trimmed = content.trim();
                if (trimmed.isEmpty()) continue;
                if (uncomment) {
                    int at = content.indexOf(marker);
                    if (at < 0) continue;
                    // Also eats the single space a comment marker usually has
                    // after it, so commenting and uncommenting round-trips.
                    int end = at + marker.length();
                    if (end < content.length() && content.charAt(end) == ' ') end++;
                    text.delete(line, at, line, end);
                } else {
                    text.insert(line, Math.min(indent, content.length()), marker + " ");
                }
            }
        } finally {
            text.endBatchEdit();
        }
    }

    /** The line comment marker for a file's language, or null when it has none. */
    public static String lineCommentMarker(File file) {
        String name = file == null ? "" : file.getName().toLowerCase(Locale.ROOT);
        if (name.endsWith(".clj") || name.endsWith(".cljc")) return ";;";
        if (name.endsWith(".py") || name.endsWith(".sh") || name.endsWith(".yml")
                || name.endsWith(".yaml") || name.endsWith(".properties")
                || name.endsWith(".gitignore")) {
            return "#";
        }
        if (name.endsWith(".sql")) return "--";
        if (name.endsWith(".xml") || name.endsWith(".html") || name.endsWith(".htm")) {
            // Block-only syntax; a line marker would produce invalid markup.
            return null;
        }
        // Java, Kotlin, Scala, Groovy, C, C++, JSON5, Gradle: all //.
        return "//";
    }

    // ── Typing helpers ────────────────────────────────────────

    /** The closing half for an opening bracket or quote, or null for anything else. */
    public static String closingFor(char opening) {
        switch (opening) {
            case '(': return ")";
            case '[': return "]";
            case '{': return "}";
            case '<': return ">";
            case '"': return "\"";
            case '\'': return "'";
            case '`': return "`";
            default: return null;
        }
    }

    /**
     * Wraps the selection in a pair instead of replacing it.
     *
     * <p>Typing a quote with text selected means "quote this" everywhere else;
     * only a plain text field reads it as "throw that away and type a quote".
     * The selection is kept selected afterwards, so a second key wraps again.</p>
     *
     * @return true when the selection was wrapped and the key is spent
     */
    public static boolean surroundSelection(CodeEditor editor, char opening) {
        String closing = closingFor(opening);
        if (closing == null) return false;
        Content text = content(editor);
        if (text == null) return false;
        Cursor cursor = text.getCursor();
        if (!cursor.isSelected()) return false;

        int startLine = cursor.getLeftLine();
        int startColumn = cursor.getLeftColumn();
        int endLine = cursor.getRightLine();
        int endColumn = cursor.getRightColumn();

        text.beginBatchEdit();
        try {
            // The end first: inserting there does not move the start, while the
            // reverse order would shift the end by one on a single-line selection.
            text.insert(endLine, endColumn, closing);
            text.insert(startLine, startColumn, String.valueOf(opening));
        } finally {
            text.endBatchEdit();
        }
        int newEndColumn = endLine == startLine ? endColumn + 1 : endColumn;
        editor.setSelectionRegion(startLine, startColumn + 1, endLine, newEndColumn);
        return true;
    }

    /**
     * Deletes both halves of an empty pair when Backspace is pressed between them.
     *
     * <p>{@code (|)} becomes nothing, not {@code )}. The leftover closing bracket
     * is the price of auto-insertion, and this is the other half of that bargain.</p>
     *
     * @return true when a pair was removed and the key is spent
     */
    public static boolean deleteEmptyPair(CodeEditor editor) {
        Content text = content(editor);
        if (text == null) return false;
        Cursor cursor = text.getCursor();
        if (cursor.isSelected()) return false;

        int line = cursor.getLeftLine();
        int column = cursor.getLeftColumn();
        if (column == 0 || column >= text.getColumnCount(line)) return false;

        char before = text.charAt(line, column - 1);
        char after = text.charAt(line, column);
        String closing = closingFor(before);
        if (closing == null || closing.length() != 1 || closing.charAt(0) != after) return false;

        text.delete(line, column - 1, line, column + 1);
        return true;
    }

    // ── Symbol bar ────────────────────────────────────────────

    /**
     * The symbols worth having one tap away for a given file.
     *
     * <p>A Java file needs a semicolon and braces; an XML file needs angle
     * brackets and {@code ="}, and never a semicolon. One fixed row cannot serve
     * both, and the row is small enough that the wrong half of it is wasted.</p>
     */
    public static String[] contextualSymbols(File file) {
        String name = file == null ? "" : file.getName().toLowerCase(Locale.ROOT);
        if (name.endsWith(".xml") || name.endsWith(".html") || name.endsWith(".htm")
                || name.endsWith(".svg")) {
            return new String[]{ "<", ">", "</", "/>", "=\"", "\"", "/", "-", ":" };
        }
        if (name.endsWith(".md") || name.endsWith(".markdown")) {
            return new String[]{ "#", "*", "_", "`", "[", "]", "(", ")", "-", ">" };
        }
        if (name.endsWith(".json")) {
            return new String[]{ "{", "}", "[", "]", "\"", ":", ",", "-" };
        }
        if (LanguageFiles.isClojure(name)) {
            return new String[]{ "(", ")", "[", "]", "{", "}", "\"", ":", ";", "#" };
        }
        if (name.endsWith(".py") || name.endsWith(".sh")) {
            return new String[]{ ":", "(", ")", "[", "]", "\"", "'", "=", "#", "$" };
        }
        if (name.endsWith(".sql")) {
            return new String[]{ "*", ",", "(", ")", "'", "=", ";", "%", "_" };
        }
        // Java, Kotlin, Scala, Groovy, C, C++ and anything unrecognised.
        return new String[]{ ";", "{", "}", "(", ")", "[", "]", "\"", ".", "=", "->", "@" };
    }

    private static Content content(CodeEditor editor) {
        if (editor == null || !editor.isEditable()) return null;
        Content text = editor.getText();
        return text == null || text.getCursor() == null ? null : text;
    }
}
