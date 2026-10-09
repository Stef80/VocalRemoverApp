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
}
