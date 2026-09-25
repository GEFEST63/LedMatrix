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
        System.arraycopy(frame.colors, 0, colors, 0,
