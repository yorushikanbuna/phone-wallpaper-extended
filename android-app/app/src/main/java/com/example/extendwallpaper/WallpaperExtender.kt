package com.example.extendwallpaper

import android.graphics.*
import kotlin.math.*

object WallpaperExtender {
    private const val EXP_K = 3.0

    data class Result(val bitmap: Bitmap, val fillColor: Int, val extendPx: Int)

    fun extend(source: Bitmap, phoneW: Int, phoneH: Int, modifyZone: Int = -1,
               position: String = "top", sameColor: Boolean = false): Result {
        val w = source.width; val h = source.height
        val targetH = (w / (phoneW.toFloat() / phoneH)).roundToInt()
        val ext = targetH - h
        if (ext <= 0) return Result(source, Color.TRANSPARENT, 0)

        val zone = if (modifyZone > 0) modifyZone else (h * 0.1f).roundToInt()
        val topOffset = when (position) { "bottom" -> 0; "center" -> ext / 2; else -> ext }
        val halfZone = zone / 2

        // 1. Fill colour from top 30px, then brightness-match to gradient boundary
        val sampleH = min(30, h)
        val top = Bitmap.createBitmap(source, 0, 0, w, sampleH)
        val blurred = blur(top, 40f)
        val baseFill = medianColor(blurred)
        top.recycle(); blurred.recycle()

        // Brightness match per-boundary
        fun matchLum(y: Int): Int {
            val t = maxOf(0, y - 10); val hh = min(20, h - t)
            if (hh <= 0) return baseFill
            val bmp = Bitmap.createBitmap(source, 0, t, w, hh)
            val px = IntArray(w * hh); bmp.getPixels(px, 0, w, 0, 0, w, hh)
            var sum=0f; for(c in px) sum+=0.299f*(c shr 16 and 0xFF)+0.587f*(c shr 8 and 0xFF)+0.114f*(c and 0xFF)
            val bLum=sum/px.size/255f; bmp.recycle()
            // HSL match
            val fR=baseFill shr 16 and 0xFF; val fG=baseFill shr 8 and 0xFF; val fB=baseFill and 0xFF
            val fRf=fR/255f;val fGf=fG/255f;val fBf=fB/255f
            val mx=maxOf(fRf,fGf,fBf);val mn=minOf(fRf,fGf,fBf);val d=mx-mn
            var fH=0f;var fS=0f
            if(d>0f){fS=if((mx+mn)/2f>.5f)d/(2f-mx-mn) else d/(mx+mn)
                fH=if(mx==fRf)((fGf-fBf)/d+(if(fGf<fBf)6f else 0f))/6f
                else if(mx==fGf)((fBf-fRf)/d+2f)/6f else((fRf-fGf)/d+4f)/6f}
            val q=if(bLum<.5f)bLum*(1f+fS)else bLum+fS-bLum*fS;val p=2f*bLum-q
            fun hue(h:Float):Int{var t=h;if(t<0f)t+=1f;if(t>1f)t-=1f
                return (if(t<1f/6f)p+(q-p)*6f*t else if(t<.5f)q else if(t<2f/3f)p+(q-p)*(2f/3f-t)*6f else p)*255f roundToInt 0xFF}
            return 0xFF shl 24 or (hue(fH+1f/3f) shl 16) or (hue(fH) shl 8) or hue(fH-1f/3f)
        }
        val fillColor = when(position){"center"->matchLum(halfZone);"bottom"->matchLum(h-zone);else->matchLum(zone)}
        val fillColor2 = if(position=="center" && !sameColor) matchLum(h-halfZone) else fillColor

        // 2. Fill background (two-colour for center mode)
        val bg = Bitmap.createBitmap(w, targetH, Bitmap.Config.ARGB_8888)
        bg.eraseColor(fillColor)
        if (position == "center") {
            // Draw bottom extension bar with second fill colour
            val botPaint = Paint(); botPaint.color = fillColor2
            Canvas(bg).drawRect(0f, (topOffset + h).toFloat(), w.toFloat(), targetH.toFloat(), botPaint)
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
