package com.better_video_player.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

// What was picked for a video, to pick it again next time, as the desktop app does:
//  - audio: the audio track, by its place among the video's ("#2"); null for the default.
//  - subtitle: a subtitle built into the video ("#1"), or "off"; null for the default. (A
//    subtitle file added to the video is remembered by SavedSubtitles instead.)
//  - found: the answer to "Subtitles found" for a subtitle file next to the video: "on" or
//    "off"; null if never asked.
data class TrackChoice(val audio: String? = null, val subtitle: String? = null, val found: String? = null) {
    val isEmpty: Boolean get() = audio == null && subtitle == null && found == null
}

// choices.json: { videoKey: { audio, subtitle, found } }, by video key as in the watch history.
class TrackChoices(dir: File, private val scope: CoroutineScope) {
    private val file = File(dir, "choices.json")
    private val entries = MutableStateFlow(load())

    fun get(key: String): TrackChoice = entries.value[key] ?: TrackChoice()

    fun update(key: String, change: (TrackChoice) -> TrackChoice) {
        if (key.isEmpty()) return
        entries.update { all ->
            val changed = change(all[key] ?: TrackChoice())
            if (changed.isEmpty) all - key else all + (key to changed)
        }
        save()
    }

    // The video was renamed or moved: its choices go with it.
    fun relocate(from: String, to: String) {
        val choice = entries.value[from] ?: return
        entries.update { it - from + (to to choice) }
        save()
    }

    // The video was deleted.
    fun forget(key: String) {
        if (key !in entries.value) return
        entries.update { it - key }
        save()
    }

    private fun load(): Map<String, TrackChoice> {
        val json = JsonFile.read(file, TAG) ?: return emptyMap()
        return buildMap {
            for (key in json.keys()) {
                val o = json.optJSONObject(key) ?: continue
                fun field(name: String) = o.optString(name).ifEmpty { null }
                val choice = TrackChoice(field("audio"), field("subtitle"), field("found"))
                if (!choice.isEmpty) put(key, choice)
            }
        }
    }

    // Writes the choices as they are when its turn comes (see SavedSubtitles.save()).
    private fun save() {
        scope.launch(Dispatchers.IO) { writeLatest() }
    }

    @Synchronized
    private fun writeLatest() {
        val json = JSONObject()
        for ((key, choice) in entries.value) {
            json.put(key, JSONObject().apply {
                choice.audio?.let { put("audio", it) }
                choice.subtitle?.let { put("subtitle", it) }
                choice.found?.let { put("found", it) }
            })
        }
        JsonFile.write(file, json, TAG)
    }

    private companion object {
        const val TAG = "TrackChoices"
    }
}
