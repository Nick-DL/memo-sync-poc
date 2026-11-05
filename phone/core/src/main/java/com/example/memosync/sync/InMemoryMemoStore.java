package com.example.memosync.sync;

import com.example.memosync.core.Hlc;
import com.example.memosync.core.MemoRecord;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory {@link MemoStore} used by the JVM tests and the desktop POC harness.
 *
 * <p>Not a database: no durability, no threading guarantees beyond a coarse {@code synchronized}
 * on each operation. The Android implementation replaces it with Room (see
 * {@code phone/android/.../data/MemoDao.kt}).
 */
public final class InMemoryMemoStore implements MemoStore {

    private static final String BASE_KEY_SEPARATOR = "\u0000";

    private final Map<String, MemoRecord> memos = new LinkedHashMap<>();
    private final Map<String, MemoRecord> bases = new HashMap<>();
    private final Map<String, PeerState> peers = new HashMap<>();

    @Override
    public synchronized MemoRecord get(String id) {
        return MemoRecord.copyOf(memos.get(id));
    }

    @Override
    public synchronized void put(MemoRecord record) {
        if (record == null || record.id == null) {
            throw new IllegalArgumentException("record and record.id are required");
        }
        memos.put(record.id, MemoRecord.copyOf(record));
    }

    @Override
    public synchronized List<MemoRecord> all() {
        List<MemoRecord> copy = new ArrayList<>(memos.size());
        for (MemoRecord record : memos.values()) {
            copy.add(MemoRecord.copyOf(record));
        }
        return copy;
    }

    @Override
    public synchronized List<MemoRecord> changedSince(String cursorHlc) {
        List<MemoRecord> changed = new ArrayList<>();
        for (MemoRecord record : memos.values()) {
            if (record.updated == null) {
                continue;
            }
            if (cursorHlc == null || Hlc.isAfter(record.updated, cursorHlc)) {
                changed.add(MemoRecord.copyOf(record));
            }
        }
        changed.sort(Comparator
                .comparing((MemoRecord r) -> Hlc.parse(r.updated))
                .thenComparing(r -> r.id == null ? "" : r.id));
        return changed;
    }

    @Override
    public synchronized MemoRecord getBase(String peerId, String memoId) {
        return MemoRecord.copyOf(bases.get(baseKey(peerId, memoId)));
    }

    @Override
    public synchronized void saveBase(String peerId, String memoId, MemoRecord canonical) {
        if (canonical == null) {
            bases.remove(baseKey(peerId, memoId));
            return;
        }
        MemoRecord copy = MemoRecord.copyOf(canonical);
        copy.base = null;
        copy.force = false;
        bases.put(baseKey(peerId, memoId), copy);
    }

    @Override
    public synchronized PeerState getPeerState(String peerId) {
        PeerState state = peers.get(peerId);
        if (state == null) {
            return null;
        }
        return new PeerState(state.peerId, state.lastPullHlc, state.lastPushHlc);
    }

    @Override
    public synchronized void savePeerState(PeerState peerState) {
        if (peerState == null || peerState.peerId == null) {
            throw new IllegalArgumentException("peerState and peerState.peerId are required");
        }
        peers.put(peerState.peerId, new PeerState(
                peerState.peerId, peerState.lastPullHlc, peerState.lastPushHlc));
    }

    /** Test helper: remove everything (simulates a fresh device / DB reset). */
    public synchronized void clearAll() {
        memos.clear();
        bases.clear();
        peers.clear();
    }

    public synchronized int size() {
        return memos.size();
    }

    private static String baseKey(String peerId, String memoId) {
        return (peerId == null ? "" : peerId) + BASE_KEY_SEPARATOR
                + (memoId == null ? "" : memoId);
    }
}
