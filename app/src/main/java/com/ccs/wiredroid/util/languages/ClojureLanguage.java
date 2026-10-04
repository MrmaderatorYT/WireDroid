package com.ccs.wiredroid.util.languages;

/** Clojure source ({@code .clj}, {@code .cljs}, {@code .cljc}, {@code .edn}). */
public class ClojureLanguage extends RuleBasedLanguage {

    static final String[] KEYWORDS = {
            "def", "defn", "defn-", "defmacro", "defmulti", "defmethod", "defprotocol",
            "defrecord", "deftype", "definterface", "defstruct", "declare", "ns",
            "fn", "let", "letfn", "if", "if-let", "if-not", "if-some", "when", "when-let",
            "when-not", "when-first", "cond", "condp", "case", "do", "doto", "loop", "recur",
            "for", "doseq", "dotimes", "while", "try", "catch", "finally", "throw",
            "quote", "var", "set!", "new", "monitor-enter", "monitor-exit",
            "require", "import", "use", "refer", "in-ns", "binding", "with-open",
            "true", "false", "nil", "and", "or", "not", "apply", "map", "filter", "reduce",
            "assoc", "dissoc", "conj", "cons", "first", "rest", "next", "count", "seq",
            "atom", "swap!", "reset!", "deref", "future", "delay", "lazy-seq", "partial",
            "comp", "juxt", "->", "->>", "some->", "some->>", "as->", "cond->", "cond->>"
    };

    private static final LexicalRules RULES = LexicalRules.builder(KEYWORDS)
            .lineComment(";")
            // Clojure has no /* */; a form is commented out with #_ or the
            // (comment ...) macro, both of which are ordinary reader syntax.
            .noBlockComment()
            .charLiteral(LexicalRules.CharLiteral.BACKSLASH)
            // :keyword is a shape, not a word — no list could enumerate them.
            .keywordSigil(':')
            // A Clojure name is far wider than a Java identifier: earmuffs,
            // hyphens, and a trailing ? or ! are all part of it.
            .extraSymbolChars("-*+!_'?<>=/.&%$#")
            .build();

    public ClojureLanguage() {
        super(RULES, KEYWORDS);
    }
}
