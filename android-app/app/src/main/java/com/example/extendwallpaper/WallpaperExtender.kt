package com.example.extendwallpaper

import android.graphics.*
import kotlin.math.*

object WallpaperExtender {
    private const val EXP_K = 3.0

    data class Result(val bitmap: Bitmap, val fillColor: Int, val extendPx: Int)

    fun extend(source: Bitmap, phoneW: Int, phoneH: Int, modifyZone: Int = -1,
               position: String = "top", sameColor: Boolean = false,
               presetFill: Int = -1, presetFill2: Int = -1): Result {
        val w = source.width; val h = source.height
        val targetH = (w / (phoneW.toFloat() / phoneH)).roundToInt()
        val ext = targetH - h
        if (ext <= 0) return Result(source, Color.TRANSPARENT, 0)

        val zone = if (modifyZone > 0) modifyZone else (h * 0.1f).roundToInt()
        val topOffset = when (position) { "bottom" -> 0; "center" -> ext / 2; else -> ext }
        val halfZone = zone / 2

        var fillColor = presetFill
        var fillColor2 = presetFill2

        // Use preset colours from caller (MainActivity computes them)
        // Fallback: blur+median on top 30px (legacy, when called without preset)
        if (presetFill < 0) {
            val sampleH = min(30, h)
            val top = Bitmap.createBitmap(source, 0, 0, w, sampleH)
            val blurred = blur(top, 40f)
            fillColor = medianColor(blurred)
            top.recycle(); blurred.recycle()
            fillColor2 = fillColor
        }

        // 2. Fill background (two-colour for center mode when colors differ)
        val bg = Bitmap.createBitmap(w, targetH, Bitmap.Config.ARGB_8888)
        bg.eraseColor(fillColor)
        android.util.Log.d("Extend", "pos=$position sameColor=$sameColor fillColor=#${Integer.toHexString(fillColor)} fillColor2=#${Integer.toHexString(fillColor2)}")
        if (position == "center" && !sameColor) {
            val botPaint = Paint(); botPaint.color = fillColor2
            val splitY = topOffset + h / 2
            android.util.Log.d("Extend", "drawing fillColor2 from y=$splitY to $targetH")
            Canvas(bg).drawRect(0f, splitY.toFloat(), w.toFloat(), targetH.toFloat(), botPaint)
        }

        // 3. Exponential alpha gradient
        val denom = 1.0 - exp(-EXP_K)
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        val canvas = Canvas(bg)
        val paint = Paint()

        for (y in 0 until h) {
            val alpha = when (position) {
                "bottom" -> if (y >= h - zone) alphaAt(h - y, zone, denom) else 255
                "center" -> when {
                    y < halfZone -> alphaAt(y, halfZone, denom)
                    y >= h - halfZone -> alphaAt(h - y, halfZone, denom)
                    else -> 255
                }
                else -> if (y < zone) alphaAt(y, zone, denom) else 255
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

    private fun alphaAt(dist: Int, range: Int, denom: Double): Int {
        val t = (dist.toFloat() / range).coerceAtMost(1f)
        val curve = (exp(-EXP_K * (1 - t)) - exp(-EXP_K)) / denom
        return (255 * curve).roundToInt().coerceIn(0, 255)
    }

    // ── Stacked box blur (approximates Gaussian, 3 passes) ──
    private fun blur(src: Bitmap, radius: Float): Bitmap {
        val r = radius.roundToInt().coerceAtLeast(2)
        val w = src.width; val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        boxBlur(pixels, w, h, r)
        boxBlur(pixels, w, h, r)
        boxBlur(pixels, w, h, r)
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun boxBlur(pixels: IntArray, w: Int, h: Int, radius: Int) {
        val tmp = pixels.copyOf()
        val kernel = radius * 2 + 1
        // Horizontal
        for (y in 0 until h) {
            var sR=0; var sG=0; var sB=0; var c=0
            for (x in 0 until w + radius) {
                if (x < w) { val v=tmp[y*w+x]; sR+=v shr 16 and 0xFF; sG+=v shr 8 and 0xFF; sB+=v and 0xFF; c++ }
                if (x >= kernel) { val v=tmp[y*w+(x-kernel)]; sR-=v shr 16 and 0xFF; sG-=v shr 8 and 0xFF; sB-=v and 0xFF; c-- }
                if (x >= radius) { val cx=x-radius; pixels[y*w+cx]=0xFF shl 24 or ((sR/c) shl 16) or ((sG/c) shl 8) or (sB/c) }
            }
        }
        pixels.copyInto(tmp)
        // Vertical
        for (x in 0 until w) {
            var sR=0; var sG=0; var sB=0; var c=0
            for (y in 0 until h + radius) {
                if (y < h) { val v=tmp[y*w+x]; sR+=v shr 16 and 0xFF; sG+=v shr 8 and 0xFF; sB+=v and 0xFF; c++ }
                if (y >= kernel) { val v=tmp[(y-kernel)*w+x]; sR-=v shr 16 and 0xFF; sG-=v shr 8 and 0xFF; sB-=v and 0xFF; c-- }
                if (y >= radius) { val cy=y-radius; pixels[cy*w+x]=0xFF shl 24 or ((sR/c) shl 16) or ((sG/c) shl 8) or (sB/c) }
            }
        }
    }

    private fun medianColor(bmp: Bitmap): Int {
        val n = bmp.width * bmp.height
        val p = IntArray(n); bmp.getPixels(p, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val r = IntArray(n); val g = IntArray(n); val b = IntArray(n)
        for (i in 0 until n) { val c=p[i]; r[i]=c shr 16 and 0xFF; g[i]=c shr 8 and 0xFF; b[i]=c and 0xFF }
        r.sort(); g.sort(); b.sort(); val m=n/2
        return 0xFF shl 24 or (r[m] shl 16) or (g[m] shl 8) or b[m]
    }
}
