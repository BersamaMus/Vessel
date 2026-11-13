package com.better_video_player.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import android.util.Size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import com.better_video_player.data.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// Thumbnails come from Android's own cache (MediaStore makes and keeps one per video), read a
// few at a time and kept in memory while they're on screen or nearby. For the videos Android
// can't thumbnail, mpv makes one (see MpvThumbnailer).
object Thumbnails {
    private val io = Dispatchers.IO.limitedParallelism(4)

    // Makes a thumbnail file when Android can't: set by the view model.
    @Volatile var fallback: (suspend (Video) -> File?)? = null

    // Videos whose thumbnail couldn't be made (a broken file): not asked for again.
    private val failed = HashSet<String>()

    private val cache = object : LruCache<String, ImageBitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    // Also changes when the file does, so an edited video gets a new thumbnail.
    private fun keyOf(video: Video) = "${video.uri}#${video.modifiedMs}"

    fun cached(video: Video): ImageBitmap? = cache.get(keyOf(video))

    fun hasFailed(video: Video): Boolean = synchronized(failed) { keyOf(video) in failed }

    suspend fun load(context: Context, video: Video): ImageBitmap? {
        val key = keyOf(video)
        cache.get(key)?.let { return it }
        if (hasFailed(video)) return null
        return withContext(io) {
            val image = try {
                context.contentResolver.loadThumbnail(video.uri, Size(512, 288), null).asImageBitmap()
            } catch (_: Exception) {
                fallback?.invoke(video)?.let { file -> BitmapFactory.decodeFile(file.path)?.asImageBitmap() }
            }
            if (image != null) cache.put(key, image) else synchronized(failed) { failed += key }
            image
        }
    }
}

// The video's thumbnail once it's loaded; null until then, or if there's none.
@Composable
fun rememberThumbnail(video: Video): ImageBitmap? {
    val context = LocalContext.current
    var image by remember(video.uri, video.modifiedMs) { mutableStateOf(Thumbnails.cached(video)) }
    LaunchedEffect(video.uri, video.modifiedMs) {
        if (image == null) image = Thumbnails.load(context.applicationContext, video)
    }
    return image
}
