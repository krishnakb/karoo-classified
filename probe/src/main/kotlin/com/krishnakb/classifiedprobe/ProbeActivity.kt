package com.krishnakb.classifiedprobe

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/**
 * Hardware investigation harness for the Classified Powershift hub.
 *
 * Two independent checks, both of which need to run on the real Karoo next to
 * the real hub:
 *  1. Can a sideloaded app reach ANT+ at all on this device?
 *  2. What does the hub expose over BLE, and which bytes change on a shift?
 *
 * Everything written to the on-screen log also goes to logcat under the tag
 * [LOG_TAG], so a session can be captured with:
 *   adb logcat -s ClassifiedProbe
 */
class ProbeActivity : AppCompatActivity() {

    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private val ble by lazy { BleExplorer(this, ::log) }
    private var pendingConnectAddress: String? = null
    private val shifting by lazy { ShiftingStreamProbe(this, ::log) }
    private val sensorSvc by lazy { SensorServiceProbe(this, ::log) }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val denied = grants.filterValues { !it }.keys
            if (denied.isEmpty()) {
                pendingConnectAddress?.let { ble.connectToAddress(it) } ?: ble.scan(SCAN_DURATION_MS)
            }
            else log("Permissions denied: ${denied.joinToString()} - cannot scan.")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_probe)
        logView = findViewById(R.id.log)
        scroll = findViewById(R.id.scroll)

        findViewById<Button>(R.id.btn_ant).setOnClickListener { log(AntEnvironment.report(this)) }
        findViewById<Button>(R.id.btn_ble).setOnClickListener { requestPermissionsThenScan() }
        findViewById<Button>(R.id.btn_shift).setOnClickListener { shifting.start() }
        findViewById<Button>(R.id.btn_clear).setOnClickListener { logView.text = "" }

        log(AntEnvironment.report(this))

        // Lets a capture session be driven over adb:
        //   adb shell am start -n <pkg>/.ProbeActivity --ez autoscan true
        if (intent.getBooleanExtra(EXTRA_AUTOSCAN, false)) requestPermissionsThenScan()
        intent.getStringExtra(EXTRA_CONNECT)?.let { connectDirect(it) }
        intent.getStringExtra(EXTRA_WATCH)?.let { ble.watch(it, WATCH_DURATION_MS) }
        if (intent.getBooleanExtra(EXTRA_SHIFTSTREAM, false)) shifting.start()
        if (intent.getBooleanExtra(EXTRA_SVCBIND, false)) sensorSvc.start()
    }

    override fun onDestroy() {
        ble.stop()
        shifting.stop()
        sensorSvc.stop()
        super.onDestroy()
    }

    private fun connectDirect(address: String) {
        pendingConnectAddress = address
        val needed = requiredBluetoothPermissions().filter {
            checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (needed.isEmpty()) ble.connectToAddress(address)
        else permissionLauncher.launch(needed.toTypedArray())
    }

    private fun requestPermissionsThenScan() {
        val needed = requiredBluetoothPermissions().filter {
            checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (needed.isEmpty()) ble.scan(SCAN_DURATION_MS)
        else permissionLauncher.launch(needed.toTypedArray())
    }

    /** Karoo 2 is API 27 and needs location for BLE scanning; Karoo 3 is API 30. */
    private fun requiredBluetoothPermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun log(message: String) {
        Log.i(LOG_TAG, message)
        runOnUiThread {
            logView.append(message + "\n")
            scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    companion object {
        private const val LOG_TAG = "ClassifiedProbe"
        private const val EXTRA_AUTOSCAN = "autoscan"
        private const val EXTRA_CONNECT = "connect"
        private const val EXTRA_WATCH = "watch"
        private const val EXTRA_SHIFTSTREAM = "shiftstream"
        private const val EXTRA_SVCBIND = "svcbind"
        private const val WATCH_DURATION_MS = 120_000L
        private const val SCAN_DURATION_MS = 60_000L
    }
}
