package com.example.scifilauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.random.Random

class CedalMatrixRainView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val random = Random(System.currentTimeMillis())
    private val drops = mutableListOf<Drop>()
    private var lastFrameTime = 0L

    // Matrix-style green colors
    private val headPaint = Paint().apply {
        color = 0xFFE6FFEA.toInt() // light green head
        isAntiAlias = true
    }
    private val tailPaint = Paint().apply {
        color = 0xFF09C20C.toInt() // your MatrixGreen tail
        isAntiAlias = true
    }

    private data class Drop(
        var x: Float,
        var y: Float,
        var speed: Float,
        var length: Int
    )

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        initDrops(w, h)
    }

    private fun initDrops(width: Int, height: Int) {
        drops.clear()
        if (width == 0 || height == 0) return

        val columnCount = 30
        val widthPerColumn = width.toFloat() / columnCount

        for (i in 0 until columnCount) {
            val x = i * widthPerColumn + widthPerColumn / 2f
            val speed = 120f + random.nextFloat() * 120f
            val length = 10 + random.nextInt(15)
            val startY = -random.nextInt(height).toFloat()
            drops.add(Drop(x, startY, speed, length))
        }
        lastFrameTime = System.currentTimeMillis()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (drops.isEmpty()) return

        val now = System.currentTimeMillis()
        val dt = max(16L, now - lastFrameTime)
        lastFrameTime = now

        val charHeight = 18f

        for (drop in drops) {
            drop.y += drop.speed * (dt / 1000f)
            if (drop.y - drop.length * charHeight > height) {
                drop.y = -random.nextInt(height).toFloat()
                drop.speed = 120f + random.nextFloat() * 120f
                drop.length = 10 + random.nextInt(15)
            }

            for (i in 0 until drop.length) {
                val y = drop.y - i * charHeight
                if (y < -charHeight || y > height + charHeight) continue

                val alphaFactor = 1f - i.toFloat() / drop.length
                if (alphaFactor <= 0f) continue

                val isHead = i == 0
                val paint = if (isHead) headPaint else tailPaint
                val alpha = (alphaFactor * 255).toInt().coerceIn(0, 255)
                paint.alpha = alpha

                canvas.drawCircle(drop.x, y, if (isHead) 4f else 3f, paint)
            }
        }

        postInvalidateOnAnimation()
    }
}
