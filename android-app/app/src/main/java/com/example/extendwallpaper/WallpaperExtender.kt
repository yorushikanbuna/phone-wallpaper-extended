package com.example.extendwallpaper

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.exp
import kotlin.math.roundToInt

object WallpaperExtender {
    private const val EXP_K = 1.5
    /** Keeps the person layer crisp without forcing the entire source row opaque. */
    private const val BACKDROP_MAX_DIM = 1024
    private const val BACKDROP_BLUR_RADIUS = 40f
    data class Result(val bitmap: Bitmap, val fillColor: Int, val extendPx: Int)

    /** Extends [source]; recognition mode uses a blurred backdrop plus the original foreground. */
    fun extend(
        source: Bitmap, phoneW: Int, phoneH: Int, modifyZone: Int = -1,
        position: String = "top", sameColor: Boolean = false,
        presetFill: Int = -1, presetFill2: Int = -1, personMask: PersonMask? = null,
    ): Result {
        val w = source.width; val h = source.height
        require(phoneW > 0 && phoneH > 0) { "phone resolution must be positive" }
        val targetH = (w / (phoneW.toFloat() / phoneH)).roundToInt()
        val ext = targetH - h
        if (ext <= 0) return Result(source, Color.TRANSPARENT, 0)
        val zone = if (modifyZone > 0) modifyZone.coerceAtMost(h) else (h * 0.1f).roundToInt().coerceAtLeast(1)
        val topOffset = when (position) { "bottom" -> 0; "center" -> ext / 2; else -> ext }
        val halfZone = zone / 2
        var fillColor = presetFill
        var fillColor2 = presetFill2
        if (fillColor == -1) {
            val sampleH = minOf(30, h)
            val top = Bitmap.createBitmap(source, 0, 0, w, sampleH)
            val blurred = blur(top, 40f)
            fillColor = medianColor(blurred)
            top.recycle(); blurred.recycle(); fillColor2 = fillColor
        }
        if (fillColor2 == -1) fillColor2 = fillColor

        val output = IntArray(w * targetH)
        val baseTop = if (position == "bottom") fillColor2 else fillColor
        java.util.Arrays.fill(output, baseTop)
        if (position == "center" && !sameColor) {
            val split = (topOffset + h / 2).coerceIn(0, targetH)
            for (y in split until targetH) java.util.Arrays.fill(output, y * w, (y + 1) * w, fillColor2)
        }
        val sourcePixels = IntArray(w * h)
        source.getPixels(sourcePixels, 0, w, 0, 0, w, h)
        // Recognition mode uses a blurred backdrop and then overlays the original
        // foreground from the soft person mask. Keep the backdrop bounded to avoid
        // allocating another full-resolution bitmap for large camera images.
        val mask = personMask
        val backdrop = mask?.let { createBackdrop(source) }
        val denom = 1.0 - exp(-EXP_K)
        for (y in 0 until h) {
            val gradient = when (position) {
                "bottom" -> if (y >= h - zone) alphaAt(h - y, zone, denom) else 255
                "center" -> when {
                    y < halfZone -> alphaAt(y, halfZone.coerceAtLeast(1), denom)
                    y >= h - halfZone -> alphaAt(h - y, halfZone.coerceAtLeast(1), denom)
                    else -> 255
                }
                else -> if (y < zone) alphaAt(y, zone, denom) else 255
            }
            val outputY = topOffset + y
            if (outputY !in 0 until targetH) continue
            for (x in 0 until w) {
                val sourceIndex = y * w + x
                val sourceColor = sourcePixels[sourceIndex]
                val dstIndex = outputY * w + x
                var composed = output[dstIndex]

                if (backdrop == null) {
                    // Keep the original, lightweight compositor when recognition
                    // is disabled. This preserves the lite APK behaviour exactly.
                    composed = composite(composed, sourceColor, gradient)
                } else {
                    // Fade a low-frequency version of the whole image into the
                    // extension fill. It sharpens back to the source by the end
                    // of the fade, so there is no second seam at the zone boundary.
                    val backdropColor = if (gradient < 255) {
                        val blurred = backdrop.colorAt(x, y, w, h)
                        mixColor(blurred, sourceColor, smoothStep(gradient / 255f))
                    } else {
                        sourceColor
                    }
                    composed = composite(composed, backdropColor, gradient)

                    // Finally restore the original person layer. The mask remains
                    // soft at hair edges, while the background keeps its gradient.
                    val protect = mask?.valueAt(x, y, w, h) ?: 0
                    composed = composite(composed, sourceColor, protect)
                }
                output[dstIndex] = composed
            }
        }
        return Result(Bitmap.createBitmap(output, w, targetH, Bitmap.Config.ARGB_8888), baseTop, ext)
    }

    private data class Backdrop(val width: Int, val height: Int, val pixels: IntArray) {
        fun colorAt(x: Int, y: Int, targetWidth: Int, targetHeight: Int): Int {
            val sx = ((x + 0.5f) * width / targetWidth).toInt().coerceIn(0, width - 1)
            val sy = ((y + 0.5f) * height / targetHeight).toInt().coerceIn(0, height - 1)
            return pixels[sy * width + sx]
        }
    }

    private fun createBackdrop(source: Bitmap): Backdrop {
        val sourceW = source.width
        val sourceH = source.height
        val scale = minOf(1f, BACKDROP_MAX_DIM.toFloat() / maxOf(sourceW, sourceH).toFloat())
        val blurW = (sourceW * scale).roundToInt().coerceAtLeast(1)
        val blurH = (sourceH * scale).roundToInt().coerceAtLeast(1)
        val scaled = if (blurW == sourceW && blurH == sourceH) {
            source
        } else {
            Bitmap.createScaledBitmap(source, blurW, blurH, true)
        }
        try {
            val radius = (BACKDROP_BLUR_RADIUS * scale).roundToInt().coerceIn(8, 40)
            val blurred = blur(scaled, radius.toFloat())
            return try {
                val pixels = IntArray(blurW * blurH)
                blurred.getPixels(pixels, 0, blurW, 0, 0, blurW, blurH)
                Backdrop(blurW, blurH, pixels)
            } finally {
                blurred.recycle()
            }
        } finally {
            if (scaled !== source) scaled.recycle()
        }
    }

    private fun smoothStep(value: Float): Float {
        val t = value.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun mixColor(first: Int, second: Int, amount: Float): Int {
        val t = amount.coerceIn(0f, 1f)
        val inverse = 1f - t
        val a = (Color.alpha(first) * inverse + Color.alpha(second) * t).roundToInt()
        val r = (Color.red(first) * inverse + Color.red(second) * t).roundToInt()
        val g = (Color.green(first) * inverse + Color.green(second) * t).roundToInt()
        val b = (Color.blue(first) * inverse + Color.blue(second) * t).roundToInt()
        return Color.argb(a, r, g, b)
    }

    private fun composite(background: Int, source: Int, alpha: Int): Int {
        val sourceAlpha = (Color.alpha(source) * alpha / 255).coerceIn(0, 255)
        if (sourceAlpha <= 0) return background
        if (sourceAlpha >= 255) return source or (0xff shl 24)
        val inverse = 255 - sourceAlpha
        val sr = Color.red(source); val sg = Color.green(source); val sb = Color.blue(source)
        val br = Color.red(background); val bg = Color.green(background); val bb = Color.blue(background)
        return (0xff shl 24) or
            (((sr * sourceAlpha + br * inverse) / 255) shl 16) or
            (((sg * sourceAlpha + bg * inverse) / 255) shl 8) or
            ((sb * sourceAlpha + bb * inverse) / 255)
    }

    private fun alphaAt(dist: Int, range: Int, denom: Double): Int {
        val t = (dist.toFloat() / range).coerceAtMost(1f)
        val curve = (exp(-EXP_K * (1 - t)) - exp(-EXP_K)) / denom
        return (255 * curve).roundToInt().coerceIn(0, 255)
    }

    private fun blur(src: Bitmap, radius: Float): Bitmap {
        val r = radius.roundToInt().coerceAtLeast(2); val w = src.width; val h = src.height
        val pixels = IntArray(w * h); src.getPixels(pixels, 0, w, 0, 0, w, h)
        repeat(3) { boxBlur(pixels, w, h, r) }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun boxBlur(pixels: IntArray, w: Int, h: Int, radius: Int) {
        val tmp = pixels.copyOf(); val kernel = radius * 2 + 1
        for (y in 0 until h) {
            var sr = 0; var sg = 0; var sb = 0; var count = 0
            for (x in 0 until w + radius) {
                if (x < w) { val c = tmp[y * w + x]; sr += c shr 16 and 0xff; sg += c shr 8 and 0xff; sb += c and 0xff; count++ }
                if (x >= kernel) { val c = tmp[y * w + x - kernel]; sr -= c shr 16 and 0xff; sg -= c shr 8 and 0xff; sb -= c and 0xff; count-- }
                if (x >= radius) { val xx = x - radius; pixels[y * w + xx] = (0xff shl 24) or ((sr / count) shl 16) or ((sg / count) shl 8) or (sb / count) }
            }
        }
        pixels.copyInto(tmp)
        for (x in 0 until w) {
            var sr = 0; var sg = 0; var sb = 0; var count = 0
            for (y in 0 until h + radius) {
                if (y < h) { val c = tmp[y * w + x]; sr += c shr 16 and 0xff; sg += c shr 8 and 0xff; sb += c and 0xff; count++ }
                if (y >= kernel) { val c = tmp[(y - kernel) * w + x]; sr -= c shr 16 and 0xff; sg -= c shr 8 and 0xff; sb -= c and 0xff; count-- }
                if (y >= radius) { val yy = y - radius; pixels[yy * w + x] = (0xff shl 24) or ((sr / count) shl 16) or ((sg / count) shl 8) or (sb / count) }
            }
        }
    }

    private fun medianColor(bitmap: Bitmap): Int {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val r = IntArray(pixels.size); val g = IntArray(pixels.size); val b = IntArray(pixels.size)
        pixels.forEachIndexed { i, c -> r[i] = c shr 16 and 0xff; g[i] = c shr 8 and 0xff; b[i] = c and 0xff }
        r.sort(); g.sort(); b.sort(); val m = pixels.size / 2
        return (0xff shl 24) or (r[m] shl 16) or (g[m] shl 8) or b[m]
    }
}
