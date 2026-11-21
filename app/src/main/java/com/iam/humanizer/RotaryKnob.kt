
package com.iam.humanizer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.cos
import kotlin.math.sin

/**
 * I-A-M SMART AUDIO ENGINE
 * Control giratorio profesional de 0 a 100.
 */
class RotaryKnob(context: Context) : View(context) {

    var minValue: Float = 0f
        set(value) {
            field = value
            this.value = this.value
            invalidate()
        }

    var maxValue: Float = 100f
        set(value) {
            field = value.coerceAtLeast(minValue + 0.01f)
            this.value = this.value
            invalidate()
        }

    var value: Float = 0f
        set(value) {
            field = value.coerceIn(minValue, maxValue)
            contentDescription = "Control: ${field.roundToInt()}$suffix"
            invalidate()
            onValueChanged?.invoke(field)
        }

    var suffix: String = "%"
        set(value) {
            field = value
            contentDescription = "Control: ${this.value.roundToInt()}$field"
            invalidate()
        }

    var accentColor: Int = 0xFF36E5FF.toInt()
        set(value) {
            field = value
            invalidate()
        }

    var onValueChanged: ((Float) -> Unit)? = null

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = 0xFF424052.toInt()
    }

    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF191629.toInt()
    }

    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = 0xFF71658F.toInt()
    }

    private val pointerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
    }

    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create(
            "sans-serif-medium",
            android.graphics.Typeface.BOLD
        )
    }

    private val bounds = RectF()
    private val startAngle = 135f
    private val sweepAngle = 270f

    init {
        isFocusable = true
        isClickable = true
        minimumHeight = (92 * resources.displayMetrics.density).toInt()
        minimumWidth = (92 * resources.displayMetrics.density).toInt()
    }

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int
    ) {
        val desired = (104 * resources.displayMetrics.density).toInt()
        val w = resolveSize(desired, widthMeasureSpec)
        val h = resolveSize(desired, heightMeasureSpec)
        val side = minOf(w, h)
        setMeasuredDimension(side, side)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val side = minOf(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        val radius = side * 0.37f
        val stroke = side * 0.085f

        trackPaint.strokeWidth = stroke
        activePaint.strokeWidth = stroke
        activePaint.color = accentColor

        bounds.set(
            cx - radius,
            cy - radius,
            cx + radius,
            cy + radius
        )

        canvas.drawArc(
            bounds,
            startAngle,
            sweepAngle,
            false,
            trackPaint
        )

        val fraction = (
            (value - minValue) / (maxValue - minValue)
        ).coerceIn(0f, 1f)

        canvas.drawArc(
            bounds,
            startAngle,
            sweepAngle * fraction,
            false,
            activePaint
        )

        val faceRadius = radius - stroke * 0.72f

        canvas.drawCircle(cx, cy, faceRadius, facePaint)
        canvas.drawCircle(cx, cy, faceRadius, rimPaint)

        val angle = Math.toRadians(
            (startAngle + sweepAngle * fraction).toDouble()
        )

        val pointerInner = faceRadius * 0.30f
        val pointerOuter = faceRadius * 0.78f

        canvas.drawLine(
            cx + cos(angle).toFloat() * pointerInner,
            cy + sin(angle).toFloat() * pointerInner,
            cx + cos(angle).toFloat() * pointerOuter,
            cy + sin(angle).toFloat() * pointerOuter,
            pointerPaint
        )

        valuePaint.textSize = side * 0.22f

        val shown = if (suffix.isEmpty()) {
            value.roundToInt().toString()
        } else {
            "${value.roundToInt()}$suffix"
        }

        val baseline = cy -
            (valuePaint.ascent() + valuePaint.descent()) / 2f

        canvas.drawText(shown, cx, baseline, valuePaint)
    }

    private fun updateFromTouch(x: Float, y: Float) {
        val cx = width / 2f
        val cy = height / 2f

        val dx = x - cx
        val dy = y - cy

        var angle = Math.toDegrees(
            atan2(dy.toDouble(), dx.toDouble())
        ).toFloat()

        if (angle < -135f) {
            angle += 360f
        }

        val normalized = (
            (angle - 135f) / 270f
        ).coerceIn(0f, 1f)

        value = minValue +
            normalized * (maxValue - minValue)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                updateFromTouch(event.x, event.y)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                updateFromTouch(event.x, event.y)
                return true
            }

            MotionEvent.ACTION_UP -> {
                performClick()
                return true
            }

            MotionEvent.ACTION_CANCEL -> return true
        }

        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
