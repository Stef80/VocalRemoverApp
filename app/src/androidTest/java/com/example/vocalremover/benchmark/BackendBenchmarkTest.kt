package com.example.vocalremover.benchmark

import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.vocalremover.BuildConfig
import com.example.vocalremover.ExecutionBackend
import com.example.vocalremover.StereoPcm
import com.example.vocalremover.VocalRemover
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

/**
 * Misura tempi e correttezza del backend del flavor installato sul dispositivo corrente.
 *
 * L'uscita viene confrontata con quella del CPU EP (riferimento di qualità) calcolata nello
 * stesso processo: un backend che produce audio rovinato (come WebGPU su Realme RMX3301)
 * fa fallire il test anche se non va in crash.
 *
 * Argomenti opzionali (`-e nome valore`): durationSec (default 20), minSnrDb (default 20).
 * Risultato: riga logcat con tag VRBenchmark e file
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
        val backend = ExecutionBackend.fromId(BuildConfig.EXECUTION_BACKEND)

        val (left, right) = SyntheticStereoSignal.generate(durationSec, SAMPLE_RATE, seed = 42)
        val input = StereoPcm(left, right)

        var sessionLoadMs = -1L
        var processMs = -1L
        var referenceMs: Long? = null
        var snr = Double.NaN
        var outRms = 0.0
        val failure: String? = try {
            val out = VocalRemover(context, backend).useRemover { remover ->
                sessionLoadMs = timed { remover.warmUp() }
                lateinit var result: StereoPcm
                processMs = timed { result = remover.removeVocals(input) }
                result
            }
            outRms = (BenchmarkMetrics.rms(out.left) + BenchmarkMetrics.rms(out.right)) / 2
            snr = if (backend == ExecutionBackend.CPU) {
                Double.POSITIVE_INFINITY
            } else {
                val ref = VocalRemover(context, ExecutionBackend.CPU).useRemover { cpu ->
                    cpu.warmUp()
                    lateinit var result: StereoPcm
                    referenceMs = timed { result = cpu.removeVocals(input) }
                    result
                }
                BenchmarkMetrics.stereoSnrDb(ref.left, ref.right, out.left, out.right)
            }
            when {
                !BenchmarkMetrics.allFinite(out.left) || !BenchmarkMetrics.allFinite(out.right) ->
                    "uscita con NaN/Inf"
                outRms < MIN_OUTPUT_RMS -> "uscita silenziosa (rms=$outRms)"
                snr < minSnrDb -> "SNR rispetto alla CPU troppo basso: ${String.format(Locale.ROOT, "%.1f", snr)} dB < $minSnrDb dB"
                else -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Benchmark fallito", e)
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
        // Passata da Gradle (connected*AndroidTest), che la copia in build/outputs prima di disinstallare.
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
        const val SAMPLE_RATE = 44100
        const val MIN_OUTPUT_RMS = 1e-3
    }
}
