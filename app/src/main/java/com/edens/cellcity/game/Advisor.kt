package com.edens.cellcity.game

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** How the citizens feel about the city right now. */
enum class CityMood(val label: String, val quote: String) {
    THRIVING("Thriving", "People are proud of this city - the streets feel alive."),
    CONTENT("Content", "Most folks are settled, though a few still grumble."),
    UNEASY("Uneasy", "There is a nervous mood on the street; citizens want answers."),
    ANGRY("Angry", "People are furious with city hall and some are packing their bags.")
}

/** How loudly an issue is shouting for attention. Order matters: worst first. */
enum class IssueLevel { CRITICAL, WARNING, NOTICE }

/** One thing the advisor wants the mayor to know. */
data class CityIssue(val title: String, val detail: String, val level: IssueLevel)

/** The advisor's read on the city: the mood, what is broken, and what is going well. */
data class AdvisorReport(
    val mood: CityMood,
    val headline: String,
    val issues: List<CityIssue>,
    val cheers: List<String>
) {
    /** A single line for banners and badges. */
    val summary: String
        get() = if (issues.isEmpty()) {
            "${mood.label} - citizens have no complaints"
        } else {
            "${mood.label} - ${issues.first().title}"
        }
}

/**
 * Reads the simulation and turns it into plain language: how the citizens feel
 * and which problems are worth fixing next. Deliberately read-only and free of
 * Android imports so the advice can be unit tested.
 */
object CityAdvisor {

    private const val MAX_ISSUES = 6
    private const val MAX_CHEERS = 3

    fun report(engine: CityEngine): AdvisorReport {
        var developed = 0
        var blackouts = 0
        var dry = 0
        var cutOff = 0
        for (r in 0 until engine.rows) {
            for (c in 0 until engine.cols) {
                val t = engine.tile(c, r) ?: continue
                if (!t.developed) continue
                developed++
                if (!t.powered) blackouts++
                if (!t.watered) dry++
                if (!t.roadAccess) cutOff++
            }
        }

        val population = engine.population
        val jobs = engine.jobs
        val unemployment = if (population == 0) {
            0f
        } else {
            clamp(1f - jobs / (population * 0.55f), 0f, 1f)
        }

        val issues = ArrayList<CityIssue>()
        val cheers = ArrayList<String>()

        // --- things that are broken ----------------------------------------
        if (population == 0) {
            issues.add(
                CityIssue(
                    "Nobody lives here yet",
                    "Lay a ROAD, zone RES beside it, then power and water the lot.",
                    IssueLevel.NOTICE
                )
            )
        }
        if (engine.activeFires > 0) {
            issues.add(
                CityIssue(
                    "Buildings are on fire",
                    "${engine.activeFires} burning - fire crews reach " +
                        "${percent(engine.fireCoverage)} of the city.",
                    IssueLevel.CRITICAL
                )
            )
        }
        if (engine.activeCrime >= 3) {
            issues.add(
                CityIssue(
                    "Crime is spreading",
                    "${engine.activeCrime} buildings hit - police reach " +
                        "${percent(engine.policeCoverage)} of the city.",
                    if (engine.activeCrime >= Config.CRIME_WAVE) {
                        IssueLevel.CRITICAL
                    } else {
                        IssueLevel.WARNING
                    }
                )
            )
        }
        if (blackouts > 0) {
            issues.add(
                CityIssue(
                    if (blackouts > developed / 2) "Half the city has no power" else "Blackouts",
                    "$blackouts building(s) are off the grid - add a PLANT or run cables.",
                    if (blackouts > developed / 2) IssueLevel.CRITICAL else IssueLevel.WARNING
                )
            )
        }
        if (dry > 0) {
            issues.add(
                CityIssue(
                    "Dry taps",
                    "$dry building(s) have no water - drop a WATER tower within reach.",
                    if (dry > developed / 2) IssueLevel.CRITICAL else IssueLevel.WARNING
                )
            )
        }
        if (cutOff > 0) {
            issues.add(
                CityIssue(
                    "Cut off from the roads",
                    "$cutOff building(s) need a street on at least one side.",
                    IssueLevel.WARNING
                )
            )
        }
        if (unemployment > 0.18f) {
            issues.add(
                CityIssue(
                    "Too few jobs",
                    "$population residents share only $jobs jobs - zone COM or IND.",
                    if (unemployment > 0.45f) IssueLevel.CRITICAL else IssueLevel.WARNING
                )
            )
        }
        if (population >= 40 && engine.policeCoverage < 0.55f) {
            issues.add(
                CityIssue(
                    "Crime is climbing",
                    "Police reach ${percent(engine.policeCoverage)} of the city - " +
                        "${engine.crimeRiskLots} built lots are unpoliced.",
                    if (engine.policeCoverage < 0.2f) IssueLevel.CRITICAL else IssueLevel.WARNING
                )
            )
        }
        if (population >= 40 && engine.fireCoverage < 0.5f) {
            issues.add(
                CityIssue(
                    "Fire cover is thin",
                    "Only ${percent(engine.fireCoverage)} of buildings sit near a FIRE station - " +
                        "${engine.fireRiskLots} can catch fire.",
                    IssueLevel.WARNING
                )
            )
        }
        if (population >= 60 && engine.healthCoverage < 0.5f) {
            issues.add(
                CityIssue(
                    "The hospital is overstretched",
                    "Health cover is ${percent(engine.healthCoverage)} - build a HOSPITAL.",
                    IssueLevel.WARNING
                )
            )
        }
        if (engine.pollution > 0.45f) {
            issues.add(
                CityIssue(
                    "The air is filthy",
                    "Smog is at ${percent(engine.pollution)} - add parks and pull industry away from homes.",
                    if (engine.pollution > 0.7f) IssueLevel.CRITICAL else IssueLevel.WARNING
                )
            )
        }
        if (engine.funds < 0) {
            issues.add(
                CityIssue(
                    "The treasury is empty",
                    "The city is ${money(engine.funds)} in the red - raise taxes or cut upkeep.",
                    IssueLevel.CRITICAL
                )
            )
        } else if (engine.netPerDay < 0) {
            issues.add(
                CityIssue(
                    "Spending more than it earns",
                    "Upkeep is ${money(engine.expensesPerDay.roundToInt())}/day against " +
                        "${money(engine.incomePerDay.roundToInt())}/day of tax.",
                    IssueLevel.WARNING
                )
            )
        }
        if (engine.taxRate > 15) {
            issues.add(
                CityIssue(
                    "Taxes are squeezing people",
                    "At ${engine.taxRate}% citizens pay for the budget with their mood.",
                    IssueLevel.WARNING
                )
            )
        }
        if (engine.subwayCount > 0 && engine.subwayStationCount == 0) {
            issues.add(
                CityIssue(
                    "Tunnels with no platforms",
                    "Subway lines only carry riders once a SUB STN stands beside them.",
                    IssueLevel.NOTICE
                )
            )
        }
        if (population >= 150 && engine.railCount + engine.subwayStationCount == 0) {
            issues.add(
                CityIssue(
                    "No public transit",
                    "A city this size needs RAIL or a subway; transit speeds up growth.",
                    IssueLevel.NOTICE
                )
            )
        }
        if (population >= 80 && engine.parkCount == 0) {
            issues.add(
                CityIssue(
                    "Nowhere to relax",
                    "Citizens have no PARK to unwind in, so happiness drags.",
                    IssueLevel.NOTICE
                )
            )
        }
        if (engine.vacantZoneCount >= 3 &&
            engine.vacantZoneCount * 2 >= engine.zoneCount &&
            blackouts == 0 && dry == 0 && cutOff == 0
        ) {
            issues.add(
                CityIssue(
                    "Zoned lots sit empty",
                    "${engine.vacantZoneCount} lots never grew - demand or services are missing.",
                    IssueLevel.NOTICE
                )
            )
        }
        if (engine.funds < 0 && engine.taxRate <= 8) {
            issues.add(
                CityIssue(
                    "Not much room left to tax",
                    "At ${engine.taxRate}% there is little to raise; cut upkeep instead.",
                    IssueLevel.NOTICE
                )
            )
        }

        // --- things that are going well ------------------------------------
        if (engine.policeCoverage >= 0.8f) cheers.add("Police cover almost every block")
        if (engine.fireCoverage >= 0.8f) cheers.add("Fire crews reach the whole city")
        if (engine.healthCoverage >= 0.8f) cheers.add("Hospitals are within reach of everyone")
        if (engine.parkCount >= 3) cheers.add("There is green space to breathe in")
        if (engine.netPerDay > 0 && engine.funds >= 0) cheers.add("The books balance")
        if (engine.subwayStationCount > 0 || engine.railCount >= 4) {
            cheers.add("Public transit is moving people")
        }
        if (population > 0 && jobs >= population * 0.5f) cheers.add("Everyone who wants work has a job")
        if (engine.happiness >= 75f) cheers.add("People genuinely like living here")

        val issuesSorted = issues.sortedBy { it.level.ordinal }.take(MAX_ISSUES)
        val mood = moodFor(engine, unemployment, blackouts, dry)
        return AdvisorReport(
            mood = mood,
            headline = headline(engine, mood, developed, unemployment),
            issues = issuesSorted,
            cheers = cheers.take(MAX_CHEERS)
        )
    }

    private fun moodFor(
        engine: CityEngine,
        unemployment: Float,
        blackouts: Int,
        dry: Int
    ): CityMood {
        var score = engine.happiness
        if (engine.funds < 0) score -= 12f
        if (unemployment > 0.25f) score -= 8f
        if (engine.taxRate > 16) score -= 6f
        if (blackouts > 0 || dry > 0) score -= 5f
        return when {
            score >= 82f -> CityMood.THRIVING
            score >= 62f -> CityMood.CONTENT
            score >= 42f -> CityMood.UNEASY
            else -> CityMood.ANGRY
        }
    }

    private fun headline(
        engine: CityEngine,
        mood: CityMood,
        developed: Int,
        unemployment: Float
    ): String {
        val happiness = engine.happiness.roundToInt()
        val jobsLine = if (engine.population == 0) "no residents yet" else "${percent(unemployment)} jobless"
        return "${mood.label} · $happiness/100 happy · $developed built · $jobsLine"
    }

    private fun percent(value: Float): String =
        "${(clamp(value, 0f, 1f) * 100f).roundToInt()}%"

    private fun money(value: Int): String {
        val sign = if (value < 0) "-$" else "$"
        return sign + kotlin.math.abs(value)
    }

    private fun clamp(v: Float, lo: Float, hi: Float): Float = max(lo, min(hi, v))
}
