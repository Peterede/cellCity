package com.edens.cellcity.game

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import kotlin.math.abs

/**
 * Ticks the saved city forward while the game is in the background and posts
 * the handful of alerts worth interrupting the player for. The game cancels
 * the alarm whenever it is in the foreground.
 */
class CityAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences(CityStore.PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(CityStore.ACTIVE, false)) return
        val engine = CityEngine.load(prefs.getString(CityStore.SAVE, null)) ?: return
        val now = System.currentTimeMillis()
        val days = CityClock.daysAway(prefs.getLong(CityStore.LAST_SEEN, 0L), now)
        if (days <= 0) return

        repeat(days) { engine.tick() }
        val events = engine.drainEvents()
        prefs.edit()
            .putString(CityStore.SAVE, engine.save())
            .putLong(CityStore.LAST_SEEN, now)
            .apply()
        report(context, events)
    }

    private fun report(context: Context, events: List<CityEvent>) {
        var fires = 0
        var month: CityEvent.MonthFinished? = null
        var completed: CityEvent.ContractComplete? = null
        var expired: CityEvent.ContractExpired? = null
        var bankrupt = false
        for (event in events) {
            when (event) {
                is CityEvent.FireStarted -> fires++
                is CityEvent.MonthFinished -> month = event
                is CityEvent.ContractComplete -> completed = event
                is CityEvent.ContractExpired -> expired = event
                is CityEvent.Bankrupt -> bankrupt = true
                else -> Unit
            }
        }

        if (fires > 0) {
            CityNotifier.notify(
                context,
                CityNotifier.FIRE_ID,
                "Fire in Cell City",
                "$fires building${if (fires == 1) "" else "s"} caught fire while you were " +
                    "away. Open the city before the crews give up."
            )
        }
        if (month != null) {
            val sign = if (month.net >= 0) "+" else "-"
            CityNotifier.notify(
                context,
                CityNotifier.MONEY_ID,
                "${CityClock.monthName(month.month)} ${month.year} report",
                "The month closed at $sign$${abs(month.net)} with " +
                    "${month.population} residents."
            )
        }
        if (completed != null) {
            CityNotifier.notify(
                context,
                CityNotifier.CONTRACT_ID,
                "Contract complete",
                "${completed.contract.kind.title} is done: " +
                    "+$${completed.contract.reward} for the treasury."
            )
        } else if (expired != null) {
            CityNotifier.notify(
                context,
                CityNotifier.CONTRACT_ID,
                "Contract lapsed",
                "${expired.contract.kind.title} ran out of time. A new one is on its way."
            )
        }
        if (bankrupt) {
            CityNotifier.notify(
                context,
                CityNotifier.CONTRACT_ID,
                "The city is bankrupt",
                "The treasury ran dry while you were away. Open the city to take an " +
                    "emergency loan or start over."
            )
        }
    }
}

/** Schedules the hourly background tick while the game is in the background. */
object CityAlarms {
    private const val INTERVAL_MS = 60L * 60L * 1000L
    private const val REQUEST_CODE = 10

    fun schedule(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        alarms.setInexactRepeating(
            AlarmManager.ELAPSED_REALTIME,
            SystemClock.elapsedRealtime() + INTERVAL_MS,
            INTERVAL_MS,
            pending(context)
        )
    }

    fun cancel(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        alarms.cancel(pending(context))
    }

    private fun pending(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, CityAlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}
