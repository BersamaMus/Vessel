package com.better_video_player.data

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

// How far into a video it was watched, and when.
data class Seen(
    val position: Double,
    val duration: Double,
    val watchedAt: Long,
    val finished: Boolean,
) {
    val started: Boolean get() = !finished && position >= MIN_PROGRESS
    val progress: Float get() = if (finished) 1f else if (duration > 0) (position / duration).toFloat().coerceIn(0f, 1f) else 0f
}

// history.json, in the desktop app's format: { key: {position, duration, watchedAt, finished} },
// times in seconds and watchedAt in milliseconds since 1970.
class WatchHistory(private val file: File, private val scope: CoroutineScope) {

    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<Map<String, Seen>> = _entries

    private var saveJob: Job? = null

    fun get(key: String): Seen? = _entries.value[key]

    // Where to pick a video up again: a little before where it was left, to catch the scene.
    fun resumePosition(key: String): Double {
        val seen = get(key) ?: return 0.0
        if (seen.finished || seen.position < MIN_PROGRESS) return 0.0
        return (seen.position - 3).coerceAtLeast(0.0)
    }

    fun save(key: String, position: Double, duration: Double) {
        if (duration <= 0) return
        put(key, Seen(position, duration, System.currentTimeMillis(), isFinished(position, duration)))
    }

    fun setWatched(key: String, watched: Boolean, duration: Double) {
        if (watched) put(key, Seen(duration, duration, System.currentTimeMillis(), true))
        else remove(key)
    }

    fun remove(key: String) {
        _entries.update { it - key }
        scheduleSave()
    }

    fun clear() {
        _entries.value = emptyMap()
        scheduleSave()
    }

    private fun put(key: String, seen: Seen) {
        _entries.update { it + (key to seen) }
        scheduleSave()
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch(Dispatchers.IO) {
            delay(1000)
            write(_entries.value)
        }
    }

    // Now, before the app may be stopped.
    fun flush() {
        if (saveJob?.isActive != true) return
        saveJob?.cancel()
        val snapshot = _entries.value
        scope.launch(Dispatchers.IO) { write(snapshot) }
    }

    // On the calling thread: for when the scope is gone (the app closing), so nothing launched
    // on it would run.
    fun writeNow() {
        saveJob?.cancel()
        try {
            write(_entries.value)
        } catch (e: Exception) {
            Log.w("WatchHistory", "Couldn't write ${file.path}", e)
        }
    }

    private fun load(): Map<String, Seen> {
        if (!file.exists()) return emptyMap()
        return try {
            val json = JSONObject(file.readText())
            buildMap {
                for (key in json.keys()) {
                    val o = json.optJSONObject(key) ?: continue
                    put(key, Seen(o.optDouble("position", 0.0), o.optDouble("duration", 0.0),
                                  o.optLong("watchedAt", 0), o.optBoolean("finished", false)))
                }
            }
        } catch (e: Exception) {
            Log.w("WatchHistory", "Couldn't read ${file.path}", e)
            emptyMap()
        }
    }

    @Synchronized
    private fun write(entries: Map<String, Seen>) {
        val json = JSONObject()
        for ((key, seen) in entries) {
            json.put(key, JSONObject()
                .put("position", seen.position)
                .put("duration", seen.duration)
                .put("watchedAt", seen.watchedAt)
                .put("finished", seen.finished))
        }
        // Written aside and swapped in, so a crash mid-write can't lose the history.
        val temp = File(file.path + ".tmp")
        temp.writeText(json.toString())
        if (!temp.renameTo(file)) {
            file.delete()
            temp.renameTo(file)
        }
    }
}
