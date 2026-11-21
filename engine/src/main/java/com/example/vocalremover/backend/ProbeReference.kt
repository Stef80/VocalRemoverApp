package com.example.vocalremover.backend

import com.example.vocalremover.StereoPcm
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * CPU output of the probe chunk (the ISTFT of one `session.run` on [ProbeSignal.generate], see
 * [OrtBackendProber.runProbeChunk]): [ProbeSignal.FRAMES] frames, stored as little-endian fp16 (all L samples, then all R).
 * fp16 halves the asset size; its precision (~1e-3) is far below the SNR threshold.
 */
object ProbeReference {

    const val ASSET = "backend_probe/expected_fp16.bin"

    fun encode(pcm: StereoPcm): ByteArray {
        val buffer = ByteBuffer.allocate(pcm.size * 2 * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (v in pcm.left) buffer.putShort(floatToHalf(v))
        for (v in pcm.right) buffer.putShort(floatToHalf(v))
        return buffer.array()
    }

    fun decode(bytes: ByteArray): StereoPcm {
        require(bytes.size % 4 == 0) { "Reference size ${bytes.size} is not a multiple of 4" }
        val frames = bytes.size / 4
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val left = FloatArray(frames) { halfToFloat(buffer.getShort()) }
        val right = FloatArray(frames) { halfToFloat(buffer.getShort()) }
        return StereoPcm(left, right)
    }

    /** IEEE 754 binary32 to binary16, round to nearest even. */
    internal fun floatToHalf(value: Float): Short {
        val bits = java.lang.Float.floatToRawIntBits(value)
        val sign = (bits ushr 16) and 0x8000
        val exp = (bits ushr 23) and 0xFF
        val mant = bits and 0x7FFFFF
        if (exp == 0xFF) return (sign or 0x7C00 or if (mant != 0) 0x200 else 0).toShort()
        val e = exp - 127 + 15
        if (e >= 0x1F) return (sign or 0x7C00).toShort() // overflow to infinity
        if (e <= 0) {
            if (e < -10) return sign.toShort() // underflow to zero
            val m = mant or 0x800000
            val shift = 14 - e
            var half = m ushr shift
            val rem = m and ((1 shl shift) - 1)
            val halfway = 1 shl (shift - 1)
            if (rem > halfway || (rem == halfway && (half and 1) == 1)) half++
            return (sign or half).toShort()
        }
        var half = sign or (e shl 10) or (mant ushr 13)
        val rem = mant and 0x1FFF
        if (rem > 0x1000 || (rem == 0x1000 && (half and 1) == 1)) half++ // carry may roll into the exponent
        return half.toShort()
    }

    internal fun halfToFloat(half: Short): Float {
        val h = half.toInt() and 0xFFFF
        val sign = (h and 0x8000) shl 16
        val exp = (h ushr 10) and 0x1F
        val mant = h and 0x3FF
        val bits = when {
            exp == 0 -> {
                if (mant == 0) {
                    sign
                } else {
                    // Subnormal half: normalize.
                    var m = mant
                    var e = 127 - 15 + 1
                    while (m and 0x400 == 0) { m = m shl 1; e-- }
                    sign or (e shl 23) or ((m and 0x3FF) shl 13)
                }
            }
            exp == 0x1F -> sign or 0x7F800000 or (mant shl 13)
            else -> sign or ((exp - 15 + 127) shl 23) or (mant shl 13)
        }
        return java.lang.Float.intBitsToFloat(bits)
    }
}
