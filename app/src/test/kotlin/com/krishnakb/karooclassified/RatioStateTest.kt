package com.krishnakb.karooclassified

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RatioStateTest {

    @Test
    fun `classified ratio shows ON`() {
        assertEquals("ON", RatioState.CLASSIFIED.label)
    }

    @Test
    fun `direct ratio shows OFF`() {
        assertEquals("OFF", RatioState.DIRECT.label)
    }

    @Test
    fun `unknown ratio shows placeholder rather than a ratio`() {
        assertEquals("--", RatioState.UNKNOWN.label)
    }

    @Test
    fun `numeric encoding is one for engaged and zero for direct`() {
        assertEquals(1.0, RatioState.CLASSIFIED.numericValue!!, 0.0)
        assertEquals(0.0, RatioState.DIRECT.numericValue!!, 0.0)
    }

    @Test
    fun `unknown has no numeric value so the field reports searching`() {
        assertNull(RatioState.UNKNOWN.numericValue)
    }
}
