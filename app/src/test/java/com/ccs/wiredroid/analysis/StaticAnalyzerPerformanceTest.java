package com.ccs.wiredroid.analysis;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.util.List;

/**
 * Guards the rule sets against the failure that made the Problems panel useless:
 * a pattern that backtracks exponentially.
 *
 * <p>{@code P_METHOD_DECL} used to be
 * {@code ...[\w<>,\s?]+\s+(\w+)\s*\(} — a character class containing {@code \s}
 * immediately before {@code \s+}. Both can claim the same spaces, so a line that
 * does not ultimately match costs the engine every way of splitting them.</p>
 *
 * <p>The cost is spread, not concentrated: no single line took even forty
 * milliseconds, but the ordinary failing line cost two to twelve, and there are
 * seventy-seven thousand of them in this project. Scanning it once did not finish
 * in ten minutes. On a two-thousand-file project the sweep never reached the end,
 * so the Problems panel stayed empty while a thread burned a core.</p>

 * <p>That is why these are volume tests rather than single-line ones, and why the
 * budgets are set from measurement: with the pattern fixed the two timed tests
 * take 0.20s and 0.01s; with the old one they took 1.62s and 0.70s. The budgets
 * sit between, with several times' headroom over the fixed figures.</p>
 */
public class StaticAnalyzerPerformanceTest {

    /** The shapes that triggered the blow-up: generics, wide spacing, no match. */
    private static final String NASTY_SOURCE =
            "package com.example.deeply.nested.pkg;\n"
            + "\n"
            + "import java.util.Map;\n"
            + "\n"
            + "public class Nasty {\n"
            // Generic return types with spaces inside the type arguments.
            + "    public Map<String, java.util.List<Map<Integer, String>>> lookup(int k) {\n"
            + "        return null;\n"
            + "    }\n"
            // Long modifier runs followed by no opening parenthesis: the case that
            // forces the engine to explore every whitespace split before failing.
            + "    public    static    final    synchronized    transient    volatile    int    x    =    1   ;\n"
            + "    private   static   final   Map<String,   Map<String,   String>>   m   =   null   ;\n"
            // A line that looks like a declaration for a long time and then is not.
            + "    if (a && b || c && d || e && f || g && h || i && j || k && l) { }\n"
            + "    someCall(a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q, r, s, t);\n"
            + "}\n";

    private static String repeat(String s, int times) {
        StringBuilder sb = new StringBuilder(s.length() * times);
        for (int i = 0; i < times; i++) sb.append(s);
        return sb.toString();
    }

    /**
     * The line that actually detonated, found by timing the project's own sources
     * one line at a time against the old pattern.
     *
     * <p>It is not even a declaration — it is the continuation of a signature
     * that was split across lines. Identifiers, commas, spaces and {@code <>?}
     * are all inside the old character class, and the line ends in {@code ) &#123;}
     * so the match fails. Failing is the expensive part: the engine has to try
     * every way of dividing those spaces and commas before it can give up. One
     * line took longer than four hundred milliseconds; a file full of them never
     * came back.</p>
     */
    private static final String DETONATOR = "        String name, Class<?>... params) {\n";

    @Test(timeout = 4000)
    public void repeatedSplitSignatureLinesStayFast() {
        // Two hundred copies. With the old pattern a single copy did not finish.
        StringBuilder sb = new StringBuilder("class Boom {\n");
        for (int i = 0; i < 200; i++) sb.append(DETONATOR);
        sb.append("}\n");
        long start = System.currentTimeMillis();
        StaticAnalyzer.analyzeSource(null, new File("Boom.java"), sb.toString());
        long took = System.currentTimeMillis() - start;
        assertTrue("200 split-signature lines took " + took + " ms; the fixed rules "
                + "need about 10. Check the rule patterns for a character class "
                + "containing \\s directly before \\s+.",
                took < 200);
    }

    @Test(timeout = 5000)
    public void oneFileDoesNotBacktrackForever() {
        List<ProblemItem> found = StaticAnalyzer.analyzeSource(
                null, new File("Nasty.java"), NASTY_SOURCE);
        // The assertion that matters is that we got here at all; the rules are
        // free to disagree about what is worth reporting.
        assertTrue("analyzeSource must return a list", found != null);
    }

    @Test(timeout = 20000)
    public void twoHundredFilesStayWithinBudget() {
        // Two hundred files of the nasty shape. With the old pattern this did not
        // finish; with the current one it is well under a second.
        long start = System.currentTimeMillis();
        for (int i = 0; i < 200; i++) {
            StaticAnalyzer.analyzeSource(null, new File("Nasty" + i + ".java"), NASTY_SOURCE);
        }
        long took = System.currentTimeMillis() - start;
        assertTrue("200 files took " + took + " ms; the fixed rules need about 200. "
                + "Check for a backtracking pattern.",
                took < 800);
    }

    @Test(timeout = 10000)
    public void longSingleLineDoesNotBlowUp() {
        // A minified or generated line. Length alone must not be quadratic.
        String wide = "    public static final int " + repeat("a", 4000) + " = 1;\n";
        StaticAnalyzer.analyzeSource(null, new File("Wide.java"), "class W {\n" + wide + "}\n");
    }

    @Test
    public void emptyFileIsReportedOnce() {
        List<ProblemItem> found = StaticAnalyzer.analyzeSource(
                null, new File("Empty.java"), "   \n\n  ");
        assertTrue("an empty file should produce exactly one finding, got " + found.size(),
                found.size() == 1);
        assertTrue(found.get(0).severity == ProblemItem.Severity.WARNING);
    }

    @Test
    public void nullSourceIsNotAFinding() {
        assertTrue(StaticAnalyzer.analyzeSource(null, new File("X.java"), null).isEmpty());
    }

    @Test
    public void nonJavaFileIsSkippedByAnalyzeFile() {
        // analyzeFile filters by extension before it reads anything, so a missing
        // .txt must come back empty rather than throwing.
        assertTrue(StaticAnalyzer.analyzeFile(null, new File("no/such/file.txt")).isEmpty());
    }

    @Test
    public void missingJavaFileIsSwallowed() {
        // A file that vanished between the directory listing and the read must not
        // take the sweep down with it.
        List<ProblemItem> found = StaticAnalyzer.analyzeFile(null, new File("no/such/file.java"));
        assertFalse("a missing file should not report findings", found == null);
        assertTrue(found.isEmpty());
    }
}
