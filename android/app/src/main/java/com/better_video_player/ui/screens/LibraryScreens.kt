package com.better_video_player.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.better_video_player.ui.Palette
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.better_video_player.VesselViewModel
import com.better_video_player.data.NaturalOrder
import com.better_video_player.data.Video
import com.better_video_player.data.countText
import com.better_video_player.data.dateText
import com.better_video_player.ui.AppIcons
import com.better_video_player.ui.EmptyNote
import com.better_video_player.ui.FolderCard
import com.better_video_player.ui.IconBtn
import com.better_video_player.ui.LocalAppActions
import com.better_video_player.ui.LocalSeen
import com.better_video_player.ui.Metrics
import com.better_video_player.ui.PillButton
import com.better_video_player.ui.VideoCard

// The sort menu of the Videos tab and folder pages, as on the desktop.
enum class VideoSort(val label: String) {
    Name("Name"), Added("Recently added"), Watched("Recently watched"), Longest("Longest first"), Largest("Largest first")
}

@Composable
fun sortVideos(videos: List<Video>, sort: VideoSort): List<Video> {
    val seen = LocalSeen.current
    return remember(videos, sort, if (sort == VideoSort.Watched) seen else null) {
        when (sort) {
            VideoSort.Name -> videos.sortedWith(compareBy(NaturalOrder) { it.title })
            VideoSort.Added -> videos.sortedByDescending { it.addedMs }
            VideoSort.Watched -> videos.sortedByDescending { seen[it.key]?.watchedAt ?: 0 }
            VideoSort.Longest -> videos.sortedByDescending { it.durationMs }
            VideoSort.Largest -> videos.sortedByDescending { it.size }
        }
    }
}

private val gridPadding = PaddingValues(horizontal = Metrics.pagePadding, vertical = 8.dp)

@Composable
private fun VideoGrid(
    videos: List<Video>,
    state: LazyGridState,
    bottomPadding: Dp,
    subtitle: (Video) -> String,
    header: (androidx.compose.foundation.lazy.grid.LazyGridScope.() -> Unit)? = null,
) {
    LazyVerticalGrid(
        GridCells.Adaptive(160.dp),
        Modifier.fillMaxSize(),
        state = state,
        contentPadding = PaddingValues(start = Metrics.pagePadding, end = Metrics.pagePadding, top = 8.dp, bottom = bottomPadding + 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        header?.invoke(this)
        items(videos, key = { it.key }) { v -> VideoCard(v, subtitle(v)) }
    }
}

// Every video on the phone, in one grid.
@Composable
fun VideosScreen(vm: VesselViewModel, bottomPadding: Dp) {
    val actions = LocalAppActions.current
    val index by vm.index.collectAsStateWithLifecycle()
    val loaded by vm.library.loaded.collectAsStateWithLifecycle()
    var sort by rememberSaveable { mutableStateOf(VideoSort.Added) }
    val grid = rememberLazyGridState()
    val videos = sortVideos(index.videos, sort)

    Column(Modifier.fillMaxSize()) {
        TitleBar("Videos", if (loaded) countText(videos.size, "video", "videos") else "", grid.canScrollBackward) {
            IconBtn(AppIcons.search, onClick = actions::openSearch, contentDescription = "Search")
            SortButton(VideoSort.entries.map { it to it.label }, sort) { sort = it }
        }
        if (loaded && videos.isEmpty())
            EmptyNote("No videos on this phone yet.", Modifier.padding(top = 16.dp), icon = AppIcons.movie)
        VideoGrid(videos, grid, bottomPadding, subtitle = { it.folderName })
    }
}

enum class FolderSort(val label: String) { Name("Name"), Added("Recently added"), Size("Most videos") }

// Every folder that holds videos.
@Composable
fun LibraryScreen(vm: VesselViewModel, bottomPadding: Dp) {
    val index by vm.index.collectAsStateWithLifecycle()
    val loaded by vm.library.loaded.collectAsStateWithLifecycle()
    var sort by rememberSaveable { mutableStateOf(FolderSort.Name) }
    val grid = rememberLazyGridState()
    val folders = remember(index, sort) {
        when (sort) {
            FolderSort.Name -> index.folders
            FolderSort.Added -> index.folders.sortedByDescending { it.newestMs }
            FolderSort.Size -> index.folders.sortedByDescending { it.videos.size }
        }
    }
    val actions = LocalAppActions.current

    Column(Modifier.fillMaxSize()) {
        TitleBar("Library", if (loaded) countText(folders.size, "folder", "folders") else "", grid.canScrollBackward) {
            IconBtn(AppIcons.search, onClick = actions::openSearch, contentDescription = "Search")
            SortButton(FolderSort.entries.map { it to it.label }, sort) { sort = it }
        }
        if (loaded && folders.isEmpty())
            EmptyNote("Folders with videos in them show up here.", Modifier.padding(top = 16.dp), icon = AppIcons.folder)
        LazyVerticalGrid(
            GridCells.Adaptive(160.dp),
            Modifier.fillMaxSize(),
            state = grid,
            contentPadding = PaddingValues(start = Metrics.pagePadding, end = Metrics.pagePadding, top = 8.dp, bottom = bottomPadding + 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(folders, key = { it.key }) { f -> FolderCard(f) }
        }
    }
}

// One folder's videos, with Play all and Shuffle.
@Composable
fun FolderScreen(vm: VesselViewModel, key: String, bottomPadding: Dp, onBack: () -> Unit) {
    val actions = LocalAppActions.current
    val index by vm.index.collectAsStateWithLifecycle()
    val folder = index.byFolder[key]
    var sort by rememberSaveable { mutableStateOf(VideoSort.Name) }
    val grid = rememberLazyGridState()
    val videos = sortVideos(folder?.videos ?: emptyList(), sort)

    Column(Modifier.fillMaxSize()) {
        TitleBar(folder?.name ?: "Folder", folder?.let { countText(it.videos.size, "video", "videos") } ?: "",
                 grid.canScrollBackward, onBack = onBack) {
            SortButton(VideoSort.entries.map { it to it.label }, sort) { sort = it }
        }
        if (folder == null) {
            EmptyNote("This folder has no videos any more.", Modifier.padding(top = 16.dp), icon = AppIcons.folder)
            return@Column
        }
        VideoGrid(videos, grid, bottomPadding, subtitle = { dateText(if (it.takenMs > 0) it.takenMs else it.addedMs) }) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.padding(bottom = 8.dp)) {
                    Text(folder.location, color = Palette.textSecondary, fontSize = 13.sp)
                    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PillButton("Play all", AppIcons.play, { actions.playAll(folder.name, videos, false) })
                        PillButton("Shuffle", AppIcons.shuffle, { actions.playAll(folder.name, videos, true) }, soft = true)
                    }
                }
            }
        }
    }
}
