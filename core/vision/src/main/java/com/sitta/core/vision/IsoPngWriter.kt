package com.sitta.core.vision

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

object IsoPngWriter {
    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )

    fun resampleToIso(bitmap: Bitmap, targetDpi: Int = 500, sourceDpi: Int = 72): Bitmap {
        val scale = targetDpi.toDouble() / sourceDpi.toDouble()
        val newWidth = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val newHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)
        val filter = scale > 1.0
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, filter)
    }

    fun savePngWithDpi(bitmap: Bitmap, outputFile: File, dpi: Int = 500): Boolean {
        val pngBytes = encodePng(bitmap) ?: return false
        val bytesWithDpi = injectPhysChunk(pngBytes, dpi) ?: return false
        return runCatching {
            outputFile.parentFile?.mkdirs()
            FileOutputStream(outputFile).use { it.write(bytesWithDpi) }
            true
        }.getOrDefault(false)
    }

    private fun encodePng(bitmap: Bitmap): ByteArray? {
        val stream = ByteArrayOutputStream()
        val ok = bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        return if (ok) stream.toByteArray() else null
    }

    private fun injectPhysChunk(pngBytes: ByteArray, dpi: Int): ByteArray? {
        if (pngBytes.size < PNG_SIGNATURE.size) return null
        if (!pngBytes.copyOfRange(0, PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE)) return null

        val pixelsPerMeter = dpiToPixelsPerMeter(dpi)
        val physData = ByteBuffer.allocate(9).order(ByteOrder.BIG_ENDIAN)
            .putInt(pixelsPerMeter)
            .putInt(pixelsPerMeter)
            .put(1) // unit: meter
            .array()

        val physChunk = buildChunk("pHYs", physData)

        // Insert pHYs before IEND; replace existing if found
        var offset = PNG_SIGNATURE.size
        val output = ByteArrayOutputStream()
        output.write(pngBytes, 0, PNG_SIGNATURE.size)
        var physWritten = false

        while (offset + 8 <= pngBytes.size) {
            val length = readInt(pngBytes, offset)
            val type = String(pngBytes, offset + 4, 4)
            val chunkSize = 12 + length
            if (offset + chunkSize > pngBytes.size) break

            if (type == "pHYs") {
                if (!physWritten) {
                    output.write(physChunk)
                    physWritten = true
                }
                // skip existing pHYs
            } else if (type == "IEND") {
                if (!physWritten) {
                    output.write(physChunk)
                    physWritten = true
                }
                output.write(pngBytes, offset, chunkSize)
            } else {
                output.write(pngBytes, offset, chunkSize)
            }
            offset += chunkSize
        }
        return output.toByteArray()
    }

    private fun buildChunk(type: String, data: ByteArray): ByteArray {
        val length = data.size
        val buffer = ByteArrayOutputStream()
        buffer.write(intToBytes(length))
        buffer.write(type.toByteArray())
        buffer.write(data)
        val crc = CRC32()
        crc.update(type.toByteArray())
        crc.update(data)
        buffer.write(intToBytes(crc.value.toInt()))
        return buffer.toByteArray()
    }

    private fun intToBytes(value: Int): ByteArray {
        return ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value).array()
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int {
        return ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.BIG_ENDIAN).int
    }

    internal fun dpiToPixelsPerMeter(dpi: Int): Int {
        return ((dpi / 0.0254) + 0.5).toInt()
    }
}
