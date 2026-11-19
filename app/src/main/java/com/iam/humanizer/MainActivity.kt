
package com.iam.humanizer

import android.graphics.Canvas
import android.graphics.Paint
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaPlayer
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
    private lateinit var exportButton: Button
    private lateinit var progress: ProgressBar
    private lateinit var presetSpinner: Spinner
    private lateinit var bassSwitch: Switch
    private lateinit var reverbSwitch: Switch
    private lateinit var stereoSwitch: Switch
    private lateinit var faders: List<VerticalFader>

    private val frequencies = listOf(
        "31", "62", "125", "250", "500",
        "1k", "2k", "4k", "8k", "16k"
    )

    private val presets = linkedMapOf(
        "Personalizado" to floatArrayOf(0f,0f,0f,0f,0f,0f,0f,0f,0f,0f),
        "Acústica" to floatArrayOf(2f,2f,1f,0f,1f,2f,2f,1f,1f,1f),
        "Pop" to floatArrayOf(-1f,0f,1f,2f,1f,0f,1f,2f,2f,1f),
        "Rock" to floatArrayOf(3f,2f,-1f,-2f,0f,1f,2f,3f,3f,2f),
        "Hip Hop" to floatArrayOf(4f,4f,2f,0f,-1f,0f,1f,1f,2f,1f),
        "Jazz" to floatArrayOf(2f,1f,0f,1f,-1f,-1f,0f,1f,2f,2f),
        "Heavy Metal" to floatArrayOf(3f,2f,1f,-1f,1f,2f,3f,2f,1f,2f),
        "Clásica" to floatArrayOf(2f,1f,0f,0f,0f,1f,1f,2f,2f,2f)
    )

    private var processedAudio: ByteArray? = null
    private var outputSampleRate = 44100
    private var outputChannels = 2
    private var player: MediaPlayer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val audioPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) processAudio(uri)
        }

    private val savePicker =
        registerForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { uri ->
            if (uri != null) saveWav(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createInterface()
    }

    private fun createInterface() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18,24,18,24)
            setBackgroundColor(0xFF101116.toInt())
        }

        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        fun label(text: String, size: Float = 15f): TextView {
            return TextView(this).apply {
                this.text = text
                textSize = size
                gravity = Gravity.CENTER
                setTextColor(0xFFF3F3F3.toInt())
                setPadding(4,10,4,10)
            }
        }

        content.addView(label("I.A.M HUMANIZER",27f))
        content.addView(label("ESTUDIO DE AUDIO · ECUALIZADOR PRO",13f))

        status = label("Selecciona una canción para comenzar.")
        content.addView(status)

        progress = ProgressBar(this).apply { visibility = View.GONE }
        content.addView(progress)

        selectButton = Button(this).apply {
            text = "SELECCIONAR CANCIÓN"
            setOnClickListener { audioPicker.launch(arrayOf("audio/*")) }
        }
        content.addView(selectButton,matchParams())

        content.addView(label("ECUALIZADOR DE 10 BANDAS",18f))

        presetSpinner = Spinner(this)
        val presetAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            presets.keys.toList()
        )
        presetAdapter.setDropDownViewResource(
            android.R.layout.simple_spinner_dropdown_item
        )
        presetSpinner.adapter = presetAdapter
        content.addView(presetSpinner,matchParams())

        val sliderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val sliders = mutableListOf<VerticalFader>()

        for (frequency in frequencies) {
            val column = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(2,4,2,4)
            }

            val valueLabel = label("0",11f)
            val fader = VerticalFader(this)

            column.addView(valueLabel)
            column.addView(
                fader,
                LinearLayout.LayoutParams(dp(28),dp(170))
            )
            column.addView(label(frequency,10f))

            fader.onValueChanged = { value ->
                valueLabel.text = value.toInt().toString()
                processedAudio = null
            }

            sliders.add(fader)
            sliderRow.addView(
                column,
                LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                )
            )
        }

        faders = sliders
        content.addView(sliderRow)

        presetSpinner.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}

                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    val values = presets.values.elementAt(position)
                    faders.forEachIndexed { index, fader ->
                        fader.value = values[index]
                    }
                    processedAudio = null
                }
            }

        bassSwitch = Switch(this).apply {
            text = "Refuerzo de graves"
            setTextColor(0xFFFFFFFF.toInt())
        }

        reverbSwitch = Switch(this).apply {
            text = "Reverberación"
            setTextColor(0xFFFFFFFF.toInt())
        }

        stereoSwitch = Switch(this).apply {
            text = "Amplitud estéreo"
            setTextColor(0xFFFFFFFF.toInt())
        }

        content.addView(bassSwitch)
        content.addView(reverbSwitch)
        content.addView(stereoSwitch)
        content.addView(label("Los efectos se aplican al exportar el audio.",12f))

        val previewButton = Button(this).apply {
            text = "ESCUCHAR RESULTADO"
            setOnClickListener {
                val audio = processedAudio
                if (audio == null) {
                    message("Primero procesa una canción.")
                } else {
                    previewAudio(audio)
                }
            }
        }
        content.addView(previewButton,matchParams())

        exportButton = Button(this).apply {
            text = "EXPORTAR WAV"
            isEnabled = false
            setOnClickListener {
                if (processedAudio != null) {
                    savePicker.launch("IAM_HUMANIZER.wav")
                }
            }
        }
        content.addView(exportButton,matchParams())
        content.addView(label("I.A.M · Procesamiento local en tu teléfono",11f))

        scroll.addView(content)
        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )
        setContentView(root)
    }

    private fun matchParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = dp(10) }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun processAudio(uri: Uri) {
        stopPreview()
        selectButton.isEnabled = false
        exportButton.isEnabled = false
        progress.visibility = View.VISIBLE
        status.text = "Decodificando y aplicando efectos..."

        val gains = faders.map { it.value }.toFloatArray()
        val bass = bassSwitch.isChecked
        val reverb = reverbSwitch.isChecked
        val stereo = stereoSwitch.isChecked

        Thread {
            try {
                val decoded = decodeAudio(uri)
                if (decoded.isEmpty()) {
                    throw Exception("El archivo no contiene audio.")
                }

                val result = applyEffects(
                    decoded,
                    outputSampleRate,
                    outputChannels,
                    gains,
                    bass,
                    reverb,
                    stereo
                )

                val wav = pcmToWav(result,outputSampleRate,outputChannels)

                mainHandler.post {
                    processedAudio = wav
                    progress.visibility = View.GONE
                    selectButton.isEnabled = true
                    exportButton.isEnabled = true
                    status.text = "¡Audio procesado! Ya puedes escucharlo o exportarlo."
                    message("Procesamiento terminado.")
                }
            } catch (e: Exception) {
                mainHandler.post {
                    progress.visibility = View.GONE
                    selectButton.isEnabled = true
                    status.text = "No se pudo procesar el audio."
                    message(e.message ?: "Error de procesamiento.")
                }
            }
        }.start()
    }

    private fun decodeAudio(uri: Uri): ShortArray {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null

        try {
            extractor.setDataSource(this,uri,null)

            var track = -1
            var format: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(i)
                val mime = candidate.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    track = i
                    format = candidate
                    break
                }
            }

            if (track < 0 || format == null) {
                throw Exception("No se encontró una pista de audio.")
            }

            extractor.selectTrack(track)

            val mime = format.getString(MediaFormat.KEY_MIME)
                ?: throw Exception("Formato de audio desconocido.")

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format,null,null,0)
            codec.start()

            val output = ByteArrayOutputStream()
            val info = MediaCodec.BufferInfo()

            var inputFinished = false
            var outputFinished = false
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = 2

            while (!outputFinished) {
                if (!inputFinished) {
                    val inputIndex = codec.dequeueInputBuffer(10000)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)
                            ?: throw Exception("Error leyendo el audio.")

                        val size = extractor.readSampleData(inputBuffer,0)

                        if (size < 0) {
                            codec.queueInputBuffer(
                                inputIndex,0,0,0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputFinished = true
                        } else {
                            codec.queueInputBuffer(
                                inputIndex,0,size,extractor.sampleTime,0
                            )
                            extractor.advance()
                        }
                    }
                }

                val outputIndex = codec.dequeueOutputBuffer(info,10000)

                when {
                    outputIndex >= 0 -> {
                        val buffer = codec.getOutputBuffer(outputIndex)

                        if (buffer != null && info.size > 0) {
                            val outFormat = codec.outputFormat

                            if (outFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                                sampleRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            }
                            if (outFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                                channels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            }
                            if (outFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                                encoding = outFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                            }

                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)

                            val chunk = ByteArray(info.size)
                            buffer.get(chunk)
                            output.write(chunk)

                            if (output.size() > 100 * 1024 * 1024) {
                                throw Exception("El audio supera el límite de memoria de 100 MB.")
                            }
                        }

                        val ended =
                            info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0

                        codec.releaseOutputBuffer(outputIndex,false)
                        if (ended) outputFinished = true
                    }

                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val newFormat = codec.outputFormat
                        sampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (newFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            encoding = newFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }
                    }
                }
            }

            if (channels !in 1..2) {
                throw Exception("Esta versión admite audio mono o estéreo.")
            }
            if (sampleRate !in 8000..192000) {
                throw Exception("Frecuencia de audio no compatible.")
            }

            outputSampleRate = sampleRate
            outputChannels = channels

            return convertToPcm16(output.toByteArray(),encoding)
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
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
                    (buffer.float.coerceIn(-1f,1f) * 32767f).toInt().toShort()
                }
            }
            3 -> ShortArray(data.size) {
                (((data[it].toInt() and 255) - 128) shl 8).toShort()
            }
            else -> throw Exception("Formato PCM no compatible.")
        }
    }

    private data class Biquad(
        val b0: Double,
        val b1: Double,
        val b2: Double,
        val a1: Double,
        val a2: Double
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

    private fun makePeaking(
        frequency: Double,
        gainDb: Double,
        sampleRate: Double
    ): Biquad? {
        val freq = frequency.coerceIn(20.0,sampleRate*0.45)
        if (freq >= sampleRate/2.0) return null

        val a = 10.0.pow(gainDb/40.0)
        val w = 2.0*PI*freq/sampleRate
        val alpha = sin(w)/1.2
        val cosW = cos(w)
        val a0 = 1.0 + alpha/a

        return Biquad(
            (1.0+alpha*a)/a0,
            (-2.0*cosW)/a0,
            (1.0-alpha*a)/a0,
            (-2.0*cosW)/a0,
            (1.0-alpha/a)/a0
        )
    }

    // FUNCIÓN CORREGIDA: filtros con tipos explícitos y sin fold ambiguo.
    private fun applyEffects(
        input: ShortArray,
        sampleRate: Int,
        channels: Int,
        gains: FloatArray,
        bass: Boolean,
        reverb: Boolean,
        stereo: Boolean
    ): ShortArray {

        val frequenciesHz = doubleArrayOf(
            31.0,62.0,125.0,250.0,500.0,
            1000.0,2000.0,4000.0,8000.0,16000.0
        )

        val filters: Array<MutableList<Biquad>> = Array(channels) {
            val channelFilters = mutableListOf<Biquad>()

            for (index in frequenciesHz.indices) {
                val filter: Biquad? = makePeaking(
                    frequenciesHz[index],
                    gains[index].toDouble(),
                    sampleRate.toDouble()
                )
                if (filter != null) channelFilters.add(filter)
            }
            channelFilters
        }

        val bassFilters: Array<MutableList<Biquad>>? = if (bass) {
            Array(channels) {
                val list = mutableListOf<Biquad>()
                val filter: Biquad? =
                    makePeaking(90.0,4.0,sampleRate.toDouble())
                if (filter != null) list.add(filter)
                list
            }
        } else null

        val delayFrames = (sampleRate*0.22).toInt().coerceAtLeast(1)
        val delay = if (reverb) DoubleArray(delayFrames*channels) else null
        var delayPosition = 0

        val output = ShortArray(input.size)
        val frameCount = input.size/channels

        for (frame in 0 until frameCount) {
            var left = input[frame*channels].toDouble()/32768.0
            var right = if (channels == 2) {
                input[frame*channels+1].toDouble()/32768.0
            } else left

            for (filter in filters[0]) {
                left = filter.process(left)
            }

            if (bassFilters != null) {
                for (filter in bassFilters[0]) {
                    left = filter.process(left)
                }
            }

            if (channels == 2) {
                for (filter in filters[1]) {
                    right = filter.process(right)
                }

                if (bassFilters != null) {
                    for (filter in bassFilters[1]) {
                        right = filter.process(right)
                    }
                }
            }

            if (stereo && channels == 2) {
                val mid = (left+right)*0.5
                val side = (left-right)*0.5*1.35
                left = mid+side
                right = mid-side
            }

            if (delay != null) {
                val index = delayPosition*channels
                val delayedLeft = delay[index]
                delay[index] = left+delayedLeft*0.28
                left += delayedLeft*0.22

                if (channels == 2) {
                    val delayedRight = delay[index+1]
                    delay[index+1] = right+delayedRight*0.28
                    right += delayedRight*0.22
                }

                delayPosition++
                if (delayPosition >= delayFrames) delayPosition = 0
            }

            left = left/(1.0+abs(left)*0.35)
            right = right/(1.0+abs(right)*0.35)

            output[frame*channels] =
                (left*32767.0).roundToInt().coerceIn(-32768,32767).toShort()

            if (channels == 2) {
                output[frame*channels+1] =
                    (right*32767.0).roundToInt().coerceIn(-32768,32767).toShort()
            }
        }

        return output
    }

    private fun pcmToWav(
        samples: ShortArray,
        sampleRate: Int,
        channels: Int
    ): ByteArray {
        val dataSize = samples.size*2
        val buffer = ByteBuffer.allocate(44+dataSize).order(ByteOrder.LITTLE_ENDIAN)

        buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
        buffer.putInt(36+dataSize)
        buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
        buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
        buffer.putInt(16)
        buffer.putShort(1)
        buffer.putShort(channels.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(sampleRate*channels*2)
        buffer.putShort((channels*2).toShort())
        buffer.putShort(16)
        buffer.put("data".toByteArray(Charsets.US_ASCII))
        buffer.putInt(dataSize)

        samples.forEach { buffer.putShort(it) }
        return buffer.array()
    }

    private fun previewAudio(wav: ByteArray) {
        try {
            stopPreview()
            val file = java.io.File(cacheDir,"iam_preview.wav")
            file.writeBytes(wav)

            player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnPreparedListener { it.start() }
                setOnCompletionListener { stopPreview() }
                prepareAsync()
            }
            status.text = "Preparando reproducción..."
        } catch (e: Exception) {
            message("No se pudo reproducir la vista previa.")
        }
    }

    private fun stopPreview() {
        try {
            player?.stop()
            player?.release()
        } catch (_: Exception) {
        }
        player = null
    }

    private fun saveWav(uri: Uri) {
        val audio = processedAudio ?: return

        try {
            contentResolver.openOutputStream(uri)?.use {
                it.write(audio)
                it.flush()
            } ?: throw Exception("No se pudo guardar el archivo.")

            status.text = "¡Canción exportada correctamente!"
            message("WAV guardado.")
        } catch (e: Exception) {
            message(e.message ?: "Error al guardar.")
        }
    }

    private fun message(text: String) {
        Toast.makeText(this,text,Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        stopPreview()
        super.onDestroy()
    }
}

class VerticalFader(context: android.content.Context) : View(context) {

    var value: Float = 0f
        set(newValue) {
            field = newValue.coerceIn(-12f,12f)
            invalidate()
            onValueChanged?.invoke(field)
        }

    var onValueChanged: ((Float) -> Unit)? = null

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF55565D.toInt()
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
    }

    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF8A25.toInt()
        strokeWidth = 7f
        strokeCap = Paint.Cap.ROUND
    }

    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFA13B.toInt()
        style = Paint.Style.FILL
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val centerX = width/2f
        val top = 12f
        val bottom = height-12f
        val centerY = (top+bottom)/2f
        val range = (bottom-top)/2f
        val knobY = centerY-(value/12f)*range

        canvas.drawLine(centerX,top,centerX,bottom,linePaint)
        canvas.drawLine(centerX,centerY,centerX,knobY,activePaint)
        canvas.drawCircle(centerX,knobY,10f,knobPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_MOVE -> {
                val top = 12f
                val bottom = height-12f
                val center = (top+bottom)/2f
                val range = (bottom-top)/2f
                val y = event.y.coerceIn(top,bottom)

                value = ((center-y)/range)*12f
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
