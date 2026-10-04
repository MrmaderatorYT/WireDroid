package com.ccs.wiredroid.util.languages.ast;

import java.util.ArrayList;
import java.util.List;

import io.github.rosemoe.sora.lang.analysis.SimpleAnalyzeManager;
import io.github.rosemoe.sora.lang.completion.IdentifierAutoComplete;
import io.github.rosemoe.sora.lang.styling.CodeBlock;
import io.github.rosemoe.sora.lang.styling.MappedSpans;
import io.github.rosemoe.sora.lang.styling.Styles;
import io.github.rosemoe.sora.lang.styling.TextStyle;
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme;

/**
 * Turns the semantic roles produced by {@link JavaAstParser} into editor spans,
 * and derives folding blocks from the same token stream.
 *
 * <p>Spans have to be emitted in ascending line-then-column order, so this walks
 * the document line by line and clips each token to the line it lands on. That
 * also means multi-line tokens — block comments, Javadoc, text blocks — get a
 * span on every line they cover.</p>
 */
public class AstJavaAnalyzeManager extends SimpleAnalyzeManager<Void> {

    /** Identifiers harvested from the parse, offered to completion. */
    private final IdentifierAutoComplete.SyncIdentifiers identifiers =
            new IdentifierAutoComplete.SyncIdentifiers();

    public IdentifierAutoComplete.SyncIdentifiers getIdentifiers() {
        return identifiers;
    }

    @Override
    protected Styles analyze(StringBuilder text, Delegate<Void> delegate) {
        List<JavaToken> tokens;
        JavaAstParser parser;
        try {
            tokens = new JavaLexer(text).tokenize();
            if (delegate.isCancelled()) return null;
            parser = new JavaAstParser(tokens);
            parser.parse();
        } catch (Throwable e) {
            // Never let a parser defect blank out the editor; fall back to a
            // document with no highlighting rather than crashing the analyzer.
            return plainStyles(text);
        }
        if (delegate.isCancelled()) return null;

        publishIdentifiers(parser);

        MappedSpans.Builder builder = new MappedSpans.Builder(
                Math.max(16, countLines(text) + 1));
        long normal = TextStyle.makeStyle(EditorColorScheme.TEXT_NORMAL);

        int[] lineStarts = computeLineStarts(text);
        int lineCount = lineStarts.length;
        int tokenIndex = 0;

        for (int line = 0; line < lineCount; line++) {
            if (delegate.isCancelled()) return null;

            int lineStart = lineStarts[line];
            int lineEnd = (line + 1 < lineCount) ? lineStarts[line + 1] - 1 : text.length();

            // Rewind to the first token that reaches this line. Tokens are sorted
            // by start offset, so a single forward cursor is enough except for
            // multi-line tokens, which we detect by their end offset.
            while (tokenIndex > 0 && tokens.get(tokenIndex - 1).end > lineStart) {
                tokenIndex--;
            }
            while (tokenIndex < tokens.size() && tokens.get(tokenIndex).end <= lineStart
                    && tokens.get(tokenIndex).kind != JavaToken.Kind.EOF) {
                tokenIndex++;
            }

            boolean wroteAny = false;
            int cursor = tokenIndex;
            int lastEmittedColumn = -1;
            int previousTokenEnd = lineStart;

            while (cursor < tokens.size()) {
                JavaToken t = tokens.get(cursor);
                if (t.kind == JavaToken.Kind.EOF) break;
                if (t.start >= lineEnd) break;
                if (t.end <= lineStart) { cursor++; continue; }

                // Whitespace before this token returns to the normal style.
                if (t.start > previousTokenEnd) {
                    int gapColumn = Math.max(0, previousTokenEnd - lineStart);
                    if (gapColumn > lastEmittedColumn) {
                        builder.addIfNeeded(line, gapColumn, normal);
                        lastEmittedColumn = gapColumn;
                        wroteAny = true;
                    }
                }

                int column = Math.max(0, t.start - lineStart);
                if (column > lastEmittedColumn) {
                    builder.addIfNeeded(line, column, styleOf(t));
                    lastEmittedColumn = column;
                    wroteAny = true;
                }
                previousTokenEnd = Math.max(previousTokenEnd, Math.min(t.end, lineEnd));
                cursor++;
            }

            // Trailing whitespace on the line.
            if (previousTokenEnd < lineEnd) {
                int gapColumn = Math.max(0, previousTokenEnd - lineStart);
                if (gapColumn > lastEmittedColumn) {
                    builder.addIfNeeded(line, gapColumn, normal);
                    wroteAny = true;
                }
            }
            if (!wroteAny) {
                builder.addIfNeeded(line, 0, normal);
            }
            builder.determine(line);
        }

        Styles styles = new Styles(builder.build(), true);
        for (CodeBlock block : computeBlocks(tokens, delegate)) {
            styles.addCodeBlock(block);
        }
        styles.finishBuilding();
        return styles;
    }

    /** A style with the role's colour, weight and slant. */
    private static long styleOf(JavaToken token) {
        SemanticRole role = token.role != null ? token.role : SemanticRole.PLAIN;
        return TextStyle.makeStyle(role.colorId, 0, role.bold, role.italic, false);
    }

    /** Folding regions from balanced braces, brackets and parentheses. */
    private List<CodeBlock> computeBlocks(List<JavaToken> tokens, Delegate<Void> delegate) {
        List<CodeBlock> blocks = new ArrayList<>();
        ArrayList<JavaToken> stack = new ArrayList<>();

        for (JavaToken t : tokens) {
            if (delegate.isCancelled()) return blocks;
            if (t.kind != JavaToken.Kind.OPERATOR) continue;
            if (t.is("{") || t.is("[")) {
                stack.add(t);
            } else if (t.is("}") || t.is("]")) {
                if (stack.isEmpty()) continue;
                JavaToken open = stack.remove(stack.size() - 1);
                if (open.line == t.line) continue;      // single-line, nothing to fold
                CodeBlock block = new CodeBlock();
                block.startLine = open.line;
                block.startColumn = open.column;
                block.endLine = t.line;
                block.endColumn = t.column;
                blocks.add(block);
            }
        }
        return blocks;
    }

    /** Feeds the types and methods declared in this file to auto-completion. */
    private void publishIdentifiers(JavaAstParser parser) {
        try {
            identifiers.clear();
            for (String name : parser.declaredTypes) identifiers.identifierIncrease(name);
            for (String name : parser.declaredMethods) identifiers.identifierIncrease(name);
        } catch (Throwable ignored) {
            // Completion is a nicety; never let it break highlighting.
        }
    }

    /** A span-per-line document with no colouring, used if the parser fails. */
    private Styles plainStyles(StringBuilder text) {
        MappedSpans.Builder builder = new MappedSpans.Builder();
        long normal = TextStyle.makeStyle(EditorColorScheme.TEXT_NORMAL);
        int lines = countLines(text);
        for (int line = 0; line <= lines; line++) {
            builder.addIfNeeded(line, 0, normal);
            builder.determine(line);
        }
        return new Styles(builder.build());
    }

    private static int countLines(CharSequence text) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') count++;
        }
        return count;
    }

    /** Offset of the first character of each line. */
    private static int[] computeLineStarts(CharSequence text) {
        int lines = countLines(text) + 1;
        int[] starts = new int[lines];
        int at = 1;
        starts[0] = 0;
        for (int i = 0; i < text.length() && at < lines; i++) {
            if (text.charAt(i) == '\n') starts[at++] = i + 1;
        }
        return starts;
    }
}
