
package io.github.jpaauwe.demucsengine

object NativeEngine {

    fun interface ProgressListener {
        fun onProgress(fraction: Float, stage: String): Boolean
    }

    init {
        System.loadLibrary("sdengine_jni")
    }

    val SOURCES: List<String> =
        listOf("drums", "bass", "other", "vocals", "guitar", "piano")

    @JvmStatic
    private external fun nativeLoadModel(path: String): String

    @JvmStatic
    private external fun nativePrepareInput(
        raw: String,
        sampleRate: Int,
        channels: Int,
        out: String,
        listener: ProgressListener?
    ): String

    @JvmStatic
    private external fun nativeAnalyze(path: String): DoubleArray

    @JvmStatic
    private external fun nativeSeparate(
        prepared: String,
        outDir: String,
        write: BooleanArray,
        threads: Int,
        computeThreads: Int,
        gpu: Boolean,
        listener: ProgressListener?
    ): DoubleArray

    @JvmStatic
    private external fun nativeComputeInfo(
        computeThreads: Int,
        gpu: Boolean
    ): String

    @JvmStatic
    private external fun nativeCancel()

    @Synchronized
    fun loadModel(path: String) {
        val error = nativeLoadModel(path)
        if (error.isNotEmpty()) {
            throw IllegalStateException(error)
        }
    }

    fun prepareInput(
        raw: String,
        sampleRate: Int,
        channels: Int,
        out: String,
        listener: ProgressListener? = null
    ) {
        val error = nativePrepareInput(
            raw, sampleRate, channels, out, listener
        )
        if (error.isNotEmpty()) {
            throw IllegalStateException(error)
        }
    }

    data class InputStats(
        val durationSec: Double,
        val peakDb: Double?,
        val lufs: Double?
    )

    fun analyze(prepared: String): InputStats {
        val values = nativeAnalyze(prepared)
        return InputStats(
            values[0],
            values[1].takeUnless { it.isNaN() },
            values[2].takeUnless { it.isNaN() }
        )
    }

    fun separate(
        prepared: String,
        outDir: String,
        selected: Set<String> = SOURCES.toSet(),
        threads: Int = 1,
        computeThreads: Int = 0,
        gpu: Boolean = false,
        listener: ProgressListener? = null
    ): DoubleArray {

        val write = BooleanArray(SOURCES.size) {
            SOURCES[it] in selected
        }

        return nativeSeparate(
            prepared,
            outDir,
            write,
            threads,
            computeThreads,
            gpu,
            listener
        )
    }

    fun computeInfo(
        gpu: Boolean = false,
        computeThreads: Int = 0
    ): String {
        return nativeComputeInfo(computeThreads, gpu)
    }

    fun cancel() {
        nativeCancel()
    }
}
