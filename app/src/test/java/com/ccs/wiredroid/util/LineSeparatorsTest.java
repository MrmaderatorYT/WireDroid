package com.ccs.wiredroid.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import io.github.rosemoe.sora.text.LineSeparator;

/**
 * Reading and rewriting line endings.
 *
 * <p>The trap in both directions is CRLF: read one character at a time it looks
 * like a CR followed by an LF, so a Windows file reports as mixed and a
 * conversion turns each of its line breaks into two.</p>
 */
public class LineSeparatorsTest {

    @Test
    public void crlfIsOneSeparatorNotTwo() {
        LineSeparators.Detected d = LineSeparators.detect("a\r\nb\r\nc");
        assertEquals(LineSeparator.CRLF, d.dominant);
        assertFalse("a Windows file is not mixed", d.mixed);
    }

    @Test
    public void plainKinds() {
        assertEquals(LineSeparator.LF, LineSeparators.detect("a\nb").dominant);
        assertEquals(LineSeparator.CR, LineSeparators.detect("a\rb").dominant);
    }

    @Test
    public void aFileWithNoLineBreakReadsAsLf() {
        LineSeparators.Detected d = LineSeparators.detect("single line");
        assertEquals(LineSeparator.LF, d.dominant);
        assertFalse(d.any);
        assertFalse(d.mixed);
    }

    @Test
    public void mixedIsReportedWithItsDominantKind() {
        LineSeparators.Detected d = LineSeparators.detect("a\r\nb\nc\nd\n");
        assertTrue(d.mixed);
        assertEquals("three LF against one CRLF", LineSeparator.LF, d.dominant);
    }

    @Test
    public void convertingRewritesEveryKind() {
        assertEquals("a\r\nb\r\nc\r\n",
                LineSeparators.convert("a\nb\r\nc\r", LineSeparator.CRLF));
        assertEquals("a\nb\nc\n",
                LineSeparators.convert("a\r\nb\rc\n", LineSeparator.LF));
        assertEquals("a\rb\rc\r",
                LineSeparators.convert("a\nb\r\nc\r", LineSeparator.CR));
    }

    @Test
    public void convertingDoesNotDoubleUpCrlf() {
        // The one that matters: treating \r\n as two breaks would give "a\r\n\r\nb".
        assertEquals("a\r\nb", LineSeparators.convert("a\r\nb", LineSeparator.CRLF));
    }

    @Test
    public void textWithoutBreaksIsUnchanged() {
        assertEquals("no breaks", LineSeparators.convert("no breaks", LineSeparator.CRLF));
        assertEquals("", LineSeparators.convert("", LineSeparator.CR));
    }

    @Test
    public void convertingIsIdempotent() {
        String once = LineSeparators.convert("a\nb\r\nc\r", LineSeparator.CRLF);
        assertEquals(once, LineSeparators.convert(once, LineSeparator.CRLF));
    }

    @Test
    public void pickerOrderMatchesTheLabels() {
        assertEquals(LineSeparator.LF, LineSeparators.CHOICES[0]);
        assertEquals(LineSeparator.CRLF, LineSeparators.CHOICES[1]);
        assertEquals(LineSeparator.CR, LineSeparators.CHOICES[2]);
        assertEquals(1, LineSeparators.indexOf(LineSeparator.CRLF));
        assertEquals(0, LineSeparators.indexOf(LineSeparator.NONE));
    }

    @Test
    public void shortNames() {
        assertEquals("CRLF", LineSeparators.shortName(LineSeparator.CRLF));
        assertEquals("CR", LineSeparators.shortName(LineSeparator.CR));
        assertEquals("LF", LineSeparators.shortName(LineSeparator.LF));
    }
}
