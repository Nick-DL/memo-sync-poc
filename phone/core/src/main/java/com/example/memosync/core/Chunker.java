package com.example.memosync.core;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Splits a record list into wire chunks that respect a per-message byte budget
 * (design doc §4.5: target &lt;= 8 KB per P2P message, 8 KB batches in the POC).
 *
 * <p>The POC estimates a record's wire size from its encoded short-key JSON (UTF-8), plus a
 * fixed envelope allowance. A single record that on its own exceeds the budget is still sent
 * alone in its own chunk (flagged by returning a batch larger than the limit - the caller may
 * decide to reject or compress; the phone limits body to 6 KB so this should not happen).
 *
 * <p>Order is preserved; the concatenation of all batches equals the input list.
 */
public final class Chunker {

    /** Default target for one P2P message payload, in UTF-8 bytes. */
    public static final int DEFAULT_MAX_MESSAGE_BYTES = 8 * 1024;

    /** Rough envelope + short-key framing overhead reserved outside record payloads. */
    public static final int ENVELOPE_OVERHEAD_BYTES = 128;

    /** One chunk: records plus the estimated encoded size of those records. */
    public static final class Batch {
        public final List<MemoRecord> records;
        public final int estimatedRecordBytes;
        private final int maxMessageBytes;

        Batch(List<MemoRecord> records, int estimatedRecordBytes, int maxMessageBytes) {
            this.records = records;
            this.estimatedRecordBytes = estimatedRecordBytes;
            this.maxMessageBytes = maxMessageBytes;
        }

        public int size() {
            return records.size();
        }

        /** True when a single record alone does not fit this chunker's budget. */
        public boolean oversized() {
            return records.size() == 1
                    && estimatedRecordBytes + ENVELOPE_OVERHEAD_BYTES > maxMessageBytes;
        }

        @Override
        public String toString() {
            return "Batch{records=" + records.size() + ", bytes=" + estimatedRecordBytes + '}';
        }
    }

    private final ProtocolCodec codec;
    private final int maxMessageBytes;

    public Chunker(ProtocolCodec codec) {
        this(codec, DEFAULT_MAX_MESSAGE_BYTES);
    }

    public Chunker(ProtocolCodec codec, int maxMessageBytes) {
        if (maxMessageBytes <= ENVELOPE_OVERHEAD_BYTES) {
            throw new IllegalArgumentException("maxMessageBytes must be > " + ENVELOPE_OVERHEAD_BYTES);
        }
        this.codec = codec;
        this.maxMessageBytes = maxMessageBytes;
    }

    public int maxMessageBytes() {
        return maxMessageBytes;
    }

    /** Splits records into batches; an empty input produces an empty batch list. */
    public List<Batch> split(List<MemoRecord> records) {
        List<Batch> batches = new ArrayList<>();
        if (records == null || records.isEmpty()) {
            return batches;
        }
        List<MemoRecord> current = new ArrayList<>();
        int currentBytes = 0;
        for (MemoRecord record : records) {
            int recordBytes = utf8Length(codec.encodeRecord(record));
            if (!current.isEmpty()
                    && currentBytes + recordBytes + ENVELOPE_OVERHEAD_BYTES > maxMessageBytes) {
                batches.add(new Batch(current, currentBytes, maxMessageBytes));
                current = new ArrayList<>();
                currentBytes = 0;
            }
            current.add(record);
            currentBytes += recordBytes;
        }
        if (!current.isEmpty()) {
            batches.add(new Batch(current, currentBytes, maxMessageBytes));
        }
        return batches;
    }

    public static int utf8Length(String text) {
        return text == null ? 0 : text.getBytes(StandardCharsets.UTF_8).length;
    }
}
