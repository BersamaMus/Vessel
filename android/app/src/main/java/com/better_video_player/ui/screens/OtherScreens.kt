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
import com.better_video_player.ui.LogoMark
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

    val keyed = remember(index) { index.videos.map { Triple(it, searchKey(it.title), searchKey(it.title + " " + it.relativePath)) } }
    val results = remember(keyed, query) {
        val q = searchKey(query)
        if (q.isEmpty()) return@remember emptyList()
        val words = q.split(' ')
        val starts = keyed.filter { it.second.startsWith(q) }
        val contains = keyed.filter { !it.second.startsWith(q) && it.second.contains(q) }
        val allWords = keyed.filter { !it.second.contains(q) && words.all { w -> it.third.contains(w) } }
        (starts + contains + allWords).map { it.first }
    }

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
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Palette.surfaceHigh, unfocusedContainerColor = Palette.surfaceHigh,
                        focusedIndicatorColor = Palette.surfaceHigh, unfocusedIndicatorColor = Palette.surfaceHigh,
                        cursorColor = Palette.accent,
                    ),
                )
            }
        }
        if (query.isNotBlank() && results.isEmpty())
            EmptyNote("Nothing matches “$query”.", Modifier.padding(top = 16.dp), icon = AppIcons.search)
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = bottomPadding + 24.dp)) {
            items(results, key = { it.key }) { v ->
                VideoRow(v, listOf(v.folderName, durationText(v.durationMs)).filter { it.isNotEmpty() }.joinToString("  •  "),
                         Modifier.fillMaxWidth(), thumbWidth = 140.dp)
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
                        IconBtn(AppIcons.close, onClick = { vm.history.remove(v.key) }, tint = Palette.textSecondary,
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
            text = { Text("Every video's progress is forgotten, and Continue watching is emptied. Your videos stay where they are.") },
            confirmButton = { TextAction("Clear", { vm.history.clear(); confirmClear = false }) },
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

            SectionTitle("History")
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(Metrics.radiusCard)).background(Palette.surfaceHigh).clickable(onClick = onOpenHistory).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                AppIcon(AppIcons.history, tint = Palette.accent)
                Text("Watch history", Modifier.weight(1f), color = Palette.textPrimary, fontSize = 15.sp)
                AppIcon(AppIcons.chevronRight, tint = Palette.textTertiary)
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
