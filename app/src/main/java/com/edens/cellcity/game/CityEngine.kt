package com.edens.cellcity.game

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
    private val enforceUnlocks: Boolean = true,
    difficultyLevel: Difficulty = Difficulty.NORMAL,
    mapStyleLevel: MapStyle = MapStyle.RIVERLANDS
) {
    private val seedValue: Long = seed
    private val rng = Random(seed)
    /** Separate stream for cards and contracts, so the map's own rolls stay stable. */
    private val eventRng = Random(seed + 7_919L)
    private val grid: Array<Array<Tile>> = Array(rows) { Array(cols) { Tile() } }
    private val events = ArrayList<CityEvent>()

    var difficulty: Difficulty = difficultyLevel
        private set

    var mapStyle: MapStyle = mapStyleLevel
        private set

    var funds: Int = difficulty.startFunds
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

    /**
     * What citizens pay, in percent. 10 is the baseline the economy was tuned
     * around; raising it fills the treasury but sours the mood.
     */
    var taxRate: Int = Config.TAX_DEFAULT
        private set

    /** Share of developed lots covered by each emergency service, 0..1. */
    var policeCoverage: Float = 0f
        private set
    var fireCoverage: Float = 0f
        private set
    var healthCoverage: Float = 0f
        private set

    /** Buildings currently burning, and buildings carrying a crime mark. */
    var activeFires: Int = 0
        private set
    var activeCrime: Int = 0
        private set

    /** Built lots that no fire or police station reaches yet: the next victims. */
    val fireRiskLots: Int get() = countTiles { it.developed && !it.fireCovered }
    val crimeRiskLots: Int get() = countTiles { it.developed && !it.policeCovered }

    private var monthIncome = 0f
    private var monthExpenses = 0f
    private var debtWarned = false
    /** Last crime warning stage, so the city is not spammed every day. */
    private var crimeStage = 0

    private val records = ArrayList<CityRecord>()

    /** Closing snapshot of every month so far, oldest first: the city's record. */
    val history: List<CityRecord> get() = records

    // --- decisions, contracts and stakes -----------------------------------
    /** Days until the next decision card is drawn. */
    var decisionCooldown: Int = Config.DECISION_FIRST_DAYS
        private set

    /** The card waiting for the mayor. A live prompt, so it is not saved. */
    var pendingDecision: PendingDecision? = null
        private set

    private var lastDecisionKind: DecisionKind? = null

    /** The region's current side objective, or null between contracts. */
    var contract: Contract? = null
        private set
    var contractCooldown: Int = 0
        private set

    /** Consecutive days the treasury has been in the red. */
    var debtDays: Int = 0
        private set
    var bankrupt: Boolean = false
        private set
    var bailouts: Int = 0
        private set
    /** Emergency loan still to be repaid, a little each day. */
    var loanBalance: Int = 0
        private set
    /** Permanent smog added by decisions such as a factory permit, 0..1. */
    var pollutionOffset: Float = 0f
        private set

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
        records.add(
            CityRecord(
                month = month,
                year = year,
                population = 0,
                jobs = 0,
                funds = funds,
                happiness = happiness.roundToInt().coerceIn(0, 100),
                pollution = 0,
                income = 0,
                expenses = 0
            )
        )
        contract = offerContract()
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
    val subwayCount: Int get() = countTiles { it.subway }
    val subwayStationCount: Int get() = countTiles { it.zone == ZoneType.SUBWAY_STATION }
    val parkCount: Int get() = countTiles { it.zone == ZoneType.PARK }
    /** Every power plant, whatever its fuel. */
    val plantCount: Int get() = countTiles { it.zone.isPowerPlant }
    val fossilPlantCount: Int get() = countTiles { it.zone == ZoneType.PLANT }
    val solarCount: Int get() = countTiles { it.zone == ZoneType.SOLAR }
    val windCount: Int get() = countTiles { it.zone == ZoneType.WIND }
    val nuclearCount: Int get() = countTiles { it.zone == ZoneType.NUCLEAR }
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

    /** Vacant zoned lots of one type: zoned but never built on. */
    fun vacantCount(zone: ZoneType): Int = countTiles { it.zone == zone && it.level == 0 }
    val mergedBlockCount: Int get() = countBlockOrigins { it.zone.isZone }

    val developedHousing: Int
        get() = countTiles { it.zone == ZoneType.RESIDENTIAL && it.level > 0 }

    val netPerDay: Int
        get() = (dailyIncome() - dailyExpenses()).roundToInt()

    /** Tax money the city takes in each day at the current rate. */
    val incomePerDay: Float get() = dailyIncome()

    /** What the city pays each day to keep everything running. */
    val expensesPerDay: Float get() = dailyExpenses()

    val dateLabel: String
        get() = "${MONTHS[(month - 1).coerceIn(0, 11)]} $year"

    /** Days since the first day of the city; contract deadlines use it. */
    val totalDays: Int
        get() = (year - Config.START_YEAR) * 12 * Config.DAYS_PER_MONTH +
            (month - 1) * Config.DAYS_PER_MONTH + (day - 1)

    /** One number for how well the city is doing, used by medals and awards. */
    val cityScore: Int
        get() = population + (happiness * 8f).roundToInt() + completed.size * 500 +
            parkCount * 40 + jobs / 2

    /** 0..4: how many score thresholds the city has passed. */
    val medalCount: Int
        get() = SCORE_MEDALS.count { cityScore >= it }

    val medalName: String
        get() = when (medalCount) {
            0 -> "No medal"
            1 -> "Bronze"
            2 -> "Silver"
            3 -> "Gold"
            else -> "Platinum"
        }

    // ------------------------------------------------------------- progression

    /**
     * Moves the tax rate within [Config.TAX_MIN]..[Config.TAX_MAX]. Returns true
     * when the rate actually changed, so callers know whether to redraw.
     */
    fun setTaxRate(rate: Int): Boolean {
        val next = rate.coerceIn(Config.TAX_MIN, Config.TAX_MAX)
        if (next == taxRate) return false
        taxRate = next
        updateHappiness()
        return true
    }

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

    // ---------------------------------------------------------------- contracts

    /** How far the active contract has come, in its own units. */
    fun contractProgress(c: Contract): Int = when (c.kind) {
        ContractKind.POPULATION -> population
        ContractKind.TREASURY -> funds
        ContractKind.FIRE_COVER -> (fireCoverage * 100f).roundToInt()
        ContractKind.POLICE_COVER -> (policeCoverage * 100f).roundToInt()
        ContractKind.HAPPINESS -> happiness.roundToInt()
        ContractKind.PARKS -> parkCount
    }

    /** Days before the contract lapses; negative once it is overdue. */
    fun contractDaysLeft(c: Contract): Int = c.deadlineDay - totalDays

    /** Picks a contract that asks for something the city does not have yet. */
    private fun offerContract(): Contract {
        val pool = ArrayList<ContractKind>()
        if (population < 250) pool.add(ContractKind.POPULATION)
        // only ask for cover the city can actually buy: the tools unlock over time
        if (fireCoverage < 0.7f && isUnlocked(Tool.FIRE)) pool.add(ContractKind.FIRE_COVER)
        if (policeCoverage < 0.7f && isUnlocked(Tool.POLICE)) {
            pool.add(ContractKind.POLICE_COVER)
        }
        if (funds in 0..11_999) pool.add(ContractKind.TREASURY)
        if (happiness < 68f) pool.add(ContractKind.HAPPINESS)
        if (parkCount < 4) pool.add(ContractKind.PARKS)
        if (pool.isEmpty()) pool.add(ContractKind.POPULATION)
        val kind = pool[eventRng.nextInt(pool.size)]
        val target = when (kind) {
            ContractKind.POPULATION -> max(20, population + max(40, population / 3))
            ContractKind.TREASURY -> max(2_000, funds + 3_000)
            ContractKind.FIRE_COVER,
            ContractKind.POLICE_COVER -> 70
            ContractKind.HAPPINESS -> min(100, happiness.roundToInt() + 8)
            ContractKind.PARKS -> parkCount + 3
        }
        val reward = when (kind) {
            ContractKind.POPULATION, ContractKind.TREASURY -> 900
            ContractKind.FIRE_COVER, ContractKind.POLICE_COVER -> 700
            ContractKind.HAPPINESS, ContractKind.PARKS -> 800
        }
        return Contract(kind, target, totalDays + Config.CONTRACT_DAYS, reward, totalDays)
    }

    private fun updateContracts() {
        if (contractCooldown > 0) contractCooldown--
        val active = contract
        if (active == null) {
            if (contractCooldown <= 0) contract = offerContract()
            return
        }
        if (contractProgress(active) >= active.target) {
            funds += active.reward
            happiness = min(100f, happiness + 3f)
            events.add(CityEvent.ContractComplete(active))
            contract = null
            contractCooldown = Config.CONTRACT_COOLDOWN_DAYS
        } else if (totalDays > active.deadlineDay) {
            events.add(CityEvent.ContractExpired(active))
            contract = null
            contractCooldown = Config.CONTRACT_COOLDOWN_DAYS
        }
    }

    // ---------------------------------------------------------------- decisions

    /** Draws a decision card once the cooldown runs out and the city is big enough. */
    private fun updateDecisions() {
        if (pendingDecision != null) return
        if (decisionCooldown > 0) {
            decisionCooldown--
            return
        }
        if (population < Config.DECISION_MIN_POPULATION) {
            decisionCooldown = 5
            return
        }
        val eligible = ArrayList<DecisionKind>()
        if (funds < 12_000) eligible.add(DecisionKind.GRANT)
        if (population >= 100) eligible.add(DecisionKind.FACTORY_PERMIT)
        if (funds >= 900) eligible.add(DecisionKind.FESTIVAL)
        if (population >= 60) eligible.add(DecisionKind.HEAT_WAVE)
        if (taxRate >= 15) eligible.add(DecisionKind.TAX_REVOLT)
        if (plantCount > 0 && population >= 50) eligible.add(DecisionKind.GRID_FAILURE)
        eligible.remove(lastDecisionKind)
        if (eligible.isEmpty()) {
            decisionCooldown = 10
            return
        }
        val kind = eligible[eventRng.nextInt(eligible.size)]
        lastDecisionKind = kind
        pendingDecision = buildDecision(kind)
        decisionCooldown = Config.DECISION_GAP_MIN +
            eventRng.nextInt(Config.DECISION_GAP_MAX - Config.DECISION_GAP_MIN + 1)
        events.add(CityEvent.DecisionOffered(pendingDecision!!))
    }

    private fun buildDecision(kind: DecisionKind): PendingDecision = when (kind) {
        DecisionKind.GRANT -> PendingDecision(
            kind,
            "A regional grant",
            "The province offers $2,000 for city hall, on the understanding that the money is spent their way.",
            listOf(
                DecisionOption("Take the grant", "+$2,000 now, but the strings cost 2 happiness"),
                DecisionOption("Refuse it", "No money, +4 happiness for standing independent")
            )
        )

        DecisionKind.FACTORY_PERMIT -> PendingDecision(
            kind,
            "A factory wants a permit",
            "An industrial firm will build on the edge of town. The jobs and fees are real; so is the smoke.",
            listOf(
                DecisionOption("Approve the permit", "+$2,500, permanent smog, -4 happiness"),
                DecisionOption("Turn it down", "+3 happiness, no smog, no fee")
            )
        )

        DecisionKind.FESTIVAL -> PendingDecision(
            kind,
            "A festival in the park",
            "Organisers ask the city to underwrite a weekend festival. It would lift the mood - at a price.",
            listOf(
                DecisionOption("Fund the festival", "-$700, +9 happiness"),
                DecisionOption("Let it pass", "Save the money, -2 happiness")
            )
        )

        DecisionKind.HEAT_WAVE -> PendingDecision(
            kind,
            "A heat wave",
            "The forecast is brutal. The city can open cooling centres, or leave people to cope.",
            listOf(
                DecisionOption("Open cooling centres", "-$500, +3 happiness"),
                DecisionOption("Ride it out", "-7 happiness")
            )
        )

        DecisionKind.TAX_REVOLT -> PendingDecision(
            kind,
            "Taxpayers are revolting",
            "At ${taxRate}% the tax rate has become the talk of every shop counter in town.",
            listOf(
                DecisionOption("Cut taxes by 4%", "Lower income, +4 happiness"),
                DecisionOption("Hold the line", "Keep the income, -8 happiness")
            )
        )

        DecisionKind.GRID_FAILURE -> PendingDecision(
            kind,
            "The grid is straining",
            "A transformer is failing. Crews can fix it now, or the city can gamble on it holding.",
            listOf(
                DecisionOption("Repair it now", "-$900, no blackout"),
                DecisionOption("Wait and see", "Keep the money, -6 happiness")
            )
        )
    }

    /**
     * Applies the mayor's choice and returns a line for the banner, or null when
     * there is no card waiting (or the choice is out of range).
     */
    fun resolveDecision(index: Int): String? {
        val card = pendingDecision ?: return null
        if (index !in card.options.indices) return null
        pendingDecision = null
        return when (card.kind) {
            DecisionKind.GRANT -> if (index == 0) {
                funds += 2_000
                happiness = (happiness - 2f).coerceAtLeast(0f)
                "Grant accepted: +$2,000, -2 happiness"
            } else {
                happiness = min(100f, happiness + 4f)
                "Grant refused: citizens like the independence"
            }

            DecisionKind.FACTORY_PERMIT -> if (index == 0) {
                funds += 2_500
                pollutionOffset = (pollutionOffset + 0.10f).coerceAtMost(1f)
                happiness = (happiness - 4f).coerceAtLeast(0f)
                "Permit approved: +$2,500, but the air got worse"
            } else {
                happiness = min(100f, happiness + 3f)
                "Permit refused: the neighbourhood breathes easier"
            }

            DecisionKind.FESTIVAL -> if (index == 0) {
                funds -= 700
                happiness = min(100f, happiness + 9f)
                "Festival funded: the city is in a fine mood"
            } else {
                happiness = (happiness - 2f).coerceAtLeast(0f)
                "No festival: a quiet weekend, a few grumbles"
            }

            DecisionKind.HEAT_WAVE -> if (index == 0) {
                funds -= 500
                happiness = min(100f, happiness + 3f)
                "Cooling centres open: people are grateful"
            } else {
                happiness = (happiness - 7f).coerceAtLeast(0f)
                "The heat wave hurts the city's mood"
            }

            DecisionKind.TAX_REVOLT -> if (index == 0) {
                setTaxRate(taxRate - 4)
                happiness = min(100f, happiness + 4f)
                "Taxes cut to ${taxRate}%: the street calms down"
            } else {
                happiness = (happiness - 8f).coerceAtLeast(0f)
                "The rate holds at ${taxRate}%: taxpayers are furious"
            }

            DecisionKind.GRID_FAILURE -> if (index == 0) {
                funds -= 900
                "Transformer repaired: the lights stayed on"
            } else {
                happiness = (happiness - 6f).coerceAtLeast(0f)
                "The grid held - barely - and citizens noticed the gamble"
            }
        }
    }

    // ------------------------------------------------------------------ build

    fun baseCost(tool: Tool): Int = when (tool) {
        Tool.ROAD -> Config.COST_ROAD
        Tool.HIGHWAY -> Config.COST_HIGHWAY
        Tool.RAIL -> Config.COST_RAIL
        Tool.SUBWAY -> Config.COST_SUBWAY
        Tool.STATION -> Config.COST_STATION
        Tool.SUBWAY_STATION -> Config.COST_SUBWAY_STATION
        Tool.RESIDENTIAL -> Config.COST_RESIDENTIAL
        Tool.COMMERCIAL -> Config.COST_COMMERCIAL
        Tool.INDUSTRIAL -> Config.COST_INDUSTRIAL
        Tool.PARK -> Config.COST_PARK
        Tool.PLANT -> Config.COST_PLANT
        Tool.SOLAR -> Config.COST_SOLAR
        Tool.WIND -> Config.COST_WIND
        Tool.NUCLEAR -> Config.COST_NUCLEAR
        Tool.WATER -> Config.COST_WATERWORKS
        Tool.PIPE -> Config.COST_PIPE
        Tool.POWERLINE -> Config.COST_POWERLINE
        Tool.POLICE -> Config.COST_POLICE
        Tool.FIRE -> Config.COST_FIRE
        Tool.HOSPITAL -> Config.COST_HOSPITAL
        Tool.AIRPORT -> Config.COST_AIRPORT
        Tool.RECLAIM -> Config.COST_RECLAIM
        Tool.CANAL -> Config.COST_CANAL
        Tool.BULLDOZE, Tool.PAN, Tool.INFO -> 0
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
            Tool.PIPE, Tool.POWERLINE, Tool.SUBWAY -> layerCost(tool, t.terrain)
            Tool.RECLAIM -> reclaimCost(col, row)
            Tool.CANAL -> canalCost(col, row)
            else -> baseCost(tool)
        }
    }

    fun canBuild(tool: Tool, col: Int, row: Int): BuildResult {
        if (tool == Tool.PAN || tool == Tool.INFO) return BuildResult.OK
        if (!inBounds(col, row)) return BuildResult.OUT_OF_BOUNDS
        val t = grid[row][col]
        if (tool == Tool.BULLDOZE) return BuildResult.OK
        if (!isUnlocked(tool)) return BuildResult.LOCKED
        if (tool.isLayer) return canBuildLayer(tool, col, row)
        if (tool == Tool.RECLAIM) return canReclaim(col, row)
        if (tool == Tool.CANAL) return canDig(col, row)
        if (t.zone == tool.zone) return BuildResult.OK
        if (tool == Tool.AIRPORT) return canBuildAirport(col, row)
        if (tool == Tool.STATION) return canBuildStation(col, row)
        if (tool == Tool.SUBWAY_STATION) return canBuildSubwayStation(col, row)
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
        Tool.SUBWAY -> if (terrain == Terrain.WATER) {
            Config.COST_SUBWAY_WATER
        } else {
            Config.COST_SUBWAY
        }
        else -> 0
    }

    /**
     * Dredging is shoreline work: the cell has to be water, and it has to touch
     * dry land (or land raised earlier) so the new ground joins the island.
     */
    private fun canReclaim(col: Int, row: Int): BuildResult {
        if (grid[row][col].terrain != Terrain.WATER) return BuildResult.NOT_WATER
        if (!touchesShore(col, row)) return BuildResult.NO_SHORE
        if (funds < reclaimCost(col, row)) return BuildResult.NO_FUNDS
        return BuildResult.OK
    }

    private fun touchesShore(col: Int, row: Int): Boolean {
        for (d in 0 until 4) {
            val c = col + DC[d]
            val r = row + DR[d]
            if (!inBounds(c, r)) continue
            if (grid[r][c].terrain != Terrain.WATER) return true
        }
        return false
    }

    /**
     * Filling a shallow waterfront cell is cheap; every water cell still beside
     * the plot adds a surcharge, so a causeway into the middle of the river
     * costs more than widening the bank.
     */
    private fun reclaimCost(col: Int, row: Int): Int {
        var water = 0
        for (d in 0 until 4) {
            val c = col + DC[d]
            val r = row + DR[d]
            if (inBounds(c, r) && grid[r][c].terrain == Terrain.WATER) water++
        }
        return Config.COST_RECLAIM + water * Config.COST_RECLAIM_DEEP
    }

    /**
     * Canals are shoreline work too, only in reverse: the cell has to be empty
     * dry land, and it has to touch water so the new channel joins the river.
     */
    private fun canDig(col: Int, row: Int): BuildResult {
        val t = grid[row][col]
        if (t.terrain == Terrain.WATER) return BuildResult.ALREADY_WATER
        if (t.zone != ZoneType.NONE) return BuildResult.OCCUPIED
        if (t.pipe || t.powerline || t.subway) return BuildResult.LAYERED
        if (!touchesWater(col, row)) return BuildResult.NO_WATER_EDGE
        if (funds < canalCost(col, row)) return BuildResult.NO_FUNDS
        return BuildResult.OK
    }

    private fun touchesWater(col: Int, row: Int): Boolean {
        for (d in 0 until 4) {
            val c = col + DC[d]
            val r = row + DR[d]
            if (!inBounds(c, r)) continue
            if (grid[r][c].terrain == Terrain.WATER) return true
        }
        return false
    }

    /** A bank cell is cheap to dig; cutting deeper into the land costs more. */
    private fun canalCost(col: Int, row: Int): Int {
        var land = 0
        for (d in 0 until 4) {
            val c = col + DC[d]
            val r = row + DR[d]
            if (inBounds(c, r) && grid[r][c].terrain != Terrain.WATER) land++
        }
        return Config.COST_CANAL + land * Config.COST_CANAL_LAND
    }

    /**
     * Works out what filling a rectangle of cells would raise, outward from the
     * shore, and what it would cost. Nothing is changed, so the UI can show the
     * quote while the player drags and only spend the money on release.
     */
    fun planFill(col1: Int, row1: Int, col2: Int, row2: Int): FillPlan {
        if (!inBounds(col1, row1) || !inBounds(col2, row2)) {
            return FillPlan(emptyList(), 0, BuildResult.OUT_OF_BOUNDS)
        }
        val left = min(col1, col2)
        val right = max(col1, col2)
        val top = min(row1, row2)
        val bottom = max(row1, row2)
        val wet = BooleanArray(rows * cols) {
            grid[it / cols][it % cols].terrain == Terrain.WATER
        }
        var waterInRect = 0
        for (r in top..bottom) {
            for (c in left..right) {
                if (wet[r * cols + c]) waterInRect++
            }
        }
        if (waterInRect == 0) return FillPlan(emptyList(), 0, BuildResult.NOT_WATER)

        val cells = ArrayList<Int>(waterInRect)
        var cost = 0
        val queue = ArrayDeque<Int>()
        for (r in top..bottom) {
            for (c in left..right) {
                val index = r * cols + c
                if (wet[index] && touchesLandIn(wet, c, r)) queue.add(index)
            }
        }
        while (queue.isNotEmpty()) {
            val index = queue.removeFirst()
            if (!wet[index]) continue
            val c = index % cols
            val r = index / cols
            wet[index] = false
            cells.add(index)
            cost += Config.COST_RECLAIM +
                waterNeighboursIn(wet, c, r) * Config.COST_RECLAIM_DEEP
            for (d in 0 until 4) {
                val nc = c + DC[d]
                val nr = r + DR[d]
                if (nc < left || nc > right || nr < top || nr > bottom) continue
                val next = nr * cols + nc
                if (wet[next]) queue.add(next)
            }
        }
        if (cells.isEmpty()) return FillPlan(emptyList(), 0, BuildResult.NO_SHORE)
        if (funds < cost) return FillPlan(cells, cost, BuildResult.NO_FUNDS)
        return FillPlan(cells, cost, BuildResult.OK)
    }

    /** Raises every cell the plan can reach, as one undoable action. */
    fun fillArea(col1: Int, row1: Int, col2: Int, row2: Int): FillPlan {
        val plan = planFill(col1, row1, col2, row2)
        if (plan.result != BuildResult.OK) return plan
        val fundsBefore = funds
        val first = plan.cells.first()
        for (index in plan.cells) {
            val c = index % cols
            val r = index / cols
            snapshotForUndo(listOf(index), Tool.RECLAIM)
            funds -= reclaimCost(c, r)
            val t = grid[r][c]
            // The water goes away for good: no later bulldoze turns it back.
            t.terrain = Terrain.GRASS
            t.reclaimed = true
        }
        events.add(CityEvent.Built(ZoneType.NONE, first % cols, first / cols))
        accrueActionCost(fundsBefore)
        refreshInfrastructure()
        return plan
    }

    private fun touchesLandIn(wet: BooleanArray, col: Int, row: Int): Boolean {
        for (d in 0 until 4) {
            val c = col + DC[d]
            val r = row + DR[d]
            if (!inBounds(c, r)) continue
            if (!wet[r * cols + c]) return true
        }
        return false
    }

    private fun waterNeighboursIn(wet: BooleanArray, col: Int, row: Int): Int {
        var water = 0
        for (d in 0 until 4) {
            val c = col + DC[d]
            val r = row + DR[d]
            if (inBounds(c, r) && wet[r * cols + c]) water++
        }
        return water
    }

    /** Mains, cables and tunnels are layers: they fit under any existing zone or building. */
    private fun canBuildLayer(tool: Tool, col: Int, row: Int): BuildResult {
        val t = grid[row][col]
        val laid = when (tool) {
            Tool.PIPE -> t.pipe
            Tool.POWERLINE -> t.powerline
            Tool.SUBWAY -> t.subway
            else -> false
        }
        if (laid) return BuildResult.OK
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

    /** A subway station needs a live tunnel running under it or right beside it. */
    private fun canBuildSubwayStation(col: Int, row: Int): BuildResult {
        if (grid[row][col].zone != ZoneType.NONE) return BuildResult.OCCUPIED
        if (grid[row][col].terrain == Terrain.WATER) return BuildResult.WATER
        if (!subwayLineAt(col, row)) return BuildResult.NO_SUBWAY
        if (funds < Config.COST_SUBWAY_STATION) return BuildResult.NO_FUNDS
        return BuildResult.OK
    }

    /** True when a connected tunnel runs under or beside this tile. */
    private fun subwayLineAt(col: Int, row: Int): Boolean {
        if (grid[row][col].subwayConnected) return true
        if (col > 0 && grid[row][col - 1].subwayConnected) return true
        if (col < cols - 1 && grid[row][col + 1].subwayConnected) return true
        if (row > 0 && grid[row - 1][col].subwayConnected) return true
        if (row < rows - 1 && grid[row + 1][col].subwayConnected) return true
        return false
    }

    fun build(tool: Tool, col: Int, row: Int): BuildResult {
        val check = canBuild(tool, col, row)
        if (check != BuildResult.OK) return check
        if (tool == Tool.PAN || tool == Tool.INFO) return BuildResult.OK
        if (!inBounds(col, row)) return BuildResult.OUT_OF_BOUNDS

        val fundsBefore = funds
        val t = grid[row][col]
        if (tool == Tool.BULLDOZE) {
            if (t.zone == ZoneType.NONE && !t.pipe && !t.powerline && !t.subway) {
                return BuildResult.OK
            }
            snapshotForUndo(blockIndices(col, row, t), tool)
            val block = blockTiles(t)
            val wasAirport = t.zone == ZoneType.AIRPORT
            var value = 0f
            for (b in block) {
                value += investedCost(b)
                if (b.pipe) value += layerCost(Tool.PIPE, b.terrain)
                if (b.powerline) value += layerCost(Tool.POWERLINE, b.terrain)
                if (b.subway) value += layerCost(Tool.SUBWAY, b.terrain)
            }
            if (wasAirport) {
                value = Config.COST_AIRPORT.toFloat()
                for (b in block) {
                    if (b.pipe) value += layerCost(Tool.PIPE, b.terrain)
                    if (b.powerline) value += layerCost(Tool.POWERLINE, b.terrain)
                    if (b.subway) value += layerCost(Tool.SUBWAY, b.terrain)
                }
            }
            funds += (value * Config.BULLDOZE_REFUND).roundToInt()
            for (b in block) {
                b.clear()
                if (b.terrain == Terrain.FOREST) b.terrain = Terrain.GRASS
            }
            accrueActionCost(fundsBefore)
            refreshInfrastructure()
            return BuildResult.OK
        }
        if (tool.isLayer) {
            val laid = when (tool) {
                Tool.PIPE -> t.pipe
                Tool.POWERLINE -> t.powerline
                Tool.SUBWAY -> t.subway
                else -> false
            }
            if (laid) return BuildResult.OK
            snapshotForUndo(listOf(row * cols + col), tool)
            funds -= layerCost(tool, t.terrain)
            when (tool) {
                Tool.PIPE -> t.pipe = true
                Tool.POWERLINE -> t.powerline = true
                Tool.SUBWAY -> t.subway = true
                else -> Unit
            }
            events.add(CityEvent.Built(tool.zone, col, row))
            accrueActionCost(fundsBefore)
            refreshInfrastructure()
            return BuildResult.OK
        }
        if (tool == Tool.RECLAIM) return fillArea(col, row, col, row).result
        if (tool == Tool.CANAL) {
            snapshotForUndo(listOf(row * cols + col), tool)
            funds -= canalCost(col, row)
            t.terrain = Terrain.WATER
            t.reclaimed = false
            events.add(CityEvent.Built(ZoneType.NONE, col, row))
            accrueActionCost(fundsBefore)
            refreshInfrastructure()
            return BuildResult.OK
        }
        if (t.zone == tool.zone) return BuildResult.OK
        if (tool == Tool.AIRPORT) return buildAirport(col, row, fundsBefore)

        snapshotForUndo(listOf(row * cols + col), tool)
        val cost = costOf(tool, col, row)
        funds -= cost
        t.zone = tool.zone
        t.variant = rng.nextInt(4)
        if (t.terrain == Terrain.FOREST) t.terrain = Terrain.GRASS
        if (tool == Tool.PARK || tool == Tool.PLANT || tool.zone.isService) t.level = 1
        events.add(CityEvent.Built(t.zone, col, row))
        if (tool == Tool.PARK) mergeParkBlock(col, row)
        accrueActionCost(fundsBefore)
        refreshInfrastructure()
        return BuildResult.OK
    }

    private fun buildAirport(col: Int, row: Int, fundsBefore: Int): BuildResult {
        val indices = ArrayList<Int>(4)
        for (r in row..row + 1) {
            for (c in col..col + 1) {
                if (inBounds(c, r)) indices.add(r * cols + c)
            }
        }
        snapshotForUndo(indices, Tool.AIRPORT)
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
        accrueActionCost(fundsBefore)
        refreshInfrastructure()
        return BuildResult.OK
    }

    // ------------------------------------------------------------------ undo

    /** Everything a build can change on one tile, saved just before it changes. */
    private data class TileState(
        val terrain: Terrain,
        val zone: ZoneType,
        val level: Int,
        val population: Int,
        val jobs: Int,
        val maturity: Int,
        val variant: Int,
        val distress: Int,
        val pipe: Boolean,
        val powerline: Boolean,
        val subway: Boolean,
        val fire: Int,
        val crime: Int,
        val merged: Boolean,
        val originCol: Int,
        val originRow: Int,
        val reclaimed: Boolean
    ) {
        constructor(t: Tile) : this(
            t.terrain, t.zone, t.level, t.population, t.jobs, t.maturity, t.variant,
            t.distress, t.pipe, t.powerline, t.subway, t.fire, t.crime, t.merged,
            t.originCol, t.originRow, t.reclaimed
        )
    }

    private data class UndoEntry(
        val label: String,
        val tiles: Map<Int, TileState>,
        val fundsDelta: Int
    )

    private val undoStack = ArrayDeque<UndoEntry>()
    private var actionTiles: MutableMap<Int, TileState>? = null
    private var actionFunds = 0
    private var actionLabel = ""

    /** True when the last tap or drag can still be taken back. */
    val canUndo: Boolean
        get() = undoStack.isNotEmpty()

    /**
     * Groups every build made by one tap or drag into a single undo step.
     * Anything left open from an interrupted gesture is closed first.
     */
    fun beginAction() {
        endAction()
        actionTiles = LinkedHashMap()
        actionFunds = 0
        actionLabel = ""
    }

    /** Closes the current step. Returns true when it changed any tiles. */
    fun endAction(): Boolean {
        val tiles = actionTiles
        actionTiles = null
        if (tiles.isNullOrEmpty()) return false
        undoStack.addLast(UndoEntry(actionLabel, tiles, actionFunds))
        while (undoStack.size > MAX_UNDO) undoStack.removeFirst()
        return true
    }

    /**
     * Puts the tiles of the last step back the way they were and settles the
     * money it cost or refunded. Returns "build" or "bulldoze" when something
     * was undone, or null when the stack is empty.
     */
    fun undo(): String? {
        val entry = undoStack.removeLastOrNull() ?: return null
        for ((index, state) in entry.tiles) {
            val t = grid[index / cols][index % cols]
            // undo can break a large park apart again
            if (t.merged && t.originCol >= 0 && t.originRow >= 0) unmergeBlock(t)
            t.terrain = state.terrain
            t.zone = state.zone
            t.level = state.level
            t.population = state.population
            t.jobs = state.jobs
            t.maturity = state.maturity
            t.variant = state.variant
            t.distress = state.distress
            t.pipe = state.pipe
            t.powerline = state.powerline
            t.subway = state.subway
            t.fire = state.fire
            t.crime = state.crime
            t.merged = state.merged
            t.originCol = state.originCol
            t.originRow = state.originRow
            t.reclaimed = state.reclaimed
        }
        funds -= entry.fundsDelta
        var pop = 0
        var job = 0
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                pop += grid[r][c].population
                job += grid[r][c].jobs
            }
        }
        population = pop
        jobs = job
        refreshInfrastructure()
        updateDemand()
        return entry.label
    }

    private fun snapshotForUndo(indices: List<Int>, tool: Tool) {
        val action = actionTiles ?: return
        if (actionLabel.isEmpty()) actionLabel = if (tool == Tool.BULLDOZE) "bulldoze" else "build"
        for (index in indices) {
            if (!action.containsKey(index)) {
                action[index] = TileState(grid[index / cols][index % cols])
            }
        }
    }

    private fun accrueActionCost(fundsBefore: Int) {
        if (actionTiles == null) return
        actionFunds += funds - fundsBefore
    }

    /** The 2x2 block of a merged lot (or airport), or just this tile. */
    private fun blockIndices(col: Int, row: Int, t: Tile): List<Int> {
        if (!t.merged || t.originCol < 0 || t.originRow < 0) return listOf(row * cols + col)
        val indices = ArrayList<Int>(4)
        for (r in t.originRow..t.originRow + 1) {
            for (c in t.originCol..t.originCol + 1) {
                if (inBounds(c, r)) indices.add(r * cols + c)
            }
        }
        return indices
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
        ZoneType.SOLAR -> Config.COST_SOLAR
        ZoneType.WIND -> Config.COST_WIND
        ZoneType.NUCLEAR -> Config.COST_NUCLEAR
        ZoneType.WATERWORKS -> Config.COST_WATERWORKS
        ZoneType.POLICE -> Config.COST_POLICE
        ZoneType.FIRE -> Config.COST_FIRE
        ZoneType.HOSPITAL -> Config.COST_HOSPITAL
        ZoneType.STATION -> Config.COST_STATION
        ZoneType.SUBWAY_STATION -> Config.COST_SUBWAY_STATION
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
        updateSubwayAccess()
        updateWater()
        updatePower()
        updateServices()
        updatePollution()
        updateDemand()
        growBuildings()
        updateOccupancy()
        updateMerges()
        updateDisasters()
        updateHappiness()
        updateEconomy()
        updateProgression()
        updateContracts()
        updateDecisions()
        advanceDate()
    }

    fun refreshInfrastructure() {
        updateRoadAccess()
        updateRailAccess()
        updateSubwayAccess()
        updateWater()
        updatePower()
        updateServices()
        updateWaterfront()
        refreshDisasterCounts()
    }

    /** Marks every cell that has an orthogonal water neighbour (computed, never saved). */
    private fun updateWaterfront() {
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                grid[r][c].waterfront = touchesWater(c, r)
            }
        }
    }

    /** Keeps the public fire/crime tallies in step after a build or a load. */
    private fun refreshDisasterCounts() {
        activeFires = countTiles { it.fire > 0 }
        activeCrime = countTiles { it.crime > 0 }
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

    /** How many tiles one plant of this type can energise, or 0 if it is not a plant. */
    fun powerCapacity(zone: ZoneType): Int = when (zone) {
        ZoneType.PLANT -> Config.PLANT_POWER
        ZoneType.SOLAR -> Config.POWER_SOLAR
        ZoneType.WIND -> Config.POWER_WIND
        ZoneType.NUCLEAR -> Config.POWER_NUCLEAR
        else -> 0
    }

    /**
     * Power flows from every plant through roads, parks and buildings until the
     * plants' combined capacity runs out. Empty grass and water stop the flow, so
     * a disconnected district really does go dark.
     */
    private fun updatePower() {
        val reached = Array(rows) { BooleanArray(cols) }
        val queue = ArrayDeque<Int>()
        var budget = 0
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val zone = grid[r][c].zone
                if (zone.isPowerPlant) {
                    reached[r][c] = true
                    queue.addLast(r * cols + c)
                    budget += powerCapacity(zone)
                }
            }
        }
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
     * Tunnels join into lines underground. A line only counts once two or more
     * tunnel tiles touch; stations sitting on or beside a live line then pull
     * riders in from every lot within walking range.
     */
    private fun updateSubwayAccess() {
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                grid[r][c].subwayConnected = false
                grid[r][c].subwayAccess = false
            }
        }

        val seen = Array(rows) { BooleanArray(cols) }
        for (r0 in 0 until rows) {
            for (c0 in 0 until cols) {
                if (!grid[r0][c0].subway || seen[r0][c0]) continue
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
                        if (!grid[nr][nc].subway) continue
                        seen[nr][nc] = true
                        queue.addLast(nr * cols + nc)
                    }
                }
                if (component.size < 2) continue
                for (idx in component) grid[idx / cols][idx % cols].subwayConnected = true
            }
        }

        for (sr in 0 until rows) {
            for (sc in 0 until cols) {
                if (grid[sr][sc].zone != ZoneType.SUBWAY_STATION) continue
                if (!subwayLineAt(sc, sr)) continue
                for (r in max(0, sr - Config.SUBWAY_RANGE)..min(rows - 1, sr + Config.SUBWAY_RANGE)) {
                    for (c in max(0, sc - Config.SUBWAY_RANGE)..min(cols - 1, sc + Config.SUBWAY_RANGE)) {
                        if (abs(c - sc) + abs(r - sr) <= Config.SUBWAY_RANGE) {
                            grid[r][c].subwayAccess = true
                        }
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
                val t = grid[r][c]
                // per-tile cover drives fires, crime and the info tool
                t.policeCovered = servedBy(police, c, r, Config.POLICE_RANGE)
                t.fireCovered = servedBy(fire, c, r, Config.FIRE_RANGE)
                t.healthCovered = servedBy(health, c, r, Config.HOSPITAL_RANGE)
                if (!t.developed) continue
                developed++
                if (t.policeCovered) policeServed++
                if (t.fireCovered) fireServed++
                if (t.healthCovered) healthServed++
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
        val plants = fossilPlantCount
        val nuclear = nuclearCount
        val industry = countTiles { it.zone == ZoneType.INDUSTRIAL && it.level > 0 }
        val parks = parkCount
        val roads = roadCount
        pollution = clamp(
            plants * Config.POLLUTION_PLANT +
                nuclear * Config.POLLUTION_NUCLEAR +
                industry * 0.045f +
                roads * 0.0015f +
                highwayCount * 0.004f +
                airportCount * 0.10f +
                railCount * 0.002f -
                parks * 0.04f +
                pollutionOffset,
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
                activeFires * 0.02f -
                (housingCap - population) * 0.018f -
                housingFill * 0.12f +
                (jobs - population) * 0.004f +
                railCount * 0.004f +
                stationCount * 0.02f +
                subwayStationCount * 0.025f,
            -1f,
            1f
        )
        demandC = clamp(
            Config.DEMAND_BASE_C -
                activeCrime * 0.008f +
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
                if (t.fire > 0) continue
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
            if (t.transitAccess) chance *= 1.3f
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
                    if (t.powered && t.roadAccess && t.watered && t.fire == 0) {
                        t.distress = max(0, t.distress - 1)
                        t.maturity += if (t.transitAccess) 2 else 1
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
                    if (t.powered && t.roadAccess && t.watered && t.fire == 0) {
                        t.distress = max(0, t.distress - 1)
                        t.maturity += if (t.transitAccess) 2 else 1
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
                        t.fire == 0 &&
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
     * Four park tiles join into one large park as soon as they fill a 2x2
     * block around the tile that was just built.
     */
    private fun mergeParkBlock(col: Int, row: Int) {
        for (r in max(0, row - 1)..min(rows - 2, row)) {
            for (c in max(0, col - 1)..min(cols - 2, col)) {
                val a = grid[r][c]
                val b = grid[r][c + 1]
                val d = grid[r + 1][c]
                val e = grid[r + 1][c + 1]
                if (a.zone != ZoneType.PARK || b.zone != ZoneType.PARK) continue
                if (d.zone != ZoneType.PARK || e.zone != ZoneType.PARK) continue
                if (a.merged || b.merged || d.merged || e.merged) continue
                if (a.level < 1 || b.level < 1 || d.level < 1 || e.level < 1) continue
                for (t in arrayOf(a, b, d, e)) {
                    t.merged = true
                    t.originCol = c
                    t.originRow = r
                    t.maturity = 0
                }
                events.add(CityEvent.Merged(ZoneType.PARK, c, r))
            }
        }
    }

    /**
     * Four neighbouring developed lots of the same zone become one large
     * building once every lot has matured to the merge level. Four park tiles
     * group into one large park as soon as they form a 2x2 block.
     */
    private fun updateMerges() {
        for (r in 0 until rows - 1) {
            for (c in 0 until cols - 1) {
                val a = grid[r][c]
                val b = grid[r][c + 1]
                val d = grid[r + 1][c]
                val e = grid[r + 1][c + 1]
                val zone = a.zone
                val park = zone == ZoneType.PARK
                if (!zone.isZone && !park) continue
                if (b.zone != zone || d.zone != zone || e.zone != zone) continue
                if (a.merged || b.merged || d.merged || e.merged) continue
                if (park) {
                    // parks are complete the moment they are placed
                    if (a.level < 1 || b.level < 1 || d.level < 1 || e.level < 1) continue
                } else {
                    if (a.level < Config.MERGE_LEVEL || b.level < Config.MERGE_LEVEL) continue
                    if (d.level < Config.MERGE_LEVEL || e.level < Config.MERGE_LEVEL) continue
                    if (!ready(a) || !ready(b) || !ready(d) || !ready(e)) continue
                }

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

    /** A lot is ready to grow (and to merge) once it is served and not on fire. */
    private fun ready(t: Tile): Boolean =
        t.powered && t.roadAccess && t.watered && t.fire == 0

    private fun preMergeReset(t: Tile) {
        if (t.merged) unmergeBlock(t)
    }

    /**
     * Fires and crime only take hold where the city is not covered. An
     * unreached building can catch fire and burn down; an unpoliced block
     * attracts crime that drains it. Crews that do reach a fire put it out and
     * police clear crime marks on their beat.
     */
    private fun updateDisasters() {
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val t = grid[r][c]
                if (t.fire > 0) {
                    if (t.fireCovered) {
                        t.fire = 0
                        t.distress = max(0, t.distress - 2)
                        events.add(CityEvent.FireOut(t.zone, c, r))
                        continue
                    }
                    t.fire++
                    val cap = capacityForTile(t)
                    t.population = max(0, t.population - max(1, cap / 3))
                    t.jobs = max(0, t.jobs - max(1, cap / 3))
                    t.distress += 2
                    if (t.fire > Config.FIRE_BURN_DAYS) {
                        val zone = t.zone
                        for (b in blockTiles(t)) {
                            b.level = 0
                            b.population = 0
                            b.jobs = 0
                            b.fire = 0
                            b.distress = Config.DISTRESS_DAYS
                            b.merged = false
                            b.originCol = -1
                            b.originRow = -1
                        }
                        events.add(CityEvent.FireDestroyed(zone, c, r))
                    }
                    continue
                }
                if (t.crime > 0) {
                    if (t.policeCovered) {
                        t.crime = max(0, t.crime - 3)
                    } else {
                        t.crime--
                        val cap = capacityForTile(t)
                        if (cap > 0) {
                            t.population = max(0, t.population - max(1, cap / 16))
                            t.jobs = max(0, t.jobs - max(1, cap / 16))
                        }
                    }
                }
            }
        }

        // new incidents: only buildings that no station reaches are at risk
        // Disasters only begin once the city can answer them: until the fire
        // and police tools are earned, early settlements are left in peace.
        val fireDanger = isUnlocked(Tool.FIRE)
        val crimeDanger = isUnlocked(Tool.POLICE)
        var fires = countTiles { it.fire > 0 }
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val t = grid[r][c]
                if (!t.developed || t.fire > 0) continue
                if (fireDanger && !t.fireCovered && fires < Config.MAX_ACTIVE_FIRES) {
                    if (rng.nextFloat() <
                        Config.FIRE_CHANCE * (1f + pollution * 0.5f) *
                            difficulty.incidentScale
                    ) {
                        t.fire = 1
                        fires++
                        events.add(CityEvent.FireStarted(t.zone, c, r))
                        continue
                    }
                }
                if (crimeDanger && t.crime == 0 && !t.policeCovered &&
                    rng.nextFloat() < Config.CRIME_CHANCE * difficulty.incidentScale
                ) {
                    t.crime = Config.CRIME_DAYS
                }
            }
        }

        // damage above moved people out after the occupancy pass re-tallied it
        var popTotal = 0
        var jobTotal = 0
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                popTotal += grid[r][c].population
                jobTotal += grid[r][c].jobs
            }
        }
        population = popTotal
        jobs = jobTotal

        activeFires = countTiles { it.fire > 0 }
        activeCrime = countTiles { it.crime > 0 }
        val stage = when {
            activeCrime >= Config.CRIME_WAVE * 2 -> 3
            activeCrime >= Config.CRIME_WAVE -> 2
            activeCrime > 0 -> 1
            else -> 0
        }
        if (stage > crimeStage) {
            crimeStage = stage
            var crimeCol = -1
            var crimeRow = -1
            for (r in 0 until rows) {
                for (c in 0 until cols) {
                    if (crimeCol < 0 && grid[r][c].crime > 0) {
                        crimeCol = c
                        crimeRow = r
                    }
                }
            }
            events.add(
                CityEvent.CrimeReported(
                    when (stage) {
                        3 -> "Crime is everywhere - $activeCrime buildings hit. Build POLICE."
                        2 -> "A crime wave: $activeCrime buildings hit, police cover is thin"
                        else -> "Crime reported - POLICE within ${Config.POLICE_RANGE} tiles stops it"
                    },
                    activeCrime,
                    stage,
                    crimeCol,
                    crimeRow
                )
            )
        } else if (stage == 0) {
            crimeStage = 0
        }
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
            min(4f, railCount * 0.2f) +
            min(6f, subwayStationCount * 1.6f) -
            pollution * 45f -
            unemployment * 26f -
            (taxRate - Config.TAX_DEFAULT) * Config.TAX_HAPPINESS_WEIGHT -
            min(15f, activeFires * 6f + activeCrime * 0.4f) -
            if (funds < 0) 12f else 0f
        happiness = clamp(happiness + (target - happiness) * 0.12f, 0f, 100f)
    }

    /** Citizens pay a share of their income set by [taxRate]; 10% is the baseline. */
    private fun taxMultiplier(): Float = taxRate / Config.TAX_DEFAULT.toFloat()

    private fun dailyIncome(): Float =
        (population * Config.TAX_PER_RESIDENT + jobs * Config.TAX_PER_JOB) * taxMultiplier()

    private fun dailyExpenses(): Float {
        val upkeep =
            roadCount * Config.UPKEEP_ROAD +
                highwayCount * Config.UPKEEP_HIGHWAY +
                railCount * Config.UPKEEP_RAIL +
                pipeCount * Config.UPKEEP_PIPE +
                powerlineCount * Config.UPKEEP_POWERLINE +
                stationCount * Config.UPKEEP_STATION +
                subwayCount * Config.UPKEEP_SUBWAY +
                subwayStationCount * Config.UPKEEP_SUBWAY_STATION +
                parkCount * Config.UPKEEP_PARK +
                fossilPlantCount * Config.UPKEEP_PLANT +
                solarCount * Config.UPKEEP_SOLAR +
                windCount * Config.UPKEEP_WIND +
                nuclearCount * Config.UPKEEP_NUCLEAR +
                waterCount * Config.UPKEEP_WATERWORKS +
                policeCount * Config.UPKEEP_POLICE +
                fireCount * Config.UPKEEP_FIRE +
                hospitalCount * Config.UPKEEP_HOSPITAL +
                airportCount * Config.UPKEEP_AIRPORT +
                buildingCount * Config.UPKEEP_BUILDING
        return upkeep * difficulty.upkeepScale
    }

    private fun updateEconomy() {
        val income = dailyIncome()
        val expenses = dailyExpenses()
        val net = (income - expenses).roundToInt()
        funds += net
        monthIncome += income
        monthExpenses += expenses
        if (funds < 0) {
            debtDays++
            // the bank charges for the privilege of running a deficit
            funds -= max(1, (-funds * Config.DEBT_DAILY_RATE).roundToInt())
            if (!debtWarned) {
                debtWarned = true
                events.add(CityEvent.Notice("The city is in debt - raise income or cut upkeep"))
            }
            if (debtDays >= Config.BANKRUPTCY_DAYS && !bankrupt) {
                bankrupt = true
                events.add(CityEvent.Bankrupt(funds))
            }
        } else {
            debtDays = 0
            if (loanBalance > 0) {
                debtWarned = false
                val payment = min(loanBalance, min(Config.LOAN_DAILY_PAYMENT, funds))
                if (payment > 0) {
                    funds -= payment
                    loanBalance -= payment
                }
            }
        }
    }

    /** Accepts the emergency loan that ends a bankruptcy, at a stiff price. */
    fun acceptBailout(): Boolean {
        if (!bankrupt) return false
        funds += Config.BAILOUT_CASH
        loanBalance += Config.BAILOUT_DEBT
        bankrupt = false
        debtDays = 0
        bailouts++
        happiness = (happiness - 8f).coerceAtLeast(0f)
        events.add(
            CityEvent.Notice(
                "Emergency loan: +$${Config.BAILOUT_CASH} now, " +
                    "$${Config.BAILOUT_DEBT} to repay"
            )
        )
        return true
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
            closeMonth(finishedMonth, finishedYear)
            monthIncome = 0f
            monthExpenses = 0f
            debtWarned = false
        }
    }

    /** Files the month that has just ended into the city record. */
    private fun closeMonth(finishedMonth: Int, finishedYear: Int) {
        records.add(
            CityRecord(
                month = finishedMonth,
                year = finishedYear,
                population = population,
                jobs = jobs,
                funds = funds,
                happiness = happiness.roundToInt().coerceIn(0, 100),
                pollution = (pollution * 100f).roundToInt().coerceIn(0, 100),
                income = monthIncome.roundToInt(),
                expenses = monthExpenses.roundToInt()
            )
        )
        while (records.size > Config.HISTORY_MONTHS) records.removeAt(0)
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
        var capacity = base.toFloat()
        if (t.merged && t.zone.isZone) capacity *= Config.MERGE_CAPACITY_BONUS
        // Water views sell: a lot beside the river, or on land raised from it, holds more.
        if (t.zone.isZone && (t.waterfront || t.reclaimed)) {
            capacity *= Config.WATERFRONT_CAPACITY
        }
        return capacity.roundToInt()
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
        val tax = (t.population * Config.TAX_PER_RESIDENT + t.jobs * Config.TAX_PER_JOB) *
            taxMultiplier()
        val upkeep = when (t.zone) {
            ZoneType.ROAD -> Config.UPKEEP_ROAD
            ZoneType.HIGHWAY -> Config.UPKEEP_HIGHWAY
            ZoneType.RAIL -> Config.UPKEEP_RAIL
            ZoneType.PARK -> Config.UPKEEP_PARK
            ZoneType.PLANT -> Config.UPKEEP_PLANT
            ZoneType.SOLAR -> Config.UPKEEP_SOLAR
            ZoneType.WIND -> Config.UPKEEP_WIND
            ZoneType.NUCLEAR -> Config.UPKEEP_NUCLEAR
            ZoneType.WATERWORKS -> Config.UPKEEP_WATERWORKS
            ZoneType.POLICE -> Config.UPKEEP_POLICE
            ZoneType.FIRE -> Config.UPKEEP_FIRE
            ZoneType.HOSPITAL -> Config.UPKEEP_HOSPITAL
            ZoneType.STATION -> Config.UPKEEP_STATION
            ZoneType.SUBWAY_STATION -> Config.UPKEEP_SUBWAY_STATION
            ZoneType.AIRPORT -> Config.UPKEEP_AIRPORT
            ZoneType.RESIDENTIAL,
            ZoneType.COMMERCIAL,
            ZoneType.INDUSTRIAL -> if (t.level > 0) Config.UPKEEP_BUILDING else 0f
            else -> 0f
        } + (if (t.pipe) Config.UPKEEP_PIPE else 0f) +
            (if (t.powerline) Config.UPKEEP_POWERLINE else 0f) +
            (if (t.subway) Config.UPKEEP_SUBWAY else 0f)
        return tax - upkeep
    }

    /** A one-word summary of what a tile is doing right now. */
    fun tileState(t: Tile): String = when {
        t.terrain == Terrain.WATER && t.zone.isRoad -> "Bridge"
        t.terrain == Terrain.WATER -> "Water"
        t.merged && t.zone == ZoneType.PARK -> "Large park"
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
        sb.append("CC11|")
        sb.append(funds).append('|')
        sb.append(day).append('|')
        sb.append(month).append('|')
        sb.append(year).append('|')
        sb.append(String.format(java.util.Locale.US, "%.2f", happiness)).append('|')
        sb.append(String.format(java.util.Locale.US, "%.3f", pollution)).append('|')
        sb.append(seedValue).append('|')
        sb.append(completed.joinToString(",") { it.name }).append('|')
        sb.append(stableDays).append('|')
        sb.append(taxRate).append('|')
        sb.append(
            records.joinToString(";") {
                "${it.month},${it.year},${it.population},${it.jobs},${it.funds}," +
                    "${it.happiness},${it.pollution},${it.income},${it.expenses}"
            }
        ).append('|')
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
                sb.append(if (t.powerline) 1 else 0).append(',')
                sb.append(if (t.subway) 1 else 0).append(',')
                sb.append(t.fire).append(',')
                sb.append(t.crime).append(',')
                sb.append(if (t.reclaimed) 1 else 0)
            }
        }
        sb.append('|').append(difficulty.ordinal).append('|')
        sb.append(decisionCooldown).append('|')
        val c = contract
        if (c != null) {
            sb.append(c.kind.name).append(',').append(c.target).append(',')
                .append(c.deadlineDay).append(',').append(c.reward).append(',')
                .append(c.offeredDay)
        }
        sb.append('|').append(contractCooldown).append('|')
        sb.append(debtDays).append('|')
        sb.append(if (bankrupt) 1 else 0).append('|')
        sb.append(bailouts).append('|')
        sb.append(loanBalance).append('|')
        sb.append(String.format(java.util.Locale.US, "%.2f", pollutionOffset))
        sb.append('|').append(pendingDecision?.kind?.name ?: "")
        sb.append('|').append(mapStyle.ordinal)
        return sb.toString()
    }

    // ------------------------------------------------------------------ load

    companion object {
        /** How many taps or drags can be taken back, newest first. */
        private const val MAX_UNDO = 15

        private val MONTHS = arrayOf(
            "Jan", "Feb", "Mar", "Apr", "May", "Jun",
            "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
        )
        private val DC = intArrayOf(-1, 1, 0, 0)
        private val DR = intArrayOf(0, 0, -1, 1)
        private val SAVE_VERSIONS =
            setOf(
                "CC1", "CC2", "CC3", "CC4", "CC5", "CC6", "CC7", "CC8", "CC9", "CC10",
                "CC11"
            )
        private val SCORE_MEDALS = intArrayOf(750, 2_000, 4_500, 9_000)

        fun load(data: String?): CityEngine? {
            if (data.isNullOrBlank()) return null
            val parts = data.split('|')
            if (parts.size < 9) return null
            val version = parts[0]
            if (version !in SAVE_VERSIONS) return null
            val mergedSaves = version != "CC1"
            val layeredPipes = version != "CC1" && version != "CC2"
            val layeredWires = version != "CC1" && version != "CC2" && version != "CC3"
            val reclaimedSaves = version == "CC11"
            val progressive = version == "CC5" || version == "CC6" ||
                version == "CC7" || version == "CC8" || version == "CC9" ||
                version == "CC10" || reclaimedSaves
            val tunnelled = version == "CC6" || version == "CC7" ||
                version == "CC8" || version == "CC9" || version == "CC10" || reclaimedSaves
            val disasterSaves = version == "CC7" || version == "CC8" ||
                version == "CC9" || version == "CC10" || reclaimedSaves
            val fullSaves = version == "CC9" || version == "CC10" || reclaimedSaves
            val mapSaves = version == "CC10" || reclaimedSaves
            val historySaves = version == "CC8" || fullSaves
            val expectedParts = when {
                !tunnelled -> 11
                mapSaves -> 24
                fullSaves -> 22
                historySaves -> 13
                else -> 12
            }
            if (progressive && parts.size != expectedParts) return null
            return try {
                val w = Config.COLS
                val h = Config.ROWS
                val engine = CityEngine(w, h, parts[7].toLongOrNull() ?: 1L)
                // Pre-progression saves never had locked tools; keep them all open.
                engine.legacyUnlockedAll = !progressive
                val tilePart = when {
                    historySaves -> parts[12]
                    tunnelled -> parts[11]
                    progressive -> parts[10]
                    else -> parts[8]
                }
                if (tunnelled) {
                    engine.taxRate = parts[10].toInt().coerceIn(Config.TAX_MIN, Config.TAX_MAX)
                }
                if (historySaves) {
                    // The constructor lays down an opening record; the save owns the real one.
                    engine.records.clear()
                    for (chunk in parts[11].split(';')) {
                        if (chunk.isEmpty()) continue
                        val f = chunk.split(',')
                        if (f.size < 9) return null
                        engine.records.add(
                            CityRecord(
                                month = f[0].toInt().coerceIn(1, 12),
                                year = f[1].toInt().coerceIn(1900, 3000),
                                population = f[2].toInt().coerceAtLeast(0),
                                jobs = f[3].toInt().coerceAtLeast(0),
                                funds = f[4].toInt(),
                                happiness = f[5].toInt().coerceIn(0, 100),
                                pollution = f[6].toInt().coerceIn(0, 100),
                                income = f[7].toInt().coerceAtLeast(0),
                                expenses = f[8].toInt().coerceAtLeast(0)
                            )
                        )
                    }
                    while (engine.records.size > Config.HISTORY_MONTHS) {
                        engine.records.removeAt(0)
                    }
                }
                if (fullSaves) {
                    engine.difficulty = Difficulty.values()
                        .getOrNull(parts[13].toIntOrNull() ?: -1) ?: return null
                    engine.decisionCooldown = parts[14].toInt().coerceAtLeast(0)
                    if (parts[15].isNotEmpty()) {
                        val f = parts[15].split(',')
                        if (f.size < 5) return null
                        val kind = ContractKind.values().firstOrNull { it.name == f[0] }
                            ?: return null
                        engine.contract = Contract(
                            kind = kind,
                            target = f[1].toInt().coerceAtLeast(1),
                            deadlineDay = f[2].toInt(),
                            reward = f[3].toInt().coerceAtLeast(0),
                            offeredDay = f[4].toInt()
                        )
                    }
                    engine.contractCooldown = parts[16].toInt().coerceAtLeast(0)
                    engine.debtDays = parts[17].toInt().coerceAtLeast(0)
                    engine.bankrupt = parts[18].toInt() != 0
                    engine.bailouts = parts[19].toInt().coerceAtLeast(0)
                    engine.loanBalance = parts[20].toInt().coerceAtLeast(0)
                    engine.pollutionOffset = parts[21].toFloat().coerceIn(0f, 1f)
                }
                if (mapSaves) {
                    if (parts[22].isNotEmpty()) {
                        val kind = DecisionKind.values()
                            .firstOrNull { it.name == parts[22] } ?: return null
                        engine.lastDecisionKind = kind
                        engine.pendingDecision = engine.buildDecision(kind)
                    }
                    engine.mapStyle = MapStyle.values()
                        .getOrNull(parts[23].toIntOrNull() ?: -1) ?: return null
                }
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
                    if (tunnelled && f.size < 14) return null
                    if (disasterSaves && f.size < 16) return null
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
                    if (tunnelled) t.subway = f[13].toInt() != 0
                    if (disasterSaves) {
                        t.fire = f[14].toInt().coerceAtLeast(0)
                        t.crime = f[15].toInt().coerceAtLeast(0)
                    }
                    if (reclaimedSaves) {
                        if (f.size < 17) return null
                        t.reclaimed = f[16].toInt() != 0
                    }
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
        when (mapStyle) {
            MapStyle.RIVERLANDS -> generateRiverlands()
            MapStyle.BAYSIDE -> generateBayside()
            MapStyle.TWIN_RIVERS -> generateTwinRivers()
        }
        clearSpawn()
    }

    /** A river down the west side and a lake in the south-east. */
    private fun generateRiverlands() {
        var c = max(2, cols / 4)
        for (r in 0 until rows) {
            c = (c + rng.nextInt(-1, 2)).coerceIn(2, max(2, cols / 3))
            setTerrain(c, r, Terrain.WATER)
            setTerrain(c + 1, r, Terrain.WATER)
        }

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

        scatterForestClumps(7)
        scatterLoneTrees()
    }

    /** A broad bay in the north-east, a creek running south, open land to the west. */
    private fun generateBayside() {
        val bayCol = (cols * 0.78f).toInt()
        val bayRow = (rows * 0.22f).toInt()
        val radius = max(4f, min(cols, rows) * 0.27f)
        for (r in 0 until rows) {
            for (col in 0 until cols) {
                val dx = (col - bayCol) / radius
                val dy = (r - bayRow) / radius
                if (dx * dx + dy * dy <= 1.0f + rng.nextFloat() * 0.15f) {
                    setTerrain(col, r, Terrain.WATER)
                }
            }
        }

        var creek = max(bayCol - 3, cols / 2)
        for (r in bayRow until rows - 1) {
            creek = (creek + rng.nextInt(-1, 2)).coerceIn(cols / 2, cols - 2)
            setTerrain(creek, r, Terrain.WATER)
        }

        scatterForestClumps(7)
        scatterLoneTrees()
    }

    /** One channel north to south and another east to west, cutting the map in four. */
    private fun generateTwinRivers() {
        var c = max(2, cols / 5)
        for (r in 0 until rows) {
            c = (c + rng.nextInt(-1, 2)).coerceIn(2, max(2, cols / 3))
            setTerrain(c, r, Terrain.WATER)
            setTerrain(c + 1, r, Terrain.WATER)
        }

        var row = rows * 2 / 3
        for (col in 0 until cols) {
            row = (row + rng.nextInt(-1, 2)).coerceIn(rows / 2, rows - 3)
            setTerrain(col, row, Terrain.WATER)
            setTerrain(col, row + 1, Terrain.WATER)
        }

        scatterForestClumps(9)
        scatterLoneTrees()
    }

    /** Forest clumps, kept away from the starting clearing. */
    private fun scatterForestClumps(count: Int) {
        val spawnCol = cols / 2
        val spawnRow = rows / 2
        repeat(count) {
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
    }

    /** A handful of lone trees for texture. */
    private fun scatterLoneTrees() {
        val spawnCol = cols / 2
        val spawnRow = rows / 2
        repeat(cols + rows) {
            val fc = rng.nextInt(0, cols)
            val fr = rng.nextInt(0, rows)
            if (abs(fr - spawnRow) > 3 || abs(fc - spawnCol) > 3) setForest(fc, fr)
        }
    }

    /** Guaranteed flat, dry starting square. */
    private fun clearSpawn() {
        val spawnCol = cols / 2
        val spawnRow = rows / 2
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
