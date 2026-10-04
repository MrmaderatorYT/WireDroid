package com.ccs.wiredroid.editor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/** Which blocks a file offers to fold. */
public class FoldingRegionsTest {

    private static final String[] CLASS_WITH_TWO_METHODS = {
            "package demo;",              // 0
            "",                           // 1
            "public class Demo {",        // 2
            "",                           // 3
            "    void first() {",         // 4
            "        one();",             // 5
            "        two();",             // 6
            "    }",                      // 7
            "",                           // 8
            "    void second() {",        // 9
            "        three();",           // 10
            "    }",                      // 11
            "}"                           // 12
    };

    @Test
    public void findsTheClassAndBothMethods() {
        List<FoldingRegions.Region> regions = FoldingRegions.scan(CLASS_WITH_TWO_METHODS);
        assertEquals(3, regions.size());
        assertEquals(2, regions.get(0).start);
        assertEquals(12, regions.get(0).end);
        assertEquals(4, regions.get(1).start);
        assertEquals(7, regions.get(1).end);
        assertEquals(9, regions.get(2).start);
        assertEquals(11, regions.get(2).end);
    }

    @Test
    public void aBlockOnOneLineIsNotWorthFolding() {
        // Nothing would be hidden, so offering it would be noise.
        assertTrue(FoldingRegions.scan(new String[]{"void f() { g(); }"}).isEmpty());
        assertTrue(FoldingRegions.scan(new String[]{"void f() {", "}"}).isEmpty());
    }

    @Test
    public void bracesInStringsAndCommentsAreNotBlocks() {
        String[] lines = {
                "class A {",
                "    String s = \"} not a brace {\";",
                "    char c = '}';",
                "    // } neither is this {",
                "    void f() {",
                "        run();",
                "    }",
                "}"
        };
        List<FoldingRegions.Region> regions = FoldingRegions.scan(lines);
        assertEquals(2, regions.size());
        assertEquals(0, regions.get(0).start);
        assertEquals(7, regions.get(0).end);
        assertEquals(4, regions.get(1).start);
        assertEquals(6, regions.get(1).end);
    }

    @Test
    public void anEscapedQuoteDoesNotEndTheString() {
        String[] lines = {
                "class A {",
                "    String s = \"a \\\" } b\";",
                "    int x;",
                "}"
        };
        List<FoldingRegions.Region> regions = FoldingRegions.scan(lines);
        assertEquals(1, regions.size());
        assertEquals(0, regions.get(0).start);
        assertEquals(3, regions.get(0).end);
    }

    @Test
    public void multiLineCommentsFoldToo() {
        String[] lines = {
                "/**",
                " * Docs.",
                " */",
                "class A {}"
        };
        List<FoldingRegions.Region> regions = FoldingRegions.scan(lines);
        assertEquals(1, regions.size());
        assertEquals(0, regions.get(0).start);
        assertEquals(2, regions.get(0).end);
    }

    @Test
    public void anUnterminatedStringDoesNotOpenAPhantomBlock() {
        // Normal halfway through typing; a { after the quote must not count.
        String[] lines = {"String s = \"oops {", "next();", "more();", "done();"};
        assertTrue(FoldingRegions.scan(lines).isEmpty());
    }

    @Test
    public void unbalancedBracesAreSurvived() {
        String[] lines = {"class A {", "    void f() {", "        g();"};
        assertTrue(FoldingRegions.scan(lines).isEmpty());
        assertTrue(FoldingRegions.scan(new String[]{"}", "}", "}"}).isEmpty());
    }

    @Test
    public void theCaretOnASignaturePicksThatBlock() {
        List<FoldingRegions.Region> regions = FoldingRegions.scan(CLASS_WITH_TWO_METHODS);
        FoldingRegions.Region region = FoldingRegions.regionFor(regions, 9);
        assertNotNull(region);
        assertEquals(9, region.start);
        assertEquals(11, region.end);
    }

    @Test
    public void theCaretInsideABodyPicksTheInnermostBlock() {
        List<FoldingRegions.Region> regions = FoldingRegions.scan(CLASS_WITH_TWO_METHODS);
        FoldingRegions.Region region = FoldingRegions.regionFor(regions, 6);
        assertNotNull(region);
        assertEquals(4, region.start);
    }

    @Test
    public void theCaretOutsideEverythingPicksNothing() {
        List<FoldingRegions.Region> regions = FoldingRegions.scan(CLASS_WITH_TWO_METHODS);
        assertNull(FoldingRegions.regionFor(regions, 0));
    }

    @Test
    public void foldAllMeansTheMembersNotTheClass() {
        // Folding the class itself would leave one line on screen, which is not
        // what anyone means by "fold all".
        List<FoldingRegions.Region> members =
                FoldingRegions.membersOf(FoldingRegions.scan(CLASS_WITH_TWO_METHODS));
        assertEquals(2, members.size());
        assertEquals(4, members.get(0).start);
        assertEquals(9, members.get(1).start);
    }

    @Test
    public void foldAllOnSeveralTopLevelBlocksTakesThemAll() {
        String[] lines = {
                "class A {", "    int x;", "}",
                "class B {", "    int y;", "}"
        };
        List<FoldingRegions.Region> members =
                FoldingRegions.membersOf(FoldingRegions.scan(lines));
        assertEquals(2, members.size());
    }
}
