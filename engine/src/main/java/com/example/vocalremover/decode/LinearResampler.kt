package com.example.vocalremover.decode

import com.example.vocalremover.StereoPcm

/** Ricampionamento lineare stereo, sufficiente per portare i file a 44,1 kHz prima del modello. */
internal object LinearResampler {

    /** Restituisce la stessa istanza se le frequenze coincidono. */
    fun resample(pcm: StereoPcm, fromRate: Int, toRate: Int): StereoPcm {
        if (fromRate == toRate) return pcm

        val ratio = fromRate.toDouble() / toRate
        val outLen = (pcm.size.toLong() * toRate / fromRate).toInt()
        val srcL = pcm.left
        val srcR = pcm.right
        val outL = FloatArray(outLen)
        val outR = FloatArray(outLen)
        val lastIdx = pcm.size - 1
        for (i in 0 until outLen) {
            val pos  = i * ratio
            val lo   = pos.toInt().coerceAtMost(lastIdx)
            val hi   = if (lo < lastIdx) lo + 1 else lo
            val frac = (pos - lo).toFloat()
            val inv  = 1f - frac
            outL[i] = srcL[lo] * inv + srcL[hi] * frac
            outR[i] = srcR[lo] * inv + srcR[hi] * frac
        }
        return StereoPcm(outL, outR)
    }
}
