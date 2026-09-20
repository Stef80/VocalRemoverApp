package com.example.vocalremover

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Trasformata di Fourier discreta (DFT/IDFT) per lunghezze arbitrarie (non necessariamente
 * potenze di 2), tramite l'algoritmo di Bluestein (chirp z-transform). Riusa una FFT radix-2
 * interna (potenza di 2) come motore di convoluzione circolare, quindi non richiede alcuna
 * libreria esterna. Necessaria per il modello UVR-MDX-NET (nFft=5120 = 2^10 * 5, non potenza
 * di 2) mentre StftProcessor (nFft=4096, potenza di 2) usa la propria FFT radix-2 diretta.
 */
object BluesteinFft {

    class Workspace internal constructor(
        internal val size: Int,
        internal val re: FloatArray,
        internal val im: FloatArray
    )

    private class Plan(val n: Int) {
        val m: Int
        val chirpRe: FloatArray
        val chirpIm: FloatArray
        // Trasformata precomputata di "b" (chirp coniugato, zero-padded a lunghezza m) per il
        // caso forward, riutilizzabile per ogni chiamata con lo stesso n.
        val bFftReForward: FloatArray
        val bFftImForward: FloatArray
        // Idem per il caso inverse (chirp con segno opposto).
        val bFftReInverse: FloatArray
        val bFftImInverse: FloatArray

        init {
            var mm = 1
            while (mm < 2 * n - 1) mm = mm shl 1
            m = mm

            chirpRe = FloatArray(n)
            chirpIm = FloatArray(n)
            for (k in 0 until n) {
                // angolo = pi * k^2 / n; usiamo k^2 mod (2n) per evitare overflow/perdita di
                // precisione con k grandi (fino a n=5120 -> k^2 fino a ~26 milioni, ok in Long).
                val kk = (k.toLong() * k.toLong()) % (2L * n)
                val angle = PI * kk / n
                chirpRe[k] = cos(angle).toFloat()
                chirpIm[k] = sin(angle).toFloat()
            }

            // Forward: "a" usa exp(-i*pi*k^2/n), quindi "b" (per la convoluzione circolare
            // a*b) deve usare il segno OPPOSTO: exp(+i*pi*m^2/n).
            val bReF = FloatArray(m)
            val bImF = FloatArray(m)
            bReF[0] = chirpRe[0]
            bImF[0] = chirpIm[0]
            for (k in 1 until n) {
                bReF[k] = chirpRe[k]
                bImF[k] = chirpIm[k]
                bReF[m - k] = chirpRe[k]
                bImF[m - k] = chirpIm[k]
            }
            bFftReForward = bReF
            bFftImForward = bImF
            radix2Fft(bFftReForward, bFftImForward, inverse = false)

            // Inverse: "a" usa exp(+i*pi*k^2/n), quindi "b" deve usare il segno opposto:
            // exp(-i*pi*m^2/n).
            val bReI = FloatArray(m)
            val bImI = FloatArray(m)
            bReI[0] = chirpRe[0]
            bImI[0] = -chirpIm[0]
            for (k in 1 until n) {
                bReI[k] = chirpRe[k]
                bImI[k] = -chirpIm[k]
                bReI[m - k] = chirpRe[k]
                bImI[m - k] = -chirpIm[k]
            }
            bFftReInverse = bReI
            bFftImInverse = bImI
            radix2Fft(bFftReInverse, bFftImInverse, inverse = false)
        }
    }

    private val planCache = ConcurrentHashMap<Int, Plan>()
    private val threadWorkspaces = ThreadLocal<MutableMap<Int, Workspace>>()

    private fun planFor(n: Int): Plan = planCache.computeIfAbsent(n) { Plan(it) }

    internal fun newWorkspace(n: Int): Workspace {
        require(n > 0) { "La dimensione FFT deve essere positiva" }
        val plan = planFor(n)
        return Workspace(n, FloatArray(plan.m), FloatArray(plan.m))
    }

    private fun workspaceFor(n: Int): Workspace {
        val workspaces = threadWorkspaces.get() ?: HashMap<Int, Workspace>().also(threadWorkspaces::set)
        return workspaces.getOrPut(n) { newWorkspace(n) }
    }

    /**
     * DFT (o IDFT se [inverse]=true) in-place di lunghezza arbitraria [re].size.
     * Per lunghezze potenza di 2 delega direttamente alla FFT radix-2 (più veloce, nessun
     * overhead di zero-padding). Per le altre lunghezze usa Bluestein.
     */
    fun transform(
        re: FloatArray,
        im: FloatArray,
        inverse: Boolean = false,
        workspace: Workspace? = null
    ) {
        val n = re.size
        require(im.size == n) { "re e im devono avere la stessa lunghezza" }
        if (n == 0) return
        if (n and (n - 1) == 0) {
            radix2Fft(re, im, inverse)
            return
        }

        val plan = planFor(n)
        val scratch = workspace ?: workspaceFor(n)
        require(scratch.size == n) { "Il workspace FFT deve avere dimensione $n" }

        // a[k] = x[k] * exp(sign * i * pi * k^2 / n), zero-padded a lunghezza m.
        // forward: sign=-1 -> conj(chirp) = (chirpRe, -chirpIm)
        // inverse: sign=+1 -> chirp = (chirpRe, chirpIm)
        val aRe = scratch.re
        val aIm = scratch.im
        for (k in 0 until n) {
            val cRe = plan.chirpRe[k]
            val cIm = if (inverse) plan.chirpIm[k] else -plan.chirpIm[k]
            aRe[k] = re[k] * cRe - im[k] * cIm
            aIm[k] = re[k] * cIm + im[k] * cRe
        }
        java.util.Arrays.fill(aRe, n, plan.m, 0f)
        java.util.Arrays.fill(aIm, n, plan.m, 0f)

        radix2Fft(aRe, aIm, inverse = false)

        val bRe = if (inverse) plan.bFftReInverse else plan.bFftReForward
        val bIm = if (inverse) plan.bFftImInverse else plan.bFftImForward

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
            val cIm = if (inverse) plan.chirpIm[k] else -plan.chirpIm[k]
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
