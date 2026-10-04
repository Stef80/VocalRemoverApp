package com.example.vocalremover.benchmark

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Brano sintetico deterministico (nessun file audio protetto da copyright nel repo):
 * accordi e basso con panning diverso su L/R, percussioni a rumore e una "voce"
 * armonica con vibrato al centro, come in un mix reale.
 */
object SyntheticStereoSignal {

    private const val TARGET_PEAK = 0.8f

    fun generate(seconds: Double, sampleRate: Int, seed: Int): Pair<FloatArray, FloatArray> {
        val n = (seconds * sampleRate).toInt()
        val rnd = Random(seed)
        val left = FloatArray(n)
        val right = FloatArray(n)
        val sr = sampleRate.toDouble()

        val chordRoots = DoubleArray(4) { 110.0 * Math.pow(2.0, rnd.nextInt(0, 12) / 12.0) }
        val beat = 0.5 // secondi
        val noteLen = (sr * 0.25).toInt()
        val vocalPhaseOffset = rnd.nextDouble() * 2 * PI
        var vocalPhase = 0.0

        for (i in 0 until n) {
            val t = i / sr
            val bar = ((t / (4 * beat)).toInt()) % chordRoots.size
            val root = chordRoots[bar]

            // Accordo (fondamentale, terza, quinta) a sinistra, ottava a destra
            var l = 0.0
            var r = 0.0
            for ((k, ratio) in doubleArrayOf(1.0, 1.26, 1.5).withIndex()) {
                val tone = sin(2 * PI * root * 2 * ratio * t) * 0.08
                l += tone * (1.0 - 0.3 * k)
                r += tone * (0.4 + 0.3 * k)
            }
            val bass = sin(2 * PI * root / 2 * t) * 0.15
            l += bass
            r += bass

            // Percussione: burst di rumore a ogni battito, decadimento esponenziale
            val sinceBeat = t % beat
            val drum = (rnd.nextDouble() * 2 - 1) * exp(-sinceBeat * 30) * 0.25
            l += drum * 0.7
            r += drum

            // Voce al centro: armoniche con vibrato, sillabe on/off
            val f0 = root * 2 * (1 + 0.01 * sin(2 * PI * 5.5 * t))
            vocalPhase += 2 * PI * f0 / sr
            val syllable = if ((i / noteLen) % 3 != 2) 1.0 else 0.0
            var voice = 0.0
            for (h in 1..8) voice += sin(h * vocalPhase + vocalPhaseOffset) / h
            voice *= 0.12 * syllable
            l += voice
            r += voice

            left[i] = l.toFloat()
            right[i] = r.toFloat()
        }

        var peak = 0f
        for (i in 0 until n) peak = maxOf(peak, abs(left[i]), abs(right[i]))
        if (peak > 0f) {
            val scale = TARGET_PEAK / peak
            for (i in 0 until n) {
                left[i] *= scale
                right[i] *= scale
            }
        }
        return left to right
    }
}
