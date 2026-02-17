package com.mastertipsy.androidopencv.nidedgedetection

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.CornerPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.mastertipsy.androidopencv.dpToPx
import org.opencv.core.Point
import kotlin.math.sqrt

class NIDRectOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var points: Array<Point> = arrayOf()
    private var willDraw: Boolean = false

    private val path = Path()
    private val paint = Paint().apply {
        color = Color.GREEN
        strokeWidth = 6f
        style = Paint.Style.STROKE
        isAntiAlias = true
        pathEffect = CornerPathEffect(8.dpToPx.toFloat())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (points.size == 4) {
            path.apply {
                reset()
                if (willDraw) {
                    val padded = expandPoints(8.dpToPx)
                    moveTo(padded[0].x.toFloat(), padded[0].y.toFloat())
                    lineTo(padded[1].x.toFloat(), padded[1].y.toFloat())
                    lineTo(padded[2].x.toFloat(), padded[2].y.toFloat())
                    lineTo(padded[3].x.toFloat(), padded[3].y.toFloat())
                }
                close()
            }
            canvas.drawPath(path, paint)
        }
    }

    fun updateRectangle(points: Array<Point>, willDraw: Boolean) {
        this.points = points
        this.willDraw = willDraw
        invalidate()
    }

    private fun expandPoints(padding: Int): Array<Point> {
        val cx = points.map { it.x }.average()
        val cy = points.map { it.y }.average()
        return points.map { p ->
            val dx = p.x - cx
            val dy = p.y - cy
            val length = sqrt(dx * dx + dy * dy)
            val scale = (length + padding) / length
            Point(cx + dx * scale, cy + dy * scale)
        }.toTypedArray()
    }
}
