
package com.iam.humanizer

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import io.github.jpaauwe.demucsengine.NativeEngine
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor

/**
 * I-A-M - DEMUCS VOICE PIPELINE 2.4
 *
 * Separa voz e instrumentos.
 * Limpia la voz con DeepFilterNet.
 * Reduce suavemente la estática instrumental.
 * Protege la voz original.
 *
 * Diagnósticos:
 * IAM_VOZ
 * IAM_INSTRUMENTOS
 * IAM_INSTRUMENTOS_LIMPIOS
 */
object DemucsVoicePipeline {

    private const val OUTPUT_RATE = 44100
    private const val CLEAN_RATE = 48000

    private const val DEFAULT_ATTENUATION_DB = 20f

    private const val ORIGINAL_VOICE_MIX = 0.35f
    private const val CLEANED_VOICE_MIX = 0.65f

    private const val INSTRUMENT_NOISE_STRENGTH = 0.30f

    private const val TAG = "IAM_PIPELINE"

    fun process(
        context: Context,
        input: ShortArray,
        sampleRate: Int,
        channels: Int,
        attenuationDb: Float = DEFAULT_ATTENUATION_DB,
        onProgress: ((Int, String) -> Unit)? = null
    ): ShortArray {

        require(input.isNotEmpty()) {
            "No hay audio para procesar."
        }

        require(sampleRate in 8000..192000) {
            "Frecuencia de audio no compatible."
        }

        require(channels in 1..2) {
            "Solo se admite audio mono o estéreo."
        }

        require(input.size % channels == 0) {
            "El audio está incompleto."
        }

        checkNotInterrupted()

        val appContext = context.applicationContext
        val workDir = File(appContext.cacheDir, "iam_demucs_work")

        if (workDir.exists()) {
            check(workDir.deleteRecursively()) {
                "No se pudo limpiar la carpeta temporal."
            }
        }

        check(workDir.mkdirs()) {
            "No se pudo crear la carpeta de trabajo."
        }

        try {
            val rawFile = File(workDir, "input.f32")
            val preparedFile = File(workDir, "prepared.f32")
            val stemsDir = File(workDir, "stems")

            check(stemsDir.mkdirs()) {
                "No se pudo crear la carpeta de pistas."
            }

            // 1. Preparar audio.
            onProgress?.invoke(2, "Preparando canción")

            writeFloat32File(rawFile, input)

            checkNotInterrupted()

            // 2. Cargar modelo Demucs.
            onProgress?.invoke(5, "Comprobando modelo Demucs")

            val model = DemucsModelManager.getModel(appContext) { percent ->
                onProgress?.invoke(
                    5 + percent * 10 / 100,
                    "Descargando modelo Demucs: $percent%"
                )
            }

            checkNotInterrupted()

            onProgress?.invoke(16, "Cargando motor Demucs")

            NativeEngine.loadModel(model.absolutePath)

            // 3. Preparar audio.
            onProgress?.invoke(20, "Preparando audio para separación")

            NativeEngine.prepareInput(
                rawFile.absolutePath,
                sampleRate,
                channels,
                preparedFile.absolutePath
            )

            checkNotInterrupted()

            // 4. Separar voz e instrumentos.
            onProgress?.invoke(25, "Separando voz e instrumentos")

            NativeEngine.separate(
                prepared = preparedFile.absolutePath,
                outDir = stemsDir.absolutePath,
                selected = NativeEngine.SOURCES.toSet(),
                threads = 1,
                computeThreads = 0,
                gpu = false
            ) { fraction, stage ->

                val percent = (
                    25 + fraction.coerceIn(0f, 1f) * 45f
                ).toInt()

                onProgress?.invoke(percent, "Demucs: $stage")

                !Thread.currentThread().isInterrupted
            }

            checkNotInterrupted()

            // 5. Leer voz separada.
            onProgress?.invoke(72, "Leyendo pistas separadas")

            val vocalsFile = File(stemsDir, "vocals.wav")

            check(vocalsFile.isFile) {
                "Demucs no generó la pista vocal."
            }

            val vocalStereo = readPcm16StereoWav(vocalsFile)
            val frameCount = vocalStereo.size / 2

            check(frameCount > 0) {
                "La pista vocal está vacía."
            }

            // 6. Mezclar instrumentos separados.
            onProgress?.invoke(74, "Mezclando instrumentos")

            val mix = FloatArray(vocalStereo.size)

            val instrumentNames = listOf(
                "drums",
                "bass",
                "other",
                "guitar",
                "piano"
            )

            for (name in instrumentNames) {

                checkNotInterrupted()

                val stemFile = File(stemsDir, "$name.wav")

                check(stemFile.isFile) {
                    "Falta la pista de instrumentos: $name"
                }

                val stem = readPcm16StereoWav(stemFile)

                check(stem.size == mix.size) {
                    "La duración de la pista $name no coincide."
                }

                for (i in mix.indices) {
                    mix[i] += stem[i].toFloat() / 32768f
                }
            }

            checkNotInterrupted()

            // 7. Guardar diagnósticos originales.
            onProgress?.invoke(
                76,
                "Guardando diagnóstico original"
            )

            saveDiagnosticTracks(
                context = appContext,
                vocals = vocalStereo,
                instruments = mix
            )

            checkNotInterrupted()

            // 8. Reducir estática instrumental.
            onProgress?.invoke(
                78,
                "Reduciendo estática de instrumentos"
            )

            val cleanedInstruments = try {

                InstrumentNoiseReducer.reduce(
                    input = mix,
                    sampleRate = OUTPUT_RATE,
                    strength = INSTRUMENT_NOISE_STRENGTH
                )

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Falló la reducción instrumental. Se conservará el original.",
                    e
                )

                mix.copyOf()
            }

            check(cleanedInstruments.size == mix.size) {
                "El filtro instrumental devolvió una duración incorrecta."
            }

            checkNotInterrupted()

            // 9. Guardar diagnóstico instrumental limpio.
            onProgress?.invoke(
                80,
                "Guardando instrumentos procesados"
            )

            saveCleanInstrumentDiagnostic(
                context = appContext,
                instruments = cleanedInstruments
            )

            checkNotInterrupted()

            // 10. Preparar voz original en mono.
            onProgress?.invoke(
                81,
                "Preparando voz para limpieza"
            )

            val vocalMono44100 = ShortArray(frameCount)

            for (frame in 0 until frameCount) {

                val left = vocalStereo[frame * 2].toInt()
                val right = vocalStereo[frame * 2 + 1].toInt()

                vocalMono44100[frame] =
                    ((left + right) / 2)
                        .coerceIn(-32768, 32767)
                        .toShort()
            }

            checkNotInterrupted()

            // 11. Remuestrear a 48 kHz.
            onProgress?.invoke(
                83,
                "Preparando frecuencia vocal"
            )

            val vocalMono48000 = resample(
                vocalMono44100,
                OUTPUT_RATE,
                CLEAN_RATE
            )

            checkNotInterrupted()

            // 12. Limpieza neuronal de la voz.
            onProgress?.invoke(
                85,
                "Limpiando ruido de la voz"
            )

            val cleaned48000 = DeepFilterVoiceCleaner.clean(
                context = appContext,
                vocal48kMono = vocalMono48000,
                attenuationDb = attenuationDb.coerceIn(5f, 25f)
            )

            checkNotInterrupted()

            // 13. Regresar voz a 44.1 kHz.
            onProgress?.invoke(
                91,
                "Preparando mezcla final"
            )

            val cleaned44100 = resample(
                cleaned48000,
                CLEAN_RATE,
                OUTPUT_RATE
            )

            checkNotInterrupted()

            // 14. Mezclar instrumentos limpios y voz protegida.
            onProgress?.invoke(
                94,
                "Protegiendo voz y mezclando instrumentos"
            )

            val output = ShortArray(mix.size)

            for (frame in 0 until frameCount) {

                if (frame % 16384 == 0) {
                    checkNotInterrupted()
                }

                val original =
                    vocalMono44100[frame].toFloat() / 32768f

                val cleaned = if (frame < cleaned44100.size) {
                    cleaned44100[frame].toFloat() / 32768f
                } else {
                    original
                }

                val protectedVoice =
                    original * ORIGINAL_VOICE_MIX +
                    cleaned * CLEANED_VOICE_MIX

                val leftIndex = frame * 2
                val rightIndex = leftIndex + 1

                val left =
                    cleanedInstruments[leftIndex] + protectedVoice

                val right =
                    cleanedInstruments[rightIndex] + protectedVoice

                output[leftIndex] = toPcm16(left)
                output[rightIndex] = toPcm16(right)
            }

            checkNotInterrupted()

            onProgress?.invoke(100, "Procesamiento terminado")

            return output

        } finally {
            workDir.deleteRecursively()
        }
    }

    // ---------------------------------------------------------
    // DIAGNÓSTICOS
    // ---------------------------------------------------------

    private fun saveDiagnosticTracks(
        context: Context,
        vocals: ShortArray,
        instruments: FloatArray
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.w(TAG, "Los diagnósticos requieren Android 10 o superior.")
            return
        }

        val stamp = System.currentTimeMillis()

        try {
            saveDiagnosticWav(
                context,
                "IAM_VOZ_$stamp.wav"
            ) { output ->
                writePcm16StereoWav(output, vocals)
            }

            saveDiagnosticWav(
                context,
                "IAM_INSTRUMENTOS_$stamp.wav"
            ) { output ->
                writeFloatStereoWav(output, instruments)
            }

            Log.i(TAG, "Diagnósticos originales guardados.")

        } catch (e: Exception) {
            Log.e(TAG, "Error guardando diagnósticos originales.", e)
        }
    }

    private fun saveCleanInstrumentDiagnostic(
        context: Context,
        instruments: FloatArray
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return

        val stamp = System.currentTimeMillis()

        try {
            saveDiagnosticWav(
                context,
                "IAM_INSTRUMENTOS_LIMPIOS_$stamp.wav"
            ) { output ->
                writeFloatStereoWav(output, instruments)
            }

            Log.i(TAG, "Diagnóstico instrumental limpio guardado.")

        } catch (e: Exception) {
            Log.e(TAG, "Error guardando instrumentos limpios.", e)
        }
    }

    private fun saveDiagnosticWav(
        context: Context,
        displayName: String,
        writer: (OutputStream) -> Unit
    ) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/wav")
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS +
                    "/I-A-M-Diagnostico"
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val resolver = context.contentResolver

        val uri = resolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            values
        ) ?: throw IllegalStateException(
            "No se pudo crear el archivo $displayName"
        )

        try {
            val output = resolver.openOutputStream(uri, "w")
                ?: throw IllegalStateException(
                    "No se pudo abrir $displayName"
                )

            output.use {
                writer(it)
                it.flush()
            }

            val completed = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }

            val updated = resolver.update(uri, completed, null, null)

            check(updated > 0) {
                "No se pudo completar el archivo $displayName"
            }

        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    // ---------------------------------------------------------
    // ESCRITURA WAV
    // ---------------------------------------------------------

    private fun writePcm16StereoWav(
        output: OutputStream,
        samples: ShortArray
    ) {
        writeWavHeader(output, samples.size * 2)

        val buffer = ByteBuffer
            .allocate(64 * 1024)
            .order(ByteOrder.LITTLE_ENDIAN)

        for (sample in samples) {

            if (buffer.remaining() < 2) {
                output.write(buffer.array(), 0, buffer.position())
                buffer.clear()
            }

            buffer.putShort(sample)
        }

        if (buffer.position() > 0) {
            output.write(buffer.array(), 0, buffer.position())
        }
    }

    private fun writeFloatStereoWav(
        output: OutputStream,
        samples: FloatArray
    ) {
        writeWavHeader(output, samples.size * 2)

        val buffer = ByteBuffer
            .allocate(64 * 1024)
            .order(ByteOrder.LITTLE_ENDIAN)

        for (sample in samples) {

            if (buffer.remaining() < 2) {
                output.write(buffer.array(), 0, buffer.position())
                buffer.clear()
            }

            buffer.putShort(toPcm16(sample))
        }

        if (buffer.position() > 0) {
            output.write(buffer.array(), 0, buffer.position())
        }
    }

    private fun writeWavHeader(
        output: OutputStream,
        dataSize: Int
    ) {
        require(dataSize >= 0) {
            "Tamaño de audio inválido."
        }

        val header = ByteBuffer
            .allocate(44)
            .order(ByteOrder.LITTLE_ENDIAN)

        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + dataSize)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1.toShort())
        header.putShort(2.toShort())
        header.putInt(OUTPUT_RATE)
        header.putInt(OUTPUT_RATE * 2 * 2)
        header.putShort(4.toShort())
        header.putShort(16.toShort())
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataSize)

        output.write(header.array())
    }

    // ---------------------------------------------------------
    // AUDIO FLOAT32 PARA DEMUCS
    // ---------------------------------------------------------

    private fun writeFloat32File(
        file: File,
        samples: ShortArray
    ) {
        val buffer = ByteBuffer
            .allocate(64 * 1024)
            .order(ByteOrder.LITTLE_ENDIAN)

        FileOutputStream(file).use { output ->

            for (sample in samples) {

                if (buffer.remaining() < 4) {
                    output.write(buffer.array(), 0, buffer.position())
                    buffer.clear()
                }

                buffer.putFloat(sample.toFloat() / 32768f)
            }

            if (buffer.position() > 0) {
                output.write(buffer.array(), 0, buffer.position())
            }

            output.flush()
            output.fd.sync()
        }
    }

    // ---------------------------------------------------------
    // LECTOR WAV PCM 16 BITS ESTÉREO
    // ---------------------------------------------------------

    private fun readPcm16StereoWav(file: File): ShortArray {

        RandomAccessFile(file, "r").use { raf ->

            require(raf.length() >= 44) {
                "Archivo WAV demasiado pequeño: ${file.name}"
            }

            val header = ByteArray(12)
            raf.readFully(header)

            require(
                String(header, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                String(header, 8, 4, Charsets.US_ASCII) == "WAVE"
            ) {
                "El archivo no es un WAV válido: ${file.name}"
            }

            var format = -1
            var channels = -1
            var bits = -1
            var dataStart = -1L
            var dataSize = -1L

            while (raf.filePointer + 8 <= raf.length()) {

                val chunkId = ByteArray(4)
                raf.readFully(chunkId)

                val chunkSize = readUnsignedIntLE(raf)

                val chunkName = String(
                    chunkId,
                    Charsets.US_ASCII
                )

                val chunkStart = raf.filePointer

                when (chunkName) {

                    "fmt " -> {
                        if (chunkSize >= 16) {
                            format = readUnsignedShortLE(raf)
                            channels = readUnsignedShortLE(raf)

                            raf.skipBytes(4)
                            raf.skipBytes(4)
                            raf.skipBytes(2)

                            bits = readUnsignedShortLE(raf)
                        }
                    }

                    "data" -> {
                        dataStart = chunkStart
                        dataSize = chunkSize
                    }
                }

                val next = chunkStart + chunkSize +
                    (chunkSize and 1L)

                if (next > raf.length()) break

                raf.seek(next)

                if (format >= 0 && dataStart >= 0) break
            }

            require(format == 1) {
                "La pista no es PCM sin comprimir."
            }

            require(channels == 2 && bits == 16) {
                "La pista debe ser estéreo PCM de 16 bits."
            }

            require(dataStart >= 0 && dataSize >= 0) {
                "El WAV no contiene datos de audio."
            }

            require(dataSize <= raf.length() - dataStart) {
                "El bloque de audio está incompleto."
            }

            val sampleCount = (dataSize / 2L).coerceAtMost(
                Int.MAX_VALUE.toLong()
            ).toInt()

            require(sampleCount > 0 && sampleCount % 2 == 0) {
                "La pista WAV está vacía o incompleta."
            }

            val result = ShortArray(sampleCount)

            raf.seek(dataStart)

            val bytes = ByteArray(64 * 1024)
            var index = 0
            var remaining = dataSize

            while (remaining >= 2 && index < result.size) {

                val count = minOf(
                    bytes.size.toLong(),
                    remaining
                ).toInt()

                val evenCount = count - (count % 2)

                raf.readFully(bytes, 0, evenCount)

                val buffer = ByteBuffer.wrap(
                    bytes,
                    0,
                    evenCount
                ).order(ByteOrder.LITTLE_ENDIAN)

                while (buffer.remaining() >= 2) {
                    result[index++] = buffer.short
                }

                remaining -= evenCount
            }

            require(index == result.size) {
                "No se pudo leer toda la pista ${file.name}"
            }

            return result
        }
    }

    private fun readUnsignedShortLE(
        raf: RandomAccessFile
    ): Int {
        val a = raf.read()
        val b = raf.read()

        require(a >= 0 && b >= 0) {
            "WAV incompleto."
        }

        return a or (b shl 8)
    }

    private fun readUnsignedIntLE(
        raf: RandomAccessFile
    ): Long {
        val a = readUnsignedShortLE(raf).toLong()
        val b = readUnsignedShortLE(raf).toLong()

        return a or (b shl 16)
    }

    // ---------------------------------------------------------
    // REMUESTREO
    // ---------------------------------------------------------

    private fun resample(
        input: ShortArray,
        inputRate: Int,
        outputRate: Int
    ): ShortArray {

        if (input.isEmpty()) return ShortArray(0)
        if (inputRate == outputRate) return input.copyOf()

        val outputSize = (
            input.size.toDouble() *
                outputRate.toDouble() /
                inputRate.toDouble()
        ).toInt().coerceAtLeast(1)

        val output = ShortArray(outputSize)
        val ratio = inputRate.toDouble() / outputRate

        for (i in output.indices) {

            if (i % 32768 == 0) {
                checkNotInterrupted()
            }

            val position = i * ratio

            val first = floor(position).toInt()
                .coerceIn(0, input.lastIndex)

            val second = (first + 1)
                .coerceAtMost(input.lastIndex)

            val fraction = position - first

            val value =
                input[first] * (1.0 - fraction) +
                input[second] * fraction

            output[i] = value
                .toInt()
                .coerceIn(-32768, 32767)
                .toShort()
        }

        return output
    }

    // ---------------------------------------------------------
    // UTILIDADES
    // ---------------------------------------------------------

    private fun toPcm16(value: Float): Short {
        return (
            value.coerceIn(-1f, 1f) * 32767f
        ).toInt().toShort()
    }

    private fun checkNotInterrupted() {
        if (Thread.currentThread().isInterrupted) {
            throw InterruptedException(
                "Procesamiento cancelado."
            )
        }
    }
}
