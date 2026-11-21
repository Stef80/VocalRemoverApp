package com.example.vocalremover.benchmark

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.vocalremover.ExecutionBackend
import com.example.vocalremover.VocalRemover
import com.example.vocalremover.backend.ProbeReference
import com.example.vocalremover.backend.ProbeSignal
import kotlinx.coroutines.runBlocking
import com.example.vocalremover.backend.OutputQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Generates the CPU reference asset for the backend probe. Not a benchmark: skipped unless
 * `-e generateProbeReference true` is passed, e.g.
 * `adb shell am instrument -w -e class com.example.vocalremover.benchmark.ProbeReferenceGenerator -e generateProbeReference true com.stef.vocalremover.test/androidx.test.runner.AndroidJUnitRunner`,
 * then pull
 * /sdcard/Android/data/<applicationId>/files/backend_probe/expected_fp16.bin into
 * engine/src/main/assets/backend_probe/.
 */
@RunWith(AndroidJUnit4::class)
class ProbeReferenceGenerator {

    @Test
    fun generateCpuReference() = runBlocking {
        assumeTrue(
            "Pass -e generateProbeReference true to regenerate the asset",
            InstrumentationRegistry.getArguments().getString("generateProbeReference") == "true"
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val remover = VocalRemover(context, ExecutionBackend.CPU)
        val out = try {
            remover.warmUp()
            remover.removeVocals(ProbeSignal.generate())
        } finally {
            remover.close()
        }
        assertEquals(ProbeSignal.FRAMES, out.size)
        assertTrue("Output has NaN/Inf", OutputQuality.allFinite(out.left) && OutputQuality.allFinite(out.right))
        val dir = File(context.getExternalFilesDir(null), "backend_probe").apply { mkdirs() }
        File(dir, "expected_fp16.bin").writeBytes(ProbeReference.encode(out))
    }
}
