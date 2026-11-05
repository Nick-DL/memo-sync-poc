package com.example.memosync.sync;

import com.example.memosync.core.ChunkAssembler;
import com.example.memosync.core.Chunker;
import com.example.memosync.core.Hlc;
import com.example.memosync.core.InMemoryTransport;
import com.example.memosync.core.MemoRecord;
import com.example.memosync.core.MergeEngine;
import com.example.memosync.core.MergeResult;
import com.example.memosync.core.ProtocolCodec;
import com.example.memosync.core.Transport;
import com.example.memosync.core.TransportException;
import com.example.memosync.core.WireMessage;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Phone-side sync hub state machine (design doc §4.4, §5.2).
 *
 * <p>The phone is the only session initiator and the merge authority. One session at a time;
 * a trigger that arrives mid-session only sets {@code pendingSync} (design doc §4.2).
 *
 * <p>Session flow implemented here:
 *
 * <pre>
 * watch -&gt; phone : req {dev, cur, dirty}
 * phone -&gt; watch : start {sid, cur}
 * watch -&gt; phone : push {sid, i, n, recs}   (n = 0 means "no dirty records")
 * phone -&gt; watch : pack {sid, i}            (one ack per chunk)
 *   ... once every chunk arrived: merge + three-way base update
 * phone -&gt; watch : pull {sid, i, n, recs, next} (0..N chunks; forced records flagged "f":1)
 * watch -&gt; phone : pack {sid, i}            (per pull chunk)
 * phone -&gt; watch : done {sid, cur=next}
 * </pre>
 *
 * <p>Phone-initiated sessions (app opened / saved / reconnect) call {@link #requestSync(String)}:
 * the same flow starts with {@code start} (using the stored watch cursor), the watch then pushes
 * its dirty records (or {@code n == 0}) and the rest is identical.
 *
 * <p>Robustness rules covered by the tests: duplicate chunks are re-acked but applied once;
 * out-of-order chunks are reassembled by index; messages from stale sessions are dropped;
 * duplicate {@code pack}/{@code done} are idempotent; malformed frames are ignored and counted.
 *
 * <p>This is a POC skeleton: no retry timers/timeouts (design doc §4.5 says 5s resend, 60s
 * session timeout), no persistence/threading; Android wraps it with coroutines + Room
 * ({@code phone/android/.../sync/SyncManager.kt}).
 */
public final class SyncManager {

    public enum State {
        /** No session running. */
        IDLE,
        /** {@code start} sent (or req received), waiting for push chunks. */
        WAIT_PUSH,
        /** Pull chunks sent, waiting for pack acks. */
        WAIT_PULL_ACK
    }

    /** Counters exposed for the POC tests and the settings screen. */
    public static final class Stats {
        public int sessionsStarted;
        public int sessionsCompleted;
        public int chunksReceived;
        public int chunksSent;
        public int recordsMerged;
        public int conflictsCreated;
        public int duplicateChunks;
        public int staleMessagesDropped;
        public int malformedMessages;
        public int queuedTriggers;
        public int sendFailures;

        void reset() {
            // intentionally not resetting across sessions; lifetime counters only
        }

        @Override
        public String toString() {
            return "Stats{sessions=" + sessionsStarted + '/' + sessionsCompleted
                    + ", chunks=" + chunksReceived + "in/" + chunksSent + "out"
                    + ", merged=" + recordsMerged + ", conflicts=" + conflictsCreated
                    + ", dupChunks=" + duplicateChunks + ", stale=" + staleMessagesDropped
                    + ", malformed=" + malformedMessages + ", queued=" + queuedTriggers
                    + ", sendFailures=" + sendFailures + '}';
        }
    }

    private final String localDeviceId;
    private final MemoStore store;
    private final Transport transport;
    private final ProtocolCodec codec;
    private final MergeEngine mergeEngine = new MergeEngine();
    private final Chunker chunker;
    private final Stats stats = new Stats();

    private State state = State.IDLE;
    private String peerId;
    private String sessionId;
    private String peerCursor;
    private ChunkAssembler incoming;
    private List<MemoRecord> mergedCanonicals = new ArrayList<>();
    private List<Chunker.Batch> outgoingBatches = new ArrayList<>();
    private int outgoingIndex;
    private String nextCursor;
    private boolean pendingSync;
    private String lastMergeSummary = "";

    /**
     * @param localDeviceId phone device id (short id used in HLCs; kept for app wiring/logs)
     * @param peerId        watch device id, used as the merge-base / cursor key
     */
    public SyncManager(String localDeviceId, String peerId, MemoStore store, Transport transport) {
        this(localDeviceId, peerId, store, transport, new ProtocolCodec());
    }

    public SyncManager(String localDeviceId, String peerId, MemoStore store,
                       Transport transport, ProtocolCodec codec) {
        if (store == null || transport == null || codec == null) {
            throw new IllegalArgumentException("store, transport and codec are required");
        }
        this.localDeviceId = localDeviceId;
        this.peerId = peerId;
        this.store = store;
        this.transport = transport;
        this.codec = codec;
        this.chunker = new Chunker(codec);
        transport.setListener(this::onPayload);
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /** Watch app / test hint that a session is wanted (received a req frame). */
    public void onPayload(byte[] payload) {
        WireMessage message;
        try {
            message = codec.decode(new String(payload, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            stats.malformedMessages++;
            lastMergeSummary = "malformed: " + e.getMessage();
            return;
        }
        onMessage(message);
    }

    public void onMessage(WireMessage message) {
        if (message == null || message.t == null) {
            stats.malformedMessages++;
            return;
        }
        switch (message.t) {
            case WireMessage.T_REQ:
                onReq(message);
                break;
            case WireMessage.T_PUSH:
                onPush(message);
                break;
            case WireMessage.T_PACK:
                onPack(message);
                break;
            case WireMessage.T_ERR:
                onErr(message);
                break;
            default:
                // start/pull/done are phone -> watch messages; receiving them is a peer bug.
                stats.staleMessagesDropped++;
                break;
        }
    }

    /** Phone-side trigger (app start / save / reconnect). Queues if a session is running. */
    public void requestSync(String reason) {
        if (state != State.IDLE) {
            pendingSync = true;
            stats.queuedTriggers++;
            return;
        }
        PeerState peerState = store.getPeerState(peerId);
        String cursor = peerState == null ? null : peerState.lastPullHlc;
        beginSession(cursor, "requestSync(" + reason + ")");
    }

    /** Aborts the running session (timeout / transport failure); keeps no partial state. */
    public void abortSession(String reason) {
        if (state == State.IDLE) {
            return;
        }
        String sid = sessionId;
        clearSession();
        state = State.IDLE;
        lastMergeSummary = "aborted: " + reason;
        if (sid != null) {
            trySend(WireMessage.err(sid, 1, "aborted: " + reason));
        }
        if (pendingSync) {
            pendingSync = false;
            requestSync("after-abort");
        }
    }

    public State state() {
        return state;
    }

    public String sessionId() {
        return sessionId;
    }

    public Stats stats() {
        return stats;
    }

    public String lastMergeSummary() {
        return lastMergeSummary;
    }

    public String localDeviceId() {
        return localDeviceId;
    }

    public String peerId() {
        return peerId;
    }

    // ------------------------------------------------------------------
    // Inbound message handlers
    // ------------------------------------------------------------------

    private void onReq(WireMessage message) {
        if (state != State.IDLE) {
            // Design §4.2: do not run two sessions concurrently; the watch retries later.
            pendingSync = true;
            stats.queuedTriggers++;
            return;
        }
        if (message.dev != null && peerId == null) {
            peerId = message.dev;
        }
        String cursor = message.cur != null ? message.cur : peerCursor;
        beginSession(cursor, "req");
    }

    private void onPush(WireMessage message) {
        if (!sameSession(message.sid)) {
            stats.staleMessagesDropped++;
            return;
        }
        if (state == State.WAIT_PULL_ACK) {
            // Session already advanced: duplicate/late push chunk -> re-ack it, do not re-merge.
            stats.duplicateChunks++;
            if (message.i != null) {
                trySend(WireMessage.pack(sessionId, message.i));
            }
            return;
        }
        if (state != State.WAIT_PUSH) {
            stats.staleMessagesDropped++;
            return;
        }
        if (message.i == null || message.n == null) {
            stats.malformedMessages++;
            return;
        }
        boolean fresh;
        try {
            fresh = incoming.accept(message.i, message.n, message.recs);
        } catch (RuntimeException e) {
            stats.malformedMessages++;
            lastMergeSummary = "bad push chunk: " + e.getMessage();
            return;
        }
        // Always ack, including duplicates (the peer may have resent after a lost pack).
        trySend(WireMessage.pack(sessionId, message.i));
        stats.chunksSent++;
        if (!fresh) {
            stats.duplicateChunks++;
            return;
        }
        stats.chunksReceived++;
        if (incoming.isComplete()) {
            mergeIncoming();
            startPull();
        }
    }

    private void onPack(WireMessage message) {
        if (!sameSession(message.sid)) {
            stats.staleMessagesDropped++;
            return;
        }
        if (state != State.WAIT_PULL_ACK) {
            // Duplicate pack after the session finished its pull phase: idempotent.
            stats.duplicateChunks++;
            return;
        }
        if (message.i == null) {
            stats.malformedMessages++;
            return;
        }
        if (message.i < outgoingIndex) {
            stats.duplicateChunks++;
            return;
        }
        if (message.i != outgoingIndex) {
            stats.malformedMessages++;
            return;
        }
        outgoingIndex++;
        if (outgoingIndex < outgoingBatches.size()) {
            sendPullChunk(outgoingIndex);
        } else {
            finishSession();
        }
    }

    private void onErr(WireMessage message) {
        stats.staleMessagesDropped++;
        lastMergeSummary = "peer err: " + message.code + " " + message.msg;
        if (sameSession(message.sid)) {
            clearSession();
            state = State.IDLE;
        }
    }

    // ------------------------------------------------------------------
    // Session machinery
    // ------------------------------------------------------------------

    private void beginSession(String watchCursor, String reason) {
        sessionId = UUID.randomUUID().toString();
        peerCursor = watchCursor;
        mergedCanonicals = new ArrayList<>();
        outgoingBatches = new ArrayList<>();
        outgoingIndex = 0;
        nextCursor = watchCursor;
        incoming = new ChunkAssembler(sessionId);
        state = State.WAIT_PUSH;
        stats.sessionsStarted++;
        lastMergeSummary = "started by " + reason;
        trySend(WireMessage.start(sessionId, watchCursor));
    }

    private void mergeIncoming() {
        List<MemoRecord> remoteRecords = incoming.assemble();
        int before = stats.recordsMerged;
        for (MemoRecord remote : remoteRecords) {
            if (remote == null || remote.id == null) {
                stats.malformedMessages++;
                continue;
            }
            MemoRecord local = store.get(remote.id);
            MemoRecord base = store.getBase(peerId, remote.id);
            MergeResult result = mergeEngine.merge(base, local, remote);
            if (result.canonical != null) {
                store.put(result.canonical);
                store.saveBase(peerId, remote.id, result.canonical);
                mergedCanonicals.add(MemoRecord.copyOf(result.canonical));
                stats.recordsMerged++;
            }
            if (result.conflict != null) {
                // Conflict copy is stored/listable like a normal memo (design doc §4.6).
                store.put(result.conflict);
                stats.conflictsCreated++;
            }
        }
        lastMergeSummary = "merged " + (stats.recordsMerged - before) + " record(s), conflicts="
                + stats.conflictsCreated;
    }

    private void startPull() {
        if (incoming == null) {
            return;
        }
        Map<String, MemoRecord> toSend = new LinkedHashMap<>();
        for (MemoRecord record : store.changedSince(peerCursor)) {
            toSend.put(record.id, record);
        }
        // Records produced by this merge must always be delivered, even when their updated HLC
        // is not newer than the watch cursor (design doc §4.3: field "f":1).
        for (MemoRecord merged : mergedCanonicals) {
            if (!toSend.containsKey(merged.id)) {
                MemoRecord forced = MemoRecord.copyOf(merged);
                forced.force = true;
                toSend.put(forced.id, forced);
            }
        }

        nextCursor = computeNextCursor(toSend.values());
        outgoingBatches = chunker.split(new ArrayList<>(toSend.values()));
        if (outgoingBatches.isEmpty()) {
            finishSession();
            return;
        }
        state = State.WAIT_PULL_ACK;
        outgoingIndex = 0;
        sendPullChunk(0);
    }

    private String computeNextCursor(Iterable<MemoRecord> records) {
        String cursor = peerCursor;
        for (MemoRecord record : records) {
            if (record.updated != null) {
                cursor = Hlc.maxEncoded(cursor, record.updated);
            }
        }
        return cursor;
    }

    private void sendPullChunk(int index) {
        Chunker.Batch batch = outgoingBatches.get(index);
        boolean last = index == outgoingBatches.size() - 1;
        WireMessage message = WireMessage.pull(sessionId, index, outgoingBatches.size(),
                batch.records, last ? nextCursor : null);
        trySend(message);
        stats.chunksSent++;
    }

    private void finishSession() {
        trySend(WireMessage.done(sessionId, nextCursor));
        stats.sessionsCompleted++;
        String doneCursor = nextCursor;
        clearSession();
        state = State.IDLE;
        if (peerId != null) {
            PeerState previous = store.getPeerState(peerId);
            String lastPush = previous == null ? null : previous.lastPushHlc;
            store.savePeerState(new PeerState(peerId, doneCursor, lastPush));
        }
        if (pendingSync) {
            pendingSync = false;
            requestSync("pending");
        }
    }

    private void clearSession() {
        incoming = null;
        mergedCanonicals = new ArrayList<>();
        outgoingBatches = new ArrayList<>();
        outgoingIndex = 0;
        sessionId = null;
    }

    private boolean sameSession(String sid) {
        return sessionId != null && sessionId.equals(sid);
    }

    private void trySend(WireMessage message) {
        try {
            transport.send(codec.encode(message).getBytes(StandardCharsets.UTF_8));
        } catch (TransportException e) {
            stats.sendFailures++;
            lastMergeSummary = "transport failure: " + e.getMessage();
            if (state != State.IDLE) {
                clearSession();
                state = State.IDLE;
            }
        }
    }
}
