package com.example.vocalremover

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureDropdownPolicyTest {

    @Test
    fun blocksDropdownWhenWindowTokenIsMissing() {
        val canShow = canShowCaptureDropdown(
            isAttachedToWindow = true,
            hasWindowToken = false,
            isActivityFinishing = false,
            isActivityDestroyed = false
        )

        assertFalse(canShow)
    }

    @Test
    fun allowsDropdownOnlyWhenViewAndActivityAreValid() {
        val canShow = canShowCaptureDropdown(
            isAttachedToWindow = true,
            hasWindowToken = true,
            isActivityFinishing = false,
            isActivityDestroyed = false
        )

        assertTrue(canShow)
    }
}
