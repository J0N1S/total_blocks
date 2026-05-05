package com.fgs.totalblocks

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class GameView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        const val BOARD_SIZE = 8
        const val PIECE_SLOTS = 3
    }

    private val board = Array(BOARD_SIZE) { IntArray(BOARD_SIZE) { 0 } }
    private val currentPieces = arrayOfNulls<Piece>(PIECE_SLOTS)
    private var score = 0
    private var bestScore = 0
    private var combo = 0
    private var showCombo = false
    private var comboAlpha = 0f

    private var draggingIdx = -1
    private var dragX = 0f; private var dragY = 0f
    private var ghostRow = -1; private var ghostCol = -1
    private var canPlace = false

    private var boardLeft = 0f; private var boardTop = 0f
    private var cellSize = 0f; private var previewCellSize = 0f
    private var pieceAreaCenterY = 0f
    private var undoBtnTop = 0f; private var undoBtnBottom = 0f
    private var undoBtnLeft = 0f; private var undoBtnRight = 0f

    // Undo state
    private var prevBoard: Array<IntArray>? = null
    private var prevScore = 0
    private var prevPieces: Array<Piece?> = arrayOfNulls(PIECE_SLOTS)

    private val clearingRows = mutableSetOf<Int>()
    private val clearingCols = mutableSetOf<Int>()
    private var clearFlash = 0f
    private var isGameOver = false

    // Light theme colors
    private val BG_COLOR       = 0xFFF0F2F7.toInt()
    private val BOARD_BG       = 0xFFE8EAF0.toInt()
    private val CELL_EMPTY     = 0xFFDDDFE8.toInt()
    private val SCORE_COLOR    = 0xFF1A1A2E.toInt()
    private val LABEL_COLOR    = 0xFF8890A8.toInt()
    private val UNDO_BG        = 0xFFEAECF4.toInt()
    private val UNDO_TEXT      = 0xFF4A6FA5.toInt()
    private val COMBO_BG       = 0xFFFFE8E8.toInt()
    private val COMBO_TEXT     = 0xFFD94F4F.toInt()

    private val PASTEL_COLORS = listOf(
        0xFF7EB8F7.toInt(),  // soft blue
        0xFF7DD9B8.toInt(),  // soft teal/green
        0xFFF4A7A7.toInt(),  // soft pink/red
        0xFFB5A7F4.toInt(),  // soft purple
        0xFFFFCC80.toInt(),  // soft orange
        0xFF80DEEA.toInt(),  // soft cyan
        0xFFA5D6A7.toInt(),  // soft green
        0xFFF48FB1.toInt(),  // soft rose
        0xFF90CAF9.toInt(),  // light blue
    )

    // Override PieceFactory colors with pastels
    private fun getRandomPiece(): Piece {
        val base = PieceFactory.random()
        return base.copy(color = PASTEL_COLORS.random())
    }

    private val bgPaint    = Paint()
    private val cellPaint  = Paint(Paint.ANTI_ALIAS_FLAG)
    private val blockPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ghostPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shinePaint  = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val rrPiece = RectF()

    init {
        refillPieces()
        bestScore = context.getSharedPreferences("bb_prefs", Context.MODE_PRIVATE).getInt("best", 0)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val pad = w * 0.045f
        cellSize = (w - pad * 2) / BOARD_SIZE
        previewCellSize = cellSize * 0.68f
        boardLeft = pad
        boardTop  = h * 0.22f

        val boardBottom = boardTop + cellSize * BOARD_SIZE
        val bottomArea = h - boardBottom
        pieceAreaCenterY = boardBottom + bottomArea * 0.32f

        // Undo button
        val undoH = h * 0.085f
        val undoW = w * 0.52f
        undoBtnLeft   = (w - undoW) / 2f
        undoBtnRight  = undoBtnLeft + undoW
        undoBtnTop    = boardBottom + bottomArea * 0.62f
        undoBtnBottom = undoBtnTop + undoH

        textPaint.textSize  = h * 0.072f
        labelPaint.textSize = h * 0.022f
    }

    override fun onDraw(canvas: Canvas) {
        drawBackground(canvas)
        drawHUD(canvas)
        drawCombo(canvas)
        drawBoard(canvas)
        drawPieceSlots(canvas)
        drawUndoButton(canvas)
        if (draggingIdx >= 0) drawDragPiece(canvas)
        if (isGameOver) drawGameOver(canvas)
    }

    private fun drawBackground(canvas: Canvas) {
        bgPaint.color = BG_COLOR
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
    }

    private fun drawHUD(canvas: Canvas) {
        val cx = width / 2f

        // TOP label + best score (top left)
        labelPaint.color = LABEL_COLOR
        labelPaint.textSize = height * 0.018f
        labelPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("TOP", width * 0.06f, boardTop * 0.28f, labelPaint)
        val bestPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF4A6FA5.toInt()
            typeface = Typeface.DEFAULT_BOLD
            textSize = height * 0.032f
        }
        canvas.drawText(formatScore(bestScore), width * 0.06f, boardTop * 0.48f, bestPaint)

        // Main score (center, large)
        textPaint.color = SCORE_COLOR
        textPaint.textSize = height * 0.088f
        canvas.drawText(formatScore(score), cx, boardTop * 0.72f, textPaint)
    }

    private fun drawCombo(canvas: Canvas) {
        if (!showCombo || combo < 2) return
        val cx = width / 2f
        val cy = boardTop * 0.88f
        val text = "⚡ COMBO x$combo"
        labelPaint.textSize = height * 0.022f
        labelPaint.textAlign = Paint.Align.CENTER
        val tw = labelPaint.measureText(text)
        val pad = 28f; val h = 44f
        val rr = RectF(cx - tw/2 - pad, cy - h*0.65f, cx + tw/2 + pad, cy + h*0.35f)
        bgPaint.color = adjustAlpha(COMBO_BG, comboAlpha)
        canvas.drawRoundRect(rr, h/2, h/2, bgPaint)
        labelPaint.color = adjustAlpha(COMBO_TEXT, comboAlpha)
        canvas.drawText(text, cx, cy, labelPaint)
    }

    private fun drawBoard(canvas: Canvas) {
        val br = boardLeft + cellSize * BOARD_SIZE
        val bb = boardTop  + cellSize * BOARD_SIZE
        bgPaint.color = BOARD_BG
        canvas.drawRoundRect(boardLeft - 6f, boardTop - 6f, br + 6f, bb + 6f, 24f, 24f, bgPaint)

        for (r in 0 until BOARD_SIZE) for (c in 0 until BOARD_SIZE) {
            val l = boardLeft + c * cellSize; val t = boardTop + r * cellSize
            rrPiece.set(l + 3f, t + 3f, l + cellSize - 3f, t + cellSize - 3f)
            val occupied = board[r][c] != 0
            val flashing = (r in clearingRows || c in clearingCols) && occupied

            when {
                flashing -> {
                    blockPaint.color = lerpColor(board[r][c], Color.WHITE, clearFlash)
                    canvas.drawRoundRect(rrPiece, 10f, 10f, blockPaint)
                }
                occupied -> drawFilledCell(canvas, l, t, board[r][c], cellSize)
                else -> {
                    cellPaint.color = CELL_EMPTY
                    canvas.drawRoundRect(rrPiece, 10f, 10f, cellPaint)
                }
            }

            if (draggingIdx >= 0 && ghostRow >= 0 && canPlace && !occupied) {
                val p = currentPieces[draggingIdx]
                if (p != null && p.blocks.any { b -> ghostRow+b.row==r && ghostCol+b.col==c }) {
                    ghostPaint.color = p.color; ghostPaint.alpha = 130
                    canvas.drawRoundRect(rrPiece, 10f, 10f, ghostPaint)
                    ghostPaint.alpha = 255
                }
            }
        }
    }

    private fun drawFilledCell(canvas: Canvas, l: Float, t: Float, color: Int, cs: Float) {
        rrPiece.set(l + 3f, t + 3f, l + cs - 3f, t + cs - 3f)
        blockPaint.color = color
        canvas.drawRoundRect(rrPiece, 10f, 10f, blockPaint)
        // Subtle shine
        shinePaint.shader = LinearGradient(
            l, t, l, t + cs * 0.5f, 0x30FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(rrPiece, 10f, 10f, shinePaint)
    }

    private fun drawPieceSlots(canvas: Canvas) {
        val slotW = width / PIECE_SLOTS.toFloat()
        for (i in 0 until PIECE_SLOTS) {
            if (i == draggingIdx) continue
            val p = currentPieces[i] ?: continue
            drawPieceAt(canvas, p, slotW * i + slotW / 2f, pieceAreaCenterY, previewCellSize)
        }
    }

    private fun drawPieceAt(canvas: Canvas, piece: Piece, cx: Float, cy: Float, cs: Float) {
        val sx = cx - piece.width * cs / 2f; val sy = cy - piece.height * cs / 2f
        for (b in piece.blocks) {
            val l = sx + b.col * cs; val t = sy + b.row * cs
            val r = RectF(l + 2f, t + 2f, l + cs - 2f, t + cs - 2f)
            blockPaint.color = piece.color
            canvas.drawRoundRect(r, 9f, 9f, blockPaint)
            shinePaint.shader = LinearGradient(l, t, l, t + cs * 0.5f, 0x30FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
            canvas.drawRoundRect(r, 9f, 9f, shinePaint)
        }
    }

    private fun drawDragPiece(canvas: Canvas) {
        val piece = currentPieces[draggingIdx] ?: return
        val cs = cellSize * 1.1f
        val sx = dragX - piece.width * cs / 2f
        val sy = dragY - piece.height * cs / 2f - cs * 0.6f
        for (b in piece.blocks) {
            val l = sx + b.col * cs; val t = sy + b.row * cs
            val r = RectF(l + 2f, t + 2f, l + cs - 2f, t + cs - 2f)
            // Shadow
            shadowPaint.color = 0x22000000
            shadowPaint.maskFilter = BlurMaskFilter(14f, BlurMaskFilter.Blur.NORMAL)
            canvas.drawRoundRect(r.apply { offset(4f, 8f) }, 10f, 10f, shadowPaint)
            r.offset(-4f, -8f)
            blockPaint.color = piece.color
            canvas.drawRoundRect(r, 10f, 10f, blockPaint)
            shinePaint.shader = LinearGradient(l, t, l, t + cs * 0.5f, 0x30FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
            canvas.drawRoundRect(r, 10f, 10f, shinePaint)
        }
    }

    private fun drawUndoButton(canvas: Canvas) {
        bgPaint.color = UNDO_BG
        val r = RectF(undoBtnLeft, undoBtnTop, undoBtnRight, undoBtnBottom)
        canvas.drawRoundRect(r, (undoBtnBottom - undoBtnTop) / 2f, (undoBtnBottom - undoBtnTop) / 2f, bgPaint)

        labelPaint.color = UNDO_TEXT
        labelPaint.textSize = height * 0.02f
        labelPaint.textAlign = Paint.Align.CENTER
        val cx = (undoBtnLeft + undoBtnRight) / 2f
        val cy = (undoBtnTop + undoBtnBottom) / 2f

        // Draw undo arrow (simple text symbol)
        val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = UNDO_TEXT; textSize = height * 0.035f; textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        canvas.drawText("↩", cx - 60f, cy + height * 0.013f, arrowPaint)
        labelPaint.textSize = height * 0.022f
        canvas.drawText("UNDO", cx + 20f, cy + height * 0.008f, labelPaint)
    }

    private fun drawGameOver(canvas: Canvas) {
        bgPaint.color = 0xBBF0F2F7.toInt()
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        val cx = width / 2f; val cy = height / 2f
        bgPaint.color = Color.WHITE
        val card = RectF(cx - 230f, cy - 200f, cx + 230f, cy + 200f)
        canvas.drawRoundRect(card, 32f, 32f, bgPaint)

        // Score
        textPaint.color = SCORE_COLOR; textPaint.textSize = height * 0.09f
        canvas.drawText(formatScore(score), cx, cy - 40f, textPaint)
        labelPaint.color = LABEL_COLOR; labelPaint.textSize = height * 0.024f
        labelPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("GAME OVER", cx, cy - 100f, labelPaint)

        // Restart button
        bgPaint.color = 0xFF4A6FA5.toInt()
        canvas.drawRoundRect(cx - 150f, cy + 80f, cx + 150f, cy + 155f, 37f, 37f, bgPaint)
        val rPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = height * 0.032f
            typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
        }
        canvas.drawText("RESTART", cx, cy + 130f, rPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isGameOver) {
            if (event.action == MotionEvent.ACTION_UP) resetGame()
            return true
        }
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // Check undo button
                if (event.x in undoBtnLeft..undoBtnRight && event.y in undoBtnTop..undoBtnBottom) {
                    doUndo(); return true
                }
                onDown(event.x, event.y)
            }
            MotionEvent.ACTION_MOVE -> onMove(event.x, event.y)
            MotionEvent.ACTION_UP   -> onUp()
        }
        return true
    }

    private fun onDown(x: Float, y: Float) {
        val boardBottom = boardTop + cellSize * BOARD_SIZE
        if (y >= boardBottom) {
            val idx = (x / (width / PIECE_SLOTS.toFloat())).toInt().coerceIn(0, PIECE_SLOTS - 1)
            if (currentPieces[idx] != null) {
                draggingIdx = idx; dragX = x; dragY = y; updateGhost(); invalidate()
            }
        }
    }
    private fun onMove(x: Float, y: Float) { if (draggingIdx < 0) return; dragX = x; dragY = y; updateGhost(); invalidate() }
    private fun onUp() {
        if (draggingIdx < 0) return
        val piece = currentPieces[draggingIdx]
        if (piece != null && ghostRow >= 0 && canPlace) {
            saveUndo()
            placePiece(piece, ghostRow, ghostCol)
            currentPieces[draggingIdx] = null
            if (currentPieces.all { it == null }) refillPieces()
        } else { combo = 0; showCombo = false }
        draggingIdx = -1; ghostRow = -1; canPlace = false; invalidate()
    }

    private fun updateGhost() {
        val piece = currentPieces[draggingIdx] ?: return
        val liftY = dragY - cellSize * 0.6f
        ghostCol = ((dragX - boardLeft) / cellSize - piece.width / 2f + 0.5f).toInt()
        ghostRow = ((liftY - boardTop)  / cellSize - piece.height / 2f + 0.5f).toInt()
        canPlace = liftY > boardTop && liftY < boardTop + cellSize * BOARD_SIZE
                && canPlacePiece(piece, ghostRow, ghostCol)
    }

    private fun canPlacePiece(piece: Piece, row: Int, col: Int): Boolean {
        for (b in piece.blocks) {
            val r = row + b.row; val c = col + b.col
            if (r !in 0 until BOARD_SIZE || c !in 0 until BOARD_SIZE || board[r][c] != 0) return false
        }
        return true
    }

    private fun placePiece(piece: Piece, row: Int, col: Int) {
        for (b in piece.blocks) board[row + b.row][col + b.col] = piece.color
        score += piece.blocks.size
        checkAndClearLines()
        saveBest()
    }

    private fun checkAndClearLines() {
        val rows = (0 until BOARD_SIZE).filter { r -> (0 until BOARD_SIZE).all { c -> board[r][c] != 0 } }
        val cols = (0 until BOARD_SIZE).filter { c -> (0 until BOARD_SIZE).all { r -> board[r][c] != 0 } }
        if (rows.isEmpty() && cols.isEmpty()) {
            combo = 0; showCombo = false
            // ← ხაზები არ გაისუფთავა, ახლა შეამოწმე
            if (checkGameOver()) isGameOver = true
            invalidate()
            return
        }

        combo++
        val multiplier = if (combo > 1) combo else 1
        score += (rows.size + cols.size) * BOARD_SIZE * multiplier
        saveBest()
        showCombo = combo >= 2
        if (showCombo) animateCombo()

        clearingRows.addAll(rows); clearingCols.addAll(cols)
        ValueAnimator.ofFloat(0f, 1f, 0f).apply {
            duration = 350
            addUpdateListener { clearFlash = it.animatedValue as Float; invalidate() }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    rows.forEach { r -> for (c in 0 until BOARD_SIZE) board[r][c] = 0 }
                    cols.forEach { c -> for (r in 0 until BOARD_SIZE) board[r][c] = 0 }
                    clearingRows.clear(); clearingCols.clear(); clearFlash = 0f
                    // ← board გაიწმინდა, ახლა შეამოწმე
                    if (checkGameOver()) isGameOver = true
                    invalidate()
                }
            })
        }.start()
    }

    private fun animateCombo() {
        ValueAnimator.ofFloat(0f, 1f, 1f, 0f).apply {
            duration = 1500
            addUpdateListener { comboAlpha = it.animatedValue as Float; invalidate() }
        }.start()
    }

    private fun saveUndo() {
        prevBoard = Array(BOARD_SIZE) { board[it].copyOf() }
        prevScore = score
        for (i in 0 until PIECE_SLOTS) prevPieces[i] = currentPieces[i]
    }

    private fun doUndo() {
        val pb = prevBoard ?: return
        for (r in 0 until BOARD_SIZE) board[r] = pb[r].copyOf()
        score = prevScore
        for (i in 0 until PIECE_SLOTS) currentPieces[i] = prevPieces[i]
        prevBoard = null; combo = 0; showCombo = false
        invalidate()
    }

    private fun checkGameOver(): Boolean {
        val activePieces = currentPieces.filterNotNull()
        if (activePieces.isEmpty()) return false
        for (p in activePieces)
            for (r in 0 until BOARD_SIZE) for (c in 0 until BOARD_SIZE) if (canPlacePiece(p, r, c)) return false
        return true
    }

    private fun refillPieces() {
        for (i in 0 until PIECE_SLOTS)
            if (currentPieces[i] == null) currentPieces[i] = getRandomPiece()
    }

    private fun resetGame() {
        for (r in 0 until BOARD_SIZE) board[r].fill(0)
        for (i in 0 until PIECE_SLOTS) currentPieces[i] = null
        score = 0; combo = 0; showCombo = false; isGameOver = false
        prevBoard = null; refillPieces(); invalidate()
    }

    private fun saveBest() {
        if (score > bestScore) {
            bestScore = score
            context.getSharedPreferences("bb_prefs", Context.MODE_PRIVATE).edit().putInt("best", bestScore).apply()
        }
    }

    private fun formatScore(n: Int): String {
        return if (n >= 1000) "${n/1000},${"%03d".format(n % 1000)}" else "$n"
    }

    private fun lerpColor(c1: Int, c2: Int, t: Float): Int {
        val inv = 1f - t
        return Color.argb(255,
            (Color.red(c1)*inv   + Color.red(c2)*t).toInt(),
            (Color.green(c1)*inv + Color.green(c2)*t).toInt(),
            (Color.blue(c1)*inv  + Color.blue(c2)*t).toInt())
    }

    private fun adjustAlpha(color: Int, alpha: Float): Int {
        return Color.argb((alpha * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color))
    }
}