package com.example.extendwallpaper

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

class GradientOverlay @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val paint = Paint()
    private var fillColor = Color.BLACK
    private var fraction = 0.1f  // 0..1, where gradient reaches transparent

    fun setFillColor(color: Int) {
        fillColor = color
        invalidate()
    }

    fun setFraction(f: Float) {
        fraction = f.coerceIn(0f, 1f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val fadeEnd = h * fraction

        // Draw gradient: opaque at top, transparent at fadeEnd
        val r = Color.red(fillColor)
        val g = Color.green(fillColor)
        val b = Color.blue(fillColor)

        val shader = LinearGradient(
            0f, 0f, 0f, fadeEnd,
            intArrayOf(
                Color.argb(255, r, g, b),  // top: fully opaque
                Color.argb(0, r, g, b)       // fadeEnd: fully transparent
            ),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        paint.shader = shader
        canvas.drawRect(0f, 0f, w, fadeEnd, paint)
        paint.shader = null
    }
}
