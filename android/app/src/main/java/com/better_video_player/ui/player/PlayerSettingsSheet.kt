package com.better_video_player.ui.player

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.better_video_player.VesselViewModel
import com.better_video_player.ui.AppIcon
import com.better_video_player.ui.AppIcons
import com.better_video_player.ui.Palette
import com.better_video_player.data.OpenSubtitles
import com.better_video_player.data.SubtitleResult
import com.better_video_player.engine.TrackType
import kotlinx.coroutines.delay
import java.text.NumberFormat

// Playback speeds offered, as on YouTube. The speed carries on to the next video.
private val speeds = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

private fun speedText(speed: Float) = if (speed == 1f) "Normal" else "${speed.toString().removeSuffix(".0")}×"

// The player's settings, from the gear on the video: speed, audio track, subtitles, repeat
// and autoplay. Each list opens in place of the main one, as in YouTube's sheet. The
// subtitles list also adds subtitles from outside the video: searched for online
// (OpenSubtitles), or picked from the phone's files.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerSettingsSheet(vm: VesselViewModel, watch: WatchState, ui: PlayerUiState, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var page by remember { mutableStateOf("main") }
    val autoplay by vm.settings.autoplay.collectAsStateWithLifecycle()
    val audio = ui.audioTracks
    val subtitles = ui.subtitleTracks
    val subtitleOn = subtitles.firstOrNull { it.selected }
    val video by vm.current.collectAsStateWithLifecycle()

    // Online search: what to look for, in which language.
    var query by remember { mutableStateOf(video?.let(vm::subtitleQuery).orEmpty()) }
    var language by remember { mutableStateOf(vm.subtitleLanguage()) }
    DisposableEffect(Unit) { onDispose { vm.cancelSubtitleSearch() } }

    // A subtitle file from the phone's files. The sheet stays open until it's picked, so the
    // answer comes back here.
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            vm.addSubtitleFile(uri) { watch.flashText = it }
            onDismiss()
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Palette.dialog) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            when (page) {
                "main" -> {
                    SheetRow(AppIcons.speed, "Playback speed", speedText(ui.speed)) { page = "speed" }
                    if (audio.size > 1)
                        SheetRow(AppIcons.audiotrack, "Audio track", audio.firstOrNull { it.selected }?.label ?: "") { page = "audio" }
                    SheetRow(AppIcons.subtitles, "Subtitles",
                             if (subtitles.isEmpty()) "None in this video" else subtitleOn?.label ?: "Off") {
                        page = "subtitles"
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
                    if (vm.openSubtitles.available) {
                        SheetRow(AppIcons.search, "Search online", "") {
                            page = "search"
                            vm.searchSubtitles(query, language)
                        }
                    }
                    // Any type: Android doesn't know subtitle files' types.
                    SheetRow(AppIcons.folderOpen, "Choose a file", "") { pickFile.launch(arrayOf("*/*")) }
                }
                "search" -> {
                    SheetTitle("Search subtitles online") { page = "subtitles" }
                    OnlineSubtitles(vm, query, language,
                                    onQueryChange = { query = it },
                                    onLanguageClick = { page = "language" },
                                    onSubtitleOn = { result ->
                                        watch.flashText = "Subtitles: ${result.language}"
                                        onDismiss()
                                    })
                }
                "language" -> {
                    SheetTitle("Language") { page = "search" }
                    for (choice in OpenSubtitles.languages) {
                        SheetChoice(choice.name, choice.code == language) {
                            language = choice.code
                            page = "search"
                            vm.searchSubtitles(query, language)
                        }
                    }
                }
            }
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars).height(16.dp))
        }
    }
}

// "Search online": a title to search for (the video's, to begin with) and a language, then
// what OpenSubtitles found. Picking one downloads it and turns it on; one downloaded for this
// video before is marked, and picking it turns the saved file on again without using up a
// download. Once the day's free downloads are used up, picking one opens its page on the
// website instead, to download it there and add it with "Choose a file".
@Composable
private fun OnlineSubtitles(
    vm: VesselViewModel,
    query: String,
    language: String,
    onQueryChange: (String) -> Unit,
    onLanguageClick: () -> Unit,
    onSubtitleOn: (SubtitleResult) -> Unit,
) {
    val search by vm.subtitleSearch.collectAsStateWithLifecycle()
    val saved by vm.savedSubtitles.entries.collectAsStateWithLifecycle()
    val video by vm.current.collectAsStateWithLifecycle()
    val limitReset by vm.settings.subtitleLimitReset.collectAsStateWithLifecycle()
    val keyboard = LocalSoftwareKeyboardController.current
    val uriHandler = LocalUriHandler.current

    // The limit lifts by itself: checked once a minute, which also counts "in 6 hours" down.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(limitReset) {
        while (true) {
            now = System.currentTimeMillis()
            delay(60_000)
        }
    }
    val limitReached = limitReset > now
    val downloadedHere = saved[video?.key]

    fun run() {
        keyboard?.hide()
        vm.searchSubtitles(query, language)
    }

    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("Title") },
            singleLine = true,
            shape = CircleShape,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { run() }),
            trailingIcon = { IconBtnSmall(AppIcons.search) { run() } },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Palette.surfaceHigh, unfocusedContainerColor = Palette.surfaceHigh,
                focusedIndicatorColor = Palette.surfaceHigh, unfocusedIndicatorColor = Palette.surfaceHigh,
                cursorColor = Palette.accent,
            ),
        )
        Row(Modifier.background(Palette.surfaceHigh, CircleShape).clickable(onClick = onLanguageClick)
                .padding(start = 14.dp, end = 8.dp, top = 15.dp, bottom = 15.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(language.uppercase(), color = Palette.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            AppIcon(AppIcons.expandMore, tint = Palette.textSecondary, size = 20.dp)
        }
    }

    // Out of downloads for today: the way round it.
    if (limitReached) {
        val minutes = (limitReset - now) / 60_000 + 1
        val reset = if (minutes < 60) "More downloads in $minutes minute${if (minutes == 1L) "" else "s"}."
                    else ((minutes + 30) / 60).let { "More downloads in $it hour${if (it == 1L) "" else "s"}." }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                   .background(Palette.accentSoft, RoundedCornerShape(12.dp)).padding(16.dp),
               verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Daily download limit reached", color = Palette.accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text("Pick a subtitle to open it on OpenSubtitles.com and download it there, then add it with " +
                 "Choose a file. Ones you've downloaded before still turn on here. $reset",
                 color = Palette.textPrimary, fontSize = 13.sp)
        }
    }

    // Searching, what went wrong, or that nothing was found.
    val message = when {
        search.searching -> "Searching OpenSubtitles…"
        search.error.isNotEmpty() -> search.error
        search.searched && search.results.isEmpty() -> "No subtitles found. Try another title or language."
        else -> ""
    }
    if (message.isNotEmpty()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (search.searching) CircularProgressIndicator(Modifier.size(20.dp), color = Palette.accent, strokeWidth = 2.dp)
            Text(message, color = if (search.error.isNotEmpty() && !search.searching) Palette.error else Palette.textSecondary,
                 fontSize = 14.sp)
        }
    }

    val numbers = remember { NumberFormat.getIntegerInstance() }
    for (result in search.results) {
        val downloading = search.downloading == result.fileId
        val downloadedBefore = downloadedHere?.downloaded(result.fileId) != null
        val enabled = search.downloading == 0 || downloading
        Row(Modifier.fillMaxWidth()
                .clickable(enabled = enabled && !downloading) {
                    when {
                        downloadedBefore || !limitReached -> vm.downloadSubtitle(result) { onSubtitleOn(result) }
                        result.url.isNotEmpty() -> uriHandler.openUri(result.url)
                    }
                }
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            AppIcon(AppIcons.subtitles, tint = if (result.hashMatch) Palette.accent else Palette.textSecondary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(result.name, color = if (enabled) Palette.textPrimary else Palette.textTertiary, fontSize = 14.sp,
                     maxLines = 2, overflow = TextOverflow.Ellipsis)
                val marks = listOfNotNull(
                    "Downloaded".takeIf { downloadedBefore },
                    "Made for this file".takeIf { result.hashMatch },
                )
                val details = listOfNotNull(
                    result.language,
                    "${numbers.format(result.downloads)} downloads",
                    "Hearing impaired".takeIf { result.hearingImpaired },
                    "Machine translated".takeIf { result.machine },
                )
                if (marks.isNotEmpty())
                    Text(marks.joinToString("  •  "), color = Palette.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text(details.joinToString("  •  "), color = Palette.textTertiary, fontSize = 12.sp)
            }
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                when {
                    downloading -> CircularProgressIndicator(Modifier.size(20.dp), color = Palette.accent, strokeWidth = 2.dp)
                    downloadedBefore -> AppIcon(AppIcons.check, tint = Palette.accent)
                    limitReached -> AppIcon(AppIcons.openInNew, tint = Palette.textSecondary)
                    else -> AppIcon(AppIcons.download, tint = Palette.textSecondary)
                }
            }
        }
    }

    Text("Subtitles from OpenSubtitles.com. Searching sends the video's title and a fingerprint of the file, never the file itself.",
         Modifier.padding(horizontal = 20.dp, vertical = 12.dp), color = Palette.textTertiary, fontSize = 12.sp)
}

@Composable
private fun IconBtnSmall(icon: ImageVector, onClick: () -> Unit) {
    Box(Modifier.size(40.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        AppIcon(icon, tint = Palette.textSecondary)
    }
}

@Composable
private fun SheetRow(icon: ImageVector, title: String, value: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        AppIcon(icon, tint = Palette.textPrimary)
        Text(title, Modifier.weight(1f), color = Palette.textPrimary, fontSize = 15.sp)
        if (value.isNotEmpty()) Text(value, color = Palette.textSecondary, fontSize = 14.sp, maxLines = 1)
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
