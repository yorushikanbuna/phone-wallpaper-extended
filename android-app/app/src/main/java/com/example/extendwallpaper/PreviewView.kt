package com.example.extendwallpaper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Lightweight visual preview. The final bitmap is always rendered by WallpaperExtender. */
class PreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    private var source: Bitmap? = null
    private var fillColor = Color.BLACK
    private var fillColor2 = Color.BLACK
    private var sameColor = false
    private var fraction = 0.1f
    private var phoneRatio = 1216f / 2640f
    private var scale = 1f
    private var position = WallpaperExtender.POSITION_TOP
    private var mode = WallpaperExtender.MODE_SOLID
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = true
    }

    fun setBitmap(bitmap: Bitmap) {
        source = bitmap
        requestLayout()
        invalidate()
    }

    fun setFillColor(color: Int) {
        fillColor = color
        invalidate()
    }

    fun setFillColor2(color: Int, same: Boolean) {
        fillColor2 = color
        sameColor = same
        invalidate()
    }

    fun setFraction(value: Float) {
        fraction = value.coerceIn(0f, 1f)
        invalidate()
    }

    fun setPhoneRatio(ratio: Float) {
        if (ratio > 0f) phoneRatio = ratio
        requestLayout()
        invalidate()
    }

    fun setPosition(value: String) {
        position = value
        requestLayout()
        invalidate()
    }

    fun setMode(value: String) {
        mode = value
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val bitmap = source
        if (bitmap == null) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }

        val maxWidth = MeasureSpec.getSize(widthMeasureSpec)
        val maxHeight = MeasureSpec.getSize(heightMeasureSpec)
        if (maxWidth <= 0 || maxHeight <= 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }

        val imageNaturalHeight = bitmap.height * (maxWidth.toFloat() / bitmap.width)
        val targetNaturalHeight = bitmap.width / phoneRatio
        val extensionNaturalHeight =
            ((targetNaturalHeight - bitmap.height) * (maxWidth.toFloat() / bitmap.width))
                .coerceAtLeast(0f)
        val naturalHeight = extensionNaturalHeight + 2f + imageNaturalHeight

        scale = if (naturalHeight > maxHeight) maxHeight / naturalHeight else 1f
        setMeasuredDimension((maxWidth * scale).roundToInt(), maxHeight)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bitmap = source ?: return
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        if (viewWidth <= 0f || viewHeight <= 0f) return

        val imageHeight = bitmap.height * (viewWidth / bitmap.width)
        val targetHeight = bitmap.width / phoneRatio
        val extensionHeight =
            ((targetHeight - bitmap.height) * (viewWidth / bitmap.width)).coerceAtLeast(0f)
        val gap = 2f * scale
        val fadeHeight = when (position) {
            WallpaperExtender.POSITION_CENTER -> imageHeight * fraction / 2f
            else -> imageHeight * fraction
        }

        val solidMode = mode == WallpaperExtender.MODE_SOLID
        when (position) {
            WallpaperExtender.POSITION_BOTTOM -> {
                val totalHeight = imageHeight + gap + extensionHeight
                val top = (viewHeight - totalHeight) / 2f
                val imageTop = top
                val extensionTop = top + imageHeight + gap
                if (solidMode) {
                    drawExtension(canvas, bitmap, extensionTop, extensionHeight, fromTop = false)
                } else {
                    drawExtension(
                        canvas,
                        bitmap,
                        imageTop + imageHeight - fadeHeight,
                        fadeHeight + gap + extensionHeight,
                        fromTop = false,
                    )
                }
                drawImage(canvas, bitmap, imageTop, imageHeight)
                if (solidMode) {
                    drawFade(
                        canvas,
                        imageTop + imageHeight - fadeHeight,
                        fadeHeight,
                        fromTop = false,
                        color = fillColor,
                    )
                }
            }

            WallpaperExtender.POSITION_CENTER -> {
                val halfExtension = extensionHeight / 2f
                val totalHeight = halfExtension + gap + imageHeight + gap + halfExtension
                val top = (viewHeight - totalHeight) / 2f
                val imageTop = top + halfExtension + gap
                val bottomColor = if (sameColor) fillColor else fillColor2
                if (solidMode) {
                    drawExtension(canvas, bitmap, top, halfExtension, fromTop = true)
                    drawExtension(
                        canvas,
                        bitmap,
                        imageTop + imageHeight + gap,
                        halfExtension,
                        fromTop = false,
                        color = bottomColor,
                    )
                } else {
                    drawExtension(
                        canvas,
                        bitmap,
                        top,
                        halfExtension + gap + fadeHeight,
                        fromTop = true,
                    )
                    drawExtension(
                        canvas,
                        bitmap,
                        imageTop + imageHeight - fadeHeight,
                        fadeHeight + gap + halfExtension,
                        fromTop = false,
                    )
                }
                drawImage(canvas, bitmap, imageTop, imageHeight)

                if (solidMode) {
                    drawFade(canvas, imageTop, fadeHeight, fromTop = true, color = fillColor)
                    drawFade(
                        canvas,
                        imageTop + imageHeight - fadeHeight,
                        fadeHeight,
                        fromTop = false,
                        color = bottomColor,
                    )
                }
            }

            else -> {
                val totalHeight = extensionHeight + gap + imageHeight
                val top = (viewHeight - totalHeight) / 2f
                val imageTop = top + extensionHeight + gap
                if (solidMode) {
                    drawExtension(canvas, bitmap, top, extensionHeight, fromTop = true)
                } else {
                    drawExtension(
                        canvas,
                        bitmap,
                        top,
                        extensionHeight + gap + fadeHeight,
                        fromTop = true,
                    )
                }
                drawImage(canvas, bitmap, imageTop, imageHeight)
                if (solidMode) {
                    drawFade(canvas, imageTop, fadeHeight, fromTop = true, color = fillColor)
                }
            }
        }
    }

    private fun drawImage(canvas: Canvas, bitmap: Bitmap, top: Float, height: Float) {
        paint.shader = null
        paint.alpha = 255
        canvas.drawBitmap(
            bitmap,
            null,
            Rect(0, top.roundToInt(), width, (top + height).roundToInt()),
            paint,
        )
    }

    private fun drawExtension(
        canvas: Canvas,
        bitmap: Bitmap,
        top: Float,
        height: Float,
        fromTop: Boolean,
        color: Int = fillColor,
    ) {
        if (height <= 0f) return
        when (mode) {
            WallpaperExtender.MODE_BLUR -> drawEdgeStretch(
                canvas, bitmap, top, height, fromTop, mirror = false, alpha = 210,
            )

            WallpaperExtender.MODE_MIRROR -> drawEdgeStretch(
                canvas, bitmap, top, height, fromTop, mirror = true, alpha = 255,
            )

            else -> {
                paint.shader = null
                paint.alpha = 255
                paint.color = color
                canvas.drawRect(0f, top, width.toFloat(), top + height, paint)
            }
        }
    }

    /** Uses a small edge strip so mode changes are visible without rendering a full bitmap. */
    private fun drawEdgeStretch(
        canvas: Canvas,
        bitmap: Bitmap,
        top: Float,
        height: Float,
        fromTop: Boolean,
        mirror: Boolean,
        alpha: Int,
    ) {
        val edgeHeight = min(bitmap.height, max(1, (bitmap.height * 0.05f).roundToInt()))
        val sourceTop = if (fromTop) 0 else bitmap.height - edgeHeight
        val strip = Bitmap.createBitmap(bitmap, 0, sourceTop, bitmap.width, edgeHeight)
        val scaled = Bitmap.createScaledBitmap(
            strip,
            width.coerceAtLeast(1),
            height.roundToInt().coerceAtLeast(1),
            true,
        )
        val drawn = if (mirror) {
            val matrix = Matrix().apply { setScale(1f, -1f) }
            Bitmap.createBitmap(scaled, 0, 0, scaled.width, scaled.height, matrix, true)
        } else {
            scaled
        }

        paint.shader = null
        paint.alpha = alpha
        canvas.drawBitmap(drawn, 0f, top, paint)
        if (mode == WallpaperExtender.MODE_BLUR) {
            paint.color = Color.argb(45, Color.red(fillColor), Color.green(fillColor), Color.blue(fillColor))
            canvas.drawRect(0f, top, width.toFloat(), top + height, paint)
        }
        paint.alpha = 255

        if (drawn !== scaled) drawn.recycle()
        if (scaled !== strip) scaled.recycle()
        strip.recycle()
    }

    /** Approximate the renderer's exponential alpha curve with several shader stops. */
    private fun drawFade(
        canvas: Canvas,
        top: Float,
        height: Float,
        fromTop: Boolean,
        color: Int,
    ) {
        if (height <= 0f) return
        val stops = floatArrayOf(0f, 0.2f, 0.4f, 0.6f, 0.8f, 1f)
        val colors = IntArray(stops.size)
        val red = Color.red(color)
        val green = Color.green(color)
        val blue = Color.blue(color)
        for (i in stops.indices) {
            val t = stops[i]
            val distance = if (fromTop) {
                (t * height).roundToInt()
            } else {
                ((1f - t) * height).roundToInt()
            }
            val sourceAlpha = WallpaperExtender.alphaAt(
                distance,
                height.roundToInt().coerceAtLeast(1),
            )
            val overlayAlpha = if (fromTop) 255 - sourceAlpha else 255 - sourceAlpha
            colors[i] = Color.argb(overlayAlpha, red, green, blue)
        }

        paint.alpha = 255
        paint.shader = LinearGradient(
            0f,
            top,
            0f,
            top + height,
            colors,
            stops,
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, top, width.toFloat(), top + height, paint)
        paint.shader = null
    }
}
