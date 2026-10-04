package com.ccs.wiredroid.util.languages.ast;

import io.github.rosemoe.sora.widget.schemes.EditorColorScheme;

/**
 * What a token *means*, as opposed to what it looks like. This is the part a
 * regex highlighter cannot produce: whether {@code foo} is a type, a method, a
 * field, a local or an unresolved name depends on the surrounding tree.
 */
public enum SemanticRole {

    KEYWORD(EditorColorScheme.KEYWORD, true, false),
    /** Class, interface, enum, record or annotation type name. */
    TYPE(EditorColorScheme.IDENTIFIER_NAME, false, false),
    /** Method declaration or call. */
    METHOD(EditorColorScheme.FUNCTION_NAME, false, false),
    /** Instance or static field. */
    FIELD(EditorColorScheme.IDENTIFIER_VAR, false, false),
    /** Local variable declared in a block. */
    LOCAL(EditorColorScheme.TEXT_NORMAL, false, false),
    /** Method, constructor, catch or lambda parameter. */
    PARAMETER(EditorColorScheme.IDENTIFIER_VAR, false, true),
    /** {@code @Annotation}, including the {@code @}. */
    ANNOTATION(EditorColorScheme.ANNOTATION, false, false),
    /** A {@code label:} target of {@code break}/{@code continue}. */
    LABEL(EditorColorScheme.ATTRIBUTE_NAME, false, true),
    STRING(EditorColorScheme.LITERAL, false, false),
    NUMBER(EditorColorScheme.LITERAL, false, false),
    COMMENT(EditorColorScheme.COMMENT, false, true),
    /** Javadoc — same colour as a comment but not italic, so tags stay legible. */
    DOC(EditorColorScheme.COMMENT, false, false),
    OPERATOR(EditorColorScheme.OPERATOR, false, false),
    /** An identifier the parser could not resolve to anything more specific. */
    PLAIN(EditorColorScheme.TEXT_NORMAL, false, false);

    /** Colour slot in {@link EditorColorScheme}. */
    public final int colorId;
    public final boolean bold;
    public final boolean italic;

    SemanticRole(int colorId, boolean bold, boolean italic) {
        this.colorId = colorId;
        this.bold = bold;
        this.italic = italic;
    }

    /** The role a token starts with, before the parser refines it. */
    static SemanticRole defaultFor(JavaToken.Kind kind) {
        switch (kind) {
            case KEYWORD:       return KEYWORD;
            case NUMBER:        return NUMBER;
            case STRING:
            case TEXT_BLOCK:
            case CHAR:          return STRING;
            case LINE_COMMENT:
            case BLOCK_COMMENT: return COMMENT;
            case JAVADOC:       return DOC;
            case OPERATOR:
            case AT:            return OPERATOR;
            case IDENTIFIER:
            default:            return PLAIN;
        }
    }
}
