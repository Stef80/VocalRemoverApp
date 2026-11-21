package com.example.vocalremover

import android.content.Context
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class QnnContextCacheTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun `context cache lives in the backup-excluded dir keyed by install time`() {
        val file = QnnConfig.contextCacheFile(context, 42L)

        assertEquals(context.noBackupFilesDir, file.parentFile)
        assertEquals("qnn_ctx_42.onnx", file.name)
    }

    @Test
    fun `stale caches and caches left in filesDir are deleted, the current one is kept`() {
        val current = QnnConfig.contextCacheFile(context, 42L).apply { writeText("ctx") }
        val stale = File(context.noBackupFilesDir, "qnn_ctx_41.onnx").apply { writeText("old") }
        val unrelated = File(context.noBackupFilesDir, "other.bin").apply { writeText("keep") }
        val legacyCurrentKey = File(context.filesDir, "qnn_ctx_42.onnx").apply { writeText("legacy") }
        val legacyOld = File(context.filesDir, "qnn_ctx_7.onnx").apply { writeText("legacy") }
        val legacyUnrelated = File(context.filesDir, "library.json").apply { writeText("keep") }

        QnnConfig.deleteStaleContextCaches(context, current)

        assertTrue(current.exists())
        assertFalse(stale.exists())
        assertTrue(unrelated.exists())
        assertFalse(legacyCurrentKey.exists())
        assertFalse(legacyOld.exists())
        assertTrue(legacyUnrelated.exists())
    }
}
