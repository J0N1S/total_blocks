package com.fgs.totalblocks

data class Block(val row: Int, val col: Int)

data class Piece(
    val blocks: List<Block>,
    val color: Int
) {
    val width: Int get() = (blocks.maxOfOrNull { it.col } ?: 0) + 1
    val height: Int get() = (blocks.maxOfOrNull { it.row } ?: 0) + 1
}

object PieceFactory {
    private val shapes = listOf(
        listOf(Block(0, 0)),
        listOf(Block(0, 0), Block(0, 1)),
        listOf(Block(0, 0), Block(1, 0)),
        listOf(Block(0, 0), Block(0, 1), Block(0, 2)),
        listOf(Block(0, 0), Block(1, 0), Block(2, 0)),
        listOf(Block(0, 0), Block(0, 1), Block(0, 2), Block(0, 3)),
        listOf(Block(0, 0), Block(1, 0), Block(2, 0), Block(3, 0)),
        listOf(Block(0, 0), Block(0, 1), Block(0, 2), Block(0, 3), Block(0, 4)),
        listOf(Block(0, 0), Block(1, 0), Block(2, 0), Block(3, 0), Block(4, 0)),
        listOf(Block(0, 0), Block(0, 1), Block(1, 0), Block(1, 1)),
        listOf(Block(0,0),Block(0,1),Block(0,2),Block(1,0),Block(1,1),Block(1,2),Block(2,0),Block(2,1),Block(2,2)),
        listOf(Block(0, 0), Block(1, 0), Block(2, 0), Block(2, 1)),
        listOf(Block(0, 1), Block(1, 1), Block(2, 0), Block(2, 1)),
        listOf(Block(0, 0), Block(0, 1), Block(1, 0), Block(2, 0)),
        listOf(Block(0, 0), Block(0, 1), Block(1, 1), Block(2, 1)),
        listOf(Block(0, 0), Block(0, 1), Block(0, 2), Block(1, 1)),
        listOf(Block(0, 1), Block(1, 0), Block(1, 1), Block(1, 2)),
        listOf(Block(0, 1), Block(0, 2), Block(1, 0), Block(1, 1)),
        listOf(Block(0, 0), Block(0, 1), Block(1, 1), Block(1, 2)),
        listOf(Block(0, 0), Block(1, 0), Block(1, 1)),
        listOf(Block(0, 1), Block(1, 0), Block(1, 1)),
        listOf(Block(0, 0), Block(0, 1), Block(1, 0)),
        listOf(Block(0, 0), Block(0, 1), Block(1, 1)),
    )
    private val colors = listOf(
        0xFFE74C3C.toInt(), 0xFF3498DB.toInt(), 0xFF2ECC71.toInt(),
        0xFFF1C40F.toInt(), 0xFF9B59B6.toInt(), 0xFF1ABC9C.toInt(),
        0xFFE67E22.toInt(), 0xFFFF6B9D.toInt(), 0xFF00CEC9.toInt(),
    )
    fun random(): Piece = Piece(shapes.random(), colors.random())
}