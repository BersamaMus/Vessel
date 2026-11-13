@file:androidx.annotation.OptIn(UnstableApi::class)

package com.better_video_player.engine

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import com.better_video_player.data.codecName
import java.util.Locale

// Media3's ExoPlayer: hardware decoding through Android's own codecs. The default engine.
class ExoEngine(context: Context) : VideoEngine {
    override val name = "ExoPlayer"

    val player: ExoPlayer = ExoPlayer.Builder(
        context,
        DefaultRenderersFactory(context)
            // If a decoder fails to start (it happens with some hardware ones), the next one is tried.
            .setEnableDecoderFallback(true)
            // The emulator's "goldfish" decoders fail mid-stream; its software ones work. (Emulator only.)
            .setMediaCodecSelector { mime, secure, tunneling ->
                MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling).filterNot { it.name.startsWith("c2.goldfish.") }
            },
    )
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
            /* handleAudioFocus = */ true,
        )
        .setHandleAudioBecomingNoisy(true)   // headphones unplugged: pause
        .build()

    private val listeners = Listeners()
    // The track groups behind the ids handed out in `tracks` ("group:track").
    private var groups: List<Tracks.Group> = emptyList()
    // A file whose tracks this engine can't decode is reported once.
    private var reportedUnsupported = false

    init {
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                listeners.each { it.onChanged() }
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) listeners.each { it.onEnded() }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                listeners.each { it.onIsPlayingChanged(isPlaying) }
            }

            override fun onTracksChanged(tracks: Tracks) {
                groups = tracks.groups
                // A picture or sound this phone can't decode would play black or silent: say so,
                // so mpv can take over.
                if (reportedUnsupported || tracks.isEmpty) return
                for ((type, what) in listOf(C.TRACK_TYPE_VIDEO to "video", C.TRACK_TYPE_AUDIO to "audio")) {
                    if (tracks.containsType(type) && !tracks.isTypeSupported(type, /* allowExceedsCapabilities = */ true)) {
                        reportedUnsupported = true
                        listeners.each { it.onError(EngineError("This phone can't decode this video's $what.", tryOtherEngine = true)) }
                        return
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                listeners.each { it.onError(describe(error)) }
            }
        })
    }

    private fun describe(error: PlaybackException): EngineError = when (error.errorCode) {
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED ->
            EngineError("This phone can't decode this video's format.", tryOtherEngine = true)
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ->
            EngineError("This file can't be read: it may be damaged, or in a format Vessel doesn't play.", tryOtherEngine = true)
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> EngineError("This video isn't there any more.", tryOtherEngine = false)
        // Reading failed (a drive pulled out, the stream cut off): not the format's fault.
        in 2000..2999 -> EngineError("This video couldn't be read (${error.errorCodeName}).", tryOtherEngine = false)
        else -> EngineError("This video can't be played (${error.errorCodeName}).", tryOtherEngine = true)
    }

    override val isPlaying get() = player.isPlaying
    override val playWhenReady get() = player.playWhenReady && player.playbackState != Player.STATE_ENDED
    override val phase get() = when (player.playbackState) {
        Player.STATE_BUFFERING -> Phase.Buffering
        Player.STATE_READY -> Phase.Ready
        Player.STATE_ENDED -> Phase.Ended
        else -> Phase.Idle
    }
    override val positionMs get() = player.currentPosition
    override val durationMs get() = player.duration.let { if (it == C.TIME_UNSET) 0 else it }
    override val bufferedMs get() = player.bufferedPosition
    override val speed get() = player.playbackParameters.speed
    override val repeat get() = player.repeatMode != Player.REPEAT_MODE_OFF

    // A phone's upright video is stored sideways, tagged to turn.
    private val turned get() = (player.videoFormat?.rotationDegrees ?: 0) % 180 != 0
    override val videoWidth get() = player.videoFormat?.let { if (turned) it.height else it.width } ?: 0
    override val videoHeight get() = player.videoFormat?.let { if (turned) it.width else it.height } ?: 0
    override val videoCodec get() = codecName(player.videoFormat?.sampleMimeType)
    override val frameRate get() = player.videoFormat?.frameRate?.takeIf { it > 0 } ?: 0f

    override val tracks: List<TrackInfo>
        get() {
            val result = mutableListOf<TrackInfo>()
            var audio = 0
            var text = 0
            groups.forEachIndexed { g, group ->
                val type = when (group.type) {
                    C.TRACK_TYPE_AUDIO -> TrackType.Audio
                    C.TRACK_TYPE_TEXT -> TrackType.Subtitle
                    else -> return@forEachIndexed
                }
                for (i in 0 until group.length) {
                    if (!group.isTrackSupported(i)) continue
                    val number = if (type == TrackType.Audio) ++audio else ++text
                    result += TrackInfo(type, "$g:$i", trackLabel(group.getTrackFormat(i), number), group.isTrackSelected(i))
                }
            }
            return result
        }

    override fun addListener(listener: EngineListener) = listeners.add(listener)
    override fun removeListener(listener: EngineListener) = listeners.remove(listener)

    override fun open(uri: Uri, startMs: Long) {
        reportedUnsupported = false
        groups = emptyList()
        player.repeatMode = Player.REPEAT_MODE_OFF
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverrides()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .build()
        player.setMediaItem(MediaItem.fromUri(uri), startMs)
        player.prepare()
        player.play()
    }

    override fun play() {
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
        player.play()
    }

    override fun pause() = player.pause()

    override fun stop() {
        player.stop()
        player.clearMediaItems()
    }

    override fun seekTo(ms: Long) = player.seekTo(ms)
    override fun setScrubbing(on: Boolean) = player.setScrubbingModeEnabled(on)
    override fun setSpeed(speed: Float) = player.setPlaybackSpeed(speed)
    override fun setRepeat(on: Boolean) {
        player.repeatMode = if (on) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }

    override fun selectTrack(type: TrackType, track: TrackInfo?) {
        val trackType = if (type == TrackType.Audio) C.TRACK_TYPE_AUDIO else C.TRACK_TYPE_TEXT
        val builder = player.trackSelectionParameters.buildUpon()
        if (track == null) {
            builder.setTrackTypeDisabled(trackType, true)
        } else {
            val (g, i) = track.id.split(':').map { it.toInt() }
            val group = groups.getOrNull(g) ?: return
            builder.setTrackTypeDisabled(trackType, false)
                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i))
        }
        player.trackSelectionParameters = builder.build()
    }

    override fun release() = player.release()
}

// "English  ·  5.1"
internal fun trackLabel(format: Format, number: Int): String {
    val language = languageName(format.language)
    return listOf(format.label ?: language ?: "Track $number", if (format.label != null) language ?: "" else "",
                  channelsText(format.channelCount))
        .filter { it.isNotEmpty() }.distinct().joinToString("  ·  ")
}

internal fun languageName(code: String?): String? =
    code?.takeIf { it.isNotEmpty() && it != C.LANGUAGE_UNDETERMINED && it != "und" }
        ?.let { Locale.forLanguageTag(it).getDisplayLanguage(Locale.getDefault()).ifEmpty { it } }

internal fun channelsText(channels: Int): String = when (channels) {
    Format.NO_VALUE, 0 -> ""
    1 -> "Mono"
    2 -> "Stereo"
    6 -> "5.1"
    8 -> "7.1"
    else -> "$channels ch"
}
