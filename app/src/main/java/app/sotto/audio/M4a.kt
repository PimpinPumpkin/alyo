package app.sotto.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import kotlin.math.roundToLong

/** Lossless container work on recordings: nothing here decodes or re-encodes audio. */
object M4a {
    private const val FRAME_US = AudioSpec.AAC_FRAME_SAMPLES * 1_000_000.0 / AudioSpec.SAMPLE_RATE

    /** Milliseconds of audio in one AAC frame (~21.3); the finest grain a cut can land on. */
    const val FRAME_MS = AudioSpec.AAC_FRAME_SAMPLES * 1000.0 / AudioSpec.SAMPLE_RATE

    /**
     * Wraps the crash-safe ADTS stream written during recording into a seekable `.m4a`.
     * Tolerates a truncated final frame, which is what a killed process leaves behind.
     *
     * @return the duration in milliseconds, or 0 if [adts] held no complete frame (no file is left behind then).
     */
    fun fromAdts(adts: File, m4a: File): Long {
        var frames = 0L
        var muxer: MediaMuxer? = null
        var track = -1
        val info = MediaCodec.BufferInfo()
        val header = ByteArray(Adts.HEADER_SIZE)
        var payload = ByteBuffer.allocate(4096)
        try {
            DataInputStream(BufferedInputStream(FileInputStream(adts), 256 * 1024)).use { input ->
                while (true) {
                    try {
                        input.readFully(header)
                    } catch (e: EOFException) {
                        break
                    }
                    val size = Adts.frameLength(header) - Adts.HEADER_SIZE
                    if (size <= 0) break
                    if (payload.capacity() < size) payload = ByteBuffer.allocate(size * 2)
                    try {
                        input.readFully(payload.array(), 0, size)
                    } catch (e: EOFException) {
                        break
                    }
                    val out = muxer ?: MediaMuxer(m4a.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).also {
                        track = it.addTrack(aacFormat())
                        it.start()
                        muxer = it
                    }
                    payload.position(0).limit(size)
                    info.set(0, size, (frames * FRAME_US).roundToLong(), MediaCodec.BUFFER_FLAG_KEY_FRAME)
                    out.writeSampleData(track, payload, info)
                    frames++
                }
            }
        } finally {
            muxer?.let {
                runCatching { it.stop() }
                it.release()
            }
        }
        if (frames == 0L) m4a.delete()
        return (frames * FRAME_MS).roundToLong()
    }

    fun durationMs(file: File): Long {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.path)
            (0 until extractor.trackCount).maxOfOrNull { i ->
                val format = extractor.getTrackFormat(i)
                if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) / 1000 else 0L
            } ?: 0L
        } catch (e: Exception) {
            0L
        } finally {
            extractor.release()
        }
    }

    /** Snaps [ms] to the nearest AAC frame boundary, which is where a cut will really happen. */
    fun snapToFrame(ms: Long): Long = ((ms / FRAME_MS).roundToLong() * FRAME_MS).roundToLong()

    /**
     * Copies only the audio inside [keep] (spans on the source timeline, ascending and
     * non-overlapping) into [dst], closing the gaps. Used for both "crop to selection" and
     * "remove selection".
     *
     * @return the new duration in milliseconds.
     */
    fun copyRanges(src: File, dst: File, keep: List<Span>): Long {
        val extractor = MediaExtractor()
        extractor.setDataSource(src.path)
        val trackIndex = (0 until extractor.trackCount).first {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        }
        extractor.selectTrack(trackIndex)
        val format = extractor.getTrackFormat(trackIndex)
        val muxer = MediaMuxer(dst.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val outTrack = muxer.addTrack(format)
        muxer.start()

        val maxSize = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0
        val buffer = ByteBuffer.allocate(maxOf(maxSize, 64 * 1024))
        val info = MediaCodec.BufferInfo()
        val keepFrames = keep.map { (it.startMs / FRAME_MS).roundToLong() until (it.endMs / FRAME_MS).roundToLong() }
        var written = 0L
        try {
            for (range in keepFrames) {
                if (range.isEmpty()) continue
                extractor.seekTo((range.first * FRAME_US).roundToLong(), MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                while (true) {
                    val time = extractor.sampleTime
                    if (time < 0) break
                    val frame = (time / FRAME_US).roundToLong()
                    if (frame > range.last) break
                    if (frame >= range.first) {
                        buffer.clear()
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) break
                        info.set(0, size, (written * FRAME_US).roundToLong(), MediaCodec.BUFFER_FLAG_KEY_FRAME)
                        muxer.writeSampleData(outTrack, buffer, info)
                        written++
                    }
                    if (!extractor.advance()) break
                }
            }
        } finally {
            extractor.release()
            if (written > 0) runCatching { muxer.stop() }
            muxer.release()
        }
        if (written == 0L) dst.delete()
        return (written * FRAME_MS).roundToLong()
    }

    private fun aacFormat(): MediaFormat =
        MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, AudioSpec.SAMPLE_RATE, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, AudioSpec.AAC_BITRATE)
            setByteBuffer("csd-0", ByteBuffer.wrap(Adts.AUDIO_SPECIFIC_CONFIG))
        }
}
