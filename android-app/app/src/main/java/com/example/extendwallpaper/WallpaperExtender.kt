package com.example.extendwallpaper

import android.graphics.*
import kotlin.math.*

object WallpaperExtender {
    private const val EXP_K = 3.0

    data class Result(val bitmap: Bitmap, val fillColor: Int, val extendPx: Int)

    fun extend(source: Bitmap, phoneW: Int, phoneH: Int, modifyZone: Int = -1,
               position: String = "top"): Result {
        val w = source.width; val h = source.height
        val targetH = (w / (phoneW.toFloat() / phoneH)).roundToInt()
        val ext = targetH - h
        if (ext <= 0) return Result(source, Color.TRANSPARENT, 0)

        val zone = if (modifyZone > 0) modifyZone else (h * 0.1f).roundToInt()
        val topOffset = when (position) { "bottom" -> 0; "center" -> ext / 2; else -> ext }
        val halfZone = zone / 2

        // 1. Fill colour from middle of modify zone
        val sampleTop = (zone * 0.4f).roundToInt()
        val sampleH = min((zone * 0.6f).roundToInt(), h - sampleTop)
        val top = Bitmap.createBitmap(source, 0, sampleTop, w, sampleH)
        val blurred = blur(top, 40f)
        val fillColor = medianColor(blurred)
        top.recycle(); blurred.recycle()

        // 2. Pure solid fill background
        val bg = Bitmap.createBitmap(w, targetH, Bitmap.Config.ARGB_8888)
        bg.eraseColor(fillColor)

        // 3. Original with power-curve alpha gradient
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        val canvas = Canvas(bg)
        val paint = Paint()

        val alphaDenom = 1.0 - exp(-EXP_K)
        fun alphaFromTop(dist: Int, range: Int): Int {
            val t = (dist.toFloat() / range).coerceAtMost(1f)
            val curve = (exp(-EXP_K * (1 - t)) - exp(-EXP_K)) / alphaDenom
            // Smooth landing: last 15% blends into 1.0
            val tail = maxOf(0f, minOf(1f, (t - 0.85f) / 0.15f))
            val tailEased = tail * tail * (3 - 2 * tail)
            return (255 * (curve * (1 - tailEased) + tailEased)).roundToInt().coerceIn(0, 255)
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

    // ── Stacked box blur (approximates Gaussian, 3 passes) ──
    private fun blur(src: Bitmap, radius: Float): Bitmap {
        val r = radius.roundToInt().coerceAtLeast(2)
        val w = src.width; val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        // 3 passes approximates true Gaussian
        boxBlur(pixels, w, h, r)
        boxBlur(pixels, w, h, r)
        boxBlur(pixels, w, h, r)

        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun boxBlur(pixels: IntArray, w: Int, h: Int, radius: Int) {
        val tmp = pixels.copyOf()
        val kernel = radius * 2 + 1

        // Horizontal pass
        for (y in 0 until h) {
            var sumR = 0; var sumG = 0; var sumB = 0; var count = 0
            for (x in 0 until w + radius) {
                // Add right edge
                if (x < w) {
                    val c = tmp[y * w + x]
                    sumR += c shr 16 and 0xFF; sumG += c shr 8 and 0xFF; sumB += c and 0xFF; count++
                }
                // Remove left edge
                if (x >= kernel) {
                    val c = tmp[y * w + (x - kernel)]
                    sumR -= c shr 16 and 0xFF; sumG -= c shr 8 and 0xFF; sumB -= c and 0xFF; count--
                }
                // Write center
                if (x >= radius) {
                    val cx = x - radius; val avgR = sumR / count; val avgG = sumG / count; val avgB = sumB / count
                    pixels[y * w + cx] = 0xFF shl 24 or (avgR shl 16) or (avgG shl 8) or avgB
                }
            }
        }

        // Vertical pass — use horizontally-blurred result as source
        pixels.copyInto(tmp)
        for (x in 0 until w) {
            var sumR = 0; var sumG = 0; var sumB = 0; var count = 0
            for (y in 0 until h + radius) {
                if (y < h) {
                    val c = tmp[y * w + x]
                    sumR += c shr 16 and 0xFF; sumG += c shr 8 and 0xFF; sumB += c and 0xFF; count++
                }
                if (y >= kernel) {
                    val c = tmp[(y - kernel) * w + x]
                    sumR -= c shr 16 and 0xFF; sumG -= c shr 8 and 0xFF; sumB -= c and 0xFF; count--
                }
                if (y >= radius) {
                    val cy = y - radius; val avgR = sumR / count; val avgG = sumG / count; val avgB = sumB / count
                    pixels[cy * w + x] = 0xFF shl 24 or (avgR shl 16) or (avgG shl 8) or avgB
                }
            }
        }
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
