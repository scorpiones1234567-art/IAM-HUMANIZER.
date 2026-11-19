
package com.iam.humanizer

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

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

    @Volatile
    var playing = false
        private set

    private var audioTrack: AudioTrack? = null
    private var playbackThread: Thread? = null
    private var position = 0

    private val frequencies = doubleArrayOf(
        31.0, 62.0, 125.0, 250.0, 500.0,
        1000.0, 2000.0, 4000.0, 8000.0, 16000.0
    )

    private data class Filter(
        var b0: Double = 1.0,
        var b1: Double = 0.0,
        var b2: Double = 0.0,
        var a1: Double = 0.0,
        var a2: Double = 0.0,
        var x1: Double = 0.0,
        var x2: Double = 0.0,
        var y1: Double = 0.0,
        var y2: Double = 0.0
    ) {
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

    private val filters = Array(channels) {
        Array(10) { Filter() }
    }

    private val bassFilters = Array(channels) {
        Filter()
    }

    private val delayFrames = (sampleRate * 0.22).toInt().coerceAtLeast(1)
    private val delayBuffer = DoubleArray(delayFrames * channels)
    private var delayPosition = 0

    private var appliedGains = FloatArray(10) { Float.NaN }
    private var appliedBass = false

    fun setGains(values: FloatArray) {
        gains = values.copyOf()
    }

    private fun updateFilter(
        filter: Filter,
        frequency: Double,
        gainDb: Double
    ) {
        val freq = frequency.coerceIn(20.0, sampleRate * 0.45)
        val a = 10.0.pow(gainDb / 40.0)
        val w = 2.0 * PI * freq / sampleRate
        val alpha = sin(w) / 1.2
        val c = cos(w)
        val a0 = 1.0 + alpha / a

        filter.b0 = (1.0 + alpha * a) / a0
        filter.b1 = (-2.0 * c) / a0
        filter.b2 = (1.0 - alpha * a) / a0
        filter.a1 = (-2.0 * c) / a0
        filter.a2 = (1.0 - alpha / a) / a0
    }

    private fun refreshSettings() {
        val current = gains

        for (i in current.indices) {
            if (current[i] != appliedGains[i]) {
                for (channel in 0 until channels) {
                    updateFilter(
                        filters[channel][i],
                        frequencies[i],
                        current[i].toDouble()
                    )
                }
                appliedGains[i] = current[i]
            }
        }

        if (bassEnabled != appliedBass) {
            for (channel in 0 until channels) {
                updateFilter(
                    bassFilters[channel],
                    90.0,
                    if (bassEnabled) 4.0 else 0.0
                )
            }
            appliedBass = bassEnabled
        }
    }

    private fun processBlock(
        input: ShortArray,
        count: Int
    ): ShortArray {
        refreshSettings()

        val output = ShortArray(count)
        val frameCount = count / channels

        for (frame in 0 until frameCount) {
            var left = input[frame * channels].toDouble() / 32768.0
            var right = if (channels == 2) {
                input[frame * channels + 1].toDouble() / 32768.0
            } else left

            for (filter in filters[0]) {
                left = filter.process(left)
            }

            left = bassFilters[0].process(left)

            if (channels == 2) {
                for (filter in filters[1]) {
                    right = filter.process(right)
                }
                right = bassFilters[1].process(right)
            }

            if (stereoEnabled && channels == 2) {
                val mid = (left + right) * 0.5
                val side = (left - right) * 0.5 * 1.25
                left = mid + side
                right = mid - side
            }

            if (reverbEnabled) {
                val index = delayPosition * channels
                val oldLeft = delayBuffer[index]
                delayBuffer[index] = left + oldLeft * 0.28
                left += oldLeft * 0.22

                if (channels == 2) {
                    val oldRight = delayBuffer[index + 1]
                    delayBuffer[index + 1] = right + oldRight * 0.28
                    right += oldRight * 0.22
                }

                delayPosition++
                if (delayPosition >= delayFrames) delayPosition = 0
            }

            left = left / (1.0 + abs(left) * 0.35)
            right = right / (1.0 + abs(right) * 0.35)

            output[frame * channels] =
                (left * 32767.0).roundToInt()
                    .coerceIn(-32768, 32767).toShort()

            if (channels == 2) {
                output[frame * channels + 1] =
                    (right * 32767.0).roundToInt()
                        .coerceIn(-32768, 32767).toShort()
            }
        }

        return output
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
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(minBuffer * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack = track
        position = 0
        delayPosition = 0
        playing = true

        track.play()

        playbackThread = Thread {
            try {
                val framesPerBlock = 2048
                val samplesPerBlock = framesPerBlock * channels

                while (playing && position < source.size) {
                    val count = minOf(
                        samplesPerBlock,
                        source.size - position
                    )

                    val input = source.copyOfRange(
                        position,
                        position + count
                    )

                    position += count

                    val output = processBlock(input, count)
                    var offset = 0

                    while (playing && offset < output.size) {
                        val written = track.write(
                            output,
                            offset,
                            output.size - offset
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
                // La reproducción se detiene de forma segura.
            } finally {
                playing = false
                try {
                    track.pause()
                    track.flush()
                    track.stop()
                } catch (_: Exception) {
                }
                track.release()
                if (audioTrack === track) {
                    audioTrack = null
                }
            }
        }.apply {
            name = "IAM-Live-Audio"
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
