package com.videoforge.nativeeditor

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList

enum class VideoFormat(val label: String, val extension: String, val mime: String) {
    MP4("MP4", "mp4", "video/avc")
}

enum class ExportResolution(val label: String, val width: Int, val height: Int) {
    ORIGINAL("الأصلية", 0, 0),
    P360("360p", 640, 360),
    P480("480p", 854, 480),
    P720("720p HD", 1280, 720),
    P1080("1080p Full HD", 1920, 1080),
    P1440("1440p 2K", 2560, 1440),
    P2160("2160p 4K", 3840, 2160)
}

enum class ExportFps(val value: Int) {
    FPS24(24), FPS25(25), FPS30(30), FPS50(50), FPS60(60)
}

enum class ExportQuality(val label: String, val multiplier: Float) {
    AUTO("تلقائي", 1.0f),
    LOW("اقتصادي", 0.65f),
    MEDIUM("متوازن", 1.0f),
    HIGH("مرتفع", 1.45f),
    MAX("أعلى جودة", 1.8f)
}

data class ExportSettings(
    val format: VideoFormat = VideoFormat.MP4,
    val resolution: ExportResolution = ExportResolution.ORIGINAL,
    val fps: ExportFps = ExportFps.FPS30,
    val quality: ExportQuality = ExportQuality.MEDIUM,
    val hevc: Boolean = false,
    val audioBitrate: Int = 192_000
)

object CodecCapabilities {
    fun supportsHevc(): Boolean =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            info.isEncoder && info.supportedTypes.any { it.equals("video/hevc", true) }
        }

    fun supportsAvcEncoder(): Boolean =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            info.isEncoder && info.supportedTypes.any { it.equals("video/avc", true) }
        }
}
