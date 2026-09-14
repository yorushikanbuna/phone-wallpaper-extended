package com.example.extendwallpaper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import kotlin.math.roundToInt

/** Preview renders through the same pixel compositor used by final export. */
class PreviewView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {
    private var source: Bitmap? = null
    private var fillColor = android.graphics.Color.BLACK
    private var fillColor2 = android.graphics.Color.BLACK
    private var sameColor = false
    private var fraction = 0.1f
    private var phoneRatio = 1216f / 2640f
    private var position = "top"
    private var personMask: PersonMask? = null
    private var rendered: Bitmap? = null
    private var dirty = true
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    fun setBitmap(bmp: Bitmap) {
        if (rendered != null && rendered !== source && !rendered!!.isRecycled) rendered?.recycle()
        rendered = null; source = bmp; dirty = true; requestLayout()
    }
    fun copyRenderedBitmap(): Bitmap? = try {
        rendered?.takeUnless { it.isRecycled }?.copy(Bitmap.Config.ARGB_8888, false)
    } catch (_: RuntimeException) {
        null
    }
    fun setFillColor(color: Int) { fillColor = color; dirty = true; invalidate() }
    fun setFillColor2(color: Int, same: Boolean) { fillColor2 = color; sameColor = same; dirty = true; invalidate() }
    fun setFraction(f: Float) { fraction = f.coerceIn(0f, 1f); dirty = true; invalidate() }
    fun setPhoneRatio(ratio: Float) {
        if (!ratio.isFinite() || ratio <= 0f) return
        phoneRatio = ratio; dirty = true; requestLayout()
    }
    fun setPosition(pos: String) { position = pos; dirty = true; requestLayout() }
    fun setPersonMask(mask: PersonMask?) { personMask = mask; dirty = true; invalidate() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val src = source
        if (src == null) { super.onMeasure(widthMeasureSpec, heightMeasureSpec); return }
        val maxW = MeasureSpec.getSize(widthMeasureSpec); val maxH = MeasureSpec.getSize(heightMeasureSpec)
        if (maxW <= 0 || maxH <= 0 || !phoneRatio.isFinite() || phoneRatio <= 0f) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        val imgNatH = src.height * (maxW.toFloat() / src.width)
        val targetH = src.width.toFloat() / phoneRatio
        if (!targetH.isFinite()) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        val safeTargetH = targetH.coerceIn(1f, MAX_PREVIEW_HEIGHT.toFloat())
        val extNatH = ((safeTargetH - src.height) * (maxW.toFloat() / src.width)).coerceAtLeast(0f)
        val natH = extNatH + 2f + imgNatH
        val scale = if (natH > maxH) maxH / natH else 1f
        setMeasuredDimension((maxW * scale).roundToInt().coerceAtLeast(1), maxH)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val src = source ?: return
        if (dirty || rendered == null) {
            if (rendered !== src) rendered?.recycle()
            val requestedPhoneH = (src.width.toFloat() / phoneRatio)
                .coerceIn(1f, MAX_PREVIEW_HEIGHT.toFloat())
                .roundToInt()
            val previewPhoneH = maxOf(src.height + 1, requestedPhoneH)
            rendered = WallpaperExtender.extend(
                src, src.width, previewPhoneH,
                (src.height * fraction).roundToInt().coerceAtLeast(0),
                position, sameColor, fillColor, fillColor2, personMask
            ).bitmap
            dirty = false
        }
        val bitmap = rendered ?: return
        val drawW = width
        val drawH = (bitmap.height * drawW.toFloat() / bitmap.width).roundToInt()
        val top = ((height - drawH) / 2).coerceAtLeast(0)
        canvas.drawBitmap(bitmap, null, Rect(0, top, drawW, top + drawH), paint)
    }

    override fun onDetachedFromWindow() {
        if (rendered !== source && rendered?.isRecycled == false) rendered?.recycle()
        rendered = null
        super.onDetachedFromWindow()
    }

    private companion object {
        const val MAX_PREVIEW_HEIGHT = 4096
    }
}
