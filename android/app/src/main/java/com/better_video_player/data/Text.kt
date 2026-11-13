package com.better_video_player.data

import java.text.DateFormat
import java.text.Normalizer
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

// Text for durations, sizes and dates, matching the desktop app's wording.

// Seconds watched before a video counts as started (Continue watching, resume).
const val MIN_PROGRESS = 10.0

// 95% covers a film's end credits; short clips just need to reach the end.
fun isFinished(position: Double, duration: Double): Boolean =
    duration > 0 && (position >= duration * 0.95 || duration - position < 5)

// "1:02:03" or "2:03".
fun formatTime(seconds: Double): String {
    val total = seconds.toLong().coerceAtLeast(0)
    val h = total / 3600
    val m = total / 60 % 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun durationText(ms: Long): String = if (ms > 0) formatTime(ms / 1000.0) else ""

fun remainingText(position: Double, duration: Double): String {
    val minutes = ((duration - position) / 60).roundToInt()
    return when {
        minutes < 1 -> "Less than a minute left"
        minutes < 60 -> "$minutes min left"
        else -> "${minutes / 60} h ${minutes % 60} min left"
    }
}

fun sizeText(bytes: Long): String = when {
    bytes <= 0 -> ""
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.0f MB".format(bytes / (1L shl 20).toDouble())
    else -> "%.0f KB".format(bytes / 1024.0)
}

fun dateText(ms: Long): String =
    if (ms > 0) DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ms)) else ""

fun dateTimeText(ms: Long): String =
    if (ms > 0) DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms)) else ""

// History's headings: "Today", "Yesterday", the weekday within a week, else the date.
fun dayHeading(ms: Long, now: Long = System.currentTimeMillis()): String {
    fun startOfDay(t: Long) = Calendar.getInstance().apply {
        timeInMillis = t
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val days = ((startOfDay(now) - startOfDay(ms)) / 86_400_000L).toInt()
    return when {
        days <= 0 -> "Today"
        days == 1 -> "Yesterday"
        days < 7 -> Calendar.getInstance().apply { timeInMillis = ms }
            .getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG, Locale.getDefault()) ?: dateText(ms)
        else -> dateText(ms)
    }
}

fun countText(count: Int, one: String, many: String): String =
    if (count == 1) "1 $one" else "$count $many"

// "A.Star.Is.Born.2018" -> "A Star Is Born 2018".
fun prettyTitle(baseName: String): String =
    if (!baseName.contains(' ') && (baseName.contains('.') || baseName.contains('_')))
        baseName.replace('.', ' ').replace('_', ' ').trim().replace(Regex("\\s+"), " ")
    else baseName

// The compression standard for a video's MIME type: "video/avc" is "H.264 (AVC)".
fun codecName(mime: String?): String = when (mime) {
    null, "" -> ""
    "video/avc" -> "H.264 (AVC)"
    "video/hevc" -> "H.265 (HEVC)"
    "video/av01" -> "AV1"
    "video/x-vnd.on2.vp9" -> "VP9"
    "video/x-vnd.on2.vp8" -> "VP8"
    "video/mp4v-es" -> "MPEG-4 Part 2"
    "video/mpeg2" -> "MPEG-2"
    "video/3gpp" -> "H.263"
    "video/dolby-vision" -> "Dolby Vision"
    "video/mjpeg" -> "Motion JPEG"
    else -> mime.substringAfter('/').uppercase()
}

// For search: lower case, without accents or punctuation, single spaces.
fun searchKey(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()

// Names as people sort them: "Episode 2" before "Episode 10".
object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                val si = i
                val sj = j
                while (i < a.length && a[i].isDigit()) i++
                while (j < b.length && b[j].isDigit()) j++
                val na = a.substring(si, i).trimStart('0')
                val nb = b.substring(sj, j).trimStart('0')
                if (na.length != nb.length) return na.length - nb.length
                val c = na.compareTo(nb)
                if (c != 0) return c
            } else {
                val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (c != 0) return c
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }
}

// The compression standard for mpv's codec name: "h264" is "H.264 (AVC)" (as on the desktop).
fun mpvCodecName(format: String?): String {
    if (format.isNullOrEmpty()) return ""
    val names = mapOf(
        "h264" to "H.264 (AVC)", "hevc" to "H.265 (HEVC)", "av1" to "AV1", "vp9" to "VP9", "vp8" to "VP8",
        "mpeg4" to "MPEG-4 Part 2 (DivX/Xvid)", "mpeg2video" to "MPEG-2", "mpeg1video" to "MPEG-1",
        "msmpeg4v3" to "MS MPEG-4 v3", "msmpeg4v2" to "MS MPEG-4 v2", "h263" to "H.263",
        "wmv3" to "WMV 9", "wmv2" to "WMV 8", "wmv1" to "WMV 7", "vc1" to "VC-1",
        "prores" to "ProRes", "theora" to "Theora", "flv1" to "Sorenson Spark", "mjpeg" to "Motion JPEG",
        "rv40" to "RealVideo 4", "rv30" to "RealVideo 3",
    )
    return names[format] ?: format.uppercase()
}
