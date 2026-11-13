package com.better_video_player.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.better_video_player.data.Folder
import com.better_video_player.data.Seen
import com.better_video_player.data.Video
import com.better_video_player.data.countText
import com.better_video_player.data.durationText

// What any page can ask of the app, as the desktop pages call into Main.qml.
interface AppActions {
    fun play(video: Video)
    fun playAll(name: String, videos: List<Video>, shuffle: Boolean)
    fun showVideoMenu(video: Video)
    fun openFolder(key: String)
    fun openSearch()
    fun openHistory()
}

val LocalAppActions = staticCompositionLocalOf<AppActions> { error("No AppActions") }

// What the history says about each video, for progress lines and "Watched" marks.
val LocalSeen = staticCompositionLocalOf<Map<String, Seen>> { emptyMap() }

@Composable
fun AppIcon(icon: ImageVector, modifier: Modifier = Modifier, tint: Color = Palette.textPrimary, size: Dp = 24.dp) {
    Icon(icon, contentDescription = null, modifier = modifier.size(size), tint = tint)
}

@Composable
fun IconBtn(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Palette.textPrimary,
    iconSize: Dp = 24.dp,
    size: Dp = 44.dp,
    enabled: Boolean = true,
    contentDescription: String? = null,
) {
    androidx.compose.material3.IconButton(onClick = onClick, modifier = modifier.size(size), enabled = enabled) {
        Icon(icon, contentDescription, Modifier.size(iconSize), tint = if (enabled) tint else tint.copy(alpha = 0.35f))
    }
}

// A thumbnail with rounded corners, or a placeholder until it has loaded (pulsing meanwhile).
@Composable
fun Thumbnail(video: Video, modifier: Modifier = Modifier, radius: Dp = Metrics.radiusCard, content: @Composable BoxScope.() -> Unit = {}) {
    val image = rememberThumbnail(video)
    val shape = RoundedCornerShape(radius)
    Box(modifier.clip(shape)) {
        if (image != null) {
            Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            val pulse = rememberInfiniteTransition(label = "pulse")
            val alpha by pulse.animateFloat(
                1f, if (Thumbnails.hasFailed(video)) 1f else 0.45f,
                infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "alpha",
            )
            Box(Modifier.fillMaxSize().alpha(alpha).background(Palette.surfaceHigh), contentAlignment = Alignment.Center) {
                AppIcon(AppIcons.movie, tint = Palette.textTertiary, size = 32.dp)
            }
        }
        content()
    }
}

@Composable
fun ProgressLine(progress: Float, modifier: Modifier = Modifier, height: Dp = 4.dp) {
    Box(modifier.fillMaxWidth().height(height).clip(CircleShape).background(Palette.track)) {
        Box(Modifier.fillMaxWidth(progress).fillMaxSize().clip(CircleShape).background(Palette.accent))
    }
}

@Composable
fun Badge(text: String, modifier: Modifier = Modifier, small: Boolean = false) {
    Text(
        text,
        modifier = modifier
            .background(Palette.badge, RoundedCornerShape(if (small) 5.dp else 6.dp))
            .padding(horizontal = if (small) 5.dp else 6.dp, vertical = 2.dp),
        color = Palette.textPrimary,
        fontSize = if (small) 11.sp else 12.sp,
        fontWeight = FontWeight.SemiBold,
    )
}

// Finished (or marked so): a tick in the corner.
@Composable
private fun WatchedMark(modifier: Modifier = Modifier, label: Boolean) {
    Row(
        modifier.background(Palette.badge, CircleShape).padding(horizontal = if (label) 7.dp else 3.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        AppIcon(AppIcons.check, tint = Palette.accent, size = if (label) 16.dp else 14.dp)
        if (label) Text("Watched", color = Palette.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

// The marks laid over a thumbnail: watched, duration, progress.
@Composable
private fun BoxScope.ThumbOverlay(video: Video, seen: Seen?, big: Boolean, showDuration: Boolean = true) {
    val progress = seen?.takeIf { it.started }?.progress ?: 0f
    if (seen?.finished == true)
        WatchedMark(Modifier.align(Alignment.TopStart).padding(if (big) 8.dp else 5.dp), label = big)
    val duration = durationText(video.durationMs)
    if (showDuration && duration.isNotEmpty())
        Badge(duration, Modifier.align(Alignment.BottomEnd).padding(end = 6.dp, bottom = if (progress > 0) 12.dp else 6.dp), small = !big)
    if (progress > 0)
        ProgressLine(progress, Modifier.align(Alignment.BottomCenter).padding(horizontal = 8.dp, vertical = 6.dp), height = if (big) 4.dp else 3.dp)
}

// A 16:9 thumbnail with the title underneath, and a progress line for videos that were started.
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VideoCard(video: Video, subtitle: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val actions = LocalAppActions.current
    val seen = LocalSeen.current[video.key]
    Column(
        modifier
            .clip(RoundedCornerShape(Metrics.radiusCard))
            .combinedClickable(onClick = onClick ?: { actions.play(video) }, onLongClick = { actions.showVideoMenu(video) })
    ) {
        Thumbnail(video, Modifier.fillMaxWidth().aspectRatio(16f / 9f)) { ThumbOverlay(video, seen, big = true) }
        Spacer(Modifier.height(8.dp))
        Text(video.title, color = Palette.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
             maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 19.sp)
        if (subtitle.isNotEmpty())
            Text(subtitle, color = Palette.textSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                 modifier = Modifier.padding(top = 2.dp, bottom = 4.dp))
    }
}

// A list row: a thumbnail, the title and details. Quick picks, history, and the queue.
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VideoRow(
    video: Video,
    subtitle: String,
    modifier: Modifier = Modifier,
    thumbWidth: Dp = 120.dp,
    titleLines: Int = 2,
    current: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val actions = LocalAppActions.current
    val seen = LocalSeen.current[video.key]
    Row(
        modifier
            .clip(RoundedCornerShape(Metrics.radiusArt))
            .background(if (current) Palette.accent.copy(alpha = 0.12f) else Color.Transparent)
            .combinedClickable(onClick = onClick ?: { actions.play(video) }, onLongClick = { actions.showVideoMenu(video) })
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumbnail(video, Modifier.width(thumbWidth).aspectRatio(16f / 9f), radius = if (thumbWidth > 110.dp) 10.dp else 8.dp) {
            if (current) {
                Box(Modifier.fillMaxSize().background(Color(0x80000000)), contentAlignment = Alignment.Center) {
                    AppIcon(AppIcons.play, tint = Palette.accent, size = 28.dp)
                }
            } else {
                ThumbOverlay(video, seen, big = false, showDuration = thumbWidth > 110.dp)
            }
        }
        Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(video.title, color = if (current) Palette.accent else Palette.textPrimary, fontSize = 14.sp,
                 fontWeight = FontWeight.SemiBold, maxLines = titleLines, overflow = TextOverflow.Ellipsis, lineHeight = 18.sp)
            Text(if (current) "Now playing" else subtitle, color = Palette.textSecondary, fontSize = 13.sp,
                 maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing()
    }
}

// A folder shown as a small stack of thumbnails, with its name and size.
@Composable
fun FolderCard(folder: Folder, modifier: Modifier = Modifier) {
    val actions = LocalAppActions.current
    Column(modifier.clip(RoundedCornerShape(Metrics.radiusCard)).clickable { actions.openFolder(folder.key) }) {
        Box(Modifier.fillMaxWidth()) {
            // The "stack" behind the cover.
            Box(Modifier.align(Alignment.TopCenter).padding(horizontal = 14.dp).fillMaxWidth().height(16.dp)
                    .background(Palette.control, RoundedCornerShape(Metrics.radiusArt)))
            Box(Modifier.align(Alignment.TopCenter).padding(top = 4.dp, start = 7.dp, end = 7.dp).fillMaxWidth().height(16.dp)
                    .background(Color(0xFF3A3A3A), RoundedCornerShape(Metrics.radiusArt)))
            Thumbnail(folder.cover, Modifier.padding(top = 8.dp).fillMaxWidth().aspectRatio(16f / 9f), radius = Metrics.radiusArt) {
                Box(Modifier.align(Alignment.BottomStart).padding(8.dp).size(28.dp).background(Palette.badge, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center) {
                    AppIcon(AppIcons.folder, tint = Palette.accent, size = 17.dp)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(folder.name, color = Palette.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
             maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(countText(folder.videos.size, "video", "videos"), color = Palette.textSecondary, fontSize = 13.sp,
             modifier = Modifier.padding(top = 2.dp, bottom = 4.dp))
    }
}

// A section title with optional extra buttons and an accent action on the right.
@Composable
fun ShelfHeader(
    title: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
    extras: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth().padding(start = Metrics.pagePadding, end = 8.dp, top = 24.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f), color = Palette.textPrimary, fontSize = Metrics.sectionSize,
             fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        extras()
        if (actionLabel != null) TextAction(actionLabel, onAction)
    }
}

@Composable
fun TextAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    androidx.compose.material3.TextButton(onClick = onClick, modifier = modifier) {
        Text(text, color = Palette.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

// A filled accent button ("Play all"), or a soft one ("Shuffle").
@Composable
fun PillButton(text: String, icon: ImageVector?, onClick: () -> Unit, modifier: Modifier = Modifier, soft: Boolean = false) {
    androidx.compose.material3.Button(
        onClick = onClick,
        modifier = modifier.height(40.dp),
        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
            containerColor = if (soft) Palette.accentSoft else Palette.accent,
            contentColor = if (soft) Palette.accent else Palette.accentText,
        ),
        contentPadding = PaddingValues(start = if (icon != null) 14.dp else 18.dp, end = 18.dp),
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

// A note where a list would be: what's missing, and maybe something to do about it.
@Composable
fun EmptyNote(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, actionText: String? = null, onAction: () -> Unit = {}) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = Metrics.pagePadding, vertical = 8.dp)
            .background(Palette.surface, RoundedCornerShape(Metrics.radiusCard)).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) AppIcon(icon, tint = Palette.textTertiary, size = 40.dp)
        Text(text, color = Palette.textSecondary, fontSize = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (actionText != null) PillButton(actionText, null, onAction, soft = true)
    }
}

// A page's big title, with a line under it ("12 videos").
@Composable
fun PageTitle(title: String, subtitle: String = "", modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(title, color = Palette.textPrimary, fontSize = Metrics.pageTitleSize, fontWeight = FontWeight.ExtraBold,
             letterSpacing = (-0.5).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (subtitle.isNotEmpty())
            Text(subtitle, color = Palette.textSecondary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// The app's mark, as in the desktop app's LogoMark.qml: a sailboat whose sail is a strip of
// film, with sprocket holes along the mast and a play symbol in the middle, on a rounded square.
@Composable
fun LogoMark(size: Dp = 30.dp, tile: Color = Palette.accent, ink: Color = Palette.accentText) {
    Canvas(Modifier.size(size)) {
        val s = this.size.width
        drawRoundRect(tile, cornerRadius = CornerRadius(s * 0.3f))
        // Drawn on a 24-unit grid, scaled to fit.
        val k = s / 28f
        translate((s - 24 * k) / 2, (s - 24 * k) / 2) {
            scale(k, k, pivot = Offset.Zero) {
                val sail = Path().apply { moveTo(7f, 2.5f); lineTo(19f, 15f); lineTo(7f, 15f); close() }
                drawPath(sail, ink)
                drawPath(sail, ink, style = Stroke(width = 1.2f, join = StrokeJoin.Round))
                for (y in listOf(6.3f, 9.3f, 12.3f))
                    drawRoundRect(tile, Offset(8.2f, y), Size(1.5f, 1.5f), CornerRadius(0.35f))
                val play = Path().apply { moveTo(11.4f, 10f); lineTo(15f, 12f); lineTo(11.4f, 14f); close() }
                drawPath(play, tile, style = Fill)
                drawPath(play, tile, style = Stroke(width = 0.6f, join = StrokeJoin.Round))
                val hull = Path().apply {
                    moveTo(3f, 16.5f); lineTo(21f, 16.5f)
                    quadraticTo(19.5f, 20.5f, 15f, 20.5f); lineTo(9f, 20.5f)
                    quadraticTo(4.5f, 20.5f, 3f, 16.5f); close()
                }
                drawPath(hull, ink.copy(alpha = 0.72f))
            }
        }
    }
}

// The mark, then the name.
@Composable
fun AppLogo(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        LogoMark(28.dp)
        Text("Vessel", color = Palette.textPrimary, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp)
    }
}
