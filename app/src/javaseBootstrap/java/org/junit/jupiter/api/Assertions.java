package org.junit.jupiter.api;

import org.junit.Assert;

/**
 * Standard assertion methods for JUnit 5 / Jupiter tests.
 */
public final class Assertions {

    private Assertions() {}

    public static void assertTrue(boolean condition) {
        Assert.assertTrue(condition);
    }

    public static void assertTrue(boolean condition, String message) {
        Assert.assertTrue(message, condition);
    }

    public static void assertFalse(boolean condition) {
        Assert.assertFalse(condition);
    }

    public static void assertFalse(boolean condition, String message) {
        Assert.assertFalse(message, condition);
    }

    public static void assertEquals(Object expected, Object actual) {
        Assert.assertEquals(expected, actual);
    }

    public static void assertEquals(Object expected, Object actual, String message) {
        Assert.assertEquals(message, expected, actual);
    }

    public static void assertEquals(long expected, long actual) {
        Assert.assertEquals(expected, actual);
    }

    public static void assertEquals(long expected, long actual, String message) {
        Assert.assertEquals(message, expected, actual);
    }

    public static void assertEquals(double expected, double actual, double delta) {
        Assert.assertEquals(expected, actual, delta);
    }

    public static void assertEquals(double expected, double actual, double delta, String message) {
        Assert.assertEquals(message, expected, actual, delta);
    }

    public static void assertNotEquals(Object unexpected, Object actual) {
        Assert.assertNotEquals(unexpected, actual);
    }

    public static void assertNotEquals(Object unexpected, Object actual, String message) {
        Assert.assertNotEquals(message, unexpected, actual);
    }

    public static void assertNull(Object actual) {
        Assert.assertNull(actual);
    }

    public static void assertNull(Object actual, String message) {
        Assert.assertNull(message, actual);
    }

    public static void assertNotNull(Object actual) {
        Assert.assertNotNull(actual);
    }

    public static void assertNotNull(Object actual, String message) {
        Assert.assertNotNull(message, actual);
    }

    public static void assertSame(Object expected, Object actual) {
        Assert.assertSame(expected, actual);
    }

    public static void assertSame(Object expected, Object actual, String message) {
        Assert.assertSame(message, expected, actual);
    }

    public static void assertNotSame(Object unexpected, Object actual) {
        Assert.assertNotSame(unexpected, actual);
    }

    public static void assertNotSame(Object unexpected, Object actual, String message) {
        Assert.assertNotSame(message, unexpected, actual);
    }

    public static void assertArrayEquals(Object[] expected, Object[] actual) {
        Assert.assertArrayEquals(expected, actual);
    }

    public static void assertArrayEquals(Object[] expected, Object[] actual, String message) {
        Assert.assertArrayEquals(message, expected, actual);
    }

    public static void fail(String message) {
        Assert.fail(message);
    }

    public static void fail() {
        Assert.fail();
    }
}
