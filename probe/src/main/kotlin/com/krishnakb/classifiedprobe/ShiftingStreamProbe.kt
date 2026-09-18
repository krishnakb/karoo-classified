package com.krishnakb.classifiedprobe

import android.content.Context
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.SavedDevices
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Answers the one question that decides whether this project is possible:
 *
 * **When both the AXS derailleur and the Classified hub are paired in Karoo's
 * Sensors app, does Karoo still receive data from both, or only from the one it
 * decided to display?**
 *
 * Karoo allows a single *active* shifting source, which is why pairing Classified
 * freezes the AXS rear gear field. But [DataPoint] carries a `sourceId`. If both
 * sensors keep streaming and the arbitration only picks which one the *native
 * field* renders, then an extension can read both and present each separately.
 *
 * This is purely a reader. It subscribes to Karoo's shifting data types, which
 * claims no shifting role and displaces nothing.
 *
 * Read the output as follows:
 *  - **two distinct sourceIds** -> both sensors stream; the approach works.
 *  - **one sourceId** -> Karoo only ever receives the winner, and nothing an
 *    extension does can recover the other.
 */
class ShiftingStreamProbe(
    private val context: Context,
    private val log: (String) -> Unit,
) {
    private val karooSystem by lazy { KarooSystemService(context) }
    private var job: Job? = null
    private val sourcesSeen = linkedSetOf<String>()
    private val lastNonStreaming = mutableMapOf<String, String>()

    fun start() {
        log("\nSHIFTING STREAM PROBE")
        log("-".repeat(40))
        log("Connecting to Karoo System...")
        karooSystem.connect { connected ->
            if (!connected) {
                log("FAILED: could not connect to Karoo System.")
                return@connect
            }
            log("Connected. Listing paired devices...\n")
            listDevices()
            streamShifting()
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        runCatching { karooSystem.disconnect() }
    }

    private fun listDevices() {
        job = CoroutineScope(Dispatchers.IO).launch {
            karooSystem.consumerFlow<SavedDevices>().collect { saved ->
                log("PAIRED DEVICES (${saved.devices.size}):")
                saved.devices.forEach { logDevice(it) }
                log("")
            }
        }
    }

    private fun logDevice(device: SavedDevices.SavedDevice) {
        log("  ${device.name}")
        log("    id=${device.id}  ${device.connectionType}  enabled=${device.enabled}")
        val shifting = device.supportedDataTypes.filter { it.contains("SHIFTING") }
        if (shifting.isNotEmpty()) log("    shifting types: ${shifting.joinToString()}")
        device.gearInfo?.let {
            log("    gears: front=${it.maxFrontGears} rear=${it.maxRearGears} " +
                "frontTeeth=${it.frontTeeth} rearTeeth=${it.rearTeeth}")
        }
    }

    private fun streamShifting() {
        SHIFTING_TYPES.forEach { type ->
            CoroutineScope(Dispatchers.IO).launch {
                karooSystem.streamDataFlow(type).collect { state -> logState(type, state) }
            }
        }
        log("Subscribed to ${SHIFTING_TYPES.size} shifting streams.")
        log("Shift the AXS and toggle Classified. Watch the sourceId values.\n")
    }

    /**
     * Logs non-streaming states too, deduplicated per data type.
     *
     * Silence is ambiguous on its own: "sensor connected but nothing shifted" and
     * "Karoo is not streaming to us at all" look identical if only Streaming is
     * logged. Searching / Idle / NotAvailable distinguish them.
     */
    private fun logState(type: String, state: StreamState) {
        val short = type.removePrefix("TYPE_SHIFTING_").removeSuffix("_ID")
        when (state) {
            is StreamState.Streaming -> logPoint(short, state.dataPoint)
            else -> {
                val name = state::class.simpleName ?: "?"
                if (lastNonStreaming.put(short, name) != name) log("[$short] state=$name")
            }
        }
    }

    private fun logPoint(label: String, point: DataPoint) {
        val source = point.sourceId ?: "null"
        if (sourcesSeen.add(source)) {
            log(">>> NEW SOURCE #${sourcesSeen.size}: $source")
        }
        val values = point.values.entries.joinToString {
            "${it.key.removePrefix("FIELD_SHIFTING_").removeSuffix("_ID")}=${it.value}"
        }
        log("[$label] src=$source  $values")
    }

    companion object {
        private val SHIFTING_TYPES = listOf(
            DataType.Type.SHIFTING_FRONT_GEAR,
            DataType.Type.SHIFTING_REAR_GEAR,
            DataType.Type.SHIFTING_GEARS,
        )
    }
}
