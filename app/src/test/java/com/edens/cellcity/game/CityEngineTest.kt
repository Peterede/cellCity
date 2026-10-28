package com.edens.cellcity.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun fillingShoreWaterMakesBuildableLand() {
        val e = city(21)
        val (c, r) = findShoreWater(e)
        val before = e.funds
        val sides = waterSides(e, c, r)
        val cost = e.costOf(Tool.RECLAIM, c, r)
        assertEquals(
            "the price is the base plus every water side",
            Config.COST_RECLAIM + sides * Config.COST_RECLAIM_DEEP,
            cost
        )

        assertEquals(BuildResult.OK, e.build(Tool.RECLAIM, c, r))
        assertEquals(Terrain.GRASS, e.tile(c, r)!!.terrain)
        assertEquals(before - cost, e.funds)

        // the raised cell is ordinary ground: it can be zoned and built on
        assertEquals(BuildResult.OK, e.build(Tool.RESIDENTIAL, c, r))
        assertEquals(ZoneType.RESIDENTIAL, e.tile(c, r)!!.zone)
    }

    @Test
    fun fillingIsTurnedDownOnDryLandAndInOpenWater() {
        val e = city(21)
        assertEquals(BuildResult.NOT_WATER, e.build(Tool.RECLAIM, 12, 12))

        val open = findOpenWater(e)
        assertNotNull("the lake has water away from any shore", open)
        assertEquals(BuildResult.NO_SHORE, e.build(Tool.RECLAIM, open!!.first, open.second))
    }

    @Test
    fun fillHasToGrowOutFromTheShore() {
        val e = city(21)
        val pair = findBankAndOpenWater(e)
        assertNotNull("the river has open water behind the bank", pair)
        val shore = pair!!.first
        val inland = pair.second

        assertEquals(BuildResult.NO_SHORE, e.build(Tool.RECLAIM, inland.first, inland.second))
        assertEquals(BuildResult.OK, e.build(Tool.RECLAIM, shore.first, shore.second))
        assertEquals(
            "once the bank is filled the next cell touches land",
            BuildResult.OK,
            e.build(Tool.RECLAIM, inland.first, inland.second)
        )
        assertEquals(Terrain.GRASS, e.tile(inland.first, inland.second)!!.terrain)
    }

    @Test
    fun deepWaterCostsMoreThanTheBank() {
        val e = city(21)
        val bank = findShoreWater(e)
        val deep = findWaterWithSides(e, 4)
        assertNotNull("the lake has open middle water", deep)
        val bankCost = e.costOf(Tool.RECLAIM, bank.first, bank.second)
        val deepCost = e.costOf(Tool.RECLAIM, deep!!.first, deep.second)
        assertTrue("filling the middle costs more than the bank", deepCost > bankCost)
    }

    @Test
    fun fillingKeepsWhateverWasUnderTheWater() {
        val e = city(21)
        val (c, r) = findShoreWater(e)
        assertEquals(BuildResult.OK, e.build(Tool.ROAD, c, r))
        assertEquals(BuildResult.OK, e.build(Tool.PIPE, c, r))
        assertEquals(Terrain.WATER, e.tile(c, r)!!.terrain)

        assertEquals(BuildResult.OK, e.build(Tool.RECLAIM, c, r))
        assertEquals(Terrain.GRASS, e.tile(c, r)!!.terrain)
        assertEquals("the bridge becomes a road on dry land", ZoneType.ROAD, e.tile(c, r)!!.zone)
        assertTrue("the main under it survives", e.tile(c, r)!!.pipe)
    }

    @Test
    fun fillUndoesAndSavesAsLand() {
        val e = city(21)
        val (c, r) = findShoreWater(e)
        val before = e.funds
        e.beginAction()
        assertEquals(BuildResult.OK, e.build(Tool.RECLAIM, c, r))
        assertTrue(e.endAction())
        assertEquals("build", e.undo())
        assertEquals(Terrain.WATER, e.tile(c, r)!!.terrain)
        assertEquals(before, e.funds)

        assertEquals(BuildResult.OK, e.build(Tool.RECLAIM, c, r))
        val loaded = CityEngine.load(e.save())
        assertNotNull(loaded)
        assertEquals(Terrain.GRASS, loaded!!.tile(c, r)!!.terrain)
        assertEquals(e.funds, loaded.funds)
        assertEquals(
            "saved land is buildable again",
            BuildResult.OK,
            loaded.build(Tool.RESIDENTIAL, c, r)
        )
    }

    @Test
    fun fillingASelectedPatchQuotesFirstThenBuilds() {
        val e = city(21)
        val pair = findBankAndOpenWater(e)
        assertNotNull(pair)
        val bank = pair!!.first
        val inlet = pair.second
        val before = e.funds

        val quote = e.planFill(bank.first, bank.second, inlet.first, inlet.second)
        assertEquals(BuildResult.OK, quote.result)
        assertEquals("both cells are in the patch", 2, quote.count)
        assertEquals("quoting spends nothing", before, e.funds)
        assertEquals(Terrain.WATER, e.tile(inlet.first, inlet.second)!!.terrain)

        val done = e.fillArea(bank.first, bank.second, inlet.first, inlet.second)
        assertEquals("the bill matches the quote", quote.cost, done.cost)
        assertEquals(before - quote.cost, e.funds)
        for (index in done.cells) {
            val t = e.tile(index % e.cols, index / e.cols)!!
            assertEquals(Terrain.GRASS, t.terrain)
            assertTrue("filled ground is marked as reclaimed", t.reclaimed)
        }
    }

    @Test
    fun fillingAPatchIsOneUndoStep() {
        val e = city(21)
        val pair = findBankAndOpenWater(e)!!
        val bank = pair.first
        val inlet = pair.second
        val before = e.funds

        e.beginAction()
        assertEquals(
            BuildResult.OK,
            e.fillArea(bank.first, bank.second, inlet.first, inlet.second).result
        )
        assertTrue(e.endAction())
        assertEquals("build", e.undo())
        assertEquals(Terrain.WATER, e.tile(bank.first, bank.second)!!.terrain)
        assertEquals(Terrain.WATER, e.tile(inlet.first, inlet.second)!!.terrain)
        assertFalse(e.tile(bank.first, bank.second)!!.reclaimed)
        assertEquals(before, e.funds)
    }

    @Test
    fun aPatchNeedsWaterItCanReach() {
        val e = city(21)
        val (dc, dr) = findDryLand(e)!!
        assertEquals(BuildResult.NOT_WATER, e.planFill(dc, dr, dc, dr).result)

        val open = findOpenWater(e)!!
        assertEquals(
            "water with no shore to start from cannot be filled",
            BuildResult.NO_SHORE,
            e.planFill(open.first, open.second, open.first, open.second).result
        )
    }

    @Test
    fun canalsCutWaterInFromTheEdge() {
        val e = city(21)
        val (c, r) = findBankland(e)!!
        val before = e.funds
        val sides = inBoundsLandSides(e, c, r)
        val cost = e.costOf(Tool.CANAL, c, r)
        assertEquals(
            "the price is the base plus every land side",
            Config.COST_CANAL + sides * Config.COST_CANAL_LAND,
            cost
        )

        assertEquals(BuildResult.OK, e.build(Tool.CANAL, c, r))
        assertEquals(Terrain.WATER, e.tile(c, r)!!.terrain)
        assertEquals(before - cost, e.funds)
        assertEquals(
            "a channel cannot be zoned",
            BuildResult.WATER,
            e.build(Tool.RESIDENTIAL, c, r)
        )
    }

    @Test
    fun canalsNeedTheWaterEdgeAndEmptyGround() {
        val e = city(21)
        val (wc, wr) = findShoreWater(e)
        assertEquals(BuildResult.ALREADY_WATER, e.build(Tool.CANAL, wc, wr))

        val (ic, ir) = findDryLand(e)!!
        assertEquals(BuildResult.NO_WATER_EDGE, e.build(Tool.CANAL, ic, ir))

        // a road in the way stops the dig
        var road: Pair<Int, Int>? = null
        var piped: Pair<Int, Int>? = null
        for (r in 0 until e.rows) {
            for (c in 0 until e.cols) {
                val t = e.tile(c, r)!!
                if (t.terrain == Terrain.WATER || !t.waterfront) continue
                if (t.zone != ZoneType.NONE) continue
                if (road == null) road = c to r else if (piped == null) piped = c to r
            }
        }
        assertNotNull("the map has two open bank cells", piped)
        assertEquals(BuildResult.OK, e.build(Tool.ROAD, road!!.first, road.second))
        assertEquals(
            BuildResult.OCCUPIED,
            e.build(Tool.CANAL, road.first, road.second)
        )

        assertEquals(BuildResult.OK, e.build(Tool.PIPE, piped!!.first, piped.second))
        assertEquals(
            "a buried main blocks the dig rather than being destroyed",
            BuildResult.LAYERED,
            e.build(Tool.CANAL, piped.first, piped.second)
        )
    }

    @Test
    fun waterfrontAndFilledLotsHoldMore() {
        val e = city(7)
        val (bc, br) = findBankland(e)!!
        val (ic, ir) = findDryLand(e)!!
        val waterside = e.tile(bc, br)!!
        val inland = e.tile(ic, ir)!!
        assertTrue("the bank cell knows it is waterfront", waterside.waterfront)
        assertFalse("inland ground does not", inland.waterfront)

        waterside.zone = ZoneType.RESIDENTIAL
        waterside.level = 1
        inland.zone = ZoneType.RESIDENTIAL
        inland.level = 1
        val base = e.capacityOf(ZoneType.RESIDENTIAL, 1)
        assertEquals(
            (base * Config.WATERFRONT_CAPACITY).roundToInt(),
            e.capacityForTile(waterside)
        )
        assertEquals(base, e.capacityForTile(inland))

        inland.reclaimed = true
        assertEquals(
            "filled ground gets the same lift",
            (base * Config.WATERFRONT_CAPACITY).roundToInt(),
            e.capacityForTile(inland)
        )
    }

    @Test
    fun reclaimedGroundSurvivesASaveAndOlderSavesStayPlain() {
        val e = city(21)
        val (c, r) = findShoreWater(e)
        assertEquals(BuildResult.OK, e.build(Tool.RECLAIM, c, r))
        assertTrue(e.tile(c, r)!!.reclaimed)
        assertTrue(e.save().startsWith("CC11|"))

        val loaded = CityEngine.load(e.save())
        assertNotNull(loaded)
        assertEquals(Terrain.GRASS, loaded!!.tile(c, r)!!.terrain)
        assertTrue("filled land is still filled land", loaded.tile(c, r)!!.reclaimed)

        val older = e.save().split('|').toMutableList()
        older[0] = "CC10"
        val old = CityEngine.load(older.joinToString("|"))
        assertNotNull("the previous save version still loads", old)
        assertEquals(Terrain.GRASS, old!!.tile(c, r)!!.terrain)
        assertFalse("older saves have no reclaimed ground", old.tile(c, r)!!.reclaimed)
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
    fun solarWindAndNuclearPlantsBuildAndSurviveASave() {
        val e = city()
        val (c, r) = findDryRun(e, 3) ?: error("no dry run")
        assertEquals(BuildResult.OK, e.build(Tool.SOLAR, c, r))
        assertEquals(BuildResult.OK, e.build(Tool.WIND, c + 1, r))
        assertEquals(BuildResult.OK, e.build(Tool.NUCLEAR, c + 2, r))

        assertEquals(ZoneType.SOLAR, e.tile(c, r)!!.zone)
        assertEquals(ZoneType.WIND, e.tile(c + 1, r)!!.zone)
        assertEquals(ZoneType.NUCLEAR, e.tile(c + 2, r)!!.zone)
        assertEquals("every plant type counts as a plant", 3, e.plantCount)
        assertEquals(1, e.solarCount)
        assertEquals(1, e.windCount)
        assertEquals(1, e.nuclearCount)
        assertTrue(
            "clean plants carry less load than the gas plant",
            e.powerCapacity(ZoneType.SOLAR) < e.powerCapacity(ZoneType.PLANT)
        )
        assertTrue(
            "nuclear is the biggest plant",
            e.powerCapacity(ZoneType.NUCLEAR) > e.powerCapacity(ZoneType.WIND)
        )

        val loaded = CityEngine.load(e.save())
        assertNotNull("new plant types survive a save", loaded)
        assertEquals(ZoneType.SOLAR, loaded!!.tile(c, r)!!.zone)
        assertEquals(ZoneType.WIND, loaded.tile(c + 1, r)!!.zone)
        assertEquals(ZoneType.NUCLEAR, loaded.tile(c + 2, r)!!.zone)
    }

    @Test
    fun aSolarFarmEnergisesItsRoadNetwork() {
        val e = city(7)
        assertEquals(BuildResult.OK, e.build(Tool.SOLAR, 10, 10))
        assertEquals(BuildResult.OK, e.build(Tool.ROAD, 10, 11))
        assertEquals(BuildResult.OK, e.build(Tool.RESIDENTIAL, 11, 11))
        assertTrue("the solar farm feeds the grid", e.tile(10, 11)!!.powered)
        assertTrue("a road-connected lot is powered", e.tile(11, 11)!!.powered)
    }

    @Test
    fun solarAndWindAreCleanWhileGasAndNuclearAreNot() {
        val coal = city(11)
        val solar = city(11)
        val nuclear = city(11)
        val (c, r) = findDryRun(coal, 1) ?: error("no dry land")

        assertEquals(BuildResult.OK, coal.build(Tool.PLANT, c, r))
        assertEquals(BuildResult.OK, solar.build(Tool.SOLAR, c, r))
        assertEquals(BuildResult.OK, nuclear.build(Tool.NUCLEAR, c, r))
        coal.tick()
        solar.tick()
        nuclear.tick()

        assertEquals(Config.POLLUTION_PLANT, coal.pollution, 0.0001f)
        assertEquals("solar adds no smog", 0f, solar.pollution, 0.0001f)
        assertEquals(Config.POLLUTION_NUCLEAR, nuclear.pollution, 0.0001f)
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
    fun fourParksGroupIntoOneLargePark() {
        val e = city()
        val (c, r) = findDryBlock(e, 2, 2) ?: error("no dry 2x2 block")
        for (rr in r..r + 1) {
            for (cc in c..c + 1) {
                assertEquals(BuildResult.OK, e.build(Tool.PARK, cc, rr))
            }
        }

        val origin = e.tile(c, r)!!
        assertTrue("adjacent parks group immediately", origin.merged)
        assertEquals(c, origin.originCol)
        assertEquals(r, origin.originRow)
        assertEquals("every park tile still counts", 4, e.parkCount)
        assertEquals("a park block is not a building block", 0, e.mergedBlockCount)
        assertEquals("Large park", e.tileState(origin))
        assertEquals(ZoneType.PARK, e.tile(c + 1, r + 1)!!.zone)
        assertTrue(e.tile(c + 1, r + 1)!!.merged)

        val loaded = CityEngine.load(e.save())
        assertNotNull("large parks survive a save", loaded)
        assertTrue(loaded!!.tile(c + 1, r + 1)!!.merged)
        assertEquals(c, loaded.tile(c + 1, r + 1)!!.originCol)
    }

    @Test
    fun undoBreaksUpALargePark() {
        val e = city()
        val (c, r) = findDryBlock(e, 2, 2) ?: error("no dry 2x2 block")
        for (rr in r..r + 1) {
            for (cc in c..c + 1) {
                e.beginAction()
                assertEquals(BuildResult.OK, e.build(Tool.PARK, cc, rr))
                e.endAction()
            }
        }
        assertTrue("the four parks grouped", e.tile(c, r)!!.merged)

        assertEquals("build", e.undo())
        assertEquals("only the undone park is gone", 3, e.parkCount)
        for (rr in r..r + 1) {
            for (cc in c..c + 1) {
                assertFalse("the rest of the group is unmerged again", e.tile(cc, rr)!!.merged)
            }
        }
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
    fun eachClosedMonthIsAddedToTheRecord() {
        val e = city()
        assertEquals("opening snapshot", 1, e.history.size)
        repeat(Config.DAYS_PER_MONTH * 2) { e.tick() }
        assertEquals(3, e.history.size)
        val first = e.history[1]
        assertEquals(1, first.month)
        assertEquals(Config.START_YEAR, first.year)
        assertEquals(first.income - first.expenses, first.net)
        assertTrue(first.income >= 0)
        assertTrue(first.expenses >= 0)
        assertEquals(2, e.history[2].month)
    }

    @Test
    fun difficultySetsTheOpeningTreasuryAndSurvivesASave() {
        val easy = CityEngine(seed = 3, enforceUnlocks = false, difficultyLevel = Difficulty.EASY)
        val normal = CityEngine(seed = 3, enforceUnlocks = false)
        val hard = CityEngine(seed = 3, enforceUnlocks = false, difficultyLevel = Difficulty.HARD)
        assertEquals(20_000, easy.funds)
        assertEquals(15_000, normal.funds)
        assertEquals(10_000, hard.funds)
        assertEquals(Difficulty.HARD, hard.difficulty)

        val loaded = CityEngine.load(hard.save())
        assertNotNull(loaded)
        assertEquals(Difficulty.HARD, loaded!!.difficulty)
        assertEquals(hard.funds, loaded.funds)
    }

    @Test
    fun heavyDebtEndsInBankruptcyAndTheLoanCostsMore() {
        val e = CityEngine(8, 8, seed = 4L, enforceUnlocks = false)
        assertEquals(BuildResult.OK, e.build(Tool.PLANT, 4, 4))
        assertTrue(e.expensesPerDay > 0f)
        var days = 0
        while (!e.bankrupt && days < 40_000) {
            e.tick()
            days++
        }
        assertTrue("the treasury eventually runs dry", e.bankrupt)
        assertTrue(e.debtDays >= Config.BANKRUPTCY_DAYS)
        assertTrue(e.funds < 0)

        val cash = e.funds
        assertTrue(e.acceptBailout())
        assertFalse(e.bankrupt)
        assertTrue(e.funds > cash)
        assertEquals(Config.BAILOUT_DEBT, e.loanBalance)
        assertEquals(1, e.bailouts)
        assertFalse("a bailout cannot be taken twice", e.acceptBailout())
    }

    @Test
    fun aDecisionCardWaitsAndItsChoiceChangesTheCity() {
        val e = city(17)
        buildBasicTown(e)
        var guard = 0
        while (e.pendingDecision == null && guard < 400) {
            e.tick()
            guard++
        }
        assertNotNull("a card arrives once the town is big enough", e.pendingDecision)
        assertTrue(e.population >= Config.DECISION_MIN_POPULATION)

        val funds = e.funds
        val happy = e.happiness
        val tax = e.taxRate
        assertNotNull(e.resolveDecision(0))
        assertNull(e.pendingDecision)
        assertTrue(
            "the choice changed something",
            e.funds != funds || e.happiness != happy || e.taxRate != tax
        )
        assertNull("a card resolves only once", e.resolveDecision(0))
    }

    @Test
    fun contractsNameATargetAndCountDown() {
        val e = city(23)
        val c = e.contract
        assertNotNull("a contract is offered with the city", c)
        assertTrue(c!!.target > 0)
        assertTrue(e.contractDaysLeft(c) in 1..Config.CONTRACT_DAYS)

        val custom = Contract(ContractKind.POPULATION, 10, e.totalDays + 5, 100, e.totalDays)
        assertEquals(e.population, e.contractProgress(custom))
        assertEquals(5, e.contractDaysLeft(custom))
    }

    @Test
    fun scoreAndMedalsFollowTheCity() {
        val e = city(41)
        val before = e.cityScore
        val (c, r) = findTile(e) { it.zone == ZoneType.NONE && it.terrain != Terrain.WATER }!!
        assertEquals(BuildResult.OK, e.build(Tool.PARK, c, r))
        assertEquals("a park adds to the score", before + 40, e.cityScore)
        assertEquals("No medal", e.medalName)
        assertEquals(0, e.medalCount)
    }

    @Test
    fun achievementsAreReadFromTheCity() {
        val e = city(43)
        assertEquals(setOf(Achievement.FOUNDED), AchievementJudge.earned(e))
        repeat(5) {
            val spot = findTile(e) { it.zone == ZoneType.NONE && it.terrain != Terrain.WATER }
            assertNotNull(spot)
            assertEquals(BuildResult.OK, e.build(Tool.PARK, spot!!.first, spot.second))
        }
        assertTrue(
            "five parks earn the Green Belt",
            Achievement.GREEN in AchievementJudge.earned(e)
        )
        assertTrue(
            "the founder award is always there",
            Achievement.FOUNDED in AchievementJudge.earned(e)
        )
    }

    @Test
    fun stakesAndContractsSurviveASave() {
        val e = CityEngine(seed = 47, enforceUnlocks = false, mapStyleLevel = MapStyle.BAYSIDE)
        e.setTaxRate(13)
        repeat(Config.DAYS_PER_MONTH) { e.tick() }

        val loaded = CityEngine.load(e.save())
        assertNotNull(loaded)
        assertEquals(e.difficulty, loaded!!.difficulty)
        assertEquals(e.contract, loaded.contract)
        assertEquals(e.decisionCooldown, loaded.decisionCooldown)
        assertEquals(e.debtDays, loaded.debtDays)
        assertEquals(e.bankrupt, loaded.bankrupt)
        assertEquals(e.bailouts, loaded.bailouts)
        assertEquals(e.loanBalance, loaded.loanBalance)
        assertEquals(e.pollutionOffset, loaded.pollutionOffset, 0.001f)
        assertEquals(e.totalDays, loaded.totalDays)
        assertEquals(MapStyle.BAYSIDE, loaded.mapStyle)
    }

    @Test
    fun anOpenDecisionSurvivesSaveAndLoad() {
        val e = city(17)
        buildBasicTown(e)
        var guard = 0
        while (e.pendingDecision == null && guard < 400) {
            e.tick()
            guard++
        }
        val card = e.pendingDecision
        assertNotNull("a card was waiting", card)

        val loaded = CityEngine.load(e.save())
        assertNotNull(loaded)
        assertNotNull("the card survives the save", loaded!!.pendingDecision)
        assertEquals(card!!.kind, loaded.pendingDecision!!.kind)
        assertNotNull(loaded.resolveDecision(0))
        assertNull(loaded.pendingDecision)
    }

    @Test
    fun cc9SavesStillLoadWithTheClassicMap() {
        val e = city(51)
        repeat(Config.DAYS_PER_MONTH) { e.tick() }
        val parts = e.save().split('|')
        assertEquals(24, parts.size)
        val cc9Parts = parts.subList(0, 22).toMutableList()
        cc9Parts[0] = "CC9"

        val loaded = CityEngine.load(cc9Parts.joinToString("|"))
        assertNotNull("the previous save version still loads", loaded)
        assertEquals(MapStyle.RIVERLANDS, loaded!!.mapStyle)
        assertNull(loaded.pendingDecision)
        assertEquals(e.funds, loaded.funds)
    }

    @Test
    fun otherMapsChangeTheLandButKeepADryStart() {
        val bay = CityEngine(seed = 9, enforceUnlocks = false, mapStyleLevel = MapStyle.BAYSIDE)
        val twin = CityEngine(
            seed = 9,
            enforceUnlocks = false,
            mapStyleLevel = MapStyle.TWIN_RIVERS
        )
        assertEquals(MapStyle.BAYSIDE, bay.mapStyle)
        assertEquals(Terrain.GRASS, bay.tile(12, 12)!!.terrain)
        assertEquals(Terrain.GRASS, twin.tile(12, 12)!!.terrain)

        var bayWater = 0
        for (r in 0 until 8) {
            for (c in 16 until 24) {
                if (bay.tile(c, r)!!.terrain == Terrain.WATER) bayWater++
            }
        }
        assertTrue("the bay fills the north-east", bayWater > 20)

        var westWater = 0
        var southWater = 0
        for (r in 0 until 24) {
            for (c in 0 until 9) {
                if (twin.tile(c, r)!!.terrain == Terrain.WATER) westWater++
            }
        }
        for (r in 16 until 24) {
            for (c in 0 until 24) {
                if (twin.tile(c, r)!!.terrain == Terrain.WATER) southWater++
            }
        }
        assertTrue("a channel runs down the west", westWater > 20)
        assertTrue("a channel runs across the south", southWater > 20)
    }

    @Test
    fun vacantLotsAreCountedByZone() {
        val e = city(59)
        val res = findTile(e) { it.zone == ZoneType.NONE && it.terrain != Terrain.WATER }!!
        assertEquals(BuildResult.OK, e.build(Tool.RESIDENTIAL, res.first, res.second))
        val com = findTile(e) { it.zone == ZoneType.NONE && it.terrain != Terrain.WATER }!!
        assertEquals(BuildResult.OK, e.build(Tool.COMMERCIAL, com.first, com.second))
        val ind = findTile(e) { it.zone == ZoneType.NONE && it.terrain != Terrain.WATER }!!
        assertEquals(BuildResult.OK, e.build(Tool.INDUSTRIAL, ind.first, ind.second))

        assertEquals(1, e.vacantCount(ZoneType.RESIDENTIAL))
        assertEquals(1, e.vacantCount(ZoneType.COMMERCIAL))
        assertEquals(1, e.vacantCount(ZoneType.INDUSTRIAL))
        assertEquals(3, e.vacantZoneCount)
    }

    @Test
    fun advisorNamesTheEmptyZones() {
        val e = city(61)
        val tools = arrayOf(Tool.RESIDENTIAL, Tool.COMMERCIAL, Tool.INDUSTRIAL)
        for (i in 0 until 12) {
            val spot = findTile(e) { it.zone == ZoneType.NONE && it.terrain != Terrain.WATER }!!
            assertEquals(BuildResult.OK, e.build(tools[i % 3], spot.first, spot.second))
        }

        val issue = CityAdvisor.report(e).issues.firstOrNull { it.title == "Zoned lots sit empty" }
        assertNotNull("empty lots are called out", issue)
        assertTrue("the advice breaks the count down: ${issue!!.detail}", issue.detail.contains("4 RES"))
        assertTrue(issue.detail.contains("4 COM"))
        assertTrue(issue.detail.contains("4 IND"))
    }

    @Test
    fun cityClockCountsWholeHoursUpToAWeek() {
        val now = 1_000_000_000_000L
        assertEquals(0, CityClock.daysAway(0L, now))
        assertEquals(0, CityClock.daysAway(now + 1000L, now))
        assertEquals(0, CityClock.daysAway(now, now))
        assertEquals(1, CityClock.daysAway(now - 3_600_000L, now))
        assertEquals(7, CityClock.daysAway(now - 100L * 3_600_000L, now))
    }

    @Test
    fun theRecordSurvivesSaveAndLoad() {
        val e = city(31)
        assertEquals(BuildResult.OK, e.build(Tool.PLANT, 10, 10))
        assertEquals(BuildResult.OK, e.build(Tool.ROAD, 10, 11))
        assertEquals(BuildResult.OK, e.build(Tool.WATER, 12, 14))
        assertEquals(BuildResult.OK, e.build(Tool.RESIDENTIAL, 11, 11))
        repeat(Config.DAYS_PER_MONTH * 3) { e.tick() }
        assertTrue(e.history.size >= 3)
        assertTrue(e.save().startsWith("CC11|"))

        val loaded = CityEngine.load(e.save())
        assertNotNull(loaded)
        assertEquals(e.history, loaded!!.history)
        assertEquals(e.funds, loaded.funds)
    }

    @Test
    fun olderSavesStillLoadWithoutARecord() {
        // A CC7 save is this save with the record part taken back out.
        val e = city(5)
        repeat(Config.DAYS_PER_MONTH) { e.tick() }
        val parts = e.save().split('|')
        val cc7 = (listOf("CC7") + parts.subList(1, 11) + parts.subList(12, 13))
            .joinToString("|")

        val loaded = CityEngine.load(cc7)
        assertNotNull(loaded)
        assertEquals(e.funds, loaded!!.funds)
        assertEquals(e.month, loaded.month)
        assertEquals(ZoneType.NONE, loaded.tile(3, 3)!!.zone)
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
        val tiles = parts[12].split(';').joinToString(";") { tile ->
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
        assertEquals("CC11", parts[0])

        // graft a fire and a crime mark onto the first tile
        val tiles = parts[12].split(';').toMutableList()
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
        val cc6Tiles = parts[12].split(';').joinToString(";") { tile ->
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
        assertEquals("CC11", parts[0])
        val cc5 = (listOf("CC5") + parts.subList(1, 10) + listOf(parts[12])).joinToString("|")

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
        val cc4 = (listOf("CC4") + parts.subList(1, 8) + listOf(parts[12])).joinToString("|")
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

    /** Water cells still beside this one; each adds to the fill price. */
    private fun waterSides(e: CityEngine, c: Int, r: Int): Int =
        listOf(c - 1 to r, c + 1 to r, c to r - 1, c to r + 1)
            .count { (cc, rr) -> e.inBounds(cc, rr) && e.tile(cc, rr)!!.terrain == Terrain.WATER }

    /** The first water cell that touches dry land, so it can be filled. */
    private fun findShoreWater(e: CityEngine): Pair<Int, Int> {
        val found = findTile(e) { it.terrain == Terrain.WATER }
            ?: error("the map has no water")
        for (r in 0 until e.rows) {
            for (c in 0 until e.cols) {
                if (e.tile(c, r)!!.terrain != Terrain.WATER) continue
                if (waterSides(e, c, r) < 4) return c to r
            }
        }
        return found
    }

    /** Water with no dry neighbour: filling there would float free of the island. */
    private fun findOpenWater(e: CityEngine): Pair<Int, Int>? {
        for (r in 0 until e.rows) {
            for (c in 0 until e.cols) {
                if (e.tile(c, r)!!.terrain != Terrain.WATER) continue
                if (waterSides(e, c, r) == 4) return c to r
            }
        }
        return null
    }

    /** A fillable bank cell and the open water right behind it. */
    private fun findBankAndOpenWater(e: CityEngine): Pair<Pair<Int, Int>, Pair<Int, Int>>? {
        for (r in 0 until e.rows) {
            for (c in 0 until e.cols) {
                if (e.tile(c, r)!!.terrain != Terrain.WATER) continue
                if (waterSides(e, c, r) == 4) continue
                for ((cc, rr) in listOf(c - 1 to r, c + 1 to r, c to r - 1, c to r + 1)) {
                    if (!e.inBounds(cc, rr)) continue
                    if (e.tile(cc, rr)!!.terrain != Terrain.WATER) continue
                    if (waterSides(e, cc, rr) == 4) return (c to r) to (cc to rr)
                }
            }
        }
        return null
    }

    /** Dry land with at least one water neighbour. */
    private fun findBankland(e: CityEngine): Pair<Int, Int>? {
        for (r in 0 until e.rows) {
            for (c in 0 until e.cols) {
                val t = e.tile(c, r)!!
                if (t.terrain != Terrain.WATER && t.waterfront) return c to r
            }
        }
        return null
    }

    /** Empty dry land away from the water, so it is no use for a canal. */
    private fun findDryLand(e: CityEngine): Pair<Int, Int>? {
        for (r in 0 until e.rows) {
            for (c in 0 until e.cols) {
                val t = e.tile(c, r)!!
                if (t.terrain != Terrain.WATER && !t.waterfront && t.zone == ZoneType.NONE) {
                    return c to r
                }
            }
        }
        return null
    }

    /** In-bounds neighbours of a cell that are not water. */
    private fun inBoundsLandSides(e: CityEngine, c: Int, r: Int): Int =
        listOf(c - 1 to r, c + 1 to r, c to r - 1, c to r + 1)
            .count { (cc, rr) -> e.inBounds(cc, rr) && e.tile(cc, rr)!!.terrain != Terrain.WATER }

    private fun findWaterWithSides(e: CityEngine, sides: Int): Pair<Int, Int>? {
        for (r in 0 until e.rows) {
            for (c in 0 until e.cols) {
                if (e.tile(c, r)!!.terrain != Terrain.WATER) continue
                if (waterSides(e, c, r) == sides) return c to r
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
