
package com.iam.humanizer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * I-A-M SMART AUDIO ENGINE
 * Espectro comparativo basado en muestras PCM reales.
 *
 * Azul: audio original.
 * Naranja: audio procesado.
 */
class SpectrumComparisonView(context: Context) : View(context) {

    private val originalColor = 0xFF36AFFF.toInt()
    private val processedColor = 0xFFFF8A35.toInt()

    private val originalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = originalColor
        style = Paint.Style.FILL
    }

    private val processedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = processedColor
        style = Paint.Style.FILL
    }

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF171329.toInt()
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF51466C.toInt()
        strokeWidth = 1f
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = 12f
        textAlign = Paint.Align.CENTER
    }

    private var before = FloatArray(48)
    private var after = FloatArray(48)

    private var hasOriginal = false
    private var hasProcessed = false

    private val barCount = 48

    init {
        contentDescription =
            "Espectro comparativo: azul original y naranja procesado"
    }

    fun setAudio(
        original: ShortArray,
        processed: ShortArray?,
        channels: Int
    ) {
        if (channels !in 1..2 || original.isEmpty()) return

        before = calculateSpectrum(original, channels)
        hasOriginal = true

        if (processed != null && processed.isNotEmpty()) {
            after = calculateSpectrum(processed, channels)
            hasProcessed = true
        } else {
            after = FloatArray(barCount)
            hasProcessed = false
        }

        invalidate()
    }

    fun clear() {
        before = FloatArray(barCount)
        after = FloatArray(barCount)
        hasOriginal = false
        hasProcessed = false
        invalidate()
    }

    private fun calculateSpectrum(
        samples: ShortArray,
        channels: Int
    ): FloatArray {

        val frames = samples.size / channels
        val result = FloatArray(barCount)

        if (frames < 256) return result

        val windowSize = minOf(2048, frames)
        val start = max(0, frames / 2 - windowSize / 2)

        val signal = DoubleArray(windowSize)

        for (i in 0 until windowSize) {
            var mono = 0.0

            for (ch in 0 until channels) {
                mono += samples[(start + i) * channels + ch] / 32768.0
            }

            mono /= channels

            val hann =
                0.5 - 0.5 * cos(2.0 * PI * i / (windowSize - 1))

            signal[i] = mono * hann
        }

        val minBin = 2.0
        val maxBin = windowSize / 2.0

        for (band in 0 until barCount) {

            val fraction = band.toDouble() / (barCount - 1)

            val bin = minBin * (
                maxBin / minBin
            ).pow(fraction)

            val omega = 2.0 * PI * bin / windowSize
            val coefficient = 2.0 * cos(omega)

            var previous = 0.0
            var previous2 = 0.0

            for (sample in signal) {
                val current =
                    sample + coefficient * previous - previous2

                previous2 = previous
                previous = current
            }

            val power = max(
                0.0,
                previous2 * previous2 +
                    previous * previous -
                    coefficient * previous * previous2
            )

            val magnitude = sqrt(power) / windowSize

            val db = 20.0 * ln(
                max(magnitude, 0.000001)
            ) / ln(10.0)

            result[band] = (
                ((db + 80.0) / 80.0)
            ).toFloat().coerceIn(0f, 1f)
        }

        return result
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()

        if (w <= 0f || h <= 0f) return

        val radius = 18f * resources.displayMetrics.density

        canvas.drawRoundRect(
            0f, 0f, w, h,
            radius, radius,
            backgroundPaint
        )

        val top = h * 0.20f
        val bottom = h * 0.82f
        val center = (top + bottom) / 2f

        for (line in 0..4) {
            val y = top + (bottom - top) * line / 4f
            canvas.drawLine(12f, y, w - 12f, y, linePaint)
        }

        val gap = 2f * resources.displayMetrics.density
        val barWidth = (w - 28f) / barCount

        for (i in 0 until barCount) {

            val x = 14f + i * barWidth

            val originalHeight =
                before[i] * (bottom - top) * 0.48f

            val processedHeight =
                after[i] * (bottom - top) * 0.48f

            val left = x + gap / 2f
            val right = x + barWidth - gap / 2f

            if (hasOriginal) {
                canvas.drawRoundRect(
                    RectF(
                        left,
                        center - originalHeight,
                        right,
                        center - 1f
                    ),
                    3f, 3f,
                    originalPaint
                )
            }

            if (hasProcessed) {
                canvas.drawRoundRect(
                    RectF(
                        left,
                        center + 1f,
                        right,
                        center + processedHeight
                    ),
                    3f, 3f,
                    processedPaint
                )
            }
        }

        textPaint.textSize = 11f *
            resources.displayMetrics.density

        textPaint.color = originalColor
        canvas.drawText(
            "ANTES",
            w * 0.25f,
            h - 8f,
            textPaint
        )

        textPaint.color = processedColor
        canvas.drawText(
            "DESPUÉS",
            w * 0.75f,
            h - 8f,
            textPaint
        )
    }
}
