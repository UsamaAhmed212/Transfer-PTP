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
        private const val CHANNEL_ID = "TransferPTP_Server_Channel"
        private const val NOTIFICATION_ID = 1001
        private const val INACTIVITY_TIMEOUT_MS = 15000L // 15 seconds timeout

        private const val ACTION_REQUEST_RECEIVED = "com.example.transferptp.ACTION_REQUEST_RECEIVED"
        private const val ACTION_STOP_SERVICE = "com.example.transferptp.ACTION_STOP_SERVICE"

        private var isForegroundActive = false

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
                Log.e("AppPriorityCheck", "Error notifying request to ServerService: ${e.message}")
            }
        }

        fun stopServerService(context: Context) {
            try {
                val intent = Intent(context, ServerService::class.java).apply {
                    action = ACTION_STOP_SERVICE
                }
                context.startService(intent)
            } catch (e: Exception) {
                Log.e("AppPriorityCheck", "Error stopping ServerService: ${e.message}")
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val stopRunnable = Runnable {
        Log.d("AppPriorityCheck", "💤 [15s TIMEOUT] No requests received for 15s -> Foreground Service stopped. Priority returned to normal background state.")
        stopForegroundState()
        checkAppPriorityState(applicationContext)
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_REQUEST_RECEIVED -> {
                handleIncomingRequest()
            }
            ACTION_STOP_SERVICE -> {
                stopForegroundState()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun handleIncomingRequest() {
        // Reset 15-second inactivity timer
        handler.removeCallbacks(stopRunnable)

        if (!isForegroundActive) {
            isForegroundActive = true
            val notification = createNotification("⚡ High Priority Active (Streaming/Previewing)")
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
                Log.d("AppPriorityCheck", "⚡ [HIGH PRIORITY ACTIVATED] Request received! Foreground Service started. High priority active for 15s.")
                checkAppPriorityState(applicationContext)
            } catch (e: Exception) {
                Log.e("AppPriorityCheck", "Failed to start Foreground Service: ${e.message}", e)
            }
        } else {
            Log.d("AppPriorityCheck", "🔄 [HIGH PRIORITY EXTENDED] Request received -> 15s timer reset.")
        }

        // Schedule timeout stop after 15 seconds
        handler.postDelayed(stopRunnable, INACTIVITY_TIMEOUT_MS)
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
                description = "Keeps video/image streaming fast during requests"
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
        super.onDestroy()
    }
}
