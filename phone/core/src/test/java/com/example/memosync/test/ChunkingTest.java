package com.example.memosync.test;

import com.example.memosync.core.ChunkAssembler;
import com.example.memosync.core.Chunker;
import com.example.memosync.core.MemoRecord;
import com.example.memosync.core.ProtocolCodec;
import com.example.memosync.core.WireMessage;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Chunking + reassembly tests, including duplicate/out-of-order chunk delivery. */
public final class ChunkingTest {

    private ChunkingTest() {
    }

    private static List<MemoRecord> records(int count) {
        List<MemoRecord> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(Records.create("m-" + i, "title-" + i, "body-" + i,
                    1000L + i, Records.PHONE_DEVICE, 1));
        }
        return list;
    }

    public static void register(TestRunner r) {
        r.test("chunking: utf8 length counts Chinese/emoji correctly", () -> {
            Assertions.assertEqualsInt(3, Chunker.utf8Length("abc"), "ascii");
            Assertions.assertEqualsInt(6, Chunker.utf8Length("中文"), "Chinese = 3 bytes/char");
            Assertions.assertEqualsInt(4, Chunker.utf8Length("\uD83D\uDE00"), "emoji = 4 bytes");
            Assertions.assertEqualsInt(0, Chunker.utf8Length(null), "null text");
        });

        r.test("chunking: empty input produces no chunks", () -> {
            Chunker chunker = new Chunker(new ProtocolCodec());
            Assertions.assertEqualsInt(0, chunker.split(new ArrayList<>()).size(), "no chunks");
            Assertions.assertEqualsInt(0, chunker.split(null).size(), "null input");
        });

        r.test("chunking: small payload fits in a single chunk", () -> {
            Chunker chunker = new Chunker(new ProtocolCodec());
            List<Chunker.Batch> batches = chunker.split(records(3));
            Assertions.assertEqualsInt(1, batches.size(), "one chunk");
            Assertions.assertEqualsInt(3, batches.get(0).size(), "all records");
        });

        r.test("chunking: preserves order and content across chunks", () -> {
            Chunker chunker = new Chunker(new ProtocolCodec(), 700);
            List<MemoRecord> original = records(50);
            List<Chunker.Batch> batches = chunker.split(original);
            Assertions.assertTrue(batches.size() > 1, "expected multiple chunks");
            List<MemoRecord> roundTrip = new ArrayList<>();
            for (Chunker.Batch batch : batches) {
                roundTrip.addAll(batch.records);
            }
            Assertions.assertEquals(original, roundTrip, "flattened chunks equal the input list");
        });

        r.test("chunking: every chunk respects the byte budget", () -> {
            Chunker chunker = new Chunker(new ProtocolCodec(), 1024);
            ProtocolCodec codec = new ProtocolCodec();
            for (Chunker.Batch batch : chunker.split(records(40))) {
                int bytes = 0;
                for (MemoRecord record : batch.records) {
                    bytes += Chunker.utf8Length(codec.encodeRecord(record));
                }
                bytes += Chunker.ENVELOPE_OVERHEAD_BYTES;
                Assertions.assertTrue(bytes <= 1024 || batch.oversized(),
                        "chunk over budget but not flagged oversized: " + bytes);
            }
        });

        r.test("chunking: Chinese bodies produce more bytes than ASCII bodies", () -> {
            ProtocolCodec codec = new ProtocolCodec();
            MemoRecord ascii = Records.create("m1", "t", "abcdef", 1000L, "p", 1);
            MemoRecord chinese = Records.create("m2", "t", "中文正文", 1000L, "p", 1);
            int asciiBytes = Chunker.utf8Length(codec.encodeRecord(ascii));
            int chineseBytes = Chunker.utf8Length(codec.encodeRecord(chinese));
            Assertions.assertTrue(chineseBytes > asciiBytes,
                    "UTF-8 size must account for multibyte characters");
        });

        r.test("chunking: a single oversized record is flagged, not dropped", () -> {
            Chunker chunker = new Chunker(new ProtocolCodec(), 512);
            StringBuilder big = new StringBuilder();
            for (int i = 0; i < 600; i++) {
                big.append('x');
            }
            List<MemoRecord> list = new ArrayList<>(Arrays.asList(
                    Records.create("m-small", "t", "small", 1000L, "p", 1),
                    Records.create("m-big", "t", big.toString(), 1001L, "p", 1)));
            List<Chunker.Batch> batches = chunker.split(list);
            Assertions.assertTrue(batches.size() >= 2, "big record must be split off");
            boolean foundOversized = false;
            for (Chunker.Batch batch : batches) {
                if (batch.oversized()) {
                    foundOversized = true;
                }
            }
            Assertions.assertTrue(foundOversized, "oversized single-record chunk is flagged");
            int total = 0;
            for (Chunker.Batch batch : batches) {
                total += batch.size();
            }
            Assertions.assertEqualsInt(2, total, "no record was dropped");
        });

        r.test("chunking: assembler accepts chunks in order", () -> {
            List<MemoRecord> original = records(6);
            ChunkAssembler assembler = new ChunkAssembler("sid-1");
            Assertions.assertTrue(assembler.accept(0, 2, original.subList(0, 3)), "chunk 0");
            Assertions.assertFalse(assembler.isComplete(), "not complete yet");
            Assertions.assertTrue(assembler.accept(1, 2, original.subList(3, 6)), "chunk 1");
            Assertions.assertTrue(assembler.isComplete(), "complete");
            Assertions.assertEquals(original, assembler.assemble(), "assembled in order");
        });

        r.test("chunking: assembler accepts out-of-order chunks and restores order", () -> {
            List<MemoRecord> original = records(9);
            ChunkAssembler assembler = new ChunkAssembler("sid-2");
            Assertions.assertTrue(assembler.accept(2, 3, original.subList(6, 9)), "chunk 2 first");
            Assertions.assertTrue(assembler.accept(0, 3, original.subList(0, 3)), "chunk 0 next");
            Assertions.assertTrue(assembler.accept(1, 3, original.subList(3, 6)), "chunk 1 last");
            Assertions.assertTrue(assembler.isComplete(), "complete regardless of arrival order");
            Assertions.assertEquals(original, assembler.assemble(), "order restored by index");
        });

        r.test("chunking: duplicate chunks are accepted but stored once", () -> {
            List<MemoRecord> original = records(4);
            ChunkAssembler assembler = new ChunkAssembler("sid-3");
            Assertions.assertTrue(assembler.accept(0, 2, original.subList(0, 2)), "first copy");
            Assertions.assertFalse(assembler.accept(0, 2, original.subList(0, 2)), "duplicate");
            Assertions.assertEqualsInt(1, assembler.receivedChunks(), "count unchanged");
            Assertions.assertTrue(assembler.accept(1, 2, original.subList(2, 4)), "chunk 1");
            Assertions.assertEquals(original, assembler.assemble(), "no duplicated records");
        });

        r.test("chunking: empty transfer (n=0) is complete immediately", () -> {
            ChunkAssembler assembler = new ChunkAssembler("sid-4");
            Assertions.assertFalse(assembler.isComplete(), "not complete before any chunk");
            Assertions.assertTrue(assembler.accept(0, 0, new ArrayList<>()), "empty chunk");
            Assertions.assertTrue(assembler.isComplete(), "n=0 completes the transfer");
            Assertions.assertEqualsInt(0, assembler.assemble().size(), "no records");
            Assertions.assertEqualsInt(0, assembler.missing().size(), "nothing missing");
        });

        r.test("chunking: missing chunks are reported", () -> {
            ChunkAssembler assembler = new ChunkAssembler("sid-5");
            assembler.accept(0, 3, records(1));
            assembler.accept(2, 3, records(1));
            Assertions.assertFalse(assembler.isComplete(), "incomplete");
            Assertions.assertEquals(Arrays.asList(1), assembler.missing(), "chunk 1 missing");
        });

        r.test("chunking: invalid chunk indices and counts are protocol errors", () -> {
            ChunkAssembler assembler = new ChunkAssembler("sid-6");
            Assertions.assertThrows(ProtocolCodec.ProtocolException.class,
                    () -> assembler.accept(-1, 2, new ArrayList<>()), "negative index");
            Assertions.assertThrows(ProtocolCodec.ProtocolException.class,
                    () -> assembler.accept(2, 2, new ArrayList<>()), "index >= n");
            Assertions.assertThrows(ProtocolCodec.ProtocolException.class,
                    () -> assembler.accept(0, -1, new ArrayList<>()), "negative n");
            assembler.accept(0, 2, records(1));
            Assertions.assertThrows(ProtocolCodec.ProtocolException.class,
                    () -> assembler.accept(1, 3, records(1)), "chunk count changed mid-transfer");
        });

        r.test("chunking: reset clears the transfer", () -> {
            ChunkAssembler assembler = new ChunkAssembler("sid-7");
            assembler.accept(0, 2, records(1));
            assembler.reset();
            Assertions.assertFalse(assembler.isComplete(), "not complete after reset");
            Assertions.assertEqualsInt(0, assembler.receivedChunks(), "no chunks after reset");
        });

        r.test("chunking: split -> wire -> decode -> assemble round trip", () -> {
            ProtocolCodec codec = new ProtocolCodec();
            Chunker chunker = new Chunker(codec, 900);
            List<MemoRecord> original = records(25);
            List<Chunker.Batch> batches = chunker.split(original);
            ChunkAssembler assembler = new ChunkAssembler("sid-wire");
            for (int i = 0; i < batches.size(); i++) {
                WireMessage message = WireMessage.push("sid-wire", i, batches.size(),
                        batches.get(i).records);
                String json = codec.encode(message);
                WireMessage decoded = codec.decode(json);
                Assertions.assertEqualsInt(i, decoded.i, "chunk index survives the wire");
                assembler.accept(decoded.i, decoded.n, decoded.recs);
            }
            Assertions.assertTrue(assembler.isComplete(), "all wire chunks received");
            List<MemoRecord> reassembled = assembler.assemble();
            Assertions.assertEqualsInt(original.size(), reassembled.size(), "same record count");
            for (int i = 0; i < original.size(); i++) {
                Assertions.assertEquals(original.get(i).id, reassembled.get(i).id,
                        "record order preserved at " + i);
                Assertions.assertEquals(original.get(i).body, reassembled.get(i).body,
                        "record body preserved at " + i);
            }
        });

        r.test("chunking: default 8KB budget keeps small records in one message", () -> {
            ProtocolCodec codec = new ProtocolCodec();
            Chunker chunker = new Chunker(codec);
            MemoRecord record = Records.create("m1", "title",
                    "hello world", 1000L, "p", 1);
            WireMessage message = WireMessage.pull("sid", 0, 1,
                    Arrays.asList(record), "0000000001000-0000-p");
            int payloadBytes = codec.encode(message).getBytes(StandardCharsets.UTF_8).length;
            Assertions.assertTrue(payloadBytes < Chunker.DEFAULT_MAX_MESSAGE_BYTES,
                    "small message within the P2P budget: " + payloadBytes + " bytes");
            Assertions.assertEqualsInt(1, chunker.split(Arrays.asList(record)).size(),
                    "one chunk for one small record");
        });
    }
}
