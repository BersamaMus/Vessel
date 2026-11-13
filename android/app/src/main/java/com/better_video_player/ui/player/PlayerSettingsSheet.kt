package com.better_video_player.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.better_video_player.VesselViewModel
import com.better_video_player.ui.AppIcon
import com.better_video_player.ui.AppIcons
import com.better_video_player.ui.Palette
import com.better_video_player.engine.TrackType

// Playback speeds offered, as on YouTube. The speed carries on to the next video.
private val speeds = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

private fun speedText(speed: Float) = if (speed == 1f) "Normal" else "${speed.toString().removeSuffix(".0")}×"

// The player's settings, from the gear on the video: speed, audio track, subtitles, repeat
// and autoplay. Each list opens in place of the main one, as in YouTube's sheet.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerSettingsSheet(vm: VesselViewModel, watch: WatchState, ui: PlayerUiState, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var page by remember { mutableStateOf("main") }
    val autoplay by vm.settings.autoplay.collectAsStateWithLifecycle()
    val audio = ui.audioTracks
    val subtitles = ui.subtitleTracks
    val subtitleOn = subtitles.firstOrNull { it.selected }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Palette.dialog) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            when (page) {
                "main" -> {
                    SheetRow(AppIcons.speed, "Playback speed", speedText(ui.speed)) { page = "speed" }
                    if (audio.size > 1)
                        SheetRow(AppIcons.audiotrack, "Audio track", audio.firstOrNull { it.selected }?.label ?: "") { page = "audio" }
                    SheetRow(AppIcons.subtitles, "Subtitles",
                             if (subtitles.isEmpty()) "None in this video" else subtitleOn?.label ?: "Off") {
                        if (subtitles.isNotEmpty()) page = "subtitles"
                    }
                    SheetSwitch(AppIcons.repeat, "Repeat this video", ui.repeat) {
                        vm.setRepeat(!ui.repeat)
                        watch.flashText = if (ui.repeat) "Repeat off" else "Repeat on"
                    }
                    SheetSwitch(AppIcons.play, "Autoplay", autoplay, "Play the next video in the folder") {
                        vm.setAutoplay(!autoplay)
                    }
                }
                "speed" -> {
                    SheetTitle("Playback speed") { page = "main" }
                    for (speed in speeds) {
                        SheetChoice(speedText(speed), ui.speed == speed) {
                            vm.setSpeed(speed)
                            watch.flashText = if (speed == 1f) "Normal speed" else "Speed ${speedText(speed)}"
                            onDismiss()
                        }
                    }
                }
                "audio" -> {
                    SheetTitle("Audio track") { page = "main" }
                    for (choice in audio) {
                        SheetChoice(choice.label, choice.selected) {
                            vm.selectTrack(TrackType.Audio, choice)
                            watch.flashText = "Audio: ${choice.label}"
                            onDismiss()
                        }
                    }
                }
                "subtitles" -> {
                    SheetTitle("Subtitles") { page = "main" }
                    SheetChoice("Off", subtitleOn == null) {
                        vm.selectTrack(TrackType.Subtitle, null)
                        watch.flashText = "Subtitles off"
                        onDismiss()
                    }
                    for (choice in subtitles) {
                        SheetChoice(choice.label, choice.selected) {
                            vm.selectTrack(TrackType.Subtitle, choice)
                            watch.flashText = "Subtitles: ${choice.label}"
                            onDismiss()
                        }
                    }
                }
            }
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars).height(16.dp))
        }
    }
}

@Composable
private fun SheetRow(icon: ImageVector, title: String, value: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        AppIcon(icon, tint = Palette.textPrimary)
        Text(title, Modifier.weight(1f), color = Palette.textPrimary, fontSize = 15.sp)
        Text(value, color = Palette.textSecondary, fontSize = 14.sp, maxLines = 1)
        AppIcon(AppIcons.chevronRight, tint = Palette.textTertiary, size = 20.dp)
    }
}

@Composable
private fun SheetSwitch(icon: ImageVector, title: String, checked: Boolean, subtitle: String? = null, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        AppIcon(icon, tint = Palette.textPrimary)
        Column(Modifier.weight(1f)) {
            Text(title, color = Palette.textPrimary, fontSize = 15.sp)
            if (subtitle != null) Text(subtitle, color = Palette.textSecondary, fontSize = 12.sp)
        }
        Switch(checked = checked, onCheckedChange = { onToggle() }, colors = switchColors())
    }
}

@Composable
fun switchColors() = SwitchDefaults.colors(
    checkedThumbColor = Palette.accentText,
    checkedTrackColor = Palette.accent,
    uncheckedThumbColor = Palette.textSecondary,
    uncheckedTrackColor = Palette.control,
    uncheckedBorderColor = Palette.border,
)

@Composable
private fun SheetTitle(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onBack).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AppIcon(AppIcons.back, tint = Palette.textPrimary)
        Text(title, color = Palette.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SheetChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        if (selected) AppIcon(AppIcons.check, tint = Palette.accent) else Spacer(Modifier.size(24.dp))
        Text(label, color = if (selected) Palette.accent else Palette.textPrimary, fontSize = 15.sp)
    }
}
