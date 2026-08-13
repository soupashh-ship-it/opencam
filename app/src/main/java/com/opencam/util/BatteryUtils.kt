package com.opencam.util

import android.content.Context
import android.os.BatteryManager

object BatteryUtils {

    /** Battery level in percent (0-100), served via the plugin's `GET /battery`. */
    fun batteryPercent(context: Context): Int {
        return try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (level in 0..100) level else 100
        } catch (_: Exception) {
            100
        }
    }
}
