package com.example.ledmatrix

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.min

class Frame(val colors: IntArray = IntArray(64))

class AnimationGridView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        private const val ROWS = 8
        private const val COLS = 8
        private const val NO_POINTER = -1
    }

    private val colors = IntArray(ROWS * COLS) { 0 }
    private var paintColor: Int = Color.RED
    private var isErasing = false

    private var pointerId = NO_POINTER
    private var lastX = 0f
    private var lastY = 0f

    private var cellSize = 0f
    private var gridLeft = 0f
    private var gridTop = 0f
    private var gridSize = 0f

    private val density = resources.displayMetrics.density

    private val backgroundPaint = Paint().apply {
        color = Color.rgb(30, 30, 30)
        style = Paint.Style.FILL
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(80, 80, 80)
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }

    private val cellPaint = Paint().apply {
        style = Paint.Style.FILL
    }

    init {
        isClickable = true
    }

    fun setPaintColor(color: Int) {
        paintColor = color
        isErasing = false
    }

    fun setErasing(erasing: Boolean) {
        isErasing = erasing
    }

    fun loadFrame(frame: Frame) {
        System.arraycopy(frame.colors, 0, colors, 0, 64)
        invalidate()
    }

    fun getCurrentFrame(): Frame {
        return Frame(colors.copyOf())
    }

    fun clearGrid() {
        for (i in colors.indices) {
            colors[i] = 0
        }
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)

        val margin = 2f * density
        gridSize = (min(w, h).toFloat() - margin * 2f).coerceAtLeast(0f)
        cellSize = gridSize / COLS
        gridLeft = (w - gridSize) / 2f
        gridTop = (h - gridSize) / 2f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (cellSize <= 0f) return

        canvas.drawRect(
            gridLeft, gridTop,
            gridLeft + gridSize, gridTop + gridSize,
            backgroundPaint
        )

        for (row in 0 until ROWS) {
            for (col in 0 until COLS) {
                val idx = row * COLS + col
                if (colors[idx] != 0) {
                    cellPaint.color = colors[idx]
                    val left = gridLeft + col * cellSize
                    val top = gridTop + row * cellSize
                    canvas.drawRect(
                        left, top,
                        left + cellSize, top + cellSize,
                        cellPaint
                    )
                }
            }
        }

        for (row in 0..ROWS) {
            val y = gridTop + row * cellSize
            canvas.drawLine(gridLeft, y, gridLeft + gridSize, y, linePaint)
        }

        for (col in 0..COLS) {
            val x = gridLeft + col * cellSize
            canvas.drawLine(x, gridTop, x, gridTop + gridSize, linePaint)
        }
    }

    private fun cellAt(x: Float, y: Float): Int {
        if (cellSize <= 0f) return -1

        val localX = x - gridLeft
        val localY = y - gridTop

        if (localX < 0f || localY < 0f || localX >= gridSize || localY >= gridSize)
            return -1

        val col = (localX / cellSize).toInt()
        val row = (localY / cellSize).toInt()

        return row * COLS + col
    }

    private fun paintCell(cell: Int) {
        if (cell < 0 || cell >= 64) return
        val newColor = if (isErasing) 0 else paintColor
        if (colors[cell] != newColor) {
            colors[cell] = newColor
            invalidate()
        }
    }

    private fun moveTo(x: Float, y: Float) {
        if (cellSize <= 0f) return

        val dx = x - lastX
        val dy = y - lastY
        val distance = hypot(dx.toDouble(), dy.toDouble())
        val stepSize = (cellSize / 4f).coerceAtLeast(1f)
        val steps = ceil(distance / stepSize).toInt().coerceAtLeast(1)

        for (step in 1..steps) {
            val fraction = step.toFloat() / steps
            paintCell(cellAt(lastX + dx * fraction, lastY + dy * fraction))
        }

        lastX = x
        lastY = y
    }

    private fun processMovement(event: MotionEvent, index: Int) {
        for (history in 0 until event.historySize) {
            moveTo(
                event.getHistoricalX(index, history),
                event.getHistoricalY(index, history)
            )
        }
        moveTo(event.getX(index), event.getY(index))
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (cellSize <= 0f) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pointerId = event.getPointerId(0)
                lastX = event.getX(0)
                lastY = event.getY(0)
                paintCell(cellAt(lastX, lastY))
            }

            MotionEvent.ACTION_MOVE -> {
                if (pointerId != NO_POINTER) {
                    val index = event.findPointerIndex(pointerId)
                    if (index >= 0) {
                        processMovement(event, index)
                    }
                }
            }

            MotionEvent.ACTION_UP -> {
                if (pointerId != NO_POINTER) {
                    val index = event.findPointerIndex(pointerId)
                    if (index >= 0) {
                        processMovement(event, index)
                    }
                }
                pointerId = NO_POINTER
                performClick()
            }

            MotionEvent.ACTION_CANCEL -> {
                pointerId = NO_POINTER
            }

            MotionEvent.ACTION_POINTER_DOWN -> Unit
            MotionEvent.ACTION_POINTER_UP -> {
                val index = event.actionIndex
                if (event.getPointerId(index) == pointerId) {
                    processMovement(event, index)
                    pointerId = NO_POINTER
                }
            }
        }

        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
