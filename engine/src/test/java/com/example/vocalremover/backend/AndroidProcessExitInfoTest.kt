package com.example.vocalremover.backend

import android.app.ApplicationExitInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidProcessExitInfoTest {

    @Test
    fun `only a native crash blames the backend`() {
        assertTrue(AndroidProcessExitInfo.isBackendCrash(ApplicationExitInfo.REASON_CRASH_NATIVE))
        assertFalse(AndroidProcessExitInfo.isBackendCrash(ApplicationExitInfo.REASON_CRASH))
        assertFalse(AndroidProcessExitInfo.isBackendCrash(ApplicationExitInfo.REASON_USER_REQUESTED))
        assertFalse(AndroidProcessExitInfo.isBackendCrash(null))
    }
}
