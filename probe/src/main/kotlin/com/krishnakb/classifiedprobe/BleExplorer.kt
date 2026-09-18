package com.krishnakb.classifiedprobe

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Scans for BLE advertisers, connects to the Classified hub, enumerates its GATT
 * database and dumps every notification it sends.
 *
 * This is the capture harness for the BLE path: run it, shift the hub between
 * ratios, and look for the byte that changes. Everything is logged as hex so the
 * payload layout can be read off directly.
 */
@SuppressLint("MissingPermission")
class BleExplorer(
    private val context: Context,
    private val log: (String) -> Unit,
) {
    private val adapter by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }
    private val handler = Handler(Looper.getMainLooper())
    private val seen = mutableSetOf<String>()
    private val subscribeQueue = ConcurrentLinkedQueue<BluetoothGattCharacteristic>()
    private var gatt: BluetoothGatt? = null
    private var connecting = false
    private var pendingDevice: BluetoothDevice? = null
    private var connectAttempts = 0
    private var watchAddress: String? = null
    private var lastWatchPayload: String? = null
    private var watchCount = 0
    private var lastHeartbeat = 0L

    /**
     * Passively watches one device's advertisements and logs only when its
     * manufacturer payload changes.
     *
     * If the hub encodes its ratio in the advert, this reads it with no GATT
     * connection at all - no bonding, no contention with the ring shifter, and
     * nothing that could disturb Karoo's own radio use.
     */
    fun watch(address: String, durationMs: Long) {
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            log("Bluetooth is off or unavailable - enable it and retry.")
            return
        }
        watchAddress = address.uppercase()
        lastWatchPayload = null
        watchCount = 0
        lastHeartbeat = System.currentTimeMillis()
        log("Watching $watchAddress for ${durationMs / 1000}s.")
        log("Toggle the ratio every ~5s. Only CHANGES are printed.\n")
        scanner.startScan(emptyList(), lowLatencySettings(), scanCallback)
        handler.postDelayed({
            scanner.stopScan(scanCallback)
            log("\nWatch finished.")
        }, durationMs)
    }

    fun scan(durationMs: Long) {
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            log("Bluetooth is off or unavailable - enable it and retry.")
            return
        }
        seen.clear()
        connecting = false
        log("Scanning ${durationMs / 1000}s. Devices named '*classified*' auto-connect.")
        log("Shift the hub while connected to see which bytes change.\n")
        scanner.startScan(emptyList(), lowLatencySettings(), scanCallback)
        handler.postDelayed({ scanner.stopScan(scanCallback); log("\nScan finished.") }, durationMs)
    }

    fun stop() {
        adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        gatt?.disconnect()
        gatt?.close()
        gatt = null
    }

    private fun lowLatencySettings() = ScanSettings.Builder()
        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
        .build()

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val address = result.device.address ?: return
            watchAddress?.let { target ->
                if (address.equals(target, ignoreCase = true)) logIfChanged(result)
                return
            }
            if (!seen.add(address)) return
            val name = result.device.name ?: result.scanRecord?.deviceName ?: "(no name)"
            val uuids = result.scanRecord?.serviceUuids?.joinToString() ?: "none advertised"
            val connectable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (result.isConnectable) "CONNECTABLE" else "NOT-CONNECTABLE"
            } else {
                "connectable-unknown"
            }
            val mfg = manufacturerData(result)
            log("$address  ${result.rssi}dBm  $name  [$connectable]")
            log("    services: $uuids")
            if (mfg.isNotEmpty()) log("    mfgdata: $mfg")
            if (isClassifiedHub(name)) connect(result.device)
        }

        override fun onScanFailed(errorCode: Int) = log("Scan failed, error $errorCode")
    }

    /** The axle advertises as "CC Thru-axle"; earlier builds only matched "classified". */
    private fun isClassifiedHub(name: String): Boolean =
        HUB_NAME_HINTS.any { name.contains(it, ignoreCase = true) }

    /** Connect straight to a known address, skipping discovery. */
    fun connectToAddress(address: String) {
        connectAttempts = 0
        val device = adapter?.getRemoteDevice(address)
        if (device == null) {
            log("Could not resolve $address")
            return
        }
        log("Connecting directly to $address")
        connecting = false
        connect(device)
    }

    /**
     * Prints the whole raw advertisement when any byte of it changes, plus a
     * periodic heartbeat.
     *
     * The heartbeat matters: with change-only logging, "the hub is advertising a
     * constant payload" and "we stopped receiving adverts" produce identical
     * output. The count distinguishes them.
     */
    private fun logIfChanged(result: ScanResult) {
        watchCount++
        val raw = result.scanRecord?.bytes?.joinToString(" ") { "%02X".format(it) } ?: return
        if (raw != lastWatchPayload) {
            lastWatchPayload = raw
            log("[CHANGE #$watchCount] ${result.rssi}dBm")
            log("  raw: $raw")
        }
        val now = System.currentTimeMillis()
        if (now - lastHeartbeat > HEARTBEAT_MS) {
            lastHeartbeat = now
            log("... $watchCount adverts received, payload unchanged (${result.rssi}dBm)")
        }
    }

    /** Manufacturer-specific advert bytes; a two-state hub may expose ratio here. */
    private fun manufacturerData(result: ScanResult): String {
        val data = result.scanRecord?.manufacturerSpecificData ?: return ""
        return (0 until data.size()).joinToString("  ") { i ->
            val id = data.keyAt(i)
            val bytes = data.valueAt(i).joinToString(" ") { "%02X".format(it) }
            "id=0x%04X [%s]".format(id, bytes)
        }
    }

    private fun connect(device: BluetoothDevice) {
        if (connecting) return
        connecting = true
        log("\n>>> Connecting to ${device.address} (${device.name}) attempt ${connectAttempts + 1}")
        pendingDevice = device
        // Explicit LE transport avoids the BR/EDR fallback that yields status 62.
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                log(">>> Connected (status $status), discovering services")
                g.discoverServices()
            } else {
                log(">>> Disconnected (status $status)")
                g.close()
                connecting = false
                if (status == STATUS_CONN_FAIL_ESTABLISH) retryConnect()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            log("\n>>> GATT database:")
            val characteristics = g.services.flatMap { service ->
                log("  service ${service.uuid}")
                service.characteristics.onEach { logCharacteristic(it) }
            }
            subscribeQueue.addAll(characteristics.filter { isNotifiable(it) })
            log("\n>>> Subscribing to ${subscribeQueue.size} notifiable characteristics...")
            subscribeNext(g)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            subscribeNext(g)
        }

        // Called on API < 33; API 33+ calls the 3-arg overload below.
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            logNotification(c.uuid, c.value)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            c: BluetoothGattCharacteristic,
            value: ByteArray,
        ) = logNotification(c.uuid, value)
    }

    /**
     * Status 62 on a first attempt is common with sleepy sensors; a short retry
     * usually succeeds where the initial connect did not.
     */
    private fun retryConnect() {
        val device = pendingDevice ?: return
        if (connectAttempts >= MAX_CONNECT_ATTEMPTS) {
            log(">>> Giving up after $MAX_CONNECT_ATTEMPTS attempts. Wake the hub and retry.")
            connectAttempts = 0
            return
        }
        connectAttempts++
        handler.postDelayed({ connect(device) }, RETRY_DELAY_MS)
    }

    private fun logCharacteristic(c: BluetoothGattCharacteristic) {
        val props = mutableListOf<String>()
        val p = c.properties
        if (p and BluetoothGattCharacteristic.PROPERTY_READ != 0) props += "read"
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) props += "write"
        if (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) props += "notify"
        if (p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) props += "indicate"
        log("    char ${c.uuid}  [${props.joinToString(",")}]")
    }

    private fun isNotifiable(c: BluetoothGattCharacteristic): Boolean {
        val mask = BluetoothGattCharacteristic.PROPERTY_NOTIFY or
            BluetoothGattCharacteristic.PROPERTY_INDICATE
        return c.properties and mask != 0
    }

    @Suppress("DEPRECATION")
    private fun subscribeNext(g: BluetoothGatt) {
        val c = subscribeQueue.poll() ?: run {
            log(">>> Subscribed to all. Shift the hub now.\n")
            return
        }
        g.setCharacteristicNotification(c, true)
        val cccd = c.getDescriptor(CCCD_UUID)
        if (cccd == null) {
            subscribeNext(g)
            return
        }
        cccd.value = enableValue(c)
        g.writeDescriptor(cccd)
    }

    private fun enableValue(c: BluetoothGattCharacteristic): ByteArray =
        if (c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        }

    private fun logNotification(uuid: UUID, value: ByteArray?) {
        val hex = value?.joinToString(" ") { "%02X".format(it) } ?: "(empty)"
        log("[${System.currentTimeMillis() % 100000}] $uuid  $hex")
    }

    companion object {
        private val HUB_NAME_HINTS = listOf("classified", "thru-axle", "thru axle")
        private const val STATUS_CONN_FAIL_ESTABLISH = 62
        private const val MAX_CONNECT_ATTEMPTS = 4
        private const val RETRY_DELAY_MS = 1500L
        private const val HEARTBEAT_MS = 10_000L

        /** Standard Client Characteristic Configuration Descriptor. */
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
