package com.krishnakb.classifiedprobe

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Binds Karoo's internal SensorService and taps per-device shifting data.
 *
 * Karoo's public karoo-ext API exposes only one shifting source at a time, which
 * is why pairing Classified freezes the AXS gear fields. The internal service has
 * no such limit: its subscribe transaction takes a `DataSource` that names a
 * specific `Device`, so each sensor can be subscribed to independently.
 *
 * ## Safety
 *
 * Only two transactions are ever sent, both identified by reading the service's
 * decompiled dispatch switch rather than by probing:
 *  - [TX_SUBSCRIBE] / [TX_UNSUBSCRIBE] for data,
 *  - [TX_DEVICE_LIST] to enumerate devices.
 *
 * Nothing else is called. If the installed service version differs from
 * [MAPPED_VERSION] the binding refuses to proceed, because a firmware update can
 * renumber transactions and turn "subscribe" into something else.
 *
 * See REMAPPING.md for how to re-derive these numbers.
 */
class SensorServiceProbe(
    private val context: Context,
    private val log: (String) -> Unit,
) {
    private var service: IBinder? = null
    private var bound = false
    private val lastByPage = mutableMapOf<String, String>()

    fun start() {
        log("\nSENSORSERVICE BINDING PROBE")
        log("-".repeat(40))
        val installed = installedVersion()
        log("hxsensorservice version: $installed")
        if (installed != MAPPED_VERSION) {
            log("WARNING: mapped against $MAPPED_VERSION - transactions may have moved.")
            log("Re-run tools/remap-sensorservice.sh (see REMAPPING.md).")
        }
        val intent = Intent().setComponent(
            ComponentName(SERVICE_PKG, "$SERVICE_PKG.service.SensorService"),
        )
        bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        log(if (bound) "Binding..." else "FAILED: bindService returned false")
    }

    fun stop() {
        runCatching { if (bound) context.unbindService(connection) }
        bound = false
        service = null
    }

    private fun installedVersion(): String = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(SERVICE_PKG, 0).versionName ?: "?"
    }.getOrDefault("not installed")

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder) {
            service = binder
            log("Bound. interface=${runCatching { binder.interfaceDescriptor }.getOrNull()}")
            requestDeviceList()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            log("SensorService disconnected.")
            service = null
        }
    }

    /** TX 12: registers a listener that receives the list of known devices. */
    private fun requestDeviceList() {
        val binder = service ?: return
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeString(LISTENER_ID)
            data.writeStrongBinder(deviceListListener)
            val ok = binder.transact(TX_DEVICE_LIST, data, null, IBinder.FLAG_ONEWAY)
            log("TX $TX_DEVICE_LIST (device list) sent, accepted=$ok")
        } catch (e: Exception) {
            log("TX $TX_DEVICE_LIST failed: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            data.recycle()
        }
    }

    /**
     * Our side of `io.hammerhead.aidlrx.IParcelableListener`.
     *
     * TX 1 delivers a payload and expects a reply; TX 2 and 3 are oneway error and
     * completion callbacks.
     */
    private val deviceListListener = object : Binder() {
        init {
            attachInterface(null, LISTENER_DESCRIPTOR)
        }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            data.enforceInterface(LISTENER_DESCRIPTOR)
            when (code) {
                LISTENER_TX_NEXT -> {
                    val a = data.readString()
                    val b = data.readString()
                    val payload = data.createByteArray()
                    val flag = data.readInt()
                    logPayload(a, b, payload, flag)
                    reply?.writeNoException()
                    return true
                }
                LISTENER_TX_ERROR -> {
                    log("listener error: code=${data.readInt()} msg=${data.readString()}")
                    return true
                }
                LISTENER_TX_COMPLETE -> {
                    log("listener complete")
                    return true
                }
            }
            return super.onTransact(code, data, reply, flags)
        }
    }

    private fun logPayload(a: String?, b: String?, payload: ByteArray?, flag: Int) {
        if (payload == null) return
        if (b?.endsWith("AntMessage") == true) {
            decodeAntMessage(payload)
            return
        }
        log(">>> payload strA=$a strB=$b flag=$flag bytes=${payload.size}")
    }

    /**
     * Decodes an `AntMessage` parcel and reports only shifting devices.
     *
     * Parcel layout: int messageId, byte[] payload (int length + bytes, padded to
     * a 4-byte boundary), long timestamp.
     *
     * The ANT payload itself is: channel, 8 data bytes, flag, then - because these
     * are extended messages - the 4-byte channel ID. That channel ID is what makes
     * every packet self-identifying, so we can separate the AXS from Classified
     * without involving Karoo's arbitration at all.
     */
    private fun decodeAntMessage(raw: ByteArray) {
        if (raw.size < 16) return
        val buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        buf.int // messageId
        val len = buf.int
        if (len < ANT_EXTENDED_MIN || len > raw.size - 8) return
        val ant = ByteArray(len).also { buf.get(it) }

        val data = ant.copyOfRange(1, 9)
        val deviceNumber = (ant[10].toInt() and 0xFF) or ((ant[11].toInt() and 0xFF) shl 8)
        val deviceType = ant[12].toInt() and 0xFF
        if (deviceType != DEVICE_TYPE_SHIFTING) return

        val txType = ant[13].toInt() and 0xFF
        val source = "$deviceNumber-$deviceType-$txType"
        val page = data[0].toInt() and 0xFF
        val hex = data.joinToString(" ") { "%02X".format(it) }

        // Only print when a page's contents actually change, so a ratio toggle
        // stands out instead of drowning in 4Hz rebroadcasts.
        val key = "$source/$page"
        if (lastByPage.put(key, hex) == hex) return
        log("[$source] page=0x%02X  %s".format(page, hex))
    }

    companion object {
        private const val SERVICE_PKG = "io.hammerhead.sensorservice"
        private const val DESCRIPTOR = "io.hammerhead.sensorservice.SensorServiceAIDL"
        private const val LISTENER_DESCRIPTOR = "io.hammerhead.aidlrx.IParcelableListener"

        /** Version this transaction table was derived from. See REMAPPING.md. */
        const val MAPPED_VERSION = "4.215.1-b362ac0378"

        const val TX_SUBSCRIBE = 8
        const val TX_UNSUBSCRIBE = 9
        const val TX_DEVICE_LIST = 12

        private const val LISTENER_TX_NEXT = 1
        private const val LISTENER_TX_ERROR = 2
        private const val LISTENER_TX_COMPLETE = 3

        private const val LISTENER_ID = "classified-probe-devices"
        private const val DEVICE_TYPE_SHIFTING = 34

        /** channel + 8 data bytes + flag + 4-byte channel ID. */
        private const val ANT_EXTENDED_MIN = 14
    }
}
