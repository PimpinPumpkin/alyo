package app.alyo.asr

import android.util.Log
import java.io.File

/**
 * Rewrites a speech model in the form ONNX Runtime would otherwise work out every time it loads
 * the model. Done once after a download, it roughly halves the wait before the first words.
 * The words that come out are unchanged.
 */
object ModelOptimizer {
    private val available: Boolean by lazy {
        runCatching {
            System.loadLibrary("onnxruntime")
            System.loadLibrary("alyo-ort")
        }.onFailure { Log.w(TAG, "Not available", it) }.isSuccess
    }

    /**
     * Replaces [file] with its optimized form. The original is left alone if anything goes wrong,
     * so the worst case is the model loading at its old speed.
     *
     * @return true if the file is now the optimized one
     */
    fun optimize(file: File): Boolean {
        if (!available || !file.exists()) return false
        val temp = File(file.parentFile, file.name + ".opt")
        return try {
            val ok = nativeOptimize(file.path, temp.path) && temp.length() > 0
            // Swapped in one step, so a model being loaded at this moment reads one or the other whole.
            ok && temp.renameTo(file)
        } catch (e: Throwable) {
            Log.w(TAG, "Could not optimize ${file.name}", e)
            false
        } finally {
            temp.delete()
        }
    }

    @JvmStatic private external fun nativeOptimize(input: String, output: String): Boolean

    private const val TAG = "ModelOptimizer"
}
