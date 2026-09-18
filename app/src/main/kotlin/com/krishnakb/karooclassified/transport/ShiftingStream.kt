package com.krishnakb.karooclassified.transport

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import com.krishnakb.karooclassified.decode.ShiftingPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.shareIn
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Every shift-status broadcast on the bike, tagged by source device.
 *
 * ## Why this exists
 *
 * karoo-ext's `streamDataFlow` publishes only one shifting source at a time. With
 * both the AXS derailleur and the Classified hub paired, Karoo picks one and the
 * other vanishes - which is what freezes the native gear fields.
 *
 * Karoo's internal sensor service has no such limit. One of its transactions
 * delivers the **raw ANT message stream**, and because ANT extended messages carry
 * the channel ID, every packet identifies its own sender. Reading here sits
 * *upstream* of the arbitration, so neither sensor can displace the other.
 *
 * The flow is shared: one service binding and one ANT subscription serve every
 * data field, however many subscribe.
 *
 * ## Stability
 *
 * This binds an undocumented internal interface. The transaction numbers were read
 * out of the service's decompiled dispatch switch rather than guessed, but a Karoo
 * firmware update can renumber them. [MAPPED_VERSION] pins the version they came
 * from; on a mismatch nothing is sent and no transaction is called, so fields show
 * "--" instead of acting on a transaction whose meaning may have changed.
 *
 * Re-derive with `tools/remap-sensorservice.sh`; see REMAPPING.md.
 */
class ShiftingStream(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob())

    /** Shared across all data fields; the binding is released when the last unsubscribes. */
    val readings: Flow<ShiftingReading> by lazy {
        rawReadings().shareIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), replay = 1)
    }

    /** Releases the shared binding; call from the extension's onDestroy. */
    fun close() {
        scope.cancel()
    }

    private fun rawReadings(): Flow<ShiftingReading> = callbackFlow {
        if (!versionMatches()) {
            Timber.w("hxsensorservice is ${installedVersion()}, mapped against $MAPPED_VERSION")
            Timber.w("Not binding. Re-run tools/remap-sensorservice.sh (see REMAPPING.md).")
            awaitClose { }
            return@callbackFlow
        }

        val listener = object : Binder() {
            init {
                attachInterface(null, LISTENER_DESCRIPTOR)
            }

            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code != LISTENER_TX_NEXT) return super.onTransact(code, data, reply, flags)
                data.enforceInterface(LISTENER_DESCRIPTOR)
                data.readString()
                val type = data.readString()
                val payload = data.createByteArray()
                reply?.writeNoException()
                if (type?.endsWith(ANT_MESSAGE_CLASS) == true && payload != null) {
                    readingFrom(payload)?.let { trySendBlocking(it) }
                }
                return true
            }
        }

        var binder: IBinder? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder) {
                binder = service
                Timber.i("Bound ${service.interfaceDescriptor}")
                transact(service, TX_ANT_STREAM) { it.writeStrongBinder(listener) }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                binder = null
                Timber.w("SensorService disconnected")
            }
        }

        val intent = Intent().setComponent(ComponentName(SERVICE_PKG, SERVICE_CLASS))
        if (!context.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
            Timber.e("bindService failed for $SERVICE_PKG")
        }

        awaitClose {
            binder?.let { transact(it, TX_ANT_STREAM_STOP) {} }
            runCatching { context.unbindService(connection) }
        }
    }

    private inline fun transact(service: IBinder, code: Int, extra: (Parcel) -> Unit) {
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeString(SUBSCRIPTION_ID)
            extra(data)
            service.transact(code, data, null, IBinder.FLAG_ONEWAY)
        } catch (e: Exception) {
            Timber.e(e, "transaction $code failed")
        } finally {
            data.recycle()
        }
    }

    /**
     * Decodes one `AntMessage` parcel, or null if it is not a shift-status page.
     *
     * Parcel layout: int messageId, byte[] (int length + bytes), long timestamp.
     * The ANT payload is: channel, 8 data bytes, flag, 4-byte channel ID, RSSI.
     */
    private fun readingFrom(raw: ByteArray): ShiftingReading? {
        if (raw.size < MIN_PARCEL_SIZE) return null
        val buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        buf.int
        val length = buf.int
        if (length < ANT_EXTENDED_MIN || length > raw.size - Int.SIZE_BYTES * 2) return null
        val ant = ByteArray(length).also { buf.get(it) }

        if ((ant[OFFSET_DEVICE_TYPE].toInt() and 0xFF) != DEVICE_TYPE_SHIFTING) return null
        val data = ant.copyOfRange(1, 9)
        if (!ShiftingPage.isStatusPage(data)) return null

        val deviceNumber = (ant[OFFSET_DEVICE_NUMBER].toInt() and 0xFF) or
            ((ant[OFFSET_DEVICE_NUMBER + 1].toInt() and 0xFF) shl 8)
        val txType = ant[OFFSET_TX_TYPE].toInt() and 0xFF
        return ShiftingReading(
            sourceId = "$deviceNumber-$DEVICE_TYPE_SHIFTING-$txType",
            isHub = ShiftingPage.isHubStatusPage(data),
            rearGear = ShiftingPage.rearGear(data),
            frontGear = ShiftingPage.frontGear(data),
        )
    }

    private fun installedVersion(): String? = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(SERVICE_PKG, 0).versionName
    }.getOrNull()

    private fun versionMatches() = installedVersion() == MAPPED_VERSION

    companion object {
        private const val SERVICE_PKG = "io.hammerhead.sensorservice"
        private const val SERVICE_CLASS = "$SERVICE_PKG.service.SensorService"
        private const val DESCRIPTOR = "$SERVICE_PKG.SensorServiceAIDL"
        private const val LISTENER_DESCRIPTOR = "io.hammerhead.aidlrx.IParcelableListener"
        private const val ANT_MESSAGE_CLASS = "AntMessage"

        /** Version the transaction numbers below were derived from. See REMAPPING.md. */
        const val MAPPED_VERSION = "4.215.1-b362ac0378"

        private const val TX_ANT_STREAM = 12
        private const val TX_ANT_STREAM_STOP = 13
        private const val LISTENER_TX_NEXT = 1

        private const val DEVICE_TYPE_SHIFTING = 34
        private const val OFFSET_DEVICE_NUMBER = 10
        private const val OFFSET_DEVICE_TYPE = 12
        private const val OFFSET_TX_TYPE = 13

        /** channel + 8 data + flag + 4-byte channel ID. */
        private const val ANT_EXTENDED_MIN = 14
        private const val MIN_PARCEL_SIZE = 16

        private const val SUBSCRIPTION_ID = "classified-shifting"

        /** Keeps the binding alive briefly across page changes. */
        private const val STOP_TIMEOUT_MS = 5_000L
    }
}
