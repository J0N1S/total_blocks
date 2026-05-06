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

class GameView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        const val BOARD_SIZE    = 8
        const val PIECE_SLOTS   = 3
        const val RING_INTERVAL = 500
    }

    // ── Board & Pieces ─────────────────────────────────────────────────
    private val board        = Array(BOARD_SIZE) { IntArray(BOARD_SIZE) { 0 } }
    private val currentPieces = arrayOfNulls<Piece>(PIECE_SLOTS)

    // ── Score ──────────────────────────────────────────────────────────
    private var score    = 0
    private var bestScore = 0

    // ── Combo ──────────────────────────────────────────────────────────
    // comboCount = how many consecutive clears we've had
    // comboLevel = multiplier shown on badge (starts at 1 on FIRST clear, bumps after each)
    private var comboCount = 0          // total consecutive clears (each move that clears ≥1)
    private var comboLevel = 0          // shown on badge, 0 = no combo active
    private var comboAlpha = 0f
    private var comboAnim: ValueAnimator? = null
    private var movesSinceClear = 0     // successful placements with no clear

    // ── Ring ───────────────────────────────────────────────────────────
    private var ringFill       = 0f
    private var prevRingLevel  = 0
    private var ringFlash      = 0f
    private var ringBonus      = false  // next refill = 3×1×1

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
    private var clearRunning  = false   // true while flash animation plays

    // ── Game state ─────────────────────────────────────────────────────
    private var isGameOver = false

    // ── Vibrator ───────────────────────────────────────────────────────
    @Suppress("DEPRECATION")
    private val vibrator: Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE)
                    as android.os.VibratorManager).defaultVibrator
        else context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }.getOrNull()

    // ── Colors ─────────────────────────────────────────────────────────
    private val BG_COLOR    = 0xFFF0F4F8.toInt()
    private val BOARD_BG    = 0xFFE8EAF0.toInt()
    private val CELL_EMPTY  = 0xFFDDDFE8.toInt()
    private val SCORE_COLOR = 0xFF1A1A2E.toInt()
    private val LABEL_COLOR = 0xFF8890A8.toInt()
    private val UNDO_BG     = 0xFFEAECF4.toInt()
    private val UNDO_FG     = 0xFF4A6FA5.toInt()
    private val COMBO_BG    = 0xFFFFE8E8.toInt()
    private val COMBO_FG    = 0xFFD94F4F.toInt()
    private val RING_COLOR  = 0xFF4CAF8A.toInt()
    private val RING_TRACK  = 0x18000000
    private val ABL_ON      = 0xFF4A6FA5.toInt()
    private val ABL_OFF     = 0x22000000

    private val PASTEL = listOf(
        0xFF7EB8F7.toInt(), 0xFF7DD9B8.toInt(), 0xFFF4A7A7.toInt(),
        0xFFB5A7F4.toInt(), 0xFFFFCC80.toInt(), 0xFF80DEEA.toInt(),
        0xFFA5D6A7.toInt(), 0xFFF48FB1.toInt(), 0xFF90CAF9.toInt()
    )

    // ── Paints ─────────────────────────────────────────────────────────
    private val bgP     = Paint()
    private val cellP   = Paint(Paint.ANTI_ALIAS_FLAG)
    private val blockP  = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ghostP  = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shineP  = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringP   = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeWidth = 5f
    }
    private val txtP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
    }
    private val lblP = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val rrRect = RectF()

    init {
        refillPieces()
        bestScore = prefs().getInt("best", 0)
    }

    // ── Layout ─────────────────────────────────────────────────────────
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val pad = w * 0.045f
        cellSize  = (w - pad * 2) / BOARD_SIZE
        previewCS = cellSize * 0.68f
        boardLeft = pad
        boardTop  = h * 0.22f

        val bb = boardTop + cellSize * BOARD_SIZE
        val ba = h - bb
        pieceAreaCY = bb + ba * 0.28f

        val as_ = w * 0.125f; val ag = w * 0.145f; val ax = w * 0.06f
        val ay  = bb + ba * 0.62f
        for (i in 0..2) abilityRects[i].set(ax + i * ag, ay - as_/2, ax + i * ag + as_, ay + as_/2)

        val uw = w * 0.22f
        undoRect.set(w * 0.76f, ay - as_/2, w * 0.76f + uw, ay + as_/2)
        menuRect.set(w - h*0.07f, boardTop*0.15f, w - h*0.01f, boardTop*0.60f)

        txtP.textSize = h * 0.072f
        lblP.textSize = h * 0.022f
    }

    // ── Draw ───────────────────────────────────────────────────────────
    override fun onDraw(canvas: Canvas) {
        drawBg(canvas)
        drawHUD(canvas)
        drawMenu(canvas)
        drawCombo(canvas)
        drawRing(canvas)
        drawBoard(canvas)
        drawSlots(canvas)
        drawAbilities(canvas)
        drawUndo(canvas)
        if (draggingIdx >= 0) drawDrag(canvas)
        if (isGameOver)       drawGameOver(canvas)
    }

    private fun drawBg(c: Canvas) { bgP.color = BG_COLOR; c.drawRect(0f,0f,width.toFloat(),height.toFloat(),bgP) }

    private fun drawHUD(canvas: Canvas) {
        val cx = width / 2f
        lblP.color = LABEL_COLOR; lblP.textAlign = Paint.Align.LEFT; lblP.textSize = height * 0.018f
        canvas.drawText("TOP", width * 0.06f, boardTop * 0.30f, lblP)
        val bp = mp(UNDO_FG, height * 0.032f, true); bp.textAlign = Paint.Align.LEFT
        canvas.drawText(fmt(bestScore), width * 0.06f, boardTop * 0.50f, bp)
        txtP.color = SCORE_COLOR; txtP.textSize = height * 0.088f
        canvas.drawText(fmt(score), cx, boardTop * 0.75f, txtP)
        lblP.textAlign = Paint.Align.CENTER
    }

    private fun drawMenu(canvas: Canvas) {
        val r = menuRect
        bgP.color = 0xFFEAECF4.toInt(); canvas.drawRoundRect(r, 10f, 10f, bgP)
        val lp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = LABEL_COLOR; strokeWidth = 2.5f
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
        }
        val cx = r.centerX(); val gap = r.height() * 0.22f
        for (i in 0..2) { val y = r.top + r.height()*0.25f + i*gap; canvas.drawLine(r.left+8f, y, r.right-8f, y, lp) }
    }

    private fun drawCombo(canvas: Canvas) {
        // Show whenever comboLevel >= 1 AND badge is fading in/visible
        if (comboLevel < 1 || comboAlpha <= 0.01f) return
        val cx = width / 2f
        // Position: just below the score, above the board
        val cy = boardTop * 0.91f
        val text = "⚡ COMBO x$comboLevel"
        lblP.textSize = height * 0.025f; lblP.textAlign = Paint.Align.CENTER
        val tw = lblP.measureText(text)
        val pd = 26f; val bh = 44f
        val rr = RectF(cx - tw/2 - pd, cy - bh*0.68f, cx + tw/2 + pd, cy + bh*0.32f)
        bgP.color = ac(COMBO_BG, comboAlpha); canvas.drawRoundRect(rr, bh/2, bh/2, bgP)
        lblP.color = ac(COMBO_FG, comboAlpha); canvas.drawText(text, cx, cy, lblP)
    }

    private fun drawRing(canvas: Canvas) {
        val m = 9f
        val rr = RectF(boardLeft-m, boardTop-m,
            boardLeft + cellSize*BOARD_SIZE + m,
            boardTop  + cellSize*BOARD_SIZE + m)
        val rad = 26f
        ringP.color = RING_TRACK; ringP.strokeWidth = 5f
        canvas.drawRoundRect(rr, rad, rad, ringP)
        if (ringFill <= 0f && ringFlash <= 0f) return
        val col = if (ringFlash > 0f) lerp(RING_COLOR, Color.WHITE, ringFlash) else RING_COLOR
        ringP.color = col
        val path = Path(); path.addRoundRect(rr, rad, rad, Path.Direction.CW)
        val pm = PathMeasure(path, false); val total = pm.length
        val sw = rr.width() - 2*rad; val sh = rr.height() - 2*rad
        val arc = (Math.PI * rad / 2).toFloat()
        val startOff = sw + arc + sh + arc + sw/2f
        val drawLen = ringFill * total
        val dst = Path()
        if (startOff + drawLen <= total) {
            pm.getSegment(startOff, startOff + drawLen, dst, true)
        } else {
            pm.getSegment(startOff, total, dst, true)
            val d2 = Path(); pm.getSegment(0f, startOff + drawLen - total, d2, true); dst.addPath(d2)
        }
        canvas.drawPath(dst, ringP)
    }

    private fun drawBoard(canvas: Canvas) {
        val br = boardLeft + cellSize*BOARD_SIZE; val bb = boardTop + cellSize*BOARD_SIZE
        bgP.color = BOARD_BG; canvas.drawRoundRect(boardLeft-6f, boardTop-6f, br+6f, bb+6f, 24f, 24f, bgP)
        for (r in 0 until BOARD_SIZE) for (c in 0 until BOARD_SIZE) {
            val l = boardLeft + c*cellSize; val t = boardTop + r*cellSize
            rrRect.set(l+3f, t+3f, l+cellSize-3f, t+cellSize-3f)
            val occ = board[r][c] != 0
            val flash = (r in clearRows || c in clearCols) && occ
            when {
                flash   -> { blockP.color = lerp(board[r][c], Color.WHITE, clearFlash); canvas.drawRoundRect(rrRect, 10f, 10f, blockP) }
                occ     -> drawCell(canvas, l, t, board[r][c], cellSize)
                else    -> { cellP.color = CELL_EMPTY; canvas.drawRoundRect(rrRect, 10f, 10f, cellP) }
            }
            if (draggingIdx >= 0 && ghostRow >= 0 && canPlace && !occ) {
                val p = currentPieces[draggingIdx]
                if (p != null && p.blocks.any { b -> ghostRow+b.row==r && ghostCol+b.col==c }) {
                    ghostP.color = p.color; ghostP.alpha = 130
                    canvas.drawRoundRect(rrRect, 10f, 10f, ghostP); ghostP.alpha = 255
                }
            }
        }
    }

    private fun drawCell(canvas: Canvas, l: Float, t: Float, color: Int, cs: Float) {
        rrRect.set(l+3f, t+3f, l+cs-3f, t+cs-3f)
        blockP.color = color; canvas.drawRoundRect(rrRect, 10f, 10f, blockP)
        shineP.shader = LinearGradient(l,t,l,t+cs*0.5f, 0x30FFFFFF,0x00FFFFFF, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(rrRect, 10f, 10f, shineP)
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
            val rr = RectF(l+2f,t+2f,l+cs-2f,t+cs-2f)
            blockP.color = piece.color; canvas.drawRoundRect(rr, 9f, 9f, blockP)
            shineP.shader = LinearGradient(l,t,l,t+cs*0.5f,0x30FFFFFF,0x00FFFFFF,Shader.TileMode.CLAMP)
            canvas.drawRoundRect(rr, 9f, 9f, shineP)
        }
    }

    private fun drawDrag(canvas: Canvas) {
        val piece = currentPieces[draggingIdx] ?: return
        val cs = cellSize * 1.1f
        val sx = dragX - piece.width*cs/2f; val sy = dragY - piece.height*cs/2f - cs*0.6f
        for (b in piece.blocks) {
            val l = sx+b.col*cs; val t = sy+b.row*cs
            val rr = RectF(l+2f,t+2f,l+cs-2f,t+cs-2f)
            shadowP.color = 0x22000000
            shadowP.maskFilter = BlurMaskFilter(14f, BlurMaskFilter.Blur.NORMAL)
            canvas.drawRoundRect(rr.apply { offset(4f,8f) }, 10f, 10f, shadowP); rr.offset(-4f,-8f)
            blockP.color = piece.color; canvas.drawRoundRect(rr, 10f, 10f, blockP)
            shineP.shader = LinearGradient(l,t,l,t+cs*0.5f,0x30FFFFFF,0x00FFFFFF,Shader.TileMode.CLAMP)
            canvas.drawRoundRect(rr, 10f, 10f, shineP)
        }
    }

    private fun drawAbilities(canvas: Canvas) {
        val labels = listOf("1,000","5,000","10,000")
        val icons  = listOf("↓↓","≡≡≡","✦")
        val avail  = listOf(gravityAvail, tripleAvail, clearAllAvail)
        for (i in 0..2) {
            val r = abilityRects[i]; val on = avail[i]
            bgP.color = if (on) ABL_ON else ABL_OFF; canvas.drawRoundRect(r, 14f, 14f, bgP)
            val ip = mp(if(on) Color.WHITE else 0x50000000, r.height()*0.40f, true); ip.textAlign=Paint.Align.CENTER
            canvas.drawText(icons[i], r.centerX(), r.centerY()+r.height()*0.15f, ip)
            val lp = mp(if(on) 0xCCFFFFFF.toInt() else 0x30000000, r.height()*0.20f); lp.textAlign=Paint.Align.CENTER
            canvas.drawText(labels[i], r.centerX(), r.bottom+r.height()*0.32f, lp)
        }
    }

    private fun drawUndo(canvas: Canvas) {
        val r = undoRect
        bgP.color = UNDO_BG; canvas.drawRoundRect(r, r.height()/2, r.height()/2, bgP)
        val ap = mp(UNDO_FG, r.height()*0.45f, true); ap.textAlign=Paint.Align.CENTER
        canvas.drawText("↩", r.centerX(), r.centerY()+r.height()*0.16f, ap)
        val lp = mp(UNDO_FG, r.height()*0.22f); lp.textAlign=Paint.Align.CENTER
        canvas.drawText("UNDO", r.centerX(), r.bottom-6f, lp)
    }

    private fun drawGameOver(canvas: Canvas) {
        bgP.color = 0xBBF0F2F7.toInt(); canvas.drawRect(0f,0f,width.toFloat(),height.toFloat(),bgP)
        val cx = width/2f; val cy = height/2f
        bgP.color = Color.WHITE; canvas.drawRoundRect(cx-230f,cy-210f,cx+230f,cy+210f,32f,32f,bgP)
        val lp = mp(LABEL_COLOR, height*0.024f); lp.textAlign=Paint.Align.CENTER
        canvas.drawText("GAME OVER", cx, cy-130f, lp)
        val sp = mp(SCORE_COLOR, height*0.09f, true); sp.textAlign=Paint.Align.CENTER
        canvas.drawText(fmt(score), cx, cy-40f, sp)
        if (score > 0 && score >= bestScore) {
            val np = mp(RING_COLOR, height*0.025f, true); np.textAlign=Paint.Align.CENTER
            canvas.drawText("🏆 NEW BEST!", cx, cy+12f, np)
        }
        bgP.color = ABL_ON; canvas.drawRoundRect(cx-155f,cy+80f,cx+155f,cy+158f,38f,38f,bgP)
        val rp = mp(Color.WHITE, height*0.032f, true); rp.textAlign=Paint.Align.CENTER
        canvas.drawText("RESTART", cx, cy+132f, rp)
    }

    // ── Touch ──────────────────────────────────────────────────────────
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (isGameOver) { if (ev.action == MotionEvent.ACTION_UP) resetGame(); return true }
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                if (menuRect.contains(ev.x, ev.y))  return true
                if (undoRect.contains(ev.x, ev.y))  { doUndo(); return true }
                for (i in 0..2) if (abilityRects[i].contains(ev.x, ev.y)) { activateAbility(i); return true }
                onDown(ev.x, ev.y)
            }
            MotionEvent.ACTION_MOVE -> onMove(ev.x, ev.y)
            MotionEvent.ACTION_UP   -> onUp()
        }
        return true
    }

    private fun onDown(x: Float, y: Float) {
        if (y < boardTop + cellSize * BOARD_SIZE) return
        val idx = (x / (width / PIECE_SLOTS.toFloat())).toInt().coerceIn(0, PIECE_SLOTS-1)
        if (currentPieces[idx] != null) { draggingIdx = idx; dragX = x; dragY = y; updateGhost(); invalidate() }
    }

    private fun onMove(x: Float, y: Float) { if (draggingIdx < 0) return; dragX=x; dragY=y; updateGhost(); invalidate() }

    private fun onUp() {
        if (draggingIdx < 0) return
        val piece = currentPieces[draggingIdx]
        if (piece != null && ghostRow >= 0 && canPlace) {
            saveUndo()
            currentPieces[draggingIdx] = null
            placePiece(piece, ghostRow, ghostCol)
            if (currentPieces.all { it == null }) refillPieces()
            // Game-over check happens INSIDE clearAndCheck()
        }
        // Note: failed drops do NOT affect combo or game-over
        draggingIdx = -1; ghostRow = -1; canPlace = false; invalidate()
    }

    private fun updateGhost() {
        val piece = currentPieces[draggingIdx] ?: return
        val ly = dragY - cellSize * 0.6f
        ghostCol = ((dragX-boardLeft)/cellSize - piece.width/2f + 0.5f).toInt()
        ghostRow = ((ly-boardTop)/cellSize    - piece.height/2f + 0.5f).toInt()
        canPlace = ly > boardTop && ly < boardTop + cellSize*BOARD_SIZE
                && canPlacePiece(piece, ghostRow, ghostCol)
    }

    // ── Game logic ─────────────────────────────────────────────────────
    private fun canPlacePiece(p: Piece, row: Int, col: Int): Boolean {
        for (b in p.blocks) {
            val r = row+b.row; val c = col+b.col
            if (r !in 0 until BOARD_SIZE || c !in 0 until BOARD_SIZE || board[r][c] != 0) return false
        }
        return true
    }

    // Returns true if NO active piece fits anywhere on the board
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
        vibrate(28)
        clearAndCheck()
        checkAbilities()
        updateRing()
        saveBest()
    }

    /**
     * Finds cleared lines, updates combo, runs animation.
     * Game-over is always evaluated AFTER this function settles
     * (immediately if no animation, inside onAnimationEnd if animation runs).
     */
    private fun clearAndCheck() {
        val rows = (0 until BOARD_SIZE).filter { r -> (0 until BOARD_SIZE).all { c -> board[r][c] != 0 } }
        val cols = (0 until BOARD_SIZE).filter { c -> (0 until BOARD_SIZE).all { r -> board[r][c] != 0 } }

        if (rows.isEmpty() && cols.isEmpty()) {
            // No clear this move
            movesSinceClear++
            if (movesSinceClear >= 2) breakCombo()
            // Check game over now (no animation pending)
            post { evalGameOver() }
            return
        }

        // ── Lines cleared ─────────────────────────────────────────────
        movesSinceClear = 0
        comboCount++

        // comboLevel: starts at 1 on first clear, +1 for every subsequent
        comboLevel = comboCount
        showComboBadge()

        if (comboCount >= 2) vibratePattern(longArrayOf(0, 40, 40, 80))
        else vibrate(55)

        val multi = comboLevel.coerceAtLeast(1)
        score += (rows.size + cols.size) * BOARD_SIZE * multi
        saveBest(); checkAbilities(); updateRing()

        clearRows.addAll(rows); clearCols.addAll(cols)
        clearRunning = true

        ValueAnimator.ofFloat(0f, 1f, 0f).apply {
            duration = 350
            addUpdateListener { clearFlash = it.animatedValue as Float; invalidate() }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    rows.forEach { r -> for (c in 0 until BOARD_SIZE) board[r][c] = 0 }
                    cols.forEach { c -> for (r in 0 until BOARD_SIZE) board[r][c] = 0 }
                    clearRows.clear(); clearCols.clear()
                    clearFlash = 0f; clearRunning = false
                    invalidate()
                    // Check game over AFTER board is cleared
                    post { evalGameOver() }
                }
            })
        }.start()
    }

    private fun evalGameOver() {
        if (!isGameOver && !clearRunning && isGameOverNow()) {
            isGameOver = true
            vibrate(300)
            invalidate()
        }
    }

    // ── Combo helpers ──────────────────────────────────────────────────
    private fun showComboBadge() {
        comboAlpha = 1f
        comboAnim?.cancel()
        comboAnim = ValueAnimator.ofFloat(1f, 1f, 0f).apply {
            duration = 2000; startDelay = 500
            addUpdateListener { comboAlpha = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun breakCombo() {
        if (comboLevel == 0) return
        comboCount = 0; comboLevel = 0; movesSinceClear = 0
        comboAnim?.cancel()
        ValueAnimator.ofFloat(comboAlpha, 0f).apply {
            duration = 300
            addUpdateListener { comboAlpha = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    // ── Ring ───────────────────────────────────────────────────────────
    private fun updateRing() {
        val lvl = score / RING_INTERVAL
        if (lvl > prevRingLevel) {
            prevRingLevel = lvl; ringBonus = true
            ValueAnimator.ofFloat(0f,1f,0f).apply {
                duration = 600
                addUpdateListener { ringFlash = it.animatedValue as Float; invalidate() }
            }.start()
            vibratePattern(longArrayOf(0,30,50,80,50,120))
        }
        ringFill = (score % RING_INTERVAL).toFloat() / RING_INTERVAL
    }

    // ── Abilities ──────────────────────────────────────────────────────
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
        var found = true
        while (found) {
            for (c in 0 until BOARD_SIZE) {
                val col = (0 until BOARD_SIZE).mapNotNull { r -> board[r][c].takeIf { it != 0 } }
                for (r in 0 until BOARD_SIZE) { val i = col.size - (BOARD_SIZE-r); board[r][c] = if (i>=0) col[i] else 0 }
            }
            val full = (0 until BOARD_SIZE).filter { r -> (0 until BOARD_SIZE).all { c -> board[r][c]!=0 } }
            found = full.isNotEmpty()
            if (found) { score += full.size*BOARD_SIZE; full.forEach { r -> for (c in 0 until BOARD_SIZE) board[r][c]=0 } }
        }
        saveBest(); invalidate()
    }

    private fun applyTriple() {
        var bRS = -1; var bR = 0
        for (r in 0..BOARD_SIZE-3) {
            val s = (r..r+2).sumOf { row -> (0 until BOARD_SIZE).count { c -> board[row][c]!=0 } }
            if (s > bRS) { bRS = s; bR = r }
        }
        var bCS = -1; var bC = 0
        for (c in 0..BOARD_SIZE-3) {
            val s = (c..c+2).sumOf { col -> (0 until BOARD_SIZE).count { r -> board[r][col]!=0 } }
            if (s > bCS) { bCS = s; bC = c }
        }
        if (bRS >= bCS) { for (r in bR..bR+2) for (c in 0 until BOARD_SIZE) board[r][c]=0 }
        else            { for (c in bC..bC+2) for (r in 0 until BOARD_SIZE) board[r][c]=0 }
        score += 3*BOARD_SIZE; saveBest(); invalidate()
    }

    private fun clearAll() {
        for (r in 0 until BOARD_SIZE) board[r].fill(0)
        score += BOARD_SIZE*BOARD_SIZE; saveBest(); invalidate()
    }

    // ── Undo ───────────────────────────────────────────────────────────
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

    // ── Refill ─────────────────────────────────────────────────────────
    private fun refillPieces() {
        if (ringBonus) {
            for (i in 0 until PIECE_SLOTS) currentPieces[i] = Piece(listOf(Block(0,0)), PASTEL.random())
            ringBonus = false; vibrate(80); return
        }
        for (i in 0 until PIECE_SLOTS)
            if (currentPieces[i] == null) currentPieces[i] = PieceFactory.random().copy(color = PASTEL.random())
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

    // ── Helpers ────────────────────────────────────────────────────────
    private fun saveBest() {
        if (score > bestScore) { bestScore = score; prefs().edit().putInt("best", bestScore).apply() }
    }

    private fun prefs() = context.getSharedPreferences("bb_prefs", Context.MODE_PRIVATE)

    private fun vibrate(ms: Long) = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            vibrator?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        else @Suppress("DEPRECATION") vibrator?.vibrate(ms)
    }

    private fun vibratePattern(p: LongArray) = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            vibrator?.vibrate(VibrationEffect.createWaveform(p, -1))
        else @Suppress("DEPRECATION") vibrator?.vibrate(p, -1)
    }

    private fun fmt(n: Int) = if (n >= 1000) "${n/1000},${"%03d".format(n%1000)}" else "$n"

    private fun lerp(c1: Int, c2: Int, t: Float): Int {
        val i = 1f-t
        return Color.argb(255,
            (Color.red(c1)*i   + Color.red(c2)*t).toInt(),
            (Color.green(c1)*i + Color.green(c2)*t).toInt(),
            (Color.blue(c1)*i  + Color.blue(c2)*t).toInt())
    }

    private fun ac(color: Int, a: Float) =
        Color.argb((a*255).toInt(), Color.red(color), Color.green(color), Color.blue(color))

    private fun mp(color: Int, size: Float, bold: Boolean = false) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color=color; textSize=size; if(bold) typeface=Typeface.DEFAULT_BOLD }
}