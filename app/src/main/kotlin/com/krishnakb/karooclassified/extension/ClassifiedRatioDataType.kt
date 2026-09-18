package com.krishnakb.karooclassified.extension

import android.content.Context
import com.krishnakb.karooclassified.R
import com.krishnakb.karooclassified.RatioState
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
 * Whether the Classified hub is in its reduced ratio ("ON") or direct 1:1 ("OFF").
 *
 * Registers its own data type only, never a built-in shifting type, so Karoo's
 * shifting-sensor arbitration is unaffected by its presence.
 */
class ClassifiedRatioDataType(
    private val stream: ShiftingStream,
    extension: String,
) : DataTypeImpl(extension, TYPE_ID) {

    private fun states(): Flow<RatioState> = stream.readings
        // Ignore the derailleur entirely. Mapping its readings to UNKNOWN instead
        // made the field alternate between the ratio and "--" several times a
        // second, because both devices broadcast onto the same stream.
        .filter { it.isHub }
        // Front gear 2 is direct drive; 1 is the 0.686 reduction.
        .map { if (it.frontGear == FRONT_GEAR_DIRECT) RatioState.DIRECT else RatioState.CLASSIFIED }
        .onStart { emit(RatioState.UNKNOWN) }
        .distinctUntilChanged()

    override fun startStream(emitter: Emitter<StreamState>) {
        val job = CoroutineScope(Dispatchers.IO).launch {
            states().collect { state ->
                val value = state.numericValue
                emitter.onNext(
                    if (value == null) {
                        StreamState.Searching
                    } else {
                        StreamState.Streaming(
                            DataPoint(dataTypeId, mapOf(DataType.Field.SINGLE to value)),
                        )
                    },
                )
            }
        }
        emitter.setCancellable { job.cancel() }
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        emitter.onNext(UpdateGraphicConfig(showHeader = false))
        val job = CoroutineScope(Dispatchers.IO).launch {
            states().collectLatest { state ->
                // Karoo throttles view updates to ~1Hz and drops anything faster,
                // so repaint on a timer to converge even if a shift lands inside
                // the throttle window.
                while (true) {
                    emitter.updateView(
                        FieldTile.render(
                            context = context,
                            config = config,
                            label = context.getString(R.string.ratio_display_name),
                            iconRes = R.drawable.ic_classified,
                            value = state.label,
                            backgroundColor = context.getColor(state.backgroundRes()),
                            textColor = context.getColor(state.textColorRes()),
                            italicLabel = true,
                        ),
                    )
                    delay(REPAINT_INTERVAL_MS)
                }
            }
        }
        emitter.setCancellable { job.cancel() }
    }

    /** The whole field goes green when the reduction is engaged. */
    private fun RatioState.backgroundRes(): Int = when (this) {
        RatioState.CLASSIFIED -> R.color.ratio_on_background
        RatioState.DIRECT, RatioState.UNKNOWN -> R.color.ratio_idle_background
    }

    /** Dark text on the green fill, light text on the plain field. */
    private fun RatioState.textColorRes(): Int = when (this) {
        RatioState.CLASSIFIED -> R.color.ratio_on_text
        RatioState.DIRECT -> R.color.ratio_off_text
        RatioState.UNKNOWN -> R.color.ratio_unknown_text
    }

    companion object {
        const val TYPE_ID = "classified-ratio-state"
        private const val FRONT_GEAR_DIRECT = 2
        private const val REPAINT_INTERVAL_MS = 1_000L
    }
}
