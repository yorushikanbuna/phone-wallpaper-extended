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

    fun setBitmap(bmp: Bitmap) { source = bmp; requestLayout() }
    fun setFillColor(color: Int) { fillColor = color; invalidate() }
    fun setFraction(f: Float) { fraction = f.coerceIn(0f, 1f); invalidate() }
    fun setPhoneRatio(ratio: Float) { phoneRatio = ratio; requestLayout() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val src = source
        if (src == null) { super.onMeasure(widthMeasureSpec, heightMeasureSpec); return }

        val vw = MeasureSpec.getSize(widthMeasureSpec)
        val imgH = (src.height * vw.toFloat() / src.width).roundToInt()
        val targetH = (src.width / phoneRatio).roundToInt()
        val extPx = targetH - src.height
        val extH = if (extPx > 0) (extPx * vw.toFloat() / src.width).roundToInt() else 0
        setMeasuredDimension(vw, extH + 2 + imgH)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val src = source ?: return
        val vw = width.toFloat()

        val imgH = src.height * (vw / src.width)
        val targetH = src.width / phoneRatio
        val extPx = targetH - src.height
        val extH = if (extPx > 0) extPx * (vw / src.width) else 0f

        val r = Color.red(fillColor)
        val g = Color.green(fillColor)
        val b = Color.blue(fillColor)

        paint.shader = null
        paint.color = fillColor
        canvas.drawRect(0f, 0f, vw, extH, paint)

        val dstRect = Rect(0, extH.roundToInt() + 2, vw.roundToInt(), (extH + 2f + imgH).roundToInt())
        canvas.drawBitmap(src, null, dstRect, paint)

        val fadeH = imgH * fraction
        if (fadeH > 0) {
            val y0 = extH + 2f
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
