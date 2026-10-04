package com.ccs.wiredroid.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ExactTextEditTest {

    @Test
    public void exactEditPreservesWhitespace() {
        String source = "class A {\n    int value = 1;\n}\n";
        ExactTextEdit.Result result = ExactTextEdit.apply(
                source, "    int value = 1;\n", "    int value = 2;\n");

        assertTrue(result.applied());
        assertEquals("class A {\n    int value = 2;\n}\n", result.text);
        assertEquals(source.indexOf("    int value"), result.offset);
    }

    @Test
    public void ambiguousEditIsRejected() {
        ExactTextEdit.Result result = ExactTextEdit.apply("x\nx\n", "x", "y");

        assertFalse(result.applied());
        assertEquals(ExactTextEdit.Status.AMBIGUOUS, result.status);
        assertEquals("x\nx\n", result.text);
    }

    @Test
    public void missingTextLeavesSourceUntouched() {
        ExactTextEdit.Result result = ExactTextEdit.apply("before\n", "after", "changed");

        assertFalse(result.applied());
        assertEquals(ExactTextEdit.Status.NOT_FOUND, result.status);
        assertEquals("before\n", result.text);
    }

    @Test
    public void emptyFindCannotBecomeAWholeFileInsertion() {
        ExactTextEdit.Result result = ExactTextEdit.apply("source", "", "prefix");

        assertFalse(result.applied());
        assertEquals(ExactTextEdit.Status.EMPTY_FIND, result.status);
        assertEquals("source", result.text);
    }

    @Test
    public void editsComposeAgainstUpdatedText() {
        ExactTextEdit.Result first = ExactTextEdit.apply("one two", "one", "ONE");
        ExactTextEdit.Result second = ExactTextEdit.apply(first.text, "ONE two", "done");

        assertTrue(second.applied());
        assertEquals("done", second.text);
    }

    @Test
    public void unfencedArgumentsAreNotTrimmed() {
        assertEquals("    line\n", ExactTextEdit.unwrapFence("    line\n"));
    }

    @Test
    public void fencedArgumentsLoseOnlyFenceFraming() {
        assertEquals("    line", ExactTextEdit.unwrapFence("```java\n    line\n```"));
    }
}
