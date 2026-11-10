package com.skitknas.smartpix

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.TextureView
import android.view.View

/** A TextureView that keeps the camera's aspect ratio and fits inside its parent. */
class AutoFitTextureView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : TextureView(context, attrs) {

    private var ratioW = 0
    private var ratioH = 0

    fun setAspectRatio(w: Int, h: Int) {
        ratioW = w
        ratioH = h
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        if (ratioW == 0 || ratioH == 0) {
            setMeasuredDimension(width, height)
        } else if (width.toLong() * ratioH < height.toLong() * ratioW) {
            setMeasuredDimension(width, (width.toLong() * ratioH / ratioW).toInt())
        } else {
            setMeasuredDimension((height.toLong() * ratioW / ratioH).toInt(), height)
        }
    }
}

/** Darkens the parts of the preview that will be cropped away and frames what is kept. */
class CropOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val preview = RectF()
    private var aspect: Float? = null
    private var label = ""

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    private val dim = Paint().apply { color = 0x99000000.toInt() }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = dp(1.5f)
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13f, resources.displayMetrics)
        setShadowLayer(4f, 0f, 0f, Color.BLACK)
    }

    fun setPreviewRect(r: RectF) {
        preview.set(r)
        invalidate()
    }

    /** aspect = width/height of the kept area in screen coordinates; null = no crop. */
    fun setTarget(aspect: Float?, label: String) {
        this.aspect = aspect
        this.label = label
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (preview.isEmpty) return
        val a = aspect ?: return
        val pr = preview.width() / preview.height()
        val crop = if (pr > a) {
            val w = preview.height() * a
            RectF(preview.centerX() - w / 2, preview.top, preview.centerX() + w / 2, preview.bottom)
        } else {
            val h = preview.width() / a
            RectF(preview.left, preview.centerY() - h / 2, preview.right, preview.centerY() + h / 2)
        }
        canvas.drawRect(preview.left, preview.top, preview.right, crop.top, dim)
        canvas.drawRect(preview.left, crop.bottom, preview.right, preview.bottom, dim)
        canvas.drawRect(preview.left, crop.top, crop.left, crop.bottom, dim)
        canvas.drawRect(crop.right, crop.top, preview.right, crop.bottom, dim)
        canvas.drawRect(crop, border)
        canvas.drawText(label, crop.left + dp(8f), crop.bottom - dp(8f), text)
    }
}
