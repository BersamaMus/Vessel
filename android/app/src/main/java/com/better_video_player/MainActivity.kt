package com.better_video_player

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.media.AudioManager
import android.view.KeyEvent
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.Lifecycle
import com.better_video_player.ui.VesselApp
import com.better_video_player.ui.VesselTheme

// Whether the app is showing as a picture-in-picture window (just the video, no controls).
val LocalInPictureInPicture = staticCompositionLocalOf { false }

class MainActivity : ComponentActivity() {

    private val vm: VesselViewModel by viewModels()

    private val inPictureInPicture = mutableStateOf(false)

    // What the picture-in-picture window would be: set by the watch page as the video plays.
    private var pipEligible = false
    private var pipAspect = Rational(16, 9)
    private var pipSourceRect: Rect? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        requestedOrientation = normalOrientation(this)
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            CompositionLocalProvider(LocalInPictureInPicture provides inPictureInPicture.value) {
                VesselTheme {
                    VesselApp(vm)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    // "Open with Vessel" from a file manager or another app.
    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) intent.data?.let { vm.openUri(it) }
    }

    // From the watch page: whether leaving now should go to picture-in-picture, the video's shape,
    // and where it is on screen (so the window grows out of it smoothly).
    fun updatePictureInPicture(eligible: Boolean, videoWidth: Int, videoHeight: Int, sourceRect: Rect?) {
        pipEligible = eligible
        if (videoWidth > 0 && videoHeight > 0) {
            // Android allows between 1:2.39 and 2.39:1.
            val ratio = (videoWidth.toFloat() / videoHeight).coerceIn(0.42f, 2.38f)
            pipAspect = Rational((ratio * 1000).toInt(), 1000)
        }
        pipSourceRect = sourceRect
        setPictureInPictureParams(pipParams())
    }

    private fun pipParams(): PictureInPictureParams = PictureInPictureParams.Builder()
        .setAspectRatio(pipAspect)
        .apply {
            pipSourceRect?.let { setSourceRectHint(it) }
            // Android 12+ goes to picture-in-picture by itself when the app is left.
            if (Build.VERSION.SDK_INT >= 31) {
                setAutoEnterEnabled(pipEligible)
                setSeamlessResizeEnabled(true)
            }
        }
        .build()

    // Before Android 12: going home (or to another app) while a video plays.
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < 31 && pipEligible && vm.wantsPictureInPicture) enterPictureInPictureMode(pipParams())
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPictureInPicture.value = isInPictureInPictureMode
        // Leaving picture-in-picture while stopped means its window was closed (not expanded).
        if (!isInPictureInPictureMode && lifecycle.currentState == Lifecycle.State.CREATED) vm.onPictureInPictureClosed()
    }

    // The volume keys, while a video is open: past the phone's maximum, Volume up boosts the
    // sound (to 200%, as the desktop's volume goes), and Volume down takes the boost off before
    // the phone's own volume. Otherwise they work as usual.
    private var boostKey = 0
    // When the boost last changed (KeyEvent.eventTime), to pace it while the key is held.
    private var boostedAt = 0L

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.action == KeyEvent.ACTION_DOWN && vm.current.value != null) {
                val audio = getSystemService(AudioManager::class.java)
                val atMax = audio.getStreamVolume(AudioManager.STREAM_MUSIC) >= audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val boost = vm.volumeBoost.value
                val step = when {
                    code == KeyEvent.KEYCODE_VOLUME_UP && atMax -> 10
                    code == KeyEvent.KEYCODE_VOLUME_DOWN && boost > 100 -> -10
                    else -> 0
                }
                if (event.repeatCount > 0) {
                    // Held down. A press that turned the phone's own volume up carries on doing
                    // that, and doesn't run on into the boost: that takes a press of its own.
                    if (boostKey != code) return super.dispatchKeyEvent(event)
                    // One that changes the boost goes on a step at a time, slower than the key
                    // repeats (which would take it to 200% in half a second), and stops at the
                    // end of it rather than running on into the phone's own volume.
                    if (step != 0 && event.eventTime - boostedAt >= BOOST_REPEAT_MS) {
                        vm.boostVolume(step)
                        boostedAt = event.eventTime
                    }
                    return true
                }
                if (step != 0) {
                    vm.boostVolume(step)
                    boostKey = code
                    boostedAt = event.eventTime
                    return true
                }
                boostKey = 0
            } else if (event.action == KeyEvent.ACTION_UP && boostKey == code) {
                // Its release too, so the system doesn't act on half a key press.
                boostKey = 0
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) vm.onAppStopped(isInPictureInPictureMode)
    }

    companion object {
        // While a volume key is held, the boost changes by a step this often (ms).
        private const val BOOST_REPEAT_MS = 250L

        // Phones, rather than tablets.
        fun isPhone(activity: Activity) = activity.resources.configuration.smallestScreenWidthDp < 600

        // Phones stay upright, as in YouTube's app; only full screen turns them sideways.
        fun normalOrientation(activity: Activity) =
            if (isPhone(activity)) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
}
