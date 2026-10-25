package com.stef.vocalremover.ui.library

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibraryPermissionTest {

    @Test
    fun `Android 13 and later use READ_MEDIA_AUDIO`() =
        assertEquals(Manifest.permission.READ_MEDIA_AUDIO, libraryReadPermission(33))

    @Test
    fun `Android 10 to 12 use READ_EXTERNAL_STORAGE`() {
        assertEquals(Manifest.permission.READ_EXTERNAL_STORAGE, libraryReadPermission(29))
        assertEquals(Manifest.permission.READ_EXTERNAL_STORAGE, libraryReadPermission(32))
    }

    @Test
    fun `below Android 10 nothing is requested`() = assertNull(libraryReadPermission(28))

    @Test
    fun `grant button requests the permission if it was never asked`() =
        assertEquals(PermissionAction.Request, permissionAction(alreadyAsked = false, showRationale = false))

    @Test
    fun `grant button requests again when the system can still show the dialog`() =
        assertEquals(PermissionAction.Request, permissionAction(alreadyAsked = true, showRationale = true))

    @Test
    fun `grant button opens settings after a permanent denial`() =
        assertEquals(PermissionAction.OpenSettings, permissionAction(alreadyAsked = true, showRationale = false))
}
