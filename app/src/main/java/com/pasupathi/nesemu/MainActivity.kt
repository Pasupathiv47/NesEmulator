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
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.json.JSONArray
import org.json.JSONObject

class GameView(context: Context) : View(context) {
    private val bitmap = Bitmap.createBitmap(256, 240, Bitmap.Config.ARGB_8888)
    private val lock = Any()
    private val paint = Paint().apply { isFilterBitmap = false }
    private val src = Rect(0, 0, 256, 240)
    private val dst = Rect()

    // 0 = Fit, 1 = 4:3, 2 = Stretch, 3 = Pixel-perfect
    var mode = 0
        set(value) {
            field = value
            invalidate()
        }

    fun submit(pixels: IntArray) {
        synchronized(lock) { bitmap.setPixels(pixels, 0, 256, 0, 0, 256, 240) }
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        val vw = width
        val vh = height
        val (w, h) = when (mode) {
            1 -> if (vw * 3 > vh * 4) Pair(vh * 4 / 3, vh) else Pair(vw, vw * 3 / 4)
            2 -> Pair(vw, vh)
            3 -> {
                val s = maxOf(1, minOf(vw / 256, vh / 240))
                Pair(256 * s, 240 * s)
            }
            else -> {
                val sc = minOf(vw / 256f, vh / 240f)
                Pair((256 * sc).toInt(), (240 * sc).toInt())
            }
        }
        val l = (vw - w) / 2
        val t = (vh - h) / 2
        dst.set(l, t, l + w, t + h)
        synchronized(lock) { canvas.drawBitmap(bitmap, src, dst, paint) }
    }
}

data class Game(val uri: String, val name: String, var lastPlayed: Long)

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var game: GameView
    private lateinit var pad: TouchPad
    private lateinit var library: ScrollView
    private lateinit var libraryList: LinearLayout
    private lateinit var topBar: LinearLayout
    private lateinit var menuBtn: Button
    private lateinit var btnLibrary: Button
    private lateinit var btnQuitGame: Button
    private lateinit var btnAspect: Button
    private lateinit var btnFull: Button
    private lateinit var btnTouch: Button
    private lateinit var btnAdd: Button
    private lateinit var btnExit: Button

    private var header = ""
    private var romStatus = ""
    private var currentName = ""
    private var romLoaded = false
    private var inGame = false

    private val aspectNames = arrayOf("Fit", "4:3", "Stretch", "Pixel-perfect")
    private var aspect = 0
    private var fullscreenPref = false

    private val touchNames = arrayOf("Off", "Light", "Normal", "Bold")
    private val touchOpacity = floatArrayOf(0f, 0.25f, 0.45f, 0.7f)
    private var touchLevel = 2
    private var padAutoHidden = false
    private var touchMask = 0

    private val games = ArrayList<Game>()

    private var emuThread: Thread? = null
    @Volatile private var running = false

    private val keyMask = IntArray(2)
    private val axisMask = IntArray(2)
    private val deviceSlots = ArrayList<Int>()

    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

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
        aspect = prefs.getInt("aspect", 0).coerceIn(0, aspectNames.size - 1)
        fullscreenPref = prefs.getBoolean("fullscreen", false)
        touchLevel = prefs.getInt("touch", 2).coerceIn(0, touchNames.size - 1)

        if (Build.VERSION.SDK_INT >= 28) {
            val attrs = window.attributes
            attrs.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = attrs
        }

        fun makeButton(action: () -> Unit) = Button(this).apply {
            isAllCaps = false
            textSize = 12f
            setOnClickListener { action() }
        }
        btnLibrary = makeButton { showLibrary() }.apply { text = "Library" }
        btnQuitGame = makeButton { quitGame() }.apply { text = "Quit game" }
        btnAspect = makeButton { cycleAspect() }
        btnFull = makeButton { toggleFullscreen() }
        btnTouch = makeButton { cycleTouch() }
        btnAdd = makeButton { addPicker.launch(arrayOf("*/*")) }.apply { text = "Add games" }
        btnExit = makeButton { quitApp() }.apply { text = "Exit app" }

        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            for (b in listOf(btnLibrary, btnQuitGame, btnAspect, btnFull, btnTouch, btnAdd, btnExit)) {
                addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
        }

        game = GameView(this).apply {
            isFocusable = false
            mode = aspect
            // Tapping the screen only brings the touch pad back after a controller hid it.
            setOnClickListener {
                if (inGame && padAutoHidden) {
                    padAutoHidden = false
                    updatePad()
                }
            }
        }
        pad = TouchPad(this).apply {
            visibility = View.GONE
            onMask = { m ->
                touchMask = m
                push(0)
            }
        }

        // Small corner button, only shown while fullscreen is on.
        menuBtn = Button(this).apply {
            text = "\u2630  Menu"
            isAllCaps = false
            textSize = 12f
            alpha = 0.5f
            visibility = View.GONE
            setOnClickListener { showMenu() }
        }

        libraryList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 24, 24)
        }
        library = ScrollView(this).apply { addView(libraryList) }

        val matchAll = {
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val content = FrameLayout(this).apply {
            addView(game, matchAll())
            addView(pad, matchAll())
            addView(menuBtn, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END).apply { setMargins(0, 12, 12, 0) })
            addView(library, matchAll())
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

    // End the current game completely and go back to the library.
    private fun quitGame() {
        stopEmu()
        nativeLoadRom(ByteArray(0))   // an empty ROM clears the emulator
        romLoaded = false
        currentName = ""
        romStatus = "Game closed"
        showLibrary()
    }

    private fun refreshStatus() {
        status.text = if (romStatus.isEmpty()) header else "$header\n$romStatus"
    }

    // ---------------- In-game menu (used in fullscreen) ----------------

    private fun showMenu() {
        if (!inGame) return
        stopEmu()   // pause while the menu is open
        val items = arrayOf(
            "Library (pause game)",
            "Quit game",
            "Aspect: ${aspectNames[aspect]}",
            "Fullscreen: ${if (fullscreenPref) "On" else "Off"}",
            "Touch pad: ${touchNames[touchLevel]}"
        )
        AlertDialog.Builder(this)
            .setTitle("Menu")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showLibrary()
                    1 -> quitGame()
                    2 -> cycleAspect()
                    3 -> toggleFullscreen()
                    4 -> cycleTouch()
                }
            }
            .setOnDismissListener { startEmu() }   // does nothing if we left the game
            .show()
    }

    // ---------------- Display and touch options ----------------

    private fun updateBar() {
        val g = if (inGame) View.VISIBLE else View.GONE
        val l = if (inGame) View.GONE else View.VISIBLE
        btnLibrary.visibility = g
        btnQuitGame.visibility = g
        btnAspect.visibility = g
        btnFull.visibility = g
        btnTouch.visibility = g
        btnAdd.visibility = l
        btnExit.visibility = l
        btnAspect.text = "Aspect: ${aspectNames[aspect]}"
        btnFull.text = "Fullscreen: ${if (fullscreenPref) "On" else "Off"}"
        btnTouch.text = "Touch pad: ${touchNames[touchLevel]}"
    }

    private fun cycleAspect() {
        aspect = (aspect + 1) % aspectNames.size
        prefs.edit().putInt("aspect", aspect).apply()
        game.mode = aspect
        updateBar()
    }

    private fun cycleTouch() {
        touchLevel = (touchLevel + 1) % touchNames.size
        prefs.edit().putInt("touch", touchLevel).apply()
        padAutoHidden = false
        updateBar()
        updatePad()
    }

    private fun updatePad() {
        val show = inGame && touchLevel > 0 && !padAutoHidden
        pad.opacity = touchOpacity[touchLevel]
        if (show) {
            pad.visibility = View.VISIBLE
        } else {
            pad.visibility = View.GONE
            pad.release()
            touchMask = 0
            push(0)
        }
    }

    private fun toggleFullscreen() {
        fullscreenPref = !fullscreenPref
        prefs.edit().putBoolean("fullscreen", fullscreenPref).apply()
        updateBar()
        applyFullscreen()
    }

    private fun applyFullscreen() {
        val on = inGame && fullscreenPref
        val c = WindowInsetsControllerCompat(window, window.decorView)
        if (on) {
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars())
            topBar.visibility = View.GONE
            status.visibility = View.GONE
            menuBtn.visibility = View.VISIBLE
        } else {
            c.show(WindowInsetsCompat.Type.systemBars())
            topBar.visibility = View.VISIBLE
            status.visibility = View.VISIBLE
            menuBtn.visibility = View.GONE
        }
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
        updateBar()
        updatePad()
        applyFullscreen()
        rebuildLibrary()
        refreshStatus()
    }

    private fun showGame() {
        inGame = true
        padAutoHidden = false
        library.visibility = View.GONE
        game.visibility = View.VISIBLE
        romStatus = "Playing: $currentName"
        updateBar()
        updatePad()
        applyFullscreen()
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

    // Player 1 also receives the on-screen touch pad.
    private fun push(p: Int) {
        nativeSetButtons(p, keyMask[p] or axisMask[p] or (if (p == 0) touchMask else 0))
    }

    private fun clearInput() {
        keyMask.fill(0)
        axisMask.fill(0)
        touchMask = 0
        pad.release()
        nativeSetButtons(0, 0)
        nativeSetButtons(1, 0)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!inGame || event.keyCode == KeyEvent.KEYCODE_BACK) {
            return super.dispatchKeyEvent(event)
        }
        // A real controller button hides the touch pad; tap the screen to bring it back.
        if (event.action == KeyEvent.ACTION_DOWN &&
            isPadSource(event.source) &&
            pad.visibility == View.VISIBLE
        ) {
            padAutoHidden = true
            updatePad()
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
