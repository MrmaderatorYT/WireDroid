package com.ccs.wiredroid.util.languages;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * How the three new languages are tokenised.
 *
 * <p>Every case here is a place where one language's rules would give the wrong
 * answer for another: an apostrophe that opens a string in Groovy and a
 * character in Scala, a semicolon that starts a comment in Clojure and means
 * nothing in the other two, a hyphen that belongs to a Clojure name and
 * separates two tokens elsewhere.</p>
 */
public class LexicalScannerTest {

    /** The kinds reported for a line, one per token start, in order. */
    private static List<LexicalScanner.Kind> kinds(String source, LexicalRules rules, int line) {
        List<LexicalScanner.Kind> out = new ArrayList<>();
        LexicalScanner.scan(source, rules, new LexicalScanner.Sink() {
            @Override public void span(int at, int column, LexicalScanner.Kind kind) {
                if (at == line) out.add(kind);
            }
            @Override public void endLine(int at) { }
        }, null);
        return out;
    }

    /** The kind covering a given position, i.e. the last span starting at or before it. */
    private static LexicalScanner.Kind at(String source, LexicalRules rules, int line, int column) {
        final LexicalScanner.Kind[] found = { LexicalScanner.Kind.NORMAL };
        LexicalScanner.scan(source, rules, new LexicalScanner.Sink() {
            @Override public void span(int l, int c, LexicalScanner.Kind kind) {
                if (l == line && c <= column) found[0] = kind;
            }
            @Override public void endLine(int l) { }
        }, null);
        return found[0];
    }

    private static LexicalRules scala() {
        return rulesOf(new ScalaLanguage());
    }

    private static LexicalRules groovy() {
        return rulesOf(new GroovyLanguage());
    }

    private static LexicalRules clojure() {
        return rulesOf(new ClojureLanguage());
    }

    /** Each language keeps its rules private; rebuilt here from the same spec. */
    private static LexicalRules rulesOf(RuleBasedLanguage language) {
        return language.rulesForTesting();
    }

    // ── Scala ────────────────────────────────────────────────────────────────

    @Test
    public void scalaKeywordsAndAnnotations() {
        LexicalRules rules = scala();
        assertEquals(LexicalScanner.Kind.KEYWORD, at("object Main", rules, 0, 0));
        assertEquals(LexicalScanner.Kind.NORMAL, at("object Main", rules, 0, 7));
        assertEquals(LexicalScanner.Kind.KEYWORD, at("@main def run() = ()", rules, 0, 0));
    }

    @Test
    public void scalaTripleQuotedStringSpansLines() {
        LexicalRules rules = scala();
        String source = "val s = \"\"\"one\ntwo\"\"\"\nval n = 1";
        // The middle line is inside the string, so nothing on it starts a token.
        assertTrue(kinds(source, rules, 1).isEmpty());
        assertEquals("the line after it is code again",
                LexicalScanner.Kind.KEYWORD, at(source, rules, 2, 0));
    }

    @Test
    public void scalaTreatsApostropheAsACharacter() {
        LexicalRules rules = scala();
        assertEquals(LexicalScanner.Kind.LITERAL, at("val c = 'x'", rules, 0, 8));
        assertEquals("the code after it is not swallowed",
                LexicalScanner.Kind.KEYWORD, at("val c = 'x'\nval d = 2", rules, 1, 0));
    }

    // ── Groovy ───────────────────────────────────────────────────────────────

    @Test
    public void groovyApostropheOpensAString() {
        LexicalRules rules = groovy();
        // 'hello world' is one string. Read as a character literal it would end
        // at the closing quote of "world" and mis-colour everything between.
        String source = "def s = 'hello world'\ndef n = 1";
        assertEquals(LexicalScanner.Kind.LITERAL, at(source, rules, 0, 8));
        assertEquals(LexicalScanner.Kind.KEYWORD, at(source, rules, 1, 0));
    }

    @Test
    public void groovyDollarIsPartOfAName() {
        LexicalRules rules = groovy();
        assertEquals(LexicalScanner.Kind.NORMAL, at("def a$b = 1", rules, 0, 4));
    }

    // ── Clojure ──────────────────────────────────────────────────────────────

    @Test
    public void clojureSemicolonStartsAComment() {
        LexicalRules rules = clojure();
        assertEquals(LexicalScanner.Kind.COMMENT, at("; a note", rules, 0, 0));
    }

    @Test
    public void aSemicolonInsideAStringIsNotAComment() {
        LexicalRules rules = clojure();
        String source = "(println \"a ; b\") (def x 1)";
        // If the string were not recognised first, everything from the semicolon
        // on would be a comment and the second form would vanish.
        assertEquals(LexicalScanner.Kind.KEYWORD, at(source, rules, 0, 19));
    }

    @Test
    public void clojureKeywordsAreShapesNotWords() {
        LexicalRules rules = clojure();
        assertEquals(LexicalScanner.Kind.LITERAL, at("{:some-key 1}", rules, 0, 1));
    }

    @Test
    public void aHyphenatedNameIsOneToken() {
        LexicalRules rules = clojure();
        // "defn-" is its own keyword; split at the hyphen it would read as
        // "defn" followed by an operator.
        List<LexicalScanner.Kind> line = kinds("(defn- f [])", clojure(), 0);
        assertEquals(LexicalScanner.Kind.OPERATOR, line.get(0));
        assertEquals(LexicalScanner.Kind.KEYWORD, line.get(1));
    }

    @Test
    public void clojureCharacterLiteralsAreNamed() {
        LexicalRules rules = clojure();
        assertEquals(LexicalScanner.Kind.LITERAL, at("(str \\newline)", rules, 0, 5));
        // The name is consumed with the backslash, so the closing paren is still
        // punctuation rather than part of the literal.
        assertEquals(LexicalScanner.Kind.OPERATOR, at("(str \\a)", rules, 0, 7));
    }

    @Test
    public void clojureHasNoBlockComments() {
        LexicalRules rules = clojure();
        // /* is division and a splat in Clojure, not the start of a comment.
        assertEquals(LexicalScanner.Kind.NORMAL, at("(/ 6 3)", rules, 0, 1));
    }

    // ── Shared ───────────────────────────────────────────────────────────────

    @Test
    public void anUnterminatedStringStopsAtTheLineEnd() {
        // Typing an opening quote must not repaint the rest of the file as a
        // string on every keystroke until the pair is closed.
        String source = "def s = \"oops\ndef n = 1";
        assertEquals(LexicalScanner.Kind.KEYWORD, at(source, groovy(), 1, 0));
    }

    @Test
    public void aCommentMarkerInsideAStringIsJustText() {
        String source = "val url = \"http://example.com\"\nval n = 1";
        assertEquals(LexicalScanner.Kind.KEYWORD, at(source, scala(), 1, 0));
    }

    @Test
    public void blockCommentsSpanLines() {
        String source = "/* one\n   two */\nval n = 1";
        assertTrue(kinds(source, scala(), 1).isEmpty());
        assertEquals(LexicalScanner.Kind.KEYWORD, at(source, scala(), 2, 0));
    }

    @Test
    public void scanningStopsWhenCancelled() {
        List<LexicalScanner.Kind> seen = new ArrayList<>();
        LexicalScanner.scan("val a = 1\nval b = 2", scala(), new LexicalScanner.Sink() {
            @Override public void span(int line, int column, LexicalScanner.Kind kind) {
                seen.add(kind);
            }
            @Override public void endLine(int line) { }
        }, () -> true);
        assertTrue("a cancelled scan must not keep working", seen.isEmpty());
    }
}
