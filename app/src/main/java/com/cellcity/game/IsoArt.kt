package com.cellcity.game

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/** Isometric camera shared by the renderer and touch handling. */
class IsoCamera {
    var zoom = 0.85f
    var panX = 0f
    var panY = 0f
    var tileW = 1f
    var tileH = 1f
    var originX = 0f
    var originY = 0f

    fun screenX(col: Float, row: Float): Float =
        originX + (col - row) * tileW * 0.5f * zoom

    fun screenY(col: Float, row: Float): Float =
        originY + (col + row) * tileH * 0.5f * zoom

    fun screenToCol(x: Float, y: Float): Int {
        val dx = (x - originX) / (tileW * 0.5f * zoom)
        val dy = (y - originY) / (tileH * 0.5f * zoom)
        return ((dx + dy) * 0.5f).roundToInt()
    }

    fun screenToRow(x: Float, y: Float): Int {
        val dx = (x - originX) / (tileW * 0.5f * zoom)
        val dy = (y - originY) / (tileH * 0.5f * zoom)
        return ((dy - dx) * 0.5f).roundToInt()
    }
}

/**
 * Draws the whole city: flat colour blocks, bold ink outlines, animated water,
 * moving traffic and the little warning badges that make the simulation legible.
 */
class CityRenderer(private val density: Float) {

    val camera = IsoCamera()
    private var viewW = 0f
    private var viewH = 0f
    private var initialized = false

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val dashed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val zoneText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val path = Path()
    private val rect = RectF()
    private val rnd = Random(9182)

    private val cars = ArrayList<Car>()
    private val trains = ArrayList<Car>()

    private class Car(
        var col: Int,
        var row: Int,
        var nextCol: Int,
        var nextRow: Int,
        var t: Float,
        val color: Int
    )

    companion object {
        private const val GRASS_A = 0xFF7FCE6F.toInt()
        private const val GRASS_B = 0xFF76C266.toInt()
        private const val GRASS_EDGE = 0xFF2F6B45.toInt()
        private const val GRASS_SKIRT = 0xFF356F49.toInt()
        private const val WATER = 0xFF3FA9DD.toInt()
        private const val WATER_LIGHT = 0xFF5AC3EE.toInt()
        private const val WATER_EDGE = 0xFF1B5A82.toInt()
        private const val ROAD = 0xFF4A4F63.toInt()
        private const val ROAD_INNER = 0xFF5A6077.toInt()
        private const val ROAD_EDGE = 0xFF262B3D.toInt()
        private const val HIGHWAY_ASPHALT = 0xFF373C4E.toInt()
        private const val HIGHWAY_EDGE = 0xFF191D28.toInt()
        private const val BRIDGE = 0xFF9A6D48.toInt()
        private const val BRIDGE_DARK = 0xFF6E4A31.toInt()
        private const val CONCRETE = 0xFFB9B7A9.toInt()
        private const val CONCRETE_DARK = 0xFF8D8B80.toInt()
        private const val ZONE_R = 0xFF8FE08A.toInt()
        private const val ZONE_C = 0xFF8AD7F0.toInt()
        private const val ZONE_I = 0xFFF1D489.toInt()
        private const val ZONE_R_DARK = 0xFF3F8A46.toInt()
        private const val ZONE_C_DARK = 0xFF2E7A97.toInt()
        private const val ZONE_I_DARK = 0xFF9B7930.toInt()
        private const val CREAM = 0xFFFFF3D6.toInt()
        private const val PAPER = 0xFFFFFBEF.toInt()
        private const val AMBER = 0xFFFFD166.toInt()
        private const val CORAL = 0xFFE86A5B.toInt()
        private const val INK = 0xFF13203A.toInt()
        private const val WINDOW = 0xFFBFEFFF.toInt()
        private const val TRUNK = 0xFF7A5138.toInt()
        private const val LEAF = 0xFF4FAE5A.toInt()
        private const val LEAF_LIGHT = 0xFF7FD97F.toInt()
        private const val LEAF_DARK = 0xFF2F7A41.toInt()
        private const val SMOKE = 0xFFE9E3D4.toInt()

        private val CAR_COLORS = intArrayOf(
            0xFFE86A5B.toInt(),
            0xFF4FA3B8.toInt(),
            0xFFFFD166.toInt(),
            0xFF8FE08A.toInt(),
            0xFFB9B7A9.toInt()
        )
        private val ROOFS = intArrayOf(
            0xFFE86A5B.toInt(),
            0xFF4FA3B8.toInt(),
            0xFFD98E4A.toInt(),
            0xFF9B6FB0.toInt()
        )
        private val WALLS = intArrayOf(
            0xFFF4E2C4.toInt(),
            0xFFFFE8CC.toInt(),
            0xFFEFD6B4.toInt(),
            0xFFF8E9D2.toInt()
        )
        private val SHOPS = intArrayOf(
            0xFF6FE0D0.toInt(),
            0xFF79C7E8.toInt(),
            0xFF9FE0C0.toInt(),
            0xFF89B8E8.toInt()
        )
        private val FACTORIES = intArrayOf(
            0xFFC9B18E.toInt(),
            0xFFB7A897.toInt(),
            0xFFD2BC96.toInt(),
            0xFFB2A58E.toInt()
        )
    }

    // ------------------------------------------------------------------ camera

    fun layout(w: Float, h: Float, topInset: Float, bottomInset: Float, cols: Int, rows: Int) {
        viewW = w
        viewH = h
        camera.tileW = Config.TILE_W * density
        camera.tileH = Config.TILE_H * density
        val firstLayout = !initialized && w > 0f
        if (firstLayout) {
            initialized = true
            camera.zoom = (w / (camera.tileW * 6.6f)).coerceIn(0.55f, 1.15f)
        }
        camera.zoom = camera.zoom.coerceIn(minZoom(cols, rows), 1.55f)
        if (firstLayout) {
            // the projection origin is tile (0,0), so shift the island's centre
            // onto the visible map area instead of its north corner
            camera.panX = 0f
            camera.panY = -(cols - 1 + rows - 1) * 0.5f * camera.tileH * 0.5f * camera.zoom
        }
        camera.originX = w * 0.5f + camera.panX
        camera.originY = topInset + (h - topInset - bottomInset) * 0.46f + camera.panY
    }

    fun minZoom(cols: Int, rows: Int): Float {
        val mapWidth = (cols + rows) * camera.tileW * 0.5f
        return (viewW / max(1f, mapWidth) * 0.92f).coerceIn(0.26f, 1.2f)
    }

    fun maxZoom(): Float = 1.55f

    fun focusOn(col: Int, row: Int) {
        camera.panX = -(col - row) * camera.tileW * 0.5f * camera.zoom
        camera.panY = -(col + row) * camera.tileH * 0.5f * camera.zoom
    }

    // ------------------------------------------------------------------ traffic

    fun updateCars(dt: Float, engine: CityEngine) {
        val roads = ArrayList<Int>()
        for (r in 0 until engine.rows) {
            for (c in 0 until engine.cols) {
                if (engine.tile(c, r)?.zone?.isRoad == true) roads.add(r * engine.cols + c)
            }
        }
        updateTrains(dt, engine)
        if (roads.isEmpty()) {
            cars.clear()
            return
        }
        val wanted = min(14, roads.size / 7)
        var guard = 0
        while (cars.size < wanted && guard++ < 30) {
            val idx = roads[rnd.nextInt(roads.size)]
            val col = idx % engine.cols
            val row = idx / engine.cols
            val next = pickRoad(engine, col, row, -1, -1) ?: continue
            cars.add(
                Car(
                    col, row, next % engine.cols, next / engine.cols,
                    0f, CAR_COLORS[cars.size % CAR_COLORS.size]
                )
            )
        }

        val iterator = cars.iterator()
        while (iterator.hasNext()) {
            val car = iterator.next()
            val here = engine.tile(car.col, car.row)?.zone
            if (here?.isRoad != true) {
                iterator.remove()
                continue
            }
            car.t += dt * (if (here == ZoneType.HIGHWAY) 1.05f else 0.62f)
            if (car.t >= 1f) {
                car.col = car.nextCol
                car.row = car.nextRow
                car.t = 0f
                val next = pickRoad(engine, car.col, car.row, car.col, car.row)
                    ?: pickRoadAny(engine, car.col, car.row)
                if (next == null) {
                    iterator.remove()
                } else {
                    car.nextCol = next % engine.cols
                    car.nextRow = next / engine.cols
                }
            }
        }
    }

    private fun pickRoad(engine: CityEngine, col: Int, row: Int, fromCol: Int, fromRow: Int): Int? {
        val options = ArrayList<Int>(4)
        addRoad(engine, options, col - 1, row)
        addRoad(engine, options, col + 1, row)
        addRoad(engine, options, col, row - 1)
        addRoad(engine, options, col, row + 1)
        if (options.isEmpty()) return null
        val forward = options.filter { it != fromRow * engine.cols + fromCol }
        val pool = forward.ifEmpty { options }
        return pool[rnd.nextInt(pool.size)]
    }

    private fun pickRoadAny(engine: CityEngine, col: Int, row: Int): Int? =
        pickRoad(engine, col, row, -1, -1)

    private fun addRoad(engine: CityEngine, out: ArrayList<Int>, col: Int, row: Int) {
        if (engine.tile(col, row)?.zone?.isRoad == true) out.add(row * engine.cols + col)
    }

    private fun updateTrains(dt: Float, engine: CityEngine) {
        val rails = ArrayList<Int>()
        for (r in 0 until engine.rows) {
            for (c in 0 until engine.cols) {
                if (engine.tile(c, r)?.zone == ZoneType.RAIL) rails.add(r * engine.cols + c)
            }
        }
        if (rails.isEmpty()) {
            trains.clear()
            return
        }
        val wanted = min(3, rails.size / 8)
        var guard = 0
        while (trains.size < wanted && guard++ < 20) {
            val idx = rails[rnd.nextInt(rails.size)]
            val col = idx % engine.cols
            val row = idx / engine.cols
            val next = pickRail(engine, col, row, -1, -1) ?: continue
            trains.add(
                Car(
                    col, row, next % engine.cols, next / engine.cols,
                    0f, 0xFF33455C.toInt()
                )
            )
        }

        val iterator = trains.iterator()
        while (iterator.hasNext()) {
            val train = iterator.next()
            if (engine.tile(train.col, train.row)?.zone != ZoneType.RAIL) {
                iterator.remove()
                continue
            }
            train.t += dt * 0.38f
            if (train.t >= 1f) {
                train.col = train.nextCol
                train.row = train.nextRow
                train.t = 0f
                val next = pickRail(engine, train.col, train.row, train.col, train.row)
                    ?: pickRail(engine, train.col, train.row, -1, -1)
                if (next == null) {
                    iterator.remove()
                } else {
                    train.nextCol = next % engine.cols
                    train.nextRow = next / engine.cols
                }
            }
        }
    }

    private fun pickRail(engine: CityEngine, col: Int, row: Int, fromCol: Int, fromRow: Int): Int? {
        val options = ArrayList<Int>(4)
        addRail(engine, options, col - 1, row)
        addRail(engine, options, col + 1, row)
        addRail(engine, options, col, row - 1)
        addRail(engine, options, col, row + 1)
        if (options.isEmpty()) return null
        val forward = options.filter { it != fromRow * engine.cols + fromCol }
        val pool = forward.ifEmpty { options }
        return pool[rnd.nextInt(pool.size)]
    }

    private fun addRail(engine: CityEngine, out: ArrayList<Int>, col: Int, row: Int) {
        if (engine.tile(col, row)?.zone == ZoneType.RAIL) out.add(row * engine.cols + col)
    }

    // ------------------------------------------------------------------ frame

    fun draw(
        canvas: Canvas,
        engine: CityEngine,
        tool: Tool,
        hoverCol: Int,
        hoverRow: Int,
        hoverValid: Boolean,
        time: Float
    ) {
        val u = density * camera.zoom
        dashed.pathEffect = DashPathEffect(floatArrayOf(5f * u, 5f * u), 0f)
        drawSea(canvas, u, time)
        drawIslandBase(canvas, engine, u)

        val margin = camera.tileW * camera.zoom
        for (sum in 0 until engine.cols + engine.rows - 1) {
            val from = max(0, sum - engine.rows + 1)
            val to = min(engine.cols - 1, sum)
            for (col in from..to) {
                val row = sum - col
                val cx = camera.screenX(col.toFloat(), row.toFloat())
                val cy = camera.screenY(col.toFloat(), row.toFloat())
                if (cx < -margin || cx > viewW + margin) continue
                if (cy < -margin || cy > viewH + margin) continue
                val tile = engine.tile(col, row) ?: continue
                drawTile(canvas, engine, tile, col, row, cx, cy, u, time)
            }
        }

        // buried layers normally hide under buildings; their tool lifts them on top
        if (tool == Tool.PIPE) drawConduitOverlay(canvas, engine, u, power = false)
        if (tool == Tool.POWERLINE) drawConduitOverlay(canvas, engine, u, power = true)

        drawCars(canvas, u)
        drawTrains(canvas, u)
        drawHover(canvas, engine, tool, hoverCol, hoverRow, hoverValid, u)
    }

    private fun drawSea(canvas: Canvas, u: Float, time: Float) {
        fill.color = Config.SKY
        canvas.drawRect(0f, 0f, viewW, viewH, fill)
        fill.color = Config.SEA
        canvas.drawRect(0f, viewH * 0.32f, viewW, viewH, fill)

        line.color = Config.SEA_LIGHT
        line.strokeWidth = 2f * u
        for (i in 0 until 9) {
            val baseY = viewH * (0.34f + i * 0.085f)
            path.reset()
            var x = -40f
            var first = true
            while (x <= viewW + 60f) {
                val y = baseY + sin((x * 0.006f) + time * 0.35f + i * 0.9f) * 5f * u
                if (first) {
                    path.moveTo(x, y)
                    first = false
                } else {
                    path.lineTo(x, y)
                }
                x += 24f * u
            }
            canvas.drawPath(path, line)
        }

        line.color = 0x99FFD166.toInt()
        line.strokeWidth = 2.4f * u
        for (i in 0 until 5) {
            val y = viewH * (0.40f + i * 0.13f) + sin(time * 0.3f + i) * 10f * u
            val x = (viewW * (0.12f + i * 0.2f) + time * 9f * u) % (viewW + 80f * u) - 40f * u
            path.reset()
            path.moveTo(x, y)
            path.lineTo(x + 26f * u, y)
            canvas.drawPath(path, line)
        }
    }

    private fun drawIslandBase(canvas: Canvas, engine: CityEngine, u: Float) {
        val hw = camera.tileW * 0.5f * camera.zoom
        val hh = camera.tileH * 0.5f * camera.zoom
        val topX = camera.screenX(0f, 0f)
        val topY = camera.screenY(0f, 0f) - hh
        val rightX = camera.screenX(engine.cols - 1f, 0f) + hw
        val rightY = camera.screenY(engine.cols - 1f, 0f)
        val bottomX = camera.screenX(engine.cols - 1f, engine.rows - 1f)
        val bottomY = camera.screenY(engine.cols - 1f, engine.rows - 1f) + hh
        val leftX = camera.screenX(0f, engine.rows - 1f) - hw
        val leftY = camera.screenY(0f, engine.rows - 1f)

        path.reset()
        path.moveTo(topX, topY + 12f * u)
        path.lineTo(rightX, rightY + 12f * u)
        path.lineTo(bottomX, bottomY + 12f * u)
        path.lineTo(leftX, leftY + 12f * u)
        path.close()
        fill.color = 0xFF0B1828.toInt()
        canvas.drawPath(path, fill)

        path.reset()
        path.moveTo(topX, topY + 6f * u)
        path.lineTo(rightX, rightY + 6f * u)
        path.lineTo(bottomX, bottomY + 6f * u)
        path.lineTo(leftX, leftY + 6f * u)
        path.close()
        fill.color = 0xFF2A5A3F.toInt()
        canvas.drawPath(path, fill)
        line.color = INK
        line.strokeWidth = max(1.2f, 1.8f * u)
        canvas.drawPath(path, line)
    }

    // ------------------------------------------------------------------ tile

    private fun drawTile(
        canvas: Canvas,
        engine: CityEngine,
        tile: Tile,
        col: Int,
        row: Int,
        cx: Float,
        cy: Float,
        u: Float,
        time: Float
    ) {
        val hw = camera.tileW * 0.5f * camera.zoom
        val hh = camera.tileH * 0.5f * camera.zoom
        val inBlock = tile.merged && tile.originCol >= 0 && tile.originRow >= 0
        val origin = inBlock && tile.originCol == col && tile.originRow == row
        // the block origin paints the whole 2x2 footprint for every tile in it
        if (inBlock && !origin) return
        val bridge = tile.zone.isRoad && tile.terrain == Terrain.WATER
        val inkW = max(1.1f, 1.5f * u)

        if (tile.terrain == Terrain.WATER) {
            drawWater(canvas, cx, cy, hw, hh, u, col, row, time)
        } else {
            val base = when {
                tile.zone == ZoneType.ROAD -> ROAD
                tile.zone == ZoneType.HIGHWAY -> HIGHWAY_ASPHALT
                else -> grassTone(col, row)
            }
            diamondPath(cx, cy + 4.5f * u, hw * 0.99f, hh * 0.99f)
            fill.color = darken(base, 0.52f)
            canvas.drawPath(path, fill)

            diamondPath(cx, cy, hw, hh)
            fill.color = base
            canvas.drawPath(path, fill)
            line.color = darken(base, 0.68f)
            line.strokeWidth = inkW
            canvas.drawPath(path, line)
        }

        // buried mains and cables sit under whatever is built on the tile
        if (tile.pipe && !inBlock) {
            drawConduit(canvas, engine, col, row, cx, cy, hw, hh, u, tile, power = false)
        }
        if (tile.powerline && !inBlock) {
            drawConduit(canvas, engine, col, row, cx, cy, hw, hh, u, tile, power = true)
        }

        if (origin) {
            val bcx = camera.screenX(col + 0.5f, row + 0.5f)
            val bcy = camera.screenY(col + 0.5f, row + 0.5f)
            if (tile.zone == ZoneType.AIRPORT) {
                drawAirport(canvas, tile, bcx, bcy, hw * 2f, hh * 2f, u, time)
            } else {
                drawBlockBase(canvas, tile, bcx, bcy, hw * 2f, hh * 2f, u)
                drawBlockBuilding(canvas, tile, bcx, bcy, hw * 2f, hh * 2f, u, time)
            }
        } else {
            if (bridge) drawRoad(canvas, engine, col, row, cx, cy, hw, hh, u, true)
            if (tile.zone == ZoneType.ROAD) drawRoad(canvas, engine, col, row, cx, cy, hw, hh, u, false)
            if (tile.zone == ZoneType.HIGHWAY && !bridge) {
                drawHighway(canvas, engine, col, row, cx, cy, hw, hh, u)
            }
            if (tile.zone == ZoneType.RAIL) drawRail(canvas, engine, col, row, cx, cy, hw, hh, u)
            if (tile.zone == ZoneType.STATION) drawTrainStation(canvas, cx, cy, hw, hh, u)
            if (tile.zone == ZoneType.PARK) drawPark(canvas, cx, cy, hw, hh, u, col, row, time)
            if (tile.zone == ZoneType.PLANT) drawPlant(canvas, cx, cy, hw, hh, u, col, row, time)
            if (tile.zone == ZoneType.WATERWORKS) drawWaterWorks(canvas, cx, cy, hw, hh, u)
            if (tile.zone == ZoneType.POLICE) drawPolice(canvas, cx, cy, hw, hh, u)
            if (tile.zone == ZoneType.FIRE) drawFireStation(canvas, cx, cy, hw, hh, u, time)
            if (tile.zone == ZoneType.HOSPITAL) drawHospital(canvas, cx, cy, hw, hh, u)
            if (tile.zone.isZone) {
                if (tile.level == 0) {
                    drawVacantPlot(canvas, tile, cx, cy, hw, hh, u)
                } else {
                    drawBuilding(canvas, tile, cx, cy, hw, hh, u, time)
                }
            }
            if (tile.zone == ZoneType.NONE && tile.terrain == Terrain.FOREST) {
                drawForest(canvas, cx, cy, hw, hh, u, col, row)
            }
            if (tile.zone == ZoneType.NONE && tile.terrain == Terrain.GRASS) {
                drawGrassDetail(canvas, cx, cy, hw, hh, u, col, row)
            }
        }

        if (tile.zone.isZone) {
            val badges = ArrayList<Badge>(3)
            if (!tile.roadAccess) badges.add(Badge.ROAD)
            if (!tile.powered) badges.add(Badge.POWER)
            if (!tile.watered) badges.add(Badge.WATER)
            for (i in badges.indices) {
                drawBadge(canvas, cx + hw * 0.42f - i * 10.5f * u, cy - hh * 0.25f, u, badges[i])
            }
        }
    }

    private fun grassTone(col: Int, row: Int): Int =
        if ((col + row) % 2 == 0) GRASS_A else GRASS_B

    private fun diamondPath(cx: Float, cy: Float, hw: Float, hh: Float) {
        path.reset()
        path.moveTo(cx, cy - hh)
        path.lineTo(cx + hw, cy)
        path.lineTo(cx, cy + hh)
        path.lineTo(cx - hw, cy)
        path.close()
    }

    private fun drawWater(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        hw: Float,
        hh: Float,
        u: Float,
        col: Int,
        row: Int,
        time: Float
    ) {
        diamondPath(cx, cy, hw, hh)
        fill.color = WATER
        canvas.drawPath(path, fill)
        line.color = WATER_EDGE
        line.strokeWidth = max(1.1f, 1.5f * u)
        canvas.drawPath(path, line)

        diamondPath(cx, cy + 1.2f * u, hw * 0.62f, hh * 0.62f)
        fill.color = WATER_LIGHT
        canvas.drawPath(path, fill)

        val wobble = sin(time * 0.9f + col * 0.7f + row * 0.4f) * 1.6f * u
        line.color = 0xCCEAF9FF.toInt()
        line.strokeWidth = 1.4f * u
        path.reset()
        path.moveTo(cx - hw * 0.34f, cy + hh * 0.16f + wobble)
        path.lineTo(cx - hw * 0.06f, cy + hh * 0.30f + wobble)
        canvas.drawPath(path, line)
        path.reset()
        path.moveTo(cx + hw * 0.12f, cy - hh * 0.18f - wobble)
        path.lineTo(cx + hw * 0.38f, cy - hh * 0.04f - wobble)
        canvas.drawPath(path, line)
    }

    private fun drawRoad(
        canvas: Canvas,
        engine: CityEngine,
        col: Int,
        row: Int,
        cx: Float,
        cy: Float,
        hw: Float,
        hh: Float,
        u: Float,
        bridge: Boolean
    ) {
        if (bridge) {
            // supports
            fill.color = BRIDGE_DARK
            rect.set(cx - hw * 0.34f, cy + hh * 0.2f, cx - hw * 0.22f, cy + hh * 1.0f)
            canvas.drawRect(rect, fill)
            rect.set(cx + hw * 0.22f, cy + hh * 0.2f, cx + hw * 0.34f, cy + hh * 1.0f)
            canvas.drawRect(rect, fill)
            diamondPath(cx, cy + 2f * u, hw * 0.98f, hh * 0.98f)
            fill.color = BRIDGE
            canvas.drawPath(path, fill)
            line.color = INK
            line.strokeWidth = max(1.1f, 1.6f * u)
            canvas.drawPath(path, line)
        }

        diamondPath(cx, cy, hw * 0.72f, hh * 0.72f)
        fill.color = if (bridge) ROAD else ROAD
        canvas.drawPath(path, fill)
        line.color = ROAD_EDGE
        line.strokeWidth = max(1.1f, 1.5f * u)
        canvas.drawPath(path, line)

        // lane paint reaching toward each connected road
        dashed.color = 0xFFF3DD8B.toInt()
        dashed.strokeWidth = max(1f, 1.7f * u)
        val dirs = intArrayOf(0, 0, 0, 0)
        dirs[0] = if (engine.tile(col + 1, row)?.zone?.isRoad == true) 1 else 0
        dirs[1] = if (engine.tile(col, row + 1)?.zone?.isRoad == true) 1 else 0
        dirs[2] = if (engine.tile(col - 1, row)?.zone?.isRoad == true) 1 else 0
        dirs[3] = if (engine.tile(col, row - 1)?.zone?.isRoad == true) 1 else 0

        val dxs = floatArrayOf(hw, -hw, -hw, hw)
        val dys = floatArrayOf(hh, hh, -hh, -hh)
        path.reset()
        for (d in 0 until 4) {
            if (dirs[d] == 0) continue
            path.moveTo(cx + dxs[d] * 0.1f, cy + dys[d] * 0.1f)
            path.lineTo(cx + dxs[d] * 0.47f, cy + dys[d] * 0.47f)
        }
        canvas.drawPath(path, dashed)

        fill.color = 0xFFF3DD8B.toInt()
        diamondPath(cx, cy, hw * 0.06f, hh * 0.06f)
        canvas.drawPath(path, fill)
    }

    /** A four-lane highway: wide asphalt, guard rails and a painted median. */
    private fun drawHighway(
        canvas: Canvas,
        engine: CityEngine,
        col: Int,
        row: Int,
        cx: Float,
        cy: Float,
        hw: Float,
        hh: Float,
        u: Float
    ) {
        diamondPath(cx, cy, hw * 0.94f, hh * 0.94f)
        fill.color = HIGHWAY_ASPHALT
        canvas.drawPath(path, fill)
        line.color = HIGHWAY_EDGE
        line.strokeWidth = max(1.1f, 1.5f * u)
        canvas.drawPath(path, line)

        val dirs = intArrayOf(0, 0, 0, 0)
        dirs[0] = if (engine.tile(col + 1, row)?.zone?.isRoad == true) 1 else 0
        dirs[1] = if (engine.tile(col, row + 1)?.zone?.isRoad == true) 1 else 0
        dirs[2] = if (engine.tile(col - 1, row)?.zone?.isRoad == true) 1 else 0
        dirs[3] = if (engine.tile(col, row - 1)?.zone?.isRoad == true) 1 else 0
        val dxs = floatArrayOf(hw, -hw, -hw, hw)
        val dys = floatArrayOf(hh, hh, -hh, -hh)

        // painted median
        dashed.color = 0xFFF3DD8B.toInt()
        dashed.strokeWidth = max(1.2f, 2f * u)
        path.reset()
        for (d in 0 until 4) {
            if (dirs[d] == 0) continue
            path.moveTo(cx + dxs[d] * 0.08f, cy + dys[d] * 0.08f)
            path.lineTo(cx + dxs[d] * 0.52f, cy + dys[d] * 0.52f)
        }
        canvas.drawPath(path, dashed)

        // guard rails along both shoulders
        line.color = 0xFFCBD2E0.toInt()
        line.strokeWidth = max(0.8f, 1.1f * u)
        path.reset()
        for (d in 0 until 4) {
            if (dirs[d] == 0) continue
            val ex = dxs[d]
            val ey = dys[d]
            val px = ex * 0.3f
            val py = -ey * 0.3f
            path.moveTo(cx + ex * 0.1f + px, cy + ey * 0.1f + py)
            path.lineTo(cx + ex * 0.5f + px * 0.92f, cy + ey * 0.5f + py * 0.92f)
            path.moveTo(cx + ex * 0.1f - px, cy + ey * 0.1f - py)
            path.lineTo(cx + ex * 0.5f - px * 0.92f, cy + ey * 0.5f - py * 0.92f)
        }
        canvas.drawPath(path, line)

        fill.color = 0xFFF3DD8B.toInt()
        diamondPath(cx, cy, hw * 0.08f, hh * 0.08f)
        canvas.drawPath(path, fill)
    }

    // ------------------------------------------------------------------ zones

    private fun drawVacantPlot(
        canvas: Canvas,
        tile: Tile,
        cx: Float,
        cy: Float,
        hw: Float,
        hh: Float,
        u: Float
    ) {
        val plot = when (tile.zone) {
            ZoneType.RESIDENTIAL -> ZONE_R
            ZoneType.COMMERCIAL -> ZONE_C
            else -> ZONE_I
        }
        val plotDark = when (tile.zone) {
            ZoneType.RESIDENTIAL -> ZONE_R_DARK
            ZoneType.COMMERCIAL -> ZONE_C_DARK
            else -> ZONE_I_DARK
        }
        diamondPath(cx, cy, hw * 0.76f, hh * 0.76f)
        fill.color = plot
        canvas.drawPath(path, fill)
        line.color = plotDark
        line.strokeWidth = max(1f, 1.4f * u)
        canvas.drawPath(path, line)

        dashed.color = PAPER
        dashed.strokeWidth = max(1f, 1.2f * u)
        diamondPath(cx, cy, hw * 0.54f, hh * 0.54f)
        canvas.drawPath(path, dashed)

        zoneText.color = plotDark
        zoneText.textSize = 13f * u
        val letter = when (tile.zone) {
            ZoneType.RESIDENTIAL -> "R"
            ZoneType.COMMERCIAL -> "C"
            else -> "I"
        }
        canvas.drawText(letter, cx, cy + 4.5f * u, zoneText)
    }

    // ------------------------------------------------------------------ trees

    private fun drawForest(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        hw: Float,
        hh: Float,
        u: Float,
        col: Int,
        row: Int
    ) {
        val variant = ((col * 7 + row * 13) and 3)
        if (variant == 0) {
            tree(canvas, cx - hw * 0.18f, cy + hh * 0.20f, 1.05f, u)
            tree(canvas, cx + hw * 0.30f, cy + hh * 0.05f, 0.8f, u)
            tree(canvas, cx - hw * 0.36f, cy - hh * 0.15f, 0.7f, u)
        } else if (variant == 1) {
            tree(canvas, cx + hw * 0.14f, cy + hh * 0.16f, 1.0f, u)
            tree(canvas, cx - hw * 0.30f, cy - hh * 0.05f, 0.85f, u)
        } else {
            tree(canvas, cx - hw * 0.05f, cy - hh * 0.12f, 1.1f, u)
            tree(canvas, cx + hw * 0.34f, cy + hh * 0.24f, 0.75f, u)
        }
    }

    private fun drawGrassDetail(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        hw: Float,
        hh: Float,
        u: Float,
        col: Int,
        row: Int
    ) {
        val hash = col * 37 + row * 71
        if (hash % 5 != 0) return
        line.color = darken(GRASS_A, 0.7f)
        line.strokeWidth = 1.3f * u
        path.reset()
        val x = cx + ((hash % 7) - 3) * 0.08f * hw
        val y = cy + ((hash % 5) - 2) * 0.08f * hh
        path.moveTo(x - 2f * u, y)
        path.lineTo(x - 1f * u, y - 3.4f * u)
        path.moveTo(x, y)
        path.lineTo(x + 1.2f * u, y - 3.8f * u)
        path.moveTo(x + 2f * u, y)
        path.lineTo(x + 2.8f * u, y - 2.8f * u)
        canvas.drawPath(path, line)
    }

    private fun tree(canvas: Canvas, x: Float, baseY: Float, scale: Float, u: Float) {
        val s = scale
        fill.color = 0x33000000
        canvas.drawOval(x - 6f * u * s, baseY - 2f * u * s, x + 6f * u * s, baseY + 2f * u * s, fill)

        fill.color = TRUNK
        rect.set(x - 1.6f * u * s, baseY - 6f * u * s, x + 1.6f * u * s, baseY)
        canvas.drawRect(rect, fill)

        fill.color = LEAF
        canvas.drawCircle(x, baseY - 11f * u * s, 6.4f * u * s, fill)
        line.color = INK
        line.strokeWidth = max(1f, 1.3f * u)
        canvas.drawCircle(x, baseY - 11f * u * s, 6.4f * u * s, line)

        fill.color = LEAF_LIGHT
        canvas.drawCircle(x - 2.2f * u * s, baseY - 13f * u * s, 2.4f * u * s, fill)
        fill.color = LEAF_DARK
        canvas.drawCircle(x + 2.5f * u * s, baseY - 9f * u * s, 2.1f * u * s, fill)
    }

    // ------------------------------------------------------------------ buildings

    private fun drawBuilding(
        canvas: Canvas,
        tile: Tile,
        cx: Float,
        cy: Float,
        hw: Float,
        hh: Float,
        u: Float,
        time: Float
    ) {
        // pad
        val pad = when (tile.zone) {
            ZoneType.RESIDENTIAL -> darken(ZONE_R, 1.02f)
            ZoneType.COMMERCIAL -> darken(ZONE_C, 1.02f)
            else -> darken(ZONE_I, 1.02f)
        }
        diamondPath(cx, cy, hw * 0.8f, hh * 0.8f)
        fill.color = pad
        canvas.drawPath(path, fill)

        when (tile.zone) {
            ZoneType.RESIDENTIAL -> drawHouse(canvas, tile, cx, cy, hw, hh, u)
            ZoneType.COMMERCIAL -> drawShop(canvas, tile, cx, cy, hw, hh, u)
            ZoneType.INDUSTRIAL -> drawFactory(canvas, tile, cx, cy, hw, hh, u, time)
            else -> Unit
        }
    }

    private fun block(
        canvas: Canvas,
        cx: Float,
        baseY: Float,
        hw: Float,
        hh: Float,
        h: Float,
        top: Int,
        left: Int,
        right: Int,
        inkW: Float
    ) {
        val ty = baseY - h
        // left face
        path.reset()
        path.moveTo(cx - hw, ty)
        path.lineTo(cx, ty + hh)
        path.lineTo(cx, baseY + hh)
        path.lineTo(cx - hw, baseY)
        path.close()
        fill.color = left
        canvas.drawPath(path, fill)
        line.color = INK
        line.strokeWidth = inkW
        canvas.drawPath(path, line)

        // right face
        path.reset()
        path.moveTo(cx, ty + hh)
        path.lineTo(cx + hw, ty)
        path.lineTo(cx + hw, baseY)
        path.lineTo(cx, baseY + hh)
        path.close()
        fill.color = right
        canvas.drawPath(path, fill)
        line.color = INK
        line.strokeWidth = inkW
        canvas.drawPath(path, line)

        // roof
        path.reset()
        path.moveTo(cx, ty - hh)
        path.lineTo(cx + hw, ty)
        path.lineTo(cx, ty + hh)
        path.lineTo(cx - hw, ty)
        path.close()
        fill.color = top
        canvas.drawPath(path, fill)
        line.color = INK
        line.strokeWidth = inkW
        canvas.drawPath(path, line)
    }

    /** Windows and doors, laid out in the local coordinates of a face. */
    private fun faceQuad(
        canvas: Canvas,
        ax: Float, ay: Float, bx: Float, by: Float,
        cx: Float, cy: Float, dx: Float, dy: Float,
        u0: Float, u1: Float, v0: Float, v1: Float,
        color: Int,
        inkW: Float
    ) {
        val p1x = ax + (bx - ax) * u0 + (dx - ax) * v0
        val p1y = ay + (by - ay) * u0 + (dy - ay) * v0
        val p2x = ax + (bx - ax) * u1 + (dx - ax) * v0
        val p2y = ay + (by - ay) * u1 + (dy - ay) * v0
        val p3x = ax + (bx - ax) * u1 + (dx - ax) * v1
        val p3y = ay + (by - ay) * u1 + (dy - ay) * v1
        val p4x = ax + (bx - ax) * u0 + (dx - ax) * v1
        val p4y = ay + (by - ay) * u0 + (dy - ay) * v1
        path.reset()
        path.moveTo(p1x, p1y)
        path.lineTo(p2x, p2y)
        path.lineTo(p3x, p3y)
        path.lineTo(p4x, p4y)
        path.close()
        fill.color = color
        canvas.drawPath(path, fill)
        line.color = INK
        line.strokeWidth = inkW * 0.7f
        canvas.drawPath(path, line)
    }

    private fun drawHouse(
        canvas: Canvas,
        tile: Tile,
        cx: Float,
        cy: Float,
        hw: Float,
        hh: Float,
        u: Float,
        big: Boolean = false
    ) {
        val inkW = max(1.1f, 1.5f * u)
        val hs = if (big) 2.0f else 1f
        val fw = hw * 0.66f
        val fh = fw * 0.5f
        val baseY = cy + hh * 0.18f
        val walls = WALLS[tile.variant % WALLS.size]
        val roof = ROOFS[tile.variant % ROOFS.size]
        val wallH = (12f + tile.level * 4.5f) * u * hs

        block(
            canvas, cx, baseY, fw, fh, wallH,
            top = lighten(walls, 1.06f),
            left = walls,
            right = darken(walls, 0.82f),
            inkW = inkW
        )

        val topY = baseY - wallH
        val leftAx = cx - fw
        val leftAy = topY
        val leftBx = cx
        val leftBy = topY + fh
        val leftDx = cx - fw
        val leftDy = baseY
        // left-face windows
        val winRows = if (tile.level >= 3) 2 else 1
        for (i in 0 until winRows) {
            val v0 = 0.24f + i * 0.30f
            faceQuad(
                canvas, leftAx, leftAy, leftBx, leftBy, cx, baseY + fh, leftDx, leftDy,
                0.22f, 0.46f, v0, v0 + 0.18f, WINDOW, inkW
            )
        }
        // right-face door + window
        val rightAx = cx
        val rightAy = topY + fh
        val rightBx = cx + fw
        val rightBy = topY
            faceQuad(
                canvas, rightAx, rightAy, rightBx, rightBy, cx + fw, baseY, cx, baseY + fh,
                0.22f, 0.44f, 0.52f, 0.92f, darken(roof, 0.7f), inkW
            )
        faceQuad(
            canvas, rightAx, rightAy, rightBx, rightBy, cx + fw, baseY, cx, baseY + fh,
            0.56f, 0.80f, 0.30f, 0.54f, WINDOW, inkW
        )

        // hip roof
        val roofH = (10f + tile.level * 2.2f) * u * hs
        val apexY = topY - roofH
        path.reset()
        path.moveTo(cx, apexY)
        path.lineTo(cx - fw, topY)
        path.lineTo(cx, topY + fh)
        path.close()
        fill.color = lighten(roof, 1.08f)
        canvas.drawPath(path, fill)
        line.color = INK
        line.strokeWidth = inkW
        canvas.drawPath(path, line)

        path.reset()
        path.moveTo(cx, apexY)
        path.lineTo(cx, topY + fh)
        path.lineTo(cx + fw, topY)
        path.close()
        fill.color = darken(roof, 0.8f)
        canvas.drawPath(path, fill)
        line.color = INK
        line.strokeWidth = inkW
        canvas.drawPath(path, line)

        // chimney for level 2+
        if (tile.level >= 2) {
            val chX = cx + fw * 0.45f
            val chY = topY - roofH * 0.45f
            fill.color = darken(walls, 0.9f)
            rect.set(chX - 1.8f * u, chY - 6f * u, chX + 1.8f * u, chY + 2f * u)
            canvas.drawRect(rect, fill)
            line.color = INK
            line.strokeWidth = inkW
            canvas.drawRect(rect, line)
        }

        // balcony stripe on apartments
        if (tile.level >= 3) {
            faceQuad(
                canvas, leftAx, leftAy, leftBx, leftBy, cx, baseY + fh, leftDx, leftDy,
                0.10f, 0.90f, 0.60f, 0.66f, darken(roof, 0.9f), inkW
            )
        }
    }

    private fun drawShop(
        canvas: Canvas,
        tile: Tile,
        cx: Float,
        cy: Float,
        hw: Float,
        hh: Float,
        u: Float,
        big: Boolean = false
    ) {
        val inkW = max(1.1f, 1.5f * u)
        val hs = if (big) 1.8f else 1f
        val fw = hw * 0.76f
        val fh = fw * 0.5f
        val baseY = cy + hh * 0.30f
        val walls = SHOPS[tile.variant % SHOPS.size]
        val wallH = (13f + tile.level * 8f) * u * hs

        block(
            canvas, cx, baseY, fw, fh, wallH,
            top = lighten(walls, 1.10f),
            left = lighten(walls, 1.0f),
            right = darken(walls, 0.82f),
            inkW = inkW
        )
        val topY = baseY - wallH

        val leftAx = cx - fw
        val leftAy = topY
        val leftBx = cx
        val leftBy = topY + fh
        val leftDx = cx - fw
        val leftDy = baseY
        val rightAx = cx
        val rightAy = topY + fh
        val rightBx = cx + fw
        val rightBy = topY

        // awning / sign band
        faceQuad(
            canvas, leftAx, leftAy, leftBx, leftBy, cx, baseY + fh, leftDx, leftDy,
            0.04f, 0.96f, 0.08f, 0.20f, AMBER, inkW
        )
        faceQuad(
            canvas, rightAx, rightAy, rightBx, rightBy, cx + fw, baseY, cx, baseY + fh,
            0.04f, 0.96f, 0.08f, 0.20f, darken(AMBER, 0.9f), inkW
        )

        // shop windows
        val rows = tile.level
        for (i in 0 until rows) {
            val v0 = 0.28f + i * 0.22f
            faceQuad(
                canvas, leftAx, leftAy, leftBx, leftBy, cx, baseY + fh, leftDx, leftDy,
                0.12f, 0.42f, v0, v0 + 0.15f, WINDOW, inkW
            )
            faceQuad(
                canvas, leftAx, leftAy, leftBx, leftBy, cx, baseY + fh, leftDx, leftDy,
                0.54f, 0.86f, v0, v0 + 0.15f, WINDOW, inkW
            )
            faceQuad(
                canvas, rightAx, rightAy, rightBx, rightBy, cx + fw, baseY, cx, baseY + fh,
                0.14f, 0.44f, v0, v0 + 0.15f, WINDOW, inkW
            )
            faceQuad(
                canvas, rightAx, rightAy, rightBx, rightBy, cx + fw, baseY, cx, baseY + fh,
                0.56f, 0.86f, v0, v0 + 0.15f, WINDOW, inkW
            )
        }

        // door
        faceQuad(
            canvas, rightAx, rightAy, rightBx, rightBy, cx + fw, baseY, cx, baseY + fh,
            0.60f, 0.88f, 0.80f, 0.98f, darken(walls, 0.45f), inkW
        )

        // rooftop unit
        if (tile.level >= 2) {
            block(
                canvas, cx + fw * 0.1f, topY + fh * 0.15f, fw * 0.22f, fh * 0.22f, 5f * u,
                top = CONCRETE, left = CONCRETE_DARK, right = darken(CONCRETE_DARK, 0.8f),
                inkW = inkW
            )
        }
    }

    private fun drawFactory(
        canvas: Canvas,
        tile: Tile,
        cx: Float,
        cy: Float,
        hw: Float,
        hh: Float,
        u: Float,
        time: Float,
        big: Boolean = false
    ) {
        val inkW = max(1.1f, 1.5f * u)
        val hs = if (big) 1.9f else 1f
        val fw = hw * 0.78f
        val fh = fw * 0.5f
        val baseY = cy + hh * 0.28f
        val walls = FACTORIES[tile.variant % FACTORIES.size]
        val wallH = (11f + tile.level * 4f) * u * hs

        block(
            canvas, cx, baseY, fw, fh, wallH,
            top = lighten(walls, 1.08f),
            left = walls,
            right = darken(walls, 0.82f),
            inkW = inkW
        )
        val topY = baseY - wallH

        val rightAx = cx
        val rightAy = topY + fh
        val rightBx = cx + fw
        val rightBy = topY

        // roll-up door
        faceQuad(
            canvas, rightAx, rightAy, rightBx, rightBy, cx + fw, baseY, cx, baseY + fh,
            0.18f, 0.78f, 0.38f, 0.92f, darken(walls, 0.55f), inkW
        )
        // hazard stripe
        faceQuad(
            canvas, rightAx, rightAy, rightBx, rightBy, cx + fw, baseY, cx, baseY + fh,
            0.18f, 0.78f, 0.30f, 0.36f, AMBER, inkW
        )
        // roof corrugation
        line.color = darken(walls, 0.75f)
        line.strokeWidth = 1f * u
        for (i in 1..2) {
            val t = i / 3f
            path.reset()
            path.moveTo(cx - fw + fw * t, topY + fh * t)
            path.lineTo(cx + fw * t, topY + fh - fh * t)
            canvas.drawPath(path, line)
        }

        // chimney and smoke
        val chX = cx - fw * 0.42f
        val chY = topY - fh * 0.1f
        fill.color = CONCRETE_DARK
        rect.set(chX - 2.6f * u, chY - 14f * u, chX + 2.6f * u, chY + 2f * u)
        canvas.drawRect(rect, fill)
        line.color = INK
        line.strokeWidth = inkW
        canvas.drawRect(rect, line)
        fill.color = CORAL
        rect.set(chX - 2.6f * u, chY - 14f * u, chX + 2.6f * u, chY - 10.5f * u)
        canvas.drawRect(rect, fill)

        if (tile.level >= 2) {
            for (i in 0 until 3) {
                val phase = (time * 0.24f + i * 0.33f) % 1f
                val sx = chX + sin((phase + i) * 5f) * 2.5f * u
                val sy = chY - 16f * u - phase * 16f * u
                fill.color = SMOKE
                canvas.drawCircle(sx, sy, (2.2f + phase * 2.4f) * u, fill)
                line.color = 0x66202A3A
                line.strokeWidth = 1f * u
                canvas.drawCircle(sx, sy, (2.2f + phase * 2.4f) * u, line)
            }
        }
    }

    private fun drawPlant(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        hw: Float,
        hh: Float,
        u: Float,
        col: Int,
        row: Int,
        time: Float
    ) {
        val inkW = max(1.2f, 1.6f * u)
        // concrete pad
        diamondPath(cx, cy, hw * 0.88f, hh * 0.88f)
        fill.color = CONCRETE
        canvas.drawPath(path, fill)
        line.color = INK
        line.strokeWidth = inkW
        canvas.drawPath(path, line)

        // hazard band
        dashed.color = AMBER
        dashed.strokeWidth = 3.4f * u
        diamondPath(cx, cy, hw * 0.74f, hh * 0.74f)
        canvas.drawPath(path, dashed)

        // main hall
        block(
            canvas, cx - hw * 0.18f, cy + hh * 0.12f, hw * 0.44f, hw * 0.22f, 18f * u,
            top = 0xFFAEB7C2.toInt(),
            left = 0xFF8D99A8.toInt(),
            right = 0xFF6C7A8C.toInt(),
            inkW = inkW
        )

        // cooling tower
        val twX = cx + hw * 0.22f
        val twBase = cy + hh * 0.16f
        val towerH = 30f * u
        val towerTop = twBase - towerH
        val bw = 8.5f * u
        val tw = 6f * u

        // left half
        path.reset()
        path.moveTo(twX - bw, twBase)
        path.lineTo(twX - tw, towerTop)
        path.lineTo(twX, towerTop + 2.2f * u)
        path.lineTo(twX, twBase + 1.5f * u)
        path.close()
        fill.color = 0xFFC3CDD8.toInt()
        canvas.drawPath(path, fill)
        line.color = INK
        line.strokeWidth = inkW
        canvas.drawPath(path, line)

        // right half
        path.reset()
        path.moveTo(twX, twBase + 1.5f * u)
        path.lineTo(twX, towerTop + 2.2f * u)
        path.lineTo(twX + tw, towerTop)
        path.lineTo(twX + bw, twBase)
        path.close()
        fill.color = 0xFF98A6B6.toInt()
        canvas.drawPath(path, fill)
        line.color = INK
        line.strokeWidth = inkW
        canvas.drawPath(path, line)

        // rim + stripes
        rect.set(twX - tw, towerTop - 2.2f * u, twX + tw, towerTop + 2.2f * u)
        fill.color = 0xFFE4EBF2.toInt()
        canvas.drawOval(rect, fill)
        line.color = INK
        line.strokeWidth = inkW
        canvas.drawOval(rect, line)

        fill.color = AMBER
        rect.set(twX - bw * 0.92f, twBase - 5f * u, twX + bw * 0.92f, twBase - 2.5f * u)
        canvas.save()
        canvas.clipRect(twX - bw, twBase - 5f * u, twX + bw, twBase - 2.5f * u)
        canvas.drawRect(rect, fill)
        canvas.restore()

        // lightning sign on the hall
        val boltX = cx - hw * 0.30f
        val boltY = cy - 8f * u
        path.reset()
        path.moveTo(boltX, boltY - 13f * u)
        path.lineTo(boltX - 4.5f * u, boltY - 3f * u)
        path.lineTo(boltX - 0.5f * u, boltY - 3f * u)
        path.lineTo(boltX - 2f * u, boltY + 5f * u)
        path.lineTo(boltX + 5f * u, boltY - 6f * u)
        path.lineTo(boltX + 1f * u, boltY - 6f * u)
        path.close()
        fill.color = AMBER
        canvas.drawPath(path, fill)
        line.color = INK
        line.strokeWidth = inkW
        canvas.drawPath(path, line)

        // smoke puffs
        for (i in 0 until 3) {
            val phase = (time * 0.22f + i * 0.34f + col * 0.03f + row * 0.02f) % 1f
            val sx = twX + sin((phase + i) * 4.4f) * 3f * u
            val sy = towerTop - 5f * u - phase * 20f * u
            val r = (2.8f + phase * 3.4f) * u
            fill.color = SMOKE
            canvas.drawCircle(sx, sy, r, fill)
            line.color = 0x55202A3A
            line.strokeWidth = 1f * u
            canvas.drawCircle(sx, sy, r, line)
        }
    }

    private fun drawPark(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        hw: Float,
        hh: Float,
        u: Float,
        col: Int,
        row: Int,
        time: Float
    ) {
        diamondPath(cx, cy, hw * 0.82f, hh * 0.82f)
        fill.color = 0xFF67C46A.toInt()
        canvas.drawPath(path, fill)
        line.color = 0xFF2E6B45.toInt()
        line.strokeWidth = max(1f, 1.3f * u)
        canvas.drawPath(path, line)

        val variant = (col * 5 + row * 3) and 3
        if (variant == 0) {
            diamondPath(cx + hw * 0.1f, cy + hh * 0.12f, hw * 0.22f, hh * 0.22f)
            fill.color = WATER
            canvas.drawPath(path, fill)
            line.color = WATER_EDGE
            line.strokeWidth = 1.2f * u
            canvas.drawPath(path, line)
        } else {
            line.color = 0xFFFFF3D6.toInt()
            line.strokeWidth = 3.4f * u
            path.reset()
            path.moveTo(cx - hw * 0.3f, cy + hh * 0.3f)
            path.lineTo(cx + hw * 0.15f, cy - hh * 0.15f)
            path.lineTo(cx + hw * 0.3f, cy + hh * 0.1f)
            canvas.drawPath(path, line)
        }

        tree(canvas, cx - hw * 0.32f, cy + hh * 0.12f, 0.85f, u)
        tree(canvas, cx + hw * 0.30f, cy + hh * 0.20f, 0.7f, u)
        if (variant >= 2) tree(canvas, cx + hw * 0.02f, cy - hh * 0.2f, 0.65f, u)

        // flowers
        fill.color = CORAL
        canvas.drawCircle(cx + hw * 0.1f, cy + hh * 0.3f, 1.4f * u, fill)
        fill.color = AMBER
        canvas.drawCircle(cx + hw * 0.24f, cy + hh * 0.02f, 1.2f * u, fill)
        fill.color = PAPER
        canvas.drawCircle(cx - hw * 0.08f, cy - hh * 0.05f, 1.1f * u + sin(time + col) * 0.2f * u, fill)
    }

    // ------------------------------------------------------------------ transit

    private fun drawRail(
        canvas: Canvas,
        engine: CityEngine,
        col: Int,
        row: Int,
        cx: Float,
        cy: Float,
        hw: Float,
        hh: Float,
        u: Float
    ) {
        val inkW = max(1f, 1.3f * u)
        diamondPath(cx, cy, hw * 0.8f, hh * 0.8f)
        fill.color = 0xFF8E8577.toInt()
        canvas.drawPath(path, fill)
        line.color = 0xFF5A5247.toInt()
        line.strokeWidth = inkW
        canvas.drawPath(path, line)

        val east = engine.tile(col + 1, row)?.zone == ZoneType.RAIL
        val west = engine.tile(col - 1, row)?.zone == ZoneType.RAIL
        val south = engine.tile(col, row + 1)?.zone == ZoneType.RAIL
        val north = engine.tile(col, row - 1)?.zone == ZoneType.RAIL
        val any = east || west || south || north

        if (east || west) drawTrack(canvas, cx, cy, hw, hh, u, east && west)
        if (south || north) drawTrack(canvas, cx, cy, -hw, hh, u, south && north)
        if (!any) drawTrack(canvas, cx, cy, hw, hh, u, false)

        // signal post on busy junctions
        if ((east && south) || (west && north)) {
            fill.color = CORAL
            canvas.drawCircle(cx + hw * 0.16f, cy - hh * 0.12f, 1.8f * u, fill)
            line.color = INK
            line.strokeWidth = 1f * u
            canvas.drawCircle(cx + hw * 0.16f, cy - hh * 0.12f, 1.8f * u, line)
        }
    }

    /**
     * A buried conduit: water mains are solid blue, power cables dashed amber.
     * The artwork is dead grey while the network never reaches the tile.
     */
    private fun drawConduit(
        canvas: Canvas,
        engine: CityEngine,
        col: Int,
        row: Int,
        cx: Float,
        cy: Float,
        hw: Float,
        hh: Float,
        u: Float,
        tile: Tile,
        power: Boolean,
        highlight: Boolean = false
    ) {
        val east = conduitHere(engine, col + 1, row, power)
        val west = conduitHere(engine, col - 1, row, power)
        val south = conduitHere(engine, col, row + 1, power)
        val north = conduitHere(engine, col, row - 1, power)
        val any = east || west || south || north
        val live = if (power) tile.powered else tile.watered
        val core = when {
            power && live -> AMBER
            power -> 0xFF7C7668.toInt()
            live -> WATER
            else -> 0xFF6F7F8E.toInt()
        }

        fun trace() {
            path.reset()
            if (east) {
                path.moveTo(cx, cy)
                path.lineTo(cx + hw * 0.52f, cy + hh * 0.52f)
            }
            if (west) {
                path.moveTo(cx, cy)
                path.lineTo(cx - hw * 0.52f, cy - hh * 0.52f)
            }
            if (south) {
                path.moveTo(cx, cy)
                path.lineTo(cx - hw * 0.52f, cy + hh * 0.52f)
            }
            if (north) {
                path.moveTo(cx, cy)
                path.lineTo(cx + hw * 0.52f, cy - hh * 0.52f)
            }
            if (!any) {
                path.moveTo(cx - hw * 0.26f, cy + hh * 0.26f)
                path.lineTo(cx + hw * 0.26f, cy - hh * 0.26f)
            }
        }

        if (highlight) {
            trace()
            line.color = PAPER
            line.strokeWidth = max(1.6f, 7.4f * u)
            canvas.drawPath(path, line)
        }

        for (pass in 0..1) {
            val paint = if (pass == 1 && power) dashed else line
            paint.color = if (pass == 0) INK else core
            paint.strokeWidth = if (pass == 0) {
                max(1f, (if (power) 4f else 5f) * u)
            } else {
                max(0.8f, (if (power) 2.2f else 2.8f) * u)
            }
            trace()
            canvas.drawPath(path, paint)
        }

        // coupling joint (or cable marker) at the centre of the tile
        val joint = max(1.2f, (if (power) 2.4f else 3.2f) * u)
        fill.color = when {
            power && live -> AMBER
            power -> 0xFF8A8578.toInt()
            live -> WATER_LIGHT
            else -> 0xFF8A99A6.toInt()
        }
        canvas.drawCircle(cx, cy, joint, fill)
        line.color = INK
        line.strokeWidth = max(0.9f, 1.2f * u)
        canvas.drawCircle(cx, cy, joint, line)
    }

    /** Lifts one buried layer above the buildings while its tool is selected. */
    private fun drawConduitOverlay(
        canvas: Canvas,
        engine: CityEngine,
        u: Float,
        power: Boolean
    ) {
        val hw = camera.tileW * 0.5f * camera.zoom
        val hh = camera.tileH * 0.5f * camera.zoom
        val margin = hw * 2f
        for (r in 0 until engine.rows) {
            for (c in 0 until engine.cols) {
                val tile = engine.tile(c, r) ?: continue
                val laid = if (power) tile.powerline else tile.pipe
                if (!laid) continue
                val cx = camera.screenX(c.toFloat(), r.toFloat())
                val cy = camera.screenY(c.toFloat(), r.toFloat())
                if (cx < -margin || cx > viewW + margin) continue
                if (cy < -margin || cy > viewH + margin) continue
                drawConduit(canvas, engine, c, r, cx, cy, hw, hh, u, tile, power, highlight = true)
            }
        }
    }

    private fun conduitHere(engine: CityEngine, col: Int, row: Int, power: Boolean): Boolean {
        val tile = engine.tile(col, row) ?: return false
        return if (power) {
            tile.powerline || tile.zone == ZoneType.PLANT
        } else {
            tile.pipe || tile.zone == ZoneType.WATERWORKS
        }
    }

    /** A set of sleepers and two steel rails running along one iso axis. */
    private fun drawTrack(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        ax: Float,
        ay: Float,
        u: Float,
        full: Boolean
    ) {
        val len = max(1f, kotlin.math.sqrt(ax * ax + ay * ay))
        val ux = ax / len
        val uy = ay / len
        val reach = len * (if (full) 0.86f else 0.52f)
        val px = -uy
        val py = ux

        // sleepers first, rails on top
        line.color = 0xFF6B5334.toInt()
        line.strokeWidth = 1.2f * u
        path.reset()
        var t = -reach
        while (t <= reach) {
            val sx = cx + ux * t
            val sy = cy + uy * t
            path.moveTo(sx - px * 4.4f * u, sy - py * 4.4f * u)
            path.lineTo(sx + px * 4.4f * u, sy + py * 4.4f * u)
            t += 6.5f * u
        }
        canvas.drawPath(path, line)

        line.color = 0xFF3A3F4C.toInt()
        line.strokeWidth = 1.3f * u
        for (side in -1..1 step 2) {
            val ox = px * 2.2f * u * side
            val oy = py * 2.2f * u * side
            path.reset()
            path.moveTo(cx - ux * reach + ox, cy - uy * reach + oy)
            path.lineTo(cx + ux * reach + ox, cy + uy * reach + oy)
            canvas.drawPath(path, line)
        }
    }

    // ------------------------------------------------------------------ services

    /** A little clapboard station with a canopy, sitting on a concrete platform. */
    private fun drawTrainStation(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        hw: Float,
        hh: Float,
        u: Float
    ) {
        val inkW = max(1.1f, 1.5f * u)
        diamondPath(cx, cy, hw * 0.86f, hh * 0.86f)
        fill.color = CONCRETE
        canvas.drawPath(path, fill)
        line.color = INK
        line.strokeWidth = inkW
        canvas.drawPath(path, line)

        // platform edge beside the track
        diamondPath(cx, cy + hh * 0.14f, hw * 0.62f, hh * 0.62f)
        fill.color = CONCRETE_DARK
        canvas.drawPath(path, fill)
        line.color = INK
        line.strokeWidth = max(0.8f, 1f * u)
        canvas.drawPath(path, line)

        val bw = hw * 0.42f
        val bh = 15f * u
        val baseY = cy + hh * 0.18f
        rect.set(cx - bw, baseY - bh, cx + bw, baseY)
        fill.color = PAPER
        canvas.drawRect(rect, fill)
        line.color = INK
        line.strokeWidth = inkW
        canvas.drawRect(rect, line)

        // glazed front
        rect.set(cx - bw * 0.64f, baseY - bh * 0.8f, cx + bw * 0.64f, baseY - bh * 0.3f)
        fill.color = WINDOW
        canvas.drawRect(rect, fill)
        line.color = INK
        line.strokeWidth = max(0.8f, 1f * u)
        canvas.drawRect(rect, line)

        // canopy roof
        path.reset()
        path.moveTo(cx - bw * 1.42f, baseY - bh)
        path.lineTo(cx, baseY - bh - 8f * u)
        path.lineTo(cx + bw * 1.42f, baseY - bh)
        path.lineTo(cx, baseY - bh + 5f * u)
        path.close()
        fill.color = 0xFF4E7FA0.toInt()
        canvas.drawPath(path, fill)
        line.color = INK
        line.strokeWidth = inkW
        canvas.drawPath(path, line)

        // station clock
        fill.color = PAPER
        canvas.drawCircle(cx, baseY - bh * 1.16f, 2.6f * u, fill)
        line.color = INK
        line.strokeWidth = max(0.8f, 1f * u)
        canvas.drawCircle(cx, baseY - bh * 1.16f, 2.6f * u, line)
        path.reset()
        path.moveTo(cx, baseY - bh * 1.16f)
        path.lineTo(cx, baseY - bh * 1.16f - 1.6f * u)
        path.moveTo(cx, baseY - bh * 1.16f)
        path.lineTo(cx + 1.2f * u, baseY - bh * 1.16f)
        canvas.drawPath(path, line)

        // lamp post on the platform
        line.color = INK
        line.strokeWidth = max(0.8f, 1.1f * u)
        path.reset()
        path.moveTo(cx + hw * 0.42f, cy + hh * 0.3f)
        path.lineTo(cx + hw * 0.42f, cy + hh * 0.3f - 13f * u)
        canvas.drawPath(path, line)
        fill.color = AMBER
        canvas.drawCircle(cx + hw * 0.42f, cy + hh * 0.3f - 14f * u, 2f * u, fill)
        line.color = INK
        canvas.drawCircle(cx + hw * 0.42f, cy + hh * 0.3f - 14f * u, 2f * u, line)
    }

    private fun drawWaterWorks(canvas: Canvas, cx: Float, cy: Float, hw: Float, hh: Float, u: Float) {
        val inkW = max(1.1f, 1.5f * u)
        diamondPath(cx, cy, hw * 0.84f, hh * 0.84f)
        fill.color = CONCRETE
        canvas.drawPath(path, fill)
        line.color = INK
        line.strokeWidth = inkW
        canvas.drawPath(path, line)

        val legTop = cy - 22f * u
        line.color = 0xFF5D6B7A.toInt()
        line.strokeWidth = 2.2f * u
        path.reset()
        path.moveTo(cx - hw * 0.40f, cy + hh * 0.22f)
        path.lineTo(cx - hw * 0.22f, legTop)
        path.moveTo(cx + hw * 0.40f, cy + hh * 0.22f)
        path.lineTo(cx + hw * 0.22f, legTop)
        path.moveTo(cx, cy + hh * 0.42f)
        path.lineTo(cx, legTop)
        path.moveTo(cx - hw * 0.32f, cy - hh * 0.05f)
        path.lineTo(cx + hw * 0.32f, cy + hh * 0.30f)
        canvas.drawPath(path, line)

        // tank
        val tankW = hw * 0.56f
        val tankH = 15f * u
        val tankTop = legTop - tankH
        rect.set(cx - tankW, tankTop, cx + tankW, legTop)
        fill.color = Config.SKY_BLUE
        canvas.drawRoundRect(rect, 4f * u, 4f * u, fill)
        line.color = INK
        line.strokeWidth = inkW
        canvas.drawRoundRect(rect, 4f * u, 4f * u, line)

        rect.set(cx - tankW, tankTop - 4.2f * u, cx + tankW, tankTop + 4.2f * u)
        fill.color = lighten(Config.SKY_BLUE, 1.14f)
        canvas.drawOval(rect, fill)
        line.color = INK
        line.strokeWidth = inkW
        canvas.drawOval(rect, line)

        rect.set(cx - tankW * 0.78f, legTop - 5.5f * u, cx + tankW * 0.78f, legTop - 3.4f * u)
        fill.color = WATER
        canvas.drawRect(rect, fill)

        // ladder
        line.color = INK
        line.strokeWidth = 1f * u
        path.reset()
        path.moveTo(cx + tankW * 0.55f, legTop)
        path.lineTo(cx + tankW * 0.55f, tankTop)
        canvas.drawPath(path, line)
    }

    private fun drawPolice(canvas: Canvas, cx: Float, cy: Float, hw: Float, hh: Float, u: Float) {
        val inkW = max(1.1f, 1.5f * u)
        diamondPath(cx, cy, hw * 0.84f, hh * 0.84f)
        fill.color = CONCRETE
        canvas.drawPath(path, fill)
        line.color = INK
        line.strokeWidth = inkW
        canvas.drawPath(path, line)

        val fw = hw * 0.66f
        val fh = fw * 0.5f
        val baseY = cy + hh * 0.26f
        val wallH = 21f * u
        block(
            canvas, cx, baseY, fw, fh, wallH,
            top = 0xFFC4D3E6.toInt(),
            left = 0xFF4E6C99.toInt(),
            right = 0xFF3A5478.toInt(),
            inkW = inkW
        )
        val topY = baseY - wallH

        faceQuad(
            canvas, cx - fw, topY, cx, topY + fh, cx, baseY + fh, cx - fw, baseY,
            0.14f, 0.86f, 0.30f, 0.52f, WINDOW, inkW
        )
        faceQuad(
            canvas, cx, topY + fh, cx + fw, topY, cx + fw, baseY, cx, baseY + fh,
            0.14f, 0.86f, 0.30f, 0.52f, WINDOW, inkW
        )
        faceQuad(
            canvas, cx, topY + fh, cx + fw, topY, cx + fw, baseY, cx, baseY + fh,
            0.56f, 0.86f, 0.56f, 0.94f, darken(0xFF3A5478.toInt(), 0.6f), inkW
        )

        // star badge on the roof
        fill.color = AMBER
        canvas.drawCircle(cx, topY, 5.2f * u, fill)
        line.color = INK
        line.strokeWidth = inkW
        canvas.drawCircle(cx, topY, 5.2f * u, line)
        star(canvas, cx, topY, 3.4f * u, PAPER)

        // roof siren
        path.reset()
        path.moveTo(cx - 2.6f * u, topY - 5.2f * u)
        path.lineTo(cx + 2.6f * u, topY - 5.2f * u)
        path.lineTo(cx + 1.4f * u, topY - 9f * u)
        path.lineTo(cx - 1.4f * u, topY - 9f * u)
        path.close()
        fill.color = CORAL
        canvas.drawPath(path, fill)
        line.color = INK
        line.strokeWidth = inkW
        canvas.drawPath(path, line)
    }

    private fun drawFireStation(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        hw: Float,
        hh: Float,
        u: Float,
        time: Float
    ) {
        val inkW = max(1.1f, 1.5f * u)
        diamondPath(cx, cy, hw * 0.84f, hh * 0.84f)
        fill.color = CONCRETE
        canvas.drawPath(path, fill)
        line.color = INK
        line.strokeWidth = inkW
        canvas.drawPath(path, line)

        val fw = hw * 0.64f
        val fh = fw * 0.5f
        val baseY = cy + hh * 0.26f
        val wallH = 19f * u
        block(
            canvas, cx, baseY, fw, fh, wallH,
            top = 0xFFD8705F.toInt(),
            left = 0xFFCE5B4C.toInt(),
            right = 0xFFA5443A.toInt(),
            inkW = inkW
        )
        val topY = baseY - wallH

        // bay doors
        faceQuad(
            canvas, cx - fw, topY, cx, topY + fh, cx, baseY + fh, cx - fw, baseY,
            0.10f, 0.46f, 0.46f, 0.96f, darken(0xFFA5443A.toInt(), 0.55f), inkW
        )
        faceQuad(
            canvas, cx - fw, topY, cx, topY + fh, cx, baseY + fh, cx - fw, baseY,
            0.54f, 0.90f, 0.46f, 0.96f, darken(0xFFA5443A.toInt(), 0.55f), inkW
        )

        // hose-drying tower
        block(
            canvas, cx + fw * 0.72f, topY + fh * 0.15f, fw * 0.30f, fh * 0.30f, 16f * u,
            top = PAPER, left = 0xFFF1E3C8.toInt(), right = 0xFFD9C7A6.toInt(),
            inkW = inkW
        )

        // flame emblem
        val fx = cx
        val fy = topY - fh * 0.15f
        path.reset()
        path.moveTo(fx, fy - 6f * u)
        path.quadTo(fx + 4.6f * u, fy - 0.5f * u, fx + 1.6f * u, fy + 3f * u)
        path.quadTo(fx, fy + 1.4f * u, fx - 1.6f * u, fy + 3f * u)
        path.quadTo(fx - 4.6f * u, fy - 0.5f * u, fx, fy - 6f * u)
        path.close()
        fill.color = AMBER
        canvas.drawPath(path, fill)
        line.color = INK
        line.strokeWidth = 1.1f * u
        canvas.drawPath(path, line)

        // blinking roof light
        val blink = 0.55f + 0.45f * sin(time * 2.4f)
        fill.color = Color.argb((blink * 255).toInt(), 255, 220, 120)
        canvas.drawCircle(cx - fw * 0.15f, topY - 2f * u, 1.8f * u, fill)
    }

    private fun drawHospital(canvas: Canvas, cx: Float, cy: Float, hw: Float, hh: Float, u: Float) {
        val inkW = max(1.1f, 1.5f * u)
        diamondPath(cx, cy, hw * 0.84f, hh * 0.84f)
        fill.color = CONCRETE
        canvas.drawPath(path, fill)
        line.color = INK
        line.strokeWidth = inkW
        canvas.drawPath(path, line)

        val fw = hw * 0.66f
        val fh = fw * 0.5f
        val baseY = cy + hh * 0.28f
        val wallH = 26f * u
        block(
            canvas, cx, baseY, fw, fh, wallH,
            top = 0xFFFFFFFF.toInt(),
            left = 0xFFF6F2E7.toInt(),
            right = 0xFFDCD6C8.toInt(),
            inkW = inkW
        )
        val topY = baseY - wallH

        // glazed wing
        block(
            canvas, cx - fw * 0.82f, topY + fh * 0.50f, fw * 0.36f, fh * 0.36f, 12f * u,
            top = 0xFFCFE9F5.toInt(), left = WINDOW, right = 0xFF8FC5DD.toInt(), inkW = inkW
        )

        faceQuad(
            canvas, cx - fw, topY, cx, topY + fh, cx, baseY + fh, cx - fw, baseY,
            0.16f, 0.84f, 0.36f, 0.56f, WINDOW, inkW
        )
        faceQuad(
            canvas, cx, topY + fh, cx + fw, topY, cx + fw, baseY, cx, baseY + fh,
            0.16f, 0.84f, 0.36f, 0.56f, WINDOW, inkW
        )
        faceQuad(
            canvas, cx, topY + fh, cx + fw, topY, cx + fw, baseY, cx, baseY + fh,
            0.40f, 0.62f, 0.70f, 0.96f, 0xFF9AB6C4.toInt(), inkW
        )

        // red cross on the roof
        val crossY = topY - fh * 0.1f
        fill.color = CORAL
        rect.set(cx - 1.9f * u, crossY - 6.2f * u, cx + 1.9f * u, crossY + 6.2f * u)
        canvas.drawRect(rect, fill)
        rect.set(cx - 6.2f * u, crossY - 1.9f * u, cx + 6.2f * u, crossY + 1.9f * u)
        canvas.drawRect(rect, fill)
        line.color = INK
        line.strokeWidth = 1f * u
        rect.set(cx - 1.9f * u, crossY - 6.2f * u, cx + 1.9f * u, crossY + 6.2f * u)
        canvas.drawRect(rect, line)
    }

    // ------------------------------------------------------------------ airport

    private fun drawAirport(
        canvas: Canvas,
        tile: Tile,
        cx: Float,
        cy: Float,
        hw: Float,
        hh: Float,
        u: Float,
        time: Float
    ) {
        val inkW = max(1.2f, 1.6f * u)
        diamondPath(cx, cy, hw * 0.97f, hh * 0.97f)
        fill.color = 0xFF596075.toInt()
        canvas.drawPath(path, fill)
        line.color = ROAD_EDGE
        line.strokeWidth = 1.8f * u
        canvas.drawPath(path, line)

        // runway strip, kept inside the diamond footprint
        line.color = 0xFF3D4256.toInt()
        line.strokeWidth = 12f * u
        path.reset()
        path.moveTo(cx - hw * 0.46f, cy - hh * 0.46f)
        path.lineTo(cx + hw * 0.46f, cy + hh * 0.46f)
        canvas.drawPath(path, line)

        dashed.color = PAPER
        dashed.strokeWidth = 1.6f * u
        path.reset()
        path.moveTo(cx - hw * 0.42f, cy - hh * 0.42f)
        path.lineTo(cx + hw * 0.42f, cy + hh * 0.42f)
        canvas.drawPath(path, dashed)

        // terminal building
        val fw = hw * 0.34f
        val fh = fw * 0.5f
        val baseY = cy + hh * 0.06f
        block(
            canvas, cx - hw * 0.28f, baseY, fw, fh, 22f * u,
            top = 0xFFEDE7D8.toInt(),
            left = 0xFFE0D8C6.toInt(),
            right = 0xFFBFB6A1.toInt(),
            inkW = inkW
        )
        val topY = baseY - 22f * u
        faceQuad(
            canvas, cx - hw * 0.28f - fw, topY, cx - hw * 0.28f, topY + fh,
            cx - hw * 0.28f, baseY + fh, cx - hw * 0.28f - fw, baseY,
            0.12f, 0.88f, 0.20f, 0.42f, WINDOW, inkW
        )
        faceQuad(
            canvas, cx - hw * 0.28f, topY + fh, cx - hw * 0.28f + fw, topY,
            cx - hw * 0.28f + fw, baseY, cx - hw * 0.28f, baseY + fh,
            0.12f, 0.88f, 0.20f, 0.42f, WINDOW, inkW
        )

        // control tower
        val tx = cx + hw * 0.30f
        val tBase = cy + hh * 0.02f
        block(
            canvas, tx, tBase, hw * 0.055f, hw * 0.028f, 32f * u,
            top = CONCRETE, left = CONCRETE_DARK, right = darken(CONCRETE_DARK, 0.78f),
            inkW = inkW
        )
        rect.set(tx - hw * 0.11f, tBase - 38f * u, tx + hw * 0.11f, tBase - 31f * u)
        fill.color = WINDOW
        canvas.drawRoundRect(rect, 2f * u, 2f * u, fill)
        line.color = INK
        line.strokeWidth = inkW
        canvas.drawRoundRect(rect, 2f * u, 2f * u, line)

        // a plane waiting on the apron
        drawPlane(canvas, cx + hw * 0.10f, cy + hh * 0.28f, u, time)

        // rotating beacon
        if ((time * 1.4f).toInt() % 2 == 0) {
            fill.color = AMBER
            canvas.drawCircle(tx, tBase - 40f * u, 1.8f * u, fill)
        }
    }

    private fun drawPlane(canvas: Canvas, x: Float, y: Float, u: Float, time: Float) {
        val inkW = max(0.8f, 1.1f * u)
        val bob = sin(time * 0.8f) * 0.5f * u
        // shadow
        fill.color = 0x33000000
        canvas.drawOval(x - 10f * u, y + 1f * u, x + 10f * u, y + 4.5f * u, fill)
        // wings
        fill.color = 0xFFE9EDF4.toInt()
        path.reset()
        path.moveTo(x - 2f * u, y - 6f * u + bob)
        path.lineTo(x + 3f * u, y - 6f * u + bob)
        path.lineTo(x + 1f * u, y + 1f * u + bob)
        path.lineTo(x - 3f * u, y + 1f * u + bob)
        path.close()
        canvas.drawPath(path, fill)
        line.color = INK
        line.strokeWidth = inkW
        canvas.drawPath(path, line)
        // fuselage
        rect.set(x - 9.5f * u, y - 3f * u + bob, x + 9.5f * u, y + 2f * u + bob)
        fill.color = PAPER
        canvas.drawRoundRect(rect, 2.4f * u, 2.4f * u, fill)
        line.color = INK
        line.strokeWidth = inkW
        canvas.drawRoundRect(rect, 2.4f * u, 2.4f * u, line)
        // tail
        path.reset()
        path.moveTo(x + 6.5f * u, y - 3f * u + bob)
        path.lineTo(x + 9.5f * u, y - 7f * u + bob)
        path.lineTo(x + 9.5f * u, y - 2f * u + bob)
        path.close()
        fill.color = CORAL
        canvas.drawPath(path, fill)
        line.color = INK
        line.strokeWidth = inkW
        canvas.drawPath(path, line)
        // windows
        fill.color = WINDOW
        for (i in 0 until 3) {
            canvas.drawCircle(x - 5f * u + i * 3f * u, y - 0.6f * u + bob, 0.8f * u, fill)
        }
    }

    // ------------------------------------------------------------------ 2x2 blocks

    private fun drawBlockBase(
        canvas: Canvas,
        tile: Tile,
        cx: Float,
        cy: Float,
        hw: Float,
        hh: Float,
        u: Float
    ) {
        val pad = when (tile.zone) {
            ZoneType.RESIDENTIAL -> ZONE_R
            ZoneType.COMMERCIAL -> ZONE_C
            else -> ZONE_I
        }
        diamondPath(cx, cy, hw * 0.96f, hh * 0.96f)
        fill.color = darken(pad, 0.92f)
        canvas.drawPath(path, fill)
        line.color = INK
        line.strokeWidth = max(1.1f, 1.4f * u)
        canvas.drawPath(path, line)
        dashed.color = withAlpha(darken(pad, 1.0f), 150)
        dashed.strokeWidth = 1.2f * u
        diamondPath(cx, cy, hw * 0.72f, hh * 0.72f)
        canvas.drawPath(path, dashed)
    }

    private fun drawBlockBuilding(
        canvas: Canvas,
        tile: Tile,
        cx: Float,
        cy: Float,
        hw: Float,
        hh: Float,
        u: Float,
        time: Float
    ) {
        val w = hw * 1.14f
        when (tile.zone) {
            ZoneType.RESIDENTIAL -> drawHouse(canvas, tile, cx, cy, w, w * 0.5f, u, true)
            ZoneType.COMMERCIAL -> drawShop(canvas, tile, cx, cy, w, w * 0.5f, u, true)
            ZoneType.INDUSTRIAL -> drawFactory(canvas, tile, cx, cy, w, w * 0.5f, u, time, true)
            else -> Unit
        }
        val hhS = w * 0.5f
        val roofY = when (tile.zone) {
            ZoneType.RESIDENTIAL -> cy + hhS * 0.18f -
                (12f + tile.level * 4.5f) * u * 2.0f -
                (10f + tile.level * 2.2f) * u * 2.0f
            ZoneType.COMMERCIAL -> cy + hhS * 0.30f -
                (13f + tile.level * 8f) * u * 1.8f
            else -> cy + hhS * 0.28f - (11f + tile.level * 4f) * u * 1.9f
        }
        when (tile.zone) {
            ZoneType.RESIDENTIAL -> {
                line.color = INK
                line.strokeWidth = 1.4f * u
                path.reset()
                path.moveTo(cx, roofY)
                path.lineTo(cx, roofY - 14f * u)
                canvas.drawPath(path, line)
                fill.color = CORAL
                canvas.drawCircle(cx, roofY - 14f * u, 1.6f * u, fill)
            }
            ZoneType.COMMERCIAL -> {
                rect.set(cx - 13f * u, roofY - 11f * u, cx + 13f * u, roofY - 3f * u)
                fill.color = 0xFF6FE0D0.toInt()
                canvas.drawRoundRect(rect, 2f * u, 2f * u, fill)
                line.color = INK
                line.strokeWidth = 1.3f * u
                canvas.drawRoundRect(rect, 2f * u, 2f * u, line)
            }
            else -> Unit
        }
    }

    private fun star(canvas: Canvas, x: Float, y: Float, r: Float, color: Int) {
        path.reset()
        for (i in 0 until 10) {
            val angle = (-90f + i * 36f) * PI.toFloat() / 180f
            val radius = if (i % 2 == 0) r else r * 0.44f
            val px = x + cos(angle) * radius
            val py = y + sin(angle) * radius
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()
        fill.color = color
        canvas.drawPath(path, fill)
    }

    // ------------------------------------------------------------------ extras

    private enum class Badge { ROAD, POWER, WATER }

    private fun drawBadge(canvas: Canvas, x: Float, y: Float, u: Float, badge: Badge) {
        val r = 5.4f * u
        fill.color = when (badge) {
            Badge.ROAD -> CORAL
            Badge.POWER -> AMBER
            Badge.WATER -> 0xFF4FA3D8.toInt()
        }
        canvas.drawCircle(x, y, r, fill)
        line.color = INK
        line.strokeWidth = max(1f, 1.3f * u)
        canvas.drawCircle(x, y, r, line)
        if (badge == Badge.WATER) {
            path.reset()
            path.moveTo(x, y - 3.4f * u)
            path.quadTo(x + 2.8f * u, y + 0.7f * u, x, y + 3.1f * u)
            path.quadTo(x - 2.8f * u, y + 0.7f * u, x, y - 3.4f * u)
            path.close()
            fill.color = PAPER
            canvas.drawPath(path, fill)
            line.color = INK
            line.strokeWidth = 1f * u
            canvas.drawPath(path, line)
        } else if (badge == Badge.ROAD) {
            line.color = PAPER
            line.strokeWidth = 1.8f * u
            path.reset()
            path.moveTo(x, y - 2.8f * u)
            path.lineTo(x, y + 0.7f * u)
            path.moveTo(x, y + 2.5f * u)
            path.lineTo(x, y + 2.6f * u)
            canvas.drawPath(path, line)
        } else {
            path.reset()
            path.moveTo(x + 0.6f * u, y - 3.2f * u)
            path.lineTo(x - 1.8f * u, y + 0.4f * u)
            path.lineTo(x - 0.2f * u, y + 0.4f * u)
            path.lineTo(x - 0.8f * u, y + 3.3f * u)
            path.lineTo(x + 2.2f * u, y - 0.9f * u)
            path.lineTo(x + 0.4f * u, y - 0.9f * u)
            path.close()
            fill.color = INK
            canvas.drawPath(path, fill)
        }
    }

    private fun drawCars(canvas: Canvas, u: Float) {
        for (car in cars) {
            val fromX = camera.screenX(car.col.toFloat(), car.row.toFloat())
            val fromY = camera.screenY(car.col.toFloat(), car.row.toFloat())
            val toX = camera.screenX(car.nextCol.toFloat(), car.nextRow.toFloat())
            val toY = camera.screenY(car.nextCol.toFloat(), car.nextRow.toFloat())
            val x = fromX + (toX - fromX) * car.t
            val y = fromY + (toY - fromY) * car.t
            val dx = toX - fromX
            val dy = toY - fromY
            val len = max(1f, kotlin.math.sqrt(dx * dx + dy * dy))
            val offX = -dy / len * 2.6f * u
            val offY = dx / len * 2.6f * u
            val cx = x + offX
            val cy = y + offY

            fill.color = 0x44000000
            canvas.drawOval(cx - 4.5f * u, cy - 1.5f * u, cx + 4.5f * u, cy + 2.5f * u, fill)
            rect.set(cx - 4f * u, cy - 7.4f * u, cx + 4f * u, cy - 1f * u)
            fill.color = car.color
            canvas.drawRoundRect(rect, 2f * u, 2f * u, fill)
            line.color = INK
            line.strokeWidth = max(0.8f, 1.1f * u)
            canvas.drawRoundRect(rect, 2f * u, 2f * u, line)
            rect.set(cx - 2.6f * u, cy - 6.4f * u, cx + 1.4f * u, cy - 4.4f * u)
            fill.color = WINDOW
            canvas.drawRoundRect(rect, 1f * u, 1f * u, fill)
        }
    }

    private fun drawTrains(canvas: Canvas, u: Float) {
        for (train in trains) {
            val fromX = camera.screenX(train.col.toFloat(), train.row.toFloat())
            val fromY = camera.screenY(train.col.toFloat(), train.row.toFloat())
            val toX = camera.screenX(train.nextCol.toFloat(), train.nextRow.toFloat())
            val toY = camera.screenY(train.nextCol.toFloat(), train.nextRow.toFloat())
            val x = fromX + (toX - fromX) * train.t
            val y = fromY + (toY - fromY) * train.t
            val dx = toX - fromX
            val dy = toY - fromY
            val angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()

            canvas.save()
            canvas.translate(x, y)
            canvas.rotate(angle)

            fill.color = 0x33000000
            rect.set(-13f * u, 1f * u, 13f * u, 4.5f * u)
            canvas.drawRoundRect(rect, 2f * u, 2f * u, fill)

            rect.set(-13f * u, -5.8f * u, 13f * u, 1.5f * u)
            fill.color = train.color
            canvas.drawRoundRect(rect, 3f * u, 3f * u, fill)
            line.color = INK
            line.strokeWidth = max(0.8f, 1.1f * u)
            canvas.drawRoundRect(rect, 3f * u, 3f * u, line)

            rect.set(-13f * u, -2.4f * u, 13f * u, -0.9f * u)
            fill.color = AMBER
            canvas.drawRect(rect, fill)

            fill.color = WINDOW
            for (i in 0 until 4) {
                rect.set(
                    -9.4f * u + i * 5f * u, -5f * u,
                    -6.6f * u + i * 5f * u, -3.1f * u
                )
                canvas.drawRoundRect(rect, 0.8f * u, 0.8f * u, fill)
            }
            fill.color = PAPER
            canvas.drawCircle(11.6f * u, -1.6f * u, 1.1f * u, fill)

            canvas.restore()
        }
    }

    private fun drawHover(
        canvas: Canvas,
        engine: CityEngine,
        tool: Tool,
        col: Int,
        row: Int,
        valid: Boolean,
        u: Float
    ) {
        if (tool == Tool.PAN || !engine.inBounds(col, row)) return
        val blockHover = tool == Tool.AIRPORT
        val cx = if (blockHover) {
            camera.screenX(col + 0.5f, row + 0.5f)
        } else {
            camera.screenX(col.toFloat(), row.toFloat())
        }
        val cy = if (blockHover) {
            camera.screenY(col + 0.5f, row + 0.5f)
        } else {
            camera.screenY(col.toFloat(), row.toFloat())
        }
        val hw = camera.tileW * 0.5f * camera.zoom
        val hh = camera.tileH * 0.5f * camera.zoom

        val accent = when {
            tool == Tool.BULLDOZE -> CORAL
            valid -> AMBER
            else -> CORAL
        }
        val hoverScale = if (blockHover) 1.94f else 0.94f
        diamondPath(cx, cy, hw * hoverScale, hh * hoverScale)
        fill.color = withAlpha(accent, if (valid) 70 else 90)
        canvas.drawPath(path, fill)
        line.color = INK
        line.strokeWidth = 5f * u
        canvas.drawPath(path, line)
        line.color = if (valid) PAPER else CORAL
        line.strokeWidth = 2.2f * u
        canvas.drawPath(path, line)
    }

    private fun lighten(color: Int, factor: Float): Int {
        val r = (Color.red(color) * factor).roundToInt().coerceIn(0, 255)
        val g = (Color.green(color) * factor).roundToInt().coerceIn(0, 255)
        val b = (Color.blue(color) * factor).roundToInt().coerceIn(0, 255)
        return Color.argb(Color.alpha(color), r, g, b)
    }

    private fun darken(color: Int, factor: Float): Int = lighten(color, factor)

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
}
