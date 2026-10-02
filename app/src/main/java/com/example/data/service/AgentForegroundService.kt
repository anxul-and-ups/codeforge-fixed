package com.example.data.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.MainActivity

/**
 * Keeps the process alive while the agent works (also when the app is in the background).
 * The actual agent job runs in the application scope, this service only provides the foreground state.
 */
class AgentForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "codeforge_agent_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "ACTION_START"
        const val EXTRA_STATUS = "EXTRA_STATUS"

        @Volatile
        private var running = false

        fun startService(context: Context, status: String = "Agent is working…") {
            try {
                val intent = Intent(context, AgentForegroundService::class.java).apply {
                    action = ACTION_START
                    putExtra(EXTRA_STATUS, status)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                // Starting a foreground service can be refused in some background states; the agent still runs.
            }
        }

        /** Only updates the existing notification, never (re)starts the service from the background. */
        fun updateStatus(context: Context, status: String) {
            if (!running) return
            try {
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(NOTIFICATION_ID, buildNotification(context, status))
            } catch (e: Exception) {
                // ignore
            }
        }

        fun stopService(context: Context) {
            try {
                context.stopService(Intent(context, AgentForegroundService::class.java))
            } catch (e: Exception) {
                // ignore
            }
        }

        private fun buildNotification(context: Context, status: String): Notification {
            val launchIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle("CodeForge Agent Active")
                .setContentText(status.take(120))
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val status = intent?.getStringExtra(EXTRA_STATUS) ?: "Processing repository…"
        val notification = buildNotification(this, status)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            running = true
        } catch (e: Exception) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    // Android 15+: dataSync foreground services have a time limit. The agent keeps running regardless.
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "CodeForge Agent Execution",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows live status while the AI coding agent is reading or modifying files"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
}
