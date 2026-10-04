package org.junit;

import java.util.Arrays;
import java.util.Objects;

/**
 * Standard assertion methods for JUnit 4 tests.
 */
public class Assert {

    protected Assert() {}

    public static void assertTrue(String message, boolean condition) {
        if (!condition) {
            fail(message);
        }
    }

    public static void assertTrue(boolean condition) {
        assertTrue(null, condition);
    }

    public static void assertFalse(String message, boolean condition) {
        assertTrue(message, !condition);
    }

    public static void assertFalse(boolean condition) {
        assertFalse(null, condition);
    }

    public static void fail(String message) {
        if (message == null) {
            throw new AssertionError();
        }
        throw new AssertionError(message);
    }

    public static void fail() {
        fail(null);
    }

    public static void assertEquals(String message, Object expected, Object actual) {
        if (equalsRegardingNull(expected, actual)) {
            return;
        }
        if (expected instanceof String && actual instanceof String) {
            String cleanMessage = message == null ? "" : message;
            throw new AssertionError(format(cleanMessage, expected, actual));
        }
        failNotEquals(message, expected, actual);
    }

    public static void assertEquals(Object expected, Object actual) {
        assertEquals(null, expected, actual);
    }

    public static void assertNotEquals(String message, Object unexpected, Object actual) {
        if (equalsRegardingNull(unexpected, actual)) {
            failEquals(message, actual);
        }
    }

    public static void assertNotEquals(Object unexpected, Object actual) {
        assertNotEquals(null, unexpected, actual);
    }

    public static void assertNotEquals(String message, long unexpected, long actual) {
        if (unexpected == actual) {
            failEquals(message, actual);
        }
    }

    public static void assertNotEquals(long unexpected, long actual) {
        assertNotEquals(null, unexpected, actual);
    }

    public static void assertNotEquals(String message, double unexpected, double actual, double delta) {
        if (doubleIsDifferent(unexpected, actual, delta)) {
            return;
        }
        failEquals(message, actual);
    }

    public static void assertNotEquals(double unexpected, double actual, double delta) {
        assertNotEquals(null, unexpected, actual, delta);
    }

    public static void assertArrayEquals(String message, Object[] expecteds, Object[] actuals) {
        if (expecteds == actuals) return;
        if (expecteds == null) fail(message == null ? "expected array was null" : message);
        if (actuals == null) fail(message == null ? "actual array was null" : message);
        if (expecteds.length != actuals.length) {
            fail(format(message, "array length " + expecteds.length, "array length " + actuals.length));
        }
        for (int i = 0; i < expecteds.length; i++) {
            try {
                assertEquals(expecteds[i], actuals[i]);
            } catch (AssertionError e) {
                fail(format(message, "element at index " + i + ": " + expecteds[i], actuals[i]));
            }
        }
    }

    public static void assertArrayEquals(Object[] expecteds, Object[] actuals) {
        assertArrayEquals(null, expecteds, actuals);
    }

    public static void assertArrayEquals(String message, byte[] expecteds, byte[] actuals) {
        if (!Arrays.equals(expecteds, actuals)) {
            fail(format(message, Arrays.toString(expecteds), Arrays.toString(actuals)));
        }
    }

    public static void assertArrayEquals(byte[] expecteds, byte[] actuals) {
        assertArrayEquals(null, expecteds, actuals);
    }

    public static void assertArrayEquals(String message, int[] expecteds, int[] actuals) {
        if (!Arrays.equals(expecteds, actuals)) {
            fail(format(message, Arrays.toString(expecteds), Arrays.toString(actuals)));
        }
    }

    public static void assertArrayEquals(int[] expecteds, int[] actuals) {
        assertArrayEquals(null, expecteds, actuals);
    }

    public static void assertEquals(String message, double expected, double actual, double delta) {
        if (doubleIsDifferent(expected, actual, delta)) {
            failNotEquals(message, expected, actual);
        }
    }

    public static void assertEquals(double expected, double actual, double delta) {
        assertEquals(null, expected, actual, delta);
    }

    public static void assertEquals(String message, float expected, float actual, float delta) {
        if (Float.compare(expected, actual) == 0) return;
        if (!(Math.abs(expected - actual) <= delta)) {
            failNotEquals(message, expected, actual);
        }
    }

    public static void assertEquals(float expected, float actual, float delta) {
        assertEquals(null, expected, actual, delta);
    }

    public static void assertEquals(String message, long expected, long actual) {
        if (expected != actual) {
            failNotEquals(message, expected, actual);
        }
    }

    public static void assertEquals(long expected, long actual) {
        assertEquals(null, expected, actual);
    }

    public static void assertNull(String message, Object object) {
        if (object != null) {
            failNotNull(message, object);
        }
    }

    public static void assertNull(Object object) {
        assertNull(null, object);
    }

    public static void assertNotNull(String message, Object object) {
        assertTrue(message, object != null);
    }

    public static void assertNotNull(Object object) {
        assertNotNull(null, object);
    }

    public static void assertSame(String message, Object expected, Object actual) {
        if (expected == actual) {
            return;
        }
        failNotSame(message, expected, actual);
    }

    public static void assertSame(Object expected, Object actual) {
        assertSame(null, expected, actual);
    }

    public static void assertNotSame(String message, Object unexpected, Object actual) {
        if (unexpected == actual) {
            failSame(message);
        }
    }

    public static void assertNotSame(Object unexpected, Object actual) {
        assertNotSame(null, unexpected, actual);
    }

    private static boolean equalsRegardingNull(Object expected, Object actual) {
        if (expected == null) {
            return actual == null;
        }
        return isEquals(expected, actual);
    }

    private static boolean isEquals(Object expected, Object actual) {
        return expected.equals(actual);
    }

    private static void failNotEquals(String message, Object expected, Object actual) {
        fail(format(message, expected, actual));
    }

    private static void failEquals(String message, Object actual) {
        String formatted = "Values should be different. Actual: " + actual;
        if (message != null) {
            formatted = message + ". " + formatted;
        }
        fail(formatted);
    }

    private static void failSame(String message) {
        String formatted = "";
        if (message != null) {
            formatted = message + " ";
        }
        fail(formatted + "expected not same");
    }

    private static void failNotSame(String message, Object expected, Object actual) {
        String formatted = "";
        if (message != null) {
            formatted = message + " ";
        }
        fail(formatted + "expected same:<" + expected + "> was not:<" + actual + ">");
    }

    private static void failNotNull(String message, Object actual) {
        String formatted = "";
        if (message != null) {
            formatted = message + " ";
        }
        fail(formatted + "expected null, but was:<" + actual + ">");
    }

    private static boolean doubleIsDifferent(double d1, double d2, double delta) {
        if (Double.compare(d1, d2) == 0) {
            return false;
        }
        return !(Math.abs(d1 - d2) <= delta);
    }

    public static String format(String message, Object expected, Object actual) {
        String formatted = "";
        if (message != null && !message.trim().isEmpty()) {
            formatted = message + " ";
        }
        String expectedString = String.valueOf(expected);
        String actualString = String.valueOf(actual);
        if (expectedString.equals(actualString)) {
            return formatted + "expected: "
                    + formatClassAndValue(expected, expectedString)
                    + " but was: " + formatClassAndValue(actual, actualString);
        } else {
            return formatted + "expected:<" + expectedString + "> but was:<" + actualString + ">";
        }
    }

    private static String formatClassAndValue(Object value, String valueString) {
        String className = value == null ? "null" : value.getClass().getName();
        return className + "<" + valueString + ">";
    }
}
