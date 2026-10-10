package com.pasupathi.nesemu

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject

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

data class Game(val uri: String, val name: String, var lastPlayed: Long)

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var game: GameView
    private lateinit var library: ScrollView
    private lateinit var libraryList: LinearLayout

    private var header = ""
    private var romStatus = ""
    private var currentName = ""
    private var romLoaded = false
    private var inGame = false

    private val games = ArrayList<Game>()

    private var emuThread: Thread? = null
    @Volatile private var running = false

    private val keyMask = IntArray(2)
    private val axisMask = IntArray(2)
    private val deviceSlots = ArrayList<Int>()

    private val addPicker = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri>? ->
        var added = 0
        for (u in uris.orEmpty()) {
            try {
                contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {
            }
            val s = u.toString()
            if (games.none { it.uri == s }) {
                games.add(Game(s, queryName(u).substringBeforeLast('.'), 0L))
                added++
            }
        }
        if (added > 0) saveLibrary()
        if (uris != null && uris.isNotEmpty()) {
            romStatus = if (added > 0) "$added game(s) added to library" else "Already in library"
        }
        refreshStatus()
        if (!inGame) rebuildLibrary()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        header = "NES core: ${nativeVersion()}  |  ${nativeCpuTest()}"

        fun bar(label: String, action: () -> Unit) = Button(this).apply {
            text = label
            setOnClickListener { action() }
        }
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(bar("Library") { if (inGame) showLibrary() else rebuildLibrary() },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(bar("Add games") { addPicker.launch(arrayOf("*/*")) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(bar("Quit") { quitApp() },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

        game = GameView(this).apply { isFocusable = false }
        libraryList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 24, 24)
        }
        library = ScrollView(this).apply { addView(libraryList) }

        val content = FrameLayout(this).apply {
            addView(game, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(library, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        status = TextView(this).apply {
            textSize = 12f
            setPadding(16, 8, 16, 8)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(topBar, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(content, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(status, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (inGame) showLibrary() else quitApp()
            }
        })

        loadLibrary()
        showLibrary()
    }

    override fun onResume() {
        super.onResume()
        startEmu()
    }

    override fun onPause() {
        super.onPause()
        stopEmu()
    }

    private fun quitApp() {
        stopEmu()
        finishAndRemoveTask()
    }

    private fun refreshStatus() {
        status.text = if (romStatus.isEmpty()) header else "$header\n$romStatus"
    }

    // ---------------- Library ----------------

    private fun loadLibrary() {
        games.clear()
        try {
            val arr = JSONArray(getSharedPreferences("library", MODE_PRIVATE).getString("games", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                games.add(Game(o.getString("uri"), o.getString("name"), o.optLong("last", 0L)))
            }
        } catch (e: Exception) {
        }
    }

    private fun saveLibrary() {
        val arr = JSONArray()
        for (g in games) {
            arr.put(JSONObject().put("uri", g.uri).put("name", g.name).put("last", g.lastPlayed))
        }
        getSharedPreferences("library", MODE_PRIVATE).edit().putString("games", arr.toString()).apply()
    }

    private fun queryName(uri: Uri): String {
        try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) return c.getString(i)
            }
        } catch (e: Exception) {
        }
        return uri.lastPathSegment ?: "Game"
    }

    private fun lp() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun rebuildLibrary() {
        libraryList.removeAllViews()

        if (romLoaded) {
            libraryList.addView(Button(this).apply {
                text = "\u25B6  Resume: $currentName"
                setOnClickListener { showGame() }
            }, lp())
        }

        if (games.isEmpty()) {
            libraryList.addView(TextView(this).apply {
                text = "No games yet.\nTap \"Add games\" and select your .nes files."
                textSize = 16f
                setPadding(8, 32, 8, 8)
            }, lp())
            return
        }

        libraryList.addView(TextView(this).apply {
            text = "Recent games  (tap to play, long-press to remove)"
            textSize = 13f
            setPadding(8, 24, 8, 8)
        }, lp())

        for (g in games.sortedByDescending { it.lastPlayed }) {
            libraryList.addView(Button(this).apply {
                text = g.name
                isAllCaps = false
                setOnClickListener { playGame(g) }
                setOnLongClickListener { confirmRemove(g); true }
            }, lp())
        }
    }

    private fun confirmRemove(g: Game) {
        AlertDialog.Builder(this)
            .setTitle("Remove from library?")
            .setMessage(g.name)
            .setPositiveButton("Remove") { _, _ ->
                games.remove(g)
                saveLibrary()
                rebuildLibrary()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showLibrary() {
        stopEmu()
        inGame = false
        game.visibility = View.GONE
        library.visibility = View.VISIBLE
        rebuildLibrary()
        refreshStatus()
    }

    private fun showGame() {
        inGame = true
        library.visibility = View.GONE
        game.visibility = View.VISIBLE
        romStatus = "Playing: $currentName"
        refreshStatus()
        startEmu()
    }

    private fun playGame(g: Game) {
        stopEmu()
        inGame = false
        try {
            val bytes = contentResolver.openInputStream(Uri.parse(g.uri))?.use { it.readBytes() }
            if (bytes == null) {
                romStatus = "Could not open ${g.name}"
            } else {
                val res = nativeLoadRom(bytes)
                if (res.startsWith("ROM:")) {
                    romLoaded = true
                    currentName = g.name
                    g.lastPlayed = System.currentTimeMillis()
                    saveLibrary()
                    clearInput()
                    showGame()
                    return
                } else {
                    romLoaded = false
                    romStatus = "${g.name}: $res"
                }
            }
        } catch (e: Exception) {
            romStatus = "Error: ${e.message}"
        }
        showLibrary()
    }

    // ---------------- Emulation + audio ----------------

    private fun startEmu() {
        if (!romLoaded || !inGame || running) return
        running = true
        emuThread = Thread {
            val pixels = IntArray(256 * 240)
            val audio = ShortArray(2048)
            var created: AudioTrack? = null
            try {
                val minBuf = AudioTrack.getMinBufferSize(
                    44100, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                created = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(44100)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build())
                    .setBufferSizeInBytes(maxOf(minBuf, 4096))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .build()
                created.play()
            } catch (e: Exception) {
                created = null
            }
            val track = created

            while (running) {
                val n = nativeRunFrame(pixels, audio)
                game.submit(pixels)
                var wrote = -1
                if (track != null && n > 0) {
                    // Blocking write: audio playback paces the emulator at the correct speed.
                    wrote = track.write(audio, 0, n)
                }
                if (wrote < 0) {
                    try {
                        Thread.sleep(16)
                    } catch (e: InterruptedException) {
                        break
                    }
                }
            }
            if (track != null) {
                try {
                    track.stop()
                } catch (e: Exception) {
                }
                track.release()
            }
        }.also { it.start() }
    }

    private fun stopEmu() {
        running = false
        emuThread?.join()
        emuThread = null
    }

    // ---------------- Input ----------------
    // NES bits: A=1 B=2 Select=4 Start=8 Up=16 Down=32 Left=64 Right=128

    private fun hasSource(src: Int, flag: Int) = (src and flag) == flag

    private fun isPadSource(src: Int) =
        hasSource(src, InputDevice.SOURCE_GAMEPAD) ||
            hasSource(src, InputDevice.SOURCE_JOYSTICK) ||
            hasSource(src, InputDevice.SOURCE_DPAD)

    private fun slotFor(deviceId: Int): Int {
        var i = deviceSlots.indexOf(deviceId)
        if (i < 0) {
            deviceSlots.add(deviceId)
            i = deviceSlots.size - 1
        }
        return if (i > 1) 1 else i
    }

    private fun playerFor(src: Int, deviceId: Int): Int =
        if (isPadSource(src)) slotFor(deviceId) else 0

    private fun buttonFor(keyCode: Int): Int = when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B,
        KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_R2,
        KeyEvent.KEYCODE_BUTTON_1, KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_X, KeyEvent.KEYCODE_SPACE -> 1
        KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y,
        KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_L2,
        KeyEvent.KEYCODE_BUTTON_C, KeyEvent.KEYCODE_BUTTON_2,
        KeyEvent.KEYCODE_BUTTON_Z, KeyEvent.KEYCODE_Z -> 2
        KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BUTTON_MODE,
        KeyEvent.KEYCODE_BUTTON_THUMBL,
        KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> 4
        KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_THUMBR,
        KeyEvent.KEYCODE_ENTER -> 8
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_W -> 16
        KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_S -> 32
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_A -> 64
        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_D -> 128
        else -> 0
    }

    private fun push(p: Int) {
        nativeSetButtons(p, keyMask[p] or axisMask[p])
    }

    private fun clearInput() {
        keyMask.fill(0)
        axisMask.fill(0)
        nativeSetButtons(0, 0)
        nativeSetButtons(1, 0)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!inGame || event.keyCode == KeyEvent.KEYCODE_BACK) {
            return super.dispatchKeyEvent(event)
        }
        val bit = buttonFor(event.keyCode)
        if (bit == 0) {
            return if (isPadSource(event.source)) true else super.dispatchKeyEvent(event)
        }
        val p = playerFor(event.source, event.deviceId)
        when (event.action) {
            KeyEvent.ACTION_DOWN -> keyMask[p] = keyMask[p] or bit
            KeyEvent.ACTION_UP -> keyMask[p] = keyMask[p] and bit.inv()
            else -> return true
        }
        push(p)
        return true
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (inGame &&
            hasSource(event.source, InputDevice.SOURCE_JOYSTICK) &&
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
            if (m != axisMask[p]) {
                axisMask[p] = m
                push(p)
            }
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    external fun nativeVersion(): String
    external fun nativeCpuTest(): String
    external fun nativeLoadRom(data: ByteArray): String
    external fun nativeRunFrame(pixels: IntArray, audio: ShortArray): Int
    external fun nativeSetButtons(player: Int, mask: Int)

    companion object {
        init {
            System.loadLibrary("nesemu")
        }
    }
}
