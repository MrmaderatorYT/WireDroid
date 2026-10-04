package com.ccs.wiredroid.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/**
 * What the breadcrumb dropdown and the structure panel are built from.
 *
 * <p>Also what {@code enclosing()} answers when the caret moves, which is how the
 * breadcrumb knows which method you are in — so a wrong {@code endLine} shows the
 * wrong method name rather than failing outright.</p>
 */
public class MemberOutlineTest {

    private static final String SOURCE =
            "package com.example;\n"                                  // 0
            + "\n"                                                    // 1
            + "public class Thing {\n"                                // 2
            + "\n"                                                    // 3
            + "    private int count;\n"                              // 4
            + "    public static final String NAME = \"thing\";\n"     // 5
            + "\n"                                                    // 6
            + "    public Thing() {\n"                                // 7
            + "        count = 0;\n"                                  // 8
            + "    }\n"                                               // 9
            + "\n"                                                    // 10
            + "    public int bump(int by) {\n"                       // 11
            + "        count += by;\n"                                // 12
            + "        return count;\n"                               // 13
            + "    }\n"                                               // 14
            + "\n"                                                    // 15
            + "    private void helper() {\n"                         // 16
            + "    }\n"                                               // 17
            + "}\n";                                                  // 18

    private static List<MemberOutline.Member> scan() {
        return MemberOutline.scan(SOURCE, "thing.java");
    }

    private static MemberOutline.Member byLabel(List<MemberOutline.Member> ms, String startsWith) {
        for (MemberOutline.Member m : ms) {
            if (m.label.startsWith(startsWith)) return m;
        }
        return null;
    }

    @Test
    public void supportsJavaAndKotlinOnly() {
        assertTrue(MemberOutline.supports("a.java"));
        assertTrue(MemberOutline.supports("a.kt"));
        assertTrue(!MemberOutline.supports("a.xml"));
        assertTrue(!MemberOutline.supports("a.txt"));
    }

    @Test
    public void findsFieldsMethodsAndTheConstructor() {
        List<MemberOutline.Member> ms = scan();
        assertNotNull("count field", byLabel(ms, "count"));
        assertNotNull("NAME field", byLabel(ms, "NAME"));
        assertNotNull("bump method", byLabel(ms, "bump"));
        assertNotNull("helper method", byLabel(ms, "helper"));
        assertNotNull("constructor", byLabel(ms, "Thing"));
    }

    @Test
    public void aMethodIsMarkedAsOneAndAFieldIsNot() {
        assertTrue(byLabel(scan(), "bump").method);
        assertTrue(!byLabel(scan(), "count").method);
    }

    @Test
    public void visibilityIsReadAsWritten() {
        assertEquals(MemberOutline.Visibility.PUBLIC, byLabel(scan(), "bump").visibility);
        assertEquals(MemberOutline.Visibility.PRIVATE, byLabel(scan(), "helper").visibility);
        assertEquals(MemberOutline.Visibility.PRIVATE, byLabel(scan(), "count").visibility);
    }

    @Test
    public void constructorIsItsOwnKind() {
        assertEquals(MemberOutline.Kind.CONSTRUCTOR, byLabel(scan(), "Thing").kind);
    }

    @Test
    public void methodBodyRangeCoversItsLines() {
        MemberOutline.Member bump = byLabel(scan(), "bump");
        assertEquals("bump is declared on line 11 (0-based)", 11, bump.line);
        assertTrue("its body should reach line 14, got " + bump.endLine, bump.endLine >= 13);
    }

    @Test
    public void enclosingFindsTheMethodTheCaretSitsIn() {
        List<MemberOutline.Member> ms = scan();
        MemberOutline.Member at12 = MemberOutline.enclosing(ms, 12);
        assertNotNull("line 12 is inside bump()", at12);
        assertTrue("expected bump, got " + at12.label, at12.label.startsWith("bump"));
    }

    @Test
    public void enclosingIsNullOutsideEveryMember() {
        // The package line belongs to no member; the breadcrumb should show
        // nothing rather than the nearest guess.
        assertNull(MemberOutline.enclosing(scan(), 0));
    }

    @Test
    public void halfWrittenSourceIsHandled() {
        MemberOutline.scan("public class A { void f(", "a.java");
        MemberOutline.scan("", "a.java");
        MemberOutline.scan("}}}}", "a.java");
    }
}
