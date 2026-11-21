
package com.iam.humanizer

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.sqrt

class LiveAudioEngine(
    private val sampleRate: Int,
    private val channels: Int,
    private val source: ShortArray
) {

    @Volatile
    var gains = FloatArray(10)
        private set

    @Volatile
    var bassEnabled = false

    @Volatile
    var reverbEnabled = false

    @Volatile
    var stereoEnabled = false

    // Nuevo: eliminación de zumbido eléctrico.
    @Volatile
    var humRemoval = false

    @Volatile
    var volumePercent = 100f

    @Volatile
    var vocalClarity = 0f

    @Volatile
    var playing = false
        private set

    data class AudioAnalysis(
        val rmsDb: Float = -90f,
        val peakDb: Float = -90f,
        val lowPercent: Float = 0f,
        val midPercent: Float = 0f,
        val highPercent: Float = 0f
    )

    @Volatile
    var analysis = AudioAnalysis()
        private set

    private var audioTrack: AudioTrack? = null
    private var playbackThread: Thread? = null
    private var position = 0

    private var processor: AudioProcessor? = null

    private var lowState = 0.0
    private var midState = 0.0

    init {
        require(sampleRate in 8000..192000) {
            "Frecuencia de audio no compatible."
        }

        require(channels in 1..2) {
            "Solo se admite audio mono o estéreo."
        }

        require(source.size % channels == 0) {
            "El audio está incompleto."
        }
    }

    fun setGains(values: FloatArray) {
        if (values.size != 10) return

        gains = FloatArray(10) {
            values[it].coerceIn(-12f, 12f)
        }
    }

    fun setVolume(percent: Float) {
        volumePercent = percent.coerceIn(0f, 120f)
    }

    fun setVocalClarityLevel(percent: Float) {
        vocalClarity = percent.coerceIn(0f, 100f)
    }

    private fun updateAnalysis(input: ShortArray) {
        if (input.isEmpty()) return

        var squareSum = 0.0
        var peak = 0.0

        var lowEnergy = 0.0
        var midEnergy = 0.0
        var highEnergy = 0.0

        val lowCoefficient =
            1.0 - exp(-2.0 * PI * 250.0 / sampleRate)

        val midCoefficient =
            1.0 - exp(-2.0 * PI * 4000.0 / sampleRate)

        for (i in input.indices) {
            val sample = input[i].toDouble() / 32768.0
            val magnitude = abs(sample)

            squareSum += sample * sample
            peak = max(peak, magnitude)

            lowState += lowCoefficient * (sample - lowState)
            midState += midCoefficient * (sample - midState)

            val low = lowState
            val mid = midState - lowState
            val high = sample - midState

            lowEnergy += low * low
            midEnergy += mid * mid
            highEnergy += high * high
        }

        val totalEnergy =
            lowEnergy + midEnergy + highEnergy

        val rms = sqrt(squareSum / input.size)

        fun toDb(value: Double): Float {
            return (
                20.0 * log10(value.coerceAtLeast(0.000001))
            ).toFloat()
        }

        analysis = AudioAnalysis(
            rmsDb = toDb(rms),
            peakDb = toDb(peak),
            lowPercent = if (totalEnergy > 0.0000001) {
                (lowEnergy / totalEnergy * 100.0).toFloat()
            } else 0f,
            midPercent = if (totalEnergy > 0.0000001) {
                (midEnergy / totalEnergy * 100.0).toFloat()
            } else 0f,
            highPercent = if (totalEnergy > 0.0000001) {
                (highEnergy / totalEnergy * 100.0).toFloat()
            } else 0f
        )
    }

    private fun createProcessor(): AudioProcessor {
        return AudioProcessor(
            sampleRate = sampleRate,
            channels = channels,
            settings = AudioProcessor.Settings(
                equalizer = gains.copyOf(),
                bassEnabled = bassEnabled,
                reverbEnabled = reverbEnabled,
                stereoEnabled = stereoEnabled,
                volumePercent = volumePercent.toInt(),
                vocalClarity = vocalClarity.toInt(),
                humRemoval = humRemoval
            )
        )
    }

    private fun processBlock(input: ShortArray): ShortArray {
        updateAnalysis(input)

        return processor?.process(input)
            ?: input.copyOf()
    }

    @Synchronized
    fun start() {
        if (playing || source.isEmpty()) return

        val channelMask = if (channels == 1) {
            AudioFormat.CHANNEL_OUT_MONO
        } else {
            AudioFormat.CHANNEL_OUT_STEREO
        }

        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            channelMask,
            AudioFormat.ENCODING_PCM_16BIT
        )

        require(minBuffer > 0) {
            "El teléfono no admite este formato de audio."
        }

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(
                        AudioAttributes.CONTENT_TYPE_MUSIC
                    )
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .setEncoding(
                        AudioFormat.ENCODING_PCM_16BIT
                    )
                    .build()
            )
            .setBufferSizeInBytes(minBuffer * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        processor = createProcessor()
        position = 0
        lowState = 0.0
        midState = 0.0

        audioTrack = track
        playing = true

        track.play()

        playbackThread = Thread {
            try {
                val samplesPerBlock = 2048 * channels

                while (playing && position < source.size) {
                    val count = min(
                        samplesPerBlock,
                        source.size - position
                    )

                    val input = source.copyOfRange(
                        position,
                        position + count
                    )

                    position += count

                    val processed = processBlock(input)

                    var offset = 0

                    while (playing && offset < processed.size) {
                        val written = track.write(
                            processed,
                            offset,
                            processed.size - offset
                        )

                        if (written <= 0) {
                            throw IllegalStateException(
                                "Error al reproducir el audio."
                            )
                        }

                        offset += written
                    }
                }
            } catch (_: Exception) {
                // Detención segura.
            } finally {
                playing = false
                processor = null

                try {
                    track.pause()
                    track.flush()
                    track.stop()
                } catch (_: Exception) {
                }

                track.release()

                synchronized(this) {
                    if (audioTrack === track) {
                        audioTrack = null
                    }
                }
            }
        }.apply {
            name = "IAM-Smart-Audio"
            start()
        }
    }

    @Synchronized
    fun stop() {
        playing = false

        try {
            audioTrack?.pause()
            audioTrack?.flush()
        } catch (_: Exception) {
        }

        playbackThread?.interrupt()
        playbackThread = null
    }

    fun isPlaying(): Boolean = playing
}
