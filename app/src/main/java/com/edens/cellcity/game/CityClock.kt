package com.edens.cellcity.game

import kotlin.math.min

/**
 * Turns wall-clock time into in-game days. One hour away is one city day, and
 * a single absence never advances more than a week. Pure Kotlin so it can be
 * unit tested and shared with the background alarm.
 */
object CityClock {
    const val MAX_DAYS_AWAY = 7

    fun daysAway(lastSeenMillis: Long, nowMillis: Long): Int {
        if (lastSeenMillis <= 0L || nowMillis <= lastSeenMillis) return 0
        val hours = (nowMillis - lastSeenMillis) / 3_600_000L
        return min(MAX_DAYS_AWAY, hours.toInt())
    }

    private val MONTHS = arrayOf(
        "Jan", "Feb", "Mar", "Apr", "May", "Jun",
        "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
    )

    fun monthName(month: Int): String = MONTHS[(month - 1).coerceIn(0, 11)]
}
