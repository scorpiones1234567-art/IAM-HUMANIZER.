package com.iam.humanizer

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.OutputStream

/**
 * I-A-M - MUSIC LIBRARY 1.1
 *
 * 1) Lista las canciones del teléfono (como un reproductor de música).
 * 2) Puede mostrar solo las canciones guardadas por I-A-M.
 * 3) Guarda las exportaciones automáticamente en Música/I-A-M
 *    (Android crea la carpeta la primera vez y luego la reutiliza).
 *
 * Permisos para LEER la biblioteca:
 * - Android 13 o superior: READ_MEDIA_AUDIO
 * - Android 12 o inferior: READ_EXTERNAL_STORAGE
 * Para GUARDAR en la carpeta (Android 10+) no hace falta ningún permiso.
 *
 * Las funciones pueden tardar: llámalas desde un hilo secundario.
 */
object MusicLibrary {

    private const val TAG = "IAM_LIBRARY"

    const val IAM_FOLDER = "I-A-M"

    // Se ignoran audios menores a 30 segundos (sonidos, avisos).
    private const val MIN_DURATION_MS = 30_000L

    data class Track(
        val id: Long,
        val title: String,
        val artist: String,
        val durationMs: Long,
        val uri: Uri
    ) {

        val durationText: String
            get() {
                val total = (durationMs / 1000L).toInt()
                return "%d:%02d".format(total / 60, total % 60)
            }

        val label: String
            get() = if (artist.isBlank()) {
                "$title\n$durationText"
            } else {
                "$title\n$artist  ·  $durationText"
            }
    }

    // ---------------------------------------------------------
    // LISTAR CANCIONES
    // ---------------------------------------------------------

    fun query(
        context: Context,
        onlyIamFolder: Boolean = false
    ): List<Track> {

        val tracks = ArrayList<Track>()

        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION
        )

        var selection: String
        val arguments = ArrayList<String>()

        if (onlyIamFolder && Build.VERSION.SDK_INT >= 29) {

            // Solo lo guardado por I-A-M (sin límite de duración).
            selection =
                "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?"

            arguments.add(
                "${Environment.DIRECTORY_MUSIC}/$IAM_FOLDER/%"
            )

        } else if (onlyIamFolder) {

            // Android 9 o inferior: no hay carpeta automática.
            return emptyList()

        } else {

            selection =
                "${MediaStore.Audio.Media.DURATION} >= ? AND " +
                    "${MediaStore.Audio.Media.IS_RINGTONE} = 0 AND " +
                    "${MediaStore.Audio.Media.IS_NOTIFICATION} = 0 AND " +
                    "${MediaStore.Audio.Media.IS_ALARM} = 0"

            arguments.add(MIN_DURATION_MS.toString())
        }

        val sortOrder =
            "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"

        try {
            context.contentResolver.query(
                collection,
                projection,
                selection,
                arguments.toTypedArray(),
                sortOrder
            )?.use { cursor ->

                val idColumn = cursor.getColumnIndexOrThrow(
                    MediaStore.Audio.Media._ID
                )
                val titleColumn = cursor.getColumnIndexOrThrow(
                    MediaStore.Audio.Media.TITLE
                )
                val artistColumn = cursor.getColumnIndexOrThrow(
                    MediaStore.Audio.Media.ARTIST
                )
                val durationColumn = cursor.getColumnIndexOrThrow(
                    MediaStore.Audio.Media.DURATION
                )

                while (cursor.moveToNext()) {

                    val id = cursor.getLong(idColumn)

                    val title = cursor.getString(titleColumn)
                        ?.trim()
                        .orEmpty()
                        .ifBlank { "Sin título" }

                    var artist = cursor.getString(artistColumn)
                        ?.trim()
                        .orEmpty()

                    if (artist.equals("<unknown>", ignoreCase = true)) {
                        artist = ""
                    }

                    val duration = cursor.getLong(durationColumn)

                    tracks.add(
                        Track(
                            id = id,
                            title = title,
                            artist = artist,
                            durationMs = duration,
                            uri = ContentUris.withAppendedId(collection, id)
                        )
                    )
                }
            }

        } catch (error: Exception) {
            Log.e(TAG, "No se pudo leer la biblioteca de música.", error)
        }

        return tracks
    }

    // ---------------------------------------------------------
    // GUARDAR EN LA CARPETA I-A-M
    // ---------------------------------------------------------

    /**
     * Guarda un audio en Música/I-A-M.
     * Devuelve la dirección del archivo, o null si el teléfono es
     * Android 9 o inferior (en ese caso se usa el selector de siempre).
     *
     * mimeType: "audio/mpeg" para MP3, "audio/x-wav" para WAV.
     */
    fun saveAudioToIamFolder(
        context: Context,
        displayName: String,
        mimeType: String,
        writer: (OutputStream) -> Unit
    ): Uri? {

        if (Build.VERSION.SDK_INT < 29) return null

        return saveToMediaStore(
            context = context,
            collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            relativePath =
                Environment.DIRECTORY_MUSIC + "/" + IAM_FOLDER,
            displayName = displayName,
            mimeType = mimeType,
            writer = writer
        )
    }

    /**
     * Guarda la carátula JPG de un WAV en Imágenes/I-A-M.
     * Devuelve null en Android 9 o inferior.
     */
    fun saveCoverToIamFolder(
        context: Context,
        displayName: String,
        jpeg: ByteArray
    ): Uri? {

        if (Build.VERSION.SDK_INT < 29) return null

        return saveToMediaStore(
            context = context,
            collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            relativePath =
                Environment.DIRECTORY_PICTURES + "/" + IAM_FOLDER,
            displayName = displayName,
            mimeType = "image/jpeg",
            writer = { output -> output.write(jpeg) }
        )
    }

    private fun saveToMediaStore(
        context: Context,
        collection: Uri,
        relativePath: String,
        displayName: String,
        mimeType: String,
        writer: (OutputStream) -> Unit
    ): Uri {

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val resolver = context.contentResolver

        val uri = resolver.insert(collection, values)
            ?: throw IllegalStateException(
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

            return uri

        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
    }
}
