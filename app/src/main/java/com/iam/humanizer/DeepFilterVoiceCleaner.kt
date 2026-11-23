package com.iam.humanizer

import android.content.Context
import android.util.Log
import com.rikorose.deepfilternet.NativeDeepFilterNet
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * I-A-M - LIMPIADOR NEURONAL DE VOZ 2.3
 *
 * Novedad: compensa el retardo interno de DeepFilterNet.
 * La voz limpia queda alineada con la voz original, así que
 * se puede mezclar sin efecto de peine.
 *
 * Entrada: PCM 16 bits, mono, 48 kHz.
 * Salida: misma duración que la entrada, ya alineada.
 */
object DeepFilterVoiceCleaner {

    private const val TAG = "IAM_DEEPFILTER"

    // Retardo máximo que se busca: 50 ms a 48 kHz.
    private const val MAX_DELAY_SAMPLES = 2400

    // Ventana de análisis: 2 segundos de la parte más fuerte.
    private const val ANALYSIS_SAMPLES = 96000

    fun clean(
        context: Context,
        vocal48kMono: ShortArray,
        attenuationDb: Float = 20f
    ): ShortArray {

        require(vocal48kMono.isNotEmpty()) {
            "La pista de voz está vacía."
        }

        val filter = NativeDeepFilterNet(
            context.applicationContext
        )

        val loaded = CountDownLatch(1)

        try {
            filter.onModelLoaded {
                loaded.countDown()
            }

            if (!loaded.await(120, TimeUnit.SECONDS)) {
                throw IllegalStateException(
                    "DeepFilterNet no terminó de cargar el modelo."
                )
            }

            val frameBytes = filter.frameLength.toInt()

            require(frameBytes > 0 && frameBytes % 2 == 0) {
                "DeepFilterNet devolvió un tamaño de bloque inválido."
            }

            val safeAttenuation = attenuationDb.coerceIn(5f, 25f)

            if (!filter.setAttenuationLimit(safeAttenuation)) {
                throw IllegalStateException(
                    "No se pudo configurar la reducción de ruido."
                )
            }

            val samplesPerFrame = frameBytes / 2

            // Bloques de la canción + bloques extra de ceros para
            // "vaciar" la cola interna del filtro (el retardo).
            val inputFrames =
                (vocal48kMono.size + samplesPerFrame - 1) / samplesPerFrame

            val flushFrames =
                (MAX_DELAY_SAMPLES + samplesPerFrame - 1) / samplesPerFrame

            val totalFrames = inputFrames + flushFrames

            val raw = ShortArray(totalFrames * samplesPerFrame)

            val frameBuffer = ByteBuffer
                .allocateDirect(frameBytes)
                .order(ByteOrder.LITTLE_ENDIAN)

            for (frameIndex in 0 until totalFrames) {

                if (Thread.currentThread().isInterrupted) {
                    throw InterruptedException(
                        "Limpieza de voz cancelada."
                    )
                }

                frameBuffer.clear()

                val start = frameIndex * samplesPerFrame

                repeat(samplesPerFrame) { index ->
                    val position = start + index

                    val sample = if (position < vocal48kMono.size) {
                        vocal48kMono[position]
                    } else {
                        0
                    }

                    frameBuffer.putShort(sample)
                }

                frameBuffer.flip()

                filter.processFrame(frameBuffer)

                frameBuffer.rewind()

                repeat(samplesPerFrame) { index ->
                    raw[start + index] = frameBuffer.short
                }
            }

            // Medir cuánto se retrasó la salida y recortarlo.
            val delay = estimateDelay(vocal48kMono, raw)

            Log.i(
                TAG,
                "Retardo de DeepFilterNet: $delay muestras " +
                    "(${delay * 1000f / 48000f} ms)"
            )

            val result = ShortArray(vocal48kMono.size)

            System.arraycopy(
                raw,
                delay,
                result,
                0,
                vocal48kMono.size
            )

            return result

        } finally {
            filter.release()
        }
    }

    /**
     * Busca el retardo (0..MAX_DELAY_SAMPLES) con mayor correlación
     * entre la voz original y la procesada, usando solo el tramo
     * más fuerte de la canción para que sea rápido.
     */
    private fun estimateDelay(
        original: ShortArray,
        processed: ShortArray
    ): Int {

        val window = minOf(ANALYSIS_SAMPLES, original.size)

        if (window < 4800) return 0

        // 1) Tramo más fuerte.
        var bestStart = 0
        var bestEnergy = -1.0

        var start = 0
        val step = 4800

        while (start + window <= original.size) {

            var energy = 0.0
            var i = start

            while (i < start + window) {
                val s = original[i].toDouble()
                energy += s * s
                i += 16
            }

            if (energy > bestEnergy) {
                bestEnergy = energy
                bestStart = start
            }

            start += step
        }

        if (bestEnergy <= 0.0) return 0

        // 2) Correlación cruzada (muestreando 1 de cada 2).
        val end = bestStart + window

        var bestLag = 0
        var bestScore = Double.NEGATIVE_INFINITY

        for (lag in 0..MAX_DELAY_SAMPLES) {

            if (lag % 200 == 0 && Thread.currentThread().isInterrupted) {
                throw InterruptedException(
                    "Limpieza de voz cancelada."
                )
            }

            var score = 0.0
            var i = bestStart

            while (i < end) {
                score += original[i].toDouble() *
                    processed[i + lag].toDouble()
                i += 2
            }

            if (score > bestScore) {
                bestScore = score
                bestLag = lag
            }
        }

        return bestLag
    }
}
