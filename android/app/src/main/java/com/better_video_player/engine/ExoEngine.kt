@file:androidx.annotation.OptIn(UnstableApi::class)

package com.better_video_player.engine

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererConfiguration
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.text.TextOutput
import com.better_video_player.data.codecName
import java.util.Locale
import java.util.TreeMap
import kotlin.math.roundToInt

// Media3's ExoPlayer: hardware decoding through Android's own codecs. The default engine.
class ExoEngine(private val context: Context) : VideoEngine {
    override val name = "ExoPlayer"

    // The subtitle delay in µs, and the video's length in µs (0 until known), read by
    // DelayedTextRenderer on ExoPlayer's own thread.
    @Volatile private var subtitleDelayUs = 0L
    @Volatile private var durationUs = 0L

    val player: ExoPlayer = ExoPlayer.Builder(
        context,
        object : DefaultRenderersFactory(context) {
            // ExoPlayer has no setting for subtitle timing: its subtitle renderer is handed a
            // clock that runs behind (or ahead of) the video's instead.
            override fun buildTextRenderers(context: Context, output: TextOutput, outputLooper: Looper,
                                            extensionRendererMode: Int, out: ArrayList<Renderer>) {
                val renderers = ArrayList<Renderer>()
                // (Media3 makes one subtitle renderer, so one memory is enough.)
                val memory = CueMemory(output)
                super.buildTextRenderers(context, memory, outputLooper, extensionRendererMode, renderers)
                val handler = Handler(outputLooper)
                renderers.mapTo(out) { DelayedTextRenderer(it, memory, handler, { subtitleDelayUs }, { durationUs }) }
            }
        }
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
                durationUs = player.duration.let { if (it == C.TIME_UNSET) 0 else it * 1000 }
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
    override val videoWidth get() = shownSize?.let { (w, h) -> if (turned) h else w } ?: 0
    override val videoHeight get() = shownSize?.let { (w, h) -> if (turned) w else h } ?: 0

    // The picture's size as shown, before turning: some videos are stored with pixels that
    // aren't square (a DVD's 720 × 480 is shown at 16:9, as 853 × 480), and the picture's
    // shape must follow how it's shown, not how it's stored. Made wider or taller as mpv's
    // dwidth / dheight are, so both engines report the same.
    private val shownSize: Pair<Int, Int>?
        get() {
            val f = player.videoFormat ?: return null
            if (f.width <= 0 || f.height <= 0) return null
            val pixel = f.pixelWidthHeightRatio
            return when {
                pixel <= 0f || pixel == 1f -> f.width to f.height
                pixel > 1f -> (f.width * pixel).roundToInt() to f.height
                else -> f.width to (f.height / pixel).roundToInt()
            }
        }
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
                    val format = group.getTrackFormat(i)
                    val file = externalPath(format)
                    // Counted even when it can't be played here (and so isn't listed), so a
                    // track's place is the same as in mpv, which lists them all.
                    val place = if (file != null) 0 else if (type == TrackType.Audio) ++audio else ++text
                    if (!group.isTrackSupported(i)) continue
                    result += TrackInfo(type, "$g:$i", trackLabel(format, place.takeIf { it > 0 } ?: 1), group.isTrackSelected(i), file, place)
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
        main.removeCallbacks(resync)
        subtitleDelayMs = 0
        subtitleDelayUs = 0
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

    override var subtitleDelayMs = 0L
        private set

    // The volume boost: Android's loudness enhancer on the player's sound, made when first
    // needed. The audio session is made here, not left to ExoPlayer (which makes its own
    // later, on another thread), so the enhancer can be attached to it right away.
    private val audioSession = context.getSystemService(android.media.AudioManager::class.java).generateAudioSessionId()
        .also { player.setAudioSessionId(it) }
    private var enhancer: android.media.audiofx.LoudnessEnhancer? = null

    override fun setVolumeBoost(percent: Int) {
        val boost = percent.coerceIn(100, 200)
        try {
            if (boost == 100) {
                enhancer?.enabled = false
                return
            }
            val e = enhancer ?: android.media.audiofx.LoudnessEnhancer(audioSession).also { enhancer = it }
            // As a gain in millibels: 200% is about +6 dB.
            e.setTargetGain((2000 * kotlin.math.log10(boost / 100.0)).toInt())
            e.enabled = true
        } catch (e: RuntimeException) {
            // Some phones don't offer the effect: the sound just isn't boosted.
            android.util.Log.w("ExoEngine", "No loudness enhancer", e)
        }
    }

    private val main = Handler(Looper.getMainLooper())

    // After the subtitles were moved later, the renderer has to show cues it has already let
    // go of; only a seek makes it read them again. Seeking to exactly where the video is does
    // nothing (ExoPlayer skips it), hence the millisecond back.
    private val resync = Runnable {
        val state = player.playbackState
        if (state == Player.STATE_READY || state == Player.STATE_BUFFERING)
            player.seekTo((player.currentPosition - 1).coerceAtLeast(0))
    }

    override fun setSubtitleDelay(ms: Long) {
        if (ms == subtitleDelayMs) return
        val later = ms > subtitleDelayMs
        subtitleDelayMs = ms
        subtitleDelayUs = ms * 1000
        // Earlier, while playing, the renderer just catches up; later, or while paused (when
        // ExoPlayer rarely asks it for anything), it needs the seek. A burst of taps gets one,
        // once they stop, since it can make the picture hitch.
        if (later || !player.isPlaying) {
            main.removeCallbacks(resync)
            main.postDelayed(resync, 400)
        }
    }

    override fun release() {
        main.removeCallbacks(resync)
        enhancer?.release()
        player.release()
    }
}

// Shows a renderer's subtitles `delayUs()` later (earlier if negative) than their own timing,
// by telling it the video is that far behind. Cues are read ahead as the video loads, so
// moving them earlier works while playing.
//
// Later is harder after a seek: ExoPlayer then reads the subtitles from about where the video
// now is, so the ones from `delay` before it, which should be showing, aren't there. The cues
// it showed while playing are remembered (CueMemory), and after a seek those are shown until
// the renderer has its own again. (Seeking far ahead, to a part not played yet, still leaves
// a gap of up to `delay`; ExoPlayer has thrown those cues away.)
private class DelayedTextRenderer(
    renderer: Renderer,
    private val memory: CueMemory,
    // On the thread the subtitles are shown from (the main one), where CueMemory lives.
    private val handler: Handler,
    private val delayUs: () -> Long,
    private val durationUs: () -> Long,
) : ForwardingRenderer(renderer) {
    // Added to a time in the video to make the renderer's own time (see Renderer.enable()).
    private var offsetUs = 0L
    // The renderer's time last rendered: the video's, and the one handed on (delay applied).
    private var realUs = C.TIME_UNSET
    private var shiftedUs = C.TIME_UNSET

    override fun enable(configuration: RendererConfiguration, formats: Array<Format>, stream: SampleStream,
                        positionUs: Long, joining: Boolean, mayRenderStartOfStream: Boolean,
                        startPositionUs: Long, offsetUs: Long, mediaPeriodId: MediaSource.MediaPeriodId) {
        this.offsetUs = offsetUs
        realUs = C.TIME_UNSET
        shiftedUs = C.TIME_UNSET
        super.enable(configuration, formats, stream, positionUs, joining, mayRenderStartOfStream,
                     startPositionUs, offsetUs, mediaPeriodId)
    }

    override fun replaceStream(formats: Array<Format>, stream: SampleStream, startPositionUs: Long,
                               offsetUs: Long, mediaPeriodId: MediaSource.MediaPeriodId) {
        this.offsetUs = offsetUs
        super.replaceStream(formats, stream, startPositionUs, offsetUs, mediaPeriodId)
    }

    // Another track, or another video: what was remembered is no use. (Posted before the
    // renderer's own clearing, so it reaches the memory first.)
    override fun disable() {
        handler.post { memory.clear() }
        super.disable()
    }

    override fun resetPosition(positionUs: Long, sampleStreamIsResetToKeyFrame: Boolean) {
        val until = if (shiftedUs == C.TIME_UNSET) C.TIME_UNSET else shiftedUs - offsetUs
        handler.post { memory.seeked(until) }
        realUs = positionUs
        super.resetPosition(positionUs, sampleStreamIsResetToKeyFrame)
    }

    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        val delay = delayUs()
        realUs = positionUs
        shiftedUs = positionUs - delay
        super.render(shiftedUs, elapsedRealtimeUs)
        if (delay > 0 && memory.restoring) {
            val at = shiftedUs - offsetUs
            handler.post { memory.show(at) }
        }
    }

    override fun getDurationToProgressUs(positionUs: Long, elapsedRealtimeUs: Long): Long =
        super.getDurationToProgressUs(positionUs - delayUs(), elapsedRealtimeUs)

    // ExoPlayer ends the video only once every renderer has. Subtitles moved later would hold
    // it on its last picture until they were through: once the video is at its end, they're done.
    override fun isEnded(): Boolean {
        if (super.isEnded()) return true
        val duration = durationUs()
        return delayUs() > 0 && duration > 0 && realUs != C.TIME_UNSET && realUs - offsetUs >= duration
    }
}

// The subtitles a renderer showed, by when in the video (the file's own timing), to show again
// after a seek (see DelayedTextRenderer). It stands between the renderer and the player, and
// is used on the thread the renderer's subtitles arrive on (the main one).
private class CueMemory(private val output: TextOutput) : TextOutput {
    private class Shown(val untilUs: Long, val cues: List<Cue>)

    // By the time they came on.
    private val shown = TreeMap<Long, Shown>()
    // What the renderer has on screen now, and since when.
    private var sinceUs = C.TIME_UNSET
    private var current: List<Cue> = emptyList()
    // After a seek the renderer first clears the screen; from then until it shows something
    // of its own, remembered cues are shown (`restoring`, read on ExoPlayer's thread).
    private var awaitingClear = false
    @Volatile var restoring = false
        private set
    private var restored: List<Cue>? = null

    fun clear() {
        shown.clear()
        sinceUs = C.TIME_UNSET
        current = emptyList()
        awaitingClear = false
        restoring = false
        restored = null
    }

    // A seek: what was on screen lasted at least until `untilUs` (the file's timing).
    fun seeked(untilUs: Long) {
        keepCurrent(untilUs)
        sinceUs = C.TIME_UNSET
        current = emptyList()
        awaitingClear = true
        restoring = false
        restored = null
    }

    // While restoring: shows what was on screen at `atUs` (the file's timing), if anything.
    fun show(atUs: Long) {
        if (!restoring) return
        val cues = shown.floorEntry(atUs)?.value?.takeIf { it.untilUs > atUs }?.cues ?: emptyList()
        if (cues === restored) return
        restored = cues
        send(CueGroup(cues, atUs))
    }

    override fun onCues(cueGroup: CueGroup) {
        if (awaitingClear) {
            awaitingClear = false
            restoring = true
            send(cueGroup)
            return
        }
        restoring = false
        restored = null
        keepCurrent(cueGroup.presentationTimeUs)
        sinceUs = cueGroup.presentationTimeUs
        current = cueGroup.cues
        send(cueGroup)
    }

    private fun keepCurrent(untilUs: Long) {
        if (sinceUs != C.TIME_UNSET && current.isNotEmpty() && untilUs != C.TIME_UNSET && untilUs > sinceUs)
            shown[sinceUs] = Shown(untilUs, current)
    }

    // On to the player, both ways the renderer itself hands them over.
    @Suppress("DEPRECATION")
    private fun send(group: CueGroup) {
        output.onCues(group.cues)
        output.onCues(group)
    }
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
