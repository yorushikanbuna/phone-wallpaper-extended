package com.example.extendwallpaper

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.exp
import kotlin.math.roundToInt

object WallpaperExtender {
    private const val EXP_K = 1.5
    data class Result(val bitmap: Bitmap, val fillColor: Int, val extendPx: Int)

    /** Extends [source] with the normal background pass and an optional person foreground pass. */
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
        val mask = personMask
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
            val foregroundFeather = if (mask == null) 1f else {
                foregroundEdgeFactor(y, h, position, zone, halfZone)
            }
            for (x in 0 until w) {
                val sourceIndex = y * w + x
                val sourceColor = sourcePixels[sourceIndex]
                val dstIndex = outputY * w + x
                // Pass 1: the exact same background compositing used when recognition is off.
                var composed = composite(output[dstIndex], sourceColor, gradient)
                if (mask != null) {
                    // Pass 2: add only the original pixels selected by the soft person mask.
                    val protect = (mask.valueAt(x, y, w, h) * foregroundFeather).roundToInt()
                    composed = composite(composed, sourceColor, protect)
                }
                output[dstIndex] = composed
            }
        }
        return Result(Bitmap.createBitmap(output, w, targetH, Bitmap.Config.ARGB_8888), baseTop, ext)
    }

    /** Softens only the source-edge entry of the foreground layer to hide a seam. */
    private fun foregroundEdgeFactor(y: Int, height: Int, position: String, zone: Int, halfZone: Int): Float {
        val edgeRows = (if (position == "center") halfZone else zone)
            .times(0.1f).roundToInt().coerceAtLeast(1)
        val distance = when (position) {
            "bottom" -> height - 1 - y
            "center" -> when {
                y < halfZone -> y
                y >= height - halfZone -> height - 1 - y
                else -> edgeRows
            }
            else -> y
        }
        return smoothStep(distance.toFloat() / edgeRows)
    }

    private fun smoothStep(value: Float): Float {
        val t = value.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
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
