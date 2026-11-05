package com.example.memosync.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/**
 * Encoder/decoder for the short-key wire format (design doc §4.3, appendix A).
 *
 * <p>Envelope example:
 * <pre>{@code {"v":1,"t":"req","dev":"w-a1b2c3d4","cur":"1844312345000-0007-w-a1b2c3d4","dirty":2}}</pre>
 *
 * <p>Record example:
 * <pre>{@code
 * {"id":"m-01","ti":"购物清单","bo":"牛奶\n鸡蛋","c":"...","u":"...","th":"...","bh":"...",
 *  "del":0,"dh":null,"rev":2,"base":"1844312300000-0003-p-11223344"}
 * }</pre>
 *
 * <p>Only the phone side of the POC is implemented here, but the same codec is what the
 * watch client must mirror; the JSON keys are the contract, the Java class layout is not.
 */
public final class ProtocolCodec {

    /** Thrown when a frame cannot be decoded or has an unsupported protocol version. */
    public static final class ProtocolException extends RuntimeException {
        public ProtocolException(String message) {
            super(message);
        }

        public ProtocolException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final Gson GSON = new GsonBuilder().serializeNulls().create();
    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting()
            .serializeNulls().disableHtmlEscaping().create();

    // ------------------------------------------------------------------
    // Envelope
    // ------------------------------------------------------------------

    public String encode(WireMessage message) {
        return GSON.toJson(encodeToObject(message));
    }

    public JsonObject encodeToObject(WireMessage message) {
        if (message == null) {
            throw new ProtocolException("message must not be null");
        }
        if (message.t == null || !WireMessage.isKnownType(message.t)) {
            throw new ProtocolException("unknown message type: " + message.t);
        }
        JsonObject o = new JsonObject();
        o.addProperty("v", WireMessage.VERSION);
        o.addProperty("t", message.t);
        if (message.sid != null) {
            o.addProperty("sid", message.sid);
        }
        if (message.dev != null) {
            o.addProperty("dev", message.dev);
        }
        if (message.cur != null) {
            o.addProperty("cur", message.cur);
        }
        if (message.dirty != null) {
            o.addProperty("dirty", message.dirty);
        }
        if (message.i != null) {
            o.addProperty("i", message.i);
        }
        if (message.n != null) {
            o.addProperty("n", message.n);
        }
        if (message.recs != null) {
            JsonArray recs = new JsonArray();
            for (MemoRecord record : message.recs) {
                recs.add(encodeRecordToObject(record));
            }
            o.add("recs", recs);
        }
        if (message.next != null) {
            o.addProperty("next", message.next);
        }
        if (message.code != null) {
            o.addProperty("code", message.code);
        }
        if (message.msg != null) {
            o.addProperty("msg", message.msg);
        }
        return o;
    }

    public JsonObject encodeRecordToObject(MemoRecord record) {
        if (record == null) {
            throw new ProtocolException("record must not be null");
        }
        JsonObject r = new JsonObject();
        r.addProperty("id", record.id);
        r.addProperty("ti", record.title);
        r.addProperty("bo", record.body);
        r.addProperty("c", record.created);
        r.addProperty("u", record.updated);
        r.addProperty("th", record.titleHlc);
        r.addProperty("bh", record.bodyHlc);
        // "del" is a 0/1 number on the wire (Gson would emit true/false for booleans).
        r.addProperty("del", record.deleted ? 1 : 0);
        // Deleted HLC is explicitly null when the record is alive (appendix A.3).
        r.addProperty("dh", record.deletedHlc);
        r.addProperty("rev", record.rev);
        if (record.base != null) {
            r.addProperty("base", record.base);
        }
        if (record.force) {
            r.addProperty("f", 1);
        }
        return r;
    }

    public String encodeRecord(MemoRecord record) {
        return GSON.toJson(encodeRecordToObject(record));
    }

    public WireMessage decode(String json) {
        if (json == null) {
            throw new ProtocolException("payload must not be null");
        }
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(json);
        } catch (RuntimeException e) {
            throw new ProtocolException("payload is not valid JSON: " + e.getMessage(), e);
        }
        if (!parsed.isJsonObject()) {
            throw new ProtocolException("payload is not a JSON object");
        }
        return decode(parsed.getAsJsonObject());
    }

    public WireMessage decode(JsonObject o) {
        int version = intOr(o, "v", -1);
        if (version != WireMessage.VERSION) {
            throw new ProtocolException("unsupported protocol version: " + version
                    + " (expected " + WireMessage.VERSION + ")");
        }
        String type = stringOrNull(o, "t");
        if (type == null || !WireMessage.isKnownType(type)) {
            throw new ProtocolException("missing or unknown message type: " + type);
        }
        WireMessage m = new WireMessage();
        m.v = version;
        m.t = type;
        m.sid = stringOrNull(o, "sid");
        m.dev = stringOrNull(o, "dev");
        m.cur = stringOrNull(o, "cur");
        m.dirty = o.has("dirty") && !o.get("dirty").isJsonNull() ? intOr(o, "dirty", 0) : null;
        m.i = o.has("i") && !o.get("i").isJsonNull() ? intOr(o, "i", 0) : null;
        m.n = o.has("n") && !o.get("n").isJsonNull() ? intOr(o, "n", 0) : null;
        m.next = stringOrNull(o, "next");
        m.code = o.has("code") && !o.get("code").isJsonNull() ? intOr(o, "code", 0) : null;
        m.msg = stringOrNull(o, "msg");
        if (o.has("recs") && !o.get("recs").isJsonNull()) {
            JsonElement recsElement = o.get("recs");
            if (!recsElement.isJsonArray()) {
                throw new ProtocolException("recs must be an array");
            }
            List<MemoRecord> records = new ArrayList<>();
            for (JsonElement element : recsElement.getAsJsonArray()) {
                if (!element.isJsonObject()) {
                    throw new ProtocolException("recs entries must be objects");
                }
                records.add(decodeRecord(element.getAsJsonObject()));
            }
            m.recs = records;
        }
        return m;
    }

    public MemoRecord decodeRecord(JsonObject r) {
        try {
            MemoRecord record = new MemoRecord();
            record.id = stringOrNull(r, "id");
            record.title = stringOrNull(r, "ti");
            record.body = stringOrNull(r, "bo");
            record.created = stringOrNull(r, "c");
            record.updated = stringOrNull(r, "u");
            record.titleHlc = stringOrNull(r, "th");
            record.bodyHlc = stringOrNull(r, "bh");
            record.deleted = boolOrZeroOne(r, "del");
            record.deletedHlc = stringOrNull(r, "dh");
            record.rev = intOr(r, "rev", 0);
            record.base = stringOrNull(r, "base");
            record.force = boolOrZeroOne(r, "f");
            return record;
        } catch (RuntimeException e) {
            throw new ProtocolException("bad record JSON: " + e.getMessage(), e);
        }
    }

    /** Pretty-printed JSON, used for test fixtures and human inspection. */
    public String pretty(JsonElement element) {
        return PRETTY.toJson(element);
    }

    // ------------------------------------------------------------------
    // Small null/bool tolerant JSON getters (no reflection, explicit keys)
    // ------------------------------------------------------------------

    private static String stringOrNull(JsonObject o, String key) {
        if (!o.has(key) || o.get(key).isJsonNull()) {
            return null;
        }
        JsonElement e = o.get(key);
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
            throw new ProtocolException("field '" + key + "' must be a string, got: " + e);
        }
        return e.getAsString();
    }

    private static int intOr(JsonObject o, String key, int fallback) {
        if (!o.has(key) || o.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return o.get(key).getAsInt();
        } catch (RuntimeException e) {
            throw new ProtocolException("field '" + key + "' must be a number", e);
        }
    }

    private static boolean boolOrZeroOne(JsonObject o, String key) {
        if (!o.has(key) || o.get(key).isJsonNull()) {
            return false;
        }
        JsonElement e = o.get(key);
        if (!e.isJsonPrimitive()) {
            throw new ProtocolException("field '" + key + "' must be boolean or 0/1");
        }
        if (e.getAsJsonPrimitive().isBoolean()) {
            return e.getAsBoolean();
        }
        return e.getAsInt() != 0;
    }

    static JsonNull nullValue() {
        return JsonNull.INSTANCE;
    }
}
