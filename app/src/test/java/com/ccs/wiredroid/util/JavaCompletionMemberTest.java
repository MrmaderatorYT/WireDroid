package com.ccs.wiredroid.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Resolving the type on the left of a dot.
 *
 * <p>The buffer is searched before the classpath, so the buffer has to be able
 * to say "not mine". When it could not, asking for {@code java.util.Random}
 * returned a fabricated declaration filled with the members of whatever class
 * was being edited, and reflection — the only thing that knows what
 * {@code nextInt} is — was never reached.</p>
 */
public class JavaCompletionMemberTest {

    private static final String SOURCE =
            "package com.ccs.test;\n"
          + "import java.util.Random;\n"
          + "public class App {\n"
          + "    private int id;\n"
          + "    public void setName(String name) { }\n"
          + "    public int getId() { return id; }\n"
          + "}\n";

    @Test
    public void aTypeNotDeclaredHereIsNotClaimed() {
        assertNull("the buffer must not answer for a library type",
                JavaReflectionCompletion.parseClassDeclarationFromSource(SOURCE, "Random"));
        assertNull(JavaReflectionCompletion.parseClassDeclarationFromSource(SOURCE, "String"));
    }

    @Test
    public void aTypeDeclaredHereIsParsed() {
        JavaReflectionCompletion.ClassDeclaration decl =
                JavaReflectionCompletion.parseClassDeclarationFromSource(SOURCE, "App");
        assertNotNull(decl);
        assertEquals("App", decl.simpleName);
        assertEquals("com.ccs.test", decl.packageName);
    }

    @Test
    public void declaredTypeOfALocalVariableIsFound() {
        String body = SOURCE.replace("private int id;",
                "private int id;\n    void f() { Random random = new Random(); }");
        assertEquals("Random", JavaReflectionCompletion.findDeclaredType(body, "random"));
    }

    @Test
    public void inheritedDeclarationsAreStillFollowed() {
        String child = "package p;\npublic class Cub extends Animal {\n    void a() { }\n}\n";
        JavaReflectionCompletion.ClassDeclaration decl =
                JavaReflectionCompletion.parseClassDeclarationFromSource(child, "Cub");
        assertNotNull(decl);
        assertEquals("Animal", decl.superClassName);
    }

    @Test
    public void memberAccessIsRecognisedFromTheCaret() {
        assertTrue(memberAccess("        rng.n", 13));
        assertTrue(memberAccess("        rng.", 12));
        assertTrue(memberAccess("Math.ab", 7));
        assertFalse(memberAccess("        rng", 11));
        assertFalse(memberAccess("int x = 1;", 10));
        // A dot that is not the start of a member name, e.g. a decimal literal
        // already closed off by other characters.
        assertFalse(memberAccess("double d = 1.5 + x", 18));
    }

    private static boolean memberAccess(String line, int column) {
        io.github.rosemoe.sora.text.Content c = new io.github.rosemoe.sora.text.Content(line);
        return JavaReflectionCompletion.isMemberAccess(
                new io.github.rosemoe.sora.text.ContentReference(c),
                c.getIndexer().getCharPosition(0, column));
    }
}
