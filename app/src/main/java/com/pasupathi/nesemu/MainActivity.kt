package com.pasupathi.nesemu

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
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
import java.io.File
import java.security.MessageDigest

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

// A named touch-pad layout. layout == null means the built-in default.
class Profile(var name: String, var layout: String?)

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var game: GameView
    private lateinit var pad: TouchPad
    private lateinit var library: LinearLayout
    private lateinit var libraryList: LinearLayout
    private lateinit var btnTabRecent: Button
    private lateinit var btnTabAll: Button
    private lateinit var topBar: View
    private lateinit var editBar: LinearLayout
    private lateinit var editLabel: TextView
    private lateinit var btnVis: Button
    private lateinit var btnAction: Button
    private lateinit var menuBtn: Button
    private lateinit var btnSaveExit: Button
    private lateinit var btnReset: Button
    private lateinit var btnLibrary: Button
    private lateinit var btnQuitGame: Button
    private lateinit var btnAspect: Button
    private lateinit var btnFull: Button
    private lateinit var btnTouch: Button
    private lateinit var btnProfile: Button
    private lateinit var btnEditPad: Button
    private lateinit var btnAdd: Button
    private lateinit var btnAddFolder: Button
    private lateinit var btnExit: Button

    private var header = ""
    private var romStatus = ""
    private var currentName = ""
    private var currentGame: Game? = null
    private var romLoaded = false
    private var inGame = false
    private var editing = false
    private var libTab = 0          // 0 = Recently played, 1 = Game library
    private var openDialogs = 0

    private val aspectNames = arrayOf("Fit", "4:3", "Stretch", "Pixel-perfect")
    private var aspect = 0
    private var fullscreenPref = false

    private val touchNames = arrayOf("Off", "Light", "Normal", "Bold")
    private val touchOpacity = floatArrayOf(0f, 0.25f, 0.45f, 0.7f)
    private var touchLevel = 2
    private var padAutoHidden = false
    private var touchMask = 0

    private val profiles = ArrayList<Profile>()
    private var gameProfiles = JSONObject()
    private var activeProfile = DEFAULT_PROFILE

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
        if (added > 0) {
            saveLibrary()
            libTab = 1
        }
        if (uris != null && uris.isNotEmpty()) {
            romStatus = if (added > 0) "$added game(s) added to library" else "Already in library"
        }
        refreshStatus()
        if (!inGame) rebuildLibrary()
    }

    private val folderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { tree: Uri? ->
        if (tree != null) {
            try {
                contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {
            }
            romStatus = "Scanning folder..."
            refreshStatus()
            Thread {
                val found = scanFolder(tree)
                runOnUiThread { addFoundGames(found) }
            }.start()
        }
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
        btnSaveExit = makeButton { saveAndExit() }.apply { text = "Save & exit" }
        btnReset = makeButton { confirmReset() }.apply { text = "Reset" }
        btnLibrary = makeButton { showLibrary() }.apply { text = "Library" }
        btnQuitGame = makeButton { quitGame() }.apply { text = "Quit game" }
        btnAspect = makeButton { cycleAspect() }
        btnFull = makeButton { toggleFullscreen() }
        btnTouch = makeButton { cycleTouch() }
        btnProfile = makeButton { showProfileMenu() }
        btnEditPad = makeButton { enterEdit() }.apply { text = "Edit pad" }
        btnAdd = makeButton { addPicker.launch(arrayOf("*/*")) }.apply { text = "Add games" }
        btnAddFolder = makeButton { folderPicker.launch(null) }.apply { text = "Add folder" }
        btnExit = makeButton { quitApp() }.apply { text = "Exit app" }

        // Scrolls sideways when there are more buttons than fit on screen.
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            for (b in listOf(btnSaveExit, btnReset, btnLibrary, btnQuitGame, btnAspect, btnFull,
                btnTouch, btnProfile, btnEditPad, btnAdd, btnAddFolder, btnExit)) {
                addView(b, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }
        topBar = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(topRow)
        }

        // ---- pad editor bar ----
        editLabel = TextView(this).apply {
            textSize = 11f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12, 4, 12, 4)
        }
        val btnSmaller = makeButton { pad.changeSize(0.9f); updateEditBar() }.apply { text = "Size \u2212" }
        val btnBigger = makeButton { pad.changeSize(1.1f); updateEditBar() }.apply { text = "Size +" }
        btnAction = makeButton { chooseAction() }.apply { text = "Action" }
        btnVis = makeButton { pad.toggleVisible(); updateEditBar() }.apply { text = "Hide" }
        val btnResetPad = makeButton { confirmResetPad() }.apply { text = "Reset pad" }
        val btnDone = makeButton { exitEdit() }.apply { text = "Done" }
        editBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            addView(editLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2.6f))
            for (b in listOf(btnSmaller, btnBigger, btnAction, btnVis, btnResetPad, btnDone)) {
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
            onSelect = { updateEditBar() }
        }
        loadProfiles()

        // Small corner button, only shown while fullscreen is on.
        menuBtn = Button(this).apply {
            text = "\u2630  Menu"
            isAllCaps = false
            textSize = 12f
            alpha = 0.5f
            visibility = View.GONE
            setOnClickListener { showMenu() }
        }

        // ---- library: logo header + two tabs (pages) ----
        val dpx = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpx(12), dpx(6), dpx(12), dpx(6))
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_logo)
            }, LinearLayout.LayoutParams(dpx(44), dpx(44)))
            addView(TextView(this@MainActivity).apply {
                text = "NES Emu"
                textSize = 20f
                setTypeface(null, Typeface.BOLD)
                setPadding(dpx(12), 0, 0, 0)
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        btnTabRecent = makeButton { libTab = 0; rebuildLibrary() }.apply { text = "Recently played" }
        btnTabAll = makeButton { libTab = 1; rebuildLibrary() }.apply { text = "Game library" }
        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(btnTabRecent, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(btnTabAll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        libraryList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 24, 24)
        }
        val libraryScroll = ScrollView(this).apply { addView(libraryList) }
        library = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(brand, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(tabRow, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(libraryScroll, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }

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
            addView(editBar, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(content, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(status, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    editing -> exitEdit()
                    inGame -> showLibrary()
                    else -> quitApp()
                }
            }
        })

        loadLibrary()
        libTab = if (games.any { it.lastPlayed > 0L }) 0 else 1
        showLibrary()
    }

    override fun onResume() {
        super.onResume()
        startEmu()
    }

    override fun onPause() {
        super.onPause()
        if (editing) saveLayout()
        stopEmu()
    }

    // Pauses the game while a dialog is open and resumes when the last one closes.
    private fun showPaused(b: AlertDialog.Builder) {
        stopEmu()
        openDialogs++
        b.setOnDismissListener {
            openDialogs--
            if (openDialogs <= 0) {
                openDialogs = 0
                startEmu()
            }
        }
        b.show()
    }

    private fun askName(title: String, initial: String, onOk: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(initial)
            setSelectAllOnFocus(true)
            isSingleLine = true
        }
        showPaused(
            AlertDialog.Builder(this)
                .setTitle(title)
                .setView(input)
                .setPositiveButton("OK") { _, _ ->
                    val n = input.text.toString().trim()
                    if (n.isNotEmpty()) onOk(n)
                }
                .setNegativeButton("Cancel", null)
        )
    }

    // ---------------- Folder scan ----------------

    // Walks the chosen folder and all sub-folders and returns every .nes file found.
    private fun scanFolder(tree: Uri): List<Pair<Uri, String>> {
        val out = ArrayList<Pair<Uri, String>>()
        val queue = ArrayDeque<String>()
        queue.add(DocumentsContract.getTreeDocumentId(tree))
        var visited = 0
        while (queue.isNotEmpty() && visited < 3000 && out.size < 5000) {
            val parent = queue.removeFirst()
            visited++
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
            try {
                contentResolver.query(
                    children,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE
                    ),
                    null, null, null
                )?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getString(0) ?: continue
                        val name = c.getString(1) ?: continue
                        val mime = c.getString(2)
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            queue.add(id)
                        } else if (name.lowercase().endsWith(".nes")) {
                            out.add(Pair(DocumentsContract.buildDocumentUriUsingTree(tree, id), name))
                        }
                    }
                }
            } catch (e: Exception) {
            }
        }
        return out
    }

    private fun addFoundGames(found: List<Pair<Uri, String>>) {
        var added = 0
        for ((u, n) in found) {
            val s = u.toString()
            if (games.none { it.uri == s }) {
                games.add(Game(s, n.substringBeforeLast('.'), 0L))
                added++
            }
        }
        if (added > 0) {
            saveLibrary()
            libTab = 1
        }
        romStatus = "Folder scan: ${found.size} .nes file(s) found, $added new"
        refreshStatus()
        if (!inGame) rebuildLibrary()
    }

    // ---------------- Controller profiles ----------------

    private fun loadProfiles() {
        profiles.clear()
        try {
            val arr = JSONArray(prefs.getString("profiles", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                profiles.add(Profile(o.getString("n"), if (o.has("l")) o.getString("l") else null))
            }
        } catch (e: Exception) {
        }
        if (profiles.none { it.name == DEFAULT_PROFILE }) profiles.add(0, Profile(DEFAULT_PROFILE, null))
        gameProfiles = try {
            JSONObject(prefs.getString("gameprofiles", "{}"))
        } catch (e: Exception) {
            JSONObject()
        }
    }

    private fun saveProfiles() {
        val arr = JSONArray()
        for (p in profiles) {
            val o = JSONObject().put("n", p.name)
            if (p.layout != null) o.put("l", p.layout)
            arr.put(o)
        }
        prefs.edit()
            .putString("profiles", arr.toString())
            .putString("gameprofiles", gameProfiles.toString())
            .apply()
    }

    private fun profileNameFor(g: Game): String {
        val n = gameProfiles.optString(stateKey(g), DEFAULT_PROFILE)
        return if (profiles.any { it.name == n }) n else DEFAULT_PROFILE
    }

    private fun setProfileFor(g: Game, name: String) {
        gameProfiles.put(stateKey(g), name)
        saveProfiles()
    }

    private fun applyProfile(name: String) {
        val p = profiles.firstOrNull { it.name == name } ?: profiles[0]
        activeProfile = p.name
        if (p.layout == null || !pad.importLayout(p.layout)) pad.resetLayout()
    }

    private fun showProfileMenu() {
        val g = currentGame ?: return
        val names = profiles.map { (if (it.name == activeProfile) "\u2713  " else "      ") + it.name }
        val items = names + listOf("+  New profile...", "Rename this profile...", "Delete this profile")
        showPaused(
            AlertDialog.Builder(this)
                .setTitle("Controller profile")
                .setItems(items.toTypedArray()) { _, which ->
                    when {
                        which < profiles.size -> {
                            val n = profiles[which].name
                            setProfileFor(g, n)
                            applyProfile(n)
                            updateBar()
                        }
                        which == profiles.size -> newProfile(g)
                        which == profiles.size + 1 -> renameProfile()
                        else -> deleteProfile(g)
                    }
                }
        )
    }

    private fun newProfile(g: Game) {
        askName("New profile name", "") { n ->
            if (profiles.any { it.name.equals(n, ignoreCase = true) }) {
                romStatus = "A profile named \"$n\" already exists"
                refreshStatus()
            } else {
                // starts as a copy of the layout you are using now
                profiles.add(Profile(n, pad.exportLayout()))
                setProfileFor(g, n)
                applyProfile(n)
                updateBar()
                romStatus = "Profile \"$n\" created - use Edit pad to change it"
                refreshStatus()
            }
        }
    }

    private fun renameProfile() {
        val old = activeProfile
        if (old == DEFAULT_PROFILE) {
            romStatus = "The Default profile cannot be renamed"
            refreshStatus()
            return
        }
        askName("Rename profile", old) { n ->
            if (n != old && profiles.any { it.name.equals(n, ignoreCase = true) }) {
                romStatus = "A profile named \"$n\" already exists"
                refreshStatus()
            } else {
                profiles.firstOrNull { it.name == old }?.name = n
                val keys = ArrayList<String>()
                val iter = gameProfiles.keys()
                while (iter.hasNext()) keys.add(iter.next())
                for (k in keys) if (gameProfiles.optString(k) == old) gameProfiles.put(k, n)
                activeProfile = n
                saveProfiles()
                updateBar()
            }
        }
    }

    private fun deleteProfile(g: Game) {
        val old = activeProfile
        if (old == DEFAULT_PROFILE) {
            romStatus = "The Default profile cannot be deleted"
            refreshStatus()
            return
        }
        showPaused(
            AlertDialog.Builder(this)
                .setTitle("Delete profile \"$old\"?")
                .setMessage("Games that use it will switch to the Default profile.")
                .setPositiveButton("Delete") { _, _ ->
                    profiles.removeAll { it.name == old }
                    val keys = ArrayList<String>()
                    val iter = gameProfiles.keys()
                    while (iter.hasNext()) keys.add(iter.next())
                    for (k in keys) if (gameProfiles.optString(k) == old) gameProfiles.remove(k)
                    setProfileFor(g, DEFAULT_PROFILE)
                    applyProfile(DEFAULT_PROFILE)
                    saveProfiles()
                    updateBar()
                }
                .setNegativeButton("Cancel", null)
        )
    }

    // ---------------- Save / reset / quit ----------------

    private fun stateKey(g: Game): String {
        val d = MessageDigest.getInstance("SHA-1").digest(g.uri.toByteArray())
        return d.joinToString("") { "%02x".format(it) }.take(24)
    }

    private fun stateFile(g: Game): File {
        val dir = File(filesDir, "states")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, stateKey(g) + ".state")
    }

    private fun hasSave(g: Game): Boolean = stateFile(g).exists()

    // Saves the running game; stops the emulator. Returns true on success.
    private fun saveCurrentState(): Boolean {
        val g = currentGame ?: return false
        stopEmu()
        val data = nativeSaveState() ?: return false
        return try {
            val f = stateFile(g)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeBytes(data)
            if (f.exists()) f.delete()
            tmp.renameTo(f)
        } catch (e: Exception) {
            false
        }
    }

    private fun saveAndExit() {
        if (currentGame == null || !romLoaded) return
        val name = currentName
        if (saveCurrentState()) {
            closeGame("Saved \"$name\" - open it from the library to continue")
        } else {
            romStatus = "Could not save the game"
            refreshStatus()
            startEmu()
        }
    }

    private fun confirmReset() {
        val g = currentGame ?: return
        showPaused(
            AlertDialog.Builder(this)
                .setTitle("Reset game?")
                .setMessage("Restarts \"$currentName\" from the very beginning, like pressing the " +
                    "Reset button on the console. Multi-game carts return to their menu. " +
                    "Unsaved progress is lost.")
                .setPositiveButton("Reset") { _, _ -> resetGame(g) }
                .setNegativeButton("Cancel", null)
        )
    }

    // Power-cycles the console with the same game.
    private fun resetGame(g: Game) {
        stopEmu()
        try {
            val bytes = contentResolver.openInputStream(Uri.parse(g.uri))?.use { it.readBytes() }
            if (bytes != null && nativeLoadRom(bytes).startsWith("ROM:")) {
                clearInput()
                romStatus = "Playing: $currentName (reset)"
                refreshStatus()
                startEmu()
                return
            }
        } catch (e: Exception) {
        }
        closeGame("Could not reset the game")
    }

    private fun closeGame(message: String) {
        stopEmu()
        nativeLoadRom(ByteArray(0))   // an empty ROM clears the emulator
        romLoaded = false
        currentName = ""
        currentGame = null
        romStatus = message
        showLibrary()
    }

    // Leave the game without saving.
    private fun quitGame() {
        closeGame("Game closed (not saved)")
    }

    private fun quitApp() {
        stopEmu()
        val g = currentGame
        if (romLoaded && g != null) {
            AlertDialog.Builder(this)
                .setTitle("Exit app")
                .setMessage("Save \"$currentName\" so you can continue it later?")
                .setPositiveButton("Save & exit") { _, _ ->
                    if (saveCurrentState()) {
                        finishAndRemoveTask()
                    } else {
                        romStatus = "Could not save the game"
                        refreshStatus()
                    }
                }
                .setNegativeButton("Exit without saving") { _, _ -> finishAndRemoveTask() }
                .setNeutralButton("Cancel", null)
                .show()
        } else {
            finishAndRemoveTask()
        }
    }

    private fun refreshStatus() {
        status.text = if (romStatus.isEmpty()) header else "$header\n$romStatus"
    }

    // ---------------- Pad editor ----------------

    // Saves the layout you are editing into the active profile.
    private fun saveLayout() {
        profiles.firstOrNull { it.name == activeProfile }?.layout = pad.exportLayout()
        saveProfiles()
    }

    private fun enterEdit() {
        if (!inGame || editing) return
        stopEmu()   // pause the game while editing
        editing = true
        padAutoHidden = false
        pad.editing = true
        updatePad()
        applyFullscreen()
        updateEditBar()
    }

    private fun exitEdit() {
        if (!editing) return
        editing = false
        pad.editing = false
        saveLayout()
        updatePad()
        applyFullscreen()
        startEmu()
    }

    private fun updateEditBar() {
        editLabel.text = "${pad.describe()}  \u00B7  Profile: $activeProfile\n" +
            "Drag to move \u00B7 pinch to resize"
        btnVis.text = if (pad.items[pad.selected].visible) "Hide" else "Show"
    }

    private fun chooseAction() {
        AlertDialog.Builder(this)
            .setTitle("What should this button do?")
            .setItems(TouchPad.ACTION_NAMES) { _, which ->
                pad.setAction(which)
                updateEditBar()
            }
            .show()
    }

    private fun confirmResetPad() {
        AlertDialog.Builder(this)
            .setTitle("Reset this profile's layout?")
            .setMessage("All buttons go back to their default size, place and action.")
            .setPositiveButton("Reset") { _, _ ->
                pad.resetLayout()
                updateEditBar()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------------- In-game menu (used in fullscreen) ----------------

    private fun showMenu() {
        if (!inGame) return
        val items = arrayOf(
            "Save & exit",
            "Reset game",
            "Library (pause game)",
            "Quit game (no save)",
            "Aspect: ${aspectNames[aspect]}",
            "Fullscreen: ${if (fullscreenPref) "On" else "Off"}",
            "Touch pad: ${touchNames[touchLevel]}",
            "Pad profile: $activeProfile",
            "Edit touch pad"
        )
        showPaused(
            AlertDialog.Builder(this)
                .setTitle("Menu")
                .setItems(items) { _, which ->
                    when (which) {
                        0 -> saveAndExit()
                        1 -> confirmReset()
                        2 -> showLibrary()
                        3 -> quitGame()
                        4 -> cycleAspect()
                        5 -> toggleFullscreen()
                        6 -> cycleTouch()
                        7 -> showProfileMenu()
                        8 -> enterEdit()
                    }
                }
        )
    }

    // ---------------- Display and touch options ----------------

    private fun updateBar() {
        val g = if (inGame) View.VISIBLE else View.GONE
        val l = if (inGame) View.GONE else View.VISIBLE
        btnSaveExit.visibility = g
        btnReset.visibility = g
        btnLibrary.visibility = g
        btnQuitGame.visibility = g
        btnAspect.visibility = g
        btnFull.visibility = g
        btnTouch.visibility = g
        btnProfile.visibility = g
        btnEditPad.visibility = g
        btnAdd.visibility = l
        btnAddFolder.visibility = l
        btnExit.visibility = l
        btnAspect.text = "Aspect: ${aspectNames[aspect]}"
        btnFull.text = "Fullscreen: ${if (fullscreenPref) "On" else "Off"}"
        btnTouch.text = "Touch pad: ${touchNames[touchLevel]}"
        btnProfile.text = "Pad: $activeProfile"
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
        val show = inGame && (editing || (touchLevel > 0 && !padAutoHidden))
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
        } else {
            c.show(WindowInsetsCompat.Type.systemBars())
        }
        topBar.visibility = if (on || editing) View.GONE else View.VISIBLE
        status.visibility = if (on) View.GONE else View.VISIBLE
        menuBtn.visibility = if (on && !editing) View.VISIBLE else View.GONE
        editBar.visibility = if (editing) View.VISIBLE else View.GONE
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

    private fun headerRow(title: String, actions: List<Pair<String, () -> Unit>>): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 24, 8, 4)
            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 14f
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            for ((label, act) in actions) {
                addView(Button(this@MainActivity).apply {
                    text = label
                    isAllCaps = false
                    textSize = 12f
                    setOnClickListener { act() }
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }
    }

    private fun gameRow(g: Game, withDelete: Boolean): LinearLayout {
        val saved = hasSave(g)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Button(this@MainActivity).apply {
                text = (if (saved) "\uD83D\uDCBE  " else "") + g.name
                isAllCaps = false
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setOnClickListener { onGameTap(g) }
                setOnLongClickListener { showGameOptions(g); true }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (withDelete) {
                addView(Button(this@MainActivity).apply {
                    text = "\u2715"
                    setOnClickListener { confirmDelete(g) }
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }
    }

    private fun updateTabs() {
        btnTabRecent.alpha = if (libTab == 0) 1f else 0.5f
        btnTabAll.alpha = if (libTab == 1) 1f else 0.5f
        btnTabRecent.setTypeface(null, if (libTab == 0) Typeface.BOLD else Typeface.NORMAL)
        btnTabAll.setTypeface(null, if (libTab == 1) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun rebuildLibrary() {
        libraryList.removeAllViews()
        updateTabs()

        if (romLoaded) {
            libraryList.addView(Button(this).apply {
                text = "\u25B6  Resume: $currentName"
                setOnClickListener { showGame() }
            }, lp())
        }

        if (libTab == 0) buildRecentPage() else buildAllPage()
    }

    private fun buildRecentPage() {
        val recent = games.filter { it.lastPlayed > 0L }
            .sortedByDescending { it.lastPlayed }
            .take(15)
        val actions: List<Pair<String, () -> Unit>> =
            if (recent.isNotEmpty()) listOf("Clear list" to { confirmClearRecent() }) else emptyList()
        libraryList.addView(headerRow("Recently played", actions), lp())
        if (recent.isEmpty()) {
            libraryList.addView(TextView(this).apply {
                text = "Nothing played recently.\nOpen the \"Game library\" tab to start a game."
                textSize = 14f
                setPadding(8, 16, 8, 8)
            }, lp())
        } else {
            for (g in recent) libraryList.addView(gameRow(g, false), lp())
        }
        libraryList.addView(TextView(this).apply {
            text = "\uD83D\uDCBE = saved game   \u00B7   hold a game for more options"
            textSize = 11f
            setPadding(8, 24, 8, 8)
        }, lp())
    }

    private fun buildAllPage() {
        val actions: List<Pair<String, () -> Unit>> = listOf(
            "Add games" to { addPicker.launch(arrayOf("*/*")) },
            "Add folder" to { folderPicker.launch(null) }
        )
        libraryList.addView(headerRow("All games  (${games.size})", actions), lp())
        if (games.isEmpty()) {
            libraryList.addView(TextView(this).apply {
                text = "No games yet.\nTap \"Add games\" to pick .nes files, or \"Add folder\" to add a whole folder."
                textSize = 14f
                setPadding(8, 16, 8, 8)
            }, lp())
        } else {
            for (g in games.sortedBy { it.name.lowercase() }) libraryList.addView(gameRow(g, true), lp())
        }
        libraryList.addView(TextView(this).apply {
            text = "\uD83D\uDCBE = saved game   \u00B7   \u2715 removes a game from the library " +
                "(your ROM file stays on the phone)   \u00B7   hold a game for more options"
            textSize = 11f
            setPadding(8, 24, 8, 8)
        }, lp())
    }

    private fun onGameTap(g: Game) {
        if (hasSave(g)) {
            AlertDialog.Builder(this)
                .setTitle(g.name)
                .setMessage("You have a saved game.")
                .setPositiveButton("Continue") { _, _ -> playGame(g, true) }
                .setNegativeButton("Start over") { _, _ -> playGame(g, false) }
                .setNeutralButton("Delete save") { _, _ -> confirmDeleteSave(g) }
                .show()
        } else {
            playGame(g, false)
        }
    }

    private fun showGameOptions(g: Game) {
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        labels.add("Controller profile: ${profileNameFor(g)}")
        actions.add { chooseProfileForGame(g) }
        if (hasSave(g)) {
            labels.add("Delete saved game")
            actions.add { confirmDeleteSave(g) }
        }
        labels.add("Remove from library")
        actions.add { confirmDelete(g) }
        AlertDialog.Builder(this)
            .setTitle(g.name)
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .show()
    }

    private fun chooseProfileForGame(g: Game) {
        val names = profiles.map { it.name }
        AlertDialog.Builder(this)
            .setTitle("Controller profile for ${g.name}")
            .setSingleChoiceItems(names.toTypedArray(), names.indexOf(profileNameFor(g))) { d, which ->
                setProfileFor(g, names[which])
                if (currentGame === g) applyProfile(names[which])
                d.dismiss()
                romStatus = "${g.name}: profile \"${names[which]}\""
                refreshStatus()
                updateBar()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDeleteSave(g: Game) {
        AlertDialog.Builder(this)
            .setTitle("Delete saved game?")
            .setMessage(g.name)
            .setPositiveButton("Delete") { _, _ ->
                stateFile(g).delete()
                romStatus = "Saved game deleted: ${g.name}"
                refreshStatus()
                rebuildLibrary()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDelete(g: Game) {
        val extra = if (hasSave(g)) "\n\nIts saved game will also be deleted." else ""
        AlertDialog.Builder(this)
            .setTitle("Delete from library?")
            .setMessage(g.name + extra)
            .setPositiveButton("Delete") { _, _ ->
                if (currentGame === g) {
                    nativeLoadRom(ByteArray(0))
                    romLoaded = false
                    currentName = ""
                    currentGame = null
                }
                gameProfiles.remove(stateKey(g))
                saveProfiles()
                games.remove(g)
                stateFile(g).delete()
                saveLibrary()
                romStatus = "Deleted: ${g.name}"
                refreshStatus()
                rebuildLibrary()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmClearRecent() {
        AlertDialog.Builder(this)
            .setTitle("Clear recently played?")
            .setMessage("Your games and saved games stay in the library. Only this list is cleared.")
            .setPositiveButton("Clear") { _, _ ->
                for (g in games) g.lastPlayed = 0L
                saveLibrary()
                rebuildLibrary()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showLibrary() {
        stopEmu()
        if (editing) {
            editing = false
            pad.editing = false
            saveLayout()
        }
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

    private fun playGame(g: Game, resume: Boolean) {
        stopEmu()
        inGame = false
        try {
            val bytes = contentResolver.openInputStream(Uri.parse(g.uri))?.use { it.readBytes() }
            if (bytes == null) {
                romStatus = "Could not open ${g.name}"
            } else {
                val res = nativeLoadRom(bytes)
                if (res.startsWith("ROM:")) {
                    var note = ""
                    if (resume) {
                        var ok = false
                        val f = stateFile(g)
                        if (f.exists()) {
                            try {
                                ok = nativeLoadState(f.readBytes())
                            } catch (e: Exception) {
                                ok = false
                            }
                        }
                        if (!ok) {
                            nativeLoadRom(bytes)   // start clean again
                            note = "  (could not restore the save - started a new game)"
                        }
                    }
                    romLoaded = true
                    currentName = g.name
                    currentGame = g
                    g.lastPlayed = System.currentTimeMillis()
                    saveLibrary()
                    applyProfile(profileNameFor(g))
                    clearInput()
                    showGame()
                    if (note.isNotEmpty()) {
                        romStatus += note
                        refreshStatus()
                    }
                    return
                } else {
                    romLoaded = false
                    currentGame = null
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
        if (!romLoaded || !inGame || editing || openDialogs > 0 || running) return
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
        if (!inGame || editing || event.keyCode == KeyEvent.KEYCODE_BACK) {
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
        if (inGame && !editing &&
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
    external fun nativeSaveState(): ByteArray?
    external fun nativeLoadState(data: ByteArray): Boolean

    companion object {
        const val DEFAULT_PROFILE = "Default"

        init {
            System.loadLibrary("nesemu")
        }
    }
}
