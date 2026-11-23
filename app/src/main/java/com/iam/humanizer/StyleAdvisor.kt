package com.iam.humanizer

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * I-A-M - STYLE ADVISOR 1.0
 *
 * Recomienda un estilo del ecualizador a partir de:
 * - balance de graves / medios / agudos (analizando todas las muestras)
 * - tempo aproximado (BPM)
 * - dinámica (diferencia entre pico y volumen medio)
 *
 * Es orientativo: no reconoce géneros con precisión.
 * Los nombres devueltos deben existir en el mapa "presets" de MainActivity.
 */
object StyleAdvisor {

    data class Result(
        val preset: String,
        val alternative: String?,
        val bpm: Int,
        val lowPercent: Float,
        val midPercent: Float,
        val highPercent: Float,
        val crestDb: Float
    )

    private data class Bands(
        val low: Float,
        val mid: Float,
        val high: Float,
        val crestDb: Float
    )

    // Umbrales iniciales (porcentaje de energía real por zona).
    // Se pueden calibrar con el analizador de la app.
    private const val LOW_BASSY = 82f
    private const val HIGH_BRIGHT = 6f
    private const val MID_HEAVY = 33f
    private const val CREST_DYNAMIC = 14f

    fun recommend(
        samples: ShortArray,
        channels: Int,
        sampleRate: Int
    ): Result {

        if (samples.isEmpty() || channels !in 1..2 || sampleRate <= 0) {
            return Result("Pop", null, 0, 0f, 0f, 0f, 0f)
        }

        val bands = measureBands(samples, channels, sampleRate)
        val bpm = estimateBpm(samples, channels, sampleRate)

        val bassy = bands.low >= LOW_BASSY
        val bright = bands.high >= HIGH_BRIGHT
        val midHeavy = bands.mid >= MID_HEAVY
        val dynamic = bands.crestDb >= CREST_DYNAMIC

        val preset: String
        val alternative: String?

        when {

            bassy && bpm in 118..135 -> {
                preset = "Electrónica"
                alternative = "Hip Hop"
            }

            bassy && bpm in 85..105 -> {
                preset = "Reggaetón"
                alternative = "Hip Hop"
            }

            bassy -> {
                preset = "Hip Hop"
                alternative = "Reggaetón"
            }

            bright && dynamic -> {
                preset = "Acústica"
                alternative = "Jazz"
            }

            bright && bpm >= 105 -> {
                preset = "Rock"
                alternative = "Pop"
            }

            bright -> {
                preset = "Pop"
                alternative = "Acústica"
            }

            midHeavy && bpm in 1..82 -> {
                preset = "Baladas"
                alternative = "VOZ CLARA"
            }

            midHeavy -> {
                preset = "VOZ CLARA"
                alternative = "Pop"
            }

            bpm in 86..108 -> {
                preset = "Cumbia · Voz"
                alternative = "Pop"
            }

            bpm in 120..142 -> {
                preset = "Bachata"
                alternative = "Pop"
            }

            else -> {
                preset = "Pop"
                alternative = null
            }
        }

        return Result(
            preset = preset,
            alternative = alternative,
            bpm = bpm,
            lowPercent = bands.low,
            midPercent = bands.mid,
            highPercent = bands.high,
            crestDb = bands.crestDb
        )
    }

    // ---------------------------------------------------------
    // BALANCE DE FRECUENCIAS
    // ---------------------------------------------------------

    private fun measureBands(
        samples: ShortArray,
        channels: Int,
        sampleRate: Int
    ): Bands {

        val totalFrames = samples.size / channels

        if (totalFrames <= 0) return Bands(0f, 0f, 0f, 0f)

        // Hasta 90 segundos del centro de la canción,
        // sin saltar muestras (así los filtros son correctos).
        val window = min(totalFrames, sampleRate * 90)
        val start = (totalFrames - window) / 2

        val lowAlpha = 1.0 - exp(-2.0 * PI * 250.0 / sampleRate)
        val highAlpha = 1.0 - exp(-2.0 * PI * 4000.0 / sampleRate)

        var lowState = 0.0
        var highState = 0.0

        var total = 0.0
        var lowEnergy = 0.0
        var midEnergy = 0.0
        var highEnergy = 0.0
        var peak = 0.0

        for (frame in start until start + window) {

            val index = frame * channels

            var value = 0.0

            for (channel in 0 until channels) {
                value += samples[index + channel].toDouble()
            }

            value /= channels * 32768.0

            peak = max(peak, abs(value))

            lowState += lowAlpha * (value - lowState)
            highState += highAlpha * (value - highState)

            val mid = highState - lowState
            val top = value - highState

            total += value * value
            lowEnergy += lowState * lowState
            midEnergy += mid * mid
            highEnergy += top * top
        }

        val sum = lowEnergy + midEnergy + highEnergy

        if (sum <= 0.0) return Bands(0f, 0f, 0f, 0f)

        val rms = sqrt(total / window)

        val crest = (
            20.0 * log10(peak.coerceAtLeast(0.000001)) -
                20.0 * log10(rms.coerceAtLeast(0.000001))
        ).toFloat()

        return Bands(
            low = (lowEnergy / sum * 100.0).toFloat(),
            mid = (midEnergy / sum * 100.0).toFloat(),
            high = (highEnergy / sum * 100.0).toFloat(),
            crestDb = crest
        )
    }

    // ---------------------------------------------------------
    // TEMPO APROXIMADO (BPM)
    // ---------------------------------------------------------

    /**
     * Estima el tempo entre 60 y 180 BPM con la autocorrelación de los
     * "golpes" de energía. Devuelve 0 si no se puede medir.
     * Puede confundir el tempo con su doble o su mitad.
     */
    private fun estimateBpm(
        samples: ShortArray,
        channels: Int,
        sampleRate: Int
    ): Int {

        val totalFrames = samples.size / channels

        // Hasta 45 segundos del centro de la canción.
        val frames = min(totalFrames, sampleRate * 45)
        val startFrame = (totalFrames - frames) / 2

        // Un valor de energía cada 10 ms.
        val hop = sampleRate / 100

        if (hop <= 0) return 0

        val length = frames / hop

        // Menos de 10 segundos: no es fiable.
        if (length < 1000) return 0

        val frameRate = sampleRate.toDouble() / hop

        val envelope = FloatArray(length)

        for (i in 0 until length) {

            var sum = 0.0
            val begin = startFrame + i * hop

            for (j in 0 until hop) {

                val index = (begin + j) * channels

                var value = 0.0

                for (channel in 0 until channels) {
                    value += samples[index + channel].toDouble()
                }

                value /= channels * 32768.0

                sum += value * value
            }

            envelope[i] = ln(1.0 + 1000.0 * sqrt(sum / hop)).toFloat()
        }

        // Fuerza de los golpes: solo subidas de energía.
        val onset = FloatArray(length)

        var mean = 0.0

        for (i in 1 until length) {
            onset[i] = max(0f, envelope[i] - envelope[i - 1])
            mean += onset[i]
        }

        mean /= length

        for (i in 0 until length) {
            onset[i] = (onset[i] - mean).toFloat()
        }

        // Rango de búsqueda: 180 BPM (lag corto) a 60 BPM (lag largo).
        val minLag = (60.0 * frameRate / 180.0).roundToInt().coerceAtLeast(2)
        val maxLag = (60.0 * frameRate / 60.0).roundToInt()

        val maxNeeded = maxLag * 2

        if (maxNeeded >= length) return 0

        val autocorrelation = DoubleArray(maxNeeded + 1)

        for (lag in 1..maxNeeded) {

            var sum = 0.0

            for (i in 0 until length - lag) {
                sum += onset[i].toDouble() * onset[i + lag].toDouble()
            }

            autocorrelation[lag] = sum / (length - lag)
        }

        var bestLag = 0
        var bestScore = 0.0

        for (lag in minLag..maxLag) {

            // El doble del lag refuerza al tempo correcto.
            val score =
                autocorrelation[lag] + 0.5 * autocorrelation[lag * 2]

            if (score > bestScore) {
                bestScore = score
                bestLag = lag
            }
        }

        if (bestLag == 0) return 0

        return (60.0 * frameRate / bestLag).roundToInt()
    }
}
