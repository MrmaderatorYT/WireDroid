package com.ccs.wiredroid.util.languages.ast;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/**
 * The AST highlighter's whole reason to exist is that it can tell a field from a
 * local from a parameter — something a per-line pattern cannot. That claim is
 * only checkable here.
 *
 * <p>It was not checkable on a device: the two highlighters render this file
 * near-identically, and a screenshot comparison of them came out byte-identical,
 * which is how an earlier "verified" claim about semantic colouring turned out to
 * be worthless.</p>
 */
public class JavaAstParserTest {

    private static final String DEMO =
            "package com.demo;\n"
            + "\n"
            + "/** Doc. */\n"
            + "public class Demo {\n"
            + "    private int counter = 0;\n"
            + "    private String label = \"field\";\n"
            + "\n"
            + "    public int bump(int amount) {\n"
            + "        int local = amount * 2;\n"
            + "        counter += local;\n"
            + "        return counter; // trailing\n"
            + "    }\n"
            + "}\n";

    private static List<JavaToken> roles(String source) {
        List<JavaToken> tokens = new JavaLexer(source).tokenize();
        new JavaAstParser(tokens).parse();
        return tokens;
    }

    /** The first token with this text, or null. */
    private static JavaToken first(List<JavaToken> tokens, String text) {
        for (JavaToken t : tokens) {
            if (text.equals(t.text)) return t;
        }
        return null;
    }

    @Test
    public void lexerEndsWithEof() {
        List<JavaToken> tokens = new JavaLexer(DEMO).tokenize();
        assertTrue("lexer must produce tokens", tokens.size() > 10);
        assertEquals(JavaToken.Kind.EOF, tokens.get(tokens.size() - 1).kind);
    }

    @Test
    public void everyTokenGetsARole() {
        for (JavaToken t : roles(DEMO)) {
            if (t.kind == JavaToken.Kind.EOF) continue;
            assertNotNull("token '" + t.text + "' (" + t.kind + ") has no role", t.role);
        }
    }

    @Test
    public void keywordsAreKeywords() {
        List<JavaToken> tokens = roles(DEMO);
        assertEquals(SemanticRole.KEYWORD, first(tokens, "public").role);
        assertEquals(SemanticRole.KEYWORD, first(tokens, "class").role);
        assertEquals(SemanticRole.KEYWORD, first(tokens, "return").role);
    }

    @Test
    public void literalsAndCommentsAreClassified() {
        List<JavaToken> tokens = roles(DEMO);
        JavaToken str = first(tokens, "\"field\"");
        assertNotNull("the string literal should be one token", str);
        assertEquals(SemanticRole.STRING, str.role);

        boolean sawDoc = false, sawLine = false;
        for (JavaToken t : tokens) {
            if (t.kind == JavaToken.Kind.JAVADOC) sawDoc = true;
            if (t.kind == JavaToken.Kind.LINE_COMMENT) sawLine = true;
        }
        assertTrue("the javadoc block should be one token", sawDoc);
        assertTrue("the trailing // comment should be one token", sawLine);
    }

    @Test
    public void theTypeNameIsATypeNotAPlainIdentifier() {
        JavaToken demo = first(roles(DEMO), "Demo");
        assertNotNull(demo);
        assertEquals("the declared class name should be TYPE", SemanticRole.TYPE, demo.role);
    }

    @Test
    public void aFieldIsNotALocalIsNotAParameter() {
        List<JavaToken> tokens = roles(DEMO);
        // This is the distinction the whole AST highlighter exists for. If these
        // three collapse to one role, it is doing nothing a lexer could not.
        assertEquals("field", SemanticRole.FIELD, first(tokens, "counter").role);
        assertEquals("local", SemanticRole.LOCAL, first(tokens, "local").role);
        assertEquals("parameter", SemanticRole.PARAMETER, first(tokens, "amount").role);
    }

    @Test
    public void methodDeclarationIsAMethod() {
        assertEquals(SemanticRole.METHOD, first(roles(DEMO), "bump").role);
    }

    @Test
    public void declaredNamesAreOfferedToCompletion() {
        List<JavaToken> tokens = new JavaLexer(DEMO).tokenize();
        JavaAstParser parser = new JavaAstParser(tokens);
        parser.parse();
        assertTrue("declaredTypes should contain Demo", parser.declaredTypes.contains("Demo"));
        assertTrue("declaredMethods should contain bump", parser.declaredMethods.contains("bump"));
    }

    @Test
    public void unterminatedStringDoesNotHang() {
        // A half-typed line is the normal state of a file being edited.
        roles("class A { String s = \"oops\n}");
    }

    @Test
    public void unbalancedBracesDoNotThrow() {
        roles("class A { void f() { if (true) { } ");
    }

    @Test
    public void emptySourceIsHandled() {
        List<JavaToken> tokens = roles("");
        assertEquals(1, tokens.size());
        assertEquals(JavaToken.Kind.EOF, tokens.get(0).kind);
    }
}
