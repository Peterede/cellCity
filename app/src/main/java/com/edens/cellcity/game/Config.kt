package com.edens.cellcity.game

/** All tuning constants for Cell City. Pure logic, no Android dependencies. */
object Config {
    const val COLS = 24
    const val ROWS = 24

    /** Isometric tile size in dp at zoom 1. */
    const val TILE_W = 56f
    const val TILE_H = 28f

    // --- starting city -----------------------------------------------------
    const val START_FUNDS = 15000
    const val START_YEAR = 2026
    const val DAYS_PER_MONTH = 30

    // --- build costs -------------------------------------------------------
    const val COST_ROAD = 15
    const val COST_BRIDGE = 90
    /** Four-lane road: acts as a road, but moves traffic faster and pulls in trade. */
    const val COST_HIGHWAY = 120
    const val COST_HIGHWAY_BRIDGE = 300
    const val COST_RAIL = 40
    /** Water mains are cheap on land, but crossing the river costs real money. */
    const val COST_PIPE = 12
    const val COST_PIPE_WATER = 70
    /** Buried cable: carries the grid where roads do not go. */
    const val COST_POWERLINE = 10
    const val COST_POWERLINE_WATER = 50
    const val COST_RESIDENTIAL = 40
    const val COST_COMMERCIAL = 50
    const val COST_INDUSTRIAL = 60
    const val COST_PARK = 100
    const val COST_PLANT = 2500
    const val COST_WATERWORKS = 500
    const val COST_POLICE = 900
    const val COST_FIRE = 800
    const val COST_HOSPITAL = 1200
    const val COST_STATION = 700
    /** A tunnel is a buried layer: cheaper than a street, but it needs stations to be useful. */
    const val COST_SUBWAY = 55
    const val COST_SUBWAY_WATER = 160
    const val COST_SUBWAY_STATION = 650
    /** One airport occupies a 2x2 block and is charged once. */
    const val COST_AIRPORT = 6000
    const val BULLDOZE_REFUND = 0.25f

    // --- economy -----------------------------------------------------------
    /** Tax collected each day for one resident / one filled job. */
    const val TAX_PER_RESIDENT = 0.16f
    const val TAX_PER_JOB = 0.10f
    const val UPKEEP_ROAD = 0.08f
    const val UPKEEP_HIGHWAY = 0.25f
    const val UPKEEP_RAIL = 0.10f
    const val UPKEEP_PIPE = 0.03f
    const val UPKEEP_POWERLINE = 0.02f
    const val UPKEEP_STATION = 1.30f
    const val UPKEEP_SUBWAY = 0.06f
    const val UPKEEP_SUBWAY_STATION = 1.10f
    const val UPKEEP_PARK = 0.40f
    const val UPKEEP_PLANT = 3.00f
    const val UPKEEP_WATERWORKS = 1.20f
    const val UPKEEP_POLICE = 1.60f
    const val UPKEEP_FIRE = 1.40f
    const val UPKEEP_HOSPITAL = 2.00f
    const val UPKEEP_AIRPORT = 8.00f
    const val UPKEEP_BUILDING = 0.02f

    // --- growth ------------------------------------------------------------
    /** Building occupancy by level: level 1..3. */
    val RES_CAPACITY = intArrayOf(6, 16, 36)
    val COM_CAPACITY = intArrayOf(5, 12, 24)
    val IND_CAPACITY = intArrayOf(7, 16, 32)

    const val MAX_GROWTH_PER_DAY = 2
    const val GROWTH_CHANCE = 0.22f
    const val UPGRADE_CHANCE = 0.35f
    const val UPGRADE_DAYS = 22
    const val MAX_LEVEL = 3
    const val DISTRESS_DAYS = 24
    const val ROAD_REACH = 1

    /** Tiles that one power plant can energise (breadth-first through roads). */
    const val PLANT_POWER = 55

    // --- services ----------------------------------------------------------
    /** Manhattan radius a water tower pipes water to. */
    const val WATER_RANGE = 6
    /** A station spreads transit access to lots this far from its door. */
    const val STATION_RANGE = 5
    /** Subway stations are underground platforms: riders walk a little further to reach one. */
    const val SUBWAY_RANGE = 6
    const val POLICE_RANGE = 7
    const val FIRE_RANGE = 6
    const val HOSPITAL_RANGE = 7
    const val AIRPORT_JOBS = 90

    // --- 2x2 city blocks ---------------------------------------------------
    /** Every lot must reach this level before four of them merge. */
    const val MERGE_LEVEL = 2
    /** Merged lots get roomier floors than four separate buildings. */
    const val MERGE_CAPACITY_BONUS = 1.35f

    // --- progression -------------------------------------------------------
    /** Below this many residents a city is too small to judge as "stable". */
    const val STABLE_MIN_POPULATION = 20
    /** A stable day needs at least this much happiness. */
    const val STABLE_MIN_HAPPINESS = 55f
    const val MILESTONE_POP_FIRST_HOME = 10
    const val MILESTONE_POP_HAMLET = 100
    const val MILESTONE_POP_TOWN = 500
    const val MILESTONE_POP_CITY = 1000
    const val MILESTONE_POP_METROPOLIS = 5000
    const val MILESTONE_STEADY_DAYS = 30
    const val MILESTONE_STABLE_CYCLE_DAYS = 100

    // --- disasters ---------------------------------------------------------
    /** Chance per day that a building with no fire cover catches fire. */
    const val FIRE_CHANCE = 0.004f
    /** Chance per day that a building with no police cover is hit by crime. */
    const val CRIME_CHANCE = 0.012f
    /** Days a fire burns with no crew before the building is gutted. */
    const val FIRE_BURN_DAYS = 4
    /** Days a crime mark lingers while police cannot reach the building. */
    const val CRIME_DAYS = 12
    /** The city never has more fires than this burning at once. */
    const val MAX_ACTIVE_FIRES = 3
    /** Buildings hit by crime before the city is warned. */
    const val CRIME_WAVE = 5

    // --- taxes -------------------------------------------------------------
    /** The tax rate the city starts with, in percent of a citizen's income. */
    const val TAX_DEFAULT = 10
    const val TAX_MIN = 0
    const val TAX_MAX = 25
    /** Each point of tax above the default costs this much happiness (and below it buys some). */
    const val TAX_HAPPINESS_WEIGHT = 1.1f

    // --- demand ------------------------------------------------------------
    const val DEMAND_BASE_R = 0.62f
    const val DEMAND_BASE_C = 0.34f
    const val DEMAND_BASE_I = 0.28f

    // --- look --------------------------------------------------------------
    const val INK = 0xFF13203A.toInt()
    const val SKY = 0xFF14263B.toInt()
    const val SEA = 0xFF1B3A54.toInt()
    const val SEA_LIGHT = 0xFF24506E.toInt()
    const val CREAM = 0xFFFFF3D6.toInt()
    const val PAPER = 0xFFFFFBEF.toInt()
    const val AMBER = 0xFFFFD166.toInt()
    const val CORAL = 0xFFE86A5B.toInt()
    const val MINT = 0xFF6FE0D0.toInt()
    const val LEAF = 0xFF69C46D.toInt()
    const val SKY_BLUE = 0xFF5AB6E8.toInt()
    const val BRASS = 0xFFD7A54A.toInt()
    /** Deep tunnel teal used for subway lines and station signs. */
    const val TUNNEL = 0xFF2E6F73.toInt()
}
