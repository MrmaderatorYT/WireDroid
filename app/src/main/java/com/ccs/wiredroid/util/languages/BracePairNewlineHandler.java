package com.ccs.wiredroid.util.languages;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.github.rosemoe.sora.lang.smartEnter.NewlineHandleResult;
import io.github.rosemoe.sora.lang.smartEnter.NewlineHandler;
import io.github.rosemoe.sora.lang.styling.Styles;
import io.github.rosemoe.sora.text.CharPosition;
import io.github.rosemoe.sora.text.Content;

/**
 * Enter pressed between a bracket and its closing partner opens a block.
 *
 * <p>Typing {@code {} auto-inserts {@code }}, so the caret sits between the two.
 * Without a handler the editor only indents the new line and leaves the closing
 * bracket sitting right after the caret:</p>
 *
 * <pre>
 * void setName(String name) {
 *     |}
 * </pre>
 *
 * <p>This puts the closing bracket on its own line at the original indent and
 * leaves the caret on the indented line between them, which is what every other
 * editor does and what the code has to be turned into by hand otherwise.</p>
 *
 * <p>Only the "caret is between the pair" case is handled. Plain indenting after
 * an opening bracket already works through {@code getIndentAdvance}, and taking
 * that over here would be a second implementation of it to keep in step.</p>
 */
public final class BracePairNewlineHandler implements NewlineHandler {

    private final boolean useTab;

    public BracePairNewlineHandler(boolean useTab) {
        this.useTab = useTab;
    }

    @Override
    public boolean matchesRequirement(@NonNull Content text, @NonNull CharPosition position,
                                      @Nullable Styles style) {
        try {
            String line = text.getLineString(position.line);
            int column = Math.min(position.column, line.length());
            char open = lastNonSpace(line, 0, column);
            char close = firstNonSpace(line, column);
            return (open == '{' && close == '}')
                    || (open == '(' && close == ')')
                    || (open == '[' && close == ']');
        } catch (Exception e) {
            return false;
        }
    }

    @NonNull
    @Override
    public NewlineHandleResult handleNewline(@NonNull Content text, @NonNull CharPosition position,
                                             @Nullable Styles style, int tabSize) {
        String line = text.getLineString(position.line);
        String indent = leadingWhitespace(line);
        String unit = useTab ? "\t" : spaces(Math.max(tabSize, 1));

        String inserted = "\n" + indent + unit + "\n" + indent;
        // Counted back from the end of the inserted text, so the caret lands at
        // the end of the indented middle line rather than beside the bracket.
        return new NewlineHandleResult(inserted, indent.length() + 1);
    }

    private static char lastNonSpace(String line, int from, int to) {
        for (int i = to - 1; i >= from; i--) {
            char c = line.charAt(i);
            if (c != ' ' && c != '\t') return c;
        }
        return 0;
    }

    private static char firstNonSpace(String line, int from) {
        for (int i = from; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c != ' ' && c != '\t') return c;
        }
        return 0;
    }

    private static String leadingWhitespace(String line) {
        int i = 0;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) i++;
        return line.substring(0, i);
    }

    private static String spaces(int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append(' ');
        return sb.toString();
    }
}
