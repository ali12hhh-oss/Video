package com.videoforge.nativeeditor

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Persists real editing projects locally without adding a database dependency. */
data class SavedProject(
    val id: String,
    val name: String,
    val clips: List<Clip>,
    val updatedAtMs: Long
) {
    val primaryClip: Clip? get() = clips.firstOrNull()
}

object ProjectRepository {
    private const val PREFS = "videoforge_projects"
    private const val KEY_PROJECTS = "projects"

    fun newId(): String = UUID.randomUUID().toString()

    fun load(context: Context): List<SavedProject> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PROJECTS, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val p = array.optJSONObject(i) ?: continue
                    val clipsArray = p.optJSONArray("clips") ?: JSONArray()
                    val clips = buildList {
                        for (j in 0 until clipsArray.length()) {
                            val c = clipsArray.optJSONObject(j) ?: continue
                            val uriText = c.optString("uri", "")
                            if (uriText.isBlank()) continue
                            add(
                                Clip(
                                    uri = Uri.parse(uriText),
                                    name = c.optString("name", "Video"),
                                    durationMs = c.optLong("durationMs", 0L),
                                    trimStartMs = c.optLong("trimStartMs", 0L),
                                    trimEndMs = c.optLong("trimEndMs", Long.MAX_VALUE),
                                    audioVolume = c.optDouble("audioVolume", 1.0).toFloat(),
                                    audioMuted = c.optBoolean("audioMuted", false),
                                    audioFadeIn = c.optDouble("audioFadeIn", 0.0).toFloat(),
                                    audioFadeOut = c.optDouble("audioFadeOut", 0.0).toFloat(),
                                    audioKeyframes = buildList {
                                        val k = c.optJSONArray("audioKeyframes") ?: JSONArray()
                                        for (n in 0 until k.length()) {
                                            val q = k.optJSONObject(n) ?: continue
                                            add(ClipAudioKeyframe(q.optLong("timeMs", 0L), q.optDouble("volume", 1.0).toFloat()))
                                        }
                                    }.sortedBy { it.timeMs }
                                )
                            )
                        }
                    }
                    if (clips.isNotEmpty()) {
                        add(
                            SavedProject(
                                id = p.optString("id", newId()),
                                name = p.optString("name", clips.first().name),
                                clips = clips,
                                updatedAtMs = p.optLong("updatedAtMs", System.currentTimeMillis())
                            )
                        )
                    }
                }
            }.sortedByDescending { it.updatedAtMs }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(context: Context, id: String, clips: List<Clip>, name: String? = null) {
        if (clips.isEmpty()) return
        val projects = load(context).toMutableList()
        val projectName = name?.takeIf { it.isNotBlank() } ?: clips.first().name
        val saved = SavedProject(id, projectName, clips, System.currentTimeMillis())
        val index = projects.indexOfFirst { it.id == id }
        if (index >= 0) projects[index] = saved else projects.add(saved)
        persist(context, projects)
    }

    fun duplicate(context: Context, id: String, newName: String? = null): String? {
        val source = load(context).firstOrNull { it.id == id } ?: return null
        val copyId = newId()
        val cleanName = newName?.trim().takeIf { !it.isNullOrBlank() } ?: "${source.name} • copy"
        val projects = load(context).toMutableList()
        projects.add(source.copy(id = copyId, name = cleanName, updatedAtMs = System.currentTimeMillis()))
        persist(context, projects)
        return copyId
    }

    fun rename(context: Context, id: String, newName: String) {
        val clean = newName.trim()
        if (clean.isBlank()) return
        val projects = load(context).toMutableList()
        val index = projects.indexOfFirst { it.id == id }
        if (index < 0) return
        val old = projects[index]
        projects[index] = old.copy(name = clean, updatedAtMs = System.currentTimeMillis())
        persist(context, projects)
    }

    fun delete(context: Context, id: String) {
        persist(context, load(context).filterNot { it.id == id })
    }

    private fun persist(context: Context, projects: List<SavedProject>) {
        val array = JSONArray()
        projects.sortedByDescending { it.updatedAtMs }.take(50).forEach { project ->
            val p = JSONObject()
                .put("id", project.id)
                .put("name", project.name)
                .put("updatedAtMs", project.updatedAtMs)
            val clips = JSONArray()
            project.clips.forEach { clip ->
                clips.put(
                    JSONObject()
                        .put("uri", clip.uri.toString())
                        .put("name", clip.name)
                        .put("durationMs", clip.durationMs)
                        .put("trimStartMs", clip.trimStartMs)
                        .put("trimEndMs", clip.trimEndMs)
                        .put("audioVolume", clip.audioVolume)
                        .put("audioMuted", clip.audioMuted)
                        .put("audioFadeIn", clip.audioFadeIn)
                        .put("audioFadeOut", clip.audioFadeOut)
                        .put("audioKeyframes", JSONArray().apply {
                            clip.audioKeyframes.sortedBy { it.timeMs }.forEach { k ->
                                put(JSONObject().put("timeMs", k.timeMs).put("volume", k.volume))
                            }
                        })
                )
            }
            p.put("clips", clips)
            array.put(p)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_PROJECTS, array.toString()).apply()
    }
}
