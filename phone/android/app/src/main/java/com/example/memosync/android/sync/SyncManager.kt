package com.example.memosync.android.sync

import android.content.Context
import com.example.memosync.android.data.AppDatabase
import com.example.memosync.android.data.RoomMemoStore
import com.example.memosync.core.ProtocolCodec
import com.example.memosync.sync.SyncManager as CoreSyncManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * NOT COMPILED IN THIS POC - Android source skeleton only.
 *
 * App-level singleton that wires the tested Java 17 sync core to the Android world:
 *
 *   SaltUI screens -> [requestSync] / [state]
 *   Java core SyncManager (HLC merge + hub state machine)
 *       -> Room [com.example.memosync.android.data.RoomMemoStore]
 *       -> [WearEngineTransport] (Wear Engine P2P bytes)
 *
 * The Java core already owns the session state machine, duplicate/out-of-order handling and the
 * merge authority; this wrapper only adds Android lifecycle, debounce and singletons
 * (design doc §5.2: single SyncManager, 300-500 ms debounce, one session at a time).
 *
 * Session triggering (design doc §2.3):
 *   - MainActivity.onStart -> requestSync("app_start")
 *   - memo saved           -> requestSync("save")
 *   - watch connection up  -> requestSync("reconnect") from [WearEngineTransport.initialize]
 *   - watch sends sync req -> handled inside the Java core automatically
 */
class SyncManager private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val store: RoomMemoStore = RoomMemoStore(AppDatabase.create(appContext))
    private val transport = WearEngineTransport(appContext)
    private val codec = ProtocolCodec()

    /**
     * Tested POC hub. Peer id is null until the first watch `req` carries its `dev` id
     * (the Java core learns it); merge bases are keyed by that id.
     */
    private val core = CoreSyncManager(DeviceIds.get(appContext), null, store, transport, codec)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var debounceJob: Job? = null

    companion object {
        private const val DEBOUNCE_MS = 300L

        @Volatile
        private var instance: SyncManager? = null

        /** Gets (and lazily initialises) the app-wide sync manager. */
        fun get(context: Context): SyncManager {
            return instance ?: synchronized(this) {
                instance ?: SyncManager(context).also {
                    instance = it
                    it.initialize()
                }
            }
        }
    }

    /** Registers the P2P receiver early so watch `sync.request` frames are not missed. */
    fun initialize() {
        transport.initialize {
            // Design doc §5.6: connection recovery restarts sync.
            requestSync("reconnect")
        }
    }

    /** Debounced sync trigger; the Java core serialises sessions and queues extra triggers. */
    fun requestSync(reason: String) {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(DEBOUNCE_MS)
            core.requestSync(reason)
        }
    }

    fun abortSession(reason: String) = core.abortSession(reason)

    fun state(): CoreSyncManager.State = core.state()

    fun stats(): CoreSyncManager.Stats = core.stats()

    fun lastMergeSummary(): String = core.lastMergeSummary()

    /** True when the watch app is currently reachable (for the settings screen). */
    fun isWatchReady(): Boolean = transport.isWatchReady()

    /** Sends a raw protocol frame (debug console / tests only). */
    fun sendDebug(payload: ByteArray) = transport.send(payload)
}

/**
 * Per-install device id for HLC encoding (design doc §3.2: random, persisted, never hardware).
 * HLC strings use this short id; it must stay stable across app restarts.
 */
object DeviceIds {
    private const val PREFS = "memosync_device"
    private const val KEY_DEVICE_ID = "device_id"

    /** Returns a stable short device id, generating one on first launch. */
    @Synchronized
    fun get(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_DEVICE_ID, null)
        if (existing != null) {
            return existing
        }
        // Short id; the design doc example uses "p". UUID avoids hardware identifiers.
        val generated = "p-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8)
        prefs.edit().putString(KEY_DEVICE_ID, generated).apply()
        return generated
    }
}
