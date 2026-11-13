package com.better_video_player.data

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// A subtitle file kept for a video: downloaded from OpenSubtitles (fileId), or picked from
// the phone's files (fileId 0).
data class SavedSubtitle(val path: String, val fileId: Int, val title: String, val language: String?) {
    val file: File get() = File(path)
}

// A video's saved subtitles, and the one turned on (null: none of them).
data class VideoSubtitles(val files: List<SavedSubtitle> = emptyList(), val chosen: String? = null) {
    // Those still there.
    val present: List<SavedSubtitle> get() = files.filter { it.file.exists() }
    fun downloaded(fileId: Int): SavedSubtitle? = files.firstOrNull { it.fileId == fileId && it.file.exists() }
}

// Subtitles added to videos, by video key (as in the watch history). Android won't let an app
// write next to the phone's videos, so unlike on the desktop the files are kept in Vessel's own
// folder (`folder`) and added to the video each time it's opened. The list is subtitles.json.
class SavedSubtitles(private val dir: File, private val scope: CoroutineScope) {
    val folder = File(dir, "subtitles")
    private val file = File(dir, "subtitles.json")

    // subtitles.json couldn't be read: then its files aren't swept away, in case it can be later.
    private var unreadable = false
    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<Map<String, VideoSubtitles>> = _entries

    init {
        // Files left behind by a list that was never written (a crash), or dropped from it.
        scope.launch(Dispatchers.IO) { sweep() }
    }

    fun get(key: String): VideoSubtitles = _entries.value[key] ?: VideoSubtitles()

    // Saves `data` as a subtitle for the video called `fileName` ("Movie.mkv"): "Movie.en.srt",
    // or "Movie.en.2.srt" if that's taken. On the calling thread (call it off the main one).
    fun write(fileName: String, language: String?, extension: String, data: ByteArray): File {
        folder.mkdirs()
        val base = fileName.substringBeforeLast('.').replace(Regex("""[\\/:*?"<>|]"""), "_").take(120) +
            (if (language.isNullOrEmpty()) "" else ".$language")
        var target = File(folder, "$base.$extension")
        var n = 2
        while (target.exists()) target = File(folder, "$base.${n++}.$extension")
        val temp = File(folder, target.name + ".tmp")
        temp.writeBytes(data)
        if (!temp.renameTo(target)) {
            temp.delete()
            throw java.io.IOException("Couldn't save ${target.name}")
        }
        return target
    }

    // A subtitle saved for a video, and turned on. Only the last MAX_PER_VIDEO added to a video
    // are kept, so trying subtitle after subtitle doesn't fill the phone. The older ones' files
    // are deleted by sweep() on the next start (the video playing may still be using them).
    fun add(key: String, subtitle: SavedSubtitle) {
        _entries.update { all ->
            val entry = all[key] ?: VideoSubtitles()
            val files = entry.files.filter { it.path != subtitle.path && it.file.exists() } + subtitle
            all + (key to VideoSubtitles(files.takeLast(MAX_PER_VIDEO), subtitle.path))
        }
        save()
    }

    // The user picked a subtitle track: one of the saved files (path), or something else (null).
    fun choose(key: String, path: String?) {
        val entry = _entries.value[key] ?: return
        if (entry.chosen == path) return
        _entries.update { it + (key to entry.copy(chosen = path)) }
        save()
    }

    private fun load(): Map<String, VideoSubtitles> {
        if (!file.exists()) return emptyMap()
        return try {
            val json = JSONObject(file.readText())
            buildMap {
                for (key in json.keys()) {
                    val o = json.optJSONObject(key) ?: continue
                    val files = o.optJSONArray("files") ?: JSONArray()
                    val list = (0 until files.length()).mapNotNull { i ->
                        files.optJSONObject(i)?.let {
                            SavedSubtitle(it.optString("path"), it.optInt("fileId"), it.optString("title"),
                                          it.optString("language").ifEmpty { null })
                        }
                    }
                    // Files deleted since (by sweep(), or by the user clearing storage) are left out,
                    // and a video with none left is dropped.
                    val present = list.filter { it.file.exists() }
                    if (present.isNotEmpty()) put(key, VideoSubtitles(present, o.optString("chosen").ifEmpty { null }))
                }
            }
        } catch (e: Exception) {
            Log.w("SavedSubtitles", "Couldn't read ${file.path}", e)
            unreadable = true
            emptyMap()
        }
    }

    // Each save writes the list as it is when its turn comes, not as it was when asked: saves
    // can run in any order, and whichever runs last then still writes the newest list.
    private fun save() {
        scope.launch(Dispatchers.IO) { writeLatest() }
    }

    // Deletes files in `folder` the list doesn't name.
    @Synchronized
    private fun sweep() {
        if (unreadable) return
        val kept = _entries.value.values.flatMap { e -> e.files.map { it.file.name } }.toSet()
        folder.listFiles()?.forEach { if (it.name !in kept) it.delete() }
    }

    @Synchronized
    private fun writeLatest() {
        val entries = _entries.value
        try {
            val json = JSONObject()
            for ((key, entry) in entries) {
                val files = JSONArray()
                for (s in entry.files) {
                    files.put(JSONObject().put("path", s.path).put("fileId", s.fileId).put("title", s.title)
                                  .put("language", s.language ?: ""))
                }
                json.put(key, JSONObject().put("files", files).put("chosen", entry.chosen ?: ""))
            }
            // Written aside and swapped in, so a crash mid-write can't lose the list.
            val temp = File(file.path + ".tmp")
            temp.writeText(json.toString())
            if (!temp.renameTo(file)) {
                file.delete()
                temp.renameTo(file)
            }
        } catch (e: Exception) {
            Log.w("SavedSubtitles", "Couldn't write ${file.path}", e)
        }
    }

    companion object {
        // The subtitle formats both engines read.
        val extensions = setOf("srt", "ass", "ssa", "vtt", "ttml")

        // Subtitles kept per video (the newest).
        const val MAX_PER_VIDEO = 5

        private val srtCue = Regex("""^\s*\d+\s*\r?\n\s*\d{1,2}:\d{2}:\d{2}[,.]\d{1,3}\s*-->""")

        // Which of `extensions` a file is, from how it starts; null if none of them.
        fun sniffExtension(data: ByteArray): String? {
            val head = String(data, 0, minOf(data.size, 4096), Charsets.UTF_8).removePrefix("﻿").trimStart()
            return when {
                head.startsWith("WEBVTT") -> "vtt"
                head.startsWith("[Script Info]", ignoreCase = true) -> "ass"
                srtCue.containsMatchIn(head) -> "srt"
                head.startsWith("<") && Regex("""<tt[\s>]""").containsMatchIn(head) -> "ttml"
                else -> null
            }
        }
    }
}
