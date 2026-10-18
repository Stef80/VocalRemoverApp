package com.example.vocalremover.decode

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.vocalremover.testaudio.TestAudioFiles
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

// Nomi dei test senza spazi: D8 li rifiuta nei metodi con minSdk < 30.
@RunWith(AndroidJUnit4::class)
class AudioDecoderInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir: File = context.cacheDir
    private val decoder = AudioDecoder(context)

    @Test
    fun stereoWavAt44100KeepsLengthAndChannels() {
        val pcm = decoder.decode(Uri.fromFile(TestAudioFiles.wav(dir, "stereo.wav", 2.0)))

        assertTrue("size=${pcm.size}", abs(pcm.size - 88200) <= 1)
        val peak = pcm.left.maxOf { abs(it) }
        assertEquals(0.5f, peak, 0.02f)
    }

    @Test
    fun monoWavIsDuplicatedOnBothChannels() {
        val pcm = decoder.decode(Uri.fromFile(TestAudioFiles.wav(dir, "mono.wav", 1.0, channels = 1)))

        assertArrayEquals(pcm.left, pcm.right, 0f)
    }

    @Test
    fun wavAt22050IsResampledTo44100() {
        val pcm = decoder.decode(Uri.fromFile(TestAudioFiles.wav(dir, "low.wav", 1.0, sampleRate = 22050)))

        assertTrue("size=${pcm.size}", abs(pcm.size - 44100) <= 2)
    }

    @Test
    fun aacMono22050DecodesTo44100Stereo() {
        val file = TestAudioFiles.aac(dir, "mono22.m4a", 2.0, sampleRate = 22050, channels = 1)

        val pcm = decoder.decode(Uri.fromFile(file))

        // L'encoder aggiunge il priming (1024–2048 campioni a 22 050 Hz) e MediaMuxer non lo
        // taglia: in uscita possono esserci fino a ~6000 frame in più a 44,1 kHz.
        assertTrue("size=${pcm.size}", pcm.size in (88200 - 2205)..(88200 + 3 * 2048 * 2))
        assertEquals(pcm.left.size, pcm.right.size)
    }

    @Test
    fun emptyWavThrowsDecodeException() {
        val uri = Uri.fromFile(TestAudioFiles.emptyWav(dir, "empty.wav"))

        assertThrows(DecodeException::class.java) { decoder.decode(uri) }
    }

    @Test
    fun garbageFileThrowsDecodeException() {
        val uri = Uri.fromFile(TestAudioFiles.garbage(dir, "garbage.mp3"))

        assertThrows(DecodeException::class.java) { decoder.decode(uri) }
    }

    @Test
    fun missingFileThrowsDecodeException() {
        val uri = Uri.fromFile(File(dir, "nope.wav").apply { delete() })

        assertThrows(DecodeException::class.java) { decoder.decode(uri) }
    }
}
