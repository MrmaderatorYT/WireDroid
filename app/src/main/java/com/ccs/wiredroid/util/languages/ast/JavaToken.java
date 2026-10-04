package com.ccs.wiredroid.util.languages.ast;

/**
 * A single lexical token with the semantic role the parser later assigns to it.
 *
 * <p>Positions are absolute character offsets into the source; the line/column
 * pair is precomputed by the lexer so span emission needs no second scan.</p>
 */
public final class JavaToken {

    /** Lexical category, decided purely by the lexer. */
    public enum Kind {
        KEYWORD,
        IDENTIFIER,
        NUMBER,
        STRING,
        TEXT_BLOCK,
        CHAR,
        LINE_COMMENT,
        BLOCK_COMMENT,
        JAVADOC,
        /** Punctuation and operators, including braces and brackets. */
        OPERATOR,
        /** The {@code @} that introduces an annotation. */
        AT,
        EOF
    }

    public final Kind kind;
    public final String text;
    /** Inclusive start offset. */
    public final int start;
    /** Exclusive end offset. */
    public final int end;
    /** Zero-based line of {@link #start}. */
    public final int line;
    /** Zero-based column of {@link #start}. */
    public final int column;

    /** Assigned by {@link JavaAstParser}; drives the colour that is emitted. */
    public SemanticRole role;

    JavaToken(Kind kind, String text, int start, int end, int line, int column) {
        this.kind = kind;
        this.text = text;
        this.start = start;
        this.end = end;
        this.line = line;
        this.column = column;
        this.role = SemanticRole.defaultFor(kind);
    }

    /** True when this token is the given punctuation or operator. */
    public boolean is(String symbol) {
        return kind == Kind.OPERATOR && text.equals(symbol);
    }

    /** True when this token is the given keyword. */
    public boolean isKeyword(String keyword) {
        return kind == Kind.KEYWORD && text.equals(keyword);
    }

    public boolean isIdentifier() {
        return kind == Kind.IDENTIFIER;
    }

    @Override
    public String toString() {
        return kind + "(" + text + ")@" + line + ":" + column;
    }
}
