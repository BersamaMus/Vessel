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
import com.better_video_player.data.Seen
import com.better_video_player.data.Video
import com.better_video_player.data.WatchHistory
import com.better_video_player.engine.EngineError
import com.better_video_player.engine.EngineListener
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
)

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

private data class Playlist(val name: String, val videos: List<Video>, val shuffled: Boolean)

class VesselViewModel(app: Application) : AndroidViewModel(app) {

    val library = MediaLibrary(app, viewModelScope)
    val history = WatchHistory(File(app.filesDir, "history.json"), viewModelScope)
    val settings = AppSettings(app)

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
            LibraryIndex(videos, folders, folders.associateBy { it.key })
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
            mpvEngine = it
        }
    } catch (e: Throwable) {
        android.util.Log.e("Vessel", "mpv isn't available", e)
        null
    }

    private val _current = MutableStateFlow<Video?>(null)
    val current: StateFlow<Video?> = _current

    private val playlist = MutableStateFlow<Playlist?>(null)

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
    private val _notice = MutableStateFlow("")
    val notice: StateFlow<String> = _notice

    fun clearNotice() {
        _notice.value = ""
    }

    private var saveJob: Job? = null

    // Events from an engine that isn't the one playing (ExoPlayer after mpv took over) are ignored.
    private fun listenerFor(source: VideoEngine) = object : EngineListener {
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
        switching = true
        try {
            if (player !== engine) {
                val old = player
                _engine.value = engine
                old.stop()
            }
            _error.value = null
            engine.open(video.uri, startMs)
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
        _current.value = video
        switchTo(engineFor(video), video, (history.resumePosition(video.key) * 1000).toLong())
        _suggestions.value = index.value.videos.filter { it.folder != video.folder || video.folder.isEmpty() }
            .filter { it.key != video.key }
            .shuffled()
            .take(SUGGESTIONS)
    }

    // Play all and Shuffle: from the first video in the list.
    fun playAll(name: String, videos: List<Video>, shuffle: Boolean) {
        if (videos.isEmpty()) return
        val list = if (shuffle) videos.shuffled() else videos
        playlist.value = Playlist(name, list, shuffle)
        saveProgress()          // open() can't: _current is cleared first
        _current.value = null   // so the first video opens even if it's the one playing
        open(list.first())
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

    fun selectTrack(type: TrackType, track: TrackInfo?) = player.selectTrack(type, track)

    fun close() {
        saveProgress()
        cancelAutoplay()
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
        val video = _current.value ?: return
        val duration = player.durationMs
        if (duration <= 0) return
        val position = if (player.phase == Phase.Ended) duration else player.positionMs
        history.save(video.key, position / 1000.0, duration / 1000.0)
    }

    fun setWatched(video: Video, watched: Boolean) {
        history.setWatched(video.key, watched, video.durationSeconds)
    }

    // The app went into the background (or the screen off): the sound carries on if that's
    // allowed, else it pauses. In a picture-in-picture window it's still showing: nothing to do.
    fun onAppStopped(inPictureInPicture: Boolean) {
        if (!inPictureInPicture && !settings.backgroundPlay.value) player.pause()
        saveProgress()
        history.flush()
    }

    // The picture-in-picture window was closed: as on YouTube, playback stops.
    fun onPictureInPictureClosed() {
        player.pause()
        saveProgress()
        history.flush()
    }

    override fun onCleared() {
        // viewModelScope is already cancelled here, so the history is written right away.
        saveProgress()
        history.writeNow()
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
        // Formats ExoPlayer has no reader for: straight to mpv.
        val MPV_ONLY = setOf("wmv", "asf", "rm", "rmvb", "ogm", "divx")
    }
}

