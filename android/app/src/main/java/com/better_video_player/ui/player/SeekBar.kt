package com.better_video_player.ui.player

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.better_video_player.ui.Palette

// The seek bar along the bottom of the video, as on YouTube: a thin line that grows a thumb
// while the controls show. Dragging scrubs (the picture follows); letting go seeks there.
@Composable
fun SeekBar(
    fraction: Float,
    buffered: Float,
    showThumb: Boolean,
    onScrubStart: () -> Unit,
    onScrub: (Float) -> Unit,
    onScrubEnd: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    val scrub by rememberUpdatedState(onScrub)
    val start by rememberUpdatedState(onScrubStart)
    val end by rememberUpdatedState(onScrubEnd)
    val shown = if (dragging) dragFraction else fraction
    val thumb by animateDpAsState(if (dragging) 16.dp else if (showThumb) 12.dp else 0.dp, label = "thumb")
    val track by animateDpAsState(if (dragging) 5.dp else 3.dp, label = "track")

    Box(
        modifier
            .fillMaxWidth()
            .height(28.dp)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val f = (offset.x / size.width).coerceIn(0f, 1f)
                    start()
                    end(f)
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        dragging = true
                        dragFraction = (offset.x / size.width).coerceIn(0f, 1f)
                        start()
                        scrub(dragFraction)
                    },
                    onDragEnd = {
                        dragging = false
                        end(dragFraction)
                    },
                    onDragCancel = {
                        dragging = false
                        end(dragFraction)
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                        scrub(dragFraction)
                    },
                )
            }
    ) {
        Canvas(Modifier.fillMaxWidth().height(28.dp)) {
            val h = track.toPx()
            val y = size.height / 2 - h / 2
            val r = CornerRadius(h / 2)
            drawRoundRect(Palette.track, Offset(0f, y), Size(size.width, h), r)
            drawRoundRect(Palette.textPrimary.copy(alpha = 0.45f), Offset(0f, y), Size(size.width * buffered, h), r)
            drawRoundRect(Palette.accent, Offset(0f, y), Size(size.width * shown, h), r)
            if (thumb > 0.dp)
                drawCircle(Palette.accent, thumb.toPx() / 2, Offset(size.width * shown, size.height / 2))
        }
    }
}
