package com.edens.cellcity.game

/**
 * Milestones that stay unlocked across every city the player founds, so a new
 * city is progress rather than a reset. Pure Kotlin so it can be unit tested.
 */
enum class Achievement(val title: String, val detail: String) {
    FOUNDED("Founder", "Found your first city"),
    HAMLET("Hamlet", "Reach 100 residents"),
    TOWN("Town", "Reach 500 residents"),
    SKYLINE("Skyline", "Merge four lots into one large building"),
    TRANSIT("Transit City", "Run a station on a live rail or subway line"),
    GREEN("Green Belt", "Keep five parks in one city"),
    TREASURY("War Chest", "Bank $25,000"),
    FIREPROOF("Fireproof", "Cover 90% of a built city with fire crews")
}

/** Reads a city and reports which achievements it satisfies right now. */
object AchievementJudge {
    fun earned(engine: CityEngine): Set<Achievement> {
        val earned = LinkedHashSet<Achievement>()
        earned.add(Achievement.FOUNDED)
        if (engine.population >= 100) earned.add(Achievement.HAMLET)
        if (engine.population >= 500) earned.add(Achievement.TOWN)
        if (engine.mergedBlockCount > 0) earned.add(Achievement.SKYLINE)
        if (engine.stationCount > 0 || engine.subwayStationCount > 0) {
            earned.add(Achievement.TRANSIT)
        }
        if (engine.parkCount >= 5) earned.add(Achievement.GREEN)
        if (engine.funds >= 25_000) earned.add(Achievement.TREASURY)
        if (engine.buildingCount >= 20 && engine.fireCoverage >= 0.9f) {
            earned.add(Achievement.FIREPROOF)
        }
        return earned
    }
}
