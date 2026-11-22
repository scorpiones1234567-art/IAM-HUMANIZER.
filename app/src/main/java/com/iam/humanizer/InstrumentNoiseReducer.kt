
package com.iam.humanizer

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * I-A-M - INSTRUMENT NOISE REDUCER 2.0
 *
 * Procesamiento espectral por bloques de aproximadamente 4 segundos.
 * Reduce memoria temporal y conserva la fase del audio.
 *
 * Entrada: FloatArray estéreo intercalado, 44.1 kHz.
 * No procesa la voz.
 */
object InstrumentNoiseReducer {

    private const val FFT_SIZE = 1024
    private const val HOP_SIZE = 512
    private const val NOISE_PERCENT = 0.08f
    private const val NOISE_MULTIPLIER = 2.2f
    private const val MIN_GAIN = 0.55f
    private const val DEFAULT_STRENGTH = 0.30f
    private const val MIN_FREQUENCY = 2800f
    private const val CHUNK_SECONDS = 4

    private val window = FloatArray(FFT_SIZE) { i ->
        (0.5 - 0.5 * cos(
            2.0 * PI * i / (FFT_SIZE - 1)
        )).toFloat()
    }

    fun reduce(
        input: FloatArray,
        sampleRate: Int = 44100,
        strength: Float = DEFAULT_STRENGTH
    ): FloatArray {

        require(input.size % 2 == 0) {
            "La mezcla instrumental no es estéreo válida."
        }

        require(sampleRate > 0) {
            "Frecuencia de muestreo inválida."
        }

        if (input.isEmpty()) return input.copyOf()

        val sampleCount = input.size / 2

        if (sampleCount < FFT_SIZE) {
            return input.copyOf()
        }

        val safeStrength = strength.coerceIn(0f, 0.60f)

        if (safeStrength == 0f) {
            return input.copyOf()
        }

        val frameCount =
            (sampleCount + HOP_SIZE - 1) / HOP_SIZE + 1

        // Solo guarda una medida pequeña por bloque.
        val frameEnergy = FloatArray(frameCount)

        // 1. Analizar energía de los bloques.
        for (frame in 0 until frameCount) {

            checkNotInterrupted()

            val start = frame * HOP_SIZE - FFT_SIZE / 2
            var sum = 0.0
            var count = 0

            for (i in 0 until FFT_SIZE) {
                val position = start + i

                if (position in 0 until sampleCount) {
                    val left = input[position * 2].toDouble()
                    val right = input[position * 2 + 1].toDouble()

                    sum += left * left + right * right
                    count += 2
                }
            }

            frameEnergy[frame] = if (count > 0) {
                sqrt(sum / count).toFloat()
            } else {
                0f
            }
        }

        // 2. Seleccionar los bloques de menor energía.
        val selectedCount = (
            frameCount * NOISE_PERCENT
        ).toInt().coerceAtLeast(8).coerceAtMost(frameCount)

        val selectedFrames = frameEnergy.indices
            .sortedBy { frameEnergy[it] }
            .take(selectedCount)

        // 3. Crear perfil de ruido.
        val bins = FFT_SIZE / 2 + 1
        val noiseProfile = FloatArray(bins)

        val real = DoubleArray(FFT_SIZE)
        val imaginary = DoubleArray(FFT_SIZE)

        for (frame in selectedFrames) {

            checkNotInterrupted()

            val start = frame * HOP_SIZE - FFT_SIZE / 2

            for (channel in 0..1) {

                real.fill(0.0)
                imaginary.fill(0.0)

                for (i in 0 until FFT_SIZE) {
                    val position = start + i

                    val sample = if (
                        position in 0 until sampleCount
                    ) {
                        input[position * 2 + channel].toDouble()
                    } else {
                        0.0
                    }

                    real[i] = sample * window[i]
                }

                fft(real, imaginary, false)

                for (bin in 0 until bins) {
                    noiseProfile[bin] += sqrt(
                        real[bin] * real[bin] +
                        imaginary[bin] * imaginary[bin]
                    ).toFloat()
                }
            }
        }

        val divisor = (selectedCount * 2).toFloat()

        for (bin in noiseProfile.indices) {
            noiseProfile[bin] /= divisor
        }

        // Suavizar el perfil de ruido.
        val smoothNoise = FloatArray(bins)

        for (bin in 0 until bins) {
            var sum = 0f
            var count = 0

            for (offset in -2..2) {
                val index = bin + offset

                if (index in 0 until bins) {
                    sum += noiseProfile[index]
                    count++
                }
            }

            smoothNoise[bin] = sum / count
        }

        // Una sola matriz grande de salida.
        // Ya no se crean matrices completas para L, R y pesos.
        val output = FloatArray(input.size)

        val rawGain = FloatArray(bins)
        val smoothedGain = FloatArray(bins)

        val minimumBin = (
            MIN_FREQUENCY / sampleRate * FFT_SIZE
        ).toInt().coerceIn(1, bins - 1)

        // Cantidad de tramas que se procesan por grupo.
        val framesPerChunk = (
            sampleRate * CHUNK_SECONDS / HOP_SIZE
        ).coerceAtLeast(1)

        // 4. Procesamiento por bloques.
        var chunkStart = 0

        while (chunkStart < frameCount) {

            checkNotInterrupted()

            val chunkEnd = minOf(
                chunkStart + framesPerChunk,
                frameCount
            )

            for (frame in chunkStart until chunkEnd) {

                if (frame % 32 == 0) {
                    checkNotInterrupted()
                }

                val start = frame * HOP_SIZE - FFT_SIZE / 2

                for (channel in 0..1) {

                    real.fill(0.0)
                    imaginary.fill(0.0)

                    // Ventana de análisis.
                    for (i in 0 until FFT_SIZE) {
                        val position = start + i

                        val sample = if (
                            position in 0 until sampleCount
                        ) {
                            input[position * 2 + channel].toDouble()
                        } else {
                            0.0
                        }

                        real[i] = sample * window[i]
                    }

                    fft(real, imaginary, false)

                    // Calcular ganancias espectrales.
                    for (bin in 0 until bins) {

                        if (bin < minimumBin) {
                            rawGain[bin] = 1f
                            continue
                        }

                        val magnitude = sqrt(
                            real[bin] * real[bin] +
                            imaginary[bin] * imaginary[bin]
                        ).toFloat()

                        val noise = smoothNoise[bin]

                        if (noise < 0.000001f) {
                            rawGain[bin] = 1f
                            continue
                        }

                        val threshold = noise * NOISE_MULTIPLIER

                        val noiseAmount = (
                            1f - magnitude / threshold
                        ).coerceIn(0f, 1f)

                        rawGain[bin] = (
                            1f - safeStrength * noiseAmount
                        ).coerceAtLeast(MIN_GAIN)
                    }

                    // Suavizar ganancias entre frecuencias.
                    for (bin in 0 until bins) {

                        var sum = 0f
                        var count = 0

                        for (offset in -2..2) {
                            val index = bin + offset

                            if (index in 0 until bins) {
                                sum += rawGain[index]
                                count++
                            }
                        }

                        smoothedGain[bin] = sum / count
                    }

                    // Aplicar ganancia conservando la fase.
                    for (bin in 0 until bins) {

                        val gain = smoothedGain[bin].toDouble()

                        real[bin] *= gain
                        imaginary[bin] *= gain

                        if (bin > 0 && bin < FFT_SIZE / 2) {
                            val mirror = FFT_SIZE - bin

                            real[mirror] *= gain
                            imaginary[mirror] *= gain
                        }
                    }

                    fft(real, imaginary, true)

                    // Reconstruir directamente en la salida.
                    for (i in 0 until FFT_SIZE) {

                        val position = start + i

                        if (position in 0 until sampleCount) {

                            val index = position * 2 + channel

                            output[index] +=
                                real[i].toFloat() * window[i]
                        }
                    }
                }
            }

            chunkStart = chunkEnd
        }

        // 5. Normalizar el solapamiento sin otra matriz gigante.
        for (position in 0 until sampleCount) {

            if (position % 32768 == 0) {
                checkNotInterrupted()
            }

            val centerFrame = (
                (position + FFT_SIZE / 2) / HOP_SIZE
            )

            var weight = 0f

            val firstFrame = maxOf(0, centerFrame - 2)
            val lastFrame = minOf(frameCount - 1, centerFrame)

            for (frame in firstFrame..lastFrame) {

                val start = frame * HOP_SIZE - FFT_SIZE / 2
                val windowIndex = position - start

                if (windowIndex in 0 until FFT_SIZE) {
                    val value = window[windowIndex]
                    weight += value * value
                }
            }

            val leftIndex = position * 2
            val rightIndex = leftIndex + 1

            if (weight > 0.000001f) {
                output[leftIndex] /= weight
                output[rightIndex] /= weight
            } else {
                output[leftIndex] = input[leftIndex]
                output[rightIndex] = input[rightIndex]
            }
        }

        return output
    }

    /**
     * FFT radix-2 en sitio.
     */
    private fun fft(
        real: DoubleArray,
        imaginary: DoubleArray,
        inverse: Boolean
    ) {
        val n = real.size

        var j = 0

        for (i in 1 until n) {

            var bit = n shr 1

            while ((j and bit) != 0) {
                j = j xor bit
                bit = bit shr 1
            }

            j = j xor bit

            if (i < j) {
                val tempReal = real[i]
                real[i] = real[j]
                real[j] = tempReal

                val tempImaginary = imaginary[i]
                imaginary[i] = imaginary[j]
                imaginary[j] = tempImaginary
            }
        }

        var length = 2

        while (length <= n) {

            val angle = (
                if (inverse) 2.0 else -2.0
            ) * PI / length

            val rootReal = cos(angle)
            val rootImaginary = sin(angle)

            val half = length / 2

            var start = 0

            while (start < n) {

                var wr = 1.0
                var wi = 0.0

                for (k in 0 until half) {

                    val even = start + k
                    val odd = even + half

                    val oddReal =
                        real[odd] * wr -
                        imaginary[odd] * wi

                    val oddImaginary =
                        real[odd] * wi +
                        imaginary[odd] * wr

                    val evenReal = real[even]
                    val evenImaginary = imaginary[even]

                    real[even] = evenReal + oddReal
                    imaginary[even] = evenImaginary + oddImaginary

                    real[odd] = evenReal - oddReal
                    imaginary[odd] = evenImaginary - oddImaginary

                    val nextWr = wr * rootReal - wi * rootImaginary
                    wi = wr * rootImaginary + wi * rootReal
                    wr = nextWr
                }

                start += length
            }

            length *= 2
        }

        if (inverse) {
            val scale = 1.0 / n

            for (i in 0 until n) {
                real[i] *= scale
                imaginary[i] *= scale
            }
        }
    }

    private fun checkNotInterrupted() {
        if (Thread.currentThread().isInterrupted) {
            throw InterruptedException(
                "Procesamiento cancelado."
            )
        }
    }
}
