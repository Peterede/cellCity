package com.edens.cellcity.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class CityEngineTest {

    private fun city(seed: Long = 7L) = CityEngine(seed = seed, enforceUnlocks = false)

    private fun progressiveCity(seed: Long = 7L) = CityEngine(seed = seed)

    @Test
    fun landscapeHasWaterForestAndAClearStart() {
        val e = city(11)
        var water = 0
        var forest = 0
        for (r in 0 until e.rows) {
            for (c in 0 until e.cols) {
                when (e.tile(c, r)!!.terrain) {
                    Terrain.WATER -> water++
                    Terrain.FOREST -> forest++
                    else -> Unit
                }
            }
        }
        assertTrue("river and lake exist", water > 20)
        assertTrue("forests exist", forest > 5)
        assertEquals(Terrain.GRASS, e.tile(e.cols / 2, e.rows / 2)!!.terrain)
        assertEquals(Config.START_FUNDS, e.funds)
    }

    @Test
    fun roadsCostMoneyAndOccupyTheTile() {
        val e = city()
        val before = e.funds
        assertEquals(BuildResult.OK, e.build(Tool.ROAD, 12, 12))
        assertEquals(ZoneType.ROAD, e.tile(12, 12)!!.zone)
        assertEquals(before - Config.COST_ROAD, e.funds)
        assertEquals(1, e.roadCount)

        // building the same road again is a no-op, not a second charge
        assertEquals(BuildResult.OK, e.build(Tool.ROAD, 12, 12))
        assertEquals(before - Config.COST_ROAD, e.funds)
    }

    @Test
    fun zoningIsBlockedOnWaterButBridgesAreAllowed() {
        val e = city(21)
        val water = findTile(e) { it.terrain == Terrain.WATER }
        assertNotNull(water)
        val (wc, wr) = water!!
        val before = e.funds

        assertEquals(BuildResult.WATER, e.build(Tool.RESIDENTIAL, wc, wr))
        assertEquals(before, e.funds)
        assertEquals(ZoneType.NONE, e.tile(wc, wr)!!.zone)

        assertEquals(BuildResult.OK, e.build(Tool.ROAD, wc, wr))
        assertEquals(before - Config.COST_BRIDGE, e.funds)
        assertEquals(ZoneType.ROAD, e.tile(wc, wr)!!.zone)
        assertEquals(Terrain.WATER, e.tile(wc, wr)!!.terrain)

        assertEquals(BuildResult.OK, e.build(Tool.BULLDOZE, wc, wr))
        assertEquals(ZoneType.NONE, e.tile(wc, wr)!!.zone)
        assertEquals(Terrain.WATER, e.tile(wc, wr)!!.terrain)
    }

    @Test
    fun zonesWithoutPowerNeverGrow() {
        val e = city()
        assertEquals(BuildResult.OK, e.build(Tool.ROAD, 12, 12))
        assertEquals(BuildResult.OK, e.build(Tool.RESIDENTIAL, 12, 11))
        val tile = e.tile(12, 11)!!
        assertTrue("road access is detected", tile.roadAccess)
        assertEquals(false, tile.powered)

        repeat(250) { e.tick() }
        assertEquals(0, e.tile(12, 11)!!.level)
        assertEquals(0, e.population)
    }

    @Test
    fun poweredRoadConnectedZonesGrowAndPopulate() {
        val e = city(7)
        assertEquals(BuildResult.OK, e.build(Tool.PLANT, 10, 10))
        assertEquals(BuildResult.OK, e.build(Tool.ROAD, 10, 11))
        assertEquals(BuildResult.OK, e.build(Tool.WATER, 12, 14))
        assertEquals(BuildResult.OK, e.build(Tool.RESIDENTIAL, 11, 11))
        assertTrue("plant energises the connected zone", e.tile(11, 11)!!.powered)
        assertTrue("the water tower pipes water to the zone", e.tile(11, 11)!!.watered)

        repeat(320) { e.tick() }
        assertTrue("a home appeared", e.tile(11, 11)!!.level >= 1)
        assertTrue("residents moved in", e.population > 0)
        assertTrue("happiness stays sane", e.happiness in 0f..100f)
    }

    @Test
    fun zonesWithoutWaterStayVacant() {
        val e = city(7)
        assertEquals(BuildResult.OK, e.build(Tool.PLANT, 10, 10))
        assertEquals(BuildResult.OK, e.build(Tool.ROAD, 10, 11))
        assertEquals(BuildResult.OK, e.build(Tool.RESIDENTIAL, 11, 11))
        assertEquals(false, e.tile(11, 11)!!.watered)

        repeat(240) { e.tick() }
        assertEquals("a dry lot never grows", 0, e.tile(11, 11)!!.level)
        assertEquals(0, e.population)

        // once a tower is in range the same lot fills in
        assertEquals(BuildResult.OK, e.build(Tool.WATER, 12, 14))
        repeat(240) { e.tick() }
        assertTrue(e.tile(11, 11)!!.level >= 1)
        assertTrue(e.population > 0)
    }

    @Test
    fun railLinesGiveAdjacentLotsTransitAccess() {
        val e = city(7)
        assertEquals(BuildResult.OK, e.build(Tool.RAIL, 11, 11))
        assertEquals(BuildResult.OK, e.build(Tool.RAIL, 12, 11))
        assertEquals(BuildResult.OK, e.build(Tool.RESIDENTIAL, 11, 10))
        assertTrue("the lot beside the line gets transit access", e.tile(11, 10)!!.railAccess)
        assertTrue("rail fees are charged", e.funds < Config.START_FUNDS)

        assertEquals(BuildResult.OK, e.build(Tool.RESIDENTIAL, 11, 13))
        assertEquals("lots two tiles away stay off the line", false, e.tile(11, 13)!!.railAccess)

        // a single rail tile is not a line yet
        assertEquals(BuildResult.OK, e.build(Tool.RAIL, 12, 14))
        assertEquals(false, e.tile(13, 14)!!.railAccess)
    }

    @Test
    fun civicServicesCoverNearbyBuildings() {
        val e = city(9)
        assertEquals(BuildResult.OK, e.build(Tool.PLANT, 10, 10))
        assertEquals(BuildResult.OK, e.build(Tool.ROAD, 10, 11))
        assertEquals(BuildResult.OK, e.build(Tool.WATER, 12, 14))
        assertEquals(BuildResult.OK, e.build(Tool.RESIDENTIAL, 11, 11))
        repeat(240) { e.tick() }
        assertTrue("a home exists to protect", e.tile(11, 11)!!.developed)

        assertEquals(0f, e.policeCoverage, 0.001f)
        assertEquals(0f, e.fireCoverage, 0.001f)
        assertEquals(0f, e.healthCoverage, 0.001f)

        assertEquals(BuildResult.OK, e.build(Tool.POLICE, 12, 11))
        assertEquals(BuildResult.OK, e.build(Tool.FIRE, 11, 12))
        assertEquals(BuildResult.OK, e.build(Tool.HOSPITAL, 13, 11))
        assertTrue("police reach the house", e.policeCoverage > 0.9f)
        assertTrue("the fire station reaches the house", e.fireCoverage > 0.9f)
        assertTrue("the hospital reaches the house", e.healthCoverage > 0.9f)
        assertEquals(1, e.policeCount)
        assertEquals(1, e.fireCount)
        assertEquals(1, e.hospitalCount)
    }

    @Test
    fun coverageOverlayFlagsFollowTheirOwnService() {
        val e = city(9)
        assertEquals(BuildResult.OK, e.build(Tool.FIRE, 12, 12))

        val near = e.tile(12, 14)!!
        assertTrue("the fire view marks a covered tile", near.coveredBy(CoverageOverlay.FIRE))
        assertEquals("police coverage is not borrowed", false, near.coveredBy(CoverageOverlay.POLICE))
        assertEquals("health coverage is not borrowed", false, near.coveredBy(CoverageOverlay.HEALTH))
        assertTrue("the normal view marks every tile", near.coveredBy(CoverageOverlay.NONE))

        val far = e.tile(0, 0)!!
        assertEquals("the fire view leaves distant tiles clear", false, far.coveredBy(CoverageOverlay.FIRE))
    }

    @Test
    fun fourLevelTwoLotsMergeIntoOneBigBuilding() {
        val e = city(13)
        assertEquals(BuildResult.OK, e.build(Tool.PLANT, 10, 9))
        assertEquals(BuildResult.OK, e.build(Tool.WATER, 13, 14))
        // a ring of roads around a 2x2 residential block at rows 11..12, cols 10..11
        for (c in 10..11) {
            assertEquals(BuildResult.OK, e.build(Tool.ROAD, c, 10))
            assertEquals(BuildResult.OK, e.build(Tool.ROAD, c, 13))
        }
        for (r in 11..12) {
            assertEquals(BuildResult.OK, e.build(Tool.ROAD, 9, r))
            assertEquals(BuildResult.OK, e.build(Tool.ROAD, 12, r))
        }
        for (r in 11..12) {
            for (c in 10..11) {
                assertEquals(BuildResult.OK, e.build(Tool.RESIDENTIAL, c, r))
            }
        }
        // keep the block served: an uncovered block would burn down mid-test
        assertEquals(BuildResult.OK, e.build(Tool.FIRE, 13, 10))
        assertEquals(BuildResult.OK, e.build(Tool.POLICE, 14, 10))

        // stop as soon as the block forms; the city is left running for 700 days
        for (i in 0 until 700) {
            e.tick()
            if (e.tile(10, 11)!!.merged) break
        }

        val block = e.tile(10, 11)!!
        assertTrue("all four lots reached level 2", block.level >= Config.MERGE_LEVEL)
        assertTrue("the block merged", block.merged)
        assertEquals(10, block.originCol)
        assertEquals(11, block.originRow)
        assertTrue(e.mergedBlockCount >= 1)
        assertEquals(10, e.tile(11, 12)!!.originCol)
        assertEquals(11, e.tile(11, 12)!!.originRow)
        assertTrue(
            "a merged lot holds more than a normal one",
            e.capacityForTile(block) > e.capacityOf(ZoneType.RESIDENTIAL, block.level)
        )

        val loaded = CityEngine.load(e.save())
        assertNotNull("merged blocks survive a save", loaded)
        assertTrue(loaded!!.tile(10, 11)!!.merged)
        assertEquals(10, loaded.tile(10, 11)!!.originCol)
    }

    @Test
    fun airportsNeedAClearBlockBesideARoad() {
        val e = city(5)
        assertEquals(BuildResult.OK, e.build(Tool.ROAD, 10, 10))
        val before = e.funds

        assertEquals("no road beside the block", BuildResult.NO_ROAD, e.build(Tool.AIRPORT, 12, 12))
        assertEquals("a refused airport charges nothing", before, e.funds)
        assertEquals(ZoneType.NONE, e.tile(12, 12)!!.zone)

        assertEquals(BuildResult.OK, e.build(Tool.AIRPORT, 11, 10))
        assertEquals("the 2x2 airport is charged once", before - Config.COST_AIRPORT, e.funds)
        for (r in 10..11) {
            for (c in 11..12) {
                assertEquals(ZoneType.AIRPORT, e.tile(c, r)!!.zone)
                assertTrue(e.tile(c, r)!!.merged)
                assertEquals(11, e.tile(c, r)!!.originCol)
                assertEquals(10, e.tile(c, r)!!.originRow)
            }
        }
        assertEquals(1, e.airportCount)
        assertEquals(Config.AIRPORT_JOBS, e.tile(11, 10)!!.jobs)

        e.tick()
        assertTrue("the airport hires workers", e.jobs >= Config.AIRPORT_JOBS)
        val afterTick = e.funds
        assertEquals(BuildResult.OK, e.build(Tool.AIRPORT, 11, 10))
        assertEquals("building the same airport is a no-op", afterTick, e.funds)

        val afterBuild = e.funds
        assertEquals(BuildResult.OK, e.build(Tool.BULLDOZE, 12, 11))
        for (r in 10..11) {
            for (c in 11..12) {
                assertEquals(ZoneType.NONE, e.tile(c, r)!!.zone)
            }
        }
        assertEquals(0, e.airportCount)
        assertTrue("the whole airport was refunded once", e.funds > afterBuild)
    }

    @Test
    fun aMixedCityPaysForItself() {
        val e = city(3)
        assertEquals(BuildResult.OK, e.build(Tool.PLANT, 10, 10))
        assertEquals(BuildResult.OK, e.build(Tool.WATER, 12, 14))
        for (c in 10..14) {
            assertEquals(BuildResult.OK, e.build(Tool.ROAD, c, 11))
        }
        assertEquals(BuildResult.OK, e.build(Tool.RESIDENTIAL, 11, 10))
        assertEquals(BuildResult.OK, e.build(Tool.COMMERCIAL, 12, 10))
        assertEquals(BuildResult.OK, e.build(Tool.INDUSTRIAL, 13, 10))
        assertEquals(BuildResult.OK, e.build(Tool.RESIDENTIAL, 11, 12))
        assertEquals(BuildResult.OK, e.build(Tool.COMMERCIAL, 12, 12))
        assertEquals(BuildResult.OK, e.build(Tool.INDUSTRIAL, 13, 12))
        assertEquals(BuildResult.OK, e.build(Tool.PARK, 14, 12))

        repeat(400) { e.tick() }
        assertTrue("people live here", e.population > 0)
        assertTrue("shops or factories hired people", e.jobs > 0)
        assertTrue("tax income beats upkeep", e.netPerDay > 0)
        assertTrue("buildings developed", e.buildingCount > 0)
    }

    @Test
    fun bulldozingRefundsAQuarterAndClearsTheTile() {
        val e = city()
        assertEquals(BuildResult.OK, e.build(Tool.PARK, 12, 12))
        val afterBuild = e.funds
        assertEquals(BuildResult.OK, e.build(Tool.BULLDOZE, 12, 12))
        assertEquals(ZoneType.NONE, e.tile(12, 12)!!.zone)
        assertEquals(0, e.tile(12, 12)!!.level)
        val expected = afterBuild + (Config.COST_PARK * Config.BULLDOZE_REFUND).toInt()
        assertTrue("refund was paid", e.funds >= afterBuild)
        assertTrue("refund is a quarter (rounded)", e.funds <= expected + 1)
    }

    @Test
    fun undoPutsBackTheLastDragAndItsCost() {
        val e = city()
        val before = e.funds
        e.beginAction()
        assertEquals(BuildResult.OK, e.build(Tool.ROAD, 12, 12))
        assertEquals(BuildResult.OK, e.build(Tool.ROAD, 13, 12))
        assertTrue("a drag is one undo step", e.endAction())
        assertTrue(e.canUndo)
        assertEquals(before - 2 * Config.COST_ROAD, e.funds)

        assertEquals("build", e.undo())
        assertEquals(ZoneType.NONE, e.tile(12, 12)!!.zone)
        assertEquals(ZoneType.NONE, e.tile(13, 12)!!.zone)
        assertEquals("both roads are refunded", before, e.funds)
        assertEquals(false, e.canUndo)
        assertNull(e.undo())
        assertEquals("an empty action is not undoable", false, e.endAction())
    }

    @Test
    fun undoRestoresABulldozedBuildingAndItsPeople() {
        val e = city(109)
        val (c, r) = findDryRun(e, 2)!!
        assertEquals(BuildResult.OK, e.build(Tool.RESIDENTIAL, c, r))
        val home = e.tile(c, r)!!
        home.level = 2
        home.population = 18
        home.jobs = 2
        val before = e.funds

        e.beginAction()
        assertEquals(BuildResult.OK, e.build(Tool.BULLDOZE, c, r))
        assertTrue(e.endAction())
        assertTrue("bulldozing pays a refund", e.funds > before)
        assertEquals(ZoneType.NONE, home.zone)

        assertEquals("bulldoze", e.undo())
        assertEquals(ZoneType.RESIDENTIAL, home.zone)
        assertEquals(2, home.level)
        assertEquals(18, home.population)
        assertEquals("the population is re-tallied", 18, e.population)
        assertEquals("the refund is taken back", before, e.funds)
    }

    @Test
    fun undoClearsAWholeAirportBlock() {
        val e = city(5)
        assertEquals(BuildResult.OK, e.build(Tool.ROAD, 10, 10))
        val before = e.funds
        e.beginAction()
        assertEquals(BuildResult.OK, e.build(Tool.AIRPORT, 11, 10))
        assertTrue(e.endAction())
        assertEquals(1, e.airportCount)
        assertEquals(before - Config.COST_AIRPORT, e.funds)

        assertEquals("build", e.undo())
        for (r in 10..11) {
            for (c in 11..12) {
                assertEquals(ZoneType.NONE, e.tile(c, r)!!.zone)
                assertEquals(false, e.tile(c, r)!!.merged)
            }
        }
        assertEquals(0, e.airportCount)
        assertEquals(before, e.funds)
    }

    @Test
    fun undoStepsBackThroughRecentActions() {
        val e = city(113)
        val (c, r) = findDryRun(e, 2)!!
        e.beginAction()
        assertEquals(BuildResult.OK, e.build(Tool.PARK, c, r))
        assertTrue(e.endAction())
        e.beginAction()
        assertEquals(BuildResult.OK, e.build(Tool.PARK, c + 1, r))
        assertTrue(e.endAction())

        assertEquals("build", e.undo())
        assertEquals(ZoneType.NONE, e.tile(c + 1, r)!!.zone)
        assertEquals(ZoneType.PARK, e.tile(c, r)!!.zone)
        assertEquals("build", e.undo())
        assertEquals(ZoneType.NONE, e.tile(c, r)!!.zone)
        assertEquals(false, e.canUndo)
    }

    @Test
    fun roadsOnlyReachOrthogonalNeighbours() {
        val e = city()
        assertEquals(BuildResult.OK, e.build(Tool.ROAD, 12, 12))
        assertEquals(BuildResult.OK, e.build(Tool.RESIDENTIAL, 13, 13))
        repeat(60) { e.tick() }
        assertEquals(false, e.tile(13, 13)!!.roadAccess)
        assertEquals(0, e.tile(13, 13)!!.level)
    }

    @Test
    fun everyMonthReportsFinances() {
        val e = city()
        var report: CityEvent.MonthFinished? = null
        repeat(Config.DAYS_PER_MONTH) {
            e.tick()
            e.drainEvents().filterIsInstance<CityEvent.MonthFinished>().lastOrNull()?.let { report = it }
        }
        assertNotNull("monthly report emitted", report)
        assertEquals(1, report!!.month)
        assertEquals(Config.START_YEAR, report!!.year)
    }

    @Test
    fun saveAndLoadPreserveTheCity() {
        val e = city(31)
        assertEquals(BuildResult.OK, e.build(Tool.PLANT, 10, 10))
        assertEquals(BuildResult.OK, e.build(Tool.ROAD, 10, 11))
        assertEquals(BuildResult.OK, e.build(Tool.WATER, 12, 14))
        assertEquals(BuildResult.OK, e.build(Tool.RESIDENTIAL, 11, 11))
        assertEquals(BuildResult.OK, e.build(Tool.PARK, 9, 11))
        repeat(90) { e.tick() }

        val data = e.save()
        val loaded = CityEngine.load(data)
        assertNotNull(loaded)
        assertEquals(e.funds, loaded!!.funds)
        assertEquals(e.day, loaded.day)
        assertEquals(e.month, loaded.month)
        assertEquals(e.year, loaded.year)
        assertEquals(e.population, loaded.population)
        assertEquals(e.jobs, loaded.jobs)
        for (r in 0 until e.rows) {
            for (c in 0 until e.cols) {
                val a = e.tile(c, r)!!
                val b = loaded.tile(c, r)!!
                assertEquals(a.terrain, b.terrain)
                assertEquals(a.zone, b.zone)
                assertEquals(a.level, b.level)
                assertEquals(a.population, b.population)
                assertEquals(a.jobs, b.jobs)
                assertEquals(a.merged, b.merged)
                assertEquals(a.originCol, b.originCol)
                assertEquals(a.originRow, b.originRow)
            }
        }
        loaded.tick()
        assertTrue(loaded.funds != Int.MIN_VALUE)
    }

    @Test
    fun loadRejectsJunk() {
        assertNull(CityEngine.load(null))
        assertNull(CityEngine.load(""))
        assertNull(CityEngine.load("hello"))
        assertNull(CityEngine.load("CC1|1000"))
        assertNull(CityEngine.load("CC2|1|1|1|2026|72.0|0.0|7|junk"))
    }

    @Test
    fun newBuildingsStartVacantThenFill() {
        val e = city(5)
        assertEquals(BuildResult.OK, e.build(Tool.PLANT, 10, 10))
        assertEquals(BuildResult.OK, e.build(Tool.ROAD, 10, 11))
        assertEquals(BuildResult.OK, e.build(Tool.WATER, 12, 14))
        assertEquals(BuildResult.OK, e.build(Tool.RESIDENTIAL, 11, 11))
        var grew = false
        repeat(200) {
            e.tick()
            if (e.tile(11, 11)!!.level > 0) grew = true
        }
        assertTrue(grew)
        val home = e.tile(11, 11)!!
        assertTrue(home.population > 0)
        assertTrue(home.population <= e.capacityOf(ZoneType.RESIDENTIAL, home.level))
    }

    @Test
    fun fundsCannotGoNegativeFromBuilding() {
        val e = city()
        while (e.funds >= Config.COST_PLANT) {
            val spot = findTile(e) { it.zone == ZoneType.NONE && it.terrain != Terrain.WATER } ?: break
            e.build(Tool.PLANT, spot.first, spot.second)
        }
        val spot = findTile(e) { it.zone == ZoneType.NONE && it.terrain != Terrain.WATER }
        if (spot != null && e.funds < Config.COST_RESIDENTIAL) {
            val before = e.funds
            assertEquals(BuildResult.NO_FUNDS, e.build(Tool.RESIDENTIAL, spot.first, spot.second))
            assertEquals(before, e.funds)
        }
    }

    @Test
    fun waterPipesCarryWaterBeyondTowerRange() {
        val e = city(17)
        // tower, seven mains, then a lot further out than the tower can reach
        val (c, r) = findDryRun(e, 9)!!
        assertEquals(BuildResult.OK, e.build(Tool.WATER, c, r))
        assertEquals(BuildResult.OK, e.build(Tool.RESIDENTIAL, c + 4, r))
        assertEquals(BuildResult.OK, e.build(Tool.RESIDENTIAL, c + 8, r))
        assertEquals("the tower alone is out of range", false, e.tile(c + 8, r)!!.watered)

        for (i in 1..7) {
            assertEquals(BuildResult.OK, e.build(Tool.PIPE, c + i, r))
        }
        assertEquals(7, e.pipeCount)
        assertEquals("a main can run under a house", ZoneType.RESIDENTIAL, e.tile(c + 4, r)!!.zone)
        assertTrue(e.tile(c + 4, r)!!.pipe)
        assertTrue("the main waters the lot beside it", e.tile(c + 8, r)!!.watered)
        assertTrue("the lot over the main is watered too", e.tile(c + 4, r)!!.watered)
    }

    @Test
    fun pipesWithoutATowerStayDry() {
        val e = city(19)
        val (c, r) = findDryRun(e, 3)!!
        assertEquals(BuildResult.OK, e.build(Tool.PIPE, c, r))
        assertEquals(BuildResult.OK, e.build(Tool.PIPE, c + 1, r))
        assertEquals(BuildResult.OK, e.build(Tool.RESIDENTIAL, c + 2, r))
        assertTrue(e.tile(c, r)!!.pipe)
        assertEquals(ZoneType.NONE, e.tile(c, r)!!.zone)
        assertEquals("water needs a tower upstream", false, e.tile(c + 2, r)!!.watered)
    }

    @Test
    fun pipesCanCrossTheRiverForABridgePrice() {
        val e = city(21)
        val (wc, wr) = findTile(e) { it.terrain == Terrain.WATER }!!
        val before = e.funds
        assertEquals(BuildResult.OK, e.build(Tool.PIPE, wc, wr))
        assertEquals(before - Config.COST_PIPE_WATER, e.funds)
        assertTrue(e.tile(wc, wr)!!.pipe)
        assertEquals("a main does not occupy the water tile", ZoneType.NONE, e.tile(wc, wr)!!.zone)
    }

    @Test
    fun trainStationsSpreadTransitAccessAlongTheirLine() {
        val e = city(7)
        assertEquals(BuildResult.OK, e.build(Tool.RAIL, 11, 11))
        assertEquals(BuildResult.OK, e.build(Tool.RAIL, 12, 11))
        assertEquals(BuildResult.OK, e.build(Tool.RESIDENTIAL, 11, 13))
        assertEquals("the lot is too far from the line", false, e.tile(11, 13)!!.railAccess)

        val dry = findTile(e) { it.terrain != Terrain.WATER }!!
        assertEquals("a station needs rail beside it", BuildResult.NO_RAIL, e.build(Tool.STATION, dry.first, dry.second))

        assertEquals(BuildResult.OK, e.build(Tool.STATION, 11, 12))
        assertEquals(ZoneType.STATION, e.tile(11, 12)!!.zone)
        assertTrue("the station touches the line", e.tile(11, 12)!!.railAccess)
        assertTrue("the station reaches the lot", e.tile(11, 13)!!.railAccess)
        assertTrue("its reach ends five tiles out", e.tile(11, 17)!!.railAccess)
        assertEquals("beyond range there is no access", false, e.tile(11, 18)!!.railAccess)
        assertEquals(1, e.stationCount)

        val loaded = CityEngine.load(e.save())
        assertNotNull("stations survive a save", loaded)
        assertEquals(ZoneType.STATION, loaded!!.tile(11, 12)!!.zone)
        assertTrue(loaded.tile(11, 13)!!.railAccess)
    }

    @Test
    fun pipesRunUnderRoadsAndBuildings() {
        val e = city(23)
        val (c, r) = findDryRun(e, 2)!!
        assertEquals(BuildResult.OK, e.build(Tool.RESIDENTIAL, c, r))
        val before = e.funds
        assertEquals(BuildResult.OK, e.build(Tool.PIPE, c, r))
        assertEquals("the main is charged once", before - Config.COST_PIPE, e.funds)
        assertEquals(ZoneType.RESIDENTIAL, e.tile(c, r)!!.zone)
        assertTrue(e.tile(c, r)!!.pipe)

        assertEquals(BuildResult.OK, e.build(Tool.ROAD, c + 1, r))
        assertEquals(BuildResult.OK, e.build(Tool.PIPE, c + 1, r))
        assertEquals(ZoneType.ROAD, e.tile(c + 1, r)!!.zone)
        assertTrue("a main can run under a street", e.tile(c + 1, r)!!.pipe)
        assertEquals(2, e.pipeCount)

        assertEquals(BuildResult.OK, e.build(Tool.POWERLINE, c, r))
        assertTrue("a cable can run under a house", e.tile(c, r)!!.powerline)
        assertEquals(1, e.powerlineCount)

        val loaded = CityEngine.load(e.save())
        assertNotNull(loaded)
        assertEquals(ZoneType.RESIDENTIAL, loaded!!.tile(c, r)!!.zone)
        assertTrue("mains survive a save", loaded.tile(c, r)!!.pipe)
        assertTrue(loaded.tile(c + 1, r)!!.pipe)
        assertTrue("cables survive a save", loaded.tile(c, r)!!.powerline)
    }

    @Test
    fun bulldozingPullsUpTheLayersUnderABuilding() {
        val e = city(27)
        val (c, r) = findDryRun(e, 2)!!
        assertEquals(BuildResult.OK, e.build(Tool.RESIDENTIAL, c, r))
        assertEquals(BuildResult.OK, e.build(Tool.PIPE, c, r))
        assertEquals(BuildResult.OK, e.build(Tool.POWERLINE, c, r))
        val before = e.funds
        assertEquals(BuildResult.OK, e.build(Tool.BULLDOZE, c, r))
        assertEquals(ZoneType.NONE, e.tile(c, r)!!.zone)
        assertEquals(false, e.tile(c, r)!!.pipe)
        assertEquals(false, e.tile(c, r)!!.powerline)
        val refund = (
            (Config.COST_RESIDENTIAL + Config.COST_PIPE + Config.COST_POWERLINE) *
                Config.BULLDOZE_REFUND
            ).roundToInt()
        assertEquals("the buried layers are refunded too", before + refund, e.funds)
    }

    @Test
    fun undergroundPowerlinesCarryPowerAcrossOpenGround() {
        val e = city(41)
        val (c, r) = findDryRun(e, 7)!!
        assertEquals(BuildResult.OK, e.build(Tool.PLANT, c, r))
        assertEquals(BuildResult.OK, e.build(Tool.RESIDENTIAL, c + 6, r))
        assertEquals("no road or cable reaches the lot yet", false, e.tile(c + 6, r)!!.powered)

        val before = e.funds
        for (i in 1..5) {
            assertEquals(BuildResult.OK, e.build(Tool.POWERLINE, c + i, r))
        }
        assertEquals(5, e.powerlineCount)
        assertEquals("cables are charged once each", before - 5 * Config.COST_POWERLINE, e.funds)
        assertTrue("the buried cable energises the lot", e.tile(c + 6, r)!!.powered)
    }

    @Test
    fun highwaysWorkAsWideRoads() {
        val e = city(47)
        val (c, r) = findDryRun(e, 3)!!
        val before = e.funds
        assertEquals(BuildResult.OK, e.build(Tool.HIGHWAY, c, r))
        assertEquals(before - Config.COST_HIGHWAY, e.funds)
        assertEquals(ZoneType.HIGHWAY, e.tile(c, r)!!.zone)
        assertTrue("the highway carries its own road access", e.tile(c, r)!!.roadAccess)

        assertEquals(BuildResult.OK, e.build(Tool.RESIDENTIAL, c + 1, r))
        assertTrue("a highway counts as a road for its neighbours", e.tile(c + 1, r)!!.roadAccess)
        assertEquals(1, e.highwayCount)
        assertEquals(0, e.roadCount)

        val loaded = CityEngine.load(e.save())
        assertNotNull(loaded)
        assertEquals(ZoneType.HIGHWAY, loaded!!.tile(c, r)!!.zone)
        assertEquals(1, loaded.highwayCount)
    }

    @Test
    fun highwaysSpanWaterAsBridges() {
        val e = city(53)
        val (wc, wr) = findTile(e) { it.terrain == Terrain.WATER }!!
        val before = e.funds
        assertEquals(BuildResult.OK, e.build(Tool.HIGHWAY, wc, wr))
        assertEquals(before - Config.COST_HIGHWAY_BRIDGE, e.funds)
        assertEquals(ZoneType.HIGHWAY, e.tile(wc, wr)!!.zone)
        assertEquals("the crossing keeps the river underneath", Terrain.WATER, e.tile(wc, wr)!!.terrain)
    }

    @Test
    fun oldPipeZoneSavesLoadAsLayeredMains() {
        val e = city(29)
        val (c, r) = findDryRun(e, 2)!!
        assertEquals(BuildResult.OK, e.build(Tool.PIPE, c, r))

        // rewrite the CC6 save the way the previous version stored it: pipes as a zone
        val parts = e.save().split('|')
        val tiles = parts[11].split(';').joinToString(";") { tile ->
            val f = tile.split(',')
            val zone = if (f[11].toInt() != 0) ZoneType.PIPE.ordinal else f[1].toInt()
            (listOf(f[0], zone.toString()) + f.subList(2, 11)).joinToString(",")
        }
        val cc2 = (listOf("CC2") + parts.subList(1, 8) + listOf(tiles)).joinToString("|")

        val loaded = CityEngine.load(cc2)
        assertNotNull("old saves still load", loaded)
        assertTrue(loaded!!.tile(c, r)!!.pipe)
        assertEquals(ZoneType.NONE, loaded.tile(c, r)!!.zone)
        assertEquals(1, loaded.pipeCount)
    }

    @Test
    fun aFreshCityStartsWithOnlyTheBasicTools() {
        val e = progressiveCity()
        assertTrue(e.isUnlocked(Tool.ROAD))
        assertTrue(e.isUnlocked(Tool.RESIDENTIAL))
        assertTrue(e.isUnlocked(Tool.PLANT))
        assertTrue(e.isUnlocked(Tool.BULLDOZE))
        assertEquals(false, e.isUnlocked(Tool.RAIL))
        assertEquals(false, e.isUnlocked(Tool.AIRPORT))
        assertEquals(false, e.isUnlocked(Tool.POLICE))

        val (c, r) = findTile(e) { it.terrain != Terrain.WATER }!!
        assertEquals(BuildResult.LOCKED, e.build(Tool.RAIL, c, r))
        assertEquals(BuildResult.LOCKED, e.build(Tool.POLICE, c, r))
        assertEquals(BuildResult.LOCKED, e.build(Tool.AIRPORT, c, r))
        // a non-validating engine still lets the tests place anything
        assertEquals(BuildResult.OK, city().build(Tool.RAIL, c, r))
    }

    @Test
    fun reachingPopulationUnlocksToolsAndPaysABonus() {
        val e = progressiveCity()
        assertEquals(Milestone.FIRST_HOME, e.nextMilestone())

        // First Residents needs 10 people; force a population with a small farm of homes
        buildBasicTown(e)
        var bonusSeen = 0
        var reached: Milestone? = null
        repeat(900) {
            e.tick()
            for (event in e.drainEvents()) {
                if (event is CityEvent.MilestoneReached && event.milestone == Milestone.FIRST_HOME) {
                    reached = event.milestone
                    bonusSeen += event.milestone.cash
                }
            }
        }
        assertTrue("the city grew", e.population >= Config.MILESTONE_POP_FIRST_HOME)
        assertNotNull("First Residents fired", reached)
        assertTrue("the cash bonus was paid", bonusSeen > 0)
        assertTrue("rail unlocked", e.isUnlocked(Tool.RAIL))
        assertTrue(Milestone.FIRST_HOME in e.completedMilestones)
        assertTrue("the bigger Hamlets goal also fell", Milestone.HAMLET in e.completedMilestones)
        assertEquals(Milestone.TOWN, e.nextMilestone())
    }

    @Test
    fun milestoneProgressTracksPopulationAndStability() {
        val e = progressiveCity()
        assertEquals(e.population, e.milestoneValue(Milestone.CITY))
        assertEquals(Config.MILESTONE_POP_CITY, e.milestoneTarget(Milestone.CITY))
        assertEquals(e.stableDays, e.milestoneValue(Milestone.STABLE_CYCLE))
        assertEquals(Config.MILESTONE_STABLE_CYCLE_DAYS, e.milestoneTarget(Milestone.STABLE_CYCLE))
        assertEquals(0, e.milestoneValue(Milestone.SKYLINE))

        // a settled, happy, solvent town builds a stable streak
        buildBasicTown(e)
        repeat(600) { e.tick() }
        assertTrue("the streak builds when happy and solvent", e.stableDays > 0)
    }

    @Test
    fun goalsSurviveASaveAndLoad() {
        val e = progressiveCity()
        buildBasicTown(e)
        repeat(900) { e.tick() }

        val data = e.save()
        val loaded = CityEngine.load(data)
        assertNotNull(loaded)
        assertEquals(e.completedMilestones, loaded!!.completedMilestones)
        assertEquals(e.stableDays, loaded.stableDays)
        assertEquals(e.population, loaded.population)
        for (milestone in e.completedMilestones) {
            for (tool in milestone.unlocks) {
                assertTrue("$tool stays unlocked after loading", loaded.isUnlocked(tool))
            }
        }
        assertEquals(e.nextMilestone(), loaded.nextMilestone())
    }

    @Test
    fun infoToolIsFreeAndNeverBuilds() {
        val e = progressiveCity()
        assertTrue("the info tool is always available", e.isUnlocked(Tool.INFO))
        assertEquals(0, e.baseCost(Tool.INFO))
        val (c, r) = findDryRun(e, 2)!!
        val before = e.funds
        assertEquals(BuildResult.OK, e.build(Tool.INFO, c, r))
        assertEquals("inspecting does not build", ZoneType.NONE, e.tile(c, r)!!.zone)
        assertEquals("inspecting is free", before, e.funds)
    }

    @Test
    fun inspectorNamesWhatAVacantLotNeeds() {
        val e = city(97)
        val (c, r) = findDryRun(e, 3)!!
        assertEquals(BuildResult.OK, e.build(Tool.RESIDENTIAL, c, r))
        val report = CityInspector.report(e, c, r)!!
        assertTrue(report.title.contains("residential", ignoreCase = true))
        assertTrue(report.needs.any { it.label == "Road access" && it.state == NeedState.MISSING })
        assertTrue(report.needs.any { it.label == "Power" && it.state == NeedState.MISSING })
        assertTrue(report.needs.any { it.label == "Water" && it.state == NeedState.MISSING })
        assertTrue(report.broken)
        assertTrue("the advice names the road", report.advice.contains("road", ignoreCase = true))
    }

    @Test
    fun inspectorUpdatesWhenTheServicesArePulled() {
        val e = city(7)
        assertEquals(BuildResult.OK, e.build(Tool.PLANT, 10, 10))
        assertEquals(BuildResult.OK, e.build(Tool.ROAD, 10, 11))
        assertEquals(BuildResult.OK, e.build(Tool.WATER, 12, 14))
        assertEquals(BuildResult.OK, e.build(Tool.RESIDENTIAL, 11, 11))
        repeat(240) { e.tick() }

        val healthy = CityInspector.report(e, 11, 11)!!
        assertTrue(healthy.needs.any { it.label == "Power" && it.state == NeedState.MET })
        assertTrue(healthy.needs.any { it.label == "Water" && it.state == NeedState.MET })
        assertTrue(healthy.facts.any { it.label == "Level" })
        assertTrue(healthy.facts.any { it.label == "Inside" })
        assertEquals(false, healthy.broken)

        assertEquals(BuildResult.OK, e.build(Tool.BULLDOZE, 10, 10))
        val dark = CityInspector.report(e, 11, 11)!!
        assertTrue(dark.needs.any { it.label == "Power" && it.state == NeedState.MISSING })
        assertTrue(dark.broken)
    }

    @Test
    fun inspectorExplainsRoadsRailTunnelsAndWater() {
        val e = city(101)
        val (c, r) = findDryBlock(e, 3, 3)!!

        assertEquals(BuildResult.OK, e.build(Tool.ROAD, c, r))
        val road = CityInspector.report(e, c, r)!!
        assertTrue("a lone road is called a stub", road.advice.contains("stub", ignoreCase = true))

        assertEquals(BuildResult.OK, e.build(Tool.RAIL, c, r + 1))
        val rail = CityInspector.report(e, c, r + 1)!!
        assertTrue(rail.needs.any { it.label == "Part of a line" && it.state == NeedState.MISSING })
        assertEquals(BuildResult.OK, e.build(Tool.RAIL, c + 1, r + 1))
        val line = CityInspector.report(e, c, r + 1)!!
        assertTrue(line.needs.any { it.label == "Part of a line" && it.state == NeedState.MET })

        val emptyByRoad = CityInspector.report(e, c + 1, r)!!
        assertTrue(
            "empty land beside a road suggests zoning",
            emptyByRoad.advice.contains("zone", ignoreCase = true)
        )

        val (wc, wr) = findTile(e) { it.terrain == Terrain.WATER }!!
        val water = CityInspector.report(e, wc, wr)!!
        assertTrue(water.needs.isEmpty())
        assertTrue("water explains bridges and tunnels", water.advice.contains("bridge", true))
    }

    @Test
    fun inspectorExplainsSubwayStubsAndPlatforms() {
        val e = city(103)
        val (c, r) = findDryBlock(e, 3, 3)!!
        assertEquals(BuildResult.OK, e.build(Tool.SUBWAY, c, r))
        val stub = CityInspector.report(e, c, r)!!
        assertTrue(stub.advice.contains("stub", ignoreCase = true))

        assertEquals(BuildResult.OK, e.build(Tool.SUBWAY, c + 1, r))
        val line = CityInspector.report(e, c, r)!!
        assertTrue(line.needs.any { it.label == "Part of a line" && it.state == NeedState.MET })
        assertTrue("it asks for a station", line.advice.contains("SUB STN", true))

        assertEquals(BuildResult.OK, e.build(Tool.SUBWAY_STATION, c, r + 1))
        val platform = CityInspector.report(e, c, r + 1)!!
        assertTrue(platform.needs.any { it.label == "Tunnel beside it" && it.state == NeedState.MET })
        assertEquals(false, platform.broken)
    }

    @Test
    fun firesOnlyStartWhereNoFireCrewReaches() {
        // a covered home stays safe
        val safe = city(7)
        assertEquals(BuildResult.OK, safe.build(Tool.PLANT, 10, 10))
        assertEquals(BuildResult.OK, safe.build(Tool.ROAD, 10, 11))
        assertEquals(BuildResult.OK, safe.build(Tool.WATER, 12, 14))
        assertEquals(BuildResult.OK, safe.build(Tool.RESIDENTIAL, 11, 11))
        assertEquals(BuildResult.OK, safe.build(Tool.FIRE, 11, 12))
        var safeFires = 0
        repeat(500) {
            safe.tick()
            for (event in safe.drainEvents()) {
                if (event is CityEvent.FireStarted) safeFires++
            }
        }
        assertTrue("the crew reaches the home", safe.tile(11, 11)!!.fireCovered)
        assertEquals("a covered city does not burn", 0, safeFires)
        assertEquals(0, safe.activeFires)

        // the same home with no crew is at risk, and nobody puts it out
        val risky = city(7)
        assertEquals(BuildResult.OK, risky.build(Tool.PLANT, 10, 10))
        assertEquals(BuildResult.OK, risky.build(Tool.ROAD, 10, 11))
        assertEquals(BuildResult.OK, risky.build(Tool.WATER, 12, 14))
        assertEquals(BuildResult.OK, risky.build(Tool.RESIDENTIAL, 11, 11))
        repeat(240) { risky.tick() }
        assertTrue("a home exists to burn", risky.tile(11, 11)!!.developed)

        var started = false
        var destroyed = false
        var warned = false
        repeat(900) {
            risky.tick()
            for (event in risky.drainEvents()) {
                when (event) {
                    is CityEvent.FireStarted -> started = true
                    is CityEvent.FireDestroyed -> destroyed = true
                    else -> Unit
                }
            }
            if (!warned) {
                warned = CityAdvisor.report(risky).issues.any { it.title == "Buildings are on fire" }
            }
        }
        assertTrue("an uncovered home catches fire", started)
        assertTrue("without a crew the fire guts it", destroyed)
        assertTrue("the advisor warns about fires", warned)
    }

    @Test
    fun aFireStationPutsOutAnActiveFire() {
        val e = city(11)
        assertEquals(BuildResult.OK, e.build(Tool.PLANT, 10, 10))
        assertEquals(BuildResult.OK, e.build(Tool.ROAD, 10, 11))
        assertEquals(BuildResult.OK, e.build(Tool.WATER, 12, 14))
        assertEquals(BuildResult.OK, e.build(Tool.RESIDENTIAL, 11, 11))
        repeat(240) { e.tick() }

        var col = -1
        var row = -1
        for (i in 0 until 900) {
            e.tick()
            for (event in e.drainEvents()) {
                if (event is CityEvent.FireStarted) {
                    col = event.col
                    row = event.row
                }
            }
            if (col >= 0 && e.tile(col, row)!!.fire > 0) break
        }
        assertTrue("a fire is burning", col >= 0 && e.tile(col, row)!!.fire > 0)

        val burning = CityInspector.report(e, col, row)!!
        assertTrue("the info tool calls out the fire", burning.advice.contains("on fire", true))
        assertTrue(burning.broken)

        var station: Pair<Int, Int>? = null
        for (r in max(0, row - 2)..min(e.rows - 1, row + 2)) {
            for (c in max(0, col - 2)..min(e.cols - 1, col + 2)) {
                if (station == null && e.canBuild(Tool.FIRE, c, r) == BuildResult.OK) {
                    station = c to r
                }
            }
        }
        assertNotNull("there is room for a fire station", station)
        assertEquals(BuildResult.OK, e.build(Tool.FIRE, station!!.first, station.second))
        e.tick()
        var out = false
        for (event in e.drainEvents()) if (event is CityEvent.FireOut) out = true
        assertEquals("the crew puts the fire out", 0, e.tile(col, row)!!.fire)
        assertTrue("the crew's arrival is announced", out)
    }

    @Test
    fun crimeFollowsMissingPoliceAndClearsWithCover() {
        val e = city(13)
        assertEquals(BuildResult.OK, e.build(Tool.PLANT, 10, 10))
        assertEquals(BuildResult.OK, e.build(Tool.ROAD, 10, 11))
        assertEquals(BuildResult.OK, e.build(Tool.WATER, 12, 14))
        assertEquals(BuildResult.OK, e.build(Tool.RESIDENTIAL, 11, 11))
        repeat(240) { e.tick() }
        assertTrue(e.tile(11, 11)!!.developed)
        assertTrue("the home has no police cover", e.crimeRiskLots > 0)

        var warned = false
        var reportStage = 0
        var reportCol = -1
        var reportRow = -1
        var crimeCol = -1
        var crimeRow = -1
        for (i in 0 until 900) {
            e.tick()
            for (event in e.drainEvents()) {
                if (event is CityEvent.CrimeReported) {
                    warned = event.text.contains("crime", true) && event.count > 0
                    reportStage = max(reportStage, event.stage)
                    if (event.col >= 0) {
                        reportCol = event.col
                        reportRow = event.row
                    }
                }
            }
            if (e.activeCrime > 0) {
                for (r in 0 until e.rows) {
                    for (c in 0 until e.cols) {
                        if (e.tile(c, r)!!.crime > 0) {
                            crimeCol = c
                            crimeRow = r
                        }
                    }
                }
                break
            }
        }
        assertTrue("unpoliced homes attract crime", crimeCol >= 0)
        assertTrue("the city is warned", warned)
        assertTrue("the report names the stage", reportStage >= 1)
        assertTrue("the report points at a crime scene", reportCol >= 0 && reportRow >= 0)
        assertTrue("that cell really is hit", e.tile(reportCol, reportRow)!!.crime > 0)

        val report = CityInspector.report(e, crimeCol, crimeRow)!!
        assertTrue("the info tool names the crime", report.advice.contains("crime", true))

        var station: Pair<Int, Int>? = null
        for (r in max(0, crimeRow - 2)..min(e.rows - 1, crimeRow + 2)) {
            for (c in max(0, crimeCol - 2)..min(e.cols - 1, crimeCol + 2)) {
                if (station == null && e.canBuild(Tool.POLICE, c, r) == BuildResult.OK) {
                    station = c to r
                }
            }
        }
        assertNotNull("there is room for a police station", station)
        assertEquals(BuildResult.OK, e.build(Tool.POLICE, station!!.first, station.second))
        repeat(6) { e.tick() }
        assertEquals("police clear the crime mark", 0, e.activeCrime)
    }

    @Test
    fun disastersWaitUntilTheFireAndPoliceToolsAreEarned() {
        val e = progressiveCity()
        assertTrue("fire and police start locked", !e.isUnlocked(Tool.FIRE) && !e.isUnlocked(Tool.POLICE))
        assertEquals(BuildResult.OK, e.build(Tool.PLANT, 10, 10))
        assertEquals(BuildResult.OK, e.build(Tool.ROAD, 10, 11))
        assertEquals(BuildResult.OK, e.build(Tool.WATER, 12, 14))
        assertEquals(BuildResult.OK, e.build(Tool.RESIDENTIAL, 11, 11))

        var incidents = 0
        repeat(400) {
            e.tick()
            for (event in e.drainEvents()) {
                if (event is CityEvent.FireStarted) incidents++
                if (event is CityEvent.CrimeReported) incidents++
            }
        }
        assertTrue("a home was built to be at risk", e.tile(11, 11)!!.developed)
        assertEquals("no disasters before the services are earned", 0, incidents)
    }

    @Test
    fun fireAndCrimeSurviveASave() {
        val e = city(19)
        val parts = e.save().split('|')
        assertEquals("CC7", parts[0])

        // graft a fire and a crime mark onto the first tile
        val tiles = parts[11].split(';').toMutableList()
        val fields = tiles[0].split(',').toMutableList()
        fields[14] = "3"
        fields[15] = "7"
        tiles[0] = fields.joinToString(",")
        val cc7 = (listOf("CC7") + parts.subList(1, 11) + listOf(tiles.joinToString(";")))
            .joinToString("|")
        val loaded = CityEngine.load(cc7)
        assertNotNull("the save loads", loaded)
        assertEquals(3, loaded!!.tile(0, 0)!!.fire)
        assertEquals(7, loaded.tile(0, 0)!!.crime)
        assertEquals(1, loaded.activeFires)
        assertEquals(1, loaded.activeCrime)

        // a CC6 save has no disaster fields and loads clean
        val cc6Tiles = parts[11].split(';').joinToString(";") { tile ->
            tile.split(',').take(14).joinToString(",")
        }
        val cc6 = (listOf("CC6") + parts.subList(1, 11) + listOf(cc6Tiles)).joinToString("|")
        val old = CityEngine.load(cc6)
        assertNotNull("CC6 still loads", old)
        assertEquals(0, old!!.tile(0, 0)!!.fire)
        assertEquals(0, old.activeCrime)
    }

    @Test
    fun cc5SavesLoadWithTheDefaultTaxAndNoTunnels() {
        val e = city(37)
        assertEquals(BuildResult.OK, e.build(Tool.SUBWAY, 8, 8))
        e.setTaxRate(21)

        // strip the CC6-only fields: the tax rate before the tiles
        val parts = e.save().split('|')
        assertEquals("CC7", parts[0])
        val cc5 = (listOf("CC5") + parts.subList(1, 10) + listOf(parts[11])).joinToString("|")

        val loaded = CityEngine.load(cc5)
        assertNotNull("a save from the previous version still loads", loaded)
        assertEquals("old saves fall back to the default rate", Config.TAX_DEFAULT, loaded!!.taxRate)
        assertEquals("no tunnels existed before CC6", 0, loaded.subwayCount)
        assertEquals(e.population, loaded.population)
        assertEquals(e.day, loaded.day)
    }

    @Test
    fun oldSavesKeepEveryToolUnlocked() {
        val e = city()
        val parts = e.save().split('|')
        // rewrite as a CC4 save: drop the goals and stable-days fields
        val cc4 = (listOf("CC4") + parts.subList(1, 8) + listOf(parts[11])).joinToString("|")
        val loaded = CityEngine.load(cc4)
        assertNotNull(loaded)
        assertEquals(true, loaded!!.isUnlocked(Tool.RAIL))
        assertEquals(true, loaded.isUnlocked(Tool.AIRPORT))
        assertEquals(true, loaded.isUnlocked(Tool.POLICE))
    }

    @Test
    fun taxRateScalesIncomeAndSoursTheMood() {
        val e = city(61)
        buildBasicTown(e)
        repeat(400) { e.tick() }
        assertTrue("people live here", e.population > 0)
        assertEquals(Config.TAX_DEFAULT, e.taxRate)
        val defaultIncome = e.incomePerDay
        assertTrue(defaultIncome > 0f)

        assertTrue(e.setTaxRate(20))
        assertEquals(20, e.taxRate)
        assertEquals("doubling the rate doubles the tax take", defaultIncome * 2f, e.incomePerDay, defaultIncome * 0.02f)

        val before = e.happiness
        repeat(150) { e.tick() }
        assertTrue("higher taxes hurt happiness", e.happiness < before)

        e.setTaxRate(999)
        assertEquals(Config.TAX_MAX, e.taxRate)
        e.setTaxRate(-5)
        assertEquals(Config.TAX_MIN, e.taxRate)
        assertEquals("no tax means no tax income", 0f, e.incomePerDay, 0.001f)
        assertTrue(e.setTaxRate(12))
        assertEquals(false, e.setTaxRate(12)) // already there
    }

    @Test
    fun subwaysRunUnderBuildingsAndNeedStations() {
        val e = city(79)
        val (c, r) = findDryBlock(e, 5, 2)!!
        val before = e.funds
        assertEquals(BuildResult.OK, e.build(Tool.SUBWAY, c, r))
        assertEquals(before - Config.COST_SUBWAY, e.funds)
        assertEquals("a tunnel does not occupy the tile", ZoneType.NONE, e.tile(c, r)!!.zone)
        assertTrue(e.tile(c, r)!!.subway)
        assertEquals("one tunnel is not a line yet", false, e.tile(c, r)!!.subwayConnected)

        assertEquals("a station cannot stand beside a stub", BuildResult.NO_SUBWAY, e.build(Tool.SUBWAY_STATION, c, r + 1))

        // tunnels share a tile with whatever sits on top of them
        assertEquals(BuildResult.OK, e.build(Tool.RESIDENTIAL, c + 1, r))
        assertEquals(BuildResult.OK, e.build(Tool.SUBWAY, c + 1, r))
        assertEquals(ZoneType.RESIDENTIAL, e.tile(c + 1, r)!!.zone)
        assertTrue("a tunnel runs under the house", e.tile(c + 1, r)!!.subway)
        assertTrue("two connected tunnels make a line", e.tile(c, r)!!.subwayConnected)

        assertEquals(BuildResult.OK, e.build(Tool.SUBWAY_STATION, c, r + 1))
        assertEquals(ZoneType.SUBWAY_STATION, e.tile(c, r + 1)!!.zone)
        assertEquals(1, e.subwayStationCount)
        assertEquals(2, e.subwayCount)
        assertTrue("the station reaches the lot three tiles out", e.tile(c + 3, r + 1)!!.subwayAccess)
        assertTrue("subway access counts as transit", e.tile(c + 3, r + 1)!!.transitAccess)
        assertEquals(
            "stations still need a tunnel right beside them",
            BuildResult.NO_SUBWAY,
            e.build(Tool.SUBWAY_STATION, c + 4, r)
        )
    }

    @Test
    fun subwayTunnelsCostMoreUnderTheRiver() {
        val e = city(89)
        val (wc, wr) = findTile(e) { it.terrain == Terrain.WATER }!!
        val before = e.funds
        assertEquals(BuildResult.OK, e.build(Tool.SUBWAY, wc, wr))
        assertEquals(before - Config.COST_SUBWAY_WATER, e.funds)
        assertEquals(ZoneType.NONE, e.tile(wc, wr)!!.zone)
        assertTrue(e.tile(wc, wr)!!.subway)
    }

    @Test
    fun taxRateAndTunnelsSurviveASave() {
        val e = city(67)
        e.setTaxRate(17)
        val (c, r) = findDryBlock(e, 4, 2)!!
        assertEquals(BuildResult.OK, e.build(Tool.SUBWAY, c, r))
        assertEquals(BuildResult.OK, e.build(Tool.SUBWAY, c + 1, r))
        assertEquals(BuildResult.OK, e.build(Tool.SUBWAY_STATION, c, r + 1))

        val loaded = CityEngine.load(e.save())
        assertNotNull("the save still loads", loaded)
        assertEquals(17, loaded!!.taxRate)
        assertTrue(loaded.tile(c, r)!!.subway)
        assertTrue(loaded.tile(c + 1, r)!!.subway)
        assertEquals(ZoneType.SUBWAY_STATION, loaded.tile(c, r + 1)!!.zone)
        assertTrue("the platform still reaches after loading", loaded.tile(c + 3, r + 1)!!.subwayAccess)
    }

    @Test
    fun bulldozingLiftsTheTunnel() {
        val e = city(83)
        val (c, r) = findDryBlock(e, 3, 2)!!
        assertEquals(BuildResult.OK, e.build(Tool.SUBWAY, c, r))
        val after = e.funds
        assertEquals(BuildResult.OK, e.build(Tool.BULLDOZE, c, r))
        assertEquals(false, e.tile(c, r)!!.subway)
        val refund = (Config.COST_SUBWAY * Config.BULLDOZE_REFUND).roundToInt()
        assertEquals("the tunnel is refunded with the tile", after + refund, e.funds)
    }

    @Test
    fun subwayToolsArriveTogetherWithTown() {
        val e = progressiveCity()
        assertEquals(false, e.isUnlocked(Tool.SUBWAY))
        assertEquals(false, e.isUnlocked(Tool.SUBWAY_STATION))
        assertTrue(Milestone.TOWN.unlocks.contains(Tool.SUBWAY))
        assertTrue("stations arrive with tunnels so lines are usable", Milestone.TOWN.unlocks.contains(Tool.SUBWAY_STATION))
    }

    @Test
    fun advisorReadsTheMoodAndNamesTheProblems() {
        val fresh = CityAdvisor.report(city(73))
        assertEquals(CityMood.CONTENT, fresh.mood)
        assertTrue(fresh.headline.startsWith("Content"))
        assertTrue(
            "an empty map is called out",
            fresh.issues.any { it.title == "Nobody lives here yet" }
        )

        val e = city(73)
        assertEquals(BuildResult.OK, e.build(Tool.PLANT, 10, 10))
        assertEquals(BuildResult.OK, e.build(Tool.ROAD, 10, 11))
        assertEquals(BuildResult.OK, e.build(Tool.WATER, 12, 14))
        assertEquals(BuildResult.OK, e.build(Tool.RESIDENTIAL, 11, 11))
        repeat(240) { e.tick() }
        assertTrue("a home exists", e.tile(11, 11)!!.developed)

        // pull the plant down: the advisor should shout about the blackout
        assertEquals(BuildResult.OK, e.build(Tool.BULLDOZE, 10, 10))
        val dark = CityAdvisor.report(e)
        assertTrue(
            "a blackout is critical",
            dark.issues.any { it.level == IssueLevel.CRITICAL && it.title.contains("power") }
        )
        assertTrue("the advisor suggests something", dark.issues.isNotEmpty())

        // heavy taxes are reported too
        e.setTaxRate(Config.TAX_MAX)
        repeat(120) { e.tick() }
        val taxed = CityAdvisor.report(e)
        assertTrue(
            "the tax burden is named",
            taxed.issues.any { it.title == "Taxes are squeezing people" }
        )
    }

    /** A little grid of powered, watered, road-connected lots to grow a city. */
    private fun buildBasicTown(e: CityEngine) {
        assertEquals(BuildResult.OK, e.build(Tool.PLANT, 10, 10))
        assertEquals(BuildResult.OK, e.build(Tool.WATER, 12, 14))
        for (c in 9..15) e.build(Tool.ROAD, c, 11)
        for (r in 12..14) e.build(Tool.ROAD, 9, r)
        for (c in 10..15) {
            e.build(Tool.RESIDENTIAL, c, 10)
            e.build(Tool.COMMERCIAL, c, 12)
        }
        for (r in 12..14) e.build(Tool.INDUSTRIAL, 10, r)
        e.build(Tool.PARK, 9, 10)
        e.build(Tool.PARK, 9, 15)
    }

    /** The top-left corner of a dry w x h patch, so tunnel runs never hit water. */
    private fun findDryBlock(e: CityEngine, w: Int, h: Int): Pair<Int, Int>? {
        for (r in 0 until e.rows - h + 1) {
            for (c in 0 until e.cols - w + 1) {
                var dry = true
                for (rr in r until r + h) {
                    for (cc in c until c + w) {
                        if (e.tile(cc, rr)!!.terrain == Terrain.WATER) dry = false
                    }
                }
                if (dry) return c to r
            }
        }
        return null
    }

    /** First horizontal run of dry tiles, so pipe runs never hit the river. */
    private fun findDryRun(e: CityEngine, length: Int): Pair<Int, Int>? {
        for (r in 0 until e.rows) {
            var run = 0
            for (c in 0 until e.cols) {
                run = if (e.tile(c, r)!!.terrain != Terrain.WATER) run + 1 else 0
                if (run >= length) return (c - length + 1) to r
            }
        }
        return null
    }

    private fun findTile(e: CityEngine, predicate: (Tile) -> Boolean): Pair<Int, Int>? {
        for (r in 0 until e.rows) {
            for (c in 0 until e.cols) {
                if (predicate(e.tile(c, r)!!)) return c to r
            }
        }
        return null
    }
}
