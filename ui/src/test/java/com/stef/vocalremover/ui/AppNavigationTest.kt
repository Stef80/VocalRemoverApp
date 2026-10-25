package com.stef.vocalremover.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppNavigationTest {

    @Test
    fun `player senza brano ricade sulla libreria`() =
        assertEquals(AppTab.Library, AppNavigation.resolve(AppTab.Player, hasTrack = false))

    @Test
    fun `player con brano resta player`() =
        assertEquals(AppTab.Player, AppNavigation.resolve(AppTab.Player, hasTrack = true))

    @Test
    fun `cattura e libreria non dipendono dal brano`() {
        assertEquals(AppTab.Capture, AppNavigation.resolve(AppTab.Capture, hasTrack = false))
        assertEquals(AppTab.Library, AppNavigation.resolve(AppTab.Library, hasTrack = true))
    }

    @Test
    fun `indietro da cattura e player torna alla libreria`() {
        assertEquals(AppTab.Library, AppNavigation.back(AppTab.Capture))
        assertEquals(AppTab.Library, AppNavigation.back(AppTab.Player))
    }

    @Test
    fun `indietro dalla libreria lascia uscire l'app`() = assertNull(AppNavigation.back(AppTab.Library))
}
