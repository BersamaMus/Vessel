package com.better_video_player.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.better_video_player.VesselViewModel
import com.better_video_player.data.WatchStats
import com.better_video_player.ui.AppIcons
import com.better_video_player.ui.EmptyNote
import com.better_video_player.ui.Metrics
import com.better_video_player.ui.Palette
import com.better_video_player.ui.TextAction
import java.time.DayOfWeek
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

// "Your stats", as on the desktop (StatsPage.qml): how long you've spent watching, today, this
// week and all time, your streak, videos played and finished, your biggest day, and bar charts
// of the last 30 days, the hours of the day and the days of the week. Tap a bar for its time.
@Composable
fun StatsScreen(vm: VesselViewModel, bottomPadding: Dp, onBack: () -> Unit) {
    val data by vm.stats.data.collectAsStateWithLifecycle()
    val s = remember(data) { vm.stats.summary() }
    val scroll = rememberScrollState()
    var confirmReset by remember { mutableStateOf(false) }
    val locale = Locale.getDefault()

    Column(Modifier.fillMaxSize()) {
        TitleBar("Your stats", if (s.since > 0) "Since ${WatchStats.dateOf(s.since).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))}" else "",
                 scroll.value > 0, onBack = onBack) {
            if (s.total > 0) TextAction("Reset", { confirmReset = true })
        }
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = Metrics.pagePadding).padding(bottom = bottomPadding + 24.dp)) {
            if (s.total <= 0) {
                EmptyNote("Watch something, and how much you watch, and when, shows up here.", Modifier.padding(top = 16.dp),
                          icon = AppIcons.barChart)
                return@Column
            }
            // "Night owl: most of your watching happens late at night."
            kindOf(s.hours, s.total)?.let { (title, text) ->
                Text(title, Modifier.padding(top = 12.dp), color = Palette.accent, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
                Text(text, color = Palette.textSecondary, fontSize = 14.sp)
            }

            Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Tile("Today", span(s.today), Modifier.weight(1f))
                Tile("Last 7 days", span(s.week), Modifier.weight(1f))
                Tile("All time", span(s.total), Modifier.weight(1f))
            }
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Tile("Streak", days(s.streak), Modifier.weight(1f), note = "Longest: ${days(s.longestStreak)}")
                Tile("Videos played", "${s.plays}", Modifier.weight(1f), note = "${s.finished} to the end")
            }
            s.bestDay?.let { best ->
                Tile("Biggest day", span(s.bestSeconds), Modifier.padding(top = 10.dp).fillMaxWidth(),
                     note = best.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)))
            }

            Chart("Last 30 days", s.last30.map { it.second },
                  tips = s.last30.map { it.first.format(DateTimeFormatter.ofPattern("EEE d MMM", locale)) },
                  labels = mapOf(0 to s.last30.first().first.dayOfMonth.toString(), 14 to s.last30[14].first.dayOfMonth.toString(),
                                 29 to "Today"),
                  marked = 29)
            Chart("Hours of the day", s.hours, tips = (0 until 24).map { "%02d:00–%02d:00".format(it, (it + 1) % 24) },
                  labels = mapOf(0 to "0", 6 to "6", 12 to "12", 18 to "18", 23 to "23"))
            val weekdays = DayOfWeek.entries.map { it.getDisplayName(TextStyle.SHORT, locale) }
            Chart("Days of the week", s.weekdays, tips = DayOfWeek.entries.map { it.getDisplayName(TextStyle.FULL, locale) },
                  labels = weekdays.withIndex().associate { it.index to it.value })
            Text("It's the time that passes while a video plays: a minute at 2× is a minute. Clearing the history leaves these be.",
                 Modifier.padding(top = 20.dp), color = Palette.textTertiary, fontSize = 12.sp)
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            containerColor = Palette.dialog,
            title = { Text("Reset stats?") },
            text = { Text("Your watch time, streaks and plays start over from now. Your history stays.") },
            confirmButton = { TextAction("Reset", { vm.stats.clear(); confirmReset = false }) },
            dismissButton = { TextAction("Cancel", { confirmReset = false }) },
        )
    }
}

// What sort of watcher the hours make you, once there's enough to go on (10 minutes).
private fun kindOf(hours: List<Double>, total: Double): Pair<String, String>? {
    if (total < 600) return null
    val hour = hours.indices.maxByOrNull { hours[it] } ?: return null
    return when {
        hour >= 22 || hour < 5 -> "Night owl" to "Most of your watching happens late at night."
        hour < 11 -> "Early bird" to "You like a video with your morning."
        hour < 15 -> "Lunch-break watcher" to "The middle of the day is your time for videos."
        hour < 19 -> "Afternoon unwinder" to "You wind down with a video in the late afternoon."
        else -> "Prime-time viewer" to "Evenings are when you sit down to watch."
    }
}

// "2 h 5 min", "12 min", "Under a minute".
private fun span(seconds: Double): String {
    val minutes = (seconds / 60).toLong()
    return when {
        seconds <= 0 -> "0 min"
        minutes < 1 -> "Under a minute"
        minutes < 60 -> "$minutes min"
        else -> "${minutes / 60} h ${minutes % 60} min"
    }
}

private fun days(n: Int) = if (n == 1) "1 day" else "$n days"

@Composable
private fun Tile(label: String, value: String, modifier: Modifier = Modifier, note: String = "") {
    Column(modifier.background(Palette.surfaceHigh, RoundedCornerShape(Metrics.radiusCard)).padding(14.dp)) {
        Text(value, color = Palette.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(label, color = Palette.textSecondary, fontSize = 13.sp)
        if (note.isNotEmpty()) Text(note, Modifier.padding(top = 2.dp), color = Palette.textTertiary, fontSize = 12.sp)
    }
}

// Bars for `values` (seconds), labelled under some of them; a tap shows a bar's time.
@Composable
private fun Chart(title: String, values: List<Double>, tips: List<String>, labels: Map<Int, String>, marked: Int = -1) {
    var picked by remember(values) { mutableIntStateOf(-1) }
    val peak = values.maxOrNull()?.takeIf { it > 0 } ?: 1.0
    Text(title, Modifier.padding(top = 26.dp), color = Palette.textPrimary, fontSize = Metrics.subsectionSize, fontWeight = FontWeight.Bold)
    Text(if (picked >= 0) "${tips[picked]}  •  ${span(values[picked])}" else "Most: ${span(peak)}",
         color = Palette.textSecondary, fontSize = 13.sp)
    Canvas(Modifier.padding(top = 10.dp).fillMaxWidth().height(110.dp).pointerInput(values) {
        detectTapGestures { offset ->
            val i = (offset.x / size.width * values.size).toInt().coerceIn(0, values.lastIndex)
            picked = if (picked == i) -1 else i
        }
    }) {
        val slot = size.width / values.size
        val gap = (slot * 0.25f).coerceAtMost(6f)
        values.forEachIndexed { i, v ->
            val h = (v / peak * size.height).toFloat().coerceAtLeast(if (v > 0) 3f else 2f)
            val color = when {
                i == picked -> Palette.textPrimary
                v <= 0 -> Palette.control
                i == marked -> Palette.accent
                else -> Palette.accent.copy(alpha = 0.7f)
            }
            drawRoundRect(color, Offset(i * slot + gap / 2, size.height - h), Size(slot - gap, h), CornerRadius(3f))
        }
    }
    Box(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
            val slot: Dp = maxWidth / values.size
            for ((i, label) in labels) {
                Text(label, Modifier.padding(start = (slot * i).coerceAtMost(maxWidth - 36.dp)),
                     color = if (i == marked) Palette.textPrimary else Palette.textTertiary, fontSize = 11.sp,
                     fontWeight = if (i == marked) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
}
