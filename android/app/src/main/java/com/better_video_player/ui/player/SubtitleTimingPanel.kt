package com.better_video_player.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.better_video_player.VesselViewModel
import com.better_video_player.delayText
import com.better_video_player.ui.AppIcons
import com.better_video_player.ui.IconBtn
import com.better_video_player.ui.Palette

// Subtitle timing (Subtitles > Timing in the player's settings), as on the desktop: a panel at
// the top of the video, leaving the subtitles at the bottom in view while they're moved
// earlier or later in steps of a tenth of a second or a whole second. Reset goes back to the
// file's own timing. With play/pause, so a line can be stopped on to compare.
@Composable
fun SubtitleTimingPanel(vm: VesselViewModel, watch: WatchState, ui: PlayerUiState, modifier: Modifier = Modifier) {
    val timing by vm.subtitleTiming.collectAsStateWithLifecycle()
    val on = timing.subtitlesOn
    val moved = on && timing.delayMs != 0L

    Column(
        modifier
            .padding(horizontal = 12.dp, vertical = if (watch.full) 16.dp else 8.dp)
            .widthIn(max = 420.dp)
            .background(Color(0xF21C1C1C), RoundedCornerShape(16.dp))
            .border(1.dp, Palette.border, RoundedCornerShape(16.dp))
            // Taps on the panel itself don't reach the video (which would close it).
            .clickable(remember { MutableInteractionSource() }, indication = null) {}
            .padding(start = 14.dp, end = 4.dp, top = 2.dp, bottom = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Subtitle timing", Modifier.weight(1f), color = Palette.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text("Reset",
                 Modifier.clickable(enabled = moved) { vm.resetSubtitleTiming() }.padding(horizontal = 8.dp, vertical = 8.dp),
                 color = if (moved) Palette.accent else Palette.textTertiary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            IconBtn(if (ui.isPlaying) AppIcons.pause else AppIcons.play, onClick = { vm.togglePlay() }, size = 40.dp,
                    iconSize = 22.dp, contentDescription = if (ui.isPlaying) "Pause" else "Play")
            IconBtn(AppIcons.close, onClick = { watch.timingOpen = false }, size = 40.dp, iconSize = 20.dp,
                    contentDescription = "Close")
        }
        Row(Modifier.padding(end = 10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TimingStep("−1 s", on) { vm.shiftSubtitles(-1000) }
            TimingStep("−0.1", on) { vm.shiftSubtitles(-100) }
            Text(if (on) delayText(timing.delayMs) else "Subtitles are off",
                 Modifier.weight(1f), color = if (moved) Palette.accent else Palette.textSecondary,
                 fontSize = 14.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 1)
            TimingStep("+0.1", on) { vm.shiftSubtitles(100) }
            TimingStep("+1 s", on) { vm.shiftSubtitles(1000) }
        }
    }
}

@Composable
private fun TimingStep(text: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.height(36.dp)
            .clip(CircleShape)
            .background(Palette.control)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (enabled) Palette.textPrimary else Palette.textTertiary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}
