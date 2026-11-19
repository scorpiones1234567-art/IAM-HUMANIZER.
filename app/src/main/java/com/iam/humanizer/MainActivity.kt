
package com.iam.humanizer

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

class MainActivity : ComponentActivity() {

    private lateinit var status: TextView
    private lateinit var selectButton: Button
    private lateinit var playButton: Button
    private lateinit var exportButton: Button
    private lateinit var progress: ProgressBar
    private lateinit var bassSwitch: Switch
    private lateinit var reverbSwitch: Switch
    private lateinit var stereoSwitch: Switch
    private lateinit var presetSpinner: Spinner
    private lateinit var faders: List<VerticalFader>

    private var audio: ShortArray? = null
    private var rate = 44100
    private var channels = 2
    private var engine: LiveAudioEngine? = null
    private val handler = Handler(Looper.getMainLooper())
    private var pendingWav: ByteArray? = null

    private val cyan = 0xFF36E5FF.toInt()
    private val pink = 0xFFFF4FCB.toInt()
    private val purple = 0xFF9B5CFF.toInt()
    private val gold = 0xFFFFD166.toInt()
    private val white = 0xFFFFFFFF.toInt()

    private val frequencies = listOf(
        "31", "62", "125", "250", "500",
        "1k", "2k", "4k", "8k", "16k"
    )

    private val presets = linkedMapOf(
        "Personalizado" to floatArrayOf(0f,0f,0f,0f,0f,0f,0f,0f,0f,0f),
        "VOZ CLARA" to floatArrayOf(-3f,-2f,-1f,0f,1f,2f,3f,3f,2f,1f),
        "Acústica" to floatArrayOf(2f,2f,1f,0f,1f,2f,2f,1f,1f,1f),
        "Pop" to floatArrayOf(-1f,0f,1f,2f,1f,0f,1f,2f,2f,1f),
        "Rock" to floatArrayOf(3f,2f,-1f,-2f,0f,1f,2f,3f,3f,2f),
        "Hip Hop" to floatArrayOf(4f,4f,2f,0f,-1f,0f,1f,1f,2f,1f),
        "Jazz" to floatArrayOf(2f,1f,0f,1f,-1f,-1f,0f,1f,2f,2f),
        "Heavy Metal" to floatArrayOf(3f,2f,1f,-1f,1f,2f,3f,2f,1f,2f),
        "Clásica" to floatArrayOf(2f,1f,0f,0f,0f,1f,1f,2f,2f,2f),
        "K-pop · Voz" to floatArrayOf(-2f,-1f,0f,1f,1f,2f,3f,3f,2f,2f),
        "Cumbia · Voz" to floatArrayOf(1f,2f,1f,0f,1f,2f,3f,2f,1f,0f)
    )

    private data class Decoded(
        val samples: ShortArray,
        val sampleRate: Int,
        val channelCount: Int
    )

    private val audioPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) {
            if (it != null) loadAudio(it)
        }

    private val savePicker =
        registerForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) {
            if (it != null) saveWav(it)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF160D32.toInt()
        window.navigationBarColor = 0xFF100A25.toInt()
        buildInterface()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun label(text: String, size: Float = 15f): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            gravity = Gravity.CENTER
            setTextColor(white)
            setPadding(dp(4), dp(7), dp(4), dp(7))
        }

    private fun buttonStyle(button: Button, color: Int) {
        button.textSize = 14f
        button.isAllCaps = false
        button.setTextColor(white)
        button.background = GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(15).toFloat()
        }
        button.setPadding(dp(12), dp(12), dp(12), dp(12))
    }

    private fun params() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply {
        bottomMargin = dp(9)
    }

    private fun buildInterface() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF100923.toInt(), 0xFF251047.toInt(), 0xFF10172D.toInt())
            )
        }

        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(4), dp(8), dp(4), dp(20))
        }

        // Logotipo original: iam_logo.png dentro de res/drawable
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.iam_logo)
            contentDescription = "Escorpión dorado I-A-M"
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
        }
        content.addView(logo, LinearLayout.LayoutParams(dp(125), dp(125)))

        content.addView(label("I-A-M", 32f).apply {
            setTextColor(gold)
            paint.isFakeBoldText = true
        })
        content.addView(label("ESTUDIO DE AUDIO EN VIVO", 13f).apply {
            setTextColor(cyan)
        })
        content.addView(label("Mejora tu sonido y dale prioridad a la voz.", 13f))

        status = label("Selecciona una canción para comenzar.").apply {
            setTextColor(gold)
        }
        content.addView(status)

        progress = ProgressBar(this).apply { visibility = View.GONE }
        content.addView(progress)

        selectButton = Button(this).apply {
            text = "♫  SELECCIONAR CANCIÓN"
            buttonStyle(this, 0xFF7438D9.toInt())
            setOnClickListener { audioPicker.launch(arrayOf("audio/*")) }
        }
        content.addView(selectButton, params())

        content.addView(label("ECUALIZADOR DE 10 BANDAS", 18f).apply {
            setTextColor(cyan)
        })
        content.addView(label("Ajusta graves, medios y agudos mientras escuchas.", 12f))

        presetSpinner = Spinner(this)
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            presets.keys.toList()
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        presetSpinner.adapter = adapter
        content.addView(presetSpinner, params())

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val list = mutableListOf<VerticalFader>()

        frequencies.forEachIndexed { index, frequency ->
            val column = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
            }
            val valueLabel = label("0", 11f).apply { setTextColor(gold) }
            val fader = VerticalFader(this).apply {
                accentColor = when (index % 3) {
                    0 -> cyan
                    1 -> pink
                    else -> purple
                }
            }
            column.addView(valueLabel)
            column.addView(fader, LinearLayout.LayoutParams(dp(28), dp(165)))
            column.addView(label(frequency, 10f))
            fader.onValueChanged = {
                valueLabel.text = it.roundToInt().toString()
                updateEngine()
            }
            list.add(fader)
            row.addView(column, LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ))
        }
        faders = list
        content.addView(row)

        presetSpinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) {}
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    val values = presets.values.elementAt(position)
                    faders.forEachIndexed { i, fader -> fader.value = values[i] }
                    updateEngine()
                }
            }

        val assistantButton = Button(this).apply {
            text = "✦  AYUDARME A ELEGIR ESTILO"
            buttonStyle(this, 0xFFB02FAD.toInt())
            setOnClickListener { recommendPreset() }
        }
        content.addView(assistantButton, params())
        content.addView(label(
            "La sugerencia usa características básicas del sonido. " +
                "Es orientativa: no reconoce géneros con precisión ni separa la voz.",
            11f
        ).apply { setTextColor(0xFFD8C8F5.toInt()) })

        bassSwitch = Switch(this).apply {
            text = "Refuerzo de graves"
            setTextColor(white)
            setOnCheckedChangeListener { _, _ -> updateEngine() }
        }
        reverbSwitch = Switch(this).apply {
            text = "Reverberación"
            setTextColor(white)
            setOnCheckedChangeListener { _, _ -> updateEngine() }
        }
        stereoSwitch = Switch(this).apply {
            text = "Amplitud estéreo"
            setTextColor(white)
            setOnCheckedChangeListener { _, _ -> updateEngine() }
        }
        content.addView(bassSwitch)
        content.addView(reverbSwitch)
        content.addView(stereoSwitch)

        content.addView(label(
            "Mueve los controles mientras escuchas la canción.", 12f
        ).apply { setTextColor(cyan) })

        playButton = Button(this).apply {
            text = "▶  REPRODUCIR EN VIVO"
            isEnabled = false
            buttonStyle(this, 0xFF168A9D.toInt())
            setOnClickListener { togglePlayback() }
        }
        content.addView(playButton, params())

        exportButton = Button(this).apply {
            text = "↓  EXPORTAR CANCIÓN WAV"
            isEnabled = false
            buttonStyle(this, 0xFF8C3FC7.toInt())
            setOnClickListener { exportAudio() }
        }
        content.addView(exportButton, params())

        content.addView(label("I-A-M · Procesamiento local", 11f).apply {
            setTextColor(gold)
        })

        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        setContentView(root)
    }

    private fun gains() = faders.map { it.value }.toFloatArray()

    private fun updateEngine() {
        val current = engine ?: return
        current.setGains(gains())
        current.bassEnabled = bassSwitch.isChecked
        current.reverbEnabled = reverbSwitch.isChecked
        current.stereoEnabled = stereoSwitch.isChecked
    }

    private fun recommendPreset() {
        val samples = audio
        if (samples == null) {
            message("Primero selecciona una canción.")
            return
        }

        val step = max(1, samples.size / 20000)
        var crossings = 0
        var count = 0
        var previous = 0

        for (i in samples.indices step step) {
            val current = samples[i].toInt()
            if (count > 0 &&
                ((previous < 0 && current >= 0) ||
                 (previous >= 0 && current < 0))
            ) crossings++
            previous = current
            count++
        }

        val ratio = crossings.toFloat() / count.coerceAtLeast(1)
        val recommendation = when {
            ratio > 0.12f -> "K-pop · Voz"
            ratio < 0.055f -> "Cumbia · Voz"
            else -> "VOZ CLARA"
        }

        val position = presets.keys.indexOf(recommendation)
        if (position >= 0) presetSpinner.setSelection(position)

        status.text = "Sugerencia: $recommendation"
        message("Sugerencia orientativa: $recommendation. Escucha y decide.")
    }

    private fun togglePlayback() {
        val samples = audio ?: return message("Selecciona una canción primero.")

        if (engine?.isPlaying() == true) {
            engine?.stop()
            playButton.text = "▶  REPRODUCIR EN VIVO"
            status.text = "Reproducción detenida."
            return
        }

        try {
            engine?.stop()
            val newEngine = LiveAudioEngine(rate, channels, samples)
            newEngine.setGains(gains())
            newEngine.bassEnabled = bassSwitch.isChecked
            newEngine.reverbEnabled = reverbSwitch.isChecked
            newEngine.stereoEnabled = stereoSwitch.isChecked
            engine = newEngine
            newEngine.start()
            playButton.text = "■  DETENER REPRODUCCIÓN"
            status.text = "Reproduciendo. Ajusta el ecualizador."
            watchPlayback()
        } catch (e: Exception) {
            engine = null
            playButton.text = "▶  REPRODUCIR EN VIVO"
            message(e.message ?: "No se pudo reproducir.")
        }
    }

    private fun watchPlayback() {
        handler.postDelayed({
            if (engine?.isPlaying() != true) {
                playButton.text = "▶  REPRODUCIR EN VIVO"
            } else {
                watchPlayback()
            }
        }, 400)
    }

    private fun stopPlayback() {
        engine?.stop()
        engine = null
        if (::playButton.isInitialized) {
            playButton.text = "▶  REPRODUCIR EN VIVO"
        }
    }

    private fun loadAudio(uri: Uri) {
        stopPlayback()
        audio = null
        selectButton.isEnabled = false
        playButton.isEnabled = false
        exportButton.isEnabled = false
        progress.visibility = View.VISIBLE
        status.text = "Cargando canción..."

        Thread {
            try {
                val decoded = decode(uri)
                handler.post {
                    audio = decoded.samples
                    rate = decoded.sampleRate
                    channels = decoded.channelCount
                    progress.visibility = View.GONE
                    selectButton.isEnabled = true
                    playButton.isEnabled = true
                    exportButton.isEnabled = true
                    status.text = "Canción lista. Pulsa reproducir."
                    message("Audio cargado correctamente.")
                }
            } catch (e: Exception) {
                handler.post {
                    progress.visibility = View.GONE
                    selectButton.isEnabled = true
                    status.text = "Error al cargar canción."
                    message(e.message ?: "Error de audio.")
                }
            }
        }.start()
    }

    private fun decode(uri: Uri): Decoded {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null

        try {
            extractor.setDataSource(this, uri, null)
            var track = -1
            var format: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(i)
                if ((candidate.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) {
                    track = i
                    format = candidate
                    break
                }
            }
            if (track < 0 || format == null) throw Exception("No se encontró audio.")

            extractor.selectTrack(track)
            val mime = format.getString(MediaFormat.KEY_MIME)
                ?: throw Exception("Formato desconocido.")

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val output = ByteArrayOutputStream()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var sr = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var ch = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = AudioFormat.ENCODING_PCM_16BIT

            while (!outputDone) {
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(10000)
                    if (index >= 0) {
                        val input = codec.getInputBuffer(index)
                            ?: throw Exception("Error leyendo audio.")
                        input.clear()
                        val size = extractor.readSampleData(input, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(index, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(info, 10000)
                when {
                    outIndex >= 0 -> {
                        val buffer = codec.getOutputBuffer(outIndex)
                        if (buffer != null && info.size > 0) {
                            val fmt = codec.outputFormat
                            if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE))
                                sr = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                                ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            if (fmt.containsKey(MediaFormat.KEY_PCM_ENCODING))
                                encoding = fmt.getInteger(MediaFormat.KEY_PCM_ENCODING)

                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val chunk = ByteArray(info.size)
                            buffer.get(chunk)
                            output.write(chunk)

                            if (output.size() > 100 * 1024 * 1024)
                                throw Exception("Audio demasiado grande: límite 100 MB.")
                        }
                        val ended = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(outIndex, false)
                        if (ended) outputDone = true
                    }
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val fmt = codec.outputFormat
                        sr = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (fmt.containsKey(MediaFormat.KEY_PCM_ENCODING))
                            encoding = fmt.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    }
                }
            }

            if (ch !in 1..2 || sr !in 8000..192000)
                throw Exception("Formato de audio no compatible.")

            val bytes = output.toByteArray()
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder())

            val samples = when (encoding) {
                AudioFormat.ENCODING_PCM_16BIT ->
                    ShortArray(bytes.size / 2) { buffer.short }
                AudioFormat.ENCODING_PCM_FLOAT ->
                    ShortArray(bytes.size / 4) {
                        (buffer.float.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                    }
                AudioFormat.ENCODING_PCM_8BIT ->
                    ShortArray(bytes.size) {
                        (((bytes[it].toInt() and 255) - 128) shl 8).toShort()
                    }
                else -> throw Exception("PCM no compatible.")
            }

            return Decoded(samples, sr, ch)
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            extractor.release()
        }
    }

    private data class Filter(
        val b0: Double, val b1: Double, val b2: Double,
        val a1: Double, val a2: Double
    ) {
        var x1 = 0.0
        var x2 = 0.0
        var y1 = 0.0
        var y2 = 0.0

        fun process(x: Double): Double {
            val y = b0*x + b1*x1 + b2*x2 - a1*y1 - a2*y2
            x2 = x1
            x1 = x
            y2 = y1
            y1 = y
            return y
        }
    }

    private fun makeFilter(freq: Double, db: Double, sr: Double): Filter? {
        val f = freq.coerceIn(20.0, sr * 0.45)
        if (f >= sr / 2.0) return null
        val a = 10.0.pow(db / 40.0)
        val w = 2.0 * PI * f / sr
        val alpha = sin(w) / 1.2
        val c = cos(w)
        val a0 = 1.0 + alpha / a

        return Filter(
            (1.0 + alpha*a) / a0,
            (-2.0*c) / a0,
            (1.0 - alpha*a) / a0,
            (-2.0*c) / a0,
            (1.0 - alpha/a) / a0
        )
    }

    private fun applyEffects(
        input: ShortArray,
        sr: Int,
        ch: Int,
        values: FloatArray,
        bass: Boolean,
        reverb: Boolean,
        stereo: Boolean
    ): ShortArray {
        val hz = doubleArrayOf(
            31.0,62.0,125.0,250.0,500.0,
            1000.0,2000.0,4000.0,8000.0,16000.0
        )
        val filters = Array(ch) {
            hz.indices.mapNotNull {
                makeFilter(hz[it], values[it].toDouble(), sr.toDouble())
            }
        }
        val bassFilters = if (bass) {
            Array(ch) { listOfNotNull(makeFilter(90.0, 4.0, sr.toDouble())) }
        } else null

        val delayFrames = (sr * 0.22).toInt().coerceAtLeast(1)
        val delay = if (reverb) DoubleArray(delayFrames * ch) else null
        var delayPos = 0
        val output = ShortArray(input.size)

        for (frame in input.indices step ch) {
            var left = input[frame].toDouble() / 32768.0
            var right = if (ch == 2) input[frame + 1].toDouble() / 32768.0 else left

            for (filter in filters[0]) left = filter.process(left)
            bassFilters?.get(0)?.forEach { left = it.process(left) }

            if (ch == 2) {
                for (filter in filters[1]) right = filter.process(right)
                bassFilters?.get(1)?.forEach { right = it.process(right) }
            }

            if (stereo && ch == 2) {
                val mid = (left + right) * 0.5
                val side = (left - right) * 0.5 * 1.25
                left = mid + side
                right = mid - side
            }

            if (delay != null) {
                val index = delayPos * ch
                val oldLeft = delay[index]
                delay[index] = left + oldLeft * 0.28
                left += oldLeft * 0.22
                if (ch == 2) {
                    val oldRight = delay[index + 1]
                    delay[index + 1] = right + oldRight * 0.28
                    right += oldRight * 0.22
                }
                delayPos = (delayPos + 1) % delayFrames
            }

            left /= 1.0 + abs(left) * 0.35
            right /= 1.0 + abs(right) * 0.35

            output[frame] = (left * 32767.0).roundToInt()
                .coerceIn(-32768, 32767).toShort()

            if (ch == 2) {
                output[frame + 1] = (right * 32767.0).roundToInt()
                    .coerceIn(-32768, 32767).toShort()
            }
        }
        return output
    }

    private fun wav(samples: ShortArray, sr: Int, ch: Int): ByteArray {
        val size = samples.size * 2
        val b = ByteBuffer.allocate(44 + size).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray(Charsets.US_ASCII))
        b.putInt(36 + size)
        b.put("WAVE".toByteArray(Charsets.US_ASCII))
        b.put("fmt ".toByteArray(Charsets.US_ASCII))
        b.putInt(16)
        b.putShort(1)
        b.putShort(ch.toShort())
        b.putInt(sr)
        b.putInt(sr * ch * 2)
        b.putShort((ch * 2).toShort())
        b.putShort(16)
        b.put("data".toByteArray(Charsets.US_ASCII))
        b.putInt(size)
        samples.forEach { b.putShort(it) }
        return b.array()
    }

    private fun exportAudio() {
        val samples = audio ?: return
        val values = gains()
        val sr = rate
        val ch = channels
        val bass = bassSwitch.isChecked
        val reverb = reverbSwitch.isChecked
        val stereo = stereoSwitch.isChecked

        exportButton.isEnabled = false
        progress.visibility = View.VISIBLE
        status.text = "Preparando WAV..."

        Thread {
            try {
                val result = applyEffects(samples, sr, ch, values, bass, reverb, stereo)
                val resultWav = wav(result, sr, ch)
                handler.post {
                    pendingWav = resultWav
                    progress.visibility = View.GONE
                    exportButton.isEnabled = true
                    status.text = "Selecciona dónde guardar el WAV."
                    savePicker.launch("I-A-M_HUMANIZADO.wav")
                }
            } catch (e: Exception) {
                handler.post {
                    progress.visibility = View.GONE
                    exportButton.isEnabled = true
                    message(e.message ?: "Error preparando WAV.")
                }
            }
        }.start()
    }

    private fun saveWav(uri: Uri) {
        val data = pendingWav ?: return
        try {
            contentResolver.openOutputStream(uri)?.use {
                it.write(data)
                it.flush()
            } ?: throw Exception("No se pudo guardar.")
            pendingWav = null
            status.text = "WAV exportado correctamente."
            message("Canción guardada.")
        } catch (e: Exception) {
            message(e.message ?: "Error guardando WAV.")
        }
    }

    private fun message(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopPlayback()
        super.onDestroy()
    }
}

class VerticalFader(context: android.content.Context) : View(context) {

    var value: Float = 0f
        set(newValue) {
            field = newValue.coerceIn(-12f, 12f)
            invalidate()
            onValueChanged?.invoke(field)
        }

    var onValueChanged: ((Float) -> Unit)? = null
    var accentColor: Int = 0xFF36E5FF.toInt()

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF55565D.toInt()
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
    }

    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 7f
        strokeCap = Paint.Cap.ROUND
    }

    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        activePaint.color = accentColor
        knobPaint.color = accentColor

        val x = width / 2f
        val top = 12f
        val bottom = height - 12f
        val center = (top + bottom) / 2f
        val range = (bottom - top) / 2f
        val y = center - (value / 12f) * range

        canvas.drawLine(x, top, x, bottom, linePaint)
        canvas.drawLine(x, center, x, y, activePaint)
        canvas.drawCircle(x, y, 10f, knobPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_MOVE -> {
                val top = 12f
                val bottom = height - 12f
                val center = (top + bottom) / 2f
                val range = (bottom - top) / 2f
                value = ((center - event.y.coerceIn(top, bottom)) / range) * 12f
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_UP -> {
                performClick()
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
