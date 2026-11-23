package com.iam.humanizer

import android.util.Log
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * I-A-M - INSTRUMENT NOISE REDUCER 2.3
 *
 * Reducción espectral conservadora.
 * Suaviza las ganancias entre tramas para evitar
 * artefactos, sonido metálico y cambios bruscos.
 *
 * Cambios 2.2:
 * - Se quitó el tope de fuerza que anulaba la reducción.
 * - El perfil de ruido ignora el silencio digital.
 * - Registra en Logcat (IAM_NOISE) la reducción media real.
 * - 2.3: filtro suave de agudos (high-shelf) contra el siseo.
 *
 * Entrada: FloatArray estéreo intercalado.
 * No procesa directamente la pista vocal.
 */
object InstrumentNoiseReducer {

    private const val FFT_SIZE = 1024
    private const val HOP_SIZE = 512

    private const val NOISE_PERCENT = 0.08f
    private const val NOISE_MULTIPLIER = 4.0f

    private const val MIN_GAIN = 0.5f
    private const val DEFAULT_STRENGTH = 0.30f

    private const val MIN_FREQUENCY = 3000f
    private const val CHUNK_SECONDS = 4

    private const val ATTACK_SMOOTHING = 0.35f
    private const val RELEASE_SMOOTHING = 0.08f

    // Filtro de agudos contra el siseo.
    // Ajusta HISS_SHELF_DB entre -2 (suave) y -6 (fuerte).
    // Pon 0f para desactivarlo.
    private const val HISS_SHELF_FREQ = 9000f
    private const val HISS_SHELF_DB = -4f

    private const val TAG = "IAM_NOISE"

    private val window = FloatArray(FFT_SIZE) { i ->
        (
            0.5 - 0.5 * cos(
                2.0 * PI * i / (FFT_SIZE - 1)
            )
        ).toFloat()
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

        val safeStrength = strength.coerceIn(0f, 0.5f)

        if (safeStrength <= 0f) {
            return input.copyOf()
        }

        val frameCount =
            (sampleCount + HOP_SIZE - 1) / HOP_SIZE + 1

        // Energía por trama.
        val frameEnergy = FloatArray(frameCount)

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

        // Ignorar tramas casi en silencio digital
        // (inicio o final de la canción).
        val candidates = frameEnergy.indices.filter {
            frameEnergy[it] > 0.0001f
        }

        if (candidates.size < 8) {
            return input.copyOf()
        }

        // Seleccionar las tramas de menor energía.
        val selectedCount = (
            candidates.size * NOISE_PERCENT
        ).toInt().coerceIn(8, candidates.size)

        val selectedFrames = candidates
            .sortedBy { frameEnergy[it] }
            .take(selectedCount)

        val bins = FFT_SIZE / 2 + 1

        val noiseProfile = FloatArray(bins)

        val real = DoubleArray(FFT_SIZE)
        val imaginary = DoubleArray(FFT_SIZE)

        // Construir perfil de ruido.
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

        // Suavizar perfil de ruido por frecuencia.
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

        val output = FloatArray(input.size)

        val rawGain = FloatArray(bins)
        val smoothedGain = FloatArray(bins)

        // Memoria pequeña para suavizar entre tramas.
        val previousGain = Array(2) {
            FloatArray(bins) { 1f }
        }

        val minimumBin = (
            MIN_FREQUENCY / sampleRate * FFT_SIZE
        ).toInt().coerceIn(1, bins - 1)

        val framesPerChunk = (
            sampleRate * CHUNK_SECONDS / HOP_SIZE
        ).coerceAtLeast(1)

        // Medidor de la reducción real aplicada.
        var gainSum = 0.0
        var gainCount = 0L

        // Procesamiento espectral por bloques.
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

                    // Análisis de la trama.
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

                    // Calcular reducción conservadora.
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

                    // Suavizar entre frecuencias.
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

                    // Suavizar también entre tramas.
                    // Evita que la reducción entre y salga bruscamente.
                    for (bin in 0 until bins) {

                        val target = smoothedGain[bin]
                        val previous = previousGain[channel][bin]

                        val coefficient = if (target < previous) {
                            ATTACK_SMOOTHING
                        } else {
                            RELEASE_SMOOTHING
                        }

                        val stable = previous +
                            (target - previous) * coefficient

                        smoothedGain[bin] = stable
                        previousGain[channel][bin] = stable
                    }

                    // Medir la ganancia aplicada (solo agudos).
                    for (bin in minimumBin until bins) {
                        gainSum += smoothedGain[bin]
                        gainCount++
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

                    // Reconstrucción.
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

        // Normalizar solapamiento.
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

        // Bajar suavemente los agudos para reducir el siseo.
        applyHighShelf(
            data = output,
            sampleRate = sampleRate,
            frequency = HISS_SHELF_FREQ,
            gainDb = HISS_SHELF_DB
        )

        // Mostrar la reducción media real en Logcat.
        if (gainCount > 0L) {

            val meanGain = gainSum / gainCount
            val db = 20.0 * log10(meanGain.coerceAtLeast(0.000001))

            Log.i(
                TAG,
                "Reducción media en agudos: " +
                    "${"%.2f".format(db)} dB"
            )
        }

        return output
    }

    /**
     * Aplica solo el filtro de agudos contra el siseo.
     * No necesita memoria extra: se usa cuando el reductor
     * completo se omite por falta de memoria o por un error.
     */
    fun softenHiss(
        data: FloatArray,
        sampleRate: Int
    ) {
        applyHighShelf(
            data = data,
            sampleRate = sampleRate,
            frequency = HISS_SHELF_FREQ,
            gainDb = HISS_SHELF_DB
        )
    }

    /**
     * Filtro high-shelf (biquad RBJ) sobre audio estéreo intercalado.
     * gainDb negativo = baja las frecuencias por encima de "frequency".
     */
    private fun applyHighShelf(
        data: FloatArray,
        sampleRate: Int,
        frequency: Float,
        gainDb: Float
    ) {

        if (gainDb == 0f) return

        if (frequency >= sampleRate / 2.0 * 0.95) return

        val a = Math.pow(10.0, gainDb / 40.0)
        val w0 = 2.0 * PI * frequency / sampleRate

        val cosW = cos(w0)
        val sinW = sin(w0)

        val alpha = sinW / 2.0 * sqrt(2.0)
        val sqrtA = sqrt(a)

        val b0 = a * ((a + 1) + (a - 1) * cosW + 2 * sqrtA * alpha)
        val b1 = -2 * a * ((a - 1) + (a + 1) * cosW)
        val b2 = a * ((a + 1) + (a - 1) * cosW - 2 * sqrtA * alpha)

        val a0 = (a + 1) - (a - 1) * cosW + 2 * sqrtA * alpha
        val a1 = 2 * ((a - 1) - (a + 1) * cosW)
        val a2 = (a + 1) - (a - 1) * cosW - 2 * sqrtA * alpha

        val nb0 = b0 / a0
        val nb1 = b1 / a0
        val nb2 = b2 / a0
        val na1 = a1 / a0
        val na2 = a2 / a0

        for (channel in 0..1) {

            var x1 = 0.0
            var x2 = 0.0
            var y1 = 0.0
            var y2 = 0.0

            var index = channel
            var count = 0

            while (index < data.size) {

                if (count % 32768 == 0) {
                    checkNotInterrupted()
                }

                val x0 = data[index].toDouble()

                val y0 = nb0 * x0 + nb1 * x1 + nb2 * x2 -
                    na1 * y1 - na2 * y2

                x2 = x1
                x1 = x0
                y2 = y1
                y1 = y0

                data[index] = y0.toFloat()

                index += 2
                count++
            }
        }
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
