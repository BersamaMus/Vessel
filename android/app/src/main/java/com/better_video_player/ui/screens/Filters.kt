package com.better_video_player.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.better_video_player.data.MIN_PROGRESS
import com.better_video_player.data.Seen
import com.better_video_player.data.Video
import com.better_video_player.ui.AppIcons
import com.better_video_player.ui.IconBtn
import com.better_video_player.ui.LocalSeen
import com.better_video_player.ui.Palette
import com.better_video_player.ui.TextAction

// The Videos view's filters, as on the desktop (FilterBar.qml): length, progress, file type
// and resolution. "" for each means any.
@Immutable
data class VideoFilter(val length: String = "", val progress: String = "", val type: String = "", val resolution: String = "") {
    val count: Int get() = listOf(length, progress, type, resolution).count { it.isNotEmpty() }

    companion object {
        val saver = Saver<VideoFilter, List<String>>(
            save = { listOf(it.length, it.progress, it.type, it.resolution) },
            restore = { VideoFilter(it[0], it[1], it[2], it[3]) },
        )
    }
}

// The buckets, as the desktop's lengthOf(), progressOf() and resolutionOf().
fun lengthOf(v: Video): String {
    val s = v.durationMs / 1000
    return when {
        s <= 0 -> ""
        s < 5 * 60 -> "short"
        s < 30 * 60 -> "medium"
        s < 90 * 60 -> "long"
        else -> "feature"
    }
}

fun progressOf(seen: Seen?): String = when {
    seen?.finished == true -> "watched"
    seen != null && seen.position >= MIN_PROGRESS -> "started"
    else -> "unwatched"
}

// By width as well, so a widescreen film (1920 × 800) still counts as 1080p.
fun resolutionOf(v: Video): String {
    val w = maxOf(v.width, v.height)
    val h = minOf(v.width, v.height)
    return when {
        w <= 0 || h <= 0 -> ""
        w >= 3200 || h >= 2000 -> "4k"
        w >= 2400 || h >= 1400 -> "1440"
        w >= 1800 || h >= 1000 -> "1080"
        w >= 1200 || h >= 700 -> "720"
        else -> "sd"
    }
}

@Composable
fun filterVideos(videos: List<Video>, filter: VideoFilter): List<Video> {
    val seen = LocalSeen.current
    return remember(videos, filter, if (filter.progress.isNotEmpty()) seen else null) {
        if (filter.count == 0) videos
        else videos.filter { v ->
            (filter.length.isEmpty() || lengthOf(v) == filter.length) &&
                (filter.progress.isEmpty() || progressOf(seen[v.key]) == filter.progress) &&
                (filter.type.isEmpty() || v.type == filter.type) &&
                (filter.resolution.isEmpty() || resolutionOf(v) == filter.resolution)
        }
    }
}

private val lengths = listOf("" to "Any length", "short" to "Under 5 minutes", "medium" to "5–30 minutes",
                             "long" to "30–90 minutes", "feature" to "Over 90 minutes")
private val progresses = listOf("" to "Any progress", "unwatched" to "Not watched", "started" to "In progress", "watched" to "Watched")
private val resolutions = listOf("" to "Any resolution", "4k" to "4K", "1440" to "1440p", "1080" to "1080p",
                                 "720" to "720p", "sd" to "Below 720p")

// The filter button (accent while any filter is on) and its sheet. `videos`: what's being
// filtered, for the file types to offer.
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FilterButton(filter: VideoFilter, videos: List<Video>, onChange: (VideoFilter) -> Unit) {
    var open by remember { mutableStateOf(false) }
    IconBtn(AppIcons.filter, onClick = { open = true }, tint = if (filter.count > 0) Palette.accent else Palette.textPrimary,
            contentDescription = "Filter")
    if (!open) return
    val types = remember(videos) { videos.map { it.type }.filter { it.isNotEmpty() }.distinct().sorted() }

    ModalBottomSheet(onDismissRequest = { open = false }, containerColor = Palette.dialog) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Filter", Modifier.weight(1f), color = Palette.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                if (filter.count > 0) TextAction("Clear", { onChange(VideoFilter()) })
            }
            FilterGroup("Length", lengths, filter.length) { onChange(filter.copy(length = it)) }
            FilterGroup("Progress", progresses, filter.progress) { onChange(filter.copy(progress = it)) }
            FilterGroup("Type", listOf("" to "Any type") + types.map { it to it.uppercase() }, filter.type) { onChange(filter.copy(type = it)) }
            FilterGroup("Resolution", resolutions, filter.resolution) { onChange(filter.copy(resolution = it)) }
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars).height(20.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterGroup(title: String, options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Text(title, Modifier.padding(top = 18.dp, bottom = 8.dp), color = Palette.textSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((value, label) in options) Chip(label, value == selected) { onSelect(value) }
    }
}

// A pill to pick, filled while picked.
@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(Modifier.background(if (selected) Palette.accent else Palette.surfaceHigh, CircleShape)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp)) {
        Text(text, color = if (selected) Palette.accentText else Palette.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}
