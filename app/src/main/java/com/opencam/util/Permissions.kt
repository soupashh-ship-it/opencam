package com.opencam.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object Permissions {
    /** Camera and microphone are required for the current full-streaming mode. */
    fun essentialPermissions(): Array<String> = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
    )

    /** Notification permission is requested when applicable, but denial must not block streaming. */
    fun requestPermissions(): Array<String> = buildList {
        addAll(essentialPermissions())
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    fun missingEssential(context: Context): Array<String> = essentialPermissions().filter {
        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
    }.toTypedArray()

    fun allGranted(context: Context): Boolean = missingEssential(context).isEmpty()
}
