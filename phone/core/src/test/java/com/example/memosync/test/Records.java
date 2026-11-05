package com.example.memosync.test;

import com.example.memosync.core.MemoRecord;

/** Record factories + a tiny local HLC clock for tests. */
public final class Records {

    public static final String PHONE_DEVICE = "p";
    public static final String WATCH_DEVICE = "w";

    private Records() {
    }

    public static String hlc(long t, int c, String deviceId) {
        return String.format("%013d-%04d-%s", t, c, deviceId);
    }

    /** A brand-new record whose created/updated/titleHlc/bodyHlc are the same HLC. */
    public static MemoRecord create(String id, String title, String body, long t,
                                    String deviceId, int rev) {
        return create(id, title, body, hlc(t, 0, deviceId), rev);
    }

    public static MemoRecord create(String id, String title, String body, String createdHlc,
                                    int rev) {
        MemoRecord record = new MemoRecord();
        record.id = id;
        record.title = title;
        record.body = body;
        record.created = createdHlc;
        record.updated = createdHlc;
        record.titleHlc = createdHlc;
        record.bodyHlc = createdHlc;
        record.deleted = false;
        record.deletedHlc = null;
        record.rev = rev;
        return record;
    }

    /** Copies a record and applies an edit with the given per-field HLCs. */
    public static MemoRecord edited(MemoRecord base, String title, String body,
                                    String titleHlc, String bodyHlc, String updatedHlc, int rev) {
        MemoRecord record = MemoRecord.copyOf(base);
        record.title = title;
        record.body = body;
        record.titleHlc = titleHlc;
        record.bodyHlc = bodyHlc;
        record.updated = updatedHlc;
        record.rev = rev;
        return record;
    }

    /** Copies a record and marks it deleted at the given HLC. */
    public static MemoRecord deleted(MemoRecord base, String deletedHlc, String updatedHlc, int rev) {
        MemoRecord record = MemoRecord.copyOf(base);
        record.deleted = true;
        record.deletedHlc = deletedHlc;
        record.updated = updatedHlc;
        record.rev = rev;
        return record;
    }
}
