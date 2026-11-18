package com.better_video_player.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.better_video_player.BuildConfig
import com.better_video_player.VesselViewModel
import com.better_video_player.data.countText
import com.better_video_player.data.dayHeading
import com.better_video_player.data.durationText
import com.better_video_player.data.remainingText
import com.better_video_player.data.searchKey
import com.better_video_player.ui.AppIcon
import com.better_video_player.ui.AppIcons
import com.better_video_player.ui.EmptyNote
import com.better_video_player.ui.IconBtn
import com.better_video_player.ui.LocalAppActions
import com.better_video_player.ui.LogoMark
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.better_video_player.ui.Metrics
import com.better_video_player.ui.Palette
import com.better_video_player.ui.PillButton
import com.better_video_player.ui.TextAction
import com.better_video_player.ui.VideoRow
import com.better_video_player.ui.player.switchColors

// Search by title and folder name, ignoring case, accents and punctuation. Titles that start
// with what was typed come first, then titles that contain it, then videos whose title and
// folders contain every word ("house season 2").
@Composable
fun SearchScreen(vm: VesselViewModel, bottomPadding: Dp, onBack: () -> Unit) {
    val index by vm.index.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val list = rememberLazyListState()
    LaunchedEffect(Unit) { focus.requestFocus() }

    val recent by vm.settings.recentSearches.collectAsStateWithLifecycle()
    val actions = LocalAppActions.current
    var sort by rememberSaveable { mutableStateOf(VideoSort.Relevance) }
    var filter by rememberSaveable(stateSaver = VideoFilter.saver) { mutableStateOf(VideoFilter()) }

    val keyed = remember(index) { index.videos.map { Triple(it, searchKey(it.title), searchKey(it.title + " " + it.relativePath)) } }
    val matches = remember(keyed, query) {
        val q = searchKey(query)
        if (q.isEmpty()) return@remember emptyList()
        val words = q.split(' ')
        val starts = keyed.filter { it.second.startsWith(q) }
        val contains = keyed.filter { !it.second.startsWith(q) && it.second.contains(q) }
        val allWords = keyed.filter { !it.second.contains(q) && words.all { w -> it.third.contains(w) } }
        (starts + contains + allWords).map { it.first }
    }
    val results = sortVideos(filterVideos(matches, filter), sort)

    // Remembered once it's been used: searched for, or a result played.
    fun keepSearch() = vm.settings.addRecentSearch(query)

    Column(Modifier.fillMaxSize()) {
        PageBar(list.canScrollBackward) {
            Row(Modifier.padding(start = 4.dp, end = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBtn(AppIcons.back, onClick = onBack, contentDescription = "Back")
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f).focusRequester(focus),
                    placeholder = { Text("Search videos and folders") },
                    singleLine = true,
                    shape = CircleShape,
                    trailingIcon = if (query.isNotEmpty()) ({ IconBtn(AppIcons.close, onClick = { query = "" }, tint = Palette.textSecondary) }) else null,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); keepSearch() }),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Palette.surfaceHigh, unfocusedContainerColor = Palette.surfaceHigh,
                        focusedIndicatorColor = Palette.surfaceHigh, unfocusedIndicatorColor = Palette.surfaceHigh,
                        cursorColor = Palette.accent,
                    ),
                )
            }
        }
        if (query.isNotBlank()) {
            Row(Modifier.fillMaxWidth().padding(start = Metrics.pagePadding, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(countText(results.size, "video", "videos"), Modifier.weight(1f), color = Palette.textSecondary, fontSize = 14.sp)
                FilterButton(filter, matches) { filter = it }
                SortButton(VideoSort.entries.map { it to it.label }, sort) { sort = it }
            }
        }
        if (query.isNotBlank() && results.isEmpty())
            EmptyNote(if (matches.isEmpty()) "Nothing matches “$query”." else "Nothing matches “$query” with these filters.",
                      Modifier.padding(top = 16.dp), icon = AppIcons.search)
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = bottomPadding + 24.dp)) {
            // Before typing: the last few searches.
            if (query.isBlank() && recent.isNotEmpty()) {
                item {
                    Text("Recent searches", Modifier.padding(start = 8.dp, top = 8.dp, bottom = 4.dp), color = Palette.textSecondary,
                         fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                items(recent, key = { "recent:$it" }) { text ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(Metrics.radiusArt)).clickable { query = text }
                            .padding(start = 12.dp, end = 0.dp, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        AppIcon(AppIcons.history, tint = Palette.textSecondary, size = 20.dp)
                        Text(text, Modifier.weight(1f), color = Palette.textPrimary, fontSize = 15.sp, maxLines = 1,
                             overflow = TextOverflow.Ellipsis)
                        IconBtn(AppIcons.close, onClick = { vm.settings.removeRecentSearch(text) }, tint = Palette.textTertiary,
                                iconSize = 18.dp, contentDescription = "Remove")
                    }
                }
            }
            items(results, key = { it.key }) { v ->
                VideoRow(v, listOf(v.folderName, durationText(v.durationMs)).filter { it.isNotEmpty() }.joinToString("  •  "),
                         Modifier.fillMaxWidth(), thumbWidth = 140.dp, onClick = { keepSearch(); actions.play(v) })
            }
        }
    }
}

// Everything watched that's still here, newest first, under the day it was watched.
@Composable
fun HistoryScreen(vm: VesselViewModel, bottomPadding: Dp, onBack: () -> Unit) {
    val watched by vm.watched.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    val groups = remember(watched) { watched.groupBy { dayHeading(it.second.watchedAt) } }

    Column(Modifier.fillMaxSize()) {
        TitleBar("History", countText(watched.size, "video", "videos"), list.canScrollBackward, onBack = onBack) {
            IconBtn(AppIcons.barChart, onClick = LocalAppActions.current::openStats, contentDescription = "Your stats")
            if (watched.isNotEmpty()) TextAction("Clear", { confirmClear = true })
        }
        if (watched.isEmpty())
            EmptyNote("Videos you watch show up here.", Modifier.padding(top = 16.dp), icon = AppIcons.history)
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(start = 8.dp, end = 4.dp, bottom = bottomPadding + 24.dp)) {
            for ((day, entries) in groups) {
                item(key = "day:$day") {
                    Text(day, Modifier.padding(start = 8.dp, top = 20.dp, bottom = 8.dp), color = Palette.textPrimary,
                         fontSize = Metrics.sectionSize, fontWeight = FontWeight.Bold)
                }
                items(entries, key = { it.first.key }) { (v, seen) ->
                    VideoRow(v, listOf(v.folderName, if (seen.finished) "Watched" else remainingText(seen.position, seen.duration))
                                    .filter { it.isNotEmpty() }.joinToString("  •  "),
                             Modifier.fillMaxWidth(), thumbWidth = 140.dp) {
                        IconBtn(AppIcons.close, onClick = { vm.removeFromHistory(v) }, tint = Palette.textSecondary,
                                contentDescription = "Remove from history")
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            containerColor = Palette.dialog,
            title = { Text("Clear history?") },
            text = { Text("Every video's progress is forgotten, and Continue watching is emptied. Your videos stay where they are, " +
                          "and so do your stats.") },
            confirmButton = { TextAction("Clear", { vm.clearHistory(); confirmClear = false }) },
            dismissButton = { TextAction("Cancel", { confirmClear = false }) },
        )
    }
}

@Composable
fun SettingsScreen(vm: VesselViewModel, bottomPadding: Dp, onOpenHistory: () -> Unit) {
    val index by vm.index.collectAsStateWithLifecycle()
    val scanning by vm.library.scanning.collectAsStateWithLifecycle()
    val skip by vm.settings.skipSeconds.collectAsStateWithLifecycle()
    val autoplay by vm.settings.autoplay.collectAsStateWithLifecycle()
    val alwaysMpv by vm.settings.alwaysMpv.collectAsStateWithLifecycle()
    val backgroundPlay by vm.settings.backgroundPlay.collectAsStateWithLifecycle()
    val pictureInPicture by vm.settings.pictureInPicture.collectAsStateWithLifecycle()
    val checkForUpdates by vm.settings.checkForUpdates.collectAsStateWithLifecycle()
    val scroll = rememberScrollState()

    Column(Modifier.fillMaxSize()) {
        TitleBar("Settings", scrolled = scroll.value > 0)
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = Metrics.pagePadding)
                   .padding(bottom = bottomPadding + 24.dp)) {
            SectionTitle("Library")
            Note("Vessel lists the videos Android knows about on this phone and its SD card: " +
                 "camera recordings, downloads, and anything copied in. New ones show up by themselves.")
            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${countText(index.videos.size, "video", "videos")} in ${countText(index.folders.size, "folder", "folders")}",
                     Modifier.weight(1f), color = Palette.textPrimary, fontSize = 15.sp)
                PillButton(if (scanning) "Scanning…" else "Rescan", AppIcons.refresh, { vm.library.refresh() }, soft = true)
            }

            SectionTitle("Player")
            Text("Double-tap skip", color = Palette.textPrimary, fontSize = 15.sp)
            Note("How far a double tap on the side of the video skips back or forward.")
            FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (seconds in listOf(5, 10, 15, 30)) {
                    val on = seconds == skip
                    Box(Modifier.background(if (on) Palette.accent else Palette.surfaceHigh, CircleShape)
                            .clickable { vm.settings.setSkipSeconds(seconds) }
                            .padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text("$seconds seconds", color = if (on) Palette.accentText else Palette.textPrimary,
                             fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Row(Modifier.padding(top = 20.dp).clickable { vm.setAutoplay(!autoplay) }, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Autoplay", color = Palette.textPrimary, fontSize = 15.sp)
                    Note("When a video ends, play the next one in its folder after a short countdown.")
                }
                Switch(autoplay, { vm.setAutoplay(it) }, colors = switchColors())
            }
            Row(Modifier.padding(top = 20.dp).clickable { vm.settings.setPictureInPicture(!pictureInPicture) }, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Picture-in-picture", color = Palette.textPrimary, fontSize = 15.sp)
                    Note("Leaving Vessel while a video plays keeps it going in a small window over other apps.")
                }
                Switch(pictureInPicture, { vm.settings.setPictureInPicture(it) }, colors = switchColors())
            }
            Row(Modifier.padding(top = 20.dp).clickable { vm.settings.setBackgroundPlay(!backgroundPlay) }, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Background playback", color = Palette.textPrimary, fontSize = 15.sp)
                    Note("Keep the sound playing when the screen is off, or when you leave Vessel with picture-in-picture off. " +
                         "Controls are in the notification.")
                }
                Switch(backgroundPlay, { vm.settings.setBackgroundPlay(it) }, colors = switchColors())
            }
            Row(Modifier.padding(top = 20.dp).clickable { vm.settings.setAlwaysMpv(!alwaysMpv) }, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Always use mpv", color = Palette.textPrimary, fontSize = 15.sp)
                    Note("Vessel plays videos with Android's own decoders, and switches to mpv by itself for the ones they can't play. " +
                         "Turn this on to play everything with mpv.")
                }
                Switch(alwaysMpv, { vm.settings.setAlwaysMpv(it) }, colors = switchColors())
            }

            SubtitleSettings(vm)

            SectionTitle("History")
            LinkRow(AppIcons.history, "Watch history", onOpenHistory)
            Spacer(Modifier.height(8.dp))
            LinkRow(AppIcons.barChart, "Your stats", LocalAppActions.current::openStats)

            SectionTitle("Updates")
            Row(Modifier.clickable { vm.settings.setCheckForUpdates(!checkForUpdates) }, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Check for updates", color = Palette.textPrimary, fontSize = 15.sp)
                    Note("Each time Vessel opens, it asks GitHub whether a newer version is out. Nothing about you or your videos is sent.")
                }
                Switch(checkForUpdates, { vm.settings.setCheckForUpdates(it) }, colors = switchColors())
            }

            SectionTitle("About")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LogoMark(40.dp)
                Column {
                    Text("Vessel ${BuildConfig.VERSION_NAME}", color = Palette.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Note("Plays with Media3 ExoPlayer, and with mpv (libmpv, FFmpeg) for what ExoPlayer can't.")
                }
            }
        }
    }
}

@Composable
private fun LinkRow(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(Metrics.radiusCard)).background(Palette.surfaceHigh).clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        AppIcon(icon, tint = Palette.accent)
        Text(text, Modifier.weight(1f), color = Palette.textPrimary, fontSize = 15.sp)
        AppIcon(AppIcons.chevronRight, tint = Palette.textTertiary)
    }
}

// Settings > Subtitles: how they look (as on the desktop, with a preview), and the folders
// Vessel may look in for subtitle files next to videos.
@Composable
private fun SubtitleSettings(vm: VesselViewModel) {
    val style by vm.settings.subtitleStyle.collectAsStateWithLifecycle()
    val folders by vm.settings.subtitleFolders.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Android's folder picker; the access is kept across restarts.
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                vm.settings.setSubtitleFolders((folders + uri.toString()).distinct())
            } catch (e: SecurityException) {
                vm.toast("Couldn't get access to that folder")
            }
        }
    }

    SectionTitle("Subtitles")
    Note("How subtitles look. Styled subtitles (.ass) keep their own colours, but follow the size.")
    // What they'll look like.
    Box(Modifier.padding(top = 14.dp).fillMaxWidth().height(110.dp).clip(RoundedCornerShape(Metrics.radiusCard))
            .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xFF3A4A5A), Color(0xFF1A2228)))),
        contentAlignment = Alignment.BottomCenter) {
        val box = style.background == "box"
        val textColor = if (style.yellow) Color(0xFFFFE14D) else Color.White
        Text("I'll see you tomorrow.",
             Modifier.padding(bottom = 14.dp).then(if (box) Modifier.background(Color(0xC0000000), RoundedCornerShape(4.dp)) else Modifier)
                 .padding(horizontal = 8.dp, vertical = 3.dp),
             color = textColor, fontSize = (18 * style.scale).sp, fontWeight = FontWeight.SemiBold,
             style = androidx.compose.ui.text.TextStyle(
                 shadow = when (style.background) {
                     "outline" -> androidx.compose.ui.graphics.Shadow(Color.Black, blurRadius = 6f)
                     "none" -> androidx.compose.ui.graphics.Shadow(Color.Black, androidx.compose.ui.geometry.Offset(2f, 2f), 3f)
                     else -> null
                 }))
    }
    ChoiceRow("Size", listOf("small" to "Small", "medium" to "Medium", "large" to "Large", "xlarge" to "Extra large"), style.size) {
        vm.settings.setSubtitleStyle(style.copy(size = it))
    }
    ChoiceRow("Colour", listOf("white" to "White", "yellow" to "Yellow"), style.color) {
        vm.settings.setSubtitleStyle(style.copy(color = it))
    }
    ChoiceRow("Background", listOf("outline" to "Outline", "box" to "Dark box", "none" to "None"), style.background) {
        vm.settings.setSubtitleStyle(style.copy(background = it))
    }

    Text("Folders to look in", Modifier.padding(top = 22.dp), color = Palette.textPrimary, fontSize = 15.sp)
    Note("Vessel offers a subtitle file next to a video (\"Movie.en.srt\", or one in a Subs folder) when it plays, " +
         "as on the desktop. Android doesn't let it see those files by itself: add the folders your videos are in " +
         "(Movies, Download...) and it looks in them and the folders inside.")
    for (folder in folders) {
        Row(Modifier.padding(top = 8.dp).fillMaxWidth().background(Palette.surfaceHigh, RoundedCornerShape(Metrics.radiusArt))
                .padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(AppIcons.folder, tint = Palette.accent, size = 20.dp)
            Text(folderLabel(folder), Modifier.weight(1f).padding(start = 12.dp), color = Palette.textPrimary, fontSize = 14.sp,
                 maxLines = 1, overflow = TextOverflow.Ellipsis)
            IconBtn(AppIcons.close, onClick = {
                try {
                    context.contentResolver.releasePersistableUriPermission(Uri.parse(folder), Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: SecurityException) {}
                vm.settings.setSubtitleFolders(folders - folder)
            }, tint = Palette.textSecondary, contentDescription = "Remove")
        }
    }
    Row(Modifier.padding(top = 10.dp)) {
        PillButton("Add a folder", AppIcons.add, { pickFolder.launch(null) }, soft = true)
    }
}

// "Phone storage/Movies", from a folder's tree URI ("…/tree/primary%3AMovies").
private fun folderLabel(tree: String): String {
    val id = try { android.provider.DocumentsContract.getTreeDocumentId(Uri.parse(tree)) } catch (e: Exception) { return tree }
    val root = id.substringBefore(':')
    return ((if (root == "primary") "Phone storage" else "SD card") + "/" + id.substringAfter(':', "")).trimEnd('/')
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ChoiceRow(title: String, options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Text(title, Modifier.padding(top = 16.dp, bottom = 8.dp), color = Palette.textPrimary, fontSize = 15.sp)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((value, label) in options) Chip(label, value == selected) { onSelect(value) }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, Modifier.padding(top = 28.dp, bottom = 8.dp), color = Palette.textPrimary, fontSize = Metrics.sectionSize,
         fontWeight = FontWeight.Bold)
}

@Composable
private fun Note(text: String) {
    Text(text, Modifier.padding(top = 2.dp), color = Palette.textSecondary, fontSize = 13.sp, lineHeight = 18.sp)
}

// Before the library can be shown: ask for access to the phone's videos, or (if only some
// were allowed, on Android 14+) say so and offer to allow all.
@Composable
fun AccessScreen(denied: Boolean, partial: Boolean, onAllow: () -> Unit, onContinue: (() -> Unit)?) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
           verticalArrangement = Arrangement.Center) {
        LogoMark(72.dp)
        Spacer(Modifier.height(24.dp))
        Text("Your videos, all in one place", color = Palette.textPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold,
             textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        Text(
            if (partial) "Vessel can only see the videos you picked. Allow all videos to see everything on this phone."
            else "Vessel needs access to the videos on this phone to list and play them. Nothing leaves your phone.",
            color = Palette.textSecondary, fontSize = 15.sp, textAlign = TextAlign.Center, lineHeight = 21.sp,
        )
        Spacer(Modifier.height(24.dp))
        if (denied) {
            // Asked too often: Android won't show the prompt again; it's in the app's settings now.
            PillButton("Open settings", AppIcons.settings, {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
            })
            Spacer(Modifier.height(8.dp))
            Text("Then allow Photos and videos.", color = Palette.textTertiary, fontSize = 13.sp)
        } else {
            PillButton(if (partial) "Allow all videos" else "Allow access", null, onAllow)
        }
        if (onContinue != null) {
            Spacer(Modifier.height(8.dp))
            TextAction("Continue with the ones I picked", onContinue)
        }
    }
}
