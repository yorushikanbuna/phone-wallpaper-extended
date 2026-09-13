package com.example.extendwallpaper

import kotlin.math.floor
import kotlin.math.roundToInt

enum class ProtectionMode { OFF, REAL, ANIME }

/** A soft foreground mask. Values are 0..255 and can be sampled at another bitmap size. */
data class PersonMask(val width: Int, val height: Int, val alpha: ByteArray) {
    init { require(alpha.size == width * height) }

    fun valueAt(x: Int, y: Int, targetWidth: Int, targetHeight: Int): Int {
        if (width <= 0 || height <= 0 || targetWidth <= 0 || targetHeight <= 0) return 0
        val sx = ((x + 0.5f) * width / targetWidth).toInt().coerceIn(0, width - 1)
        val sy = ((y + 0.5f) * height / targetHeight).toInt().coerceIn(0, height - 1)
        return alpha[sy * width + sx].toInt() and 0xff
    }

    /** Resamples a mask with bilinear filtering before masks from different models are merged. */
    fun resampleTo(targetWidth: Int, targetHeight: Int): PersonMask {
        if (width == targetWidth && height == targetHeight) return this
        if (targetWidth <= 0 || targetHeight <= 0) return PersonMask(1, 1, byteArrayOf(0))
        val result = ByteArray(targetWidth * targetHeight)
        for (y in 0 until targetHeight) {
            val sy = (((y + 0.5f) * height / targetHeight) - 0.5f).coerceIn(0f, (height - 1).toFloat())
            val y0 = floor(sy).toInt().coerceIn(0, height - 1)
            val y1 = (y0 + 1).coerceAtMost(height - 1)
            val fy = sy - y0
            for (x in 0 until targetWidth) {
                val sx = (((x + 0.5f) * width / targetWidth) - 0.5f).coerceIn(0f, (width - 1).toFloat())
                val x0 = floor(sx).toInt().coerceIn(0, width - 1)
                val x1 = (x0 + 1).coerceAtMost(width - 1)
                val fx = sx - x0
                val a00 = alpha[y0 * width + x0].toInt() and 0xff
                val a10 = alpha[y0 * width + x1].toInt() and 0xff
                val a01 = alpha[y1 * width + x0].toInt() and 0xff
                val a11 = alpha[y1 * width + x1].toInt() and 0xff
                val top = a00 + (a10 - a00) * fx
                val bottom = a01 + (a11 - a01) * fx
                result[y * targetWidth + x] = (top + (bottom - top) * fy).roundToInt().toByte()
            }
        }
        return PersonMask(targetWidth, targetHeight, result)
    }

    fun union(other: PersonMask): PersonMask {
        require(width == other.width && height == other.height)
        val result = ByteArray(alpha.size)
        for (i in alpha.indices) {
            result[i] = maxOf(alpha[i].toInt() and 0xff, other.alpha[i].toInt() and 0xff).toByte()
        }
        return PersonMask(width, height, result)
    }
}
