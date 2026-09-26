package com.example.vocalremover

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * FFT complessa in-place per n = 2^k oppure n = 5·2^k (es. nFft=5120 = 5·1024 del modello MDX).
 *
 * Decimazione nel tempo in 5 sotto-FFT radix-2 da n/5 punti + butterfly radix-5, con tabelle
 * di bit-reversal e twiddle precomputate. Molto più economica di Bluestein, che per n=5120
 * richiede due FFT da 16384 punti per trasformata.
 *
 * Convenzione: forward = exp(-2πi·kn/N); inverse = exp(+2πi·kn/N) scalata di 1/N.
 * Non thread-safe: usa un workspace interno.
 */
class MixedRadixFft(val n: Int) {

    companion object {
        fun supports(n: Int): Boolean {
            if (n <= 0) return false
            val m = if (n % 5 == 0) n / 5 else n
            return m and (m - 1) == 0
        }

        private val C1 = cos(2.0 * PI / 5.0).toFloat()
        private val C2 = cos(4.0 * PI / 5.0).toFloat()
        private val S1 = sin(2.0 * PI / 5.0).toFloat()
        private val S2 = sin(4.0 * PI / 5.0).toFloat()
    }

    private val radix: Int
    private val m: Int
    private val bitReverse: IntArray
    private val cosTable: FloatArray
    private val sinTable: FloatArray
    private val twiddleCos: FloatArray
    private val twiddleSin: FloatArray
    private val subRe: FloatArray
    private val subIm: FloatArray

    init {
        require(supports(n)) { "MixedRadixFft supporta solo n = 2^k o 5·2^k (n=$n)" }
        radix = if (n and (n - 1) == 0) 1 else 5
        m = n / radix

        var bits = 0
        while ((1 shl bits) < m) bits++
        bitReverse = IntArray(m) { i ->
            var r = 0
            var x = i
            for (b in 0 until bits) {
                r = (r shl 1) or (x and 1)
                x = x shr 1
            }
            r
        }
        val half = maxOf(1, m / 2)
        cosTable = FloatArray(half) { cos(2.0 * PI * it / m).toFloat() }
        sinTable = FloatArray(half) { sin(2.0 * PI * it / m).toFloat() }

        if (radix == 5) {
            twiddleCos = FloatArray(4 * m)
            twiddleSin = FloatArray(4 * m)
            for (r in 1..4) {
                for (k in 0 until m) {
                    val ang = 2.0 * PI * ((r.toLong() * k) % n) / n
                    twiddleCos[(r - 1) * m + k] = cos(ang).toFloat()
                    twiddleSin[(r - 1) * m + k] = sin(ang).toFloat()
                }
            }
            subRe = FloatArray(n)
            subIm = FloatArray(n)
        } else {
            twiddleCos = FloatArray(0)
            twiddleSin = FloatArray(0)
            subRe = FloatArray(0)
            subIm = FloatArray(0)
        }
    }

    fun transform(re: FloatArray, im: FloatArray, inverse: Boolean = false) {
        require(re.size >= n && im.size >= n) { "Buffer FFT troppo corti (n=$n)" }
        if (radix == 1) {
            radix2(re, im, 0, inverse)
        } else {
            radix5(re, im, inverse)
        }
        if (inverse) {
            val scale = 1f / n
            for (i in 0 until n) {
                re[i] *= scale
                im[i] *= scale
            }
        }
    }

    private fun radix5(re: FloatArray, im: FloatArray, inverse: Boolean) {
        val sr = subRe
        val si = subIm
        val mm = m
        for (r in 0 until 5) {
            val base = r * mm
            var src = r
            for (t in 0 until mm) {
                sr[base + t] = re[src]
                si[base + t] = im[src]
                src += 5
            }
            radix2(sr, si, base, inverse)
        }

        val sign = if (inverse) 1f else -1f
        val tc = twiddleCos
        val ts = twiddleSin
        for (k in 0 until mm) {
            val y0r = sr[k]
            val y0i = si[k]

            var c = tc[k]; var s = sign * ts[k]
            var xr = sr[mm + k]; var xi = si[mm + k]
            val y1r = xr * c - xi * s; val y1i = xr * s + xi * c

            c = tc[mm + k]; s = sign * ts[mm + k]
            xr = sr[2 * mm + k]; xi = si[2 * mm + k]
            val y2r = xr * c - xi * s; val y2i = xr * s + xi * c

            c = tc[2 * mm + k]; s = sign * ts[2 * mm + k]
            xr = sr[3 * mm + k]; xi = si[3 * mm + k]
            val y3r = xr * c - xi * s; val y3i = xr * s + xi * c

            c = tc[3 * mm + k]; s = sign * ts[3 * mm + k]
            xr = sr[4 * mm + k]; xi = si[4 * mm + k]
            val y4r = xr * c - xi * s; val y4i = xr * s + xi * c

            val a1r = y1r + y4r; val a1i = y1i + y4i
            val b1r = y1r - y4r; val b1i = y1i - y4i
            val a2r = y2r + y3r; val a2i = y2i + y3i
            val b2r = y2r - y3r; val b2i = y2i - y3i

            // q=1/4: y0 + C1·a1 + C2·a2 ± i·sign·(S1·b1 + S2·b2)
            val p1r = y0r + C1 * a1r + C2 * a2r
            val p1i = y0i + C1 * a1i + C2 * a2i
            val t1r = sign * (S1 * b1r + S2 * b2r)
            val t1i = sign * (S1 * b1i + S2 * b2i)
            // q=2/3: y0 + C2·a1 + C1·a2 ± i·sign·(S2·b1 − S1·b2)
            val p2r = y0r + C2 * a1r + C1 * a2r
            val p2i = y0i + C2 * a1i + C1 * a2i
            val t2r = sign * (S2 * b1r - S1 * b2r)
            val t2i = sign * (S2 * b1i - S1 * b2i)

            re[k] = y0r + a1r + a2r
            im[k] = y0i + a1i + a2i
            re[mm + k] = p1r - t1i
            im[mm + k] = p1i + t1r
            re[4 * mm + k] = p1r + t1i
            im[4 * mm + k] = p1i - t1r
            re[2 * mm + k] = p2r - t2i
            im[2 * mm + k] = p2i + t2r
            re[3 * mm + k] = p2r + t2i
            im[3 * mm + k] = p2i - t2r
        }
    }

    /** FFT radix-2 in-place sul blocco [offset, offset+m) con tabelle precomputate. */
    private fun radix2(re: FloatArray, im: FloatArray, offset: Int, inverse: Boolean) {
        val mm = m
        if (mm == 1) return
        val rev = bitReverse
        for (i in 0 until mm) {
            val j = rev[i]
            if (i < j) {
                val a = offset + i
                val b = offset + j
                val tr = re[a]; re[a] = re[b]; re[b] = tr
                val ti = im[a]; im[a] = im[b]; im[b] = ti
            }
        }
        val sign = if (inverse) 1f else -1f
        val ct = cosTable
        val st = sinTable
        var size = 2
        while (size <= mm) {
            val half = size shr 1
            val step = mm / size
            var start = offset
            val end = offset + mm
            while (start < end) {
                var tw = 0
                for (j in 0 until half) {
                    val wr = ct[tw]
                    val wi = sign * st[tw]
                    val a = start + j
                    val b = a + half
                    val br = re[b]; val bi = im[b]
                    val vr = br * wr - bi * wi
                    val vi = br * wi + bi * wr
                    val ur = re[a]; val ui = im[a]
                    re[a] = ur + vr; im[a] = ui + vi
                    re[b] = ur - vr; im[b] = ui - vi
                    tw += step
                }
                start += size
            }
            size = size shl 1
        }
    }
}
