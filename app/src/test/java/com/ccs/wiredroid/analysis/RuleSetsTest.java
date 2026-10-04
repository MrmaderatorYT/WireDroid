package com.ccs.wiredroid.analysis;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.util.List;

/**
 * The rule sets as a whole: they must say something about code that deserves it,
 * and stay quiet about code that does not.
 *
 * <p>Deliberately loose about *which* rule fires. Asserting exact messages would
 * make every wording change a test failure, and the wording is translated. What
 * is worth pinning is the shape: findings on bad code, silence on good code, and
 * no exceptions escaping on half-written code.</p>
 */
public class RuleSetsTest {

    private static List<ProblemItem> scan(String source) {
        return StaticAnalyzer.analyzeSource(null, new File("T.java"), source);
    }

    private static final String CLEAN =
            "package com.example;\n"
            + "\n"
            + "/** A small, tidy class. */\n"
            + "public final class Tidy {\n"
            + "\n"
            + "    private final String name;\n"
            + "\n"
            + "    /** Creates one. */\n"
            + "    public Tidy(String name) {\n"
            + "        this.name = name;\n"
            + "    }\n"
            + "\n"
            + "    /** Returns the name. */\n"
            + "    public String name() {\n"
            + "        return name;\n"
            + "    }\n"
            + "}\n";

    @Test
    public void aSystemOutIsWorthMentioning() {
        assertTrue(!scan("class A { void f() { System.out.println(\"x\"); } }").isEmpty());
    }

    @Test
    public void anEmptyCatchIsWorthMentioning() {
        assertTrue(!scan("class A { void f() { try { g(); } catch (Exception e) {} } }").isEmpty());
    }

    @Test
    public void aTidyClassIsQuietOrNearlySo() {
        List<ProblemItem> found = scan(CLEAN);
        // Style rules will always have an opinion; what must not happen is a wall
        // of findings on code that is fine.
        assertTrue("a tidy class produced " + found.size() + " findings: " + found,
                found.size() <= 4);
    }

    @Test
    public void noErrorsAreInventedByStaticRules() {
        // Compile errors come from ECJ. The text rules must never claim one,
        // or the Problems panel starts showing errors that do not exist.
        for (ProblemItem p : scan(CLEAN)) {
            assertTrue("a static rule reported ERROR: " + p.message,
                    p.severity != ProblemItem.Severity.ERROR);
        }
    }

    @Test
    public void halfWrittenCodeDoesNotThrow() {
        // What a file looks like most of the time while it is being typed.
        scan("public class ");
        scan("class A { void f( ");
        scan("class A { String s = \"unterminated");
        scan("}}}}");
        scan("/* unterminated comment");
        scan("class A { void f() { if (x) { for (;;) { switch (y) { case 1: ");
    }

    @Test
    public void everyFindingPointsAtARealLine() {
        // Only the line is checked. The message cannot be: these rules build their
        // text with ctx.getString(...) and fall back to "" when the Context is
        // null, which is exactly how they are called from here. A blank message is
        // therefore expected in a unit test and never happens in the app, where
        // the application Context is always passed.
        for (ProblemItem p : scan("class A { void f() { System.out.println(1); int x = 12345; } }")) {
            assertTrue("line must be positive, got " + p.line, p.line > 0);
        }
    }
}
