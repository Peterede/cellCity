package com.cellcity.game

import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

class GameView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density
    private val prefs = context.getSharedPreferences("cellcity", Context.MODE_PRIVATE)
    private var engine: CityEngine = loadCity()
    private val renderer = CityRenderer(density)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var selected = Tool.ROAD
    private var speed = 1
    private var dayTimer = 0f
    private var time = 0f
    private var lastFrame = 0L
    private var running = false
    private var lastHaptic = 0L

    private var bannerText: String = ""
    private var bannerColor: Int = Config.INK
    private var bannerTimer = 0f

    private var hudPanelBottom = 0f
    private var toolbarTop = 0f
    private var mapCenterY = 0f
    private val toolRects = Array(Tool.values().size) { RectF() }
    private val speedRect = RectF()
    private val newRect = RectF()

    private var hoverCol = Config.COLS / 2
    private var hoverRow = Config.ROWS / 2

    // gesture state
    private var mode = MODE_NONE
    private var activePointer = -1
    private var downX = 0f
    private var downY = 0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var lastPaintCol = -1
    private var lastPaintRow = -1
    private var didPaint = false
    private var pinchStartDistance = 1f
    private var pinchStartZoom = 1f
    private var lastMidX = 0f
    private var lastMidY = 0f

    // ------------------------------------------------------------------ paints

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(5f * density, 4f * density), 0f)
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Config.INK
        textAlign = Paint.Align.LEFT
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    }
    private val textBold = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Config.INK
        textAlign = Paint.Align.LEFT
        isFakeBoldText = true
    }
    private val textCenter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Config.INK
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val textRight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Config.INK
        textAlign = Paint.Align.RIGHT
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Config.INK
        textAlign = Paint.Align.LEFT
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        letterSpacing = 0.06f
    }
    private val path = Path()
    private val rect = RectF()
    private val shadowRect = RectF()

    private val ink = Config.INK
    private val cream = Config.CREAM
    private val paper = Config.PAPER
    private val amber = Config.AMBER
    private val coral = Config.CORAL
    private val mint = Config.MINT
    private val green = 0xFF3E9E5A.toInt()
    private val muted = 0xFF8C8678.toInt()

    private val onDrawFrame: Runnable = object : Runnable {
        override fun run() {
            if (!running) return
            val now = System.nanoTime()
            var dt = (now - lastFrame) / 1_000_000_000f
            lastFrame = now
            if (dt > 0.1f) dt = 0.1f
            step(dt)
            invalidate()
            postOnAnimation(this)
        }
    }

    init {
        renderer.camera.panX = 0f
        renderer.camera.panY = 0f
        banner("Welcome to Cell City!", Config.INK)
        bannerTimer = 5f
    }

    // ------------------------------------------------------------------ lifecycle

    fun onActivated() {
        if (running) return
        running = true
        lastFrame = System.nanoTime()
        postOnAnimation(onDrawFrame)
    }

    fun onDeactivated() {
        running = false
        removeCallbacks(onDrawFrame)
        saveCity()
    }

    private fun loadCity(): CityEngine {
        val saved = prefs.getString(SAVE_KEY, null)
        val loaded = CityEngine.load(saved)
        return loaded ?: CityEngine(seed = System.currentTimeMillis())
    }

    private fun saveCity() {
        prefs.edit().putString(SAVE_KEY, engine.save()).apply()
    }

    private fun step(dt: Float) {
        time += dt
        if (bannerTimer > 0f) bannerTimer = max(0f, bannerTimer - dt)

        if (speed > 0) {
            val interval = 1.15f / speed
            dayTimer += dt
            var guard = 0
            while (dayTimer >= interval && guard < 3) {
                dayTimer -= interval
                engine.tick()
                handleEvents()
                guard++
            }
            renderer.updateCars(dt, engine)
        }
    }

    private fun handleEvents() {
        for (event in engine.drainEvents()) {
            when (event) {
                is CityEvent.MonthFinished -> {
                    val sign = if (event.net >= 0) "+" else "-"
                    banner(
                        "${monthName(event.month)} ${event.year}  $sign$${abs(event.net)}",
                        if (event.net >= 0) green else coral
                    )
                    saveCity()
                }
                is CityEvent.Notice -> banner(event.text, amber)
                is CityEvent.Merged -> banner(
                    "Four lots became one large ${event.zone.label.lowercase()} building",
                    mint
                )
                else -> Unit
            }
        }
    }

    private fun banner(message: String, color: Int) {
        bannerText = message
        bannerColor = color
        bannerTimer = 4.5f
    }

    // ------------------------------------------------------------------ layout

    private fun computeLayout(w: Float, h: Float) {
        val margin = 9f * density
        val panelH = 92f * density
        hudPanelBottom = margin + panelH

        val pad = 10f * density
        val gap = 5f * density
        val buttonH = 42f * density
        val columns = TOOL_COLUMNS
        val rows = (Tool.values().size + columns - 1) / columns
        val hintH = 24f * density
        val bottomPad = 8f * density
        toolbarTop = h - (hintH + rows * buttonH + (rows - 1) * gap + bottomPad)
        mapCenterY = hudPanelBottom + (toolbarTop - hudPanelBottom) * 0.5f

        val buttonW = (w - pad * 2 - gap * (columns - 1)) / columns
        val startY = toolbarTop + hintH
        for (i in toolRects.indices) {
            val col = i % columns
            val row = i / columns
            val left = pad + col * (buttonW + gap)
            val top = startY + row * (buttonH + gap)
            toolRects[i].set(left, top, left + buttonW, top + buttonH)
        }

        val right = w - margin
        val top = margin
        speedRect.set(right - 44f * density, top + 8f * density, right - 8f * density, top + 34f * density)
        newRect.set(
            speedRect.left - 40f * density, top + 8f * density,
            speedRect.left - 6f * density, top + 34f * density
        )
    }

    private fun updateCameraLayout(w: Float, h: Float) {
        renderer.layout(w, h, hudPanelBottom, h - toolbarTop, engine.cols, engine.rows)
    }

    private fun clampCamera() {
        val cam = renderer.camera
        val maxX = (engine.cols + engine.rows) * cam.tileW * 0.5f * cam.zoom * 0.7f
        val maxY = (engine.cols + engine.rows) * cam.tileH * 0.5f * cam.zoom * 0.7f
        cam.panX = cam.panX.coerceIn(-maxX, maxX)
        cam.panY = cam.panY.coerceIn(-maxY, maxY)
    }

    // ------------------------------------------------------------------ draw

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        computeLayout(w, h)
        updateCameraLayout(w, h)

        val valid = hoverCol >= 0 && hoverRow >= 0 &&
            (selected == Tool.BULLDOZE || engine.canBuild(selected, hoverCol, hoverRow) == BuildResult.OK)
        renderer.draw(canvas, engine, selected, hoverCol, hoverRow, valid, time)

        drawTopBar(canvas, w)
        drawBanner(canvas, w)
        drawToolbar(canvas, w, h)
    }

    private fun drawTopBar(canvas: Canvas, w: Float) {
        val margin = 9f * density
        val top = margin
        val bottom = hudPanelBottom
        drawPanel(canvas, margin, top, w - margin, bottom, cream, 2.6f * density)

        val pad = 12f * density
        titlePaint.textSize = 15.5f * density
        titlePaint.color = ink
        canvas.drawText("CELL CITY", margin + pad, top + 24f * density, titlePaint)

        // speed button
        drawSmallButton(canvas, speedRect)
        drawSpeedIcon(canvas, speedRect)

        // new-city button
        drawSmallButton(canvas, newRect)
        textBold.textSize = 8.5f * density
        textBold.color = ink
        textBold.textAlign = Paint.Align.CENTER
        canvas.drawText("NEW", newRect.centerX(), newRect.centerY() + 3f * density, textBold)
        textBold.textAlign = Paint.Align.LEFT

        textRight.textSize = 10f * density
        textRight.color = ink
        canvas.drawText(engine.dateLabel, newRect.left - 8f * density, top + 23f * density, textRight)

        // funds
        val fundsY = top + 52f * density
        textBold.textSize = 17f * density
        textBold.color = if (engine.funds < 0) coral else ink
        canvas.drawText(money(engine.funds), margin + pad, fundsY, textBold)
        text.textSize = 8f * density
        text.color = muted
        canvas.drawText("TREASURY", margin + pad, fundsY + 13f * density, text)

        // population / jobs / happiness
        val colRight = w - margin - pad
        drawStat(canvas, colRight, top + 38f * density, "POP", engine.population.toString(), ink)
        drawStat(canvas, colRight - 52f * density, top + 38f * density, "JOBS", engine.jobs.toString(), ink)
        drawHappiness(canvas, colRight - 104f * density, top + 38f * density)

        // demand strip
        drawDemand(canvas, margin + pad, bottom - 11f * density, w - margin - pad, 9f * density)

        // pause chip
        if (speed == 0) {
            textBold.textSize = 8.5f * density
            textBold.textAlign = Paint.Align.CENTER
            val cx = w * 0.5f
            val cy = top + 24f * density
            rect.set(cx - 34f * density, cy - 9f * density, cx + 34f * density, cy + 9f * density)
            fill.color = amber
            canvas.drawRoundRect(rect, 7f * density, 7f * density, fill)
            stroke.color = ink
            stroke.strokeWidth = 1.5f * density
            canvas.drawRoundRect(rect, 7f * density, 7f * density, stroke)
            canvas.drawText("PAUSED", cx, cy + 3f * density, textBold)
            textBold.textAlign = Paint.Align.LEFT
        }
    }

    private fun drawStat(canvas: Canvas, right: Float, y: Float, label: String, value: String, color: Int) {
        text.textSize = 8f * density
        text.color = muted
        text.textAlign = Paint.Align.RIGHT
        canvas.drawText(label, right, y, text)
        textBold.textSize = 12f * density
        textBold.color = color
        textBold.textAlign = Paint.Align.RIGHT
        canvas.drawText(value, right, y + 14f * density, textBold)
        text.textAlign = Paint.Align.LEFT
        textBold.textAlign = Paint.Align.LEFT
    }

    private fun drawHappiness(canvas: Canvas, right: Float, y: Float) {
        val value = engine.happiness.toInt()
        val color = when {
            value >= 65 -> green
            value >= 40 -> 0xFFD79A2B.toInt()
            else -> coral
        }
        text.textSize = 8f * density
        text.color = muted
        text.textAlign = Paint.Align.RIGHT
        canvas.drawText("HAPPY", right, y, text)
        textBold.textSize = 12f * density
        textBold.color = ink
        textBold.textAlign = Paint.Align.RIGHT
        canvas.drawText(value.toString(), right, y + 14f * density, textBold)
        text.textAlign = Paint.Align.LEFT
        textBold.textAlign = Paint.Align.LEFT

        val fx = right - 35f * density
        val fy = y + 10f * density
        fill.color = color
        canvas.drawCircle(fx, fy, 5.4f * density, fill)
        stroke.color = ink
        stroke.strokeWidth = 1.3f * density
        canvas.drawCircle(fx, fy, 5.4f * density, stroke)
        fill.color = ink
        canvas.drawCircle(fx - 1.8f * density, fy - 1.4f * density, 0.8f * density, fill)
        canvas.drawCircle(fx + 1.8f * density, fy - 1.4f * density, 0.8f * density, fill)
        path.reset()
        path.moveTo(fx - 2.2f * density, fy + 1.2f * density)
        path.quadTo(fx, fy + (if (value >= 50) 4.2f else 2.6f) * density, fx + 2.2f * density, fy + 1.2f * density)
        stroke.strokeWidth = 1.1f * density
        canvas.drawPath(path, stroke)
    }

    private fun drawDemand(canvas: Canvas, left: Float, bottom: Float, right: Float, height: Float) {
        val gap = 8f * density
        val labelW = 10f * density
        val segW = (right - left - gap * 2 - labelW * 3) / 3f
        val values = floatArrayOf(engine.demandR, engine.demandC, engine.demandI)
        val labels = arrayOf("R", "C", "I")
        val colors = intArrayOf(green, 0xFF3C97C7.toInt(), 0xFFD79A2B.toInt())
        var x = left
        for (i in 0 until 3) {
            textBold.textSize = 8f * density
            textBold.color = ink
            textBold.textAlign = Paint.Align.LEFT
            canvas.drawText(labels[i], x, bottom, textBold)
            val barLeft = x + labelW
            rect.set(barLeft, bottom - height, barLeft + segW, bottom)
            fill.color = 0xFFE7DEC8.toInt()
            canvas.drawRoundRect(rect, height * 0.5f, height * 0.5f, fill)
            stroke.color = ink
            stroke.strokeWidth = 1f * density
            canvas.drawRoundRect(rect, height * 0.5f, height * 0.5f, stroke)

            val v = values[i].coerceIn(-1f, 1f)
            val fillW = abs(v) * segW
            if (fillW > 1f) {
                fill.color = if (v >= 0f) colors[i] else coral
                if (v >= 0f) {
                    rect.set(barLeft, bottom - height, barLeft + fillW, bottom)
                } else {
                    rect.set(barLeft + segW - fillW, bottom - height, barLeft + segW, bottom)
                }
                canvas.drawRoundRect(rect, height * 0.5f, height * 0.5f, fill)
            }
            x = barLeft + segW + gap
        }
    }

    private fun drawBanner(canvas: Canvas, w: Float) {
        if (bannerTimer <= 0f || bannerText.isEmpty()) return
        val margin = 14f * density
        val top = hudPanelBottom + 5f * density
        val h = 26f * density
        val left = margin
        val right = w - margin
        drawPanel(canvas, left, top, right, top + h, paper, 2f * density)
        fill.color = bannerColor
        rect.set(left + 8f * density, top + 8f * density, left + 14f * density, top + 18f * density)
        canvas.drawRoundRect(rect, 2f * density, 2f * density, fill)

        textBold.textSize = 9.5f * density
        textBold.color = ink
        textBold.textAlign = Paint.Align.LEFT
        val available = right - left - 34f * density
        canvas.drawText(
            fitText(bannerText, textBold, available),
            left + 20f * density,
            top + h * 0.63f,
            textBold
        )
    }

    private fun drawToolbar(canvas: Canvas, w: Float, h: Float) {
        val top = toolbarTop
        fill.color = cream
        rect.set(0f, top, w, h)
        canvas.drawRoundRect(rect, 16f * density, 16f * density, fill)
        stroke.color = ink
        stroke.strokeWidth = 3f * density
        canvas.drawRoundRect(rect, 16f * density, 16f * density, stroke)

        // hint
        textBold.textSize = 10f * density
        textBold.color = ink
        textBold.textAlign = Paint.Align.LEFT
        val hintColor = if (selected == Tool.BULLDOZE) coral else ink
        textBold.color = hintColor
        canvas.drawText(
            fitText(hintText(), textBold, w - 28f * density),
            14f * density,
            top + 16f * density,
            textBold
        )
        textBold.color = ink

        for (i in Tool.values().indices) {
            drawToolButton(canvas, Tool.values()[i], toolRects[i])
        }
    }

    private fun drawToolButton(canvas: Canvas, tool: Tool, r: RectF) {
        val selectedNow = tool == selected
        val cost = engine.baseCost(tool)
        val affordable = cost <= engine.funds || tool == Tool.BULLDOZE || tool == Tool.PAN
        val radius = 9f * density

        if (selectedNow) {
            fill.color = ink
            shadowRect.set(r.left + 2f * density, r.top + 3f * density, r.right + 2f * density, r.bottom + 3f * density)
            canvas.drawRoundRect(shadowRect, radius, radius, fill)
        }

        fill.color = when {
            selectedNow -> amber
            !affordable -> 0xFFE8E0CD.toInt()
            else -> paper
        }
        canvas.drawRoundRect(r, radius, radius, fill)
        stroke.color = ink
        stroke.strokeWidth = if (selectedNow) 2.6f * density else 1.6f * density
        canvas.drawRoundRect(r, radius, radius, stroke)

        val iconColor = if (!affordable) muted else ink
        val cx = r.centerX()
        val cy = r.top + 16f * density
        drawToolIcon(canvas, tool, cx, cy, 9.5f * density, iconColor)

        textBold.textSize = 8.2f * density
        textBold.color = if (!affordable) muted else ink
        textBold.textAlign = Paint.Align.CENTER
        val label = if (cost > 0) "${tool.label} ${costLabel(cost)}" else tool.label
        canvas.drawText(
            fitText(label, textBold, r.width() - 8f * density),
            r.centerX(),
            r.bottom - 6f * density,
            textBold
        )
        textBold.textAlign = Paint.Align.LEFT
    }

    private fun drawToolIcon(canvas: Canvas, tool: Tool, cx: Float, cy: Float, s: Float, color: Int) {
        val accent = if (tool == Tool.BULLDOZE) coral else amber
        stroke.color = color
        stroke.strokeWidth = 1.7f * density
        fill.color = color
        when (tool) {
            Tool.STATION -> {
                stroke.color = color
                stroke.strokeWidth = 1.6f * density
                fill.color = paper
                rect.set(cx - s * 0.68f, cy - s * 0.12f, cx + s * 0.68f, cy + s * 0.62f)
                canvas.drawRect(rect, fill)
                canvas.drawRect(rect, stroke)
                path.reset()
                path.moveTo(cx - s * 0.92f, cy - s * 0.12f)
                path.lineTo(cx, cy - s * 0.72f)
                path.lineTo(cx + s * 0.92f, cy - s * 0.12f)
                path.lineTo(cx, cy + s * 0.2f)
                path.close()
                fill.color = 0xFF4E7FA0.toInt()
                canvas.drawPath(path, fill)
                stroke.color = color
                canvas.drawPath(path, stroke)
                fill.color = color
                canvas.drawCircle(cx, cy + s * 0.22f, s * 0.18f, fill)
            }
            Tool.PIPE -> {
                stroke.color = color
                stroke.strokeWidth = 6.5f * density
                path.reset()
                path.moveTo(cx - s, cy + s * 0.3f)
                path.lineTo(cx + s, cy - s * 0.3f)
                canvas.drawPath(path, stroke)
                stroke.color = Config.SKY_BLUE
                stroke.strokeWidth = 4f * density
                canvas.drawPath(path, stroke)
                stroke.color = color
                stroke.strokeWidth = 1.5f * density
                fill.color = paper
                canvas.drawCircle(cx - s * 0.5f, cy + s * 0.15f, s * 0.26f, fill)
                canvas.drawCircle(cx - s * 0.5f, cy + s * 0.15f, s * 0.26f, stroke)
                canvas.drawCircle(cx + s * 0.5f, cy - s * 0.15f, s * 0.26f, fill)
                canvas.drawCircle(cx + s * 0.5f, cy - s * 0.15f, s * 0.26f, stroke)
            }
            Tool.HIGHWAY -> {
                stroke.color = 0xFF373C4E.toInt()
                stroke.strokeWidth = 9f * density
                path.reset()
                path.moveTo(cx - s, cy + s * 0.45f)
                path.lineTo(cx + s, cy - s * 0.45f)
                canvas.drawPath(path, stroke)
                stroke.color = color
                stroke.strokeWidth = 1.1f * density
                path.reset()
                path.moveTo(cx - s, cy + s * 0.2f)
                path.lineTo(cx + s, cy - s * 0.7f)
                path.moveTo(cx - s, cy + s * 0.7f)
                path.lineTo(cx + s, cy - s * 0.2f)
                canvas.drawPath(path, stroke)
                dash.color = amber
                dash.strokeWidth = 1.3f * density
                path.reset()
                path.moveTo(cx - s * 0.55f, cy + s * 0.25f)
                path.lineTo(cx + s * 0.55f, cy - s * 0.25f)
                canvas.drawPath(path, dash)
            }
            Tool.POWERLINE -> {
                dash.color = amber
                dash.strokeWidth = 2.2f * density
                path.reset()
                path.moveTo(cx - s, cy + s * 0.4f)
                path.lineTo(cx + s, cy - s * 0.4f)
                canvas.drawPath(path, dash)
                stroke.color = color
                stroke.strokeWidth = 1.3f * density
                fill.color = paper
                for (i in -1..1 step 2) {
                    val bx = cx + i * s * 0.48f
                    val by = cy - i * s * 0.19f
                    rect.set(bx - s * 0.16f, by - s * 0.16f, bx + s * 0.16f, by + s * 0.16f)
                    canvas.drawRect(rect, fill)
                    canvas.drawRect(rect, stroke)
                }
            }
            Tool.RAIL -> {
                stroke.color = color
                stroke.strokeWidth = 1.3f * density
                path.reset()
                for (i in -2..2) {
                    path.moveTo(cx + i * s * 0.4f, cy - s * 0.5f)
                    path.lineTo(cx + i * s * 0.4f, cy + s * 0.5f)
                }
                canvas.drawPath(path, stroke)
                stroke.strokeWidth = 1.7f * density
                path.reset()
                path.moveTo(cx - s, cy - s * 0.24f)
                path.lineTo(cx + s, cy - s * 0.24f)
                path.moveTo(cx - s, cy + s * 0.24f)
                path.lineTo(cx + s, cy + s * 0.24f)
                canvas.drawPath(path, stroke)
            }
            Tool.WATER -> {
                path.reset()
                path.moveTo(cx, cy - s * 0.9f)
                path.quadTo(cx + s * 0.82f, cy + s * 0.12f, cx, cy + s * 0.82f)
                path.quadTo(cx - s * 0.82f, cy + s * 0.12f, cx, cy - s * 0.9f)
                path.close()
                fill.color = Config.SKY_BLUE
                canvas.drawPath(path, fill)
                stroke.color = color
                stroke.strokeWidth = 1.5f * density
                canvas.drawPath(path, stroke)
                fill.color = paper
                canvas.drawCircle(cx - s * 0.22f, cy + s * 0.12f, s * 0.16f, fill)
            }
            Tool.POLICE -> {
                path.reset()
                path.moveTo(cx, cy - s * 0.88f)
                path.lineTo(cx + s * 0.72f, cy - s * 0.52f)
                path.lineTo(cx + s * 0.55f, cy + s * 0.32f)
                path.lineTo(cx, cy + s * 0.88f)
                path.lineTo(cx - s * 0.55f, cy + s * 0.32f)
                path.lineTo(cx - s * 0.72f, cy - s * 0.52f)
                path.close()
                fill.color = 0xFF4E6C99.toInt()
                canvas.drawPath(path, fill)
                stroke.color = color
                stroke.strokeWidth = 1.5f * density
                canvas.drawPath(path, stroke)
                starIcon(canvas, cx, cy, s * 0.36f, paper)
            }
            Tool.FIRE -> {
                path.reset()
                path.moveTo(cx, cy - s * 0.9f)
                path.quadTo(cx + s * 0.82f, cy - s * 0.1f, cx + s * 0.34f, cy + s * 0.72f)
                path.quadTo(cx, cy + s * 0.32f, cx - s * 0.34f, cy + s * 0.72f)
                path.quadTo(cx - s * 0.82f, cy - s * 0.1f, cx, cy - s * 0.9f)
                path.close()
                fill.color = coral
                canvas.drawPath(path, fill)
                stroke.color = color
                stroke.strokeWidth = 1.5f * density
                canvas.drawPath(path, stroke)
                path.reset()
                path.moveTo(cx, cy - s * 0.2f)
                path.quadTo(cx + s * 0.36f, cy + s * 0.22f, cx, cy + s * 0.52f)
                path.quadTo(cx - s * 0.36f, cy + s * 0.22f, cx, cy - s * 0.2f)
                path.close()
                fill.color = amber
                canvas.drawPath(path, fill)
            }
            Tool.HOSPITAL -> {
                fill.color = coral
                rect.set(cx - s * 0.26f, cy - s * 0.86f, cx + s * 0.26f, cy + s * 0.86f)
                canvas.drawRect(rect, fill)
                rect.set(cx - s * 0.86f, cy - s * 0.26f, cx + s * 0.86f, cy + s * 0.26f)
                canvas.drawRect(rect, fill)
                stroke.color = color
                stroke.strokeWidth = 1.3f * density
                rect.set(cx - s * 0.26f, cy - s * 0.86f, cx + s * 0.26f, cy + s * 0.86f)
                canvas.drawRect(rect, stroke)
                rect.set(cx - s * 0.86f, cy - s * 0.26f, cx + s * 0.86f, cy + s * 0.26f)
                canvas.drawRect(rect, stroke)
            }
            Tool.AIRPORT -> {
                path.reset()
                path.moveTo(cx, cy - s * 0.92f)
                path.lineTo(cx + s * 0.16f, cy - s * 0.2f)
                path.lineTo(cx + s * 0.92f, cy + s * 0.24f)
                path.lineTo(cx + s * 0.92f, cy + s * 0.44f)
                path.lineTo(cx + s * 0.18f, cy + s * 0.26f)
                path.lineTo(cx + s * 0.22f, cy + s * 0.68f)
                path.lineTo(cx + s * 0.42f, cy + s * 0.88f)
                path.lineTo(cx, cy + s * 0.72f)
                path.lineTo(cx - s * 0.42f, cy + s * 0.88f)
                path.lineTo(cx - s * 0.22f, cy + s * 0.68f)
                path.lineTo(cx - s * 0.18f, cy + s * 0.26f)
                path.lineTo(cx - s * 0.92f, cy + s * 0.44f)
                path.lineTo(cx - s * 0.92f, cy + s * 0.24f)
                path.lineTo(cx - s * 0.16f, cy - s * 0.2f)
                path.close()
                fill.color = color
                canvas.drawPath(path, fill)
                stroke.color = ink
                stroke.strokeWidth = 1.2f * density
                canvas.drawPath(path, stroke)
            }
            Tool.ROAD -> {
                stroke.strokeWidth = 7f * density
                stroke.color = 0xFF6E7386.toInt()
                path.reset()
                path.moveTo(cx - s, cy + s * 0.55f)
                path.lineTo(cx + s, cy - s * 0.55f)
                canvas.drawPath(path, stroke)
                stroke.color = color
                stroke.strokeWidth = 1.4f * density
                path.reset()
                path.moveTo(cx - s * 0.9f, cy + s * 0.5f)
                path.lineTo(cx + s * 0.9f, cy - s * 0.5f)
                canvas.drawPath(path, stroke)
                dash.color = paper
                dash.strokeWidth = 1.6f * density
                path.reset()
                path.moveTo(cx - s * 0.45f, cy + s * 0.22f)
                path.lineTo(cx + s * 0.45f, cy - s * 0.22f)
                canvas.drawPath(path, dash)
            }
            Tool.RESIDENTIAL -> {
                fill.color = paper
                rect.set(cx - s * 0.6f, cy - s * 0.1f, cx + s * 0.6f, cy + s * 0.65f)
                canvas.drawRect(rect, fill)
                canvas.drawRect(rect, stroke)
                path.reset()
                path.moveTo(cx - s * 0.75f, cy - s * 0.05f)
                path.lineTo(cx, cy - s * 0.7f)
                path.lineTo(cx + s * 0.75f, cy - s * 0.05f)
                path.close()
                fill.color = coral
                canvas.drawPath(path, fill)
                canvas.drawPath(path, stroke)
                fill.color = color
                rect.set(cx + s * 0.15f, cy + s * 0.28f, cx + s * 0.42f, cy + s * 0.65f)
                canvas.drawRect(rect, fill)
            }
            Tool.COMMERCIAL -> {
                fill.color = mint
                rect.set(cx - s * 0.7f, cy - s * 0.15f, cx + s * 0.7f, cy + s * 0.65f)
                canvas.drawRect(rect, fill)
                canvas.drawRect(rect, stroke)
                fill.color = amber
                rect.set(cx - s * 0.8f, cy - s * 0.4f, cx + s * 0.8f, cy - s * 0.05f)
                canvas.drawRect(rect, fill)
                canvas.drawRect(rect, stroke)
                fill.color = paper
                rect.set(cx - s * 0.5f, cy + s * 0.15f, cx - s * 0.05f, cy + s * 0.5f)
                canvas.drawRect(rect, fill)
                canvas.drawRect(rect, stroke)
            }
            Tool.INDUSTRIAL -> {
                fill.color = 0xFFC9B18E.toInt()
                rect.set(cx - s * 0.8f, cy - s * 0.1f, cx + s * 0.7f, cy + s * 0.65f)
                canvas.drawRect(rect, fill)
                canvas.drawRect(rect, stroke)
                path.reset()
                path.moveTo(cx - s * 0.8f, cy - s * 0.1f)
                path.lineTo(cx - s * 0.35f, cy - s * 0.5f)
                path.lineTo(cx + s * 0.1f, cy - s * 0.1f)
                path.lineTo(cx + s * 0.35f, cy - s * 0.45f)
                path.lineTo(cx + s * 0.7f, cy - s * 0.1f)
                canvas.drawPath(path, stroke)
                fill.color = color
                rect.set(cx + s * 0.3f, cy - s * 0.85f, cx + s * 0.55f, cy - s * 0.35f)
                canvas.drawRect(rect, fill)
                canvas.drawCircle(cx + s * 0.45f, cy - s * 1.0f, s * 0.14f, fill)
            }
            Tool.PARK -> {
                fill.color = 0xFF7A5138.toInt()
                rect.set(cx - s * 0.12f, cy + s * 0.2f, cx + s * 0.12f, cy + s * 0.7f)
                canvas.drawRect(rect, fill)
                fill.color = 0xFF4FAE5A.toInt()
                canvas.drawCircle(cx, cy - s * 0.15f, s * 0.6f, fill)
                stroke.color = color
                canvas.drawCircle(cx, cy - s * 0.15f, s * 0.6f, stroke)
                fill.color = 0xFF8FE08A.toInt()
                canvas.drawCircle(cx - s * 0.2f, cy - s * 0.35f, s * 0.16f, fill)
            }
            Tool.PLANT -> {
                stroke.color = color
                path.reset()
                path.moveTo(cx - s * 0.55f, cy + s * 0.6f)
                path.lineTo(cx - s * 0.3f, cy - s * 0.5f)
                path.lineTo(cx + s * 0.1f, cy - s * 0.5f)
                path.lineTo(cx + s * 0.4f, cy + s * 0.6f)
                canvas.drawPath(path, stroke)
                path.reset()
                path.moveTo(cx - s * 0.3f, cy - s * 0.5f)
                path.lineTo(cx + s * 0.1f, cy - s * 0.5f)
                canvas.drawPath(path, stroke)
                path.reset()
                path.moveTo(cx + s * 0.55f, cy - s * 0.8f)
                path.lineTo(cx + s * 0.1f, cy - s * 0.15f)
                path.lineTo(cx + s * 0.4f, cy - s * 0.15f)
                path.lineTo(cx + s * 0.2f, cy + s * 0.45f)
                path.lineTo(cx + s * 0.82f, cy - s * 0.3f)
                path.lineTo(cx + s * 0.48f, cy - s * 0.3f)
                path.close()
                fill.color = accent
                canvas.drawPath(path, fill)
                stroke.color = color
                canvas.drawPath(path, stroke)
            }
            Tool.BULLDOZE -> {
                diamondIcon(canvas, cx, cy, s)
                stroke.color = coral
                stroke.strokeWidth = 2.4f * density
                path.reset()
                path.moveTo(cx - s * 0.4f, cy - s * 0.4f)
                path.lineTo(cx + s * 0.4f, cy + s * 0.4f)
                path.moveTo(cx + s * 0.4f, cy - s * 0.4f)
                path.lineTo(cx - s * 0.4f, cy + s * 0.4f)
                canvas.drawPath(path, stroke)
            }
            Tool.PAN -> {
                stroke.strokeWidth = 1.8f * density
                path.reset()
                path.moveTo(cx - s * 0.7f, cy)
                path.lineTo(cx + s * 0.7f, cy)
                path.moveTo(cx, cy - s * 0.7f)
                path.lineTo(cx, cy + s * 0.7f)
                canvas.drawPath(path, stroke)
                path.reset()
                path.moveTo(cx - s * 0.7f, cy)
                path.lineTo(cx - s * 0.35f, cy - s * 0.28f)
                path.moveTo(cx - s * 0.7f, cy)
                path.lineTo(cx - s * 0.35f, cy + s * 0.28f)
                path.moveTo(cx + s * 0.7f, cy)
                path.lineTo(cx + s * 0.35f, cy - s * 0.28f)
                path.moveTo(cx + s * 0.7f, cy)
                path.lineTo(cx + s * 0.35f, cy + s * 0.28f)
                path.moveTo(cx, cy - s * 0.7f)
                path.lineTo(cx - s * 0.28f, cy - s * 0.35f)
                path.moveTo(cx, cy - s * 0.7f)
                path.lineTo(cx + s * 0.28f, cy - s * 0.35f)
                path.moveTo(cx, cy + s * 0.7f)
                path.lineTo(cx - s * 0.28f, cy + s * 0.35f)
                path.moveTo(cx, cy + s * 0.7f)
                path.lineTo(cx + s * 0.28f, cy + s * 0.35f)
                canvas.drawPath(path, stroke)
            }
        }
    }

    private fun starIcon(canvas: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        path.reset()
        for (i in 0 until 10) {
            val angle = Math.toRadians((-90 + i * 36).toDouble()).toFloat()
            val radius = if (i % 2 == 0) r else r * 0.44f
            val px = cx + kotlin.math.cos(angle) * radius
            val py = cy + kotlin.math.sin(angle) * radius
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()
        fill.color = color
        canvas.drawPath(path, fill)
    }

    private fun diamondIcon(canvas: Canvas, cx: Float, cy: Float, s: Float) {
        path.reset()
        path.moveTo(cx, cy - s * 0.55f)
        path.lineTo(cx + s * 0.75f, cy)
        path.lineTo(cx, cy + s * 0.55f)
        path.lineTo(cx - s * 0.75f, cy)
        path.close()
        fill.color = paper
        canvas.drawPath(path, fill)
        stroke.color = ink
        stroke.strokeWidth = 1.6f * density
        canvas.drawPath(path, stroke)
    }

    private fun drawSmallButton(canvas: Canvas, r: RectF) {
        fill.color = paper
        canvas.drawRoundRect(r, 7f * density, 7f * density, fill)
        stroke.color = ink
        stroke.strokeWidth = 1.5f * density
        canvas.drawRoundRect(r, 7f * density, 7f * density, stroke)
    }

    private fun drawSpeedIcon(canvas: Canvas, r: RectF) {
        fill.color = ink
        val cx = r.centerX()
        val cy = r.centerY()
        val s = 4.2f * density
        when (speed) {
            0 -> {
                rect.set(cx - s * 0.75f, cy - s, cx - s * 0.15f, cy + s)
                canvas.drawRect(rect, fill)
                rect.set(cx + s * 0.15f, cy - s, cx + s * 0.75f, cy + s)
                canvas.drawRect(rect, fill)
            }
            1 -> {
                triangle(canvas, cx + s * 0.5f, cy, s)
            }
            2 -> {
                triangle(canvas, cx - s * 0.2f, cy, s)
                triangle(canvas, cx + s * 1.1f, cy, s)
            }
            else -> {
                triangle(canvas, cx - s * 1.2f, cy, s)
                triangle(canvas, cx, cy, s)
                triangle(canvas, cx + s * 1.2f, cy, s)
            }
        }
    }

    private fun triangle(canvas: Canvas, cx: Float, cy: Float, s: Float) {
        path.reset()
        path.moveTo(cx - s * 0.6f, cy - s)
        path.lineTo(cx + s * 0.8f, cy)
        path.lineTo(cx - s * 0.6f, cy + s)
        path.close()
        canvas.drawPath(path, fill)
    }

    private fun drawPanel(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        color: Int,
        strokeWidth: Float
    ) {
        val radius = 12f * density
        fill.color = ink
        rect.set(left + 3f * density, top + 4f * density, right + 3f * density, bottom + 4f * density)
        canvas.drawRoundRect(rect, radius, radius, fill)
        fill.color = color
        rect.set(left, top, right, bottom)
        canvas.drawRoundRect(rect, radius, radius, fill)
        stroke.color = ink
        stroke.strokeWidth = strokeWidth
        canvas.drawRoundRect(rect, radius, radius, stroke)
    }

    // ------------------------------------------------------------------ text

    private fun money(value: Int): String {
        val sign = if (value < 0) "-$" else "$"
        return sign + String.format(Locale.US, "%,d", abs(value))
    }

    /** Compact price for the narrow toolbar buttons: $40, $900, $2.5k. */
    private fun costLabel(cost: Int): String {
        if (cost < 1000) return "$$cost"
        val thousands = cost / 1000f
        return if (cost % 1000 == 0) {
            "$${cost / 1000}k"
        } else {
            "$" + String.format(Locale.US, "%.1fk", thousands)
        }
    }

    private fun monthName(month: Int): String {
        val names = arrayOf(
            "Jan", "Feb", "Mar", "Apr", "May", "Jun",
            "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
        )
        return names[(month - 1).coerceIn(0, 11)]
    }

    private fun fitText(value: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(value) <= maxWidth) return value
        var end = value.length
        while (end > 1 && paint.measureText(value.substring(0, end) + "…") > maxWidth) end--
        return value.substring(0, end) + "…"
    }

    private fun hintText(): String {
        if (engine.roadCount == 0 && engine.highwayCount == 0 &&
            selected != Tool.ROAD && selected != Tool.HIGHWAY && selected != Tool.PAN
        ) {
            return "Start with ROAD - drag it across the grass"
        }
        if (engine.roadCount == 0 && engine.highwayCount == 0 &&
            (selected == Tool.ROAD || selected == Tool.HIGHWAY)
        ) {
            return "Drag across the grass to lay a street"
        }
        if (engine.zoneCount == 0 && selected != Tool.RESIDENTIAL) {
            return "Roads down. Now zone RES beside them"
        }
        if (engine.plantCount == 0 && selected == Tool.PLANT) {
            return "Place the plant beside a road so power can flow"
        }
        if (engine.plantCount == 0 && selected != Tool.PLANT) {
            return "Homes need power - add a PLANT next to the road"
        }
        if (engine.waterCount == 0 && engine.zoneCount > 0) {
            return "Zones also need water - place a WATER tower nearby"
        }
        if (engine.developedHousing == 0 && engine.zoneCount > 0) {
            return "Keep zones powered, watered and road-connected"
        }
        if (engine.population > 20 && engine.jobs < engine.population * 0.4f) {
            return "Citizens need jobs - zone COM or IND beside a road"
        }
        if (engine.population > 20 && engine.happiness < 50f && engine.parkCount < 2) {
            return "Add a PARK to cheer the citizens up"
        }
        if (engine.population > 30 && engine.policeCount == 0) {
            return "Crime is rising - add a POLICE station"
        }
        if (engine.population > 30 && engine.fireCount == 0) {
            return "Fires are a risk - a FIRE station covers the block"
        }
        if (engine.population > 40 && engine.hospitalCount == 0) {
            return "Citizens need a HOSPITAL to stay healthy"
        }
        if (engine.mergedBlockCount == 0 && engine.buildingCount > 8) {
            return "Four level-2 lots of a zone merge into one big building"
        }
        return when (selected) {
            Tool.PAN -> "Drag to pan - pinch to zoom"
            Tool.BULLDOZE -> "Tap a tile to clear it (and its main) - 25% refund"
            Tool.PLANT -> "PLANT $2500 - powers connected roads"
            Tool.ROAD -> "ROAD $15 - bridges cost $90 over water"
            Tool.HIGHWAY -> "HWY $120 - four lanes: faster traffic, more trade; $300 over water"
            Tool.POWERLINE -> "WIRE $10 - buried cable carries power across open ground"
            Tool.RAIL -> "RAIL $40 - lines boost the lots beside them"
            Tool.RESIDENTIAL -> "RES $40 - homes fill up, then upgrade"
            Tool.COMMERCIAL -> "COM $50 - shops need residents nearby"
            Tool.INDUSTRIAL -> "IND $60 - jobs and taxes, but pollution"
            Tool.PARK -> "PARK $100 - makes citizens happier"
            Tool.WATER -> "WATER $500 - pipes water to lots within 6 tiles"
            Tool.PIPE -> "PIPE $12 - mains go under roads and buildings; $70 under the river"
            Tool.STATION -> "STATION $700 - place it beside rail to spread transit access"
            Tool.POLICE -> "POLICE $900 - cuts crime and lifts happiness"
            Tool.FIRE -> "FIRE $800 - protects the neighbourhood"
            Tool.HOSPITAL -> "HOSP $1200 - healthy citizens are happy ones"
            Tool.AIRPORT -> "AIR $6000 - build on a clear 2x2 lot beside a road"
        }
    }

    // ------------------------------------------------------------------ input

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val w = width.toFloat()
        val h = height.toFloat()
        computeLayout(w, h)
        updateCameraLayout(w, h)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val x = event.x
                val y = event.y
                if (y <= hudPanelBottom + 4f * density) {
                    if (speedRect.contains(x, y)) {
                        cycleSpeed()
                        performClick()
                        return true
                    }
                    if (newRect.contains(x, y)) {
                        confirmNewCity()
                        return true
                    }
                    return true
                }
                if (y >= toolbarTop) {
                    for (i in toolRects.indices) {
                        if (toolRects[i].contains(x, y)) {
                            selected = Tool.values()[i]
                            lastPaintCol = -1
                            lastPaintRow = -1
                            performClick()
                            return true
                        }
                    }
                    return true
                }

                activePointer = event.getPointerId(0)
                downX = x
                downY = y
                lastTouchX = x
                lastTouchY = y
                lastPaintCol = -1
                lastPaintRow = -1
                didPaint = false
                mode = if (selected == Tool.PAN) MODE_PAN else MODE_PAINT
                updateHover(x, y)
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2) {
                    mode = MODE_PINCH
                    pinchStartDistance = max(1f, distance(event, 0, 1))
                    pinchStartZoom = renderer.camera.zoom
                    lastMidX = (event.getX(0) + event.getX(1)) * 0.5f
                    lastMidY = (event.getY(0) + event.getY(1)) * 0.5f
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == MODE_PINCH && event.pointerCount >= 2) {
                    val dist = max(1f, distance(event, 0, 1))
                    val cam = renderer.camera
                    val newZoom = (pinchStartZoom * dist / pinchStartDistance)
                        .coerceIn(renderer.minZoom(engine.cols, engine.rows), renderer.maxZoom())
                    val ratio = if (cam.zoom <= 0.0001f) 1f else newZoom / cam.zoom
                    val midX = (event.getX(0) + event.getX(1)) * 0.5f
                    val midY = (event.getY(0) + event.getY(1)) * 0.5f
                    val centerX = w * 0.5f + cam.panX
                    val centerY = mapCenterY + cam.panY
                    cam.panX += (midX - centerX) * (1f - ratio) + (midX - lastMidX)
                    cam.panY += (midY - centerY) * (1f - ratio) + (midY - lastMidY)
                    cam.zoom = newZoom
                    lastMidX = midX
                    lastMidY = midY
                    clampCamera()
                } else if (mode == MODE_PAN) {
                    val idx = event.findPointerIndex(activePointer)
                    if (idx >= 0) {
                        val x = event.getX(idx)
                        val y = event.getY(idx)
                        val cam = renderer.camera
                        cam.panX += x - lastTouchX
                        cam.panY += y - lastTouchY
                        lastTouchX = x
                        lastTouchY = y
                        clampCamera()
                        updateHover(x, y)
                    }
                } else if (mode == MODE_PAINT) {
                    val idx = event.findPointerIndex(activePointer)
                    if (idx >= 0) {
                        val x = event.getX(idx)
                        val y = event.getY(idx)
                        updateHover(x, y)
                        val moved = hypot(x - downX, y - downY)
                        if (moved > touchSlop || didPaint) {
                            if (!didPaint) {
                                paintSegment(downX, downY, downX, downY, force = false)
                                didPaint = true
                            }
                            paintSegment(lastTouchX, lastTouchY, x, y, force = false)
                        }
                        lastTouchX = x
                        lastTouchY = y
                    }
                }
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (mode == MODE_PINCH && event.pointerCount - 1 < 2) {
                    var remaining = 0
                    while (remaining < event.pointerCount && remaining == event.actionIndex) remaining++
                    if (remaining >= event.pointerCount) remaining = 0
                    activePointer = event.getPointerId(remaining)
                    lastTouchX = event.getX(remaining)
                    lastTouchY = event.getY(remaining)
                    mode = MODE_PAN
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val x = event.x
                val y = event.y
                if (mode == MODE_PAINT && (!didPaint || selected.singlePlacement)) {
                    updateHover(x, y)
                    paintSegment(x, y, x, y, force = true)
                } else if (mode == MODE_PAN) {
                    updateHover(x, y)
                }
                mode = MODE_NONE
                activePointer = -1
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                mode = MODE_NONE
                activePointer = -1
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun cycleSpeed() {
        speed = (speed + 1) % 4
        dayTimer = 0f
        if (speed == 0) banner("City paused", amber)
    }

    private fun distance(event: MotionEvent, a: Int, b: Int): Float {
        val dx = event.getX(a) - event.getX(b)
        val dy = event.getY(a) - event.getY(b)
        return hypot(dx, dy)
    }

    private fun updateHover(x: Float, y: Float) {
        val col = renderer.camera.screenToCol(x, y)
        val row = renderer.camera.screenToRow(x, y)
        if (engine.inBounds(col, row)) {
            hoverCol = col
            hoverRow = row
        } else {
            hoverCol = -1
            hoverRow = -1
        }
    }

    private fun paintSegment(fromX: Float, fromY: Float, toX: Float, toY: Float, force: Boolean) {
        if (selected == Tool.PAN) return
        if (selected.singlePlacement) {
            if (force) paintTile(toX, toY, true)
            return
        }
        val cam = renderer.camera
        val distance = hypot(toX - fromX, toY - fromY)
        val step = max(1f, cam.tileW * cam.zoom * 0.22f)
        val steps = max(1, (distance / step).toInt())
        for (i in 0..steps) {
            val t = i.toFloat() / steps
            val x = fromX + (toX - fromX) * t
            val y = fromY + (toY - fromY) * t
            paintTile(x, y, force && i == steps)
        }
    }

    private fun paintTile(x: Float, y: Float, force: Boolean) {
        val cam = renderer.camera
        val col = cam.screenToCol(x, y)
        val row = cam.screenToRow(x, y)
        if (!engine.inBounds(col, row)) return
        if (col == lastPaintCol && row == lastPaintRow) return

        val tile = engine.tile(col, row) ?: return
        val beforeZone = tile.zone
        val beforePipe = tile.pipe
        val beforeWire = tile.powerline
        val result = engine.build(selected, col, row)
        engine.drainEvents()
        lastPaintCol = col
        lastPaintRow = row

        if (result != BuildResult.OK) {
            if (force || bannerTimer <= 0.8f) banner(result.message, coral)
            return
        }
        val after = engine.tile(col, row)
        if (after?.zone != beforeZone || after.pipe != beforePipe || after.powerline != beforeWire) {
            val now = System.currentTimeMillis()
            if (now - lastHaptic > 70) {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                lastHaptic = now
            }
            when {
                selected == Tool.PLANT && engine.plantCount == 1 ->
                    banner("Power plant online - watch the flow along roads", mint)
                selected == Tool.WATER && engine.waterCount == 1 ->
                    banner("Water tower online - nearby lots now have water", Config.SKY_BLUE)
                selected == Tool.PIPE && engine.pipeCount == 1 ->
                    banner("Water mains laid - run them from a tower to dry lots", Config.SKY_BLUE)
                selected == Tool.POWERLINE && engine.powerlineCount == 1 ->
                    banner("Cables live - the grid can now cross open ground", amber)
                selected == Tool.STATION && engine.stationCount == 1 ->
                    banner("Train station open - nearby lots gain transit access", mint)
                selected == Tool.AIRPORT ->
                    banner("Airport open - tourists boost the shops", Config.BRASS)
            }
        }
    }

    private fun confirmNewCity() {
        val wasSpeed = speed
        speed = 0
        AlertDialog.Builder(context, android.R.style.Theme_Material_Light_Dialog_Alert)
            .setTitle("Start a new city?")
            .setMessage("Your current city will be wiped from the map.")
            .setPositiveButton("New city") { _, _ ->
                prefs.edit().remove(SAVE_KEY).apply()
                engine = CityEngine(seed = System.currentTimeMillis())
                selected = Tool.ROAD
                hoverCol = engine.cols / 2
                hoverRow = engine.rows / 2
                dayTimer = 0f
                renderer.focusOn(engine.cols / 2, engine.rows / 2)
                clampCamera()
                speed = 1
                banner("A fresh patch of land awaits", mint)
                saveCity()
                invalidate()
            }
            .setNegativeButton("Keep building") { _, _ ->
                speed = wasSpeed
                invalidate()
            }
            .setOnCancelListener {
                speed = wasSpeed
                invalidate()
            }
            .show()
    }

    companion object {
        private const val SAVE_KEY = "city_v1"
        private const val TOOL_COLUMNS = 5
        private const val MODE_NONE = 0
        private const val MODE_PAINT = 1
        private const val MODE_PAN = 2
        private const val MODE_PINCH = 3
    }
}
