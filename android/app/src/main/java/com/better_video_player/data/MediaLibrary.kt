package com.better_video_player.data

import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.storage.StorageManager
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Every video on the phone and its SD cards, from MediaStore. Unlike the desktop app, there's
// nothing to walk: Android indexes media files itself (leaving out apps' private folders), and
// tells us when they change, so new and removed videos show up by themselves.
class MediaLibrary(private val context: Context, private val scope: CoroutineScope) {

    private val _videos = MutableStateFlow<List<Video>>(emptyList())
    val videos: StateFlow<List<Video>> = _videos

    // The first query has come back (before that, "no videos" would be a guess).
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    private var queryJob: Job? = null
    private var changeJob: Job? = null
    private var observer: ContentObserver? = null

    // Changes anywhere in MediaStore, on every volume.
    private val collection: Uri = MediaStore.AUTHORITY_URI

    // Called once storage access is granted (and again when it changes).
    fun start() {
        if (observer == null) {
            observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                // Copying a film in is many changes: look again a moment after the last one.
                override fun onChange(selfChange: Boolean) {
                    changeJob?.cancel()
                    changeJob = scope.launch {
                        delay(800)
                        refresh()
                    }
                }
            }.also { context.contentResolver.registerContentObserver(collection, true, it) }
        }
        refresh()
    }

    fun stop() {
        observer?.let { context.contentResolver.unregisterContentObserver(it) }
        observer = null
    }

    fun refresh() {
        queryJob?.cancel()
        queryJob = scope.launch {
            _scanning.value = true
            try {
                _videos.value = withContext(Dispatchers.IO) { query() }
                _loaded.value = true
            } catch (e: SecurityException) {
                Log.w(TAG, "No access to videos", e)
                _videos.value = emptyList()
                _loaded.value = true
            } finally {
                // A query cancelled by a newer one leaves "Scanning…" up for that one.
                if (queryJob === coroutineContext[Job]) _scanning.value = false
            }
        }
    }

    private fun query(): List<Video> {
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.VOLUME_NAME,
            MediaStore.Video.Media.RELATIVE_PATH,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.DATE_TAKEN,
            MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.ORIENTATION,
        )
        val result = ArrayList<Video>()
        // Each volume (the phone's storage, SD cards, USB drives) on its own: the combined
        // "external" view leaves out removable drives for apps on some Android versions.
        for (volumeName in MediaStore.getExternalVolumeNames(context)) {
        // Empty files are unfinished downloads; they can't be played. A drive ejected since it
        // was listed throws ("Volume not found"): leave it out.
        val cursor = try {
            context.contentResolver.query(MediaStore.Video.Media.getContentUri(volumeName), projection,
                                          "${MediaStore.Video.Media.SIZE} > 0", null, null)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Volume $volumeName is gone", e)
            null
        }
        cursor?.use { c ->
            val id = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val name = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val volume = c.getColumnIndexOrThrow(MediaStore.Video.Media.VOLUME_NAME)
            val path = c.getColumnIndexOrThrow(MediaStore.Video.Media.RELATIVE_PATH)
            val duration = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val size = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val width = c.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
            val height = c.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
            val added = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            val modified = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
            val taken = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_TAKEN)
            val mime = c.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
            val orientation = c.getColumnIndexOrThrow(MediaStore.Video.Media.ORIENTATION)
            while (c.moveToNext()) {
                val fileName = c.getString(name) ?: continue
                val volumeName = c.getString(volume) ?: MediaStore.VOLUME_EXTERNAL_PRIMARY
                // A phone's video recorded upright is stored sideways with a rotation tag.
                val turned = c.getInt(orientation) % 180 != 0
                val w = c.getInt(width)
                val h = c.getInt(height)
                result += Video(
                    id = c.getLong(id),
                    uri = ContentUris.withAppendedId(MediaStore.Video.Media.getContentUri(volumeName), c.getLong(id)),
                    fileName = fileName,
                    title = prettyTitle(fileName.substringBeforeLast('.')),
                    volume = volumeName,
                    relativePath = c.getString(path) ?: "",
                    durationMs = c.getLong(duration),
                    size = c.getLong(size),
                    width = if (turned) h else w,
                    height = if (turned) w else h,
                    addedMs = c.getLong(added) * 1000,
                    modifiedMs = c.getLong(modified) * 1000,
                    takenMs = c.getLong(taken),
                    mimeType = c.getString(mime) ?: "",
                )
            }
        }
        }
        return result
    }

    // "Phone storage", or the SD card's or USB drive's own name.
    fun volumeLabel(volume: String): String {
        if (volume == MediaStore.VOLUME_EXTERNAL_PRIMARY || volume.isEmpty())
            return "Phone storage"
        if (Build.VERSION.SDK_INT >= 30) {
            val storage = context.getSystemService(StorageManager::class.java)
            storage.storageVolumes.firstOrNull { it.mediaStoreVolumeName == volume }
                ?.getDescription(context)?.let { return it }
        }
        return "SD card"
    }

    // "Phone storage/Movies/Films"
    fun locationOf(video: Video): String =
        if (video.volume.isEmpty()) "" else (volumeLabel(video.volume) + "/" + video.relativePath).trimEnd('/')

    // A file another app asked us to play: what we can find out about it.
    fun describe(uri: Uri): Video {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "Video"
        var size = 0L
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { name = it }
                    size = c.getLong(1)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't read details of $uri", e)
        }
        // A library video handed over by its MediaStore URI: use what the library knows. Matched
        // by id, since the same video can be named under "external" or its own volume.
        if (uri.authority == MediaStore.AUTHORITY) {
            uri.lastPathSegment?.toLongOrNull()?.let { id -> _videos.value.firstOrNull { it.id == id }?.let { return it } }
        }
        return Video(
            id = -1, uri = uri, fileName = name, title = prettyTitle(name.substringBeforeLast('.')),
            volume = "", relativePath = "", durationMs = 0, size = size, width = 0, height = 0,
            addedMs = 0, modifiedMs = 0, takenMs = 0, mimeType = context.contentResolver.getType(uri) ?: "",
        )
    }

    private companion object {
        const val TAG = "MediaLibrary"
    }
}
