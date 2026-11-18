package com.better_video_player.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

// Watch time on one day, in seconds: in all, and in each hour of the day.
data class DayStats(val seconds: Double = 0.0, val hours: List<Double> = List(24) { 0.0 })

// How often a video was played, and how often to the end.
data class VideoStats(val plays: Int = 0, val finished: Int = 0)

data class StatsData(
    // When counting began (ms since 1970); 0 before anything was counted.
    val since: Long = 0,
    val days: Map<LocalDate, DayStats> = emptyMap(),
    val videos: Map<String, VideoStats> = emptyMap(),
)

// What the Stats page shows, worked out from StatsData (as the desktop's watchStats()).
data class StatsSummary(
    val total: Double,
    val today: Double,
    val week: Double,
    val daysWatched: Int,
    val streak: Int,
    val longestStreak: Int,
    val plays: Int,
    val finished: Int,
    val since: Long,
    // The last 30 days, oldest first.
    val last30: List<Pair<LocalDate, Double>>,
    val hours: List<Double>,
    // Monday first.
    val weekdays: List<Double>,
    val bestDay: LocalDate?,
    val bestSeconds: Double,
)

// Time spent watching, as on the desktop: the time that passes while a video plays (a minute
// at 2x is a minute), per day and hour of the day, and plays and finishes per video.
// stats.json, in the desktop app's format: { since, days: { "2026-11-18": { s, h: { "21": s } } },
// videos: { key: { plays, done } } }. Clearing the history leaves it be.
class WatchStats(dir: File, private val scope: CoroutineScope) {
    private val file = File(dir, "stats.json")
    private val _data = MutableStateFlow(load())
    val data: StateFlow<StatsData> = _data
    private var saveJob: Job? = null

    // `seconds` more of watching, just now. The player hands it over a few seconds at a time.
    fun addWatchTime(seconds: Double) {
        if (seconds <= 0) return
        val now = LocalDateTime.now()
        val s = seconds.coerceAtMost(120.0)
        change { d ->
            val day = d.days[now.toLocalDate()] ?: DayStats()
            val hours = day.hours.toMutableList().also { it[now.hour] += s }
            d.copy(since = if (d.since == 0L) System.currentTimeMillis() else d.since,
                   days = d.days + (now.toLocalDate() to DayStats(day.seconds + s, hours)))
        }
    }

    fun notePlay(key: String) = editVideo(key) { it.copy(plays = it.plays + 1) }

    fun noteFinished(key: String) = editVideo(key) { it.copy(finished = it.finished + 1) }

    fun relocate(from: String, to: String) {
        val stats = _data.value.videos[from] ?: return
        change { it.copy(videos = it.videos - from + (to to stats)) }
    }

    // The video was deleted. (The time spent watching it still counts.)
    fun forget(key: String) {
        if (key !in _data.value.videos) return
        change { it.copy(videos = it.videos - key) }
    }

    fun clear() = change { StatsData() }

    private fun editVideo(key: String, block: (VideoStats) -> VideoStats) {
        if (key.isEmpty()) return
        change { d -> d.copy(videos = d.videos + (key to block(d.videos[key] ?: VideoStats()))) }
    }

    fun summary(today: LocalDate = LocalDate.now()): StatsSummary {
        val d = _data.value
        fun on(date: LocalDate) = d.days[date]?.seconds ?: 0.0
        var total = 0.0
        var week = 0.0
        var best = 0.0
        var bestDay: LocalDate? = null
        val hours = MutableList(24) { 0.0 }
        val weekdays = MutableList(7) { 0.0 }
        val watched = mutableListOf<LocalDate>()
        for ((date, day) in d.days.toSortedMap()) {
            total += day.seconds
            weekdays[date.dayOfWeek.value - 1] += day.seconds
            for (h in 0 until 24) hours[h] += day.hours.getOrElse(h) { 0.0 }
            if (!date.isBefore(today.minusDays(6))) week += day.seconds
            if (day.seconds > best) { best = day.seconds; bestDay = date }
            if (day.seconds >= DAY_COUNTS) watched += date
        }
        var longest = 0
        var run = 0
        watched.forEachIndexed { i, date ->
            run = if (i > 0 && watched[i - 1].plusDays(1) == date) run + 1 else 1
            longest = maxOf(longest, run)
        }
        var streak = 0
        var date = if (on(today) >= DAY_COUNTS) today else today.minusDays(1)
        while (on(date) >= DAY_COUNTS) { streak++; date = date.minusDays(1) }
        return StatsSummary(
            total = total, today = on(today), week = week, daysWatched = watched.size, streak = streak,
            longestStreak = longest, plays = d.videos.values.sumOf { it.plays },
            finished = d.videos.values.count { it.finished > 0 }, since = d.since,
            last30 = (29 downTo 0).map { back -> today.minusDays(back.toLong()).let { it to on(it) } },
            hours = hours, weekdays = weekdays, bestDay = bestDay, bestSeconds = best,
        )
    }

    // Now, before the app may be stopped.
    fun flush() {
        if (saveJob?.isActive != true) return
        saveJob?.cancel()
        val snapshot = _data.value
        scope.launch(Dispatchers.IO) { write(snapshot) }
    }

    fun writeNow() {
        saveJob?.cancel()
        write(_data.value)
    }

    private fun change(block: (StatsData) -> StatsData) {
        _data.update(block)
        // Watch time comes in every few seconds: written a little after, not each time.
        saveJob?.cancel()
        saveJob = scope.launch(Dispatchers.IO) {
            delay(2000)
            write(_data.value)
        }
    }

    private fun load(): StatsData {
        val json = JsonFile.read(file, TAG) ?: return StatsData()
        val days = buildMap {
            val o = json.optJSONObject("days") ?: JSONObject()
            for (key in o.keys()) {
                val date = try { LocalDate.parse(key) } catch (e: Exception) { continue }
                val entry = o.optJSONObject(key) ?: continue
                val h = entry.optJSONObject("h") ?: JSONObject()
                put(date, DayStats(entry.optDouble("s", 0.0), List(24) { h.optDouble(it.toString(), 0.0) }))
            }
        }
        val videos = buildMap {
            val o = json.optJSONObject("videos") ?: JSONObject()
            for (key in o.keys()) {
                val entry = o.optJSONObject(key) ?: continue
                put(key, VideoStats(entry.optInt("plays"), entry.optInt("done")))
            }
        }
        return StatsData(json.optLong("since"), days, videos)
    }

    @Synchronized
    private fun write(d: StatsData) {
        val days = JSONObject()
        for ((date, day) in d.days) {
            val hours = JSONObject()
            day.hours.forEachIndexed { h, s -> if (s > 0) hours.put(h.toString(), s) }
            days.put(date.toString(), JSONObject().put("s", day.seconds).put("h", hours))
        }
        val videos = JSONObject()
        for ((key, v) in d.videos) {
            val entry = JSONObject().put("plays", v.plays)
            if (v.finished > 0) entry.put("done", v.finished)
            videos.put(key, entry)
        }
        val json = JSONObject().put("days", days).put("videos", videos)
        if (d.since > 0) json.put("since", d.since)
        JsonFile.write(file, json, TAG)
    }

    companion object {
        private const val TAG = "WatchStats"
        // A day counts as one with watching in it (for streaks) from a minute on.
        const val DAY_COUNTS = 60.0

        // The local date of `ms`.
        fun dateOf(ms: Long): LocalDate = java.time.Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()
    }
}
