package com.ccs.wiredroid.util.languages;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * What one language's tokens look like, for the shared highlighter.
 *
 * <p>The languages already here each carry their own copy of the same scanning
 * loop — comments, strings, identifiers, numbers — differing only in a keyword
 * list and a quote style. Three more copies of that loop would be three more
 * places to fix the next time a string form is handled wrongly, so the new
 * languages describe their tokens instead and share one scanner.</p>
 *
 * <p>Immutable, and cheap enough to build once per language class.</p>
 */
public final class LexicalRules {

    /** How a language spells a single-character literal. */
    public enum CharLiteral {
        /** {@code 'c'} — Java, Scala, Groovy. */
        QUOTED,
        /** {@code \c}, {@code \newline} — Clojure. */
        BACKSLASH,
        /** The language has none. */
        NONE
    }

    public final Set<String> keywords;
    public final String lineComment;
    /** Null when the language has no block comments. */
    public final String blockCommentStart;
    public final String blockCommentEnd;
    public final boolean tripleQuotedStrings;
    public final boolean singleQuotedStrings;
    public final CharLiteral charLiteral;
    public final boolean annotations;
    /**
     * Marks a self-evaluating name, e.g. Clojure's {@code :keyword}, or 0.
     *
     * <p>Separate from {@link #keywords} because it is a shape rather than a
     * list: every {@code :name} is one, and no list could hold them all.</p>
     */
    public final char keywordSigil;
    /** Symbol characters beyond {@link Character#isJavaIdentifierPart}. */
    public final String extraSymbolChars;

    private LexicalRules(Builder builder) {
        this.keywords = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(builder.keywords)));
        this.lineComment = builder.lineComment;
        this.blockCommentStart = builder.blockCommentStart;
        this.blockCommentEnd = builder.blockCommentEnd;
        this.tripleQuotedStrings = builder.tripleQuotedStrings;
        this.singleQuotedStrings = builder.singleQuotedStrings;
        this.charLiteral = builder.charLiteral;
        this.annotations = builder.annotations;
        this.keywordSigil = builder.keywordSigil;
        this.extraSymbolChars = builder.extraSymbolChars;
    }

    /** Whether {@code c} may appear inside an identifier in this language. */
    public boolean isSymbolPart(char c) {
        return Character.isJavaIdentifierPart(c) || extraSymbolChars.indexOf(c) >= 0;
    }

    /** Whether {@code c} may start an identifier in this language. */
    public boolean isSymbolStart(char c) {
        if (Character.isDigit(c)) return false;
        return Character.isJavaIdentifierStart(c) || extraSymbolChars.indexOf(c) >= 0;
    }

    public static Builder builder(String... keywords) {
        return new Builder(keywords);
    }

    public static final class Builder {
        private final String[] keywords;
        private String lineComment = "//";
        private String blockCommentStart = "/*";
        private String blockCommentEnd = "*/";
        private boolean tripleQuotedStrings = false;
        private boolean singleQuotedStrings = false;
        private CharLiteral charLiteral = CharLiteral.QUOTED;
        private boolean annotations = false;
        private char keywordSigil = 0;
        private String extraSymbolChars = "";

        private Builder(String[] keywords) {
            this.keywords = keywords;
        }

        public Builder lineComment(String token) {
            this.lineComment = token;
            return this;
        }

        public Builder blockComment(String start, String end) {
            this.blockCommentStart = start;
            this.blockCommentEnd = end;
            return this;
        }

        public Builder noBlockComment() {
            this.blockCommentStart = null;
            this.blockCommentEnd = null;
            return this;
        }

        public Builder tripleQuotedStrings() {
            this.tripleQuotedStrings = true;
            return this;
        }

        /** {@code '...'} is a string, not a character — Groovy and Clojure differ here. */
        public Builder singleQuotedStrings() {
            this.singleQuotedStrings = true;
            return this;
        }

        public Builder charLiteral(CharLiteral style) {
            this.charLiteral = style;
            return this;
        }

        public Builder annotations() {
            this.annotations = true;
            return this;
        }

        public Builder keywordSigil(char sigil) {
            this.keywordSigil = sigil;
            return this;
        }

        public Builder extraSymbolChars(String chars) {
            this.extraSymbolChars = chars;
            return this;
        }

        public LexicalRules build() {
            return new LexicalRules(this);
        }
    }
}
