package com.example.vocalremover.library

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.example.vocalremover.capture.CapturedTrackInfo
import com.example.vocalremover.lyrics.LyricsScheduler
import com.example.vocalremover.lyrics.LyricsStatus
import com.example.vocalremover.lyrics.TrackLyrics
import com.example.vocalremover.lyrics.TrackLyricsStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LibraryDeleteLyricsTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val dir = File("build/test-library-lyrics/${System.nanoTime()}")
    private val store = TrackLyricsStore(dir)
    private val cancelled = mutableListOf<String>()
    private val scheduler = object : LyricsScheduler {
        override fun schedule(savedUri: String) = Unit
        override fun cancel(savedUri: String) { cancelled += savedUri }
    }
    private val uri: Uri by lazy {
        val values = ContentValues().apply { put(MediaStore.Audio.Media.DISPLAY_NAME, "Brano_strumentale.wav") }
        context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)!!
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun cancellareIlBranoCancellaAncheIlTestoEIlDownload() {
        store.write(
            TrackLyrics(
                uri.toString(),
                CapturedTrackInfo("Brano", "Gruppo", null, null, null, "com.example.player", null),
                LyricsStatus.PENDING,
            )
        )

        MediaStoreLibraryRepository(context, store, scheduler).delete(uri)

        assertNull(store.read(uri.toString()))
        assertEquals(listOf(uri.toString()), cancelled)
    }
}
