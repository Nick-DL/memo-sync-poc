package com.example.memosync.android.sync

import android.content.Context
import android.util.Log
import com.huawei.wearengine.HiWear
import com.huawei.wearengine.device.Device
import com.huawei.wearengine.device.DeviceClient
import com.huawei.wearengine.monitor.MonitorClient
import com.huawei.wearengine.monitor.MonitorData
import com.huawei.wearengine.monitor.MonitorItem
import com.huawei.wearengine.monitor.MonitorListener
import com.huawei.wearengine.p2p.Message
import com.huawei.wearengine.p2p.P2pClient
import com.huawei.wearengine.p2p.PingCallback
import com.huawei.wearengine.p2p.Receiver
import com.huawei.wearengine.p2p.SendCallback

/**
 * NOT COMPILED IN THIS POC - Android source skeleton only.
 *
 * Real Huawei Wear Engine P2P transport, ported from the decompiled reference implementation
 * (read-only):
 *   daysmatter-analysis/work/src/HiWearHelper.java
 *   daysmatter-analysis/work/src/HuaweiController.java
 * and the design doc §5.6 / §7.1.
 *
 * Behaviour matching the reference:
 *   - HiWear.getP2pClient + setPeerPkgName / setPeerFingerPrint (双向证书锁定);
 *   - HiWear.getDeviceClient().hasAvailableDevices() -> getBondedDevices() -> first isConnected;
 *   - p2pClient.ping(device, ...): 200 = watch app not running, 202 = ok, retry up to 5 times
 *     every 500 ms (HMController.MAX_PING_TIMES / PING_DELAY);
 *   - p2pClient.registerReceiver(...) registered before/independent of a sync session so watch
 *     `sync.request` frames are not missed;
 *   - MonitorClient MONITOR_ITEM_CONNECTION value 2 -> connection restored -> sync trigger;
 *   - Message.Builder().setPayload(bytes) + p2pClient.send(...), UTF-8 JSON payloads;
 *   - no Bluetooth permission and no direct socket on the app side.
 *
 * NOT VERIFIED / TODO(SDK): exact Wear Engine SDK version, callback interface names/signatures
 * (`SendCallback`, `PingCallback`, `Receiver`, `MonitorListener`), `Task` type used by the
 * device/client APIs (the reference wraps them in an `i15` compatibility wrapper whose success
 * listener is registered via OooO0O0 and failure via OooO00o), and the `Permission.DEVICE_MANAGER`
 * auth flow (`AuthClient.checkPermission/requestPermission`) that the reference performs before
 * ping/send. Compile this against the real SDK before shipping; the skeleton deliberately avoids
 * inventing signatures it could not confirm.
 */
class WearEngineTransport(
    context: Context,
    private val peerPkgName: String = DEFAULT_PEER_PKG_NAME,
    private val peerFingerPrint: String = DEFAULT_PEER_FINGERPRINT,
) : Transport {

    companion object {
        private const val TAG = "WearEngineTransport"

        /** Watch HAP package name - TODO(Android): replace with the real HAP bundle name. */
        const val DEFAULT_PEER_PKG_NAME = "com.example.memosync.hmw"

        /** Watch HAP signing cert - TODO(Android): per debug/release flavor (design doc §7.1). */
        const val DEFAULT_PEER_FINGERPRINT =
            "com.example.memosync.hmw_<WATCH_HAP_CERT_SHA256_BASE64>"

        /** p2pClient.ping result codes from the reference (WearEngineErrorCode). */
        const val WATCH_APP_NOT_RUNNING = 200
        const val WATCH_APP_RUNNING = 202

        const val MAX_PING_TIMES = 5
        const val PING_DELAY_MS = 500L

        /** MonitorData.asInt() == 2 means connected in the reference implementation. */
        const val CONNECTION_STATE_CONNECTED = 2

        /** Design doc §4.5: target payload size; larger records are chunked by the core. */
        const val MAX_MESSAGE_BYTES = 8 * 1024
    }

    private val appContext = context.applicationContext

    private val p2pClient: P2pClient = HiWear.getP2pClient(appContext).apply {
        setPeerPkgName(peerPkgName)
        setPeerFingerPrint(peerFingerPrint)
    }
    private val deviceClient: DeviceClient = HiWear.getDeviceClient(appContext)
    private val monitorClient: MonitorClient = HiWear.getMonitorClient(appContext)

    private var checkedDevice: Device? = null
    private var receiver: Receiver? = null
    private var listener: com.example.memosync.core.Transport.Listener? = null
    private var onReconnected: (() -> Unit)? = null
    private var pingTimes = 0

    // ------------------------------------------------------------------
    // Core Transport contract (Java POC interface)
    // ------------------------------------------------------------------

    override fun setListener(listener: com.example.memosync.core.Transport.Listener) {
        this.listener = listener
    }

    override fun isPeerReady(): Boolean = isWatchReady()

    override fun send(payload: ByteArray) {
        send(payload) { result ->
            if (result.isFailure) {
                Log.w(TAG, "send failed", result.exceptionOrNull())
            }
        }
    }

    override fun send(payload: ByteArray, onResult: (Result<Unit>) -> Unit) {
        val device = checkedDevice
        if (device == null) {
            onResult(Result.failure(IllegalStateException("watch not connected")))
            return
        }
        if (payload.size > MAX_MESSAGE_BYTES) {
            // The core chunker keeps payloads below this; oversized here is a bug to log.
            Log.w(TAG, "payload ${payload.size} B exceeds P2P target ${MAX_MESSAGE_BYTES} B")
        }
        // TODO(SDK): Message.Builder API from the reference: Message.Builder().setPayload(bytes).build().
        val message = Message.Builder().setPayload(payload).build()
        p2pClient.setPeerPkgName(peerPkgName)
        p2pClient.setPeerFingerPrint(peerFingerPrint)
        p2pClient.send(device, message, object : SendCallback {
            // TODO(SDK): confirm callback method name/signature against the SDK jar.
            override fun onSendResult(errorCode: Int) {
                if (errorCode == 0) {
                    onResult(Result.success(Unit))
                } else {
                    onResult(Result.failure(
                        IllegalStateException("Wear Engine send failed, code=$errorCode")))
                }
            }
        })
    }

    // ------------------------------------------------------------------
    // Connection management (port of HuaweiController)
    // ------------------------------------------------------------------

    /**
     * Registers the P2P receiver and connection monitor, then pings the watch.
     * [onReconnected] is invoked when the watch link comes back (design doc §5.6).
     */
    fun initialize(onReconnected: () -> Unit) {
        this.onReconnected = onReconnected
        refreshDeviceAndPing()
    }

    private fun refreshDeviceAndPing() {
        // TODO(SDK): hasAvailableDevices()/getBondedDevices() return the Huawei Task wrapper;
        // the reference adds success/failure listeners around it (i15.OooO0O0/OooO00o).
        deviceClient.hasAvailableDevices()
            .addOnSuccessListener { available: Boolean ->
                if (available) {
                    deviceClient.getBondedDevices()
                        .addOnSuccessListener { devices: List<Device> ->
                            checkedDevice = devices.firstOrNull { it.isConnected }
                            if (checkedDevice != null) {
                                registerReceiver()
                                pingWatch()
                            } else {
                                Log.w(TAG, "no connected watch among bonded devices")
                            }
                        }
                        .addOnFailureListener { error: Exception ->
                            Log.w(TAG, "getBondedDevices failed", error)
                        }
                } else {
                    Log.w(TAG, "no Wear Engine device available (华为运动健康 installed?)")
                }
            }
            .addOnFailureListener { error: Exception ->
                Log.w(TAG, "hasAvailableDevices failed", error)
            }
    }

    private fun pingWatch() {
        val device = checkedDevice ?: return
        // TODO(SDK): reference uses PingCallback.onPingResult(int); confirm class/package.
        p2pClient.ping(device, object : PingCallback {
            override fun onPingResult(resultCode: Int) {
                when (resultCode) {
                    WATCH_APP_RUNNING -> {
                        pingTimes = 0
                        observeConnection()
                        onReconnected?.invoke()
                    }
                    WATCH_APP_NOT_RUNNING -> {
                        // Watch app closed: retry a few times, then give up until next trigger
                        // (design doc: 手机 ping 返回 200/201，重试 3 次后停止).
                        schedulePingRetry()
                    }
                    else -> schedulePingRetry()
                }
            }
        })
    }

    private fun schedulePingRetry() {
        if (pingTimes >= MAX_PING_TIMES) {
            Log.w(TAG, "watch app not reachable after $MAX_PING_TIMES pings")
            return
        }
        pingTimes += 1
        // TODO(Android): replace with a coroutine/timer on the app scope.
        Thread {
            Thread.sleep(PING_DELAY_MS)
            if (checkedDevice != null) {
                pingWatch()
            }
        }.start()
    }

    private fun registerReceiver() {
        val device = checkedDevice ?: return
        if (receiver == null) {
            receiver = object : Receiver {
                // TODO(SDK): reference: Receiver.onReceiveMessage(Message); payload via getData().
                override fun onReceiveMessage(message: Message) {
                    val data = message.data ?: return
                    listener?.onMessage(data)
                }
            }
            // Register before any session starts so watch sync.request frames are not lost.
            p2pClient.registerReceiver(device, receiver)
        }
    }

    private fun observeConnection() {
        val device = checkedDevice ?: return
        val monitorListener = object : MonitorListener {
            // TODO(SDK): confirm MonitorListener callback signature in the SDK jar.
            override fun onMonitorData(monitorData: MonitorData) {
                if (monitorData.asInt() == CONNECTION_STATE_CONNECTED) {
                    refreshDeviceAndPing()
                }
            }
        }
        // TODO(SDK): MonitorClient.register(device, MonitorItem.MONITOR_ITEM_CONNECTION, listener).
        monitorClient.register(device, MonitorItem.MONITOR_ITEM_CONNECTION, monitorListener)
    }

    fun currentDevice(): Device? = checkedDevice
}
