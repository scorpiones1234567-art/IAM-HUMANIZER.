package com.iam.humanizer

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

class MainActivity : ComponentActivity() {

    private lateinit var status: TextView
    private lateinit var selectButton: Button
    private lateinit var aiButton: Button
    private lateinit var playButton: Button
    private lateinit var exportButton: Button
    private lateinit var progress: ProgressBar

    private lateinit var bassSwitch: Switch
    private lateinit var reverbSwitch: Switch
    private lateinit var stereoSwitch: Switch
    private lateinit var humSwitch: Switch
    private lateinit var presetSpinner: Spinner

    private lateinit var faders: List<AudioControl>

    private lateinit var volumeSlider: SeekBar
    private lateinit var claritySlider: SeekBar
    private lateinit var volumeValue: TextView
    private lateinit var clarityValue: TextView
    private lateinit var analysisText: TextView

    private lateinit var mainScreen: View
    private lateinit var exportNameInput: EditText
    private lateinit var exportNamePreview: TextView
    private lateinit var coverStatus: TextView

    private var audio: ShortArray? = null

    // Resultado de Demucs + DeepFilterNet.
    // La salida de la tubería es PCM 16 bits, estéreo, 44.1 kHz.
    private var aiProcessedAudio: ShortArray? = null
    private var aiRate = 44100
    private var aiChannels = 2

    // Formato del audio que se está preparando para exportar.
    private var pendingExportRate = 44100
    private var pendingExportChannels = 2

    private var rate = 44100
    private var channels = 2

    private var engine: LiveAudioEngine? = null

    private val handler = Handler(Looper.getMainLooper())

    private var pendingProcessedAudio: ShortArray? = null
    private var pendingCoverJpeg: ByteArray? = null
    private var pendingCoverSave = false
    private var pendingExportTitle = "Cancion"

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
                "Cumbia · Voz" to floatArrayOf(1f,2f,1f,0f,1f,2f,3f,2f,1f,0f),
        "Cumbia" to floatArrayOf(1f,2f,2f,1f,0f,1f,1f,2f,2f,0f),
        "Reggaetón" to floatArrayOf(4f,4f,2f,0f,-2f,-1f,1f,2f,2f,1f),
        "Bachata" to floatArrayOf(0f,1f,1f,0f,-1f,0f,2f,3f,2f,1f),
        "Baladas" to floatArrayOf(0f,1f,2f,1f,0f,1f,1f,0f,1f,1f),
        "Electrónica" to floatArrayOf(3f,4f,1f,-1f,-1f,0f,1f,2f,3f,2f),
        "Menos siseo" to floatArrayOf(0f,0f,0f,0f,0f,0f,0f,0f,-2f,-3f)
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

    private val coverPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) {
            if (it != null) loadCover(it)
        }

    private val saveWavPicker =
        registerForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) {
            if (it != null) saveWav(it)
        }

    private val saveMp3Picker =
        registerForActivityResult(ActivityResultContracts.CreateDocument("audio/mpeg")) {
            if (it != null) saveMp3(it)
        }

    private val saveCoverPicker =
        registerForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg")) {
            if (it != null) saveCover(it)
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

    private fun addSection(parent: LinearLayout, title: String) {
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

        addSection(content, "ECUALIZADOR DE 10 BANDAS")

        content.addView(label(
            "Gira cada perilla para ajustar graves, medios y agudos.", 12f
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

        val knobRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val knobScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = false
            addView(
                knobRow,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        val descriptions = listOf(
            "Subgraves: profundidad y vibración",
            "Graves: cuerpo y fuerza del sonido",
            "Graves medios: peso del sonido",
            "Medios bajos: calidez",
            "Medios: cuerpo de instrumentos y voz",
            "Medios altos: definición",
            "Presencia: claridad de voz",
            "Agudos: brillo",
            "Agudos altos: detalle",
            "Aire: brillo extremo"
        )

        val controls = mutableListOf<AudioControl>()

        frequencies.forEachIndexed { index, frequency ->

            val accent = when (index % 3) {
                0 -> cyan
                1 -> pink
                else -> purple
            }

            val control = AudioControl(
                context = this,
                title = frequency,
                description = descriptions[index],
                accentColor = accent,
                minValue = -12f,
                maxValue = 12f,
                initialValue = 0f,
                suffix = " dB"
            )

            control.onValueChanged = {
                updateEngine()
            }

            controls.add(control)

            knobRow.addView(
                control,
                LinearLayout.LayoutParams(
                    dp(132),
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = dp(6)
                }
            )
        }

        faders = controls

        content.addView(
            knobScroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(10)
            }
        )

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

                    faders.forEachIndexed { index, control ->
                        control.value = values[index]
                    }

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
            "La recomendación analiza características básicas del sonido. " +
                "Es orientativa: no reconoce géneros con precisión ni separa la voz.",
            11f
        ).apply {
            setTextColor(0xFFD8C8F5.toInt())
        })

        addSection(content, "SMART AUDIO CONTROLS")

        content.addView(label("VOLUMEN DE SALIDA", 14f).apply {
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
            progressTintList =
                android.content.res.ColorStateList.valueOf(cyan)
            thumbTintList =
                android.content.res.ColorStateList.valueOf(cyan)

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

        content.addView(label("CLARIDAD VOCAL", 14f).apply {
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
            progressTintList =
                android.content.res.ColorStateList.valueOf(pink)
            thumbTintList =
                android.content.res.ColorStateList.valueOf(pink)

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

        addSection(content, "ANALIZADOR DE AUDIO")

        analysisText = label(
            "RMS: -- dB\nPICO: -- dB\n\n" +
                "GRAVES: --%\nMEDIOS: --%\nAGUDOS: --%",
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

        humSwitch = Switch(this).apply {
            text = "Eliminar zumbido eléctrico (60 Hz)"
            setTextColor(white)
            setOnCheckedChangeListener { _, _ -> updateEngine() }
        }

        content.addView(bassSwitch)
        content.addView(reverbSwitch)
        content.addView(stereoSwitch)
        content.addView(humSwitch)

        content.addView(label(
            "El filtro de zumbido se aplica al iniciar la reproducción o al preparar la exportación.",
            12f
        ).apply {
            setTextColor(cyan)
        })

        // NUEVO: procesamiento neuronal real.
        aiButton = Button(this).apply {
            text = "✦  PROCESAR VOZ CON IA"
            isEnabled = false
            buttonStyle(this, 0xFF7438D9.toInt())
            setOnClickListener {
                processWithAI()
            }
        }
        content.addView(aiButton, params())

        content.addView(label(
            "Demucs separa voz e instrumentos. DeepFilterNet limpia la pista vocal " +
                "y después I-A-M vuelve a mezclar las pistas. La primera vez descarga el modelo.",
            11f
        ).apply {
            setTextColor(0xFFD8C8F5.toInt())
        })

        playButton = Button(this).apply {
            text = "▶  REPRODUCIR EN VIVO"
            isEnabled = false
            buttonStyle(this, 0xFF168A9D.toInt())
            setOnClickListener { togglePlayback() }
        }
        content.addView(playButton, params())

        exportButton = Button(this).apply {
            text = "↓  EXPORTAR CANCIÓN"
            isEnabled = false
            buttonStyle(this, 0xFF8C3FC7.toInt())
            setOnClickListener { exportAudio() }
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

    // PANTALLA DE EXPORTACIÓN

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

        content.addView(logo, LinearLayout.LayoutParams(dp(115), dp(115)))

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

        content.addView(label("NOMBRE DE LA CANCIÓN", 13f).apply {
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

        content.addView(label("NOMBRE DEL ARCHIVO WAV", 13f).apply {
            setTextColor(cyan)
        })

        exportNamePreview = label("Mi canción-I-A-M.wav", 17f).apply {
            setTextColor(gold)
            paint.isFakeBoldText = true
        }
        content.addView(exportNamePreview, params())

        exportNameInput.addTextChangedListener(
            object : android.text.TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?, start: Int, count: Int, after: Int
                ) {}

                override fun onTextChanged(
                    s: CharSequence?, start: Int, before: Int, count: Int
                ) {
                    exportNamePreview.text =
                        makeExportFileName(s?.toString().orEmpty(), "wav")
                }

                override fun afterTextChanged(s: android.text.Editable?) {}
            }
        )

        content.addView(label(
            "CARÁTULA DE LA CANCIÓN", 16f
        ).apply {
            setTextColor(pink)
            paint.isFakeBoldText = true
        })

        coverStatus = label(
            if (pendingCoverJpeg == null) "Sin carátula seleccionada"
            else "Carátula lista"
        ).apply {
            setTextColor(gold)
        }
        content.addView(coverStatus)

        val coverButton = Button(this).apply {
            text = "▣  ELEGIR CARÁTULA"
            buttonStyle(this, 0xFFB02FAD.toInt())
            setOnClickListener {
                coverPicker.launch(arrayOf("image/*"))
            }
        }
        content.addView(coverButton, params())

        val removeCoverButton = Button(this).apply {
            text = "QUITAR CARÁTULA"
            buttonStyle(this, 0xFF553B70.toInt())
            setOnClickListener {
                pendingCoverJpeg = null
                coverStatus.text = "Sin carátula seleccionada"
                message("Carátula quitada.")
            }
        }
        content.addView(removeCoverButton, params())

        content.addView(label(
            "Elige el formato para guardar tu canción.", 14f
        ))

        val wavButton = Button(this).apply {
            text = "↓  EXPORTAR WAV"
            buttonStyle(this, 0xFF8C3FC7.toInt())
            setOnClickListener {
                val samples = pendingProcessedAudio
                if (samples == null) {
                    message("No hay audio preparado.")
                } else {
                    saveWavPicker.launch(
                        makeExportFileName(
                            exportNameInput.text.toString(), "wav"
                        )
                    )
                }
            }
        }
        content.addView(wavButton, params())

        val mp3Button = Button(this).apply {
            text = "↓  EXPORTAR MP3"
            buttonStyle(this, 0xFF168A9D.toInt())
            setOnClickListener {
                if (pendingProcessedAudio == null) {
                    message("No hay audio preparado.")
                } else {
                    saveMp3Picker.launch(
                        makeExportFileName(
                            exportNameInput.text.toString(), "mp3"
                        )
                    )
                }
            }
        }
        content.addView(mp3Button, params())

        content.addView(label(
            "MP3: la carátula se incorpora al archivo. WAV: la carátula se guarda como JPG independiente.",
            12f
        ).apply {
            setTextColor(0xFFD8C8F5.toInt())
        })

        val backButton = Button(this).apply {
            text = "←  REGRESAR AL ECUALIZADOR"
            buttonStyle(this, 0xFF553B70.toInt())
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

    private fun makeExportFileName(rawName: String, extension: String): String {

        var name = rawName.trim()

        name = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        name = name.trim('.', ' ')

        name = name.replace(
            Regex("(?i)\\.(wav|mp3)$"),
            ""
        )

        name = name.replace(
            Regex("(?i)(-I-A-M|_I-A-M| I-A-M)$"),
            ""
        ).trim()

        if (name.isBlank()) name = "Cancion"

        return "$name-I-A-M.$extension"
    }

    private fun makeCoverFileName(rawName: String): String {
        val audioName = makeExportFileName(rawName, "wav")
        return audioName.removeSuffix(".wav") + "-CARATULA.jpg"
    }

    // CARGAR CARÁTULA

    private fun loadCover(uri: Uri) {

        try {
            val source = contentResolver.openInputStream(uri)
                ?: throw Exception("No se pudo abrir la imagen.")

            val bitmap = source.use {
                BitmapFactory.decodeStream(it)
            } ?: throw Exception("La imagen no es compatible.")

            val maxSide = 1200
            val scale = minOf(
                1f,
                maxSide.toFloat() / maxOf(bitmap.width, bitmap.height)
            )

            val resized = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).toInt().coerceAtLeast(1),
                    (bitmap.height * scale).toInt().coerceAtLeast(1),
                    true
                )
            } else {
                bitmap
            }

            val output = ByteArrayOutputStream()
            resized.compress(Bitmap.CompressFormat.JPEG, 90, output)

            pendingCoverJpeg = output.toByteArray()

            if (resized !== bitmap) resized.recycle()
            bitmap.recycle()

            if (::coverStatus.isInitialized) {
                coverStatus.text = "✓ Carátula seleccionada"
            }

            message("Carátula lista para exportar.")

        } catch (e: Exception) {
            message(e.message ?: "No se pudo cargar la carátula.")
        }
    }

    // CONTROLES DEL MOTOR

    private fun gains(): FloatArray =
        faders.map { control -> control.value }.toFloatArray()

    private fun updateEngine() {

        val current = engine ?: return

        current.setGains(gains())
        current.bassEnabled = bassSwitch.isChecked
        current.reverbEnabled = reverbSwitch.isChecked
        current.stereoEnabled = stereoSwitch.isChecked
        current.humRemoval = humSwitch.isChecked
        current.setVolume(volumeSlider.progress.toFloat())
        current.setVocalClarityLevel(claritySlider.progress.toFloat())
    }

    // ANALIZADOR

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

        val stride = 1
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

            peak = max(peak, abs(value))

            lowState += lowAlpha * (value - lowState)
            highState += highAlpha * (value - highState)

            val low = lowState
            val mid = highState - lowState
            val top = value - highState

            totalEnergy += value * value
            lowEnergy += low * low
            midEnergy += mid * mid
            highEnergy += top * top

            count++
        }

        val rms = sqrt(totalEnergy / count.coerceAtLeast(1))

        fun db(value: Double): Float =
            (20.0 * log10(value.coerceAtLeast(0.000001)))
                .toFloat().coerceIn(-120f, 0f)

        val sum = lowEnergy + midEnergy + highEnergy

        val lowPercent =
            if (sum > 0.0) (lowEnergy / sum * 100.0).toFloat() else 0f

        val midPercent =
            if (sum > 0.0) (midEnergy / sum * 100.0).toFloat() else 0f

        val highPercent =
            if (sum > 0.0) (highEnergy / sum * 100.0).toFloat() else 0f

        return AudioStats(
            db(rms), db(peak),
            lowPercent, midPercent, highPercent
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

    // RECOMENDACIÓN

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
        if (position >= 0) presetSpinner.setSelection(position)

        status.text = "Sugerencia orientativa: $recommendation"
        message("I-A-M recomienda $recommendation. Escucha el resultado y decide.")
    }

    // LA SEGUNDA PARTE CONTINÚA AQUÍ.
        // PROCESAMIENTO NEURONAL DEMUCS + DEEPFILTERNET

    private fun processWithAI() {

        val source = audio
            ?: return message("Primero selecciona una canción.")

        if (aiProcessedAudio != null) {
            message("Esta canción ya fue procesada con IA. Puedes reproducirla o exportarla.")
            return
        }

        stopPlayback()

        val originalRate = rate
        val originalChannels = channels

        aiButton.isEnabled = false
        selectButton.isEnabled = false
        playButton.isEnabled = false
        exportButton.isEnabled = false

        progress.visibility = View.VISIBLE
        progress.isIndeterminate = false
        progress.max = 100
        progress.progress = 0

        status.text = "I-A-M está preparando los motores neuronales..."

        Thread {
            try {

                val result = DemucsVoicePipeline.process(
                    context = applicationContext,
                    input = source,
                    sampleRate = originalRate,
                    channels = originalChannels,
                    attenuationDb = 20f,
                    onProgress = { percent, stage ->

                        handler.post {
                            if (!isFinishing && !isDestroyed) {
                                progress.progress =
                                    percent.coerceIn(0, 100)

                                status.text =
                                    "IA ${percent.coerceIn(0, 100)}% · $stage"
                            }
                        }
                    }
                )

                check(result.isNotEmpty()) {
                    "La IA no generó audio."
                }

                check(result.size % 2 == 0) {
                    "La mezcla final no tiene formato estéreo válido."
                }

                handler.post {

                    aiProcessedAudio = result
                    aiRate = 44100
                    aiChannels = 2

                    progress.visibility = View.GONE
                    progress.isIndeterminate = true

                    aiButton.isEnabled = true
                    aiButton.text = "✓  VOZ PROCESADA CON IA"

                    selectButton.isEnabled = true
                    playButton.isEnabled = true
                    exportButton.isEnabled = true

                    status.text =
                        "IA terminada: voz limpia e instrumentos mezclados."

                    val stats = analyzeAudio(
                        result,
                        aiChannels,
                        aiRate
                    )

                    showAnalysis(stats)

                    message(
                        "¡Procesamiento neuronal terminado! " +
                            "Demucs separó las pistas y DeepFilterNet limpió la voz."
                    )
                }

            } catch (e: Exception) {

                handler.post {

                    progress.visibility = View.GONE
                    progress.isIndeterminate = true

                    aiButton.isEnabled = true
                    selectButton.isEnabled = true
                    playButton.isEnabled = true
                    exportButton.isEnabled = true

                    status.text = "No se pudo completar el procesamiento IA."

                    message(
                        e.message ?: "Error durante el procesamiento neuronal."
                    )
                }
            }
        }.start()
    }

    // REPRODUCCIÓN EN VIVO

    private fun togglePlayback() {

        val samples = aiProcessedAudio ?: audio
            ?: return message("Selecciona una canción primero.")

        if (engine?.isPlaying() == true) {
            engine?.stop()
            playButton.text = "▶  REPRODUCIR EN VIVO"
            status.text = "Reproducción detenida."
            return
        }

        try {
            engine?.stop()

            val playbackRate =
                if (aiProcessedAudio != null) aiRate else rate

            val playbackChannels =
                if (aiProcessedAudio != null) aiChannels else channels

            val newEngine = LiveAudioEngine(
                playbackRate,
                playbackChannels,
                samples
            )

            newEngine.setGains(gains())
            newEngine.bassEnabled = bassSwitch.isChecked
            newEngine.reverbEnabled = reverbSwitch.isChecked
            newEngine.stereoEnabled = stereoSwitch.isChecked
            newEngine.humRemoval = humSwitch.isChecked
            newEngine.setVolume(volumeSlider.progress.toFloat())
            newEngine.setVocalClarityLevel(claritySlider.progress.toFloat())

            engine = newEngine
            newEngine.start()

            playButton.text = "■  DETENER REPRODUCCIÓN"

            status.text =
                if (aiProcessedAudio != null) {
                    "Reproduciendo mezcla procesada con IA."
                } else {
                    "Reproduciendo. Ajusta los controles en vivo."
                }

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

    // CARGA DE AUDIO

    private fun loadAudio(uri: Uri) {

        stopPlayback()

        audio = null
        aiProcessedAudio = null
        pendingProcessedAudio = null

        aiRate = 44100
        aiChannels = 2

        selectButton.isEnabled = false
        aiButton.isEnabled = false
        aiButton.text = "✦  PROCESAR VOZ CON IA"
        playButton.isEnabled = false
        exportButton.isEnabled = false

        progress.visibility = View.VISIBLE
        progress.isIndeterminate = true

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
                    aiButton.isEnabled = true
                    playButton.isEnabled = true
                    exportButton.isEnabled = true

                    status.text = "Canción lista. Análisis completado."

                    message(
                        "Audio cargado correctamente. Ya puedes procesarlo con IA."
                    )
                }

            } catch (e: Exception) {

                handler.post {

                    progress.visibility = View.GONE

                    selectButton.isEnabled = true
                    aiButton.isEnabled = false
                    playButton.isEnabled = false
                    exportButton.isEnabled = false

                    status.text = "Error al cargar canción."

                    message(e.message ?: "Error de audio.")
                }
            }
        }.start()
    }

    // DECODIFICADOR

    
private fun decode(uri: Uri): Decoded {

    val extractor = MediaExtractor()
    var codec: MediaCodec? = null

    try {
        extractor.setDataSource(this, uri, null)

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
                val inputIndex = codec.dequeueInputBuffer(10000)

                if (inputIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputIndex)
                        ?: throw Exception("No se pudo leer el audio.")

                    inputBuffer.clear()

                    val size = extractor.readSampleData(inputBuffer, 0)

                    if (size < 0) {
                        codec.queueInputBuffer(
                            inputIndex,
                            0,
                            0,
                            0,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(
                            inputIndex,
                            0,
                            size,
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

                    try {
                        val buffer = codec.getOutputBuffer(outputIndex)

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

                            if (info.offset < 0 ||
                                info.size < 0 ||
                                info.offset + info.size > buffer.capacity()
                            ) {
                                throw Exception("Bloque de audio inválido.")
                            }

                            if (output.size().toLong() + info.size >
                                100L * 1024L * 1024L
                            ) {
                                throw Exception(
                                    "Audio demasiado grande: límite 100 MB."
                                )
                            }

                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)

                            val chunk = ByteArray(info.size)
                            buffer.get(chunk)
                            output.write(chunk)
                        }

                        if (info.flags and
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        ) {
                            outputDone = true
                        }

                    } finally {
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }

                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {

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
            throw Exception("Frecuencia o canales de audio no compatibles.")
        }

        val bytes = output.toByteArray()

        if (bytes.isEmpty()) {
            throw Exception("El archivo no contiene audio.")
        }

        // Android entrega PCM en orden little-endian.
        val buffer = ByteBuffer.wrap(bytes)
            .order(ByteOrder.LITTLE_ENDIAN)

        val samples: ShortArray = when (encoding) {

            AudioFormat.ENCODING_PCM_16BIT -> {

                if (bytes.size % 2 != 0) {
                    throw Exception("Audio PCM de 16 bits incompleto.")
                }

                ShortArray(bytes.size / 2) {
                    buffer.short
                }
            }

            AudioFormat.ENCODING_PCM_FLOAT -> {

                if (bytes.size % 4 != 0) {
                    throw Exception("Audio PCM flotante incompleto.")
                }

                ShortArray(bytes.size / 4) {
                    val value = buffer.float

                    val safeValue = if (value.isFinite()) {
                        value.coerceIn(-1f, 1f)
                    } else {
                        0f
                    }

                    (safeValue * 32767f).toInt().toShort()
                }
            }

            AudioFormat.ENCODING_PCM_8BIT -> {

                ShortArray(bytes.size) {
                    val unsignedValue = bytes[it].toInt() and 0xFF
                    ((unsignedValue - 128) shl 8).toShort()
                }
            }

            AudioFormat.ENCODING_PCM_24BIT_PACKED -> {

                if (bytes.size % 3 != 0) {
                    throw Exception("Audio PCM de 24 bits incompleto.")
                }

                ShortArray(bytes.size / 3) {
                    val b0 = buffer.get().toInt() and 0xFF
                    val b1 = buffer.get().toInt() and 0xFF
                    val b2 = buffer.get().toInt() and 0xFF

                    var value = b0 or (b1 shl 8) or (b2 shl 16)

                    if ((value and 0x800000) != 0) {
                        value = value or -0x1000000
                    }

                    (value shr 8).toShort()
                }
            }

            AudioFormat.ENCODING_PCM_32BIT -> {

                if (bytes.size % 4 != 0) {
                    throw Exception("Audio PCM de 32 bits incompleto.")
                }

                ShortArray(bytes.size / 4) {
                    (buffer.int shr 16).toShort()
                }
            }

            else -> {
                throw Exception("Formato PCM no compatible: $encoding")
            }
        }

        if (samples.isEmpty() || samples.size % ch != 0) {
            throw Exception("Las muestras de audio están incompletas.")
        }

        return Decoded(
            samples = samples,
            sampleRate = sr,
            channelCount = ch
        )

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


    // FILTROS DIGITALES

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

    // PROCESAMIENTO COMPARTIDO PARA EXPORTACIÓN WAV Y MP3

    private fun applyEffects(
        input: ShortArray,
        sr: Int,
        ch: Int,
        values: FloatArray,
        bass: Boolean,
        reverb: Boolean,
        stereo: Boolean,
        volumePercent: Int,
        clarityPercent: Int,
        hum: Boolean
    ): ShortArray {

        require(ch in 1..2) {
            "Canales de audio no compatibles."
        }

        require(input.size % ch == 0) {
            "El audio está incompleto."
        }

        val processor = AudioProcessor(
            sampleRate = sr,
            channels = ch,
            settings = AudioProcessor.Settings(
                equalizer = values.copyOf(),
                bassEnabled = bass,
                reverbEnabled = reverb,
                stereoEnabled = stereo,
                volumePercent = volumePercent,
                vocalClarity = clarityPercent,
                humRemoval = hum
            )
        )

        val output = ShortArray(input.size)

        val blockSize = 8192 - (8192 % ch)

        var offset = 0

        while (offset < input.size) {

            val count = minOf(
                blockSize,
                input.size - offset
            )

            val block = input.copyOfRange(
                offset,
                offset + count
            )

            val processed = processor.process(block)

            processed.copyInto(
                destination = output,
                destinationOffset = offset
            )

            offset += count
        }

        return output
    }

    // CREAR WAV

    private fun wav(
        samples: ShortArray,
        sr: Int,
        ch: Int
    ): ByteArray {

        require(ch in 1..2)
        require(sr in 8000..192000)
        require(samples.size % ch == 0)

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

    // PREPARAR EXPORTACIÓN

    private fun exportAudio() {

        val samples = aiProcessedAudio ?: audio
            ?: return message("Primero selecciona una canción.")

        val sr = if (aiProcessedAudio != null) aiRate else rate
        val ch = if (aiProcessedAudio != null) aiChannels else channels

        val values = gains()
        val bass = bassSwitch.isChecked
        val reverb = reverbSwitch.isChecked
        val stereo = stereoSwitch.isChecked
        val volume = volumeSlider.progress
        val clarity = claritySlider.progress
        val hum = humSwitch.isChecked

        stopPlayback()

        exportButton.isEnabled = false
        progress.visibility = View.VISIBLE
        progress.isIndeterminate = true

        status.text = "I-A-M está preparando la canción para exportar..."

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
                    clarity,
                    hum
                )

                handler.post {

                    pendingProcessedAudio = result
                    pendingExportRate = sr
                    pendingExportChannels = ch

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
                        e.message ?: "Error preparando audio."
                    )
                }
            }
        }.start()
    }

    // GUARDAR WAV

    private fun saveWav(uri: Uri) {

        val samples = pendingProcessedAudio ?: return

        val sr = pendingExportRate
        val ch = pendingExportChannels

        progress.visibility = View.VISIBLE
        progress.isIndeterminate = true

        Thread {
            try {

                val data = wav(samples, sr, ch)

                contentResolver.openOutputStream(uri)?.use {
                    it.write(data)
                    it.flush()
                } ?: throw Exception("No se pudo guardar el WAV.")

                handler.post {

                    progress.visibility = View.GONE

                    message("WAV guardado correctamente.")

                    if (pendingCoverJpeg != null) {
                        pendingCoverSave = true

                        saveCoverPicker.launch(
                            makeCoverFileName(
                                exportNameInput.text.toString()
                            )
                        )
                    } else {
                        finishExport()
                    }
                }

            } catch (e: Exception) {

                handler.post {

                    progress.visibility = View.GONE

                    message(
                        e.message ?: "Error guardando WAV."
                    )
                }
            }
        }.start()
    }

    // GUARDAR MP3

    private fun saveMp3(uri: Uri) {

        val samples = pendingProcessedAudio ?: return

        val sr = pendingExportRate
        val ch = pendingExportChannels
        val cover = pendingCoverJpeg

        val title = makeExportFileName(
            exportNameInput.text.toString(),
            "mp3"
        ).removeSuffix(".mp3")

        pendingExportTitle = title

        progress.visibility = View.VISIBLE
        progress.isIndeterminate = true

        message("I-A-M está creando el MP3. Espera un momento.")

        Thread {
            var tempFile: File? = null

            try {

                val generatedFile = Mp3Exporter.export(
                    this,
                    samples,
                    sr,
                    ch,
                    title,
                    cover
                )

                tempFile = generatedFile

                contentResolver.openOutputStream(uri)?.use { output ->

                    generatedFile.inputStream().use { input ->
                        input.copyTo(output)
                    }

                    output.flush()

                } ?: throw Exception("No se pudo guardar el MP3.")

                generatedFile.delete()
                tempFile = null

                handler.post {

                    progress.visibility = View.GONE

                    message("¡MP3 exportado correctamente con I-A-M!")

                    finishExport()
                }

            } catch (e: Exception) {

                tempFile?.delete()

                handler.post {

                    progress.visibility = View.GONE

                    message(
                        e.message ?: "Error creando el MP3."
                    )
                }
            }
        }.start()
    }

    // GUARDAR CARÁTULA INDEPENDIENTE PARA WAV

    private fun saveCover(uri: Uri) {

        val cover = pendingCoverJpeg

        if (cover == null) {
            finishExport()
            return
        }

        try {

            contentResolver.openOutputStream(uri)?.use {
                it.write(cover)
                it.flush()
            } ?: throw Exception("No se pudo guardar la carátula.")

            message("Carátula JPG guardada junto con tu WAV.")

        } catch (e: Exception) {

            message(
                e.message ?: "No se pudo guardar la carátula."
            )
        }

        pendingCoverSave = false
        finishExport()
    }

    private fun finishExport() {

        pendingProcessedAudio = null

        setContentView(mainScreen)

        status.text =
            "Exportación terminada. Puedes continuar trabajando."
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

// CONTROL VERTICAL ANTIGUO
// Se conserva como clase auxiliar, pero ya no se utiliza en el ecualizador.

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
