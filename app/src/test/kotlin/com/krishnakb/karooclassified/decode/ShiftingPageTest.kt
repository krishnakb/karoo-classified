package com.krishnakb.karooclassified.decode

import com.krishnakb.karooclassified.RatioState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the decoder against payloads captured from real hardware.
 *
 * [DIRECT] and [CLASSIFIED] are verbatim from a Classified hub on a Karoo 3, with
 * the physical ratio confirmed by the rider at the moment of capture. [AXS_PAGE_1]
 * is a genuine shift-status page from a SRAM AXS derailleur on the same stream,
 * which the decoder must reject rather than misread as a hub.
 */
class ShiftingPageTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    /** Captured while physically in 1:1 Direct. */
    private val DIRECT = bytes(0x01, 0x24, 0xFF, 0x3F, 0x40, 0x00, 0x00, 0x00)

    /** Captured while physically in the 0.686 reduced ratio. */
    private val CLASSIFIED = bytes(0x01, 0x25, 0xFF, 0x1F, 0x40, 0x00, 0x00, 0x00)

    /** A real AXS derailleur page 1: reports an actual rear gear (5), not 0x1F. */
    private val AXS_PAGE_1 = bytes(0x01, 0x27, 0xF5, 0x05, 0x2D, 0x6C, 0x00, 0x00)

    @Test
    fun `captured direct payload decodes to DIRECT`() {
        assertEquals(RatioState.DIRECT, ShiftingPage.toRatioState(DIRECT))
    }

    @Test
    fun `captured reduced payload decodes to CLASSIFIED`() {
        assertEquals(RatioState.CLASSIFIED, ShiftingPage.toRatioState(CLASSIFIED))
    }

    @Test
    fun `the two captured payloads differ only in the ratio bit`() {
        val difference = DIRECT[ShiftingPage.OFFSET_GEAR].toInt() xor
            CLASSIFIED[ShiftingPage.OFFSET_GEAR].toInt()
        assertEquals(ShiftingPage.MASK_RATIO, difference)
    }

    @Test
    fun `a derailleur page is not mistaken for the hub`() {
        assertFalse(ShiftingPage.isHubStatusPage(AXS_PAGE_1))
        assertEquals(RatioState.UNKNOWN, ShiftingPage.toRatioState(AXS_PAGE_1))
    }

    @Test
    fun `hub pages are recognised as such`() {
        assertTrue(ShiftingPage.isHubStatusPage(DIRECT))
        assertTrue(ShiftingPage.isHubStatusPage(CLASSIFIED))
    }

    @Test
    fun `other page numbers are rejected`() {
        val batteryPage = bytes(0x52, 0xFF, 0x52, 0x00, 0x00, 0x00, 0x80, 0x31)
        assertFalse(ShiftingPage.isHubStatusPage(batteryPage))
        assertEquals(RatioState.UNKNOWN, ShiftingPage.toRatioState(batteryPage))
    }

    @Test
    fun `payloads of the wrong length are rejected`() {
        assertEquals(RatioState.UNKNOWN, ShiftingPage.toRatioState(ByteArray(4)))
        assertEquals(RatioState.UNKNOWN, ShiftingPage.toRatioState(ByteArray(16)))
        assertNull(ShiftingPage.eventCount(ByteArray(4)))
    }

    @Test
    fun `AXS rear gear is the low five bits plus one`() {
        // Captured sweeping a 13-speed cassette; byte 3 ran 0x00..0x0C for gears 1..13.
        assertEquals(1, ShiftingPage.rearGear(bytes(0x01, 0x3B, 0xF5, 0x00, 0x2D, 0x6C, 0, 0)))
        assertEquals(6, ShiftingPage.rearGear(bytes(0x01, 0x40, 0xF5, 0x05, 0x2D, 0x6C, 0, 0)))
        assertEquals(13, ShiftingPage.rearGear(bytes(0x01, 0x47, 0xF5, 0x0C, 0x2D, 0x6C, 0, 0)))
    }

    @Test
    fun `the hub reports no rear gear`() {
        assertNull(ShiftingPage.rearGear(DIRECT))
        assertNull(ShiftingPage.rearGear(CLASSIFIED))
    }

    @Test
    fun `front gear distinguishes the hub ratios`() {
        // Front 2 is direct drive, front 1 is the reduction.
        assertEquals(2, ShiftingPage.frontGear(DIRECT))
        assertEquals(1, ShiftingPage.frontGear(CLASSIFIED))
    }

    @Test
    fun `a single-ring derailleur reports front gear one`() {
        assertEquals(1, ShiftingPage.frontGear(AXS_PAGE_1))
    }

    @Test
    fun `event counter is read from the captured payloads`() {
        assertEquals(0x24, ShiftingPage.eventCount(DIRECT))
        assertEquals(0x25, ShiftingPage.eventCount(CLASSIFIED))
    }
}
