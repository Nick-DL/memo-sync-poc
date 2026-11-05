package com.example.memosync.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Reassembles a chunked push/pull transfer by {@code (sid, i)} (design doc §4.5).
 *
 * <p>Guarantees needed by the POC tests:
 * <ul>
 *   <li>chunks may arrive out of order (index order is restored on assembly);</li>
 *   <li>duplicate chunks are accepted but only stored once (the caller still re-acks them);</li>
 *   <li>an empty transfer ({@code n == 0}) is complete immediately;</li>
 *   <li>invalid indices / inconsistent chunk counts are protocol errors.</li>
 * </ul>
 *
 * <p>Not thread-safe; one assembler per session, driven from the sync thread.
 */
public final class ChunkAssembler {

    private final String sessionId;
    private int totalChunks = -1;
    private boolean emptyTransferReceived;
    private final Map<Integer, List<MemoRecord>> chunks = new TreeMap<>();

    public ChunkAssembler(String sessionId) {
        this.sessionId = sessionId;
    }

    public String sessionId() {
        return sessionId;
    }

    /**
     * Accepts one chunk.
     *
     * @return true when this chunk was new, false when it was a duplicate (already stored)
     * @throws ProtocolCodec.ProtocolException on negative/out-of-range index or a chunk count
     *                                        that disagrees with earlier chunks of the session
     */
    public boolean accept(int index, int total, List<MemoRecord> records) {
        if (total < 0) {
            throw new ProtocolCodec.ProtocolException("negative chunk count: " + total);
        }
        if (index < 0 || index >= Math.max(total, 1)) {
            throw new ProtocolCodec.ProtocolException(
                    "chunk index " + index + " out of range for n=" + total);
        }
        if (totalChunks == -1) {
            totalChunks = total;
        } else if (totalChunks != total) {
            throw new ProtocolCodec.ProtocolException(
                    "inconsistent chunk count: " + total + " != " + totalChunks);
        }
        if (total == 0) {
            // Zero-chunk transfer ("no dirty records"): complete without storing anything.
            boolean fresh = !emptyTransferReceived;
            emptyTransferReceived = true;
            return fresh;
        }
        if (chunks.containsKey(index)) {
            return false;
        }
        chunks.put(index, records == null ? new ArrayList<>() : new ArrayList<>(records));
        return true;
    }

    public boolean isComplete() {
        return totalChunks >= 0 && chunks.size() == totalChunks;
    }

    public int totalChunks() {
        return totalChunks;
    }

    public int receivedChunks() {
        return chunks.size();
    }

    /** Missing chunk indices in ascending order (empty when complete). */
    public List<Integer> missing() {
        List<Integer> missing = new ArrayList<>();
        if (totalChunks < 0) {
            missing.add(0);
            return missing;
        }
        for (int i = 0; i < totalChunks; i++) {
            if (!chunks.containsKey(i)) {
                missing.add(i);
            }
        }
        return missing;
    }

    /** All records in chunk order. Only meaningful when {@link #isComplete()}. */
    public List<MemoRecord> assemble() {
        List<MemoRecord> all = new ArrayList<>();
        for (List<MemoRecord> chunk : chunks.values()) {
            all.addAll(chunk);
        }
        return all;
    }

    public void reset() {
        chunks.clear();
        totalChunks = -1;
        emptyTransferReceived = false;
    }
}
