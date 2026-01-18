package com.sitta.core.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HybridFingerprintMatcherTest {
    @Test
    fun findPeaksDetectsRegularIntervals() {
        val matcher = HybridFingerprintMatcher()
        val profile = FloatArray(64) { 0f }
        for (i in 8 until profile.size step 8) {
            profile[i] = 5f
        }
        val peaks = matcher.findPeaks(profile, distance = 3, height = 1f)
        assertTrue(peaks.size >= 6)
        val diffs = peaks.zipWithNext { a, b -> b - a }
        val avg = diffs.average()
        assertEquals(8.0, avg, 1.0)
    }
}
