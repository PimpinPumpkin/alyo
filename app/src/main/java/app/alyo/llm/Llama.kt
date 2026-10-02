package app.alyo.llm

import java.io.ByteArrayOutputStream
import java.io.File

/**
 * A chat language model running on the phone through llama.cpp. One instance holds the model in
 * memory (around a gigabyte), so load it for a job and [close] it afterwards. Not thread-safe:
 * use it from one thread, except [cancel].
 */
class Llama private constructor(private var handle: Long) : AutoCloseable {

    /** Receives the reply as it is written. Called on the generating thread. */
    interface Listener {
        /** While the model is still reading the prompt: [read] of [total] tokens. */
        fun onReading(read: Int, total: Int) {}

        /** Everything written so far. */
        fun onText(text: String) {}
    }

    fun countTokens(text: String): Int = nativeCountTokens(handle, text)

    /**
     * Answers [user] under the instructions in [system].
     *
     * @param opening words the reply is made to begin with. Small models drift from a requested
     *   format; starting the answer for them is the reliable way to hold them to it.
     * @param grammar rules in llama.cpp's GBNF form that the rest of the reply must follow, or
     *   empty for free text. Unlike instructions, a grammar cannot be ignored.
     * @return the reply, or null if it was cancelled
     * @throws IllegalStateException if the prompt does not fit the context or decoding failed
     */
    fun generate(system: String, user: String, maxTokens: Int, opening: String = "", grammar: String = "", listener: Listener = object : Listener {}): String? {
        val bytes = ByteArrayOutputStream()
        bytes.write(opening.toByteArray())
        val sink = object : Sink {
            override fun onProgress(read: Int, total: Int) = listener.onReading(read, total)
            override fun onPiece(piece: ByteArray) {
                bytes.write(piece)
                listener.onText(bytes.toString(Charsets.UTF_8).trimEnd('�'))
            }
        }
        return when (val code = nativeGenerate(handle, system, user, opening, grammar, maxTokens, sink)) {
            0 -> bytes.toString(Charsets.UTF_8)
            1 -> null
            -2 -> error("The text is too long for the model in one go")
            else -> error("The model failed while running ($code)")
        }
    }

    /** Stops a [generate] in progress. Safe from any thread. */
    fun cancel() {
        if (handle != 0L) nativeCancel(handle)
    }

    override fun close() {
        if (handle != 0L) nativeFree(handle)
        handle = 0
    }

    /** What the native side calls back into; kept from being renamed or removed by the shrinker. */
    interface Sink {
        fun onProgress(read: Int, total: Int)
        fun onPiece(piece: ByteArray)
    }

    companion object {
        /** True when the faster build of the engine is the one in use. */
        val fast: Boolean

        init {
            // Two builds of the engine ship. The faster one needs instructions that only chips
            // from about 2018 on have, and every core has to have them, so the processor's own
            // list of features is checked before it is loaded.
            val features = runCatching { File("/proc/cpuinfo").readLines().filter { it.startsWith("Features") }.map { it.substringAfter(':').trim().split(' ').toSet() } }.getOrDefault(emptyList())
            val capable = features.isNotEmpty() && features.all { "asimddp" in it && "fphp" in it && "asimdhp" in it }
            fast = capable && runCatching { System.loadLibrary("alyo-llm-dotprod") }.isSuccess
            if (!fast) System.loadLibrary("alyo-llm")
        }

        /**
         * @param writeThreads threads used while writing the reply, one token at a time
         * @param readThreads threads used while reading the transcript in, which parallelizes better
         * @return null if [file] is not a model llama.cpp can run
         */
        fun load(file: File, contextTokens: Int, writeThreads: Int, readThreads: Int): Llama? =
            nativeLoad(file.path, contextTokens, writeThreads, readThreads).takeIf { it != 0L }?.let(::Llama)

        @JvmStatic private external fun nativeLoad(path: String, contextTokens: Int, threads: Int, batchThreads: Int): Long
        @JvmStatic private external fun nativeCountTokens(handle: Long, text: String): Int
        @JvmStatic private external fun nativeGenerate(handle: Long, system: String, user: String, opening: String, grammar: String, maxTokens: Int, sink: Sink): Int
        @JvmStatic private external fun nativeCancel(handle: Long)
        @JvmStatic private external fun nativeFree(handle: Long)
    }
}
