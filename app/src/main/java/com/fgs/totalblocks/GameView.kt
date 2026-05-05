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
    private var draggingIdx = -1
    private var dragX = 0f; private var dragY = 0f
    private var ghostRow = -1; private var ghostCol = -1
    private var canPlace = false
    private var boardLeft = 0f; private var boardTop = 0f
    private var cellSize = 0f; private var previewCellSize = 0f
    private var pieceAreaCenterY = 0f
    private val clearingRows = mutableSetOf<Int>()
    private val clearingCols = mutableSetOf<Int>()
    private var clearFlash = 0f
    private var isGameOver = false

    private val bgPaint    = Paint()
    private val cellPaint  = Paint()
    private val blockPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shinePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ghostPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { alpha = 110 }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        maskFilter = BlurMaskFilter(12f, BlurMaskFilter.Blur.NORMAL)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
    }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFAAAAAA.toInt(); textAlign = Paint.Align.CENTER
    }
    private val rrPiece = RectF()

    init {
        refillPieces()
        bestScore = context.getSharedPreferences("bb_prefs", Context.MODE_PRIVATE).getInt("best", 0)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val pad = w * 0.04f
        cellSize = (w - pad * 2) / BOARD_SIZE
        previewCellSize = cellSize * 0.72f
        boardLeft = pad; boardTop = h * 0.13f
        pieceAreaCenterY = (boardTop + cellSize * BOARD_SIZE) + (h - boardTop - cellSize * BOARD_SIZE) * 0.52f
        textPaint.textSize = h * 0.042f; subPaint.textSize = h * 0.024f
    }

    override fun onDraw(canvas: Canvas) {
        drawBg(canvas); drawHUD(canvas); drawBoard(canvas); drawPieceSlots(canvas)
        if (draggingIdx >= 0) drawDragPiece(canvas)
        if (isGameOver) drawGameOver(canvas)
    }

    private fun drawBg(canvas: Canvas) {
        bgPaint.color = 0xFF0D0D1A.toInt()
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
    }

    private fun drawHUD(canvas: Canvas) {
        val cx = width / 2f
        textPaint.color = Color.WHITE
        canvas.drawText("$score", cx - width * 0.22f, boardTop * 0.58f, textPaint)
        textPaint.color = 0xFFFFD700.toInt()
        canvas.drawText("$bestScore", cx + width * 0.22f, boardTop * 0.58f, textPaint)
        canvas.drawText("SCORE", cx - width * 0.22f, boardTop * 0.85f, subPaint)
        canvas.drawText("BEST", cx + width * 0.22f, boardTop * 0.85f, subPaint)
    }

    private fun drawBoard(canvas: Canvas) {
        bgPaint.color = 0xFF141428.toInt()
        val br = boardLeft + cellSize * BOARD_SIZE; val bb = boardTop + cellSize * BOARD_SIZE
        canvas.drawRoundRect(boardLeft - 3f, boardTop - 3f, br + 3f, bb + 3f, 14f, 14f, bgPaint)
        for (r in 0 until BOARD_SIZE) for (c in 0 until BOARD_SIZE) {
            val l = boardLeft + c * cellSize; val t = boardTop + r * cellSize
            rrPiece.set(l + 2.5f, t + 2.5f, l + cellSize - 2.5f, t + cellSize - 2.5f)
            val occupied = board[r][c] != 0
            when {
                (r in clearingRows || c in clearingCols) && occupied -> {
                    blockPaint.color = lerpColor(board[r][c], Color.WHITE, clearFlash)
                    canvas.drawRoundRect(rrPiece, 7f, 7f, blockPaint)
                }
                occupied -> drawFilledCell(canvas, l, t, board[r][c], cellSize)
                else -> { cellPaint.color = 0xFF1A1A30.toInt(); canvas.drawRoundRect(rrPiece, 7f, 7f, cellPaint) }
            }
            if (draggingIdx >= 0 && ghostRow >= 0 && canPlace && !occupied) {
                val p = currentPieces[draggingIdx]
                if (p != null && p.blocks.any { b -> ghostRow + b.row == r && ghostCol + b.col == c }) {
                    ghostPaint.color = p.color; canvas.drawRoundRect(rrPiece, 7f, 7f, ghostPaint)
                }
            }
        }
    }

    private fun drawFilledCell(canvas: Canvas, l: Float, t: Float, color: Int, cs: Float) {
        rrPiece.set(l + 2.5f, t + 2.5f, l + cs - 2.5f, t + cs - 2.5f)
        blockPaint.color = color; canvas.drawRoundRect(rrPiece, 7f, 7f, blockPaint)
        shinePaint.shader = LinearGradient(l, t, l, t + cs * 0.45f, 0x55FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(rrPiece, 7f, 7f, shinePaint)
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
            val r = RectF(l + 1.5f, t + 1.5f, l + cs - 1.5f, t + cs - 1.5f)
            blockPaint.color = piece.color; canvas.drawRoundRect(r, 6f, 6f, blockPaint)
            shinePaint.shader = LinearGradient(l, t, l, t + cs * 0.45f, 0x55FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
            canvas.drawRoundRect(r, 6f, 6f, shinePaint)
        }
    }

    private fun drawDragPiece(canvas: Canvas) {
        val piece = currentPieces[draggingIdx] ?: return
        val cs = cellSize * 1.15f
        val sx = dragX - piece.width * cs / 2f; val sy = dragY - piece.height * cs / 2f - cs * 0.7f
        for (b in piece.blocks) {
            val l = sx + b.col * cs; val t = sy + b.row * cs
            val r = RectF(l + 2f, t + 2f, l + cs - 2f, t + cs - 2f)
            shadowPaint.color = 0x66000000
            canvas.drawRoundRect(r.apply { offset(5f, 8f) }, 7f, 7f, shadowPaint); r.offset(-5f, -8f)
            blockPaint.color = piece.color; canvas.drawRoundRect(r, 7f, 7f, blockPaint)
            shinePaint.shader = LinearGradient(l, t, l, t + cs * 0.45f, 0x55FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
            canvas.drawRoundRect(r, 7f, 7f, shinePaint)
        }
    }

    private fun drawGameOver(canvas: Canvas) {
        bgPaint.color = 0xCC000000.toInt(); canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        val cx = width / 2f; val cy = height / 2f
        bgPaint.color = 0xFF1A1A35.toInt()
        canvas.drawRoundRect(cx - 220f, cy - 180f, cx + 220f, cy + 180f, 28f, 28f, bgPaint)
        bgPaint.color = 0xFFE74C3C.toInt()
        canvas.drawRoundRect(cx - 220f, cy - 180f, cx + 220f, cy - 148f, 28f, 28f, bgPaint)
        textPaint.textSize = height * 0.052f; textPaint.color = Color.WHITE
        canvas.drawText("GAME OVER", cx, cy - 118f, textPaint)
        textPaint.textSize = height * 0.038f; textPaint.color = 0xFFAAAAAA.toInt()
        canvas.drawText("Score", cx, cy - 40f, textPaint)
        textPaint.textSize = height * 0.07f; textPaint.color = Color.WHITE
        canvas.drawText("$score", cx, cy + 30f, textPaint)
        bgPaint.color = 0xFF2ECC71.toInt()
        canvas.drawRoundRect(cx - 140f, cy + 80f, cx + 140f, cy + 148f, 34f, 34f, bgPaint)
        textPaint.textSize = height * 0.036f
        canvas.drawText("RESTART", cx, cy + 124f, textPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isGameOver) { if (event.action == MotionEvent.ACTION_UP) resetGame(); return true }
        when (event.action) {
            MotionEvent.ACTION_DOWN -> onDown(event.x, event.y)
            MotionEvent.ACTION_MOVE -> onMove(event.x, event.y)
            MotionEvent.ACTION_UP   -> onUp()
        }
        return true
    }

    private fun onDown(x: Float, y: Float) {
        if (y >= boardTop + cellSize * BOARD_SIZE) {
            val idx = (x / (width / PIECE_SLOTS.toFloat())).toInt().coerceIn(0, PIECE_SLOTS - 1)
            if (currentPieces[idx] != null) { draggingIdx = idx; dragX = x; dragY = y; updateGhost(); invalidate() }
        }
    }
    private fun onMove(x: Float, y: Float) { if (draggingIdx < 0) return; dragX = x; dragY = y; updateGhost(); invalidate() }
    private fun onUp() {
        if (draggingIdx < 0) return
        val piece = currentPieces[draggingIdx]
        if (piece != null && ghostRow >= 0 && canPlace) {
            placePiece(piece, ghostRow, ghostCol)
            currentPieces[draggingIdx] = null
            if (currentPieces.all { it == null }) refillPieces()
            if (checkGameOver()) isGameOver = true
        }
        draggingIdx = -1; ghostRow = -1; canPlace = false; invalidate()
    }

    private fun updateGhost() {
        val piece = currentPieces[draggingIdx] ?: return
        val liftY = dragY - cellSize * 0.7f
        ghostCol = ((dragX - boardLeft) / cellSize - piece.width / 2f + 0.5f).toInt()
        ghostRow = ((liftY - boardTop) / cellSize - piece.height / 2f + 0.5f).toInt()
        canPlace = liftY > boardTop && liftY < boardTop + cellSize * BOARD_SIZE && canPlacePiece(piece, ghostRow, ghostCol)
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
        score += piece.blocks.size; checkAndClearLines(); saveBest()
    }

    private fun checkAndClearLines() {
        val rows = (0 until BOARD_SIZE).filter { r -> (0 until BOARD_SIZE).all { c -> board[r][c] != 0 } }
        val cols = (0 until BOARD_SIZE).filter { c -> (0 until BOARD_SIZE).all { r -> board[r][c] != 0 } }
        if (rows.isEmpty() && cols.isEmpty()) return
        score += (rows.size + cols.size) * BOARD_SIZE; saveBest()
        clearingRows.addAll(rows); clearingCols.addAll(cols)
        ValueAnimator.ofFloat(0f, 1f, 0f).apply {
            duration = 400
            addUpdateListener { clearFlash = it.animatedValue as Float; invalidate() }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    rows.forEach { r -> for (c in 0 until BOARD_SIZE) board[r][c] = 0 }
                    cols.forEach { c -> for (r in 0 until BOARD_SIZE) board[r][c] = 0 }
                    clearingRows.clear(); clearingCols.clear(); clearFlash = 0f; invalidate()
                }
            })
        }.start()
    }

    private fun checkGameOver(): Boolean {
        for (i in 0 until PIECE_SLOTS) {
            val p = currentPieces[i] ?: continue
            for (r in 0 until BOARD_SIZE) for (c in 0 until BOARD_SIZE) if (canPlacePiece(p, r, c)) return false
        }
        return true
    }

    private fun refillPieces() { for (i in 0 until PIECE_SLOTS) if (currentPieces[i] == null) currentPieces[i] = PieceFactory.random() }

    private fun resetGame() {
        for (r in 0 until BOARD_SIZE) board[r].fill(0)
        for (i in 0 until PIECE_SLOTS) currentPieces[i] = null
        score = 0; isGameOver = false; refillPieces(); invalidate()
    }

    private fun saveBest() {
        if (score > bestScore) { bestScore = score
            context.getSharedPreferences("bb_prefs", Context.MODE_PRIVATE).edit().putInt("best", bestScore).apply() }
    }

    private fun lerpColor(c1: Int, c2: Int, t: Float): Int {
        val inv = 1f - t
        return Color.argb(255, (Color.red(c1)*inv + Color.red(c2)*t).toInt(),
            (Color.green(c1)*inv + Color.green(c2)*t).toInt(), (Color.blue(c1)*inv + Color.blue(c2)*t).toInt())
    }
}