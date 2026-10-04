package com.ccs.wiredroid.util.languages;

/** Scala source ({@code .scala}, {@code .sc}). */
public class ScalaLanguage extends RuleBasedLanguage {

    static final String[] KEYWORDS = {
            "abstract", "case", "catch", "class", "def", "do", "else", "enum", "export",
            "extends", "false", "final", "finally", "for", "forSome", "given", "if",
            "implicit", "import", "lazy", "match", "new", "null", "object", "override",
            "package", "private", "protected", "return", "sealed", "super", "then", "this",
            "throw", "trait", "true", "try", "type", "using", "val", "var", "while", "with",
            "yield", "opaque", "inline", "transparent", "derives", "extension", "end",
            "Unit", "Int", "Long", "Double", "Float", "Boolean", "String", "Any", "AnyRef",
            "AnyVal", "Nothing", "Option", "Some", "None", "List", "Seq", "Map", "Set"
    };

    private static final LexicalRules RULES = LexicalRules.builder(KEYWORDS)
            .tripleQuotedStrings()
            .charLiteral(LexicalRules.CharLiteral.QUOTED)
            .annotations()
            // Scala names may end in operators, and `_` matters as a name of its
            // own; both are ordinary symbol characters here.
            .extraSymbolChars("_")
            .build();

    public ScalaLanguage() {
        super(RULES, KEYWORDS);
    }
}
