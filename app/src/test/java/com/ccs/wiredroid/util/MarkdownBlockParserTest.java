package com.ccs.wiredroid.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/**
 * The split that decides whether a table is drawn as a real grid or falls back
 * to a monospace approximation.
 *
 * <p>A table the parser does not recognise still renders — as literal pipe
 * characters in a fixed-width font, one per line, which is what "the lines are
 * not connected" looks like on screen. That failure is silent and invisible from
 * the code, so the recognition rules are pinned here.</p>
 */
public class MarkdownBlockParserTest {

    private static List<MarkdownBlockParser.Part> split(String markdown) {
        return MarkdownBlockParser.split(markdown);
    }

    /** The one table part in the document, or null. */
    private static MarkdownBlockParser.Part table(String markdown) {
        MarkdownBlockParser.Part found = null;
        for (MarkdownBlockParser.Part p : split(markdown)) {
            if (p.table) {
                assertTrue("more than one table part", found == null);
                found = p;
            }
        }
        return found;
    }

    @Test
    public void plainPipeTableIsRecognised() {
        String md = "| Language | Year |\n"
                + "|----------|------|\n"
                + "| Java | 1995 |\n";
        assertTrue("a leading-pipe table must be a table part", table(md) != null);
    }

    @Test
    public void alignmentColonsAreStillATable() {
        String md = "| Left | Middle | Right |\n"
                + "|:-----|:------:|------:|\n"
                + "| a | b | c |\n";
        assertTrue(table(md) != null);
    }

    @Test
    public void tableWithoutOuterPipesIsRecognised() {
        // GitHub accepts this shape and so do the docs people paste in.
        String md = "Language | Year\n"
                + "-------- | ----\n"
                + "Java | 1995\n";
        assertTrue("a table without outer pipes must still be a table", table(md) != null);
    }

    @Test
    public void aHeadingBeforeTheTableDoesNotSwallowIt() {
        String md = "## Heading\n"
                + "\n"
                + "| A | B |\n"
                + "|---|---|\n"
                + "| 1 | 2 |\n";
        MarkdownBlockParser.Part t = table(md);
        assertTrue("the table after a heading must be its own part", t != null);
        assertTrue("the heading must not be inside the table",
                !t.text.contains("Heading"));
    }

    @Test
    public void tableEndsAtTheFollowingParagraph() {
        String md = "| A | B |\n"
                + "|---|---|\n"
                + "| 1 | 2 |\n"
                + "\n"
                + "After the table.\n";
        MarkdownBlockParser.Part t = table(md);
        assertTrue(t != null);
        assertTrue("prose after the table must not be part of it",
                !t.text.contains("After the table"));
    }

    @Test
    public void aTableInsideAFenceStaysCode() {
        String md = "```\n"
                + "| A | B |\n"
                + "|---|---|\n"
                + "```\n";
        assertTrue("a table inside a fence is code, not a table", table(md) == null);
        boolean sawCode = false;
        for (MarkdownBlockParser.Part p : split(md)) {
            if (p.code) sawCode = true;
        }
        assertTrue(sawCode);
    }

    @Test
    public void aLoneLineWithAPipeIsNotATable() {
        // Prose and code both contain pipes; without a delimiter row it is text.
        String md = "Use a | b to pipe one into the other.\n";
        assertTrue(table(md) == null);
    }

    @Test
    public void emptyInputProducesNothing() {
        assertEquals(0, split("").size());
    }
}
