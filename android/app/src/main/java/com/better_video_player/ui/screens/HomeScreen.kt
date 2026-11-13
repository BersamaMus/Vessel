package com.better_video_player.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.better_video_player.VesselViewModel
import com.better_video_player.data.durationText
import com.better_video_player.data.remainingText
import com.better_video_player.ui.AppIcons
import com.better_video_player.ui.AppLogo
import com.better_video_player.ui.EmptyNote
import com.better_video_player.ui.FolderCard
import com.better_video_player.ui.IconBtn
import com.better_video_player.ui.LocalAppActions
import com.better_video_player.ui.LocalSeen
import com.better_video_player.ui.Metrics
import com.better_video_player.ui.Palette
import com.better_video_player.ui.ShelfHeader
import com.better_video_player.ui.VideoCard
import com.better_video_player.ui.VideoRow

private val CardWidth = 240.dp
private val FolderWidth = 200.dp
private val shelfPadding = PaddingValues(horizontal = Metrics.pagePadding)

// Continue watching, Quick picks, Recently added and Folders, under a bar with the logo.
@Composable
fun HomeScreen(vm: VesselViewModel, bottomPadding: Dp, onSeeAllFolders: () -> Unit) {
    val actions = LocalAppActions.current
    val index by vm.index.collectAsStateWithLifecycle()
    val loaded by vm.library.loaded.collectAsStateWithLifecycle()
    val continueWatching by vm.continueWatching.collectAsStateWithLifecycle()
    val picks by vm.quickPicks.collectAsStateWithLifecycle()
    val recent by vm.recentlyAdded.collectAsStateWithLifecycle()
    val seen = LocalSeen.current
    val list = rememberLazyListState()
    val hasVideos = index.videos.isNotEmpty()

    Column(Modifier.fillMaxSize()) {
        PageBar(scrolled = list.canScrollBackward) {
            Row(Modifier.padding(start = Metrics.pagePadding, end = 4.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                AppLogo()
                Spacer(Modifier.weight(1f))
                IconBtn(AppIcons.history, onClick = actions::openHistory, contentDescription = "History")
                IconBtn(AppIcons.search, onClick = actions::openSearch, contentDescription = "Search")
            }
        }

        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = bottomPadding + 24.dp)) {
            if (loaded && !hasVideos) {
                item { EmptyNote("No videos on this phone yet. Videos you record, download or copy here show up by themselves.",
                                 Modifier.padding(top = 16.dp), icon = AppIcons.movie) }
            }
            if (!hasVideos) return@LazyColumn

            // --- Continue watching ---------------------------------------------
            item { ShelfHeader("Continue watching") }
            if (continueWatching.isEmpty()) {
                item { EmptyNote("Videos you start watching will show up here, so you can pick up where you left off.") }
            } else {
                item {
                    LazyRow(contentPadding = shelfPadding, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(continueWatching, key = { it.key }) { v ->
                            val s = seen[v.key]
                            VideoCard(v, if (s != null) remainingText(s.position, s.duration) else "", Modifier.width(CardWidth))
                        }
                    }
                }
            }

            // --- Quick picks ---------------------------------------------------
            item {
                ShelfHeader("Quick picks") {
                    IconBtn(AppIcons.refresh, onClick = vm::reshuffleQuickPicks, tint = Palette.textSecondary, contentDescription = "Refresh")
                    IconBtn(AppIcons.shuffle, onClick = { picks.randomOrNull()?.let(actions::play) },
                            tint = Palette.textSecondary, contentDescription = "Play a random pick")
                }
            }
            item {
                LazyHorizontalGrid(GridCells.Fixed(4), Modifier.height(72.dp * 4), contentPadding = PaddingValues(horizontal = 8.dp)) {
                    items(picks, key = { it.key }) { v ->
                        val subtitle = listOf(v.folderName, durationText(v.durationMs)).filter { it.isNotEmpty() }.joinToString("  •  ")
                        VideoRow(v, subtitle, Modifier.width(320.dp).height(72.dp), thumbWidth = 96.dp, titleLines = 1)
                    }
                }
            }

            // --- Recently added ------------------------------------------------
            item { ShelfHeader("Recently added") }
            item {
                LazyRow(contentPadding = shelfPadding, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(recent, key = { it.key }) { v -> VideoCard(v, v.folderName, Modifier.width(CardWidth)) }
                }
            }

            // --- Folders ---------------------------------------------------------
            if (index.folders.isNotEmpty()) {
                item { ShelfHeader("Folders", actionLabel = "See all", onAction = onSeeAllFolders) }
                item {
                    LazyRow(contentPadding = shelfPadding, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(index.folders.sortedByDescending { it.newestMs }.take(20), key = { it.key }) { f ->
                            FolderCard(f, Modifier.width(FolderWidth))
                        }
                    }
                }
            }
        }
    }
}
