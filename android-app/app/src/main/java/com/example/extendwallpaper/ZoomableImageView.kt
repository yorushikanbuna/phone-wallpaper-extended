package com.example.extendwallpaper

import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.min

/** 全屏预览使用的轻量缩放图片控件。 */
class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private val imageMatrixValues = Matrix()
    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val nextScale = (relativeScale * detector.scaleFactor).coerceIn(1f, MAX_SCALE)
                val factor = nextScale / relativeScale
                imageMatrixValues.postScale(factor, factor, detector.focusX, detector.focusY)
                relativeScale = nextScale
                constrainTranslation()
                imageMatrix = imageMatrixValues
                return true
            }
        }
    )
    private var relativeScale = 1f
    private var lastX = 0f
    private var lastY = 0f

    init {
        scaleType = ScaleType.MATRIX
        isClickable = true
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        post { resetImageMatrix() }
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        resetImageMatrix()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress && relativeScale > 1f) {
                    imageMatrixValues.postTranslate(event.x - lastX, event.y - lastY)
                    constrainTranslation()
                    imageMatrix = imageMatrixValues
                }
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    private fun resetImageMatrix() {
        val drawable = drawable ?: return
        if (width <= 0 || height <= 0 || drawable.intrinsicWidth <= 0 || drawable.intrinsicHeight <= 0) {
            return
        }
        val fitScale = min(
            width.toFloat() / drawable.intrinsicWidth,
            height.toFloat() / drawable.intrinsicHeight
        )
        val drawWidth = drawable.intrinsicWidth * fitScale
        val drawHeight = drawable.intrinsicHeight * fitScale
        imageMatrixValues.setScale(fitScale, fitScale)
        imageMatrixValues.postTranslate(
            (width - drawWidth) / 2f,
            (height - drawHeight) / 2f
        )
        relativeScale = 1f
        imageMatrix = imageMatrixValues
    }

    private fun constrainTranslation() {
        val drawable = drawable ?: return
        val bounds = RectF(
            0f,
            0f,
            drawable.intrinsicWidth.toFloat(),
            drawable.intrinsicHeight.toFloat()
        )
        imageMatrixValues.mapRect(bounds)

        val dx = when {
            bounds.width() <= width -> width / 2f - bounds.centerX()
            bounds.left > 0f -> -bounds.left
            bounds.right < width -> width - bounds.right
            else -> 0f
        }
        val dy = when {
            bounds.height() <= height -> height / 2f - bounds.centerY()
            bounds.top > 0f -> -bounds.top
            bounds.bottom < height -> height - bounds.bottom
            else -> 0f
        }
        imageMatrixValues.postTranslate(dx, dy)
    }

    private companion object {
        const val MAX_SCALE = 5f
    }
}
