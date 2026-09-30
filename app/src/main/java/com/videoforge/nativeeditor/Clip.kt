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
    val audioKeyframes: List<ClipAudioKeyframe> = emptyList(),
    val videoKeyframes: List<VideoKeyframe> = emptyList(),
    val speedKeyframes: List<SpeedKeyframe> = emptyList(),
    val isFreezeFrame: Boolean = false,
    val freezeDurationMs: Long = 1000L,
    /** Absolute project position of the clip, independent of its source trim. */
    val timelineStartMs: Long = 0L,
    /** Visual track/layer. 0 is the primary track; higher tracks render above it. */
    val trackIndex: Int = 0,
    /** Transition applied at the start of this clip, between it and the previous adjacent clip. */
    val transition: String = "none",
    val transitionDurationMs: Long = 400L
)
