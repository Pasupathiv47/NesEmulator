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
// Every item is a button with its own position, size, visibility and action.
class TouchPad(context: Context) : View(context) {

    class Item(var x: Float, var y: Float, var scale: Float, var visible: Boolean, var action: Int)

    companion object {
        const val TA = 256
        const val TB = 512
        val ACTION_NAMES = arrayOf(
            "A", "B", "Turbo A", "Turbo B", "Select", "Start", "Up", "Down", "Left", "Right")
        private val ACTION_BITS = intArrayOf(1, 2, TA, TB, 4, 8, 16, 32, 64, 128)
        private val ACTION_LABELS = arrayOf(
            "A", "B", "TA", "TB", "SELECT", "START", "", "", "", "")
        private const val TURBO_MS = 50L
        private const val COUNT = 10
    }

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
    private var pendingDefault = true

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
    private var brBase = 0f
    private var pillHwBase = 0f
    private var arrowBase = 0f

    init {
        resetLayout()
    }

    fun resetLayout() {
        items.clear()
        for (i in 0 until COUNT) items.add(Item(0.5f, 0.5f, 1f, true, 0))
        pendingDefault = true
        selected = 0
        if (width > 0 && height > 0) layoutGeometry()
        invalidate()
    }

    // Returns null while the pad still uses the built-in default layout.
    fun exportLayout(): String? {
        if (pendingDefault) return null
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

    fun importLayout(json: String?): Boolean {
        if (json == null) return false
        try {
            val arr = JSONArray(json)
            if (arr.length() != COUNT) return false
            val tmp = ArrayList<Item>()
            for (i in 0 until COUNT) {
                val o = arr.getJSONObject(i)
                tmp.add(
                    Item(
                        o.getDouble("x").toFloat().coerceIn(0.02f, 0.98f),
                        o.getDouble("y").toFloat().coerceIn(0.02f, 0.98f),
                        o.getDouble("s").toFloat().coerceIn(0.5f, 2.5f),
                        o.getBoolean("v"),
                        o.getInt("a").coerceIn(0, ACTION_BITS.size - 1)
                    )
                )
            }
            items.clear()
            items.addAll(tmp)
            pendingDefault = false
            selected = 0
            invalidate()
            return true
        } catch (e: Exception) {
            return false
        }
    }

    // ---- editing helpers ----

    fun changeSize(factor: Float) {
        val item = items[selected]
        item.scale = (item.scale * factor).coerceIn(0.5f, 2.5f)
        invalidate()
    }

    fun toggleVisible() {
        items[selected].visible = !items[selected].visible
        invalidate()
    }

    fun setAction(action: Int) {
        items[selected].action = action.coerceIn(0, ACTION_BITS.size - 1)
        invalidate()
    }

    fun describe(): String {
        val item = items[selected]
        return "Button ${selected + 1} (${ACTION_NAMES[item.action]})" +
            if (item.visible) "" else " - hidden"
    }

    // ---- geometry ----

    private fun layoutGeometry() {
        u = min(width, height) / 100f
        brBase = min(10f * u, 42f * dp)
        pillHwBase = min(7f * u, 36f * dp)
        arrowBase = brBase * 0.55f
        if (pendingDefault && width > 0 && height > 0) applyDefaults()
    }

    private fun place(i: Int, px: Float, py: Float, action: Int, visible: Boolean) {
        val item = items[i]
        item.x = px / width
        item.y = py / height
        item.scale = 1f
        item.visible = visible
        item.action = action
    }

    private fun applyDefaults() {
        val m = min(5f * u, 24f * dp)
        val half = arrowBase
        val sp = half * 2.3f
        val cx = m + half + sp
        val cy = height - m - half - sp
        val br = brBase
        val aX = width - m - br
        val aY = height - m - br - 1.8f * br
        val bX = aX - 2.4f * br
        val bY = height - m - br
        val pillHh = pillHwBase * 3f / 7f
        val sY = height - m - pillHh

        place(0, cx, cy - sp, 6, true)          // Up
        place(1, cx, cy + sp, 7, true)          // Down
        place(2, cx - sp, cy, 8, true)          // Left
        place(3, cx + sp, cy, 9, true)          // Right
        place(4, aX, aY, 0, true)               // A
        place(5, bX, bY, 1, true)               // B
        place(6, width / 2f - 9f * u, sY, 4, true)   // Select
        place(7, width / 2f + 9f * u, sY, 5, true)   // Start
        place(8, aX, aY - 2.3f * br, 2, false)  // Turbo A (hidden)
        place(9, bX, bY - 2.3f * br, 3, false)  // Turbo B (hidden)
        pendingDefault = false
    }

    // 0 = circle, 1 = pill (Select/Start), 2 = arrow square
    private fun shapeOf(i: Int): Int {
        val a = items[i].action
        return if (a >= 6) 2 else if (a >= 4) 1 else 0
    }

    private fun hit(i: Int, x: Float, y: Float, slop: Float): Boolean {
        val item = items[i]
        val dx = abs(x - item.x * width)
        val dy = abs(y - item.y * height)
        return when (shapeOf(i)) {
            2 -> {
                val h = arrowBase * item.scale * slop
                dx <= h && dy <= h
            }
            1 -> {
                val hw = pillHwBase * item.scale
                val hh = hw * 3f / 7f
                dx <= hw * slop && dy <= hh * slop * 1.5f
            }
            else -> hypot(dx, dy) <= brBase * item.scale * slop
        }
    }

    private fun playSlop(i: Int): Float = when (shapeOf(i)) {
        2 -> 1.15f
        1 -> 1.2f
        else -> 1.3f
    }

    private fun maskAt(x: Float, y: Float): Int {
        layoutGeometry()
        var m = 0
        for (i in items.indices) {
            val item = items[i]
            if (item.visible && hit(i, x, y, playSlop(i))) m = m or ACTION_BITS[item.action]
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

    private fun outline(c: Canvas, i: Int, p: Paint, grow: Float) {
        val item = items[i]
        val cx = item.x * width
        val cy = item.y * height
        when (shapeOf(i)) {
            2 -> {
                val h = arrowBase * item.scale * grow
                rect.set(cx - h, cy - h, cx + h, cy + h)
                c.drawRoundRect(rect, h * 0.3f, h * 0.3f, p)
            }
            1 -> {
                val hw = pillHwBase * item.scale * grow
                val hh = hw * 3f / 7f * (if (grow > 1f) 1.2f else 1f)
                rect.set(cx - hw, cy - hh, cx + hw, cy + hh)
                c.drawRoundRect(rect, hh, hh, p)
            }
            else -> c.drawCircle(cx, cy, brBase * item.scale * grow, p)
        }
    }

    private fun drawArrowGlyph(c: Canvas, cx: Float, cy: Float, half: Float, dirX: Float, dirY: Float) {
        val t = half * 0.5f
        val px = -dirY
        val py = dirX
        val bx = cx - dirX * t * 0.7f
        val by = cy - dirY * t * 0.7f
        path.reset()
        path.moveTo(cx + dirX * t, cy + dirY * t)
        path.lineTo(bx + px * t, by + py * t)
        path.lineTo(bx - px * t, by - py * t)
        path.close()
        label.alpha = 210
        c.drawPath(path, label)
    }

    private fun drawButton(c: Canvas, i: Int, base: Int, hi: Int) {
        val item = items[i]
        val cx = item.x * width
        val cy = item.y * height
        val pressed = (held and ACTION_BITS[item.action]) != 0
        fill.alpha = if (pressed) hi else base

        when (shapeOf(i)) {
            2 -> {
                val h = arrowBase * item.scale
                rect.set(cx - h, cy - h, cx + h, cy + h)
                c.drawRoundRect(rect, h * 0.3f, h * 0.3f, fill)
                val dirX = when (item.action) { 8 -> -1f; 9 -> 1f; else -> 0f }
                val dirY = when (item.action) { 6 -> -1f; 7 -> 1f; else -> 0f }
                drawArrowGlyph(c, cx, cy, h, dirX, dirY)
            }
            1 -> {
                val hw = pillHwBase * item.scale
                val hh = hw * 3f / 7f
                rect.set(cx - hw, cy - hh, cx + hw, cy + hh)
                c.drawRoundRect(rect, hh, hh, fill)
                label.alpha = 220
                label.textSize = max(hh * 0.85f, 10f * dp)
                c.drawText(ACTION_LABELS[item.action], cx,
                    cy - (label.descent() + label.ascent()) / 2f, label)
            }
            else -> {
                val r = brBase * item.scale
                c.drawCircle(cx, cy, r, fill)
                val text = ACTION_LABELS[item.action]
                label.alpha = 220
                label.textSize = r * (if (text.length > 1) 0.75f else 0.95f)
                c.drawText(text, cx, cy - (label.descent() + label.ascent()) / 2f, label)
            }
        }

        if (!item.visible) {
            stroke.strokeWidth = 2f * dp
            stroke.alpha = 200
            stroke.pathEffect = DashPathEffect(floatArrayOf(10f * dp, 6f * dp), 0f)
            outline(c, i, stroke, 1f)
            stroke.pathEffect = null
        }
    }

    override fun onDraw(c: Canvas) {
        layoutGeometry()
        val op = if (editing) max(opacity, 0.45f) else opacity
        val base = (op * 255f).toInt().coerceIn(0, 255)
        val hi = min(255, base + 100)

        for (i in items.indices) {
            val item = items[i]
            if (!item.visible && !editing) continue
            drawButton(c, i, if (item.visible) base else base / 3, hi)
        }
        if (editing) outline(c, selected, highlight, 1.15f)
    }
}
