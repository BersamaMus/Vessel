@file:androidx.annotation.OptIn(UnstableApi::class)

package com.better_video_player.ui.player

import android.app.Activity
import android.content.pm.ActivityInfo
import android.provider.Settings
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.view.OrientationEventListener
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.better_video_player.LocalInPictureInPicture
import com.better_video_player.MainActivity
import androidx.compose.ui.layout.boundsInWindow
import com.better_video_player.Queue
import com.better_video_player.VesselViewModel
import com.better_video_player.data.Video
import com.better_video_player.data.codecName
import com.better_video_player.data.dateText
import com.better_video_player.data.formatTime
import com.better_video_player.data.sizeText
import com.better_video_player.ui.AppIcon
import com.better_video_player.ui.AppIcons
import com.better_video_player.ui.IconBtn
import com.better_video_player.ui.Metrics
import com.better_video_player.ui.Palette
import com.better_video_player.ui.PillButton
import com.better_video_player.ui.VideoCard
import com.better_video_player.ui.VideoRow
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

// Where the player is: the watch page, the mini player above the tabs, or full screen.
// Kept by the app, so the pages can open and expand it.
@Stable
class WatchState {
    // 0 = watch page, 1 = mini player; follows the finger while swiping.
    val mini = Animatable(0f)
    var full by mutableStateOf(false)
    // The mini player swiped sideways, in pixels: it follows the finger, fading.
    var dismissX by mutableFloatStateOf(0f)
    var controlsShown by mutableStateOf(true)
    // Bumped by anything that should keep the controls up a while longer.
    var activity by mutableIntStateOf(0)
    // The skip in progress: which side (-1 back, 1 forward), and how far so far.
    var seekSide by mutableIntStateOf(0)
    var seekTotal by mutableIntStateOf(0)
    // A sideways swipe on the video, seeking: where it started and where it's going (ms; -1 when none).
    var swipeFromMs by mutableLongStateOf(-1L)
    var swipeToMs by mutableLongStateOf(-1L)
    // A brief pill at the top of the video saying what just changed.
    var flashText by mutableStateOf("")
    var settingsOpen by mutableStateOf(false)
    // A finger is dragging the player, which started out as the mini player or not. Its layout
    // keeps that shape until the finger lifts, so the drag's own handler isn't removed halfway.
    var dragging by mutableStateOf(false)
    var dragFromMini by mutableStateOf(false)

    val isMini: Boolean get() = mini.value > 0.5f && !full
    // Laid out as the mini player: as when the drag began, while one is going on.
    val miniLayout: Boolean get() = if (dragging) dragFromMini && !full else isMini

    suspend fun expand() = mini.animateTo(0f, tween(320))
    suspend fun minimize() {
        full = false
        mini.animateTo(1f, tween(320))
    }

    fun wake() {
        controlsShown = true
        activity++
    }
}

private val MiniHeight = 64.dp
// How far a swipe across the whole video seeks.
private const val SwipeSeekMsPerWidth = 90_000L

// The player, laid over the whole app, as on the desktop and in YouTube's app. One video
// moves smoothly between the watch page (video on top, its details, the rest of its folder
// and more videos below), the mini player above the tabs, and full screen.
// Swipe the video down for the mini player and up for full screen; swipe the mini player up
// (or tap it) to bring the watch page back, and down or sideways to close it.
// Swipe the video sideways to seek.
@Composable
fun WatchLayer(vm: VesselViewModel, watch: WatchState, navHeight: Dp) {
    val video = vm.current.collectAsStateWithLifecycle().value ?: return
    val engine by vm.engine.collectAsStateWithLifecycle()
    val ui = rememberPlayerUiState(engine)
    val notice by vm.notice.collectAsStateWithLifecycle()
    val queue by vm.queue.collectAsStateWithLifecycle()
    val suggestions by vm.suggestions.collectAsStateWithLifecycle()
    val countdown by vm.autoplayCountdown.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val skipSeconds by vm.settings.skipSeconds.collectAsStateWithLifecycle()
    val autoplay by vm.settings.autoplay.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val activity = LocalActivity.current
    // In a picture-in-picture window: just the video, filling it.
    val pip = LocalInPictureInPicture.current
    val pipSetting by vm.settings.pictureInPicture.collectAsStateWithLifecycle()
    var videoBounds by remember { mutableStateOf<android.graphics.Rect?>(null) }

    // A new video opens on the watch page (from the mini player, it grows into it).
    LaunchedEffect(video.key) {
        watch.dismissX = 0f
        endSwipeSeek(vm, watch, land = false)
        watch.wake()
        if (!watch.full) watch.expand()
    }

    // Controls hide after a few seconds of playing untouched.
    LaunchedEffect(watch.controlsShown, watch.activity, ui.isPlaying, watch.settingsOpen) {
        if (watch.controlsShown && ui.isPlaying && !watch.settingsOpen) {
            delay(3000)
            watch.controlsShown = false
        }
    }
    // Skips in quick succession add up on one bubble.
    LaunchedEffect(watch.seekSide, watch.seekTotal) {
        if (watch.seekSide != 0) {
            delay(800)
            watch.seekSide = 0
            watch.seekTotal = 0
        }
    }
    // The engine changed by itself (ExoPlayer couldn't play it): say so briefly.
    LaunchedEffect(notice) {
        if (notice.isNotEmpty()) {
            watch.flashText = notice
            vm.clearNotice()
        }
    }
    LaunchedEffect(watch.flashText) {
        if (watch.flashText.isNotEmpty()) {
            delay(1200)
            watch.flashText = ""
        }
    }

    // The screen stays on while a video plays.
    val view = LocalView.current
    DisposableEffect(ui.isPlaying) {
        view.keepScreenOn = ui.isPlaying
        onDispose { view.keepScreenOn = false }
    }

    val portraitVideo = ui.videoHeight > ui.videoWidth || (ui.videoWidth == 0 && video.portrait)
    // Leaving the app while a video plays shrinks it into a floating window (if allowed).
    LaunchedEffect(pipSetting, ui.isPlaying, ui.videoWidth, ui.videoHeight, videoBounds) {
        // Once the video stops moving: not on every frame of a swipe or an animation (each is a call to the system).
        delay(150)
        (activity as? MainActivity)?.updatePictureInPicture(pipSetting && ui.isPlaying, ui.videoWidth, ui.videoHeight, videoBounds)
    }
    DisposableEffect(Unit) {
        onDispose {
            (activity as? MainActivity)?.updatePictureInPicture(false, 0, 0, null)
            endSwipeSeek(vm, watch, land = false)
        }
    }

    FullScreenEffect(activity, watch.full, portraitVideo)
    RotateToFullScreen(activity, watch, enabled = !watch.isMini && !portraitVideo && !pip)

    BackHandler(enabled = watch.full) { watch.full = false }
    BackHandler(enabled = !watch.full && !watch.isMini) { scope.launch { watch.minimize() } }

    fun close() {
        vm.close()
        watch.full = false
        scope.launch { watch.mini.snapTo(1f) }
    }

    fun seekBy(side: Int) {
        val from = engine.positionMs
        var target = (from + side * skipSeconds * 1000L).coerceAtLeast(0)
        // Not past the end, once that's known.
        if (ui.durationMs > 0) target = target.coerceAtMost(ui.durationMs)
        if (side != watch.seekSide) watch.seekTotal = 0
        watch.seekSide = side
        // What it really skipped: less than usual near the start or the end.
        watch.seekTotal += (abs(target - from) / 1000.0).roundToInt()
        vm.seekTo(target)
        ui.update()
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val top = WindowInsets.statusBars.getTop(density).toFloat()
        val miniH = with(density) { MiniHeight.toPx() }
        val miniTop = h - with(density) { navHeight.toPx() } - miniH
        // The video is as wide as the screen, but leaves room for its details on a wide one.
        val watchRect = Rect(0f, top, w, top + min(w * 9 / 16, h * 0.6f))
        val miniRect = Rect(0f, miniTop, miniH * 16 / 9, miniTop + miniH)
        val dragRange = (miniRect.top - watchRect.top).coerceAtLeast(1f)
        val p = watch.mini.value

        fun videoRect(): Rect =
            if (watch.full || pip) Rect(0f, 0f, w, h)
            else lerp(watchRect, miniRect, watch.mini.value).translate(watch.dismissX, 0f)

        // A sideways swipe on the video seeks, following the finger: the video's width is 90 seconds.
        fun swipeSeek(dx: Float) {
            val duration = ui.durationMs
            if (duration <= 0) return
            if (watch.swipeFromMs < 0) {
                watch.swipeFromMs = engine.positionMs
                watch.controlsShown = false
                vm.setScrubbing(true)
            }
            val offset = (dx / w * SwipeSeekMsPerWidth).toLong() / 1000 * 1000
            val target = (watch.swipeFromMs + offset).coerceIn(0, duration)
            if (target != watch.swipeToMs) {
                watch.swipeToMs = target
                vm.seekTo(target)
            }
        }

        // Called when a swipe on the video (or the mini player) ends.
        fun finishSwipe(axis: Int, delta: Offset, velocity: Offset, startMini: Float) {
            val far = with(density) { 60.dp.toPx() }
            val fast = with(density) { 900.dp.toPx() }
            if (watch.swipeFromMs >= 0) {
                endSwipeSeek(vm, watch)
                ui.update()
                return
            }
            scope.launch {
                when {
                    axis == 1 && startMini > 0.5f && !watch.full -> {
                        // Thrown far or fast enough: off it goes, the way it was swiped.
                        val away = abs(delta.x) > w * 0.35f || abs(velocity.x) > fast
                        if (away) {
                            val side = if ((if (abs(velocity.x) > fast) velocity.x else delta.x) < 0) -1 else 1
                            Animatable(watch.dismissX).animateTo(side * w, tween(200)) { watch.dismissX = value }
                            close()
                        } else {
                            Animatable(watch.dismissX).animateTo(0f, tween(200)) { watch.dismissX = value }
                        }
                    }
                    axis != 2 -> {}
                    watch.full -> if (delta.y > far) watch.full = false
                    startMini < 0.5f && delta.y < -far -> {
                        watch.mini.snapTo(0f)
                        watch.full = true
                    }
                    startMini < 0.5f ->
                        if (watch.mini.value > 0.3f || velocity.y > fast) watch.minimize() else watch.expand()
                    delta.y > far * 0.8f -> close()
                    else -> if (watch.mini.value < 0.8f || velocity.y < -fast) watch.expand() else watch.minimize()
                }
            }
        }

        // Swipes, followed in window coordinates since the video moves under the finger.
        val swipeModifier = Modifier.swipes(watch, onSwipe = { axis, delta, startMini ->
            if (axis == 2 && !watch.full) scope.launch { watch.mini.snapTo((startMini + delta.y / dragRange).coerceIn(0f, 1f)) }
            if (axis == 1 && startMini > 0.5f && !watch.full) watch.dismissX = delta.x
            if (axis == 1 && (startMini < 0.5f || watch.full)) swipeSeek(delta.x)
        }, onEnd = ::finishSwipe)

        // --- The watch page ----------------------------------------------------
        if (!watch.full && !pip && p < 1f) {
            Box(
                Modifier.fillMaxSize()
                    .graphicsLayer { alpha = 1f - watch.mini.value }
                    .background(Palette.background)
                    .blockTouches()
            )
            WatchDetails(
                vm, video, ui, queue, suggestions,
                Modifier.fillMaxSize()
                    .padding(top = with(density) { watchRect.bottom.toDp() })
                    .graphicsLayer {
                        alpha = (1f - watch.mini.value * 1.6f).coerceAtLeast(0f)
                        translationY = watch.mini.value * 80.dp.toPx()
                    },
            )
        }
        if (watch.full || pip) Box(Modifier.fillMaxSize().background(Color.Black).blockTouches())

        // --- The mini player's bar ----------------------------------------------
        if (!watch.full && !pip && (p > 0.5f || watch.miniLayout)) {
            Box(
                Modifier
                    .placeAt { Rect(0f, miniTop, w, miniTop + miniH) }
                    .graphicsLayer {
                        translationX = watch.dismissX
                        alpha = ((watch.mini.value - 0.5f) * 2f).coerceAtLeast(0f) * (1f - min(1f, abs(watch.dismissX) / w))
                    }
                    .background(Palette.surfaceHigh)
                    .then(swipeModifier)
                    .clickable { scope.launch { watch.expand() } }
            ) {
                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(MiniHeight * 16 / 9 + 12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(video.title, color = Palette.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                             maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(queue?.name ?: video.folderName, color = Palette.textSecondary, fontSize = 12.sp,
                             maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconBtn(if (ui.isPlaying) AppIcons.pause else if (ui.ended) AppIcons.replay else AppIcons.play,
                            onClick = { vm.togglePlay() }, contentDescription = "Play or pause")
                    IconBtn(AppIcons.close, onClick = ::close, contentDescription = "Close")
                }
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(ui.progress).height(2.dp).background(Palette.accent))
            }
        }

        // --- The video -----------------------------------------------------------
        Box(
            Modifier
                .placeAt(::videoRect)
                .onGloballyPositioned { c ->
                    // Where the video is, so a picture-in-picture window grows out of it.
                    val b = c.boundsInWindow()
                    videoBounds = android.graphics.Rect(b.left.roundToInt(), b.top.roundToInt(), b.right.roundToInt(), b.bottom.roundToInt())
                }
                .graphicsLayer { alpha = 1f - min(1f, abs(watch.dismissX) / w) }
                .background(Color.Black)
                .then(
                    if (watch.miniLayout || pip) Modifier
                    // Keyed on the engine too: seekBy reads it, and mpv may take over from ExoPlayer.
                    else swipeModifier.pointerInput(watch.full, skipSeconds, engine) {
                        detectTapGestures(
                            onTap = { offset ->
                                // Still tapping on one side after a double tap: keep skipping.
                                val side = if (offset.x < size.width * 0.35f) -1 else if (offset.x > size.width * 0.65f) 1 else 0
                                if (watch.seekSide != 0 && side == watch.seekSide) seekBy(side)
                                else if (watch.controlsShown) watch.controlsShown = false
                                else watch.wake()
                            },
                            onDoubleTap = { offset ->
                                when {
                                    offset.x < size.width * 0.35f -> seekBy(-1)
                                    offset.x > size.width * 0.65f -> seekBy(1)
                                    else -> watch.full = !watch.full
                                }
                            },
                        )
                    }
                )
        ) {
            VideoSurface(engine)

            if (error != null && !watch.isMini) {
                Text(error!!, Modifier.align(Alignment.Center).padding(24.dp), color = Palette.textSecondary,
                     fontSize = 14.sp, textAlign = TextAlign.Center)
            }

            if (!watch.isMini && !pip) {
                SeekBubble(watch, side = -1)
                SeekBubble(watch, side = 1)
                if (watch.swipeToMs >= 0) SwipeSeekPill(watch, ui.durationMs, Modifier.align(Alignment.Center))
                PlayerControls(vm, watch, ui, queue, countdown, autoplay, video, hasError = error != null, onMinimize = { scope.launch { watch.minimize() } })
                if (ui.buffering && !watch.controlsShown)
                    CircularProgressIndicator(Modifier.align(Alignment.Center).size(48.dp), color = Palette.accent, strokeWidth = 3.dp)
            }
            // While the controls are hidden on the watch page, a thin line still shows how far in.
            if (!watch.full && !pip && !watch.controlsShown && p < 0.2f && ui.durationMs > 0) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(ui.progress).height(2.dp).background(Palette.accent))
            }
        }

        if (watch.settingsOpen && !pip) PlayerSettingsSheet(vm, watch, ui, onDismiss = { watch.settingsOpen = false })
    }
}

// Lays a child out at a rectangle read at layout time, so the video can follow a swipe
// without recomposing every frame.
private fun Modifier.placeAt(rect: () -> Rect): Modifier = layout { measurable, _ ->
    val r = rect()
    val placeable = measurable.measure(
        Constraints.fixed(r.width.roundToInt().coerceAtLeast(0), r.height.roundToInt().coerceAtLeast(0))
    )
    layout(placeable.width, placeable.height) { placeable.place(r.left.roundToInt(), r.top.roundToInt()) }
}

// Keeps touches from reaching the pages underneath.
private fun Modifier.blockTouches(): Modifier = pointerInput(Unit) {
    awaitEachGesture { awaitFirstDown(requireUnconsumed = false) }
}

// Sideways (axis 1) or up-and-down (axis 2) swipes, with the distance moved in window
// coordinates and the velocity at the end.
private fun Modifier.swipes(
    watch: WatchState,
    onSwipe: (axis: Int, delta: Offset, startMini: Float) -> Unit,
    onEnd: (axis: Int, delta: Offset, velocity: Offset, startMini: Float) -> Unit,
): Modifier = composed {
    val coords = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val swipe by rememberUpdatedState(onSwipe)
    val end by rememberUpdatedState(onEnd)
    val lockDistance = with(LocalDensity.current) { 14.dp.toPx() }
    this
        .onGloballyPositioned { coords[0] = it }
        .pointerInput(Unit) {
            val tracker = VelocityTracker()
            var axis = 0
            var start = Offset.Zero
            var delta = Offset.Zero
            var startMini = 0f
            fun windowPos(local: Offset) = coords[0]?.takeIf { it.isAttached }?.localToWindow(local) ?: local
            try {
                detectDragGestures(
                    onDragStart = { local ->
                        watch.dragFromMini = watch.isMini
                        watch.dragging = true
                        tracker.resetTracking()
                        axis = 0
                        start = windowPos(local)
                        delta = Offset.Zero
                        startMini = watch.mini.value
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val pos = windowPos(change.position)
                        tracker.addPosition(change.uptimeMillis, pos)
                        delta = pos - start
                        if (axis == 0 && delta.getDistance() > lockDistance)
                            axis = if (abs(delta.x) > abs(delta.y)) 1 else 2
                        if (axis != 0) swipe(axis, delta, startMini)
                    },
                    onDragEnd = {
                        watch.dragging = false
                        val v = tracker.calculateVelocity()
                        end(axis, delta, Offset(v.x, v.y), startMini)
                    },
                    onDragCancel = {
                        watch.dragging = false
                        end(axis, delta, Offset.Zero, startMini)
                    },
                )
            } finally {
                // The handler went away mid-drag (picture-in-picture, full screen): end it all the same,
                // or the player would stay laid out for a drag that never ends.
                if (watch.dragging) {
                    watch.dragging = false
                    end(axis, delta, Offset.Zero, startMini)
                }
            }
        }
}

// The "10 seconds" bubble for skipping: a circle's edge coming in from the side, with the total.
@Composable
private fun androidx.compose.foundation.layout.BoxScope.SeekBubble(watch: WatchState, side: Int) {
    AnimatedVisibility(
        visible = watch.seekSide == side,
        enter = fadeIn(tween(120)),
        exit = fadeOut(tween(200)),
        modifier = Modifier.align(if (side < 0) Alignment.CenterStart else Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(0.38f),
    ) {
        val shape = if (side < 0) RoundedCornerShape(topEndPercent = 50, bottomEndPercent = 50)
                    else RoundedCornerShape(topStartPercent = 50, bottomStartPercent = 50)
        Box(Modifier.fillMaxSize().background(Palette.pressed, shape), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                AppIcon(if (side < 0) AppIcons.rewind else AppIcons.forward, size = 30.dp)
                Text("${watch.seekTotal} seconds", color = Palette.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

// A sideways swipe's seek is over: land where it was going (if anywhere yet), unless the
// video changed or closed under it.
private fun endSwipeSeek(vm: VesselViewModel, watch: WatchState, land: Boolean = true) {
    if (watch.swipeFromMs < 0) return
    vm.setScrubbing(false)
    if (land && watch.swipeToMs >= 0) vm.seekTo(watch.swipeToMs)
    watch.swipeFromMs = -1
    watch.swipeToMs = -1
}

// While swiping sideways to seek: how far, and where that lands ("+0:15", "12:34 / 45:00").
@Composable
private fun SwipeSeekPill(watch: WatchState, durationMs: Long, modifier: Modifier) {
    val diff = (watch.swipeToMs - watch.swipeFromMs) / 1000.0
    Column(
        modifier.background(Palette.badge, RoundedCornerShape(16.dp)).padding(horizontal = 18.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text((if (diff < 0) "-" else "+") + formatTime(abs(diff)), color = Palette.textPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("${formatTime(watch.swipeToMs / 1000.0)} / ${formatTime(durationMs / 1000.0)}", color = Palette.textSecondary, fontSize = 13.sp)
    }
}

// Play/pause, previous/next, time, seek bar, full screen and settings, over the video.
@Composable
private fun androidx.compose.foundation.layout.BoxScope.PlayerControls(
    vm: VesselViewModel,
    watch: WatchState,
    ui: PlayerUiState,
    queue: Queue?,
    countdown: Int,
    autoplay: Boolean,
    video: Video,
    hasError: Boolean,
    onMinimize: () -> Unit,
) {
    var scrubbing by remember { mutableStateOf<Float?>(null) }
    val shown = watch.controlsShown || scrubbing != null || countdown > 0
    val alpha by animateFloatAsState(if (shown) 1f else 0f, tween(200), label = "controls")
    val full = watch.full
    val bottomInset = if (full) 12.dp else 0.dp

    if (alpha > 0f) {
        Box(Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha }) {
            Box(Modifier.fillMaxSize().background(Color(0x73000000)))

            // Top: down to the mini player (or out of full screen), the title, settings.
            Row(
                Modifier.fillMaxWidth().align(Alignment.TopStart).padding(horizontal = 4.dp, vertical = if (full) 12.dp else 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconBtn(AppIcons.expandMore, onClick = { if (full) watch.full = false else onMinimize() }, iconSize = 28.dp,
                        enabled = shown, contentDescription = "Minimize")
                Text(if (full) video.title else "", Modifier.weight(1f), color = Palette.textPrimary, fontSize = 16.sp,
                     fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconBtn(AppIcons.settings, onClick = { watch.settingsOpen = true; watch.wake() }, enabled = shown,
                        tint = if (ui.speed != 1f || ui.repeat) Palette.accent else Palette.textPrimary, contentDescription = "Settings")
            }

            // Middle: previous, play/pause, next; or the "Up next" card while autoplay counts down.
            if (countdown > 0 && queue?.next != null) {
                UpNextCard(queue.next!!, countdown, Modifier.align(Alignment.Center),
                           onCancel = { vm.cancelAutoplay() }, onPlayNow = { vm.playNext() })
            } else if (!hasError) {
                Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(if (full) 40.dp else 24.dp)) {
                    IconBtn(AppIcons.skipPrevious, onClick = { vm.playPrevious(); watch.wake() }, iconSize = 30.dp, size = 48.dp,
                            enabled = shown && queue?.previous != null, contentDescription = "Previous video")
                    Box(Modifier.size(60.dp), contentAlignment = Alignment.Center) {
                        if (ui.buffering) {
                            CircularProgressIndicator(Modifier.size(48.dp), color = Palette.accent, strokeWidth = 3.dp)
                        } else {
                            Box(Modifier.size(60.dp).background(Palette.accent, CircleShape)
                                    .clickable(enabled = shown) { vm.togglePlay(); watch.wake() },
                                contentAlignment = Alignment.Center) {
                                AppIcon(if (ui.ended) AppIcons.replay else if (ui.isPlaying) AppIcons.pause else AppIcons.play,
                                        tint = Palette.accentText, size = 34.dp)
                            }
                        }
                    }
                    IconBtn(AppIcons.skipNext, onClick = { vm.playNext(); watch.wake() }, iconSize = 30.dp, size = 48.dp,
                            enabled = shown && queue?.next != null, contentDescription = "Next video")
                }
            }

            // Bottom: the time and full screen; in full screen the seek bar sits above them.
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(bottom = bottomInset)) {
                Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, bottom = if (full) 0.dp else 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    val position = scrubbing?.let { it * ui.durationMs } ?: ui.positionMs.toFloat()
                    Text("${formatTime(position / 1000.0)} / ${formatTime(ui.durationMs / 1000.0)}",
                         color = Palette.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    if (!autoplay) {
                        Text("  •  Autoplay off", color = Palette.textSecondary, fontSize = 12.sp)
                    }
                    Spacer(Modifier.weight(1f))
                    IconBtn(if (full) AppIcons.fullscreenExit else AppIcons.fullscreen, onClick = { watch.full = !full; watch.wake() },
                            enabled = shown, contentDescription = "Full screen")
                }
                if (full) {
                    SeekBar(
                        fraction = ui.progress,
                        buffered = if (ui.durationMs > 0) ui.bufferedMs.toFloat() / ui.durationMs else 0f,
                        showThumb = true,
                        onScrubStart = { vm.setScrubbing(true); watch.wake() },
                        onScrub = { f -> scrubbing = f; vm.seekTo((f * ui.durationMs).toLong()); watch.wake() },
                        onScrubEnd = { f -> vm.setScrubbing(false); vm.seekTo((f * ui.durationMs).toLong()); scrubbing = null; ui.update() },
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
        }
    }

    // On the watch page the seek bar runs along the video's bottom edge, half over it.
    if (!full && ui.durationMs > 0 && watch.mini.value < 0.2f && shown) {
        SeekBar(
            fraction = ui.progress,
            buffered = if (ui.durationMs > 0) ui.bufferedMs.toFloat() / ui.durationMs else 0f,
            showThumb = true,
            onScrubStart = { vm.setScrubbing(true); watch.wake() },
            onScrub = { f -> scrubbing = f; vm.seekTo((f * ui.durationMs).toLong()); watch.wake() },
            onScrubEnd = { f -> vm.setScrubbing(false); vm.seekTo((f * ui.durationMs).toLong()); scrubbing = null; ui.update() },
            modifier = Modifier.align(Alignment.BottomStart).offset(y = 14.dp),
        )
    }

    // What just changed ("Speed 1.5×").
    if (watch.flashText.isNotEmpty()) {
        Text(watch.flashText,
             Modifier.align(Alignment.TopCenter).padding(top = if (full) 64.dp else 48.dp)
                 .background(Palette.badge, CircleShape).padding(horizontal = 14.dp, vertical = 6.dp),
             color = Palette.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun UpNextCard(next: Video, seconds: Int, modifier: Modifier, onCancel: () -> Unit, onPlayNow: () -> Unit) {
    Column(modifier.padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally,
           verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Up next in $seconds", color = Palette.textSecondary, fontSize = 13.sp)
        Text(next.title, color = Palette.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 2,
             overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
            PillButton("Cancel", null, onCancel, soft = true)
            PillButton("Play now", AppIcons.play, onPlayNow)
        }
    }
}

// Below the video: its details, the rest of its folder (or the Play all list), more videos.
@Composable
private fun WatchDetails(
    vm: VesselViewModel,
    video: Video,
    ui: PlayerUiState,
    queue: Queue?,
    suggestions: List<Video>,
    modifier: Modifier,
) {
    val list = rememberLazyListState()
    var queueOpen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(video.key) { list.scrollToItem(0) }

    LazyColumn(modifier, state = list, contentPadding = PaddingValues(top = 20.dp)) {
        item {
            Column(Modifier.padding(horizontal = Metrics.pagePadding), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(video.title, color = Palette.textPrimary, fontSize = 19.sp, fontWeight = FontWeight.Bold, lineHeight = 24.sp)
                // Created: when it was recorded (a camera's tag, else the file's date). Added: when it arrived.
                val created = if (video.takenMs > 0) video.takenMs else video.modifiedMs
                Text(listOf(video.folderName, formatTime(ui.durationMs.coerceAtLeast(video.durationMs) / 1000.0),
                            if (created > 0) "Created ${dateText(created)}" else "",
                            if (video.addedMs > 0) "Added ${dateText(video.addedMs)}" else "")
                         .filter { it.isNotEmpty() }.joinToString("  •  "),
                     color = Palette.textSecondary, fontSize = 13.sp)
                FileInfo(vm, video, ui)
            }
        }

        if (queue != null && queue.videos.size > 1) {
            item {
                QueueHeader(queue, queueOpen, onToggle = { queueOpen = !queueOpen })
            }
            if (queueOpen) {
                items(queue.videos, key = { "q" + it.key }) { v ->
                    VideoRow(v, durationTextOf(v), Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                             current = v.key == video.key, onClick = { vm.open(v) })
                }
            }
        }

        if (suggestions.isNotEmpty()) {
            item {
                Text("More videos", Modifier.padding(start = Metrics.pagePadding, top = 24.dp, bottom = 12.dp),
                     color = Palette.textPrimary, fontSize = Metrics.subsectionSize, fontWeight = FontWeight.Bold)
            }
            items(suggestions, key = { "s" + it.key }) { v ->
                VideoCard(v, v.folderName, Modifier.fillMaxWidth().padding(horizontal = Metrics.pagePadding, vertical = 6.dp),
                          onClick = { vm.open(v) })
            }
        }
        item { Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars).height(24.dp)) }
    }
}

private fun durationTextOf(v: Video) = com.better_video_player.data.durationText(v.durationMs)

// "MP4  •  H.264 (AVC)  •  1920 × 1080  •  30 fps  •  1.4 GB", and where the file is.
@Composable
private fun FileInfo(vm: VesselViewModel, video: Video, ui: PlayerUiState) {
    val type = video.fileName.substringAfterLast('.', "").uppercase()
    // "30 fps", "29.97 fps", "59.9 fps": with a dot, whatever the phone's language.
    val fps = ui.frameRate.takeIf { it > 0 }?.let { f ->
        "%.2f".format(java.util.Locale.ROOT, f).trimEnd('0').trimEnd('.') + " fps"
    } ?: ""
    val width = if (ui.videoWidth > 0) ui.videoWidth else video.width
    val height = if (ui.videoHeight > 0) ui.videoHeight else video.height
    val details = listOf(type, ui.videoCodec, if (width > 0) "$width × $height" else "", fps, sizeText(video.size),
                         // Which engine is playing it, when it isn't the usual one.
                         if (ui.engine.name != "ExoPlayer") "Played by ${ui.engine.name}" else "")
        .filter { it.isNotEmpty() }.joinToString("  •  ")
    val location = vm.library.locationOf(video)

    Column(
        Modifier.padding(top = 8.dp).fillMaxWidth().background(Palette.surfaceHigh, RoundedCornerShape(Metrics.radiusArt)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(video.fileName, color = Palette.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(details, color = Palette.textSecondary, fontSize = 13.sp)
        if (location.isNotEmpty()) {
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AppIcon(AppIcons.folderOpen, tint = Palette.accent, size = 16.dp)
                Text(location, color = Palette.accent, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// The folder (or Play all list), as on YouTube's playlist panel: folded to what's next, or open.
@Composable
private fun QueueHeader(queue: Queue, open: Boolean, onToggle: () -> Unit) {
    Column(
        Modifier.padding(start = Metrics.pagePadding, end = Metrics.pagePadding, top = 20.dp, bottom = 4.dp)
            .fillMaxWidth()
            .background(Palette.surface, RoundedCornerShape(Metrics.radiusArt))
            .clickable(onClick = onToggle)
            .padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (queue.playlist) (if (queue.shuffled) "Shuffle: " else "Play all: ") + queue.name else queue.name,
                     color = Palette.textPrimary, fontSize = Metrics.subsectionSize, fontWeight = FontWeight.Bold,
                     maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (open || queue.next == null) "${queue.index + 1} / ${queue.videos.size}"
                    else "Next: ${queue.next!!.title}  •  ${queue.index + 1} / ${queue.videos.size}",
                    color = Palette.textSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            AppIcon(AppIcons.expandMore, Modifier.graphicsLayer { rotationZ = if (open) 180f else 0f }.padding(8.dp), tint = Palette.textSecondary)
        }
    }
}

// Full screen: the system bars hide, and a landscape video turns the screen sideways.
@Composable
private fun FullScreenEffect(activity: Activity?, full: Boolean, portraitVideo: Boolean) {
    if (activity == null) return
    DisposableEffect(full, portraitVideo) {
        val window = activity.window
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (full) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
            activity.requestedOrientation =
                if (portraitVideo) MainActivity.normalOrientation(activity) else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        onDispose {
            controller.show(WindowInsetsCompat.Type.systemBars())
            activity.requestedOrientation = MainActivity.normalOrientation(activity)
        }
    }
}

// As on YouTube: turning the phone sideways on the watch page goes full screen, and turning it
// back upright leaves it. Only with auto-rotate on, and only once the phone has actually been
// turned the other way since the last change (so the full screen button isn't undone at once).
@Composable
private fun RotateToFullScreen(activity: Activity?, watch: WatchState, enabled: Boolean) {
    val context = LocalContext.current
    if (activity == null || !MainActivity.isPhone(activity)) return
    val active by rememberUpdatedState(enabled)
    DisposableEffect(Unit) {
        var armed = false
        var lastFull = watch.full
        // Read once and then followed: the sensor fires many times a second, and each read is a
        // call into another process.
        fun readAutoRotate() = Settings.System.getInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1
        var autoRotate = readAutoRotate()
        val rotationObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                autoRotate = readAutoRotate()
            }
        }
        context.contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.ACCELEROMETER_ROTATION), false, rotationObserver)
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(degrees: Int) {
                if (degrees == ORIENTATION_UNKNOWN || !active) return
                if (!autoRotate) return
                if (watch.full != lastFull) {
                    lastFull = watch.full
                    armed = false
                }
                val landscape = degrees in 65..115 || degrees in 245..295
                val upright = degrees <= 25 || degrees >= 335
                if (!watch.full) {
                    if (upright) armed = true
                    else if (landscape && armed) {
                        watch.full = true
                        lastFull = true
                        armed = false
                    }
                } else {
                    if (landscape) armed = true
                    else if (upright && armed) {
                        watch.full = false
                        lastFull = false
                        armed = false
                    }
                }
            }
        }
        listener.enable()
        onDispose {
            listener.disable()
            context.contentResolver.unregisterContentObserver(rotationObserver)
        }
    }
}
