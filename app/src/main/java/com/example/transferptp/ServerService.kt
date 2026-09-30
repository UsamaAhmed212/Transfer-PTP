package com.example.transferptp

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat

class ServerService : Service() {

    companion object {
        private const val TAG = "ServerService"
        private const val CHANNEL_ID = "TransferPTP_Server_Channel"
        private const val NOTIFICATION_ID = 1001
        private const val INACTIVITY_TIMEOUT_MS = 15000L // 15 seconds timeout

        private const val ACTION_START_SERVICE = "com.example.transferptp.ACTION_START_SERVICE"
        private const val ACTION_REQUEST_RECEIVED = "com.example.transferptp.ACTION_REQUEST_RECEIVED"
        private const val ACTION_STOP_SERVICE = "com.example.transferptp.ACTION_STOP_SERVICE"

        private var isForegroundActive = false
        private var isHighPriority = false

        private fun getPriorityTag(): String = if (isHighPriority) "[Priority: HIGH]" else "[Priority: NORMAL]"

        fun startServerService(context: Context) {
            try {
                val intent = Intent(context, ServerService::class.java).apply {
                    action = ACTION_START_SERVICE
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting service: ${e.message}")
            }
        }

        fun notifyRequestReceived(context: Context) {
            try {
                val intent = Intent(context, ServerService::class.java).apply {
                    action = ACTION_REQUEST_RECEIVED
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error notifying request: ${e.message}")
            }
        }

        fun stopServerService(context: Context) {
            try {
                val intent = Intent(context, ServerService::class.java).apply {
                    action = ACTION_STOP_SERVICE
                }
                context.startService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping service: ${e.message}")
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val stopRunnable = Runnable {
        Log.d(TAG, "💤 15s idle timeout -> Reverting from [Priority: HIGH] to [Priority: NORMAL]")
        updateNotificationState(isHighPriorityState = false)
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.d(TAG, "${getPriorityTag()} ServerService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SERVICE -> {
                Log.d(TAG, "[Priority: NORMAL] Start server action received -> Running in Normal Priority mode")
                updateNotificationState(isHighPriorityState = false)
            }
            ACTION_REQUEST_RECEIVED -> {
                handleIncomingRequest()
            }
            ACTION_STOP_SERVICE -> {
                Log.d(TAG, "${getPriorityTag()} Stop server action received -> Stopping service")
                stopForegroundState()
                stopSelf()
            }
            else -> {
                updateNotificationState(isHighPriorityState = false)
            }
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d(TAG, "${getPriorityTag()} App removed from Recents -> Service remains running in background")
    }

    private fun handleIncomingRequest() {
        handler.removeCallbacks(stopRunnable)
        if (!isHighPriority) {
            Log.d(TAG, "⚡ Request received -> Switched from [Priority: NORMAL] to [Priority: HIGH] (15s timer active)")
        } else {
            Log.d(TAG, "🔄 Request received -> Extended [Priority: HIGH] (15s timer reset)")
        }
        updateNotificationState(isHighPriorityState = true)
        handler.postDelayed(stopRunnable, INACTIVITY_TIMEOUT_MS)
    }

    private fun updateNotificationState(isHighPriorityState: Boolean) {
        isHighPriority = isHighPriorityState
        val title = if (isHighPriorityState) "⚡ High Priority Active (Streaming/Previewing)" else "Transfer PTP Server Active"
        val notification = createNotification(title)

        try {
            if (!isForegroundActive) {
                isForegroundActive = true
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            } else {
                val manager = getSystemService(NOTIFICATION_SERVICE) as? NotificationManager
                manager?.notify(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update notification: ${e.message}", e)
        }
    }

    private fun stopForegroundState() {
        if (isForegroundActive) {
            isForegroundActive = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Transfer PTP Active Server",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Transfer PTP Server running in background"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(contentText: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Transfer PTP Server")
            .setContentText(contentText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacks(stopRunnable)
        Log.d(TAG, "${getPriorityTag()} ServerService destroyed")
        super.onDestroy()
    }
}
