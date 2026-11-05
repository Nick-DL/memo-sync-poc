package com.example.memosync.core;

/**
 * Output of {@link MergeEngine#merge(MemoRecord, MemoRecord, MemoRecord)}.
 *
 * <p>Mirrors the contract in {@code shared/PROTOCOL.md} §2:
 *
 * <pre>{@code
 * { "canonical": { ...MemoRecord... }, "conflict": { ...MemoRecord... } | null }
 * }</pre>
 */
public final class MergeResult {

    /** The merged canonical record; may be null only when base/phone/watch are all null. */
    public final MemoRecord canonical;

    /** Optional conflict copy ({@code id + "-conflict"}); null when there is no body conflict. */
    public final MemoRecord conflict;

    public MergeResult(MemoRecord canonical, MemoRecord conflict) {
        this.canonical = canonical;
        this.conflict = conflict;
    }

    public boolean hasConflict() {
        return conflict != null;
    }

    @Override
    public String toString() {
        return "MergeResult{canonical=" + canonical + ", conflict=" + conflict + '}';
    }
}
