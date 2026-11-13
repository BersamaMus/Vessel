@file:androidx.annotation.OptIn(UnstableApi::class)

package com.better_video_player.engine

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
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
    // The video playing, the subtitle files added to it, and the one of those to turn on once
    // its track shows up (its path).
    private var uri: Uri? = null
    private var externals: List<ExternalSubtitle> = emptyList()
    private var pendingSubtitle: String? = null
    // See addSubtitle().
    private var pendingAudio: Format? = null
    private var reloadingAtEnd = false

    init {
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                listeners.each { it.onChanged() }
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) reloadingAtEnd = false
                if (state == Player.STATE_ENDED) {
                    if (reloadingAtEnd) reloadingAtEnd = false
                    else listeners.each { it.onEnded() }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                listeners.each { it.onIsPlayingChanged(isPlaying) }
            }

            override fun onTracksChanged(tracks: Tracks) {
                groups = tracks.groups
                selectPendingSubtitle()
                selectPendingAudio()
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
                    val format = group.getTrackFormat(i)
                    result += TrackInfo(type, "$g:$i", trackLabel(format, number), group.isTrackSelected(i), externalPath(format))
                }
            }
            return result
        }

    override fun addListener(listener: EngineListener) = listeners.add(listener)
    override fun removeListener(listener: EngineListener) = listeners.remove(listener)

    override fun open(uri: Uri, startMs: Long, subtitles: List<ExternalSubtitle>, selected: ExternalSubtitle?) {
        reportedUnsupported = false
        groups = emptyList()
        this.uri = uri
        externals = subtitles
        pendingSubtitle = selected?.file?.absolutePath
        pendingAudio = null
        reloadingAtEnd = false
        player.repeatMode = Player.REPEAT_MODE_OFF
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverrides()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .build()
        player.setMediaItem(mediaItem(uri), startMs)
        player.prepare()
        player.play()
    }

    // ExoPlayer only takes subtitle files as part of the media item, so a new one means setting
    // the video again with it, from where it is. One it already has is just turned on.
    override fun addSubtitle(subtitle: ExternalSubtitle) {
        val uri = uri ?: return
        val path = subtitle.file.absolutePath
        pendingSubtitle = path
        // (Tracks not known yet: it's turned on when they are.)
        if (externals.any { it.file.absolutePath == path } && (groups.isEmpty() || selectPendingSubtitle())) {
            listeners.each { it.onChanged() }
            return
        }
        externals = externals.filterNot { it.file.absolutePath == path } + subtitle
        // Setting the video again makes new track groups, which the audio track the user picked
        // no longer matches: it's picked again by its format once they're there.
        pendingAudio = if (player.trackSelectionParameters.overrides.keys.any { it.type == C.TRACK_TYPE_AUDIO })
            groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }
                ?.let { g -> (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let(g::getTrackFormat) }
        else null
        // At the end, it reloads and ends again: that's not the video ending a second time.
        reloadingAtEnd = player.playbackState == Player.STATE_ENDED
        val playing = player.playWhenReady
        groups = emptyList()
        player.setMediaItem(mediaItem(uri), player.currentPosition)
        player.prepare()
        player.playWhenReady = playing
    }

    private fun mediaItem(uri: Uri): MediaItem = MediaItem.Builder()
        .setUri(uri)
        .setSubtitleConfigurations(externals.map { s ->
            MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(s.file))
                .setId(EXTERNAL_ID + s.file.absolutePath)
                .setMimeType(subtitleMimeType(s.file.extension))
                .setLanguage(s.language)
                .setLabel(s.title)
                .build()
        })
        .build()

    // The added subtitle file a track was made from, by the id given to it in mediaItem()
    // (ExoPlayer may put something in front of it).
    private fun externalPath(format: Format): String? =
        format.id?.substringAfter(EXTERNAL_ID, "")?.takeIf { it.isNotEmpty() }

    // Whether the subtitle waiting to be turned on was found (and turned on).
    private fun selectPendingSubtitle(): Boolean {
        val path = pendingSubtitle ?: return false
        groups.forEach { group ->
            if (group.type != C.TRACK_TYPE_TEXT) return@forEach
            for (i in 0 until group.length) {
                if (externalPath(group.getTrackFormat(i)) != path) continue
                pendingSubtitle = null
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i))
                    .build()
                return true
            }
        }
        return false
    }

    // The audio track picked before addSubtitle() set the video again, picked again in the new
    // track groups: the one with the same format.
    private fun selectPendingAudio() {
        val wanted = pendingAudio ?: return
        if (groups.isEmpty()) return
        pendingAudio = null
        groups.forEach { group ->
            if (group.type != C.TRACK_TYPE_AUDIO) return@forEach
            for (i in 0 until group.length) {
                val f = group.getTrackFormat(i)
                if (f.id != wanted.id || f.language != wanted.language || f.sampleMimeType != wanted.sampleMimeType ||
                    f.channelCount != wanted.channelCount || f.label != wanted.label) continue
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i))
                    .build()
                return
            }
        }
    }

    override fun play() {
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
        player.play()
    }

    override fun pause() = player.pause()

    override fun stop() {
        uri = null
        externals = emptyList()
        pendingSubtitle = null
        pendingAudio = null
        reloadingAtEnd = false
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

private const val EXTERNAL_ID = "vessel-subtitle|"

internal fun subtitleMimeType(extension: String): String = when (extension.lowercase()) {
    "ass", "ssa" -> MimeTypes.TEXT_SSA
    "vtt" -> MimeTypes.TEXT_VTT
    "ttml", "dfxp", "xml" -> MimeTypes.APPLICATION_TTML
    else -> MimeTypes.APPLICATION_SUBRIP
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
