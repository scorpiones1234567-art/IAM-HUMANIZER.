package com.iam.humanizer

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
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

        fun reset() {
            x1 = 0.0
            x2 = 0.0
            y1 = 0.0
            y2 = 0.0
        }
    }

    private val filters = Array(channels) {
        Array(10) { Filter() }
    }

    private val bassFilters = Array(channels) {
        Filter()
    }

    private val vocalPresenceFilters = Array(channels) {
        Filter()
    }

    private val vocalClarityFilters = Array(channels) {
        Filter()
    }

    private val delayFrames = (sampleRate * 0.22)
        .toInt()
        .coerceAtLeast(1)

    private val delayBuffer = DoubleArray(delayFrames * channels)
    private var delayPosition = 0

    private var appliedGains = FloatArray(10) { Float.NaN }
    private var appliedBass = false
    private var appliedClarity = Float.NaN

    private var limiterEnvelope = 0.0
    private var limiterGain = 1.0

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

    private fun updateFilter(
        filter: Filter,
        frequency: Double,
        gainDb: Double
    ) {
        val freq = frequency.coerceIn(
            20.0,
            sampleRate * 0.45
        )

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

        val clarity = vocalClarity

        if (clarity != appliedClarity) {
            val amount = clarity / 100.0

            for (channel in 0 until channels) {
                updateFilter(
                    vocalPresenceFilters[channel],
                    2800.0,
                    amount * 3.5
                )

                updateFilter(
                    vocalClarityFilters[channel],
                    5200.0,
                    amount * 1.5
                )
            }

            appliedClarity = clarity
        }
    }

    private fun updateAnalysis(
        input: ShortArray,
        count: Int
    ) {
        if (count <= 0) return

        var squareSum = 0.0
        var peak = 0.0

        var lowEnergy = 0.0
        var midEnergy = 0.0
        var highEnergy = 0.0

        val lowCoefficient =
            1.0 - kotlin.math.exp(-2.0 * PI * 250.0 / sampleRate)

        val midCoefficient =
            1.0 - kotlin.math.exp(-2.0 * PI * 4000.0 / sampleRate)

        for (i in 0 until count) {
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

        val rms = kotlin.math.sqrt(
            squareSum / count
        )

        fun toDb(value: Double): Float {
            return (20.0 * kotlin.math.log10(
                value.coerceAtLeast(0.000001)
            )).toFloat()
        }

        val lowPercent =
            if (totalEnergy > 0.0000001) {
                (lowEnergy / totalEnergy * 100.0).toFloat()
            } else 0f

        val midPercent =
            if (totalEnergy > 0.0000001) {
                (midEnergy / totalEnergy * 100.0).toFloat()
            } else 0f

        val highPercent =
            if (totalEnergy > 0.0000001) {
                (highEnergy / totalEnergy * 100.0).toFloat()
            } else 0f

        analysis = AudioAnalysis(
            rmsDb = toDb(rms),
            peakDb = toDb(peak),
            lowPercent = lowPercent,
            midPercent = midPercent,
            highPercent = highPercent
        )
    }

    private fun applyLimiter(
        sample: Double
    ): Double {
        val magnitude = abs(sample)

        val attack = 0.35
        val release = 0.0025

        limiterEnvelope = if (magnitude > limiterEnvelope) {
            limiterEnvelope +
                (magnitude - limiterEnvelope) * attack
        } else {
            limiterEnvelope +
                (magnitude - limiterEnvelope) * release
        }

        val threshold = 0.94

        val desiredGain = if (limiterEnvelope > threshold) {
            threshold / limiterEnvelope
        } else {
            1.0
        }

        limiterGain = if (desiredGain < limiterGain) {
            desiredGain
        } else {
            limiterGain +
                (desiredGain - limiterGain) * 0.002
        }

        return (sample * limiterGain).coerceIn(
            -0.98,
            0.98
        )
    }

    private fun processBlock(
        input: ShortArray,
        count: Int
    ): ShortArray {
        refreshSettings()
        updateAnalysis(input, count)

        val output = ShortArray(count)
        val frameCount = count / channels

        val volume = volumePercent / 100.0

        for (frame in 0 until frameCount) {
            var left =
                input[frame * channels].toDouble() / 32768.0

            var right = if (channels == 2) {
                input[frame * channels + 1].toDouble() / 32768.0
            } else {
                left
            }

            for (filter in filters[0]) {
                left = filter.process(left)
            }

            left = bassFilters[0].process(left)
            left = vocalPresenceFilters[0].process(left)
            left = vocalClarityFilters[0].process(left)

            if (channels == 2) {
                for (filter in filters[1]) {
                    right = filter.process(right)
                }

                right = bassFilters[1].process(right)
                right = vocalPresenceFilters[1].process(right)
                right = vocalClarityFilters[1].process(right)
            }

            if (stereoEnabled && channels == 2) {
                val mid = (left + right) * 0.5
                val side = (left - right) * 0.5 * 1.20

                left = mid + side
                right = mid - side
            }

            if (reverbEnabled) {
                val index = delayPosition * channels

                val oldLeft = delayBuffer[index]

                delayBuffer[index] =
                    left + oldLeft * 0.28

                left += oldLeft * 0.22

                if (channels == 2) {
                    val oldRight = delayBuffer[index + 1]

                    delayBuffer[index + 1] =
                        right + oldRight * 0.28

                    right += oldRight * 0.22
                }

                delayPosition++

                if (delayPosition >= delayFrames) {
                    delayPosition = 0
                }
            }

            left = applyLimiter(left * volume)
            right = if (channels == 2) {
                applyLimiter(right * volume)
            } else {
                left
            }

            output[frame * channels] =
                (left * 32767.0)
                    .roundToInt()
                    .coerceIn(-32768, 32767)
                    .toShort()

            if (channels == 2) {
                output[frame * channels + 1] =
                    (right * 32767.0)
                        .roundToInt()
                        .coerceIn(-32768, 32767)
                        .toShort()
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

        audioTrack = track
        position = 0
        delayPosition = 0

        limiterEnvelope = 0.0
        limiterGain = 1.0

        lowState = 0.0
        midState = 0.0

        for (channel in 0 until channels) {
            for (filter in filters[channel]) {
                filter.reset()
            }

            bassFilters[channel].reset()
            vocalPresenceFilters[channel].reset()
            vocalClarityFilters[channel].reset()
        }

        delayBuffer.fill(0.0)

        playing = true
        track.play()

        playbackThread = Thread {
            try {
                val framesPerBlock = 2048
                val samplesPerBlock =
                    framesPerBlock * channels

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

                    val processed = processBlock(input, count)

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
                // Detención segura del motor.
            } finally {
                playing = false

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

