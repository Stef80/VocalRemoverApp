package com.example.vocalremover.backend

import android.os.Build
import com.example.vocalremover.ExecutionBackend

/**
 * Execution backends worth trying on a device, best first. CPU is always last: it is the
 * quality baseline and works everywhere. QNN is only listed on Qualcomm SoCs.
 */
object BackendCandidates {

    private const val QUALCOMM_SOC_MANUFACTURER = "QTI"
    private const val QUALCOMM_HARDWARE_PREFIX = "qcom"

    fun forSoc(socManufacturer: String, hardware: String): List<ExecutionBackend> {
        val qualcomm = socManufacturer.equals(QUALCOMM_SOC_MANUFACTURER, ignoreCase = true) ||
            hardware.startsWith(QUALCOMM_HARDWARE_PREFIX)
        return if (qualcomm) {
            listOf(ExecutionBackend.QNN, ExecutionBackend.WEBGPU, ExecutionBackend.CPU)
        } else {
            listOf(ExecutionBackend.WEBGPU, ExecutionBackend.CPU)
        }
    }

    fun forThisDevice(): List<ExecutionBackend> {
        // Build.SOC_MANUFACTURER exists only from API 31; older devices rely on Build.HARDWARE.
        val socManufacturer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MANUFACTURER else ""
        return forSoc(socManufacturer, Build.HARDWARE)
    }
}
