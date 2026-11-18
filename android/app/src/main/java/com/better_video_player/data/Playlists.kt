package com.better_video_player.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

// A playlist the user made: a name and its videos' keys in order. Videos are kept by key (as
// in the watch history), so one that's gone just isn't shown; it comes back if the file does.
data class Playlist(
    val id: String,
    val name: String,
    // ms since 1970.
    val created: Long,
    // When a video was last added: the playlists most recently added to come first.
    val updated: Long,
    val keys: List<String>,
)

// playlists.json, in the desktop app's format: { "playlists": [ {id, name, created, updated,
// videos: [keys]} ] }.
class Playlists(dir: File, private val scope: CoroutineScope) {
    private val file = File(dir, "playlists.json")
    private val _all = MutableStateFlow(load())
    // Most recently added to first.
    val all: StateFlow<List<Playlist>> = _all

    fun get(id: String): Playlist? = _all.value.firstOrNull { it.id == id }

    // The playlists `key` is in.
    fun containing(key: String): Set<String> = _all.value.filter { key in it.keys }.map { it.id }.toSet()

    // A new playlist, with `firstKey` in it if given. Returns its id.
    fun create(name: String, firstKey: String? = null): String {
        val now = System.currentTimeMillis()
        val playlist = Playlist(UUID.randomUUID().toString(), name.trim().ifEmpty { "Playlist" }, now, now, listOfNotNull(firstKey))
        change { listOf(playlist) + it }
        return playlist.id
    }

    fun rename(id: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        edit(id) { it.copy(name = trimmed) }
    }

    // Returns what was deleted, and where it was, for Undo.
    fun delete(id: String): Pair<Playlist, Int>? {
        val list = _all.value
        val at = list.indexOfFirst { it.id == id }
        if (at < 0) return null
        change { it.filterNot { p -> p.id == id } }
        return list[at] to at
    }

    fun restore(playlist: Playlist, at: Int) {
        change { list -> list.filterNot { it.id == playlist.id }.toMutableList().apply { add(at.coerceIn(0, size), playlist) } }
    }

    // At the end (or at `at`), once: a video already there stays where it is.
    fun add(id: String, key: String, at: Int? = null) {
        val playlist = get(id) ?: return
        if (key in playlist.keys) return
        val keys = playlist.keys.toMutableList().apply { add((at ?: size).coerceIn(0, size), key) }
        edit(id) { it.copy(keys = keys, updated = System.currentTimeMillis()) }
        sortByUpdated()
    }

    // Returns where it was, for Undo (-1 if it wasn't there).
    fun remove(id: String, key: String): Int {
        val playlist = get(id) ?: return -1
        val at = playlist.keys.indexOf(key)
        if (at < 0) return -1
        edit(id) { it.copy(keys = it.keys - key) }
        return at
    }

    // Moves the video at `from` to `to` (up or down the list).
    fun move(id: String, from: Int, to: Int) {
        val playlist = get(id) ?: return
        if (from !in playlist.keys.indices || to !in playlist.keys.indices || from == to) return
        val keys = playlist.keys.toMutableList().apply { add(to, removeAt(from)) }
        edit(id) { it.copy(keys = keys) }
    }

    // A video was renamed or moved: it keeps its place in every playlist.
    fun relocate(from: String, to: String) {
        if (_all.value.none { from in it.keys }) return
        change { list -> list.map { p -> if (from in p.keys) p.copy(keys = p.keys.map { if (it == from) to else it }) else p } }
    }

    // A video was deleted: out of every playlist.
    fun forget(key: String) {
        if (_all.value.none { key in it.keys }) return
        change { list -> list.map { p -> if (key in p.keys) p.copy(keys = p.keys - key) else p } }
    }

    private fun edit(id: String, block: (Playlist) -> Playlist) =
        change { list -> list.map { if (it.id == id) block(it) else it } }

    private fun sortByUpdated() = change { list -> list.sortedByDescending { it.updated } }

    private fun change(block: (List<Playlist>) -> List<Playlist>) {
        _all.update(block)
        scope.launch(Dispatchers.IO) { writeLatest() }
    }

    private fun load(): List<Playlist> {
        val list = JsonFile.read(file, TAG)?.optJSONArray("playlists") ?: return emptyList()
        return (0 until list.length()).mapNotNull { i ->
            val o = list.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").ifEmpty { return@mapNotNull null }
            val videos = o.optJSONArray("videos") ?: JSONArray()
            Playlist(id, o.optString("name"), o.optLong("created"), o.optLong("updated"),
                     (0 until videos.length()).map { videos.optString(it) }.filter { it.isNotEmpty() })
        }.sortedByDescending { it.updated }
    }

    // Writes the playlists as they are when its turn comes (see SavedSubtitles.save()).
    @Synchronized
    private fun writeLatest() {
        val list = JSONArray()
        for (p in _all.value) {
            list.put(JSONObject().put("id", p.id).put("name", p.name).put("created", p.created)
                         .put("updated", p.updated).put("videos", JSONArray(p.keys)))
        }
        JsonFile.write(file, JSONObject().put("playlists", list), TAG)
    }

    private companion object {
        const val TAG = "Playlists"
    }
}
