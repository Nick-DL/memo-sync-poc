package com.example.memosync.test;

import com.example.memosync.core.InMemoryTransport;
import com.example.memosync.core.MemoRecord;
import com.example.memosync.core.ProtocolCodec;
import com.example.memosync.core.WireMessage;
import com.example.memosync.sync.InMemoryMemoStore;
import com.example.memosync.sync.PeerState;
import com.example.memosync.sync.SyncManager;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * SyncManager hub state-machine tests with an in-memory transport.
 *
 * <p>These drive the phone through real short-key JSON messages and cover: phone-initiated and
 * watch-initiated sessions, merge + pull, conflict copies, duplicate/out-of-order chunks,
 * stale-session and duplicate-pack handling, queued triggers, malformed frames, transport
 * failures and multi-chunk pulls.
 */
public final class SyncManagerTest {

    private static final String WATCH = "w-a1b2c3d4";
    private static final String PHONE = "p";

    private SyncManagerTest() {
    }

    /** Test rig: in-memory store + transport wired to a SyncManager. */
    private static final class Rig {
        final ProtocolCodec codec = new ProtocolCodec();
        final InMemoryTransport transport = new InMemoryTransport();
        final InMemoryMemoStore store = new InMemoryMemoStore();
        final SyncManager manager;

        Rig() {
            this(WATCH);
        }

        Rig(String peerId) {
            manager = new SyncManager(PHONE, peerId, store, transport, codec);
        }

        void deliver(WireMessage message) {
            transport.deliver(codec.encode(message).getBytes(StandardCharsets.UTF_8));
        }

        void deliverRaw(String json) {
            transport.deliver(json.getBytes(StandardCharsets.UTF_8));
        }

        WireMessage last(String type) {
            WireMessage message = transport.lastSentOfType(codec, type);
            Assertions.assertNotNull(message, "expected a sent '" + type + "' message");
            return message;
        }

        List<WireMessage> sentOfType(String type) {
            List<WireMessage> filtered = new ArrayList<>();
            for (WireMessage message : transport.sentDecoded(codec)) {
                if (type.equals(message.t)) {
                    filtered.add(message);
                }
            }
            return filtered;
        }
    }

    /** Sets up a session that has completed its push phase (waiting for pack acks). */
    private static WireMessage startAndPush(Rig rig, MemoRecord... pushed) {
        rig.manager.requestSync("test");
        String sid = rig.last(WireMessage.T_START).sid;
        if (pushed.length == 0) {
            rig.deliver(WireMessage.push(sid, 0, 0, new ArrayList<>()));
        } else {
            rig.deliver(WireMessage.push(sid, 0, 1,
                    new ArrayList<>(java.util.Arrays.asList(pushed))));
        }
        return rig.last(WireMessage.T_PULL);
    }

    public static void register(TestRunner r) {
        r.test("sync: requestSync sends start and waits for push", () -> {
            Rig rig = new Rig();
            rig.store.put(Records.create("m1", "A", "a", 1000L, PHONE, 1));
            rig.manager.requestSync("app_start");
            WireMessage start = rig.last(WireMessage.T_START);
            Assertions.assertNotNull(start.sid, "session id created");
            Assertions.assertNull(start.cur, "fresh device has no cursor");
            Assertions.assertEquals(SyncManager.State.WAIT_PUSH, rig.manager.state(), "state");
            Assertions.assertEqualsInt(1, rig.manager.stats().sessionsStarted, "sessions");
            Assertions.assertNotNull(rig.manager.sessionId(), "session id assigned");
        });

        r.test("sync: watch req starts a session with the watch cursor", () -> {
            Rig rig = new Rig(null); // peer id not configured yet: learned from req
            String cursor = Records.hlc(3000L, 0, PHONE);
            rig.deliver(WireMessage.req(WATCH, cursor, 2));
            WireMessage start = rig.last(WireMessage.T_START);
            Assertions.assertEquals(cursor, start.cur, "start carries the watch cursor");
            Assertions.assertEquals(SyncManager.State.WAIT_PUSH, rig.manager.state(), "state");
            Assertions.assertEquals(WATCH, rig.manager.peerId(), "peer id learned from req");
        });

        r.test("sync: full session - empty push, one-record pull, done", () -> {
            Rig rig = new Rig();
            MemoRecord local = Records.create("m1", "A", "hello", 2000L, PHONE, 1);
            rig.store.put(local);

            rig.manager.requestSync("app_start");
            String sid = rig.last(WireMessage.T_START).sid;
            Assertions.assertEqualsInt(1, rig.sentOfType(WireMessage.T_START).size(),
                    "exactly one start");

            rig.deliver(WireMessage.push(sid, 0, 0, new ArrayList<>()));
            WireMessage pull = rig.last(WireMessage.T_PULL);
            Assertions.assertEqualsInt(1, pull.n, "one pull chunk");
            Assertions.assertEqualsInt(1, pull.recs.size(), "one record pulled");
            Assertions.assertEquals("m1", pull.recs.get(0).id, "pulled record id");
            Assertions.assertEquals(local.updated, pull.next, "next cursor is the record HLC");

            rig.deliver(WireMessage.pack(sid, 0));
            WireMessage done = rig.last(WireMessage.T_DONE);
            Assertions.assertEquals(pull.next, done.cur, "done carries the new cursor");
            Assertions.assertEquals(SyncManager.State.IDLE, rig.manager.state(), "back to idle");
            Assertions.assertEqualsInt(1, rig.manager.stats().sessionsCompleted, "sessions done");
            PeerState peerState = rig.store.getPeerState(WATCH);
            Assertions.assertNotNull(peerState, "peer state persisted");
            Assertions.assertEquals(pull.next, peerState.lastPullHlc, "cursor persisted");
        });

        r.test("sync: remote edit is merged then pulled back as canonical", () -> {
            Rig rig = new Rig();
            MemoRecord base = Records.create("m1", "A", "X", 1000L, PHONE, 1);
            MemoRecord local = Records.edited(base, "A", "X",
                    base.titleHlc, base.bodyHlc, Records.hlc(2000L, 0, PHONE), 2);
            rig.store.put(local);
            rig.store.saveBase(WATCH, "m1", base);

            rig.manager.requestSync("app_start");
            String sid = rig.last(WireMessage.T_START).sid;
            MemoRecord remote = Records.edited(base, "A", "W",
                    base.titleHlc, Records.hlc(4000L, 0, WATCH),
                    Records.hlc(4000L, 0, WATCH), 2);
            remote.base = base.updated;
            rig.deliver(WireMessage.push(sid, 0, 1, new ArrayList<>(
                    java.util.Arrays.asList(remote))));

            MemoRecord merged = rig.store.get("m1");
            Assertions.assertEquals("W", merged.body, "remote newer body wins");
            Assertions.assertEqualsInt(3, merged.rev, "merged rev = max+1");
            MemoRecord newBase = rig.store.getBase(WATCH, "m1");
            Assertions.assertNotNull(newBase, "merge base updated");
            Assertions.assertEquals(merged.updated, newBase.updated, "base = new canonical");

            WireMessage pack = rig.last(WireMessage.T_PACK);
            Assertions.assertEquals(Integer.valueOf(0), pack.i, "push chunk acked");
            WireMessage pull = rig.last(WireMessage.T_PULL);
            Assertions.assertEquals(1, rig.manager.stats().recordsMerged, "one record merged");
            boolean found = false;
            for (MemoRecord record : pull.recs) {
                if ("m1".equals(record.id)) {
                    found = true;
                    Assertions.assertEquals("W", record.body, "pulled merged body");
                }
            }
            Assertions.assertTrue(found, "merged canonical is pulled to the watch");
        });

        r.test("sync: remote create is inserted", () -> {
            Rig rig = new Rig();
            MemoRecord remote = Records.create("m-new", "N", "created on watch", 2500L, WATCH, 1);
            rig.manager.requestSync("app_start");
            String sid = rig.last(WireMessage.T_START).sid;
            rig.deliver(WireMessage.push(sid, 0, 1, new ArrayList<>(
                    java.util.Arrays.asList(remote))));

            MemoRecord stored = rig.store.get("m-new");
            Assertions.assertNotNull(stored, "remote record inserted");
            Assertions.assertEquals("created on watch", stored.body, "body");
            Assertions.assertEquals(remote, stored, "one-sided merge stores the remote verbatim");
            Assertions.assertNotNull(rig.store.getBase(WATCH, "m-new"), "base recorded");
        });

        r.test("sync: deletion tombstone is pulled with del=1", () -> {
            Rig rig = new Rig();
            MemoRecord alive = Records.create("m1", "A", "body", 1000L, PHONE, 1);
            MemoRecord tombstone = Records.deleted(alive, Records.hlc(3000L, 0, PHONE),
                    Records.hlc(3000L, 0, PHONE), 2);
            rig.store.put(tombstone);

            rig.manager.requestSync("app_start");
            String sid = rig.last(WireMessage.T_START).sid;
            rig.deliver(WireMessage.push(sid, 0, 0, new ArrayList<>()));
            WireMessage pull = rig.last(WireMessage.T_PULL);
            MemoRecord pulled = pull.recs.get(0);
            Assertions.assertTrue(pulled.deleted, "tombstone pulled");
            Assertions.assertEquals(tombstone.deletedHlc, pulled.deletedHlc, "deletedHlc pulled");
        });

        r.test("sync: body conflict creates the conflict copy", () -> {
            Rig rig = new Rig();
            MemoRecord base = Records.create("m1", "A", "X", 1000L, PHONE, 1);
            MemoRecord local = Records.edited(base, "A", "P",
                    base.titleHlc, Records.hlc(4000L, 0, PHONE),
                    Records.hlc(4000L, 0, PHONE), 2);
            rig.store.put(local);
            rig.store.saveBase(WATCH, "m1", base);

            rig.manager.requestSync("app_start");
            String sid = rig.last(WireMessage.T_START).sid;
            MemoRecord remote = Records.edited(base, "A", "W",
                    base.titleHlc, Records.hlc(5000L, 0, WATCH),
                    Records.hlc(5000L, 0, WATCH), 2);
            remote.base = base.updated;
            rig.deliver(WireMessage.push(sid, 0, 1, new ArrayList<>(
                    java.util.Arrays.asList(remote))));

            Assertions.assertEquals("W", rig.store.get("m1").body, "canonical body");
            MemoRecord conflict = rig.store.get("m1-conflict");
            Assertions.assertNotNull(conflict, "conflict copy stored");
            Assertions.assertEquals("[冲突] A", conflict.title, "conflict title");
            Assertions.assertEquals("P", conflict.body, "phone body preserved in the copy");
            Assertions.assertEqualsInt(1, rig.manager.stats().conflictsCreated, "conflict counter");
        });

        r.test("sync: duplicate push chunk is acked but merged once", () -> {
            Rig rig = new Rig();
            MemoRecord first = Records.create("m-a", "A", "a", 1000L, WATCH, 1);
            MemoRecord second = Records.create("m-b", "B", "b", 1100L, WATCH, 1);

            rig.manager.requestSync("app_start");
            String sid = rig.last(WireMessage.T_START).sid;
            rig.deliver(WireMessage.push(sid, 1, 2, new ArrayList<>(
                    java.util.Arrays.asList(second))));
            rig.deliver(WireMessage.push(sid, 1, 2, new ArrayList<>(
                    java.util.Arrays.asList(second)))); // duplicate
            rig.deliver(WireMessage.push(sid, 0, 2, new ArrayList<>(
                    java.util.Arrays.asList(first))));

            Assertions.assertEqualsInt(2, rig.store.size(), "two records stored");
            Assertions.assertNotNull(rig.store.get("m-a"), "first chunk applied");
            Assertions.assertNotNull(rig.store.get("m-b"), "second chunk applied");
            Assertions.assertEqualsInt(2, rig.manager.stats().chunksReceived, "fresh chunks");
            Assertions.assertEqualsInt(1, rig.manager.stats().duplicateChunks, "duplicate counted");
            Assertions.assertEqualsInt(3, rig.sentOfType(WireMessage.T_PACK).size(),
                    "every chunk acked, including the duplicate");
        });

        r.test("sync: out-of-order push chunks are reassembled by index", () -> {
            Rig rig = new Rig();
            MemoRecord first = Records.create("m-a", "A", "a", 1000L, WATCH, 1);
            MemoRecord second = Records.create("m-b", "B", "b", 1100L, WATCH, 1);

            rig.manager.requestSync("app_start");
            String sid = rig.last(WireMessage.T_START).sid;
            rig.deliver(WireMessage.push(sid, 1, 2, new ArrayList<>(
                    java.util.Arrays.asList(second))));
            rig.deliver(WireMessage.push(sid, 0, 2, new ArrayList<>(
                    java.util.Arrays.asList(first))));

            Assertions.assertEqualsInt(2, rig.store.size(), "both records stored");
            Assertions.assertEqualsInt(2, rig.manager.stats().recordsMerged, "both merged");
            Assertions.assertEqualsInt(0, rig.manager.stats().duplicateChunks,
                    "no duplicates in a clean out-of-order run");
            WireMessage pull = rig.last(WireMessage.T_PULL);
            Assertions.assertEqualsInt(2, pull.recs.size(), "both records pulled");
        });

        r.test("sync: stale-session messages are dropped", () -> {
            Rig rig = new Rig();
            rig.store.put(Records.create("m1", "A", "a", 2000L, PHONE, 1));
            rig.manager.requestSync("app_start");
            String oldSid = rig.last(WireMessage.T_START).sid;
            rig.deliver(WireMessage.push(oldSid, 0, 0, new ArrayList<>()));
            rig.deliver(WireMessage.pack(oldSid, 0));
            Assertions.assertEquals(SyncManager.State.IDLE, rig.manager.state(), "session done");
            int sendsAfterDone = rig.transport.sendCount();

            rig.deliver(WireMessage.push(oldSid, 0, 1, new ArrayList<>(
                    java.util.Arrays.asList(Records.create("m2", "B", "b", 3000L, WATCH, 1)))));
            Assertions.assertEqualsInt(sendsAfterDone, rig.transport.sendCount(),
                    "stale push produces no reply");
            Assertions.assertTrue(rig.manager.stats().staleMessagesDropped >= 1,
                    "stale message counted");
            Assertions.assertNull(rig.store.get("m2"), "stale push not merged");
        });

        r.test("sync: duplicate pack after done is idempotent", () -> {
            Rig rig = new Rig();
            rig.store.put(Records.create("m1", "A", "a", 2000L, PHONE, 1));
            rig.manager.requestSync("app_start");
            String sid = rig.last(WireMessage.T_START).sid;
            rig.deliver(WireMessage.push(sid, 0, 0, new ArrayList<>()));
            rig.deliver(WireMessage.pack(sid, 0));
            int sendsAfterDone = rig.transport.sendCount();
            rig.deliver(WireMessage.pack(sid, 0)); // duplicate
            Assertions.assertEqualsInt(sendsAfterDone, rig.transport.sendCount(),
                    "duplicate pack sends nothing");
            Assertions.assertEqualsInt(1, rig.sentOfType(WireMessage.T_DONE).size(),
                    "only one done sent");
        });

        r.test("sync: trigger during a session is queued and starts the next session", () -> {
            Rig rig = new Rig();
            rig.store.put(Records.create("m1", "A", "a", 2000L, PHONE, 1));
            rig.manager.requestSync("app_start");
            String sid = rig.last(WireMessage.T_START).sid;
            rig.deliver(WireMessage.push(sid, 0, 0, new ArrayList<>()));
            Assertions.assertEquals(SyncManager.State.WAIT_PULL_ACK, rig.manager.state(), "pulling");

            rig.manager.requestSync("save");
            Assertions.assertEqualsInt(1, rig.manager.stats().queuedTriggers, "trigger queued");
            Assertions.assertEqualsInt(1, rig.sentOfType(WireMessage.T_START).size(),
                    "no concurrent second session");

            rig.deliver(WireMessage.pack(sid, 0));
            Assertions.assertEqualsInt(1, rig.sentOfType(WireMessage.T_DONE).size(), "done sent");
            Assertions.assertEqualsInt(2, rig.sentOfType(WireMessage.T_START).size(),
                    "queued trigger opened a new session");
            Assertions.assertEquals(SyncManager.State.WAIT_PUSH, rig.manager.state(),
                    "new session waits for push");
        });

        r.test("sync: malformed frames are ignored and counted", () -> {
            Rig rig = new Rig();
            rig.deliverRaw("{definitely not json");
            Assertions.assertEqualsInt(1, rig.manager.stats().malformedMessages, "malformed");
            Assertions.assertEquals(SyncManager.State.IDLE, rig.manager.state(), "still idle");
            rig.deliverRaw("{\"v\":99,\"t\":\"req\"}");
            Assertions.assertEqualsInt(2, rig.manager.stats().malformedMessages,
                    "wrong version counted");
            rig.deliver(WireMessage.req(WATCH, null, 0));
            Assertions.assertEquals(SyncManager.State.WAIT_PUSH, rig.manager.state(),
                    "valid frame still works afterwards");
        });

        r.test("sync: transport failure aborts the session gracefully", () -> {
            Rig rig = new Rig();
            rig.store.put(Records.create("m1", "A", "a", 2000L, PHONE, 1));
            rig.transport.setPeerReady(false);
            rig.manager.requestSync("app_start");
            Assertions.assertEquals(SyncManager.State.IDLE, rig.manager.state(),
                    "failed send returns to idle");
            Assertions.assertEqualsInt(1, rig.manager.stats().sendFailures, "send failure counted");

            rig.transport.setPeerReady(true);
            rig.manager.requestSync("retry");
            Assertions.assertEquals(SyncManager.State.WAIT_PUSH, rig.manager.state(),
                    "retry works when the watch is back");
        });

        r.test("sync: abortSession sends err and clears the session", () -> {
            Rig rig = new Rig();
            rig.store.put(Records.create("m1", "A", "a", 2000L, PHONE, 1));
            rig.manager.requestSync("app_start");
            Assertions.assertEquals(SyncManager.State.WAIT_PUSH, rig.manager.state(), "waiting");
            rig.manager.abortSession("timeout");
            Assertions.assertEquals(SyncManager.State.IDLE, rig.manager.state(), "idle");
            WireMessage err = rig.last(WireMessage.T_ERR);
            Assertions.assertEquals(Integer.valueOf(1), err.code, "err code");
            Assertions.assertTrue(err.msg.contains("timeout"), "err message");
        });

        r.test("sync: multi-chunk pull is acked chunk by chunk", () -> {
            Rig rig = new Rig();
            List<MemoRecord> localRecords = new ArrayList<>();
            for (int i = 0; i < 120; i++) {
                localRecords.add(Records.create("m-" + i, "title-" + i,
                        "body payload of memo number " + i, 1000L + i, PHONE, 1));
            }
            for (MemoRecord record : localRecords) {
                rig.store.put(record);
            }

            rig.manager.requestSync("app_start");
            String sid = rig.last(WireMessage.T_START).sid;
            rig.deliver(WireMessage.push(sid, 0, 0, new ArrayList<>()));
            WireMessage currentPull = rig.last(WireMessage.T_PULL);
            int totalChunks = currentPull.n;
            Assertions.assertTrue(totalChunks > 1, "sanity: 120 records need multiple chunks");
            List<MemoRecord> pulled = new ArrayList<>(currentPull.recs);

            for (int index = 0; index < totalChunks; index++) {
                rig.deliver(WireMessage.pack(sid, index));
                if (index < totalChunks - 1) {
                    currentPull = rig.last(WireMessage.T_PULL);
                    Assertions.assertEqualsInt(index + 1, currentPull.i, "next chunk index");
                    Assertions.assertEqualsInt(totalChunks, currentPull.n, "chunk count stable");
                    pulled.addAll(currentPull.recs);
                }
            }

            Assertions.assertEqualsInt(120, pulled.size(), "all records pulled");
            Assertions.assertEqualsInt(totalChunks + 1, rig.manager.stats().chunksSent,
                    "chunksSent = empty-push ack + pull chunks");
            WireMessage done = rig.last(WireMessage.T_DONE);
            Assertions.assertEquals(currentPull.next, done.cur, "done cursor = last pull next");
        });

        r.test("sync: forced pull for a merged record older than the cursor", () -> {
            Rig rig = new Rig();
            String cursor = Records.hlc(4000L, 0, PHONE);
            rig.store.savePeerState(new PeerState(WATCH, cursor, null));

            rig.manager.requestSync("reconnect");
            Assertions.assertEquals(cursor, rig.last(WireMessage.T_START).cur,
                    "start uses the stored cursor");
            String sid = rig.manager.sessionId();
            MemoRecord remote = Records.create("m-old", "O", "old record", 2500L, WATCH, 1);
            rig.deliver(WireMessage.push(sid, 0, 1, new ArrayList<>(
                    java.util.Arrays.asList(remote))));

            WireMessage pull = rig.last(WireMessage.T_PULL);
            Assertions.assertEqualsInt(1, pull.recs.size(), "forced record pulled");
            Assertions.assertTrue(pull.recs.get(0).force, "pull record flagged f=1");
            Assertions.assertEquals(cursor, pull.next, "cursor does not move backwards");
        });

        r.test("sync: remote deletion newer than a local edit tombstones the record", () -> {
            Rig rig = new Rig();
            MemoRecord base = Records.create("m1", "A", "X", 1000L, PHONE, 1);
            MemoRecord local = Records.edited(base, "A", "P",
                    base.titleHlc, Records.hlc(4000L, 0, PHONE),
                    Records.hlc(4000L, 0, PHONE), 2);
            rig.store.put(local);
            rig.store.saveBase(WATCH, "m1", base);

            rig.manager.requestSync("app_start");
            String sid = rig.last(WireMessage.T_START).sid;
            MemoRecord remote = Records.deleted(base, Records.hlc(5000L, 0, WATCH),
                    Records.hlc(5000L, 0, WATCH), 2);
            remote.base = base.updated;
            rig.deliver(WireMessage.push(sid, 0, 1, new ArrayList<>(
                    java.util.Arrays.asList(remote))));

            MemoRecord merged = rig.store.get("m1");
            Assertions.assertTrue(merged.deleted, "deletion wins over the older edit");
            Assertions.assertEquals(Records.hlc(5000L, 0, WATCH), merged.deletedHlc,
                    "deletion HLC kept");
        });

        r.test("sync: next phone-initiated session reuses the persisted cursor", () -> {
            Rig rig = new Rig();
            rig.store.put(Records.create("m1", "A", "a", 2000L, PHONE, 1));
            rig.manager.requestSync("app_start");
            String sid = rig.last(WireMessage.T_START).sid;
            rig.deliver(WireMessage.push(sid, 0, 0, new ArrayList<>()));
            rig.deliver(WireMessage.pack(sid, 0));
            String nextCursor = rig.last(WireMessage.T_DONE).cur;
            Assertions.assertNotNull(nextCursor, "cursor moved");

            InMemoryTransport transport2 = new InMemoryTransport();
            SyncManager manager2 = new SyncManager(PHONE, WATCH, rig.store, transport2,
                    new ProtocolCodec());
            manager2.requestSync("restart");
            WireMessage start2 = transport2.lastSentOfType(new ProtocolCodec(),
                    WireMessage.T_START);
            Assertions.assertNotNull(start2, "second session started");
            Assertions.assertEquals(nextCursor, start2.cur, "stored cursor reused");
        });
    }
}
