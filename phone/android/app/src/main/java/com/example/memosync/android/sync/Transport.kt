package com.example.memosync.android.sync

import com.example.memosync.core.Transport as CoreTransport

/**
 * NOT COMPILED IN THIS POC - Android source skeleton only.
 *
 * Android transport contract. Extends the Java 17 POC core transport so that the tested
 * `com.example.memosync.sync.SyncManager` state machine can be driven by the real Wear Engine
 * implementation without any business-layer changes (design doc §5.2 / §5.6: the transport only
 * moves bytes, it must not parse protocol messages).
 *
 * Implementation of record here: [WearEngineTransport] (P2P stub, not compiled).
 * POC test fake: `com.example.memosync.core.InMemoryTransport` (Java, tested).
 */
interface Transport : CoreTransport {

    /** True when the watch app is reachable (watch app running + device connected). */
    fun isWatchReady(): Boolean

    /**
     * Sends one payload. [onResult] delivers the send result asynchronously; the sync layer
     * treats a failure like an aborted session (design doc §4.5: retry next trigger).
     */
    fun send(payload: ByteArray, onResult: (Result<Unit>) -> Unit)
}
