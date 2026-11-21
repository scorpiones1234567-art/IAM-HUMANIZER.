
package com.iam.humanizer

import android.content.Context
import com.rikorose.deepfilternet.NativeDeepFilterNet
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * I-A-M - LIMPIADOR NEURONAL DE VOZ
 *
 * Recibe audio mono PCM de 16 bits a 48 kHz.
 * Procesa la voz con DeepFilterNet.
 *
 * Debe ejecutarse desde un hilo de trabajo,
 * nunca desde el hilo principal de Android.
 */
object DeepFilterVoiceCleaner {

    fun clean(
        context: Context,
        vocal48kMono: ShortArray,
        attenuationDb: Float = 50f
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

            if (!filter.setAttenuationLimit(
                    attenuationDb.coerceIn(0f, 100f)
                )
            ) {
                throw IllegalStateException(
                    "No se pudo configurar la reducción de ruido."
                )
            }

            val samplesPerFrame = frameBytes / 2
            val result = ShortArray(vocal48kMono.size)

            val frameBuffer = ByteBuffer
                .allocateDirect(frameBytes)
                .order(ByteOrder.LITTLE_ENDIAN)

            var sourcePosition = 0
            var outputPosition = 0

            while (sourcePosition < vocal48kMono.size) {

                frameBuffer.clear()

                val available = minOf(
                    samplesPerFrame,
                    vocal48kMono.size - sourcePosition
                )

                repeat(samplesPerFrame) { index ->
                    val sample = if (index < available) {
                        vocal48kMono[sourcePosition + index]
                    } else {
                        0
                    }

                    frameBuffer.putShort(sample.toShort())
                }

                frameBuffer.flip()

                filter.processFrame(frameBuffer)

                frameBuffer.rewind()

                repeat(available) {
                    result[outputPosition++] =
                        frameBuffer.short
                }

                sourcePosition += available
            }

            return result

        } finally {
            filter.release()
        }
    }
}
