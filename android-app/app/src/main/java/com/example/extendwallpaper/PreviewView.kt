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
    private var fillColor2 = Color.BLACK
    private var sameColor = false
    private var fraction = 0.1f
    private var phoneRatio = 1216f / 2640f
    private val paint = Paint()
    private var scale = 1f
    private var position = "top"

    fun setBitmap(bmp: Bitmap) { source = bmp; requestLayout() }
    fun setFillColor(color: Int) { fillColor = color; invalidate() }
    fun setFillColor2(color: Int, same: Boolean) { fillColor2 = color; sameColor = same; invalidate() }
    fun setFraction(f: Float) { fraction = f.coerceIn(0f, 1f); invalidate() }
    fun setPhoneRatio(ratio: Float) { phoneRatio = ratio; requestLayout() }
    fun setPosition(pos: String) { position = pos; requestLayout() }

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
        val vw = width.toFloat(); val vh = height.toFloat()

        val imgH = src.height * (vw / src.width)
        val targetH = src.width / phoneRatio
        val extH = ((targetH - src.height) * (vw / src.width)).coerceAtLeast(0f)
        val gap = 2f * scale

        val r = Color.red(fillColor); val g = Color.green(fillColor); val b = Color.blue(fillColor)
        paint.shader = null; paint.color = fillColor

        when (position) {
            "bottom" -> {
                val totalH = imgH + gap + extH
                val top = (vh - totalH) / 2f
                val imgTop = top; val extTop = top + imgH + gap
                val br = Color.red(fillColor2); val bgC = Color.green(fillColor2); val bb = Color.blue(fillColor2)
                paint.color = fillColor2
                // image
                val dstRect = Rect(0, imgTop.roundToInt(), vw.roundToInt(), (imgTop + imgH).roundToInt())
                canvas.drawBitmap(src, null, dstRect, paint)
                // ext bar below
                canvas.drawRect(0f, extTop, vw, extTop + extH, paint)
                // gradient: from image bottom upward (transparent→fill)
                val fadeH = imgH * fraction
                if (fadeH > 0) {
                    val y0 = imgTop + imgH - fadeH
                    paint.shader = LinearGradient(0f, y0, 0f, y0 + fadeH,
                        intArrayOf(Color.argb(0, br, bgC, bb), Color.argb(255, br, bgC, bb)),
                        floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
                    canvas.drawRect(0f, y0, vw, y0 + fadeH, paint)
                    paint.shader = null
                }
            }
            "center" -> {
                val halfExt = extH / 2f
                val totalH = halfExt + gap + imgH + gap + halfExt
                val top = (vh - totalH) / 2f
                val useSecond = !sameColor
                val r2 = Color.red(fillColor2); val g2 = Color.green(fillColor2); val b2 = Color.blue(fillColor2)
                // top ext bar
                canvas.drawRect(0f, top, vw, top + halfExt, paint)
                // image
                val imgTop = top + halfExt + gap
                val dstRect = Rect(0, imgTop.roundToInt(), vw.roundToInt(), (imgTop + imgH).roundToInt())
                canvas.drawBitmap(src, null, dstRect, paint)
                // bottom ext bar
                paint.shader = null; paint.color = if (useSecond) fillColor2 else fillColor
                canvas.drawRect(0f, imgTop + imgH + gap, vw, imgTop + imgH + gap + halfExt, paint)
                paint.color = fillColor
                // top gradient
                var fadeH = imgH * fraction
                if (fadeH > 0) {
                    paint.shader = LinearGradient(0f, imgTop, 0f, imgTop + fadeH,
                        intArrayOf(Color.argb(255, r, g, b), Color.argb(0, r, g, b)),
                        floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
                    canvas.drawRect(0f, imgTop, vw, imgTop + fadeH, paint)
                    paint.shader = null
                }
                // bottom gradient — use fillColor2 if different
                val br = if(useSecond) r2 else r; val bgC = if(useSecond) g2 else g; val bb = if(useSecond) b2 else b
                if (fadeH > 0) {
                    val botY = imgTop + imgH - fadeH
                    paint.shader = LinearGradient(0f, botY, 0f, botY + fadeH,
                        intArrayOf(Color.argb(0, br, bgC, bb), Color.argb(255, br, bgC, bb)),
                        floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
                    canvas.drawRect(0f, botY, vw, botY + fadeH, paint)
                    paint.shader = null
                }
            }
            else -> { // "top" default
                val totalH = extH + gap + imgH
                val top = (vh - totalH) / 2f
                val extTop = top; val imgTop = top + extH + gap
                // ext bar
                canvas.drawRect(0f, extTop, vw, extTop + extH, paint)
                // image
                val dstRect = Rect(0, imgTop.roundToInt(), vw.roundToInt(), (imgTop + imgH).roundToInt())
                canvas.drawBitmap(src, null, dstRect, paint)
                // gradient
                val fadeH = imgH * fraction
                if (fadeH > 0) {
                    paint.shader = LinearGradient(0f, imgTop, 0f, imgTop + fadeH,
                        intArrayOf(Color.argb(255, r, g, b), Color.argb(0, r, g, b)),
                        floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
                    canvas.drawRect(0f, imgTop, vw, imgTop + fadeH, paint)
                    paint.shader = null
                }
            }
        }
    }
}
