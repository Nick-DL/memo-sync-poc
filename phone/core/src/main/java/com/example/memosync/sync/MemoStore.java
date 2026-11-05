package com.example.memosync.sync;

import com.example.memosync.core.MemoRecord;

import java.util.List;

/**
 * Repository abstraction the {@link SyncManager} needs (design doc §3.3 / §5.1).
 *
 * <p>Java 17 core keeps this as an interface so the POC can use
 * {@link InMemoryMemoStore}, while the Android app implements it with Room
 * ({@code MemoDao} + {@code MemoPeerBaseDao} + {@code SyncStateDao}).
 */
public interface MemoStore {

    /** Current local canonical record, or null. */
    MemoRecord get(String id);

    /** Inserts or replaces the canonical record (including conflict copies). */
    void put(MemoRecord record);

    /** All records, for tests/diagnostics. */
    List<MemoRecord> all();

    /**
     * Records whose {@code updated} HLC is strictly newer than {@code cursorHlc}; a null cursor
     * means "everything". Results are ordered by updated HLC, then id, to keep POC runs
     * deterministic.
     */
    List<MemoRecord> changedSince(String cursorHlc);

    /** Three-way merge base for a peer (last canonical record that was synced). */
    MemoRecord getBase(String peerId, String memoId);

    void saveBase(String peerId, String memoId, MemoRecord canonical);

    PeerState getPeerState(String peerId);

    void savePeerState(PeerState peerState);
}
