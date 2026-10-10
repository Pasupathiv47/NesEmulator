package com.pasupathi.nesemu

import android.net.Uri
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var info: TextView
    private var header = ""
    private var romStatus = "No ROM loaded"
    private var inputStatus = "Press a controller button..."

    private val picker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) loadRom(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        header = "NES core: ${nativeVersion()}\n${nativeCpuTest()}"

        val button = Button(this).apply {
            text = "Load ROM"
            isFocusable = false
            isFocusableInTouchMode = false
            setOnClickListener { picker.launch(arrayOf("*/*")) }
        }
        info = TextView(this).apply {
            textSize = 16f
            setPadding(32, 32, 32, 32)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 48, 24, 24)
            addView(button)
            addView(info)
        }
        setContentView(root)
        refresh()
    }

    private fun refresh() {
        info.text = "$header\n\n$romStatus\n\n$inputStatus"
    }

    private fun loadRom(uri: Uri) {
        try {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
            romStatus = if (bytes == null) "Could not read file" else nativeLoadRom(bytes)
        } catch (e: Exception) {
            romStatus = "Error: ${e.message}"
        }
        refresh()
    }

    private fun isGamepad(source: Int): Boolean {
        return (source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK ||
            (source and InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (isGamepad(event.source)) {
            val name = event.device?.name ?: "unknown"
            inputStatus = "Device: $name\nButton: ${KeyEvent.keyCodeToString(keyCode)}"
            refresh()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if ((event.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK &&
            event.action == MotionEvent.ACTION_MOVE
        ) {
            val x = event.getAxisValue(MotionEvent.AXIS_X)
            val y = event.getAxisValue(MotionEvent.AXIS_Y)
            val hx = event.getAxisValue(MotionEvent.AXIS_HAT_X)
            val hy = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
            inputStatus = "Stick X=%.2f Y=%.2f\nHat X=%.0f Y=%.0f".format(x, y, hx, hy)
            refresh()
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    external fun nativeVersion(): String
    external fun nativeCpuTest(): String
    external fun nativeLoadRom(data: ByteArray): String

    companion object {
        init {
            System.loadLibrary("nesemu")
        }
    }
}
