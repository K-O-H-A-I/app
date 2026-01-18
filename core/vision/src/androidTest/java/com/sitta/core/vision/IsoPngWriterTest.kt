package com.sitta.core.vision

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class IsoPngWriterTest {
    @Test
    fun writesPhysChunkWithExpectedDpi() {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val output = File.createTempFile("iso_test", ".png")
        output.deleteOnExit()

        val ok = IsoPngWriter.savePngWithDpi(bitmap, output, 500)
        assertEquals(true, ok)

        val bytes = output.readBytes()
        val phys = readPhysChunk(bytes)
        assertNotNull("pHYs chunk missing", phys)
        val (ppmX, ppmY, unit) = phys!!
        val expected = IsoPngWriter.dpiToPixelsPerMeter(500)
        assertEquals(expected, ppmX)
        assertEquals(expected, ppmY)
        assertEquals(1, unit)
    }

    private fun readPhysChunk(bytes: ByteArray): Triple<Int, Int, Int>? {
        var idx = 8 // PNG signature
        while (idx + 8 < bytes.size) {
            val length = readInt(bytes, idx)
            val type = String(bytes, idx + 4, 4)
            val dataStart = idx + 8
            if (type == "pHYs" && length == 9) {
                val ppmX = readInt(bytes, dataStart)
                val ppmY = readInt(bytes, dataStart + 4)
                val unit = bytes[dataStart + 8].toInt() and 0xFF
                return Triple(ppmX, ppmY, unit)
            }
            idx += 12 + length
        }
        return null
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF shl 24) or
            (bytes[offset + 1].toInt() and 0xFF shl 16) or
            (bytes[offset + 2].toInt() and 0xFF shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
    }
}
