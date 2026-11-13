package com.better_video_player.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.better_video_player.MainActivity
import com.better_video_player.R

// Holds the media session, so playback can carry on (with its notification) when the app is in
// the background or the screen is off. The player itself belongs to the app's view model, which
// hands it over here (see VesselViewModel); the service only exists while the view model does.
@UnstableApi
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        setMediaNotificationProvider(DefaultMediaNotificationProvider.Builder(this).build().apply {
            setSmallIcon(R.drawable.ic_notification)
        })
        val player = player ?: return
        // Tapping the notification brings the app back.
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player).setSessionActivity(open).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    // Swiped away from the recent apps: the view model (and its player) goes too, so stop.
    override fun onTaskRemoved(rootIntent: Intent?) {
        session?.player?.pause()
        stopSelf()
    }

    override fun onDestroy() {
        session?.release()
        session = null
        super.onDestroy()
    }

    companion object {
        // Set by the view model before it connects to the service, cleared when it goes.
        @Volatile var player: SessionPlayer? = null
    }
}
