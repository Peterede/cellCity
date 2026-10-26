package com.edens.cellcity.game

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.edens.cellcity.MainActivity
import com.edens.cellcity.R

/** The app's alert channel and the handful of notifications it posts. */
object CityNotifier {
    const val CHANNEL_ID = "city_alerts"

    const val FIRE_ID = 1001
    const val MONEY_ID = 1002
    const val CONTRACT_ID = 1003

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "City alerts",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Fires, contract news and monthly reports from Cell City"
        }
        manager.createNotificationChannel(channel)
    }

    fun canNotify(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun notify(context: Context, id: Int, title: String, text: String) {
        if (!canNotify(context)) return
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
        builder
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.notify(id, builder.build())
    }

    /** Removes stale alerts once the player is back in the game. */
    fun clear(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.cancel(FIRE_ID)
        manager.cancel(MONEY_ID)
        manager.cancel(CONTRACT_ID)
    }
}
