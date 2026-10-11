package com.pasupathi.nesemu

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

// NES bits: A=1 B=2 Select=4 Start=8 Up=16 Down=32 Left=64 Right=128
// Internal turbo bits: TA=256, TB=512
class TouchPad(context: Context) : View(context) {

    class Item(var x: Float, var y: Float, var scale: Float, var visible: Boolean, var action: Int)

    companion object {
        const val TA = 256
        const val TB = 512
        val ACTION_NAMES = arrayOf("A", "B", "Turbo A", "Turbo B", "Select", "Start")
        private val ACTION_BITS = intArrayOf(1, 2, TA, TB, 4, 8)
        private val ACTION_LABELS = arrayOf("A", "B", "TA", "TB", "SELECT", "START")
        private const val TURBO_MS = 50L
    }

    // items[0] = D-pad, items[1..6] = buttons
    val items = ArrayList<Item>()
    var selected = 0

    var onMask: ((Int) -> Unit)? = null
    var onSelect: (() -> Unit)? = null

    var opacity = 0.45f
        set(value) {
            field = value
            invalidate()
        }

    var editing = false
        set(value) {
            field = value
            dragging = false
            invalidate()
        }

    private var held = 0
    private var lastEmitted = 0
    private var turboPhase = false
    private var turboRunning = false
    private var dragging = false
    private var dragDx = 0f
    private var dragDy = 0f

    private val dp = resources.displayMetrics.density

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
    }
    private val highlight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#FFD54F")
        strokeWidth = 3f * dp
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val rect = RectF()
    private val path = Path()

    // base sizes (recomputed from the view size)
    private var u = 1f
    private var dRBase = 0f
    private var brBase = 0f
    private var pillHwBase = 0f

    init {
        resetLayout()
    }

    fun resetLayout() {
        items.clear()
        items.add(Item(0.13f, 0.72f, 1f, true, 0))    // D-pad
        items.add(Item(0.935f, 0.68f, 1f, true, 0))   // A
        items.add(Item(0.835f, 0.84f, 1f, true, 1))   // B
        items.add(Item(0.43f, 0.93f, 1f, true, 4))    // Select
        items.add(Item(0.57f, 0.93f, 1f, true, 5))    // Start
        items.add(Item(0.935f, 0.42f, 1f, false, 2))  // Turbo A (hidden)
        items.add(Item(0.835f, 0.58f, 1f, false, 3))  // Turbo B (hidden)
        selected = 0
        invalidate()
    }

    fun exportLayout(): String {
        val arr = JSONArray()
        for (item in items) {
            arr.put(
                JSONObject()
                    .put("x", item.x.toDouble())
                    .put("y", item.y.toDouble())
                    .put("s", item.scale.toDouble())
                    .put("v", item.visible)
                    .put("a", item.action)
            )
        }
        return arr.toString()
    }

    fun importLayout(json: String?) {
        if (json == null) return
        try {
            val arr = JSONArray(json)
            if (arr.length() != items.size) return
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val item = items[i]
                item.x = o.getDouble("x").toFloat().coerceIn(0.02f, 0.98f)
                item.y = o.getDouble("y").toFloat().coerceIn(0.02f, 0.98f)
                item.scale = o.getDouble("s").toFloat().coerceIn(0.5f, 2.5f)
                item.visible = o.getBoolean("v")
                item.action = o.getInt("a").coerceIn(0, ACTION_BITS.size - 1)
            }
        } catch (e: Exception) {
        }
        invalidate()
    }

    // ---- editing helpers ----

    fun changeSize(factor: Float) {
        val item = items[selected]
        item.scale = (item.scale * factor).coerceIn(0.5f, 2.5f)
        invalidate()
    }

    fun toggleVisible() {
        if (selected == 0) return
        items[selected].visible = !items[selected].visible
        invalidate()
    }

    fun setAction(action: Int) {
        if (selected == 0) return
        items[selected].action = action.coerceIn(0, ACTION_BITS.size - 1)
        invalidate()
    }

    fun describe(): String {
        if (selected == 0) return "D-pad"
        val item = items[selected]
        return "Button $selected (${ACTION_NAMES[item.action]})" + if (item.visible) "" else " - hidden"
    }

    // ---- geometry ----

    private fun layoutGeometry() {
        u = min(width, height) / 100f
        dRBase = min(22f * u, 88f * dp)
        brBase = min(10f * u, 42f * dp)
        pillHwBase = min(7f * u, 36f * dp)
    }

    private fun isPill(i: Int): Boolean {
        if (i == 0) return false
        val a = items[i].action
        return a == 4 || a == 5
    }

    private fun hit(i: Int, x: Float, y: Float, slop: Float): Boolean {
        val item = items[i]
        val cx = item.x * width
        val cy = item.y * height
        if (i == 0) return hypot(x - cx, y - cy) <= dRBase * item.scale * slop
        if (isPill(i)) {
            val hw = pillHwBase * item.scale
            val hh = hw * 3f / 7f
            return abs(x - cx) <= hw * slop && abs(y - cy) <= hh * slop * 1.5f
        }
        return hypot(x - cx, y - cy) <= brBase * item.scale * slop
    }

    // D-pad with a dead zone, narrow diagonals and "sticky" directions.
    private fun dpadMask(x: Float, y: Float, prev: Int): Int {
        val item = items[0]
        val dx = x - item.x * width
        val dy = y - item.y * height
        val r = dRBase * item.scale
        val d = hypot(dx, dy)
        if (d > r * 1.35f || d < r * 0.22f) return 0
        val nx = dx / d
        val ny = dy / d
        fun on(v: Float, bit: Int): Boolean = v > (if ((prev and bit) != 0) 0.45f else 0.6f)
        var m = 0
        if (on(nx, 128)) m = m or 128
        if (on(-nx, 64)) m = m or 64
        if (on(ny, 32)) m = m or 32
        if (on(-ny, 16)) m = m or 16
        return m
    }

    private fun maskAt(x: Float, y: Float): Int {
        layoutGeometry()
        var m = dpadMask(x, y, held)
        for (i in 1 until items.size) {
            val item = items[i]
            if (item.visible && hit(i, x, y, 1.3f)) m = m or ACTION_BITS[item.action]
        }
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

    // ---- turbo + output ----

    private fun effective(): Int {
        var m = held and 0xFF
        if (turboPhase) {
            if ((held and TA) != 0) m = m or 1
            if ((held and TB) != 0) m = m or 2
        }
        return m
    }

    private fun emit() {
        val e = effective()
        if (e != lastEmitted) {
            lastEmitted = e
            onMask?.invoke(e)
        }
    }

    private val turboTick = object : Runnable {
        override fun run() {
            if ((held and (TA or TB)) == 0) {
                turboRunning = false
                return
            }
            turboPhase = !turboPhase
            emit()
            invalidate()
            postDelayed(this, TURBO_MS)
        }
    }

    fun release() {
        held = 0
        lastEmitted = 0
        turboPhase = false
        turboRunning = false
        removeCallbacks(turboTick)
        invalidate()
    }

    // ---- touch ----

    private val scaler = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val item = items[selected]
                item.scale = (item.scale * detector.scaleFactor).coerceIn(0.5f, 2.5f)
                invalidate()
                onSelect?.invoke()
                return true
            }
        })

    private fun findItem(x: Float, y: Float): Int {
        for (i in items.size - 1 downTo 0) {
            if (hit(i, x, y, 1.15f)) return i
        }
        return -1
    }

    private fun editTouch(e: MotionEvent): Boolean {
        scaler.onTouchEvent(e)
        if (scaler.isInProgress) {
            dragging = false
            return true
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                layoutGeometry()
                val idx = findItem(e.x, e.y)
                if (idx >= 0) {
                    selected = idx
                    dragging = true
                    dragDx = items[idx].x * width - e.x
                    dragDy = items[idx].y * height - e.y
                    onSelect?.invoke()
                    invalidate()
                } else {
                    dragging = false
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging && e.pointerCount == 1) {
                    val item = items[selected]
                    item.x = ((e.x + dragDx) / width).coerceIn(0.03f, 0.97f)
                    item.y = ((e.y + dragDy) / height).coerceIn(0.05f, 0.97f)
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return true
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (editing) return editTouch(e)

        // A touch that starts outside every control passes through to the game view.
        if (e.actionMasked == MotionEvent.ACTION_DOWN && maskAt(e.x, e.y) == 0) return false
        val nm = computeMask(e)
        if (nm != held) {
            if ((nm and held.inv()) != 0) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            held = nm
            if ((held and (TA or TB)) != 0) {
                if (!turboRunning) {
                    turboRunning = true
                    turboPhase = true
                    postDelayed(turboTick, TURBO_MS)
                }
            } else {
                turboPhase = false
            }
            emit()
            invalidate()
        }
        return true
    }

    // ---- drawing ----

    private fun arrowButton(
        c: Canvas, cx: Float, cy: Float, r: Float, bit: Int,
        dirX: Float, dirY: Float, base: Int, hi: Int
    ) {
        val s = r * 0.27f
        fill.alpha = if ((held and bit) != 0) hi else base
        rect.set(cx - s, cy - s, cx + s, cy + s)
        c.drawRoundRect(rect, s * 0.35f, s * 0.35f, fill)

        val t = s * 0.55f
        val px = -dirY
        val py = dirX
        val bx = cx - dirX * t * 0.7f
        val by = cy - dirY * t * 0.7f
        path.reset()
        path.moveTo(cx + dirX * t, cy + dirY * t)
        path.lineTo(bx + px * t, by + py * t)
        path.lineTo(bx - px * t, by - py * t)
        path.close()
        label.alpha = 200
        c.drawPath(path, label)
    }

    private fun drawDpad(c: Canvas, base: Int, hi: Int) {
        val item = items[0]
        val cx = item.x * width
        val cy = item.y * height
        val r = dRBase * item.scale
        fill.alpha = base / 4
        c.drawCircle(cx, cy, r, fill)
        stroke.strokeWidth = max(2f, u * 0.4f)
        stroke.alpha = base
        c.drawCircle(cx, cy, r, stroke)
        val off = r * 0.62f
        arrowButton(c, cx, cy - off, r, 16, 0f, -1f, base, hi)
        arrowButton(c, cx, cy + off, r, 32, 0f, 1f, base, hi)
        arrowButton(c, cx - off, cy, r, 64, -1f, 0f, base, hi)
        arrowButton(c, cx + off, cy, r, 128, 1f, 0f, base, hi)
    }

    private fun drawButton(c: Canvas, i: Int, base: Int, hi: Int) {
        val item = items[i]
        val cx = item.x * width
        val cy = item.y * height
        val bit = ACTION_BITS[item.action]
        val pressed = (held and bit) != 0
        fill.alpha = if (pressed) hi else base
        val text = ACTION_LABELS[item.action]

        if (isPill(i)) {
            val hw = pillHwBase * item.scale
            val hh = hw * 3f / 7f
            rect.set(cx - hw, cy - hh, cx + hw, cy + hh)
            c.drawRoundRect(rect, hh, hh, fill)
            label.alpha = 220
            label.textSize = max(hh * 0.85f, 10f * dp)
            c.drawText(text, cx, cy - (label.descent() + label.ascent()) / 2f, label)
        } else {
            val r = brBase * item.scale
            c.drawCircle(cx, cy, r, fill)
            label.alpha = 220
            label.textSize = r * (if (text.length > 1) 0.75f else 0.95f)
            c.drawText(text, cx, cy - (label.descent() + label.ascent()) / 2f, label)
        }

        if (!item.visible) {
            // dashed outline for hidden buttons (edit mode only)
            stroke.strokeWidth = 2f * dp
            stroke.alpha = 200
            stroke.pathEffect = DashPathEffect(floatArrayOf(10f * dp, 6f * dp), 0f)
            if (isPill(i)) {
                val hw = pillHwBase * item.scale
                val hh = hw * 3f / 7f
                rect.set(cx - hw, cy - hh, cx + hw, cy + hh)
                c.drawRoundRect(rect, hh, hh, stroke)
            } else {
                c.drawCircle(cx, cy, brBase * item.scale, stroke)
            }
            stroke.pathEffect = null
        }
    }

    private fun drawSelection(c: Canvas) {
        val item = items[selected]
        val cx = item.x * width
        val cy = item.y * height
        if (selected == 0) {
            c.drawCircle(cx, cy, dRBase * item.scale * 1.12f, highlight)
        } else if (isPill(selected)) {
            val hw = pillHwBase * item.scale * 1.12f
            val hh = hw * 3f / 7f * 1.2f
            rect.set(cx - hw, cy - hh, cx + hw, cy + hh)
            c.drawRoundRect(rect, hh, hh, highlight)
        } else {
            c.drawCircle(cx, cy, brBase * item.scale * 1.15f, highlight)
        }
    }

    override fun onDraw(c: Canvas) {
        layoutGeometry()
        val op = if (editing) max(opacity, 0.45f) else opacity
        val base = (op * 255f).toInt().coerceIn(0, 255)
        val hi = min(255, base + 100)

        drawDpad(c, base, hi)
        for (i in 1 until items.size) {
            val item = items[i]
            if (!item.visible && !editing) continue
            drawButton(c, i, if (item.visible) base else base / 3, hi)
        }
        if (editing) drawSelection(c)
    }
}
