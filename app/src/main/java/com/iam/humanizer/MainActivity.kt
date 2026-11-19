
package com.iam.humanizer

import android.app.Activity
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max

class MainActivity : ComponentActivity() {

    private lateinit var status: TextView
    private lateinit var selectButton: Button
    private lateinit var progress: ProgressBar

    private var processedAudio: ByteArray? = null
    private var outputSampleRate = 44100
    private var outputChannels = 2

    private val mainHandler = Handler(Looper.getMainLooper())

    private val audioPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                processAudio(uri)
            }
        }

    private val savePicker =
        registerForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { uri ->
            if (uri != null) {
                saveWav(uri)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createInterface()
    }

    private fun createInterface() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(28, 35, 28, 35)
            setBackgroundColor(android.graphics.Color.rgb(12, 16, 25))
        }

        val title = TextView(this).apply {
            text = "I.A.M HUMANIZER"
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(android.graphics.Color.rgb(70, 230, 190))
        }

        val subtitle = TextView(this).apply {
            text = "Procesamiento de audio"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(android.graphics.Color.WHITE)
        }

        status = TextView(this).apply {
            text = "Selecciona una canción para comenzar."
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(android.graphics.Color.LTGRAY)
            setPadding(8, 30, 8, 30)
        }

        progress = ProgressBar(this).apply {
            visibility = ProgressBar.GONE
        }

        selectButton = Button(this).apply {
            text = "SELECCIONAR CANCIÓN"
            setOnClickListener {
                openAudioPicker()
            }
        }

        val saveButton = Button(this).apply {
            text = "EXPORTAR WAV"
            setOnClickListener {
                if (processedAudio == null) {
                    message("Primero selecciona y procesa una canción.")
                } else {
                    savePicker.launch("IAM_HUMANIZER.wav")
                }
            }
        }

        layout.addView(title, match())
        layout.addView(subtitle, match())
        layout.addView(status, match())
        layout.addView(progress, match())
        layout.addView(selectButton, match())
        layout.addView(saveButton, match())

        setContentView(layout)
    }

    private fun match(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = 18
        }
    }

    private fun openAudioPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf(
                    "audio/mpeg",
                    "audio/mp3",
                    "audio/wav",
                    "audio/x-wav",
                    "audio/mp4",
                    "audio/aac",
                    "audio/flac",
                    "audio/ogg",
                    "audio/opus",
                    "audio/3gpp",
                    "audio/amr",
                    "audio/x-m4a"
                )
            )
        }

        audioPicker.launch(arrayOf("audio/*"))
    }

    private fun processAudio(uri: Uri) {
        selectButton.isEnabled = false
        progress.visibility = ProgressBar.VISIBLE
        status.text = "Leyendo y decodificando audio..."

        Thread {
            try {
                val decoded = decodeAudio(uri)

                if (decoded.isEmpty()) {
                    throw Exception("No se pudo obtener audio.")
                }

                val normalized = normalizeAudio(decoded)

                processedAudio = pcmToWav(
                    normalized,
                    outputSampleRate,
                    outputChannels
                )

                mainHandler.post {
                    progress.visibility = ProgressBar.GONE
                    selectButton.isEnabled = true
                    status.text =
                        "Audio procesado correctamente.\nFormato de salida: WAV PCM 16-bit\nAhora puedes exportarlo."
                    message("Procesamiento terminado.")
                }

            } catch (e: Exception) {
                mainHandler.post {
                    progress.visibility = ProgressBar.GONE
                    selectButton.isEnabled = true
                    status.text = "No se pudo procesar el archivo."
                    message(e.message ?: "Error al procesar audio.")
                }
            }
        }.start()
    }

    private fun decodeAudio(uri: Uri): ShortArray {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null

        try {
            extractor.setDataSource(this, uri, null)

            var audioTrack = -1
            var format: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(i)
                val mime = candidate.getString(MediaFormat.KEY_MIME) ?: ""

                if (mime.startsWith("audio/")) {
                    audioTrack = i
                    format = candidate
                    break
                }
            }

            if (audioTrack < 0 || format == null) {
                throw Exception("El archivo no contiene una pista de audio.")
            }

            extractor.selectTrack(audioTrack)

            val mime = format.getString(MediaFormat.KEY_MIME)
                ?: throw Exception("Formato de audio desconocido.")

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val output = ByteArrayOutputStream()
            val info = MediaCodec.BufferInfo()

            var inputFinished = false
            var outputFinished = false
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var pcmEncoding = 2

            while (!outputFinished) {

                if (!inputFinished) {
                    val inputIndex = codec.dequeueInputBuffer(10000)

                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)
                            ?: throw Exception("Error de lectura del audio.")

                        val sampleSize = extractor.readSampleData(inputBuffer, 0)

                        if (sampleSize < 0) {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputFinished = true
                        } else {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                sampleSize,
                                extractor.sampleTime,
                                0
                            )
                            extractor.advance()
                        }
                    }
                }

                val outputIndex = codec.dequeueOutputBuffer(info, 10000)

                when {
                    outputIndex >= 0 -> {
                        val buffer = codec.getOutputBuffer(outputIndex)

                        if (buffer != null && info.size > 0) {
                            val outputFormat = codec.outputFormat

                            if (outputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                                sampleRate = outputFormat.getInteger(
                                    MediaFormat.KEY_SAMPLE_RATE
                                )
                            }

                            if (outputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                                channels = outputFormat.getInteger(
                                    MediaFormat.KEY_CHANNEL_COUNT
                                )
                            }

                            if (outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                                pcmEncoding = outputFormat.getInteger(
                                    MediaFormat.KEY_PCM_ENCODING
                                )
                            }

                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)

                            val chunk = ByteArray(info.size)
                            buffer.get(chunk)
                            output.write(chunk)

                            if (output.size() > 100 * 1024 * 1024) {
                                throw Exception(
                                    "El audio es demasiado grande para la memoria disponible."
                                )
                            }
                        }

                        val endOfStream =
                            info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0

                        codec.releaseOutputBuffer(outputIndex, false)

                        if (endOfStream) {
                            outputFinished = true
                        }
                    }

                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val newFormat = codec.outputFormat

                        sampleRate = newFormat.getInteger(
                            MediaFormat.KEY_SAMPLE_RATE
                        )
                        channels = newFormat.getInteger(
                            MediaFormat.KEY_CHANNEL_COUNT
                        )

                        if (newFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            pcmEncoding = newFormat.getInteger(
                                MediaFormat.KEY_PCM_ENCODING
                            )
                        }
                    }
                }
            }

            outputSampleRate = sampleRate
            outputChannels = channels

            if (channels !in 1..2) {
                throw Exception("Este prototipo admite audio mono o estéreo.")
            }

            return convertToPcm16(output.toByteArray(), pcmEncoding)

        } finally {
            try {
                codec?.stop()
            } catch (_: Exception) {
            }

            try {
                codec?.release()
            } catch (_: Exception) {
            }

            extractor.release()
        }
    }

    private fun convertToPcm16(data: ByteArray, encoding: Int): ShortArray {
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.nativeOrder())

        return when (encoding) {
            2 -> {
                val count = data.size / 2
                ShortArray(count) { buffer.short }
            }

            4 -> {
                val count = data.size / 4
                ShortArray(count) {
                    val sample = buffer.float.coerceIn(-1f, 1f)
                    (sample * 32767f).toInt().toShort()
                }
            }

            3 -> {
                ShortArray(data.size) {
                    ((data[it].toInt() and 0xFF) - 128).toShort().let {
                        (it.toInt() shl 8).toShort()
                    }
                }
            }

            else -> throw Exception(
                "El teléfono entregó un formato PCM no compatible."
            )
        }
    }

    private fun normalizeAudio(input: ShortArray): ShortArray {
        if (input.isEmpty()) return input

        var peak = 0

        for (sample in input) {
            peak = max(peak, abs(sample.toInt()))
        }

        if (peak == 0) return input

        val target = 29204.0
        val gain = (target / peak).coerceAtMost(2.0)

        return ShortArray(input.size) { index ->
            (input[index].toDouble() * gain)
                .toInt()
                .coerceIn(-32768, 32767)
                .toShort()
        }
    }

    private fun pcmToWav(
        samples: ShortArray,
        sampleRate: Int,
        channels: Int
    ): ByteArray {

        val dataSize = samples.size * 2
        val totalSize = 44 + dataSize

        val buffer = ByteBuffer.allocate(totalSize)
            .order(ByteOrder.LITTLE_ENDIAN)

        buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
        buffer.putInt(totalSize - 8)
        buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
        buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
        buffer.putInt(16)
        buffer.putShort(1)
        buffer.putShort(channels.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(sampleRate * channels * 2)
        buffer.putShort((channels * 2).toShort())
        buffer.putShort(16)
        buffer.put("data".toByteArray(Charsets.US_ASCII))
        buffer.putInt(dataSize)

        for (sample in samples) {
            buffer.putShort(sample)
        }

        return buffer.array()
    }

    private fun saveWav(uri: Uri) {
        val audio = processedAudio ?: return

        try {
            contentResolver.openOutputStream(uri)?.use { stream ->
                stream.write(audio)
                stream.flush()
            } ?: throw Exception("No se pudo guardar el archivo.")

            status.text = "¡Canción exportada correctamente!"
            message("WAV guardado correctamente.")

        } catch (e: Exception) {
            message(e.message ?: "Error al guardar.")
        }
    }

    private fun message(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }
}
