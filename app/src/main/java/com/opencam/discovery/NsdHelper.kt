package com.opencam.discovery

import android.content.Context
import com.opencam.BuildConfig
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings

/**
 * Advertises the `_droidcamobs._tcp` service so the droidcam-obs-plugin can
 * auto-discover this phone (mDNS PTR/SRV/TXT records, exactly what the
 * plugin's `MDNS::DoReload` queries for).
 */
class NsdHelper(private val context: Context) {

    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var registered = false

    private val instanceName: String by lazy {
        val suffix = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                ?.takeLast(4)?.uppercase() ?: "0000"
        } catch (_: Exception) {
            "0000"
        }
        "OpenCam-$suffix"
    }

    fun start(port: Int) {
        stop()
        val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        nsdManager = nsd

        // Ensure the app can answer mDNS queries even with the screen off.
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("opencam-mdns").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {
        }

        val info = NsdServiceInfo().apply {
            serviceName = instanceName
            serviceType = "_droidcamobs._tcp"
            this.port = port
            try {
                setAttribute("name", "OpenCam ${Build.MODEL} (WiFi)")
                setAttribute("model", Build.MODEL)
                setAttribute("version", BuildConfig.VERSION_NAME)
            } catch (_: Exception) {
            }
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) { registered = true }
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) { registered = false }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) { registered = false }
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) { registered = false }
        }
        registrationListener = listener

        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (_: Exception) {
            registered = false
        }
    }

    fun stop() {
        // Attempt unregistration even if the registration callback hasn't fired
        // yet (IllegalArgumentException is caught for the not-registered case).
        try {
            registrationListener?.let { nsdManager?.unregisterService(it) }
        } catch (_: Exception) {
        }
        registered = false
        nsdManager = null
        registrationListener = null
        try {
            multicastLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
        multicastLock = null
    }
}
