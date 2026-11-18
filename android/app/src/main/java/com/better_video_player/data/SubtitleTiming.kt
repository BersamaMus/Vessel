package com.better_video_player.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

// The timing fixed for videos' subtitles (see VideoEngine.setSubtitleDelay), remembered so a
// video's subtitles are in step again the next time it plays. One per subtitle track, since
// each may be off by a different amount: by video key (as in the watch history), then by track
// (a subtitle file's path, or "#2" for the second one built into the video). The delays are in
// seconds, as the desktop app keeps them. Only tracks whose timing was changed are listed.
// subtitle-timing.json.
class SubtitleTiming(dir: File, private val scope: CoroutineScope) {
    private val file = File(dir, "subtitle-timing.json")
    private val entries = MutableStateFlow(load())

    // In ms; 0 if it was never changed.
    fun get(key: String, track: String): Long =
        entries.value[key]?.get(track)?.let { Math.round(it * 1000) } ?: 0L

    fun set(key: String, track: String, ms: Long) {
        if (key.isEmpty() || track.isEmpty()) return
        entries.update { all ->
            val timing = all[key].orEmpty()
            // Back to the file's own timing: nothing to keep.
            val changed = if (ms == 0L) timing - track else timing + (track to ms / 1000.0)
            if (changed.isEmpty()) all - key else all + (key to changed)
        }
        scope.launch(Dispatchers.IO) { writeLatest() }
    }

    // The video was renamed or moved: its timings go with it. A subtitle file's track keeps
    // its key, since Vessel's copy of the file doesn't move.
    fun relocate(from: String, to: String) {
        val timing = entries.value[from] ?: return
        entries.update { it - from + (to to timing) }
        scope.launch(Dispatchers.IO) { writeLatest() }
    }

    // The video was deleted.
    fun forget(key: String) {
        if (key !in entries.value) return
        entries.update { it - key }
        scope.launch(Dispatchers.IO) { writeLatest() }
    }

    private fun load(): Map<String, Map<String, Double>> {
        val json = JsonFile.read(file, TAG) ?: return emptyMap()
        return buildMap {
            for (key in json.keys()) {
                val o = json.optJSONObject(key) ?: continue
                val timing = buildMap { for (track in o.keys()) put(track, o.optDouble(track, 0.0)) }
                    .filterValues { it != 0.0 && !it.isNaN() }
                if (timing.isNotEmpty()) put(key, timing)
            }
        }
    }

    // Writes the timings as they are when its turn comes (see SavedSubtitles.save()).
    @Synchronized
    private fun writeLatest() {
        val json = JSONObject()
        for ((key, timing) in entries.value) {
            val o = JSONObject()
            for ((track, seconds) in timing) o.put(track, seconds)
            json.put(key, o)
        }
        JsonFile.write(file, json, TAG)
    }

    private companion object {
        const val TAG = "SubtitleTiming"
    }
}
