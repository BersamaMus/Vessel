package com.better_video_player.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// How subtitles look (Settings > Subtitles); see AppSettings.subtitleStyle.
data class SubtitleStyle(val size: String = "medium", val color: String = "white", val background: String = "outline") {
    val scale: Float get() = when (size) { "small" -> 0.8f; "large" -> 1.25f; "xlarge" -> 1.5f; else -> 1f }
    val yellow: Boolean get() = color == "yellow"
}

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

    // Ask GitHub whether a newer Vessel is out, each time the app opens (see UpdateCheck).
    private val _checkForUpdates = MutableStateFlow(prefs.getBoolean("checkForUpdates", true))
    val checkForUpdates: StateFlow<Boolean> = _checkForUpdates

    // Videos that needed mpv (ExoPlayer couldn't play them), or were switched to it by hand.
    private val mpvVideos = prefs.getStringSet("mpvVideos", emptySet())!!.toMutableSet()

    // The language last searched for subtitles in ("en"); "" before the first search.
    private val _subtitleLanguage = MutableStateFlow(prefs.getString("subtitleLanguage", "")!!)
    val subtitleLanguage: StateFlow<String> = _subtitleLanguage

    // When OpenSubtitles allows more downloads (ms since 1970), once the day's are used up;
    // 0 if they haven't. Kept so a restart doesn't spend a request finding it again.
    private val _subtitleLimitReset = MutableStateFlow(prefs.getLong("subtitleLimitReset", 0L))
    val subtitleLimitReset: StateFlow<Long> = _subtitleLimitReset

    // How subtitles look, as on the desktop: size "small" / "medium" / "large" / "xlarge",
    // colour "white" / "yellow", background "outline" / "box" / "none".
    private val _subtitleStyle = MutableStateFlow(SubtitleStyle(
        prefs.getString("subtitleSize", "medium")!!, prefs.getString("subtitleColor", "white")!!,
        prefs.getString("subtitleBackground", "outline")!!))
    val subtitleStyle: StateFlow<SubtitleStyle> = _subtitleStyle

    // The last few searches, newest first.
    private val _recentSearches = MutableStateFlow(
        prefs.getString("recentSearches", "")!!.split('\n').filter { it.isNotBlank() })
    val recentSearches: StateFlow<List<String>> = _recentSearches

    // Folders the user let Vessel read (Storage Access Framework tree URIs), where it looks
    // for subtitle files next to videos: Android's video permission doesn't cover them.
    private val _subtitleFolders = MutableStateFlow(
        prefs.getString("subtitleFolders", "")!!.split('\n').filter { it.isNotBlank() })
    val subtitleFolders: StateFlow<List<String>> = _subtitleFolders

    fun setSubtitleStyle(style: SubtitleStyle) {
        _subtitleStyle.value = style
        prefs.edit {
            putString("subtitleSize", style.size)
            putString("subtitleColor", style.color)
            putString("subtitleBackground", style.background)
        }
    }

    fun addRecentSearch(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        setRecentSearches((listOf(t) + _recentSearches.value.filterNot { it.equals(t, ignoreCase = true) }).take(8))
    }

    fun removeRecentSearch(text: String) = setRecentSearches(_recentSearches.value - text)

    private fun setRecentSearches(list: List<String>) {
        _recentSearches.value = list
        prefs.edit { putString("recentSearches", list.joinToString("\n")) }
    }

    fun setSubtitleFolders(list: List<String>) {
        _subtitleFolders.value = list
        prefs.edit { putString("subtitleFolders", list.joinToString("\n")) }
    }

    fun setSubtitleLanguage(code: String) {
        _subtitleLanguage.value = code
        prefs.edit { putString("subtitleLanguage", code) }
    }

    fun setSubtitleLimitReset(ms: Long) {
        _subtitleLimitReset.value = ms
        prefs.edit { putLong("subtitleLimitReset", ms) }
    }

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

    fun setCheckForUpdates(on: Boolean) {
        _checkForUpdates.value = on
        prefs.edit { putBoolean("checkForUpdates", on) }
    }

    fun usesMpv(key: String) = key in mpvVideos

    // The video was renamed or moved: it still plays with mpv if it did.
    fun relocateMpv(from: String, to: String) {
        if (from in mpvVideos) {
            setUsesMpv(from, false)
            setUsesMpv(to, true)
        }
    }

    fun setUsesMpv(key: String, on: Boolean) {
        if (if (on) mpvVideos.add(key) else mpvVideos.remove(key))
            prefs.edit { putStringSet("mpvVideos", mpvVideos.toSet()) }
    }
}
