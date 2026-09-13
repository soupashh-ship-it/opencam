package com.opencam.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object Permissions {
    /** Camera is essential for video streaming. Microphone is optional. */
    fun essentialPermissions(): Array<String> = arrayOf(
        Manifest.permission.CAMERA,
    )

    fun audioPermissions(): Array<String> = arrayOf(
        Manifest.permission.RECORD_AUDIO,
    )

    /** All permissions to request upfront (camera, optional audio, optional notifications). */
    fun requestPermissions(): Array<String> = buildList {
        addAll(essentialPermissions())
        addAll(audioPermissions())
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    fun missingEssential(context: Context): Array<String> = essentialPermissions().filter {
        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
    }.toTypedArray()

    fun allGranted(context: Context): Boolean = missingEssential(context).isEmpty()

    fun hasAudio(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
}
