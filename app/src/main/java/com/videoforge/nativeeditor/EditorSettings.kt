package com.videoforge.nativeeditor

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class VideoKeyframe(
    val timeMs: Long = 0L,
    val x: Float = 0f,
    val y: Float = 0f,
    val scale: Float = 1f,
    val rotation: Float = 0f,
    val easing: String = "easeInOut"
)

data class TextKeyframe(
    val timeMs: Long = 0L,
    val x: Float = 0f,
    val y: Float = -0.65f,
    val scale: Float = 1f,
    val rotation: Float = 0f,
    val alpha: Float = 1f,
    val easing: String = "easeInOut"
)

data class AudioKeyframe(
    val timeMs: Long = 0L,
    val volume: Float = 1f
)

data class MusicKeyframe(
    val timeMs: Long = 0L,
    val volume: Float = 1f
)

data class TimelineMarker(
    val id: String = System.nanoTime().toString(),
    val timeMs: Long = 0L,
    val label: String = "Marker"
)

data class SpeedKeyframe(
    val timeMs: Long = 0L,
    val speed: Float = 1f,
    val easing: String = "easeInOut"
)

/** Interpolates rotation along the shortest angular path to avoid an unwanted full spin. */
fun interpolateAngleDegrees(from: Float, to: Float, progress: Float): Float {
    val delta = ((to - from + 540f) % 360f) - 180f
    return from + delta * progress.coerceIn(0f, 1f)
}

fun easedProgress(progress: Float, easing: String): Float {
    val t = progress.coerceIn(0f, 1f)
    return when (easing) {
        "hold" -> if (t >= 1f) 1f else 0f
        "linear" -> t
        "easeIn" -> t * t
        "easeOut" -> 1f - (1f - t) * (1f - t)
        else -> t * t * (3f - 2f * t)
    }
}

data class Subtitle(
    val id: String = System.nanoTime().toString(),
    val text: String = "",
    val startMs: Long = 0L,
    val endMs: Long = 2000L,
    val size: Float = 30f,
    val color: Long = 0xFFFFFFFF,
    val bold: Boolean = true,
    val x: Float = 0f,
    val y: Float = 0.72f,
    val backgroundAlpha: Float = 0.55f
)

data class TextLayer(
    val id: String = System.nanoTime().toString(),
    val name: String = "Text",
    val visible: Boolean = true,
    val locked: Boolean = false,
    val text: String = "",
    val size: Float = 28f,
    val color: Long = 0xFFFFFFFF,
    val font: String = "noto_sans_arabic",
    val bold: Boolean = true,
    val alpha: Float = 1f,
    val x: Float = 0f,
    val y: Float = -0.65f,
    val rotation: Float = 0f,
    val scale: Float = 1f,
    val animation: String = "none",
    val backgroundColor: Long = 0xCC000000,
    val backgroundAlpha: Float = 0f,
    val shadowEnabled: Boolean = false,
    val shadowColor: Long = 0xCC000000,
    val shadowRadius: Float = 4f,
    val shadowDx: Float = 2f,
    val shadowDy: Float = 2f,
    val strokeEnabled: Boolean = false,
    val strokeColor: Long = 0xFF000000,
    val strokeWidth: Float = 0f,
    val keyframes: List<TextKeyframe> = emptyList()
)

/** Persistent non-destructive editor controls for each local project. */
data class PipLayer(
    val id: String = System.nanoTime().toString(),
    val uri: String = "",
    val x: Float = 0.72f,
    val y: Float = -0.72f,
    val scale: Float = 0.32f,
    val rotation: Float = 0f,
    val alpha: Float = 1f,
    val visible: Boolean = true
)

data class EditorSettings(
    val volume: Float = 1f,
    val muted: Boolean = false,
    val speed: Float = 1f,
    val speedKeyframes: List<SpeedKeyframe> = emptyList(),
    val text: String = "",
    val textSize: Float = 28f,
    val textColor: Long = 0xFFFFFFFF,
    val textVisible: Boolean = false,
    val textFont: String = "noto_sans_arabic",
    val textLayers: List<TextLayer> = emptyList(),
    val subtitles: List<Subtitle> = emptyList(),
    val musicUri: String = "",
    val musicVolume: Float = 0.65f,
    val musicStartMs: Long = 0L,
    val musicDurationMs: Long = 0L,
    val musicFadeIn: Float = 0f,
    val musicFadeOut: Float = 0f,
    val musicDucking: Boolean = false,
    val musicDuckVolume: Float = 0.28f,
    val musicDuckAttack: Float = 0.12f,
    val musicDuckRelease: Float = 0.22f,
    val musicKeyframes: List<MusicKeyframe> = emptyList(),
    val markers: List<TimelineMarker> = emptyList(),
    val audioKeyframes: List<AudioKeyframe> = emptyList(),
    val filter: String = "none",
    val brightness: Float = 0f,
    val contrast: Float = 1f,
    val saturation: Float = 1f,
    val hue: Float = 0f,
    val temperature: Float = 0f,
    val tint: Float = 0f,
    val blurRadius: Float = 0f,
    val aspect: String = "16:9",
    val cropZoom: Float = 1f,
    val cropX: Float = 0f,
    val cropY: Float = 0f,
    val rotation: Int = 0,
    val flipHorizontal: Boolean = false,
    val flipVertical: Boolean = false,
    val overlayOpacity: Float = 0f,
    val overlayImageUri: String = "",
    val overlayImageX: Float = 0.72f,
    val overlayImageY: Float = -0.72f,
    val overlayImageScale: Float = 0.32f,
    val overlayImageRotation: Float = 0f,
    val overlayImageAlpha: Float = 1f,
    val pipLayers: List<PipLayer> = emptyList(),
    val sticker: String = "",
    val stickerX: Float = 0.78f,
    val stickerY: Float = 0.72f,
    val stickerScale: Float = 0.35f,
    val stickerRotation: Float = 0f,
    val stickerAlpha: Float = 1f,
    val transition: String = "none",
    val transitionDuration: Float = 0.4f,
    val textAnimation: String = "none",
    val motionIntensity: Float = 1f,
    val fadeIn: Float = 0f,
    val fadeOut: Float = 0f,
    val videoKeyframes: List<VideoKeyframe> = emptyList()
)

object EditorSettingsRepository {
    private const val PREFS = "videoforge_editor_settings"

    private fun layersFromJson(a: JSONArray): List<TextLayer> = buildList {
        for (i in 0 until a.length()) {
            val j = a.optJSONObject(i) ?: continue
            add(TextLayer(
                id = j.optString("id", System.nanoTime().toString()),
                name = j.optString("name", "Text"),
                visible = j.optBoolean("visible", true),
                locked = j.optBoolean("locked", false),
                text = j.optString("text", ""),
                size = j.optDouble("size", 28.0).toFloat(),
                color = j.optString("color", "4294967295").toLongOrNull() ?: 0xFFFFFFFF,
                font = j.optString("font", "noto_sans_arabic"),
                bold = j.optBoolean("bold", true),
                alpha = j.optDouble("alpha", 1.0).toFloat(),
                x = j.optDouble("x", 0.0).toFloat(),
                y = j.optDouble("y", -0.65).toFloat(),
                rotation = j.optDouble("rotation", 0.0).toFloat(),
                scale = j.optDouble("scale", 1.0).toFloat(),
                animation = j.optString("animation", "none"),
                backgroundColor = j.optString("backgroundColor", "3422552064").toLongOrNull() ?: 0xCC000000,
                backgroundAlpha = j.optDouble("backgroundAlpha", 0.0).toFloat(),
                shadowEnabled = j.optBoolean("shadowEnabled", false),
                shadowColor = j.optString("shadowColor", "3422552064").toLongOrNull() ?: 0xCC000000,
                shadowRadius = j.optDouble("shadowRadius", 4.0).toFloat(),
                shadowDx = j.optDouble("shadowDx", 2.0).toFloat(),
                shadowDy = j.optDouble("shadowDy", 2.0).toFloat(),
                strokeEnabled = j.optBoolean("strokeEnabled", false),
                strokeColor = j.optString("strokeColor", "4278190080").toLongOrNull() ?: 0xFF000000,
                strokeWidth = j.optDouble("strokeWidth", 0.0).toFloat(),
                keyframes = buildList {
                    val k = j.optJSONArray("keyframes") ?: JSONArray()
                    for (n in 0 until k.length()) {
                        val q = k.optJSONObject(n) ?: continue
                        add(TextKeyframe(q.optLong("timeMs",0L), q.optDouble("x",0.0).toFloat(), q.optDouble("y",-0.65).toFloat(), q.optDouble("scale",1.0).toFloat(), q.optDouble("rotation",0.0).toFloat(), q.optDouble("alpha",1.0).toFloat(), q.optString("easing", "easeInOut")))
                    }
                }.sortedBy { it.timeMs }
            ))
        }
    }

    fun load(context: Context, projectId: String): EditorSettings {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(projectId, null) ?: return EditorSettings()
        return try {
            val j = JSONObject(raw)
            val legacyText = j.optString("text", "")
            val layers = layersFromJson(j.optJSONArray("textLayers") ?: JSONArray()).ifEmpty {
                if (legacyText.isNotBlank()) listOf(TextLayer(text = legacyText, size = j.optDouble("textSize", 28.0).toFloat(), color = j.optString("textColor", "4294967295").toLongOrNull() ?: 0xFFFFFFFF, font = j.optString("textFont", "noto_sans_arabic"))) else emptyList()
            }
            EditorSettings(
                volume = j.optDouble("volume", 1.0).toFloat(),
                muted = j.optBoolean("muted", false),
                speed = j.optDouble("speed", 1.0).toFloat(),
                speedKeyframes = buildList {
                    val k = j.optJSONArray("speedKeyframes") ?: JSONArray()
                    for (n in 0 until k.length()) {
                        val q = k.optJSONObject(n) ?: continue
                        add(SpeedKeyframe(q.optLong("timeMs", 0L), q.optDouble("speed", 1.0).toFloat(), q.optString("easing", "easeInOut")))
                    }
                }.sortedBy { it.timeMs },
                text = legacyText,
                textSize = j.optDouble("textSize", 28.0).toFloat(),
                textColor = j.optString("textColor", "4294967295").toLongOrNull() ?: 0xFFFFFFFF,
                textVisible = j.optBoolean("textVisible", false),
                textFont = j.optString("textFont", "noto_sans_arabic"),
                textLayers = layers,
                subtitles = buildList {
                    val a = j.optJSONArray("subtitles") ?: JSONArray()
                    for (n in 0 until a.length()) {
                        val q = a.optJSONObject(n) ?: continue
                        add(Subtitle(
                            id = q.optString("id", System.nanoTime().toString()),
                            text = q.optString("text", ""),
                            startMs = q.optLong("startMs", 0L),
                            endMs = q.optLong("endMs", 2000L),
                            size = q.optDouble("size", 30.0).toFloat(),
                            color = q.optString("color", "4294967295").toLongOrNull() ?: 0xFFFFFFFF,
                            bold = q.optBoolean("bold", true),
                            x = q.optDouble("x", 0.0).toFloat(),
                            y = q.optDouble("y", 0.72).toFloat(),
                            backgroundAlpha = q.optDouble("backgroundAlpha", 0.55).toFloat()
                        ))
                    }
                }.sortedBy { it.startMs },
                musicUri = j.optString("musicUri", ""),
                musicVolume = j.optDouble("musicVolume", 0.65).toFloat(),
                musicStartMs = j.optLong("musicStartMs", 0L),
                musicDurationMs = j.optLong("musicDurationMs", 0L),
                musicFadeIn = j.optDouble("musicFadeIn", 0.0).toFloat(),
                musicFadeOut = j.optDouble("musicFadeOut", 0.0).toFloat(),
                musicDucking = j.optBoolean("musicDucking", false),
                musicDuckVolume = j.optDouble("musicDuckVolume", 0.28).toFloat(),
                musicDuckAttack = j.optDouble("musicDuckAttack", 0.12).toFloat(),
                musicDuckRelease = j.optDouble("musicDuckRelease", 0.22).toFloat(),
                markers = buildList {
                    val a = j.optJSONArray("markers") ?: JSONArray()
                    for (n in 0 until a.length()) {
                        val q = a.optJSONObject(n) ?: continue
                        add(TimelineMarker(q.optString("id", System.nanoTime().toString()), q.optLong("timeMs", 0L), q.optString("label", "Marker")))
                    }
                }.sortedBy { it.timeMs },
                musicKeyframes = buildList {
                    val k = j.optJSONArray("musicKeyframes") ?: JSONArray()
                    for (n in 0 until k.length()) {
                        val q = k.optJSONObject(n) ?: continue
                        add(MusicKeyframe(q.optLong("timeMs", 0L), q.optDouble("volume", 1.0).toFloat()))
                    }
                }.sortedBy { it.timeMs },
                audioKeyframes = buildList {
                    val k = j.optJSONArray("audioKeyframes") ?: JSONArray()
                    for (n in 0 until k.length()) {
                        val q = k.optJSONObject(n) ?: continue
                        add(AudioKeyframe(q.optLong("timeMs", 0L), q.optDouble("volume", 1.0).toFloat()))
                    }
                }.sortedBy { it.timeMs },
                filter = j.optString("filter", "none"),
                brightness = j.optDouble("brightness", 0.0).toFloat(),
                contrast = j.optDouble("contrast", 1.0).toFloat(),
                saturation = j.optDouble("saturation", 1.0).toFloat(),
                hue = j.optDouble("hue", 0.0).toFloat(),
                temperature = j.optDouble("temperature", 0.0).toFloat(),
                tint = j.optDouble("tint", 0.0).toFloat(),
                blurRadius = j.optDouble("blurRadius", 0.0).toFloat(),
                aspect = j.optString("aspect", "16:9"),
                cropZoom = j.optDouble("cropZoom", 1.0).toFloat(),
                cropX = j.optDouble("cropX", 0.0).toFloat(),
                cropY = j.optDouble("cropY", 0.0).toFloat(),
                rotation = j.optInt("rotation", 0),
                flipHorizontal = j.optBoolean("flipHorizontal", false),
                flipVertical = j.optBoolean("flipVertical", false),
                overlayOpacity = j.optDouble("overlayOpacity", 0.0).toFloat(),
                overlayImageUri = j.optString("overlayImageUri", ""),
                overlayImageX = j.optDouble("overlayImageX", 0.72).toFloat(),
                overlayImageY = j.optDouble("overlayImageY", -0.72).toFloat(),
                overlayImageScale = j.optDouble("overlayImageScale", 0.32).toFloat(),
                overlayImageRotation = j.optDouble("overlayImageRotation", 0.0).toFloat(),
                overlayImageAlpha = j.optDouble("overlayImageAlpha", 1.0).toFloat(),
                pipLayers = buildList {
                    val a = j.optJSONArray("pipLayers") ?: JSONArray()
                    for (n in 0 until a.length()) {
                        val q = a.optJSONObject(n) ?: continue
                        val uri = q.optString("uri", "")
                        if (uri.isBlank()) continue
                        add(PipLayer(
                            id = q.optString("id", System.nanoTime().toString()),
                            uri = uri,
                            x = q.optDouble("x", 0.72).toFloat(),
                            y = q.optDouble("y", -0.72).toFloat(),
                            scale = q.optDouble("scale", 0.32).toFloat(),
                            rotation = q.optDouble("rotation", 0.0).toFloat(),
                            alpha = q.optDouble("alpha", 1.0).toFloat(),
                            visible = q.optBoolean("visible", true)
                        ))
                    }
                }.ifEmpty {
                    if (j.optString("overlayImageUri", "").isNotBlank()) listOf(
                        PipLayer(
                            uri = j.optString("overlayImageUri", ""),
                            x = j.optDouble("overlayImageX", 0.72).toFloat(),
                            y = j.optDouble("overlayImageY", -0.72).toFloat(),
                            scale = j.optDouble("overlayImageScale", 0.32).toFloat(),
                            rotation = j.optDouble("overlayImageRotation", 0.0).toFloat(),
                            alpha = j.optDouble("overlayImageAlpha", 1.0).toFloat()
                        )
                    ) else emptyList()
                },
                sticker = j.optString("sticker", ""),
                stickerX = j.optDouble("stickerX", 0.78).toFloat(),
                stickerY = j.optDouble("stickerY", 0.72).toFloat(),
                stickerScale = j.optDouble("stickerScale", 0.35).toFloat(),
                stickerRotation = j.optDouble("stickerRotation", 0.0).toFloat(),
                stickerAlpha = j.optDouble("stickerAlpha", 1.0).toFloat(),
                transition = j.optString("transition", "none"),
                transitionDuration = j.optDouble("transitionDuration", 0.4).toFloat(),
                textAnimation = j.optString("textAnimation", "none"),
                motionIntensity = j.optDouble("motionIntensity", 1.0).toFloat(),
                fadeIn = j.optDouble("fadeIn", 0.0).toFloat(),
                fadeOut = j.optDouble("fadeOut", 0.0).toFloat(),
                videoKeyframes = buildList {
                    val k = j.optJSONArray("videoKeyframes") ?: JSONArray()
                    for (n in 0 until k.length()) {
                        val q = k.optJSONObject(n) ?: continue
                        add(VideoKeyframe(q.optLong("timeMs", 0L), q.optDouble("x", 0.0).toFloat(), q.optDouble("y", 0.0).toFloat(), q.optDouble("scale", 1.0).toFloat(), q.optDouble("rotation", 0.0).toFloat(), q.optString("easing", "easeInOut")))
                    }
                }.sortedBy { it.timeMs }
            )
        } catch (_: Exception) { EditorSettings() }
    }

    fun save(context: Context, projectId: String, s: EditorSettings) {
        val a = JSONArray()
        s.textLayers.forEach { layer ->
            a.put(JSONObject()
                .put("id", layer.id).put("name", layer.name).put("visible", layer.visible).put("locked", layer.locked).put("text", layer.text).put("size", layer.size)
                .put("color", layer.color.toString()).put("font", layer.font).put("bold", layer.bold)
                .put("alpha", layer.alpha).put("x", layer.x).put("y", layer.y).put("rotation", layer.rotation).put("scale", layer.scale)
                .put("animation", layer.animation).put("backgroundColor", layer.backgroundColor.toString()).put("backgroundAlpha", layer.backgroundAlpha)
                .put("shadowEnabled", layer.shadowEnabled).put("shadowColor", layer.shadowColor.toString()).put("shadowRadius", layer.shadowRadius).put("shadowDx", layer.shadowDx).put("shadowDy", layer.shadowDy)
                .put("strokeEnabled", layer.strokeEnabled).put("strokeColor", layer.strokeColor.toString()).put("strokeWidth", layer.strokeWidth)
                .put("keyframes", JSONArray().apply { layer.keyframes.sortedBy { it.timeMs }.forEach { k -> put(JSONObject().put("timeMs", k.timeMs).put("x", k.x).put("y", k.y).put("scale", k.scale).put("rotation", k.rotation).put("easing", k.easing).put("alpha", k.alpha)) } }))
        }
        val j = JSONObject()
            .put("volume", s.volume).put("muted", s.muted).put("speed", s.speed)
            .put("speedKeyframes", JSONArray().apply { s.speedKeyframes.sortedBy { it.timeMs }.forEach { k -> put(JSONObject().put("timeMs", k.timeMs).put("speed", k.speed).put("easing", k.easing)) } })
            .put("text", s.text).put("textSize", s.textSize).put("textColor", s.textColor.toString())
            .put("textVisible", s.textVisible).put("textFont", s.textFont).put("textLayers", a)
            .put("subtitles", JSONArray().apply { s.subtitles.sortedBy { it.startMs }.forEach { q -> put(JSONObject().put("id", q.id).put("text", q.text).put("startMs", q.startMs).put("endMs", q.endMs).put("size", q.size).put("color", q.color.toString()).put("bold", q.bold).put("x", q.x).put("y", q.y).put("backgroundAlpha", q.backgroundAlpha)) } })
            .put("musicUri", s.musicUri).put("musicVolume", s.musicVolume)
            .put("musicStartMs", s.musicStartMs).put("musicDurationMs", s.musicDurationMs)
            .put("musicFadeIn", s.musicFadeIn)
            .put("musicFadeOut", s.musicFadeOut)
            .put("musicDucking", s.musicDucking)
            .put("markers", JSONArray().apply { s.markers.sortedBy { it.timeMs }.forEach { m -> put(JSONObject().put("id", m.id).put("timeMs", m.timeMs).put("label", m.label)) } })
            .put("musicDuckVolume", s.musicDuckVolume)
            .put("musicDuckAttack", s.musicDuckAttack)
            .put("musicDuckRelease", s.musicDuckRelease)
            .put("musicKeyframes", JSONArray().apply { s.musicKeyframes.sortedBy { it.timeMs }.forEach { k -> put(JSONObject().put("timeMs", k.timeMs).put("volume", k.volume)) } })
            .put("audioKeyframes", JSONArray().apply { s.audioKeyframes.sortedBy { it.timeMs }.forEach { k -> put(JSONObject().put("timeMs", k.timeMs).put("volume", k.volume)) } })
            .put("filter", s.filter).put("brightness", s.brightness).put("contrast", s.contrast)
            .put("saturation", s.saturation).put("hue", s.hue).put("temperature", s.temperature).put("tint", s.tint).put("blurRadius", s.blurRadius).put("aspect", s.aspect)
            .put("cropZoom", s.cropZoom).put("cropX", s.cropX).put("cropY", s.cropY)
            .put("rotation", s.rotation).put("flipHorizontal", s.flipHorizontal).put("flipVertical", s.flipVertical).put("overlayOpacity", s.overlayOpacity)
            .put("overlayImageUri", s.overlayImageUri).put("overlayImageX", s.overlayImageX).put("overlayImageY", s.overlayImageY).put("overlayImageScale", s.overlayImageScale).put("overlayImageRotation", s.overlayImageRotation).put("overlayImageAlpha", s.overlayImageAlpha)
            .put("pipLayers", JSONArray().apply { s.pipLayers.forEach { p -> put(JSONObject().put("id", p.id).put("uri", p.uri).put("x", p.x).put("y", p.y).put("scale", p.scale).put("rotation", p.rotation).put("alpha", p.alpha).put("visible", p.visible)) })
            .put("sticker", s.sticker).put("stickerX", s.stickerX).put("stickerY", s.stickerY)
            .put("stickerScale", s.stickerScale).put("stickerRotation", s.stickerRotation).put("stickerAlpha", s.stickerAlpha)
            .put("transition", s.transition)
            .put("transitionDuration", s.transitionDuration).put("textAnimation", s.textAnimation)
            .put("motionIntensity", s.motionIntensity).put("fadeIn", s.fadeIn).put("fadeOut", s.fadeOut)
            .put("videoKeyframes", JSONArray().apply { s.videoKeyframes.sortedBy { it.timeMs }.forEach { k -> put(JSONObject().put("timeMs", k.timeMs).put("x", k.x).put("y", k.y).put("scale", k.scale).put("rotation", k.rotation).put("easing", k.easing)) } })
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(projectId, j.toString()).apply()
    }
}
