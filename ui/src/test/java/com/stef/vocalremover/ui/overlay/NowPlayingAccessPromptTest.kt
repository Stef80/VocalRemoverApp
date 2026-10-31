package com.stef.vocalremover.ui.overlay

import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NowPlayingAccessPromptTest {

    private val prefs = RuntimeEnvironment.getApplication()
        .getSharedPreferences("now_playing_prompt_test", Context.MODE_PRIVATE)
        .also { it.edit().clear().commit() }

    @Test
    fun nonConcessoEMaiChiestoSiChiede() =
        assertTrue(NowPlayingAccessPrompt(prefs) { false }.shouldAsk())

    @Test
    fun dopoLaPrimaRichiestaNonSiChiedePiu() {
        NowPlayingAccessPrompt(prefs) { false }.markAsked()
        assertFalse(NowPlayingAccessPrompt(prefs) { false }.shouldAsk())
    }

    @Test
    fun giaConcessoNonSiChiede() =
        assertFalse(NowPlayingAccessPrompt(prefs) { true }.shouldAsk())

    @Test
    fun impostazioniAssentiSiProsegueSenza() {
        var proseguito = false
        openSettingsOrContinue(
            launch = { throw android.content.ActivityNotFoundException() },
            onUnavailable = { proseguito = true },
        )
        assertTrue(proseguito)
    }
}
