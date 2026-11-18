package com.better_video_player.ui

import android.app.Activity
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.better_video_player.VesselViewModel
import com.better_video_player.data.FileOutcome
import com.better_video_player.data.Video
import com.better_video_player.ui.screens.NameDialog
import com.better_video_player.ui.screens.SheetItem
import kotlinx.coroutines.launch

// Runs a change to a file (VesselViewModel.renameVideo / moveVideo / deleteVideo). When
// Android wants the user's say first, its own dialog is shown, and the change is tried again
// if they agree. The outcome is told in a note at the bottom.
class FileRunner(val run: (doneMessage: String, change: suspend () -> FileOutcome) -> Unit)

@Composable
fun rememberFileRunner(vm: VesselViewModel): FileRunner {
    val scope = rememberCoroutineScope()
    // What to do once the user has answered Android's dialog.
    var waiting by remember { mutableStateOf<Pair<String, suspend () -> FileOutcome>?>(null) }
    val handle = remember { arrayOfNulls<(String, FileOutcome) -> Unit>(1) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val (message, retry) = waiting ?: return@rememberLauncherForActivityResult
        waiting = null
        if (result.resultCode == Activity.RESULT_OK) scope.launch { handle[0]?.invoke(message, retry()) }
    }
    handle[0] = { message, outcome ->
        when (outcome) {
            FileOutcome.Done -> vm.toast(message)
            is FileOutcome.Failed -> vm.toast(outcome.message)
            is FileOutcome.NeedsConsent -> {
                waiting = message to outcome.retry
                launcher.launch(IntentSenderRequest.Builder(outcome.request).build())
            }
        }
    }
    return remember { FileRunner { message, change -> scope.launch { handle[0]?.invoke(message, change()) } } }
}

// Rename, Move and Delete from a video's menu, as on the desktop.
@Composable
fun RenameVideoDialog(vm: VesselViewModel, runner: FileRunner, video: Video, onDismiss: () -> Unit) {
    NameDialog("Rename", video.fileName.substringBeforeLast('.'), "Name", "Rename", onDismiss) { name ->
        runner.run("Renamed to $name") { vm.renameVideo(video, name) }
    }
}

// The folders it can go to: the library's on the same storage (Android keeps videos within
// a few top folders, such as Movies and DCIM).
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoveVideoSheet(vm: VesselViewModel, runner: FileRunner, video: Video, onDismiss: () -> Unit) {
    val targets = remember(video) { vm.moveTargets(video) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Palette.dialog) {
        Text("Move “${video.title}” to", Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = Palette.textPrimary,
             fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 2)
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
            items(targets) { path ->
                SheetItem(AppIcons.folder, (vm.library.volumeLabel(video.volume) + "/" + path).trimEnd('/')) {
                    onDismiss()
                    runner.run("Moved to ${path.trimEnd('/').substringAfterLast('/')}") { vm.moveVideo(video, path) }
                }
            }
        }
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars).height(12.dp))
    }
}

// On Android 11+ the video goes to the phone's trash, and Android asks in its own dialog; on
// Android 10 there's no trash, so this asks first: it's deleted for good.
@Composable
fun DeleteVideo(vm: VesselViewModel, runner: FileRunner, video: Video, onDismiss: () -> Unit) {
    if (Build.VERSION.SDK_INT >= 30) {
        androidx.compose.runtime.LaunchedEffect(video.key) {
            runner.run("Moved to the trash") { vm.deleteVideo(video) }
            onDismiss()
        }
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.dialog,
        title = { Text("Delete “${video.title}”?") },
        text = { Text("${video.fileName} is deleted from the phone for good.") },
        confirmButton = { TextAction("Delete", { onDismiss(); runner.run("Deleted") { vm.deleteVideo(video) } }) },
        dismissButton = { TextAction("Cancel", onDismiss) },
    )
}
