package com.better_video_player.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// The player's settings, kept in SharedPreferences.
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    // How far a double tap on the side of the video skips.
    private val _skipSeconds = MutableStateFlow(prefs.getInt("skipSeconds", 10))
    val skipSeconds: StateFlow<Int> = _skipSeconds

    // When a video ends, play the next one in its folder after a short countdown.
    private val _autoplay = MutableStateFlow(prefs.getBoolean("autoplay", true))
    val autoplay: StateFlow<Boolean> = _autoplay

    // Keep the sound playing (with a notification) when leaving the app or turning the screen off.
    private val _backgroundPlay = MutableStateFlow(prefs.getBoolean("backgroundPlay", true))
    val backgroundPlay: StateFlow<Boolean> = _backgroundPlay

    // Leaving the app while a video plays shrinks it into a floating window.
    private val _pictureInPicture = MutableStateFlow(prefs.getBoolean("pictureInPicture", true))
    val pictureInPicture: StateFlow<Boolean> = _pictureInPicture

    // Play everything with mpv rather than ExoPlayer.
    private val _alwaysMpv = MutableStateFlow(prefs.getBoolean("alwaysMpv", false))
    val alwaysMpv: StateFlow<Boolean> = _alwaysMpv

    // Videos that needed mpv (ExoPlayer couldn't play them), or were switched to it by hand.
    private val mpvVideos = prefs.getStringSet("mpvVideos", emptySet())!!.toMutableSet()

    fun setSkipSeconds(seconds: Int) {
        _skipSeconds.value = seconds
        prefs.edit { putInt("skipSeconds", seconds) }
    }

    fun setAutoplay(on: Boolean) {
        _autoplay.value = on
        prefs.edit { putBoolean("autoplay", on) }
    }

    fun setBackgroundPlay(on: Boolean) {
        _backgroundPlay.value = on
        prefs.edit { putBoolean("backgroundPlay", on) }
    }

    fun setPictureInPicture(on: Boolean) {
        _pictureInPicture.value = on
        prefs.edit { putBoolean("pictureInPicture", on) }
    }

    fun setAlwaysMpv(on: Boolean) {
        _alwaysMpv.value = on
        prefs.edit { putBoolean("alwaysMpv", on) }
    }

    fun usesMpv(key: String) = key in mpvVideos

    fun setUsesMpv(key: String, on: Boolean) {
        if (if (on) mpvVideos.add(key) else mpvVideos.remove(key))
            prefs.edit { putStringSet("mpvVideos", mpvVideos.toSet()) }
    }
}
