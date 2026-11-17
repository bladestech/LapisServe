package com.lapisserve.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.lapisserve.app.LapisApp
import com.lapisserve.app.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

/** Keeps the process alive (and visible to the user) while any server is running. */
class ServerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel(this)
        val mgr = LapisApp.get(this).manager
        if (intent?.action == ACTION_STOP_ALL) mgr.stopAll()
        startForeground(NOTIF_ID, build(this, mgr.activeSummary().second.ifEmpty { "Starting…" }))
        watcher?.cancel()
        watcher = scope.launch {
            mgr.states.collectLatest {
                val (n, text) = mgr.activeSummary()
                if (n == 0) {
                    delay(1500)
                    if (!mgr.anyActive()) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
                } else {
                    getSystemService(NotificationManager::class.java).notify(NOTIF_ID, build(this@ServerService, text, n))
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    companion object {
        private const val CHANNEL = "servers"
        private const val NOTIF_ID = 1
        const val ACTION_STOP_ALL = "com.lapisserve.app.STOP_ALL"

        fun start(ctx: Context) = ContextCompat.startForegroundService(ctx, Intent(ctx, ServerService::class.java))
        fun refresh() {}

        private fun ensureChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT >= 26) {
                val nm = ctx.getSystemService(NotificationManager::class.java)
                if (nm.getNotificationChannel(CHANNEL) == null)
                    nm.createNotificationChannel(NotificationChannel(CHANNEL, "Running servers", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "Shown while a Minecraft server is running"; setShowBadge(false)
                    })
            }
        }

        private fun build(ctx: Context, text: String, count: Int = 1): Notification {
            val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val stop = PendingIntent.getService(ctx, 1, Intent(ctx, ServerService::class.java).setAction(ACTION_STOP_ALL), PendingIntent.FLAG_IMMUTABLE)
            return NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(if (count == 1) "LapisServe is hosting a server" else "LapisServe is hosting $count servers")
                .setContentText(text).setOngoing(true).setOnlyAlertOnce(true)
                .setContentIntent(open)
                .addAction(0, "Stop all", stop)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build()
        }
    }
}
