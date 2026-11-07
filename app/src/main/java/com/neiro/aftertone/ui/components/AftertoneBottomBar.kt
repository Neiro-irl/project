package com.neiro.aftertone.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.neiro.aftertone.AppTab
import com.neiro.aftertone.model.Track
import com.neiro.aftertone.ui.theme.AftertoneBackground
import com.neiro.aftertone.ui.theme.AftertoneMuted
import com.neiro.aftertone.ui.theme.AftertoneSurfaceHigh
import com.neiro.aftertone.ui.theme.AftertoneText

@Composable
internal fun AftertoneBottomBar(
    selectedTab: AppTab,
    collectionOpen: Boolean,
    currentTrack: Track?,
    isPlaying: Boolean,
    currentPositionMs: Long,
    durationMs: Long,
    onTabSelected: (AppTab) -> Unit,
    onOpenNowPlaying: () -> Unit,
    onPlayPause: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AftertoneBackground)
            .navigationBarsPadding(),
    ) {
        currentTrack?.let { track ->
            Surface(
                color = AftertoneSurfaceHigh,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
            ) {
                MiniPlayer(
                    track = track,
                    isPlaying = isPlaying,
                    currentPositionMs = currentPositionMs,
                    durationMs = durationMs.takeIf { it > 0 } ?: track.durationMs,
                    onOpen = onOpenNowPlaying,
                    onPlayPause = onPlayPause,
                )
            }
        }
        NavigationBar(
            containerColor = AftertoneBackground,
            windowInsets = WindowInsets(0, 0, 0, 0),
        ) {
            AppTab.entries.forEach { tab ->
                val icon = when (tab) {
                    AppTab.HOME -> Icons.Filled.Home
                    AppTab.LIBRARY -> Icons.Filled.LibraryMusic
                    AppTab.COLLECTIONS -> Icons.AutoMirrored.Filled.QueueMusic
                }
                NavigationBarItem(
                    selected = selectedTab == tab && !collectionOpen,
                    onClick = { onTabSelected(tab) },
                    icon = { Icon(icon, contentDescription = tab.label) },
                    label = { Text(tab.label) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = AftertoneSurfaceHigh,
                        unselectedIconColor = AftertoneMuted,
                        unselectedTextColor = AftertoneMuted,
                    ),
                )
            }
        }
    }
}
