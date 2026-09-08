package com.example.extendwallpaper

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The Android renderer is the canonical implementation used by the app.
 *
 * A mode controls how the newly-created area is filled:
 * - solid: sampled or user-selected colour + alpha fade
 * - blur: blurred edge strip stretched into the new area
 * - mirror: reflected edge pixels stretched/repeated into the new area
 */
object WallpaperExtender {
    const val MODE_SOLID = "solid"
    const val MODE_BLUR = "blur"
    const val MODE_MIRROR = "mirror"

    const val POSITION_TOP = "top"
    const val POSITION_CENTER = "center"
    const val POSITION_BOTTOM = "bottom"

    const val EXP_K = 1.5

    private const val EDGE_SAMPLE_HEIGHT = 15
    private const val BLUR_RADIUS = 12f

    data class Result(val bitmap: Bitmap, val fillColor: Int, val extendPx: Int)

    fun extend(
        source: Bitmap,
        phoneW: Int,
        phoneH: Int,
        modifyZone: Int = -1,
        position: String = POSITION_TOP,
        sameColor: Boolean = false,
        presetFill: Int = -1,
        presetFill2: Int = -1,
        mode: String = MODE_SOLID,
    ): Result {
        require(phoneW > 0 && phoneH > 0) { "目标分辨率必须大于 0" }

        val w = source.width
        val h = source.height
        val targetH = (w / (phoneW.toFloat() / phoneH)).roundToInt()
        val extendPx = targetH - h
        if (extendPx <= 0) return Result(source, Color.TRANSPARENT, 0)

        val safePosition = when (position) {
            POSITION_CENTER, POSITION_BOTTOM -> position
            else -> POSITION_TOP
        }
        val safeMode = when (mode) {
            MODE_BLUR, MODE_MIRROR -> mode
            else -> MODE_SOLID
        }
        val zone = (if (modifyZone > 0) modifyZone else (h * 0.1f).roundToInt())
            .coerceIn(1, h)
        val halfZone = (zone / 2).coerceAtLeast(1)
        val topOffset = when (safePosition) {
            POSITION_CENTER -> extendPx / 2
            else -> extendPx
        }

        var fillColor = presetFill
        var fillColor2 = presetFill2
        if (fillColor == -1) {
            fillColor = sampleEdgeColor(source, fromTop = safePosition != POSITION_BOTTOM)
        }
        if (fillColor2 == -1) {
            fillColor2 = sampleEdgeColor(source, fromTop = false)
        }

        val background = createBackground(
            source = source,
            targetH = targetH,
            topOffset = topOffset,
            position = safePosition,
            sameColor = sameColor,
            fillColor = fillColor,
            fillColor2 = fillColor2,
            mode = safeMode,
            zone = zone,
            halfZone = halfZone,
        )

        // Apply the exact same exponential alpha curve to the complete source in one pass.
        // This avoids allocating one temporary Bitmap per row on large phone wallpapers.
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        val denom = 1.0 - exp(-EXP_K)

        for (y in 0 until h) {
            val alpha = when (safePosition) {
                POSITION_BOTTOM -> if (y >= h - zone) {
                    alphaAt(h - y, zone, denom)
                } else {
                    255
                }

                POSITION_CENTER -> when {
                    y < halfZone -> alphaAt(y, halfZone, denom)
                    y >= h - halfZone -> alphaAt(h - y, halfZone, denom)
                    else -> 255
                }

                else -> if (y < zone) alphaAt(y, zone, denom) else 255
            }

            if (alpha >= 255) continue
            for (x in 0 until w) {
                val index = y * w + x
                val color = pixels[index]
                val sourceAlpha = color ushr 24
                val outputAlpha = sourceAlpha * alpha / 255
                pixels[index] = (color and 0x00FFFFFF) or (outputAlpha shl 24)
            }
        }

        val sourceWithAlpha = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        Canvas(background).drawBitmap(sourceWithAlpha, 0f, topOffset.toFloat(), null)
        sourceWithAlpha.recycle()

        return Result(background, fillColor, extendPx)
    }

    /** Shared curve used by the preview and by the final renderer. */
    fun alphaAt(dist: Int, range: Int, denom: Double = 1.0 - exp(-EXP_K)): Int {
        if (range <= 0) return 255
        val t = (dist.toFloat() / range).coerceIn(0f, 1f)
        val curve = (exp(-EXP_K * (1 - t)) - exp(-EXP_K)) / denom
        return (255 * curve).roundToInt().coerceIn(0, 255)
    }

    private fun createBackground(
        source: Bitmap,
        targetH: Int,
        topOffset: Int,
        position: String,
        sameColor: Boolean,
        fillColor: Int,
        fillColor2: Int,
        mode: String,
        zone: Int,
        halfZone: Int,
    ): Bitmap {
        val width = source.width
        val height = source.height
        val background = Bitmap.createBitmap(width, targetH, Bitmap.Config.ARGB_8888)
        background.eraseColor(fillColor)

        val canvas = Canvas(background)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        if (position == POSITION_CENTER && !sameColor) {
            paint.color = fillColor2
            val splitY = (topOffset + height - halfZone).coerceIn(0, targetH)
            canvas.drawRect(0f, splitY.toFloat(), width.toFloat(), targetH.toFloat(), paint)
        }

        if (mode == MODE_SOLID) return background

        when (position) {
            POSITION_BOTTOM -> {
                val start = (topOffset + height - zone).coerceAtLeast(0)
                drawModeRegion(
                    canvas, source, mode, false, start, targetH - start,
                    seam = topOffset + height,
                )
            }

            POSITION_CENTER -> {
                val topHeight = (topOffset + halfZone).coerceAtLeast(0)
                drawModeRegion(
                    canvas, source, mode, true, 0, topHeight,
                    seam = topOffset,
                )

                val bottomStart = (topOffset + height - halfZone).coerceAtLeast(0)
                drawModeRegion(
                    canvas, source, mode, false, bottomStart, targetH - bottomStart,
                    seam = topOffset + height,
                )
            }

            else -> {
                val topHeight = (topOffset + zone).coerceAtMost(targetH)
                drawModeRegion(
                    canvas, source, mode, true, 0, topHeight,
                    seam = topOffset,
                )
            }
        }

        return background
    }

    private fun drawModeRegion(
        canvas: Canvas,
        source: Bitmap,
        mode: String,
        fromTop: Boolean,
        top: Int,
        height: Int,
        seam: Int,
    ) {
        if (height <= 0) return
        when (mode) {
            MODE_MIRROR -> drawMirrorRegion(canvas, source, fromTop, top, height, seam)
            else -> drawBlurRegion(canvas, source, fromTop, top, height)
        }
    }

    private fun drawBlurRegion(
        canvas: Canvas,
        source: Bitmap,
        fromTop: Boolean,
        top: Int,
        height: Int,
    ) {
        val width = source.width
        val edgeHeight = min(EDGE_SAMPLE_HEIGHT, source.height)
        val sourceTop = if (fromTop) 0 else source.height - edgeHeight
        val strip = Bitmap.createBitmap(source, 0, sourceTop, width, edgeHeight)
        val blurred = blur(strip, BLUR_RADIUS)
        val stretched = Bitmap.createScaledBitmap(blurred, width, height, true)
        canvas.drawBitmap(stretched, 0f, top.toFloat(), null)
        if (stretched !== blurred) stretched.recycle()
        blurred.recycle()
        strip.recycle()
    }

    private fun drawMirrorRegion(
        canvas: Canvas,
        source: Bitmap,
        fromTop: Boolean,
        top: Int,
        height: Int,
        seam: Int,
    ) {
        val width = source.width
        val sourceHeight = source.height
        val sourcePixels = IntArray(width * sourceHeight)
        source.getPixels(sourcePixels, 0, width, 0, 0, width, sourceHeight)
        val regionPixels = IntArray(width * height)
        val bottomSeam = sourceHeight - 1

        for (row in 0 until height) {
            val absoluteY = top + row
            val distance = if (absoluteY < seam) {
                seam - 1 - absoluteY
            } else {
                absoluteY - seam
            }
            val mirrorDistance = mirrorIndex(distance, sourceHeight)
            val sourceY = if (fromTop) {
                mirrorDistance
            } else {
                bottomSeam - mirrorDistance
            }
            sourcePixels.copyInto(
                destination = regionPixels,
                destinationOffset = row * width,
                startIndex = sourceY * width,
                endIndex = (sourceY + 1) * width,
            )
        }

        val region = Bitmap.createBitmap(regionPixels, width, height, Bitmap.Config.ARGB_8888)
        canvas.drawBitmap(region, 0f, top.toFloat(), null)
        region.recycle()
    }

    private fun mirrorIndex(distance: Int, length: Int): Int {
        if (length <= 1) return 0
        val period = length * 2
        val value = distance % period
        return if (value < length) value else period - value - 1
    }

    // ── Stacked box blur (approximates Gaussian, 3 passes) ──
    private fun blur(src: Bitmap, radius: Float): Bitmap {
        val r = radius.roundToInt().coerceAtLeast(2)
        val w = src.width
        val h = src.height
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

        for (y in 0 until h) {
            var sumR = 0
            var sumG = 0
            var sumB = 0
            var count = 0
            for (x in 0 until w + radius) {
                if (x < w) {
                    val value = tmp[y * w + x]
                    sumR += value shr 16 and 0xFF
                    sumG += value shr 8 and 0xFF
                    sumB += value and 0xFF
                    count++
                }
                if (x >= kernel) {
                    val value = tmp[y * w + (x - kernel)]
                    sumR -= value shr 16 and 0xFF
                    sumG -= value shr 8 and 0xFF
                    sumB -= value and 0xFF
                    count--
                }
                if (x >= radius && count > 0) {
                    val center = x - radius
                    pixels[y * w + center] =
                        (0xFF shl 24) or ((sumR / count) shl 16) or
                            ((sumG / count) shl 8) or (sumB / count)
                }
            }
        }

        pixels.copyInto(tmp)
        for (x in 0 until w) {
            var sumR = 0
            var sumG = 0
            var sumB = 0
            var count = 0
            for (y in 0 until h + radius) {
                if (y < h) {
                    val value = tmp[y * w + x]
                    sumR += value shr 16 and 0xFF
                    sumG += value shr 8 and 0xFF
                    sumB += value and 0xFF
                    count++
                }
                if (y >= kernel) {
                    val value = tmp[(y - kernel) * w + x]
                    sumR -= value shr 16 and 0xFF
                    sumG -= value shr 8 and 0xFF
                    sumB -= value and 0xFF
                    count--
                }
                if (y >= radius && count > 0) {
                    val center = y - radius
                    pixels[center * w + x] =
                        (0xFF shl 24) or ((sumR / count) shl 16) or
                            ((sumG / count) shl 8) or (sumB / count)
                }
            }
        }
    }

    private fun sampleEdgeColor(bitmap: Bitmap, fromTop: Boolean): Int {
        val height = min(EDGE_SAMPLE_HEIGHT, bitmap.height)
        val startY = if (fromTop) 0 else bitmap.height - height
        val strip = Bitmap.createBitmap(bitmap, 0, startY, bitmap.width, height)
        val small = Bitmap.createScaledBitmap(
            strip,
            max(1, (bitmap.width * 0.05f).roundToInt()),
            max(1, (height * 0.05f).roundToInt()),
            true,
        )
        val color = medianColor(small)
        strip.recycle()
        small.recycle()
        return color
    }

    private fun medianColor(bitmap: Bitmap): Int {
        val count = bitmap.width * bitmap.height
        val pixels = IntArray(count)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val red = IntArray(count)
        val green = IntArray(count)
        val blue = IntArray(count)
        for (i in 0 until count) {
            val color = pixels[i]
            red[i] = color shr 16 and 0xFF
            green[i] = color shr 8 and 0xFF
            blue[i] = color and 0xFF
        }
        red.sort()
        green.sort()
        blue.sort()
        val middle = count / 2
        return (0xFF shl 24) or (red[middle] shl 16) or
            (green[middle] shl 8) or blue[middle]
    }
}
