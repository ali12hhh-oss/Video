package com.videoforge.nativeeditor

import android.content.ContentResolver
import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.MetricAffectingSpan
import android.text.style.CharacterStyle
import android.text.TextPaint
import androidx.core.content.res.ResourcesCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.C
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.audio.AudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import androidx.media3.effect.Brightness
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.GaussianBlur
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.TimestampWrapper
import androidx.media3.effect.Contrast
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.RgbFilter
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.effect.TextOverlay
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.Composition
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Real Media3 Transformer export: trim + concat + color/effects + text/sticker overlays + background music. */
data class ExportProgress(val fraction: Float, val message: String)

private class TypefaceSpanCompat(private val typeface: Typeface) : MetricAffectingSpan() {
    override fun updateMeasureState(textPaint: android.text.TextPaint) { textPaint.typeface = typeface }
    override fun updateDrawState(textPaint: android.text.TextPaint) { textPaint.typeface = typeface }
}

private class ShadowSpan(private val color: Int, private val radius: Float, private val dx: Float, private val dy: Float) : CharacterStyle() {
    override fun updateDrawState(tp: TextPaint) { tp.setShadowLayer(radius, dx, dy, color) }
}


@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private class AnimatedTextOverlay(
    private val baseText: SpannableString,
    private val x0: Float, private val y0: Float, private val scaleX0: Float, private val scaleY0: Float,
    private val rotation0: Float, private val alpha0: Float,
    private val animation: String,
    private val durationUs: Long,
    private val keyframes: List<TextKeyframe> = emptyList()
) : TextOverlay() {
    override fun getText(presentationTimeUs: Long): SpannableString {
        if (animation != "typewriter" || baseText.isEmpty()) return baseText
        val progress = (presentationTimeUs.toFloat() / durationUs.coerceAtLeast(1L)).coerceIn(0f, 1f)
        val count = (baseText.length * progress).toInt().coerceIn(1, baseText.length)
        return SpannableString(baseText.subSequence(0, count))
    }

    override fun getOverlaySettings(presentationTimeUs: Long): androidx.media3.effect.OverlaySettings {
        val progress = (presentationTimeUs.toFloat() / durationUs.coerceAtLeast(1L)).coerceIn(0f, 1f)
        val eased = 1f - (1f - progress) * (1f - progress)
        var x = x0
        var y = y0
        var scale = 1f
        var rotation = rotation0
        var alpha = alpha0
        if (keyframes.isNotEmpty()) {
            val tMs = presentationTimeUs / 1000L
            val ks = keyframes.sortedBy { it.timeMs }
            val a = ks.lastOrNull { it.timeMs <= tMs } ?: ks.first()
            val b = ks.firstOrNull { it.timeMs >= tMs } ?: ks.last()
            val span = (b.timeMs - a.timeMs).coerceAtLeast(1L)
            val k0 = ((tMs - a.timeMs).toFloat() / span).coerceIn(0f, 1f)
            val k = easedProgress(k0, b.easing)
            fun lerp(v1: Float, v2: Float) = v1 + (v2 - v1) * k
            x = lerp(a.x, b.x); y = lerp(a.y, b.y); scale = lerp(a.scale, b.scale); rotation = lerp(a.rotation, b.rotation); alpha = lerp(a.alpha, b.alpha)
        }
        when (animation) {
            "fade" -> alpha = alpha0 * eased
            "pop" -> scale = 0.55f + 0.45f * eased
            "zoom" -> scale = 0.25f + 0.75f * eased
            "slide" -> x = x0 - 0.45f * (1f - eased)
        }
        return StaticOverlaySettings.Builder()
            .setBackgroundFrameAnchor(x.coerceIn(-1f, 1f), y.coerceIn(-1f, 1f))
            .setOverlayFrameAnchor(0f, 0f)
            .setScale(scaleX0 * scale, scaleY0 * scale)
            .setRotationDegrees(rotation)
            .setAlphaScale(alpha)
            .build()
    }

}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private class TimedSubtitleOverlay(
    private val subtitle: Subtitle,
    private val baseText: SpannableString,
    private val scale: Float,
    private val alpha: Float
) : TextOverlay() {
    override fun getText(presentationTimeUs: Long): SpannableString {
        val t = presentationTimeUs / 1000L
        return if (t in subtitle.startMs until subtitle.endMs) baseText else SpannableString("")
    }

    override fun getOverlaySettings(presentationTimeUs: Long): androidx.media3.effect.OverlaySettings {
        val t = presentationTimeUs / 1000L
        val visible = t in subtitle.startMs until subtitle.endMs
        val fadeWindow = 140L
        val fade = when {
            !visible -> 0f
            t - subtitle.startMs < fadeWindow -> ((t - subtitle.startMs).toFloat() / fadeWindow).coerceIn(0f, 1f)
            subtitle.endMs - t < fadeWindow -> ((subtitle.endMs - t).toFloat() / fadeWindow).coerceIn(0f, 1f)
            else -> 1f
        }
        return StaticOverlaySettings.Builder()
            .setBackgroundFrameAnchor(subtitle.x.coerceIn(-1f, 1f), subtitle.y.coerceIn(-1f, 1f))
            .setOverlayFrameAnchor(0f, 0f)
            .setScale(scale, scale)
            .setAlphaScale((alpha * fade).coerceIn(0f, 1f))
            .build()
    }
}

class ExportEngine(private val context: Context, private val resolver: ContentResolver) {
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    suspend fun export(
        clips: List<Clip>,
        settings: ExportSettings,
        editor: EditorSettings = EditorSettings(),
        output: Uri,
        onProgress: (ExportProgress) -> Unit
    ): Result<Unit> = withContext(Dispatchers.Main.immediate) {
        var tempFileToDelete: File? = null
        try {
            require(clips.isNotEmpty()) { "No clips to export" }
            require(clips.all { it.durationMs > 0L || it.trimEndMs == Long.MAX_VALUE || it.trimEndMs > it.trimStartMs }) {
                "One or more clips have invalid duration settings"
            }
            require(!settings.hevc || CodecCapabilities.supportsHevc()) {
                "HEVC is not supported by an available device encoder"
            }
            require(CodecCapabilities.supportsAvcEncoder() || settings.hevc) {
                "No H.264 encoder is available on this device"
            }
            clips.forEachIndexed { index, clip ->
                resolver.openAssetFileDescriptor(clip.uri, "r")?.use { descriptor ->
                    require(descriptor.length != 0L) { "Clip ${index + 1} cannot be read" }
                } ?: error("Clip ${index + 1} cannot be opened")
            }
            if (editor.musicUri.isNotBlank()) {
                resolver.openAssetFileDescriptor(Uri.parse(editor.musicUri), "r")?.use { descriptor ->
                    require(descriptor.length != 0L) { "Background music cannot be read" }
                } ?: error("Background music cannot be opened")
            }
            onProgress(ExportProgress(0.03f, "Preparing project…"))

            val edited = clips.mapIndexed { index, clip ->
                val clipping = MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(clip.trimStartMs.coerceAtLeast(0L))
                    .apply {
                        if (clip.trimEndMs != Long.MAX_VALUE && clip.trimEndMs > clip.trimStartMs) setEndPositionMs(clip.trimEndMs)
                    }.build()
                val mediaItem = MediaItem.Builder().setUri(clip.uri).setClippingConfiguration(clipping).build()
                val videoEffects = mutableListOf<androidx.media3.common.Effect>()
                if (editor.brightness != 0f) videoEffects += Brightness(editor.brightness.coerceIn(-1f, 1f))
                if (editor.blurRadius > 0.01f) videoEffects += GaussianBlur(editor.blurRadius.coerceIn(0.1f, 20f))
                if (editor.contrast != 1f) videoEffects += Contrast(((editor.contrast - 1f) * 0.5f).coerceIn(-1f, 1f))
                if (editor.saturation != 1f || editor.hue != 0f || editor.temperature != 0f || editor.tint != 0f) {
                    videoEffects += HslAdjustment.Builder()
                        .adjustSaturation(((editor.saturation - 1f) * 100f + editor.temperature * 0.10f).coerceIn(-100f, 100f))
                        .adjustHue((editor.hue + editor.temperature * 0.12f + editor.tint * 0.08f).coerceIn(-180f, 180f))
                        .build()
                }
                when (editor.filter) {
                    "mono" -> videoEffects += RgbFilter.createGrayscaleFilter()
                    "invert" -> videoEffects += RgbFilter.createInvertedFilter()
                    "sepia" -> videoEffects += RgbFilter.createSepiaFilter()
                    "warm" -> videoEffects += HslAdjustment.Builder().adjustHue(18f).adjustSaturation(10f).build()
                    "cool" -> videoEffects += HslAdjustment.Builder().adjustHue(-18f).adjustSaturation(6f).build()
                    "vivid" -> videoEffects += HslAdjustment.Builder().adjustSaturation(28f).build()
                    "dream" -> { videoEffects += Brightness(0.08f); videoEffects += HslAdjustment.Builder().adjustSaturation(-6f).adjustHue(6f).build(); videoEffects += GaussianBlur(0.45f) }
                    "noir" -> { videoEffects += RgbFilter.createGrayscaleFilter(); videoEffects += Contrast(0.22f) }
                    "faded" -> { videoEffects += Brightness(0.03f); videoEffects += Contrast(-0.12f); videoEffects += HslAdjustment.Builder().adjustSaturation(-18f).build() }
                    "tealOrange" -> { videoEffects += HslAdjustment.Builder().adjustHue(8f).adjustSaturation(18f).build(); videoEffects += Contrast(0.08f) }
                    "vintage" -> { videoEffects += RgbFilter.createSepiaFilter(); videoEffects += HslAdjustment.Builder().adjustSaturation(-12f).build() }
                    "sunset" -> { videoEffects += HslAdjustment.Builder().adjustHue(22f).adjustSaturation(20f).build(); videoEffects += Brightness(0.04f) }
                    "ice" -> { videoEffects += HslAdjustment.Builder().adjustHue(-24f).adjustSaturation(10f).build(); videoEffects += Brightness(0.04f) }
                    "dramatic" -> { videoEffects += Contrast(0.28f); videoEffects += HslAdjustment.Builder().adjustSaturation(12f).build() }
                    "soft" -> { videoEffects += Contrast(-0.08f); videoEffects += GaussianBlur(0.3f) }
                }
                if (editor.rotation % 360 != 0 || kotlin.math.abs(editor.cropZoom - 1f) > 0.001f) {
                    videoEffects += ScaleAndRotateTransformation.Builder()
                        .setScale(editor.cropZoom.coerceIn(1f, 6f), editor.cropZoom.coerceIn(1f, 6f))
                        .setRotationDegrees((editor.rotation % 360 + 360) % 360).build()
                }
                if (editor.flipHorizontal || editor.flipVertical) {
                    videoEffects += MatrixTransformation {
                        android.graphics.Matrix().apply {
                            postScale(if (editor.flipHorizontal) -1f else 1f, if (editor.flipVertical) -1f else 1f)
                        }
                    }
                }
                if (kotlin.math.abs(editor.cropX) > 0.001f || kotlin.math.abs(editor.cropY) > 0.001f) {
                    videoEffects += MatrixTransformation {
                        android.graphics.Matrix().apply {
                            postTranslate(editor.cropX.coerceIn(-1f, 1f) * 500f, editor.cropY.coerceIn(-1f, 1f) * 500f)
                        }
                    }
                }
                if (editor.videoKeyframes.isNotEmpty()) {
                    val keyframes = editor.videoKeyframes.sortedBy { it.timeMs }
                    videoEffects += MatrixTransformation { presentationTimeUs ->
                        val tMs = presentationTimeUs / 1000L
                        val a = keyframes.lastOrNull { it.timeMs <= tMs } ?: keyframes.first()
                        val b = keyframes.firstOrNull { it.timeMs >= tMs } ?: keyframes.last()
                        val span = (b.timeMs - a.timeMs).coerceAtLeast(1L)
                        val f0 = ((tMs - a.timeMs).toFloat() / span).coerceIn(0f, 1f)
                        val f = easedProgress(f0, b.easing)
                        fun lerp(x: Float, y: Float) = x + (y - x) * f
                        val scale = lerp(a.scale, b.scale).coerceIn(0.1f, 6f)
                        val rotation = interpolateAngleDegrees(a.rotation, b.rotation, f)
                        val x = lerp(a.x, b.x)
                        val y = lerp(a.y, b.y)
                        android.graphics.Matrix().apply {
                            postScale(scale, scale)
                            postRotate(rotation)
                            postTranslate(x * 500f, y * 500f)
                        }
                    }
                }

                // Professional motion transitions are rendered per clip. Media3 Compositions do
                // not currently support true video cross-fades, so these are implemented as
                // timestamped GPU transforms (zoom/slide/spin/blur/flash) at clip boundaries.
                addMotionTransitionEffects(videoEffects, editor.transition, clipDurationUs(clip), editor.motionIntensity)

                val overlays = buildOverlayEffect(editor)
                if (overlays != null) videoEffects += overlays
                presentationFor(settings.resolution, editor.aspect)?.let { videoEffects += it }

                val audioProcessors = mutableListOf<androidx.media3.common.audio.AudioProcessor>()
                val clipMuted = editor.muted || clip.audioMuted
                val effectiveVolume = (editor.volume * clip.audioVolume).coerceIn(0f, 2f)
                val clipKeyframes = clip.audioKeyframes.map { AudioKeyframe(it.timeMs, it.volume) }
                val effectiveKeyframes = if (clipKeyframes.isNotEmpty()) clipKeyframes else editor.audioKeyframes
                if (!clipMuted) {
                    if (effectiveKeyframes.isNotEmpty()) {
                        audioProcessors += VolumeAutomationProcessor(
                            clipDurationUs(clip), effectiveVolume, effectiveKeyframes
                        )
                    } else if (effectiveVolume != 1f) audioProcessors += volumeProcessor(effectiveVolume)
                    val effectiveFadeIn = maxOf(editor.fadeIn, clip.audioFadeIn).coerceIn(0f, 30f)
                    val effectiveFadeOut = maxOf(editor.fadeOut, clip.audioFadeOut).coerceIn(0f, 30f)
                    if (effectiveFadeIn > 0f || effectiveFadeOut > 0f) {
                        audioProcessors += VolumeEnvelopeProcessor(
                            clipDurationUs(clip),
                            effectiveFadeIn * 1_000_000L,
                            effectiveFadeOut * 1_000_000L
                        )
                    }
                }
                val builder = EditedMediaItem.Builder(mediaItem)
                    .setEffects(Effects(audioProcessors, videoEffects))
                val speedKeys = editor.speedKeyframes.sortedBy { it.timeMs }
                if (speedKeys.isNotEmpty()) {
                    val fallback = editor.speed.coerceIn(0.25f, 4f)
                    val provider = object : SpeedProvider {
                        override fun getSpeed(timeUs: Long): Float {
                            val t = timeUs / 1000L
                            val a = speedKeys.lastOrNull { it.timeMs <= t } ?: speedKeys.first()
                            val b = speedKeys.firstOrNull { it.timeMs >= t } ?: speedKeys.last()
                            val span = (b.timeMs - a.timeMs).coerceAtLeast(1L)
                            val f0 = ((t - a.timeMs).toFloat() / span).coerceIn(0f, 1f)
                            val f = easedProgress(f0, b.easing)
                            return (a.speed + (b.speed - a.speed) * f).coerceIn(0.25f, 4f)
                        }
                        override fun getNextSpeedChangeTimeUs(timeUs: Long): Long {
                            val t = timeUs / 1000L
                            val next = speedKeys.firstOrNull { it.timeMs > t }?.timeMs ?: return C.TIME_UNSET
                            return next * 1000L
                        }
                    }
                    builder.setSpeed(provider)
                } else if (editor.speed > 0f && kotlin.math.abs(editor.speed - 1f) > 0.001f) {
                    val speed = editor.speed.coerceIn(0.25f, 4f)
                    val provider = object : SpeedProvider {
                        override fun getSpeed(timeUs: Long): Float = speed
                        override fun getNextSpeedChangeTimeUs(timeUs: Long): Long = C.TIME_UNSET
                    }
                    builder.setSpeed(provider)
                }
                if (settings.fps.value > 0) builder.setFrameRate(settings.fps.value)
                if (editor.muted || clip.audioMuted) builder.setRemoveAudio(true)
                onProgress(ExportProgress(0.05f + index.toFloat() / clips.size * 0.15f, "Preparing clip ${index + 1}/${clips.size}…"))
                builder.build()
            }

            val videoSequence = EditedMediaItemSequence.withAudioAndVideoFrom(edited)
            val sequences = mutableListOf(videoSequence)
            if (editor.musicUri.isNotBlank()) {
                val musicClipping = MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(editor.musicStartMs.coerceAtLeast(0L))
                    .apply {
                        if (editor.musicDurationMs > 0L) setEndPositionMs(editor.musicStartMs + editor.musicDurationMs)
                    }
                    .build()
                val musicMediaItem = MediaItem.Builder()
                    .setUri(Uri.parse(editor.musicUri))
                    .setClippingConfiguration(musicClipping)
                    .build()
                val musicAudioProcessors = mutableListOf<androidx.media3.common.audio.AudioProcessor>()
                val musicActiveDurationMs = if (editor.musicDurationMs > 0L) editor.musicDurationMs else timelineDurationMs(clips)
                val musicAutomation = buildMusicAutomationKeyframes(clips, editor, musicActiveDurationMs)
                if (musicAutomation.isNotEmpty()) {
                    musicAudioProcessors += VolumeAutomationProcessor(
                        musicActiveDurationMs * 1000L, editor.musicVolume, musicAutomation
                    )
                } else if (editor.musicVolume != 1f) musicAudioProcessors += volumeProcessor(editor.musicVolume)
                if (editor.musicFadeIn > 0f || editor.musicFadeOut > 0f) {
                    musicAudioProcessors += VolumeEnvelopeProcessor(
                        musicActiveDurationMs.coerceAtLeast(1L) * 1000L,
                        editor.musicFadeIn.coerceIn(0f, 30f) * 1_000_000L,
                        editor.musicFadeOut.coerceIn(0f, 30f) * 1_000_000L
                    )
                }
                val musicItem = EditedMediaItem.Builder(musicMediaItem)
                    .setEffects(Effects(musicAudioProcessors, emptyList()))
                    .build()
                sequences += EditedMediaItemSequence.Builder(musicItem).setIsLooping(true).build()
            }
            val composition = Composition.Builder(*sequences.toTypedArray()).build()
            val temp = File(context.cacheDir, "export_${System.currentTimeMillis()}.mp4")
            tempFileToDelete = temp
            if (temp.exists()) temp.delete()

            val bitrate = estimateBitrate(settings).coerceAtLeast(500_000)
            val encoderFactory = DefaultEncoderFactory.Builder(context)
                .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build()).build()
            val transformer = Transformer.Builder(context)
                .setVideoMimeType(if (settings.hevc) MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .setEncoderFactory(encoderFactory)
                .setPortraitEncodingEnabled(true)
                .build()

            onProgress(ExportProgress(0.22f, "Encoding ${if (settings.hevc) "H.265 / HEVC" else "H.264"}…"))
            suspendCancellableCoroutine<Unit> { cont ->
                val handler = android.os.Handler(android.os.Looper.getMainLooper())
                val holder = Transformer.ProgressHolder()
                val poll = object : Runnable {
                    override fun run() {
                        if (!cont.isActive) return
                        if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                            onProgress(ExportProgress(0.22f + holder.progress / 100f * 0.73f, "Rendering… ${holder.progress}%"))
                        }
                        if (cont.isActive) handler.postDelayed(this, 350L)
                    }
                }
                val listener = object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: androidx.media3.transformer.ExportResult) {
                        handler.removeCallbacks(poll)
                        if (cont.isActive) cont.resume(Unit)
                    }
                    override fun onError(composition: Composition, exportException: androidx.media3.transformer.ExportException) {
                        handler.removeCallbacks(poll)
                        if (cont.isActive) cont.resumeWithException(exportException)
                    }
                }
                transformer.addListener(listener)
                cont.invokeOnCancellation {
                    handler.removeCallbacks(poll)
                    transformer.cancel()
                }
                transformer.start(composition, temp.absolutePath)
                handler.post(poll)
            }

            onProgress(ExportProgress(0.97f, "Saving video…"))
            resolver.openOutputStream(output)?.use { out -> File(temp.absolutePath).inputStream().use { it.copyTo(out) } } ?: error("Cannot open output destination")
            temp.delete()
            onProgress(ExportProgress(1f, "Export complete"))
            Result.success(Unit)
        } catch (e: Throwable) {
            // Never leave partially rendered cache files behind after a failed/cancelled export.
            runCatching { tempFileToDelete?.delete() }
            Result.failure(e)
        }
    }

    private fun timelineDurationMs(clips: List<Clip>): Long = clips.sumOf { clip ->
        val end = if (clip.trimEndMs == Long.MAX_VALUE) clip.durationMs else clip.trimEndMs
        (end - clip.trimStartMs).coerceAtLeast(1L)
    }.coerceAtLeast(1L)

    private fun clipDurationUs(clip: Clip): Long {
        val end = if (clip.trimEndMs == Long.MAX_VALUE) clip.durationMs else clip.trimEndMs
        return ((end - clip.trimStartMs).coerceAtLeast(1L)) * 1000L
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun addMotionTransitionEffects(
        effects: MutableList<androidx.media3.common.Effect>,
        transition: String,
        durationUs: Long,
        intensity: Float
    ) {
        if (transition == "none" || durationUs <= 0L) return
        val window = minOf(700_000L, durationUs / 3L).coerceAtLeast(120_000L)
        val strength = intensity.coerceIn(0.35f, 1.8f)

        fun animated(startUs: Long, endUs: Long, builder: (Float) -> android.graphics.Matrix) {
            effects += TimestampWrapper(
                MatrixTransformation { timeUs ->
                    val t = ((timeUs - startUs).toFloat() / (endUs - startUs).coerceAtLeast(1L)).coerceIn(0f, 1f)
                    builder(t)
                }, startUs, endUs
            )
        }

        when (transition) {
            "zoom" -> animated(0L, window) { t ->
                val m = android.graphics.Matrix()
                val scale = 1f + (0.16f * strength * (1f - t))
                m.postScale(scale, scale)
                m
            }
            "slide", "push" -> animated(0L, window) { t ->
                val m = android.graphics.Matrix()
                m.postTranslate((-0.16f * strength * (1f - t)), 0f)
                m
            }
            "pull" -> animated(0L, window) { t ->
                val m = android.graphics.Matrix()
                val scale = 1.18f - (0.18f * t * strength)
                m.postScale(scale.coerceAtLeast(1f), scale.coerceAtLeast(1f))
                m
            }
            "spin" -> animated(0L, window) { t ->
                val m = android.graphics.Matrix()
                m.postRotate(8f * strength * (1f - t))
                m
            }
            "blur" -> effects += TimestampWrapper(GaussianBlur((6f * strength).coerceIn(1f, 10f)), 0L, window)
            "flash" -> effects += TimestampWrapper(Brightness(0.28f * strength), 0L, minOf(180_000L, window))
            "glitch", "digital" -> {
                effects += TimestampWrapper(RgbFilter.createInvertedFilter(), 0L, minOf(90_000L, window))
                effects += TimestampWrapper(Contrast(0.35f), 0L, minOf(180_000L, window))
            }
            "crossZoom", "rotateZoom" -> animated(0L, window) { t ->
                val m = android.graphics.Matrix()
                val scale = 1f + (0.22f * strength * (1f - t))
                m.postScale(scale, scale)
                if (transition == "rotateZoom") m.postRotate(10f * strength * (1f - t))
                m
            }
            "lightLeak", "filmBurn", "prism" -> {
                effects += TimestampWrapper(Brightness(0.22f * strength), 0L, minOf(180_000L, window))
                effects += TimestampWrapper(HslAdjustment.Builder().adjustHue(if (transition == "prism") 28f else 12f).adjustSaturation(12f).build(), 0L, window)
            }
            "radial", "shutter", "cube", "elastic", "swing", "bounce" -> animated(0L, window) { t ->
                val m = android.graphics.Matrix()
                val amount = (1f - t) * strength
                m.postScale(1f + 0.08f * amount, 1f + 0.08f * amount)
                m.postRotate(if (transition == "swing") 5f * amount else 0f)
                m
            }
            // Wipe/fade remain semantic presets in the UI. True crossfade/wipe between two
            // independent sequences requires a compositor/overlap pipeline rather than a simple
            // item effect, and is intentionally kept out of the export path here.
            "wipe", "fade" -> Unit
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun buildOverlayEffect(editor: EditorSettings): OverlayEffect? {
        val overlays = mutableListOf<androidx.media3.effect.TextureOverlay>()
        editor.subtitles.filter { it.text.isNotBlank() && it.endMs > it.startMs }.forEach { subtitle ->
            val span = SpannableString(subtitle.text)
            val color = android.graphics.Color.argb(255,
                ((subtitle.color shr 16) and 0xFF).toInt(), ((subtitle.color shr 8) and 0xFF).toInt(), (subtitle.color and 0xFF).toInt())
            span.setSpan(ForegroundColorSpan(color), 0, span.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            val typeface = ResourcesCompat.getFont(context, R.font.noto_sans_arabic_bold) ?: Typeface.DEFAULT_BOLD
            span.setSpan(TypefaceSpanCompat(typeface), 0, span.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (subtitle.backgroundAlpha > 0f) {
                span.setSpan(android.text.style.BackgroundColorSpan(android.graphics.Color.argb((subtitle.backgroundAlpha.coerceIn(0f,1f) * 255).toInt(), 0, 0, 0)), 0, span.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            overlays += TimedSubtitleOverlay(subtitle, span, (subtitle.size / 100f).coerceIn(0.12f, 1.5f), 1f)
        }
        val layers = if (editor.textLayers.isNotEmpty()) {
            editor.textLayers.filter { it.visible && it.text.isNotBlank() }
        } else if (editor.textVisible && editor.text.isNotBlank()) {
            listOf(TextLayer(text = editor.text, size = editor.textSize, color = editor.textColor, font = editor.textFont))
        } else emptyList()
        layers.forEach { layer ->
            val span = SpannableString(layer.text)
            val color = android.graphics.Color.argb((layer.alpha.coerceIn(0f, 1f) * 255).toInt(),
                ((layer.color shr 16) and 0xFF).toInt(), ((layer.color shr 8) and 0xFF).toInt(), (layer.color and 0xFF).toInt())
            span.setSpan(ForegroundColorSpan(color), 0, span.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            val typeface = ResourcesCompat.getFont(context, fontRes(layer.font, layer.bold)) ?: Typeface.DEFAULT_BOLD
            span.setSpan(TypefaceSpanCompat(typeface), 0, span.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (layer.backgroundAlpha > 0f) {
                val bg = android.graphics.Color.argb((layer.backgroundAlpha.coerceIn(0f,1f) * 255).toInt(), ((layer.backgroundColor shr 16) and 0xFF).toInt(), ((layer.backgroundColor shr 8) and 0xFF).toInt(), (layer.backgroundColor and 0xFF).toInt())
                span.setSpan(android.text.style.BackgroundColorSpan(bg), 0, span.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (layer.shadowEnabled) {
                val sh = android.graphics.Color.argb(((layer.shadowColor shr 24) and 0xFF).toInt(), ((layer.shadowColor shr 16) and 0xFF).toInt(), ((layer.shadowColor shr 8) and 0xFF).toInt(), (layer.shadowColor and 0xFF).toInt())
                span.setSpan(ShadowSpan(sh, layer.shadowRadius.coerceIn(0f, 24f), layer.shadowDx.coerceIn(-20f,20f), layer.shadowDy.coerceIn(-20f,20f)), 0, span.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            val baseScale = (layer.size / 100f).coerceIn(0.12f, 1.5f)
            overlays += AnimatedTextOverlay(
                baseText = span,
                x0 = layer.x.coerceIn(-1f, 1f),
                y0 = layer.y.coerceIn(-1f, 1f),
                scaleX0 = baseScale * layer.scale, scaleY0 = baseScale * layer.scale,
                rotation0 = layer.rotation,
                alpha0 = layer.alpha.coerceIn(0f, 1f),
                animation = if (layer.animation == "none") editor.textAnimation else layer.animation,
                durationUs = 650_000L,
                keyframes = layer.keyframes
            )
        }
        val pipLayers = editor.pipLayers.ifEmpty {
            if (editor.overlayImageUri.isNotBlank()) listOf(
                PipLayer(uri = editor.overlayImageUri, x = editor.overlayImageX, y = editor.overlayImageY,
                    scale = editor.overlayImageScale, rotation = editor.overlayImageRotation, alpha = editor.overlayImageAlpha)
            ) else emptyList()
        }
        pipLayers.filter { it.visible && it.uri.isNotBlank() }.forEach { pip ->
            runCatching {
                val overlaySettings = StaticOverlaySettings.Builder()
                    .setBackgroundFrameAnchor(pip.x.coerceIn(-1f, 1f), pip.y.coerceIn(-1f, 1f))
                    .setOverlayFrameAnchor(0f, 0f)
                    .setScale(pip.scale.coerceIn(0.05f, 1.5f), pip.scale.coerceIn(0.05f, 1.5f))
                    .setRotationDegrees(pip.rotation)
                    .setAlphaScale(pip.alpha.coerceIn(0f, 1f))
                    .build()
                overlays += BitmapOverlay.createStaticBitmapOverlay(context, Uri.parse(pip.uri), overlaySettings)
            }
        }
        if (editor.sticker.isNotBlank()) {
            val span = SpannableString(editor.sticker)
            span.setSpan(TypefaceSpanCompat(Typeface.DEFAULT), 0, span.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            val overlaySettings = StaticOverlaySettings.Builder()
                .setBackgroundFrameAnchor(editor.stickerX.coerceIn(-1f, 1f), editor.stickerY.coerceIn(-1f, 1f))
                .setOverlayFrameAnchor(0f, 0f)
                .setScale(editor.stickerScale.coerceIn(0.05f, 2f), editor.stickerScale.coerceIn(0.05f, 2f))
                .setRotationDegrees(editor.stickerRotation)
                .setAlphaScale(editor.stickerAlpha.coerceIn(0f, 1f))
                .build()
            overlays += TextOverlay.createStaticTextOverlay(span, overlaySettings)
        }
        return if (overlays.isEmpty()) null else OverlayEffect(overlays)
    }

    private fun fontRes(name: String, bold: Boolean): Int = when (name) {
        "noto_kufi_arabic" -> if (bold) R.font.noto_kufi_arabic_bold else R.font.noto_kufi_arabic
        "noto_naskh_arabic" -> if (bold) R.font.noto_naskh_arabic_bold else R.font.noto_naskh_arabic
        "amiri" -> if (bold) R.font.amiri_bold else R.font.amiri
        "lato" -> if (bold) R.font.lato_bold else R.font.lato
        "inter" -> if (bold) R.font.inter_bold else R.font.inter
        "cabin" -> if (bold) R.font.cabin_bold else R.font.cabin
        "comic_neue" -> if (bold) R.font.comic_neue_bold else R.font.comic_neue
        "dejavu_sans" -> if (bold) R.font.dejavu_sans_bold else R.font.dejavu_sans
        else -> if (bold) R.font.noto_sans_arabic_bold else R.font.noto_sans_arabic
    }


    /** PCM16 gain envelope used for real audio fade-in/fade-out during export. */
    private class VolumeEnvelopeProcessor(
        private val durationUs: Long,
        private val fadeInUs: Long,
        private val fadeOutUs: Long
    ) : AudioProcessor {
        private lateinit var inputFormat: AudioProcessor.AudioFormat
        private var outputBuffer: ByteBuffer = ByteBuffer.allocateDirect(0).order(ByteOrder.LITTLE_ENDIAN)
        private var ended = false
        private var positionBytes: Long = 0L
        private var bytesPerFrame: Int = 0
        private var sampleRate: Int = 0

        override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
            inputFormat = inputAudioFormat
            if (inputAudioFormat.encoding != androidx.media3.common.C.ENCODING_PCM_16BIT) {
                throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
            }
            sampleRate = inputAudioFormat.sampleRate
            bytesPerFrame = inputAudioFormat.channelCount * 2
            return inputAudioFormat
        }

        override fun isActive(): Boolean = fadeInUs > 0L || fadeOutUs > 0L

        override fun queueInput(inputBuffer: ByteBuffer) {
            if (!inputBuffer.hasRemaining()) return
            val bytes = inputBuffer.remaining()
            ensureOutputCapacity(bytes)
            val view = inputBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            while (view.remaining() >= 2) {
                val sample = view.short.toInt()
                val frameIndex = positionBytes / bytesPerFrame
                val timeUs = frameIndex * 1_000_000L / sampleRate.coerceAtLeast(1)
                val inGain = if (fadeInUs > 0L) (timeUs.toDouble() / fadeInUs).coerceIn(0.0, 1.0) else 1.0
                val outGain = if (fadeOutUs > 0L) {
                    val remaining = durationUs - timeUs
                    (remaining.toDouble() / fadeOutUs).coerceIn(0.0, 1.0)
                } else 1.0
                val gain = minOf(inGain, outGain)
                outputBuffer.putShort((sample * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
                positionBytes += 2
            }
            inputBuffer.position(inputBuffer.limit())
            outputBuffer.flip()
        }

        private fun ensureOutputCapacity(bytes: Int) {
            if (outputBuffer.capacity() >= bytes && outputBuffer.remaining() >= bytes) return
            val needed = bytes.coerceAtLeast(4096)
            outputBuffer = ByteBuffer.allocateDirect(needed).order(ByteOrder.LITTLE_ENDIAN)
        }

        override fun queueEndOfStream() { ended = true }
        override fun getOutput(): ByteBuffer {
            val out = outputBuffer
            outputBuffer = ByteBuffer.allocateDirect(0).order(ByteOrder.LITTLE_ENDIAN)
            return out
        }
        override fun isEnded(): Boolean = ended && !outputBuffer.hasRemaining()
        override fun flush() {
            outputBuffer = ByteBuffer.allocateDirect(0).order(ByteOrder.LITTLE_ENDIAN)
            ended = false
            positionBytes = 0L
        }
        override fun reset() { flush(); if (::inputFormat.isInitialized) inputFormat = AudioProcessor.AudioFormat.NOT_SET }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private class VolumeAutomationProcessor(
        private val durationUs: Long,
        private val baseVolume: Float,
        keyframes: List<AudioKeyframe>
    ) : AudioProcessor {
        private val keyframes = keyframes.sortedBy { it.timeMs }
        private var outputBuffer: ByteBuffer = ByteBuffer.allocateDirect(0).order(ByteOrder.LITTLE_ENDIAN)
        private var ended = false
        private var positionBytes = 0L
        private var bytesPerFrame = 0
        private var sampleRate = 0
        override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
            if (inputAudioFormat.encoding != androidx.media3.common.C.ENCODING_PCM_16BIT) throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
            sampleRate = inputAudioFormat.sampleRate
            bytesPerFrame = inputAudioFormat.channelCount * 2
            return inputAudioFormat
        }
        override fun isActive(): Boolean = keyframes.isNotEmpty()
        private fun gainAt(timeMs: Long): Float {
            if (keyframes.isEmpty()) return baseVolume
            val a = keyframes.lastOrNull { it.timeMs <= timeMs } ?: keyframes.first()
            val b = keyframes.firstOrNull { it.timeMs >= timeMs } ?: keyframes.last()
            val span = (b.timeMs - a.timeMs).coerceAtLeast(1L)
            val f = ((timeMs - a.timeMs).toFloat() / span).coerceIn(0f, 1f)
            return (a.volume + (b.volume - a.volume) * f) * baseVolume
        }
        override fun queueInput(inputBuffer: ByteBuffer) {
            if (!inputBuffer.hasRemaining()) return
            val bytes = inputBuffer.remaining()
            outputBuffer = ByteBuffer.allocateDirect(bytes.coerceAtLeast(4096)).order(ByteOrder.LITTLE_ENDIAN)
            val view = inputBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            while (view.remaining() >= 2) {
                val sample = view.short.toInt()
                val frame = if (bytesPerFrame > 0) positionBytes / bytesPerFrame else 0L
                val tMs = frame * 1000L / sampleRate.coerceAtLeast(1)
                val out = (sample * gainAt(tMs)).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                outputBuffer.putShort(out.toShort())
                positionBytes += 2
            }
            inputBuffer.position(inputBuffer.limit())
            outputBuffer.flip()
        }
        override fun queueEndOfStream() { ended = true }
        override fun getOutput(): ByteBuffer { val out = outputBuffer; outputBuffer = ByteBuffer.allocateDirect(0).order(ByteOrder.LITTLE_ENDIAN); return out }
        override fun isEnded(): Boolean = ended && !outputBuffer.hasRemaining()
        override fun flush() { outputBuffer = ByteBuffer.allocateDirect(0).order(ByteOrder.LITTLE_ENDIAN); ended = false; positionBytes = 0L }
        override fun reset() { flush() }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun volumeProcessor(volume: Float): ChannelMixingAudioProcessor {
        val p = ChannelMixingAudioProcessor()
        for (channels in 1..6) {
            val matrix = ChannelMixingMatrix.createForConstantPower(channels, channels).scaleBy(volume.coerceIn(0f, 2f))
            p.putChannelMixingMatrix(matrix)
        }
        return p
    }

    private fun buildMusicAutomationKeyframes(clips: List<Clip>, editor: EditorSettings, musicDurationMs: Long): List<AudioKeyframe> {
        val base = editor.musicKeyframes.map { AudioKeyframe(it.timeMs.coerceIn(0L, musicDurationMs), it.volume.coerceIn(0f, 1.5f)) }
        if (!editor.musicDucking || clips.isEmpty()) return base
        val attackMs = (editor.musicDuckAttack.coerceIn(0f, 2f) * 1000f).toLong()
        val releaseMs = (editor.musicDuckRelease.coerceIn(0f, 3f) * 1000f).toLong()
        val boundaries = mutableSetOf<Long>(0L, musicDurationMs.coerceAtLeast(0L))
        var offset = 0L
        clips.forEach { clip ->
            val length = clipDurationMs(clip)
            val audible = !editor.muted && !clip.audioMuted && clip.audioVolume > 0.001f
            if (audible) {
                val start = (offset - editor.musicStartMs).coerceIn(0L, musicDurationMs)
                val end = (offset + length - editor.musicStartMs).coerceIn(0L, musicDurationMs)
                boundaries += start
                boundaries += end
                boundaries += (start + attackMs).coerceIn(0L, musicDurationMs)
                boundaries += (end - releaseMs).coerceIn(0L, musicDurationMs)
            }
            offset += length
        }
        val points = (boundaries + base.map { it.timeMs }).filter { it in 0L..musicDurationMs }.distinct().sorted()
        fun baseAt(t: Long): Float = interpolateAutomation(base, t, editor.musicVolume).coerceIn(0f, 1.5f)
        fun duckProgressAt(t: Long): Float {
            val global = editor.musicStartMs + t
            var best = 0f
            var clipOffset = 0L
            clips.forEach { clip ->
                val length = clipDurationMs(clip)
                val audible = !editor.muted && !clip.audioMuted && clip.audioVolume > 0.001f
                if (audible) {
                    val local = global - clipOffset
                    if (local in 0L..length) {
                        val attack = if (attackMs > 0L) (local.toFloat() / attackMs).coerceIn(0f, 1f) else 1f
                        val release = if (releaseMs > 0L) ((length - local).toFloat() / releaseMs).coerceIn(0f, 1f) else 1f
                        best = maxOf(best, minOf(attack, release))
                    }
                }
                clipOffset += length
            }
            return best
        }
        return points.map { t ->
            val duck = duckProgressAt(t)
            val duckGain = 1f + (editor.musicDuckVolume.coerceIn(0f, 1f) - 1f) * duck
            AudioKeyframe(t, (baseAt(t) * duckGain).coerceIn(0f, 1.5f))
        }
    }

    private fun clipsAtGlobalTime(clips: List<Clip>, globalMs: Long): List<Clip> {
        var offset = 0L
        val result = mutableListOf<Clip>()
        clips.forEach { clip ->
            val length = clipDurationMs(clip)
            if (globalMs >= offset && globalMs < offset + length) result += clip
            offset += length
        }
        return result
    }

    private fun interpolateAutomation(keys: List<AudioKeyframe>, timeMs: Long, fallback: Float): Float {
        if (keys.isEmpty()) return fallback
        val sorted = keys.sortedBy { it.timeMs }
        val a = sorted.lastOrNull { it.timeMs <= timeMs } ?: sorted.first()
        val b = sorted.firstOrNull { it.timeMs >= timeMs } ?: sorted.last()
        val span = (b.timeMs - a.timeMs).coerceAtLeast(1L)
        val f = ((timeMs - a.timeMs).toFloat() / span).coerceIn(0f, 1f)
        return a.volume + (b.volume - a.volume) * f
    }

    private fun estimateBitrate(settings: ExportSettings): Int {
        val base = when (settings.resolution) {
            ExportResolution.P360 -> 900_000; ExportResolution.P480 -> 1_800_000; ExportResolution.P720 -> 4_000_000
            ExportResolution.P1080 -> 8_000_000; ExportResolution.P1440 -> 14_000_000; ExportResolution.P2160 -> 28_000_000; ExportResolution.ORIGINAL -> 8_000_000
        }
        return (base * settings.quality.multiplier).toInt()
    }

    private fun presentationFor(resolution: ExportResolution, aspect: String): Presentation? {
        if (resolution == ExportResolution.ORIGINAL) return null
        val short = minOf(resolution.width, resolution.height)
        val (width, height) = when (aspect) {
            "9:16" -> (short * 9 / 16).toInt() to short
            "1:1" -> short to short
            "4:5" -> (short * 4 / 5).toInt() to short
            "2:3" -> (short * 2 / 3).toInt() to short
            "3:4" -> (short * 3 / 4).toInt() to short
            "3:2" -> short to (short * 2 / 3).toInt()
            "21:9" -> short to (short * 9 / 21).toInt()
            else -> resolution.width to resolution.height
        }
        return Presentation.createForWidthAndHeight(width, height, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP)
    }
}
