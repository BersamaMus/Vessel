package com.better_video_player.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.better_video_player.ui.AppIcon
import com.better_video_player.ui.AppIcons
import com.better_video_player.ui.IconBtn
import com.better_video_player.ui.Metrics
import com.better_video_player.ui.PageTitle
import com.better_video_player.ui.Palette

// The top of a page (its title and buttons), pinned while the rest scrolls under it.
// A hairline shows once something has scrolled beneath.
@Composable
fun PageBar(scrolled: Boolean, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().statusBarsPadding()) {
        content()
        Box(Modifier.padding(top = 4.dp).fillMaxWidth().height(1.dp)
                .background(if (scrolled) Palette.borderSoft else Color.Transparent))
    }
}

// A page bar with a title (and a back button on pages opened from a tab).
@Composable
fun TitleBar(
    title: String,
    subtitle: String = "",
    scrolled: Boolean,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    PageBar(scrolled) {
        Row(Modifier.padding(start = if (onBack != null) 4.dp else Metrics.pagePadding, end = 4.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) IconBtn(AppIcons.back, onClick = onBack, contentDescription = "Back")
            PageTitle(title, subtitle, Modifier.weight(1f).padding(start = if (onBack != null) 4.dp else 0.dp))
            actions()
        }
    }
}

// The sort button and its menu: options are (value, label) pairs.
@Composable
fun <T> SortButton(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconBtn(AppIcons.sort, onClick = { open = true }, contentDescription = "Sort")
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = Palette.dialog) {
            Text("Sort by", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = Palette.textTertiary)
            for ((value, label) in options) {
                DropdownMenuItem(
                    text = { Text(label, color = if (value == selected) Palette.accent else Palette.textPrimary) },
                    leadingIcon = { if (value == selected) AppIcon(AppIcons.check, tint = Palette.accent) else Box(Modifier.height(24.dp)) },
                    onClick = {
                        onSelect(value)
                        open = false
                    },
                )
            }
        }
    }
}
