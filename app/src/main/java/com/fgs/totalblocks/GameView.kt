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
    var onHomeClicked: (() -> Unit)? = null
    var onGameOver: ((Int) -> Unit)? = null

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

    @Volatile
    var isAnimationEnabled: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                prefs().edit().putBoolean("animation_enabled", value).apply()
                invalidate()
            }
        }

    data class Block(val row: Int, val col: Int)
    data class Piece(val blocks: List<Block>, val color: Int) {
        val width: Int  by lazy { blocks.maxOf { it.col } - blocks.minOf { it.col } + 1 }
        val height: Int by lazy { blocks.maxOf { it.row } - blocks.minOf { it.row } + 1 }
    }

    object PieceFactory {
        private val baseTemplates = listOf(
            listOf(Block(0,0), Block(0,1)), // 2x1 Line
            listOf(Block(0,0), Block(0,1), Block(0,2)), // 3x1 Line
            listOf(Block(0,0), Block(0,1), Block(0,2), Block(0,3)), // 4x1 Line
            listOf(Block(0,0), Block(0,1), Block(0,2), Block(0,3), Block(0,4)), // 5x1 Line
            listOf(Block(0,0), Block(0,1), Block(1,0), Block(1,1)), // 2x2 Square
            listOf(Block(0,0), Block(0,1), Block(0,2), Block(1,0), Block(1,1), Block(1,2), Block(2,0), Block(2,1), Block(2,2)), // 3x3 Square
            listOf(Block(0,0), Block(1,0), Block(1,1)), // L-small (3 blocks)
            listOf(Block(0,0), Block(1,0), Block(2,0), Block(2,1)), // L-shape (4 blocks)
            listOf(Block(0,0), Block(0,1), Block(0,2), Block(1,1)), // T-shape
            listOf(Block(0,0), Block(1,0), Block(1,1), Block(2,1)), // Z-shape
            listOf(Block(0,0), Block(0,1), Block(0,2), Block(1,0), Block(1,1), Block(1,2)), // 2x3 Rectangle
            listOf(Block(0,0), Block(1,0), Block(2,0), Block(2,1), Block(2,2)) // 3x3 L-shape
        )

        private fun rotate(blocks: List<Block>, times: Int): List<Block> {
            var current = blocks
            repeat(times % 4) {
                // (r, c) -> (c, -r)
                val rotated = current.map { Block(it.col, -it.row) }
                val minR = rotated.minOf { it.row }
                val minC = rotated.minOf { it.col }
                current = rotated.map { Block(it.row - minR, it.col - minC) }
            }
            return current
        }

        fun random(): Piece {
            val base = baseTemplates.random()
            val rotated = rotate(base, (0..3).random())
            return Piece(rotated, 0)
        }

        fun smartRandom(board: Array<IntArray>): Piece {
            val shuffledTemplates = baseTemplates.shuffled()
            for (base in shuffledTemplates) {
                val rotations = (0..3).shuffled()
                for (rot in rotations) {
                    val candidate = rotate(base, rot)
                    val p = Piece(candidate, 0)
                    for (r in 0..BOARD_SIZE - p.height) {
                        for (c in 0..BOARD_SIZE - p.width) {
                            if (canFit(p, r, c, board)) return p
                        }
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

    private var lastAbilityTapTime = 0L
    private var lastAbilityTapIdx = -1
    private var lastUndoTapTime = 0L
    private val DOUBLE_TAP_TIMEOUT = 500L

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
    private val blobP   = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isDither = true
    }
    private val cellP   = Paint(Paint.ANTI_ALIAS_FLAG)
    private val blockP  = Paint(Paint.ANTI_ALIAS_FLAG)
    private val floatP  = Paint(Paint.ANTI_ALIAS_FLAG)
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
        isAnimationEnabled = prefs().getBoolean("animation_enabled", true)
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
        updateTimer()
        drawBg(canvas)
        drawHUD(canvas)
        drawRing(canvas)
        drawBoard(canvas)
        drawSlots(canvas)
        drawAbilities(canvas)
        drawUndo(canvas)
        if (draggingIdx >= 0) drawDrag(canvas)
    }

    private fun updateTimer() {
        if (!isTimeAttack || isGameOver || clearRunning) {
            lastFrameTime = System.currentTimeMillis()
            return
        }
        val now = System.currentTimeMillis()
        if (lastFrameTime > 0) {
            val delta = now - lastFrameTime
            timeLeftMillis -= delta
            if (timeLeftMillis <= 0) {
                timeLeftMillis = 0
                isGameOver = true
                invalidate()
                onGameOver?.invoke(score)
            }
        }
        lastFrameTime = now
        postInvalidateOnAnimation()
    }

    private var bgStartTime = System.currentTimeMillis()

    private class FloatingBlock(
        var x: Float, var y: Float,
        var size: Float,
        var rotation: Float,
        var rotSpeed: Float,
        var speedY: Float,
        val color: Int,
        val type: Int // 0: 2x2 Square, 1: T-block, 2: Z-block, 3: 4x1 Line
    )

    private val floatingBlocks = mutableListOf<FloatingBlock>()

    private fun initFloatingBlocks() {
        if (width <= 0 || height <= 0) return
        floatingBlocks.clear()
        val colors = listOf(0x4400ACC1, 0x446A1B9A, 0x44283593, 0x44AD1457)
        repeat(15) {
            floatingBlocks.add(createRandomFloatingBlock(colors.random()))
        }
    }

    private fun createRandomFloatingBlock(color: Int): FloatingBlock {
        val w = width.toFloat()
        val h = height.toFloat()
        return FloatingBlock(
            (0..100).random() / 100f * w,
            (0..100).random() / 100f * h,
            w * (0.04f + (0..8).random() / 100f),
            (0..360).random().toFloat(),
            (5..15).random() / 10f * (if ((0..1).random() == 0) 1 else -1),
            (10..30).random() / 10f, // Doubled speed
            color,
            (0..3).random()
        )
    }

    private fun drawBg(c: Canvas) {
        if (floatingBlocks.isEmpty()) initFloatingBlocks()

        if (!isAnimationEnabled) {
            // Dark Royal Blue solid background
            c.drawColor(0xFF002366.toInt())
            return
        }

        // Base dark background
        c.drawColor(0xFF080812.toInt())

        val time = (System.currentTimeMillis() - bgStartTime) / 1000f
        val w = width.toFloat()
        val h = height.toFloat()

        // 1. Teal/Cyan Blob - Top Left area
        drawBlob(c,
            w * 0.25f + Math.sin(time * 0.45).toFloat() * (w * 0.2f),
            h * 0.35f + Math.cos(time * 0.35).toFloat() * (h * 0.15f),
            w * 0.85f, 0xFF00ACC1.toInt(), 0.32f)

        // 2. Deep Purple Blob - Bottom Right area
        drawBlob(c,
            w * 0.75f + Math.cos(time * 0.3).toFloat() * (w * 0.25f),
            h * 0.65f + Math.sin(time * 0.4).toFloat() * (h * 0.2f),
            w * 1.1f, 0xFF6A1B9A.toInt(), 0.28f)

        // 3. Royal Blue Blob - Center area
        drawBlob(c,
            w * 0.5f + Math.sin(time * 0.55).toFloat() * (w * 0.3f),
            h * 0.5f + Math.cos(time * 0.45).toFloat() * (h * 0.25f),
            w * 0.95f, 0xFF283593.toInt(), 0.25f)

        // 4. Magenta accent - Bottom Left
        drawBlob(c,
            w * 0.1f + Math.cos(time * 0.6).toFloat() * (w * 0.15f),
            h * 0.85f + Math.sin(time * 0.5).toFloat() * (h * 0.1f),
            w * 0.7f, 0xFFAD1457.toInt(), 0.15f)

        // 5. Floating Blocks
        drawFloatingBlocks(c, time)

        // Continuous animation
        postInvalidateOnAnimation()
    }

    private fun drawFloatingBlocks(c: Canvas, time: Float) {
        val w = width.toFloat()
        val h = height.toFloat()

        for (fb in floatingBlocks) {
            fb.y += fb.speedY
            fb.rotation += fb.rotSpeed

            if (fb.y - fb.size * 2 > h) {
                fb.y = -fb.size * 2
                fb.x = (0..100).random() / 100f * w
            }

            c.save()
            c.translate(fb.x, fb.y)
            c.rotate(fb.rotation)

            val cs = fb.size / 2f // cell size for the blocks
            val blocks = when (fb.type) {
                0 -> listOf(Block(0,0), Block(0,1), Block(1,0), Block(1,1)) // 2x2 Square
                1 -> listOf(Block(0,0), Block(0,1), Block(0,2), Block(1,1)) // T-shape
                2 -> listOf(Block(0,0), Block(1,0), Block(1,1), Block(2,1)) // Z-shape
                3 -> listOf(Block(0,0), Block(0,1), Block(0,2), Block(0,3)) // 4x1 Line
                else -> emptyList()
            }

            // Calculate center offset to rotate around center of piece
            val pWidth = blocks.maxOf { it.col } - blocks.minOf { it.col } + 1
            val pHeight = blocks.maxOf { it.row } - blocks.minOf { it.row } + 1
            val offsetX = -pWidth * cs / 2f
            val offsetY = -pHeight * cs / 2f

            for (b in blocks) {
                val l = offsetX + b.col * cs
                val t = offsetY + b.row * cs
                rrRect.set(l + 1f, t + 1f, l + cs - 1f, t + cs - 1f)

                // Use same rendering logic as drawPiece
                blockP.color = fb.color
                blockP.alpha = 100 // Semi-transparent for background
                c.drawRoundRect(rrRect, 6f, 6f, blockP)

                // Add the shine effect from in-game blocks
                shineP.color = Color.WHITE
                shineP.alpha = 30
                c.drawRoundRect(l + 2f, t + 2f, l + cs - 2f, t + 3f, 1f, 1f, shineP)
            }

            c.restore()
        }
    }

    private fun drawBlob(c: Canvas, cx: Float, cy: Float, radius: Float, color: Int, alpha: Float) {
        if (radius <= 0) return
        val colors = intArrayOf((alpha * 255).toInt() shl 24 or (color and 0x00FFFFFF), Color.TRANSPARENT)
        val shader = RadialGradient(cx, cy, radius, colors, null, Shader.TileMode.CLAMP)
        blobP.shader = shader
        c.drawCircle(cx, cy, radius, blobP)
        blobP.shader = null
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
        
        if (isTimeAttack) {
            val seconds = (timeLeftMillis / 1000).coerceAtLeast(0)
            val timeStr = String.format(java.util.Locale.US, "%02d:%02d", seconds / 60, seconds % 60)
            txtP.color = if (timeLeftMillis < 10000) Color.RED else textColor
            canvas.drawText(timeStr, width / 2f, scoreY, txtP)
            
            // Draw small score below timer
            txtP.textSize = height * 0.02f
            txtP.color = textColor
            canvas.drawText("SCORE: ${scoreNoFmt(score)}", width / 2f, scoreY + height * 0.04f, txtP)
        } else {
            if (isScoreVisible) {
                canvas.drawText(scoreNoFmt(score), width / 2f, scoreY, txtP)
            } else {
                hideBitmap?.let {
                    val hSize = txtP.textSize * 0.6f
                    tempRect.set(width/2f - hSize/2, scoreY - hSize*0.75f, width/2f + hSize/2, scoreY + hSize*0.25f)
                    canvas.drawBitmap(it, null, tempRect, iconP)
                }
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
        
        // Time Attack Costs
        val costs = listOf(60000L, 180000L, 300000L)

        val isDarkMode = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val labelColor = if (isDarkMode) Color.WHITE else COLOR_ON_SURFACE_V

        for (i in 0..2) {
            val r = abilityRects[i]
            val on = if (isTimeAttack) timeLeftMillis > costs[i] else charges[i] > 0
            
            bgP.color = if (on) Color.WHITE else 0x1A000000; bgP.style = Paint.Style.FILL
            canvas.drawRoundRect(r, 24f, 24f, bgP)
            
            if (!isTimeAttack) {
                val progress = ((score - lasts[i]).toFloat() / steps[i]).coerceIn(0f, 1f)
                if (progress > 0f && charges[i] < (if (i==2) 1 else 2)) {
                    bgP.color = COLOR_ABILITY_FILL
                    val fillH = r.height() * progress
                    tempRect.set(r.left, r.bottom - fillH, r.right, r.bottom)
                    canvas.save()
                    val path = Path(); path.addRoundRect(r, 24f, 24f, Path.Direction.CW)
                    canvas.clipPath(path)
                    canvas.drawRect(tempRect, bgP)
                    canvas.restore()
                }
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
            
            if (!isTimeAttack && charges[i] > 0) {
                val badgeR = r.height() * 0.15f
                bgP.color = COLOR_SECONDARY; canvas.drawCircle(r.right, r.top, badgeR, bgP)
                lblP.color = Color.WHITE; lblP.textSize = badgeR * 1.5f; lblP.textAlign = Paint.Align.CENTER
                canvas.drawText(charges[i].toString(), r.right, r.top + lblP.textSize * 0.35f, lblP)
            } else if (isTimeAttack) {
                // Show cost in Time Attack
                val costMin = (costs[i] / 60000).toInt()
                lblP.color = if (on) Color.BLACK else Color.GRAY
                lblP.textSize = r.height() * 0.2f
                canvas.drawText("${costMin}m", r.centerX(), r.centerY() + r.height() * 0.35f, lblP)
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

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (isGameOver) return true
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                val now = System.currentTimeMillis()
                if (menuRect.contains(ev.x, ev.y)) { onMenuClicked?.invoke(); return true }
                if (scoreRect.contains(ev.x, ev.y)) { isScoreVisible = !isScoreVisible; invalidate(); return true }
                
                if (undoRect.contains(ev.x, ev.y)) {
                    if (now - lastUndoTapTime < DOUBLE_TAP_TIMEOUT) {
                        doUndo()
                        lastUndoTapTime = 0
                    } else {
                        lastUndoTapTime = now
                    }
                    return true
                }

                for (i in 0..2) {
                    if (abilityRects[i].contains(ev.x, ev.y)) {
                        if (i == lastAbilityTapIdx && now - lastAbilityTapTime < DOUBLE_TAP_TIMEOUT) {
                            activateAbility(i)
                            lastAbilityTapIdx = -1
                            lastAbilityTapTime = 0
                        } else {
                            lastAbilityTapIdx = i
                            lastAbilityTapTime = now
                        }
                        return true
                    }
                }

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
            
            if (isTimeAttack) {
                timeLeftMillis += if (linesCleared > 1) 15000L else 5000L
            }

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

    private fun evalGameOver() {
        if (!isGameOver && !clearRunning && isGameOverNow()) {
            isGameOver = true
            vibrate(500)
            invalidate()
            onGameOver?.invoke(score)
        }
    }

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
        if (isTimeAttack) return
        while (score >= lastGravityEarnedAt + GRAVITY_STEP) { lastGravityEarnedAt += GRAVITY_STEP; if (gravityCharges < 2) gravityCharges++ }
        while (score >= lastTripleEarnedAt + TRIPLE_STEP) { lastTripleEarnedAt += TRIPLE_STEP; if (tripleCharges < 2) tripleCharges++ }
        while (score >= lastClearAllEarnedAt + CLEAR_ALL_STEP) { lastClearAllEarnedAt += CLEAR_ALL_STEP; if (clearAllCharges < 1) clearAllCharges++ }
    }

    private fun activateAbility(i: Int) {
        if (isTimeAttack) {
            val costs = listOf(60000L, 180000L, 300000L)
            if (timeLeftMillis > costs[i]) {
                timeLeftMillis -= costs[i]
                when (i) {
                    0 -> { applyGravityLoop(); vibrate(150) }
                    1 -> { applyTriple();      vibrate(150) }
                    2 -> { clearAll();         vibrate(250) }
                }
            } else {
                return // Not enough time
            }
        } else {
            when (i) {
                0 -> if (gravityCharges > 0)  { applyGravityLoop(); gravityCharges--; vibrate(150) }
                1 -> if (tripleCharges > 0)   { applyTriple();      tripleCharges--;  vibrate(150) }
                2 -> if (clearAllCharges > 0) { clearAll();         clearAllCharges--; vibrate(250) }
            }
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

    private var isTimeAttack = false
    private var timeLeftMillis = 60000L
    private var lastFrameTime = 0L

    fun startNewGame() {
        resetGame()
        saveGameState()
    }

    fun startClassic() {
        isTimeAttack = false
        startNewGame()
    }

    fun startTimeAttack() {
        isTimeAttack = true
        timeLeftMillis = 60000L
        startNewGame()
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
        timeLeftMillis = 60000L
        lastFrameTime = 0L
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
        p.putBoolean("isTimeAttack", isTimeAttack)
        p.putLong("timeLeftMillis", timeLeftMillis)

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
        isTimeAttack = pr.getBoolean("isTimeAttack", false)
        timeLeftMillis = pr.getLong("timeLeftMillis", 60000L)

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