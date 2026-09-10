package com.mblivestudio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * Foreground Service that keeps M.B. Live Studio's stream alive when the
 * phone screen locks or the app goes to the background.
 *
 * IMPORTANT: this service does NOT own the camera or RtmpCamera2 instance —
 * that stays in MainActivity exactly as before. RootEncoder's StreamBase
 * classes (RtmpCamera2 included) already keep the camera+encoder pipeline
 * running independently of the on-screen preview Surface once streaming has
 * started (stopPreview() is a no-op while isStreaming == true). What was
 * actually killing the background stream before was:
 *
 *   1. MainActivity.surfaceDestroyed() explicitly calling stopStream() when
 *      the screen locked / surface was destroyed (fixed separately in
 *      MainActivity.kt — this service does not fix that part).
 *   2. Android's background camera/mic access restriction, plus aggressive
 *      OEM battery managers (common on Indian devices) killing the app
 *      process once it's no longer a foreground service.
 *
 * This service exists purely to solve #2: while it's running, it shows a
 * permanent notification and holds a partial wake lock, which tells Android
 * (and the OEM battery manager) "this is an active, user-visible task — do
 * not throttle or kill it."
 *
 * Usage from MainActivity:
 *   StreamingService.start(this)   // call right when rtmpCamera.startStream() is called
 *   StreamingService.stop(this)    // call when the stream truly ends (user stop, or final failure)
 */
class StreamingService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        private const val CHANNEL_ID = "mb_live_stream_channel"
        private const val NOTIFICATION_ID = 4321

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

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        acquireWakeLock()
        return START_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "MBLiveStudio:StreamingWakeLock"
        ).apply {
            setReferenceCounted(false)
            // Safety cap so a forgotten stream can't hold the CPU awake forever.
            acquire(12 * 60 * 60 * 1000L) // 12 hours
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Live Streaming",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shown while M.B. Live Studio is streaming"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("M.B. Live Studio")
                .setContentText("Live streaming in progress")
                .setSmallIcon(R.drawable.app_logo)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("M.B. Live Studio")
                .setContentText("Live streaming in progress")
                .setSmallIcon(R.drawable.app_logo)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_LOW)
                .build()
        }
    }
}
