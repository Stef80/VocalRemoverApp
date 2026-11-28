package com.stef.vocalremover.ui.projection

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ApplicationProvider
import androidx.test.uiautomator.UiDevice
import com.example.vocalremover.library.LibraryTrack
import com.example.vocalremover.testaudio.TestAudioFiles
import com.stef.vocalremover.ui.VocalRemoverApplication
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The real path ProjectionController -> TvProjectionService -> TvPresentation on a private presentation
 * `VirtualDisplay`, without launching MainActivity (so ORT is never loaded).
 */
@RunWith(AndroidJUnit4::class)
class TvProjectionInstrumentedTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = ApplicationProvider.getApplicationContext<VocalRemoverApplication>()
    private lateinit var reader: ImageReader
    private lateinit var readerThread: HandlerThread
    private var virtualDisplay: VirtualDisplay? = null
    private val frameWithContent = AtomicBoolean(false)

    private val session get() = app.playerSession
    private val controller get() = app.projectionController

    @Before
    fun setUp() {
        if (Build.VERSION.SDK_INT >= 33) {
            val output = instrumentation.uiAutomation
                .executeShellCommand("pm grant ${app.packageName} android.permission.POST_NOTIFICATIONS")
            ParcelFileDescriptor.AutoCloseInputStream(output).use { it.readBytes() }
        }
        readerThread = HandlerThread("vr-test-reader").apply { start() }
        reader = ImageReader.newInstance(WIDTH, HEIGHT, PixelFormat.RGBA_8888, 2)
        reader.setOnImageAvailableListener({ r ->
            r.acquireLatestImage()?.use { if (hasContent(it)) frameWithContent.set(true) }
        }, Handler(readerThread.looper))
        // Without VIRTUAL_DISPLAY_FLAG_PUBLIC (needs a system permission): private, but visible to its creator.
        virtualDisplay = app.getSystemService(DisplayManager::class.java).createVirtualDisplay(
            "vr-test-tv", WIDTH, HEIGHT, 320, reader.surface, DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION,
        )
        val wav = TestAudioFiles.wav(app.cacheDir, "tv.wav", 20.0)
        onMain { session.open(LibraryTrack(Uri.fromFile(wav), TRACK_TITLE, 20_000L)) }
        await { controller.state.value is ProjectionState.Ready }
    }

    @After
    fun tearDown() {
        onMain {
            controller.stop()
            session.pause()
        }
        virtualDisplay?.release()
        reader.close()
        readerThread.quitSafely()
    }

    @Test
    fun la_proiezione_compare_sul_display_di_presentazione() {
        // The private display shows only our content (no accessibility windows are listed on it), so pixels with
        // content on the ImageReader prove the Presentation is on that display.
        val displayId = virtualDisplay!!.display.displayId
        assertEquals(displayId, (controller.state.value as ProjectionState.Ready).display.id)
        assertEquals(ProjectionStartResult.STARTED, onMain { controller.start() })
        await { frameWithContent.get() }
        assertTrue(serviceRunning())
    }

    @Test
    fun la_proiezione_resta_dopo_Home() {
        onMain { controller.start() }
        // The view is static, so after Home a new frame is not guaranteed: require the one drawn before it.
        await { frameWithContent.get() }
        UiDevice.getInstance(instrumentation).pressHome()
        Thread.sleep(2_000)
        assertTrue(controller.state.value is ProjectionState.Projecting)
        assertTrue(serviceRunning())
    }

    @Test
    fun stop_e_start_ravvicinati_lasciano_la_proiezione_attiva() {
        onMain { controller.start() }
        await { frameWithContent.get() }
        frameWithContent.set(false)
        onMain {
            controller.stop()
            controller.start()
        }
        await { controller.state.value is ProjectionState.Projecting }
        await { frameWithContent.get() }
        Thread.sleep(500)
        assertTrue(controller.state.value is ProjectionState.Projecting)
        assertTrue(serviceRunning())
    }

    @Test
    fun rimuovere_il_display_mette_in_pausa_e_ferma_il_servizio() {
        onMain { controller.start() }
        // The Presentation must be shown before the display goes away, or the service finds it already gone.
        await { session.state.value?.isPlaying == true }
        await { frameWithContent.get() }
        virtualDisplay!!.release()
        virtualDisplay = null
        // The event waits in the controller until it is collected.
        runBlocking { withTimeout(TIMEOUT_MS) { assertEquals(ProjectionEvent.DISCONNECTED, controller.events.first()) } }
        await { session.state.value?.isPlaying == false }
        assertEquals(ProjectionState.NoDisplay, controller.state.value)
        await { !serviceRunning() }
    }

    @Test
    fun la_notifica_media_compare_con_la_riproduzione() {
        onMain { controller.start() }
        await { session.state.value?.isPlaying == true }
        InstrumentationRegistry.getArguments().getString("holdMs")?.toLong()?.let { Thread.sleep(it) }
        val manager = app.getSystemService(NotificationManager::class.java)
        await {
            manager.activeNotifications.any {
                it.notification.channelId == "tv_projection" &&
                    it.notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)
            }
        }
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun await(timeoutMs: Long = TIMEOUT_MS, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            assertTrue("Timed out", System.currentTimeMillis() < deadline)
            Thread.sleep(100)
        }
    }

    /** Not a uniform image: at least 2 distinct colors on a grid of samples (dark background + logo/title). */
    private fun hasContent(image: android.media.Image): Boolean {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val colors = HashSet<Int>()
        for (y in 0 until image.height step image.height / 9) {
            for (x in 0 until image.width step image.width / 16) {
                colors += buffer.getInt(y * plane.rowStride + x * plane.pixelStride)
            }
        }
        return colors.size >= 2
    }

    @Suppress("DEPRECATION")
    private fun serviceRunning(): Boolean =
        app.getSystemService(ActivityManager::class.java).getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == TvProjectionService::class.java.name }

    private companion object {
        const val WIDTH = 1280
        const val HEIGHT = 720
        const val TIMEOUT_MS = 5_000L
        const val TRACK_TITLE = "Brano di prova"
    }
}
