package com.example.vocalremover.playback

import android.net.Uri
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.vocalremover.testaudio.TestAudioFiles
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

// Nomi dei test senza spazi: D8 li rifiuta nei metodi con minSdk < 30.
@RunWith(AndroidJUnit4::class)
class LibraryPlayerInstrumentedTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var player: LibraryPlayer
    private lateinit var song: Uri
    private lateinit var otherSong: Uri

    @Before
    fun setUp() {
        song = Uri.fromFile(TestAudioFiles.wav(context.cacheDir, "song.wav", 3.0))
        otherSong = Uri.fromFile(TestAudioFiles.wav(context.cacheDir, "other.wav", 3.0))
        instrumentation.runOnMainSync { player = LibraryPlayer(context) }
    }

    @After
    fun tearDown() {
        instrumentation.runOnMainSync { player.release() }
    }

    private fun onMain(block: LibraryPlayer.() -> Unit) = instrumentation.runOnMainSync { player.block() }

    private fun awaitState(predicate: (PlayerState) -> Boolean): PlayerState =
        runBlocking { withTimeout(5_000) { player.state.first(predicate) } }

    /**
     * seekTo scrive subito la posizione richiesta nello stato: per sapere dove suona davvero
     * si aspetta che il polling (ogni 200 ms) l'abbia sovrascritta con quella di ExoPlayer.
     */
    private fun assertPlayingFromAtLeast(minMs: Long) {
        awaitState { it.isPlaying }
        SystemClock.sleep(600)
        val polled = player.state.value
        assertTrue("positionMs=${polled.positionMs}", polled.positionMs >= minMs)
    }

    @Test
    fun playsASavedWavAndAdvancesPosition() {
        onMain { play(song) }

        val playing = awaitState { it.isPlaying && it.durationMs > 0 }
        assertTrue("durationMs=${playing.durationMs}", playing.durationMs in 2900..3100)
        awaitState { it.positionMs > 300 }
    }

    @Test
    fun pauseStopsPosition() {
        onMain { play(song) }
        awaitState { it.positionMs > 300 }

        onMain { pause() }
        val paused = awaitState { !it.isPlaying }
        SystemClock.sleep(500)

        assertTrue(abs(player.state.value.positionMs - paused.positionMs) <= 50)
    }

    @Test
    fun seekToMovesPosition() {
        onMain { play(song) }
        awaitState { it.isPlaying }

        onMain { seekTo(2000) }

        assertPlayingFromAtLeast(1900)
    }

    @Test
    fun endOfTrackLeavesPositionAtDurationAndPlayRestartsFromZero() {
        onMain { play(song) }
        awaitState { it.isPlaying && it.durationMs > 0 }
        onMain { seekTo(2800) }

        val ended = awaitState { !it.isPlaying && it.durationMs > 0 && it.positionMs == it.durationMs }
        assertEquals(song, ended.currentUri)

        onMain { play(song) }
        awaitState { it.isPlaying && it.positionMs < 500 }
    }

    @Test
    fun switchingUriRestartsFromZero() {
        onMain { play(song) }
        awaitState { it.positionMs > 300 }

        onMain { play(otherSong) }

        val switched = awaitState { it.currentUri == otherSong && it.isPlaying }
        assertTrue("positionMs=${switched.positionMs}", switched.positionMs < 500)
    }

    @Test
    fun seekBeforeDurationIsKnownIsNotClampedToZero() {
        onMain {
            play(song)
            seekTo(1500)
        }

        assertPlayingFromAtLeast(1400)
    }

    @Test
    fun missingFileReportsSourceUnavailable() {
        val missing = Uri.fromFile(File(context.cacheDir, "nope.wav").apply { delete() })

        onMain { play(missing) }
        awaitState { it.error == PlayerError.SOURCE_UNAVAILABLE }

        onMain { play(song) }
        val recovered = awaitState { it.isPlaying }
        assertNull(recovered.error)
    }

    @Test
    fun callsOffTheMainThreadAreRejected() {
        assertThrows(IllegalStateException::class.java) { player.play(song) }
    }

    @Test
    fun callsAfterReleaseAreRejected() {
        var failure: Throwable? = null
        onMain {
            release()
            failure = runCatching { play(song) }.exceptionOrNull()
        }

        assertTrue("failure=$failure", failure is IllegalStateException)
        assertFalse(player.state.value.isPlaying)
    }
}
