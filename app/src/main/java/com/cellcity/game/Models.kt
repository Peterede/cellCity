package com.cellcity.game

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
    HIGHWAY;

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
            this == FIRE || this == HOSPITAL || this == STATION

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
        }
}

/** The player's current build tool. */
enum class Tool(val zone: ZoneType, val label: String) {
    ROAD(ZoneType.ROAD, "ROAD"),
    HIGHWAY(ZoneType.HIGHWAY, "HWY"),
    RAIL(ZoneType.RAIL, "RAIL"),
    STATION(ZoneType.STATION, "STATION"),
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
    PAN(ZoneType.NONE, "PAN");

    /** True for tools that bury a layer instead of occupying the tile. */
    val isLayer: Boolean
        get() = this == PIPE || this == POWERLINE

    val buildsZones: Boolean
        get() = this != PAN && this != BULLDOZE

    /** Civic buildings snap to one spot instead of being painted along a drag. */
    val singlePlacement: Boolean
        get() = zone.isService || zone.isMultiTile
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

    /** True when this tile is one quarter of a 2x2 block (merged lots or an airport). */
    var merged: Boolean = false
    var originCol: Int = -1
    var originRow: Int = -1

    val developed: Boolean
        get() = zone.isZone && level > 0

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
    CANNOT("Can't build there"),
    OUT_OF_BOUNDS("")
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

    data class Notice(val text: String) : CityEvent()
}
