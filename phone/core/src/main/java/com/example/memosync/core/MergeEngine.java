package com.example.memosync.core;

import java.util.Objects;

/**
 * Three-way merge for one memo record.
 *
 * <p>This is the POC's intentionally simplified merge (no diff3, no CRDT), exactly as
 * specified by {@code shared/PROTOCOL.md} §2. The implementation is a line-by-line port of
 * the Lead's conformance reference {@code memo-sync-poc/tools/reference_merge.py}; both must
 * produce identical canonical/conflict content for {@code shared/merge_cases.json}.
 *
 * <p>Summary of the rules:
 * <ol>
 *   <li>one side null: the other side is canonical as-is;</li>
 *   <li>field-level LWW on {@code titleHlc} / {@code bodyHlc}; equal HLC -&gt; phone wins;</li>
 *   <li>deletion: compare the deleting side's {@code deletedHlc} with the editing side's
 *       {@code updated} (edit wins only when strictly newer); both deleted -&gt; keep the
 *       larger deletion HLC;</li>
 *   <li>{@code created} = min HLC, {@code updated} = max HLC, {@code rev} = max(rev) + 1;</li>
 *   <li>body conflict copy when both sides changed body since {@code base} and the bodies
 *       differ; loser = smaller {@code bodyHlc} (tie -&gt; watch).</li>
 * </ol>
 *
 * <p>Note that field-level title/body merging still happens when a deletion wins - this is
 * what the shared case {@code edit_vs_delete} pins down (deleted=true, body=watch's newer body).
 */
public final class MergeEngine {

    public static final String CONFLICT_ID_SUFFIX = "-conflict";
    public static final String CONFLICT_TITLE_PREFIX = "[冲突] ";

    /**
     * @param base  canonical record from the last successful sync, or null
     * @param phone phone-side current record, or null
     * @param watch watch-side current record, or null
     */
    public MergeResult merge(MemoRecord base, MemoRecord phone, MemoRecord watch) {
        // Rule 1: one side missing -> take the other verbatim.
        if (phone == null && watch == null) {
            return new MergeResult(null, null);
        }
        if (phone == null) {
            return new MergeResult(MemoRecord.copyOf(watch), null);
        }
        if (watch == null) {
            return new MergeResult(MemoRecord.copyOf(phone), null);
        }

        // Rule 2 baseline: start from the phone record; watch only overrides a field when
        // its field HLC is strictly larger (equal HLC => phone wins, deterministic tie-break).
        MemoRecord canonical = MemoRecord.copyOf(phone);
        canonical.base = null;
        canonical.force = false;

        if (Hlc.isAfter(watch.titleHlc, phone.titleHlc)) {
            canonical.title = watch.title;
            canonical.titleHlc = watch.titleHlc;
        }
        if (Hlc.isAfter(watch.bodyHlc, phone.bodyHlc)) {
            canonical.body = watch.body;
            canonical.bodyHlc = watch.bodyHlc;
        }

        // Rule 4: deletion vs edit. "Edit wins" requires the edit to be strictly newer;
        // on an exact tie the deletion wins (matches reference_merge.py).
        if (phone.deleted && watch.deleted) {
            canonical.deleted = true;
            canonical.deletedHlc = Hlc.maxEncoded(phone.deletedHlc, watch.deletedHlc);
        } else if (phone.deleted) {
            if (Hlc.isAfter(watch.updated, phone.deletedHlc)) {
                canonical.deleted = false;
                canonical.deletedHlc = null;
            } else {
                canonical.deleted = true;
                canonical.deletedHlc = phone.deletedHlc;
            }
        } else if (watch.deleted) {
            if (Hlc.isAfter(phone.updated, watch.deletedHlc)) {
                canonical.deleted = false;
                canonical.deletedHlc = null;
            } else {
                canonical.deleted = true;
                canonical.deletedHlc = watch.deletedHlc;
            }
        } else {
            canonical.deleted = false;
            canonical.deletedHlc = null;
        }

        canonical.created = Hlc.minEncoded(phone.created, watch.created);
        canonical.updated = Hlc.maxEncoded(phone.updated, watch.updated);
        canonical.rev = Math.max(phone.rev, watch.rev) + 1;

        return new MergeResult(canonical, buildConflictIfNeeded(base, phone, watch, canonical));
    }

    /**
     * Rule 3: conflict copy.
     *
     * <p>Conditions: base is known; both sides changed {@code bodyHlc} since base (string
     * inequality, matching the reference); the two bodies differ. The loser is the side with
     * the smaller {@code bodyHlc}; on equal HLC the watch is the loser.
     *
     * <p>The conflict record carries the contract fields (id/title/body/deleted/rev) plus
     * document columns so it can be stored and listed like a normal memo. Only
     * title/body are part of the shared test contract.
     */
    private MemoRecord buildConflictIfNeeded(MemoRecord base, MemoRecord phone,
                                             MemoRecord watch, MemoRecord canonical) {
        if (base == null) {
            return null;
        }
        boolean phoneBodyChanged = !Objects.equals(phone.bodyHlc, base.bodyHlc);
        boolean watchBodyChanged = !Objects.equals(watch.bodyHlc, base.bodyHlc);
        if (!phoneBodyChanged || !watchBodyChanged) {
            return null;
        }
        if (Objects.equals(phone.body, watch.body)) {
            return null; // both edited to the same text: no conflict
        }

        MemoRecord loser = Hlc.isBefore(phone.bodyHlc, watch.bodyHlc) ? phone : watch;
        MemoRecord conflict = new MemoRecord();
        // Contract uses phone.id; phone is guaranteed non-null on this path.
        conflict.id = (phone.id == null ? "" : phone.id) + CONFLICT_ID_SUFFIX;
        conflict.title = CONFLICT_TITLE_PREFIX + (loser.title == null ? "" : loser.title);
        conflict.body = loser.body;
        conflict.deleted = false;
        conflict.deletedHlc = null;
        conflict.rev = 1;
        // Storage-only columns (not compared by the shared contract).
        conflict.created = canonical.created;
        conflict.updated = canonical.updated;
        conflict.titleHlc = canonical.updated;
        conflict.bodyHlc = canonical.updated;
        conflict.base = null;
        conflict.force = false;
        return conflict;
    }
}
