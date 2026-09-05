package com.arise.assistant.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.arise.assistant.MainActivity
import com.arise.assistant.R
import com.arise.assistant.engine.AriseEngine
import com.arise.assistant.log.LocalLog
import com.arise.assistant.settings.Settings

/**
 * Hands-free wake-word host. While this service runs, a persistent notification
 * shows that the microphone is in use and the low-power wake gate is active.
 */
class AriseForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            AriseAppInstance.engine().stopEverything()
            stopSelf()
            return START_NOT_STICKY
        }
        val engine = AriseAppInstance.engine()
        val settings = Settings(this)
        val wake = settings.wakeWord
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, AriseForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle(getString(R.string.fgs_notification_title, wake))
            .setContentText(getString(R.string.fgs_notification_text))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, getString(R.string.stop_listening), stopIntent)
            .addAction(0, getString(R.string.settings_open),
                PendingIntent.getActivity(this, 2,
                    Intent(this, com.arise.assistant.SettingsActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            @Suppress("DEPRECATION") startForeground(ID, notification)
        }
        engine.startWakeListener()
        LocalLog.i("Service", "foreground wake listening active")
        return START_STICKY
    }

    override fun onDestroy() {
        AriseAppInstance.engine().stopWakeListener()
        super.onDestroy()
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(
            CHANNEL_ID, getString(R.string.fgs_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = getString(R.string.fgs_channel_desc) }
        nm.createNotificationChannel(ch)
    }

    companion object {
        private const val ID = 41
        private const val CHANNEL_ID = "arise_wake"
        private const val ACTION_STOP = "com.arise.assistant.STOP"

        /** Start only when the OS will actually allow it (mic permission + 13+ notification perm). */
        fun startIfPermitted(context: Context, engine: AriseEngine) {
            if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                engine.stopWakeListener()
                return
            }
            if (Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                // still allowed to run without showing in the shade on some OEMs; try anyway
            }
            try {
                val i = Intent(context, AriseForegroundService::class.java)
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
            } catch (e: Exception) {
                LocalLog.e("Service", "start failed: ${e.message}")
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AriseForegroundService::class.java))
        }
    }
}

/** Keeps engine access decoupled for the service/UI. */
object AriseAppInstance {
    fun engine(): AriseEngine = com.arise.assistant.AriseApp.instance.engine
}
