package com.example.memosync.core;

import java.util.ArrayList;
import java.util.List;

/**
 * A protocol envelope, using the short-key wire format from the design doc §4.3 / appendix A.
 *
 * <pre>{@code
 * req   watch -> phone : v, t, dev, cur, dirty
 * start phone -> watch : v, t, sid, cur
 * push  watch -> phone : v, t, sid, i, n, recs[]
 * pull  phone -> watch : v, t, sid, i, n, recs[], next
 * pack  both directions: v, t, sid, i
 * done  phone -> watch : v, t, sid, cur
 * err   both directions: v, t, sid, code, msg
 * }</pre>
 *
 * <p>Records inside {@code recs} use short keys; see {@link ProtocolCodec}.
 * This class is a plain mutable DTO on purpose (POC skeleton, easy to inspect in tests).
 */
public final class WireMessage {

    public static final int VERSION = 1;

    public static final String T_REQ = "req";
    public static final String T_START = "start";
    public static final String T_PUSH = "push";
    public static final String T_PULL = "pull";
    public static final String T_PACK = "pack";
    public static final String T_DONE = "done";
    public static final String T_ERR = "err";

    /** Protocol version, always 1 in this POC. */
    public int v = VERSION;
    /** Message type: one of the T_* constants. */
    public String t;
    /** Session id (UUID); absent on {@code req}. */
    public String sid;
    /** Sender device id; only used by {@code req} in the POC. */
    public String dev;
    /** Cursor HLC (watch lastPullHlc in req/start, new cursor in done). */
    public String cur;
    /** Number of dirty records the watch holds (req only). */
    public Integer dirty;
    /** Chunk index, 0-based (push/pull/pack). */
    public Integer i;
    /** Total chunk count (push/pull); 0 means "empty transfer". */
    public Integer n;
    /** Records carried by a push/pull chunk. */
    public List<MemoRecord> recs;
    /** New cursor after the last pull chunk (pull only). */
    public String next;
    /** Error code (err only). */
    public Integer code;
    /** Human readable error (err only). */
    public String msg;

    public WireMessage() {
    }

    // ------------------------------------------------------------------
    // Factories (keep call sites in SyncManager / tests readable)
    // ------------------------------------------------------------------

    public static WireMessage req(String deviceId, String cursor, Integer dirtyCount) {
        WireMessage m = new WireMessage();
        m.t = T_REQ;
        m.dev = deviceId;
        m.cur = cursor;
        m.dirty = dirtyCount;
        return m;
    }

    public static WireMessage start(String sessionId, String watchCursor) {
        WireMessage m = new WireMessage();
        m.t = T_START;
        m.sid = sessionId;
        m.cur = watchCursor;
        return m;
    }

    public static WireMessage push(String sessionId, int index, int total, List<MemoRecord> records) {
        WireMessage m = new WireMessage();
        m.t = T_PUSH;
        m.sid = sessionId;
        m.i = index;
        m.n = total;
        m.recs = records == null ? new ArrayList<>() : records;
        return m;
    }

    public static WireMessage pull(String sessionId, int index, int total,
                                   List<MemoRecord> records, String nextCursor) {
        WireMessage m = new WireMessage();
        m.t = T_PULL;
        m.sid = sessionId;
        m.i = index;
        m.n = total;
        m.recs = records == null ? new ArrayList<>() : records;
        m.next = nextCursor;
        return m;
    }

    public static WireMessage pack(String sessionId, int index) {
        WireMessage m = new WireMessage();
        m.t = T_PACK;
        m.sid = sessionId;
        m.i = index;
        return m;
    }

    public static WireMessage done(String sessionId, String newCursor) {
        WireMessage m = new WireMessage();
        m.t = T_DONE;
        m.sid = sessionId;
        m.cur = newCursor;
        return m;
    }

    public static WireMessage err(String sessionId, int errorCode, String message) {
        WireMessage m = new WireMessage();
        m.t = T_ERR;
        m.sid = sessionId;
        m.code = errorCode;
        m.msg = message;
        return m;
    }

    public static boolean isKnownType(String type) {
        return T_REQ.equals(type) || T_START.equals(type) || T_PUSH.equals(type)
                || T_PULL.equals(type) || T_PACK.equals(type) || T_DONE.equals(type)
                || T_ERR.equals(type);
    }

    @Override
    public String toString() {
        return "WireMessage{v=" + v + ", t=" + t + ", sid=" + sid + ", dev=" + dev
                + ", cur=" + cur + ", dirty=" + dirty + ", i=" + i + ", n=" + n
                + ", recs=" + (recs == null ? "null" : recs.size()) + ", next=" + next
                + ", code=" + code + ", msg=" + msg + '}';
    }
}
