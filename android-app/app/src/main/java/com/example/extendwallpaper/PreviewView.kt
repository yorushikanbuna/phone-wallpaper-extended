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
    private var fraction = 0.1f
    private var phoneRatio = 1216f / 2640f
    private val paint = Paint()
    private var scale = 1f

    fun setBitmap(bmp: Bitmap) { source = bmp; requestLayout() }
    fun setFillColor(color: Int) { fillColor = color; invalidate() }
    fun setFraction(f: Float) { fraction = f.coerceIn(0f, 1f); invalidate() }
    fun setPhoneRatio(ratio: Float) { phoneRatio = ratio; requestLayout() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val src = source
        if (src == null) { super.onMeasure(widthMeasureSpec, heightMeasureSpec); return }

        val maxW = MeasureSpec.getSize(widthMeasureSpec)
        val maxH = MeasureSpec.getSize(heightMeasureSpec)

        // Natural size at full width
        val imgNatH = src.height * (maxW.toFloat() / src.width)
        val targetH = src.width / phoneRatio
        val extNatH = ((targetH - src.height) * (maxW.toFloat() / src.width)).coerceAtLeast(0f)
        val natH = extNatH + 2f + imgNatH

        scale = if (natH > maxH) maxH / natH else 1f
        val drawW = (maxW * scale).roundToInt()
        val drawH = maxH

        setMeasuredDimension(drawW, drawH)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val src = source ?: return
        val vw = width.toFloat()
        val vh = height.toFloat()

        // Full image height at this width
        val imgH = src.height * (vw / src.width)
        val targetH = src.width / phoneRatio
        val extH = ((targetH - src.height) * (vw / src.width)).coerceAtLeast(0f)
        val gap = 2f * scale

        // Center vertically
        val totalH = extH + gap + imgH
        val top = (vh - totalH) / 2f

        val r = Color.red(fillColor)
        val g = Color.green(fillColor)
        val b = Color.blue(fillColor)

        // Extension bar
        paint.shader = null
        paint.color = fillColor
        canvas.drawRect(0f, top, vw, top + extH, paint)

        // Original image
        val dstRect = Rect(0, (top + extH + gap).roundToInt(), vw.roundToInt(), (top + extH + gap + imgH).roundToInt())
        canvas.drawBitmap(src, null, dstRect, paint)

        // Gradient overlay
        val fadeH = imgH * fraction
        if (fadeH > 0) {
            val y0 = top + extH + gap
            paint.shader = LinearGradient(
                0f, y0, 0f, y0 + fadeH,
                intArrayOf(Color.argb(255, r, g, b), Color.argb(0, r, g, b)),
                floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, y0, vw, y0 + fadeH, paint)
            paint.shader = null
        }
    }
}
