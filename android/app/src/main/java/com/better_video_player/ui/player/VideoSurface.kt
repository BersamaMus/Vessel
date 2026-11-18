@file:androidx.annotation.OptIn(UnstableApi::class)

package com.better_video_player.ui.player

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import com.better_video_player.R
import com.better_video_player.data.SubtitleStyle
import com.better_video_player.engine.ExoEngine
import com.better_video_player.engine.MpvEngine
import com.better_video_player.engine.Picture
import com.better_video_player.engine.VideoEngine
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// The picture, drawn by whichever engine is playing: ExoPlayer into Media3's PlayerView, mpv
// into a plain SurfaceView. mpv draws its own subtitles and does the picture's aspect, crop
// and turn itself (MpvEngine.setPicture); for ExoPlayer they're done here: the picture is
// laid out at the chosen shape (and turned), and its subtitles are drawn by a SubtitleView
// over the whole video, so they stay upright and in view whatever the picture does.
@Composable
fun VideoSurface(
    engine: VideoEngine,
    modifier: Modifier = Modifier,
    picture: Picture = Picture(),
    subtitleStyle: SubtitleStyle = SubtitleStyle(),
    // The video's own shape (width / height, as shown); 0 until known.
    videoRatio: Float = 0f,
) {
    key(engine) {
        when (engine) {
            is ExoEngine -> Box(modifier.fillMaxSize().clipToBounds()) {
                // A turned or cropped picture needs a TextureView: a SurfaceView can't be turned,
                // and isn't clipped to the video's space (a cropped one would spill out of it).
                val textured = picture.rotation != 0 || picture.fill
                key(textured) {
                    AndroidView(
                        factory = { context ->
                            val view = if (textured)
                                LayoutInflater.from(context).inflate(R.layout.player_texture_view, null) as PlayerView
                            else PlayerView(context)
                            view.apply {
                                useController = false
                                setShutterBackgroundColor(android.graphics.Color.BLACK)
                                // Drawn by the SubtitleView below instead.
                                subtitleView?.visibility = View.GONE
                                player = engine.player
                            }
                        },
                        update = { view ->
                            // At the chosen shape the picture fills its frame; else as it is, whole.
                            view.resizeMode = if (picture.ratio != null || videoRatio > 0) AspectRatioFrameLayout.RESIZE_MODE_FILL
                                              else AspectRatioFrameLayout.RESIZE_MODE_FIT
                        },
                        onRelease = { it.player = null },
                        modifier = Modifier.pictureFrame(picture, picture.ratio ?: videoRatio),
                    )
                }
                ExoSubtitles(engine, subtitleStyle)
            }
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

// Lays the picture out at `ratio` (width / height; 0 if not known yet: then it fills the space
// and the player fits it), turned by the picture's rotation: as big as fits, or (fill) as big
// as covers the space, cropping the edges. Centred.
private fun Modifier.pictureFrame(picture: Picture, ratio: Float): Modifier = this
    .layout { measurable, constraints ->
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        if (ratio <= 0f || w == 0 || h == 0) {
            val p = measurable.measure(Constraints.fixed(w, h))
            return@layout layout(w, h) { p.place(0, 0) }
        }
        val turned = picture.rotation % 180 != 0
        // The shape it takes on screen, once turned.
        val shown = if (turned) 1f / ratio else ratio
        val scale = if (picture.fill) max(w / shown, h.toFloat()) else min(w / shown, h.toFloat())
        val shownH = scale
        val shownW = scale * shown
        // Laid out unturned, then turned in place.
        val frameW = (if (turned) shownH else shownW).roundToInt().coerceAtLeast(1)
        val frameH = (if (turned) shownW else shownH).roundToInt().coerceAtLeast(1)
        val p = measurable.measure(Constraints.fixed(frameW, frameH))
        layout(w, h) { p.place((w - frameW) / 2, (h - frameH) / 2) }
    }
    .graphicsLayer { rotationZ = picture.rotation.toFloat() }

// ExoPlayer's subtitles, in the style chosen in Settings (as mpv's, see MpvEngine.setSubtitleStyle).
@Composable
private fun ExoSubtitles(engine: ExoEngine, style: SubtitleStyle) {
    val view = remember { arrayOfNulls<SubtitleView>(1) }
    DisposableEffect(engine) {
        val listener = object : Player.Listener {
            override fun onCues(cueGroup: CueGroup) {
                view[0]?.setCues(cueGroup.cues)
            }
        }
        engine.player.addListener(listener)
        onDispose { engine.player.removeListener(listener) }
    }
    AndroidView(
        factory = { context -> SubtitleView(context).also { view[0] = it; it.setCues(engine.player.currentCues.cues) } },
        update = { v ->
            val box = style.background == "box"
            v.setStyle(CaptionStyleCompat(
                if (style.yellow) 0xFFFFE14D.toInt() else android.graphics.Color.WHITE,
                if (box) 0xC0000000.toInt() else android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
                when (style.background) {
                    "outline" -> CaptionStyleCompat.EDGE_TYPE_OUTLINE
                    "none" -> CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
                    else -> CaptionStyleCompat.EDGE_TYPE_NONE
                },
                android.graphics.Color.BLACK,
                Typeface.create("sans-serif-medium", Typeface.NORMAL),
            ))
            v.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * style.scale)
            // Styled subtitles keep their own colours and placement, but follow the size (as on the desktop).
            v.setApplyEmbeddedStyles(true)
            v.setApplyEmbeddedFontSizes(false)
        },
        onRelease = { view[0] = null },
        modifier = Modifier.fillMaxSize(),
    )
}
