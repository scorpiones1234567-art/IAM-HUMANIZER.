
package com.iam.humanizer

import android.content.Context
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * I-A-M - DESCARGADOR DEL MODELO DEMUCS
 *
 * Descarga el modelo htdemucs_6s y verifica su SHA-256.
 * La descarga debe ejecutarse desde un hilo de fondo.
 */
object DemucsModelManager {

    private const val MODEL_NAME = "5c90dfd2.safetensors"

    private const val MODEL_URL =
        "https://huggingface.co/adefossez/HTDemucs-6s/resolve/3c5ee475be622df764938de97e4281a7b07ffa58/5c90dfd2.safetensors"

    private const val EXPECTED_SHA256 =
        "d2a1745f0744721f6b8ca5bf469b67c651ea5ed1b52998cab033b2158609d411"

    /**
     * Devuelve el modelo local.
     *
     * Si todavía no existe, lo descarga y verifica.
     * onProgress recibe un porcentaje de 0 a 100.
     */
    @Synchronized
    fun getModel(
        context: Context,
        onProgress: ((Int) -> Unit)? = null
    ): File {

        val directory = File(context.filesDir, "demucs")
        if (!directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException(
                "No se pudo crear la carpeta del modelo."
            )
        }

        val modelFile = File(directory, MODEL_NAME)

        if (modelFile.exists() && verifyFile(modelFile)) {
            onProgress?.invoke(100)
            return modelFile
        }

        if (modelFile.exists()) {
            modelFile.delete()
        }

        val temporaryFile = File(directory, "$MODEL_NAME.download")

        var connection: HttpURLConnection? = null

        try {
            connection = URL(MODEL_URL)
                .openConnection() as HttpURLConnection

            connection.connectTimeout = 30000
            connection.readTimeout = 60000
            connection.instanceFollowRedirects = true
            connection.requestMethod = "GET"

            connection.connect()

            val response = connection.responseCode

            if (response !in 200..299) {
                throw IllegalStateException(
                    "Error descargando el modelo. HTTP $response"
                )
            }

            val totalBytes = connection.contentLengthLong
            var downloadedBytes = 0L

            val digest = MessageDigest.getInstance("SHA-256")

            BufferedInputStream(connection.inputStream).use { input ->
                FileOutputStream(temporaryFile).use { output ->

                    val buffer = ByteArray(64 * 1024)

                    while (true) {
                        val count = input.read(buffer)

                        if (count == -1) break

                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)

                        downloadedBytes += count

                        if (totalBytes > 0) {
                            val percent =
                                (downloadedBytes * 100L / totalBytes)
                                    .toInt()
                                    .coerceIn(0, 100)

                            onProgress?.invoke(percent)
                        }
                    }

                    output.flush()
                    output.fd.sync()
                }
            }

            val actualHash = digest.digest().joinToString("") {
                "%02x".format(it)
            }

            if (!actualHash.equals(EXPECTED_SHA256, ignoreCase = true)) {
                throw IllegalStateException(
                    "El modelo descargado no pasó la verificación SHA-256."
                )
            }

            if (!temporaryFile.renameTo(modelFile)) {
                throw IllegalStateException(
                    "No se pudo guardar el modelo descargado."
                )
            }

            onProgress?.invoke(100)

            return modelFile

        } catch (error: Exception) {
            temporaryFile.delete()
            throw error

        } finally {
            connection?.disconnect()
        }
    }

    private fun verifyFile(file: File): Boolean {
        if (!file.isFile || file.length() == 0L) {
            return false
        }

        val digest = MessageDigest.getInstance("SHA-256")

        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)

            while (true) {
                val count = input.read(buffer)
                if (count == -1) break

                digest.update(buffer, 0, count)
            }
        }

        val actualHash = digest.digest().joinToString("") {
            "%02x".format(it)
        }

        return actualHash.equals(
            EXPECTED_SHA256,
            ignoreCase = true
        )
    }
}
