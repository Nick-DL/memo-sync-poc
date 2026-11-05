package com.example.memosync.test;

import com.example.memosync.core.Hlc;

/** HLC unit tests: encode/parse, total order, next/receive, overflow guard. */
public final class HlcTest {

    private HlcTest() {
    }

    public static void register(TestRunner r) {
        r.test("hlc: parse + encode round trip (short device id)", () -> {
            String encoded = "0000000001000-0000-p";
            Hlc hlc = Hlc.parse(encoded);
            Assertions.assertEqualsLong(1000L, hlc.t(), "t");
            Assertions.assertEqualsInt(0, hlc.c(), "c");
            Assertions.assertEquals("p", hlc.deviceId(), "deviceId");
            Assertions.assertEquals(encoded, hlc.encode(), "re-encoded");
        });

        r.test("hlc: parse device id containing hyphens", () -> {
            String encoded = "1844312345000-0001-w-a1b2c3d4";
            Hlc hlc = Hlc.parse(encoded);
            Assertions.assertEqualsLong(1844312345000L, hlc.t(), "t");
            Assertions.assertEqualsInt(1, hlc.c(), "c");
            Assertions.assertEquals("w-a1b2c3d4", hlc.deviceId(), "deviceId with hyphen");
            Assertions.assertEquals(encoded, hlc.encode(), "round trip");
        });

        r.test("hlc: encode uses fixed 13/4 width", () -> {
            Assertions.assertEquals("0000000000042-0007-p", Hlc.of(42L, 7, "p").encode(),
                    "padded encoding");
            Assertions.assertEquals(13, Hlc.of(42L, 7, "p").encode().substring(0, 13).length(),
                    "13-digit physical time");
        });

        r.test("hlc: parse rejects malformed input", () -> {
            Assertions.assertThrows(IllegalArgumentException.class, () -> Hlc.parse(null),
                    "null must be rejected");
            Assertions.assertThrows(IllegalArgumentException.class, () -> Hlc.parse(""),
                    "empty must be rejected");
            Assertions.assertThrows(IllegalArgumentException.class,
                    () -> Hlc.parse("1844312345000-1-w"), "counter must be 4 digits");
            Assertions.assertThrows(IllegalArgumentException.class,
                    () -> Hlc.parse("184431234500-0001-w"), "time must be 13 digits");
            Assertions.assertThrows(IllegalArgumentException.class,
                    () -> Hlc.parse("1844312345000-0001-"), "device id must not be empty");
            Assertions.assertThrows(IllegalArgumentException.class,
                    () -> Hlc.parse("not-an-hlc"), "garbage must be rejected");
            Assertions.assertThrows(IllegalArgumentException.class,
                    () -> Hlc.of(-1L, 0, "p"), "negative t must be rejected");
            Assertions.assertThrows(IllegalArgumentException.class,
                    () -> Hlc.of(1L, 0, ""), "empty device id must be rejected");
        });

        r.test("hlc: compare order is t, then c, then device id", () -> {
            Hlc base = Hlc.of(1000L, 5, "p");
            Assertions.assertTrue(base.compareTo(Hlc.of(1001L, 0, "p")) < 0, "t first");
            Assertions.assertTrue(base.compareTo(Hlc.of(1000L, 6, "p")) < 0, "then c");
            Assertions.assertTrue(base.compareTo(Hlc.of(1000L, 5, "w")) < 0, "then device id");
            Assertions.assertEqualsInt(0, base.compareTo(Hlc.of(1000L, 5, "p")), "equal");
            Assertions.assertTrue(Hlc.of(1001L, 0, "a")
                    .compareTo(Hlc.of(1000L, 9999, "z")) > 0, "larger t always wins");
        });

        r.test("hlc: encoded strings sort like the Hlc comparator", () -> {
            Hlc a = Hlc.of(1000L, 0, "p");
            Hlc b = Hlc.of(1000L, 1, "p");
            Hlc c = Hlc.of(1000L, 1, "w");
            Hlc d = Hlc.of(1001L, 0, "p");
            Assertions.assertTrue(a.encode().compareTo(b.encode()) < 0, "a < b as strings");
            Assertions.assertTrue(b.encode().compareTo(c.encode()) < 0, "b < c as strings");
            Assertions.assertTrue(c.encode().compareTo(d.encode()) < 0, "c < d as strings");
        });

        r.test("hlc: next advances when wall clock moved forward", () -> {
            Hlc now = Hlc.of(1000L, 7, "p").next(1200L);
            Assertions.assertEqualsLong(1200L, now.t(), "t follows wall clock");
            Assertions.assertEqualsInt(0, now.c(), "counter resets");
        });

        r.test("hlc: next bumps counter when clock did not advance or went backwards", () -> {
            Hlc same = Hlc.of(1000L, 7, "p").next(1000L);
            Assertions.assertEqualsLong(1000L, same.t(), "t kept");
            Assertions.assertEqualsInt(8, same.c(), "counter bumped on equal clock");

            Hlc backwards = Hlc.of(1000L, 7, "p").next(999L);
            Assertions.assertEqualsLong(1000L, backwards.t(), "t kept on clock rollback");
            Assertions.assertEqualsInt(8, backwards.c(), "counter bumped on rollback");
        });

        r.test("hlc: next is strictly monotonic on a frozen clock", () -> {
            Hlc current = Hlc.of(1000L, 0, "p");
            String previous = current.encode();
            for (int i = 0; i < 1000; i++) {
                current = current.next(1000L);
                Assertions.assertTrue(current.encode().compareTo(previous) > 0,
                        "iteration " + i + " must increase");
                previous = current.encode();
            }
            Assertions.assertEqualsInt(1000, current.c(), "counter after 1000 bumps");
        });

        r.test("hlc: receive merges a remote HLC newer than local", () -> {
            Hlc local = Hlc.of(1000L, 2, "p");
            Hlc next = local.receive(Hlc.of(1001L, 9, "w"));
            Assertions.assertEqualsLong(1001L, next.t(), "remote t wins");
            Assertions.assertEqualsInt(10, next.c(), "remote counter + 1");
            Assertions.assertEquals("p", next.deviceId(), "local device id is kept");
        });

        r.test("hlc: receive merges a remote HLC in the same millisecond", () -> {
            Hlc local = Hlc.of(1000L, 2, "p");
            Hlc next = local.receive(Hlc.of(1000L, 9, "w"));
            Assertions.assertEqualsLong(1000L, next.t(), "equal t");
            Assertions.assertEqualsInt(10, next.c(), "max(c) + 1");
        });

        r.test("hlc: receive with an older remote only bumps local counter", () -> {
            Hlc local = Hlc.of(1000L, 2, "p");
            Hlc next = local.receive(Hlc.of(999L, 100, "w"));
            Assertions.assertEqualsLong(1000L, next.t(), "local t kept");
            Assertions.assertEqualsInt(3, next.c(), "local counter + 1");
        });

        r.test("hlc: counter overflow keeps the 4-digit encoding valid", () -> {
            Hlc maxed = Hlc.of(1000L, Hlc.MAX_COUNTER, "p");
            Hlc next = maxed.next(1000L);
            Assertions.assertEqualsLong(1001L, next.t(), "physical time advanced");
            Assertions.assertEqualsInt(0, next.c(), "counter reset");
            Hlc overflowed = maxed.receive(Hlc.of(1000L, Hlc.MAX_COUNTER, "w"));
            Assertions.assertEquals(next.encode(), overflowed.encode(),
                    "receive overflow behaves like next");
            Hlc roundTrip = Hlc.parse(next.encode());
            Assertions.assertEquals(next, roundTrip, "overflow output stays parseable");
        });

        r.test("hlc: equality and hash include all components", () -> {
            Hlc a = Hlc.of(1000L, 2, "p");
            Hlc b = Hlc.of(1000L, 2, "p");
            Hlc c = Hlc.of(1000L, 2, "w");
            Assertions.assertEquals(a, b, "same components are equal");
            Assertions.assertEquals(a.hashCode(), b.hashCode(), "hash codes match");
            Assertions.assertNotEquals(a, c, "different device ids differ");
            Assertions.assertEquals(a.encode(), a.toString(), "toString is the encoded form");
        });

        r.test("hlc: null-tolerant comparison helpers", () -> {
            String h1 = "0000000001000-0000-p";
            String h2 = "0000000002000-0000-p";
            Assertions.assertFalse(Hlc.isAfter(null, h1), "null is never after");
            Assertions.assertTrue(Hlc.isAfter(h1, null), "anything is after null");
            Assertions.assertTrue(Hlc.isAfter(h2, h1), "t ordering");
            Assertions.assertFalse(Hlc.isAfter(h1, h1), "equal is not after");
            Assertions.assertTrue(Hlc.isBefore(h1, h2), "isBefore is the mirror");
            Assertions.assertEquals(h2, Hlc.maxEncoded(h1, h2), "max picks larger");
            Assertions.assertEquals(h2, Hlc.maxEncoded(h2, h1), "max is symmetric");
            Assertions.assertEquals(h2, Hlc.maxEncoded(h2, null), "max ignores null");
            Assertions.assertNull(Hlc.maxEncoded(null, null), "max of two nulls");
            Assertions.assertEquals(h1, Hlc.minEncoded(h1, h2), "min picks smaller");
            Assertions.assertEquals(h1, Hlc.minEncoded(h1, null), "min ignores absent null");
            Assertions.assertEquals(h1, Hlc.minEncoded(null, h1), "min ignores absent null (left)");
            Assertions.assertNull(Hlc.minEncoded(null, null), "min of two nulls");
        });
    }
}
