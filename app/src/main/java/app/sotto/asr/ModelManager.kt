package app.sotto.asr

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

sealed interface InstallState {
    data object Missing : InstallState
    data class Downloading(val doneBytes: Long, val totalBytes: Long) : InstallState {
        val fraction: Float get() = if (totalBytes > 0) (doneBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
    }
    data object Installed : InstallState
    data class Failed(val message: String) : InstallState
}

/**
 * Downloads speech models into app-private storage and tracks which are ready. This is the only
 * code in the app that touches the network.
 */
class ModelManager(context: Context, private val scope: CoroutineScope) {
    private val root = File(context.filesDir, "models").apply { mkdirs() }
    private val jobs = HashMap<String, Job>()

    // Downloads the user started and has not paused. A download cut short by the app being
    // closed is picked up again at the next launch; one they paused stays paused.
    private val prefs = context.getSharedPreferences("models", Context.MODE_PRIVATE)
    private var wanted: Set<String>
        get() = prefs.getStringSet(WANTED, emptySet())!!
        set(value) = prefs.edit().putStringSet(WANTED, value).apply()

    private val _states = MutableStateFlow<Map<String, InstallState>>(emptyMap())
    val states: StateFlow<Map<String, InstallState>> = _states

    init {
        _states.value = ModelCatalog.allModels
            .associate { it.id to if (isInstalled(it)) InstallState.Installed else InstallState.Missing }
    }

    /** Continues any download that was interrupted rather than paused. */
    fun resumeInterrupted() {
        val unfinished = wanted
        ModelCatalog.allModels
            .filter { it.id in unfinished }
            .forEach(::install)
    }

    fun dir(spec: ModelSpec): File = File(root, spec.id)
    fun isInstalled(spec: ModelSpec): Boolean = File(dir(spec), DONE).exists()
    fun state(spec: ModelSpec): InstallState = _states.value[spec.id] ?: InstallState.Missing

    @Synchronized
    fun install(spec: ModelSpec) {
        if (isInstalled(spec) || jobs[spec.id]?.isActive == true) return
        wanted = wanted + spec.id
        set(spec, InstallState.Downloading(0, spec.source.downloadBytes))
        jobs[spec.id] = scope.launch(Dispatchers.IO) {
            try {
                val dir = dir(spec).apply { mkdirs() }
                when (val source = spec.source) {
                    is ModelSource.Files -> downloadFiles(spec, source, dir)
                    is ModelSource.Tarball -> downloadTarball(spec, source, dir)
                }
                File(dir, DONE).createNewFile()
                wanted = wanted - spec.id
                set(spec, InstallState.Installed)
            } catch (e: CancellationException) {
                set(spec, InstallState.Missing)
                throw e
            } catch (e: Exception) {
                set(spec, InstallState.Failed(e.message ?: e.javaClass.simpleName))
            }
        }
    }

    /** Pauses a download. What has arrived is kept, so [install] carries on from there. */
    @Synchronized
    fun cancel(spec: ModelSpec) {
        wanted = wanted - spec.id
        jobs.remove(spec.id)?.cancel()
    }

    @Synchronized
    fun delete(spec: ModelSpec) {
        wanted = wanted - spec.id
        jobs.remove(spec.id)?.cancel()
        dir(spec).deleteRecursively()
        set(spec, InstallState.Missing)
    }

    /**
     * Installs a model from a `.tar.bz2` the user already has: the same archive sherpa-onnx
     * publishes, fetched on another device or by other means. This is the only way in for the
     * offline build, and lets anyone avoid the in-app download.
     *
     * @return the model that was installed
     * @throws IOException if the file is not an archive of a model this version uses
     */
    fun importArchive(source: InputStream): ModelSpec {
        val staging = File(root, ".import").apply { deleteRecursively(); mkdirs() }
        val input = BufferedInputStream(source, 256 * 1024)
        try {
            // A summary model is a single GGUF file rather than an archive: tell by its first bytes.
            input.mark(4)
            val magic = ByteArray(4).also { input.read(it) }
            input.reset()
            if (String(magic, Charsets.US_ASCII) == "GGUF") return importGguf(input, staging)
            // The speaker model is a bare ONNX file: recognized by matching its pinned hash.
            if (magic[0] != 'B'.code.toByte() || magic[1] != 'Z'.code.toByte()) return importSpeakers(input, staging)

            var spec: ModelSpec? = null
            TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(input, 256 * 1024))).use { tar ->
                while (true) {
                    val entry = tar.nextEntry ?: break
                    val parts = entry.name.removePrefix("./").split('/')
                    if (spec == null) {
                        spec = ModelCatalog.allModels.firstOrNull { it.archive == parts.first() }
                            ?: throw IOException("This is not a speech model Sotto Recorder uses.")
                    }
                    val name = parts.last()
                    val wanted = spec?.let { setOf(it.encoder, it.decoder, it.joiner, it.tokens) }.orEmpty()
                    if (entry.isDirectory || parts.size != 2 || name !in wanted) continue
                    File(staging, name).outputStream().use { tar.copyTo(it, 256 * 1024) }
                }
            }
            val found = spec ?: throw IOException("The file is empty or not a .tar.bz2 archive.")
            val needed = listOf(found.encoder, found.decoder, found.joiner, found.tokens)
            if (needed.any { !File(staging, it).exists() }) throw IOException("The archive is missing files. It may be incomplete.")
            // Where hashes are pinned, an imported file is held to them just as a download is.
            (found.source as? ModelSource.Files)?.files?.forEach { file ->
                if (file.sha256 != null && sha256(File(staging, file.name)) != file.sha256) {
                    throw IOException("${file.name} does not match the expected model. The file may be damaged.")
                }
            }
            synchronized(this) {
                jobs.remove(found.id)?.cancel()
                val dir = dir(found).apply { deleteRecursively(); mkdirs() }
                needed.forEach { if (!File(staging, it).renameTo(File(dir, it))) throw IOException("Could not save $it") }
                File(dir, DONE).createNewFile()
                wanted = wanted - found.id
                set(found, InstallState.Installed)
            }
            return found
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun importSpeakers(input: InputStream, staging: File): ModelSpec {
        val spec = ModelCatalog.speakers
        val wanted = (spec.source as ModelSource.Files).files.single()
        val file = File(staging, wanted.name)
        file.outputStream().use { input.copyTo(it, 1 shl 20) }
        if (sha256(file) != wanted.sha256) throw IOException("This is not a model file Sotto Recorder uses.")
        synchronized(this) {
            jobs.remove(spec.id)?.cancel()
            val dir = dir(spec).apply { deleteRecursively(); mkdirs() }
            if (!file.renameTo(File(dir, wanted.name))) throw IOException("Could not save the model")
            File(dir, DONE).createNewFile()
            this.wanted = this.wanted - spec.id
            set(spec, InstallState.Installed)
        }
        return spec
    }

    /** One of the listed summary models if the file is exactly that, otherwise kept as the user's own. */
    private fun importGguf(input: InputStream, staging: File): ModelSpec {
        val file = File(staging, ModelCatalog.GGUF)
        file.outputStream().use { input.copyTo(it, 1 shl 20) }
        val hash = sha256(file)
        val spec = ModelCatalog.summarizers.firstOrNull { (it.source as ModelSource.Files).files.single().sha256 == hash }
            ?: ModelCatalog.importedSummarizer
        synchronized(this) {
            jobs.remove(spec.id)?.cancel()
            val dir = dir(spec).apply { deleteRecursively(); mkdirs() }
            if (!file.renameTo(File(dir, ModelCatalog.GGUF))) throw IOException("Could not save the model")
            File(dir, DONE).createNewFile()
            wanted = wanted - spec.id
            set(spec, InstallState.Installed)
        }
        return spec
    }

    private fun set(spec: ModelSpec, state: InstallState) = _states.update { it + (spec.id to state) }

    private suspend fun downloadFiles(spec: ModelSpec, source: ModelSource.Files, dir: File) {
        val total = source.downloadBytes
        var before = 0L
        for (file in source.files) {
            val target = File(dir, file.name)
            if (!(target.exists() && target.length() == file.bytes)) {
                val part = File(dir, file.name + ".part")
                fetchResumable(file, part) { done -> set(spec, InstallState.Downloading(before + done, total)) }
                if (file.sha256 != null && sha256(part) != file.sha256) {
                    part.delete()
                    throw IOException("${file.name} was corrupted in transit. Try again.")
                }
                if (!part.renameTo(target)) throw IOException("Could not save ${file.name}")
            }
            before += file.bytes
            set(spec, InstallState.Downloading(before, total))
        }
    }

    /** Appends to [part] from wherever a previous attempt stopped. */
    private suspend fun fetchResumable(file: ModelFile, part: File, onProgress: (Long) -> Unit) {
        var have = if (part.exists()) part.length() else 0L
        if (have > file.bytes) {
            part.delete()
            have = 0
        }
        if (have == file.bytes) return
        val connection = open(file.url, rangeFrom = have)
        try {
            if (connection.responseCode == HttpURLConnection.HTTP_OK && have > 0) {
                // Server ignored the range request; start over.
                part.delete()
                have = 0
            } else if (connection.responseCode !in 200..299) {
                throw IOException("Download failed (HTTP ${connection.responseCode})")
            }
            RandomAccessFile(part, "rw").use { out ->
                out.seek(have)
                connection.inputStream.use { input ->
                    val buffer = ByteArray(256 * 1024)
                    var lastReport = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        have += n
                        if (have - lastReport >= REPORT_EVERY) {
                            lastReport = have
                            onProgress(have)
                        }
                    }
                }
            }
            if (have != file.bytes) throw IOException("Connection dropped. Try again to resume.")
        } finally {
            connection.disconnect()
        }
    }

    /** Streams the archive straight into its files: nothing but the wanted entries ever hits storage. */
    private suspend fun downloadTarball(spec: ModelSpec, source: ModelSource.Tarball, dir: File) {
        val connection = open(source.url, rangeFrom = 0)
        try {
            if (connection.responseCode !in 200..299) throw IOException("Download failed (HTTP ${connection.responseCode})")
            val counting = CountingStream(connection.inputStream)
            var lastReport = 0L
            val found = HashSet<String>()
            TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(counting, 256 * 1024))).use { tar ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    val entry = tar.nextEntry ?: break
                    val name = entry.name.substringAfterLast('/')
                    if (entry.isDirectory || name !in source.keep) continue
                    File(dir, name).outputStream().use { out ->
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = tar.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            if (counting.count - lastReport >= REPORT_EVERY) {
                                lastReport = counting.count
                                set(spec, InstallState.Downloading(counting.count, source.downloadBytes))
                            }
                        }
                    }
                    found += name
                    if (found.size == source.keep.size) break
                }
            }
            if (found != source.keep) throw IOException("Download was incomplete. Try again.")
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String, rangeFrom: Long): HttpURLConnection {
        var location = url
        // Followed by hand: release assets hop across hosts, and the range header has to survive every hop.
        repeat(6) {
            val connection = (URL(location).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "Sotto")
                if (rangeFrom > 0) setRequestProperty("Range", "bytes=$rangeFrom-")
            }
            val code = connection.responseCode
            if (code !in 300..399) return connection
            val next = connection.getHeaderField("Location") ?: throw IOException("Bad redirect")
            connection.disconnect()
            location = URL(URL(location), next).toString()
        }
        throw IOException("Too many redirects")
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private class CountingStream(input: InputStream) : FilterInputStream(input) {
        @Volatile var count = 0L
        override fun read(): Int = super.read().also { if (it >= 0) count++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }
    }

    private companion object {
        const val DONE = ".installed"
        const val WANTED = "wanted"
        const val REPORT_EVERY = 512 * 1024L
    }
}
