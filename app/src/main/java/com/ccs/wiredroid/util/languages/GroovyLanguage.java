package com.ccs.wiredroid.util.languages;

/**
 * Groovy source ({@code .groovy}, {@code .gvy}, {@code .gy}).
 *
 * <p>Not the same as {@code GradleLanguage}: a build script has its own DSL
 * vocabulary and gets highlighted for that, while this is the general
 * language.</p>
 */
public class GroovyLanguage extends RuleBasedLanguage {

    static final String[] KEYWORDS = {
            "abstract", "as", "assert", "boolean", "break", "byte", "case", "catch", "char",
            "class", "const", "continue", "def", "default", "do", "double", "else", "enum",
            "extends", "false", "final", "finally", "float", "for", "goto", "if", "implements",
            "import", "in", "instanceof", "int", "interface", "long", "native", "new", "null",
            "package", "private", "protected", "public", "return", "short", "static", "strictfp",
            "super", "switch", "synchronized", "this", "threadsafe", "throw", "throws",
            "trait", "transient", "true", "try", "var", "void", "volatile", "while",
            "it", "println", "print", "each", "with", "collect", "findAll", "inject"
    };

    private static final LexicalRules RULES = LexicalRules.builder(KEYWORDS)
            .tripleQuotedStrings()
            // In Groovy '...' is a plain string, not a character — treating it as
            // a character literal would leave every apostrophe-quoted string
            // ending at the first newline.
            .singleQuotedStrings()
            .charLiteral(LexicalRules.CharLiteral.NONE)
            .annotations()
            .extraSymbolChars("_$")
            .build();

    public GroovyLanguage() {
        super(RULES, KEYWORDS);
    }
}
