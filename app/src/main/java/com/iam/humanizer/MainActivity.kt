package com.iam.humanizer

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class MainActivity : Activity() {
    private var inputUri: Uri? = null
    private lateinit var status: TextView
    private lateinit var clarity: SeekBar
    private lateinit var warmth: SeekBar
    private lateinit var music: SeekBar
    private lateinit var outputName: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(10, 16, 28)
        window.navigationBarColor = android.graphics.Color.rgb(10, 16, 28)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 28, 24, 24)
            setBackgroundColor(android.graphics.Color.rgb(10, 16, 28))
        }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        content.addView(label("I.A.M HUMANIZER", 27f, true))
        content.addView(label("Mejora de audio sin IA · procesamiento local", 14f, false))
        content.addView(space(18))
        val pick = button("1. SELECCIONAR CANCIÓN WAV")
        content.addView(pick)
        pick.setOnClickListener {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "audio/wav"
            }
            startActivityForResult(i, 10)
        }
        content.addView(space(14))
        content.addView(label("PRESENCIA Y CLARIDAD (global)", 15f, true))
        clarity = slider(content, 0, 100, 35, "Más presencia")
        content.addView(label("CALIDEZ (global)", 15f, true))
        warmth = slider(content, 0, 100, 25, "Más cuerpo")
        content.addView(label("REDUCCIÓN DE MÚSICA (aproximada)", 15f, true))
        music = slider(content, 0, 60, 12, "Menos medios")
        content.addView(label("Nombre del archivo de salida", 14f, true))
        outputName = EditText(this).apply {
            setText("cancion_IAM_Humanizada")
            setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(0xff9aa7ba.toInt())
            setSingleLine(true)
        }
        content.addView(outputName)
        content.addView(space(12))
        val export = button("2. PROCESAR Y GUARDAR WAV")
        content.addView(export)
        export.setOnClickListener { processAndSave() }
        content.addView(space(14))
        status = label("Estado: selecciona un archivo WAV PCM de 16 bits.", 14f, false)
        content.addView(status)
        content.addView(space(16))
        content.addView(label("IMPORTANTE", 15f, true))
        content.addView(label(
            "Esta versión funciona sin Internet y sin IA. No separa la voz de los instrumentos: al recibir una canción ya mezclada, los controles afectan el sonido general. La reducción de música es una aproximación por frecuencias, no un control independiente de cada instrumento. Entrada y salida: WAV PCM de 16 bits.",
            13f, false
        ))
        setContentView(root)
    }

    private fun label(text: String, size: Float, bold: Boolean): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(if (bold) 0xff65e6c2.toInt() else 0xffd6deea.toInt())
            if (bold) setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 8, 0, 8)
        }

    private fun button(text: String) = Button(this).apply {
        this.text = text
        setTextColor(android.graphics.Color.WHITE)
        setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xff147d75.toInt()))
    }

    private fun space(h: Int) = TextView(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, h)
    }

    private fun slider(parent: LinearLayout, minV: Int, maxV: Int, initial: Int, title: String): SeekBar {
        val s = SeekBar(this).apply {
            max = maxV - minV
            progress = initial - minV
            contentDescription = title
        }
        parent.addView(s, ViewGroup.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        return s
    }

    @Deprecated("Deprecated in Android but retained for broad compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 10 && resultCode == Activity.RESULT_OK) {
            inputUri = data?.data
            status.text = if (inputUri != null) "Archivo seleccionado. Listo para procesar." else "No se seleccionó archivo."
        } else if (requestCode == 11 && resultCode == Activity.RESULT_OK) {
            val uri = data?.data
            if (uri != null) {
                try {
                    val result = pendingOutput
                    if (result != null) contentResolver.openOutputStream(uri)?.use { it.write(result) }
                    status.text = "¡Listo! WAV humanizado guardado."
                    Toast.makeText(this, "Archivo guardado", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    status.text = "Error al guardar: ${e.message}"
                }
            }
        }
    }

    private var pendingOutput: ByteArray? = null

    private fun processAndSave() {
        val uri = inputUri
        if (uri == null) {
            status.text = "Primero selecciona una canción WAV."
            return
        }
        status.text = "Procesando en el teléfono..."
        try {
            val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            val wav = WavPcm16.read(bytes)
            val clarityAmount = clarity.progress / 100.0
            val warmthAmount = warmth.progress / 100.0
            val musicAmount = music.progress / 100.0
            val processed = Dsp.process(wav.samples, wav.channels, wav.sampleRate, clarityAmount, warmthAmount, musicAmount)
            pendingOutput = WavPcm16.write(processed, wav.channels, wav.sampleRate)
            val name = outputName.text.toString().trim().ifBlank { "cancion_IAM_Humanizada" }
                .replace(Regex("[^A-Za-z0-9_-]"), "_")
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "audio/wav"
                putExtra(Intent.EXTRA_TITLE, "$name.wav")
            }
            startActivityForResult(intent, 11)
        } catch (e: Exception) {
            status.text = "No se pudo procesar. Usa WAV PCM de 16 bits. Detalle: ${e.message}"
        }
    }
}

private data class WavData(val samples: ShortArray, val channels: Int, val sampleRate: Int)

private object WavPcm16 {
    fun read(b: ByteArray): WavData {
        require(b.size > 44 && String(b, 0, 4) == "RIFF" && String(b, 8, 4) == "WAVE") { "El archivo no parece WAV." }
        var pos = 12
        var channels = 0
        var rate = 0
        var bits = 0
        var format = 0
        var dataOffset = -1
        var dataSize = 0
        while (pos + 8 <= b.size) {
            val id = String(b, pos, 4)
            val size = leInt(b, pos + 4).coerceAtLeast(0)
            val start = pos + 8
            if (start + size > b.size) break
            when (id) {
                "fmt " -> {
                    format = leShort(b, start)
                    channels = leShort(b, start + 2)
                    rate = leInt(b, start + 4)
                    bits = leShort(b, start + 14)
                }
                "data" -> { dataOffset = start; dataSize = size }
            }
            pos = start + size + (size and 1)
        }
        require(format == 1 && bits == 16 && (channels == 1 || channels == 2) && rate > 0 && dataOffset >= 0) {
            "Formato no compatible: se requiere PCM 16-bit mono o estéreo."
        }
        val count = dataSize / 2
        val samples = ShortArray(count) { i -> leShort(b, dataOffset + i * 2).toShort() }
        return WavData(samples, channels, rate)
    }

    fun write(samples: ShortArray, channels: Int, rate: Int): ByteArray {
        val dataSize = samples.size * 2
        val out = ByteArrayOutputStream(44 + dataSize)
        fun put(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
        fun i(v: Int) { repeat(4) { out.write((v ushr (8 * it)) and 255) } }
        fun sh(v: Int) { out.write(v and 255); out.write((v ushr 8) and 255) }
        put("RIFF"); i(36 + dataSize); put("WAVE"); put("fmt "); i(16)
        sh(1); sh(channels); i(rate); i(rate * channels * 2); sh(channels * 2); sh(16)
        put("data"); i(dataSize)
        samples.forEach { sh(it.toInt() and 0xffff) }
        return out.toByteArray()
    }

    private fun leInt(b: ByteArray, p: Int) =
        (b[p].toInt() and 255) or ((b[p+1].toInt() and 255) shl 8) or
        ((b[p+2].toInt() and 255) shl 16) or ((b[p+3].toInt() and 255) shl 24)
    private fun leShort(b: ByteArray, p: Int) = (b[p].toInt() and 255) or ((b[p+1].toInt() and 255) shl 8)
}

private object Dsp {
    fun process(input: ShortArray, channels: Int, sampleRate: Int, clarity: Double, warmth: Double, music: Double): ShortArray {
        val out = ShortArray(input.size)
        val n = channels
        val lowAlpha = (1.0 - kotlin.math.exp(-2.0 * Math.PI * 180.0 / sampleRate)).coerceIn(0.001, 0.99)
        val highAlpha = (1.0 - kotlin.math.exp(-2.0 * Math.PI * 3200.0 / sampleRate)).coerceIn(0.001, 0.99)
        val low = DoubleArray(n)
        val high = DoubleArray(n)
        val previous = DoubleArray(n)
        for (i in input.indices) {
            val c = i % n
            val x = input[i].toDouble() / 32768.0
            low[c] += lowAlpha * (x - low[c])
            high[c] += highAlpha * (x - high[c])
            val highPart = x - high[c]
            val midPart = x - low[c] - highPart
            // Presence/clarity and warmth are broad tonal adjustments, not vocal extraction.
            var y = x + clarity * 0.28 * highPart + warmth * 0.18 * low[c] - music * 0.22 * midPart
            // Gentle compression and peak protection.
            val threshold = 0.72
            val a = abs(y)
            if (a > threshold) y = (if (y < 0) -1 else 1) * (threshold + (a - threshold) * 0.35)
            y *= 0.94
            y = y.coerceIn(-0.98, 0.98)
            out[i] = (y * 32767.0).toInt().toShort()
            previous[c] = y
        }
        return out
    }
}
