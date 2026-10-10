package com.pasupathi.nesemu

import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var info: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        info = TextView(this).apply {
            textSize = 18f
            setPadding(32, 64, 32, 32)
            text = "NES core: ${nativeVersion()}\nPress a controller button..."
        }
        setContentView(info)
    }

    private fun isGamepad(source: Int): Boolean {
        return (source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK ||
            (source and InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (isGamepad(event.source)) {
            val name = event.device?.name ?: "unknown"
            info.text = "Device: $name\nButton: ${KeyEvent.keyCodeToString(keyCode)}"
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
            info.text = "Stick X=%.2f Y=%.2f\nHat X=%.0f Y=%.0f".format(x, y, hx, hy)
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    external fun nativeVersion(): String

    companion object {
        init {
            System.loadLibrary("nesemu")
        }
    }
}
