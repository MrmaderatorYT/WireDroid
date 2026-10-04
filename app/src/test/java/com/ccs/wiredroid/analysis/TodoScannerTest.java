package com.ccs.wiredroid.analysis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.io.File;

import org.junit.Test;

/** What counts as a TODO note and what the panel is told about it. */
public class TodoScannerTest {

    private static final File FILE = new File("/tmp/Example.java");

    private static ProblemItem scan(String line) {
        return TodoScanner.itemIn(line, FILE, 7);
    }

    @Test
    public void findsALineComment() {
        ProblemItem item = scan("        // TODO: use the cached value");
        assertNotNull(item);
        assertEquals("TODO: use the cached value", item.message);
        assertEquals(7, item.line);
        assertEquals(ProblemItem.Severity.INFO, item.severity);
    }

    @Test
    public void fixmeIsAWarning() {
        ProblemItem item = scan("// FIXME broken on rotate");
        assertNotNull(item);
        assertEquals("FIXME: broken on rotate", item.message);
        assertEquals(ProblemItem.Severity.WARNING, item.severity);
    }

    @Test
    public void lowercaseAndNoSpaceStillCount() {
        // How they are actually typed: //todo with no colon and no space.
        assertEquals("TODO: fix this", scan("//todo fix this").message);
        assertEquals("FIXME: later", scan("//Fixme later").message);
    }

    @Test
    public void blockAndScriptMarkersCount() {
        assertEquals("TODO: split this up", scan("     * TODO split this up").message);
        assertEquals("TODO: rename", scan("/* TODO rename */").message);
        assertEquals("TODO: pin the version", scan("# TODO pin the version").message);
        assertEquals("TODO: use a map", scan(";; TODO use a map").message);
        assertEquals("TODO: translate", scan("<!-- TODO translate -->").message);
    }

    @Test
    public void anAuthorInParenthesesIsNotTheNote() {
        assertEquals("TODO: drop the shim", scan("// TODO(dmytro): drop the shim").message);
    }

    @Test
    public void aTagWithNothingAfterItStillListsTheLine() {
        assertEquals("TODO: TODO", scan("// TODO").message);
    }

    @Test
    public void codeThatMerelyMentionsTheWordIsNotANote() {
        // The word in a string or an identifier is not a note, and listing it
        // would make the panel useless in the files that talk about TODOs.
        assertNull(scan("String label = \"TODO\";"));
        assertNull(scan("int todoCount = 0;"));
        assertNull(scan("if (message.contains(\"FIXME\")) count++;"));
    }

    @Test
    public void aWordStartingWithTheTagIsNotTheTag() {
        assertNull(scan("// TODOS are tracked elsewhere"));
    }

    @Test
    public void nullsAndGiantLinesAreIgnored() {
        assertNull(TodoScanner.itemIn(null, FILE, 1));
        StringBuilder huge = new StringBuilder("// TODO ");
        for (int i = 0; i < 3000; i++) huge.append('x');
        assertNull(TodoScanner.itemIn(huge.toString(), FILE, 1));
    }
}
