package com.stef.vocalremover.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.example.vocalremover.ui.R
import com.stef.vocalremover.ui.theme.VocalRemoverTheme

/** The footer tabs, in the order they appear. */
enum class AppTab(@param:DrawableRes val icon: Int, @param:StringRes val label: Int) {
    Library(R.drawable.ic_tab_library, R.string.tab_library),
    Capture(R.drawable.ic_tab_capture, R.string.tab_capture),
    Player(R.drawable.ic_tab_player, R.string.tab_player),
}

/** Navigation rules between tabs, without Compose so they can be tested on the JVM. */
object AppNavigation {

    /** The tab to show: the Player without a track (e.g. after process death) falls back to the Library. */
    fun resolve(selected: AppTab, hasTrack: Boolean): AppTab =
        if (selected == AppTab.Player && !hasTrack) AppTab.Library else selected

    /** Back key: from Capture and Player go back to the Library; `null` = from the Library the app exits. */
    fun back(current: AppTab): AppTab? = if (current == AppTab.Library) null else AppTab.Library
}

/** Footer shared by the three tabs. The Player stays disabled until a track is opened. */
@Composable
fun AppBottomBar(selected: AppTab, playerEnabled: Boolean, onSelect: (AppTab) -> Unit) {
    // Screens already apply safeDrawingPadding: no insets of its own, so they are not counted twice.
    NavigationBar(windowInsets = WindowInsets(0)) {
        AppTab.entries.forEach { tab ->
            NavigationBarItem(
                selected = tab == selected,
                onClick = { onSelect(tab) },
                enabled = tab != AppTab.Player || playerEnabled,
                icon = { Icon(painterResource(tab.icon), contentDescription = null) },
                label = { Text(stringResource(tab.label)) },
                alwaysShowLabel = true,
            )
        }
    }
}

@Preview
@Composable
private fun AppBottomBarPreview() {
    VocalRemoverTheme {
        AppBottomBar(selected = AppTab.Library, playerEnabled = false, onSelect = {})
    }
}
