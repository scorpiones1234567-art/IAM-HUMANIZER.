
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

    private lateinit var volumeSlider: SeekBar
    private lateinit var claritySlider: SeekBar
    private lateinit var volumeValue: TextView
    private lateinit var clarityValue: TextView
    private lateinit var analysisText: TextView

    private lateinit var mainScreen: View
    private lateinit var exportNameInput: EditText
    private lateinit var exportNamePreview: TextView

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

    private data class AudioStats(
        val rmsDb: Float,
        val peakDb: Float,
        val lowPercent: Float,
        val midPercent: Float,
        val highPercent: Float
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

    private fun addSection(
        parent: LinearLayout,
        title: String
    ) {
        parent.addView(label(title, 18f).apply {
            setTextColor(cyan)
            paint.isFakeBoldText = true
        })
    }

    private fun buildInterface() {

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))

            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(
                    0xFF100923.toInt(),
                    0xFF251047.toInt(),
                    0xFF10172D.toInt()
                )
            )
        }

        val scroll = ScrollView(this)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(4), dp(8), dp(4), dp(20))
        }

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

        content.addView(label("SMART AUDIO ENGINE", 15f).apply {
            setTextColor(cyan)
            paint.isFakeBoldText = true
        })

        content.addView(label(
            "Estudio de audio inteligente · Procesamiento local", 12f
        ))

        content.addView(label(
            "Mejora tu sonido y dale prioridad a la voz.", 13f
        ))

        status = label("Selecciona una canción para comenzar.").apply {
            setTextColor(gold)
        }

        content.addView(status)

        progress = ProgressBar(this).apply {
            visibility = View.GONE
        }

        content.addView(progress)

        selectButton = Button(this).apply {
            text = "♫  SELECCIONAR CANCIÓN"
            buttonStyle(this, 0xFF7438D9.toInt())

            setOnClickListener {
                audioPicker.launch(arrayOf("audio/*"))
            }
        }

        content.addView(selectButton, params())

        // ECUALIZADOR

        addSection(content, "ECUALIZADOR DE 10 BANDAS")

        content.addView(label(
            "Ajusta graves, medios y agudos mientras escuchas.", 12f
        ))

        presetSpinner = Spinner(this)

        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            presets.keys.toList()
        )

        adapter.setDropDownViewResource(
            android.R.layout.simple_spinner_dropdown_item
        )

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

            val valueLabel = label("0", 11f).apply {
                setTextColor(gold)
            }

            val fader = VerticalFader(this).apply {
                accentColor = when (index % 3) {
                    0 -> cyan
                    1 -> pink
                    else -> purple
                }
            }

            column.addView(valueLabel)

            column.addView(
                fader,
                LinearLayout.LayoutParams(dp(28), dp(165))
            )

            column.addView(label(frequency, 10f))

            fader.onValueChanged = {
                valueLabel.text = it.roundToInt().toString()
                updateEngine()
            }

            list.add(fader)

            row.addView(
                column,
                LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                )
            )
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

                    faders.forEachIndexed { i, fader ->
                        fader.value = values[i]
                    }

                    updateEngine()
                }
            }

        // ASISTENTE DE ESTILO

        val assistantButton = Button(this).apply {
            text = "✦  AYUDARME A ELEGIR ESTILO"
            buttonStyle(this, 0xFFB02FAD.toInt())

            setOnClickListener {
                recommendPreset()
            }
        }

        content.addView(assistantButton, params())

        content.addView(label(
            "La recomendación analiza características básicas del sonido. " +
                "Es orientativa: no reconoce géneros con precisión ni separa la voz.",
            11f
        ).apply {
            setTextColor(0xFFD8C8F5.toInt())
        })

        // CONTROLES INTELIGENTES

        addSection(content, "SMART AUDIO CONTROLS")

        content.addView(label(
            "VOLUMEN DE SALIDA", 14f
        ).apply {
            setTextColor(gold)
        })

        volumeValue = label("100%", 20f).apply {
            setTextColor(cyan)
            paint.isFakeBoldText = true
        }

        content.addView(volumeValue)

        volumeSlider = SeekBar(this).apply {
            max = 120
            progress = 100
            progressTintList = android.content.res.ColorStateList.valueOf(cyan)
            thumbTintList = android.content.res.ColorStateList.valueOf(cyan)

            setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {

                    override fun onProgressChanged(
                        seekBar: SeekBar?,
                        value: Int,
                        fromUser: Boolean
                    ) {
                        volumeValue.text = "$value%"
                        updateEngine()
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}

                    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                }
            )
        }

        content.addView(volumeSlider, params())

        content.addView(label(
            "Puedes aumentar el volumen hasta 120%. El limitador ayuda a controlar los picos.",
            11f
        ).apply {
            setTextColor(0xFFD8C8F5.toInt())
        })

        content.addView(label(
            "CLARIDAD VOCAL", 14f
        ).apply {
            setTextColor(gold)
        })

        clarityValue = label("0%", 20f).apply {
            setTextColor(pink)
            paint.isFakeBoldText = true
        }

        content.addView(clarityValue)

        claritySlider = SeekBar(this).apply {
            max = 100
            progress = 0
            progressTintList = android.content.res.ColorStateList.valueOf(pink)
            thumbTintList = android.content.res.ColorStateList.valueOf(pink)

            setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {

                    override fun onProgressChanged(
                        seekBar: SeekBar?,
                        value: Int,
                        fromUser: Boolean
                    ) {
                        clarityValue.text = "$value%"
                        updateEngine()
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}

                    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                }
            )
        }

        content.addView(claritySlider, params())

        content.addView(label(
            "Aumenta la presencia de frecuencias que ayudan a destacar la voz. " +
                "No separa voces de instrumentos.",
            11f
        ).apply {
            setTextColor(0xFFD8C8F5.toInt())
        })

        // ANALIZADOR

        addSection(content, "ANALIZADOR DE AUDIO")

        analysisText = label(
            "RMS: -- dB\n" +
                "PICO: -- dB\n\n" +
                "GRAVES: --%\n" +
                "MEDIOS: --%\n" +
                "AGUDOS: --%",
            14f
        ).apply {
            gravity = Gravity.CENTER
            setTextColor(gold)
            setPadding(dp(12), dp(12), dp(12), dp(12))

            background = GradientDrawable().apply {
                setColor(0xFF211438.toInt())
                cornerRadius = dp(15).toFloat()
                setStroke(dp(1), purple)
            }
        }

        content.addView(analysisText, params())

        content.addView(label(
            "El análisis muestra la energía aproximada de cada zona de frecuencia.",
            11f
        ).apply {
            setTextColor(0xFFD8C8F5.toInt())
        })

        // INTERRUPTORES

        bassSwitch = Switch(this).apply {
            text = "Refuerzo de graves"
            setTextColor(white)

            setOnCheckedChangeListener { _, _ ->
                updateEngine()
            }
        }

        reverbSwitch = Switch(this).apply {
            text = "Reverberación"
            setTextColor(white)

            setOnCheckedChangeListener { _, _ ->
                updateEngine()
            }
        }

        stereoSwitch = Switch(this).apply {
            text = "Amplitud estéreo"
            setTextColor(white)

            setOnCheckedChangeListener { _, _ ->
                updateEngine()
            }
        }

        content.addView(bassSwitch)
        content.addView(reverbSwitch)
        content.addView(stereoSwitch)

        content.addView(label(
            "Los cambios se aplican durante la reproducción.", 12f
        ).apply {
            setTextColor(cyan)
        })

        // REPRODUCCIÓN

        playButton = Button(this).apply {
            text = "▶  REPRODUCIR EN VIVO"
            isEnabled = false
            buttonStyle(this, 0xFF168A9D.toInt())

            setOnClickListener {
                togglePlayback()
            }
        }

        content.addView(playButton, params())

        // EXPORTACIÓN

        exportButton = Button(this).apply {
            text = "↓  EXPORTAR CANCIÓN WAV"
            isEnabled = false
            buttonStyle(this, 0xFF8C3FC7.toInt())

            setOnClickListener {
                exportAudio()
            }
        }

        content.addView(exportButton, params())

        content.addView(label(
            "I-A-M · SMART AUDIO ENGINE · Procesamiento local", 11f
        ).apply {
            setTextColor(gold)
        })

        scroll.addView(content)

        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        mainScreen = root
        setContentView(mainScreen)
    }

    // ------------------------------------------------
    // PANTALLA DE EXPORTACIÓN
    // ------------------------------------------------

    private fun showExportPage() {

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(24), dp(22), dp(24))

            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(
                    0xFF100923.toInt(),
                    0xFF35105C.toInt(),
                    0xFF10172D.toInt()
                )
            )
        }

        val scroll = ScrollView(this)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val logo = ImageView(this).apply {
            setImageResource(R.drawable.iam_logo)
            contentDescription = "Escorpión dorado I-A-M"
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
        }

        content.addView(
            logo,
            LinearLayout.LayoutParams(dp(115), dp(115))
        )

        content.addView(label("I-A-M", 34f).apply {
            setTextColor(gold)
            paint.isFakeBoldText = true
        })

        content.addView(label("EXPORTAR CANCIÓN", 22f).apply {
            setTextColor(cyan)
            paint.isFakeBoldText = true
        })

        content.addView(label("Tu canción está preparada.", 15f))
        content.addView(label("Escribe el nombre que quieres ponerle.", 14f))

        content.addView(label(
            "NOMBRE DE LA CANCIÓN", 13f
        ).apply {
            setTextColor(gold)
        })

        exportNameInput = EditText(this).apply {
            setSingleLine(true)
            textSize = 17f
            setTextColor(white)
            setHintTextColor(0xFFBBA9D8.toInt())
            hint = "Mi canción"
            setPadding(dp(14), dp(12), dp(14), dp(12))

            background = GradientDrawable().apply {
                setColor(0xFF281A43.toInt())
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), purple)
            }
        }

        content.addView(exportNameInput, params())

        content.addView(label(
            "NOMBRE DEL ARCHIVO FINAL", 13f
        ).apply {
            setTextColor(cyan)
        })

        exportNamePreview = label(
            "Mi canción-I-A-M.wav", 18f
        ).apply {
            setTextColor(gold)
            paint.isFakeBoldText = true
        }

        content.addView(exportNamePreview, params())

        exportNameInput.addTextChangedListener(
            object : android.text.TextWatcher {

                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int
                ) {}

                override fun onTextChanged(
                    s: CharSequence?,
                    start: Int,
                    before: Int,
                    count: Int
                ) {
                    exportNamePreview.text =
                        makeExportFileName(s?.toString().orEmpty())
                }

                override fun afterTextChanged(
                    s: android.text.Editable?
                ) {}
            }
        )

        content.addView(label("Formato de audio: WAV", 14f))

        content.addView(label(
            "La marca I-A-M se agrega automáticamente.", 12f
        ).apply {
            setTextColor(0xFFD8C8F5.toInt())
        })

        val saveButton = Button(this).apply {
            text = "↓  EXPORTAR WAV"
            buttonStyle(this, 0xFF8C3FC7.toInt())

            setOnClickListener {
                if (pendingWav == null) {
                    message("No hay una canción preparada.")
                } else {
                    savePicker.launch(
                        makeExportFileName(
                            exportNameInput.text.toString()
                        )
                    )
                }
            }
        }

        content.addView(saveButton, params())

        val backButton = Button(this).apply {
            text = "←  REGRESAR AL ECUALIZADOR"
            buttonStyle(this, 0xFF168A9D.toInt())

            setOnClickListener {
                setContentView(mainScreen)
                status.text = "Puedes continuar ajustando tu canción."
            }
        }

        content.addView(backButton, params())

        scroll.addView(content)

        page.addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        setContentView(page)
    }

    private fun makeExportFileName(rawName: String): String {

        var name = rawName.trim()

        name = name.replace(
            Regex("[\\\\/:*?\"<>|]"),
            "_"
        )

        name = name.trim('.', ' ')

        if (name.lowercase().endsWith(".wav")) {
            name = name.dropLast(4)
        }

        name = name.replace(
            Regex("(?i)(-I-A-M|_I-A-M| I-A-M)$"),
            ""
        ).trim()

        if (name.isBlank()) {
            name = "Cancion"
        }

        return "$name-I-A-M.wav"
    }

    // ------------------------------------------------
    // CONTROLES DEL MOTOR
    // ------------------------------------------------

    private fun gains(): FloatArray =
        faders.map { it.value }.toFloatArray()

    private fun updateEngine() {

        val current = engine ?: return

        current.setGains(gains())

        current.bassEnabled = bassSwitch.isChecked
        current.reverbEnabled = reverbSwitch.isChecked
        current.stereoEnabled = stereoSwitch.isChecked

        current.setVolume(volumeSlider.progress.toFloat())

        current.setVocalClarityLevel(
            claritySlider.progress.toFloat()
        )
    }

    // ------------------------------------------------
    // ANALIZADOR BÁSICO
    // ------------------------------------------------

    private fun analyzeAudio(
        samples: ShortArray,
        channelCount: Int,
        sampleRate: Int
    ): AudioStats {

        if (samples.isEmpty() || channelCount !in 1..2) {
            return AudioStats(-120f, -120f, 0f, 0f, 0f)
        }

        val totalFrames = samples.size / channelCount

        if (totalFrames <= 0) {
            return AudioStats(-120f, -120f, 0f, 0f, 0f)
        }

        val stride = max(1, totalFrames / 300000)

        val lowAlpha = 1.0 - exp(-2.0 * PI * 250.0 / sampleRate)
        val highAlpha = 1.0 - exp(-2.0 * PI * 4000.0 / sampleRate)

        var lowState = 0.0
        var highState = 0.0

        var totalEnergy = 0.0
        var lowEnergy = 0.0
        var midEnergy = 0.0
        var highEnergy = 0.0

        var peak = 0.0
        var count = 0

        for (frame in 0 until totalFrames step stride) {

            val index = frame * channelCount

            var value = 0.0

            for (channel in 0 until channelCount) {
                value += samples[index + channel].toDouble() / 32768.0
            }

            value /= channelCount

            val absolute = abs(value)
            peak = max(peak, absolute)

            lowState += lowAlpha * (value - lowState)
            highState += highAlpha * (value - highState)

            val low = lowState
            val high = highState - lowState
            val top = value - highState

            totalEnergy += value * value
            lowEnergy += low * low
            midEnergy += high * high
            highEnergy += top * top

            count++
        }

        val rms = sqrt(totalEnergy / count.coerceAtLeast(1))

        fun db(value: Double): Float {
            return (20.0 * log10(value.coerceAtLeast(0.000001)))
                .toFloat()
                .coerceIn(-120f, 0f)
        }

        val sum = lowEnergy + midEnergy + highEnergy

        val lowPercent = if (sum > 0.0) {
            (lowEnergy / sum * 100.0).toFloat()
        } else 0f

        val midPercent = if (sum > 0.0) {
            (midEnergy / sum * 100.0).toFloat()
        } else 0f

        val highPercent = if (sum > 0.0) {
            (highEnergy / sum * 100.0).toFloat()
        } else 0f

        return AudioStats(
            db(rms),
            db(peak),
            lowPercent,
            midPercent,
            highPercent
        )
    }

    private fun showAnalysis(stats: AudioStats) {

        analysisText.text =
            "RMS: ${"%.1f".format(stats.rmsDb)} dB\n" +
            "PICO: ${"%.1f".format(stats.peakDb)} dB\n\n" +
            "GRAVES: ${stats.lowPercent.roundToInt()}%\n" +
            "MEDIOS: ${stats.midPercent.roundToInt()}%\n" +
            "AGUDOS: ${stats.highPercent.roundToInt()}%"
    }

    // ------------------------------------------------
    // RECOMENDACIÓN DE ESTILO
    // ------------------------------------------------

    private fun recommendPreset() {

        val samples = audio

        if (samples == null) {
            message("Primero selecciona una canción.")
            return
        }

        val stats = analyzeAudio(samples, channels, rate)

        val recommendation = when {
            stats.lowPercent > 48f -> "Hip Hop"
            stats.highPercent > 42f -> "Acústica"
            stats.midPercent > 45f -> "VOZ CLARA"
            else -> "Pop"
        }

        val position = presets.keys.indexOf(recommendation)

        if (position >= 0) {
            presetSpinner.setSelection(position)
        }

        status.text = "Sugerencia orientativa: $recommendation"

        message(
            "I-A-M recomienda $recommendation. " +
                "Escucha el resultado y decide."
        )
    }

    // ------------------------------------------------
    // REPRODUCCIÓN EN VIVO
    // ------------------------------------------------

    private fun togglePlayback() {

        val samples = audio
            ?: return message("Selecciona una canción primero.")

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

            newEngine.setVolume(volumeSlider.progress.toFloat())

            newEngine.setVocalClarityLevel(
                claritySlider.progress.toFloat()
            )

            engine = newEngine

            newEngine.start()

            playButton.text = "■  DETENER REPRODUCCIÓN"
            status.text = "Reproduciendo. Ajusta los controles en vivo."

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

    // ------------------------------------------------
    // CARGA DE AUDIO
    // ------------------------------------------------

    private fun loadAudio(uri: Uri) {

        stopPlayback()

        audio = null

        selectButton.isEnabled = false
        playButton.isEnabled = false
        exportButton.isEnabled = false

        progress.visibility = View.VISIBLE
        status.text = "Cargando y analizando canción..."

        Thread {

            try {

                val decoded = decode(uri)

                val stats = analyzeAudio(
                    decoded.samples,
                    decoded.channelCount,
                    decoded.sampleRate
                )

                handler.post {

                    audio = decoded.samples
                    rate = decoded.sampleRate
                    channels = decoded.channelCount

                    showAnalysis(stats)

                    progress.visibility = View.GONE

                    selectButton.isEnabled = true
                    playButton.isEnabled = true
                    exportButton.isEnabled = true

                    status.text = "Canción lista. Análisis completado."

                    message("Audio cargado y analizado correctamente.")
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

    // ------------------------------------------------
    // DECODIFICADOR
    // ------------------------------------------------

    private fun decode(uri: Uri): Decoded {

        val extractor = MediaExtractor()
        var codec: MediaCodec? = null

        try {

            extractor.setDataSource(this, uri, null)

            var track = -1
            var format: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {

                val candidate = extractor.getTrackFormat(i)

                if ((candidate.getString(MediaFormat.KEY_MIME) ?: "")
                    .startsWith("audio/")
                ) {
                    track = i
                    format = candidate
                    break
                }
            }

            if (track < 0 || format == null) {
                throw Exception("No se encontró audio.")
            }

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

                            codec.queueInputBuffer(
                                index,
                                0,
                                0,
                                0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )

                            inputDone = true

                        } else {

                            codec.queueInputBuffer(
                                index,
                                0,
                                size,
                                extractor.sampleTime,
                                0
                            )

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

                            if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                                sr = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            }

                            if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                                ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            }

                            if (fmt.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                                encoding = fmt.getInteger(MediaFormat.KEY_PCM_ENCODING)
                            }

                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)

                            val chunk = ByteArray(info.size)

                            buffer.get(chunk)
                            output.write(chunk)

                            if (output.size() > 100 * 1024 * 1024) {
                                throw Exception(
                                    "Audio demasiado grande: límite 100 MB."
                                )
                            }
                        }

                        val ended =
                            info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0

                        codec.releaseOutputBuffer(outIndex, false)

                        if (ended) {
                            outputDone = true
                        }
                    }

                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {

                        val fmt = codec.outputFormat

                        sr = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

                        if (fmt.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            encoding = fmt.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }
                    }
                }
            }

            if (ch !in 1..2 || sr !in 8000..192000) {
                throw Exception("Formato de audio no compatible.")
            }

            val bytes = output.toByteArray()

            val buffer = ByteBuffer.wrap(bytes)
                .order(ByteOrder.nativeOrder())

            val samples = when (encoding) {

                AudioFormat.ENCODING_PCM_16BIT -> {

                    if (bytes.size % 2 != 0) {
                        throw Exception("Audio PCM incompleto.")
                    }

                    ShortArray(bytes.size / 2) {
                        buffer.short
                    }
                }

                AudioFormat.ENCODING_PCM_FLOAT -> {

                    if (bytes.size % 4 != 0) {
                        throw Exception("Audio PCM incompleto.")
                    }

                    ShortArray(bytes.size / 4) {
                        (buffer.float.coerceIn(-1f, 1f) * 32767f)
                            .toInt().toShort()
                    }
                }

                AudioFormat.ENCODING_PCM_8BIT -> {

                    ShortArray(bytes.size) {
                        (((bytes[it].toInt() and 255) - 128) shl 8)
                            .toShort()
                    }
                }

                else -> throw Exception("PCM no compatible.")
            }

            if (samples.isEmpty() || samples.size % ch != 0) {
                throw Exception("La canción no contiene audio válido.")
            }

            return Decoded(samples, sr, ch)

        } finally {

            try {
                codec?.stop()
            } catch (_: Exception) {}

            try {
                codec?.release()
            } catch (_: Exception) {}

            extractor.release()
        }
    }

    // ------------------------------------------------
    // FILTROS DIGITALES
    // ------------------------------------------------

    private data class Filter(
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

    private fun makeFilter(
        freq: Double,
        db: Double,
        sr: Double
    ): Filter? {

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

    // ------------------------------------------------
    // PROCESAMIENTO PARA EXPORTACIÓN
    // ------------------------------------------------

    private fun applyEffects(
        input: ShortArray,
        sr: Int,
        ch: Int,
        values: FloatArray,
        bass: Boolean,
        reverb: Boolean,
        stereo: Boolean,
        volumePercent: Int,
        clarityPercent: Int
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
            Array(ch) {
                listOfNotNull(
                    makeFilter(90.0, 4.0, sr.toDouble())
                )
            }
        } else null

        val clarity = clarityPercent.coerceIn(0, 100) / 100.0

        val clarityFilters = Array(ch) {
            listOfNotNull(
                makeFilter(2800.0, 3.5 * clarity, sr.toDouble()),
                makeFilter(5200.0, 1.5 * clarity, sr.toDouble())
            )
        }

        val delayFrames = (sr * 0.22).toInt().coerceAtLeast(1)

        val delay = if (reverb) {
            DoubleArray(delayFrames * ch)
        } else null

        var delayPos = 0

        val output = ShortArray(input.size)

        val volume = volumePercent.coerceIn(0, 120) / 100.0

        var limiterEnvelope = 0.0

        val attack = 0.01
        val release = 0.0005

        for (frame in input.indices step ch) {

            var left = input[frame].toDouble() / 32768.0

            var right = if (ch == 2) {
                input[frame + 1].toDouble() / 32768.0
            } else {
                left
            }

            for (filter in filters[0]) {
                left = filter.process(left)
            }

            bassFilters?.get(0)?.forEach {
                left = it.process(left)
            }

            clarityFilters[0].forEach {
                left = it.process(left)
            }

            if (ch == 2) {

                for (filter in filters[1]) {
                    right = filter.process(right)
                }

                bassFilters?.get(1)?.forEach {
                    right = it.process(right)
                }

                clarityFilters[1].forEach {
                    right = it.process(right)
                }
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

            left *= volume
            right *= volume

            val peak = max(abs(left), abs(right))

            if (peak > limiterEnvelope) {
                limiterEnvelope += (peak - limiterEnvelope) * attack
            } else {
                limiterEnvelope += (peak - limiterEnvelope) * release
            }

            val limiterGain = if (limiterEnvelope > 0.94) {
                0.94 / limiterEnvelope
            } else {
                1.0
            }

            left *= limiterGain
            right *= limiterGain

            output[frame] =
                (left * 32767.0).roundToInt()
                    .coerceIn(-32768, 32767).toShort()

            if (ch == 2) {
                output[frame + 1] =
                    (right * 32767.0).roundToInt()
                        .coerceIn(-32768, 32767).toShort()
            }
        }

        return output
    }

    // ------------------------------------------------
    // CREAR WAV
    // ------------------------------------------------

    private fun wav(
        samples: ShortArray,
        sr: Int,
        ch: Int
    ): ByteArray {

        val size = samples.size * 2

        val b = ByteBuffer.allocate(44 + size)
            .order(ByteOrder.LITTLE_ENDIAN)

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

        samples.forEach {
            b.putShort(it)
        }

        return b.array()
    }

    // ------------------------------------------------
    // EXPORTACIÓN
    // ------------------------------------------------

    private fun exportAudio() {

        val samples = audio ?: return

        val values = gains()
        val sr = rate
        val ch = channels

        val bass = bassSwitch.isChecked
        val reverb = reverbSwitch.isChecked
        val stereo = stereoSwitch.isChecked

        val volume = volumeSlider.progress
        val clarity = claritySlider.progress

        exportButton.isEnabled = false
        progress.visibility = View.VISIBLE

        status.text = "I-A-M está procesando tu canción..."

        Thread {

            try {

                val result = applyEffects(
                    samples,
                    sr,
                    ch,
                    values,
                    bass,
                    reverb,
                    stereo,
                    volume,
                    clarity
                )

                val resultWav = wav(result, sr, ch)

                handler.post {

                    pendingWav = resultWav

                    progress.visibility = View.GONE
                    exportButton.isEnabled = true

                    status.text = "Canción preparada."

                    showExportPage()
                }

            } catch (e: Exception) {

                handler.post {

                    progress.visibility = View.GONE
                    exportButton.isEnabled = true

                    message(
                        e.message ?: "Error preparando WAV."
                    )
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

            message("¡Canción guardada correctamente con el nombre I-A-M!")

            setContentView(mainScreen)

            status.text = "WAV exportado correctamente."

        } catch (e: Exception) {

            message(e.message ?: "Error guardando WAV.")
        }
    }

    private fun message(text: String) {

        Toast.makeText(
            this,
            text,
            Toast.LENGTH_LONG
        ).show()
    }

    override fun onDestroy() {

        handler.removeCallbacksAndMessages(null)

        stopPlayback()

        super.onDestroy()
    }
}

// ------------------------------------------------
// CONTROL VERTICAL DEL ECUALIZADOR
// ------------------------------------------------

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

                value =
                    ((center - event.y.coerceIn(top, bottom)) / range) * 12f

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
