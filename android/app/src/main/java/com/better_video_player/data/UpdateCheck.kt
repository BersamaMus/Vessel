package com.better_video_player.data

import com.better_video_player.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// Asks GitHub which version of Vessel is the newest, once each time the app opens (unless
// it's turned off in Settings). Only the question is sent: nothing about the user or their
// videos. The desktop app, Blimp and Glide do the same.
object UpdateCheck {
    // Where a newer version can be downloaded: the latest release's page, which has the
    // Android app, the Windows installer and what's changed.
    const val RELEASES_PAGE = "https://github.com/BersamaMus/Vessel/releases/latest"

    // The newer version's number, like "1.7.0", or null when this one is the newest or
    // GitHub couldn't be reached.
    suspend fun newerVersion(): String? = withContext(Dispatchers.IO) {
        try {
            val connection = URL("https://api.github.com/repos/BersamaMus/Vessel/releases/latest").openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                connection.setRequestProperty("User-Agent", "Vessel (https://github.com/BersamaMus/Vessel)")
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                if (connection.responseCode != 200) return@withContext null
                val body = connection.inputStream.use { String(it.readBytes()) }
                // Release tags are "v1.7.0".
                val latest = JSONObject(body).optString("tag_name").removePrefix("v")
                latest.takeIf { it.isNotEmpty() && isNewerVersion(it, BuildConfig.VERSION_NAME) }
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            // Offline, or an answer that isn't GitHub's (a Wi-Fi sign-in page): try again next time.
            null
        }
    }

    // Whether version `latest` ("1.7.0") comes after `current` ("1.6.0"), comparing each
    // number in turn, so 1.10.0 is newer than 1.9.0. Anything after a "-" or "+" is ignored,
    // and a missing number counts as 0.
    fun isNewerVersion(latest: String, current: String): Boolean {
        fun parts(version: String) = version.split('-', '+').first().trim().split('.').map { it.toIntOrNull() ?: 0 }
        val a = parts(latest)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
