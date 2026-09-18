package com.krishnakb.karooclassified.extension

import android.content.Context
import com.krishnakb.karooclassified.R
import com.krishnakb.karooclassified.transport.ShiftingStream
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch

/**
 * Rear gear read from the derailleur's own ANT channel.
 *
 * Karoo's native Rear Gear field freezes whenever a Classified hub wins the single
 * active shifting slot. This reads the derailleur directly from the raw stream, so
 * it keeps working regardless of which sensor Karoo has picked.
 */
class RearGearDataType(
    private val stream: ShiftingStream,
    extension: String,
) : DataTypeImpl(extension, TYPE_ID) {

    /** Null means no reading yet; the hub never reports a cassette position. */
    private fun gears(): Flow<Int?> = stream.readings
        .filter { !it.isHub }
        .map { it.rearGear }
        .filter { it != null }
        .onStart { emit(null) }
        .distinctUntilChanged()

    override fun startStream(emitter: Emitter<StreamState>) {
        val job = CoroutineScope(Dispatchers.IO).launch {
            gears().collect { gear ->
                emitter.onNext(
                    if (gear == null) {
                        StreamState.Searching
                    } else {
                        StreamState.Streaming(
                            DataPoint(dataTypeId, mapOf(DataType.Field.SINGLE to gear.toDouble())),
                        )
                    },
                )
            }
        }
        emitter.setCancellable { job.cancel() }
    }

    /**
     * Renders the tile directly rather than relying on Karoo's numeric view.
     *
     * Karoo does not reliably call [startStream] - sometimes only `startView` - and
     * a field that depends on the stream freezes at its last value when that
     * happens. Drawing here works either way.
     */
    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        // Draw our own header. Karoo's header consumes 69px of a 126px tile,
        // leaving only 57px for the value; our label row costs less, so the number
        // renders considerably larger for the same tile size.
        emitter.onNext(UpdateGraphicConfig(showHeader = false))
        val job = CoroutineScope(Dispatchers.IO).launch {
            gears().collectLatest { gear ->
                while (true) {
                    emitter.updateView(
                        FieldTile.render(
                            context = context,
                            config = config,
                            label = context.getString(R.string.rear_gear_display_name),
                            iconRes = R.drawable.ic_rear_gear,
                            value = gear?.toString() ?: context.getString(R.string.ratio_placeholder),
                            backgroundColor = context.getColor(R.color.ratio_idle_background),
                            textColor = context.getColor(R.color.ratio_off_text),
                        ),
                    )
                    delay(REPAINT_INTERVAL_MS)
                }
            }
        }
        emitter.setCancellable { job.cancel() }
    }

    companion object {
        const val TYPE_ID = "derailleur-rear-gear"

        private const val REPAINT_INTERVAL_MS = 1_000L
    }
}
