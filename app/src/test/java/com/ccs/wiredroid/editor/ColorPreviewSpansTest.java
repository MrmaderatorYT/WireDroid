package com.ccs.wiredroid.editor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/** What counts as a colour literal — and, more importantly, what does not. */
public class ColorPreviewSpansTest {

    private static List<ColorPreviewSpans.Swatch> find(String line) {
        return ColorPreviewSpans.findIn(line);
    }

    @Test
    public void findsSixDigitHex() {
        List<ColorPreviewSpans.Swatch> s = find("int accent = 0; // #3574F0");
        assertEquals(1, s.size());
        assertEquals(0xFF3574F0, s.get(0).color);
    }

    @Test
    public void shorthandExpands() {
        // #f00 is the same red as #ff0000.
        assertEquals(0xFFFF0000, find("color: #f00;").get(0).color);
    }

    @Test
    public void alphaIsDroppedSoTheSwatchStaysVisible() {
        assertEquals(0xFF112233, find("#80112233").get(0).color);
    }

    @Test
    public void readsTheLiteralInsideParseColor() {
        List<ColorPreviewSpans.Swatch> s = find("Color.parseColor(\"#00FF7F\")");
        assertEquals(1, s.size());
        assertEquals(0xFF00FF7F, s.get(0).color);
    }

    @Test
    public void readsAJavaHexLiteral() {
        assertEquals(0xFF2ECC71, find("int c = 0xFF2ECC71;").get(0).color);
    }

    @Test
    public void aCommitHashIsNotAColour() {
        // The tail of ef8718e34ac1 is six hex digits and means nothing here.
        assertTrue(find("// see commit ef8718e34ac1 for details").isEmpty());
        assertTrue(find("#0123456789abcdef").isEmpty());
    }

    @Test
    public void anIdentifierIsNotAColour() {
        assertTrue(find("String abcdef = name;").isEmpty());
        assertTrue(find("#define FOO 1").isEmpty());
    }

    @Test
    public void severalOnOneLineComeBackInOrder() {
        List<ColorPreviewSpans.Swatch> s = find("from #FF0000 to #0000FF");
        assertEquals(2, s.size());
        assertTrue(s.get(0).start < s.get(1).start);
        assertEquals(0xFFFF0000, s.get(0).color);
        assertEquals(0xFF0000FF, s.get(1).color);
    }

    @Test
    public void nothingSillyCrashesIt() {
        assertTrue(find(null).isEmpty());
        assertTrue(find("").isEmpty());
        assertTrue(find("#").isEmpty());
        assertTrue(find("#gg0011").isEmpty());
    }
}
