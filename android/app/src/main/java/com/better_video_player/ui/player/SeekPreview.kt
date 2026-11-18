package com.better_video_player.ui.player

import android.content.Context
import android.media.MediaMetadataRetriever
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.better_video_player.data.Video
import com.better_video_player.data.formatTime
import com.better_video_player.ui.Palette
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

// Frames for the seek bar while it's dragged, as the desktop's SeekPreview: the keyframe
// nearest the spot (quick, if not exact), from Android's own decoders, one at a time. A video
// only mpv can play gets none: then just the time shows.
class SeekPreview(context: Context, private val video: Video) {
    private val context = context.applicationContext
    // One thread: MediaMetadataRetriever isn't safe to share between threads.
    private val thread = Executors.newSingleThreadExecutor()
    private val dispatcher = thread.asCoroutineDispatcher()
    private var retriever: MediaMetadataRetriever? = null
    private var failed = false
    private val cache = LinkedHashMap<Long, ImageBitmap>()

    // The frame near `ms`, or null if there's none to be had.
    suspend fun frameAt(ms: Long): ImageBitmap? = withContext(dispatcher) {
        // Frames a couple of seconds apart are all the bar's precision allows.
        val slot = ms / 2000
        cache[slot]?.let { return@withContext it }
        if (failed) return@withContext null
        val r = retriever ?: try {
            MediaMetadataRetriever().also { it.setDataSource(context, video.uri); retriever = it }
        } catch (e: Exception) {
            failed = true
            return@withContext null
        }
        val frame = try {
            r.getScaledFrameAtTime(ms * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 180)?.asImageBitmap()
        } catch (e: Exception) {
            null
        }
        if (frame != null) {
            cache[slot] = frame
            if (cache.size > 60) cache.remove(cache.keys.first())
        }
        frame
    }

    fun release() {
        thread.execute {
            try { retriever?.release() } catch (_: Exception) {}
            retriever = null
        }
        thread.shutdown()
    }
}

@Composable
fun rememberSeekPreview(video: Video): SeekPreview {
    val context = LocalContext.current
    val preview = remember(video.key) { SeekPreview(context, video) }
    DisposableEffect(preview) { onDispose { preview.release() } }
    return preview
}

// The frame at `ms` and the time under it, above the seek bar while it's dragged.
@Composable
fun SeekPreviewBubble(preview: SeekPreview, ms: Long, durationMs: Long, modifier: Modifier = Modifier, width: Dp = 160.dp,
                      showTime: Boolean = true) {
    var frame by remember(preview) { mutableStateOf<ImageBitmap?>(null) }
    val target by rememberUpdatedState(ms)
    // The newest spot wins: a frame still loading for an older one is dropped.
    LaunchedEffect(preview) {
        snapshotFlow { target / 2000 }.distinctUntilChanged().collectLatest {
            preview.frameAt(target)?.let { frame = it }
        }
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        frame?.let { image ->
            Box(Modifier.width(width).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp))
                    .border(2.dp, Palette.textPrimary, RoundedCornerShape(10.dp)).background(Palette.surfaceHigh)) {
                Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
            }
        }
        if (showTime) Text("${formatTime(ms / 1000.0)} / ${formatTime(durationMs / 1000.0)}",
             Modifier.padding(top = 6.dp).background(Palette.badge, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
             color = Palette.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}
