package com.pasupathi.nesemu

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

// NES bits: A=1 B=2 Select=4 Start=8 Up=16 Down=32 Left=64 Right=128
class TouchPad(context: Context) : View(context) {

    var onMask: ((Int) -> Unit)? = null
    var opacity = 0.45f
        set(value) {
            field = value
            invalidate()
        }

    private var mask = 0
    private val dp = resources.displayMetrics.density

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val rect = RectF()

    // layout (recomputed from the view size)
    private var u = 1f
    private var dCx = 0f
    private var dCy = 0f
    private var dR = 0f
    private var br = 0f
    private var aCx = 0f
    private var aCy = 0f
    private var bCx = 0f
    private var bCy = 0f
    private var selCx = 0f
    private var stCx = 0f
    private var ssCy = 0f
    private var pillHw = 0f
    private var pillHh = 0f

    private fun layoutGeometry() {
        u = min(width, height) / 100f
        val m = min(5f * u, 24f * dp)
        dR = min(20f * u, 80f * dp)
        dCx = m + dR
        dCy = height - m - dR
        br = min(10f * u, 42f * dp)
        aCx = width - m - br
        aCy = height - m - br - 1.8f * br
        bCx = aCx - 2.4f * br
        bCy = height - m - br
        pillHw = 7f * u
        pillHh = 3f * u
        selCx = width / 2f - 9f * u
        stCx = width / 2f + 9f * u
        ssCy = height - m - pillHh
    }

    private fun inPill(x: Float, y: Float, cx: Float): Boolean =
        abs(x - cx) <= pillHw * 1.2f && abs(y - ssCy) <= pillHh * 1.8f

    private fun maskAt(x: Float, y: Float): Int {
        layoutGeometry()
        var m = 0
        val dx = x - dCx
        val dy = y - dCy
        val d = hypot(dx, dy)
        if (d <= dR * 1.35f && d > dR * 0.15f) {
            val nx = dx / d
            val ny = dy / d
            if (nx > 0.4f) m = m or 128
            if (nx < -0.4f) m = m or 64
            if (ny > 0.4f) m = m or 32
            if (ny < -0.4f) m = m or 16
        }
        if (hypot(x - aCx, y - aCy) <= br * 1.45f) m = m or 1
        if (hypot(x - bCx, y - bCy) <= br * 1.45f) m = m or 2
        if (inPill(x, y, selCx)) m = m or 4
        if (inPill(x, y, stCx)) m = m or 8
        return m
    }

    private fun computeMask(e: MotionEvent): Int {
        val action = e.actionMasked
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) return 0
        val skip = if (action == MotionEvent.ACTION_POINTER_UP) e.actionIndex else -1
        var m = 0
        for (i in 0 until e.pointerCount) {
            if (i == skip) continue
            m = m or maskAt(e.getX(i), e.getY(i))
        }
        return m
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        // A touch that starts outside every control passes through to the game view.
        if (e.actionMasked == MotionEvent.ACTION_DOWN && maskAt(e.x, e.y) == 0) return false
        val nm = computeMask(e)
        if (nm != mask) {
            if ((nm and mask.inv()) != 0) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            mask = nm
            onMask?.invoke(mask)
            invalidate()
        }
        return true
    }

    fun release() {
        mask = 0
        invalidate()
    }

    private fun arm(c: Canvas, bit: Int, base: Int, hi: Int, l: Float, t: Float, r: Float, b: Float) {
        fill.alpha = if ((mask and bit) != 0) hi else base
        rect.set(l, t, r, b)
        c.drawRoundRect(rect, u, u, fill)
    }

    private fun button(c: Canvas, cx: Float, cy: Float, bit: Int, text: String, base: Int, hi: Int) {
        fill.alpha = if ((mask and bit) != 0) hi else base
        c.drawCircle(cx, cy, br, fill)
        label.alpha = 220
        label.textSize = br * 0.95f
        c.drawText(text, cx, cy - (label.descent() + label.ascent()) / 2f, label)
    }

    private fun pill(c: Canvas, cx: Float, bit: Int, text: String, base: Int, hi: Int) {
        fill.alpha = if ((mask and bit) != 0) hi else base
        rect.set(cx - pillHw, ssCy - pillHh, cx + pillHw, ssCy + pillHh)
        c.drawRoundRect(rect, pillHh, pillHh, fill)
        label.alpha = 220
        label.textSize = max(pillHh * 0.85f, 10f * dp)
        c.drawText(text, cx, ssCy - (label.descent() + label.ascent()) / 2f, label)
    }

    override fun onDraw(c: Canvas) {
        layoutGeometry()
        val base = (opacity * 255f).toInt().coerceIn(0, 255)
        val hi = min(255, base + 100)

        // D-pad
        fill.alpha = base / 3
        c.drawCircle(dCx, dCy, dR, fill)
        stroke.strokeWidth = max(2f, u * 0.4f)
        stroke.alpha = base
        c.drawCircle(dCx, dCy, dR, stroke)
        val w = dR * 0.3f
        arm(c, 16, base, hi, dCx - w, dCy - dR * 0.92f, dCx + w, dCy - dR * 0.12f)
        arm(c, 32, base, hi, dCx - w, dCy + dR * 0.12f, dCx + w, dCy + dR * 0.92f)
        arm(c, 64, base, hi, dCx - dR * 0.92f, dCy - w, dCx - dR * 0.12f, dCy + w)
        arm(c, 128, base, hi, dCx + dR * 0.12f, dCy - w, dCx + dR * 0.92f, dCy + w)

        // A / B
        button(c, bCx, bCy, 2, "B", base, hi)
        button(c, aCx, aCy, 1, "A", base, hi)

        // Select / Start
        pill(c, selCx, 4, "SELECT", base, hi)
        pill(c, stCx, 8, "START", base, hi)
    }
}
