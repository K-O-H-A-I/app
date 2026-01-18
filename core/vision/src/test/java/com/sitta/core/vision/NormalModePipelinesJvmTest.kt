package com.sitta.core.vision

import android.graphics.Bitmap
import org.junit.Assert.assertNull
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NormalModePipelinesJvmTest {
    @Test
    fun segmentationReturnsNullWhenOpenCvUnavailable() {
        assumeFalse(OpenCvUtils.ensureLoadedOrFalse())
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val result = NormalModeSegmentation().segment(bitmap)
        assertNull(result)
    }

    @Test
    fun ridgeExtractorReturnsNullWhenOpenCvUnavailable() {
        assumeFalse(OpenCvUtils.ensureLoadedOrFalse())
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val result = NormalModeRidgeExtractor().extractRidges(bitmap, null)
        assertNull(result)
    }
}
