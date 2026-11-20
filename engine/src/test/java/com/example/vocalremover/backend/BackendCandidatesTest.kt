package com.example.vocalremover.backend

import com.example.vocalremover.ExecutionBackend.CPU
import com.example.vocalremover.ExecutionBackend.QNN
import com.example.vocalremover.ExecutionBackend.WEBGPU
import org.junit.Assert.assertEquals
import org.junit.Test

class BackendCandidatesTest {

    @Test fun `qualcomm by soc manufacturer`() =
        assertEquals(listOf(QNN, WEBGPU, CPU), BackendCandidates.forSoc("QTI", "mt6993"))

    @Test fun `qualcomm by legacy hardware name`() =
        assertEquals(listOf(QNN, WEBGPU, CPU), BackendCandidates.forSoc("", "qcom"))

    @Test fun `mediatek gets webgpu then cpu`() =
        assertEquals(listOf(WEBGPU, CPU), BackendCandidates.forSoc("Mediatek", "mt6993"))
}
