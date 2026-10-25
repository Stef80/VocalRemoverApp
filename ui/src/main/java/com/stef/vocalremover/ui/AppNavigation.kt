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

/** Le schede del footer, nell'ordine in cui compaiono. */
enum class AppTab(@param:DrawableRes val icon: Int, @param:StringRes val label: Int) {
    Library(R.drawable.ic_tab_library, R.string.tab_library),
    Capture(R.drawable.ic_tab_capture, R.string.tab_capture),
    Player(R.drawable.ic_tab_player, R.string.tab_player),
}

/** Regole di navigazione tra le schede, senza Compose per poterle testare in JVM. */
object AppNavigation {

    /** La scheda da mostrare: il Player senza brano (es. dopo la morte del processo) ricade sulla Libreria. */
    fun resolve(selected: AppTab, hasTrack: Boolean): AppTab =
        if (selected == AppTab.Player && !hasTrack) AppTab.Library else selected

    /** Tasto Indietro: da Cattura e Player si torna alla Libreria; `null` = dalla Libreria si esce dall'app. */
    fun back(current: AppTab): AppTab? = if (current == AppTab.Library) null else AppTab.Library
}

/** Footer comune alle tre schede. Il Player resta disabilitato finché non si apre un brano. */
@Composable
fun AppBottomBar(selected: AppTab, playerEnabled: Boolean, onSelect: (AppTab) -> Unit) {
    // Le schermate applicano già safeDrawingPadding: niente inset propri per non contarli due volte.
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
