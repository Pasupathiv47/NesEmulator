package com.pasupathi.nesemu

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class GameView(context: Context) : View(context) {
    private val bitmap = Bitmap.createBitmap(256, 240, Bitmap.Config.ARGB_8888)
    private val lock = Any()
    private val paint = Paint().apply { isFilterBitmap = false }
    private val src = Rect(0, 0, 256, 240)
    private val dst = Rect()

    fun submit(pixels: IntArray) {
        synchronized(lock) { bitmap.setPixels(pixels, 0, 256, 0, 0, 256, 240) }
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        val scale = minOf(width / 256f, height / 240f)
        val w = (256 * scale).toInt()
        val h = (240 * scale).toInt()
        val l = (width - w) / 2
        val t = (height - h) / 2
        dst.set(l, t, l + w, t + h)
        synchronized(lock) { canvas.drawBitmap(bitmap, src, dst, paint) }
    }
}

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var game: GameView
    private var header = ""
    private var romStatus = "No ROM loaded"
    private var romLoaded = false

    private var emuThread: Thread? = null
    @Volatile private var running = false

    private val keyMask = IntArray(2)
    private val axisMask = IntArray(2)
    private val deviceSlots = ArrayList<Int>()

    private val picker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) loadRom(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        header = "NES core: ${nativeVersion()}  |  ${nativeCpuTest()}"

        val button = Button(this).apply {
            text = "Load ROM"
            isFocusable = false
            isFocusableInTouchMode = false
            setOnClickListener { picker.launch(arrayOf("*/*")) }
        }
        game = GameView(this)
        status = TextView(this).apply {
            textSize = 12f
            setPadding(16, 8, 16, 8)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(button, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(game, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(status, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(root)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        startEmu()
    }

    override fun onPause() {
        super.onPause()
        stopEmu()
    }

    private fun refresh() {
        status.text = "$header\n$romStatus"
    }

    private fun startEmu() {
        if (!romLoaded || running) return
        running = true
        emuThread = Thread {
            val pixels = IntArray(256 * 240)
            val frameNs = 16_639_267L
            var next = System.nanoTime()
            while (running) {
                nativeRunFrame(pixels)
                game.submit(pixels)
                next += frameNs
                val now = System.nanoTime()
                val sleep = next - now
                if (sleep > 0) {
                    try {
                        Thread.sleep(sleep / 1_000_000, (sleep % 1_000_000).toInt())
                    } catch (e: InterruptedException) {
                        break
                    }
                } else if (sleep < -100_000_000L) {
                    next = now
                }
            }
        }.also { it.start() }
    }

    private fun stopEmu() {
        running = false
        emuThread?.join()
        emuThread = null
    }

    private fun loadRom(uri: Uri) {
        stopEmu()
        romLoaded = false
        try {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
            romStatus = if (bytes == null) "Could not read file" else nativeLoadRom(bytes)
            romLoaded = romStatus.startsWith("ROM:")
        } catch (e: Exception) {
            romStatus = "Error: ${e.message}"
        }
        refresh()
        startEmu()
    }

    // ---- Input: NES bits A=1 B=2 Select=4 Start=8 Up=16 Down=32 Left=64 Right=128 ----

    private fun slotFor(deviceId: Int): Int {
        var i = deviceSlots.indexOf(deviceId)
        if (i < 0) {
            deviceSlots.add(deviceId)
            i = deviceSlots.size - 1
        }
        return if (i > 1) 1 else i
    }

    private fun isGamepadSource(src: Int): Boolean =
        (src and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD

    private fun buttonFor(keyCode: Int): Int = when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_X -> 1
        KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_Z -> 2
        KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_SHIFT_LEFT,
        KeyEvent.KEYCODE_SHIFT_RIGHT -> 4
        KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_ENTER -> 8
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_W -> 16
        KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_S -> 32
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_A -> 64
        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_D -> 128
        else -> 0
    }

    private fun push(p: Int) {
        nativeSetButtons(p, keyMask[p] or axisMask[p])
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val bit = buttonFor(keyCode)
        if (bit != 0) {
            val p = if (isGamepadSource(event.source)) slotFor(event.deviceId) else 0
            keyMask[p] = keyMask[p] or bit
            push(p)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val bit = buttonFor(keyCode)
        if (bit != 0) {
            val p = if (isGamepadSource(event.source)) slotFor(event.deviceId) else 0
            keyMask[p] = keyMask[p] and bit.inv()
            push(p)
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if ((event.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK &&
            event.action == MotionEvent.ACTION_MOVE
        ) {
            val p = slotFor(event.deviceId)
            val x = event.getAxisValue(MotionEvent.AXIS_X)
            val y = event.getAxisValue(MotionEvent.AXIS_Y)
            val hx = event.getAxisValue(MotionEvent.AXIS_HAT_X)
            val hy = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
            var m = 0
            if (x < -0.5f || hx < -0.5f) m = m or 64
            if (x > 0.5f || hx > 0.5f) m = m or 128
            if (y < -0.5f || hy < -0.5f) m = m or 16
            if (y > 0.5f || hy > 0.5f) m = m or 32
            axisMask[p] = m
            push(p)
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    external fun nativeVersion(): String
    external fun nativeCpuTest(): String
    external fun nativeLoadRom(data: ByteArray): String
    external fun nativeRunFrame(pixels: IntArray)
    external fun nativeSetButtons(player: Int, mask: Int)

    companion object {
        init {
            System.loadLibrary("nesemu")
        }
    }
}
