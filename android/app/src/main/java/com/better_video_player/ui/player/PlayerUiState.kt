package com.better_video_player.ui.player

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
import androidx.compose.runtime.setValue
import com.better_video_player.engine.EngineListener
import com.better_video_player.engine.Phase
import com.better_video_player.engine.TrackInfo
import com.better_video_player.engine.TrackType
import com.better_video_player.engine.VideoEngine
import kotlinx.coroutines.delay

// The playing engine's state for Compose: updated when it says something changed, and the
// position polled while something on screen shows it.
@Stable
class PlayerUiState(val engine: VideoEngine) : EngineListener {
    var isPlaying by mutableStateOf(false)
        private set
    var phase by mutableStateOf(Phase.Idle)
        private set
    var durationMs by mutableLongStateOf(0L)
        private set
    var positionMs by mutableLongStateOf(0L)
        private set
    var bufferedMs by mutableLongStateOf(0L)
        private set
    var tracks by mutableStateOf<List<TrackInfo>>(emptyList())
        private set
    var speed by mutableFloatStateOf(1f)
        private set
    var repeat by mutableStateOf(false)
        private set
    var videoWidth by mutableIntStateOf(0)
        private set
    var videoHeight by mutableIntStateOf(0)
        private set
    var videoCodec by mutableStateOf("")
        private set
    var frameRate by mutableFloatStateOf(0f)
        private set

    val ended: Boolean get() = phase == Phase.Ended
    val buffering: Boolean get() = phase == Phase.Buffering
    val progress: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val audioTracks: List<TrackInfo> get() = tracks.filter { it.type == TrackType.Audio }
    val subtitleTracks: List<TrackInfo> get() = tracks.filter { it.type == TrackType.Subtitle }

    fun update() {
        positionMs = engine.positionMs
        bufferedMs = engine.bufferedMs
        durationMs = engine.durationMs
    }

    override fun onChanged() {
        isPlaying = engine.isPlaying
        phase = engine.phase
        speed = engine.speed
        repeat = engine.repeat
        videoWidth = engine.videoWidth
        videoHeight = engine.videoHeight
        videoCodec = engine.videoCodec
        frameRate = engine.frameRate
        val t = engine.tracks
        if (t != tracks) tracks = t
        update()
    }
}

@Composable
fun rememberPlayerUiState(engine: VideoEngine): PlayerUiState {
    val state = remember(engine) { PlayerUiState(engine) }
    DisposableEffect(engine) {
        engine.addListener(state)
        state.onChanged()
        onDispose { engine.removeListener(state) }
    }
    // The position, a few times a second while playing.
    LaunchedEffect(state, state.isPlaying) {
        while (true) {
            state.update()
            delay(if (state.isPlaying) 200 else 500)
        }
    }
    return state
}
