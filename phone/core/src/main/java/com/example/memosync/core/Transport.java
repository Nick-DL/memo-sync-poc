package com.example.memosync.core;

/**
 * Byte-level transport used by the sync layer (design doc §5.2).
 *
 * <p>The real Android implementation is {@code WearEngineTransport.kt} (Huawei Wear Engine
 * P2P; see {@code phone/android/}), and the POC tests use {@link InMemoryTransport}.
 * The sync/merge layer only ever sees bytes, so swapping transports does not touch the
 * protocol code.
 */
public interface Transport {

    /** Callback invoked on the receiving thread whenever a full payload arrives. */
    interface Listener {
        void onMessage(byte[] payload);
    }

    /** Registers the single inbound listener. Must be called once before messages arrive. */
    void setListener(Listener listener);

    /** True when the peer (watch app) is reachable right now. */
    boolean isPeerReady();

    /**
     * Sends one payload. The POC transport is best-effort: it throws
     * {@link TransportException} when the peer is not ready; retry logic lives in
     * {@code SyncManager} / the Android layer.
     */
    void send(byte[] payload);
}
