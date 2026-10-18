package com.example.vocalremover.playback

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerErrorMappingTest {

    @Test
    fun `file not found maps to SOURCE_UNAVAILABLE`() {
        assertEquals(
            PlayerError.SOURCE_UNAVAILABLE,
            playerErrorFor(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
        )
    }

    @Test
    fun `no permission maps to SOURCE_UNAVAILABLE`() {
        assertEquals(
            PlayerError.SOURCE_UNAVAILABLE,
            playerErrorFor(PlaybackException.ERROR_CODE_IO_NO_PERMISSION)
        )
    }

    @Test
    fun `other codes map to PLAYBACK_FAILED`() {
        assertEquals(PlayerError.PLAYBACK_FAILED, playerErrorFor(PlaybackException.ERROR_CODE_DECODING_FAILED))
        assertEquals(PlayerError.PLAYBACK_FAILED, playerErrorFor(PlaybackException.ERROR_CODE_UNSPECIFIED))
    }
}
