package com.better_video_player.data

import android.content.Context
import android.net.Uri
import com.better_video_player.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.FileInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.util.Locale

// A subtitle found online.
data class SubtitleResult(
    val fileId: Int,
    // The release it was made for ("House.S01E02.720p.BluRay"), or its file name.
    val name: String,
    // "House (2004)"
    val title: String,
    // "English", and OpenSubtitles' code for it ("en").
    val language: String,
    val languageCode: String,
    val downloads: Int,
    // Made for this exact file (found by its hash), so it's in sync.
    val hashMatch: Boolean,
    val hearingImpaired: Boolean,
    val machine: Boolean,
    // Its page on the website, for when the day's downloads are used up.
    val url: String,
)

data class SubtitleLanguage(val code: String, val name: String)

// What a search found; error says what went wrong when nothing was.
data class SubtitleSearchResult(val results: List<SubtitleResult>, val error: String)

// A downloaded subtitle: its text, its name as OpenSubtitles has it ("Movie.en.srt"), and,
// if that was the day's last download, when more are allowed (ms since 1970).
class SubtitleDownload(val data: ByteArray, val fileName: String, val limitResetMs: Long?)

class SubtitleException(message: String) : Exception(message)
// The day's downloads are used up until resetMs.
class SubtitleLimitException(val resetMs: Long) : Exception("Daily download limit reached")

// "Search online" in the subtitles sheet, as on the desktop (SubtitleSearch.cpp): finds
// subtitles for a video on OpenSubtitles (opensubtitles.com's REST API) and downloads the one
// picked. Nothing goes online until the user searches. Each search asks two ways at once: by
// the file's hash, which only finds subtitles made for this exact release (so they're in
// sync), and by its name. Without an account, OpenSubtitles allows a few downloads a day.
class OpenSubtitles(context: Context) {
    private val context = context.applicationContext

    // Vessel was built with an API key (the desktop app's opensubtitles.key, read by
    // build.gradle.kts); without one the feature is hidden.
    val available: Boolean get() = BuildConfig.OPENSUBTITLES_API_KEY.isNotEmpty()

    // The one of `languages` the phone is set to, or English.
    fun systemLanguage(): String {
        val locale = Locale.getDefault()
        val code = when (val language = locale.language.lowercase()) {
            "pt" -> if (locale.country.equals("BR", ignoreCase = true)) "pt-br" else "pt-pt"
            "zh" -> if (locale.script == "Hant" || locale.country in setOf("TW", "HK", "MO")) "zh-tw" else "zh-cn"
            "nb", "nn" -> "no"
            "in" -> "id"   // Android's old code for Indonesian
            "iw" -> "he"   // and for Hebrew
            else -> language
        }
        return languages.firstOrNull { it.code == code }?.code ?: "en"
    }

    // Looks for subtitles for the video at `uri` in `language`, by its hash and by `query`
    // (its title, which the user can change). Results from one way are shown even if the
    // other failed; those that match the hash come first.
    suspend fun search(uri: Uri, query: String, language: String): SubtitleSearchResult = coroutineScope {
        val byName = query.trim().takeIf { it.isNotEmpty() }?.let { q ->
            async { searchBy(listOf("languages" to language, "query" to q.lowercase())) }
        }
        val byHash = async {
            val hash = withContext(Dispatchers.IO) { movieHash(uri) }
            if (hash == null) Result.success(emptyList())
            else searchBy(listOf("languages" to language, "moviehash" to hash))
        }
        val answers = listOfNotNull(byHash.await(), byName?.await())

        // One list, each subtitle once (the hash's version of it if both found it), the hash's
        // matches first and the rest in OpenSubtitles' order (the best match for the name first).
        val byFile = LinkedHashMap<Int, SubtitleResult>()
        for (found in answers.flatMap { it.getOrNull().orEmpty() }) {
            val seen = byFile[found.fileId]
            if (seen == null || (found.hashMatch && !seen.hashMatch)) byFile[found.fileId] = found
        }
        val results = byFile.values.sortedByDescending { it.hashMatch }   // stable
        val error = if (results.isEmpty()) answers.firstNotNullOfOrNull { it.exceptionOrNull()?.message }.orEmpty() else ""
        SubtitleSearchResult(results, error)
    }

    // One GET /subtitles. The parameters go in alphabetical order with "+" for spaces, as
    // OpenSubtitles asks (otherwise it answers with a redirect to the same thing written its way).
    // A cancelled search (a new one started) is let through as cancelled, not turned into a
    // failure whose message might be shown.
    private suspend fun searchBy(parameters: List<Pair<String, String>>): Result<List<SubtitleResult>> = try {
        Result.success(searchByOrThrow(parameters))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    private suspend fun searchByOrThrow(parameters: List<Pair<String, String>>): List<SubtitleResult> = run {
        val query = parameters.sortedBy { it.first }
            .joinToString("&") { (name, value) -> name + "=" + URLEncoder.encode(value, "UTF-8") }
        val answer = request("subtitles?$query")
        if (answer.status != 200) throw SubtitleException(failureText(answer))
        val data = answer.json().optJSONArray("data") ?: return@run emptyList()
        buildList {
            for (i in 0 until data.length()) {
                val attributes = data.optJSONObject(i)?.optJSONObject("attributes") ?: continue
                val files = attributes.optJSONArray("files") ?: continue
                // A film split over several files (CD1, CD2) doesn't fit a single video.
                if (files.length() != 1) continue
                val file = files.getJSONObject(0)
                val fileId = file.optInt("file_id")
                if (fileId == 0) continue
                val feature = attributes.optJSONObject("feature_details") ?: JSONObject()
                val year = feature.optInt("year")
                val title = feature.optString("title")
                val release = attributes.optString("release").trim()
                val code = attributes.optString("language").lowercase()
                add(SubtitleResult(
                    fileId = fileId,
                    name = release.ifEmpty { file.optString("file_name") },
                    title = if (year > 0) "$title ($year)" else title,
                    language = languageName(code),
                    languageCode = code,
                    downloads = attributes.optInt("download_count"),
                    hashMatch = attributes.optBoolean("moviehash_match"),
                    hearingImpaired = attributes.optBoolean("hearing_impaired"),
                    machine = attributes.optBoolean("ai_translated") || attributes.optBoolean("machine_translated"),
                    url = attributes.optString("url"),
                ))
            }
        }
    }

    // First OpenSubtitles is asked for the file, which counts as one of the day's downloads
    // and gives a link to it; then the file is fetched from there. Throws SubtitleException
    // (what went wrong) or SubtitleLimitException (no downloads left today).
    suspend fun download(fileId: Int): SubtitleDownload {
        val answer = request("download", JSONObject().put("file_id", fileId).toString())
        val json = answer.json()
        val link = json.optString("link")
        val remaining = json.optInt("remaining", 1)
        val resetMs = parseTime(json.optString("reset_time_utc"))

        // Out of downloads for today (406), or this was the last one.
        if (answer.status == 406 || (link.isEmpty() && remaining <= 0))
            throw SubtitleLimitException(resetMs ?: (System.currentTimeMillis() + 3_600_000))
        if (answer.status != 200 || link.isEmpty()) throw SubtitleException(failureText(answer))

        val file = fetch(link, api = false, timeoutMs = 30_000)
        if (file.status != 200 || file.body.isEmpty()) throw SubtitleException(failureText(file))
        return SubtitleDownload(file.body, json.optString("file_name"), if (remaining <= 0) resetMs else null)
    }

    private class Answer(val status: Int, val body: ByteArray) {
        fun json(): JSONObject = try { JSONObject(String(body)) } catch (e: Exception) { JSONObject() }
    }

    private suspend fun request(endpoint: String, postJson: String? = null) =
        fetch("https://api.opensubtitles.com/api/v1/$endpoint", api = true, postJson = postJson)

    // Status 0 if OpenSubtitles couldn't be reached at all.
    private suspend fun fetch(url: String, api: Boolean, postJson: String? = null, timeoutMs: Int = 20_000): Answer =
        withContext(Dispatchers.IO) {
            try {
                val connection = URL(url).openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = timeoutMs
                    connection.readTimeout = timeoutMs
                    // OpenSubtitles asks every app to say what it is, as the name its key was
                    // registered under, on everything it asks for: the API and the files it links to.
                    connection.setRequestProperty("User-Agent", "Vessel v${BuildConfig.VERSION_NAME}")
                    if (api) {
                        connection.setRequestProperty("Api-Key", BuildConfig.OPENSUBTITLES_API_KEY)
                        connection.setRequestProperty("Accept", "application/json")
                    }
                    if (postJson != null) {
                        connection.requestMethod = "POST"
                        connection.doOutput = true
                        connection.setRequestProperty("Content-Type", "application/json")
                        connection.outputStream.use { it.write(postJson.toByteArray()) }
                    }
                    val status = connection.responseCode
                    val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                    Answer(status, stream?.use { it.readBytes() } ?: ByteArray(0))
                } finally {
                    connection.disconnect()
                }
            } catch (e: IOException) {
                Answer(0, ByteArray(0))
            }
        }

    // What to tell the user when a request failed, from how it failed.
    private fun failureText(answer: Answer): String = when (answer.status) {
        0 -> "Couldn't reach OpenSubtitles. Check your internet connection."
        401, 403 -> "OpenSubtitles didn't accept this copy of Vessel. Try updating Vessel."
        429 -> "OpenSubtitles is busy. Try again in a moment."
        else -> answer.json().optString("message").ifEmpty { "OpenSubtitles couldn't do that just now (error ${answer.status})." }
    }

    private fun parseTime(text: String): Long? =
        try { if (text.isEmpty()) null else Instant.parse(text).toEpochMilli() } catch (e: Exception) { null }

    // OpenSubtitles' hash of a video: its size plus its first and last 64 KB, read as 64-bit
    // numbers and added up (letting it overflow). Quick even for a huge file, and the same for
    // every copy of the same release, whatever it's called. null if the file can't be read or
    // is under 128 KB.
    private fun movieHash(uri: Uri): String? = try {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
            val size = descriptor.statSize
            if (size < 2 * CHUNK) return@use null
            FileInputStream(descriptor.fileDescriptor).channel.use { channel ->
                var hash = size
                for (offset in longArrayOf(0, size - CHUNK)) {
                    val buffer = ByteBuffer.allocate(CHUNK.toInt()).order(ByteOrder.LITTLE_ENDIAN)
                    while (buffer.hasRemaining()) {
                        if (channel.read(buffer, offset + buffer.position()) < 0) return@use null
                    }
                    buffer.flip()
                    while (buffer.hasRemaining()) hash += buffer.long
                }
                "%016x".format(hash)
            }
        }
    } catch (e: Exception) {
        null
    }

    companion object {
        private const val CHUNK = 64 * 1024L

        // The languages offered, by OpenSubtitles' codes (ISO 639-1, plus the Portuguese and
        // Chinese variants it tells apart).
        val languages = listOf(
            "ar" to "Arabic", "bg" to "Bulgarian", "zh-cn" to "Chinese (simplified)", "zh-tw" to "Chinese (traditional)",
            "hr" to "Croatian", "cs" to "Czech", "da" to "Danish", "nl" to "Dutch", "en" to "English",
            "et" to "Estonian", "fi" to "Finnish", "fr" to "French", "de" to "German", "el" to "Greek",
            "he" to "Hebrew", "hi" to "Hindi", "hu" to "Hungarian", "id" to "Indonesian", "it" to "Italian",
            "ja" to "Japanese", "ko" to "Korean", "ms" to "Malay", "no" to "Norwegian", "fa" to "Persian",
            "pl" to "Polish", "pt-pt" to "Portuguese", "pt-br" to "Portuguese (Brazil)", "ro" to "Romanian",
            "ru" to "Russian", "sr" to "Serbian", "sk" to "Slovak", "sl" to "Slovenian", "es" to "Spanish",
            "sv" to "Swedish", "th" to "Thai", "tr" to "Turkish", "uk" to "Ukrainian", "vi" to "Vietnamese",
        ).map { (code, name) -> SubtitleLanguage(code, name) }

        fun languageName(code: String): String =
            languages.firstOrNull { it.code.equals(code, ignoreCase = true) }?.name ?: code
    }
}
