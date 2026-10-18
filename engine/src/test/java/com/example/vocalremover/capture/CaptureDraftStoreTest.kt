package com.example.vocalremover.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CaptureDraftStoreTest {

    private val store = CaptureDraftStore(RuntimeEnvironment.getApplication())

    @Test
    fun `draft round trips for the same capture`() {
        val draft = CaptureDraft("a", "Canzone", "/tmp/x_tagliato.wav", 1000, 5000)
        store.write(draft)
        assertEquals(draft, store.read("a"))
    }

    @Test
    fun `draft for another capture is discarded`() {
        store.write(CaptureDraft("a", "Canzone", null, 0, 5000))
        assertNull(store.read("b"))
        assertNull(store.read("a"))
    }
}
