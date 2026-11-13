@file:androidx.annotation.OptIn(UnstableApi::class)

package com.better_video_player.ui.player

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.better_video_player.engine.ExoEngine
import com.better_video_player.engine.MpvEngine
import com.better_video_player.engine.VideoEngine

// The picture, drawn by whichever engine is playing: ExoPlayer into Media3's PlayerView (which
// also shows its subtitles), mpv into a plain SurfaceView (it draws subtitles itself).
@Composable
fun VideoSurface(engine: VideoEngine, modifier: Modifier = Modifier) {
    key(engine) {
        when (engine) {
            is ExoEngine -> AndroidView(
                factory = { context ->
                    PlayerView(context).apply {
                        useController = false
                        player = engine.player
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        setShutterBackgroundColor(android.graphics.Color.BLACK)
                    }
                },
                onRelease = { it.player = null },
                modifier = modifier.fillMaxSize(),
            )
            is MpvEngine -> AndroidView(
                factory = { context ->
                    SurfaceView(context).apply {
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) = engine.attachSurface(holder.surface)
                            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) =
                                engine.surfaceSizeChanged(width, height)
                            override fun surfaceDestroyed(holder: SurfaceHolder) = engine.detachSurface()
                        })
                    }
                },
                modifier = modifier.fillMaxSize(),
            )
        }
    }
}
