package com.better_video_player.playback

import android.graphics.Bitmap
import android.os.Looper
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.better_video_player.VesselViewModel
import com.better_video_player.data.Video
import com.better_video_player.engine.EngineListener
import com.better_video_player.engine.Phase
import com.better_video_player.engine.VideoEngine
import com.better_video_player.ui.Thumbnails
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import androidx.lifecycle.viewModelScope
import java.io.ByteArrayOutputStream

// Vessel's player as Android's media system sees it: the playback notification, the lock screen,
// headset buttons, and the play/pause button in the picture-in-picture window all go through
// this. It speaks for whichever engine is playing (ExoPlayer or mpv), and offers the previous
// and next videos of the folder (or Play all list) as its playlist.
@UnstableApi
class SessionPlayer(private val vm: VesselViewModel) : SimpleBasePlayer(Looper.getMainLooper()) {

    private var engine: VideoEngine = vm.engine.value
    private val listener = object : EngineListener {
        override fun onChanged() = invalidateState()
    }
    // The cover art: the current video's thumbnail, as JPEG.
    private var artworkKey: String? = null
    private var artwork: ByteArray? = null

    init {
        engine.addListener(listener)
        vm.viewModelScope.launch {
            vm.engine.collect { e ->
                if (e !== engine) {
                    engine.removeListener(listener)
                    engine = e
                    engine.addListener(listener)
                }
                invalidateState()
            }
        }
        vm.viewModelScope.launch {
            combine(vm.current, vm.queue) { _, _ -> }.collect { invalidateState() }
        }
    }

    // The current video with the ones either side of it, so the notification can offer previous
    // and next.
    private data class Window(val items: List<Video>, val index: Int)

    private fun window(): Window? {
        val video = vm.current.value ?: return null
        val queue = vm.queue.value
        if (queue == null || queue.index < 0) return Window(listOf(video), 0)
        val items = listOfNotNull(queue.previous, video, queue.next)
        return Window(items, if (queue.previous != null) 1 else 0)
    }

    override fun getState(): State {
        val window = window()
        if (window == null) {
            return State.Builder()
                .setAvailableCommands(Player.Commands.EMPTY)
                .setPlaybackState(Player.STATE_IDLE)
                .build()
        }
        val current = window.items[window.index]
        val e = engine
        val commands = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_STOP,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Player.COMMAND_SEEK_BACK,
                Player.COMMAND_SEEK_FORWARD,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_TIMELINE,
                Player.COMMAND_GET_METADATA,
            )
            .apply {
                if (window.index > 0) add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                if (window.index < window.items.size - 1) addAll(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            }
            .build()
        val playlist = window.items.map { v ->
            MediaItemData.Builder(v.key)
                .setMediaItem(
                    MediaItem.Builder()
                        .setMediaId(v.key)
                        .setMediaMetadata(metadataFor(v, v === current))
                        .build()
                )
                .setDurationUs(if (v === current && e.durationMs > 0) e.durationMs * 1000 else v.durationMs.takeIf { it > 0 }?.times(1000) ?: androidx.media3.common.C.TIME_UNSET)
                .setIsSeekable(true)
                .build()
        }
        return State.Builder()
            .setAvailableCommands(commands)
            .setPlaylist(playlist)
            .setCurrentMediaItemIndex(window.index)
            .setPlayWhenReady(e.playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(when (e.phase) {
                Phase.Idle -> Player.STATE_IDLE
                Phase.Buffering -> Player.STATE_BUFFERING
                Phase.Ready -> Player.STATE_READY
                Phase.Ended -> Player.STATE_ENDED
            })
            .setContentPositionMs { engine.positionMs }
            .setPlaybackParameters(PlaybackParameters(e.speed))
            .setRepeatMode(if (e.repeat) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF)
            .setSeekBackIncrementMs(SKIP_MS)
            .setSeekForwardIncrementMs(SKIP_MS)
            .build()
    }

    private fun metadataFor(video: Video, current: Boolean): MediaMetadata {
        val b = MediaMetadata.Builder()
            .setTitle(video.title)
            .setDisplayTitle(video.title)
            .setArtist(video.folderName)
            .setMediaType(MediaMetadata.MEDIA_TYPE_VIDEO)
        if (current) artworkOf(video)?.let { b.setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER) }
        return b.build()
    }

    // The thumbnail (if it's been loaded) as the notification's picture.
    private fun artworkOf(video: Video): ByteArray? {
        if (artworkKey == video.key && artwork != null) return artwork
        val image = Thumbnails.cached(video) ?: return null
        val out = ByteArrayOutputStream()
        image.asAndroidBitmap().compress(Bitmap.CompressFormat.JPEG, 85, out)
        artworkKey = video.key
        artwork = out.toByteArray()
        return artwork
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) {
            if (engine.phase == Phase.Ended) vm.togglePlay() else engine.play()
        } else {
            engine.pause()
        }
        return Futures.immediateVoidFuture()
    }

    // Seeking within the video, or to the previous or next one.
    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val window = window() ?: return Futures.immediateVoidFuture()
        when {
            mediaItemIndex == window.index || mediaItemIndex < 0 -> engine.seekTo(positionMs.coerceAtLeast(0))
            mediaItemIndex < window.index -> vm.playPrevious()
            else -> vm.playNext()
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        engine.pause()
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        engine.removeListener(listener)
        return Futures.immediateVoidFuture()
    }


    private companion object {
        const val SKIP_MS = 10_000L
    }
}
