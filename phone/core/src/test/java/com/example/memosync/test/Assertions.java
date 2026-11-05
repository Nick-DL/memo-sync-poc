package com.example.memosync.test;

import java.util.Objects;

/** Minimal assertion helpers: the POC intentionally avoids JUnit/TestNG. */
public final class Assertions {

    private Assertions() {
    }

    public static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public static void assertFalse(boolean condition, String message) {
        assertTrue(!condition, message);
    }

    public static void assertEquals(Object expected, Object actual, String message) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(message + " (expected=" + expected + ", actual=" + actual + ")");
        }
    }

    public static void assertNotEquals(Object unexpected, Object actual, String message) {
        if (Objects.equals(unexpected, actual)) {
            throw new AssertionError(message + " (both=" + actual + ")");
        }
    }

    public static void assertEqualsInt(int expected, int actual, String message) {
        if (expected != actual) {
            throw new AssertionError(message + " (expected=" + expected + ", actual=" + actual + ")");
        }
    }

    public static void assertEqualsLong(long expected, long actual, String message) {
        if (expected != actual) {
            throw new AssertionError(message + " (expected=" + expected + ", actual=" + actual + ")");
        }
    }

    public static void assertNull(Object actual, String message) {
        if (actual != null) {
            throw new AssertionError(message + " (expected null, actual=" + actual + ")");
        }
    }

    public static void assertNotNull(Object actual, String message) {
        if (actual == null) {
            throw new AssertionError(message + " (expected non-null)");
        }
    }

    public static void fail(String message) {
        throw new AssertionError(message);
    }

    /** Asserts that {@code body} throws {@code expectedType}. */
    public static void assertThrows(Class<? extends Throwable> expectedType, Runnable body,
                                    String message) {
        try {
            body.run();
        } catch (Throwable t) {
            if (expectedType.isInstance(t)) {
                return;
            }
            throw new AssertionError(message + " (threw " + t.getClass().getSimpleName()
                    + " instead of " + expectedType.getSimpleName() + ": " + t.getMessage() + ")", t);
        }
        throw new AssertionError(message + " (nothing was thrown, expected "
                + expectedType.getSimpleName() + ")");
    }
}
