package com.ccs.wiredroid.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AnsiParserTest {

    private static final String ESC = "\u001B";
    private static final String BEL = "\u0007";

    @Test
    public void containsAnsiReturnsFalseForPlainText() {
        assertFalse(AnsiParser.containsAnsi("Hello, World!"));
        assertFalse(AnsiParser.containsAnsi(""));
        assertFalse(AnsiParser.containsAnsi(null));
    }

    @Test
    public void containsAnsiReturnsTrueForEscapeCode() {
        assertTrue(AnsiParser.containsAnsi(ESC + "[31mError" + ESC + "[0m"));
        assertTrue(AnsiParser.containsAnsi(ESC + "[1;32mOK" + ESC + "[0m"));
    }

    @Test
    public void stripAnsiRemovesColorAndStyleCodes() {
        String input = ESC + "[31mRed" + ESC + "[0m "
                + ESC + "[32mGreen" + ESC + "[0m "
                + ESC + "[1mBold" + ESC + "[0m";
        assertEquals("Red Green Bold", AnsiParser.stripAnsi(input));
    }

    @Test
    public void stripAnsiRemoves256AndTruecolorCodes() {
        String input = ESC + "[38;5;196m256-Red" + ESC + "[0m "
                + ESC + "[38;2;100;150;200mRGB-Blue" + ESC + "[0m";
        assertEquals("256-Red RGB-Blue", AnsiParser.stripAnsi(input));
    }

    @Test
    public void stripAnsiRemovesCursorAndClearSequences() {
        String input = ESC + "[2K" + ESC + "[1AProcessing..." + ESC + "[0m";
        assertEquals("Processing...", AnsiParser.stripAnsi(input));
    }

    @Test
    public void stripAnsiPreservesPlainTextUnchanged() {
        String input = "Simple log message with no ANSI: [100% complete]";
        assertEquals(input, AnsiParser.stripAnsi(input));
    }

    @Test
    public void stripAnsiRemovesPrivateModeSequences() {
        // ESC[?25l / ESC[?25h hide and show the cursor, which any progress bar
        // emits. The parameter grammar used to allow digits and semicolons only,
        // so the "?" made these unmatchable and they reached the console as
        // literal "[?25l".
        String input = ESC + "[?25lWorking" + ESC + "[?25h done";
        assertEquals("Working done", AnsiParser.stripAnsi(input));
    }

    @Test
    public void stripAnsiRemovesOscWindowTitle() {
        assertEquals("Compiling",
                AnsiParser.stripAnsi(ESC + "]0;my build" + BEL + "Compiling"));
        assertEquals("Compiling",
                AnsiParser.stripAnsi(ESC + "]2;title" + ESC + "\\Compiling"));
    }

    @Test
    public void stripAnsiDropsTruncatedSequence() {
        // A chunk that ended mid-sequence used to leave a bare ESC in the text.
        assertEquals("done", AnsiParser.stripAnsi("done" + ESC));
        assertEquals("done", AnsiParser.stripAnsi(ESC + "done"));
    }

    @Test
    public void stripAnsiLeavesBracketTextAlone() {
        // No ESC, so none of this is an escape sequence however much it looks
        // like one.
        String input = "[31m is not a colour code and [INFO] is not either";
        assertEquals(input, AnsiParser.stripAnsi(input));
    }

    @Test
    public void sgrColourSurvivesIntoTheNextChunk() {
        // The console appends one line per call, so a colour set while printing
        // the first line has to still be in force for the second.
        AnsiParser.State state = new AnsiParser.State();
        AnsiParser.applySgr(AnsiParser.parseCodes("31"), state);

        assertNotNull("red should be carried", state.fg);
        Integer red = state.fg;
        assertFalse(state.bold);

        AnsiParser.applySgr(AnsiParser.parseCodes("1"), state);
        assertTrue("bold adds to the carried colour", state.bold);
        assertEquals(red, state.fg);
    }

    @Test
    public void sgrResetClearsCarriedStyle() {
        AnsiParser.State state = new AnsiParser.State();
        AnsiParser.applySgr(AnsiParser.parseCodes("1;4;31;44"), state);
        assertNotNull(state.fg);
        assertNotNull(state.bg);
        assertTrue(state.bold);
        assertTrue(state.underline);

        AnsiParser.applySgr(AnsiParser.parseCodes("0"), state);
        assertNull("reset returns the foreground to the caller's default", state.fg);
        assertNull(state.bg);
        assertFalse(state.bold);
        assertFalse(state.underline);
    }

    @Test
    public void sgrDefaultColourCodesClearOnlyTheirOwnChannel() {
        AnsiParser.State state = new AnsiParser.State();
        AnsiParser.applySgr(AnsiParser.parseCodes("31;44"), state);

        AnsiParser.applySgr(AnsiParser.parseCodes("39"), state);
        assertNull(state.fg);
        assertNotNull("39 resets the foreground only", state.bg);

        AnsiParser.applySgr(AnsiParser.parseCodes("49"), state);
        assertNull(state.bg);
    }

    @Test
    public void sgrTrueColorAndPaletteReachBothChannels() {
        AnsiParser.State state = new AnsiParser.State();
        AnsiParser.applySgr(AnsiParser.parseCodes("38;2;10;20;30"), state);
        assertEquals(Integer.valueOf(0xFF0A141E), state.fg);

        AnsiParser.applySgr(AnsiParser.parseCodes("48;5;196"), state);
        assertEquals("196 is the top corner of the 6x6x6 cube",
                Integer.valueOf(0xFFFF0000), state.bg);
    }

    @Test
    public void sgrIgnoresTruncatedExtendedColour() {
        // "38;2" with no channels behind it must not consume garbage or throw.
        AnsiParser.State state = new AnsiParser.State();
        AnsiParser.applySgr(AnsiParser.parseCodes("38;2"), state);
        assertNull(state.fg);
    }

    @Test
    public void sgrIgnoresNonColourFinalBytes() {
        // A bare "m" with no parameters is a reset; anything unparseable in the
        // parameter list degrades to 0 rather than throwing.
        AnsiParser.State state = new AnsiParser.State();
        AnsiParser.applySgr(AnsiParser.parseCodes("31"), state);
        AnsiParser.applySgr(AnsiParser.parseCodes(""), state);
        assertNull(state.fg);
    }
}
