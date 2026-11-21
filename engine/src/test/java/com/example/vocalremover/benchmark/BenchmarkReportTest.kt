package com.example.vocalremover.benchmark

import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkReportTest {

    @Test
    fun `report json contains device backend timings and quality`() {
        val json = BenchmarkReport(
            manufacturer = "OPPO", model = "CPH2791", soc = "MT6993", sdk = 36,
            backend = "qnn", audioSeconds = 20.0,
            sessionLoadMs = 265, processMs = 8000, referenceProcessMs = 120000,
            snrDb = 42.5, outputRms = 0.1, passed = true, failure = null
        ).toJson()
        assertTrue(json, json.contains("\"model\":\"CPH2791\""))
        assertTrue(json, json.contains("\"backend\":\"qnn\""))
        assertTrue(json, json.contains("\"processMs\":8000"))
        assertTrue(json, json.contains("\"realtimeFactor\":0.4"))
        assertTrue(json, json.contains("\"snrDb\":42.5"))
        assertTrue(json, json.contains("\"passed\":true"))
        assertTrue(json, json.contains("\"failure\":null"))
    }

    @Test
    fun `report json escapes strings and encodes infinite snr as null`() {
        val json = BenchmarkReport(
            manufacturer = "A\"B", model = "x", soc = "y", sdk = 30,
            backend = "cpu", audioSeconds = 10.0,
            sessionLoadMs = 1, processMs = 1, referenceProcessMs = null,
            snrDb = Double.POSITIVE_INFINITY, outputRms = 0.1, passed = false, failure = "bad\nline"
        ).toJson()
        assertTrue(json, json.contains("\"manufacturer\":\"A\\\"B\""))
        assertTrue(json, json.contains("\"snrDb\":null"))
        assertTrue(json, json.contains("\"referenceProcessMs\":null"))
        assertTrue(json, json.contains("\"failure\":\"bad\\nline\""))
    }
}
