package com.better_video_player.data

import android.app.RecoverableSecurityException
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// What came of changing a file.
sealed interface FileOutcome {
    data object Done : FileOutcome
    // Android wants the user's say first: ask with `request`, and if they agree, call `retry`.
    class NeedsConsent(val request: IntentSender, val retry: suspend () -> FileOutcome) : FileOutcome
    class Failed(val message: String) : FileOutcome
}

// Renaming, moving and deleting the phone's videos, through MediaStore, as the desktop's
// video menu does. Android lets an app change only the files it made itself, so for the rest
// it asks the user each time, in a dialog of its own: on Android 11+ before the change
// (createWriteRequest / createTrashRequest), on Android 10 when the change is turned down
// (RecoverableSecurityException).
class VideoFiles(private val context: Context) {
    // Each takes `beforeChange`, called (on the caller's thread) just before the file is
    // changed, once the user has agreed where Android asks first: the player lets go of it then.

    // Gives the video a new name; the extension stays as it was.
    suspend fun rename(video: Video, newName: String, beforeChange: () -> Unit = {}): FileOutcome {
        val name = newName.trim()
        if (name.isEmpty() || name.any { it in INVALID }) return FileOutcome.Failed("A name can't contain / \\ : * ? \" < > |")
        val fileName = renamedFileName(video, name)
        if (fileName == video.fileName) return FileOutcome.Done
        return change(video, ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, fileName) }, beforeChange)
    }

    // Moves the video to `relativePath` ("Movies/Films/") on the same storage.
    suspend fun move(video: Video, relativePath: String, beforeChange: () -> Unit = {}): FileOutcome {
        if (relativePath == video.relativePath) return FileOutcome.Done
        return change(video, ContentValues().apply { put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath) }, beforeChange)
    }

    // On Android 11+, to the phone's trash (where it stays 30 days, as in the recycle bin on
    // the desktop): the system's own dialog does it, so `beforeChange` comes once it has (an
    // open file can still be read after it's moved to the trash). On Android 10, deleted for good.
    suspend fun delete(video: Video, beforeChange: () -> Unit = {}): FileOutcome {
        if (Build.VERSION.SDK_INT >= 30)
            return FileOutcome.NeedsConsent(MediaStore.createTrashRequest(context.contentResolver, listOf(video.uri), true).intentSender) {
                beforeChange()
                FileOutcome.Done
            }
        beforeChange()
        return attempt {
            if (context.contentResolver.delete(video.uri, null, null) > 0) FileOutcome.Done
            else FileOutcome.Failed("Couldn't delete ${video.fileName}")
        }
    }

    private suspend fun change(video: Video, values: ContentValues, beforeChange: () -> Unit): FileOutcome {
        val apply: suspend () -> FileOutcome = { beforeChange(); attempt { update(video, values) } }
        // On Android 11+ the user agrees first (unless Vessel made the file itself).
        return if (Build.VERSION.SDK_INT >= 30)
            FileOutcome.NeedsConsent(MediaStore.createWriteRequest(context.contentResolver, listOf(video.uri)).intentSender, apply)
        else apply()
    }

    private fun update(video: Video, values: ContentValues): FileOutcome =
        if (context.contentResolver.update(video.uri, values, null, null) > 0) FileOutcome.Done
        else FileOutcome.Failed("Couldn't change ${video.fileName}")

    // Runs `block` off the main thread; Android 10's "ask the user first" comes back as
    // NeedsConsent, to try again once they've agreed.
    private suspend fun attempt(block: () -> FileOutcome): FileOutcome = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (e: RecoverableSecurityException) {
            FileOutcome.NeedsConsent(e.userAction.actionIntent.intentSender) { attempt(block) }
        } catch (e: IllegalStateException) {
            // A file of that name is already there.
            Log.w(TAG, "Couldn't change the file", e)
            FileOutcome.Failed("There's already a file with that name there")
        } catch (e: IllegalArgumentException) {
            // A folder MediaStore won't put videos in.
            Log.w(TAG, "Couldn't change the file", e)
            FileOutcome.Failed("Android doesn't allow videos in that folder")
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't change the file", e)
            FileOutcome.Failed("Couldn't change the file")
        }
    }

    private companion object {
        const val TAG = "VideoFiles"
        const val INVALID = "/\\:*?\"<>|"
    }
}

// The file name renaming `video` to `newName` gives: the extension stays as it was. (Also how
// the view model knows the renamed video's key, so its history and the rest can follow it.)
fun renamedFileName(video: Video, newName: String): String {
    val name = newName.trim()
    val extension = video.fileName.substringAfterLast('.', "")
    return if (extension.isEmpty() || name.endsWith(".$extension", ignoreCase = true)) name else "$name.$extension"
}
