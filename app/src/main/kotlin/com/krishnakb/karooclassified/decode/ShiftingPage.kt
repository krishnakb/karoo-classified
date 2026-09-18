package com.krishnakb.karooclassified.decode

import com.krishnakb.karooclassified.RatioState

/**
 * Decoder for the ANT+ Shifting page the Classified Smart Thru-Axle broadcasts.
 *
 * ## Verified against real hardware
 *
 * Captured from a Classified hub (`15479-34-5`) on a Karoo 3, with both ratios
 * confirmed physically by the rider. Page `0x01`, 8 data bytes:
 *
 * ```
 * 01 24 FF 3F 40 00 00 00   <- 1:1 Direct
 * 01 25 FF 1F 40 00 00 00   <- 0.686 Classified (reduced)
 *  |  |  |  |  |
 *  |  |  |  |  +-- byte 4: constant 0x40
 *  |  |  |  +----- byte 3: gear field, see below
 *  |  |  +-------- byte 2: constant 0xFF
 *  |  +----------- byte 1: shift event counter, increments on every shift
 *  +-------------- byte 0: page number
 * ```
 *
 * Byte 3 splits as the ANT+ Shifting profile describes:
 *  - low 5 bits  = rear gear. Always `0b11111` (31) for the hub, which has no
 *    cassette knowledge. A derailleur reports a real index here instead - the AXS
 *    was observed sending `0x05`. This is what distinguishes the two.
 *  - bit 5 ([MASK_RATIO]) = the ratio. **Set means Direct**, clear means the
 *    reduction is engaged.
 *
 * The polarity is counter-intuitive - the bit is set when the hub is *not*
 * reducing - so it was confirmed in both directions rather than inferred. It also
 * agrees with Karoo's own decoder, which reported `FRONT_GEAR=1.0` alongside
 * `FRONT_GEAR_MAX=2.0` while in Direct.
 */
object ShiftingPage {
    /** ANT+ Shifting "shift system status" page. */
    const val PAGE_SHIFT_SYSTEM_STATUS = 0x01

    /** ANT+ broadcast payloads are always 8 bytes. */
    const val PAYLOAD_SIZE = 8

    /** Byte holding the gear field. */
    const val OFFSET_GEAR = 3

    /** Bit 5 of [OFFSET_GEAR]: set = Direct, clear = Classified. */
    const val MASK_RATIO = 0x20

    /** Low 5 bits of [OFFSET_GEAR]: the rear gear index. */
    const val MASK_REAR_GEAR = 0x1F

    /** Rear gear value meaning "not applicable", as the hub always reports. */
    const val REAR_GEAR_NONE = 0x1F

    /** Bit position of the front gear field within [OFFSET_GEAR]. */
    const val FRONT_GEAR_SHIFT = 5

    /**
     * Decodes a ratio from an 8-byte ANT+ payload.
     *
     * Returns [RatioState.UNKNOWN] for any payload this decoder does not
     * positively recognise, including shifting pages from a derailleur, so that a
     * misidentified device shows "--" rather than a confidently wrong ratio.
     */
    fun toRatioState(payload: ByteArray): RatioState {
        if (!isHubStatusPage(payload)) return RatioState.UNKNOWN
        val gear = payload[OFFSET_GEAR].toInt() and 0xFF
        return if (gear and MASK_RATIO != 0) RatioState.DIRECT else RatioState.CLASSIFIED
    }

    /**
     * True when [payload] is a shift-status page from a device with no cassette -
     * i.e. the hub rather than a derailleur.
     */
    fun isHubStatusPage(payload: ByteArray): Boolean {
        if (!isStatusPage(payload)) return false
        val gear = payload[OFFSET_GEAR].toInt() and 0xFF
        return (gear and MASK_REAR_GEAR) == REAR_GEAR_NONE
    }

    /** True when [payload] is a well-formed shift-status page from any device. */
    fun isStatusPage(payload: ByteArray): Boolean =
        payload.size == PAYLOAD_SIZE &&
            (payload[0].toInt() and 0xFF) == PAGE_SHIFT_SYSTEM_STATUS

    /**
     * Rear gear as a 1-based index, or null when the device reports none.
     *
     * Verified by sweeping a 13-speed AXS cassette and correlating against Karoo's
     * own decoder: raw `0x00..0x0C` mapped exactly onto gears 1..13.
     */
    fun rearGear(payload: ByteArray): Int? {
        if (!isStatusPage(payload)) return null
        val raw = payload[OFFSET_GEAR].toInt() and MASK_REAR_GEAR
        return if (raw == REAR_GEAR_NONE) null else raw + 1
    }

    /**
     * Front gear as a 1-based index, or null if this is not a status page.
     *
     * For the Classified hub this is the virtual chainring: 1 is the reduced
     * ratio, 2 is direct.
     */
    fun frontGear(payload: ByteArray): Int? {
        if (!isStatusPage(payload)) return null
        val raw = (payload[OFFSET_GEAR].toInt() and 0xFF) ushr FRONT_GEAR_SHIFT
        return raw + 1
    }

    /** Shift event counter, which increments on every shift. Null if not this page. */
    fun eventCount(payload: ByteArray): Int? {
        if (payload.size != PAYLOAD_SIZE) return null
        if ((payload[0].toInt() and 0xFF) != PAGE_SHIFT_SYSTEM_STATUS) return null
        return payload[1].toInt() and 0xFF
    }
}
