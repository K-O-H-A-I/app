package com.sitta.core.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QualityStabilityGateTest {
    @Test
    fun `requires stable duration to pass`() {
        val gate = QualityStabilityGate(500L)
        assertFalse(gate.update(true, 1000L))
        assertFalse(gate.update(true, 1200L))
        assertTrue(gate.update(true, 1600L))
    }

    @Test
    fun `resets on failure`() {
        val gate = QualityStabilityGate(500L)
        assertFalse(gate.update(true, 1000L))
        assertFalse(gate.update(false, 1300L))
        assertFalse(gate.update(true, 1400L))
        assertFalse(gate.update(true, 1700L))
        assertTrue(gate.update(true, 2000L))
    }
}
