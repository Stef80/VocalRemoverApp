package com.example.vocalremover.benchmark

import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.vocalremover.ExecutionBackend
import com.example.vocalremover.StereoPcm
import com.example.vocalremover.VocalRemover
import com.example.vocalremover.backend.BackendCandidates
import com.example.vocalremover.backend.OutputQuality
import com.example.vocalremover.backend.ProbeSignal
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Measures timings and correctness of one execution backend on the current device.
 *
 * The output is compared with that of the CPU EP (quality reference) computed in the
 * same process: a backend that produces ruined audio (like WebGPU on Realme RMX3301)
 * fails the test even if it does not crash.
 *
 * Optional arguments (`-e name value`): backend (cpu|webgpu|qnn|auto, default auto),
 * durationSec (default 20), minSnrDb (default 20).
 * Result: logcat line with tag VRBenchmark and file
 * /sdcard/Android/data/<applicationId>/files/benchmark/report-<backend>.json
 */
@RunWith(AndroidJUnit4::class)
class BackendBenchmarkTest {

    @Test
    fun benchmarkInstalledBackend() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()
        val durationSec = args.getString("durationSec")?.toDouble() ?: 20.0
        val minSnrDb = args.getString("minSnrDb")?.toDouble() ?: 20.0
        val backendArg = args.getString("backend") ?: "auto"
        // auto: for now the first candidate for this SoC.
        val backend = if (backendArg == "auto") {
            BackendCandidates.forThisDevice().first()
        } else {
            ExecutionBackend.fromId(backendArg)
        }

        val input = ProbeSignal.generate(durationSec, ProbeSignal.SAMPLE_RATE, seed = 42)

        var sessionLoadMs = -1L
        var processMs = -1L
        var referenceMs: Long? = null
        var snr = Double.NaN
        var outRms = 0.0
        var reference: StereoPcm? = null
        val failure: String? = try {
            val out = VocalRemover(context, backend).useRemover { remover ->
                sessionLoadMs = timed { remover.warmUp() }
                lateinit var result: StereoPcm
                processMs = timed { result = remover.removeVocals(input) }
                result
            }
            outRms = (OutputQuality.rms(out.left) + OutputQuality.rms(out.right)) / 2
            snr = if (backend == ExecutionBackend.CPU) {
                Double.POSITIVE_INFINITY
            } else {
                val ref = VocalRemover(context, ExecutionBackend.CPU).useRemover { cpu ->
                    cpu.warmUp()
                    lateinit var result: StereoPcm
                    referenceMs = timed { result = cpu.removeVocals(input) }
                    result
                }
                reference = ref
                OutputQuality.stereoSnrDb(ref.left, ref.right, out.left, out.right)
            }
            OutputQuality.verdict(ref = reference ?: out, out = out, minSnrDb = minSnrDb)
        } catch (e: Exception) {
            Log.e(TAG, "Benchmark failed", e)
            "${e.javaClass.simpleName}: ${e.message}"
        }

        val report = BenchmarkReport(
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            soc = if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else Build.HARDWARE,
            sdk = Build.VERSION.SDK_INT,
            backend = backend.id,
            audioSeconds = durationSec,
            sessionLoadMs = sessionLoadMs,
            processMs = processMs,
            referenceProcessMs = referenceMs,
            snrDb = snr,
            outputRms = outRms,
            passed = failure == null,
            failure = failure
        )
        val json = report.toJson()
        Log.i(TAG, json)
        context.getExternalFilesDir(null)?.let { dir ->
            File(dir, "benchmark").apply { mkdirs() }.resolve("report-${backend.id}.json").writeText(json + "\n")
        }
        // Passed by Gradle (connected*AndroidTest), which copies it to build/outputs before uninstalling.
        args.getString("additionalTestOutputDir")?.let { dir ->
            File(dir).apply { mkdirs() }.resolve("report-${backend.id}.json").writeText(json + "\n")
        }
        instrumentation.sendStatus(0, Bundle().apply { putString("VRBenchmark", json) })

        assertTrue(failure ?: "", failure == null)
    }

    private inline fun timed(block: () -> Unit): Long {
        val start = SystemClock.elapsedRealtime()
        block()
        return SystemClock.elapsedRealtime() - start
    }

    private inline fun <T> VocalRemover.useRemover(block: (VocalRemover) -> T): T =
        try { block(this) } finally { close() }

    private companion object {
        const val TAG = "VRBenchmark"
    }
}
