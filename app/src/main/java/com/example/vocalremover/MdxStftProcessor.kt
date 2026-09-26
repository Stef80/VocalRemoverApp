package com.example.vocalremover

import kotlin.math.PI
import kotlin.math.cos

/**
 * STFT/ISTFT "centrata" (reflect-padding) per UVR-MDX-NET-Inst_HQ_5 (nFft=5120).
 *
 * NOTA: la classe non è thread-safe: riutilizza buffer interni tra chiamate. Tutte le
 * chiamate devono avvenire in serie dallo stesso thread (come nel flusso di VocalRemover).
 */
class MdxStftProcessor(
    val nFft: Int = 5120,
    val hopLength: Int = 1024,
    val dimF: Int = 2560
) {
    val freqBins: Int = nFft / 2 + 1

    private val window: FloatArray = FloatArray(nFft) { n ->
        (0.5f * (1.0 - cos(2.0 * PI * n / nFft))).toFloat()
    }
    private val windowSquareSum: FloatArray = FloatArray(nFft) { window[it] * window[it] }

    /** FFT diretta 5·2^k (nFft=5120); le altre lunghezze ricadono su Bluestein. */
    private val mixedRadixFft: MixedRadixFft? =
        if (MixedRadixFft.supports(nFft)) MixedRadixFft(nFft) else null

    // --- Scratch riutilizzabili ---------------------------------------------
    private var paddedBuf: FloatArray = FloatArray(0)
    private var paddedBufR: FloatArray = FloatArray(0)
    private var overlapAccR: FloatArray = FloatArray(0)
    private val fftRe = FloatArray(nFft)
    private val fftIm = FloatArray(nFft)
    private val frameRe = FloatArray(nFft)
    private val frameIm = FloatArray(nFft)
    private var overlapAcc: FloatArray = FloatArray(0)

    /**
     * normSum dell'ISTFT è costante per un dato numero di frame (dipende solo da
     * hopLength/nFft/frames). Cachata: nel caso reale frames = dimT = 256 sempre,
     * quindi viene calcolata UNA sola volta per tutta la sessione.
     */
    private val normSumCache = HashMap<Int, FloatArray>()

    fun frameCountFor(signalLength: Int): Int = signalLength / hopLength + 1

    // ---------- STFT (forward) ----------

    /** Wrapper allocante per compatibilità con il codice esistente. */
    fun forward(signal: FloatArray): Pair<FloatArray, FloatArray> {
        val outRe = FloatArray(frameCountFor(signal.size) * dimF)
        val outIm = FloatArray(outRe.size)
        forwardInto(signal, outRe, outIm)
        return Pair(outRe, outIm)
    }

    /**
     * Variante zero-alloc: scrive in [outRe]/[outIm] già dimensionati a
     * `frameCountFor(signal.size) * dimF`. Riusa paddedBuf/fftRe/fftIm tra chiamate.
     */
    fun forwardInto(signal: FloatArray, outRe: FloatArray, outIm: FloatArray) {
        val half = nFft / 2
        val paddedLen = signal.size + 2 * half
        ensurePadded(paddedLen)
        reflectPadInto(signal, half, paddedBuf)

        val frames = frameCountFor(signal.size)
        val padded = paddedBuf
        val win = window
        val re = fftRe
        val im = fftIm
        val dF = dimF

        for (frame in 0 until frames) {
            val start = frame * hopLength
            // Unico loop di multiply: `im[i] = 0f` è obbligatorio perché Bluestein legge
            // l'immaginario in input.
            for (i in 0 until nFft) {
                re[i] = padded[start + i] * win[i]
                im[i] = 0f
            }
            fft(re, im, inverse = false)
            // Copia bulk contigua invece del loop per bin.
            val dst = frame * dF
            System.arraycopy(re, 0, outRe, dst, dF)
            System.arraycopy(im, 0, outIm, dst, dF)
        }
    }

    // ---------- ISTFT (inverse) ----------

    /** Wrapper allocante per compatibilità. */
    fun inverse(re: FloatArray, im: FloatArray, frames: Int, outputLength: Int): FloatArray {
        val out = FloatArray(outputLength)
        inverseInto(re, im, frames, outputLength, out)
        return out
    }

    /**
     * Variante zero-alloc: scrive in [out] (già dimensionato a [outputLength]).
     * [re]/[im] sono piatti [frames * freqBins] (devono arrivare fino a freqBins).
     */
    fun inverseInto(
        re: FloatArray,
        im: FloatArray,
        frames: Int,
        outputLength: Int,
        out: FloatArray
    ) {
        val paddedLen = (frames - 1) * hopLength + nFft
        ensureOverlapAcc(paddedLen)
        val acc = overlapAcc

        val win = window
        val fr = frameRe
        val fi = frameIm
        val fBins = freqBins
        val nF = nFft

        for (frame in 0 until frames) {
            val offset = frame * fBins
            System.arraycopy(re, offset, fr, 0, fBins)
            System.arraycopy(im, offset, fi, 0, fBins)

            // Simmetria hermitiana per le frequenze da freqBins a nFft-1.
            for (k in fBins until nF) {
                fr[k] = fr[nF - k]
                fi[k] = -fi[nF - k]
            }
            fft(fr, fi, inverse = true)

            val start = frame * hopLength
            for (i in 0 until nF) {
                acc[start + i] += fr[i] * win[i]
            }
        }

        normalizeAndTrim(acc, frames, outputLength, out)
    }

    // ---------- STFT/ISTFT stereo (due canali reali in una sola FFT complessa) ----------

    /**
     * STFT di [left] e [right] con una sola FFT complessa per frame: z = L + i·R, poi
     * separazione tramite simmetria hermitiana. Risultato identico a due [forwardInto].
     */
    fun forwardStereoInto(
        left: FloatArray,
        right: FloatArray,
        outLRe: FloatArray,
        outLIm: FloatArray,
        outRRe: FloatArray,
        outRIm: FloatArray
    ) {
        require(left.size == right.size) { "I canali stereo devono avere la stessa lunghezza" }
        val half = nFft / 2
        val paddedLen = left.size + 2 * half
        ensurePadded(paddedLen)
        if (paddedBufR.size < paddedLen) paddedBufR = FloatArray(paddedLen)
        reflectPadInto(left, half, paddedBuf)
        reflectPadInto(right, half, paddedBufR)

        val frames = frameCountFor(left.size)
        val padL = paddedBuf
        val padR = paddedBufR
        val win = window
        val re = fftRe
        val im = fftIm
        val dF = dimF
        val nF = nFft

        for (frame in 0 until frames) {
            val start = frame * hopLength
            for (i in 0 until nF) {
                val w = win[i]
                re[i] = padL[start + i] * w
                im[i] = padR[start + i] * w
            }
            fft(re, im, inverse = false)

            val dst = frame * dF
            for (k in 0 until dF) {
                val nk = if (k == 0) 0 else nF - k
                val zr = re[k]; val zi = im[k]
                val cr = re[nk]; val ci = im[nk]
                outLRe[dst + k] = 0.5f * (zr + cr)
                outLIm[dst + k] = 0.5f * (zi - ci)
                outRRe[dst + k] = 0.5f * (zi + ci)
                outRIm[dst + k] = 0.5f * (cr - zr)
            }
        }
    }

    /**
     * ISTFT di due canali con una sola IFFT complessa per frame: Z = X_L + i·X_R (spettri
     * resi hermitiani), quindi Re → L, Im → R. Risultato identico a due [inverseInto].
     */
    fun inverseStereoInto(
        lRe: FloatArray,
        lIm: FloatArray,
        rRe: FloatArray,
        rIm: FloatArray,
        frames: Int,
        outputLength: Int,
        outL: FloatArray,
        outR: FloatArray
    ) {
        require(nFft % 2 == 0) { "inverseStereoInto richiede nFft pari" }
        val paddedLen = (frames - 1) * hopLength + nFft
        ensureOverlapAcc(paddedLen)
        if (overlapAccR.size < paddedLen) {
            overlapAccR = FloatArray(paddedLen)
        } else {
            java.util.Arrays.fill(overlapAccR, 0, paddedLen, 0f)
        }
        val accL = overlapAcc
        val accR = overlapAccR

        val win = window
        val zr = frameRe
        val zi = frameIm
        val fBins = freqBins
        val nF = nFft
        val h = nF / 2

        for (frame in 0 until frames) {
            val o = frame * fBins
            // DC e Nyquist: l'inverse per canale scarta la parte immaginaria (prende Re(IFFT)).
            zr[0] = lRe[o]; zi[0] = rRe[o]
            zr[h] = lRe[o + h]; zi[h] = rRe[o + h]
            for (k in 1 until h) {
                val lr = lRe[o + k]; val li = lIm[o + k]
                val rr = rRe[o + k]; val ri = rIm[o + k]
                zr[k] = lr - ri; zi[k] = li + rr
                zr[nF - k] = lr + ri; zi[nF - k] = rr - li
            }
            fft(zr, zi, inverse = true)

            val start = frame * hopLength
            for (i in 0 until nF) {
                val w = win[i]
                accL[start + i] += zr[i] * w
                accR[start + i] += zi[i] * w
            }
        }

        normalizeAndTrim(accL, frames, outputLength, outL)
        normalizeAndTrim(accR, frames, outputLength, outR)
    }

    // ---------- helpers ----------

    private fun fft(re: FloatArray, im: FloatArray, inverse: Boolean) {
        val engine = mixedRadixFft
        if (engine != null) engine.transform(re, im, inverse) else BluesteinFft.transform(re, im, inverse)
    }

    private fun normalizeAndTrim(acc: FloatArray, frames: Int, outputLength: Int, out: FloatArray) {
        val paddedLen = (frames - 1) * hopLength + nFft
        val nF = nFft
        // Normalizzazione: usa la normSum cachata. Equivalente all'originale ma
        // senza accumulare windowSquareSum per ogni frame ad ogni chiamata.
        val normSum = getNormSum(frames)
        for (i in 0 until paddedLen) {
            val d = normSum[i]
            if (d > 1e-8f) acc[i] /= d
        }

        // Trim del reflect-padding (nFft/2 per lato), copia bulk.
        val half = nF / 2
        val available = paddedLen - half
        val copyLen = if (outputLength <= available) outputLength else available
        if (copyLen > 0) System.arraycopy(acc, half, out, 0, copyLen)
        if (copyLen < outputLength) {
            java.util.Arrays.fill(out, copyLen, outputLength, 0f)
        }
    }

    private fun ensurePadded(len: Int) {
        if (paddedBuf.size < len) paddedBuf = FloatArray(len)
    }

    private fun ensureOverlapAcc(len: Int) {
        if (overlapAcc.size < len) {
            overlapAcc = FloatArray(len)
        } else {
            java.util.Arrays.fill(overlapAcc, 0, len, 0f)
        }
    }

    private fun reflectPadInto(signal: FloatArray, pad: Int, out: FloatArray) {
        val n = signal.size
        for (i in 0 until pad) {
            out[i] = signal[reflectIndex(pad - i, n)]
        }
        System.arraycopy(signal, 0, out, pad, n)
        for (i in 0 until pad) {
            out[pad + n + i] = signal[reflectIndex(n - 2 - i, n)]
        }
    }

    private fun reflectIndex(indexIn: Int, n: Int): Int {
        if (n == 1) return 0
        var idx = indexIn
        val period = 2 * (n - 1)
        idx %= period
        if (idx < 0) idx += period
        return if (idx < n) idx else period - idx
    }

    private fun getNormSum(frames: Int): FloatArray {
        normSumCache[frames]?.let { return it }
        val paddedLen = (frames - 1) * hopLength + nFft
        val ns = FloatArray(paddedLen)
        for (frame in 0 until frames) {
            val start = frame * hopLength
            for (i in 0 until nFft) {
                ns[start + i] += windowSquareSum[i]
            }
        }
        normSumCache[frames] = ns
        return ns
    }
}