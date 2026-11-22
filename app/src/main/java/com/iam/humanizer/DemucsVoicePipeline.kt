
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
 * I-A-M - DEMUCS VOICE PIPELINE 2.6
 *
 * Separa voz e instrumentos.
 * Limpia la voz con DeepFilterNet.
 * Reduce suavemente la estática instrumental cuando hay memoria.
 * Conserva parte de la voz original para evitar fluctuaciones.
 *
 * Optimizaciones:
 * - Lee los instrumentos directamente desde los WAV por bloques.
 * - Evita crear cinco ShortArray adicionales.
 * - Evita crear una pista vocal adicional a 44.1 kHz.
 * - Convierte de 48 kHz durante la mezcla final.
 * - Comprueba memoria antes del filtro instrumental.
 * - Libera referencias temporales tan pronto como es posible.
 * - Comprueba las pistas y tamaños antes de mezclarlas.
 */
object DemucsVoicePipeline {

    private const val OUTPUT_RATE = 44100
    private const val CLEAN_RATE = 48000

    private const val DEFAULT_ATTENUATION_DB = 20f

    private const val ORIGINAL_VOICE_MIX = 0.35f
    private const val CLEANED_VOICE_MIX = 0.65f

    private const val INSTRUMENT_NOISE_STRENGTH = 0.30f

    // Margen adicional para el filtro instrumental.
    private const val FILTER_RESERVED_MEMORY =
        160L * 1024L * 1024L

    private const val IO_BUFFER_SIZE = 64 * 1024

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
            workDir.deleteRecursively()
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
            onProgress?.invoke(
                20,
                "Preparando audio para separación"
            )

            NativeEngine.prepareInput(
                rawFile.absolutePath,
                sampleRate,
                channels,
                preparedFile.absolutePath
            )

            checkNotInterrupted()

            // 4. Separar voz e instrumentos.
            onProgress?.invoke(
                25,
                "Separando voz e instrumentos"
            )

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

                onProgress?.invoke(
                    percent,
                    "Demucs: $stage"
                )

                !Thread.currentThread().isInterrupted
            }

            checkNotInterrupted()

            // 5. Leer únicamente la voz completa.
            onProgress?.invoke(
                72,
                "Leyendo pista vocal"
            )

            var vocalStereo = readPcm16StereoWav(
                File(stemsDir, "vocals.wav")
            )

            val frameCount = vocalStereo.size / 2

            check(frameCount > 0) {
                "La pista vocal está vacía."
            }

            // 6. Preparar mezcla instrumental.
            // Cada pista se lee directamente desde el disco
            // en bloques pequeños.
            onProgress?.invoke(
                74,
                "Mezclando instrumentos por bloques"
            )

            var mix = FloatArray(vocalStereo.size)

            val instrumentNames = arrayOf(
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

                addPcm16StereoWavToFloat(
                    file = stemFile,
                    destination = mix
                )
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

            // 8. Reducción instrumental con margen de memoria.
            onProgress?.invoke(
                78,
                "Comprobando memoria para limpiar instrumentos"
            )

            val requiredMemory =
                mix.size.toLong() * 4L +
                FILTER_RESERVED_MEMORY

            val availableMemory = availableHeapBytes()

            var filterApplied = false
            var cleanedInstruments = mix

            if (availableMemory >= requiredMemory) {

                onProgress?.invoke(
                    79,
                    "Reduciendo estática de instrumentos"
                )

                try {
                    val filtered = InstrumentNoiseReducer.reduce(
                        input = mix,
                        sampleRate = OUTPUT_RATE,
                        strength = INSTRUMENT_NOISE_STRENGTH
                    )

                    check(filtered.size == mix.size) {
                        "El filtro instrumental devolvió una duración incorrecta."
                    }

                    cleanedInstruments = filtered
                    filterApplied = true

                    Log.i(
                        TAG,
                        "Reducción instrumental aplicada."
                    )

                } catch (error: OutOfMemoryError) {

                    Log.e(
                        TAG,
                        "Memoria insuficiente para el filtro instrumental.",
                        error
                    )

                    cleanedInstruments = mix
                    System.gc()

                } catch (error: Exception) {

                    Log.e(
                        TAG,
                        "Falló el filtro instrumental.",
                        error
                    )

                    cleanedInstruments = mix
                }

            } else {

                Log.w(
                    TAG,
                    "Filtro instrumental omitido por memoria. " +
                        "Disponible: $availableMemory; " +
                        "estimado necesario: $requiredMemory"
                )
            }

            checkNotInterrupted()

            // Liberar la referencia original si el filtro
            // generó una pista nueva.
            if (cleanedInstruments !== mix) {
                mix = FloatArray(0)
            }

            // 9. Guardar diagnóstico instrumental.
            onProgress?.invoke(
                80,
                if (filterApplied) {
                    "Guardando instrumentos procesados"
                } else {
                    "Conservando instrumentos originales"
                }
            )

            saveCleanInstrumentDiagnostic(
                context = appContext,
                instruments = cleanedInstruments
            )

            checkNotInterrupted()

            // 10. Convertir voz estéreo a mono.
            onProgress?.invoke(
                81,
                "Preparando voz para limpieza"
            )

            val vocalMono44100 = ShortArray(frameCount)

            for (frame in 0 until frameCount) {

                if (frame % 32768 == 0) {
                    checkNotInterrupted()
                }

                val left = vocalStereo[frame * 2].toInt()
                val right = vocalStereo[frame * 2 + 1].toInt()

                vocalMono44100[frame] =
                    ((left + right) / 2)
                        .coerceIn(-32768, 32767)
                        .toShort()
            }

            // La pista estéreo ya no se necesita.
            vocalStereo = ShortArray(0)

            checkNotInterrupted()

            // 11. Remuestrear a 48 kHz.
            onProgress?.invoke(
                83,
                "Preparando frecuencia vocal"
            )

            var vocalMono48000 = resample(
                input = vocalMono44100,
                inputRate = OUTPUT_RATE,
                outputRate = CLEAN_RATE
            )

            checkNotInterrupted()

            // 12. Limpieza neuronal.
            onProgress?.invoke(
                85,
                "Limpiando ruido de la voz"
            )

            var cleaned48000 = DeepFilterVoiceCleaner.clean(
                context = appContext,
                vocal48kMono = vocalMono48000,
                attenuationDb = attenuationDb.coerceIn(5f, 25f)
            )

            // Liberar entrada de DeepFilterNet.
            vocalMono48000 = ShortArray(0)

            check(cleaned48000.isNotEmpty()) {
                "DeepFilterNet devolvió una pista vocal vacía."
            }

            checkNotInterrupted()

            // 13. Mezclar sin crear cleaned44100.
            // La conversión 48 kHz -> 44.1 kHz se realiza
            // directamente mientras se genera la salida.
            onProgress?.invoke(
                91,
                "Preparando mezcla final"
            )

            check(cleanedInstruments.size == frameCount * 2) {
                "La duración instrumental no coincide con la voz."
            }

            onProgress?.invoke(
                94,
                "Protegiendo voz y mezclando instrumentos"
            )

            val output = ShortArray(cleanedInstruments.size)

            val conversionRatio =
                CLEAN_RATE.toDouble() / OUTPUT_RATE.toDouble()

            for (frame in 0 until frameCount) {

                if (frame % 8192 == 0) {
                    checkNotInterrupted()
                }

                val original =
                    vocalMono44100[frame].toFloat() / 32768f

                // Interpolación directa de 48 kHz a 44.1 kHz.
                val position = frame * conversionRatio

                val first = floor(position)
                    .toInt()
                    .coerceIn(0, cleaned48000.lastIndex)

                val second = (first + 1)
                    .coerceAtMost(cleaned48000.lastIndex)

                val fraction = position - first

                val cleanedSample =
                    cleaned48000[first] * (1.0 - fraction) +
                    cleaned48000[second] * fraction

                val cleaned =
                    (cleanedSample / 32768.0).toFloat()

                // Protege la voz original.
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

            // Liberar la referencia a la voz procesada.
            cleaned48000 = ShortArray(0)

            onProgress?.invoke(
                100,
                "Procesamiento terminado"
            )

            Log.i(
                TAG,
                "Pipeline terminado. Filtro instrumental: $filterApplied"
            )

            return output

        } finally {
            // Los archivos temporales se eliminan al terminar.
            try {
                workDir.deleteRecursively()
            } catch (error: Exception) {
                Log.w(
                    TAG,
                    "No se pudo eliminar la carpeta temporal.",
                    error
                )
            }
        }
    }

    // ---------------------------------------------------------
    // MEMORIA
    // ---------------------------------------------------------

    private fun availableHeapBytes(): Long {
        val runtime = Runtime.getRuntime()

        val used =
            runtime.totalMemory() - runtime.freeMemory()

        return (
            runtime.maxMemory() - used
        ).coerceAtLeast(0L)
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
            Log.w(
                TAG,
                "Los diagnósticos requieren Android 10 o superior."
            )
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

            Log.i(
                TAG,
                "Diagnósticos originales guardados."
            )

        } catch (error: Exception) {
            Log.e(
                TAG,
                "Error guardando diagnósticos originales.",
                error
            )
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

            Log.i(
                TAG,
                "Diagnóstico instrumental guardado."
            )

        } catch (error: Exception) {
            Log.e(
                TAG,
                "Error guardando instrumentos limpios.",
                error
            )
        }
    }

    private fun saveDiagnosticWav(
        context: Context,
        displayName: String,
        writer: (OutputStream) -> Unit
    ) {
        val values = ContentValues().apply {
            put(
                MediaStore.MediaColumns.DISPLAY_NAME,
                displayName
            )

            put(
                MediaStore.MediaColumns.MIME_TYPE,
                "audio/wav"
            )

            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS +
                    "/I-A-M-Diagnostico"
            )

            put(
                MediaStore.MediaColumns.IS_PENDING,
                1
            )
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
                put(
                    MediaStore.MediaColumns.IS_PENDING,
                    0
                )
            }

            val updated = resolver.update(
                uri,
                completed,
                null,
                null
            )

            check(updated > 0) {
                "No se pudo completar el archivo $displayName"
            }

        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    // ---------------------------------------------------------
    // ESCRITURA WAV
    // ---------------------------------------------------------

    private fun writePcm16StereoWav(
        output: OutputStream,
        samples: ShortArray
    ) {
        val dataSize = samples.size.toLong() * 2L

        writeWavHeader(output, dataSize)

        val buffer = ByteBuffer
            .allocate(IO_BUFFER_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN)

        for (sample in samples) {

            if (buffer.remaining() < 2) {
                output.write(
                    buffer.array(),
                    0,
                    buffer.position()
                )
                buffer.clear()
            }

            buffer.putShort(sample)
        }

        if (buffer.position() > 0) {
            output.write(
                buffer.array(),
                0,
                buffer.position()
            )
        }
    }

    private fun writeFloatStereoWav(
        output: OutputStream,
        samples: FloatArray
    ) {
        val dataSize = samples.size.toLong() * 2L

        writeWavHeader(output, dataSize)

        val buffer = ByteBuffer
            .allocate(IO_BUFFER_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN)

        for (sample in samples) {

            if (buffer.remaining() < 2) {
                output.write(
                    buffer.array(),
                    0,
                    buffer.position()
                )
                buffer.clear()
            }

            buffer.putShort(toPcm16(sample))
        }

        if (buffer.position() > 0) {
            output.write(
                buffer.array(),
                0,
                buffer.position()
            )
        }
    }

    private fun writeWavHeader(
        output: OutputStream,
        dataSize: Long
    ) {
        require(dataSize >= 0L) {
            "Tamaño de audio inválido."
        }

        require(dataSize <= 0xFFFFFFFFL - 36L) {
            "El audio supera el tamaño máximo del formato WAV."
        }

        val header = ByteBuffer
            .allocate(44)
            .order(ByteOrder.LITTLE_ENDIAN)

        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt((36L + dataSize).toInt())

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
        header.putInt(dataSize.toInt())

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
            .allocate(IO_BUFFER_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN)

        FileOutputStream(file).use { output ->

            for (sample in samples) {

                if (buffer.remaining() < 4) {
                    output.write(
                        buffer.array(),
                        0,
                        buffer.position()
                    )
                    buffer.clear()
                }

                buffer.putFloat(
                    sample.toFloat() / 32768f
                )
            }

            if (buffer.position() > 0) {
                output.write(
                    buffer.array(),
                    0,
                    buffer.position()
                )
            }

            output.flush()
            output.fd.sync()
        }
    }

    // ---------------------------------------------------------
    // INFORMACIÓN DE WAV
    // ---------------------------------------------------------

    private data class WavDataInfo(
        val dataStart: Long,
        val dataSize: Long,
        val channels: Int,
        val bits: Int,
        val format: Int,
        val sampleRate: Long
    )

    private fun readWavDataInfo(
        raf: RandomAccessFile
    ): WavDataInfo {

        require(raf.length() >= 12L) {
            "Archivo WAV demasiado pequeño."
        }

        raf.seek(0)

        val header = ByteArray(12)
        raf.readFully(header)

        require(
            String(
                header,
                0,
                4,
                Charsets.US_ASCII
            ) == "RIFF" &&
            String(
                header,
                8,
                4,
                Charsets.US_ASCII
            ) == "WAVE"
        ) {
            "El archivo no es un WAV válido."
        }

        var format = -1
        var channels = -1
        var bits = -1
        var sampleRate = -1L

        var dataStart = -1L
        var dataSize = -1L

        while (raf.filePointer + 8L <= raf.length()) {

            val chunkId = ByteArray(4)
            raf.readFully(chunkId)

            val chunkSize = readUnsignedIntLE(raf)
            val chunkName = String(
                chunkId,
                Charsets.US_ASCII
            )

            val chunkStart = raf.filePointer

            require(chunkSize <= raf.length() - chunkStart) {
                "Bloque WAV incompleto."
            }

            when (chunkName) {

                "fmt " -> {
                    require(chunkSize >= 16L) {
                        "Formato WAV incompleto."
                    }

                    format = readUnsignedShortLE(raf)
                    channels = readUnsignedShortLE(raf)
                    sampleRate = readUnsignedIntLE(raf)

                    // byteRate, blockAlign y bitsPerSample.
                    raf.skipBytes(6)
                    bits = readUnsignedShortLE(raf)
                }

                "data" -> {
                    dataStart = chunkStart
                    dataSize = chunkSize
                }
            }

            val next =
                chunkStart + chunkSize + (chunkSize and 1L)

            if (next > raf.length()) break

            raf.seek(next)

            if (format >= 0 && dataStart >= 0L) break
        }

        require(format == 1) {
            "La pista no es PCM sin comprimir."
        }

        require(channels == 2 && bits == 16) {
            "La pista debe ser estéreo PCM de 16 bits."
        }

        require(sampleRate == OUTPUT_RATE.toLong()) {
            "La pista instrumental no está a 44.1 kHz."
        }

        require(dataStart >= 0L && dataSize >= 0L) {
            "El WAV no contiene datos de audio."
        }

        require(dataSize <= raf.length() - dataStart) {
            "El bloque de audio está incompleto."
        }

        require(dataSize % 4L == 0L) {
            "Los datos estéreo están incompletos."
        }

        return WavDataInfo(
            dataStart = dataStart,
            dataSize = dataSize,
            channels = channels,
            bits = bits,
            format = format,
            sampleRate = sampleRate
        )
    }

    // ---------------------------------------------------------
    // LECTOR DE VOZ WAV PCM 16 BITS ESTÉREO
    // ---------------------------------------------------------

    private fun readPcm16StereoWav(
        file: File
    ): ShortArray {

        require(file.isFile) {
            "No existe el archivo: ${file.name}"
        }

        RandomAccessFile(file, "r").use { raf ->

            val info = readWavDataInfo(raf)

            val sampleCountLong = info.dataSize / 2L

            require(
                sampleCountLong in 1..Int.MAX_VALUE.toLong()
            ) {
                "La pista es demasiado grande."
            }

            val sampleCount = sampleCountLong.toInt()

            require(sampleCount % 2 == 0) {
                "La pista WAV está incompleta."
            }

            val result = ShortArray(sampleCount)

            raf.seek(info.dataStart)

            val bytes = ByteArray(IO_BUFFER_SIZE)

            var index = 0
            var remaining = info.dataSize

            while (remaining > 0L) {

                checkNotInterrupted()

                val count = minOf(
                    bytes.size.toLong(),
                    remaining
                ).toInt()

                val evenCount = count - (count % 2)

                require(evenCount > 0) {
                    "Bloque PCM incompleto."
                }

                raf.readFully(
                    bytes,
                    0,
                    evenCount
                )

                val buffer = ByteBuffer
                    .wrap(bytes, 0, evenCount)
                    .order(ByteOrder.LITTLE_ENDIAN)

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

    // ---------------------------------------------------------
    // MEZCLA INSTRUMENTAL POR BLOQUES
    // ---------------------------------------------------------

    private fun addPcm16StereoWavToFloat(
        file: File,
        destination: FloatArray
    ) {
        require(file.isFile) {
            "No existe la pista: ${file.name}"
        }

        RandomAccessFile(file, "r").use { raf ->

            val info = readWavDataInfo(raf)

            require(info.dataSize / 2L == destination.size.toLong()) {
                "La duración de ${file.name} no coincide."
            }

            raf.seek(info.dataStart)

            val bytes = ByteArray(IO_BUFFER_SIZE)

            var index = 0
            var remaining = info.dataSize

            while (remaining > 0L) {

                checkNotInterrupted()

                val count = minOf(
                    bytes.size.toLong(),
                    remaining
                ).toInt()

                val evenCount = count - (count % 2)

                require(evenCount > 0) {
                    "Bloque instrumental incompleto."
                }

                raf.readFully(
                    bytes,
                    0,
                    evenCount
                )

                val buffer = ByteBuffer
                    .wrap(bytes, 0, evenCount)
                    .order(ByteOrder.LITTLE_ENDIAN)

                while (buffer.remaining() >= 2) {

                    require(index < destination.size) {
                        "La pista instrumental supera la duración esperada."
                    }

                    destination[index] +=
                        buffer.short.toFloat() / 32768f

                    index++
                }

                remaining -= evenCount
            }

            require(index == destination.size) {
                "No se pudo mezclar toda la pista ${file.name}"
            }
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

        if (input.isEmpty()) {
            return ShortArray(0)
        }

        if (inputRate == outputRate) {
            return input.copyOf()
        }

        val outputSizeLong = (
            input.size.toDouble() *
                outputRate.toDouble() /
                inputRate.toDouble()
        ).toLong()

        require(
            outputSizeLong in 1..Int.MAX_VALUE.toLong()
        ) {
            "El audio remuestreado es demasiado grande."
        }

        val output = ShortArray(outputSizeLong.toInt())

        val ratio =
            inputRate.toDouble() / outputRate.toDouble()

        for (i in output.indices) {

            if (i % 32768 == 0) {
                checkNotInterrupted()
            }

            val position = i * ratio

            val first = floor(position)
                .toInt()
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
