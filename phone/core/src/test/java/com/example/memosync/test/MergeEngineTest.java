package com.example.memosync.test;

import com.example.memosync.core.MemoRecord;
import com.example.memosync.core.MergeEngine;
import com.example.memosync.core.MergeResult;

/**
 * MergeEngine rule tests (shared/PROTOCOL.md §2). The shared fixture cases themselves are
 * exercised by {@link MergeCasesTest}; here we pin the individual rules, including the
 * edge cases the fixture does not contain.
 */
public final class MergeEngineTest {

    private static final MergeEngine ENGINE = new MergeEngine();

    private MergeEngineTest() {
    }

    public static void register(TestRunner r) {
        r.test("merge: both null -> null canonical and no conflict", () -> {
            MergeResult result = ENGINE.merge(null, null, null);
            Assertions.assertNull(result.canonical, "canonical");
            Assertions.assertNull(result.conflict, "conflict");
        });

        r.test("merge: phone only -> phone becomes canonical unchanged", () -> {
            MemoRecord phone = Records.create("m1", "P", "phone", 1100L, Records.PHONE_DEVICE, 1);
            MergeResult result = ENGINE.merge(null, phone, null);
            Assertions.assertEquals(phone, result.canonical, "canonical equals phone");
            Assertions.assertNotEquals(phone, null, "sanity");
            Assertions.assertNull(result.conflict, "conflict");
            Assertions.assertEqualsInt(1, result.canonical.rev, "rev unchanged for one-sided merge");
        });

        r.test("merge: watch only -> watch becomes canonical unchanged", () -> {
            MemoRecord watch = Records.create("m1", "W", "watch", 1000L, Records.WATCH_DEVICE, 1);
            MergeResult result = ENGINE.merge(null, null, watch);
            Assertions.assertEquals(watch, result.canonical, "canonical equals watch");
            Assertions.assertNull(result.conflict, "conflict");
        });

        r.test("merge: field-level LWW takes the newer title and body independently", () -> {
            MemoRecord phone = Records.create("m1", "B", "X", 1000L, Records.PHONE_DEVICE, 2);
            phone.titleHlc = Records.hlc(2000L, 0, Records.PHONE_DEVICE);
            phone.bodyHlc = Records.hlc(1000L, 0, Records.PHONE_DEVICE);

            MemoRecord watch = Records.create("m1", "A", "Y", 1000L, Records.PHONE_DEVICE, 2);
            watch.titleHlc = Records.hlc(1000L, 0, Records.PHONE_DEVICE);
            watch.bodyHlc = Records.hlc(3000L, 0, Records.WATCH_DEVICE);
            watch.updated = Records.hlc(3000L, 0, Records.WATCH_DEVICE);

            MergeResult result = ENGINE.merge(null, phone, watch);
            Assertions.assertEquals("B", result.canonical.title, "phone's newer title");
            Assertions.assertEquals("Y", result.canonical.body, "watch's newer body");
            Assertions.assertEqualsInt(3, result.canonical.rev, "rev = max(rev)+1 = 3");
        });

        r.test("merge: equal field HLC with different content -> phone wins tie-break", () -> {
            MemoRecord phone = Records.create("m1", "P", "P-body", 2000L, Records.PHONE_DEVICE, 2);
            // Copy the phone record so every HLC string is identical; only content differs.
            MemoRecord watch = MemoRecord.copyOf(phone);
            watch.title = "W";
            watch.body = "W-body";
            MergeResult result = ENGINE.merge(null, phone, watch);
            Assertions.assertEquals("P", result.canonical.title, "phone title wins the tie");
            Assertions.assertEquals("P-body", result.canonical.body, "phone body wins the tie");
        });

        r.test("merge: created=min, updated=max, rev=max+1", () -> {
            MemoRecord phone = new MemoRecord("m1", "A", "a",
                    "0000000001000-0000-p", "0000000005000-0000-p",
                    "0000000001000-0000-p", "0000000001000-0000-p", false, null, 3);
            MemoRecord watch = new MemoRecord("m1", "A", "a",
                    "0000000002000-0000-w", "0000000004000-0000-w",
                    "0000000002000-0000-w", "0000000002000-0000-w", false, null, 5);
            MergeResult result = ENGINE.merge(null, phone, watch);
            Assertions.assertEquals("0000000001000-0000-p", result.canonical.created,
                    "created takes the smaller HLC");
            Assertions.assertEquals("0000000005000-0000-p", result.canonical.updated,
                    "updated takes the larger HLC");
            Assertions.assertEqualsInt(6, result.canonical.rev, "rev = max(rev)+1");
            Assertions.assertEquals("m1", result.canonical.id, "id preserved");
        });

        r.test("merge: neither deleted -> deleted=false and deletedHlc cleared", () -> {
            MemoRecord phone = Records.create("m1", "A", "a", 1000L, Records.PHONE_DEVICE, 1);
            phone.deletedHlc = "0000000000500-0000-p"; // stale value must be cleared
            MemoRecord watch = Records.create("m1", "A", "a", 1000L, Records.WATCH_DEVICE, 1);
            MergeResult result = ENGINE.merge(null, phone, watch);
            Assertions.assertFalse(result.canonical.deleted, "alive");
            Assertions.assertNull(result.canonical.deletedHlc, "deletedHlc cleared");
        });

        r.test("merge: both deleted -> keeps the larger deletedHlc", () -> {
            MemoRecord phone = Records.deleted(
                    Records.create("m1", "A", "a", 1000L, Records.PHONE_DEVICE, 1),
                    "0000000003000-0000-p", "0000000003000-0000-p", 2);
            MemoRecord watch = Records.deleted(
                    Records.create("m1", "A", "a", 1000L, Records.WATCH_DEVICE, 1),
                    "0000000004000-0000-w", "0000000004000-0000-w", 2);
            MergeResult result = ENGINE.merge(null, phone, watch);
            Assertions.assertTrue(result.canonical.deleted, "still deleted");
            Assertions.assertEquals("0000000004000-0000-w", result.canonical.deletedHlc,
                    "largest deletion HLC");
        });

        r.test("merge: phone deleted, watch edited later -> edit revives the record", () -> {
            MemoRecord phone = Records.deleted(
                    Records.create("m1", "A", "P", 1000L, Records.PHONE_DEVICE, 2),
                    "0000000003000-0000-p", "0000000003000-0000-p", 2);
            MemoRecord watch = Records.edited(
                    Records.create("m1", "A", "X", 1000L, Records.PHONE_DEVICE, 2),
                    "A", "W", "0000000001000-0000-p", "0000000004000-0000-w",
                    "0000000004000-0000-w", 2);
            MergeResult result = ENGINE.merge(null, phone, watch);
            Assertions.assertFalse(result.canonical.deleted, "edit wins");
            Assertions.assertNull(result.canonical.deletedHlc, "deletion HLC cleared");
            Assertions.assertEquals("W", result.canonical.body, "edited body kept");
        });

        r.test("merge: phone deleted, watch edit older -> stays deleted", () -> {
            MemoRecord phone = Records.deleted(
                    Records.create("m1", "A", "P", 1000L, Records.PHONE_DEVICE, 2),
                    "0000000005000-0000-p", "0000000005000-0000-p", 2);
            MemoRecord watch = Records.edited(
                    Records.create("m1", "A", "X", 1000L, Records.PHONE_DEVICE, 2),
                    "A", "W", "0000000001000-0000-p", "0000000004000-0000-w",
                    "0000000004000-0000-w", 2);
            MergeResult result = ENGINE.merge(null, phone, watch);
            Assertions.assertTrue(result.canonical.deleted, "delete wins");
            Assertions.assertEquals("0000000005000-0000-p", result.canonical.deletedHlc,
                    "deletion HLC kept");
        });

        r.test("merge: watch deleted, phone edited later -> edit revives the record", () -> {
            MemoRecord watch = Records.deleted(
                    Records.create("m1", "A", "X", 1000L, Records.PHONE_DEVICE, 2),
                    "0000000003000-0000-w", "0000000003000-0000-w", 2);
            MemoRecord phone = Records.edited(
                    Records.create("m1", "A", "X", 1000L, Records.PHONE_DEVICE, 2),
                    "A", "P", "0000000001000-0000-p", "0000000004000-0000-p",
                    "0000000004000-0000-p", 2);
            MergeResult result = ENGINE.merge(null, phone, watch);
            Assertions.assertFalse(result.canonical.deleted, "edit wins");
            Assertions.assertNull(result.canonical.deletedHlc, "deletion HLC cleared");
        });

        r.test("merge: delete/edit tie -> deletion wins (matches reference)", () -> {
            String hlc = "0000000003000-0000-p";
            MemoRecord phone = Records.deleted(
                    Records.create("m1", "A", "P", 1000L, Records.PHONE_DEVICE, 2),
                    hlc, hlc, 2);
            MemoRecord watch = Records.edited(
                    Records.create("m1", "A", "X", 1000L, Records.PHONE_DEVICE, 2),
                    "A", "W", "0000000001000-0000-p", "0000000004000-0000-w",
                    hlc, 2); // updated HLC exactly equals the deletion HLC
            MergeResult result = ENGINE.merge(null, phone, watch);
            Assertions.assertTrue(result.canonical.deleted,
                    "strictly-newer edit is required to revive; a tie keeps the deletion");
        });

        r.test("merge: deletion wins but body still takes the newer field HLC", () -> {
            // This is the edit_vs_delete shared case semantics after the contract update:
            // deleted=true because the delete is newer than the edit, but the canonical body
            // is the watch's newer body (field-level merge is independent from the tombstone).
            MemoRecord phone = Records.deleted(
                    Records.create("m1", "A", "X", 1000L, Records.PHONE_DEVICE, 2),
                    "0000000005000-0000-p", "0000000005000-0000-p", 2);
            MemoRecord watch = Records.edited(
                    Records.create("m1", "A", "X", 1000L, Records.PHONE_DEVICE, 2),
                    "A", "W", "0000000001000-0000-p", "0000000004000-0000-w",
                    "0000000004000-0000-w", 2);
            MergeResult result = ENGINE.merge(null, phone, watch);
            Assertions.assertTrue(result.canonical.deleted, "deletion wins the flag");
            Assertions.assertEquals("W", result.canonical.body, "newer body still wins");
            Assertions.assertEquals("0000000005000-0000-p", result.canonical.updated,
                    "updated = max(phone.updated, watch.updated)");
        });

        r.test("merge: body conflict creates a conflict copy for the smaller-HLC loser", () -> {
            MemoRecord base = Records.create("m1", "A", "X", 1000L, Records.PHONE_DEVICE, 1);
            MemoRecord phone = Records.edited(base, "A", "P",
                    "0000000001000-0000-p", "0000000004000-0000-p",
                    "0000000004000-0000-p", 2);
            MemoRecord watch = Records.edited(base, "A", "W",
                    "0000000001000-0000-p", "0000000005000-0000-w",
                    "0000000005000-0000-w", 2);
            MergeResult result = ENGINE.merge(base, phone, watch);
            Assertions.assertNotNull(result.conflict, "conflict expected");
            Assertions.assertEquals("m1-conflict", result.conflict.id, "conflict id");
            Assertions.assertEquals("[冲突] A", result.conflict.title, "conflict title");
            Assertions.assertEquals("P", result.conflict.body, "phone is the loser (4000 < 5000)");
            Assertions.assertFalse(result.conflict.deleted, "conflict copy is alive");
            Assertions.assertEqualsInt(1, result.conflict.rev, "conflict rev is 1");
            Assertions.assertEquals("W", result.canonical.body, "canonical keeps the winner");
        });

        r.test("merge: body conflict with watch as loser", () -> {
            MemoRecord base = Records.create("m1", "A", "X", 1000L, Records.PHONE_DEVICE, 1);
            MemoRecord phone = Records.edited(base, "A", "P",
                    "0000000001000-0000-p", "0000000006000-0000-p",
                    "0000000006000-0000-p", 2);
            MemoRecord watch = Records.edited(base, "A", "W",
                    "0000000001000-0000-p", "0000000005000-0000-w",
                    "0000000005000-0000-w", 2);
            MergeResult result = ENGINE.merge(base, phone, watch);
            Assertions.assertEquals("[冲突] A", result.conflict.title, "watch loses");
            Assertions.assertEquals("W", result.conflict.body, "watch body in conflict copy");
            Assertions.assertEquals("P", result.canonical.body, "phone body in canonical");
        });

        r.test("merge: equal body HLC conflict -> watch is the loser", () -> {
            MemoRecord base = Records.create("m1", "A", "X", 1000L, Records.PHONE_DEVICE, 1);
            String tied = "0000000004000-0000-p";
            MemoRecord phone = Records.edited(base, "A", "P",
                    "0000000001000-0000-p", tied, tied, 2);
            MemoRecord watch = Records.edited(base, "A", "W",
                    "0000000001000-0000-w", tied, tied, 2);
            MergeResult result = ENGINE.merge(base, phone, watch);
            Assertions.assertEquals("W", result.conflict.body, "tie -> watch is the loser");
            Assertions.assertEquals("P", result.canonical.body, "phone wins the tie");
        });

        r.test("merge: no conflict when both bodies changed to the same text", () -> {
            MemoRecord base = Records.create("m1", "A", "X", 1000L, Records.PHONE_DEVICE, 1);
            MemoRecord phone = Records.edited(base, "A", "same",
                    "0000000001000-0000-p", "0000000004000-0000-p",
                    "0000000004000-0000-p", 2);
            MemoRecord watch = Records.edited(base, "A", "same",
                    "0000000001000-0000-p", "0000000005000-0000-w",
                    "0000000005000-0000-w", 2);
            MergeResult result = ENGINE.merge(base, phone, watch);
            Assertions.assertNull(result.conflict, "same content -> no conflict copy");
            Assertions.assertEquals("same", result.canonical.body, "canonical body");
        });

        r.test("merge: no conflict when only one side changed the body", () -> {
            MemoRecord base = Records.create("m1", "A", "X", 1000L, Records.PHONE_DEVICE, 1);
            MemoRecord phone = Records.edited(base, "A", "P",
                    "0000000001000-0000-p", "0000000004000-0000-p",
                    "0000000004000-0000-p", 2);
            MemoRecord watch = Records.create("m1", "A", "X", 1000L, Records.PHONE_DEVICE, 2);
            MergeResult result = ENGINE.merge(base, phone, watch);
            Assertions.assertNull(result.conflict, "watch body unchanged -> no conflict");
        });

        r.test("merge: no conflict when base is null", () -> {
            MemoRecord phone = Records.create("m1", "A", "P", 2000L, Records.PHONE_DEVICE, 2);
            MemoRecord watch = Records.create("m1", "A", "W", 3000L, Records.WATCH_DEVICE, 2);
            MergeResult result = ENGINE.merge(null, phone, watch);
            Assertions.assertNull(result.conflict, "without a base there is no three-way conflict");
        });

        r.test("merge: result records are defensive copies (inputs are not aliased)", () -> {
            MemoRecord phone = Records.create("m1", "A", "P", 2000L, Records.PHONE_DEVICE, 1);
            MemoRecord watch = Records.create("m1", "A", "W", 3000L, Records.WATCH_DEVICE, 1);
            MergeResult result = ENGINE.merge(null, phone, watch);
            Assertions.assertNotEquals(phone, result.canonical, "canonical is a copy");
            String snapshot = result.canonical.body;
            phone.body = "mutated";
            Assertions.assertEquals(snapshot, result.canonical.body, "mutating phone must not leak");
        });
    }
}
