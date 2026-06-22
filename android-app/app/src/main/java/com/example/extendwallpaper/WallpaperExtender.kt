package com.example.extendwallpaper

import android.graphics.*
import kotlin.math.*

object WallpaperExtender {
    private const val EXP_K = 3.0
    private const val FILL_SAMPLE_H: Int = 30

    data class Result(val bitmap: Bitmap, val fillColor: Int, val extendPx: Int)

    fun extend(source: Bitmap, phoneW: Int, phoneH: Int, modifyZone: Int = -1,
               position: String = "top"): Result {
        val w = source.width; val h = source.height
        val targetH = (w / (phoneW.toFloat() / phoneH)).roundToInt()
        val ext = targetH - h
        if (ext <= 0) return Result(source, Color.TRANSPARENT, 0)

        val zone = if (modifyZone > 0) modifyZone else (h * 0.1f).roundToInt()
        val topOffset = when (position) { "bottom" -> ext; "center" -> ext / 2; else -> ext }
        val halfZone = zone / 2

        // 1. Fill colour from blurred top 30px
        val topH = min(FILL_SAMPLE_H, h)
        val top = Bitmap.createBitmap(source, 0, 0, w, topH)
        val blurred = blur(top, 40f)
        val fillColor = medianColor(blurred)
        top.recycle(); blurred.recycle()

        // 2. Fill background
        val bg = Bitmap.createBitmap(w, targetH, Bitmap.Config.ARGB_8888)
        bg.eraseColor(fillColor)

        // 3. Original with S-curve alpha gradient
        val denom = 1.0 - exp(-EXP_K)
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        val canvas = Canvas(bg)
        val paint = Paint()

        fun alphaFromTop(dist: Int, range: Int): Int {
            val t = (dist.toFloat() / range).coerceAtMost(1f)
            return (255 * ((exp(-EXP_K * (1 - t)) - exp(-EXP_K)) / denom)).roundToInt().coerceIn(0, 255)
        }

        for (y in 0 until h) {
            val alpha = when (position) {
                "bottom" -> if (y >= h - zone) alphaFromTop(h - y, zone) else 255
                "center" -> when {
                    y < halfZone -> alphaFromTop(y, halfZone)
                    y >= h - halfZone -> alphaFromTop(h - y, halfZone)
                    else -> 255
                }
                else -> if (y < zone) alphaFromTop(y, zone) else 255  // top
            }
            if (alpha <= 0) continue
            if (alpha >= 255) {
                val row = Bitmap.createBitmap(pixels, y * w, w, w, 1, Bitmap.Config.ARGB_8888)
                canvas.drawBitmap(row, 0f, (topOffset + y).toFloat(), null)
                row.recycle()
            } else {
                paint.alpha = alpha
                val row = Bitmap.createBitmap(pixels, y * w, w, w, 1, Bitmap.Config.ARGB_8888)
                canvas.drawBitmap(row, 0f, (topOffset + y).toFloat(), paint)
                row.recycle()
            }
        }
        return Result(bg, fillColor, ext)
    }

    private fun blur(src: Bitmap, radius: Float): Bitmap {
        val s = (1f / (1f + radius * 0.1f)).coerceIn(0.02f, 1f)
        val sw = (src.width * s).roundToInt().coerceAtLeast(1)
        val sh = (src.height * s).roundToInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(src, sw, sh, true)
        val result = Bitmap.createScaledBitmap(small, src.width, src.height, true)
        small.recycle()
        return result
    }

    private fun medianColor(bmp: Bitmap): Int {
        val n = bmp.width * bmp.height
        val p = IntArray(n); bmp.getPixels(p, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val r = IntArray(n); val g = IntArray(n); val b = IntArray(n)
        for (i in 0 until n) {
            val c = p[i]; r[i] = c shr 16 and 0xFF; g[i] = c shr 8 and 0xFF; b[i] = c and 0xFF
        }
        r.sort(); g.sort(); b.sort()
        val m = n / 2
        return 0xFF shl 24 or (r[m] shl 16) or (g[m] shl 8) or b[m]
    }
}
