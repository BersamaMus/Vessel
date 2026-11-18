package com.better_video_player.engine

import android.net.Uri
import com.better_video_player.data.SubtitleStyle
import java.io.File

// What the player is doing.
enum class Phase { Idle, Buffering, Ready, Ended }

enum class TrackType { Audio, Subtitle }

// An audio or subtitle track the user can pick. `file`: for a subtitle added from a file
// (see ExternalSubtitle), that file's path. `place`: its place among the video's own tracks of
// its type, in the file's order (1 for the first), counting ones the engine can't play, so it's
// the same whichever engine plays it; 0 for a subtitle file.
data class TrackInfo(val type: TrackType, val id: String, val label: String, val selected: Boolean,
                     val file: String? = null, val place: Int = 0)

// A subtitle file added to the video (downloaded, or picked from the phone's files), shown in
// the track list as "<title>  ·  <language>".
data class ExternalSubtitle(val file: File, val title: String, val language: String?)

// The picture, as the player's More menu sets it (as on the desktop); each video starts
// without any of it. aspect: "" for the video's own, else "16:9", "4:3", "21:9"...; fill: crop
// the edges so there are no black bars; rotation: degrees, clockwise.
data class Picture(val aspect: String = "", val fill: Boolean = false, val rotation: Int = 0) {
    // The aspect as a number (width / height), or null for the video's own.
    val ratio: Float? get() = aspect.split(':').takeIf { it.size == 2 }?.let { (w, h) ->
        val r = (w.toFloatOrNull() ?: return null) / (h.toFloatOrNull() ?: return null)
        r.takeIf { it.isFinite() && it > 0 }
    }
    val isDefault: Boolean get() = this == Picture()
}

// Why an engine gave up on a file: whether another engine might do better.
data class EngineError(val message: String, val tryOtherEngine: Boolean)

// Told about everything the UI and the view model follow. Called on the main thread.
interface EngineListener {
    // Anything shown changed: playing, phase, tracks, speed, video size...
    fun onChanged() {}
    fun onEnded() {}
    fun onIsPlayingChanged(isPlaying: Boolean) {}
    fun onError(error: EngineError) {}
}

// A way of playing videos. There are two: ExoPlayer (Android's own decoders, the default) and
// mpv (FFmpeg, for what ExoPlayer can't play). The pages and controls only see this.
interface VideoEngine {
    val name: String

    val isPlaying: Boolean
    // Playing, or about to (loading or buffering, not paused): what the media session reports.
    val playWhenReady: Boolean
    val phase: Phase
    val positionMs: Long
    // 0 until known.
    val durationMs: Long
    val bufferedMs: Long
    val speed: Float
    val repeat: Boolean
    // The picture as shown (turned upright); 0 until known.
    val videoWidth: Int
    val videoHeight: Int
    // "H.264 (AVC)"; "" until known.
    val videoCodec: String
    // 0 until known.
    val frameRate: Float
    val tracks: List<TrackInfo>

    fun addListener(listener: EngineListener)
    fun removeListener(listener: EngineListener)

    // `subtitles` are added to the video's own tracks; `selected`, one of them, is turned on.
    fun open(uri: Uri, startMs: Long, subtitles: List<ExternalSubtitle> = emptyList(), selected: ExternalSubtitle? = null)
    // Adds a subtitle file to the video playing, and turns it on.
    fun addSubtitle(subtitle: ExternalSubtitle)
    fun play()
    fun pause()
    // Stops and lets go of the file.
    fun stop()
    fun seekTo(ms: Long)
    // While dragging the seek bar: quick, approximate seeks.
    fun setScrubbing(on: Boolean)
    fun setSpeed(speed: Float)
    fun setRepeat(on: Boolean)
    // null turns subtitles off.
    fun selectTrack(type: TrackType, track: TrackInfo?)
    // How much later the subtitles show than their file says (negative: earlier), in ms, for
    // subtitles out of step with the video. Doesn't change any file; open() puts it back to 0.
    val subtitleDelayMs: Long
    fun setSubtitleDelay(ms: Long)
    // How subtitles look. mpv draws them itself; ExoPlayer's are drawn by VideoSurface.
    fun setSubtitleStyle(style: SubtitleStyle) {}
    // The picture's shape, crop and turn. mpv does it itself; for ExoPlayer, VideoSurface does.
    fun setPicture(picture: Picture) {}
    // Louder than the phone's own maximum: 100 (as is) to 200 percent.
    fun setVolumeBoost(percent: Int)
    fun release()
}

// Listeners kept by both engines.
internal class Listeners {
    private val list = mutableListOf<EngineListener>()
    fun add(l: EngineListener) { if (l !in list) list += l }
    fun remove(l: EngineListener) { list -= l }
    fun each(block: (EngineListener) -> Unit) = list.toList().forEach(block)
}
