package com.cellcity.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

class CityEngineTest {

    private fun city(seed: Long = 7L) = CityEngine(seed = seed)

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

        repeat(700) { e.tick() }

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

        // rewrite the CC3 save the way the previous version stored it: pipes as a zone
        val parts = e.save().split('|')
        val tiles = parts[8].split(';').joinToString(";") { tile ->
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
