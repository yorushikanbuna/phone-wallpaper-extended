package com.example.extendwallpaper

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.roundToInt

class PreviewView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var source: Bitmap? = null
    private var fillColor = Color.BLACK
    private var fraction = 0.1f        // gradient zone fraction of image height
    private var phoneRatio = 1216f / 2640f
    private val paint = Paint()

    fun setBitmap(bmp: Bitmap) {
        source = bmp
        invalidate()
    }

    fun setFillColor(color: Int) {
        fillColor = color
        invalidate()
    }

    fun setFraction(f: Float) {
        fraction = f.coerceIn(0f, 1f)
        invalidate()
    }

    fun setPhoneRatio(ratio: Float) {
        phoneRatio = ratio
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val src = source ?: return
        val vw = width.toFloat()
        val vh = height.toFloat()

        // Calculate layout: image scales to fit width, extension bar on top
        val imgDrawW = vw
        val imgDrawH = src.height * (imgDrawW / src.width)
        val targetH = src.width / phoneRatio
        val extPx = targetH - src.height
        val extDrawH = if (extPx > 0) extPx * (imgDrawW / src.width) else 0f

        val totalDrawH = extDrawH + imgDrawH
        val scale = if (totalDrawH > vh) vh / totalDrawH else 1f
        val extH = extDrawH * scale
        val imgH = imgDrawH * scale
        val gap = 2f * scale
        val top = (vh - extH - gap - imgH) / 2f

        val r = Color.red(fillColor)
        val g = Color.green(fillColor)
        val bCol = Color.blue(fillColor)

        // 1. Fill-colour extension bar
        paint.shader = null
        paint.color = fillColor
        canvas.drawRect(0f, top, vw, top + extH, paint)

        // 2. Original image
        val srcRect = Rect(0, 0, src.width, src.height)
        val dstRect = Rect(0, (top + extH + gap).roundToInt(), vw.roundToInt(),
            (top + extH + gap + imgH).roundToInt())
        canvas.drawBitmap(src, srcRect, dstRect, paint)

        // 3. Gradient overlay on the image portion
        val fadeH = imgH * fraction
        if (fadeH > 0) {
            val shader = LinearGradient(
                0f, top + extH + gap, 0f, top + extH + gap + fadeH,
                intArrayOf(
                    Color.argb(255, r, g, bCol),  // top: fully opaque fill
                    Color.argb(0, r, g, bCol)       // fadeEnd: transparent
                ),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
            paint.shader = shader
            canvas.drawRect(0f, top + extH + gap, vw, top + extH + gap + fadeH, paint)
            paint.shader = null
        }
    }
}
