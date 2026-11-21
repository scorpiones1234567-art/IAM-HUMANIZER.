
package com.iam.humanizer

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/**
 * I-A-M MOTOR MUSICAL 2.1
 * Núcleo compartido de procesamiento de audio.
 *
 * Reproduce y exporta utilizando el mismo procesamiento.
 */
class AudioProcessor(
    private val sampleRate: Int,
    private val channels: Int,
    private val settings: Settings = Settings()
) {

    data class Settings(
        val equalizer: FloatArray = FloatArray(10) { 0f },
        val bassEnabled: Boolean = false,
        val reverbEnabled: Boolean = false,
        val stereoEnabled: Boolean = false,
        val volumePercent: Int = 100,
        val vocalClarity: Int = 0,
        val noiseReduction: Float = 0f,
        val humRemoval: Boolean = false
    )

    private class Biquad(
        sampleRate: Int,
        frequency: Float,
        gainDb: Float,
        q: Float = 1.0f
    ) {
        private var x1 = 0.0
        private var x2 = 0.0
        private var y1 = 0.0
        private var y2 = 0.0

        private val b0: Double
        private val b1: Double
        private val b2: Double
        private val a1: Double
        private val a2: Double

        init {
            val f = frequency.toDouble()
            val sr = sampleRate.toDouble()
            val w = 2.0 * PI * f / sr
            val c = cos(w)
            val s = sin(w)
            val alpha = s / (2.0 * q.toDouble())
            val a = 10.0.pow(gainDb.toDouble() / 40.0)

            val bb0 = 1.0 + alpha * a
            val bb1 = -2.0 * c
            val bb2 = 1.0 - alpha * a
            val aa0 = 1.0 + alpha / a
            val aa1 = -2.0 * c
            val aa2 = 1.0 - alpha / a

            b0 = bb0 / aa0
            b1 = bb1 / aa0
            b2 = bb2 / aa0
            a1 = aa1 / aa0
            a2 = aa2 / aa0
        }

        fun process(input: Double): Double {
            val output =
                b0 * input +
                b1 * x1 +
                b2 * x2 -
                a1 * y1 -
                a2 * y2

            x2 = x1
            x1 = input
            y2 = y1
            y1 = output

            return output
        }
    }

    private val frequencies = floatArrayOf(
        31f, 62f, 125f, 250f, 500f,
        1000f, 2000f, 4000f, 8000f, 16000f
    )

    private val equalizerFilters: Array<Array<Biquad?>>
    private val bassFilters: Array<Biquad?>
    private val clarityFilters: Array<Biquad?>
    private val humFilters: Array<Array<Biquad>>

    private val delayFrames =
        (sampleRate * 0.22f).toInt().coerceAtLeast(1)

    private val delayBuffer =
        DoubleArray(delayFrames * channels)

    private var delayPosition = 0

    private var limiterEnvelope = 1.0
    private val limiterCeiling = 0.94

    init {
        require(sampleRate in 8000..192000) {
            "Frecuencia de audio no compatible: $sampleRate"
        }

        require(channels in 1..2) {
            "Solo se admite audio mono o estéreo."
        }

        equalizerFilters = Array(channels) { channel ->
            Array(frequencies.size) { band ->
                val frequency = frequencies[band]
                val gain = settings.equalizer
                    .getOrElse(band) { 0f }
                    .coerceIn(-12f, 12f)

                if (frequency < sampleRate * 0.45f &&
                    abs(gain) > 0.01f
                ) {
                    Biquad(sampleRate, frequency, gain)
                } else {
                    null
                }
            }
        }

        bassFilters = Array(channels) {
            if (settings.bassEnabled) {
                Biquad(sampleRate, 90f, 3.0f, 0.8f)
            } else null
        }

        clarityFilters = Array(channels) {
            if (settings.vocalClarity > 0) {
                Biquad(
                    sampleRate,
                    3200f,
                    settings.vocalClarity.coerceIn(0, 100) / 100f * 3.0f,
                    0.8f
                )
            } else null
        }

        // Eliminación opcional de zumbido eléctrico.
        // Frecuencia fundamental: 60 Hz.
        // Armónicos: 120 Hz y 180 Hz.
        // Se mantiene apagada por defecto para proteger la música.
        humFilters = Array(channels) {
            if (settings.humRemoval) {
                arrayOf(60f, 120f, 180f)
                    .filter { it < sampleRate * 0.45f }
                    .map {
                        Biquad(
                            sampleRate,
                            it,
                            -18.0f,
                            18.0f
                        )
                    }
                    .toTypedArray()
            } else {
                emptyArray()
            }
        }
    }

    /**
     * Procesa un bloque PCM de 16 bits.
     * Conserva el estado de los filtros entre bloques.
     */
    fun process(input: ShortArray): ShortArray {
        if (input.isEmpty()) return ShortArray(0)

        require(input.size % channels == 0) {
            "El bloque de audio está incompleto."
        }

        val output = ShortArray(input.size)
        val frames = input.size / channels

        val volume = settings.volumePercent
            .coerceIn(0, 120) / 100.0

        val wet = if (settings.reverbEnabled) 0.16 else 0.0
        val feedback = 0.22

        val clarityNoise = settings.noiseReduction
            .coerceIn(0f, 1f)

        for (frame in 0 until frames) {
            val frameStart = frame * channels

            var left =
                input[frameStart].toDouble() / 32768.0

            var right = if (channels == 2) {
                input[frameStart + 1].toDouble() / 32768.0
            } else {
                left
            }

            // Ecualizador de 10 bandas.
            for (band in frequencies.indices) {
                equalizerFilters[0][band]?.let {
                    left = it.process(left)
                }

                if (channels == 2) {
                    equalizerFilters[1][band]?.let {
                        right = it.process(right)
                    }
                }
            }

            // Refuerzo de graves.
            bassFilters[0]?.let {
                left = it.process(left)
            }

            if (channels == 2) {
                bassFilters[1]?.let {
                    right = it.process(right)
                }
            }

            // Presencia y claridad vocal.
            clarityFilters[0]?.let {
                left = it.process(left)
            }

            if (channels == 2) {
                clarityFilters[1]?.let {
                    right = it.process(right)
                }
            }

            // Eliminación opcional de zumbido eléctrico.
            for (filter in humFilters[0]) {
                left = filter.process(left)
            }

            if (channels == 2) {
                for (filter in humFilters[1]) {
                    right = filter.process(right)
                }
            }

            // Reducción suave de ruido existente.
            // No es todavía un denoiser avanzado.
            if (clarityNoise > 0f) {
                val attenuation =
                    1.0 - clarityNoise * 0.08

                left *= attenuation
                right *= attenuation
            }

            // Amplitud estéreo mediante procesamiento Mid/Side.
            if (channels == 2 && settings.stereoEnabled) {
                val mid = (left + right) * 0.5
                val side = (left - right) * 0.5 * 1.20

                left = mid + side
                right = mid - side
            }

            // Reverberación con memoria entre bloques.
            if (wet > 0.0) {
                val delayedLeft = delayBuffer[delayPosition]

                val delayedRight = if (channels == 2) {
                    delayBuffer[delayPosition + 1]
                } else {
                    delayedLeft
                }

                delayBuffer[delayPosition] =
                    left + delayedLeft * feedback

                if (channels == 2) {
                    delayBuffer[delayPosition + 1] =
                        right + delayedRight * feedback
                }

                left += delayedLeft * wet
                right += delayedRight * wet

                delayPosition += channels

                if (delayPosition >= delayBuffer.size) {
                    delayPosition = 0
                }
            }

            // Volumen general.
            left *= volume
            right *= volume

            // Limitador enlazado.
            val peak = max(abs(left), abs(right))

            val targetGain = if (peak > limiterCeiling) {
                limiterCeiling / peak
            } else {
                1.0
            }

            val coefficient = if (targetGain < limiterEnvelope) {
                0.65
            } else {
                0.015
            }

            limiterEnvelope +=
                (targetGain - limiterEnvelope) * coefficient

            left *= limiterEnvelope
            right *= limiterEnvelope

            // Conversión segura a PCM de 16 bits.
            output[frameStart] =
                (left.coerceIn(-1.0, 1.0) * 32767.0)
                    .toInt().toShort()

            if (channels == 2) {
                output[frameStart + 1] =
                    (right.coerceIn(-1.0, 1.0) * 32767.0)
                        .toInt().toShort()
            }
        }

        return output
    }

    /**
     * Reinicia filtros, reverberación y limitador.
     */
    fun reset() {
        delayBuffer.fill(0.0)
        delayPosition = 0
        limiterEnvelope = 1.0
    }
}
