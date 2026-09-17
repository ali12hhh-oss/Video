package com.videoforge.nativeeditor

import android.net.Uri

data class ClipAudioKeyframe(
    val timeMs: Long = 0L,
    val volume: Float = 1f
)

data class Clip(
    val uri: Uri,
    val name: String,
    val durationMs: Long = 0L,
    val trimStartMs: Long = 0L,
    val trimEndMs: Long = if (durationMs > 0L) durationMs else Long.MAX_VALUE,
    val audioVolume: Float = 1f,
    val audioMuted: Boolean = false,
    val audioFadeIn: Float = 0f,
    val audioFadeOut: Float = 0f,
    val audioKeyframes: List<ClipAudioKeyframe> = emptyList()
)
