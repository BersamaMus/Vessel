@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.better_video_player

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.better_video_player.data.AppSettings
import com.better_video_player.data.Folder
import com.better_video_player.data.MediaLibrary
import com.better_video_player.data.NaturalOrder
import com.better_video_player.data.OpenSubtitles
import com.better_video_player.data.SavedSubtitle
import com.better_video_player.data.SavedSubtitles
import com.better_video_player.data.SubtitleException
import com.better_video_player.data.SubtitleLimitException
import com.better_video_player.data.SubtitleResult
import com.better_video_player.data.SubtitleTiming
import com.better_video_player.data.FileOutcome
import com.better_video_player.data.FoundSubtitle
import com.better_video_player.data.Playlist
import com.better_video_player.data.Playlists
import com.better_video_player.data.SubtitleFinder
import com.better_video_player.data.TrackChoice
import com.better_video_player.data.TrackChoices
import com.better_video_player.data.VideoFiles
import com.better_video_player.data.WatchStats
import com.better_video_player.data.renamedFileName
import com.better_video_player.engine.Picture
import com.better_video_player.data.Seen
import com.better_video_player.data.UpdateCheck
import com.better_video_player.data.Video
import com.better_video_player.data.WatchHistory
import com.better_video_player.engine.EngineError
import com.better_video_player.engine.EngineListener
import com.better_video_player.engine.ExternalSubtitle
import com.better_video_player.engine.ExoEngine
import com.better_video_player.engine.MpvEngine
import com.better_video_player.engine.MpvThumbnailer
import com.better_video_player.ui.Thumbnails
import com.better_video_player.engine.Phase
import com.better_video_player.engine.TrackInfo
import com.better_video_player.engine.TrackType
import com.better_video_player.engine.VideoEngine
import com.better_video_player.playback.PlaybackService
import com.better_video_player.playback.SessionPlayer
import android.content.ComponentName
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.random.Random

// The library grouped for the pages: every video, its folders, and lookups.
data class LibraryIndex(
    val videos: List<Video> = emptyList(),
    // By name.
    val folders: List<Folder> = emptyList(),
    val byFolder: Map<String, Folder> = emptyMap(),
    val byKey: Map<String, Video> = emptyMap(),
)

// A note at the bottom of the app ("Removed from history"), with Undo when it can be undone.
data class Toast(val message: String, val undo: (() -> Unit)? = null, val id: Long = System.nanoTime())

// "Subtitles found" for the video playing: subtitle files next to it, offered once.
data class FoundSubtitlesOffer(val videoKey: String, val subtitles: List<FoundSubtitle>)

// The list beside the video: Play all / Shuffle's list, or else the video's folder.
data class Queue(
    val name: String,
    val videos: List<Video>,
    val index: Int,
    // Play all or Shuffle, rather than just the folder.
    val playlist: Boolean = false,
    val shuffled: Boolean = false,
) {
    val next: Video? get() = videos.getOrNull(index + 1)
    val previous: Video? get() = if (index > 0) videos.getOrNull(index - 1) else null
}

// "Search online" in the subtitles sheet, as the sheet shows it.
data class SubtitleSearchState(
    val searching: Boolean = false,
    // A search has finished (so no results means none were found, not none looked for yet).
    val searched: Boolean = false,
    // Those that match the file's hash first.
    val results: List<SubtitleResult> = emptyList(),
    // The subtitle being downloaded (its fileId), or 0.
    val downloading: Int = 0,
    // What went wrong; "" if nothing.
    val error: String = "",
)

// The timing of the subtitles showing: which track they are (see SubtitleTiming; "" while
// subtitles are off), and how much later than their file says they show (ms, negative: earlier).
data class SubtitleTimingState(val track: String = "", val delayMs: Long = 0) {
    val subtitlesOn: Boolean get() = track.isNotEmpty()
}

// "0.5 s later", for a subtitle delay.
fun delayText(delayMs: Long): String {
    val seconds = "%.1f".format(kotlin.math.abs(delayMs) / 1000.0)
    return when {
        kotlin.math.abs(delayMs) < 50 -> "Original timing"
        delayMs > 0 -> "$seconds s later"
        else -> "$seconds s earlier"
    }
}

// A subtitle file bigger than this (10 MB) is something else picked by mistake.
private const val MAX_SUBTITLE_BYTES = 10 * 1024 * 1024

// The list Play all / Shuffle (or a playlist) plays, in order.
private data class PlayList(val name: String, val videos: List<Video>, val shuffled: Boolean)

class VesselViewModel(app: Application) : AndroidViewModel(app) {

    val library = MediaLibrary(app, viewModelScope)
    val history = WatchHistory(File(app.filesDir, "history.json"), viewModelScope)
    val settings = AppSettings(app)
    val savedSubtitles = SavedSubtitles(app.filesDir, viewModelScope)
    val openSubtitles = OpenSubtitles(app)
    private val timing = SubtitleTiming(app.filesDir, viewModelScope)
    private val choices = TrackChoices(app.filesDir, viewModelScope)
    val playlists = Playlists(app.filesDir, viewModelScope)
    val stats = WatchStats(app.filesDir, viewModelScope)
    private val files = VideoFiles(app)
    private val subtitleFinder = SubtitleFinder(app)

    // Thumbnails (and durations) for the videos Android can't read: see MpvThumbnailer.
    val thumbnailer = MpvThumbnailer(app, viewModelScope)

    val index: StateFlow<LibraryIndex> = combine(library.videos, thumbnailer.durations) { all, durations ->
            // Durations MediaStore didn't know, from mpv's thumbnails.
            if (durations.isEmpty()) all
            else all.map { v -> if (v.durationMs > 0) v else durations[thumbnailer.keyOf(v)]?.let { v.copy(durationMs = it) } ?: v }
        }
        .map { videos ->
            val folders = videos.groupBy { it.folder }.map { (key, inFolder) ->
                val first = inFolder.first()
                Folder(
                    key = key,
                    name = first.folderName.ifEmpty { library.volumeLabel(first.volume) },
                    location = library.locationOf(first),
                    videos = inFolder.sortedWith(compareBy(NaturalOrder) { it.fileName }),
                )
            }.sortedWith(compareBy(NaturalOrder) { it.name })
            LibraryIndex(videos, folders, folders.associateBy { it.key }, videos.associateBy { it.key })
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, LibraryIndex())

    // --- Home's shelves --------------------------------------------------------
    val continueWatching: StateFlow<List<Video>> = combine(index, history.entries) { index, seen ->
        index.videos.filter { seen[it.key]?.started == true }
            .sortedByDescending { seen[it.key]?.watchedAt ?: 0 }
            .take(SHELF_SIZE)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val recentlyAdded: StateFlow<List<Video>> = index
        .map { it.videos.sortedByDescending { v -> v.addedMs }.take(SHELF_SIZE) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // A shuffle that stays put until reshuffled.
    private val pickSeed = MutableStateFlow(Random.nextInt())
    val quickPicks: StateFlow<List<Video>> = combine(index, pickSeed) { index, seed ->
        index.videos.sortedBy { (it.key.hashCode() xor seed) * 0x9E3779B1.toInt() }.take(QUICK_PICKS)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun reshuffleQuickPicks() {
        pickSeed.value = Random.nextInt()
    }

    // Everything watched that's still here, newest first.
    val watched: StateFlow<List<Pair<Video, Seen>>> = combine(index, history.entries) { index, seen ->
        index.videos.mapNotNull { v -> seen[v.key]?.let { v to it } }.sortedByDescending { it.second.watchedAt }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // --- The player ------------------------------------------------------------
    // ExoPlayer plays everything it can; mpv takes over what it can't (see onEngineError).
    val exo = ExoEngine(app)
    private var mpvEngine: MpvEngine? = null

    private val _engine = MutableStateFlow<VideoEngine>(exo)
    val engine: StateFlow<VideoEngine> = _engine
    private val player: VideoEngine get() = _engine.value

    // null if libmpv couldn't be loaded.
    private fun mpv(): MpvEngine? = mpvEngine ?: try {
        MpvEngine(getApplication()).also {
            it.addListener(listenerFor(it))
            it.setSubtitleStyle(settings.subtitleStyle.value)
            mpvEngine = it
        }
    } catch (e: Throwable) {
        android.util.Log.e("Vessel", "mpv isn't available", e)
        null
    }

    private val _current = MutableStateFlow<Video?>(null)
    val current: StateFlow<Video?> = _current

    private val playlist = MutableStateFlow<PlayList?>(null)

    val queue: StateFlow<Queue?> = combine(_current, playlist, index) { video, list, index ->
        when {
            video == null -> null
            list != null && list.videos.any { it.key == video.key } ->
                Queue(list.name, list.videos, list.videos.indexOfFirst { it.key == video.key }, playlist = true, shuffled = list.shuffled)
            else -> index.byFolder[video.folder]?.let { folder ->
                Queue(folder.name, folder.videos, folder.videos.indexOfFirst { it.key == video.key })
            }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // "More videos": a few from other folders, picked when the video opens.
    private val _suggestions = MutableStateFlow<List<Video>>(emptyList())
    val suggestions: StateFlow<List<Video>> = _suggestions

    // Seconds until the next video autoplays; 0 when no countdown is running.
    private val _autoplayCountdown = MutableStateFlow(0)
    val autoplayCountdown: StateFlow<Int> = _autoplayCountdown
    private var autoplayJob: Job? = null

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    // A brief note over the video when the engine changes by itself ("Playing with mpv").
    // A newer Vessel that's out ("1.7.0"), for the notice that offers it; null when there
    // isn't one, or the notice has been closed.
    private val _newerVersion = MutableStateFlow<String?>(null)
    val newerVersion: StateFlow<String?> = _newerVersion

    private val _notice = MutableStateFlow("")
    val notice: StateFlow<String> = _notice

    fun clearNotice() {
        _notice.value = ""
    }

    private var saveJob: Job? = null

    // Events from an engine that isn't the one playing (ExoPlayer after mpv took over) are ignored.
    private fun listenerFor(source: VideoEngine) = object : EngineListener {
        override fun onChanged() {
            if (source !== player || switching) return
            applyTrackChoices()
            followSubtitleTrack()
        }

        override fun onEnded() {
            if (source !== player) return
            saveProgress()
            if (settings.autoplay.value && queue.value?.next != null && !player.repeat) startAutoplay()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // Mid-switch, the engine still holds the previous video while _current is already
            // the next one: saving now would file one's position under the other.
            if (source !== player || switching) return
            saveJob?.cancel()
            if (isPlaying) {
                playingSince = android.os.SystemClock.elapsedRealtime()
                cancelAutoplay()   // e.g. seeked back from the end
                // Saved now and then, in case the app is closed unexpectedly.
                saveJob = viewModelScope.launch {
                    while (isActive) {
                        delay(5000)
                        saveProgress()
                    }
                }
            } else {
                saveProgress()
                playingSince = 0
            }
        }

        override fun onError(error: EngineError) {
            if (source === player) onEngineError(error)
        }
    }

    // The media session (notification, lock screen, headset buttons, the PiP window's play
    // button): PlaybackService holds it; connecting to it here starts the service.
    private val sessionPlayer = SessionPlayer(this)
    private val sessionController: ListenableFuture<MediaController>

    init {
        exo.addListener(listenerFor(exo))
        Thumbnails.fallback = thumbnailer::thumbnail
        PlaybackService.player = sessionPlayer
        sessionController = MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync()
        if (settings.checkForUpdates.value) viewModelScope.launch { _newerVersion.value = UpdateCheck.newerVersion() }
        // mpv draws subtitles itself (ExoPlayer's are drawn by VideoSurface).
        viewModelScope.launch { settings.subtitleStyle.collect { style -> mpvEngine?.setSubtitleStyle(style) } }
    }

    fun dismissUpdate() {
        _newerVersion.value = null
    }

    // Whether leaving the app now should shrink the video into a picture-in-picture window.
    val wantsPictureInPicture: Boolean
        get() = settings.pictureInPicture.value && _current.value != null && player.isPlaying

    // ExoPlayer couldn't play it (or only without picture or sound): mpv takes over from the
    // same place, and plays this video from now on. When mpv can't either, say why.
    private fun onEngineError(error: EngineError) {
        val video = _current.value ?: return
        val mpv = if (error.tryOtherEngine && player === exo) mpv() else null
        if (mpv != null) {
            val at = exo.positionMs.takeIf { it > 0 } ?: (history.resumePosition(video.key) * 1000).toLong()
            settings.setUsesMpv(video.key, true)
            switchTo(mpv, video, at)
            _notice.value = "Playing with mpv"
        } else {
            _error.value = error.message
        }
    }

    private var switching = false

    private fun switchTo(engine: VideoEngine, video: Video, startMs: Long) {
        saveJob?.cancel()
        // The time played so far is counted now: the engine's "stopped playing" while
        // switching is ignored, and the next video may never start (it can fail to open).
        countWatchTime()
        playingSince = 0
        switching = true
        try {
            if (player !== engine) {
                val old = player
                _engine.value = engine
                old.stop()
            }
            _error.value = null
            // open() starts the engine on the file's own timing; the track's is set once its
            // tracks are known (followSubtitleTrack).
            _subtitleTiming.value = SubtitleTimingState()
            // With the subtitles added to it before, and the one that was on.
            val saved = savedSubtitles.get(video.key)
            val subtitles = saved.present.map(::external)
            engine.open(video.uri, startMs, subtitles, subtitles.firstOrNull { it.file.path == saved.chosen })
            // The audio track and subtitle picked last time, once its tracks are known.
            pendingChoice = choices.get(video.key)
            engine.setPicture(_picture.value)
            engine.setVolumeBoost(_volumeBoost.value)
        } finally {
            switching = false
        }
    }

    // mpv for videos that needed it before, for formats ExoPlayer can't read at all, or for
    // everything if chosen in Settings.
    private fun engineFor(video: Video): VideoEngine {
        val extension = video.fileName.substringAfterLast('.', "").lowercase()
        val wantsMpv = settings.alwaysMpv.value || settings.usesMpv(video.key) || extension in MPV_ONLY
        return (if (wantsMpv) mpv() else null) ?: exo
    }

    fun open(video: Video) {
        if (video.key == _current.value?.key) {
            if (player.phase == Phase.Idle) player.play()
            return
        }
        // A video from outside the playlist (a suggestion) leaves it.
        if (playlist.value?.videos?.none { it.key == video.key } == true)
            playlist.value = null
        saveProgress()
        cancelAutoplay()
        _notice.value = ""
        _foundSubtitles.value = null
        _current.value = video
        // Each video starts with its own picture (as on the desktop).
        _picture.value = Picture()
        stats.notePlay(video.key)
        switchTo(engineFor(video), video, (history.resumePosition(video.key) * 1000).toLong())
        lookForSubtitleFiles(video)
        _suggestions.value = index.value.videos.filter { it.folder != video.folder || video.folder.isEmpty() }
            .filter { it.key != video.key }
            .shuffled()
            .take(SUGGESTIONS)
    }

    // Play all and Shuffle: from the first video in the list, or (a playlist's video picked)
    // from `start` on.
    fun playAll(name: String, videos: List<Video>, shuffle: Boolean, start: Video? = null) {
        if (videos.isEmpty()) return
        val list = if (shuffle) videos.shuffled() else videos
        playlist.value = PlayList(name, list, shuffle)
        saveProgress()          // open() can't: _current is cleared first
        _current.value = null   // so the first video opens even if it's the one playing
        open(start?.takeIf { s -> list.any { it.key == s.key } } ?: list.first())
    }

    // A file another app asked us to play ("Open with"). One of the phone's own videos is
    // matched to the library (for its folder and history) once the library has loaded.
    fun openUri(uri: Uri) {
        viewModelScope.launch {
            if (uri.authority == android.provider.MediaStore.AUTHORITY)
                withTimeoutOrNull(5000) { library.loaded.first { it } }
            open(library.describe(uri))
        }
    }

    fun playNext() {
        queue.value?.next?.let { open(it) }
    }

    fun playPrevious() {
        queue.value?.previous?.let { open(it) }
    }

    fun togglePlay() {
        when {
            player.phase == Phase.Ended -> {
                cancelAutoplay()
                player.seekTo(0)
                player.play()
            }
            player.isPlaying -> player.pause()
            else -> player.play()
        }
    }

    fun seekTo(ms: Long) = player.seekTo(ms)

    fun setScrubbing(on: Boolean) = player.setScrubbing(on)

    fun setSpeed(speed: Float) = player.setSpeed(speed)

    fun setRepeat(on: Boolean) = player.setRepeat(on)

    fun selectTrack(type: TrackType, track: TrackInfo?) {
        val tracks = player.tracks
        player.selectTrack(type, track)
        pendingChoice = null
        val video = _current.value ?: return
        // Remembered for next time, as on the desktop: one of the subtitle files added to it
        // (SavedSubtitles), or one built into the video, or off (TrackChoices); the audio track.
        if (type == TrackType.Subtitle) {
            savedSubtitles.choose(video.key, track?.file)
            val choice = when {
                track == null -> "off"
                track.file != null -> null
                else -> trackKeyOf(track)
            }
            choices.update(video.key) { it.copy(subtitle = choice) }
        } else if (track != null) {
            choices.update(video.key) { it.copy(audio = trackKeyOf(track)) }
        }
    }

    // --- Remembered tracks ----------------------------------------------------
    // What was picked last time for the video being opened, until its tracks are known.
    private var pendingChoice: TrackChoice? = null

    // A track as TrackChoices and SubtitleTiming know it: a subtitle file by its path, else
    // by its place among the video's own tracks of its type ("#1"), which both engines count
    // the same way (TrackInfo.place).
    private fun trackKeyOf(track: TrackInfo): String = track.file ?: "#${track.place}"

    private fun trackFor(key: String, type: TrackType, tracks: List<TrackInfo>): TrackInfo? =
        tracks.filter { it.type == type }.firstOrNull { trackKeyOf(it) == key }

    private fun applyTrackChoices() {
        val choice = pendingChoice ?: return
        val video = _current.value ?: return
        val tracks = player.tracks
        if (tracks.isEmpty()) return
        pendingChoice = null
        choice.audio?.let { key ->
            trackFor(key, TrackType.Audio, tracks)?.takeIf { !it.selected }?.let { player.selectTrack(TrackType.Audio, it) }
        }
        // A subtitle file turned on (SavedSubtitles) was already asked for in open().
        if (savedSubtitles.get(video.key).chosen != null) return
        when (val key = choice.subtitle) {
            null -> {}
            "off" -> if (tracks.any { it.type == TrackType.Subtitle && it.selected }) player.selectTrack(TrackType.Subtitle, null)
            else -> trackFor(key, TrackType.Subtitle, tracks)?.takeIf { !it.selected }?.let { player.selectTrack(TrackType.Subtitle, it) }
        }
    }

    // --- Picture and sound (the player's More menu) ------------------------------
    private val _picture = MutableStateFlow(Picture())
    val picture: StateFlow<Picture> = _picture

    fun setPicture(picture: Picture) {
        _picture.value = picture
        player.setPicture(picture)
    }

    // Louder than the phone's maximum, from the volume keys (see MainActivity): 100 to 200
    // percent. For this run of the app only, so it can't surprise anyone the next time.
    private val _volumeBoost = MutableStateFlow(100)
    val volumeBoost: StateFlow<Int> = _volumeBoost

    fun setVolumeBoost(percent: Int) {
        val p = percent.coerceIn(100, 200)
        _volumeBoost.value = p
        player.setVolumeBoost(p)
    }

    // A volume key past the phone's maximum (see MainActivity): `step` more (or less), said
    // over the video.
    fun boostVolume(step: Int) {
        setVolumeBoost(_volumeBoost.value + step)
        _notice.value = if (_volumeBoost.value == 100) "Volume 100%" else "Volume boost ${_volumeBoost.value}%"
    }

    // --- Notes with Undo ------------------------------------------------------
    private val _toast = MutableStateFlow<Toast?>(null)
    val toast: StateFlow<Toast?> = _toast

    fun toast(message: String, undo: (() -> Unit)? = null) {
        _toast.value = Toast(message, undo)
    }

    fun dismissToast(toast: Toast) {
        _toast.update { if (it?.id == toast.id) null else it }
    }

    // --- History edits (with Undo) ----------------------------------------------
    fun removeFromHistory(video: Video) {
        val before = history.get(video.key) ?: return
        history.remove(video.key)
        toast("Removed from history") { history.restore(video.key, before) }
    }

    fun clearHistory() {
        val before = history.entries.value
        history.clear()
        toast("History cleared") { history.restoreAll(before) }
    }

    // --- Playlists --------------------------------------------------------------
    // A playlist's videos that are on the phone, in its order.
    fun videosOf(playlist: Playlist): List<Video> {
        val byKey = index.value.byKey
        return playlist.keys.mapNotNull { byKey[it] }
    }

    fun removeFromPlaylist(id: String, video: Video) {
        val playlist = playlists.get(id) ?: return
        val at = playlists.remove(id, video.key)
        if (at >= 0) toast("Removed from ${playlist.name}") { playlists.add(id, video.key, at) }
    }

    fun deletePlaylist(id: String) {
        val (deleted, at) = playlists.delete(id) ?: return
        toast("Deleted “${deleted.name}”") { playlists.restore(deleted, at) }
    }

    // --- Renaming, moving and deleting files --------------------------------------
    // Each returns what came of it; NeedsConsent goes to the user first (see FileActions in
    // the UI), and its retry does the rest.
    // The player lets go of the video just before the file is changed (it may be holding it
    // open), not before: if the user says no in Android's dialog, it plays on.
    suspend fun renameVideo(video: Video, name: String): FileOutcome =
        afterFileChange(video, files.rename(video, name) { releaseIfPlaying(video) },
                        video.folder + renamedFileName(video, name))

    suspend fun moveVideo(video: Video, relativePath: String): FileOutcome =
        afterFileChange(video, files.move(video, relativePath) { releaseIfPlaying(video) },
                        "${video.volume}:$relativePath${video.fileName}")

    suspend fun deleteVideo(video: Video): FileOutcome =
        whenDone(files.delete(video) { releaseIfPlaying(video) }) {
            forget(video.key)
            library.refresh()
        }

    private fun afterFileChange(video: Video, outcome: FileOutcome, newKey: String): FileOutcome =
        whenDone(outcome) {
            relocate(video.key, newKey)
            library.refresh()
        }

    // Runs `done` once the outcome is Done: now, or after the user agrees and the retry works.
    private fun whenDone(outcome: FileOutcome, done: () -> Unit): FileOutcome = when (outcome) {
        FileOutcome.Done -> { done(); outcome }
        is FileOutcome.NeedsConsent -> FileOutcome.NeedsConsent(outcome.request) { whenDone(outcome.retry(), done) }
        is FileOutcome.Failed -> outcome
    }

    // The player lets go of a file before it's changed (it may be holding it open).
    private fun releaseIfPlaying(video: Video) {
        if (_current.value?.key == video.key) close()
    }

    // A video was renamed or moved: everything kept for it follows it, as the desktop's relocate().
    private fun relocate(from: String, to: String) {
        if (from == to) return
        history.relocate(from, to)
        savedSubtitles.relocate(from, to)
        timing.relocate(from, to)
        choices.relocate(from, to)
        playlists.relocate(from, to)
        stats.relocate(from, to)
        settings.relocateMpv(from, to)
    }

    // A video was deleted: nothing kept for it is left behind for another file that gets the
    // same name and folder later.
    private fun forget(key: String) {
        history.remove(key)
        savedSubtitles.forget(key)
        timing.forget(key)
        choices.forget(key)
        playlists.forget(key)
        stats.forget(key)
        settings.setUsesMpv(key, false)
    }

    // Folders a video can be moved to: the library's on the same storage, and Movies.
    fun moveTargets(video: Video): List<String> =
        (index.value.folders.mapNotNull { f -> f.videos.firstOrNull()?.takeIf { it.volume == video.volume }?.relativePath } + "Movies/")
            .distinct().filter { it != video.relativePath }.sortedWith(compareBy(NaturalOrder) { it })

    // --- Subtitle files next to the video ("Subtitles found") ---------------------
    private val _foundSubtitles = MutableStateFlow<FoundSubtitlesOffer?>(null)
    val foundSubtitles: StateFlow<FoundSubtitlesOffer?> = _foundSubtitles
    private var findJob: Job? = null

    // Looks in the folders the user let Vessel read. The first time, it asks; after "Turn on"
    // the file is added each time (as one picked by hand), after "No thanks" it isn't offered.
    private fun lookForSubtitleFiles(video: Video) {
        findJob?.cancel()
        val trees = settings.subtitleFolders.value
        if (trees.isEmpty() || choices.get(video.key).found == "off") return
        findJob = viewModelScope.launch {
            val found = withContext(Dispatchers.IO) { subtitleFinder.find(video, trees) }
            if (found.isEmpty() || _current.value?.key != video.key) return@launch
            // Already added: nothing to ask.
            val saved = savedSubtitles.get(video.key).present
            val new = found.filterNot { f -> saved.any { it.title == f.name.substringBeforeLast('.') } }
            if (new.isEmpty()) return@launch
            if (choices.get(video.key).found == "on") addFoundSubtitle(video, new.first())
            else _foundSubtitles.value = FoundSubtitlesOffer(video.key, new)
        }
    }

    fun answerFoundSubtitles(turnOn: Boolean) {
        val offer = _foundSubtitles.value ?: return
        _foundSubtitles.value = null
        choices.update(offer.videoKey) { it.copy(found = if (turnOn) "on" else "off") }
        val video = _current.value?.takeIf { it.key == offer.videoKey } ?: return
        if (turnOn) addFoundSubtitle(video, offer.subtitles.first())
    }

    private fun addFoundSubtitle(video: Video, subtitle: FoundSubtitle) {
        if (_current.value?.key == video.key) addSubtitleFile(subtitle.uri) { toast(it) }
    }

    // --- Subtitles from outside the video --------------------------------------

    // --- Subtitle timing ---------------------------------------------------------
    // For subtitles out of step with the video: shown earlier or later, without changing any
    // file, and remembered for that video and track (as on the desktop).
    private val _subtitleTiming = MutableStateFlow(SubtitleTimingState())
    val subtitleTiming: StateFlow<SubtitleTimingState> = _subtitleTiming

    // The subtitle track on, as SubtitleTiming knows it (see trackKeyOf); "" if none is on.
    private fun timingTrackOf(tracks: List<TrackInfo>): String =
        tracks.firstOrNull { it.type == TrackType.Subtitle && it.selected }?.let(::trackKeyOf) ?: ""

    // Each track gets the timing fixed for it before, once it's on. While subtitles are off
    // (or the tracks are being reloaded, see ExoEngine.addSubtitle), the engine is left as it is.
    private fun followSubtitleTrack() {
        val video = _current.value ?: return
        val track = timingTrackOf(player.tracks)
        if (track == _subtitleTiming.value.track) return
        val delay = if (track.isEmpty()) player.subtitleDelayMs else timing.get(video.key, track)
        if (track.isNotEmpty()) player.setSubtitleDelay(delay)
        _subtitleTiming.value = SubtitleTimingState(track, delay)
    }

    // Shows the subtitles on `stepMs` later (negative: earlier).
    fun shiftSubtitles(stepMs: Long) {
        setSubtitleDelay(_subtitleTiming.value.delayMs + stepMs)
    }

    fun resetSubtitleTiming() = setSubtitleDelay(0)

    private fun setSubtitleDelay(ms: Long) {
        val video = _current.value ?: return
        val track = _subtitleTiming.value.track
        if (track.isEmpty()) return
        val delay = ms.coerceIn(-MAX_SUBTITLE_DELAY_MS, MAX_SUBTITLE_DELAY_MS)
        player.setSubtitleDelay(delay)
        timing.set(video.key, track, delay)
        _subtitleTiming.value = SubtitleTimingState(track, delay)
    }

    // --- Subtitles from outside the video --------------------------------------
    private val _subtitleSearch = MutableStateFlow(SubtitleSearchState())
    val subtitleSearch: StateFlow<SubtitleSearchState> = _subtitleSearch
    private var searchJob: Job? = null

    private fun external(saved: SavedSubtitle) = ExternalSubtitle(saved.file, saved.title, saved.language)

    // What to search for at first: the video's file name, which OpenSubtitles makes sense of
    // by itself ("House.S01E02.720p").
    fun subtitleQuery(video: Video): String = video.fileName.substringBeforeLast('.')

    // The language to search in: the one last used, else the phone's.
    fun subtitleLanguage(): String = settings.subtitleLanguage.value.ifEmpty { openSubtitles.systemLanguage() }

    // Looks for subtitles for the video playing. Replaces any search still going.
    fun searchSubtitles(query: String, language: String) {
        val video = _current.value ?: return
        settings.setSubtitleLanguage(language)
        searchJob?.cancel()
        _subtitleSearch.update { SubtitleSearchState(searching = true, downloading = it.downloading) }
        searchJob = viewModelScope.launch {
            val found = openSubtitles.search(video.uri, query, language)
            _subtitleSearch.update { it.copy(searching = false, searched = true, results = found.results, error = found.error) }
        }
    }

    // What the sheet that started a download wants done once it's on; dropped when that sheet
    // closes, so a download that finishes later can't close a sheet opened since.
    private var subtitleDone: (() -> Unit)? = null

    // The sheet was closed: the search stops (a download carries on, and its subtitle still
    // turns on).
    fun cancelSubtitleSearch() {
        subtitleDone = null
        searchJob?.cancel()
        _subtitleSearch.update { SubtitleSearchState(downloading = it.downloading) }
    }

    // Downloads a subtitle found by searchSubtitles(), keeps it for the video and turns it on;
    // one downloaded for this video before is just turned on again, without using up a
    // download. onDone is called once it's on. When the day's downloads are used up,
    // settings.subtitleLimitReset says until when.
    fun downloadSubtitle(result: SubtitleResult, onDone: () -> Unit) {
        val video = _current.value ?: return
        savedSubtitles.get(video.key).downloaded(result.fileId)?.let { saved ->
            savedSubtitles.add(video.key, saved)
            player.addSubtitle(external(saved))
            onDone()
            return
        }
        if (_subtitleSearch.value.downloading != 0) return
        subtitleDone = onDone
        _subtitleSearch.update { it.copy(downloading = result.fileId, error = "") }
        viewModelScope.launch {
            try {
                val download = openSubtitles.download(result.fileId)
                download.limitResetMs?.let(settings::setSubtitleLimitReset)
                val extension = download.fileName.substringAfterLast('.', "").lowercase()
                    .takeIf { it in SavedSubtitles.extensions } ?: "srt"
                val language = result.languageCode.ifEmpty { null }
                val file = withContext(Dispatchers.IO) {
                    savedSubtitles.write(video.fileName, language, extension, download.data)
                }
                val saved = SavedSubtitle(file.path, result.fileId, "Downloaded", language)
                savedSubtitles.add(video.key, saved)
                if (_current.value?.key == video.key) {
                    player.addSubtitle(external(saved))
                    subtitleDone?.invoke()
                }
            } catch (e: SubtitleLimitException) {
                settings.setSubtitleLimitReset(e.resetMs)
            } catch (e: SubtitleException) {
                _subtitleSearch.update { it.copy(error = e.message.orEmpty()) }
            } catch (e: java.io.IOException) {
                _subtitleSearch.update { it.copy(error = "Couldn't save the subtitle file.") }
            } finally {
                subtitleDone = null
                _subtitleSearch.update { it.copy(downloading = 0) }
            }
        }
    }

    // A subtitle file picked from the phone's files (one downloaded in the browser, say):
    // copied into Vessel's folder, kept for the video and turned on. onDone gets what to tell
    // the user.
    fun addSubtitleFile(uri: Uri, onDone: (String) -> Unit) {
        val video = _current.value ?: return
        viewModelScope.launch {
            val resolver = getApplication<Application>().contentResolver
            val outcome: Result<SavedSubtitle> = withContext(Dispatchers.IO) {
                try {
                    val name = resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { if (it.moveToFirst()) it.getString(0) else null }
                        ?: uri.lastPathSegment?.substringAfterLast('/') ?: "subtitle"
                    // Read up to one byte past the limit, so a bigger file (a video picked by
                    // mistake) is turned away without being read whole.
                    val data = resolver.openInputStream(uri)?.use { input ->
                        val buffer = java.io.ByteArrayOutputStream()
                        val chunk = ByteArray(64 * 1024)
                        while (buffer.size() <= MAX_SUBTITLE_BYTES) {
                            val n = input.read(chunk)
                            if (n < 0) break
                            buffer.write(chunk, 0, n)
                        }
                        buffer.toByteArray()
                    } ?: throw java.io.IOException("No stream for $uri")
                    if (data.size > MAX_SUBTITLE_BYTES)
                        return@withContext Result.failure(SubtitleException("This file is too big to be a subtitle file"))
                    // By its name if that says ("Movie.srt"); otherwise ("Movie.srt.txt", or no
                    // extension at all, as browsers sometimes save them) by what's in it.
                    val extension = name.substringAfterLast('.', "").lowercase().takeIf { it in SavedSubtitles.extensions }
                        ?: SavedSubtitles.sniffExtension(data)
                        ?: return@withContext Result.failure(
                            SubtitleException("Not a subtitle file Vessel can read (SRT, ASS, SSA, VTT, TTML)"))
                    val file = savedSubtitles.write(video.fileName, null, extension, data)
                    // Its name without the endings: "Movie.en.srt.txt" -> "Movie.en".
                    var title = name
                    while (title.substringAfterLast('.', "").lowercase().let { it in SavedSubtitles.extensions || it == "txt" })
                        title = title.substringBeforeLast('.')
                    Result.success(SavedSubtitle(file.path, 0, title.ifEmpty { "Subtitle" }, null))
                } catch (e: Exception) {
                    android.util.Log.w("Vessel", "Couldn't add the subtitle $uri", e)
                    Result.failure(SubtitleException("Couldn't add that file"))
                }
            }
            val saved = outcome.getOrElse {
                onDone(it.message.orEmpty())
                return@launch
            }
            savedSubtitles.add(video.key, saved)
            if (_current.value?.key == video.key) player.addSubtitle(external(saved))
            onDone("Subtitles: ${saved.title}")
        }
    }

    fun close() {
        saveProgress()
        playingSince = 0
        cancelAutoplay()
        findJob?.cancel()
        _foundSubtitles.value = null
        player.stop()
        playlist.value = null
        _current.value = null
        _error.value = null
        _notice.value = ""
    }

    private fun startAutoplay() {
        autoplayJob?.cancel()
        autoplayJob = viewModelScope.launch {
            for (seconds in 5 downTo 1) {
                _autoplayCountdown.value = seconds
                delay(1000)
            }
            _autoplayCountdown.value = 0
            playNext()
        }
    }

    fun cancelAutoplay() {
        autoplayJob?.cancel()
        autoplayJob = null
        _autoplayCountdown.value = 0
    }

    fun setAutoplay(on: Boolean) {
        settings.setAutoplay(on)
        if (!on) cancelAutoplay()
        else if (player.phase == Phase.Ended && queue.value?.next != null) startAutoplay()
    }

    // --- History ---------------------------------------------------------------
    fun saveProgress() {
        countWatchTime()
        val video = _current.value ?: return
        val duration = player.durationMs
        if (duration <= 0) return
        val position = if (player.phase == Phase.Ended) duration else player.positionMs
        val wasFinished = history.get(video.key)?.finished == true
        history.save(video.key, position / 1000.0, duration / 1000.0)
        // Played to the end (for the stats), once each time it gets there.
        if (!wasFinished && history.get(video.key)?.finished == true) stats.noteFinished(video.key)
    }

    // --- Watch stats --------------------------------------------------------------
    // When the video last started playing, or the time was last counted (elapsedRealtime);
    // 0 while nothing plays.
    private var playingSince = 0L

    // The time since then goes to the stats: the time that passed, whatever the speed.
    private fun countWatchTime() {
        if (playingSince <= 0) return
        val now = android.os.SystemClock.elapsedRealtime()
        stats.addWatchTime((now - playingSince) / 1000.0)
        playingSince = now
    }

    fun setWatched(video: Video, watched: Boolean) {
        val before = history.get(video.key)
        history.setWatched(video.key, watched, video.durationSeconds)
        toast(if (watched) "Marked as watched" else "Marked as unwatched") { history.restore(video.key, before) }
    }

    // The app went into the background (or the screen off): the sound carries on if that's
    // allowed, else it pauses. In a picture-in-picture window it's still showing: nothing to do.
    fun onAppStopped(inPictureInPicture: Boolean) {
        if (!inPictureInPicture && !settings.backgroundPlay.value) player.pause()
        saveProgress()
        history.flush()
        stats.flush()
    }

    // The picture-in-picture window was closed: as on YouTube, playback stops.
    fun onPictureInPictureClosed() {
        player.pause()
        saveProgress()
        history.flush()
        stats.flush()
    }

    override fun onCleared() {
        // viewModelScope is already cancelled here, so the history is written right away.
        saveProgress()
        history.writeNow()
        stats.writeNow()
        library.stop()
        MediaController.releaseFuture(sessionController)
        PlaybackService.player = null
        sessionPlayer.release()
        exo.release()
        mpvEngine?.release()
        Thumbnails.fallback = null
        thumbnailer.release()
    }

    private companion object {
        const val SHELF_SIZE = 20
        const val QUICK_PICKS = 16
        const val SUGGESTIONS = 12
        // Further out than this (10 minutes), it's not the timing that's wrong.
        const val MAX_SUBTITLE_DELAY_MS = 600_000L
        // Formats ExoPlayer has no reader for: straight to mpv.
        val MPV_ONLY = setOf("wmv", "asf", "rm", "rmvb", "ogm", "divx")
    }
}

