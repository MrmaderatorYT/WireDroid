package com.ccs.wiredroid.util.languages;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import io.github.rosemoe.sora.lang.smartEnter.NewlineHandleResult;
import io.github.rosemoe.sora.text.CharPosition;
import io.github.rosemoe.sora.text.Content;

/**
 * Enter pressed between a bracket and its auto-inserted partner.
 *
 * <p>The property that matters is where the caret ends up: the handler returns
 * one string plus a distance to walk back from its end, and getting that
 * distance wrong parks the caret on the closing bracket's line instead of the
 * empty one.</p>
 */
public class BracePairNewlineHandlerTest {

    private static final BracePairNewlineHandler SPACES = new BracePairNewlineHandler(false);

    private static CharPosition at(Content c, int line, int column) {
        return c.getIndexer().getCharPosition(line, column);
    }

    @Test
    public void opensABlockBetweenBraces() {
        Content c = new Content("    void setName(String name) {}");
        CharPosition p = at(c, 0, 31);          // between { and }
        assertTrue(SPACES.matchesRequirement(c, p, null));

        NewlineHandleResult r = SPACES.handleNewline(c, p, null, 4);
        assertEquals("\n        \n    ", r.text.toString());
        // Walking back over "\n" + the four-space base indent leaves the caret
        // at the end of the eight-space line between the braces.
        assertEquals(5, r.shiftLeft);
    }

    @Test
    public void keepsTheLinesIndentOfTheOpeningLine() {
        Content c = new Content("            if (x) {}");
        NewlineHandleResult r = SPACES.handleNewline(c, at(c, 0, 20), null, 4);
        assertEquals("\n                \n            ", r.text.toString());
        assertEquals(13, r.shiftLeft);
    }

    @Test
    public void tabsWhenTheLanguageUsesTabs() {
        BracePairNewlineHandler tabs = new BracePairNewlineHandler(true);
        Content c = new Content("\tvoid f() {}");
        NewlineHandleResult r = tabs.handleNewline(c, at(c, 0, 11), null, 4);
        assertEquals("\n\t\t\n\t", r.text.toString());
    }

    @Test
    public void parenthesesAndBracketsCountToo() {
        Content parens = new Content("foo()");
        assertTrue(SPACES.matchesRequirement(parens, at(parens, 0, 4), null));
        Content brackets = new Content("int[] a = {}");
        assertTrue(SPACES.matchesRequirement(brackets, at(brackets, 0, 11), null));
    }

    @Test
    public void doesNothingWhenTheCaretIsNotBetweenAPair() {
        Content after = new Content("void f() {}");
        // After the closing brace, not between the pair.
        assertFalse(SPACES.matchesRequirement(after, at(after, 0, 11), null));

        Content plain = new Content("int x = 1;");
        assertFalse(SPACES.matchesRequirement(plain, at(plain, 0, 10), null));

        // An opening brace with real code after it is a line being split, not a
        // block being opened; the editor's own indenting handles that.
        Content code = new Content("void f() { return; }");
        assertFalse(SPACES.matchesRequirement(code, at(code, 0, 11), null));
    }

    @Test
    public void mismatchedBracketsAreNotAPair() {
        Content c = new Content("f(]");
        assertFalse(SPACES.matchesRequirement(c, at(c, 0, 2), null));
    }
}
