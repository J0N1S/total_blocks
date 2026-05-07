package com.fgs.totalblocks

import android.animation.*
import android.content.Context
import android.graphics.*
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * GameView: A Block Puzzle Game with a modern Material Design 3 aesthetic.
 * This file contains the full game logic, UI rendering, and piece management.
 */
class GameView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        const val BOARD_SIZE    = 8
        const val PIECE_SLOTS   = 3
        const val RING_INTERVAL = 500
    }

    // ── Data Models ────────────────────────────────────────────────────
    data class Block(val row: Int, val col: Int)
    data class Piece(val blocks: List<Block>, val color: Int) {
        val width: Int  by lazy { blocks.maxOf { it.col } - blocks.minOf { it.col } + 1 }
        val height: Int by lazy { blocks.maxOf { it.row } - blocks.minOf { it.row } + 1 }
    }

    object PieceFactory {
        private val templates = listOf(
            listOf(Block(0,0)), // 1x1
            listOf(Block(0,0), Block(0,1)), // 2x1
            listOf(Block(0,0), Block(1,0)), // 1x2
            listOf(Block(0,0), Block(0,1), Block(0,2)), // 3x1
            listOf(Block(0,0), Block(1,0), Block(2,0)), // 1x3
            listOf(Block(0,0), Block(0,1), Block(1,0), Block(1,1)), // 2x2
            listOf(Block(0,0), Block(1,0), Block(1,1)), // L-small
            listOf(Block(0,0), Block(1,0), Block(2,0), Block(2,1)), // L-3x2
            listOf(Block(0,0), Block(0,1), Block(0,2), Block(1,1)), // T-shape
            listOf(Block(0,0), Block(1,0), Block(1,1), Block(2,1))  // Z-shape
        )
        fun random() = Piece(templates.random(), 0)
    }

    // ── Board & Pieces ─────────────────────────────────────────────────
    private val board        = Array(BOARD_SIZE) { IntArray(BOARD_SIZE) { 0 } }
    private val currentPieces = arrayOfNulls<Piece>(PIECE_SLOTS)

    // ── Score ──────────────────────────────────────────────────────────
    private var score    = 0
    private var bestScore = 0

    // ── Combo ──────────────────────────────────────────────────────────
    private var comboCount = 0
    private var comboLevel = 0
    private var comboAlpha = 0f // Now used for fade-in, but we keep it visible if level > 0
    private var comboAnim: ValueAnimator? = null
    private var movesSinceClear = 0

    // ── Ring ───────────────────────────────────────────────────────────
    private var ringFill       = 0f
    private var prevRingLevel  = 0
    private var ringFlash      = 0f
    private var ringBonus      = false

    // ── Abilities ──────────────────────────────────────────────────────
    private var gravityAvail   = false; private var gravityUsed   = false
    private var tripleAvail    = false; private var tripleUsed    = false
    private var clearAllAvail  = false; private var clearAllUsed  = false
    private val abilityRects   = Array(3) { RectF() }

    // ── Drag ───────────────────────────────────────────────────────────
    private var draggingIdx = -1
    private var dragX = 0f; private var dragY = 0f
    private var ghostRow = -1; private var ghostCol = -1
    private var canPlace = false

    // ── Layout ─────────────────────────────────────────────────────────
    private var boardLeft = 0f; private var boardTop  = 0f
    private var cellSize  = 0f; private var previewCS = 0f
    private var pieceAreaCY = 0f
    private var undoRect = RectF(); private var menuRect = RectF()

    // ── Undo ───────────────────────────────────────────────────────────
    private var prevBoard:  Array<IntArray>? = null
    private var prevScore   = 0
    private var prevPieces: Array<Piece?>   = arrayOfNulls(PIECE_SLOTS)

    // ── Clear anim ─────────────────────────────────────────────────────
    private val clearRows = mutableSetOf<Int>()
    private val clearCols = mutableSetOf<Int>()
    private var clearFlash    = 0f
    private var clearRunning  = false

    // ── Game state ─────────────────────────────────────────────────────
    private var isGameOver = false

    // ── Vibrator ───────────────────────────────────────────────────────
    @Suppress("DEPRECATION")
    private val vibrator: Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager).defaultVibrator
        else context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }.getOrNull()

    // ── Colors (Based on User's TailWind Palette) ──────────────────────
    private val COLOR_PRIMARY     = 0xFF215FA6.toInt()
    private val COLOR_SECONDARY   = 0xFF336857.toInt() // Green for ring
    private val COLOR_TERTIARY    = 0xFF884D54.toInt()
    private val COLOR_BACKGROUND  = 0xFFF8F9FA.toInt()
    private val COLOR_SURFACE_VAR = 0xFFE1E3E4.toInt()
    private val COLOR_SURFACE_CON = 0xFFEDEEEF.toInt()
    private val COLOR_ON_SURFACE  = 0xFF191C1D.toInt()
    private val COLOR_ON_SURFACE_V = 0xFF424751.toInt()
    private val COLOR_RING_TRACK  = 0x15000000 // Subtle track
    private val COLOR_COMBO_BG    = 0x4DFFDAD6.toInt() // tertiary-container/30
    private val COLOR_COMBO_FG    = 0xFF69333A.toInt() // on-tertiary-container

    private val PASTEL = listOf(
        0xFF0014E0.toInt(), // blue
        0xFFFF3636.toInt(), // red
        0xFFFFA536.toInt(), // orange
        0xFF0FBA00.toInt(), // green
        0xFF2FA4D7.toInt(), // cyan
        0xFFE76F2E.toInt()  // burnt orange
    )

    // ── Paints ─────────────────────────────────────────────────────────
    private val bgP     = Paint()
    private val cellP   = Paint(Paint.ANTI_ALIAS_FLAG)
    private val blockP  = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ghostP  = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shineP  = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringP   = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeWidth = 10f
    }
    private val txtP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val lblP = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val iconP = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val rrRect = RectF()

    init {
        refillPieces()
        bestScore = prefs().getInt("best", 0)
    }

    // ── Layout ─────────────────────────────────────────────────────────
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val pad = w * 0.08f
        cellSize  = (w - pad * 2) / BOARD_SIZE
        previewCS = cellSize * 0.65f
        boardLeft = pad
        boardTop  = h * 0.28f

        val boardBottom = boardTop + cellSize * BOARD_SIZE
        pieceAreaCY = boardBottom + (h - boardBottom) * 0.25f

        // Abilities and Undo positioning
        val iconSize = w * 0.12f
        val startX = w * 0.10f
        val gap = w * 0.18f
        val yPos = h * 0.88f

        for (i in 0..2) {
            abilityRects[i].set(startX + i * gap, yPos - iconSize/2, startX + i * gap + iconSize, yPos + iconSize/2)
        }

        val undoW = w * 0.18f
        undoRect.set(w * 0.72f, yPos - iconSize/2, w * 0.72f + undoW, yPos + iconSize/2)

        // Menu at top right
        val menuSize = h * 0.05f
        menuRect.set(w - pad - menuSize, h * 0.08f, w - pad, h * 0.08f + menuSize)

        txtP.textSize = h * 0.065f
        lblP.textSize = h * 0.018f
        iconP.textSize = iconSize * 0.5f
    }

    // ── Draw ───────────────────────────────────────────────────────────
    override fun onDraw(canvas: Canvas) {
        drawBg(canvas)
        drawHUD(canvas)
        drawMenu(canvas)
        drawRing(canvas)
        drawBoard(canvas)
        drawSlots(canvas)
        drawCombo(canvas)
        drawAbilities(canvas)
        drawUndo(canvas)
        if (draggingIdx >= 0) drawDrag(canvas)
        if (isGameOver)       drawGameOver(canvas)
    }

    private fun drawBg(c: Canvas) {
        bgP.shader = LinearGradient(0f, 0f, 0f, height.toFloat(), 0xFFF0F4F8.toInt(), Color.WHITE, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgP)
        bgP.shader = null
    }

    private fun drawHUD(canvas: Canvas) {
        val pad = width * 0.08f
        // Top Score
        lblP.color = COLOR_ON_SURFACE_V; lblP.alpha = 180; lblP.textAlign = Paint.Align.LEFT
        canvas.drawText("TOP", pad, height * 0.08f, lblP)
        txtP.color = COLOR_PRIMARY; txtP.textAlign = Paint.Align.LEFT; txtP.textSize = height * 0.03f
        canvas.drawText(fmt(bestScore), pad, height * 0.115f, txtP)

        // Center Brand Icon
        iconP.color = 0xFF7EB2FF.toInt(); iconP.textSize = height * 0.04f
        canvas.drawText("🏆", width / 2f, height * 0.10f, iconP)

        // Main Score
        txtP.color = COLOR_ON_SURFACE; txtP.textAlign = Paint.Align.CENTER; txtP.textSize = height * 0.08f
        canvas.drawText(fmt(score), width / 2f, boardTop * 0.75f, txtP)
    }

    private fun drawMenu(canvas: Canvas) {
        bgP.color = COLOR_SURFACE_CON; canvas.drawRoundRect(menuRect, menuRect.width()/2, menuRect.width()/2, bgP)
        iconP.color = COLOR_ON_SURFACE_V; iconP.textSize = menuRect.height() * 0.6f
        canvas.drawText("≡", menuRect.centerX(), menuRect.centerY() + iconP.textSize * 0.35f, iconP)
    }

    private fun drawCombo(canvas: Canvas) {
        if (comboLevel < 1) return // Hide if no combo
        val cx = width / 2f
        val cy = boardTop * 0.88f
        val text = "⚡ COMBO x$comboLevel"
        lblP.textSize = height * 0.022f; lblP.textAlign = Paint.Align.CENTER; lblP.typeface = Typeface.DEFAULT_BOLD
        val tw = lblP.measureText(text)
        val bh = height * 0.045f; val bw = tw + 60f
        val rr = RectF(cx - bw/2, cy - bh/2, cx + bw/2, cy + bh/2)

        // Permanent visibility if comboLevel > 0
        bgP.color = COLOR_COMBO_BG; canvas.drawRoundRect(rr, bh/2, bh/2, bgP)
        lblP.color = COLOR_COMBO_FG; canvas.drawText(text, cx, cy + lblP.textSize * 0.35f, lblP)
        lblP.typeface = Typeface.DEFAULT
    }

    private fun drawRing(canvas: Canvas) {
        val m = 12f; val rad = 42f
        val rr = RectF(boardLeft - m, boardTop - m, boardLeft + cellSize*BOARD_SIZE + m, boardTop + cellSize*BOARD_SIZE + m)

        // Background Track
        ringP.color = COLOR_RING_TRACK; ringP.strokeWidth = 10f
        canvas.drawRoundRect(rr, rad, rad, ringP)

        // Progress Fill (Green)
        if (ringFill <= 0f && ringFlash <= 0f) return
        ringP.color = if (ringFlash > 0f) lerp(COLOR_SECONDARY, Color.WHITE, ringFlash) else COLOR_SECONDARY
        ringP.strokeWidth = 10f

        val path = Path(); path.addRoundRect(rr, rad, rad, Path.Direction.CW)
        val pm = PathMeasure(path, false); val total = pm.length

        // Starting from top center
        val startDist = total * 0.875f // Roughly top-center for a rounded rect
        val drawLen = (ringFill * total).coerceIn(0f, total)

        val dst = Path()
        if (startDist + drawLen <= total) {
            pm.getSegment(startDist, startDist + drawLen, dst, true)
        } else {
            pm.getSegment(startDist, total, dst, true)
            val d2 = Path(); pm.getSegment(0f, drawLen - (total - startDist), d2, true); dst.addPath(d2)
        }
        canvas.drawPath(dst, ringP)
    }

    private fun drawBoard(canvas: Canvas) {
        val br = boardLeft + cellSize*BOARD_SIZE; val bb = boardTop + cellSize*BOARD_SIZE
        bgP.color = COLOR_SURFACE_CON; canvas.drawRoundRect(boardLeft-8f, boardTop-8f, br+8f, bb+8f, 32f, 32f, bgP)
        for (r in 0 until BOARD_SIZE) for (c in 0 until BOARD_SIZE) {
            val l = boardLeft + c*cellSize; val t = boardTop + r*cellSize
            rrRect.set(l+4f, t+4f, l+cellSize-4f, t+cellSize-4f)
            val occ = board[r][c] != 0
            val flash = (r in clearRows || c in clearCols) && occ
            when {
                flash -> { blockP.color = lerp(board[r][c], Color.WHITE, clearFlash); canvas.drawRoundRect(rrRect, 12f, 12f, blockP) }
                occ -> drawCell(canvas, l, t, board[r][c], cellSize)
                else -> { cellP.color = 0x1A000000; canvas.drawRoundRect(rrRect, 12f, 12f, cellP) }
            }
            if (draggingIdx >= 0 && ghostRow >= 0 && canPlace && !occ) {
                val p = currentPieces[draggingIdx]
                if (p != null && p.blocks.any { b -> ghostRow+b.row==r && ghostCol+b.col==c }) {
                    ghostP.color = p.color; ghostP.alpha = 100
                    canvas.drawRoundRect(rrRect, 12f, 12f, ghostP); ghostP.alpha = 255
                }
            }
        }
    }

    private fun drawCell(canvas: Canvas, l: Float, t: Float, color: Int, cs: Float) {
        rrRect.set(l+4f, t+4f, l+cs-4f, t+cs-4f)
        blockP.color = color; canvas.drawRoundRect(rrRect, 12f, 12f, blockP)
        shineP.color = Color.WHITE; shineP.alpha = 40
        canvas.drawRoundRect(l+6f, t+6f, l+cs-6f, t+7f, 2f, 2f, shineP)
    }

    private fun drawSlots(canvas: Canvas) {
        val sw = width / PIECE_SLOTS.toFloat()
        for (i in 0 until PIECE_SLOTS) {
            if (i == draggingIdx) continue
            val p = currentPieces[i] ?: continue
            drawPiece(canvas, p, sw*i + sw/2f, pieceAreaCY, previewCS)
        }
    }

    private fun drawPiece(canvas: Canvas, piece: Piece, cx: Float, cy: Float, cs: Float) {
        val sx = cx - piece.width*cs/2f; val sy = cy - piece.height*cs/2f
        for (b in piece.blocks) {
            val l = sx+b.col*cs; val t = sy+b.row*cs
            val rr = RectF(l+3f, t+3f, l+cs-3f, t+cs-3f)
            blockP.color = piece.color; canvas.drawRoundRect(rr, 10f, 10f, blockP)
            shineP.color = Color.WHITE; shineP.alpha = 40
            canvas.drawRoundRect(l+5f, t+5f, l+cs-5f, t+6f, 2f, 2f, shineP)
        }
    }

    private fun drawDrag(canvas: Canvas) {
        val piece = currentPieces[draggingIdx] ?: return
        val cs = cellSize * 1.05f
        val sx = dragX - piece.width*cs/2f; val sy = dragY - piece.height*cs/2f - cs*0.8f
        for (b in piece.blocks) {
            val l = sx+b.col*cs; val t = sy+b.row*cs
            val rr = RectF(l+3f, t+3f, l+cs-3f, t+cs-3f)
            shadowP.color = 0x33000000; shadowP.maskFilter = BlurMaskFilter(15f, BlurMaskFilter.Blur.NORMAL)
            canvas.drawRoundRect(RectF(rr).apply { offset(0f, 10f) }, 12f, 12f, shadowP)
            blockP.color = piece.color; canvas.drawRoundRect(rr, 12f, 12f, blockP)
            shineP.color = Color.WHITE; shineP.alpha = 50
            canvas.drawRoundRect(l+5f, t+5f, l+cs-5f, t+6f, 2f, 2f, shineP)
        }
        shadowP.maskFilter = null
    }

    private fun drawAbilities(canvas: Canvas) {
        val labels = listOf("1000", "5000", "10000")
        val icons = listOf("▼", "≡", "✦")
        val avail = listOf(gravityAvail, tripleAvail, clearAllAvail)
        for (i in 0..2) {
            val r = abilityRects[i]; val on = avail[i]
            bgP.color = if (on) Color.WHITE else 0x1A000000; bgP.style = Paint.Style.FILL
            canvas.drawRoundRect(r, 24f, 24f, bgP)
            if (!on) {
                bgP.color = 0x20000000; bgP.style = Paint.Style.STROKE; bgP.strokeWidth = 2f
                canvas.drawRoundRect(r, 24f, 24f, bgP); bgP.style = Paint.Style.FILL
            }
            iconP.color = if (on) COLOR_PRIMARY else 0x40000000; iconP.textSize = r.height() * 0.45f
            canvas.drawText(icons[i], r.centerX(), r.centerY() + iconP.textSize * 0.35f, iconP)
            lblP.color = COLOR_ON_SURFACE_V; lblP.alpha = 150; lblP.textSize = r.height() * 0.25f
            canvas.drawText(labels[i], r.centerX(), r.bottom + lblP.textSize * 1.5f, lblP)
        }
    }

    private fun drawUndo(canvas: Canvas) {
        val r = undoRect
        iconP.color = COLOR_PRIMARY; iconP.textSize = r.height() * 0.6f
        canvas.drawText("↩", r.centerX(), r.centerY() + iconP.textSize * 0.2f, iconP)
        lblP.color = COLOR_PRIMARY; lblP.textSize = r.height() * 0.25f; lblP.typeface = Typeface.DEFAULT_BOLD
        canvas.drawText("UNDO", r.centerX(), r.bottom, lblP)
        lblP.typeface = Typeface.DEFAULT
    }

    private fun drawGameOver(canvas: Canvas) {
        bgP.color = 0xCCF8F9FA.toInt(); canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgP)
        val cx = width/2f; val cy = height/2f
        bgP.color = Color.WHITE; canvas.drawRoundRect(cx-250f, cy-220f, cx+250f, cy+220f, 48f, 48f, bgP)

        lblP.color = COLOR_ON_SURFACE_V; lblP.textSize = height*0.025f; lblP.textAlign=Paint.Align.CENTER
        canvas.drawText("GAME OVER", cx, cy-140f, lblP)

        txtP.color = COLOR_ON_SURFACE; txtP.textSize = height*0.1f; txtP.textAlign=Paint.Align.CENTER
        canvas.drawText(fmt(score), cx, cy-30f, txtP)

        if (score > 0 && score >= bestScore) {
            lblP.color = COLOR_SECONDARY; lblP.typeface = Typeface.DEFAULT_BOLD
            canvas.drawText("🏆 NEW BEST!", cx, cy+25f, lblP); lblP.typeface = Typeface.DEFAULT
        }

        val btnR = RectF(cx-160f, cy+100f, cx+160f, cy+180f)
        bgP.color = COLOR_PRIMARY; canvas.drawRoundRect(btnR, 40f, 40f, bgP)
        iconP.color = Color.WHITE; iconP.textSize = height*0.035f; iconP.typeface = Typeface.DEFAULT_BOLD
        canvas.drawText("RESTART", cx, cy+152f, iconP); iconP.typeface = Typeface.DEFAULT
    }

    // ── Touch ──────────────────────────────────────────────────────────
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (isGameOver) {
            if (ev.action == MotionEvent.ACTION_UP) {
                val cx = width/2f; val cy = height/2f
                val btnR = RectF(cx-160f, cy+100f, cx+160f, cy+180f)
                if (btnR.contains(ev.x, ev.y)) resetGame()
            }
            return true
        }
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                if (undoRect.contains(ev.x, ev.y)) { doUndo(); return true }
                for (i in 0..2) if (abilityRects[i].contains(ev.x, ev.y)) { activateAbility(i); return true }
                onDown(ev.x, ev.y)
            }
            MotionEvent.ACTION_MOVE -> onMove(ev.x, ev.y)
            MotionEvent.ACTION_UP   -> onUp()
        }
        return true
    }

    private fun onDown(x: Float, y: Float) {
        val sw = width / PIECE_SLOTS.toFloat()
        val idx = (x / sw).toInt().coerceIn(0, PIECE_SLOTS-1)
        if (currentPieces[idx] != null) {
            draggingIdx = idx; dragX = x; dragY = y
            updateGhost(); invalidate()
            vibrate(15)
        }
    }

    private fun onMove(x: Float, y: Float) {
        if (draggingIdx < 0) return
        dragX = x; dragY = y; updateGhost(); invalidate()
    }

    private fun onUp() {
        if (draggingIdx < 0) return
        val piece = currentPieces[draggingIdx]
        if (piece != null && ghostRow >= 0 && canPlace) {
            saveUndo()
            currentPieces[draggingIdx] = null
            placePiece(piece, ghostRow, ghostCol)
            if (currentPieces.all { it == null }) refillPieces()
        }
        draggingIdx = -1; ghostRow = -1; canPlace = false; invalidate()
    }

    private fun updateGhost() {
        val piece = currentPieces[draggingIdx] ?: return
        val ly = dragY - cellSize * 0.8f
        ghostCol = ((dragX - boardLeft) / cellSize - piece.width / 2f + 0.5f).toInt()
        ghostRow = ((ly - boardTop) / cellSize - piece.height / 2f + 0.5f).toInt()
        canPlace = ly > boardTop && ly < boardTop + cellSize * BOARD_SIZE && canPlacePiece(piece, ghostRow, ghostCol)
    }

    // ── Game logic ─────────────────────────────────────────────────────
    private fun canPlacePiece(p: Piece, row: Int, col: Int): Boolean {
        for (b in p.blocks) {
            val r = row + b.row; val c = col + b.col
            if (r !in 0 until BOARD_SIZE || c !in 0 until BOARD_SIZE || board[r][c] != 0) return false
        }
        return true
    }

    private fun isGameOverNow(): Boolean {
        val active = currentPieces.filterNotNull()
        if (active.isEmpty()) return false
        return active.none { p ->
            (0 until BOARD_SIZE).any { r -> (0 until BOARD_SIZE).any { c -> canPlacePiece(p, r, c) } }
        }
    }

    private fun placePiece(piece: Piece, row: Int, col: Int) {
        for (b in piece.blocks) board[row+b.row][col+b.col] = piece.color
        score += piece.blocks.size
        vibrate(30)
        clearAndCheck()
        checkAbilities()
        updateRing()
        saveBest()
    }

    private fun clearAndCheck() {
        val rows = (0 until BOARD_SIZE).filter { r -> (0 until BOARD_SIZE).all { c -> board[r][c] != 0 } }
        val cols = (0 until BOARD_SIZE).filter { c -> (0 until BOARD_SIZE).all { r -> board[r][c] != 0 } }

        if (rows.isEmpty() && cols.isEmpty()) {
            movesSinceClear++
            if (movesSinceClear >= 2) breakCombo()
            post { evalGameOver() }
            return
        }

        movesSinceClear = 0
        comboCount++
        comboLevel = comboCount
        // showComboBadge() // Not needed for permanent display, but we can keep for animation logic if needed

        if (comboCount >= 2) vibratePattern(longArrayOf(0, 40, 40, 80)) else vibrate(60)

        score += (rows.size + cols.size) * BOARD_SIZE * comboLevel
        saveBest(); checkAbilities(); updateRing()

        clearRows.addAll(rows); clearCols.addAll(cols)
        clearRunning = true

        ValueAnimator.ofFloat(0f, 1f, 0f).apply {
            duration = 400
            addUpdateListener { clearFlash = it.animatedValue as Float; invalidate() }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    rows.forEach { r -> for (c in 0 until BOARD_SIZE) board[r][c] = 0 }
                    cols.forEach { c -> for (r in 0 until BOARD_SIZE) board[r][c] = 0 }
                    clearRows.clear(); clearCols.clear()
                    clearFlash = 0f; clearRunning = false
                    invalidate()
                    post { evalGameOver() }
                }
            })
        }.start()
    }

    private fun evalGameOver() {
        if (!isGameOver && !clearRunning && isGameOverNow()) {
            isGameOver = true
            vibrate(400)
            invalidate()
        }
    }

    private fun breakCombo() {
        if (comboLevel == 0) return
        comboCount = 0; comboLevel = 0; movesSinceClear = 0
        invalidate()
    }

    private fun updateRing() {
        val lvl = score / RING_INTERVAL
        if (lvl > prevRingLevel) {
            prevRingLevel = lvl; ringBonus = true
            ValueAnimator.ofFloat(0f, 1f, 0f).apply {
                duration = 600
                addUpdateListener { ringFlash = it.animatedValue as Float; invalidate() }
            }.start()
            vibratePattern(longArrayOf(0, 40, 60, 100))
        }
        ringFill = (score % RING_INTERVAL).toFloat() / RING_INTERVAL
        invalidate()
    }

    private fun checkAbilities() {
        if (score >= 1000  && !gravityUsed)  gravityAvail  = true
        if (score >= 5000  && !tripleUsed)   tripleAvail   = true
        if (score >= 10000 && !clearAllUsed) clearAllAvail = true
    }

    private fun activateAbility(i: Int) {
        when (i) {
            0 -> if (gravityAvail)  { applyGravity();   gravityAvail=false;  gravityUsed=true;  vibrate(120) }
            1 -> if (tripleAvail)   { applyTriple();    tripleAvail=false;   tripleUsed=true;   vibrate(120) }
            2 -> if (clearAllAvail) { clearAll();       clearAllAvail=false; clearAllUsed=true; vibrate(200) }
        }
        updateRing(); saveBest(); post { evalGameOver() }; invalidate()
    }

    private fun applyGravity() {
        for (c in 0 until BOARD_SIZE) {
            val col = (0 until BOARD_SIZE).mapNotNull { r -> board[r][c].takeIf { it != 0 } }
            for (r in 0 until BOARD_SIZE) {
                val i = col.size - (BOARD_SIZE - r)
                board[r][c] = if (i >= 0) col[i] else 0
            }
        }
        clearAndCheck()
    }

    private fun applyTriple() {
        var bRS = -1; var bR = 0
        for (r in 0..BOARD_SIZE-3) {
            val s = (r..r+2).sumOf { row -> (0 until BOARD_SIZE).count { c -> board[row][c]!=0 } }
            if (s > bRS) { bRS = s; bR = r }
        }
        for (r in bR..bR+2) for (c in 0 until BOARD_SIZE) board[r][c] = 0
        score += 3 * BOARD_SIZE; saveBest(); invalidate()
    }

    private fun clearAll() {
        for (r in 0 until BOARD_SIZE) board[r].fill(0)
        score += BOARD_SIZE * BOARD_SIZE; saveBest(); invalidate()
    }

    private fun saveUndo() {
        prevBoard = Array(BOARD_SIZE) { board[it].copyOf() }; prevScore = score
        for (i in 0 until PIECE_SLOTS) prevPieces[i] = currentPieces[i]
    }

    private fun doUndo() {
        val pb = prevBoard ?: return
        for (r in 0 until BOARD_SIZE) board[r] = pb[r].copyOf(); score = prevScore
        for (i in 0 until PIECE_SLOTS) currentPieces[i] = prevPieces[i]
        prevBoard = null; breakCombo(); updateRing(); checkAbilities(); vibrate(40); invalidate()
    }

    private fun refillPieces() {
        if (ringBonus) {
            for (i in 0 until PIECE_SLOTS) currentPieces[i] = Piece(listOf(Block(0,0)), PASTEL.random())
            ringBonus = false; vibrate(80); return
        }
        for (i in 0 until PIECE_SLOTS) if (currentPieces[i] == null) currentPieces[i] = PieceFactory.random().copy(color = PASTEL.random())
    }

    private fun resetGame() {
        for (r in 0 until BOARD_SIZE) board[r].fill(0)
        for (i in 0 until PIECE_SLOTS) currentPieces[i] = null
        score = 0; isGameOver = false; clearRunning = false
        comboCount = 0; comboLevel = 0; movesSinceClear = 0; comboAlpha = 0f
        ringFill = 0f; ringBonus = false; prevRingLevel = 0; ringFlash = 0f
        gravityAvail=false; gravityUsed=false; tripleAvail=false; tripleUsed=false
        clearAllAvail=false; clearAllUsed=false; prevBoard=null
        refillPieces(); invalidate()
    }

    private fun saveBest() { if (score > bestScore) { bestScore = score; prefs().edit().putInt("best", bestScore).apply() } }
    private fun prefs() = context.getSharedPreferences("bb_prefs", Context.MODE_PRIVATE)
    private fun vibrate(ms: Long) = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) vibrator?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        else @Suppress("DEPRECATION") vibrator?.vibrate(ms)
    }
    private fun vibratePattern(p: LongArray) = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) vibrator?.vibrate(VibrationEffect.createWaveform(p, -1))
        else @Suppress("DEPRECATION") vibrator?.vibrate(p, -1)
    }
    private fun fmt(n: Int) = if (n >= 1000) "${n/1000},${"%03d".format(n%1000)}" else "$n"
    private fun lerp(c1: Int, c2: Int, t: Float): Int {
        val i = 1f-t
        return Color.argb(255, (Color.red(c1)*i + Color.red(c2)*t).toInt(), (Color.green(c1)*i + Color.green(c2)*t).toInt(), (Color.blue(c1)*i + Color.blue(c2)*t).toInt())
    }
    private fun ac(color: Int, a: Float) = Color.argb((a*255).toInt(), Color.red(color), Color.green(color), Color.blue(color))
}