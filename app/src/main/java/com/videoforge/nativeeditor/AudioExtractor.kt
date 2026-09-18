package com.videoforge.nativeeditor

import android.content.ContentResolver
import android.content.ContentValues
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

/** Non-destructive extraction of the first AAC audio track from a video into M4A. */
object AudioExtractor {
    suspend fun extractToMediaStore(
        resolver: ContentResolver,
        source: Uri,
        displayName: String,
        onProgress: (Float) -> Unit = {}
    ): Result<Uri> = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        var outputUri: Uri? = null
        var pfd: android.os.ParcelFileDescriptor? = null
        var muxer: MediaMuxer? = null
        try {
            resolver.openFileDescriptor(source, "r")?.use {
                extractor.setDataSource(it.fileDescriptor)
            } ?: error("Unable to open source video")

            var audioTrack = -1
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                if (format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    audioTrack = i
                    break
                }
            }
            require(audioTrack >= 0) { "The selected video has no audio track" }

            val sourceFormat = extractor.getTrackFormat(audioTrack)
            require(sourceFormat.getString(MediaFormat.KEY_MIME) == "audio/mp4a-latm") {
                "Only AAC audio extraction to M4A is currently supported"
            }

            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME,
                    if (displayName.endsWith(".m4a", true)) displayName else "$displayName.m4a")
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
                put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/VideoForge")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            outputUri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("Unable to create output audio file")

            pfd = resolver.openFileDescriptor(outputUri, "rw")
                ?: error("Unable to open output audio file")
            muxer = MediaMuxer(pfd!!.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val track = muxer!!.addTrack(sourceFormat)
            muxer!!.start()
            extractor.selectTrack(audioTrack)

            val buffer = ByteBuffer.allocate(1024 * 1024)
            val info = android.media.MediaCodec.BufferInfo()
            val durationUs = if (sourceFormat.containsKey(MediaFormat.KEY_DURATION)) {
                sourceFormat.getLong(MediaFormat.KEY_DURATION)
            } else {
                1L
            }.coerceAtLeast(1L)

            while (true) {
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                info.offset = 0
                info.size = size
                info.presentationTimeUs = extractor.sampleTime.coerceAtLeast(0L)
                info.flags = extractor.sampleFlags
                muxer!!.writeSampleData(track, buffer, info)
                onProgress((info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f))
                if (!extractor.advance()) break
            }

            muxer!!.stop()
            muxer!!.release()
            muxer = null
            pfd!!.close()
            pfd = null
            resolver.update(outputUri!!,
                ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) },
                null, null)
            onProgress(1f)
            Result.success(outputUri!!)
        } catch (t: Throwable) {
            try { muxer?.stop() } catch (_: Throwable) {}
            try { muxer?.release() } catch (_: Throwable) {}
            try { pfd?.close() } catch (_: Throwable) {}
            outputUri?.let { try { resolver.delete(it, null, null) } catch (_: Throwable) {} }
            Result.failure(t)
        } finally {
            extractor.release()
        }
    }
}