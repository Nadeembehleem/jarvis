package com.jarvis.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Animated arc-reactor. mode: 0 idle, 1 thinking, 2 listening, 3 speaking */
class ReactorView(c: Context) : View(c) {
    @Volatile var mode = 0
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var a = 0f

    init { setLayerType(LAYER_TYPE_SOFTWARE, null) }

    override fun onDraw(cv: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(cx, cy) * 0.9f
        val col = if (mode == 2) 0xFFFFC83D.toInt() else 0xFF27D3FF.toInt()
        a += when (mode) { 1 -> 9f; 2 -> 4f; 3 -> 5f; else -> 1.5f }
        p.style = Paint.Style.STROKE
        p.strokeWidth = 5f
        p.color = col
        for (i in 0..2) {
            val rr = r * (1f - 0.24f * i)
            val dir = if (i == 1) -1f else 1f
            cv.drawArc(RectF(cx - rr, cy - rr, cx + rr, cy + rr), dir * a * (1f + i * 0.4f) + i * 50f, 250f, false, p)
        }
        p.style = Paint.Style.FILL
        p.setShadowLayer(30f, 0f, 0f, col)
        cv.drawCircle(cx, cy, r * (0.28f + 0.04f * sin(a / 10f)), p)
        p.clearShadowLayer()
        postInvalidateOnAnimation()
    }
}

data class GNode(val label: String, val kind: Int, val x: Float, val y: Float, val z: Float)

/** Rotating 3D galaxy of notes (cyan) and memories (gold). */
class GalaxyView(c: Context, private val nodes: List<GNode>) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var a = 0f

    override fun onDraw(cv: Canvas) {
        cv.drawColor(0xFF04101A.toInt())
        a += 0.006f
        val w = width.toFloat()
        val h = height.toFloat()
        val cs = cos(a)
        val sn = sin(a)
        p.textSize = 26f
        if (nodes.isEmpty()) {
            p.color = 0xFF7AB8CC.toInt()
            cv.drawText("No notes or memories yet", 30f, h / 2f, p)
        }
        val pr = nodes.map { n ->
            val px = n.x * cs - n.z * sn
            val pz = n.x * sn + n.z * cs
            val k = 1f / (2.6f - pz)
            Triple(n, Pair(w / 2f + px * k * w * 0.9f, h / 2f + n.y * k * h * 0.9f), k)
        }.sortedBy { it.third }
        for ((n, pt, k) in pr) {
            p.color = if (n.kind == 0) 0xFF27D3FF.toInt() else 0xFFFFC83D.toInt()
            p.alpha = (min(1f, k * 1.6f) * 255).toInt()
            cv.drawCircle(pt.first, pt.second, k * 7f, p)
            if (k > 0.5f) cv.drawText(n.label.take(18), pt.first + 10f, pt.second + 4f, p)
        }
        postInvalidateOnAnimation()
    }
}
