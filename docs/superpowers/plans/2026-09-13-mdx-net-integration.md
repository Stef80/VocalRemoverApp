# Integrazione UVR-MDX-NET-Inst_HQ_5 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the app's Open-Unmix ONNX vocal-separation model with `UVR-MDX-NET-Inst_HQ_5.onnx`, which benchmarking (real-audio, cross-leak correlation 0.02-0.03 vs current 0.08-0.14) showed produces substantially cleaner instrumental separation, while keeping the public `VocalRemover` API unchanged so `AudioPlayer.kt`/`MainActivity.kt` require no changes.

**Architecture:** The new model requires `n_fft=5120` (not a power of 2, unlike the current radix-2-only FFT), a centered/reflect-padded STFT (unlike the app's current non-centered STFT), a 4-channel complex tensor layout (`[L_re, L_im, R_re, R_im]`), and outputs the **instrumental** spectrum directly (no ratio-mask step needed, unlike Open-Unmix). We add: (1) a standalone Bluestein FFT for arbitrary-length complex DFT, reusing the existing radix-2 engine internally; (2) a new `MdxStftProcessor` implementing centered STFT/ISTFT with dim_f cropping; (3) a new `MdxChunker` implementing the reference algorithm's outer 25%-overlap Hann-windowed waveform chunking (required because the ONNX graph has a fixed `dim_t=256` frame constraint, mirroring the existing `CHUNK_FRAMES` pattern in `VocalRemover.kt` but at the waveform-sample level); (4) a rewritten `VocalRemover.kt` wiring these pieces to the new ONNX model. The existing `StftProcessor` (n_fft=4096, non-centered) is left untouched, since it's still referenced by its own tests and documented as the primitives module for potential other call sites.

**Tech Stack:** Kotlin, Coroutines, ONNX Runtime Android (`onnxruntime-android:1.24.3`), JUnit (`:app:testDebugUnitTest`).

## Global Constraints

- `n_fft=5120, hop_length=1024, dim_f=2560, dim_t=256` — exact model contract (from `UVR-MDX-NET-Inst_HQ_5.yaml` + ONNX I/O inspection: input/output `[batch,4,2560,256]`).
- `segment_size` must equal `dim_t=256` (audio-separator requires this for the ONNX-only fast path we're replicating — no PyTorch fallback needed).
- `overlap=0.25` (default MDX arch overlap in `audio-separator`'s reference implementation, the same setting used during benchmarking).
- Outer overlap-add window: **symmetric** Hann (`numpy.hanning` formula, denominator `N-1`), NOT the periodic Hann used for the inner STFT window (denominator `N`). These are two different formulas — do not conflate them.
- `compensate=1.01` from the yaml is only applied by the reference tool when computing the **secondary** (vocals) stem. The app only needs the **primary** (instrumental) stem, which does NOT get compensated — do not apply `compensate` anywhere in this port.
- Peak normalization: if `max(abs(mix)) > 0.9`, scale mix down by `0.9/peak` before processing; multiply the final output by the **original** (pre-scaling) peak afterward. This exactly replicates the reference tool's (quirky but validated) behavior. Do not "fix" or simplify this.
- Zero out the first 3 frequency bins of the (already dim_f-cropped) spectrum before every ONNX inference call (`spek[:, :, :3, :] *= 0` in the reference).
- Model asset: place `UVR-MDX-NET-Inst_HQ_5.onnx` (~59MB) at `app/src/main/assets/vocal_remover.onnx` (same filename/path as today, so no other code changes are needed) — it is gitignored like the current model.
- Follow existing repo convention: TDD for all new Kotlin logic (write failing test first), run `gradle :app:testDebugUnitTest` after every task, run `gradle :app:assembleDebug` at the end.
- Do not touch `AudioPlayer.kt` or `MainActivity.kt` — `VocalRemover`'s public API (`VocalRemover(context)`, `suspend fun removeVocals(signal: StereoPcm, onProgress: (Int) -> Unit): StereoPcm`, `fun close()`) must stay identical.

---

### Task 1: Bluestein arbitrary-length FFT

**Files:**
- Create: `app/src/main/java/com/example/vocalremover/BluesteinFft.kt`
- Test: `app/src/test/java/com/example/vocalremover/BluesteinFftTest.kt`

**Interfaces:**
- Produces: `object BluesteinFft { fun transform(re: FloatArray, im: FloatArray, inverse: Boolean = false) }` — in-place arbitrary-length complex DFT/IDFT, analogous in calling convention to the existing `StftProcessor.fft` (`re`/`im` same length `N`, any `N >= 1`, `inverse=true` divides by `N` and conjugates appropriately). Used by `MdxStftProcessor` (Task 2) for `N=5120`.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.example.vocalremover

import org.junit.Assert.assertEquals
import org.junit.Test

class BluesteinFftTest {

    private fun assertComplexArraysEqual(
        expectedRe: FloatArray, expectedIm: FloatArray,
        actualRe: FloatArray, actualIm: FloatArray, delta: Float = 1e-3f
    ) {
        for (i in expectedRe.indices) {
            assertEquals("re[$i]", expectedRe[i], actualRe[i], delta)
            assertEquals("im[$i]", expectedIm[i], actualIm[i], delta)
        }
    }

    @Test
    fun `matches known DFT for N=6 (non power of 2)`() {
        val re = floatArrayOf(0.4967f, -0.1383f, 0.6477f, 1.523f, -0.2342f, -0.2341f)
        val im = FloatArray(6)

        BluesteinFft.transform(re, im)

        val expectedRe = floatArrayOf(2.0608f, -1.41925f, 1.99915f, -0.2404f, 1.99915f, -1.41925f)
        val expectedIm = floatArrayOf(0.0f, -0.846713f, 0.680783f, 0.0f, -0.680783f, 0.846713f)
        assertComplexArraysEqual(expectedRe, expectedIm, re, im)
    }

    @Test
    fun `matches known DFT for N=10 (non power of 2)`() {
        val re = floatArrayOf(
            1.5792f, 0.7674f, -0.4695f, 0.5426f, -0.4634f,
            -0.4657f, 0.242f, -1.9133f, -1.7249f, -0.5623f
        )
        val im = FloatArray(10)

        BluesteinFft.transform(re, im)

        val expectedRe = floatArrayOf(
            -2.4679f, 2.135408f, 3.99269f, 2.579492f, 0.02501f,
            0.7947f, 0.02501f, 2.579492f, 3.99269f, 2.135408f
        )
        val expectedIm = floatArrayOf(
            0.0f, -3.89661f, -1.229859f, 1.587703f, -2.337945f,
            -0.0f, 2.337945f, -1.587703f, 1.229859f, 3.89661f
        )
        assertComplexArraysEqual(expectedRe, expectedIm, re, im)
    }

    @Test
    fun `inverse of forward recovers original signal for N=6`() {
        val original = floatArrayOf(0.4967f, -0.1383f, 0.6477f, 1.523f, -0.2342f, -0.2341f)
        val re = original.copyOf()
        val im = FloatArray(6)

        BluesteinFft.transform(re, im)
        BluesteinFft.transform(re, im, inverse = true)

        for (i in original.indices) {
            assertEquals(original[i], re[i], 1e-3f)
            assertEquals(0f, im[i], 1e-3f)
        }
    }

    @Test
    fun `inverse of forward recovers original signal for N=5120 (actual model size)`() {
        val n = 5120
        val rnd = java.util.Random(7)
        val original = FloatArray(n) { rnd.nextFloat() * 2f - 1f }
        val re = original.copyOf()
        val im = FloatArray(n)

        BluesteinFft.transform(re, im)
        BluesteinFft.transform(re, im, inverse = true)

        for (i in 0 until n) {
            assertEquals(original[i], re[i], 1e-2f)
            assertEquals(0f, im[i], 1e-2f)
        }
    }

    @Test
    fun `matches existing radix-2 FFT for power-of-2 size (cross-check)`() {
        // Bluestein must agree with the existing StftProcessor-style radix-2 FFT for N=8.
        val re1 = floatArrayOf(1f, 2f, 3f, 4f, -1f, -2f, 0.5f, 0.25f)
        val im1 = FloatArray(8)
        val re2 = re1.copyOf()
        val im2 = FloatArray(8)

        BluesteinFft.transform(re1, im1)
        radix2Reference(re2, im2)

        for (i in 0 until 8) {
            assertEquals(re2[i], re1[i], 1e-3f)
            assertEquals(im2[i], im1[i], 1e-3f)
        }
    }

    /** Minimal radix-2 reference copied from StftProcessor's algorithm, for cross-checking only. */
    private fun radix2Reference(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { re[i] = re[j].also { re[j] = re[i] }; im[i] = im[j].also { im[j] = im[i] } }
        }
        var len = 2
        while (len <= n) {
            val ang = 2.0 * Math.PI / len
            val wRe = Math.cos(ang).toFloat()
            val wIm = Math.sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var curRe = 1f; var curIm = 0f
                for (k in 0 until len / 2) {
                    val uRe = re[i + k]; val uIm = im[i + k]
                    val vRe = re[i + k + len / 2] * curRe - im[i + k + len / 2] * curIm
                    val vIm = re[i + k + len / 2] * curIm + im[i + k + len / 2] * curRe
                    re[i + k] = uRe + vRe; im[i + k] = uIm + vIm
                    re[i + k + len / 2] = uRe - vRe; im[i + k + len / 2] = uIm - vIm
                    val newCurRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe; curRe = newCurRe
                }
                i += len
            }
            len = len shl 1
        }
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `gradle :app:testDebugUnitTest --tests "com.example.vocalremover.BluesteinFftTest"`
Expected: FAIL (compile error — `BluesteinFft` does not exist yet).

- [ ] **Step 3: Implement Bluestein's algorithm**

```kotlin
package com.example.vocalremover

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Trasformata di Fourier discreta (DFT/IDFT) per lunghezze arbitrarie (non necessariamente
 * potenze di 2), tramite l'algoritmo di Bluestein (chirp z-transform). Riusa una FFT radix-2
 * interna (potenza di 2) come motore di convoluzione circolare, quindi non richiede alcuna
 * libreria esterna. Necessaria per StftProcessor "classico" (nFft=4096, potenza di 2) NON basta
 * per il modello UVR-MDX-NET (nFft=5120 = 2^10 * 5, non potenza di 2).
 */
object BluesteinFft {

    private class Plan(val n: Int) {
        val m: Int
        val chirpRe: FloatArray
        val chirpIm: FloatArray
        // Trasformata precomputata di "b" (il chirp coniugato, zero-padded a lunghezza m),
        // riutilizzabile per ogni chiamata con lo stesso n.
        val bFftRe: FloatArray
        val bFftIm: FloatArray

        init {
            var mm = 1
            while (mm < 2 * n - 1) mm = mm shl 1
            m = mm

            chirpRe = FloatArray(n)
            chirpIm = FloatArray(n)
            for (k in 0 until n) {
                // angolo = pi * k^2 / n, calcolato su k^2 mod (2n) per evitare overflow/perdita
                // di precisione con k grandi (fino a n=5120 -> k^2 fino a ~26 milioni, ok in Long).
                val kk = (k.toLong() * k.toLong()) % (2L * n)
                val angle = PI * kk / n
                chirpRe[k] = cos(angle).toFloat()
                chirpIm[k] = -sin(angle).toFloat()
            }

            val bRe = FloatArray(m)
            val bIm = FloatArray(m)
            bRe[0] = chirpRe[0]
            bIm[0] = -chirpIm[0]
            for (k in 1 until n) {
                bRe[k] = chirpRe[k]
                bIm[k] = -chirpIm[k]
                bRe[m - k] = chirpRe[k]
                bIm[m - k] = -chirpIm[k]
            }
            bFftRe = bRe
            bFftIm = bIm
            radix2Fft(bFftRe, bFftIm, inverse = false)
        }
    }

    private val planCache = HashMap<Int, Plan>()

    private fun planFor(n: Int): Plan = planCache.getOrPut(n) { Plan(n) }

    /**
     * DFT (o IDFT se [inverse]=true) in-place di lunghezza arbitraria [re].size.
     * Per lunghezze potenza di 2 delega direttamente alla FFT radix-2 (più veloce, nessun
     * overhead di zero-padding). Per le altre lunghezze usa Bluestein.
     */
    fun transform(re: FloatArray, im: FloatArray, inverse: Boolean = false) {
        val n = re.size
        require(im.size == n) { "re e im devono avere la stessa lunghezza" }
        if (n == 0) return
        if (n and (n - 1) == 0) {
            radix2Fft(re, im, inverse)
            return
        }

        val plan = planFor(n)
        val sign = if (inverse) 1.0 else -1.0

        // a[k] = x[k] * exp(sign * i * pi * k^2 / n), zero-padded a lunghezza m.
        val aRe = FloatArray(plan.m)
        val aIm = FloatArray(plan.m)
        for (k in 0 until n) {
            val cRe = plan.chirpRe[k]
            val cIm = sign.toFloat() * -plan.chirpIm[k] // chirpIm già negativo per il forward; invertilo per l'inverso
            aRe[k] = re[k] * cRe - im[k] * cIm
            aIm[k] = re[k] * cIm + im[k] * cRe
        }

        radix2Fft(aRe, aIm, inverse = false)

        val bRe: FloatArray
        val bIm: FloatArray
        if (inverse) {
            // Per l'inverso il chirp "b" ha segno opposto: ricalcola la FFT di b coniugato.
            val bReTmp = FloatArray(plan.m)
            val bImTmp = FloatArray(plan.m)
            bReTmp[0] = plan.chirpRe[0]
            bImTmp[0] = plan.chirpIm[0]
            for (k in 1 until n) {
                bReTmp[k] = plan.chirpRe[k]
                bImTmp[k] = plan.chirpIm[k]
                bReTmp[plan.m - k] = plan.chirpRe[k]
                bImTmp[plan.m - k] = plan.chirpIm[k]
            }
            radix2Fft(bReTmp, bImTmp, inverse = false)
            bRe = bReTmp
            bIm = bImTmp
        } else {
            bRe = plan.bFftRe
            bIm = plan.bFftIm
        }

        // Convoluzione circolare nel dominio della frequenza.
        for (i in 0 until plan.m) {
            val pr = aRe[i] * bRe[i] - aIm[i] * bIm[i]
            val pi = aRe[i] * bIm[i] + aIm[i] * bRe[i]
            aRe[i] = pr
            aIm[i] = pi
        }

        radix2Fft(aRe, aIm, inverse = true)

        for (k in 0 until n) {
            val cRe = plan.chirpRe[k]
            val cIm = sign.toFloat() * -plan.chirpIm[k]
            val xr = aRe[k] * cRe - aIm[k] * cIm
            val xi = aRe[k] * cIm + aIm[k] * cRe
            re[k] = xr
            im[k] = xi
        }

        if (inverse) {
            for (k in 0 until n) {
                re[k] /= n
                im[k] /= n
            }
        }
    }

    /** FFT radix-2 Cooley-Tukey standard (richiede lunghezza potenza di 2), in-place. */
    private fun radix2Fft(re: FloatArray, im: FloatArray, inverse: Boolean) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { re[i] = re[j].also { re[j] = re[i] }; im[i] = im[j].also { im[j] = im[i] } }
        }
        var len = 2
        while (len <= n) {
            val ang = 2.0 * PI / len * (if (inverse) -1 else 1)
            val wRe = cos(ang).toFloat()
            val wIm = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var curRe = 1f; var curIm = 0f
                for (k in 0 until len / 2) {
                    val uRe = re[i + k]; val uIm = im[i + k]
                    val vRe = re[i + k + len / 2] * curRe - im[i + k + len / 2] * curIm
                    val vIm = re[i + k + len / 2] * curIm + im[i + k + len / 2] * curRe
                    re[i + k] = uRe + vRe; im[i + k] = uIm + vIm
                    re[i + k + len / 2] = uRe - vRe; im[i + k + len / 2] = uIm - vIm
                    val newCurRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe; curRe = newCurRe
                }
                i += len
            }
            len = len shl 1
        }
        if (inverse) { for (i in 0 until n) { re[i] /= n; im[i] /= n } }
    }
}
```

**Note for implementer:** the sign-handling for `inverse=true` is subtle (Bluestein's inverse needs the chirp conjugated). Trust the tests, not intuition here — if the "inverse of forward recovers original" tests pass (both N=6 and N=5120), the signs are correct. If they fail, the most common bug is forgetting to conjugate the `b` chirp sequence for the inverse case (handled above by recomputing `bReTmp/bImTmp` with flipped `chirpIm` sign when `inverse=true`).

- [ ] **Step 4: Run tests to verify they pass**

Run: `gradle :app:testDebugUnitTest --tests "com.example.vocalremover.BluesteinFftTest"`
Expected: PASS (all 5 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/vocalremover/BluesteinFft.kt app/src/test/java/com/example/vocalremover/BluesteinFftTest.kt
git commit -m "feat: add Bluestein arbitrary-length FFT for MDX-Net n_fft=5120 support"
```

---

### Task 2: MdxStftProcessor — forward centered STFT

**Files:**
- Create: `app/src/main/java/com/example/vocalremover/MdxStftProcessor.kt`
- Test: `app/src/test/java/com/example/vocalremover/MdxStftProcessorTest.kt`

**Interfaces:**
- Consumes: `BluesteinFft.transform(re: FloatArray, im: FloatArray, inverse: Boolean = false)` (Task 1).
- Produces: `class MdxStftProcessor(val nFft: Int = 5120, val hopLength: Int = 1024, val dimF: Int = 2560)` with:
  - `val freqBins: Int` = `nFft/2+1` (full, uncropped)
  - `fun frameCountFor(signalLength: Int): Int` — number of centered STFT frames for a signal of this length (`= signalLength / hopLength + 1`, matching `torch.stft(center=True)`).
  - `fun forward(signal: FloatArray): Pair<FloatArray, FloatArray>` — centered STFT with reflect padding, returns flat `[frames * dimF]` re/im arrays (already cropped to `dimF` bins). Used by `VocalRemover.kt` (Task 5).
  - `fun inverse(re: FloatArray, im: FloatArray, frames: Int, outputLength: Int): FloatArray` — centered ISTFT (Task 3, declared here for interface completeness, implemented in Task 3).

- [ ] **Step 1: Write the failing test**

```kotlin
package com.example.vocalremover

import org.junit.Assert.assertEquals
import org.junit.Test

class MdxStftProcessorTest {

    @Test
    fun `frame count matches centered STFT convention`() {
        // n_fft=6, hop=2, 12-sample signal -> frames = 12/2 + 1 = 7 (verified against torch.stft center=True).
        val processor = MdxStftProcessor(nFft = 6, hopLength = 2, dimF = 3)
        assertEquals(7, processor.frameCountFor(12))
    }

    @Test
    fun `forward centered STFT matches torch stft reference (n_fft=6, hop=2, dimF=3)`() {
        val processor = MdxStftProcessor(nFft = 6, hopLength = 2, dimF = 3)
        val signal = floatArrayOf(
            0.5f, -0.2f, 0.8f, 1.1f, -0.3f, -0.1f, 0.4f, 0.9f, -0.6f, 0.2f, 0.15f, -0.45f
        )

        val (re, im) = processor.forward(signal)

        // Reference values generated via torch.stft(x, n_fft=6, hop_length=2,
        // window=torch.hann_window(6, periodic=True), center=True, return_complex=True),
        // cropped to the first 3 (of 4) frequency bins per frame.
        val expectedRe = arrayOf(
            floatArrayOf(0.6f, -0.15f, 0.45f),
            floatArrayOf(1.525f, -1.1125f, 0.4375f),
            floatArrayOf(0.75f, 0.075f, -0.825f),
            floatArrayOf(0.775f, -0.8125f, 0.2125f),
            floatArrayOf(0.3625f, 0.25625f, -1.08125f),
            floatArrayOf(-0.15f, -0.1125f, 0.3f),
            floatArrayOf(-0.15f, -0.1125f, 0.3f)
        )
        val expectedIm = arrayOf(
            floatArrayOf(0.0f, 0.0f, 0.0f),
            floatArrayOf(0.0f, 0.67117f, -1.01758f),
            floatArrayOf(0.0f, -0.866025f, 0.69282f),
            floatArrayOf(0.0f, 0.584567f, -0.714471f),
            floatArrayOf(0.0f, -0.50879f, 0.400537f),
            floatArrayOf(0.0f, -0.259808f, 0.584567f),
            floatArrayOf(0.0f, 0.259808f, -0.584567f)
        )

        val dimF = 3
        for (frame in 0 until 7) {
            for (bin in 0 until dimF) {
                val idx = frame * dimF + bin
                assertEquals("re frame=$frame bin=$bin", expectedRe[frame][bin], re[idx], 1e-3f)
                assertEquals("im frame=$frame bin=$bin", expectedIm[frame][bin], im[idx], 1e-3f)
            }
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `gradle :app:testDebugUnitTest --tests "com.example.vocalremover.MdxStftProcessorTest"`
Expected: FAIL (compile error — `MdxStftProcessor` does not exist yet).

- [ ] **Step 3: Implement forward centered STFT**

```kotlin
package com.example.vocalremover

import kotlin.math.PI
import kotlin.math.cos

/**
 * STFT/ISTFT "centrata" (con reflect-padding, come torch.stft/torch.istft con center=True)
 * per il modello UVR-MDX-NET-Inst_HQ_5 (nFft=5120, non potenza di 2 -> usa [BluesteinFft]).
 * A differenza di [StftProcessor] (nFft=4096, non centrata, usata da Open-Unmix), questa
 * classe implementa la convenzione esatta richiesta da MDX-Net: padding riflesso di nFft/2
 * campioni a inizio/fine segnale prima del framing, e ritaglio in frequenza a [dimF] bin
 * (il modello lavora solo sulle frequenze più basse, scartando la coda alta dello spettro).
 */
class MdxStftProcessor(
    val nFft: Int = 5120,
    val hopLength: Int = 1024,
    val dimF: Int = 2560
) {
    val freqBins: Int = nFft / 2 + 1

    // Finestra di Hann "periodica" (denominatore = nFft), identica per formula a quella di
    // StftProcessor ma con nFft diverso. Usata per l'analisi (forward) e la sintesi (inverse).
    private val window: FloatArray = FloatArray(nFft) { n ->
        (0.5f * (1.0 - cos(2.0 * PI * n / nFft))).toFloat()
    }
    internal val windowSquareSum: FloatArray = FloatArray(nFft) { window[it] * window[it] }

    fun frameCountFor(signalLength: Int): Int = signalLength / hopLength + 1

    /**
     * STFT centrata: applica reflect-padding di nFft/2 campioni a inizio e fine di [signal],
     * poi calcola una FFT per frame con hop [hopLength]. Ritorna Re/Im come array piatti
     * [frames * dimF] (già ritagliati alle prime [dimF] bin di frequenza).
     */
    fun forward(signal: FloatArray): Pair<FloatArray, FloatArray> {
        val half = nFft / 2
        val padded = reflectPad(signal, half)
        val frames = frameCountFor(signal.size)

        val outRe = FloatArray(frames * dimF)
        val outIm = FloatArray(frames * dimF)
        val re = FloatArray(nFft)
        val im = FloatArray(nFft)

        for (frame in 0 until frames) {
            val start = frame * hopLength
            for (i in 0 until nFft) {
                re[i] = padded[start + i] * window[i]
                im[i] = 0f
            }
            BluesteinFft.transform(re, im)
            for (bin in 0 until dimF) {
                outRe[frame * dimF + bin] = re[bin]
                outIm[frame * dimF + bin] = im[bin]
            }
        }
        return Pair(outRe, outIm)
    }

    /**
     * Riflette [signal] di [pad] campioni a inizio e fine, modalità 'reflect' di numpy/torch
     * (esclude il campione di bordo stesso dallo specchio): per pad=3 e segnale [a,b,c,d,...],
     * il prefisso diventa [d,c,b, a,b,c,d,...] (specchiato attorno ad "a", "a" escluso).
     */
    private fun reflectPad(signal: FloatArray, pad: Int): FloatArray {
        val n = signal.size
        val out = FloatArray(n + 2 * pad)
        for (i in 0 until pad) {
            out[i] = signal[reflectIndex(pad - i, n)]
        }
        System.arraycopy(signal, 0, out, pad, n)
        for (i in 0 until pad) {
            out[pad + n + i] = signal[reflectIndex(n - 2 - i, n)]
        }
        return out
    }

    /** Indice riflesso (modalità 'reflect') dentro [0, n) per un indice potenzialmente fuori range. */
    private fun reflectIndex(indexIn: Int, n: Int): Int {
        var idx = indexIn
        if (n == 1) return 0
        val period = 2 * (n - 1)
        idx %= period
        if (idx < 0) idx += period
        return if (idx < n) idx else period - idx
    }

    fun inverse(re: FloatArray, im: FloatArray, frames: Int, outputLength: Int): FloatArray {
        throw NotImplementedError("Implementato nel Task 3")
    }
}
```

**Note for implementer:** the `reflectIndex` helper is the standard formula for numpy/torch `'reflect'` padding mode (period `2*(n-1)`, triangular-wave folding). For the test's `n=12, pad=3` case: prefix asks for `signal[reflectIndex(3-i, 12)]` for `i=0,1,2` → `reflectIndex(3,12)=3`, `reflectIndex(2,12)=2`, `reflectIndex(1,12)=1` → prefix = `[signal[3], signal[2], signal[1]]` = mirrors correctly (verify this matches frame 0's expected values, which only see reflected+original samples since the first window starts at the padded array's beginning).

- [ ] **Step 4: Run test to verify it passes**

Run: `gradle :app:testDebugUnitTest --tests "com.example.vocalremover.MdxStftProcessorTest"`
Expected: PASS (2 tests; `inverse` isn't called yet).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/vocalremover/MdxStftProcessor.kt app/src/test/java/com/example/vocalremover/MdxStftProcessorTest.kt
git commit -m "feat: add MDX-Net centered STFT forward pass"
```

---

### Task 3: MdxStftProcessor — inverse centered ISTFT

**Files:**
- Modify: `app/src/main/java/com/example/vocalremover/MdxStftProcessor.kt`
- Modify: `app/src/test/java/com/example/vocalremover/MdxStftProcessorTest.kt`

**Interfaces:**
- Consumes: `BluesteinFft.transform` (Task 1), `MdxStftProcessor.forward` (Task 2).
- Produces: `MdxStftProcessor.inverse(re: FloatArray, im: FloatArray, frames: Int, outputLength: Int): FloatArray` fully implemented — consumed by `VocalRemover.kt` (Task 5). Input `re`/`im` are flat `[frames * freqBins]` (full, **uncropped** — Task 5 is responsible for zero-padding the model's `dimF`-wide output back up to `freqBins` before calling this).

- [ ] **Step 1: Write the failing tests**

```kotlin
    @Test
    fun `inverse centered ISTFT full roundtrip recovers original signal`() {
        val processor = MdxStftProcessor(nFft = 6, hopLength = 2, dimF = 4) // dimF=freqBins=4: no crop
        val signal = floatArrayOf(
            0.5f, -0.2f, 0.8f, 1.1f, -0.3f, -0.1f, 0.4f, 0.9f, -0.6f, 0.2f, 0.15f, -0.45f
        )

        val (re, im) = processor.forward(signal)
        val reconstructed = processor.inverse(re, im, frames = 7, outputLength = signal.size)

        for (i in signal.indices) {
            assertEquals("sample $i", signal[i], reconstructed[i], 1e-3f)
        }
    }

    @Test
    fun `inverse centered ISTFT with dimF crop matches torch istft reference`() {
        val processor = MdxStftProcessor(nFft = 6, hopLength = 2, dimF = 3)
        val signal = floatArrayOf(
            0.5f, -0.2f, 0.8f, 1.1f, -0.3f, -0.1f, 0.4f, 0.9f, -0.6f, 0.2f, 0.15f, -0.45f
        )

        val (croppedRe, croppedIm) = processor.forward(signal) // already dimF=3 cropped
        // Pad back from dimF=3 to freqBins=4 with zeros, as VocalRemover will do for real model output.
        val fullRe = FloatArray(7 * processor.freqBins)
        val fullIm = FloatArray(7 * processor.freqBins)
        for (frame in 0 until 7) {
            for (bin in 0 until 3) {
                fullRe[frame * processor.freqBins + bin] = croppedRe[frame * 3 + bin]
                fullIm[frame * processor.freqBins + bin] = croppedIm[frame * 3 + bin]
            }
        }

        val reconstructed = processor.inverse(fullRe, fullIm, frames = 7, outputLength = signal.size)

        // Reference values from torch.istft on the same dimF=3-cropped-then-zero-padded spectrum.
        val expected = floatArrayOf(
            0.304902f, -0.047222f, 0.757407f, 1.036111f, -0.17963f, -0.230556f,
            0.538426f, 0.709722f, -0.401852f, 0.081944f, 0.156019f, -0.4f
        )
        for (i in expected.indices) {
            assertEquals("sample $i", expected[i], reconstructed[i], 1e-3f)
        }
    }
```

Add these two tests inside the existing `MdxStftProcessorTest` class from Task 2.

- [ ] **Step 2: Run tests to verify they fail**

Run: `gradle :app:testDebugUnitTest --tests "com.example.vocalremover.MdxStftProcessorTest"`
Expected: FAIL with `NotImplementedError: Implementato nel Task 3`.

- [ ] **Step 3: Implement inverse centered ISTFT**

Replace the placeholder `inverse` method in `MdxStftProcessor.kt`:

```kotlin
    /**
     * ISTFT centrata: overlap-add standard (normalizzato per somma dei quadrati della finestra,
     * come [StftProcessor.istft]) seguito dal ritaglio di nFft/2 campioni a inizio e fine per
     * annullare il reflect-padding introdotto da [forward]. [re]/[im] sono piatti [frames * freqBins]
     * (NON ritagliati a dimF: il chiamante deve prima riportarli a [freqBins] con zero-padding,
     * dato che il modello produce solo le prime dimF bin).
     */
    fun inverse(re: FloatArray, im: FloatArray, frames: Int, outputLength: Int): FloatArray {
        val paddedLen = (frames - 1) * hopLength + nFft
        val output = FloatArray(paddedLen)
        val normSum = FloatArray(paddedLen)
        val frameRe = FloatArray(nFft)
        val frameIm = FloatArray(nFft)

        for (frame in 0 until frames) {
            val offset = frame * freqBins
            for (k in 0 until freqBins) {
                frameRe[k] = re[offset + k]
                frameIm[k] = im[offset + k]
            }
            for (k in freqBins until nFft) {
                frameRe[k] = frameRe[nFft - k]
                frameIm[k] = -frameIm[nFft - k]
            }
            BluesteinFft.transform(frameRe, frameIm, inverse = true)

            val start = frame * hopLength
            for (i in 0 until nFft) {
                output[start + i] += frameRe[i] * window[i]
                normSum[start + i] += windowSquareSum[i]
            }
        }

        for (i in 0 until paddedLen) {
            if (normSum[i] > 1e-8f) output[i] /= normSum[i]
        }

        // Rimuove il reflect-padding (nFft/2 campioni per lato) introdotto da forward().
        val half = nFft / 2
        val result = FloatArray(outputLength)
        for (i in 0 until outputLength) {
            val srcIdx = half + i
            result[i] = if (srcIdx < paddedLen) output[srcIdx] else 0f
        }
        return result
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `gradle :app:testDebugUnitTest --tests "com.example.vocalremover.MdxStftProcessorTest"`
Expected: PASS (4 tests total).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/vocalremover/MdxStftProcessor.kt app/src/test/java/com/example/vocalremover/MdxStftProcessorTest.kt
git commit -m "feat: add MDX-Net centered ISTFT inverse pass"
```

---

### Task 4: MdxChunker — outer 25%-overlap waveform windowing

**Files:**
- Create: `app/src/main/java/com/example/vocalremover/MdxChunker.kt`
- Test: `app/src/test/java/com/example/vocalremover/MdxChunkerTest.kt`

**Interfaces:**
- Consumes: nothing from earlier tasks (pure waveform-array logic; the per-chunk "process" callback is injected by the caller and will wrap STFT+ONNX+ISTFT in Task 5).
- Produces: `class MdxChunker(nFft: Int = 5120, hopLength: Int = 1024, dimT: Int = 256, overlap: Double = 0.25)` with `fun process(mono: FloatArray, processChunk: (FloatArray) -> FloatArray): FloatArray`. Used by `VocalRemover.kt` (Task 5), called once per channel (left, right).

**Design notes for implementer:** This replicates `MDXSeparator.demix()`'s outer loop exactly:
- `trim = nFft / 2` (2560), `chunkSize = hopLength * (dimT - 1)` (261120), `genSize = chunkSize - 2*trim` (256000), `step = ((1 - overlap) * chunkSize).toInt()` (195840).
- `pad = genSize + trim - (mono.size % genSize)`.
- `mixture = [zeros(trim), mono, zeros(pad)]` (pad only at the end; trim-zeros only at the start).
- For `i in 0 until mixture.size step step`: `end = min(i + chunkSize, mixture.size)`; extract `mixture[i until end]`, zero-pad on the right up to `chunkSize` if short; apply **symmetric** Hann window (`numpy.hanning` formula: `0.5 - 0.5*cos(2*pi*n/(len-1))`, using `len = end - i` i.e. the *actual* unpadded chunk length) to `processChunk(...)`'s output truncated to `end-i`, unless `end-i == 1` (avoid divide-by-zero — won't happen in practice since `chunkSize` is large); accumulate into `result`/`divider` arrays sized `mixture.size`.
- After the loop: `result /= divider`; crop `trim` samples off both ends; crop to `mono.size`.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.example.vocalremover

import org.junit.Assert.assertEquals
import org.junit.Test

class MdxChunkerTest {

    @Test
    fun `passthrough process function reconstructs original signal`() {
        // Small nFft/hop/dimT so chunkSize is small and the test runs fast.
        val chunker = MdxChunker(nFft = 8, hopLength = 2, dimT = 5, overlap = 0.25)
        // chunkSize = hop*(dimT-1) = 2*4 = 8
        val signal = FloatArray(37) { (it % 7).toFloat() - 3f }

        val result = chunker.process(signal) { chunk -> chunk } // identity "model"

        assertEquals(signal.size, result.size)
        for (i in signal.indices) {
            assertEquals("sample $i", signal[i], result[i], 1e-3f)
        }
    }

    @Test
    fun `scaling process function scales reconstructed signal uniformly`() {
        val chunker = MdxChunker(nFft = 8, hopLength = 2, dimT = 5, overlap = 0.25)
        val signal = FloatArray(50) { (it % 5).toFloat() * 0.1f }

        val result = chunker.process(signal) { chunk -> FloatArray(chunk.size) { chunk[it] * 2f } }

        for (i in signal.indices) {
            assertEquals("sample $i", signal[i] * 2f, result[i], 1e-3f)
        }
    }

    @Test
    fun `output length always matches input length regardless of chunk boundary alignment`() {
        val chunker = MdxChunker(nFft = 8, hopLength = 2, dimT = 5, overlap = 0.25)
        for (len in listOf(1, 7, 8, 9, 16, 33, 100)) {
            val signal = FloatArray(len) { it.toFloat() }
            val result = chunker.process(signal) { chunk -> chunk }
            assertEquals("len=$len", len, result.size)
        }
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `gradle :app:testDebugUnitTest --tests "com.example.vocalremover.MdxChunkerTest"`
Expected: FAIL (compile error — `MdxChunker` does not exist yet).

- [ ] **Step 3: Implement MdxChunker**

```kotlin
package com.example.vocalremover

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * Suddivisione a finestre sovrapposte (25% di default) del segnale mono in chunk di dimensione
 * fissa richiesta dal grafo ONNX (chunkSize = hopLength*(dimT-1) campioni). Ogni chunk viene
 * passato a [processChunk] (che internamente farà STFT->ONNX->ISTFT, vedi VocalRemover), poi
 * ricombinato con overlap-add pesato da una finestra di Hann SIMMETRICA (formula numpy.hanning,
 * diversa dalla finestra "periodica" usata dentro la STFT). Replica esattamente l'algoritmo
 * MDXSeparator.demix() di audio-separator (il tool di riferimento usato per validare la qualità
 * del modello UVR-MDX-NET-Inst_HQ_5 in fase di benchmarking).
 */
class MdxChunker(
    nFft: Int = 5120,
    hopLength: Int = 1024,
    dimT: Int = 256,
    private val overlap: Double = 0.25
) {
    private val trim = nFft / 2
    private val chunkSize = hopLength * (dimT - 1)
    private val genSize = chunkSize - 2 * trim
    private val step = ((1.0 - overlap) * chunkSize).toInt()

    fun process(mono: FloatArray, processChunk: (FloatArray) -> FloatArray): FloatArray {
        val n = mono.size
        val pad = genSize + trim - (n % genSize)

        val mixtureLen = trim + n + pad
        val mixture = FloatArray(mixtureLen)
        System.arraycopy(mono, 0, mixture, trim, n)

        val result = FloatArray(mixtureLen)
        val divider = FloatArray(mixtureLen)

        var i = 0
        while (i < mixtureLen) {
            val end = min(i + chunkSize, mixtureLen)
            val actualLen = end - i

            val chunkInput = FloatArray(chunkSize)
            System.arraycopy(mixture, i, chunkInput, 0, actualLen)

            val processed = processChunk(chunkInput)

            if (overlap != 0.0) {
                val win = symmetricHann(actualLen)
                for (k in 0 until actualLen) {
                    result[i + k] += processed[k] * win[k]
                    divider[i + k] += win[k]
                }
            } else {
                for (k in 0 until actualLen) {
                    result[i + k] += processed[k]
                    divider[i + k] += 1f
                }
            }

            i += step
        }

        val out = FloatArray(n)
        for (idx in 0 until n) {
            val srcIdx = trim + idx
            out[idx] = if (divider[srcIdx] > 1e-8f) result[srcIdx] / divider[srcIdx] else 0f
        }
        return out
    }

    /** Finestra di Hann SIMMETRICA (formula numpy.hanning: denominatore len-1, non len). */
    private fun symmetricHann(len: Int): FloatArray {
        if (len == 1) return floatArrayOf(1f)
        return FloatArray(len) { n ->
            (0.5 - 0.5 * cos(2.0 * Math.PI * n / (len - 1))).toFloat()
        }
    }
}
```

**Note for implementer:** `pad = genSize + trim - (n % genSize)` matches the reference `pad = gen_size + trim - (mix.shape[-1] % gen_size)` exactly.

- [ ] **Step 4: Run tests to verify they pass**

Run: `gradle :app:testDebugUnitTest --tests "com.example.vocalremover.MdxChunkerTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/vocalremover/MdxChunker.kt app/src/test/java/com/example/vocalremover/MdxChunkerTest.kt
git commit -m "feat: add MDX-Net outer overlap-add waveform chunker"
```

---

### Task 5: Rewrite VocalRemover.kt for UVR-MDX-NET-Inst_HQ_5

**Files:**
- Modify: `app/src/main/java/com/example/vocalremover/VocalRemover.kt`
- Test: `app/src/test/java/com/example/vocalremover/VocalRemoverIntegrationTest.kt` (new — uses a fake ONNX-less seam, see below)

**Interfaces:**
- Consumes: `MdxStftProcessor` (Tasks 2-3), `MdxChunker` (Task 4).
- Produces: same public API as before — `class VocalRemover(context: Context)`, `suspend fun removeVocals(signal: StereoPcm, onProgress: (Int) -> Unit = {}): StereoPcm`, `fun close()`. `StereoPcm` is unchanged (defined in the same file).

**Design notes for implementer — full algorithm (per-channel, called once for left and once for right... actually NOTE: the reference algorithm processes L+R jointly as a single 2-channel STFT per chunk (4-channel tensor), not independently per channel** — re-read `MdxStftProcessor`/model I/O: the ONNX input is `[batch,4,2560,256]` = `[L_re,L_im,R_re,R_im]` for ONE joint chunk. So `MdxChunker.process` must be called ONCE for a *stereo pair* of chunks in lock-step (both channels windowed identically, same `i`/`step`/`actualLen` per iteration), not independently per channel — **refactor `MdxChunker` is NOT needed**, instead `VocalRemover` should NOT call `chunker.process` twice (once per channel) with two separate `MdxChunker` instances, because that would run the ONNX model twice per chunk redundantly AND could subtly desync if channel lengths ever differ. Instead:

- Add a **second, stereo-aware entry point** to `MdxChunker` in this task (not Task 4, since Task 4 already shipped the mono/single-channel version which stays as-is and is reused internally): `fun processStereo(left: FloatArray, right: FloatArray, processChunk: (FloatArray, FloatArray) -> Pair<FloatArray, FloatArray>): Pair<FloatArray, FloatArray>` — identical windowing/overlap-add logic as `process`, but drives one joint loop over `(left, right)` pairs and calls `processChunk(leftChunk, rightChunk)` once per iteration, returning both reconstructed channels. Implement this by extracting the shared padding/windowing math from `process` into a private helper reused by both entry points.

- `VocalRemover.removeVocals`:
  1. Compute `peak = max(abs(signal.left) + abs(signal.right))`; if `peak > 0.9f`, create scaled copies of left/right (`* (0.9f/peak)`); else use as-is. Remember original `peak`.
  2. Call `chunker.processStereo(leftMaybeScaled, rightMaybeScaled) { chunkL, chunkR -> runModelOnChunk(chunkL, chunkR) }`.
  3. `runModelOnChunk`: `mdxStft.forward(chunkL)` and `.forward(chunkR)` → zero first 3 bins of both → build ONNX input tensor shape `[1,4,2560,256]` with channel order `[L_re,L_im,R_re,R_im]` → run session → read output tensor same shape/order → zero-pad each channel's `re`/`im` from `dimF=2560` back to `freqBins=2561` → `mdxStft.inverse(...)` per channel → return `Pair(instrumentalChunkL, instrumentalChunkR)`.
  4. Multiply the final `processStereo` output by the original `peak` (undo step 1's normalization) — this recovers the reference tool's exact (if unusual) scaling behavior.
  5. Report progress via `onProgress` proportional to chunk index processed (reuse the existing `2..100` convention).
  6. Return `StereoPcm(instrumentalLeft, instrumentalRight)`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.example.vocalremover

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifica la logica di chunking stereo + normalizzazione di picco di VocalRemover SENZA
 * caricare il vero modello ONNX (troppo pesante/lento per un unit test JVM): testiamo
 * direttamente MdxChunker.processStereo con un "modello" fittizio (identity), per garantire
 * che se il vero modello restituisse l'input invariato, l'output combacerebbe esattamente
 * con l'originale (nessuna perdita/introduzione di offset nel chunking a livello VocalRemover).
 */
class VocalRemoverIntegrationTest {

    @Test
    fun `stereo chunker passthrough reconstructs both channels`() = runBlocking {
        val chunker = MdxChunker(nFft = 8, hopLength = 2, dimT = 5, overlap = 0.25)
        val left = FloatArray(50) { (it % 5).toFloat() * 0.1f }
        val right = FloatArray(50) { (it % 7).toFloat() * 0.05f }

        val (outL, outR) = chunker.processStereo(left, right) { chunkL, chunkR -> Pair(chunkL, chunkR) }

        assertEquals(left.size, outL.size)
        assertEquals(right.size, outR.size)
        for (i in left.indices) {
            assertEquals("left[$i]", left[i], outL[i], 1e-3f)
            assertEquals("right[$i]", right[i], outR[i], 1e-3f)
        }
    }

    @Test
    fun `stereo chunker keeps both channels synchronized on same chunk boundaries`() = runBlocking {
        val chunker = MdxChunker(nFft = 8, hopLength = 2, dimT = 5, overlap = 0.25)
        val left = FloatArray(37) { 1f }
        val right = FloatArray(37) { 2f }

        // "Model" that returns chunk index markers instead of passthrough, to prove both
        // channels see the same chunk windows (same actualLen) at each step.
        var callCount = 0
        val (outL, outR) = chunker.processStereo(left, right) { chunkL, chunkR ->
            assertEquals("both channels must have same chunk size", chunkL.size, chunkR.size)
            callCount++
            Pair(chunkL, chunkR)
        }

        assert(callCount > 0)
        assertEquals(37, outL.size)
        assertEquals(37, outR.size)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `gradle :app:testDebugUnitTest --tests "com.example.vocalremover.VocalRemoverIntegrationTest"`
Expected: FAIL (compile error — `MdxChunker.processStereo` does not exist yet).

- [ ] **Step 3a: Add `processStereo` to `MdxChunker.kt`**

Refactor `MdxChunker.kt` (from Task 4) to extract shared math and add the stereo entry point:

```kotlin
package com.example.vocalremover

import kotlin.math.cos
import kotlin.math.min

class MdxChunker(
    nFft: Int = 5120,
    hopLength: Int = 1024,
    dimT: Int = 256,
    private val overlap: Double = 0.25
) {
    private val trim = nFft / 2
    private val chunkSize = hopLength * (dimT - 1)
    private val genSize = chunkSize - 2 * trim

    fun process(mono: FloatArray, processChunk: (FloatArray) -> FloatArray): FloatArray {
        val (left, _) = processStereo(mono, mono) { chunkL, _ -> Pair(processChunk(chunkL), chunkL) }
        return left
    }

    fun processStereo(
        left: FloatArray,
        right: FloatArray,
        processChunk: (FloatArray, FloatArray) -> Pair<FloatArray, FloatArray>
    ): Pair<FloatArray, FloatArray> {
        require(left.size == right.size) { "I canali devono avere la stessa lunghezza" }
        val n = left.size
        val step = ((1.0 - overlap) * chunkSize).toInt()
        val pad = genSize + trim - (n % genSize)
        val mixtureLen = trim + n + pad

        val mixtureL = FloatArray(mixtureLen).also { System.arraycopy(left, 0, it, trim, n) }
        val mixtureR = FloatArray(mixtureLen).also { System.arraycopy(right, 0, it, trim, n) }

        val resultL = FloatArray(mixtureLen)
        val resultR = FloatArray(mixtureLen)
        val divider = FloatArray(mixtureLen)

        var i = 0
        while (i < mixtureLen) {
            val end = min(i + chunkSize, mixtureLen)
            val actualLen = end - i

            val chunkL = FloatArray(chunkSize).also { System.arraycopy(mixtureL, i, it, 0, actualLen) }
            val chunkR = FloatArray(chunkSize).also { System.arraycopy(mixtureR, i, it, 0, actualLen) }

            val (processedL, processedR) = processChunk(chunkL, chunkR)

            if (overlap != 0.0) {
                val win = symmetricHann(actualLen)
                for (k in 0 until actualLen) {
                    resultL[i + k] += processedL[k] * win[k]
                    resultR[i + k] += processedR[k] * win[k]
                    divider[i + k] += win[k]
                }
            } else {
                for (k in 0 until actualLen) {
                    resultL[i + k] += processedL[k]
                    resultR[i + k] += processedR[k]
                    divider[i + k] += 1f
                }
            }
            i += step
        }

        val outL = FloatArray(n)
        val outR = FloatArray(n)
        for (idx in 0 until n) {
            val srcIdx = trim + idx
            val d = divider[srcIdx]
            outL[idx] = if (d > 1e-8f) resultL[srcIdx] / d else 0f
            outR[idx] = if (d > 1e-8f) resultR[srcIdx] / d else 0f
        }
        return Pair(outL, outR)
    }

    private fun symmetricHann(len: Int): FloatArray {
        if (len == 1) return floatArrayOf(1f)
        return FloatArray(len) { n ->
            (0.5 - 0.5 * cos(2.0 * Math.PI * n / (len - 1))).toFloat()
        }
    }
}
```

(This replaces the Task 4 version wholesale — same public `process` behavior, now implemented in terms of `processStereo`, so Task 4's tests must still pass unchanged.)

- [ ] **Step 3b: Run Task 4 + Task 5 chunker tests together to confirm no regression**

Run: `gradle :app:testDebugUnitTest --tests "com.example.vocalremover.MdxChunkerTest" --tests "com.example.vocalremover.VocalRemoverIntegrationTest"`
Expected: PASS (all tests from both classes).

- [ ] **Step 4: Rewrite `VocalRemover.kt`**

```kotlin
package com.example.vocalremover

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import kotlinx.coroutines.ensureActive
import java.nio.FloatBuffer
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.max

data class StereoPcm(
    val left: FloatArray,
    val right: FloatArray
) {
    init {
        require(left.size == right.size) { "I canali stereo devono avere la stessa lunghezza" }
    }

    val size: Int
        get() = left.size

    fun downmix(): FloatArray = FloatArray(size) { i ->
        (left[i] + right[i]) * 0.5f
    }
}

/**
 * Carica il modello ONNX UVR-MDX-NET-Inst_HQ_5 e ricava direttamente la traccia strumentale
 * (il modello è addestrato per emettere lo spettro complesso dello strumentale, non una maschera
 * di rapporto come il precedente Open-Unmix: niente ratio-mask, l'output dell'inferenza va
 * dritto in ISTFT). Sostituisce il precedente modello Open-Unmix a seguito di un benchmark su
 * audio reale che ha mostrato una perdita di voce residua (cross-leak) 3-4 volte inferiore
 * (0.02-0.03 contro 0.08-0.14).
 */
class VocalRemover(context: Context) {

    companion object {
        private const val TAG = "VocalRemover"
        private const val MODEL_ASSET = "vocal_remover.onnx"
        private const val N_FFT = 5120
        private const val HOP_LENGTH = 1024
        private const val DIM_F = 2560
        private const val DIM_T = 256
        private const val OVERLAP = 0.25
        private const val NORMALIZATION_PEAK = 0.9f
    }

    private val mdxStft = MdxStftProcessor(nFft = N_FFT, hopLength = HOP_LENGTH, dimF = DIM_F)
    private val chunker = MdxChunker(nFft = N_FFT, hopLength = HOP_LENGTH, dimT = DIM_T, overlap = OVERLAP)
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String
    private val outputName: String

    init {
        val modelBytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(4)
            try {
                addNnapi()
                Log.d(TAG, "NNAPI execution provider attivo")
            } catch (e: Exception) {
                Log.w(TAG, "NNAPI non disponibile, uso CPU: ${e.message}")
            }
        }
        session = env.createSession(modelBytes, options)
        inputName = session.inputNames.iterator().next()
        outputName = session.outputNames.iterator().next()
    }

    suspend fun removeVocals(
        signal: StereoPcm,
        onProgress: (Int) -> Unit = {}
    ): StereoPcm {
        onProgress(2)

        // 1. Normalizzazione di picco (replica esatta del comportamento del tool di riferimento
        //    audio-separator: se il picco supera 0.9, scala giù prima di processare, poi
        //    ri-scala il risultato per il picco ORIGINALE, non per 0.9).
        var peak = 0f
        for (i in 0 until signal.size) {
            peak = max(peak, max(abs(signal.left[i]), abs(signal.right[i])))
        }
        val (procLeft, procRight) = if (peak > NORMALIZATION_PEAK) {
            val scale = NORMALIZATION_PEAK / peak
            val l = FloatArray(signal.size) { signal.left[it] * scale }
            val r = FloatArray(signal.size) { signal.right[it] * scale }
            Pair(l, r)
        } else {
            Pair(signal.left, signal.right)
        }

        // 2. Chunking a finestre sovrapposte (25%) con inferenza ONNX per chunk.
        var chunksDone = 0
        val estimatedChunks = max(1, (signal.size / (HOP_LENGTH * (DIM_T - 1) / 2)) + 1)
        val (instL, instR) = chunker.processStereo(procLeft, procRight) { chunkL, chunkR ->
            coroutineContext.ensureActive()
            val result = runModelOnChunk(chunkL, chunkR)
            chunksDone++
            onProgress(2 + minOf(93, (chunksDone * 93) / estimatedChunks))
            result
        }

        // 3. Annulla la normalizzazione di picco del punto 1.
        val instrumentalLeft = FloatArray(signal.size) { instL[it] * peak }
        val instrumentalRight = FloatArray(signal.size) { instR[it] * peak }

        onProgress(100)
        return StereoPcm(instrumentalLeft, instrumentalRight)
    }

    /** Esegue STFT -> ONNX -> ISTFT su un singolo chunk stereo (dimensione fissa chunkSize). */
    private fun runModelOnChunk(chunkL: FloatArray, chunkR: FloatArray): Pair<FloatArray, FloatArray> {
        val (leftRe, leftIm) = mdxStft.forward(chunkL)
        val (rightRe, rightIm) = mdxStft.forward(chunkR)

        // Azzera le prime 3 bin di frequenza (riduzione rumore a bassa frequenza, come da
        // implementazione di riferimento: spek[:, :, :3, :] *= 0).
        zeroFirstBins(leftRe, leftIm, DIM_F, 3)
        zeroFirstBins(rightRe, rightIm, DIM_F, 3)

        val inputData = FloatArray(4 * DIM_F * DIM_T)
        // Layout canale: [L_re, L_im, R_re, R_im], ciascuno [DIM_F, DIM_T] (bin-major, poi tempo).
        writeChannel(inputData, 0, leftRe)
        writeChannel(inputData, 1, leftIm)
        writeChannel(inputData, 2, rightRe)
        writeChannel(inputData, 3, rightIm)

        val shape = longArrayOf(1L, 4L, DIM_F.toLong(), DIM_T.toLong())
        val inputBuffer = FloatBuffer.wrap(inputData)
        OnnxTensor.createTensor(env, inputBuffer, shape).use { inputTensor ->
            session.run(mapOf(inputName to inputTensor)).use { results ->
                val outputTensor = results.get(outputName).get() as OnnxTensor
                val outBuffer = outputTensor.floatBuffer
                val outData = FloatArray(4 * DIM_F * DIM_T)
                outBuffer.get(outData)

                val outLeftRe = readChannel(outData, 0)
                val outLeftIm = readChannel(outData, 1)
                val outRightRe = readChannel(outData, 2)
                val outRightIm = readChannel(outData, 3)

                // Ripristina le bin di frequenza da DIM_F a freqBins (nFft/2+1) con zero-padding
                // prima dell'ISTFT (il modello opera solo sulle frequenze più basse).
                val leftFullRe = padToFreqBins(outLeftRe, DIM_T)
                val leftFullIm = padToFreqBins(outLeftIm, DIM_T)
                val rightFullRe = padToFreqBins(outRightRe, DIM_T)
                val rightFullIm = padToFreqBins(outRightIm, DIM_T)

                val outChunkL = mdxStft.inverse(leftFullRe, leftFullIm, DIM_T, chunkL.size)
                val outChunkR = mdxStft.inverse(rightFullRe, rightFullIm, DIM_T, chunkR.size)
                return Pair(outChunkL, outChunkR)
            }
        }
    }

    private fun zeroFirstBins(re: FloatArray, im: FloatArray, dimF: Int, count: Int) {
        val frames = re.size / dimF
        for (frame in 0 until frames) {
            for (bin in 0 until count) {
                re[frame * dimF + bin] = 0f
                im[frame * dimF + bin] = 0f
            }
        }
    }

    /** Scrive re/im [frames*dimF] (bin-major) nel layout [channel][dimF][dimT] atteso dal tensore ONNX. */
    private fun writeChannel(dest: FloatArray, channel: Int, data: FloatArray) {
        val channelOffset = channel * DIM_F * DIM_T
        for (frame in 0 until DIM_T) {
            for (bin in 0 until DIM_F) {
                dest[channelOffset + bin * DIM_T + frame] = data[frame * DIM_F + bin]
            }
        }
    }

    /** Legge un canale [DIM_F, DIM_T] dal tensore di output e lo riporta al layout [frame*dimF+bin]. */
    private fun readChannel(src: FloatArray, channel: Int): FloatArray {
        val channelOffset = channel * DIM_F * DIM_T
        val out = FloatArray(DIM_T * DIM_F)
        for (frame in 0 until DIM_T) {
            for (bin in 0 until DIM_F) {
                out[frame * DIM_F + bin] = src[channelOffset + bin * DIM_T + frame]
            }
        }
        return out
    }

    /** Riporta [data] (piatto [frames*DIM_F]) a [frames*freqBins], zero-paddando le bin mancanti. */
    private fun padToFreqBins(data: FloatArray, frames: Int): FloatArray {
        val freqBins = mdxStft.freqBins
        val out = FloatArray(frames * freqBins)
        for (frame in 0 until frames) {
            System.arraycopy(data, frame * DIM_F, out, frame * freqBins, DIM_F)
        }
        return out
    }

    fun close() = session.close()
}
```

**Note for implementer:** the ONNX tensor's frequency-major-vs-time-major memory layout (`writeChannel`/`readChannel`) MUST be verified against the actual model before trusting this code — inspect via `onnxruntime` in Python (`sess.get_inputs()[0].shape == ['batch_size', 4, 2560, 256]`, i.e., dims are `[batch, channel, freq=2560, time=256]`, so the tensor is **freq-major, time-minor** — confirm the flat buffer layout ONNX Runtime Java expects is row-major C order matching `[channel][freq][time]`, i.e. index `= channel*DIM_F*DIM_T + bin*DIM_T + frame`, which is exactly what `writeChannel`/`readChannel` implement above. This is a common source of transposition bugs — Task 7's cross-validation against the Python reference on real audio is the authoritative check for this.

- [ ] **Step 5: Run all unit tests**

Run: `gradle :app:testDebugUnitTest`
Expected: PASS (all existing + new tests; note `VocalRemover` itself isn't unit-testable without Android/ONNX, so it has no direct test — its correctness is validated by Task 7's real-audio cross-check).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/example/vocalremover/VocalRemover.kt app/src/main/java/com/example/vocalremover/MdxChunker.kt app/src/test/java/com/example/vocalremover/VocalRemoverIntegrationTest.kt
git commit -m "feat: rewrite VocalRemover for UVR-MDX-NET-Inst_HQ_5 model"
```

---

### Task 6: Swap model asset and update documentation

**Files:**
- Modify: `README.md`
- Manual (not git-tracked): `app/src/main/assets/vocal_remover.onnx`

- [ ] **Step 1: Update README model setup section**

```markdown
Il modello `vocal_remover.onnx` (~59MB, UVR-MDX-NET-Inst_HQ_5) **non è incluso nel repository** perché supera il limite di 100MB imposto da GitHub (o comunque per non appesantire il repo). Va scaricato manualmente prima di poter compilare/eseguire l'app.

1. Scarica `UVR-MDX-NET-Inst_HQ_5.onnx` da [Ultimate Vocal Remover models](https://github.com/TRvlvr/model_repo/releases) (o dal repository/hub da cui è stato ottenuto).
2. Rinominalo in `vocal_remover.onnx`.
3. Copialo in `app/src/main/assets/vocal_remover.onnx`.

Il file è elencato in `.gitignore` (pattern `*.onnx`): resterà sul tuo disco ma non verrà mai committato per errore. Ogni sviluppatore/macchina di build deve ripetere questo passaggio dopo il clone.
```

Also update the top-of-file description line (`riga 3`, currently `... e un modello Open-Unmix (UMX-L)`) to `... e un modello UVR-MDX-NET (Inst_HQ_5)`.

- [ ] **Step 2: Copy the real model file into place for local building/testing**

```bash
cp /home/stefano/Scaricati/UVR-MDX-NET-Inst_HQ_5.onnx /home/stefano/VocalRemoverApp/app/src/main/assets/vocal_remover.onnx
```

- [ ] **Step 3: Commit (README only — the .onnx asset is gitignored)**

```bash
git add README.md
git commit -m "docs: update model setup instructions for UVR-MDX-NET-Inst_HQ_5"
```

---

### Task 7: Cross-validate against the Python reference implementation on real audio

**Files:** none (verification-only task, no new source files).

This is the most important task for catching subtle bugs (tensor layout transposition, sign errors in Bluestein, off-by-one in reflect padding) that unit tests with small synthetic signals cannot fully rule out.

- [ ] **Step 1: Build a debug APK with the new model and run it (or a JVM harness) on `/home/stefano/.cache/raw_1205.wav`**

Since `onnxruntime-android` doesn't run in a plain JVM unit test, the most direct approach is an instrumented run. If a physical/emulated device is available:

```bash
gradle :app:assembleDebug
```

Then load `raw_1205.wav` (or the app's existing capture flow) through the app's "carica file ed elabora" path and export the resulting instrumental WAV.

- [ ] **Step 2: Run the same input through the Python reference (`audio-separator`) for comparison**

```bash
cd /home/stefano/.cache/mdxnet_venv && source venv/bin/activate
python3 -c "
from audio_separator.separator import Separator
sep = Separator(output_dir='/home/stefano/.cache/mdx_reference_output')
sep.load_model(model_filename='/home/stefano/Scaricati/UVR-MDX-NET-Inst_HQ_5.onnx')
sep.separate('/home/stefano/.cache/raw_1205.wav')
"
```

- [ ] **Step 3: Compare the app's Kotlin output against the Python reference output**

```bash
source /tmp/demucs_venv/bin/activate
python3 -c "
import soundfile as sf
import numpy as np

def corr(a, b):
    if a.ndim > 1: a = a.mean(axis=1)
    if b.ndim > 1: b = b.mean(axis=1)
    n = min(len(a), len(b)); a = a[:n]; b = b[:n]
    a = a - a.mean(); b = b - b.mean()
    return np.dot(a, b) / (np.linalg.norm(a) * np.linalg.norm(b) + 1e-9)

kotlin_out, _ = sf.read('<path to app-exported instrumental wav>')
python_ref, _ = sf.read('<path to audio-separator instrumental output>')
print('correlation (expect > 0.95 if the port is correct):', corr(kotlin_out, python_ref))
"
```

Expected: correlation > 0.95. If significantly lower, the most likely bugs, in order of likelihood, are: (a) `writeChannel`/`readChannel` tensor layout transposition (Task 5's flagged risk), (b) Bluestein inverse sign error not caught by the small synthetic tests, (c) reflect-padding boundary handling for very short/edge chunks, (d) peak-normalization threshold logic. Add a temporary Kotlin `Log.d` dump of intermediate STFT magnitude sums per chunk and compare against equivalent Python-side prints to bisect which stage diverges.

- [ ] **Step 4: If correlation confirms correctness, clean up temporary reference-output files**

```bash
rm -rf /home/stefano/.cache/mdx_reference_output
```

---

### Task 8: Final build, lint, and full test suite verification

**Files:** none (verification-only).

- [ ] **Step 1: Run the full unit test suite**

Run: `gradle :app:testDebugUnitTest`
Expected: PASS (all tests, including pre-existing `StftProcessorTest`, capture package tests, and all new Bluestein/MdxStft/MdxChunker/VocalRemoverIntegration tests).

- [ ] **Step 2: Run lint**

Run: `gradle :app:lintDebug`
Expected: no new errors introduced (warnings pre-existing before this change are out of scope).

- [ ] **Step 3: Run a full debug build**

Run: `gradle :app:assembleDebug`
Expected: BUILD SUCCESSFUL, with `vocal_remover.onnx` (UVR-MDX-NET-Inst_HQ_5, ~59MB) bundled from `app/src/main/assets/`.

- [ ] **Step 4: Manual smoke test on device/emulator (if available)**

Load a real captured recording through the app's existing UI flow and confirm: (a) processing completes without crashing or OOM, (b) progress bar advances smoothly from 2% to 100%, (c) output audio is audibly instrumental-only with noticeably less vocal bleed than before (subjective final check, complementing Task 7's quantitative correlation check).

- [ ] **Step 5: Final commit (if any cleanup was needed)**

```bash
git add -A
git commit -m "chore: final verification pass for UVR-MDX-NET-Inst_HQ_5 integration"
```

---

## Self-Review Notes

- **Spec coverage:** Bluestein FFT (Task 1) ✅, centered STFT/ISTFT (Tasks 2-3) ✅, dim_f crop + 4-channel tensor layout (Tasks 2, 5) ✅, outer 25%-overlap chunking with symmetric Hann (Tasks 4-5) ✅, peak normalization (Task 5) ✅, first-3-bins zeroing (Task 5) ✅, model asset swap + docs (Task 6) ✅, real-audio cross-validation (Task 7) ✅, build/lint/test (Task 8) ✅. No `compensate`/secondary-source logic is implemented, intentionally (Global Constraints explains why — the app only needs the primary/instrumental stem).
- **Type/interface consistency:** `MdxStftProcessor(nFft, hopLength, dimF)` constructor signature, `forward`/`inverse` method signatures, and `MdxChunker(nFft, hopLength, dimT, overlap)` / `processStereo` signature are used identically across Tasks 2, 3, 4, 5 — verified consistent.
