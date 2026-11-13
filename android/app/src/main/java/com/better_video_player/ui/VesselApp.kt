package com.better_video_player.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.better_video_player.VesselViewModel
import com.better_video_player.data.Video
import com.better_video_player.data.durationText
import com.better_video_player.ui.player.WatchLayer
import com.better_video_player.ui.player.WatchState
import com.better_video_player.ui.screens.AccessScreen
import com.better_video_player.ui.screens.FolderScreen
import com.better_video_player.ui.screens.HistoryScreen
import com.better_video_player.ui.screens.HomeScreen
import com.better_video_player.ui.screens.LibraryScreen
import com.better_video_player.ui.screens.SearchScreen
import com.better_video_player.ui.screens.SettingsScreen
import com.better_video_player.ui.screens.VideosScreen
import kotlinx.coroutines.launch

// The tabs along the bottom (the desktop app's side rail).
enum class Tab(val label: String, val icon: ImageVector) {
    Home("Home", AppIcons.home),
    Videos("Videos", AppIcons.movie),
    Library("Library", AppIcons.library),
    Settings("Settings", AppIcons.settings),
}

// Pages opened on top of a tab; each tab keeps its own stack.
sealed interface Route {
    data class Folder(val key: String) : Route
    data object Search : Route
    data object History : Route
}

private val MiniPlayerHeight = 64.dp

@Composable
fun VesselApp(vm: VesselViewModel) {
    val access = rememberVideoAccess(vm)
    val watch = remember { WatchState() }
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableStateOf(Tab.Home) }
    val stacks = remember { Tab.entries.associateWith { mutableStateListOf<Route>() } }
    val pages = rememberSaveableStateHolder()
    val seen by vm.history.entries.collectAsStateWithLifecycle()
    val current by vm.current.collectAsStateWithLifecycle()
    var menuVideo by remember { mutableStateOf<Video?>(null) }
    var navHeightPx by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    // Room under each page for the mini player, so it doesn't cover the end of a list.
    val bottomPadding = if (current != null) MiniPlayerHeight else 0.dp

    fun push(route: Route) {
        stacks.getValue(tab).add(route)
    }

    val actions = remember {
        object : AppActions {
            override fun play(video: Video) {
                if (vm.current.value?.key == video.key) scope.launch { watch.expand() } else vm.open(video)
            }
            override fun playAll(name: String, videos: List<Video>, shuffle: Boolean) = vm.playAll(name, videos, shuffle)
            override fun showVideoMenu(video: Video) {
                menuVideo = video
            }
            override fun openFolder(key: String) = push(Route.Folder(key))
            override fun openSearch() = push(Route.Search)
            override fun openHistory() = push(Route.History)
        }
    }

    // Back: the page on top of the tab, then Home. (The player's own Back handlers come later
    // in composition, so they go first while it's open.)
    BackHandler(enabled = stacks.getValue(tab).isNotEmpty() || tab != Tab.Home) {
        val stack = stacks.getValue(tab)
        if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) else tab = Tab.Home
    }

    CompositionLocalProvider(LocalAppActions provides actions, LocalSeen provides seen) {
        Box(Modifier.fillMaxSize().background(Palette.background)) {
            if (!access.ready) {
                AccessScreen(access.denied, access.partial, onAllow = access.request,
                             onContinue = if (access.partial) access.continuePartial else null)
            } else {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) {
                        val stack = stacks.getValue(tab)
                        val route = stack.lastOrNull()
                        val back = { stack.removeAt(stack.lastIndex); Unit }
                        pages.SaveableStateProvider("${tab.name}/${stack.size}") {
                            when (route) {
                                null -> when (tab) {
                                    Tab.Home -> HomeScreen(vm, bottomPadding, onSeeAllFolders = { tab = Tab.Library })
                                    Tab.Videos -> VideosScreen(vm, bottomPadding)
                                    Tab.Library -> LibraryScreen(vm, bottomPadding)
                                    Tab.Settings -> SettingsScreen(vm, bottomPadding, onOpenHistory = { push(Route.History) })
                                }
                                is Route.Folder -> FolderScreen(vm, route.key, bottomPadding, back)
                                Route.Search -> SearchScreen(vm, bottomPadding, back)
                                Route.History -> HistoryScreen(vm, bottomPadding, back)
                            }
                        }
                    }
                    NavigationBar(
                        Modifier.onSizeChanged { navHeightPx = it.height },
                        containerColor = Palette.surfaceHigh,
                    ) {
                        for (t in Tab.entries) {
                            NavigationBarItem(
                                selected = t == tab,
                                onClick = {
                                    // The current tab again: back to its top.
                                    if (t == tab) stacks.getValue(t).clear()
                                    tab = t
                                },
                                icon = { Icon(t.icon, contentDescription = null) },
                                label = { Text(t.label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = Palette.accent,
                                    selectedTextColor = Palette.accent,
                                    indicatorColor = Palette.accentSoft,
                                    unselectedIconColor = Palette.textSecondary,
                                    unselectedTextColor = Palette.textSecondary,
                                ),
                            )
                        }
                    }
                }
            }

            WatchLayer(vm, watch, navHeight = if (access.ready) with(density) { navHeightPx.toDp() } else 0.dp)
        }

        menuVideo?.let { video ->
            VideoMenu(vm, video, onDismiss = { menuVideo = null }, onOpenFolder = {
                menuVideo = null
                scope.launch { if (vm.current.value != null) watch.minimize() }
                actions.openFolder(video.folder)
            })
        }
    }
}

// A long press on a video: its menu, as a sheet.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VideoMenu(vm: VesselViewModel, video: Video, onDismiss: () -> Unit, onOpenFolder: () -> Unit) {
    val actions = LocalAppActions.current
    val seen = LocalSeen.current[video.key]
    val context = LocalContext.current

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Palette.dialog) {
        Row(Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Thumbnail(video, Modifier.width(112.dp).aspectRatio(16f / 9f), radius = 8.dp)
            Column(Modifier.padding(start = 14.dp)) {
                Text(video.title, color = Palette.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                     maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(listOf(video.folderName, durationText(video.durationMs)).filter { it.isNotEmpty() }.joinToString("  •  "),
                     color = Palette.textSecondary, fontSize = 13.sp)
            }
        }
        MenuItem(AppIcons.play, "Play") { onDismiss(); actions.play(video) }
        if (seen?.finished == true) MenuItem(AppIcons.replay, "Mark as unwatched") { vm.setWatched(video, false); onDismiss() }
        else MenuItem(AppIcons.check, "Mark as watched") { vm.setWatched(video, true); onDismiss() }
        if (seen != null && !seen.finished)
            MenuItem(AppIcons.history, "Remove from history") { vm.history.remove(video.key); onDismiss() }
        if (video.folder.isNotEmpty()) MenuItem(AppIcons.folderOpen, "Go to folder", onOpenFolder)
        MenuItem(AppIcons.openInFull, "Share") {
            onDismiss()
            val send = Intent(Intent.ACTION_SEND).apply {
                type = video.mimeType.ifEmpty { "video/*" }
                putExtra(Intent.EXTRA_STREAM, video.uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, video.title))
        }
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars).height(12.dp))
    }
}

@Composable
private fun MenuItem(icon: ImageVector, text: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        AppIcon(icon, tint = Palette.textPrimary)
        Text(text, color = Palette.textPrimary, fontSize = 15.sp)
    }
}

// Access to the phone's videos: asked for at first launch, and checked again whenever the
// app comes back (it may have been changed in Android's settings meanwhile).
class VideoAccess(
    val ready: Boolean,
    // Android 14+: only some videos were allowed.
    val partial: Boolean,
    // Refused, and Android won't ask again: it's up to the app's settings page now.
    val denied: Boolean,
    val request: () -> Unit,
    val continuePartial: () -> Unit,
)

private val videoPermissions: Array<String> = when {
    Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

@Composable
private fun rememberVideoAccess(vm: VesselViewModel): VideoAccess {
    val context = LocalContext.current
    val activity = LocalActivity.current
    fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    fun fullAccess() = granted(videoPermissions[0])
    fun partialAccess() = Build.VERSION.SDK_INT >= 34 && !fullAccess() && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)

    var full by remember { mutableStateOf(fullAccess()) }
    var partial by remember { mutableStateOf(partialAccess()) }
    var partialAccepted by rememberSaveable { mutableStateOf(false) }
    var denied by remember { mutableStateOf(false) }
    var asked by rememberSaveable { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        full = fullAccess()
        partial = partialAccess()
        denied = !full && !partial && activity?.shouldShowRequestPermissionRationale(videoPermissions[0]) == false
        // The videos picked for partial access may have changed.
        if (full || partial) vm.library.refresh()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val wasFull = full
        val wasPartial = partial
        full = fullAccess()
        partial = partialAccess()
        if (full || partial) denied = false
        // With full access MediaStore's change notices keep the library current; look again only
        // when access changed in Settings, or with partial access (whose picked videos can change
        // there unnoticed).
        if ((full || partial) && (full != wasFull || partial != wasPartial || !full)) vm.library.refresh()
    }
    // Asked once by itself at first launch; after that, from the button.
    LaunchedEffect(Unit) {
        if (!full && !partial && !asked) {
            asked = true
            launcher.launch(videoPermissions)
        }
    }
    val ready = full || (partial && partialAccepted)
    LaunchedEffect(ready) { if (ready) vm.library.start() }

    return VideoAccess(ready, partial, denied, request = { launcher.launch(videoPermissions) }, continuePartial = { partialAccepted = true })
}
