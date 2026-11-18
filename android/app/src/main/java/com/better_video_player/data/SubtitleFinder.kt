package com.better_video_player.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log

// A subtitle file next to a video: where it is, and its name ("Movie.en.srt").
data class FoundSubtitle(val uri: Uri, val name: String)

// Finds subtitle files that seem to belong to a video, as the desktop's subtitlesFor() does:
// the same name, ignoring case, spaces and punctuation, with an optional language tag
// ("Movie.en.srt"); or the same episode ("S01E02", "1x02", "Season 1 Episode 2"); or any, in a
// folder that holds just that one video. Subs / Subtitles folders next to the video count too.
//
// Android's permission to read videos doesn't cover subtitle files, so Vessel can only look in
// folders the user has let it read (Settings > Subtitles > Folders to look in), through the
// Storage Access Framework. A video in none of them isn't looked for.
class SubtitleFinder(private val context: Context) {

    // Subtitle files for `video`, best matches first. On the calling thread (call it off the
    // main one). `trees`: the folders granted, as tree URIs.
    fun find(video: Video, trees: List<String>): List<FoundSubtitle> {
        if (video.volume.isEmpty()) return emptyList()
        for (tree in trees) {
            val treeUri = Uri.parse(tree)
            val folderId = folderIdIn(treeUri, video) ?: continue
            return try {
                match(video, treeUri, folderId)
            } catch (e: Exception) {
                // The folder's access was taken back, or it's gone (an SD card taken out).
                Log.w(TAG, "Couldn't look in $tree", e)
                emptyList()
            }
        }
        return emptyList()
    }

    // The video's folder as a document in `tree` ("primary:Movies/Films"), if the tree holds it.
    private fun folderIdIn(tree: Uri, video: Video): String? {
        val treeId = try { DocumentsContract.getTreeDocumentId(tree) } catch (e: Exception) { return null }
        // The storage the tree is on: "primary" for the phone's own, else the SD card's id
        // ("1234-ABCD"), which MediaStore names in lower case.
        val root = treeId.substringBefore(':')
        val volume = if (root == "primary") MediaStore.VOLUME_EXTERNAL_PRIMARY else root.lowercase()
        if (video.volume != volume) return null
        val treePath = treeId.substringAfter(':', "").trim('/')
        val videoPath = video.relativePath.trim('/')
        val inside = treePath.isEmpty() || videoPath.equals(treePath, ignoreCase = true) ||
            videoPath.startsWith("$treePath/", ignoreCase = true)
        // The tree's own spelling of the path, then the rest as MediaStore has it.
        return if (inside) "$root:" + (if (treePath.isEmpty()) videoPath else treePath + videoPath.substring(treePath.length)) else null
    }

    private data class Entry(val id: String, val name: String, val folder: Boolean, val mime: String)

    private fun list(tree: Uri, folderId: String): List<Entry> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, folderId)
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                              DocumentsContract.Document.COLUMN_MIME_TYPE)
        return context.contentResolver.query(children, columns, null, null, null)?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    val mime = c.getString(2) ?: ""
                    add(Entry(c.getString(0), c.getString(1) ?: "", mime == DocumentsContract.Document.MIME_TYPE_DIR, mime))
                }
            }
        } ?: emptyList()
    }

    private fun match(video: Video, tree: Uri, folderId: String): List<FoundSubtitle> {
        val baseName = video.fileName.substringBeforeLast('.')
        val here = list(tree, folderId)
        fun isSubtitle(e: Entry) = !e.folder && e.name.substringAfterLast('.', "").lowercase() in SavedSubtitles.extensions
        fun found(e: Entry) = FoundSubtitle(DocumentsContract.buildDocumentUriUsingTree(tree, e.id), e.name)

        // Next to the video, and in Subs / Subtitles folders (including Subs/<video name>/, as
        // some releases do). Files in a folder named after the video are its own.
        val candidates = here.filter(::isSubtitle).toMutableList()
        val ownFolder = mutableListOf<Entry>()
        for (folder in here.filter { it.folder && it.name.lowercase() in SUBTITLE_FOLDERS }) {
            val inside = list(tree, folder.id)
            candidates += inside.filter(::isSubtitle)
            inside.firstOrNull { it.folder && it.name.equals(baseName, ignoreCase = true) }
                ?.let { own -> ownFolder += list(tree, own.id).filter(::isSubtitle) }
        }

        // In a folder with just this one video (a movie's own folder), every subtitle is its.
        val onlyVideo = here.count { !it.folder && it.mime.startsWith("video/") } <= 1

        val name = normalizedName(baseName)
        val episode = episodeOf(baseName)
        val exact = mutableListOf<FoundSubtitle>()
        val likely = mutableListOf<FoundSubtitle>()
        for (subtitle in candidates) {
            val subtitleBase = subtitle.name.substringBeforeLast('.')
            val subtitleName = normalizedName(subtitleBase)
            val rest = if (subtitleName.startsWith(name)) subtitleName.substring(name.length) else null
            // "Movie.en", but not "Movie 2".
            val languageTag = rest != null && rest.length <= 16 && rest.all { it.isLetter() }
            when {
                subtitleName == name -> exact += found(subtitle)
                languageTag || (episode != null && episodeOf(subtitleBase) == episode) || onlyVideo -> likely += found(subtitle)
            }
        }
        likely += ownFolder.map(::found)
        return (exact + likely).distinctBy { it.uri }
    }

    companion object {
        private const val TAG = "SubtitleFinder"
        private val SUBTITLE_FOLDERS = setOf("subs", "sub", "subtitles", "subtitle")

        // Letters and digits only, in lower case: "Movie.Name_2019" -> "moviename2019".
        fun normalizedName(name: String): String = name.filter { it.isLetterOrDigit() }.lowercase()

        // Season and episode from "S01E02", "1x02" or "Season 1 Episode 2"; null if none.
        fun episodeOf(name: String): Pair<Int, Int>? {
            val patterns = listOf(
                Regex("s(\\d{1,2})[ ._-]*e(\\d{1,3})", RegexOption.IGNORE_CASE),
                Regex("season\\D{0,3}(\\d{1,2})\\D{0,3}episode\\D{0,3}(\\d{1,3})", RegexOption.IGNORE_CASE),
                // Not "1920x1080": no digits either side.
                Regex("(?<!\\d)(\\d{1,2})x(\\d{2})(?!\\d)"),
            )
            for (p in patterns) p.find(name)?.let { return it.groupValues[1].toInt() to it.groupValues[2].toInt() }
            return null
        }
    }
}
