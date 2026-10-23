package com.edens.cellcity.game

/** What the ground looks like before the city is built on it. */
enum class Terrain {
    GRASS,
    FOREST,
    WATER
}

/** What occupies a tile. */
enum class ZoneType {
    NONE,
    ROAD,
    RESIDENTIAL,
    COMMERCIAL,
    INDUSTRIAL,
    PARK,
    PLANT,
    RAIL,
    WATERWORKS,
    POLICE,
    FIRE,
    HOSPITAL,
    AIRPORT,
    /** Legacy only: old saves stored mains as a zone. New pipes live in [Tile.pipe]. */
    PIPE,
    STATION,
    HIGHWAY,
    /** Appended last so that zone ordinals in older saves keep their meaning. */
    SUBWAY_STATION;

    val isZone: Boolean
        get() = this == RESIDENTIAL || this == COMMERCIAL || this == INDUSTRIAL

    val isDevelopable: Boolean
        get() = isZone || this == PARK

    /** Surfaces cars drive on and that carry the power grid. */
    val isRoad: Boolean
        get() = this == ROAD || this == HIGHWAY

    /** Utilities and emergency services: paid for, but nobody lives in them. */
    val isService: Boolean
        get() = this == PLANT || this == WATERWORKS || this == POLICE ||
            this == FIRE || this == HOSPITAL || this == STATION || this == SUBWAY_STATION

    /** Structures that always occupy a full 2x2 block. */
    val isMultiTile: Boolean
        get() = this == AIRPORT

    val label: String
        get() = when (this) {
            NONE -> "Empty"
            ROAD -> "Road"
            RESIDENTIAL -> "Residential"
            COMMERCIAL -> "Commercial"
            INDUSTRIAL -> "Industrial"
            PARK -> "Park"
            PLANT -> "Power plant"
            RAIL -> "Rail line"
            WATERWORKS -> "Water tower"
            POLICE -> "Police station"
            FIRE -> "Fire station"
            HOSPITAL -> "Hospital"
            AIRPORT -> "Airport"
            PIPE -> "Water pipe"
            STATION -> "Train station"
            HIGHWAY -> "Highway"
            SUBWAY_STATION -> "Subway station"
        }
}

/** The player's current build tool. */
enum class Tool(val zone: ZoneType, val label: String) {
    ROAD(ZoneType.ROAD, "ROAD"),
    HIGHWAY(ZoneType.HIGHWAY, "HWY"),
    RAIL(ZoneType.RAIL, "RAIL"),
    /** Tunnels are a buried layer, like mains and cables. */
    SUBWAY(ZoneType.NONE, "SUBWAY"),
    STATION(ZoneType.STATION, "STATION"),
    SUBWAY_STATION(ZoneType.SUBWAY_STATION, "SUB STN"),
    AIRPORT(ZoneType.AIRPORT, "AIR"),
    RESIDENTIAL(ZoneType.RESIDENTIAL, "RES"),
    COMMERCIAL(ZoneType.COMMERCIAL, "COM"),
    INDUSTRIAL(ZoneType.INDUSTRIAL, "IND"),
    PARK(ZoneType.PARK, "PARK"),
    PLANT(ZoneType.PLANT, "PLANT"),
    WATER(ZoneType.WATERWORKS, "WATER"),
    /** Underground mains and cables are layers on top of whatever occupies the tile. */
    PIPE(ZoneType.NONE, "PIPE"),
    POWERLINE(ZoneType.NONE, "WIRE"),
    POLICE(ZoneType.POLICE, "POLICE"),
    FIRE(ZoneType.FIRE, "FIRE"),
    HOSPITAL(ZoneType.HOSPITAL, "HOSP"),
    BULLDOZE(ZoneType.NONE, "DOZE"),
    PAN(ZoneType.NONE, "PAN"),
    /** Read-only tool: tapping a cell explains what it needs. */
    INFO(ZoneType.NONE, "INFO");

    /** True for tools that bury a layer instead of occupying the tile. */
    val isLayer: Boolean
        get() = this == PIPE || this == POWERLINE || this == SUBWAY

    val buildsZones: Boolean
        get() = this != PAN && this != BULLDOZE

    /** Civic buildings and the info tool act on one tile per tap. */
    val singlePlacement: Boolean
        get() = zone.isService || zone.isMultiTile || this == INFO
}

/**
 * A data view that shades the map by how far one emergency service reaches.
 * NONE is the normal city view.
 */
enum class CoverageOverlay(val label: String, val color: Int) {
    NONE("OFF", 0xFF9A9484.toInt()),
    FIRE("FIRE", 0xFFE86A5B.toInt()),
    POLICE("POLICE", 0xFF5AB6E8.toInt()),
    HEALTH("HEALTH", 0xFF6FE0D0.toInt());

    /** The station that provides this view's coverage, or null for the normal view. */
    val station: ZoneType?
        get() = when (this) {
            FIRE -> ZoneType.FIRE
            POLICE -> ZoneType.POLICE
            HEALTH -> ZoneType.HOSPITAL
            NONE -> null
        }
}

/** One map cell. */
class Tile {
    var terrain: Terrain = Terrain.GRASS
    var zone: ZoneType = ZoneType.NONE
    var level: Int = 0
    var population: Int = 0
    var jobs: Int = 0
    var powered: Boolean = false
    var roadAccess: Boolean = false
    var watered: Boolean = false
    var railAccess: Boolean = false
    var variant: Int = 0
    var maturity: Int = 0
    var distress: Int = 0

    /** A water main buried under this tile; it can share the tile with any zone. */
    var pipe: Boolean = false

    /** Buried power cable under this tile, on top of whichever zone is here. */
    var powerline: Boolean = false

    /** A subway tunnel buried under this tile; it can share the tile with anything. */
    var subway: Boolean = false

    /** True when this tunnel is part of a line of two or more connected tunnel tiles. */
    var subwayConnected: Boolean = false

    /** True when a subway station with a live tunnel reaches this tile. */
    var subwayAccess: Boolean = false

    /** Rail or subway access: either one speeds up growth and maturity. */
    val transitAccess: Boolean
        get() = railAccess || subwayAccess

    /** Days this building has been on fire; 0 means it is safe. */
    var fire: Int = 0

    /** Days a crime mark lingers on this building; 0 means it is clean. */
    var crime: Int = 0

    /** Computed each tick: a police station reaches this tile. */
    var policeCovered: Boolean = false

    /** Computed each tick: a fire station reaches this tile. */
    var fireCovered: Boolean = false

    /** Computed each tick: a hospital reaches this tile. */
    var healthCovered: Boolean = false

    /** True when this tile is one quarter of a 2x2 block (merged lots or an airport). */
    var merged: Boolean = false
    var originCol: Int = -1
    var originRow: Int = -1

    val developed: Boolean
        get() = zone.isZone && level > 0

    /** True when the active coverage overlay reaches this tile; always true for NONE. */
    fun coveredBy(overlay: CoverageOverlay): Boolean = when (overlay) {
        CoverageOverlay.FIRE -> fireCovered
        CoverageOverlay.POLICE -> policeCovered
        CoverageOverlay.HEALTH -> healthCovered
        CoverageOverlay.NONE -> true
    }

    fun clear() {
        zone = ZoneType.NONE
        level = 0
        population = 0
        jobs = 0
        powered = false
        roadAccess = false
        watered = false
        railAccess = false
        maturity = 0
        distress = 0
        pipe = false
        powerline = false
        subway = false
        subwayConnected = false
        subwayAccess = false
        fire = 0
        crime = 0
        merged = false
        originCol = -1
        originRow = -1
    }
}

enum class BuildResult(val message: String) {
    OK(""),
    NO_FUNDS("Not enough funds"),
    OCCUPIED("Tile is already in use"),
    WATER("Can't build on water"),
    NO_ROAD("Needs a road beside it"),
    NO_RAIL("Needs rail beside it"),
    NO_SUBWAY("Needs a subway line under or beside it"),
    LOCKED("Locked - reach the next milestone"),
    CANNOT("Can't build there"),
    OUT_OF_BOUNDS("")
}

/**
 * A progression goal. Completing one pays a cash bonus and may unlock tools
 * that were unavailable when the city was founded. Order matters: this is the
 * order they are offered and drawn in the goals panel.
 */
enum class Milestone(
    val title: String,
    val detail: String,
    val cash: Int,
    val unlocks: List<Tool>
) {
    FIRST_HOME("First Residents", "Reach 10 population", 250, listOf(Tool.RAIL)),
    HAMLET("Hamlet", "Reach 100 population", 500, listOf(Tool.STATION, Tool.HIGHWAY)),
    STEADY("Steady Hands", "30 stable days in a row", 750, listOf(Tool.POLICE, Tool.FIRE)),
    TOWN(
        "Town",
        "Reach 500 population",
        1_000,
        listOf(Tool.HOSPITAL, Tool.SUBWAY, Tool.SUBWAY_STATION)
    ),
    SKYLINE("Skyline", "Merge four lots into one large building", 1_500, listOf(Tool.AIRPORT)),
    CITY("City", "Reach 1,000 population", 3_000, emptyList()),
    STABLE_CYCLE("Stable Cycle", "100 stable days in a row", 5_000, emptyList()),
    METROPOLIS("Metropolis", "Reach 5,000 population", 10_000, emptyList());

    companion object {
        /** The tools a fresh city starts with; every other tool arrives with a milestone. */
        val START_TOOLS = setOf(
            Tool.ROAD,
            Tool.RESIDENTIAL,
            Tool.COMMERCIAL,
            Tool.INDUSTRIAL,
            Tool.PARK,
            Tool.PLANT,
            Tool.WATER,
            Tool.PIPE,
            Tool.POWERLINE,
            Tool.BULLDOZE,
            Tool.PAN,
            Tool.INFO
        )
    }
}

/** Things the UI may want to announce. */
sealed class CityEvent {
    data class Built(val zone: ZoneType, val col: Int, val row: Int) : CityEvent()
    data class Grew(val zone: ZoneType, val level: Int, val col: Int, val row: Int) : CityEvent()
    data class Upgraded(val zone: ZoneType, val level: Int) : CityEvent()
    data class MonthFinished(
        val month: Int,
        val year: Int,
        val net: Int,
        val population: Int
    ) : CityEvent()

    data class Merged(val zone: ZoneType, val col: Int, val row: Int) : CityEvent()

    /** A building emptied out and was abandoned: the cell "died". */
    data class Died(val zone: ZoneType, val col: Int, val row: Int) : CityEvent()

    data class MilestoneReached(val milestone: Milestone) : CityEvent()

    /** A building with no fire cover has caught fire. */
    data class FireStarted(val zone: ZoneType, val col: Int, val row: Int) : CityEvent()

    /** Fire crews reached a burning building and put it out. */
    data class FireOut(val zone: ZoneType, val col: Int, val row: Int) : CityEvent()

    /** Nobody reached a fire in time and the building was gutted. */
    data class FireDestroyed(val zone: ZoneType, val col: Int, val row: Int) : CityEvent()

    /**
     * Crime got worse: [stage] climbs from 1 (first report) to 3 (everywhere).
     * The UI announces it with a police siren and can jump to ([col], [row]).
     */
    data class CrimeReported(
        val text: String,
        val count: Int,
        val stage: Int,
        val col: Int,
        val row: Int
    ) : CityEvent()

    data class Notice(val text: String) : CityEvent()
}
