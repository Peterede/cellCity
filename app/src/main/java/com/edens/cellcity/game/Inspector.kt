package com.edens.cellcity.game

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Whether a requirement is met, missing, or just nice to have. */
enum class NeedState { MET, MISSING, OPTIONAL }

/** One thing a cell wants before it can do its job. */
data class TileNeed(val label: String, val state: NeedState, val detail: String)

/** A read-only number worth showing about a cell. */
data class TileFact(val label: String, val value: String)

/**
 * Everything the info tool says about one cell: what it is, what it needs, a
 * few numbers, and the single most useful next action.
 */
data class TileReport(
    val title: String,
    val subtitle: String,
    val needs: List<TileNeed>,
    val facts: List<TileFact>,
    val advice: String,
    val broken: Boolean
)

/**
 * Turns a single tile into plain-language advice: what is missing before it can
 * grow, what a service is (or is not) reaching, and what to build next. Pure
 * logic, no Android imports, so it can be unit tested.
 */
object CityInspector {

    fun report(engine: CityEngine, col: Int, row: Int): TileReport? {
        val t = engine.tile(col, row) ?: return null
        val onWater = t.terrain == Terrain.WATER
        val around = neighbours(engine, col, row)
        val roadNext = around.any { it.zone.isRoad } || t.zone.isRoad
        val needs = ArrayList<TileNeed>(5)
        val facts = ArrayList<TileFact>(5)
        var advice = ""
        var broken = false

        when {
            t.zone.isZone -> {
                needs.add(need("Road access", t.roadAccess))
                needs.add(need("Power", t.powered))
                needs.add(need("Water", t.watered))
                if (t.level > 0) {
                    needs.add(
                        TileNeed(
                            "Fire cover",
                            if (t.fireCovered) NeedState.MET else NeedState.MISSING,
                            if (t.fireCovered) "yes" else "NO"
                        )
                    )
                    needs.add(
                        TileNeed(
                            "Police cover",
                            if (t.policeCovered) NeedState.MET else NeedState.MISSING,
                            if (t.policeCovered) "yes" else "NO"
                        )
                    )
                }
                needs.add(
                    TileNeed(
                        "Transit",
                        if (t.transitAccess) NeedState.MET else NeedState.OPTIONAL,
                        if (t.transitAccess) "rail or subway" else "none (optional)"
                    )
                )

                if (t.level == 0) {
                    val demand = demandFor(engine, t.zone)
                    needs.add(
                        TileNeed(
                            "Demand above zero",
                            if (demand > 0.04f) NeedState.MET else NeedState.MISSING,
                            signed(demand)
                        )
                    )
                    facts.add(TileFact("Status", "vacant lot"))
                    if (t.waterfront || t.reclaimed) {
                        facts.add(TileFact("Waterfront", waterfrontBonus()))
                    }
                    facts.add(
                        TileFact(
                            "R / C / I demand",
                            "${signed(engine.demandR)} ${signed(engine.demandC)} " +
                                "${signed(engine.demandI)}"
                        )
                    )
                    broken = !t.roadAccess || !t.powered || !t.watered
                    advice = when {
                        !t.roadAccess -> "Lay a road beside this lot so people can reach it."
                        !t.powered -> "No power reaches it - place a PLANT or run a cable here."
                        !t.watered -> "No water reaches it - place a WATER tower or pipe a main."
                        demand <= 0.04f -> when (t.zone) {
                            ZoneType.RESIDENTIAL ->
                                "Residential demand is flat - jobs and services need to catch " +
                                    "up before more homes fill."

                            ZoneType.COMMERCIAL ->
                                "There are already more shops than customers - grow the " +
                                    "population before zoning more COM."

                            ZoneType.INDUSTRIAL ->
                                "Industry is ahead of the workforce - add homes and jobs " +
                                    "before zoning more IND."

                            else -> "Demand is flat - improve the city before zoning more."
                        }
                        else -> "Ready to grow - a building appears over the next few days."
                    }
                } else {
                    val cap = engine.capacityForTile(t)
                    val inside = t.population + t.jobs
                    val fill = if (cap == 0) 0f else inside.toFloat() / cap
                    facts.add(TileFact("Level", "L${t.level} of ${Config.MAX_LEVEL}"))
                    facts.add(TileFact("Inside", "$inside of $cap"))
                    if (t.waterfront || t.reclaimed) {
                        facts.add(TileFact("Waterfront", waterfrontBonus()))
                    }
                    facts.add(TileFact("Income", perDay(engine.incomePerTick(t))))

                    val cover = coverage(engine, col, row)
                    facts.add(
                        TileFact(
                            "Police / fire / health",
                            "${mark(cover.police)} ${mark(cover.fire)} ${mark(cover.health)}"
                        )
                    )

                    if (t.fire > 0) {
                        facts.add(0, TileFact("Burning", "${t.fire} of ${Config.FIRE_BURN_DAYS} days"))
                        broken = true
                        advice = "It is on fire - a FIRE station within ${Config.FIRE_RANGE} tiles puts it out."
                    } else if (t.crime > 0) {
                        facts.add(0, TileFact("Crime mark", "${t.crime} days left"))
                        broken = true
                        advice = "Crime here - POLICE cover within ${Config.POLICE_RANGE} tiles clears it."
                    } else if (t.distress > 0) {
                        facts.add(0, TileFact("Neglected", "${t.distress} days"))
                        broken = true
                        advice = "Cut off from road, power or water - it will empty out and be abandoned."
                    } else if (!t.roadAccess || !t.powered || !t.watered) {
                        broken = true
                        advice = when {
                            !t.roadAccess -> "It has lost its road - reopen access before it empties."
                            !t.powered -> "It has lost power - reconnect the plant or cable."
                            else -> "It has lost water - reconnect the tower or pipe."
                        }
                    } else if (t.level < Config.MAX_LEVEL) {
                        if (fill < 0.8f) {
                            advice = "Filling up (${(fill * 100).toInt()}%) - it upgrades when it is full."
                        } else {
                            val span = Config.UPGRADE_DAYS * t.level
                            val progress = (t.maturity * 100 / max(1, span)).coerceIn(0, 100)
                            advice = if (progress >= 100) {
                                "Full and settled - it should upgrade to L${t.level + 1} soon."
                            } else {
                                "Full and settling ($progress%) - it upgrades to L${t.level + 1} in time."
                            }
                        }
                    } else {
                        advice = "A top-level building: keep it powered, watered and covered."
                    }
                    if (t.merged) advice = "Part of a city block - all four lots level up together."
                }
            }

            t.zone.isService -> {
                val upkeep = -engine.incomePerTick(t)
                facts.add(TileFact("Upkeep", perDay(upkeep)))
                when (t.zone) {
                    ZoneType.POLICE,
                    ZoneType.FIRE,
                    ZoneType.HOSPITAL -> {
                        val range = when (t.zone) {
                            ZoneType.POLICE -> Config.POLICE_RANGE
                            ZoneType.FIRE -> Config.FIRE_RANGE
                            else -> Config.HOSPITAL_RANGE
                        }
                        val served = developedWithin(engine, col, row, range)
                        needs.add(
                            TileNeed(
                                "Buildings in range",
                                if (served > 0) NeedState.MET else NeedState.MISSING,
                                "$served built"
                            )
                        )
                        facts.add(TileFact("Range", "$range tiles"))
                        broken = served == 0
                        advice = if (served == 0) {
                            "It reaches no buildings - move it closer to the neighbourhood."
                        } else {
                            "Covering $served built lots within $range tiles."
                        }
                    }

                    ZoneType.PLANT,
                    ZoneType.SOLAR,
                    ZoneType.WIND,
                    ZoneType.NUCLEAR -> {
                        val touchRoad = roadNext || around.any { it.powerline } || t.powerline
                        needs.add(
                            TileNeed(
                                "Road or cable beside it",
                                if (touchRoad) NeedState.MET else NeedState.MISSING,
                                if (touchRoad) "connected" else "none"
                            )
                        )
                        facts.add(TileFact("Capacity", "${engine.powerCapacity(t.zone)} tiles"))
                        broken = !touchRoad
                        advice = when {
                            !touchRoad -> "A plant needs a road or cable to send power anywhere."
                            t.zone == ZoneType.SOLAR || t.zone == ZoneType.WIND ->
                                "Clean power - it flows along roads, buildings, parks and buried cables."
                            t.zone == ZoneType.NUCLEAR ->
                                "Low-smog baseload power flows along roads, buildings, parks and cables."
                            else -> "Power flows along roads, buildings, parks and buried cables."
                        }
                    }

                    ZoneType.WATERWORKS -> {
                        val served = developedWithin(engine, col, row, Config.WATER_RANGE)
                        needs.add(
                            TileNeed(
                                "Built lots in range",
                                if (served > 0) NeedState.MET else NeedState.MISSING,
                                "$served built"
                            )
                        )
                        facts.add(TileFact("Range", "${Config.WATER_RANGE} tiles"))
                        broken = served == 0
                        advice = if (served == 0) {
                            "It waters nothing yet - put it nearer homes or run mains out."
                        } else {
                            "Watering $served built lots, and any lot beside a live main."
                        }
                    }

                    ZoneType.STATION -> {
                        needs.add(
                            TileNeed(
                                "On a rail line",
                                if (t.railAccess) NeedState.MET else NeedState.MISSING,
                                if (t.railAccess) "connected" else "no line"
                            )
                        )
                        val served = developedWithin(engine, col, row, Config.STATION_RANGE)
                        facts.add(TileFact("Covers", "$served built"))
                        broken = !t.railAccess
                        advice = if (!t.railAccess) {
                            "A station only works beside a rail line of two or more tiles."
                        } else {
                            "Spreading transit access ${Config.STATION_RANGE} tiles from its door."
                        }
                    }

                    ZoneType.SUBWAY_STATION -> {
                        val tunnel = t.subwayConnected || around.any { it.subwayConnected }
                        needs.add(
                            TileNeed(
                                "Tunnel beside it",
                                if (tunnel) NeedState.MET else NeedState.MISSING,
                                if (tunnel) "connected" else "none"
                            )
                        )
                        val served = developedWithin(engine, col, row, Config.SUBWAY_RANGE)
                        facts.add(TileFact("Covers", "$served built"))
                        broken = !tunnel
                        advice = if (!tunnel) {
                            "Dig a SUBWAY tunnel under or beside this station."
                        } else {
                            "Riders reach ${Config.SUBWAY_RANGE} tiles from this platform."
                        }
                    }

                    else -> advice = "A civic building for the city."
                }
            }

            t.zone.isRoad -> {
                val joins = around.count { it.zone.isRoad }
                needs.add(
                    TileNeed(
                        "Connected street",
                        if (joins > 0) NeedState.MET else NeedState.MISSING,
                        "$joins of 4 sides"
                    )
                )
                facts.add(TileFact("Upkeep", perDay(-engine.incomePerTick(t))))
                if (onWater) {
                    facts.add(TileFact("Bridge", "over water"))
                    facts.add(TileFact("Fill cost", dollars(engine.costOf(Tool.RECLAIM, col, row))))
                }
                if (t.pipe) facts.add(TileFact("Water main", if (t.watered) "live" else "dry"))
                if (t.powerline) facts.add(TileFact("Cable", if (t.powered) "live" else "dead"))
                broken = joins == 0
                advice = when {
                    joins == 0 -> "A stub with no neighbours - connect it to the street grid."
                    onWater -> "A bridge: cars, power and water mains cross here. FILL makes it dry land."
                    else -> "Roads carry power and give every lot beside them access."
                }
            }

            t.zone == ZoneType.RAIL -> {
                val joins = around.count { it.zone == ZoneType.RAIL }
                needs.add(
                    TileNeed(
                        "Part of a line",
                        if (joins > 0) NeedState.MET else NeedState.MISSING,
                        "$joins rail neighbour(s)"
                    )
                )
                val station = nearestZone(engine, col, row, ZoneType.STATION)
                needs.add(
                    TileNeed(
                        "Station within ${Config.STATION_RANGE}",
                        if (station <= Config.STATION_RANGE) NeedState.MET else NeedState.OPTIONAL,
                        if (station == Int.MAX_VALUE) "none" else "nearest $station tiles"
                    )
                )
                broken = joins == 0
                advice = when {
                    joins == 0 -> "One rail tile does nothing - join it to another rail tile."
                    station > Config.STATION_RANGE ->
                        "Line is live - add a STATION beside it to spread transit."
                    else -> "Carrying passengers to the station on this line."
                }
            }

            t.zone == ZoneType.AIRPORT -> {
                facts.add(TileFact("Jobs", "${Config.AIRPORT_JOBS}"))
                facts.add(TileFact("Upkeep", perDay(-engine.incomePerTick(t))))
                needs.add(
                    TileNeed(
                        "Road beside the block",
                        if (roadNext) NeedState.MET else NeedState.MISSING,
                        if (roadNext) "connected" else "none"
                    )
                )
                broken = !roadNext
                advice = if (roadNext) {
                    "A landmark: tourists keep the shops busy."
                } else {
                    "The airport has lost its road access."
                }
            }

            t.subway -> {
                needs.add(
                    TileNeed(
                        "Part of a line",
                        if (t.subwayConnected) NeedState.MET else NeedState.MISSING,
                        if (t.subwayConnected) "connected" else "stub"
                    )
                )
                val station = nearestZone(engine, col, row, ZoneType.SUBWAY_STATION)
                needs.add(
                    TileNeed(
                        "Station within ${Config.SUBWAY_RANGE}",
                        if (station <= Config.SUBWAY_RANGE) NeedState.MET else NeedState.MISSING,
                        if (station == Int.MAX_VALUE) "none" else "nearest $station tiles"
                    )
                )
                broken = !t.subwayConnected || station > Config.SUBWAY_RANGE
                advice = when {
                    !t.subwayConnected ->
                        "A single tunnel is a stub - connect two or more tunnel tiles."
                    station > Config.SUBWAY_RANGE ->
                        "The line is live but no station reaches it - build a SUB STN."
                    else -> "Tunnels and platforms are working together."
                }
            }

            onWater -> {
                facts.add(TileFact("Terrain", "open water"))
                val shore = around.any { it.terrain != Terrain.WATER }
                if (shore) {
                    facts.add(TileFact("Fill cost", dollars(engine.costOf(Tool.RECLAIM, col, row))))
                }
                advice = if (shore) {
                    "Open water: bridge it, tunnel under it, or FILL it into land you can zone."
                } else {
                    "Open water: FILL works from the shore - fill the edge first, then outward."
                }
            }

            else -> {
                needs.add(
                    TileNeed(
                        "Road beside it",
                        if (roadNext) NeedState.MET else NeedState.MISSING,
                        if (roadNext) "yes" else "none"
                    )
                )
                facts.add(
                    TileFact(
                        "Terrain",
                        if (t.terrain == Terrain.FOREST) "forest (cleared when built)" else "grass"
                    )
                )
                if (t.waterfront || t.reclaimed) {
                    facts.add(TileFact("Waterfront", waterfrontBonus()))
                }
                if (t.subway) facts.add(TileFact("Subway", if (t.subwayConnected) "tunnel" else "stub"))
                if (t.pipe) facts.add(TileFact("Water main", if (t.watered) "live" else "dry"))
                if (t.powerline) facts.add(TileFact("Cable", if (t.powered) "live" else "dead"))
                if (roadNext) {
                    facts.add(TileFact("R / C / I demand", "${signed(engine.demandR)} ${signed(engine.demandC)} ${signed(engine.demandI)}"))
                    advice = "Road-connected land - zone RES, COM or IND here."
                } else {
                    advice = "No road touches this cell - lay a ROAD first, or pick another spot."
                }
            }
        }

        // problems first, so the card leads with what actually needs fixing
        val ordered = needs.sortedBy {
            when (it.state) {
                NeedState.MISSING -> 0
                NeedState.MET -> 1
                NeedState.OPTIONAL -> 2
            }
        }
        return TileReport(
            title = engine.tileState(t),
            subtitle = "col $col · row $row",
            needs = ordered,
            facts = facts,
            advice = advice,
            broken = broken
        )
    }

    // ------------------------------------------------------------------ helpers

    private fun neighbours(engine: CityEngine, col: Int, row: Int): List<Tile> {
        val around = ArrayList<Tile>(4)
        engine.tile(col - 1, row)?.let { around.add(it) }
        engine.tile(col + 1, row)?.let { around.add(it) }
        engine.tile(col, row - 1)?.let { around.add(it) }
        engine.tile(col, row + 1)?.let { around.add(it) }
        return around
    }

    private fun nearestZone(engine: CityEngine, col: Int, row: Int, zone: ZoneType): Int {
        var best = Int.MAX_VALUE
        for (r in 0 until engine.rows) {
            for (c in 0 until engine.cols) {
                if (engine.tile(c, r)?.zone != zone) continue
                val d = abs(c - col) + abs(r - row)
                if (d < best) best = d
            }
        }
        return best
    }

    private fun developedWithin(engine: CityEngine, col: Int, row: Int, range: Int): Int {
        var count = 0
        for (r in max(0, row - range)..min(engine.rows - 1, row + range)) {
            for (c in max(0, col - range)..min(engine.cols - 1, col + range)) {
                if (abs(c - col) + abs(r - row) > range) continue
                if (engine.tile(c, r)?.developed == true) count++
            }
        }
        return count
    }

    private data class Cover(val police: Boolean, val fire: Boolean, val health: Boolean)

    private fun coverage(engine: CityEngine, col: Int, row: Int): Cover = Cover(
        police = nearestZone(engine, col, row, ZoneType.POLICE) <= Config.POLICE_RANGE,
        fire = nearestZone(engine, col, row, ZoneType.FIRE) <= Config.FIRE_RANGE,
        health = nearestZone(engine, col, row, ZoneType.HOSPITAL) <= Config.HOSPITAL_RANGE
    )

    private fun need(label: String, met: Boolean): TileNeed =
        TileNeed(label, if (met) NeedState.MET else NeedState.MISSING, if (met) "yes" else "NO")

    private fun demandFor(engine: CityEngine, zone: ZoneType): Float = when (zone) {
        ZoneType.RESIDENTIAL -> engine.demandR
        ZoneType.COMMERCIAL -> engine.demandC
        ZoneType.INDUSTRIAL -> engine.demandI
        else -> 0f
    }

    private fun mark(value: Boolean): String = if (value) "yes" else "no"

    private fun signed(value: Float): String {
        val percent = (value * 100).toInt()
        return when {
            percent > 0 -> "+$percent%"
            else -> "$percent%"
        }
    }

    private fun dollars(value: Int): String = "\$" + value

    /** The desirability bump a waterside lot gets, as a percent of capacity. */
    private fun waterfrontBonus(): String =
        "+" + ((Config.WATERFRONT_CAPACITY - 1f) * 100f).toInt() + "% capacity"

    private fun perDay(value: Float): String {
        val cents = Math.round(value * 100.0).toInt()
        val sign = if (cents < 0) "-$" else "$"
        val abs = abs(cents)
        return "$sign${abs / 100}.${(abs % 100).toString().padStart(2, '0')}/day"
    }
}
