package app.sotto.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Where 48 kHz mono 16-bit samples come from. Blocking; used from the recorder thread only. */
interface PcmSource {
    fun start()

    /** Suspends capture without releasing it; [start] resumes. */
    fun stop()

    /** Blocks until samples are available. Returns the count read, or a negative value when the source is exhausted or broken. */
    fun read(buffer: ShortArray): Int
    fun release()
}

/**
 * @param choose picks the input to record from out of those connected, or null to let Android
 *   route as it sees fit (which already prefers a wired or USB headset microphone)
 */
class MicSource(private val context: Context, private val choose: (List<AudioDeviceInfo>) -> AudioDeviceInfo? = { null }) : PcmSource {
    private val record: AudioRecord
    private val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var bluetooth = false

    init {
        val min = AudioRecord.getMinBufferSize(AudioSpec.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        // Half a second of headroom so a busy moment on the recorder thread never drops samples.
        val size = maxOf(min, AudioSpec.SAMPLE_RATE)
        val device = choose(manager.getDevices(AudioManager.GET_DEVICES_INPUTS).toList())
        bluetooth = device != null && device.type in BLUETOOTH_TYPES
        // A Bluetooth headset microphone only carries sound over the call link, which has to be
        // opened first and is the one case where the "communication" source is the right one.
        if (bluetooth) openBluetoothLink(device!!)
        @SuppressLint("MissingPermission") // The service refuses to start without RECORD_AUDIO.
        record = AudioRecord(
            // Tuned for speech recognition: no aggressive noise gating that would clip word onsets.
            if (bluetooth) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.VOICE_RECOGNITION,
            AudioSpec.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            size,
        )
        check(record.state == AudioRecord.STATE_INITIALIZED) { "Microphone unavailable" }
        if (device != null) record.preferredDevice = device
    }

    /** What is actually being recorded from, in words: "Phone", "Headset", or the Bluetooth device's name. */
    val inputName: String?
        get() = record.routedDevice?.let { device ->
            when (device.type) {
                AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Phone microphone"
                AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> "Headset microphone"
                in BLUETOOTH_TYPES -> device.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Bluetooth microphone"
                else -> null
            }
        }

    /** True when the system has muted this recording because something with priority (a call) has the microphone. */
    val isSilenced: Boolean get() = record.activeRecordingConfiguration?.isClientSilenced == true

    private fun openBluetoothLink(device: AudioDeviceInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            manager.setCommunicationDevice(device)
        } else {
            @Suppress("DEPRECATION")
            manager.startBluetoothSco()
        }
    }

    override fun start() = record.startRecording()
    override fun stop() = record.stop()
    override fun read(buffer: ShortArray): Int = record.read(buffer, 0, buffer.size)

    override fun release() {
        record.release()
        if (bluetooth) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                manager.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                manager.stopBluetoothSco()
            }
        }
    }

    companion object {
        val BLUETOOTH_TYPES = setOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET)
    }
}

/**
 * Plays a WAV file through the recording pipeline at real-time speed, as if it were the microphone.
 * Debug builds only: it lets the whole capture -> transcribe -> save path be exercised on a device
 * with nobody speaking into it. Accepts 16-bit mono at 48 kHz or 16 kHz (upsampled by repetition).
 */
class WavFileSource(file: File) : PcmSource {
    private val raf = RandomAccessFile(file, "r")
    private val upsample: Int
    private var startedAtNs = 0L
    private var delivered = 0L
    private val bytes = ByteArray(8192)

    init {
        // Walk the RIFF chunks: encoders are free to put padding or metadata before the samples.
        val word = ByteArray(8)
        raf.seek(12)
        var rate = 0
        while (true) {
            raf.readFully(word)
            val id = String(word, 0, 4, Charsets.US_ASCII)
            val size = ByteBuffer.wrap(word, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (id == "data") break
            if (id == "fmt ") {
                val fmt = ByteArray(size)
                raf.readFully(fmt)
                rate = ByteBuffer.wrap(fmt, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            } else {
                raf.seek(raf.filePointer + size)
            }
        }
        require(rate == AudioSpec.SAMPLE_RATE || rate == AudioSpec.ASR_SAMPLE_RATE) { "Unsupported WAV rate $rate" }
        upsample = AudioSpec.SAMPLE_RATE / rate
    }

    override fun start() {
        startedAtNs = System.nanoTime() - AudioSpec.samplesToUs(delivered) * 1000
    }

    override fun stop() = Unit

    override fun read(buffer: ShortArray): Int {
        val want = minOf(buffer.size / upsample, bytes.size / 2)
        val got = raf.read(bytes, 0, want * 2)
        if (got <= 0) return -1
        val samples = got / 2
        val shorts = ByteBuffer.wrap(bytes, 0, samples * 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        var o = 0
        for (i in 0 until samples) {
            val s = shorts.get(i)
            repeat(upsample) { buffer[o++] = s }
        }
        delivered += o
        val dueNs = startedAtNs + AudioSpec.samplesToUs(delivered) * 1000
        val waitMs = (dueNs - System.nanoTime()) / 1_000_000
        if (waitMs > 0) Thread.sleep(waitMs)
        return o
    }

    override fun release() = raf.close()
}
