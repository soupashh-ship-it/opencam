package com.opencam

import android.app.Application
import com.opencam.stream.StreamManager
import com.opencam.stream.StreamManagerHolder

/** Owns the process-wide streaming stack before activities or services are restored. */
class OpenCamApplication : Application() {
    val streamManager: StreamManager by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        StreamManager(applicationContext)
    }

    override fun onCreate() {
        super.onCreate()
        StreamManagerHolder.instance = streamManager
    }
}
