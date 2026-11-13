package com.better_video_player.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import androidx.core.content.ContextCompat
import com.better_video_player.data.mpvCodecName
import dev.jdtech.mpv.MPVLib
import dev.jdtech.mpv.MPVLib.MpvEvent
import dev.jdtech.mpv.MPVLib.MpvFormat

// libmpv (with FFmpeg), the engine the desktop app uses: plays what ExoPlayer can't (old AVI
// codecs, WMV, AC-3/DTS sound on phones without those decoders, styled subtitles). It draws
// into a Surface given to it by MpvSurface. Created only when first needed: loading its
// libraries takes a moment and some memory.
class MpvEngine(context: Context) : VideoEngine, MPVLib.EventObserver {
    override val name = "mpv"

    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val listeners = Listeners()
    private val mpv: MPVLib = MPVLib.create(this.context) ?: error("libmpv couldn't start")

    // From mpv's property observers.
    private var loaded = false
    private var paused = false
    private var buffering = false
    private var eof = false
    private var position = 0.0
    private var duration = 0.0
    private var cached = 0.0
    private var currentSpeed = 1.0
    private var looping = false
    private var width = 0
    private var height = 0
    private var rotate = 0
    private var format = ""
    private var fps = 0.0
    private var trackList: List<TrackInfo> = emptyList()
    private var wasPlaying = false
    private var scrubbing = false
    // Subtitle files for the video being opened, added once it has loaded; and the one of them
    // to turn on (its path).
    private var externals: List<ExternalSubtitle> = emptyList()
    private var selectedExternal: String? = null

    init {
        // As mpv-android sets it up: OpenGL ES on the Surface, hardware decoding through
        // MediaCodec (copied back, so mpv's renderer and filters still work).
        // No picture output until there's a Surface to draw on (attachSurface switches to "gpu"):
        // a file opened before that would otherwise lose its video track.
        mpv.setOptionString("vo", "null")
        mpv.setOptionString("gpu-context", "android")
        mpv.setOptionString("opengl-es", "yes")
        // The emulator's hardware decoders fail mid-stream; decode in software there.
        mpv.setOptionString("hwdec", if (isEmulator()) "no" else "mediacodec-copy")
        mpv.setOptionString("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1")
        mpv.setOptionString("ao", "audiotrack,opensles")
        // Stay on the last frame at the end (so "ended" can be seen, and replayed).
        mpv.setOptionString("keep-open", "yes")
        mpv.setOptionString("idle", "yes")
        mpv.setOptionString("force-window", "no")
        mpv.setOptionString("input-default-bindings", "no")
        mpv.setOptionString("save-position-on-quit", "no")
        mpv.setOptionString("demuxer-max-bytes", "64MiB")
        // libass needs fonts for subtitles: the phone's own.
        mpv.setOptionString("sub-fonts-dir", "/system/fonts")
        mpv.setOptionString("sub-font", "Roboto")
        mpv.init()

        for ((property, type) in listOf(
            "time-pos" to MpvFormat.MPV_FORMAT_DOUBLE,
            "duration" to MpvFormat.MPV_FORMAT_DOUBLE,
            "demuxer-cache-time" to MpvFormat.MPV_FORMAT_DOUBLE,
            "pause" to MpvFormat.MPV_FORMAT_FLAG,
            "paused-for-cache" to MpvFormat.MPV_FORMAT_FLAG,
            "eof-reached" to MpvFormat.MPV_FORMAT_FLAG,
            "speed" to MpvFormat.MPV_FORMAT_DOUBLE,
            "loop-file" to MpvFormat.MPV_FORMAT_STRING,
            "dwidth" to MpvFormat.MPV_FORMAT_INT64,
            "dheight" to MpvFormat.MPV_FORMAT_INT64,
            "video-params/rotate" to MpvFormat.MPV_FORMAT_INT64,
            "video-format" to MpvFormat.MPV_FORMAT_STRING,
            "container-fps" to MpvFormat.MPV_FORMAT_DOUBLE,
            "track-list" to MpvFormat.MPV_FORMAT_NONE,
        )) mpv.observeProperty(property, type)
        mpv.addObserver(this)
    }

    // --- The Surface (see MpvSurface) ----------------------------------------------
    fun attachSurface(surface: Surface) {
        mpv.attachSurface(surface)
        mpv.setOptionString("force-window", "yes")
        mpv.setPropertyString("vo", "gpu")
    }

    fun surfaceSizeChanged(w: Int, h: Int) {
        mpv.setPropertyString("android-surface-size", "${w}x$h")
    }

    fun detachSurface() {
        mpv.setPropertyString("vo", "null")
        mpv.setPropertyString("force-window", "no")
        mpv.detachSurface()
    }

    // --- VideoEngine -------------------------------------------------------------------
    override val isPlaying get() = loaded && !paused && !eof && !buffering
    override val playWhenReady get() = (loaded || opening) && !paused && !eof
    override val phase get() = when {
        !loaded -> if (opening) Phase.Buffering else Phase.Idle
        eof -> Phase.Ended
        buffering -> Phase.Buffering
        else -> Phase.Ready
    }
    override val positionMs get() = (position * 1000).toLong()
    override val durationMs get() = (duration * 1000).toLong()
    override val bufferedMs get() = (cached * 1000).toLong()
    override val speed get() = currentSpeed.toFloat()
    override val repeat get() = looping
    override val videoWidth get() = if (rotate % 180 != 0) height else width
    override val videoHeight get() = if (rotate % 180 != 0) width else height
    override val videoCodec get() = mpvCodecName(format)
    override val frameRate get() = fps.toFloat()
    override val tracks get() = trackList

    override fun addListener(listener: EngineListener) = listeners.add(listener)
    override fun removeListener(listener: EngineListener) = listeners.remove(listener)

    private var opening = false
    // loadfile commands whose START_FILE hasn't come back yet. Until it has, an END_FILE is
    // the previous file being replaced, not this one failing.
    private var pendingStarts = 0
    // After release(): events still queued on the main thread must not touch mpv.
    private var released = false

    override fun open(uri: Uri, startMs: Long, subtitles: List<ExternalSubtitle>, selected: ExternalSubtitle?) {
        externals = subtitles
        selectedExternal = selected?.file?.absolutePath
        loaded = false
        opening = true
        eof = false
        position = startMs / 1000.0
        duration = 0.0
        trackList = emptyList()
        width = 0; height = 0; rotate = 0; format = ""; fps = 0.0
        val url = urlFor(uri)
        if (url == null) {
            opening = false
            listeners.each { it.onError(EngineError("This file can't be opened.", tryOtherEngine = false)) }
            return
        }
        mpv.setPropertyString("start", if (startMs > 0) "%.3f".format(java.util.Locale.ROOT, startMs / 1000.0) else "none")
        mpv.setPropertyString("loop-file", "no")
        mpv.setPropertyString("sid", "auto")
        mpv.setPropertyString("aid", "auto")
        mpv.setPropertyBoolean("pause", false)
        pendingStarts++
        mpv.command(arrayOf("loadfile", url, "replace"))
        requestFocus()
        changed()
    }

    // mpv reads a content:// file through its descriptor (closed by mpv when done).
    private fun urlFor(uri: Uri): String? = when (uri.scheme) {
        "content" -> try {
            context.contentResolver.openFileDescriptor(uri, "r")?.detachFd()?.let { "fdclose://$it" }
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't open $uri", e)
            null
        }
        "file" -> uri.path
        else -> uri.toString()
    }

    override fun addSubtitle(subtitle: ExternalSubtitle) {
        val path = subtitle.file.absolutePath
        externals = externals.filterNot { it.file.absolutePath == path } + subtitle
        selectedExternal = path
        // Still loading: added with the rest once it has.
        if (!loaded) return
        // Already one of its tracks (added when it loaded, or earlier): turned on, not added again.
        val existing = trackList.firstOrNull { it.type == TrackType.Subtitle && it.file == path }
        if (existing != null) mpv.setPropertyString("sid", existing.id)
        else subAdd(subtitle, select = true)
        main.postDelayed({ if (!released) { readTracks(); changed() } }, 100)
    }

    private fun subAdd(subtitle: ExternalSubtitle, select: Boolean) {
        // "auto" adds it without turning it on.
        mpv.command(listOfNotNull("sub-add", subtitle.file.absolutePath, if (select) "select" else "auto",
                                  subtitle.title, subtitle.language).toTypedArray())
    }

    override fun play() {
        if (eof) mpv.command(arrayOf("seek", "0", "absolute"))
        mpv.setPropertyBoolean("pause", false)
        requestFocus()
    }

    override fun pause() {
        mpv.setPropertyBoolean("pause", true)
    }

    override fun stop() {
        mpv.command(arrayOf("stop"))
        loaded = false
        opening = false
        abandonFocus()
        changed()
    }

    override fun seekTo(ms: Long) {
        if (!loaded) return
        position = ms / 1000.0
        mpv.command(arrayOf("seek", "%.3f".format(java.util.Locale.ROOT, ms / 1000.0),
                            if (scrubbing) "absolute+keyframes" else "absolute"))
    }

    override fun setScrubbing(on: Boolean) {
        scrubbing = on
    }

    override fun setSpeed(speed: Float) = mpv.setPropertyDouble("speed", speed.toDouble())

    override fun setRepeat(on: Boolean) = mpv.setPropertyString("loop-file", if (on) "inf" else "no")

    override fun selectTrack(type: TrackType, track: TrackInfo?) {
        mpv.setPropertyString(if (type == TrackType.Audio) "aid" else "sid", track?.id ?: "no")
        main.postDelayed({ if (!released) { readTracks(); changed() } }, 100)
    }

    override fun release() {
        released = true
        main.removeCallbacksAndMessages(null)
        abandonFocus()
        mpv.removeObserver(this)
        mpv.destroy()
    }

    // --- mpv's events (on its own thread: handled on the main one) ----------------------
    private fun onMain(block: () -> Unit) {
        main.post { if (!released) block() }
    }

    override fun eventProperty(property: String) {
        onMain {
            if (property == "track-list") readTracks()
            changed()
        }
    }

    override fun eventProperty(property: String, value: Long) {
        onMain {
            when (property) {
                "dwidth" -> width = value.toInt()
                "dheight" -> height = value.toInt()
                "video-params/rotate" -> rotate = value.toInt()
            }
            changed()
        }
    }

    override fun eventProperty(property: String, value: Double) {
        onMain {
            when (property) {
                "time-pos" -> if (!scrubbing) position = value
                "duration" -> duration = value
                "demuxer-cache-time" -> cached = value
                "speed" -> currentSpeed = value
                // Some AVI files claim absurd rates (600 fps): leave those out.
                "container-fps" -> fps = if (value in 1.0..240.0) value else 0.0
            }
            changed()
        }
    }

    override fun eventProperty(property: String, value: Boolean) {
        onMain {
            when (property) {
                "pause" -> paused = value
                "paused-for-cache" -> buffering = value
                "eof-reached" -> {
                    val ended = value && !eof
                    eof = value
                    if (ended && loaded) listeners.each { it.onEnded() }
                }
            }
            changed()
        }
    }

    override fun eventProperty(property: String, value: String) {
        onMain {
            when (property) {
                "loop-file" -> looping = value != "no"
                "video-format" -> format = value
            }
            changed()
        }
    }

    override fun event(eventId: Int) {
        onMain {
            when (eventId) {
                MpvEvent.MPV_EVENT_START_FILE -> if (pendingStarts > 0) pendingStarts--
                MpvEvent.MPV_EVENT_FILE_LOADED -> {
                    loaded = true
                    opening = false
                    for (subtitle in externals) subAdd(subtitle, select = subtitle.file.absolutePath == selectedExternal)
                    readTracks()
                }
                // Ended before it ever loaded: mpv couldn't play it either.
                MpvEvent.MPV_EVENT_END_FILE -> if (opening && !loaded && pendingStarts == 0) {
                    opening = false
                    listeners.each { it.onError(EngineError("This video can't be played: the file may be damaged.", tryOtherEngine = false)) }
                }
            }
            changed()
        }
    }

    private fun changed() {
        val playing = isPlaying
        if (playing != wasPlaying) {
            wasPlaying = playing
            if (playing) requestFocus()
            listeners.each { it.onIsPlayingChanged(playing) }
        }
        listeners.each { it.onChanged() }
    }

    private fun readTracks() {
        val count = mpv.getPropertyInt("track-list/count") ?: 0
        val result = mutableListOf<TrackInfo>()
        var audio = 0
        var subs = 0
        for (i in 0 until count) {
            val type = when (mpv.getPropertyString("track-list/$i/type")) {
                "audio" -> TrackType.Audio
                "sub" -> TrackType.Subtitle
                else -> continue
            }
            val id = mpv.getPropertyInt("track-list/$i/id") ?: continue
            val number = if (type == TrackType.Audio) ++audio else ++subs
            val title = mpv.getPropertyString("track-list/$i/title")
            val language = languageName(mpv.getPropertyString("track-list/$i/lang"))
            val channels = channelsText(mpv.getPropertyInt("track-list/$i/demux-channel-count") ?: 0)
            val label = listOf(title ?: language ?: "Track $number", if (title != null) language ?: "" else "", channels)
                .filter { it.isNotEmpty() }.distinct().joinToString("  ·  ")
            result += TrackInfo(type, id.toString(), label, mpv.getPropertyBoolean("track-list/$i/selected") == true,
                                mpv.getPropertyString("track-list/$i/external-filename"))
        }
        trackList = result
    }

    // --- Sound: ExoPlayer handles these itself; mpv doesn't --------------------------------
    private val audio = context.getSystemService(AudioManager::class.java)
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
        .setOnAudioFocusChangeListener({ change ->
            when (change) {
                // Another app took the sound for good: pause, and ask again on the next play.
                AudioManager.AUDIOFOCUS_LOSS -> { pause(); abandonFocus() }
                // A call or a brief sound: pause; the focus comes back by itself.
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pause()
            }
        }, main)
        .build()
    private var hasFocus = false

    // Headphones unplugged: pause.
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) = pause()
    }

    private fun requestFocus() {
        if (hasFocus) return
        hasFocus = audio.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (hasFocus) ContextCompat.registerReceiver(context, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_EXPORTED)
    }

    private fun abandonFocus() {
        if (!hasFocus) return
        hasFocus = false
        audio.abandonAudioFocusRequest(focusRequest)
        context.unregisterReceiver(noisy)
    }

    private companion object {
        const val TAG = "MpvEngine"
        fun isEmulator() = Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish")
    }
}
