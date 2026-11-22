
package com.iam.humanizer

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * I-A-M - INSTRUMENT NOISE REDUCER 1.0
 *
 * Reducción espectral suave de estática en instrumentos.
 * Conserva la fase y protege los componentes musicales.
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
    private const val DEFAULT_STRENGTH = 0.45f
    private const val MIN_FREQUENCY = 2800f

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

        val frameEnergy = FloatArray(frameCount)

        // 1. Medir energía de cada bloque.
        for (frame in 0 until frameCount) {

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

        // 3. Calcular perfil espectral de ruido.
        val bins = FFT_SIZE / 2 + 1
        val noiseProfile = FloatArray(bins)

        val real = DoubleArray(FFT_SIZE)
        val imaginary = DoubleArray(FFT_SIZE)

        for (frame in selectedFrames) {

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

        // Suavizar el perfil para evitar artefactos musicales.
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

        // 4. Procesar los instrumentos.
        val outputLeft = FloatArray(sampleCount)
        val outputRight = FloatArray(sampleCount)
        val weights = FloatArray(sampleCount)

        val minimumBin = (
            MIN_FREQUENCY / sampleRate * FFT_SIZE
        ).toInt().coerceIn(1, bins - 1)

        for (frame in 0 until frameCount) {

            val start = frame * HOP_SIZE - FFT_SIZE / 2

            // Peso de solapamiento.
            for (i in 0 until FFT_SIZE) {
                val position = start + i

                if (position in 0 until sampleCount) {
                    weights[position] += window[i] * window[i]
                }
            }

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

                val rawGain = FloatArray(bins)

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

                    val desiredGain = (
                        1f - safeStrength * noiseAmount
                    ).coerceAtLeast(MIN_GAIN)

                    rawGain[bin] = desiredGain
                }

                // Suavizado entre frecuencias.
                val smoothedGain = FloatArray(bins)

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

                    val gain = smoothedGain[bin]

                    real[bin] *= gain.toDouble()
                    imaginary[bin] *= gain.toDouble()

                    if (bin > 0 && bin < FFT_SIZE / 2) {
                        val mirror = FFT_SIZE - bin

                        real[mirror] *= gain.toDouble()
                        imaginary[mirror] *= gain.toDouble()
                    }
                }

                fft(real, imaginary, true)

                val destination = if (channel == 0) {
                    outputLeft
                } else {
                    outputRight
                }

                // Reconstrucción por solapamiento.
                for (i in 0 until FFT_SIZE) {
                    val position = start + i

                    if (position in 0 until sampleCount) {
                        destination[position] += (
                            real[i].toFloat() * window[i]
                        )
                    }
                }
            }
        }

        // 5. Normalizar y reconstruir estéreo.
        val output = FloatArray(input.size)

        for (i in 0 until sampleCount) {

            val weight = weights[i]

            val left = if (weight > 0.000001f) {
                outputLeft[i] / weight
            } else {
                input[i * 2]
            }

            val right = if (weight > 0.000001f) {
                outputRight[i] / weight
            } else {
                input[i * 2 + 1]
            }

            output[i * 2] = left
            output[i * 2 + 1] = right
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
}
