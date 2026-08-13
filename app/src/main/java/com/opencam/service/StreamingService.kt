package com.opencam.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import com.opencam.MainActivity
import com.opencam.OpenCamApplication
import com.opencam.R
import com.opencam.stream.StreamManager
import com.opencam.stream.StreamManagerHolder

/** Keeps the camera, encoders and TCP server alive while the app is backgrounded. */
class StreamingService : Service() {
    private lateinit var streamManager: StreamManager
    private var wakeLock: PowerManager.WakeLock? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val notificationUpdater = object : Runnable {
        override fun run() {
            updateNotification()
            mainHandler.postDelayed(this, NOTIFICATION_REFRESH_MS)
        }
    }

    private val wakeLockRenewer = object : Runnable {
        override fun run() {
            renewWakeLock()
            mainHandler.postDelayed(this, WAKE_LOCK_RENEW_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        streamManager = (application as? OpenCamApplication)?.streamManager
            ?: StreamManagerHolder.instance
            ?: StreamManager(applicationContext).also { StreamManagerHolder.instance = it }
        createChannel()
        startInForeground()
        renewWakeLock()
        streamManager.start()
        mainHandler.post(notificationUpdater)
        mainHandler.postDelayed(wakeLockRenewer, WAKE_LOCK_RENEW_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground()
        streamManager.start()
        return START_STICKY
    }

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun buildNotification(text: String): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, StreamingService::class.java).setAction(ACTION_STOP),
            flags,
        )
        val openIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            flags,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.notification_stop),
                stopIntent,
            )
            .build()
    }

    private fun notificationText(): String {
        val state = streamManager.state.value
        if (!state.running) return "Connecting…"
        val ip = state.ipAddress ?: "…"
        return "$ip:${state.port}  •  ${state.videoClients} viewer(s)  •  ${state.battery}%"
    }

    private fun startInForeground() {
        val notification = buildNotification(notificationText())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification() {
        try {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, buildNotification(notificationText()))
        } catch (_: Exception) {
            // Notification permission may be denied on Android 13+; streaming remains functional.
        }
    }

    private fun renewWakeLock() {
        try {
            val existing = wakeLock
            if (existing?.isHeld == true) existing.release()
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = (existing ?: powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "opencam:streaming",
            ).apply { setReferenceCounted(false) }).also {
                it.acquire(WAKE_LOCK_TIMEOUT_MS)
            }
        } catch (_: Exception) {
            // The app remains usable in the foreground even when a vendor rejects the lock.
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(notificationUpdater)
        mainHandler.removeCallbacks(wakeLockRenewer)
        streamManager.stop()
        try { wakeLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "opencam_streaming"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.opencam.action.STOP"
        private const val NOTIFICATION_REFRESH_MS = 5_000L
        private const val WAKE_LOCK_TIMEOUT_MS = 30 * 60 * 1_000L
        private const val WAKE_LOCK_RENEW_MS = 20 * 60 * 1_000L

        fun start(context: Context) {
            val intent = Intent(context, StreamingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, StreamingService::class.java))
        }
    }
}
