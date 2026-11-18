package com.better_video_player.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.better_video_player.VesselViewModel
import com.better_video_player.data.Playlist
import com.better_video_player.data.Video
import com.better_video_player.data.countText
import com.better_video_player.data.durationText
import com.better_video_player.ui.AppIcon
import com.better_video_player.ui.AppIcons
import com.better_video_player.ui.EmptyNote
import com.better_video_player.ui.IconBtn
import com.better_video_player.ui.LocalAppActions
import com.better_video_player.ui.Metrics
import com.better_video_player.ui.Palette
import com.better_video_player.ui.PillButton
import com.better_video_player.ui.TextAction
import com.better_video_player.ui.Thumbnail
import com.better_video_player.ui.VideoRow

// The Playlists tab, as on the desktop: the playlists made, the most recently added to first.
// A long press on one renames or deletes it.
@Composable
fun PlaylistsScreen(vm: VesselViewModel, bottomPadding: Dp) {
    val playlists by vm.playlists.all.collectAsStateWithLifecycle()
    val index by vm.index.collectAsStateWithLifecycle()
    val grid = rememberLazyGridState()
    var menuFor by remember { mutableStateOf<Playlist?>(null) }
    var creating by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        TitleBar("Playlists", countText(playlists.size, "playlist", "playlists"), grid.canScrollBackward) {
            IconBtn(AppIcons.add, onClick = { creating = true }, contentDescription = "New playlist")
        }
        if (playlists.isEmpty())
            EmptyNote("Save videos to a playlist with Save on the watch page, or Save to playlist in a video's menu (a long press).",
                      Modifier.padding(top = 16.dp), icon = AppIcons.playlistPlay)
        LazyVerticalGrid(
            GridCells.Adaptive(160.dp),
            Modifier.fillMaxSize(),
            state = grid,
            contentPadding = PaddingValues(start = Metrics.pagePadding, end = Metrics.pagePadding, top = 8.dp, bottom = bottomPadding + 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(playlists, key = { it.id }) { p ->
                val videos = remember(p, index) { p.keys.mapNotNull { index.byKey[it] } }
                PlaylistCard(p, videos, onLongClick = { menuFor = p })
            }
        }
    }

    menuFor?.let { p -> PlaylistMenu(vm, p, onDismiss = { menuFor = null }) }
    if (creating) PlaylistNameDialog("New playlist", "", onDismiss = { creating = false }) { vm.playlists.create(it) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaylistCard(playlist: Playlist, videos: List<Video>, onLongClick: () -> Unit) {
    val actions = LocalAppActions.current
    Column(Modifier.clip(RoundedCornerShape(Metrics.radiusCard))
               .combinedClickable(onClick = { actions.openPlaylist(playlist.id) }, onLongClick = onLongClick)) {
        val cover = videos.firstOrNull()
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            if (cover != null) Thumbnail(cover, Modifier.fillMaxSize())
            else Box(Modifier.fillMaxSize().background(Palette.surfaceHigh, RoundedCornerShape(Metrics.radiusCard)),
                     contentAlignment = Alignment.Center) { AppIcon(AppIcons.playlistPlay, tint = Palette.textTertiary, size = 32.dp) }
            // How many, on a band down the right, as YouTube shows a playlist.
            Column(Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(0.34f)
                       .background(Palette.badge, RoundedCornerShape(topEnd = Metrics.radiusCard, bottomEnd = Metrics.radiusCard)),
                   horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text("${videos.size}", color = Palette.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                AppIcon(AppIcons.playlistPlay, tint = Palette.textPrimary, size = 20.dp)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(playlist.name, color = Palette.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
             maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(countText(videos.size, "video", "videos"), color = Palette.textSecondary, fontSize = 13.sp,
             modifier = Modifier.padding(top = 2.dp, bottom = 4.dp))
    }
}

// One playlist: play it from the top, shuffled, or from any of its videos on; move videos up
// or down, or take them out (with Undo).
@Composable
fun PlaylistScreen(vm: VesselViewModel, id: String, bottomPadding: Dp, onBack: () -> Unit) {
    val actions = LocalAppActions.current
    val playlists by vm.playlists.all.collectAsStateWithLifecycle()
    val index by vm.index.collectAsStateWithLifecycle()
    val playlist = playlists.firstOrNull { it.id == id }
    val list = rememberLazyListState()
    var menuOpen by remember { mutableStateOf(false) }
    // The videos on the phone, with their place in the playlist (some may be gone).
    val videos = remember(playlist, index) {
        playlist?.keys?.mapIndexedNotNull { i, key -> index.byKey[key]?.let { i to it } } ?: emptyList()
    }

    Column(Modifier.fillMaxSize()) {
        TitleBar(playlist?.name ?: "Playlist", countText(videos.size, "video", "videos"), list.canScrollBackward, onBack = onBack) {
            if (playlist != null) IconBtn(AppIcons.more, onClick = { menuOpen = true }, contentDescription = "More")
        }
        if (playlist == null) {
            EmptyNote("This playlist was deleted.", Modifier.padding(top = 16.dp), icon = AppIcons.playlistPlay)
            return@Column
        }
        val playing = videos.map { it.second }
        LazyColumn(Modifier.fillMaxSize(), state = list,
                   contentPadding = PaddingValues(start = 8.dp, end = 4.dp, bottom = bottomPadding + 24.dp)) {
            item {
                Row(Modifier.padding(start = 8.dp, top = 8.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton("Play all", AppIcons.play, { actions.playAll(playlist.name, playing, false) })
                    PillButton("Shuffle", AppIcons.shuffle, { actions.playAll(playlist.name, playing, true) }, soft = true)
                }
            }
            if (videos.isEmpty()) {
                item { EmptyNote("No videos in this playlist yet.", icon = AppIcons.playlistPlay) }
            }
            itemsIndexed(videos, key = { _, (_, v) -> v.key }) { shown, (at, v) ->
                VideoRow(v, listOf(v.folderName, durationText(v.durationMs)).filter { it.isNotEmpty() }.joinToString("  •  "),
                         Modifier.fillMaxWidth(), thumbWidth = 120.dp,
                         onClick = { vm.playAll(playlist.name, playing, false, start = v) }) {
                    // Moved past its neighbours on the phone (not ones that are gone).
                    IconBtn(AppIcons.arrowUp, onClick = { vm.playlists.move(id, at, videos[shown - 1].first) },
                            enabled = shown > 0, tint = Palette.textSecondary, size = 36.dp, iconSize = 20.dp, contentDescription = "Move up")
                    IconBtn(AppIcons.arrowDown, onClick = { vm.playlists.move(id, at, videos[shown + 1].first) },
                            enabled = shown < videos.lastIndex, tint = Palette.textSecondary, size = 36.dp, iconSize = 20.dp,
                            contentDescription = "Move down")
                    IconBtn(AppIcons.close, onClick = { vm.removeFromPlaylist(id, v) }, tint = Palette.textSecondary,
                            size = 36.dp, iconSize = 20.dp, contentDescription = "Remove from playlist")
                }
            }
        }
    }

    if (menuOpen && playlist != null) PlaylistMenu(vm, playlist, onDismiss = { menuOpen = false }, onDeleted = onBack)
}

// Rename or delete a playlist (deleting leaves the videos where they are).
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaylistMenu(vm: VesselViewModel, playlist: Playlist, onDismiss: () -> Unit, onDeleted: () -> Unit = {}) {
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    when {
        renaming -> PlaylistNameDialog("Rename playlist", playlist.name, onDismiss = onDismiss) { vm.playlists.rename(playlist.id, it) }
        deleting -> AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = Palette.dialog,
            title = { Text("Delete “${playlist.name}”?") },
            text = { Text("The playlist goes; its videos stay where they are.") },
            confirmButton = { TextAction("Delete", { vm.deletePlaylist(playlist.id); onDismiss(); onDeleted() }) },
            dismissButton = { TextAction("Cancel", onDismiss) },
        )
        else -> ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Palette.dialog) {
            Text(playlist.name, Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = Palette.textPrimary,
                 fontSize = 16.sp, fontWeight = FontWeight.Bold)
            SheetItem(AppIcons.edit, "Rename") { renaming = true }
            SheetItem(AppIcons.delete, "Delete playlist") { deleting = true }
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars).height(12.dp))
        }
    }
}

// "Save to playlist": ticks the playlists the video is in; ticking or unticking one saves it
// there and then, and a new playlist can be made with the video already in it.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SaveToPlaylistSheet(vm: VesselViewModel, video: Video, onDismiss: () -> Unit) {
    val playlists by vm.playlists.all.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }

    if (creating) {
        PlaylistNameDialog("New playlist", "", onDismiss = onDismiss) { name ->
            vm.playlists.create(name, video.key)
            vm.toast("Saved to $name")
        }
        return
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Palette.dialog) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            Text("Save to playlist", Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = Palette.textPrimary,
                 fontSize = 16.sp, fontWeight = FontWeight.Bold)
            for (p in playlists) {
                val inIt = video.key in p.keys
                Row(Modifier.fillMaxWidth().clickable {
                        if (inIt) vm.playlists.remove(p.id, video.key) else vm.playlists.add(p.id, video.key)
                    }.padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(inIt, onCheckedChange = null, Modifier.padding(12.dp),
                             colors = CheckboxDefaults.colors(checkedColor = Palette.accent, checkmarkColor = Palette.accentText,
                                                              uncheckedColor = Palette.textSecondary))
                    Text(p.name, Modifier.weight(1f), color = Palette.textPrimary, fontSize = 15.sp, maxLines = 1,
                         overflow = TextOverflow.Ellipsis)
                }
            }
            SheetItem(AppIcons.add, "New playlist") { creating = true }
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars).height(12.dp))
        }
    }
}

@Composable
fun PlaylistNameDialog(title: String, initial: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    NameDialog(title, initial, "Name", if (initial.isEmpty()) "Create" else "Rename", onDismiss, onDone)
}

// A dialog asking for a name (a playlist's, a file's), with the text ready to type over.
@Composable
fun NameDialog(title: String, initial: String, placeholder: String, action: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var text by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(initial, androidx.compose.ui.text.TextRange(0, initial.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    fun done() {
        if (text.text.isBlank()) return
        onDone(text.text.trim())
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.dialog,
        title = { Text(title) },
        text = {
            TextField(text, { text = it }, Modifier.fillMaxWidth().focusRequester(focus), singleLine = true,
                      placeholder = { Text(placeholder) },
                      keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                      keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { done() }),
                      colors = TextFieldDefaults.colors(focusedContainerColor = Palette.surfaceHigh, unfocusedContainerColor = Palette.surfaceHigh,
                                                        cursorColor = Palette.accent, focusedIndicatorColor = Palette.accent))
        },
        confirmButton = { TextAction(action, { done() }) },
        dismissButton = { TextAction("Cancel", onDismiss) },
    )
}

// A row in a menu sheet: an icon and what it does.
@Composable
fun SheetItem(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        AppIcon(icon, tint = Palette.textPrimary)
        Text(text, color = Palette.textPrimary, fontSize = 15.sp)
    }
}
