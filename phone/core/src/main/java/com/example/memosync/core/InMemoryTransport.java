package com.example.memosync.core;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * In-memory {@link Transport} used by the JVM tests (and usable from the Android debug
 * build as a fake peer). Behaves like the real byte transport:
 *
 * <ul>
 *   <li>{@link #send(byte[])} records the payload and - when linked to a peer via
 *       {@link #connect(InMemoryTransport, InMemoryTransport)} - delivers it to the peer;</li>
 *   <li>{@link #deliver(byte[])} simulates an inbound message (watch -&gt; phone);</li>
 *   <li>{@link #setPeerReady(boolean)} simulates the watch app being closed (ping returns
 *       200 / detect fails).</li>
 * </ul>
 *
 * <p>Sends are re-entrant on a single thread (a sync manager may reply synchronously from
 * inside a listener callback); this is enough for the POC's deterministic tests.
 * {@link #sentMessages()} gives the test assertions access to everything the phone sent.
 */
public final class InMemoryTransport implements Transport {

    private Listener listener;
    private InMemoryTransport linked;
    private boolean peerReady = true;
    private boolean recordSends = true;
    private final List<byte[]> sent = new ArrayList<>();
    private final List<String> sentText = new ArrayList<>();

    public InMemoryTransport() {
    }

    /** Links two transports so each send is delivered to the other side's listener. */
    public static void connect(InMemoryTransport a, InMemoryTransport b) {
        if (a == null || b == null) {
            throw new IllegalArgumentException("both transports are required");
        }
        a.linked = b;
        b.linked = a;
    }

    @Override
    public synchronized void setListener(Listener listener) {
        this.listener = listener;
    }

    @Override
    public synchronized boolean isPeerReady() {
        return peerReady;
    }

    public synchronized void setPeerReady(boolean peerReady) {
        this.peerReady = peerReady;
    }

    public synchronized void setRecordSends(boolean recordSends) {
        this.recordSends = recordSends;
    }

    @Override
    public synchronized void send(byte[] payload) {
        if (payload == null) {
            throw new TransportException("payload must not be null");
        }
        if (!peerReady) {
            throw new TransportException("peer is not ready (watch app not running)");
        }
        if (recordSends) {
            sent.add(payload.clone());
            sentText.add(new String(payload, StandardCharsets.UTF_8));
        }
        if (linked != null) {
            linked.deliver(payload.clone());
        }
    }

    /** Simulates an inbound payload arriving from the peer. */
    public synchronized void deliver(byte[] payload) {
        if (payload == null) {
            throw new TransportException("payload must not be null");
        }
        if (listener == null) {
            throw new IllegalStateException("no listener registered");
        }
        listener.onMessage(payload.clone());
    }

    public synchronized List<byte[]> sentMessages() {
        List<byte[]> copy = new ArrayList<>(sent.size());
        for (byte[] payload : sent) {
            copy.add(payload.clone());
        }
        return copy;
    }

    public synchronized List<String> sentJson() {
        return Collections.unmodifiableList(new ArrayList<>(sentText));
    }

    public synchronized WireMessage lastSent(ProtocolCodec codec) {
        if (sentText.isEmpty()) {
            return null;
        }
        return codec.decode(sentText.get(sentText.size() - 1));
    }

    public synchronized List<WireMessage> sentDecoded(ProtocolCodec codec) {
        List<WireMessage> messages = new ArrayList<>();
        for (String json : sentText) {
            messages.add(codec.decode(json));
        }
        return messages;
    }

    public synchronized void clearSent() {
        sent.clear();
        sentText.clear();
    }

    public synchronized int sendCount() {
        return sent.size();
    }

    /** Finds the last sent message of a given type, or null. */
    public synchronized WireMessage lastSentOfType(ProtocolCodec codec, String type) {
        for (int index = sentText.size() - 1; index >= 0; index--) {
            WireMessage message = codec.decode(sentText.get(index));
            if (type.equals(message.t)) {
                return message;
            }
        }
        return null;
    }
}
