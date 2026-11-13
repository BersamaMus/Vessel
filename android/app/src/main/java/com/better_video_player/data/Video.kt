package com.better_video_player.data

import android.net.Uri

// A video on the phone, as MediaStore knows it.
data class Video(
    // MediaStore's id; -1 for a file another app handed over ("Open with").
    val id: Long,
    val uri: Uri,
    val fileName: String,
    val title: String,
    // MediaStore's volume: "external_primary" for the phone's own storage, else an SD card or
    // USB drive. Empty for a file from another app.
    val volume: String,
    // The folder on that volume, with a trailing slash: "Movies/Films/".
    val relativePath: String,
    val durationMs: Long,
    val size: Long,
    val width: Int,
    val height: Int,
    val addedMs: Long,
    val modifiedMs: Long,
    // When it was recorded (a camera's videos); 0 when not known.
    val takenMs: Long,
    val mimeType: String,
) {
    // Identifies the folder across rescans.
    val folder: String get() = if (volume.isEmpty()) "" else "$volume:$relativePath"

    // Watch history is keyed by where the file is, which, unlike MediaStore's ids, stays the
    // same if the phone's media database is rebuilt.
    val key: String get() = if (id < 0) uri.toString() else folder + fileName

    val folderName: String get() = folderNameOf(relativePath)

    val durationSeconds: Double get() = durationMs / 1000.0

    val portrait: Boolean get() = height > width
}

fun folderNameOf(relativePath: String): String =
    relativePath.trimEnd('/').substringAfterLast('/')

// A folder that holds videos (directly, not in sub-folders).
data class Folder(
    val key: String,
    val name: String,
    // "Phone storage/Movies/Films"
    val location: String,
    val videos: List<Video>,
) {
    // The newest video's thumbnail stands for the folder.
    val cover: Video get() = videos.maxBy { it.addedMs }
    val newestMs: Long get() = videos.maxOf { it.addedMs }
}
