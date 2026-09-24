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

class MatrixView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        private const val ROWS = 8
        private const val COLS = 8
        private const val NO_POINTER = -1
    }

    // Номер логической ячейки: row * 8 + col.
    // -1 означает отсутствие нажатия.
    var activeCell: Int = -1
        private set

    var onCellChanged: (() -> Unit)? = null

    private var pointerId = NO_POINTER

    private var lastX = 0f
    private var lastY = 0f

    private var cellSize = 0f
    private var gridLeft = 0f
    private var gridTop = 0f
    private var gridSize = 0f

    private val density = resources.displayMetrics.density

    private val backgroundPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val activePaint = Paint().apply {
        color = Color.rgb(255, 224, 178)
        style = Paint.Style.FILL
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(150, 150, 150)
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
    }

    init {
        isClickable = true
    }

    override fun onSizeChanged(
        w: Int,
        h: Int,
        oldw: Int,
        oldh: Int
    ) {
        super.onSizeChanged(w, h, oldw, oldh)

        clearTouch()

        // Небольшой отступ, чтобы крайние линии не обрезались.
        val margin = 2f * density

        gridSize = (min(w, h).toFloat() - margin * 2f)
            .coerceAtLeast(0f)

        cellSize = gridSize / COLS
        gridLeft = (w - gridSize) / 2f
        gridTop = (h - gridSize) / 2f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (cellSize <= 0f) {
            return
        }

        canvas.drawRect(
            gridLeft,
            gridTop,
            gridLeft + gridSize,
            gridTop + gridSize,
            backgroundPaint
        )

        if (activeCell >= 0) {
            val row = activeCell / COLS
            val col = activeCell % COLS

            val left = gridLeft + col * cellSize
            val top = gridTop + row * cellSize

            canvas.drawRect(
                left,
                top,
                left + cellSize,
                top + cellSize,
                activePaint
            )
        }

        linePaint.color = if (isEnabled) {
            Color.rgb(130, 130, 130)
        } else {
            Color.rgb(195, 195, 195)
        }

        for (row in 0..ROWS) {
            val y = gridTop + row * cellSize

            canvas.drawLine(
                gridLeft,
                y,
                gridLeft + gridSize,
                y,
                linePaint
            )
        }

        for (col in 0..COLS) {
            val x = gridLeft + col * cellSize

            canvas.drawLine(
                x,
                gridTop,
                x,
                gridTop + gridSize,
                linePaint
            )
        }
    }

    override fun setEnabled(enabled: Boolean) {
        if (!enabled) {
            clearTouch()
        }

        super.setEnabled(enabled)
        invalidate()
    }

    private fun cellAt(x: Float, y: Float): Int {
        if (cellSize <= 0f) {
            return -1
        }

        val localX = x - gridLeft
        val localY = y - gridTop

        if (
            localX < 0f ||
            localY < 0f ||
            localX >= gridSize ||
            localY >= gridSize
        ) {
            return -1
        }

        val col = (localX / cellSize).toInt()
        val row = (localY / cellSize).toInt()

        return row * COLS + col
    }

    private fun setActiveCell(cell: Int) {
        if (activeCell == cell) {
            return
        }

        activeCell = cell
        invalidate()
        onCellChanged?.invoke()
    }

    /*
     * Между двумя полученными координатами добавляем промежуточные.
     * Шаг не больше четверти клетки:
     * при проходе по строке/столбцу промежуточные клетки не теряются.
     *
     * Реальный путь между измерениями сенсора неизвестен,
     * поэтому здесь используется линейная интерполяция.
     */
    private fun moveTo(x: Float, y: Float) {
        if (cellSize <= 0f) {
            return
        }

        val dx = x - lastX
        val dy = y - lastY

        val distance = hypot(dx.toDouble(), dy.toDouble())
        val stepSize = (cellSize / 4f).coerceAtLeast(1f)

        val steps = ceil(distance / stepSize)
            .toInt()
            .coerceAtLeast(1)

        for (step in 1..steps) {
            val fraction = step.toFloat() / steps

            val pointX = lastX + dx * fraction
            val pointY = lastY + dy * fraction

            setActiveCell(cellAt(pointX, pointY))
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

        moveTo(
            event.getX(index),
            event.getY(index)
        )
    }

    fun clearTouch() {
        pointerId = NO_POINTER
        setActiveCell(-1)
        parent?.requestDisallowInterceptTouchEvent(false)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || cellSize <= 0f) {
            return false
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pointerId = event.getPointerId(0)

                lastX = event.getX(0)
                lastY = event.getY(0)

                parent?.requestDisallowInterceptTouchEvent(true)
                setActiveCell(cellAt(lastX, lastY))
            }

            MotionEvent.ACTION_MOVE -> {
                if (pointerId != NO_POINTER) {
                    val index = event.findPointerIndex(pointerId)

                    if (index >= 0) {
                        processMovement(event, index)
                    } else {
                        clearTouch()
                    }
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val index = event.actionIndex

                if (event.getPointerId(index) == pointerId) {
                    processMovement(event, index)
                    clearTouch()
                    performClick()
                }
            }

            MotionEvent.ACTION_UP -> {
                if (pointerId != NO_POINTER) {
                    val index = event.findPointerIndex(pointerId)

                    if (index >= 0) {
                        processMovement(event, index)
                    }
                }

                clearTouch()
                performClick()
            }

            MotionEvent.ACTION_CANCEL -> {
                clearTouch()
            }

            // Дополнительные пальцы не переключают активную ячейку.
            MotionEvent.ACTION_POINTER_DOWN -> Unit
        }

        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDetachedFromWindow() {
        clearTouch()
        super.onDetachedFromWindow()
    }
}
