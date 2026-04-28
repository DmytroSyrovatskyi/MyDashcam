package com.example.mydashcam.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import androidx.core.graphics.toColorInt
import kotlin.math.min

class GMeterView(context: Context) : View(context) {
    private var currentX = 0f
    private var currentY = 0f
    private val maxG = 1.5f // Предел шкалы (1.5G — это жесткое экстренное торможение)

    // Стили отрисовки с использованием KTX toColorInt()
    private val paintCircle = Paint().apply {
        color = "#44FFFFFF".toColorInt() // Полупрозрачный белый
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }
    private val paintCross = Paint().apply {
        color = "#44FFFFFF".toColorInt()
        strokeWidth = 2f
        isAntiAlias = true
    }
    private val paintDot = Paint().apply {
        color = "#FF3B30".toColorInt() // Спортивный красный
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    fun updateG(latG: Float, lonG: Float) {
        currentX = latG
        currentY = lonG
        invalidate() // Заставляет Android перерисовать кадр
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(cx, cy) - 10f

        // Рисуем радар (Внешнее кольцо, внутреннее кольцо, перекрестие)
        canvas.drawCircle(cx, cy, radius, paintCircle)
        canvas.drawCircle(cx, cy, radius * 0.5f, paintCircle)
        canvas.drawLine(cx, cy - radius, cx, cy + radius, paintCross)
        canvas.drawLine(cx - radius, cy, cx + radius, cy, paintCross)

        // Ограничиваем точку, чтобы она не вылетала за круг при ударе
        val mappedX = (currentX / maxG).coerceIn(-1f, 1f) * radius
        val mappedY = (currentY / maxG).coerceIn(-1f, 1f) * radius

        // Рисуем красную точку перегрузки
        canvas.drawCircle(cx + mappedX, cy - mappedY, 14f, paintDot)
    }
}