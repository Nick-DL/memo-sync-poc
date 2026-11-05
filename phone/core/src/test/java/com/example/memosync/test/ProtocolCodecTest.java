package com.example.memosync.test;

import com.example.memosync.core.MemoRecord;
import com.example.memosync.core.ProtocolCodec;
import com.example.memosync.core.WireMessage;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Short-key wire format tests (design doc §4.3 / appendix A). */
public final class ProtocolCodecTest {

    private ProtocolCodecTest() {
    }

    public static void register(TestRunner r) {
        ProtocolCodec codec = new ProtocolCodec();

        r.test("codec: req envelope uses short keys", () -> {
            WireMessage req = WireMessage.req("w-a1b2c3d4", "1844312345000-0007-w-a1b2c3d4", 2);
            JsonObject json = JsonParser.parseString(codec.encode(req)).getAsJsonObject();
            Assertions.assertEqualsInt(1, json.get("v").getAsInt(), "v");
            Assertions.assertEquals("req", json.get("t").getAsString(), "t");
            Assertions.assertEquals("w-a1b2c3d4", json.get("dev").getAsString(), "dev");
            Assertions.assertEquals("1844312345000-0007-w-a1b2c3d4",
                    json.get("cur").getAsString(), "cur");
            Assertions.assertEqualsInt(2, json.get("dirty").getAsInt(), "dirty");
            Assertions.assertFalse(json.has("sid"), "req has no session id");
        });

        r.test("codec: req round trip", () -> {
            WireMessage req = WireMessage.req("w-a1b2c3d4", "0000000001000-0000-p", 0);
            WireMessage decoded = codec.decode(codec.encode(req));
            Assertions.assertEquals(WireMessage.T_REQ, decoded.t, "type");
            Assertions.assertEquals("w-a1b2c3d4", decoded.dev, "dev");
            Assertions.assertEquals("0000000001000-0000-p", decoded.cur, "cur");
            Assertions.assertEquals(Integer.valueOf(0), decoded.dirty, "dirty");
            Assertions.assertNull(decoded.sid, "sid stays absent");
        });

        r.test("codec: start/done/pack messages", () -> {
            String startJson = codec.encode(WireMessage.start("sid-1", "cur-1"));
            JsonObject start = JsonParser.parseString(startJson).getAsJsonObject();
            Assertions.assertEquals("start", start.get("t").getAsString(), "start type");
            Assertions.assertEquals("sid-1", start.get("sid").getAsString(), "start sid");
            WireMessage startDecoded = codec.decode(startJson);
            Assertions.assertEquals("sid-1", startDecoded.sid, "decoded sid");
            Assertions.assertEquals("cur-1", startDecoded.cur, "decoded cur");

            WireMessage packDecoded = codec.decode(
                    codec.encode(WireMessage.pack("sid-2", 3)));
            Assertions.assertEquals(WireMessage.T_PACK, packDecoded.t, "pack type");
            Assertions.assertEquals(Integer.valueOf(3), packDecoded.i, "pack index");
            Assertions.assertNull(packDecoded.cur, "pack has no cursor");

            WireMessage doneDecoded = codec.decode(
                    codec.encode(WireMessage.done("sid-3", "next-1")));
            Assertions.assertEquals(WireMessage.T_DONE, doneDecoded.t, "done type");
            Assertions.assertEquals("next-1", doneDecoded.cur, "done cursor");
        });

        r.test("codec: record short keys + del 0/1 + explicit null dh", () -> {
            MemoRecord record = Records.create("m1", "标题", "正文\n第二行",
                    1000L, Records.PHONE_DEVICE, 3);
            record.base = "0000000000500-0000-p";
            WireMessage push = WireMessage.push("sid-1", 0, 1,
                    Collections.singletonList(record));
            String json = codec.encode(push);
            JsonObject envelope = JsonParser.parseString(json).getAsJsonObject();
            JsonObject wireRecord = envelope.getAsJsonArray("recs").get(0).getAsJsonObject();
            Assertions.assertEquals("m1", wireRecord.get("id").getAsString(), "id");
            Assertions.assertEquals("标题", wireRecord.get("ti").getAsString(), "ti");
            Assertions.assertEquals("正文\n第二行", wireRecord.get("bo").getAsString(), "bo");
            Assertions.assertEquals(record.created, wireRecord.get("c").getAsString(), "c");
            Assertions.assertEquals(record.updated, wireRecord.get("u").getAsString(), "u");
            Assertions.assertEquals(record.titleHlc, wireRecord.get("th").getAsString(), "th");
            Assertions.assertEquals(record.bodyHlc, wireRecord.get("bh").getAsString(), "bh");
            Assertions.assertTrue(wireRecord.get("del").getAsJsonPrimitive().isNumber(),
                    "del must be encoded as 0/1 number");
            Assertions.assertEqualsInt(0, wireRecord.get("del").getAsInt(), "del=0");
            Assertions.assertTrue(wireRecord.get("dh").isJsonNull(), "dh is explicit null");
            Assertions.assertEqualsInt(3, wireRecord.get("rev").getAsInt(), "rev");
            Assertions.assertEquals("0000000000500-0000-p",
                    wireRecord.get("base").getAsString(), "base included on push");
            Assertions.assertFalse(wireRecord.has("f"), "force flag omitted when false");

            MemoRecord decoded = codec.decode(json).recs.get(0);
            Assertions.assertEquals(record, decoded, "record survives wire round trip");
        });

        r.test("codec: deleted record uses del=1 and dh", () -> {
            MemoRecord record = Records.create("m1", "A", "B", 1000L, Records.PHONE_DEVICE, 2);
            record.deleted = true;
            record.deletedHlc = "0000000002000-0000-p";
            WireMessage push = WireMessage.push("sid", 0, 1,
                    Collections.singletonList(record));
            JsonObject wireRecord = JsonParser.parseString(codec.encode(push))
                    .getAsJsonObject().getAsJsonArray("recs").get(0).getAsJsonObject();
            Assertions.assertEqualsInt(1, wireRecord.get("del").getAsInt(), "del=1");
            Assertions.assertEquals("0000000002000-0000-p",
                    wireRecord.get("dh").getAsString(), "dh carried");
            MemoRecord decoded = codec.decode(codec.encode(push)).recs.get(0);
            Assertions.assertTrue(decoded.deleted, "decoded deleted flag");
            Assertions.assertEquals("0000000002000-0000-p", decoded.deletedHlc, "decoded dh");
        });

        r.test("codec: force pull record carries f=1", () -> {
            MemoRecord record = Records.create("m1", "A", "B", 1000L, Records.PHONE_DEVICE, 2);
            record.force = true;
            WireMessage pull = WireMessage.pull("sid", 0, 1,
                    Collections.singletonList(record), "0000000001000-0000-p");
            JsonObject json = JsonParser.parseString(codec.encode(pull)).getAsJsonObject();
            JsonObject wireRecord = json.getAsJsonArray("recs").get(0).getAsJsonObject();
            Assertions.assertEqualsInt(1, wireRecord.get("f").getAsInt(), "f=1");
            Assertions.assertEquals("0000000001000-0000-p", json.get("next").getAsString(), "next");
            Assertions.assertTrue(codec.decode(codec.encode(pull)).recs.get(0).force,
                    "force flag round trips");
        });

        r.test("codec: push/pull recs lists round trip", () -> {
            List<MemoRecord> records = new ArrayList<>();
            records.add(Records.create("m1", "A", "one", 1000L, "p", 1));
            records.add(Records.create("m2", "B", "two", 2000L, "p", 1));
            WireMessage push = WireMessage.push("sid", 0, 1, records);
            WireMessage decoded = codec.decode(codec.encode(push));
            Assertions.assertNotNull(decoded.recs, "recs decoded");
            Assertions.assertEqualsInt(2, decoded.recs.size(), "two records");
            Assertions.assertEquals("m2", decoded.recs.get(1).id, "order preserved");
        });

        r.test("codec: err message", () -> {
            WireMessage decoded = codec.decode(
                    codec.encode(WireMessage.err("sid", 7, "boom")));
            Assertions.assertEquals(WireMessage.T_ERR, decoded.t, "type");
            Assertions.assertEquals(Integer.valueOf(7), decoded.code, "code");
            Assertions.assertEquals("boom", decoded.msg, "msg");
        });

        r.test("codec: unsupported protocol version is rejected", () -> {
            Assertions.assertThrows(ProtocolCodec.ProtocolException.class,
                    () -> codec.decode("{\"v\":2,\"t\":\"req\"}"), "v=2 must be rejected");
            Assertions.assertThrows(ProtocolCodec.ProtocolException.class,
                    () -> codec.decode("{\"t\":\"req\"}"), "missing v must be rejected");
        });

        r.test("codec: unknown type / invalid json / non-object payload rejected", () -> {
            Assertions.assertThrows(ProtocolCodec.ProtocolException.class,
                    () -> codec.decode("{\"v\":1,\"t\":\"nope\"}"), "unknown type");
            Assertions.assertThrows(ProtocolCodec.ProtocolException.class,
                    () -> codec.decode("{not json"), "invalid json");
            Assertions.assertThrows(ProtocolCodec.ProtocolException.class,
                    () -> codec.decode("[]"), "array payload");
            Assertions.assertThrows(ProtocolCodec.ProtocolException.class,
                    () -> codec.decode((String) null), "null payload");
            Assertions.assertThrows(ProtocolCodec.ProtocolException.class,
                    () -> codec.encode(new WireMessage()), "encode without type");
        });

        r.test("codec: unknown/extra keys are tolerated; boolean del/f accepted", () -> {
            WireMessage decoded = codec.decode("{\"v\":1,\"t\":\"push\",\"sid\":\"s\","
                    + "\"i\":0,\"n\":1,\"future\":true,\"recs\":[{\"id\":\"m1\",\"ti\":\"A\","
                    + "\"bo\":\"B\",\"del\":true,\"f\":true,\"unknown\":\"x\"}]}");
            Assertions.assertNotNull(decoded.recs, "recs parsed");
            Assertions.assertTrue(decoded.recs.get(0).deleted, "boolean del accepted");
            Assertions.assertTrue(decoded.recs.get(0).force, "boolean f accepted");
            Assertions.assertNull(decoded.recs.get(0).deletedHlc, "missing dh -> null");
        });

        r.test("codec: absent recs decodes as null, empty recs as empty list", () -> {
            Assertions.assertNull(codec.decode("{\"v\":1,\"t\":\"pack\",\"sid\":\"s\",\"i\":0}").recs,
                    "absent recs");
            WireMessage empty = codec.decode(codec.encode(
                    WireMessage.pull("s", 0, 0, new ArrayList<>(), null)));
            Assertions.assertNotNull(empty.recs, "empty list kept");
            Assertions.assertEqualsInt(0, empty.recs.size(), "no records");
        });
    }
}
