package com.example.mydashcam.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.toColorInt // ОБЯЗАТЕЛЬНЫЙ ИМПОРТ
import kotlin.math.min

class StorageRingView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var progress = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val rectF = RectF()

    fun setProgress(value: Float) {
        progress = value.coerceIn(0f, 1f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val size = min(width, height).toFloat()
        val strokeWidth = size * 0.07f
        val radius = (size - strokeWidth) / 2f

        paint.strokeWidth = strokeWidth

        val cx = width / 2f
        val cy = height / 2f
        rectF.set(cx - radius, cy - radius, cx + radius, cy + radius)

        // 1. Используем современный .toColorInt()
        paint.color = "#22FFFFFF".toColorInt()
        canvas.drawCircle(cx, cy, radius, paint)

        // 2. Цвета заполнения
        paint.color = when {
            progress < 0.6f -> "#00E676".toColorInt() // Зеленый
            progress < 0.9f -> "#FFEA00".toColorInt() // Желтый
            else -> "#FF1744".toColorInt()             // Красный
        }

        canvas.drawArc(rectF, -90f, progress * 360f, false, paint)
    }
}