package com.opencam

import android.Manifest
import com.opencam.util.Permissions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionsTest {
    @Test
    fun testEssentialPermissionsOnlyCamera() {
        val essential = Permissions.essentialPermissions()
        assertEquals(1, essential.size)
        assertEquals(Manifest.permission.CAMERA, essential[0])
    }

    @Test
    fun testAudioPermissionsOptional() {
        val audio = Permissions.audioPermissions()
        assertEquals(1, audio.size)
        assertEquals(Manifest.permission.RECORD_AUDIO, audio[0])
        assertFalse(Permissions.essentialPermissions().contains(Manifest.permission.RECORD_AUDIO))
    }

    @Test
    fun testRequestPermissionsIncludesCameraAndAudio() {
        val requested = Permissions.requestPermissions()
        assertTrue(requested.contains(Manifest.permission.CAMERA))
        assertTrue(requested.contains(Manifest.permission.RECORD_AUDIO))
    }
}
