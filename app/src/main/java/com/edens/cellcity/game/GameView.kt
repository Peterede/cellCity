package com.edens.cellcity.game

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
import kotlin.math.roundToInt
import kotlin.math.sin

class GameView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density
    private val prefs = context.getSharedPreferences("cellcity", Context.MODE_PRIVATE)
    private var engine: CityEngine = loadCity()
    private val renderer = CityRenderer(density)
    private val sound = SoundManager(context)
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
    private val bannerRect = RectF()

    // the cell the newest event happened on, so its banner can jump there
    private var eventCol = -1
    private var eventRow = -1
    private var eventHighlightTimer = 0f

    // smooth camera move when a banner is tapped
    private var panTargetX: Float? = null
    private var panTargetY: Float? = null

    private var hudPanelBottom = 0f
    private var toolbarTop = 0f
    private var mapCenterY = 0f
    private val toolRects = Array(Tool.values().size) { RectF() }
    private val speedRect = RectF()
    private val newRect = RectF()
    private val goalsRect = RectF()
    private val soundRect = RectF()
    private val undoRect = RectF()
    private val advisorRect = RectF()
    private var goalsOpen = false

    // advisor sheet and the tax stepper inside it
    private var advisorOpen = false
    private var advisorReport = CityAdvisor.report(engine)
    private val taxDownRect = RectF()
    private val taxUpRect = RectF()

    // info tool: the cell being inspected and what it needs
    private var inspectCol = -1
    private var inspectRow = -1
    private var inspectReport: TileReport? = null

    // long-press hover card
    private var tooltipCol = -1
    private var tooltipRow = -1
    private var tooltipActive = false
    private var pressStart = 0L
    private var pressX = 0f
    private var pressY = 0f

    private val longPressRunnable = Runnable {
        if (!engine.inBounds(hoverCol, hoverRow)) return@Runnable
        tooltipCol = hoverCol
        tooltipRow = hoverRow
        tooltipActive = true
        lastHaptic = System.currentTimeMillis()
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        invalidate()
    }

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
        sound.enabled = prefs.getBoolean(SOUND_KEY, true)
        banner("Welcome to Cell City!", Config.INK)
        bannerTimer = 5f
    }

    // ------------------------------------------------------------------ lifecycle

    fun onActivated() {
        if (running) return
        running = true
        lastFrame = System.nanoTime()
        sound.resumeAmbient()
        postOnAnimation(onDrawFrame)
    }

    fun onDeactivated() {
        running = false
        removeCallbacks(onDrawFrame)
        sound.pauseAmbient()
        sound.pauseEffects()
        saveCity()
    }

    fun onReleased() {
        sound.release()
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
        if (eventHighlightTimer > 0f) eventHighlightTimer = max(0f, eventHighlightTimer - dt)

        // glide the camera toward an event the player tapped
        val targetX = panTargetX
        val targetY = panTargetY
        if (targetX != null && targetY != null) {
            val cam = renderer.camera
            val k = min(1f, dt * 8f)
            cam.panX += (targetX - cam.panX) * k
            cam.panY += (targetY - cam.panY) * k
            if (abs(targetX - cam.panX) < 0.5f && abs(targetY - cam.panY) < 0.5f) {
                cam.panX = targetX
                cam.panY = targetY
                panTargetX = null
                panTargetY = null
            }
        }

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
            if (guard > 0) {
                advisorReport = CityAdvisor.report(engine)
                if (inspectReport != null) {
                    inspectReport = CityInspector.report(engine, inspectCol, inspectRow)
                }
            }
            renderer.updateCars(dt, engine)
        }
        sound.updateScene(
            if (speed > 0) dt else 0f,
            engine.activeFires,
            engine.population,
            engine.happiness
        )
        renderer.updateAnimations(dt)
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
                is CityEvent.FireStarted -> {
                    banner(
                        "Fire! A ${event.zone.label.lowercase()} is burning - FIRE cover puts it out",
                        coral,
                        event.col,
                        event.row
                    )
                    sound.onFireStarted()
                }
                is CityEvent.FireOut -> {
                    banner("Fire crews put out the blaze", mint, event.col, event.row)
                    sound.onFireOut()
                }
                is CityEvent.FireDestroyed -> {
                    renderer.flashDeath(event.col, event.row)
                    banner(
                        "A building burned down - nobody reached the fire",
                        coral,
                        event.col,
                        event.row
                    )
                    sound.playDeath()
                }
                is CityEvent.CrimeReported -> {
                    banner(event.text, coral, event.col, event.row)
                    sound.onCrimeReported()
                }
                is CityEvent.Notice -> banner(event.text, amber)
                is CityEvent.MilestoneReached -> {
                    val unlockText = if (event.milestone.unlocks.isNotEmpty()) {
                        " - unlocked ${event.milestone.unlocks.joinToString(", ") { it.label }}"
                    } else {
                        ""
                    }
                    banner(
                        "Goal reached: ${event.milestone.title}  +$${event.milestone.cash}$unlockText",
                        green
                    )
                    sound.playCheer()
                }
                is CityEvent.Grew -> {
                    renderer.flashBirth(event.col, event.row)
                    sound.playBirth()
                }
                is CityEvent.Upgraded -> {
                    sound.playUpgrade()
                }
                is CityEvent.Died -> {
                    renderer.flashDeath(event.col, event.row)
                    sound.playDeath()
                }
                is CityEvent.Built -> sound.playBuild()
                is CityEvent.Merged -> {
                    renderer.flashBirth(event.col, event.row)
                    banner(
                        "Four lots became one large ${event.zone.label.lowercase()} building",
                        mint
                    )
                }
                else -> Unit
            }
        }
    }

    /**
     * Shows a message. Pass the coordinates of the cell it is about and the
     * banner becomes tappable: it pans there and points an arrow at it.
     */
    private fun banner(message: String, color: Int, col: Int = -1, row: Int = -1) {
        bannerText = message
        bannerColor = color
        bannerTimer = 4.5f
        val located = engine.inBounds(col, row)
        eventCol = if (located) col else -1
        eventRow = if (located) row else -1
        if (!located) eventHighlightTimer = 0f
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

        // five small buttons share the top-right corner, so they stay narrow
        val right = w - margin
        val top = margin
        val smallW = 34f * density
        val buttonTop = top + 8f * density
        val buttonBottom = buttonTop + 26f * density
        speedRect.set(right - 8f * density - smallW, buttonTop, right - 8f * density, buttonBottom)
        newRect.set(speedRect.left - gap - smallW, buttonTop, speedRect.left - gap, buttonBottom)
        goalsRect.set(newRect.left - gap - smallW, buttonTop, newRect.left - gap, buttonBottom)
        soundRect.set(goalsRect.left - gap - smallW, buttonTop, goalsRect.left - gap, buttonBottom)
        undoRect.set(soundRect.left - gap - smallW, buttonTop, soundRect.left - gap, buttonBottom)
        advisorRect.set(undoRect.left - gap - smallW, buttonTop, undoRect.left - gap, buttonBottom)

        if (advisorOpen) advisorTaxRects(advisorPanel(w))
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
            (selected == Tool.BULLDOZE || selected == Tool.INFO ||
                engine.canBuild(selected, hoverCol, hoverRow) == BuildResult.OK)
        renderer.draw(canvas, engine, selected, hoverCol, hoverRow, valid, time)
        drawEventFocus(canvas)
        if (selected == Tool.INFO) drawInspectorMarker(canvas)

        drawTopBar(canvas, w)
        drawBanner(canvas, w)
        drawToolbar(canvas, w, h)
        if (selected == Tool.INFO) drawInspectorCard(canvas, w, h)
        if (tooltipActive) drawTooltip(canvas, w, h)
        if (goalsOpen) drawGoalsOverlay(canvas, w, h)
        if (advisorOpen) drawAdvisorOverlay(canvas, w, h)
    }

    /** The bounds of the goals sheet: a tall panel across the middle of the screen. */
    private fun goalsLayout(w: Float): RectF {
        val margin = 12f * density
        val top = hudPanelBottom + 8f * density
        val bottom = toolbarTop - 8f * density
        return RectF(margin, top, w - margin, bottom)
    }

    private fun goalsRowRect(index: Int, panel: RectF): RectF {
        val pad = 12f * density
        val headerH = 46f * density
        val footerH = 18f * density
        val available = panel.height() - headerH - footerH
        val rowH = min(34f * density, available / Milestone.values().size)
        val headY = panel.top + headerH
        val left = panel.left + pad
        val top = headY + index * rowH
        return RectF(left, top, panel.right - pad, top + rowH - 4f * density)
    }

    private fun drawGoalsOverlay(canvas: Canvas, w: Float, h: Float) {
        fill.color = 0xB014263B.toInt()
        canvas.drawRect(0f, 0f, w, h, fill)

        val panel = goalsLayout(w)
        drawPanel(canvas, panel.left, panel.top, panel.right, panel.bottom, paper, 3f * density)

        titlePaint.textSize = 15f * density
        titlePaint.color = ink
        canvas.drawText("GOALS", panel.left + 12f * density, panel.top + 24f * density, titlePaint)

        text.textSize = 9f * density
        text.color = muted
        canvas.drawText(
            "${engine.completedMilestones.size} of ${Milestone.values().size} complete  ·  " +
                "stable streak ${engine.stableDays} days",
            panel.left + 12f * density,
            panel.top + 38f * density,
            text
        )

        for ((i, milestone) in Milestone.values().withIndex()) {
            drawGoalRow(canvas, milestone, goalsRowRect(i, panel))
        }

        text.textSize = 8.5f * density
        text.color = muted
        text.textAlign = Paint.Align.CENTER
        canvas.drawText("Tap anywhere to close", panel.centerX(), panel.bottom - 8f * density, text)
        text.textAlign = Paint.Align.LEFT
    }

    private fun drawGoalRow(canvas: Canvas, milestone: Milestone, r: RectF) {
        val done = milestone in engine.completedMilestones
        val current = engine.nextMilestone() == milestone
        val radius = 8f * density
        fill.color = when {
            done -> 0xFFDCEBD6.toInt()
            current -> 0xFFFFF2CE.toInt()
            else -> 0xFFF1EAD8.toInt()
        }
        canvas.drawRoundRect(r, radius, radius, fill)
        stroke.color = if (current) amber else ink
        stroke.strokeWidth = if (current) 2.4f * density else 1.3f * density
        canvas.drawRoundRect(r, radius, radius, stroke)

        // tick / bullet
        val bx = r.left + 15f * density
        val by = r.centerY()
        if (done) {
            fill.color = green
            canvas.drawCircle(bx, by, 7f * density, fill)
            stroke.color = paper
            stroke.strokeWidth = 2f * density
            path.reset()
            path.moveTo(bx - 3.4f * density, by)
            path.lineTo(bx - 0.8f * density, by + 2.8f * density)
            path.lineTo(bx + 3.6f * density, by - 3f * density)
            canvas.drawPath(path, stroke)
        } else {
            stroke.color = if (current) amber else muted
            stroke.strokeWidth = 1.8f * density
            canvas.drawCircle(bx, by, 6f * density, stroke)
        }

        val textLeft = bx + 14f * density
        textBold.textSize = 10.5f * density
        textBold.color = ink
        textBold.textAlign = Paint.Align.LEFT
        canvas.drawText(milestone.title, textLeft, r.top + 14f * density, textBold)

        text.textSize = 8.5f * density
        text.color = muted
        canvas.drawText(milestone.detail, textLeft, r.top + 25f * density, text)

        // progress or reward on the right
        textBold.textAlign = Paint.Align.RIGHT
        textBold.textSize = 9.5f * density
        if (!done) {
            val value = engine.milestoneValue(milestone).coerceAtMost(engine.milestoneTarget(milestone))
            textBold.color = ink
            canvas.drawText("$value/${engine.milestoneTarget(milestone)}", r.right - 10f * density, r.centerY() + 3f * density, textBold)
        } else {
            textBold.color = 0xFF3E7A45.toInt()
            canvas.drawText("+$${milestone.cash}", r.right - 10f * density, r.centerY() + 3f * density, textBold)
        }
        textBold.textAlign = Paint.Align.LEFT

        // unlock chip
        if (milestone.unlocks.isNotEmpty()) {
            val names = milestone.unlocks.joinToString(", ") { it.label }
            text.textSize = 7.5f * density
            text.color = 0xFF3C6E9E.toInt()
            text.textAlign = Paint.Align.RIGHT
            canvas.drawText("unlocks $names", r.right - 10f * density, r.top + 12f * density, text)
            text.textAlign = Paint.Align.LEFT
        }
    }

    // ------------------------------------------------------------------ advisor

    /** The advisor shares the goals sheet's footprint. */
    private fun advisorPanel(w: Float): RectF = goalsLayout(w)

    private fun taxBandRect(panel: RectF): RectF {
        val inset = 12f * density
        return RectF(
            panel.left + inset,
            panel.top + 50f * density,
            panel.right - inset,
            panel.top + 96f * density
        )
    }

    private fun advisorTaxRects(panel: RectF) {
        val band = taxBandRect(panel)
        val size = 30f * density
        val top = band.centerY() - size * 0.5f
        taxUpRect.set(band.right - 10f * density - size, top, band.right - 10f * density, top + size)
        taxDownRect.set(taxUpRect.left - 6f * density - size, top, taxUpRect.left - 6f * density, top + size)
    }

    private fun moodColor(mood: CityMood): Int = when (mood) {
        CityMood.THRIVING -> green
        CityMood.CONTENT -> 0xFF3C97C7.toInt()
        CityMood.UNEASY -> 0xFFD79A2B.toInt()
        CityMood.ANGRY -> coral
    }

    private fun drawAdvisorOverlay(canvas: Canvas, w: Float, h: Float) {
        fill.color = 0xB014263B.toInt()
        canvas.drawRect(0f, 0f, w, h, fill)

        val panel = advisorPanel(w)
        val report = advisorReport
        drawPanel(canvas, panel.left, panel.top, panel.right, panel.bottom, paper, 3f * density)

        val pad = 12f * density
        titlePaint.textSize = 15f * density
        titlePaint.color = ink
        canvas.drawText("ADVISOR", panel.left + pad, panel.top + 23f * density, titlePaint)

        textBold.textSize = 11f * density
        textBold.color = ink
        textBold.textAlign = Paint.Align.RIGHT
        canvas.drawText(
            "${engine.happiness.toInt()}/100 HAPPY",
            panel.right - pad,
            panel.top + 22f * density,
            textBold
        )
        textBold.textAlign = Paint.Align.LEFT

        // mood: one big word, then what the citizens are saying
        textBold.textSize = 10f * density
        textBold.color = moodColor(report.mood)
        val moodText = report.mood.label.uppercase(Locale.US)
        canvas.drawText(moodText, panel.left + pad, panel.top + 41f * density, textBold)
        val quoteLeft = panel.left + pad + textBold.measureText(moodText) + 8f * density
        text.textSize = 8.5f * density
        text.color = muted
        canvas.drawText(
            fitText(report.mood.quote, text, panel.right - pad - quoteLeft),
            quoteLeft,
            panel.top + 41f * density,
            text
        )

        drawTaxBand(canvas, panel)

        val rowsTop = panel.top + 124f * density
        val footerReserve = 46f * density
        val available = max(0f, panel.bottom - footerReserve - rowsTop)
        val maxRows = min(5, max(0, (available / (34f * density)).toInt()))
        val shown = min(maxRows, report.issues.size)

        titlePaint.textSize = 9.5f * density
        titlePaint.color = ink
        canvas.drawText(
            "WHAT NEEDS FIXING",
            panel.left + pad,
            panel.top + 116f * density,
            titlePaint
        )
        if (report.issues.isNotEmpty()) {
            textBold.textSize = 8.5f * density
            textBold.color = muted
            textBold.textAlign = Paint.Align.RIGHT
            val count = if (shown < report.issues.size) {
                "$shown of ${report.issues.size}"
            } else {
                "${report.issues.size}"
            }
            canvas.drawText(count, panel.right - pad, panel.top + 116f * density, textBold)
            textBold.textAlign = Paint.Align.LEFT
        }

        if (shown == 0) {
            if (available >= 24f * density) {
                val rowRect = RectF(
                    panel.left + pad,
                    rowsTop,
                    panel.right - pad,
                    rowsTop + min(34f * density, available)
                )
                fill.color = 0xFFDCEBD6.toInt()
                canvas.drawRoundRect(rowRect, 8f * density, 8f * density, fill)
                textBold.textSize = 9.5f * density
                textBold.color = 0xFF3E7A45.toInt()
                canvas.drawText(
                    "Nothing needs fixing - the city is running smoothly",
                    rowRect.left + 10f * density,
                    rowRect.centerY() + 3f * density,
                    textBold
                )
            }
        } else {
            val rowH = min(38f * density, available / shown)
            for (i in 0 until shown) {
                val top = rowsTop + i * rowH
                drawIssueRow(
                    canvas,
                    report.issues[i],
                    RectF(
                        panel.left + pad,
                        top,
                        panel.right - pad,
                        top + rowH - 4f * density
                    )
                )
            }
        }

        if (report.cheers.isNotEmpty()) {
            text.textSize = 8f * density
            text.color = 0xFF3E7A45.toInt()
            canvas.drawText(
                fitText("GOING WELL: " + report.cheers.joinToString(" · "), text, panel.width() - pad * 2),
                panel.left + pad,
                panel.bottom - 26f * density,
                text
            )
        }

        text.textSize = 8.5f * density
        text.color = muted
        text.textAlign = Paint.Align.CENTER
        canvas.drawText(
            "Adjust the tax rate with - and +  ·  tap outside to close",
            panel.centerX(),
            panel.bottom - 8f * density,
            text
        )
        text.textAlign = Paint.Align.LEFT
    }

    private fun drawTaxBand(canvas: Canvas, panel: RectF) {
        val band = taxBandRect(panel)
        fill.color = 0xFFF1EAD8.toInt()
        canvas.drawRoundRect(band, 9f * density, 9f * density, fill)
        stroke.color = ink
        stroke.strokeWidth = 1.4f * density
        canvas.drawRoundRect(band, 9f * density, 9f * density, stroke)

        text.textSize = 8f * density
        text.color = muted
        canvas.drawText("TAX RATE", band.left + 12f * density, band.top + 16f * density, text)

        textBold.textSize = 16f * density
        textBold.color = when {
            engine.taxRate >= 18 -> coral
            engine.taxRate <= 6 -> 0xFF3C97C7.toInt()
            else -> ink
        }
        canvas.drawText(
            "${engine.taxRate}%",
            band.left + 12f * density,
            band.top + 36f * density,
            textBold
        )

        val valueRight = band.left + 16f * density + textBold.measureText("${engine.taxRate}%")
        val room = taxDownRect.left - 10f * density - valueRight
        if (room > 90f * density) {
            text.textSize = 8f * density
            text.color = muted
            text.textAlign = Paint.Align.RIGHT
            canvas.drawText(
                "+$${engine.incomePerDay.roundToInt()}/day in tax  ·  " +
                    "-$${engine.expensesPerDay.roundToInt()}/day upkeep",
                taxDownRect.left - 10f * density,
                band.top + 32f * density,
                text
            )
            text.textAlign = Paint.Align.LEFT
        }

        drawStepperButton(canvas, taxDownRect, plus = false, enabled = engine.taxRate > Config.TAX_MIN)
        drawStepperButton(canvas, taxUpRect, plus = true, enabled = engine.taxRate < Config.TAX_MAX)
    }

    private fun drawStepperButton(canvas: Canvas, r: RectF, plus: Boolean, enabled: Boolean) {
        fill.color = if (enabled) paper else 0xFFE8E0CD.toInt()
        canvas.drawRoundRect(r, 8f * density, 8f * density, fill)
        stroke.color = if (enabled) ink else muted
        stroke.strokeWidth = 1.6f * density
        canvas.drawRoundRect(r, 8f * density, 8f * density, stroke)

        stroke.strokeWidth = 2.4f * density
        val cx = r.centerX()
        val cy = r.centerY()
        val s = 4.6f * density
        path.reset()
        path.moveTo(cx - s, cy)
        path.lineTo(cx + s, cy)
        if (plus) {
            path.moveTo(cx, cy - s)
            path.lineTo(cx, cy + s)
        }
        canvas.drawPath(path, stroke)
    }

    private fun drawIssueRow(canvas: Canvas, issue: CityIssue, r: RectF) {
        val accent = when (issue.level) {
            IssueLevel.CRITICAL -> coral
            IssueLevel.WARNING -> 0xFFD79A2B.toInt()
            IssueLevel.NOTICE -> 0xFF3C97C7.toInt()
        }
        fill.color = when (issue.level) {
            IssueLevel.CRITICAL -> 0xFFFBE3DF.toInt()
            IssueLevel.WARNING -> 0xFFFFF2CE.toInt()
            IssueLevel.NOTICE -> 0xFFF1EAD8.toInt()
        }
        canvas.drawRoundRect(r, 8f * density, 8f * density, fill)
        stroke.color = accent
        stroke.strokeWidth = 1.2f * density
        canvas.drawRoundRect(r, 8f * density, 8f * density, stroke)

        fill.color = accent
        canvas.drawCircle(r.left + 11f * density, r.top + 12f * density, 4f * density, fill)

        val textLeft = r.left + 23f * density
        textBold.textSize = 9.5f * density
        textBold.color = ink
        canvas.drawText(
            fitText(issue.title, textBold, r.width() - 33f * density),
            textLeft,
            r.top + 14f * density,
            textBold
        )
        text.textSize = 8f * density
        text.color = muted
        canvas.drawText(
            fitText(issue.detail, text, r.width() - 33f * density),
            textLeft,
            r.top + 26f * density,
            text
        )
    }

    // ------------------------------------------------------------------ info tool

    private fun inspectAt(x: Float, y: Float) {
        val cam = renderer.camera
        val col = cam.screenToCol(x, y)
        val row = cam.screenToRow(x, y)
        if (!engine.inBounds(col, row)) return
        val report = CityInspector.report(engine, col, row) ?: return
        inspectCol = col
        inspectRow = row
        inspectReport = report
        val now = System.currentTimeMillis()
        if (now - lastHaptic > 70) {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            lastHaptic = now
        }
        invalidate()
    }

    private fun clearInspect() {
        inspectCol = -1
        inspectRow = -1
        inspectReport = null
    }

    private fun drawInspectorMarker(canvas: Canvas) {
        if (inspectCol < 0 || inspectRow < 0) return
        val cam = renderer.camera
        val cx = cam.screenX(inspectCol.toFloat(), inspectRow.toFloat())
        val cy = cam.screenY(inspectCol.toFloat(), inspectRow.toFloat())
        val hw = cam.tileW * 0.5f * cam.zoom
        val hh = cam.tileH * 0.5f * cam.zoom
        path.reset()
        path.moveTo(cx, cy - hh)
        path.lineTo(cx + hw, cy)
        path.lineTo(cx, cy + hh)
        path.lineTo(cx - hw, cy)
        path.close()
        fill.color = 0x40FFD166
        canvas.drawPath(path, fill)
        stroke.color = ink
        stroke.strokeWidth = 5f * density
        canvas.drawPath(path, stroke)
        stroke.color = amber
        stroke.strokeWidth = 2.4f * density
        canvas.drawPath(path, stroke)
    }

    /** A persistent card next to the inspected cell: what it needs and why. */
    private fun drawInspectorCard(canvas: Canvas, w: Float, h: Float) {
        val report = inspectReport ?: return
        if (inspectCol < 0 || inspectRow < 0) return
        val cam = renderer.camera
        val anchorX = cam.screenX(inspectCol.toFloat(), inspectRow.toFloat())
        val anchorY = cam.screenY(inspectCol.toFloat(), inspectRow.toFloat())

        val pad = 10f * density
        val cardW = 238f * density
        val lineH = 13f * density
        val titleH = 19f * density
        val subtitleH = 12f * density
        val blockH = 13f * density
        val needs = report.needs.take(4)
        val facts = report.facts.take(4)

        text.textSize = 8.5f * density
        val adviceLines = wrap(report.advice, text, cardW - pad * 2f - 12f * density, 2)
        val adviceH = 16f * density + max(0, adviceLines.size - 1) * 11f * density
        val needsBlock = if (needs.isEmpty()) 0f else blockH + needs.size * lineH
        val factsBlock = if (facts.isEmpty()) 0f else 4f * density + blockH + facts.size * lineH
        val cardH = pad + titleH + subtitleH + needsBlock + factsBlock + 6f * density +
            adviceH + pad

        var left = anchorX + 22f * density
        var top = anchorY - cardH - 14f * density
        if (left + cardW > w - 8f * density) left = anchorX - cardW - 22f * density
        left = left.coerceIn(8f * density, max(8f * density, w - cardW - 8f * density))
        val minTop = hudPanelBottom + 6f * density
        val maxTop = toolbarTop - cardH - 6f * density
        top = if (maxTop < minTop) minTop else top.coerceIn(minTop, maxTop)

        val right = left + cardW
        val bottom = top + cardH
        fill.color = ink
        rect.set(left + 2f * density, top + 3f * density, right + 2f * density, bottom + 3f * density)
        canvas.drawRoundRect(rect, 12f * density, 12f * density, fill)
        fill.color = paper
        rect.set(left, top, right, bottom)
        canvas.drawRoundRect(rect, 12f * density, 12f * density, fill)
        stroke.color = ink
        stroke.strokeWidth = 2.4f * density
        canvas.drawRoundRect(rect, 12f * density, 12f * density, stroke)

        // pointer toward the cell
        val tipY = if (top > anchorY) top else bottom
        val dir = if (top > anchorY) -1f else 1f
        val tipX = anchorX.coerceIn(left + 14f * density, right - 14f * density)
        path.reset()
        path.moveTo(tipX, tipY)
        path.lineTo(tipX - 6f * density, tipY + dir * 8f * density)
        path.lineTo(tipX + 6f * density, tipY + dir * 8f * density)
        path.close()
        fill.color = paper
        canvas.drawPath(path, fill)
        stroke.color = ink
        stroke.strokeWidth = 2.4f * density
        canvas.drawPath(path, stroke)

        titlePaint.textSize = 12f * density
        titlePaint.color = ink
        canvas.drawText(
            fitText(report.title, titlePaint, cardW - pad * 2f),
            left + pad,
            top + pad + 10f * density,
            titlePaint
        )
        text.textSize = 7.5f * density
        text.color = muted
        canvas.drawText(report.subtitle, left + pad, top + pad + 10f * density + subtitleH, text)

        var y = top + pad + titleH + subtitleH
        if (needs.isNotEmpty()) {
            titlePaint.textSize = 8.5f * density
            titlePaint.color = muted
            canvas.drawText("NEEDS", left + pad, y, titlePaint)
            y += blockH
            for (need in needs) {
                val dotColor = when (need.state) {
                    NeedState.MET -> green
                    NeedState.MISSING -> coral
                    NeedState.OPTIONAL -> muted
                }
                fill.color = dotColor
                canvas.drawCircle(left + pad + 3f * density, y - 3f * density, 3.4f * density, fill)
                text.textSize = 9f * density
                text.color = ink
                canvas.drawText(
                    fitText(need.label, text, cardW - pad * 2f - 74f * density),
                    left + pad + 11f * density,
                    y,
                    text
                )
                textBold.textSize = 8.5f * density
                textBold.color = dotColor
                textBold.textAlign = Paint.Align.RIGHT
                canvas.drawText(need.detail, right - pad, y, textBold)
                textBold.textAlign = Paint.Align.LEFT
                y += lineH
            }
        }

        if (facts.isNotEmpty()) {
            y += 4f * density
            titlePaint.textSize = 8.5f * density
            titlePaint.color = muted
            canvas.drawText("DETAILS", left + pad, y, titlePaint)
            y += blockH
            for (fact in facts) {
                text.textSize = 8.5f * density
                text.color = muted
                canvas.drawText(
                    fitText(fact.label, text, cardW - pad * 2f - 96f * density),
                    left + pad,
                    y,
                    text
                )
                textBold.textSize = 8.5f * density
                textBold.color = ink
                textBold.textAlign = Paint.Align.RIGHT
                canvas.drawText(fact.value, right - pad, y, textBold)
                textBold.textAlign = Paint.Align.LEFT
                y += lineH
            }
        }

        y += 6f * density
        fill.color = if (report.broken) 0xFFFBE3DF.toInt() else 0xFFDCEBD6.toInt()
        rect.set(left + pad, y, right - pad, y + adviceH)
        canvas.drawRoundRect(rect, 8f * density, 8f * density, fill)
        stroke.color = if (report.broken) coral else green
        stroke.strokeWidth = 1.2f * density
        canvas.drawRoundRect(rect, 8f * density, 8f * density, stroke)

        text.textSize = 8.5f * density
        text.color = ink
        var ty = y + 12f * density
        for (line in adviceLines) {
            canvas.drawText(line, left + pad + 6f * density, ty, text)
            ty += 11f * density
        }
    }

    /** Greedy word wrap for the advice line, keeping at most [maxLines] lines. */
    private fun wrap(value: String, paint: Paint, maxWidth: Float, maxLines: Int): List<String> {
        if (maxLines <= 1) return listOf(fitText(value, paint, maxWidth))
        val words = value.split(' ')
        val lines = ArrayList<String>()
        var current = ""
        for (i in words.indices) {
            val candidate = if (current.isEmpty()) words[i] else "$current ${words[i]}"
            if (paint.measureText(candidate) <= maxWidth) {
                current = candidate
            } else {
                if (lines.size == maxLines - 1) {
                    current = if (current.isEmpty()) words[i] else "$current ${words[i]}"
                    for (j in i + 1 until words.size) current += " ${words[j]}"
                    break
                }
                if (current.isNotEmpty()) lines.add(current)
                current = words[i]
            }
        }
        if (current.isNotEmpty()) lines.add(current)
        if (lines.size > maxLines) return lines.take(maxLines)
        if (lines.isNotEmpty()) lines[lines.size - 1] = fitText(lines[lines.size - 1], paint, maxWidth)
        return lines
    }

    // ------------------------------------------------------------------ top bar

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

        // goals button
        drawSmallButton(canvas, goalsRect)
        val done = engine.completedMilestones.size
        textBold.textSize = 8.5f * density
        textBold.color = ink
        textBold.textAlign = Paint.Align.CENTER
        canvas.drawText("$done/${Milestone.values().size}", goalsRect.centerX(), goalsRect.centerY() + 3f * density, textBold)
        textBold.textAlign = Paint.Align.LEFT

        // sound toggle
        drawSmallButton(canvas, soundRect)
        drawSoundIcon(canvas, soundRect)

        // undo the last tap or drag
        drawSmallButton(canvas, undoRect)
        textBold.textSize = 7.5f * density
        textBold.color = if (engine.canUndo) ink else muted
        textBold.textAlign = Paint.Align.CENTER
        canvas.drawText("UNDO", undoRect.centerX(), undoRect.centerY() + 2.5f * density, textBold)
        textBold.textAlign = Paint.Align.LEFT

        // advisor
        drawSmallButton(canvas, advisorRect)
        drawAdvisorIcon(canvas, advisorRect)
        drawAdvisorBadge(canvas, advisorRect)

        // funds
        val fundsY = top + 52f * density
        textBold.textSize = 17f * density
        textBold.color = if (engine.funds < 0) coral else ink
        canvas.drawText(money(engine.funds), margin + pad, fundsY, textBold)

        // a quiet line under the treasury: the date and what citizens are taxed
        val colRight = w - margin - pad
        var labelX = margin + pad
        val labelLimit = colRight - 146f * density
        val labels = arrayOf(
            "TREASURY" to muted,
            engine.dateLabel to muted,
            "TAX ${engine.taxRate}%" to when {
                engine.taxRate >= 18 -> coral
                engine.taxRate <= 6 -> 0xFF3C97C7.toInt()
                else -> ink
            }
        )
        text.textSize = 8f * density
        for (i in labels.indices) {
            val piece = (if (i == 0) "" else "  ·  ") + labels[i].first
            if (labelX + text.measureText(piece) > labelLimit) break
            text.color = labels[i].second
            canvas.drawText(piece, labelX, fundsY + 13f * density, text)
            labelX += text.measureText(piece)
        }
        text.color = muted

        // population / jobs / happiness
        drawStat(canvas, colRight, top + 38f * density, "POP", engine.population.toString(), ink)
        drawStat(canvas, colRight - 52f * density, top + 38f * density, "JOBS", engine.jobs.toString(), ink)
        drawHappiness(canvas, colRight - 104f * density, top + 38f * density)

        // demand strip
        drawDemand(canvas, margin + pad, bottom - 11f * density, w - margin - pad, 9f * density)
    }

    private fun drawAdvisorIcon(canvas: Canvas, r: RectF) {
        val cx = r.centerX()
        val cy = r.centerY() - 0.5f * density
        val s = 4.8f * density
        path.reset()
        path.moveTo(cx - s * 1.15f, cy - s)
        path.lineTo(cx + s * 1.15f, cy - s)
        path.lineTo(cx + s * 1.15f, cy + s * 0.35f)
        path.lineTo(cx - s * 0.15f, cy + s * 0.35f)
        path.lineTo(cx - s * 0.62f, cy + s * 1.05f)
        path.lineTo(cx - s * 0.62f, cy + s * 0.35f)
        path.lineTo(cx - s * 1.15f, cy + s * 0.35f)
        path.close()
        fill.color = amber
        canvas.drawPath(path, fill)
        stroke.color = ink
        stroke.strokeWidth = 1.5f * density
        canvas.drawPath(path, stroke)

        fill.color = ink
        canvas.drawCircle(cx - s * 0.42f, cy - s * 0.34f, 0.85f * density, fill)
        canvas.drawCircle(cx + s * 0.42f, cy - s * 0.34f, 0.85f * density, fill)
        stroke.strokeWidth = 1f * density
        path.reset()
        path.moveTo(cx - s * 0.36f, cy + s * 0.02f)
        path.quadTo(cx, cy + s * 0.34f, cx + s * 0.36f, cy + s * 0.02f)
        canvas.drawPath(path, stroke)
    }

    /** A small count of open issues, so the mayor knows when to look. */
    private fun drawAdvisorBadge(canvas: Canvas, r: RectF) {
        val count = advisorReport.issues.size
        if (count == 0) return
        val cx = r.right - 2f * density
        val cy = r.top + 3f * density
        fill.color = coral
        canvas.drawCircle(cx, cy, 6.5f * density, fill)
        stroke.color = ink
        stroke.strokeWidth = 1.2f * density
        canvas.drawCircle(cx, cy, 6.5f * density, stroke)
        textBold.textSize = 7.5f * density
        textBold.color = paper
        textBold.textAlign = Paint.Align.CENTER
        canvas.drawText(count.toString(), cx, cy + 2.6f * density, textBold)
        textBold.textAlign = Paint.Align.LEFT
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
        bannerRect.set(left, top, right, top + h)
        drawPanel(canvas, left, top, right, top + h, paper, 2f * density)
        fill.color = bannerColor
        rect.set(left + 8f * density, top + 8f * density, left + 14f * density, top + 18f * density)
        canvas.drawRoundRect(rect, 2f * density, 2f * density, fill)

        val jump = eventCol >= 0 && eventRow >= 0
        textBold.textSize = 9.5f * density
        textBold.color = ink
        textBold.textAlign = Paint.Align.LEFT
        val available = right - left - if (jump) 56f * density else 34f * density
        canvas.drawText(
            fitText(bannerText, textBold, available),
            left + 20f * density,
            top + h * 0.63f,
            textBold
        )

        if (jump) {
            textBold.textSize = 8f * density
            textBold.color = bannerColor
            textBold.textAlign = Paint.Align.RIGHT
            canvas.drawText("VIEW >", right - 9f * density, top + h * 0.63f, textBold)
            textBold.textAlign = Paint.Align.LEFT
        }
    }

    /** A bobbing arrow over the cell behind the newest event banner. */
    private fun drawEventFocus(canvas: Canvas) {
        if (eventHighlightTimer <= 0f || eventCol < 0 || eventRow < 0) return
        val cam = renderer.camera
        val cx = cam.screenX(eventCol.toFloat(), eventRow.toFloat())
        val cy = cam.screenY(eventCol.toFloat(), eventRow.toFloat())
        val hw = cam.tileW * 0.5f * cam.zoom
        val hh = cam.tileH * 0.5f * cam.zoom
        val fade = min(1f, eventHighlightTimer / 0.6f)
        val alpha = (220 * fade).toInt()
        val pulse = 1f + 0.12f * sin(time * 5f)

        stroke.color = withAlpha(amber, (150 * fade).toInt())
        stroke.strokeWidth = 2f * density
        rect.set(cx - hw * pulse, cy - hh * pulse, cx + hw * pulse, cy + hh * pulse)
        canvas.drawOval(rect, stroke)

        val bob = sin(time * 5f) * 3f * density
        val s = 8f * density
        val tipY = cy - hh - 7f * density + bob
        val topY = tipY - s * 1.7f
        path.reset()
        path.moveTo(cx, tipY)
        path.lineTo(cx - s * 0.85f, topY)
        path.lineTo(cx + s * 0.85f, topY)
        path.close()
        fill.color = withAlpha(amber, alpha)
        canvas.drawPath(path, fill)
        stroke.color = withAlpha(ink, alpha)
        stroke.strokeWidth = 2f * density
        canvas.drawPath(path, stroke)
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    private fun drawTooltip(canvas: Canvas, w: Float, h: Float) {
        val tile = engine.tile(tooltipCol, tooltipRow) ?: return
        val counts = engine.neighborCounts(tooltipCol, tooltipRow)
        val income = engine.incomePerTick(tile)

        // size the card to its contents
        val pad = 10f * density
        val lineH = 13f * density
        val titleH = 20f * density
        val rows = ArrayList<Pair<String, String>>(6)
        rows.add("Income" to incomeLabel(income))
        if (tile.zone.isZone) {
            rows.add("Level" to if (tile.level == 0) "vacant" else "L${tile.level} of ${Config.MAX_LEVEL}")
            rows.add("Inside" to "${tile.population + tile.jobs}")
            rows.add("Road / power / water" to "${tick(tile.roadAccess)}  ${tick(tile.powered)}  ${tick(tile.watered)}")
            rows.add("Transit" to tick(tile.transitAccess))
        }
        if (tile.subway) {
            rows.add("Subway" to if (tile.subwayConnected) "tunnel" else "stub")
        }
        rows.add("Neighbours" to "R${counts.developed} C${counts.zoned} B${counts.built} / 8")

        val cardW = 176f * density
        val cardH = titleH + rows.size * lineH + pad * 1.6f

        // anchor the card to the tile's projected position
        val anchorX = renderer.camera.screenX(tooltipCol.toFloat(), tooltipRow.toFloat())
        val anchorY = renderer.camera.screenY(tooltipCol.toFloat(), tooltipRow.toFloat())
        var left = anchorX + 18f * density
        var top = anchorY - cardH - 12f * density
        if (left + cardW > w - 8f * density) left = anchorX - cardW - 18f * density
        left = left.coerceIn(8f * density, w - cardW - 8f * density)
        top = top.coerceIn(hudPanelBottom + 6f * density, toolbarTop - cardH - 6f * density)

        val right = left + cardW
        val bottom = top + cardH
        fill.color = ink
        rect.set(left + 2f * density, top + 3f * density, right + 2f * density, bottom + 3f * density)
        canvas.drawRoundRect(rect, 12f * density, 12f * density, fill)
        fill.color = paper
        rect.set(left, top, right, bottom)
        canvas.drawRoundRect(rect, 12f * density, 12f * density, fill)
        stroke.color = amber
        stroke.strokeWidth = 2.4f * density
        canvas.drawRoundRect(rect, 12f * density, 12f * density, stroke)

        // small pointer triangle toward the tile
        path.reset()
        val tipY = if (top > anchorY) top else bottom
        val dir = if (top > anchorY) -1f else 1f
        val tipX = anchorX.coerceIn(left + 12f * density, right - 12f * density)
        path.moveTo(tipX, tipY)
        path.lineTo(tipX - 6f * density, tipY + dir * 8f * density)
        path.lineTo(tipX + 6f * density, tipY + dir * 8f * density)
        path.close()
        fill.color = paper
        canvas.drawPath(path, fill)
        stroke.color = amber
        stroke.strokeWidth = 2.4f * density
        canvas.drawPath(path, stroke)

        titlePaint.textSize = 12f * density
        titlePaint.color = ink
        canvas.drawText(engine.tileState(tile), left + pad, top + titleH, titlePaint)

        text.textSize = 9f * density
        var y = top + titleH + lineH * 0.6f
        for ((label, value) in rows) {
            text.color = muted
            text.textAlign = Paint.Align.LEFT
            canvas.drawText(label, left + pad, y, text)
            textBold.textSize = 9f * density
            textBold.color = when {
                label == "Income" && income < 0 -> coral
                label == "Income" -> green
                else -> ink
            }
            textBold.textAlign = Paint.Align.RIGHT
            canvas.drawText(value, right - pad, y, textBold)
            y += lineH
        }
        textBold.textAlign = Paint.Align.LEFT
        text.textAlign = Paint.Align.LEFT
    }

    private fun tick(value: Boolean): String = if (value) "yes" else "NO"

    private fun incomeLabel(income: Float): String {
        val cents = (income * 100).roundToInt()
        val sign = if (cents < 0) "-" else "+"
        return "$sign$${abs(cents) / 100}.${(abs(cents) % 100).toString().padStart(2, '0')}/day"
    }

    private fun drawToolbar(canvas: Canvas, w: Float, h: Float) {
        val top = toolbarTop
        fill.color = cream
        rect.set(0f, top, w, h)
        canvas.drawRoundRect(rect, 16f * density, 16f * density, fill)
        stroke.color = ink
        stroke.strokeWidth = 3f * density
        canvas.drawRoundRect(rect, 16f * density, 16f * density, stroke)

        // hint, with the pause chip at the end of the same row
        val paused = speed == 0
        val chipW = 62f * density
        textBold.textSize = 10f * density
        textBold.color = ink
        textBold.textAlign = Paint.Align.LEFT
        val hintColor = if (selected == Tool.BULLDOZE) coral else ink
        textBold.color = hintColor
        val hintRoom = w - 28f * density - if (paused) chipW + 10f * density else 0f
        canvas.drawText(
            fitText(hintText(), textBold, hintRoom),
            14f * density,
            top + 16f * density,
            textBold
        )
        textBold.color = ink

        if (paused) {
            rect.set(
                w - 14f * density - chipW,
                top + 2f * density,
                w - 14f * density,
                top + 20f * density
            )
            fill.color = amber
            canvas.drawRoundRect(rect, 7f * density, 7f * density, fill)
            stroke.color = ink
            stroke.strokeWidth = 1.3f * density
            canvas.drawRoundRect(rect, 7f * density, 7f * density, stroke)
            textBold.textSize = 8.5f * density
            textBold.textAlign = Paint.Align.CENTER
            canvas.drawText("PAUSED", rect.centerX(), rect.centerY() + 3f * density, textBold)
            textBold.textAlign = Paint.Align.LEFT
        }

        for (i in Tool.values().indices) {
            drawToolButton(canvas, Tool.values()[i], toolRects[i])
        }
    }

    private fun drawToolButton(canvas: Canvas, tool: Tool, r: RectF) {
        val selectedNow = tool == selected
        val unlocked = engine.isUnlocked(tool)
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
            !unlocked -> 0xFFD8D2C4.toInt()
            !affordable -> 0xFFE8E0CD.toInt()
            else -> paper
        }
        canvas.drawRoundRect(r, radius, radius, fill)
        stroke.color = ink
        stroke.strokeWidth = if (selectedNow) 2.6f * density else 1.6f * density
        canvas.drawRoundRect(r, radius, radius, stroke)

        val iconColor = if (!unlocked || !affordable) muted else ink
        val cx = r.centerX()
        val cy = r.top + 16f * density
        drawToolIcon(canvas, tool, cx, cy, 9.5f * density, iconColor)

        textBold.textSize = 8.2f * density
        textBold.color = if (!unlocked || !affordable) muted else ink
        textBold.textAlign = Paint.Align.CENTER
        val label = when {
            !unlocked -> "LOCKED"
            cost > 0 -> "${tool.label} ${costLabel(cost)}"
            else -> tool.label
        }
        canvas.drawText(
            fitText(label, textBold, r.width() - 8f * density),
            r.centerX(),
            r.bottom - 6f * density,
            textBold
        )
        textBold.textAlign = Paint.Align.LEFT

        if (!unlocked) {
            stroke.color = ink
            stroke.strokeWidth = 1.7f * density
            padlock(canvas, cx, cy, 7.5f * density)
        }
    }

    private fun padlock(canvas: Canvas, cx: Float, cy: Float, s: Float) {
        stroke.color = muted
        stroke.strokeWidth = 2f * density
        path.reset()
        path.moveTo(cx - s * 0.45f, cy - s * 0.1f)
        path.lineTo(cx - s * 0.45f, cy - s * 0.55f)
        path.quadTo(cx - s * 0.45f, cy - s * 1.05f, cx, cy - s * 1.05f)
        path.quadTo(cx + s * 0.45f, cy - s * 1.05f, cx + s * 0.45f, cy - s * 0.55f)
        path.lineTo(cx + s * 0.45f, cy - s * 0.1f)
        canvas.drawPath(path, stroke)
        fill.color = 0xFFB7AE9B.toInt()
        rect.set(cx - s * 0.7f, cy - s * 0.2f, cx + s * 0.7f, cy + s * 0.7f)
        canvas.drawRoundRect(rect, 2f * density, 2f * density, fill)
        stroke.color = ink
        stroke.strokeWidth = 1.3f * density
        canvas.drawRoundRect(rect, 2f * density, 2f * density, stroke)
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
            Tool.SUBWAY -> {
                // tunnel mouth with the line running off to the side
                dash.color = Config.TUNNEL
                dash.strokeWidth = 1.9f * density
                path.reset()
                path.moveTo(cx - s * 0.95f, cy + s * 0.6f)
                path.lineTo(cx - s * 0.5f, cy + s * 0.38f)
                path.moveTo(cx + s * 0.5f, cy - s * 0.38f)
                path.lineTo(cx + s * 0.95f, cy - s * 0.6f)
                canvas.drawPath(path, dash)

                stroke.color = color
                stroke.strokeWidth = 1.8f * density
                fill.color = 0xFF233A42.toInt()
                rect.set(cx - s * 0.52f, cy - s * 0.76f, cx + s * 0.52f, cy + s * 0.5f)
                canvas.drawRoundRect(rect, s * 0.5f, s * 0.5f, fill)
                canvas.drawRoundRect(rect, s * 0.5f, s * 0.5f, stroke)
                stroke.color = Config.TUNNEL
                stroke.strokeWidth = 2f * density
                rect.set(cx - s * 0.32f, cy - s * 0.5f, cx + s * 0.32f, cy + s * 0.46f)
                canvas.drawRoundRect(rect, s * 0.32f, s * 0.32f, stroke)
            }
            Tool.SUBWAY_STATION -> {
                // stair entrance under a canopy, with the round platform sign
                stroke.color = color
                stroke.strokeWidth = 1.5f * density
                fill.color = 0xFF1B2C33.toInt()
                rect.set(cx - s * 0.55f, cy - s * 0.05f, cx + s * 0.55f, cy + s * 0.55f)
                canvas.drawRect(rect, fill)
                canvas.drawRect(rect, stroke)

                path.reset()
                path.moveTo(cx - s * 0.9f, cy - s * 0.05f)
                path.lineTo(cx, cy - s * 0.42f)
                path.lineTo(cx + s * 0.9f, cy - s * 0.05f)
                path.lineTo(cx, cy + s * 0.28f)
                path.close()
                fill.color = 0xFF9FD8D6.toInt()
                canvas.drawPath(path, fill)
                stroke.color = Config.TUNNEL
                canvas.drawPath(path, stroke)

                stroke.strokeWidth = 1.2f * density
                path.reset()
                path.moveTo(cx + s * 0.78f, cy - s * 0.05f)
                path.lineTo(cx + s * 0.78f, cy - s * 0.6f)
                canvas.drawPath(path, stroke)
                fill.color = Config.TUNNEL
                canvas.drawCircle(cx + s * 0.78f, cy - s * 0.72f, s * 0.3f, fill)
                stroke.color = color
                stroke.strokeWidth = 1.2f * density
                canvas.drawCircle(cx + s * 0.78f, cy - s * 0.72f, s * 0.3f, stroke)
                textCenter.textSize = 6.2f * density
                textCenter.color = paper
                canvas.drawText("U", cx + s * 0.78f, cy - s * 0.62f, textCenter)
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
            Tool.INFO -> {
                stroke.color = color
                stroke.strokeWidth = 1.7f * density
                fill.color = paper
                canvas.drawCircle(cx, cy, s * 0.88f, fill)
                canvas.drawCircle(cx, cy, s * 0.88f, stroke)
                fill.color = color
                canvas.drawCircle(cx, cy - s * 0.4f, s * 0.15f, fill)
                rect.set(cx - s * 0.14f, cy - s * 0.12f, cx + s * 0.14f, cy + s * 0.52f)
                canvas.drawRoundRect(rect, s * 0.12f, s * 0.12f, fill)
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

    private fun drawSoundIcon(canvas: Canvas, r: RectF) {
        val on = sound.enabled
        val cx = r.centerX()
        val cy = r.centerY()
        val s = 4.2f * density
        fill.color = if (on) ink else muted
        // speaker body
        path.reset()
        path.moveTo(cx - s * 0.9f, cy - s * 0.32f)
        path.lineTo(cx - s * 0.4f, cy - s * 0.32f)
        path.lineTo(cx + s * 0.15f, cy - s * 0.85f)
        path.lineTo(cx + s * 0.15f, cy + s * 0.85f)
        path.lineTo(cx - s * 0.4f, cy + s * 0.32f)
        path.lineTo(cx - s * 0.9f, cy + s * 0.32f)
        path.close()
        canvas.drawPath(path, fill)
        if (on) {
            stroke.color = ink
            stroke.strokeWidth = 1.4f * density
            path.reset()
            path.moveTo(cx + s * 0.4f, cy - s * 0.5f)
            path.quadTo(cx + s * 0.95f, cy, cx + s * 0.4f, cy + s * 0.5f)
            canvas.drawPath(path, stroke)
            path.reset()
            path.moveTo(cx + s * 0.68f, cy - s * 0.85f)
            path.quadTo(cx + s * 1.35f, cy, cx + s * 0.68f, cy + s * 0.85f)
            canvas.drawPath(path, stroke)
        } else {
            stroke.color = coral
            stroke.strokeWidth = 1.6f * density
            path.reset()
            path.moveTo(cx + s * 0.45f, cy - s * 0.5f)
            path.lineTo(cx + s * 1.15f, cy + s * 0.5f)
            path.moveTo(cx + s * 1.15f, cy - s * 0.5f)
            path.lineTo(cx + s * 0.45f, cy + s * 0.5f)
            canvas.drawPath(path, stroke)
        }
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
        if (engine.subwayCount > 0 && engine.subwayStationCount == 0) {
            return "Tunnels need a subway station - the advisor can help"
        }
        if (engine.population > 60 && engine.taxRate >= 18) {
            return "Citizens resent the ${engine.taxRate}% tax rate - try lowering it"
        }
        if (engine.population > 60 && engine.netPerDay < 0) {
            return "Upkeep beats income - raise taxes in the ADVISOR panel"
        }
        val goal = engine.nextMilestone()
        if (goal != null && engine.completedMilestones.isNotEmpty()) {
            val value = engine.milestoneValue(goal).coerceAtMost(engine.milestoneTarget(goal))
            if (value >= engine.milestoneTarget(goal) - 1) return "GOALS: ${goal.title} is nearly done"
        }
        return when (selected) {
            Tool.PAN -> "Drag to pan - pinch to zoom"
            Tool.BULLDOZE -> "Tap a tile to clear it (and its main) - 25% refund"
            Tool.PLANT -> "PLANT $2500 - powers connected roads"
            Tool.ROAD -> "ROAD $15 - bridges cost $90 over water"
            Tool.HIGHWAY -> "HWY $120 - four lanes: faster traffic, more trade; $300 over water"
            Tool.POWERLINE -> "WIRE $10 - buried cable carries power across open ground"
            Tool.RAIL -> "RAIL $40 - lines boost the lots beside them"
            Tool.SUBWAY -> "SUBWAY $55 - tunnels run under anything; $160 under the river"
            Tool.SUBWAY_STATION -> "SUB STN $650 - place it beside a subway tunnel to bring riders"
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
            Tool.INFO -> "INFO - tap any cell to see what it needs"
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
                if (advisorOpen) {
                    if (taxDownRect.contains(x, y)) {
                        adjustTax(-1)
                        performClick()
                        return true
                    }
                    if (taxUpRect.contains(x, y)) {
                        adjustTax(1)
                        performClick()
                        return true
                    }
                    advisorOpen = false
                    performClick()
                    return true
                }
                if (goalsOpen) {
                    goalsOpen = false
                    performClick()
                    return true
                }
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
                    if (goalsRect.contains(x, y)) {
                        goalsOpen = true
                        advisorOpen = false
                        tooltipActive = false
                        performClick()
                        return true
                    }
                    if (advisorRect.contains(x, y)) {
                        advisorReport = CityAdvisor.report(engine)
                        advisorOpen = true
                        goalsOpen = false
                        tooltipActive = false
                        performClick()
                        invalidate()
                        return true
                    }
                    if (soundRect.contains(x, y)) {
                        toggleSound()
                        performClick()
                        return true
                    }
                    if (undoRect.contains(x, y)) {
                        undoLastBuild()
                        performClick()
                        return true
                    }
                    return true
                }
                // the event banner is a shortcut to whatever it is about
                if (bannerTimer > 0f && bannerRect.contains(x, y)) {
                    if (eventCol >= 0 && eventRow >= 0) focusEventCell()
                    performClick()
                    return true
                }
                if (y >= toolbarTop) {
                    for (i in toolRects.indices) {
                        if (toolRects[i].contains(x, y)) {
                            val tool = Tool.values()[i]
                            if (!engine.isUnlocked(tool)) {
                                banner("${tool.label} unlocks later - see GOALS", coral)
                                performClick()
                                return true
                            }
                            selected = tool
                            if (tool != Tool.INFO) clearInspect()
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
                tooltipActive = false
                panTargetX = null
                panTargetY = null
                mode = when (selected) {
                    Tool.PAN -> MODE_PAN
                    Tool.INFO -> MODE_INFO
                    else -> MODE_PAINT
                }
                updateHover(x, y)
                if (mode == MODE_PAINT) {
                    engine.beginAction()
                    postDelayed(longPressRunnable, LONG_PRESS_MS)
                }
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2) {
                    if (mode == MODE_PAINT) engine.endAction()
                    panTargetX = null
                    panTargetY = null
                    mode = MODE_PINCH
                    removeCallbacks(longPressRunnable)
                    tooltipActive = false
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
                } else if (mode == MODE_PAN || mode == MODE_INFO) {
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
                        if (moved > touchSlop) {
                            removeCallbacks(longPressRunnable)
                            tooltipActive = false
                        }
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
                removeCallbacks(longPressRunnable)
                if (tooltipActive) {
                    tooltipActive = false
                } else if (mode == MODE_PAINT && (!didPaint || selected.singlePlacement)) {
                    updateHover(x, y)
                    paintSegment(x, y, x, y, force = true)
                } else if (mode == MODE_PAN) {
                    updateHover(x, y)
                } else if (mode == MODE_INFO) {
                    if (hypot(x - downX, y - downY) <= touchSlop) inspectAt(x, y)
                    updateHover(x, y)
                }
                if (mode == MODE_PAINT) engine.endAction()
                mode = MODE_NONE
                activePointer = -1
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                if (mode == MODE_PAINT) engine.endAction()
                tooltipActive = false
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

    private fun adjustTax(delta: Int) {
        if (!engine.setTaxRate(engine.taxRate + delta)) return
        advisorReport = CityAdvisor.report(engine)
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        saveCity()
        invalidate()
    }

    private fun toggleSound() {
        sound.setSoundEnabled(!sound.enabled)
        prefs.edit().putBoolean(SOUND_KEY, sound.enabled).apply()
        banner(if (sound.enabled) "Sound on" else "Sound off", if (sound.enabled) mint else muted)
        invalidate()
    }

    /** Pans to the cell behind the newest event banner and points an arrow at it. */
    private fun focusEventCell() {
        if (!engine.inBounds(eventCol, eventRow)) return
        val cam = renderer.camera
        val maxX = (engine.cols + engine.rows) * cam.tileW * 0.5f * cam.zoom * 0.7f
        val maxY = (engine.cols + engine.rows) * cam.tileH * 0.5f * cam.zoom * 0.7f
        panTargetX = (-(eventCol - eventRow) * cam.tileW * 0.5f * cam.zoom)
            .coerceIn(-maxX, maxX)
        panTargetY = (-(eventCol + eventRow) * cam.tileH * 0.5f * cam.zoom)
            .coerceIn(-maxY, maxY)
        eventHighlightTimer = 6f
        lastPaintCol = -1
        lastPaintRow = -1
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        invalidate()
    }

    /** Puts back the tiles of the last tap or drag, and the money it cost. */
    private fun undoLastBuild() {
        val undone = engine.undo()
        if (undone == null) {
            banner("Nothing to undo", muted)
            invalidate()
            return
        }
        lastPaintCol = -1
        lastPaintRow = -1
        advisorReport = CityAdvisor.report(engine)
        if (inspectReport != null) {
            inspectReport = CityInspector.report(engine, inspectCol, inspectRow)
        }
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        banner(
            if (undone == "bulldoze") "Undo: bulldoze reverted" else "Undo: build reverted",
            mint
        )
        saveCity()
        invalidate()
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
        val beforeSubway = tile.subway
        val result = engine.build(selected, col, row)
        engine.drainEvents()
        lastPaintCol = col
        lastPaintRow = row

        if (result != BuildResult.OK) {
            if (force || bannerTimer <= 0.8f) banner(result.message, coral)
            return
        }
        val after = engine.tile(col, row)
        if (after?.zone != beforeZone || after.pipe != beforePipe ||
            after.powerline != beforeWire || after.subway != beforeSubway
        ) {
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
                selected == Tool.SUBWAY && engine.subwayCount == 1 ->
                    banner("Tunnel dug - link two or more tiles to make a line", mint)
                selected == Tool.SUBWAY_STATION && engine.subwayStationCount == 1 ->
                    banner("Subway station open - nearby lots gain transit access", mint)
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
                clearInspect()
                hoverCol = engine.cols / 2
                hoverRow = engine.rows / 2
                dayTimer = 0f
                goalsOpen = false
                advisorOpen = false
                advisorReport = CityAdvisor.report(engine)
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
        private const val SOUND_KEY = "sound_on"
        private const val LONG_PRESS_MS = 400L
        private const val TOOL_COLUMNS = 6
        private const val MODE_NONE = 0
        private const val MODE_PAINT = 1
        private const val MODE_PAN = 2
        private const val MODE_PINCH = 3
        private const val MODE_INFO = 4
    }
}
