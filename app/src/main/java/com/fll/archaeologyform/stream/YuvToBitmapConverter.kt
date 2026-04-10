package com.fll.archaeologyform.stream

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import java.nio.ByteBuffer

internal object YuvToBitmapConverter {

    private var pixels: IntArray = IntArray(0)
    private var yuvBytes: ByteArray = ByteArray(0)
    private var cachedBitmap: Bitmap? = null
    private var lastWidth: Int = 0
    private var lastHeight: Int = 0

    fun convert(yuvData: ByteBuffer, width: Int, height: Int): Bitmap? {
        if (width <= 0 || height <= 0 || width % 2 == 1 || height % 2 == 1) return null

        val frameSize = width * height
        val expectedSize = frameSize + (frameSize shr 1)
        if (yuvData.remaining() < expectedSize) return null

        if (pixels.size < frameSize) pixels = IntArray(frameSize)
        if (yuvBytes.size < expectedSize) yuvBytes = ByteArray(expectedSize)

        val currentBitmap = cachedBitmap
        val bitmap = if (currentBitmap != null && lastWidth == width && lastHeight == height && !currentBitmap.isRecycled) {
            currentBitmap
        } else {
            currentBitmap?.recycle()
            try {
                createBitmap(width, height).also {
                    cachedBitmap = it; lastWidth = width; lastHeight = height
                }
            } catch (_: OutOfMemoryError) { return null }
        }

        val originalPosition = yuvData.position()
        yuvData.get(yuvBytes, 0, expectedSize)
        yuvData.position(originalPosition)

        convertI420ToArgb(yuvBytes, pixels, width, height)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    private fun convertI420ToArgb(yuvBytes: ByteArray, argbOut: IntArray, width: Int, height: Int) {
        val frameSize = width * height
        val uvPlaneSize = frameSize shr 2
        val uOffset = frameSize
        val vOffset = uOffset + uvPlaneSize

        val coeffVr = 1836; val coeffUg = 218; val coeffVg = 546; val coeffUb = 2163
        val halfWidth = width shr 1
        var pixelIndex = 0

        for (row in 0 until height) {
            val uvRowOffset = (row shr 1) * halfWidth
            for (col in 0 until width) {
                val uvIndex = uvRowOffset + (col shr 1)
                val y = (yuvBytes[pixelIndex].toInt() and 0xFF) - 16
                val u = (yuvBytes[uOffset + uvIndex].toInt() and 0xFF) - 128
                val v = (yuvBytes[vOffset + uvIndex].toInt() and 0xFF) - 128
                val yScaled = (y * 1192) shr 10
                val r = yScaled + ((coeffVr * v) shr 10)
                val g = yScaled - ((coeffUg * u + coeffVg * v) shr 10)
                val b = yScaled + ((coeffUb * u) shr 10)
                val rC = (r and (r shr 31).inv()) or ((255 - r) shr 31 and 255) and 255
                val gC = (g and (g shr 31).inv()) or ((255 - g) shr 31 and 255) and 255
                val bC = (b and (b shr 31).inv()) or ((255 - b) shr 31 and 255) and 255
                argbOut[pixelIndex] = 0xFF000000.toInt() or (rC shl 16) or (gC shl 8) or bC
                pixelIndex++
            }
        }
    }
}
