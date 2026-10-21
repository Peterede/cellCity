package com.cellcity.game

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * The whole city simulation. Deliberately free of Android imports so it can be
 * exercised from plain JVM unit tests and serialised into SharedPreferences.
 */
class CityEngine(
    val cols: Int = Config.COLS,
    val rows: Int = Config.ROWS,
    seed: Long = 20261020L,
    private val enforceUnlocks: Boolean = true
) {
    private val seedValue: Long = seed
    private val rng = Random(seed)
    private val grid: Array<Array<Tile>> = Array(rows) { Array(cols) { Tile() } }
    private val events = ArrayList<CityEvent>()

    var funds: Int = Config.START_FUNDS
        private set
    var day: Int = 1
        private set
    var month: Int = 1
        private set
    var year: Int = Config.START_YEAR
        private set

    var population: Int = 0
        private set
    var jobs: Int = 0
        private set
    var happiness: Float = 72f
        private set
    var pollution: Float = 0f
        private set

    var demandR: Float = Config.DEMAND_BASE_R
        private set
    var demandC: Float = Config.DEMAND_BASE_C
        private set
    var demandI: Float = Config.DEMAND_BASE_I
        private set

    /** Share of developed lots covered by each emergency service, 0..1. */
    var policeCoverage: Float = 0f
        private set
    var fireCoverage: Float = 0f
        private set
    var healthCoverage: Float = 0f
        private set

    private var monthIncome = 0f
    private var monthExpenses = 0f
    private var debtWarned = false

    // --- progression -------------------------------------------------------
    /** Consecutive days the city has counted as stable (populous, happy, in the black). */
    var stableDays: Int = 0
        private set

    private val completed = LinkedHashSet<Milestone>()

    /** Saves from before progression existed keep every tool available. */
    private var legacyUnlockedAll = false

    val completedMilestones: Set<Milestone> get() = completed

    init {
        require(cols in 8..64 && rows in 8..64) { "Unsupported map size" }
        generateLandscape()
        refreshInfrastructure()
        updateDemand()
    }

    // ------------------------------------------------------------------ access

    fun inBounds(col: Int, row: Int): Boolean = col in 0 until cols && row in 0 until rows

    fun tile(col: Int, row: Int): Tile? = if (inBounds(col, row)) grid[row][col] else null

    fun drainEvents(): List<CityEvent> {
        if (events.isEmpty()) return emptyList()
        val copy = events.toList()
        events.clear()
        return copy
    }

    val roadCount: Int get() = countTiles { it.zone == ZoneType.ROAD }
    val highwayCount: Int get() = countTiles { it.zone == ZoneType.HIGHWAY }
    val railCount: Int get() = countTiles { it.zone == ZoneType.RAIL }
    val parkCount: Int get() = countTiles { it.zone == ZoneType.PARK }
    val plantCount: Int get() = countTiles { it.zone == ZoneType.PLANT }
    val waterCount: Int get() = countTiles { it.zone == ZoneType.WATERWORKS }
    val pipeCount: Int get() = countTiles { it.pipe }
    val powerlineCount: Int get() = countTiles { it.powerline }
    val stationCount: Int get() = countTiles { it.zone == ZoneType.STATION }
    val policeCount: Int get() = countTiles { it.zone == ZoneType.POLICE }
    val fireCount: Int get() = countTiles { it.zone == ZoneType.FIRE }
    val hospitalCount: Int get() = countTiles { it.zone == ZoneType.HOSPITAL }
    val airportCount: Int get() = countOrigins(ZoneType.AIRPORT)
    val buildingCount: Int get() = countTiles { it.developed }
    val zoneCount: Int get() = countTiles { it.zone.isZone }
    val poweredBuildingCount: Int get() = countTiles { it.developed && it.powered }
    val vacantZoneCount: Int get() = countTiles { it.zone.isZone && it.level == 0 }
    val mergedBlockCount: Int get() = countBlockOrigins { it.zone.isZone }

    val developedHousing: Int
        get() = countTiles { it.zone == ZoneType.RESIDENTIAL && it.level > 0 }

    val netPerDay: Int
        get() = (dailyIncome() - dailyExpenses()).roundToInt()

    val dateLabel: String
        get() = "${MONTHS[(month - 1).coerceIn(0, 11)]} $year"

    // ------------------------------------------------------------- progression

    /** Whether the given tool may be built in this city right now. */
    fun isUnlocked(tool: Tool): Boolean {
        if (!enforceUnlocks || legacyUnlockedAll) return true
        if (tool in Milestone.START_TOOLS) return true
        return completed.any { tool in it.unlocks }
    }

    /** The next milestone in line, or null when every goal is done. */
    fun nextMilestone(): Milestone? = Milestone.values().firstOrNull { it !in completed }

    /** How far the city currently is toward a milestone, in that milestone's units. */
    fun milestoneValue(milestone: Milestone): Int = when (milestone) {
        Milestone.FIRST_HOME,
        Milestone.HAMLET,
        Milestone.TOWN,
        Milestone.CITY,
        Milestone.METROPOLIS -> population

        Milestone.STEADY,
        Milestone.STABLE_CYCLE -> stableDays

        Milestone.SKYLINE -> min(1, mergedBlockCount)
    }

    /** The value [milestoneValue] has to reach for the milestone to complete. */
    fun milestoneTarget(milestone: Milestone): Int = when (milestone) {
        Milestone.FIRST_HOME -> Config.MILESTONE_POP_FIRST_HOME
        Milestone.HAMLET -> Config.MILESTONE_POP_HAMLET
        Milestone.TOWN -> Config.MILESTONE_POP_TOWN
        Milestone.CITY -> Config.MILESTONE_POP_CITY
        Milestone.METROPOLIS -> Config.MILESTONE_POP_METROPOLIS
        Milestone.STEADY -> Config.MILESTONE_STEADY_DAYS
        Milestone.STABLE_CYCLE -> Config.MILESTONE_STABLE_CYCLE_DAYS
        Milestone.SKYLINE -> 1
    }

    // ------------------------------------------------------------------ build

    fun baseCost(tool: Tool): Int = when (tool) {
        Tool.ROAD -> Config.COST_ROAD
        Tool.HIGHWAY -> Config.COST_HIGHWAY
        Tool.RAIL -> Config.COST_RAIL
        Tool.STATION -> Config.COST_STATION
        Tool.RESIDENTIAL -> Config.COST_RESIDENTIAL
        Tool.COMMERCIAL -> Config.COST_COMMERCIAL
        Tool.INDUSTRIAL -> Config.COST_INDUSTRIAL
        Tool.PARK -> Config.COST_PARK
        Tool.PLANT -> Config.COST_PLANT
        Tool.WATER -> Config.COST_WATERWORKS
        Tool.PIPE -> Config.COST_PIPE
        Tool.POWERLINE -> Config.COST_POWERLINE
        Tool.POLICE -> Config.COST_POLICE
        Tool.FIRE -> Config.COST_FIRE
        Tool.HOSPITAL -> Config.COST_HOSPITAL
        Tool.AIRPORT -> Config.COST_AIRPORT
        Tool.BULLDOZE, Tool.PAN -> 0
    }

    fun costOf(tool: Tool, col: Int, row: Int): Int {
        val t = tile(col, row) ?: return baseCost(tool)
        return when (tool) {
            Tool.ROAD -> if (t.terrain == Terrain.WATER) Config.COST_BRIDGE else Config.COST_ROAD
            Tool.HIGHWAY -> if (t.terrain == Terrain.WATER) {
                Config.COST_HIGHWAY_BRIDGE
            } else {
                Config.COST_HIGHWAY
            }
            Tool.PIPE, Tool.POWERLINE -> layerCost(tool, t.terrain)
            else -> baseCost(tool)
        }
    }

    fun canBuild(tool: Tool, col: Int, row: Int): BuildResult {
        if (tool == Tool.PAN) return BuildResult.OK
        if (!inBounds(col, row)) return BuildResult.OUT_OF_BOUNDS
        val t = grid[row][col]
        if (tool == Tool.BULLDOZE) return BuildResult.OK
        if (!isUnlocked(tool)) return BuildResult.LOCKED
        if (tool.isLayer) return canBuildLayer(tool, col, row)
        if (t.zone == tool.zone) return BuildResult.OK
        if (tool == Tool.AIRPORT) return canBuildAirport(col, row)
        if (tool == Tool.STATION) return canBuildStation(col, row)
        if (t.zone != ZoneType.NONE) return BuildResult.OCCUPIED
        if (t.terrain == Terrain.WATER && !tool.zone.isRoad) return BuildResult.WATER
        if (t.terrain == Terrain.WATER && tool.zone.isRoad) {
            if (funds < costOf(tool, col, row)) return BuildResult.NO_FUNDS
            return BuildResult.OK
        }
        if (funds < baseCost(tool)) return BuildResult.NO_FUNDS
        return BuildResult.OK
    }

    /** An airport needs a clear, dry 2x2 block with a road touching it. */
    private fun canBuildAirport(col: Int, row: Int): BuildResult {
        if (!inBounds(col + 1, row + 1)) return BuildResult.OUT_OF_BOUNDS
        var touchesRoad = false
        for (r in row..row + 1) {
            for (c in col..col + 1) {
                val t = grid[r][c]
                if (t.zone != ZoneType.NONE) return BuildResult.OCCUPIED
                if (t.terrain == Terrain.WATER) return BuildResult.WATER
                if (hasRoadNeighbour(c, r)) touchesRoad = true
            }
        }
        if (!touchesRoad) return BuildResult.NO_ROAD
        if (funds < Config.COST_AIRPORT) return BuildResult.NO_FUNDS
        return BuildResult.OK
    }

    private fun layerCost(tool: Tool, terrain: Terrain): Int = when (tool) {
        Tool.PIPE -> if (terrain == Terrain.WATER) Config.COST_PIPE_WATER else Config.COST_PIPE
        Tool.POWERLINE -> if (terrain == Terrain.WATER) {
            Config.COST_POWERLINE_WATER
        } else {
            Config.COST_POWERLINE
        }
        else -> 0
    }

    /** Mains and cables are layers: they fit under any existing zone, road or building. */
    private fun canBuildLayer(tool: Tool, col: Int, row: Int): BuildResult {
        val t = grid[row][col]
        if (if (tool == Tool.PIPE) t.pipe else t.powerline) return BuildResult.OK
        if (funds < layerCost(tool, t.terrain)) return BuildResult.NO_FUNDS
        return BuildResult.OK
    }

    /** A station has to sit beside a rail line, and only on dry land. */
    private fun canBuildStation(col: Int, row: Int): BuildResult {
        if (grid[row][col].zone != ZoneType.NONE) return BuildResult.OCCUPIED
        if (grid[row][col].terrain == Terrain.WATER) return BuildResult.WATER
        if (!hasRailNeighbour(col, row)) return BuildResult.NO_RAIL
        if (funds < Config.COST_STATION) return BuildResult.NO_FUNDS
        return BuildResult.OK
    }

    fun build(tool: Tool, col: Int, row: Int): BuildResult {
        val check = canBuild(tool, col, row)
        if (check != BuildResult.OK) return check
        if (tool == Tool.PAN) return BuildResult.OK
        if (!inBounds(col, row)) return BuildResult.OUT_OF_BOUNDS

        val t = grid[row][col]
        if (tool == Tool.BULLDOZE) {
            if (t.zone == ZoneType.NONE && !t.pipe && !t.powerline) return BuildResult.OK
            val block = blockTiles(t)
            val wasAirport = t.zone == ZoneType.AIRPORT
            var value = 0f
            for (b in block) {
                value += investedCost(b)
                if (b.pipe) value += layerCost(Tool.PIPE, b.terrain)
                if (b.powerline) value += layerCost(Tool.POWERLINE, b.terrain)
            }
            if (wasAirport) {
                value = Config.COST_AIRPORT.toFloat()
                for (b in block) {
                    if (b.pipe) value += layerCost(Tool.PIPE, b.terrain)
                    if (b.powerline) value += layerCost(Tool.POWERLINE, b.terrain)
                }
            }
            funds += (value * Config.BULLDOZE_REFUND).roundToInt()
            for (b in block) {
                b.clear()
                if (b.terrain == Terrain.FOREST) b.terrain = Terrain.GRASS
            }
            refreshInfrastructure()
            return BuildResult.OK
        }
        if (tool.isLayer) {
            val laid = if (tool == Tool.PIPE) t.pipe else t.powerline
            if (laid) return BuildResult.OK
            funds -= layerCost(tool, t.terrain)
            if (tool == Tool.PIPE) t.pipe = true else t.powerline = true
            events.add(CityEvent.Built(tool.zone, col, row))
            refreshInfrastructure()
            return BuildResult.OK
        }
        if (t.zone == tool.zone) return BuildResult.OK
        if (tool == Tool.AIRPORT) return buildAirport(col, row)

        val cost = costOf(tool, col, row)
        funds -= cost
        t.zone = tool.zone
        t.variant = rng.nextInt(4)
        if (t.terrain == Terrain.FOREST) t.terrain = Terrain.GRASS
        if (tool == Tool.PARK || tool == Tool.PLANT || tool.zone.isService) t.level = 1
        events.add(CityEvent.Built(t.zone, col, row))
        refreshInfrastructure()
        return BuildResult.OK
    }

    private fun buildAirport(col: Int, row: Int): BuildResult {
        funds -= Config.COST_AIRPORT
        for (r in row..row + 1) {
            for (c in col..col + 1) {
                val t = grid[r][c]
                t.zone = ZoneType.AIRPORT
                t.level = 1
                t.merged = true
                t.originCol = col
                t.originRow = row
                t.variant = rng.nextInt(4)
                if (t.terrain == Terrain.FOREST) t.terrain = Terrain.GRASS
            }
        }
        grid[row][col].jobs = Config.AIRPORT_JOBS
        events.add(CityEvent.Built(ZoneType.AIRPORT, col, row))
        refreshInfrastructure()
        return BuildResult.OK
    }

    private fun investedCost(t: Tile): Int = when (t.zone) {
        ZoneType.ROAD -> if (t.terrain == Terrain.WATER) Config.COST_BRIDGE else Config.COST_ROAD
        ZoneType.RAIL -> Config.COST_RAIL
        ZoneType.PIPE -> if (t.terrain == Terrain.WATER) Config.COST_PIPE_WATER else Config.COST_PIPE
        ZoneType.RESIDENTIAL -> Config.COST_RESIDENTIAL
        ZoneType.COMMERCIAL -> Config.COST_COMMERCIAL
        ZoneType.INDUSTRIAL -> Config.COST_INDUSTRIAL
        ZoneType.PARK -> Config.COST_PARK
        ZoneType.PLANT -> Config.COST_PLANT
        ZoneType.WATERWORKS -> Config.COST_WATERWORKS
        ZoneType.POLICE -> Config.COST_POLICE
        ZoneType.FIRE -> Config.COST_FIRE
        ZoneType.HOSPITAL -> Config.COST_HOSPITAL
        ZoneType.STATION -> Config.COST_STATION
        ZoneType.AIRPORT -> Config.COST_AIRPORT
        ZoneType.HIGHWAY -> if (t.terrain == Terrain.WATER) {
            Config.COST_HIGHWAY_BRIDGE
        } else {
            Config.COST_HIGHWAY
        }
        ZoneType.NONE -> 0
    }

    /** The four tiles of a 2x2 block, or just the tile itself. */
    private fun blockTiles(t: Tile): List<Tile> {
        if (!t.merged || t.originCol < 0 || t.originRow < 0) return listOf(t)
        val list = ArrayList<Tile>(4)
        for (r in t.originRow..t.originRow + 1) {
            for (c in t.originCol..t.originCol + 1) {
                if (inBounds(c, r)) list.add(grid[r][c])
            }
        }
        return list
    }

    private fun syncBlockLevel(origin: Tile) {
        for (b in blockTiles(origin)) {
            b.level = origin.level
            b.maturity = 0
            b.distress = 0
        }
    }

    private fun unmergeBlock(t: Tile) {
        for (b in blockTiles(t)) {
            b.merged = false
            b.originCol = -1
            b.originRow = -1
        }
    }

    // ------------------------------------------------------------------ tick

    /** Advance exactly one in-game day. */
    fun tick() {
        updateRoadAccess()
        updateRailAccess()
        updateWater()
        updatePower()
        updateServices()
        updatePollution()
        updateDemand()
        growBuildings()
        updateOccupancy()
        updateMerges()
        updateHappiness()
        updateEconomy()
        updateProgression()
        advanceDate()
    }

    fun refreshInfrastructure() {
        updateRoadAccess()
        updateRailAccess()
        updateWater()
        updatePower()
        updateServices()
    }

    private fun updateRoadAccess() {
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val t = grid[r][c]
                t.roadAccess = t.zone.isRoad || hasRoadNeighbour(c, r)
            }
        }
    }

    private fun hasRoadNeighbour(col: Int, row: Int): Boolean {
        if (col > 0 && grid[row][col - 1].zone.isRoad) return true
        if (col < cols - 1 && grid[row][col + 1].zone.isRoad) return true
        if (row > 0 && grid[row - 1][col].zone.isRoad) return true
        if (row < rows - 1 && grid[row + 1][col].zone.isRoad) return true
        return false
    }

    private fun hasRailNeighbour(col: Int, row: Int): Boolean {
        if (col > 0 && grid[row][col - 1].zone == ZoneType.RAIL) return true
        if (col < cols - 1 && grid[row][col + 1].zone == ZoneType.RAIL) return true
        if (row > 0 && grid[row - 1][col].zone == ZoneType.RAIL) return true
        if (row < rows - 1 && grid[row + 1][col].zone == ZoneType.RAIL) return true
        return false
    }

    /**
     * Power flows from every plant through roads, parks and buildings until the
     * plant's capacity runs out. Empty grass and water stop the flow, so a
     * disconnected district really does go dark.
     */
    private fun updatePower() {
        val reached = Array(rows) { BooleanArray(cols) }
        val queue = ArrayDeque<Int>()
        var plants = 0
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                if (grid[r][c].zone == ZoneType.PLANT) {
                    reached[r][c] = true
                    queue.addLast(r * cols + c)
                    plants++
                }
            }
        }
        val budget = plants * Config.PLANT_POWER
        var used = 0
        while (queue.isNotEmpty() && used < budget) {
            val idx = queue.removeFirst()
            used++
            val c = idx % cols
            val r = idx / cols
            for (dir in 0 until 4) {
                val nc = c + DC[dir]
                val nr = r + DR[dir]
                if (!inBounds(nc, nr) || reached[nr][nc]) continue
                // cables carry the grid across open ground that roads do not reach
                if (grid[nr][nc].zone == ZoneType.NONE && !grid[nr][nc].powerline) continue
                reached[nr][nc] = true
                queue.addLast(nr * cols + nc)
            }
        }
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                grid[r][c].powered = reached[r][c]
            }
        }
    }

    /**
     * A rail network of two or more connected tiles counts as a line, and the
     * lots beside it get transit access. A train station on the line spreads
     * that access to every lot within its range.
     */
    private fun updateRailAccess() {
        for (r in 0 until rows) {
            for (c in 0 until cols) grid[r][c].railAccess = false
        }
        val seen = Array(rows) { BooleanArray(cols) }
        for (r0 in 0 until rows) {
            for (c0 in 0 until cols) {
                if (grid[r0][c0].zone != ZoneType.RAIL || seen[r0][c0]) continue
                val component = ArrayList<Int>()
                val queue = ArrayDeque<Int>()
                seen[r0][c0] = true
                queue.addLast(r0 * cols + c0)
                while (queue.isNotEmpty()) {
                    val idx = queue.removeFirst()
                    component.add(idx)
                    val c = idx % cols
                    val r = idx / cols
                    for (dir in 0 until 4) {
                        val nc = c + DC[dir]
                        val nr = r + DR[dir]
                        if (!inBounds(nc, nr) || seen[nr][nc]) continue
                        if (grid[nr][nc].zone != ZoneType.RAIL) continue
                        seen[nr][nc] = true
                        queue.addLast(nr * cols + nc)
                    }
                }
                if (component.size < 2) continue
                for (idx in component) {
                    val c = idx % cols
                    val r = idx / cols
                    for (dir in 0 until 4) {
                        val nc = c + DC[dir]
                        val nr = r + DR[dir]
                        if (!inBounds(nc, nr)) continue
                        if (grid[nr][nc].zone == ZoneType.RAIL) continue
                        grid[nr][nc].railAccess = true
                    }
                }
            }
        }

        // stations that actually touch a line pull riders in from further out
        val stations = ArrayList<Int>()
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                if (grid[r][c].zone == ZoneType.STATION && grid[r][c].railAccess) {
                    stations.add(r * cols + c)
                }
            }
        }
        for (idx in stations) {
            val sc = idx % cols
            val sr = idx / cols
            for (r in max(0, sr - Config.STATION_RANGE)..min(rows - 1, sr + Config.STATION_RANGE)) {
                for (c in max(0, sc - Config.STATION_RANGE)..min(cols - 1, sc + Config.STATION_RANGE)) {
                    if (abs(c - sc) + abs(r - sr) <= Config.STATION_RANGE) {
                        grid[r][c].railAccess = true
                    }
                }
            }
        }
    }

    /**
     * Water towers pipe water to every tile within range, and to any lot beside
     * a water main that reaches back to a tower. Mains may run under the river.
     */
    private fun updateWater() {
        val sources = ArrayList<Int>()
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                if (grid[r][c].zone == ZoneType.WATERWORKS) sources.add(r * cols + c)
            }
        }

        // flood the mains out from every tower
        val live = Array(rows) { BooleanArray(cols) }
        val queue = ArrayDeque<Int>()
        for (idx in sources) {
            val c = idx % cols
            val r = idx / cols
            live[r][c] = true
            for (dir in 0 until 4) {
                val nc = c + DC[dir]
                val nr = r + DR[dir]
                if (!inBounds(nc, nr) || live[nr][nc]) continue
                if (!grid[nr][nc].pipe) continue
                live[nr][nc] = true
                queue.addLast(nr * cols + nc)
            }
        }
        while (queue.isNotEmpty()) {
            val idx = queue.removeFirst()
            val c = idx % cols
            val r = idx / cols
            for (dir in 0 until 4) {
                val nc = c + DC[dir]
                val nr = r + DR[dir]
                if (!inBounds(nc, nr) || live[nr][nc]) continue
                if (!grid[nr][nc].pipe) continue
                live[nr][nc] = true
                queue.addLast(nr * cols + nc)
            }
        }

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val t = grid[r][c]
                t.watered = when {
                    t.zone == ZoneType.WATERWORKS -> true
                    t.pipe && live[r][c] -> true
                    hasLivePipeNeighbour(live, c, r) -> true
                    else -> sources.any {
                        abs(it % cols - c) + abs(it / cols - r) <= Config.WATER_RANGE
                    }
                }
            }
        }
    }

    private fun hasLivePipeNeighbour(live: Array<BooleanArray>, col: Int, row: Int): Boolean {
        if (col > 0 && live[row][col - 1] && grid[row][col - 1].pipe) return true
        if (col < cols - 1 && live[row][col + 1] && grid[row][col + 1].pipe) return true
        if (row > 0 && live[row - 1][col] && grid[row - 1][col].pipe) return true
        if (row < rows - 1 && live[row + 1][col] && grid[row + 1][col].pipe) return true
        return false
    }

    /** How much of the built city the police, fire and health services reach. */
    private fun updateServices() {
        val police = ArrayList<Int>()
        val fire = ArrayList<Int>()
        val health = ArrayList<Int>()
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                when (grid[r][c].zone) {
                    ZoneType.POLICE -> police.add(r * cols + c)
                    ZoneType.FIRE -> fire.add(r * cols + c)
                    ZoneType.HOSPITAL -> health.add(r * cols + c)
                    else -> Unit
                }
            }
        }
        var developed = 0
        var policeServed = 0
        var fireServed = 0
        var healthServed = 0
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                if (!grid[r][c].developed) continue
                developed++
                if (servedBy(police, c, r, Config.POLICE_RANGE)) policeServed++
                if (servedBy(fire, c, r, Config.FIRE_RANGE)) fireServed++
                if (servedBy(health, c, r, Config.HOSPITAL_RANGE)) healthServed++
            }
        }
        policeCoverage = if (developed == 0) 0f else policeServed.toFloat() / developed
        fireCoverage = if (developed == 0) 0f else fireServed.toFloat() / developed
        healthCoverage = if (developed == 0) 0f else healthServed.toFloat() / developed
    }

    private fun servedBy(stations: List<Int>, col: Int, row: Int, range: Int): Boolean {
        for (idx in stations) {
            if (abs(idx % cols - col) + abs(idx / cols - row) <= range) return true
        }
        return false
    }

    private fun updatePollution() {
        val plants = plantCount
        val industry = countTiles { it.zone == ZoneType.INDUSTRIAL && it.level > 0 }
        val parks = parkCount
        val roads = roadCount
        pollution = clamp(
            plants * 0.15f +
                industry * 0.045f +
                roads * 0.0015f +
                highwayCount * 0.004f +
                airportCount * 0.10f +
                railCount * 0.002f -
                parks * 0.04f,
            0f,
            1f
        )
    }

    private fun updateDemand() {
        val housingCap = capacityTotal(ZoneType.RESIDENTIAL)
        val comCap = capacityTotal(ZoneType.COMMERCIAL)
        val indCap = capacityTotal(ZoneType.INDUSTRIAL)
        val housingFill = population.toFloat() / max(1, housingCap).toFloat()

        demandR = clamp(
            Config.DEMAND_BASE_R -
                (housingCap - population) * 0.018f -
                housingFill * 0.12f +
                (jobs - population) * 0.004f +
                railCount * 0.004f +
                stationCount * 0.02f,
            -1f,
            1f
        )
        demandC = clamp(
            Config.DEMAND_BASE_C +
                (population * 0.20f - comCap * 0.85f) / 45f +
                airportCount * 0.30f +
                highwayCount * 0.03f,
            -1f,
            1f
        )
        demandI = clamp(
            Config.DEMAND_BASE_I + (population * 0.14f + jobs * 0.15f - indCap * 0.85f) / 55f +
                highwayCount * 0.02f,
            -1f,
            1f
        )
    }

    private fun demandFor(zone: ZoneType): Float = when (zone) {
        ZoneType.RESIDENTIAL -> demandR
        ZoneType.COMMERCIAL -> demandC
        ZoneType.INDUSTRIAL -> demandI
        else -> 0f
    }

    private fun growBuildings() {
        val candidates = ArrayList<Int>()
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val t = grid[r][c]
                if (!t.zone.isZone || t.level > 0) continue
                if (!t.powered || !t.roadAccess || !t.watered) continue
                if (demandFor(t.zone) <= 0.04f) continue
                candidates.add(r * cols + c)
            }
        }
        if (candidates.isEmpty()) return
        candidates.shuffle(rng)
        var grown = 0
        for (idx in candidates) {
            if (grown >= Config.MAX_GROWTH_PER_DAY) break
            val t = grid[idx / cols][idx % cols]
            val demand = demandFor(t.zone)
            var chance = Config.GROWTH_CHANCE * (0.45f + 0.55f * demand)
            if (t.railAccess) chance *= 1.3f
            if (rng.nextFloat() < chance) {
                t.level = 1
                t.variant = rng.nextInt(4)
                t.maturity = 0
                events.add(CityEvent.Grew(t.zone, 1, idx % cols, idx / cols))
                grown++
            }
        }
    }

    /**
     * Residents and workers drift toward each building's capacity; buildings
     * upgrade when full and abandoned when cut off from roads or power.
     */
    private fun updateOccupancy() {
        var popTotal = 0
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val t = grid[r][c]
                if (t.zone == ZoneType.RESIDENTIAL && t.level > 0) {
                    val cap = capacityForTile(t)
                    if (t.powered && t.roadAccess && t.watered) {
                        t.distress = max(0, t.distress - 1)
                        t.maturity += if (t.railAccess) 2 else 1
                        val step = max(
                            1,
                            (cap * 0.12f * (0.55f + max(0f, demandR)) *
                                (happiness / 100f + 0.35f)).roundToInt()
                        )
                        t.population = if (t.population < cap) {
                            min(cap, t.population + step)
                        } else {
                            cap
                        }
                    } else {
                        t.distress++
                        t.population = max(0, t.population - max(1, cap / 5))
                    }
                }
                popTotal += t.population
            }
        }
        population = popTotal

        val jobCapacity = capacityTotal(ZoneType.COMMERCIAL) + capacityTotal(ZoneType.INDUSTRIAL)
        val workerRatio = if (population == 0 || jobCapacity == 0) {
            0f
        } else {
            clamp(population.toFloat() / jobCapacity.toFloat() * 1.35f, 0f, 1f)
        }

        var jobTotal = 0
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val t = grid[r][c]
                if ((t.zone == ZoneType.COMMERCIAL || t.zone == ZoneType.INDUSTRIAL) && t.level > 0) {
                    val cap = capacityForTile(t)
                    if (t.powered && t.roadAccess && t.watered) {
                        t.distress = max(0, t.distress - 1)
                        t.maturity += if (t.railAccess) 2 else 1
                        val target = (cap * workerRatio).roundToInt()
                        val step = max(1, (cap * 0.14f).roundToInt())
                        t.jobs = if (t.jobs < target) {
                            min(target, t.jobs + step)
                        } else {
                            max(target, t.jobs - step)
                        }
                    } else {
                        t.distress++
                        t.jobs = max(0, t.jobs - max(1, cap / 5))
                    }
                }
                jobTotal += t.jobs
            }
        }
        jobs = jobTotal

        // upgrades and abandonment
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val t = grid[r][c]
                if (!t.zone.isZone) continue
                // a merged block levels up as one building, driven by its origin
                if (t.merged && (t.originCol != c || t.originRow != r)) continue
                if (t.level > 0) {
                    val cap = capacityForTile(t)
                    val occupied = if (t.zone == ZoneType.RESIDENTIAL) t.population else t.jobs
                    if (t.distress > Config.DISTRESS_DAYS && occupied == 0) {
                        val deadZone = t.zone
                        val deadCol = c
                        val deadRow = r
                        t.level = 0
                        t.maturity = 0
                        t.distress = 0
                        preMergeReset(t)
                        events.add(CityEvent.Died(deadZone, deadCol, deadRow))
                        continue
                    }
                    val upgradeChance = Config.UPGRADE_CHANCE +
                        policeCoverage * 0.12f + fireCoverage * 0.06f
                    if (t.level < Config.MAX_LEVEL &&
                        t.maturity >= Config.UPGRADE_DAYS * t.level &&
                        occupied >= cap * 0.8f &&
                        t.watered &&
                        demandFor(t.zone) > 0.05f &&
                        rng.nextFloat() < upgradeChance
                    ) {
                        t.level++
                        t.maturity = 0
                        if (t.merged) syncBlockLevel(t)
                        events.add(CityEvent.Upgraded(t.zone, t.level))
                    }
                }
            }
        }
    }

    /**
     * Four neighbouring developed lots of the same zone become one large
     * building once every lot has matured to the merge level.
     */
    private fun updateMerges() {
        for (r in 0 until rows - 1) {
            for (c in 0 until cols - 1) {
                val a = grid[r][c]
                val b = grid[r][c + 1]
                val d = grid[r + 1][c]
                val e = grid[r + 1][c + 1]
                val zone = a.zone
                if (!zone.isZone) continue
                if (b.zone != zone || d.zone != zone || e.zone != zone) continue
                if (a.merged || b.merged || d.merged || e.merged) continue
                if (a.level < Config.MERGE_LEVEL || b.level < Config.MERGE_LEVEL) continue
                if (d.level < Config.MERGE_LEVEL || e.level < Config.MERGE_LEVEL) continue
                if (!ready(a) || !ready(b) || !ready(d) || !ready(e)) continue

                // the four lots level up together from now on, so start even
                val level = maxOf(a.level, b.level, d.level, e.level)
                for (t in arrayOf(a, b, d, e)) {
                    t.merged = true
                    t.originCol = c
                    t.originRow = r
                    t.level = level
                    t.maturity = 0
                }
                events.add(CityEvent.Merged(zone, c, r))
            }
        }
    }

    private fun ready(t: Tile): Boolean = t.powered && t.roadAccess && t.watered

    private fun preMergeReset(t: Tile) {
        if (t.merged) unmergeBlock(t)
    }

    private fun updateHappiness() {
        var developed = 0
        var supplied = 0
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val t = grid[r][c]
                if (t.developed) {
                    developed++
                    if (t.powered && t.roadAccess && t.watered) supplied++
                }
            }
        }
        val coverage = if (developed == 0) 1f else supplied.toFloat() / developed
        val unemployment = if (population == 0) {
            0f
        } else {
            clamp(1f - jobs / (population * 0.55f), 0f, 1f)
        }
        val parks = parkCount
        val target = 58f +
            min(12f, parks * 1.4f) +
            coverage * 12f +
            policeCoverage * 5f +
            fireCoverage * 4f +
            healthCoverage * 5f +
            min(4f, railCount * 0.2f) -
            pollution * 45f -
            unemployment * 26f -
            if (funds < 0) 12f else 0f
        happiness = clamp(happiness + (target - happiness) * 0.12f, 0f, 100f)
    }

    private fun dailyIncome(): Float =
        population * Config.TAX_PER_RESIDENT + jobs * Config.TAX_PER_JOB

    private fun dailyExpenses(): Float =
        roadCount * Config.UPKEEP_ROAD +
            highwayCount * Config.UPKEEP_HIGHWAY +
            railCount * Config.UPKEEP_RAIL +
            pipeCount * Config.UPKEEP_PIPE +
            powerlineCount * Config.UPKEEP_POWERLINE +
            stationCount * Config.UPKEEP_STATION +
            parkCount * Config.UPKEEP_PARK +
            plantCount * Config.UPKEEP_PLANT +
            waterCount * Config.UPKEEP_WATERWORKS +
            policeCount * Config.UPKEEP_POLICE +
            fireCount * Config.UPKEEP_FIRE +
            hospitalCount * Config.UPKEEP_HOSPITAL +
            airportCount * Config.UPKEEP_AIRPORT +
            buildingCount * Config.UPKEEP_BUILDING

    private fun updateEconomy() {
        val income = dailyIncome()
        val expenses = dailyExpenses()
        val net = (income - expenses).roundToInt()
        funds += net
        monthIncome += income
        monthExpenses += expenses
        if (funds < 0 && !debtWarned) {
            debtWarned = true
            events.add(CityEvent.Notice("The city is in debt - raise income or cut upkeep"))
        }
    }

    /**
     * A day counts as stable once the city is big enough, happy enough and
     * paying its way; any bad day breaks the streak. Milestones are then
     * checked and their cash bonus paid the moment they complete.
     */
    private fun updateProgression() {
        val stable = population >= Config.STABLE_MIN_POPULATION &&
            happiness >= Config.STABLE_MIN_HAPPINESS &&
            netPerDay >= 0
        stableDays = if (stable) stableDays + 1 else 0
        for (milestone in Milestone.values()) {
            if (milestone in completed) continue
            if (milestoneValue(milestone) >= milestoneTarget(milestone)) {
                completed.add(milestone)
                funds += milestone.cash
                events.add(CityEvent.MilestoneReached(milestone))
            }
        }
    }

    private fun advanceDate() {
        day++
        if (day > Config.DAYS_PER_MONTH) {
            val finishedMonth = month
            val finishedYear = year
            day = 1
            month++
            if (month > 12) {
                month = 1
                year++
            }
            val net = (monthIncome - monthExpenses).roundToInt()
            events.add(CityEvent.MonthFinished(finishedMonth, finishedYear, net, population))
            monthIncome = 0f
            monthExpenses = 0f
            debtWarned = false
        }
    }

    // ------------------------------------------------------------------ stats

    fun capacityOf(zone: ZoneType, level: Int): Int {
        if (level <= 0) return 0
        val idx = (level - 1).coerceIn(0, Config.MAX_LEVEL - 1)
        return when (zone) {
            ZoneType.RESIDENTIAL -> Config.RES_CAPACITY[idx]
            ZoneType.COMMERCIAL -> Config.COM_CAPACITY[idx]
            ZoneType.INDUSTRIAL -> Config.IND_CAPACITY[idx]
            else -> 0
        }
    }

    private fun capacityTotal(zone: ZoneType): Int {
        var total = 0
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val t = grid[r][c]
                if (t.zone == zone && t.level > 0) total += capacityForTile(t)
            }
        }
        return total
    }

    /** Room in one lot, including the bonus that comes with a merged block. */
    fun capacityForTile(t: Tile): Int {
        val base = capacityOf(t.zone, t.level)
        if (base == 0) return 0
        return if (t.merged && t.zone.isZone) {
            (base * Config.MERGE_CAPACITY_BONUS).roundToInt()
        } else {
            base
        }
    }

    /**
     * Cellular view of a tile: how many of its eight Moore neighbours are
     * inhabited, zoned or built on. The three counts mirror the classic
     * live/dead/empty neighbour tallies a cellular-automata player watches.
     */
    data class NeighborCounts(val developed: Int, val zoned: Int, val built: Int, val total: Int = 8)

    fun neighborCounts(col: Int, row: Int): NeighborCounts {
        var developed = 0
        var zoned = 0
        var built = 0
        for (dr in -1..1) {
            for (dc in -1..1) {
                if (dc == 0 && dr == 0) continue
                val t = tile(col + dc, row + dr) ?: continue
                if (t.developed) developed++
                if (t.zone != ZoneType.NONE) {
                    zoned++
                    built++
                } else if (t.pipe || t.powerline) {
                    built++
                }
            }
        }
        return NeighborCounts(developed, zoned, built)
    }

    /**
     * Rough money this tile contributes each tick (a day): its own tax take
     * minus the upkeep its structure costs. Negative means the tile is a drain.
     */
    fun incomePerTick(t: Tile): Float {
        val tax = t.population * Config.TAX_PER_RESIDENT + t.jobs * Config.TAX_PER_JOB
        val upkeep = when (t.zone) {
            ZoneType.ROAD -> Config.UPKEEP_ROAD
            ZoneType.HIGHWAY -> Config.UPKEEP_HIGHWAY
            ZoneType.RAIL -> Config.UPKEEP_RAIL
            ZoneType.PARK -> Config.UPKEEP_PARK
            ZoneType.PLANT -> Config.UPKEEP_PLANT
            ZoneType.WATERWORKS -> Config.UPKEEP_WATERWORKS
            ZoneType.POLICE -> Config.UPKEEP_POLICE
            ZoneType.FIRE -> Config.UPKEEP_FIRE
            ZoneType.HOSPITAL -> Config.UPKEEP_HOSPITAL
            ZoneType.STATION -> Config.UPKEEP_STATION
            ZoneType.AIRPORT -> Config.UPKEEP_AIRPORT
            ZoneType.RESIDENTIAL,
            ZoneType.COMMERCIAL,
            ZoneType.INDUSTRIAL -> if (t.level > 0) Config.UPKEEP_BUILDING else 0f
            else -> 0f
        } + (if (t.pipe) Config.UPKEEP_PIPE else 0f) +
            (if (t.powerline) Config.UPKEEP_POWERLINE else 0f)
        return tax - upkeep
    }

    /** A one-word summary of what a tile is doing right now. */
    fun tileState(t: Tile): String = when {
        t.terrain == Terrain.WATER && t.zone.isRoad -> "Bridge"
        t.terrain == Terrain.WATER -> "Water"
        t.merged && t.zone.isZone -> "${t.zone.label} block (L${t.level})"
        t.developed -> "${t.zone.label} L${t.level} · ${t.population + t.jobs} inside"
        t.zone.isZone -> "Vacant ${t.zone.label.lowercase()}"
        t.zone != ZoneType.NONE -> t.zone.label
        t.terrain == Terrain.FOREST -> "Forest"
        else -> "Grass"
    }

    private inline fun countTiles(predicate: (Tile) -> Boolean): Int {
        var n = 0
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                if (predicate(grid[r][c])) n++
            }
        }
        return n
    }

    private fun countOrigins(zone: ZoneType): Int {
        var n = 0
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val t = grid[r][c]
                if (t.zone == zone && t.merged && t.originCol == c && t.originRow == r) n++
            }
        }
        return n
    }

    private fun countBlockOrigins(predicate: (Tile) -> Boolean): Int {
        var n = 0
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val t = grid[r][c]
                if (t.merged && t.originCol == c && t.originRow == r && predicate(t)) n++
            }
        }
        return n
    }

    // ------------------------------------------------------------------ save

    fun save(): String {
        val sb = StringBuilder(4096)
        sb.append("CC5|")
        sb.append(funds).append('|')
        sb.append(day).append('|')
        sb.append(month).append('|')
        sb.append(year).append('|')
        sb.append(String.format(java.util.Locale.US, "%.2f", happiness)).append('|')
        sb.append(String.format(java.util.Locale.US, "%.3f", pollution)).append('|')
        sb.append(seedValue).append('|')
        sb.append(completed.joinToString(",") { it.name }).append('|')
        sb.append(stableDays).append('|')
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                if (c != 0 || r != 0) sb.append(';')
                val t = grid[r][c]
                sb.append(t.terrain.ordinal).append(',')
                sb.append(t.zone.ordinal).append(',')
                sb.append(t.level).append(',')
                sb.append(t.population).append(',')
                sb.append(t.jobs).append(',')
                sb.append(t.maturity).append(',')
                sb.append(t.variant).append(',')
                sb.append(t.distress).append(',')
                sb.append(if (t.merged) 1 else 0).append(',')
                sb.append(t.originCol).append(',')
                sb.append(t.originRow).append(',')
                sb.append(if (t.pipe) 1 else 0).append(',')
                sb.append(if (t.powerline) 1 else 0)
            }
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------ load

    companion object {
        private val MONTHS = arrayOf(
            "Jan", "Feb", "Mar", "Apr", "May", "Jun",
            "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
        )
        private val DC = intArrayOf(-1, 1, 0, 0)
        private val DR = intArrayOf(0, 0, -1, 1)

        fun load(data: String?): CityEngine? {
            if (data.isNullOrBlank()) return null
            val parts = data.split('|')
            if (parts.size < 9) return null
            val version = parts[0]
            if (version != "CC1" && version != "CC2" && version != "CC3" &&
                version != "CC4" && version != "CC5"
            ) {
                return null
            }
            val mergedSaves = version != "CC1"
            val layeredPipes = version == "CC3" || version == "CC4" || version == "CC5"
            val layeredWires = version == "CC4" || version == "CC5"
            val progressive = version == "CC5"
            if (progressive && parts.size != 11) return null
            return try {
                val w = Config.COLS
                val h = Config.ROWS
                val engine = CityEngine(w, h, parts[7].toLongOrNull() ?: 1L)
                // Pre-progression saves never had locked tools; keep them all open.
                engine.legacyUnlockedAll = !progressive
                val tilePart = parts[if (progressive) 10 else 8]
                if (progressive) {
                    if (parts[8].isNotEmpty()) {
                        for (name in parts[8].split(',')) {
                            val milestone = Milestone.values().firstOrNull { it.name == name }
                                ?: return null
                            engine.completed.add(milestone)
                        }
                    }
                    engine.stableDays = parts[9].toInt().coerceAtLeast(0)
                }
                engine.funds = parts[1].toInt()
                engine.day = parts[2].toInt().coerceIn(1, Config.DAYS_PER_MONTH)
                engine.month = parts[3].toInt().coerceIn(1, 12)
                engine.year = parts[4].toInt().coerceIn(1900, 3000)
                val happy = parts[5].toFloat()
                val dirty = parts[6].toFloat()
                if (!happy.isFinite() || !dirty.isFinite()) return null
                engine.happiness = happy.coerceIn(0f, 100f)
                engine.pollution = dirty.coerceIn(0f, 1f)
                val tiles = tilePart.split(';')
                if (tiles.size != w * h) return null
                var pop = 0
                var job = 0
                for (idx in tiles.indices) {
                    val f = tiles[idx].split(',')
                    if (f.size < 8) return null
                    if (mergedSaves && f.size < 11) return null
                    if (layeredPipes && f.size < 12) return null
                    if (layeredWires && f.size < 13) return null
                    val t = engine.grid[idx / w][idx % w]
                    val terrain = f[0].toInt()
                    val zone = f[1].toInt()
                    if (terrain !in 0 until Terrain.values().size) return null
                    if (zone !in 0 until ZoneType.values().size) return null
                    t.terrain = Terrain.values()[terrain]
                    t.zone = ZoneType.values()[zone]
                    t.level = f[2].toInt().coerceIn(0, Config.MAX_LEVEL)
                    t.population = f[3].toInt().coerceAtLeast(0)
                    t.jobs = f[4].toInt().coerceAtLeast(0)
                    t.maturity = f[5].toInt().coerceAtLeast(0)
                    t.variant = f[6].toInt().coerceIn(0, 3)
                    t.distress = f[7].toInt().coerceAtLeast(0)
                    if (mergedSaves) {
                        t.merged = f[8].toInt() != 0
                        t.originCol = f[9].toInt()
                        t.originRow = f[10].toInt()
                    }
                    if (layeredPipes) {
                        t.pipe = f[11].toInt() != 0
                    } else if (t.zone == ZoneType.PIPE) {
                        // CC1/CC2 stored mains as a zone; lift them into the layer
                        t.pipe = true
                        t.zone = ZoneType.NONE
                    }
                    if (layeredWires) t.powerline = f[12].toInt() != 0
                    pop += t.population
                    job += t.jobs
                }
                engine.population = pop
                engine.jobs = job
                engine.refreshInfrastructure()
                engine.updateDemand()
                engine
            } catch (e: Exception) {
                null
            }
        }
    }

    // ------------------------------------------------------------------ terrain

    private fun generateLandscape() {
        // A river down the west side, crossable only by expensive bridges.
        var c = max(2, cols / 4)
        for (r in 0 until rows) {
            c = (c + rng.nextInt(-1, 2)).coerceIn(2, max(2, cols / 3))
            setTerrain(c, r, Terrain.WATER)
            setTerrain(c + 1, r, Terrain.WATER)
        }

        // A lake in the south-east.
        val lakeRow = (rows * 0.72f).toInt()
        val lakeCol = (cols * 0.75f).toInt()
        val lakeRadius = 3
        for (r in lakeRow - lakeRadius..lakeRow + lakeRadius) {
            for (col in lakeCol - lakeRadius..lakeCol + lakeRadius) {
                if (!inBounds(col, r)) continue
                val dx = (col - lakeCol).toFloat() / (lakeRadius + 1)
                val dy = (r - lakeRow).toFloat() / lakeRadius
                if (dx * dx + dy * dy <= 1.0f + rng.nextFloat() * 0.22f) {
                    setTerrain(col, r, Terrain.WATER)
                }
            }
        }

        // Forest clumps, kept away from the starting clearing.
        val spawnCol = cols / 2
        val spawnRow = rows / 2
        repeat(7) {
            var fr = rng.nextInt(1, rows - 1)
            var fc = rng.nextInt(1, cols - 1)
            if (abs(fr - spawnRow) < 5 && abs(fc - spawnCol) < 5) {
                fr = (fr + 9) % rows
                fc = (fc + 9) % cols
            }
            repeat(rng.nextInt(3, 9)) {
                setForest(fc, fr)
                fc += rng.nextInt(-1, 2)
                fr += rng.nextInt(-1, 2)
                fc = fc.coerceIn(0, cols - 1)
                fr = fr.coerceIn(0, rows - 1)
            }
        }

        // A handful of lone trees for texture.
        repeat(cols + rows) {
            val fc = rng.nextInt(0, cols)
            val fr = rng.nextInt(0, rows)
            if (abs(fr - spawnRow) > 3 || abs(fc - spawnCol) > 3) setForest(fc, fr)
        }

        // Guaranteed flat, dry starting square.
        for (r in spawnRow - 2..spawnRow + 2) {
            for (col in spawnCol - 2..spawnCol + 2) {
                if (inBounds(col, r)) {
                    grid[r][col].terrain = Terrain.GRASS
                }
            }
        }
    }

    private fun setTerrain(col: Int, row: Int, terrain: Terrain) {
        if (inBounds(col, row)) grid[row][col].terrain = terrain
    }

    private fun setForest(col: Int, row: Int) {
        if (!inBounds(col, row)) return
        if (grid[row][col].terrain == Terrain.GRASS) grid[row][col].terrain = Terrain.FOREST
    }

    private fun clamp(v: Float, lo: Float, hi: Float): Float = max(lo, min(hi, v))
}
