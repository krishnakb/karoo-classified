package com.krishnakb.karooclassified.extension

import com.krishnakb.karooclassified.transport.ShiftingStream
import io.hammerhead.karooext.extension.KarooExtension
import timber.log.Timber

/**
 * Karoo extension exposing shifting data read from the raw ANT stream.
 *
 * Both fields share a single [ShiftingStream], which reads Karoo's internal sensor
 * service upstream of its single-active-shifting-source arbitration. Neither field
 * registers a built-in shifting data type, so nothing here competes with Karoo's
 * own sensor handling.
 */
class ClassifiedExtension : KarooExtension(EXTENSION_ID, VERSION) {

    private val stream by lazy { ShiftingStream(this) }

    override val types by lazy {
        listOf(
            ClassifiedRatioDataType(stream, extension),
            RearGearDataType(stream, extension),
        )
    }

    override fun onCreate() {
        super.onCreate()
        if (Timber.treeCount == 0) Timber.plant(Timber.DebugTree())
        Timber.i("Classified extension started")
    }

    override fun onDestroy() {
        stream.close()
        super.onDestroy()
    }

    companion object {
        // Must match the id in res/xml/extension_info.xml and contain no '.'
        const val EXTENSION_ID = "classified"
        private const val VERSION = "0.2"
    }
}
