package com.lapisserve.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.lapisserve.app.MainActivity

/** Heads-up notifications for things an owner shouldn't miss while the app is in the background. */
object Alerts {
    private const val CHANNEL = "alerts"

    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Server alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Tells you when a server stops unexpectedly"
            })
    }

    fun crashed(ctx: Context, serverName: String, detail: String) = runCatching {
        ensureChannel(ctx)
        val open = PendingIntent.getActivity(ctx, 2, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("$serverName stopped unexpectedly").setContentText(detail)
            .setContentIntent(open).setAutoCancel(true).setCategory(NotificationCompat.CATEGORY_ERROR)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(("crash-$serverName").hashCode(), n)
    }
}
