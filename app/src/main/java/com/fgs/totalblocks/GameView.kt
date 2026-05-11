package com.fgs.totalblocks

import android.animation.*
import android.content.Context
import android.graphics.*
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * GameView: Modified Version v5.
 * Fixed vibration toggle persistence and thread safety.
 */
class GameView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        const val BOARD_SIZE    = 8
        const val PIECE_SLOTS   = 3
        const val RING_INTERVAL = 1000
        const val GRAVITY_STEP   = 1000
        const val TRIPLE_STEP    = 5000
        const val CLEAR_ALL_STEP = 10000
    }

    var onMenuClicked: (() -> Unit)? = null

    @Volatile
    var isVibrationEnabled: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                prefs().edit().putBoolean("vibration_enabled", value).apply()
            }
        }

    @Volatile
    var isSoundEnabled: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                prefs().edit().putBoolean("sound_enabled", value).apply()
            }
        }

    data class Block(val row: Int, val col: Int)
    data class Piece(val blocks: List<Block>, val color: Int) {
        val width: Int  by lazy { blocks.maxOf { it.col } - blocks.minOf { it.col } + 1 }
        val height: Int by lazy { blocks.maxOf { it.row } - blocks.minOf { it.row } + 1 }
    }

    object PieceFactory {
        private val templates = listOf(
            listOf(Block(0,0), Block(0,1)), // 2x1
            listOf(Block(0,0), Block(1,0)), // 1x2
            listOf(Block(0,0), Block(0,1), Block(0,2)), // 3x1
            listOf(Block(0,0), Block(1,0), Block(2,0)), // 1x3
            listOf(Block(0,0), Block(0,1), Block(1,0), Block(1,1)), // 2x2
            listOf(Block(0,0), Block(1,0), Block(1,1)), // L-small
            listOf(Block(0,0), Block(1,0), Block(2,0), Block(2,1)), // L-3x2
            listOf(Block(0,0), Block(0,1), Block(0,2), Block(1,1)), // T-shape
            listOf(Block(0,0), Block(1,0), Block(1,1), Block(2,1)), // Z-shape
            listOf(Block(0,0), Block(0,1), Block(0,2), Block(1,0), Block(1,1), Block(1,2), Block(2,0), Block(2,1), Block(2,2)), // 3x3 Square
            listOf(Block(0,0), Block(0,1), Block(0,2), Block(1,0), Block(1,1), Block(1,2)), // 2x3 Rectangle
            listOf(Block(0,0), Block(1,0), Block(2,0), Block(2,1), Block(2,2)), // 3x3 L-shape
            listOf(Block(0,0), Block(0,1), Block(0,2), Block(0,3)), // 1x4
            listOf(Block(0,0), Block(1,0), Block(2,0), Block(3,0)), // 4x1
            listOf(Block(0,0), Block(0,1), Block(0,2), Block(0,3), Block(0,4)), // 1x5
            listOf(Block(0,0), Block(1,0), Block(2,0), Block(3,0), Block(4,0))  // 5x1
        )

        fun random(): Piece = Piece(templates.random(), 0)

        fun smartRandom(board: Array<IntArray>): Piece {
            val shuffled = templates.shuffled()
            for (template in shuffled) {
                val p = Piece(template, 0)
                for (r in 0..BOARD_SIZE - p.height) {
                    for (c in 0..BOARD_SIZE - p.width) {
                        if (canFit(p, r, c, board)) return p
                    }
                }
            }
            return random()
        }

        private fun canFit(p: Piece, row: Int, col: Int, board: Array<IntArray>): Boolean {
            for (b in p.blocks) {
                if (board[row + b.row][col + b.col] != 0) return false
            }
            return true
        }
    }

    private val board        = Array(BOARD_SIZE) { IntArray(BOARD_SIZE) { 0 } }
    private val currentPieces = arrayOfNulls<Piece>(PIECE_SLOTS)
    private val nextPieces    = arrayOfNulls<Piece>(PIECE_SLOTS)

    private var score    = 0
    private var bestScore = 0
    private var comboLevel = 0
    private var movesSinceLastClear = 0

    private var ringFill       = 0f
    private var prevRingLevel  = 0
    private var ringFlash      = 0f

    private var gravityCharges = 0
    private var tripleCharges  = 0
    private var clearAllCharges = 0
    private var lastGravityEarnedAt = 0
    private var lastTripleEarnedAt  = 0
    private var lastClearAllEarnedAt = 0
    private val abilityRects   = Array(3) { RectF() }

    private val ghostRows = mutableSetOf<Int>()
    private val ghostCols = mutableSetOf<Int>()
    private var draggingIdx = -1
    private var dragX = 0f; private var dragY = 0f
    private var ghostRow = -1; private var ghostCol = -1
    private var canPlace = false

    private var boardLeft = 0f; private var boardTop  = 0f
    private var cellSize  = 0f; private var previewCS = 0f
    private var pieceAreaCY = 0f
    private val undoRect = RectF(); private val menuRect = RectF()
    private val scoreRect = RectF()
    private val topBarRect = RectF()

    private var prevBoard:  Array<IntArray>? = null
    private var prevScore   = 0
    private var prevPieces: Array<Piece?>   = arrayOfNulls(PIECE_SLOTS)
    private var prevNextPieces: Array<Piece?> = arrayOfNulls(PIECE_SLOTS)

    private val clearRows = mutableSetOf<Int>()
    private val clearCols = mutableSetOf<Int>()
    private var clearFlash    = 0f
    private var clearScale    = 1f
    private var clearRunning  = false

    private var isGameOver = false
    private var isScoreVisible = true
    private var flagBitmap: Bitmap? = null
    private var undoBitmap: Bitmap? = null
    private var hideBitmap: Bitmap? = null
    private var menuBitmap: Bitmap? = null
    private var cleanBitmap: Bitmap? = null
    private var gravityBitmap: Bitmap? = null
    private var thunderBitmap: Bitmap? = null

    private var soundPool: SoundPool? = null
    private var desapearSoundId: Int = 0
    private var comboSounds = IntArray(5)

    private val vibrator: Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager).defaultVibrator
        else context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }.getOrNull()

    private val COLOR_PRIMARY     = 0xFF215FA6.toInt()
    private val COLOR_SECONDARY   = 0xFF336857.toInt()
    private val COLOR_SURFACE_CON = 0xFFEDEEEF.toInt()
    private val COLOR_ON_SURFACE  = 0xFF191C1D.toInt()
    private val COLOR_ON_SURFACE_V = 0xFF424751.toInt()
    private val COLOR_RING_TRACK  = 0x15000000
    private val COLOR_COMBO_BG    = 0xCCFFDAD6.toInt()
    private val COLOR_COMBO_FG    = 0xFF69333A.toInt()
    private val COLOR_ABILITY_FILL = 0x33215FA6.toInt()

    private val PASTEL = listOf(0xFF0014E0.toInt(), 0xFFFF3636.toInt(), 0xFFFFA536.toInt(), 0xFF0FBA00.toInt(), 0xFF2FA4D7.toInt(), 0xFFE76F2E.toInt())

    private val bgP     = Paint()
    private val cellP   = Paint(Paint.ANTI_ALIAS_FLAG)
    private val blockP  = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ghostP  = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shineP  = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringP   = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeWidth = 40f
    }
    private val txtP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val lblP = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val iconP = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    private val rrRect = RectF()
    private val tempRect = RectF()
    private val ringRect = RectF()
    private val ringPath = Path()
    private val ringDstPath = Path()
    private val ringPathMeasure = PathMeasure()

    init {
        bestScore = prefs().getInt("best", 0)
        isVibrationEnabled = prefs().getBoolean("vibration_enabled", true)
        isSoundEnabled = prefs().getBoolean("sound_enabled", true)
        loadBitmaps()
        initSounds()
        
        if (!loadGameState()) {
            resetGame()
        }
    }

    private fun initSounds() {
        val attr = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        soundPool = SoundPool.Builder()
            .setMaxStreams(5)
            .setAudioAttributes(attr)
            .build()
        desapearSoundId = soundPool?.load(context, R.raw.desapear, 1) ?: 0
        comboSounds[0] = soundPool?.load(context, R.raw.combo2, 1) ?: 0
        comboSounds[1] = soundPool?.load(context, R.raw.combo3, 1) ?: 0
        comboSounds[2] = soundPool?.load(context, R.raw.combo4, 1) ?: 0
        comboSounds[3] = soundPool?.load(context, R.raw.combo5, 1) ?: 0
        comboSounds[4] = soundPool?.load(context, R.raw.combo6, 1) ?: 0
    }

    private fun loadBitmaps() {
        try {
            val res = context.resources
            val pkg = context.packageName
            fun load(name: String): Bitmap? {
                val id = res.getIdentifier(name, "drawable", pkg)
                return if (id != 0) BitmapFactory.decodeResource(res, id) else null
            }
            flagBitmap    = load("flag")
            undoBitmap    = load("undo")
            hideBitmap    = load("hide")
            menuBitmap    = load("menu")
            cleanBitmap   = load("clean")
            gravityBitmap = load("gravity")
            thunderBitmap = load("thunder")
        } catch (e: Exception) { e.printStackTrace() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val pad = w * 0.08f
        cellSize  = (w - pad * 2) / BOARD_SIZE
        previewCS = cellSize * 0.45f
        boardLeft = pad
        boardTop  = h * 0.35f

        val boardBottom = boardTop + cellSize * BOARD_SIZE
        pieceAreaCY = boardBottom + (h - boardBottom) * 0.25f

        val topY = h * 0.10f
        val topH = h * 0.04f
        topBarRect.set(pad, topY, w - pad, topY + topH)
        menuRect.set(w - pad - topH, topY, w - pad, topY + topH)

        val iconSize = w * 0.12f
        val startX = w * 0.10f
        val gap = w * 0.18f
        val yPos = h * 0.88f
        for (i in 0..2) abilityRects[i].set(startX + i * gap, yPos - iconSize/2, startX + i * gap + iconSize, yPos + iconSize/2)
        val undoW = w * 0.18f
        undoRect.set(w * 0.72f, yPos - iconSize/2, w * 0.72f + undoW, yPos + iconSize/2)

        txtP.textSize = h * 0.065f
        lblP.textSize = h * 0.018f
        iconP.textSize = iconSize * 0.5f

        val m = 20f
        ringRect.set(boardLeft - m, boardTop - m, boardLeft + cellSize*BOARD_SIZE + m, boardTop + cellSize*BOARD_SIZE + m)
    }

    override fun onDraw(canvas: Canvas) {
        drawBg(canvas)
        drawHUD(canvas)
        drawRing(canvas)
        drawBoard(canvas)
        drawSlots(canvas)
        drawAbilities(canvas)
        drawUndo(canvas)
        if (draggingIdx >= 0) drawDrag(canvas)
        if (isGameOver)       drawGameOver(canvas)
    }

    private fun drawBg(c: Canvas) {
        val isDarkMode = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val color1 = if (isDarkMode) 0xFF121212.toInt() else 0xFFF0F4F8.toInt()
        val color2 = if (isDarkMode) 0xFF1E1E1E.toInt() else Color.WHITE
        bgP.shader = LinearGradient(0f, 0f, 0f, height.toFloat(), color1, color2, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgP)
        bgP.shader = null
    }

    private fun drawHUD(canvas: Canvas) {
        val isDarkMode = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val textColor = if (isDarkMode) Color.WHITE else Color.BLACK

        val iconSize = topBarRect.height() * 0.8f
        flagBitmap?.let {
            tempRect.set(topBarRect.left, topBarRect.centerY() - iconSize/2, topBarRect.left + iconSize, topBarRect.centerY() + iconSize/2)
            canvas.drawBitmap(it, null, tempRect, iconP)
        }

        txtP.color = COLOR_PRIMARY; txtP.textAlign = Paint.Align.LEFT; txtP.textSize = height * 0.018f
        canvas.drawText(scoreNoFmt(bestScore), topBarRect.left + iconSize + 15f, topBarRect.centerY() + txtP.textSize * 0.35f, txtP)

        menuBitmap?.let {
            iconP.alpha = 255
            canvas.drawBitmap(it, null, menuRect, iconP)
        }

        val scoreY = boardTop * 0.75f
        txtP.textSize = height * 0.055f; txtP.color = textColor; txtP.textAlign = Paint.Align.CENTER
        if (isScoreVisible) {
            canvas.drawText(scoreNoFmt(score), width / 2f, scoreY, txtP)
        } else {
            hideBitmap?.let {
                val hSize = txtP.textSize * 0.6f
                tempRect.set(width/2f - hSize/2, scoreY - hSize*0.75f, width/2f + hSize/2, scoreY + hSize*0.25f)
                canvas.drawBitmap(it, null, tempRect, iconP)
            }
        }
        scoreRect.set(width/2f - 200f, scoreY - 150f, width/2f + 200f, scoreY + 80f)

        drawCombo(canvas)
    }

    private fun drawCombo(canvas: Canvas) {
        if (comboLevel < 1) return
        val cx = width / 2f; val cy = boardTop * 0.88f
        lblP.textSize = height * 0.018f; lblP.textAlign = Paint.Align.CENTER; lblP.typeface = Typeface.DEFAULT_BOLD
        val text = "COMBO x$comboLevel"
        val tw = lblP.measureText(text)
        val bh = height * 0.035f; val iconW = bh * 0.55f
        val bw = tw + iconW + 60f
        tempRect.set(cx - bw/2, cy - bh/2, cx + bw/2, cy + bh/2)
        bgP.color = COLOR_COMBO_BG; canvas.drawRoundRect(tempRect, bh/2, bh/2, bgP)

        thunderBitmap?.let {
            val dst = RectF(cx - bw/2 + 20f, cy - iconW/2, cx - bw/2 + 20f + iconW, cy + iconW/2)
            canvas.drawBitmap(it, null, dst, iconP)
        }

        lblP.color = COLOR_COMBO_FG; canvas.drawText(text, cx + iconW/2, cy + lblP.textSize * 0.35f, lblP)
        lblP.typeface = Typeface.DEFAULT
    }

    private fun drawRing(canvas: Canvas) {
        val rad = 42f
        ringP.color = COLOR_RING_TRACK; ringP.strokeWidth = 40f
        canvas.drawRoundRect(ringRect, rad, rad, ringP)

        if (ringFill <= 0f && ringFlash <= 0f) return
        ringP.color = if (ringFlash > 0f) lerp(COLOR_SECONDARY, Color.WHITE, ringFlash) else COLOR_SECONDARY

        ringPath.reset()
        ringPath.addRoundRect(ringRect, rad, rad, Path.Direction.CW)
        ringPathMeasure.setPath(ringPath, false)
        val total = ringPathMeasure.length
        
        // Target: Start from bottom center, go left & right, meet at top center.
        // On a RoundRect CW path, bottom center is at 0.5 * total + start_offset.
        // Let's adjust offset so 0 is top center.
        val topCenter = total * 0.125f // Approximate
        val bottomCenter = (topCenter + total * 0.5f) % total
        val halfLen = (ringFill * total / 2f).coerceIn(0f, total / 2f)

        ringDstPath.reset()
        
        // helper to draw segment with wrap-around
        fun addSegment(s: Float, e: Float) {
            val start = (s % total + total) % total
            val end = (e % total + total) % total
            if (start < end) {
                ringPathMeasure.getSegment(start, end, ringDstPath, true)
            } else {
                ringPathMeasure.getSegment(start, total, ringDstPath, true)
                ringPathMeasure.getSegment(0f, end, ringDstPath, true)
            }
        }

        // Draw left half (CCW from bottom center)
        addSegment(bottomCenter - halfLen, bottomCenter)
        // Draw right half (CW from bottom center)
        addSegment(bottomCenter, bottomCenter + halfLen)

        canvas.drawPath(ringDstPath, ringP)
    }

    private fun drawBoard(canvas: Canvas) {
        val br = boardLeft + cellSize*BOARD_SIZE; val bb = boardTop + cellSize*BOARD_SIZE
        bgP.color = COLOR_SURFACE_CON; canvas.drawRoundRect(boardLeft-8f, boardTop-8f, br+8f, bb+8f, 32f, 32f, bgP)
        for (r in 0 until BOARD_SIZE) for (c in 0 until BOARD_SIZE) {
            val l = boardLeft + c*cellSize; val t = boardTop + r*cellSize
            val occ = board[r][c] != 0
            val isClearing = (r in clearRows || c in clearCols) && occ
            val ghostFlash = (r in ghostRows || c in ghostCols)

            if (isClearing) {
                val size = cellSize * clearScale
                val offset = (cellSize - size) / 2f
                rrRect.set(l + offset + 4f, t + offset + 4f, l + offset + size - 4f, t + offset + size - 4f)
                blockP.color = lerp(board[r][c], Color.WHITE, clearFlash)
                canvas.drawRoundRect(rrRect, 12f * clearScale, 12f * clearScale, blockP)
            } else {
                rrRect.set(l+4f, t+4f, l+cellSize-4f, t+cellSize-4f)
                if (occ) {
                    if (ghostFlash) {
                        blockP.color = lerp(board[r][c], Color.WHITE, 0.4f)
                        canvas.drawRoundRect(rrRect, 12f, 12f, blockP)
                    } else {
                        drawCell(canvas, l, t, board[r][c], cellSize)
                    }
                } else {
                    cellP.color = 0x1A000000; canvas.drawRoundRect(rrRect, 12f, 12f, cellP)
                }
            }

            if (draggingIdx >= 0 && ghostRow >= 0 && canPlace && !occ) {
                currentPieces[draggingIdx]?.let { p ->
                    if (p.blocks.any { b -> ghostRow+b.row==r && ghostCol+b.col==c }) {
                        if (ghostFlash) {
                            ghostP.color = lerp(p.color, Color.WHITE, 0.6f)
                            ghostP.alpha = 200
                        } else {
                            ghostP.color = p.color; ghostP.alpha = 100
                        }
                        canvas.drawRoundRect(rrRect, 12f, 12f, ghostP); ghostP.alpha = 255
                    }
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
            currentPieces[i]?.let { drawPiece(canvas, it, sw*i + sw/2f, pieceAreaCY, previewCS) }
        }
    }

    private fun drawPiece(canvas: Canvas, piece: Piece, cx: Float, cy: Float, cs: Float) {
        val sx = cx - piece.width*cs/2f; val sy = cy - piece.height*cs/2f
        for (b in piece.blocks) {
            val l = sx+b.col*cs; val t = sy+b.row*cs
            rrRect.set(l+3f, t+3f, l+cs-3f, t+cs-3f)
            blockP.color = piece.color; canvas.drawRoundRect(rrRect, 10f, 10f, blockP)
            shineP.color = Color.WHITE; shineP.alpha = 40
            canvas.drawRoundRect(l+5f, t+5f, l+cs-5f, t+6f, 2f, 2f, shineP)
        }
    }

    private fun drawDrag(canvas: Canvas) {
        val piece = currentPieces[draggingIdx] ?: return
        val cs = cellSize * 1.05f
        val sx = dragX - piece.width*cs/2f; val sy = dragY - piece.height*cs/2f - cs*2.5f
        for (b in piece.blocks) {
            val l = sx+b.col*cs; val t = sy+b.row*cs
            rrRect.set(l+3f, t+3f, l+cs-3f, t+cs-3f)
            shadowP.color = 0x33000000; shadowP.maskFilter = BlurMaskFilter(15f, BlurMaskFilter.Blur.NORMAL)
            tempRect.set(rrRect); tempRect.offset(0f, 10f)
            canvas.drawRoundRect(tempRect, 12f, 12f, shadowP)
            blockP.color = piece.color; canvas.drawRoundRect(rrRect, 12f, 12f, blockP)
            shineP.color = Color.WHITE; shineP.alpha = 50
            canvas.drawRoundRect(l+5f, t+5f, l+cs-5f, t+6f, 2f, 2f, shineP)
        }
        shadowP.maskFilter = null
    }

    private fun drawAbilities(canvas: Canvas) {
        val icons = listOf("🧲", "🧹", "✨")
        val labels = listOf("GRAVITY", "TRIPLE", "CLEAR")
        val charges = listOf(gravityCharges, tripleCharges, clearAllCharges)
        val steps = listOf(GRAVITY_STEP, TRIPLE_STEP, CLEAR_ALL_STEP)
        val lasts = listOf(lastGravityEarnedAt, lastTripleEarnedAt, lastClearAllEarnedAt)

        val isDarkMode = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val labelColor = if (isDarkMode) Color.WHITE else COLOR_ON_SURFACE_V

        for (i in 0..2) {
            val r = abilityRects[i]
            val count = charges[i]
            val on = count > 0
            bgP.color = if (on) Color.WHITE else 0x1A000000; bgP.style = Paint.Style.FILL
            canvas.drawRoundRect(r, 24f, 24f, bgP)
            val progress = ((score - lasts[i]).toFloat() / steps[i]).coerceIn(0f, 1f)
            if (progress > 0f && count < (if (i==2) 1 else 2)) {
                bgP.color = COLOR_ABILITY_FILL
                val fillH = r.height() * progress
                tempRect.set(r.left, r.bottom - fillH, r.right, r.bottom)
                canvas.save()
                val path = Path(); path.addRoundRect(r, 24f, 24f, Path.Direction.CW)
                canvas.clipPath(path)
                canvas.drawRect(tempRect, bgP)
                canvas.restore()
            }
            iconP.alpha = 255
            iconP.color = if (on) COLOR_PRIMARY else 0x40000000; iconP.textSize = r.height() * 0.45f
            
            val abilityBitmap = when (i) {
                0 -> gravityBitmap
                2 -> cleanBitmap
                else -> null
            }

            if (abilityBitmap != null) {
                val iSize = r.height() * 0.6f
                tempRect.set(r.centerX() - iSize/2, r.centerY() - iSize/2, r.centerX() + iSize/2, r.centerY() + iSize/2)
                if (!on) iconP.alpha = 64
                canvas.drawBitmap(abilityBitmap, null, tempRect, iconP)
            } else {
                canvas.drawText(icons[i], r.centerX(), r.centerY() + iconP.textSize * 0.35f, iconP)
            }
            if (count > 0) {
                val badgeR = r.height() * 0.15f
                bgP.color = COLOR_SECONDARY; canvas.drawCircle(r.right, r.top, badgeR, bgP)
                lblP.color = Color.WHITE; lblP.textSize = badgeR * 1.5f; lblP.textAlign = Paint.Align.CENTER
                canvas.drawText(count.toString(), r.right, r.top + lblP.textSize * 0.35f, lblP)
            }
            lblP.color = labelColor; lblP.alpha = 255; lblP.textSize = r.height() * 0.25f; lblP.textAlign = Paint.Align.CENTER
            canvas.drawText(labels[i], r.centerX(), r.bottom + lblP.textSize * 1.5f, lblP)
        }
    }

    private fun drawUndo(canvas: Canvas) {
        val r = undoRect
        undoBitmap?.let {
            val iSize = r.height() * 0.9f
            tempRect.set(r.centerX() - iSize/2, r.centerY() - iSize/2, r.centerX() + iSize/2, r.centerY() + iSize/2)
            iconP.alpha = 255; canvas.drawBitmap(it, null, tempRect, iconP)
        }
    }

    private val gameOverNewGameRect = RectF()
    private val gameOverHomeRect = RectF()

    private fun drawGameOver(canvas: Canvas) {
        // Dark Overlay
        bgP.color = 0xDD0D0D1A.toInt()
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgP)

        val cx = width / 2f
        val cy = height / 2f

        // Game Over Window
        val winW = width * 0.85f
        val winH = height * 0.45f
        val winRect = RectF(cx - winW/2, cy - winH/2, cx + winW/2, cy + winH/2)
        
        bgP.color = 0xFF1A1A3A.toInt()
        canvas.drawRoundRect(winRect, 64f, 64f, bgP)
        
        // Border
        bgP.style = Paint.Style.STROKE
        bgP.color = 0x33FFFFFF
        bgP.strokeWidth = 4f
        canvas.drawRoundRect(winRect, 64f, 64f, bgP)
        bgP.style = Paint.Style.FILL

        // Title
        lblP.color = Color.WHITE
        lblP.textSize = height * 0.022f
        lblP.alpha = 160
        canvas.drawText("GAME OVER", cx, cy - winH/2 + 80f, lblP)
        lblP.alpha = 255

        // Score
        txtP.color = Color.WHITE
        txtP.textSize = height * 0.09f
        canvas.drawText(scoreNoFmt(score), cx, cy - 20f, txtP)
        
        lblP.textSize = height * 0.018f
        lblP.alpha = 140
        canvas.drawText("POINTS", cx, cy + 40f, lblP)
        lblP.alpha = 255

        // Buttons
        val btnW = winW * 0.75f
        val btnH = 100f
        val btnSpacing = 30f

        gameOverNewGameRect.set(cx - btnW/2, cy + 90f, cx + btnW/2, cy + 90f + btnH)
        bgP.color = COLOR_PRIMARY
        canvas.drawRoundRect(gameOverNewGameRect, 32f, 32f, bgP)
        
        iconP.color = Color.WHITE
        iconP.textSize = height * 0.028f
        iconP.typeface = Typeface.DEFAULT_BOLD
        canvas.drawText("NEW GAME", cx, gameOverNewGameRect.centerY() + iconP.textSize * 0.35f, iconP)

        gameOverHomeRect.set(cx - btnW/2, gameOverNewGameRect.bottom + btnSpacing, cx + btnW/2, gameOverNewGameRect.bottom + btnSpacing + btnH)
        bgP.color = 0xFF424751.toInt()
        canvas.drawRoundRect(gameOverHomeRect, 32f, 32f, bgP)
        canvas.drawText("HOME", cx, gameOverHomeRect.centerY() + iconP.textSize * 0.35f, iconP)
        
        iconP.typeface = Typeface.DEFAULT
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (isGameOver) {
            if (ev.action == MotionEvent.ACTION_UP) {
                if (gameOverNewGameRect.contains(ev.x, ev.y)) {
                    startNewGame()
                } else if (gameOverHomeRect.contains(ev.x, ev.y)) {
                    onMenuClicked?.invoke()
                }
            }
            return true
        }
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                if (menuRect.contains(ev.x, ev.y)) { onMenuClicked?.invoke(); return true }
                if (scoreRect.contains(ev.x, ev.y)) { isScoreVisible = !isScoreVisible; invalidate(); return true }
                if (undoRect.contains(ev.x, ev.y)) { doUndo(); return true }
                for (i in 0..2) if (abilityRects[i].contains(ev.x, ev.y)) { activateAbility(i); return true }
                if (ev.y > boardTop + cellSize * BOARD_SIZE) onDown(ev.x, ev.y)
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
            updateGhost(); invalidate(); vibrate(40)
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
        val ly = dragY - cellSize * 2.5f
        ghostCol = ((dragX - boardLeft) / cellSize - piece.width / 2f + 0.5f).toInt()
        ghostRow = ((ly - boardTop) / cellSize - piece.height / 2f + 0.5f).toInt()
        
        ghostRows.clear()
        ghostCols.clear()
        
        canPlace = ly > boardTop && ly < boardTop + cellSize * BOARD_SIZE && canPlacePiece(piece, ghostRow, ghostCol)
        
        if (canPlace) {
            for (r in 0 until BOARD_SIZE) {
                var full = true
                for (c in 0 until BOARD_SIZE) {
                    val occupied = board[r][c] != 0 || piece.blocks.any { it.row + ghostRow == r && it.col + ghostCol == c }
                    if (!occupied) { full = false; break }
                }
                if (full) ghostRows.add(r)
            }
            for (c in 0 until BOARD_SIZE) {
                var full = true
                for (r in 0 until BOARD_SIZE) {
                    val occupied = board[r][c] != 0 || piece.blocks.any { it.row + ghostRow == r && it.col + ghostCol == c }
                    if (!occupied) { full = false; break }
                }
                if (full) ghostCols.add(c)
            }
        }
    }

    private fun canPlacePiece(p: Piece, row: Int, col: Int): Boolean {
        for (b in p.blocks) {
            val r = row + b.row; val c = col + b.col
            if (r !in 0 until BOARD_SIZE || c !in 0 until BOARD_SIZE || board[r][c] != 0) return false
        }
        return true
    }

    private fun isGameOverNow(): Boolean {
        if (gravityCharges > 0 || tripleCharges > 0 || clearAllCharges > 0) return false
        val active = currentPieces.filterNotNull()
        if (active.isEmpty()) return false
        return active.none { p -> (0 until BOARD_SIZE).any { r -> (0 until BOARD_SIZE).any { c -> canPlacePiece(p, r, c) } } }
    }

    private fun placePiece(piece: Piece, row: Int, col: Int) {
        for (b in piece.blocks) board[row+b.row][col+b.col] = piece.color

        val rows = (0 until BOARD_SIZE).filter { r -> (0 until BOARD_SIZE).all { c -> board[r][c] != 0 } }
        val cols = (0 until BOARD_SIZE).filter { c -> (0 until BOARD_SIZE).all { r -> board[r][c] != 0 } }
        val linesCleared = rows.size + cols.size

        if (linesCleared > 0) {
            movesSinceLastClear = 0
            comboLevel += linesCleared
            score += (piece.blocks.size * comboLevel) + (linesCleared * BOARD_SIZE * comboLevel)
            vibrate(80)
            clearRows.addAll(rows); clearCols.addAll(cols); clearRunning = true
            startClearAnim(rows, cols)
        } else {
            movesSinceLastClear++
            if (movesSinceLastClear >= 4) {
                comboLevel = 0
            }
            score += piece.blocks.size * (if (comboLevel > 0) comboLevel else 1)
            vibrate(50)
            post { evalGameOver() }
        }

        checkAbilities(); updateRing(); saveBest(); saveGameState()
    }

    private fun startClearAnim(rows: List<Int>, cols: List<Int>) {
        if (isSoundEnabled) {
            val soundToPlay = when {
                comboLevel in 2..3 -> comboSounds[0]
                comboLevel in 4..6 -> comboSounds[1]
                comboLevel in 7..8 -> comboSounds[2]
                comboLevel in 9..10 -> comboSounds[3]
                comboLevel >= 11 -> comboSounds[4]
                else -> desapearSoundId
            }
            soundPool?.play(soundToPlay, 1f, 1f, 1, 0, 1f)
        }
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 450
            addUpdateListener { 
                val v = it.animatedValue as Float
                clearFlash = v 
                clearScale = when {
                    v < 0.3f -> 1f + (v / 0.3f) * 0.15f // Pop
                    else -> ((1f - (v - 0.3f) / 0.7f) * 1.15f).coerceAtLeast(0f) // Shrink
                }
                invalidate() 
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    rows.forEach { r -> for (c in 0 until BOARD_SIZE) board[r][c] = 0 }
                    cols.forEach { c -> for (r in 0 until BOARD_SIZE) board[r][c] = 0 }
                    clearRows.clear(); clearCols.clear()
                    clearFlash = 0f; clearScale = 1f; clearRunning = false; invalidate(); post { evalGameOver() }
                }
            })
        }.start()
    }

    private fun evalGameOver() { if (!isGameOver && !clearRunning && isGameOverNow()) { isGameOver = true; vibrate(500); invalidate() } }

    private fun updateRing() {
        val lvl = score / RING_INTERVAL
        if (lvl > prevRingLevel) {
            prevRingLevel = lvl
            ValueAnimator.ofFloat(0f, 1f, 0f).apply {
                duration = 600
                addUpdateListener { ringFlash = it.animatedValue as Float; invalidate() }
            }.start()
            vibrate(150)
        }
        ringFill = (score % RING_INTERVAL).toFloat() / RING_INTERVAL; invalidate()
    }

    private fun checkAbilities() {
        while (score >= lastGravityEarnedAt + GRAVITY_STEP) { lastGravityEarnedAt += GRAVITY_STEP; if (gravityCharges < 2) gravityCharges++ }
        while (score >= lastTripleEarnedAt + TRIPLE_STEP) { lastTripleEarnedAt += TRIPLE_STEP; if (tripleCharges < 2) tripleCharges++ }
        while (score >= lastClearAllEarnedAt + CLEAR_ALL_STEP) { lastClearAllEarnedAt += CLEAR_ALL_STEP; if (clearAllCharges < 1) clearAllCharges++ }
    }

    private fun activateAbility(i: Int) {
        when (i) {
            0 -> if (gravityCharges > 0)  { applyGravityLoop(); gravityCharges--; vibrate(150) }
            1 -> if (tripleCharges > 0)   { applyTriple();      tripleCharges--;  vibrate(150) }
            2 -> if (clearAllCharges > 0) { clearAll();         clearAllCharges--; vibrate(250) }
        }
        updateRing(); saveBest(); post { evalGameOver() }; invalidate(); saveGameState()
    }

    private fun applyGravityLoop() {
        var changed = true
        while (changed) {
            changed = false
            for (c in 0 until BOARD_SIZE) {
                val col = (0 until BOARD_SIZE).mapNotNull { r -> board[r][c].takeIf { it != 0 } }
                for (r in 0 until BOARD_SIZE) {
                    val newVal = if (col.size - (BOARD_SIZE - r) >= 0) col[col.size - (BOARD_SIZE - r)] else 0
                    if (board[r][c] != newVal) { board[r][c] = newVal; changed = true }
                }
            }
            if (changed) {
                val rows = (0 until BOARD_SIZE).filter { r -> (0 until BOARD_SIZE).all { c -> board[r][c] != 0 } }
                val cols = (0 until BOARD_SIZE).filter { c -> (0 until BOARD_SIZE).all { r -> board[r][c] != 0 } }
                if (rows.isNotEmpty() || cols.isNotEmpty()) {
                    rows.forEach { r -> for (c in 0 until BOARD_SIZE) board[r][c] = 0 }
                    cols.forEach { c -> for (r in 0 until BOARD_SIZE) board[r][c] = 0 }
                    score += (rows.size + cols.size) * BOARD_SIZE
                } else {
                    break
                }
            }
        }
        invalidate()
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

    private fun clearAll() { for (r in 0 until BOARD_SIZE) board[r].fill(0); score += BOARD_SIZE * BOARD_SIZE; saveBest(); invalidate() }

    private fun saveUndo() {
        prevBoard = Array(BOARD_SIZE) { board[it].copyOf() }; prevScore = score
        for (i in 0 until PIECE_SLOTS) { prevPieces[i] = currentPieces[i]; prevNextPieces[i] = nextPieces[i] }
    }

    private fun doUndo() {
        val pb = prevBoard ?: return
        for (r in 0 until BOARD_SIZE) board[r] = pb[r].copyOf(); score = prevScore
        for (i in 0 until PIECE_SLOTS) { currentPieces[i] = prevPieces[i]; nextPieces[i] = prevNextPieces[i] }
        prevBoard = null; comboLevel = 0; movesSinceLastClear = 0; updateRing(); checkAbilities(); vibrate(100); invalidate(); saveGameState()
    }

    private fun refillPieces() {
        var changed = false
        for (i in 0 until PIECE_SLOTS) if (currentPieces[i] == null) {
            currentPieces[i] = nextPieces[i]
            nextPieces[i] = PieceFactory.smartRandom(board).copy(color = PASTEL.random())
            changed = true
        }
        if (changed) saveGameState()
    }

    fun startNewGame() {
        resetGame()
        saveGameState()
    }

    private fun resetGame() {
        for (r in 0 until BOARD_SIZE) board[r].fill(0)
        for (i in 0 until PIECE_SLOTS) { 
            currentPieces[i] = null
            nextPieces[i] = PieceFactory.random().copy(color = PASTEL.random()) 
        }
        score = 0; isGameOver = false; clearRunning = false; comboLevel = 0; movesSinceLastClear = 0
        ringFill = 0f; prevRingLevel = 0; ringFlash = 0f
        gravityCharges = 0; tripleCharges = 0; clearAllCharges = 0
        lastGravityEarnedAt = 0; lastTripleEarnedAt = 0; lastClearAllEarnedAt = 0
        prevBoard = null; prevScore = 0
        for (i in 0 until PIECE_SLOTS) { prevPieces[i] = null; prevNextPieces[i] = null }
        refillPieces(); invalidate()
    }

    private fun saveGameState() {
        val p = prefs().edit()
        p.putBoolean("has_saved_game", true)
        p.putInt("score", score)
        p.putInt("comboLevel", comboLevel)
        p.putInt("movesSinceLastClear", movesSinceLastClear)
        p.putInt("gravityCharges", gravityCharges)
        p.putInt("tripleCharges", tripleCharges)
        p.putInt("clearAllCharges", clearAllCharges)
        p.putInt("lastGravityEarnedAt", lastGravityEarnedAt)
        p.putInt("lastTripleEarnedAt", lastTripleEarnedAt)
        p.putInt("lastClearAllEarnedAt", lastClearAllEarnedAt)
        p.putInt("prevRingLevel", prevRingLevel)
        p.putBoolean("isGameOver", isGameOver)

        // Board
        val sb = StringBuilder()
        for (r in 0 until BOARD_SIZE) for (c in 0 until BOARD_SIZE) sb.append(board[r][c]).append(",")
        p.putString("board", sb.toString())

        // Pieces
        p.putString("currentPieces", serializePieces(currentPieces))
        p.putString("nextPieces", serializePieces(nextPieces))
        p.apply()
    }

    private fun loadGameState(): Boolean {
        val pr = prefs()
        if (!pr.getBoolean("has_saved_game", false)) return false
        
        score = pr.getInt("score", 0)
        comboLevel = pr.getInt("comboLevel", 0)
        movesSinceLastClear = pr.getInt("movesSinceLastClear", 0)
        gravityCharges = pr.getInt("gravityCharges", 0)
        tripleCharges = pr.getInt("tripleCharges", 0)
        clearAllCharges = pr.getInt("clearAllCharges", 0)
        lastGravityEarnedAt = pr.getInt("lastGravityEarnedAt", 0)
        lastTripleEarnedAt = pr.getInt("lastTripleEarnedAt", 0)
        lastClearAllEarnedAt = pr.getInt("lastClearAllEarnedAt", 0)
        prevRingLevel = pr.getInt("prevRingLevel", 0)
        isGameOver = pr.getBoolean("isGameOver", false)

        val bStr = pr.getString("board", "") ?: ""
        if (bStr.isNotEmpty()) {
            val vals = bStr.split(",")
            var idx = 0
            for (r in 0 until BOARD_SIZE) for (c in 0 until BOARD_SIZE) {
                if (idx < vals.size - 1) board[r][c] = vals[idx].toInt()
                idx++
            }
        }

        deserializePieces(pr.getString("currentPieces", ""), currentPieces)
        deserializePieces(pr.getString("nextPieces", ""), nextPieces)
        
        ringFill = (score % RING_INTERVAL).toFloat() / RING_INTERVAL
        invalidate()
        return true
    }

    private fun serializePieces(pieces: Array<Piece?>): String {
        return pieces.joinToString(";") { p ->
            if (p == null) "null"
            else p.blocks.joinToString(",") { "${it.row},${it.col}" } + "|" + p.color
        }
    }

    private fun deserializePieces(str: String?, pieces: Array<Piece?>) {
        if (str.isNullOrEmpty()) return
        val parts = str.split(";")
        for (i in 0 until PIECE_SLOTS) {
            if (i >= parts.size || parts[i] == "null") {
                pieces[i] = null
            } else {
                val sub = parts[i].split("|")
                val blocks = sub[0].split(",").windowed(2, 2).map { Block(it[0].toInt(), it[1].toInt()) }
                pieces[i] = Piece(blocks, sub[1].toInt())
            }
        }
    }

    fun hasSavedGame(): Boolean = prefs().getBoolean("has_saved_game", false) && !isGameOver

    private fun saveBest() { if (score > bestScore) { bestScore = score; prefs().edit().putInt("best", bestScore).apply() } }
    private fun prefs() = context.getSharedPreferences("bb_prefs", Context.MODE_PRIVATE)

    private fun vibrate(ms: Long) {
        if (!isVibrationEnabled) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                vibrator?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            else vibrator?.vibrate(ms)
        }
    }

    private fun scoreNoFmt(n: Int) = n.toString()
    private fun lerp(c1: Int, c2: Int, t: Float): Int { val i = 1f-t; return Color.argb(255, (Color.red(c1)*i + Color.red(c2)*t).toInt(), (Color.green(c1)*i + Color.green(c2)*t).toInt(), (Color.blue(c1)*i + Color.blue(c2)*t).toInt()) }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        soundPool?.release()
        soundPool = null
    }
}