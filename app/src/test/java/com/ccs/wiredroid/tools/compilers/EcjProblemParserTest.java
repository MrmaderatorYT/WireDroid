package com.ccs.wiredroid.tools.compilers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.ccs.wiredroid.analysis.ProblemItem;

import org.junit.Test;

import java.util.List;

/**
 * The bridge between the compiler's text output and the Problems panel.
 *
 * <p>Everything the user sees about a compile error goes through here, and it is
 * pure string work, so there is no reason for it to be untested. The one case
 * that matters most is the last: unrecognised output must still surface as a
 * finding, because an empty panel is indistinguishable from a clean file — which
 * is exactly how a broken analysis stayed invisible.</p>
 */
public class EcjProblemParserTest {

    private static final String ECJ_OUTPUT =
            "----------\n"
            + "1. ERROR in /data/user/0/com.ccs.wiredroid/cache/Broken.java (at line 5)\n"
            + "\tString s = 123;\n"
            + "\t           ^^^\n"
            + "Type mismatch: cannot convert from int to String\n"
            + "----------\n"
            + "2. ERROR in /data/user/0/com.ccs.wiredroid/cache/Broken.java (at line 6)\n"
            + "\treturn s\n"
            + "\t       ^\n"
            + "Syntax error, insert \";\" to complete BlockStatements\n"
            + "----------\n"
            + "2 problems (2 errors)\n";

    @Test
    public void findsEveryReportedProblem() {
        List<ProblemItem> items = EcjProblemParser.parse(ECJ_OUTPUT, null);
        assertEquals("both errors should be picked up", 2, items.size());
    }

    @Test
    public void keepsLineNumbers() {
        List<ProblemItem> items = EcjProblemParser.parse(ECJ_OUTPUT, null);
        assertEquals(5, items.get(0).line);
        assertEquals(6, items.get(1).line);
    }

    @Test
    public void severityIsRead() {
        for (ProblemItem p : EcjProblemParser.parse(ECJ_OUTPUT, null)) {
            assertEquals(ProblemItem.Severity.ERROR, p.severity);
        }
    }

    @Test
    public void warningsAreNotErrors() {
        String out = "1. WARNING in /tmp/A.java (at line 3)\n"
                + "\tint unused = 1;\n"
                + "\t    ^^^^^^\n"
                + "The value of the local variable unused is not used\n";
        List<ProblemItem> items = EcjProblemParser.parse(out, null);
        assertEquals(1, items.size());
        assertEquals(ProblemItem.Severity.WARNING, items.get(0).severity);
    }

    @Test
    public void messageIsCarriedNotJustTheLine() {
        List<ProblemItem> items = EcjProblemParser.parse(ECJ_OUTPUT, null);
        assertFalse("the description should not be empty", items.get(0).message.trim().isEmpty());
    }

    @Test
    public void identicalProblemsAreNotListedTwice() {
        String doubled = ECJ_OUTPUT + ECJ_OUTPUT;
        assertEquals("the same problem twice is still one problem",
                2, EcjProblemParser.parse(doubled, null).size());
    }

    @Test
    public void emptyOutputMeansNoProblems() {
        assertTrue(EcjProblemParser.parse("", null).isEmpty());
        assertTrue(EcjProblemParser.parse("   \n  ", null).isEmpty());
        assertTrue(EcjProblemParser.parse(null, null).isEmpty());
    }

    @Test
    public void unrecognisedOutputIsStillReported() {
        // A different ECJ version, another locale, or a crash dump. Whatever it is,
        // it must not vanish: "no problems" and "we could not read the output" have
        // to look different to the user.
        List<ProblemItem> items = EcjProblemParser.parse(
                "Something went badly wrong and this is not the usual format", null);
        assertEquals(1, items.size());
        assertEquals(ProblemItem.Severity.ERROR, items.get(0).severity);
    }

    @Test
    public void hugeOutputIsTruncatedNotDropped() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5000; i++) sb.append("unparseable line ").append(i).append('\n');
        List<ProblemItem> items = EcjProblemParser.parse(sb.toString(), null);
        assertEquals(1, items.size());
        assertTrue("the fallback should be capped",
                items.get(0).message.length() < 3000);
    }
}
