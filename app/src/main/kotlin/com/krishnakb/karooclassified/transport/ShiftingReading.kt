package com.krishnakb.karooclassified.transport

/**
 * One decoded shift-status broadcast, tagged with the device that sent it.
 *
 * The raw ANT stream carries every paired sensor, so readings from the Classified
 * hub and the AXS derailleur arrive interleaved. [isHub] separates them.
 */
data class ShiftingReading(
    /** Device identity as `deviceNumber-deviceType-transmissionType`. */
    val sourceId: String,
    /** True when the sender reports no cassette, i.e. the Powershift hub. */
    val isHub: Boolean,
    /** 1-based rear gear, or null when the sender has no cassette. */
    val rearGear: Int?,
    /** 1-based front gear. For the hub, 1 is the reduced ratio and 2 is direct. */
    val frontGear: Int?,
)
