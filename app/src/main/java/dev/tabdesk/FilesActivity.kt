package dev.tabdesk

import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.storage.StorageManager
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.DragEvent
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.window.OnBackInvokedDispatcher
import dev.tabdesk.icons.Icons
import java.io.File
import kotlin.math.max

/**
 * TabDesk Files: a file manager in a window of its own, with Folder View's look and file actions, plus places, a
 * path bar, search, a list view, selecting several files, and copy / cut / paste.
 *
 * It has its own icon in Start, its own task (so it can float beside other apps) and its own process (a problem
 * here can't take the home screen down). It reads TabDesk's settings for its colours and font but never writes
 * them: putting a folder on the desktop is asked of the home screen with a broadcast ([ACTION_ADD_FOLDER_VIEW]).
 */
class FilesActivity : TabActivity() {
    companion object {
        const val EXTRA_PATH = "dev.tabdesk.files.path"
        /** To the home screen: show the folder [EXTRA_PATH] as a Folder View, on the desktop or ([EXTRA_DOCK]) in the dock. */
        const val ACTION_ADD_FOLDER_VIEW = "dev.tabdesk.ADD_FOLDER_VIEW"
        const val EXTRA_DOCK = "dev.tabdesk.files.dock"
        /** Held only by TabDesk itself (signature): who may send [ACTION_ADD_FOLDER_VIEW]. */
        const val ADD_PERMISSION = "dev.tabdesk.permission.FOLDER_VIEWS"

        private const val SORT_NAME = 0
        private const val SORT_NEWEST = 1
        private const val SORT_LARGEST = 2

        /** Opens TabDesk Files, at [dir] if given. */
        fun intent(ctx: Context, dir: File? = null): Intent = Intent(ctx, FilesActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).apply { if (dir != null) putExtra(EXTRA_PATH, dir.path) }

        /** What was copied or cut, kept while TabDesk Files runs, for Paste in any folder. */
        private var clip: List<File> = emptyList()
        private var clipMove = false
    }

    /** Files dragged within TabDesk Files (the drag's local state). */
    private class FilesDrag(val files: List<File>)

    private lateinit var sp: SharedPreferences
    private val main = Handler(Looper.getMainLooper())
    private val storageRoot: File = Environment.getExternalStorageDirectory()

    private var dir: File = storageRoot
    private val history = ArrayList<File>()
    /** Everything in the folder, and what shows (after the search). */
    private var all: List<FileEntry> = emptyList()
    private var entries: List<FileEntry> = emptyList()
    private var loaded = false
    private var query = ""
    private var searching = false
    private val selected = LinkedHashSet<File>()
    private var anchorIndex = -1
    private var observer: FileObserver? = null
    private val refresh = Runnable { load() }

    private var listMode: Boolean
        get() = sp.getBoolean("list", false)
        set(v) = sp.edit().putBoolean("list", v).apply()
    private var sort: Int
        get() = sp.getInt("sort", SORT_NAME)
        set(v) = sp.edit().putInt("sort", v).apply()
    private var showHidden: Boolean
        get() = sp.getBoolean("hidden", false)
        set(v) = sp.edit().putBoolean("hidden", v).apply()

    private lateinit var rootView: FrameLayout
    private lateinit var content: LinearLayout
    private lateinit var bar: LinearLayout
    private lateinit var sidebar: ScrollView
    private lateinit var sideList: LinearLayout
    private lateinit var sideLine: View
    private lateinit var mainArea: FrameLayout
    private lateinit var grid: GridView
    private lateinit var message: LinearLayout
    private lateinit var messageText: TextView
    private var messageButton: View? = null
    private lateinit var pasteBar: LinearLayout
    private lateinit var pasteText: TextView
    private var crumbsScroll: HorizontalScrollView? = null
    private val places = ArrayList<Pair<File, View>>()
    private var narrow = false
    private var themeKey = ""

    // ------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        Fonts.load(this)
        sp = getSharedPreferences("files", Context.MODE_PRIVATE)
        window.setDecorFitsSystemWindows(false)
        pal = Palette.resolve(this)
        themeKey = themeKeyNow()
        buildUi()
        val saved = savedInstanceState?.getString(EXTRA_PATH)?.let { File(it) }
        savedInstanceState?.getStringArrayList("history")?.let { h -> history += h.map { File(it) } }
        dir = (saved ?: intent?.getStringExtra(EXTRA_PATH)?.let { File(it) })?.let { norm(it) }?.takeIf { it.isDirectory } ?: storageRoot
        if (Build.VERSION.SDK_INT >= 33)
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { handleBack() }
        showFolder()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_PATH)?.let { norm(File(it)) }?.takeIf { it.isDirectory }?.let { go(it) }
    }

    override fun onResume() {
        super.onResume()
        // The home screen may have changed TabDesk's settings (colours, Folder Views) since this process read them.
        @Suppress("DEPRECATION") applicationContext.getSharedPreferences("tabdesk", Context.MODE_MULTI_PROCESS)
        if (themeKeyNow() != themeKey) { recreate(); return }
        buildSidebar()
        load()
    }

    override fun onDestroy() {
        super.onDestroy()
        observer?.stopWatching(); observer = null
        main.removeCallbacks(refresh)
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putString(EXTRA_PATH, dir.path)
        out.putStringArrayList("history", ArrayList(history.takeLast(30).map { it.path }))
    }

    override fun onConfigurationChanged(c: Configuration) {
        super.onConfigurationChanged(c)
        applyWidth()
    }

    @Deprecated("Android 12 only; later versions use the OnBackInvokedCallback")
    override fun onBackPressed() = handleBack()

    private fun themeKeyNow() = "${Prefs.themeMode}/${Prefs.accent}/${Prefs.font}/" +
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)

    private fun handleBack() {
        when {
            overlays.dismissTop() -> {}
            selected.isNotEmpty() -> clearSelection()
            searching -> closeSearch()
            history.isNotEmpty() -> goBack()
            else -> finish()
        }
    }

    // ------------------------------------------------------------ the window

    private fun buildUi() {
        overlays = Overlays(this)
        rootView = FrameLayout(this).apply { setBackgroundColor(pal.surfaceSolid) }
        content = linear(this, true)
        rootView.addView(content, flp(MATCH, MATCH))

        bar = linear(this, false) { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(6), 0, dp(6), 0) }
        content.addView(bar, llp(MATCH, dp(56)))
        content.addView(Hairline(this).apply { setBackgroundColor(pal.divider) }, llp(MATCH, 1))

        val body = linear(this, false)
        content.addView(body, llp(MATCH, 0, 1f))

        sideList = linear(this, true) { setPadding(dp(8), dp(8), dp(8), dp(12)) }
        sidebar = ScrollView(this).apply {
            setBackgroundColor(mix(pal.surfaceSolid, pal.field, 0.45f))
            isVerticalScrollBarEnabled = false
            addView(sideList)
        }
        body.addView(sidebar, llp(dp(216), MATCH))
        sideLine = Hairline(this).apply { setBackgroundColor(pal.divider) }
        body.addView(sideLine, llp(1, MATCH))

        mainArea = FrameLayout(this)
        body.addView(mainArea, llp(0, MATCH, 1f))

        grid = GridView(this).apply {
            selector = ColorDrawable(0)
            isVerticalScrollBarEnabled = true
            clipToPadding = false
            setPadding(dp(8), dp(8), dp(8), dp(88))
            verticalSpacing = dp(2)
        }
        mainArea.addView(grid, flp(MATCH, MATCH))
        // Holding empty space gives this folder's menu; tapping it ends selecting.
        val empty = PressHelper(grid, onTap = { clearSelection() }, onLong = { _, x, y -> folderMenu(pointRect(grid, x, y)) })
        var emptyDown = false
        grid.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) emptyDown = grid.pointToPosition(e.x.toInt(), e.y.toInt()) == AdapterView.INVALID_POSITION
            if (emptyDown) empty.handle(e)
            false
        }
        mainArea.setOnDragListener { _, e -> onDrag(e) }

        messageText = TextView(this).label(pal.textDim, 15f).apply {
            gravity = Gravity.CENTER; setLineSpacing(0f, 1.15f); setPadding(dp(24), 0, dp(24), dp(12))
        }
        message = linear(this, true) { gravity = Gravity.CENTER; addView(messageText, llp(MATCH, WRAP)); visibility = View.GONE }
        mainArea.addView(message, flp(MATCH, MATCH))

        pasteText = TextView(this).label(pal.text, 14f).apply { isSingleLine = true; ellipsize = TextUtils.TruncateAt.END }
        pasteBar = linear(this, false) {
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(pal.surfaceSolid, dpf(26), 1, pal.divider)
            elevation = dpf(10)
            setPadding(dp(16), 0, dp(6), 0)
            addView(TextView(this@FilesActivity).symbolText("content_paste", 20f, pal.accent), llp(dp(28), MATCH))
            addView(pasteText, llp(0, WRAP, 1f).apply { marginStart = dp(8); marginEnd = dp(8) })
            addView(overlays.button("Paste here", true) { paste() }, llp(WRAP, dp(40)))
            addView(symbolButton("close", "Forget what was copied") { clip = emptyList(); bindPaste() }, llp(dp(44), dp(44)))
            visibility = View.GONE
        }
        mainArea.addView(pasteBar, flp(WRAP, dp(52), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(16) })

        rootView.addView(overlays, flp(MATCH, MATCH))
        rootView.setOnApplyWindowInsetsListener { _, ins ->
            val b = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime = ins.getInsets(WindowInsets.Type.ime()).bottom
            content.setPadding(b.left, b.top, b.right, max(b.bottom, ime))
            overlays.setInsets(b, ime)
            WindowInsets.CONSUMED
        }
        setContentView(rootView)
        window.insetsController?.setSystemBarsAppearance(
            if (pal.dark) 0 else WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
        applyWidth()
        applyMode()
    }

    /** A narrow window (portrait, or a small floating one) shows places from a button instead of beside the files. */
    private fun applyWidth() {
        narrow = resources.configuration.screenWidthDp < 600
        sidebar.visibility = if (narrow) View.GONE else View.VISIBLE
        sideLine.visibility = sidebar.visibility
        bindBar()
    }

    private fun applyMode() {
        if (listMode) {
            grid.numColumns = 1
            grid.stretchMode = GridView.STRETCH_COLUMN_WIDTH
        } else {
            grid.numColumns = GridView.AUTO_FIT
            grid.columnWidth = dp(108)
            grid.stretchMode = GridView.STRETCH_COLUMN_WIDTH
        }
        grid.adapter = Adapter()
    }

    // ------------------------------------------------------------ top bar

    private fun bindBar() {
        if (!::bar.isInitialized) return
        bar.removeAllViews()
        crumbsScroll = null
        when {
            selected.isNotEmpty() -> {
                bar.addView(symbolButton("close", "Stop selecting") { clearSelection() }, llp(dp(44), dp(44)))
                bar.addView(TextView(this).label(pal.text, 16f, bold = true).apply {
                    text = if (selected.size == 1) "1 selected" else "${selected.size} selected"
                    setPadding(dp(8), 0, 0, 0)
                }, llp(0, WRAP, 1f))
                bar.addView(symbolButton("select_all", "Select all") { selectAll() }, llp(dp(44), dp(44)))
                bar.addView(symbolButton("content_copy", "Copy") { copySelection(move = false) }, llp(dp(44), dp(44)))
                bar.addView(symbolButton("content_cut", "Cut (to move)") { copySelection(move = true) }, llp(dp(44), dp(44)))
                bar.addView(symbolButton("share", "Share") { shareFiles(selected.toList()) }, llp(dp(44), dp(44)))
                bar.addView(symbolButton("delete", "Delete") { deleteSelection() }, llp(dp(44), dp(44)))
                if (selected.size == 1) bar.addView(symbolButton("more_vert", "More") { v ->
                    entries.firstOrNull { it.file == selected.first() }?.let { itemMenu(it, v.rectIn(rootView)) }
                }, llp(dp(44), dp(44)))
            }
            searching -> {
                bar.addView(symbolButton("arrow_back", "Close search") { closeSearch() }, llp(dp(44), dp(44)))
                val et = EditText(this).apply {
                    setText(query); setSelection(query.length)
                    hint = "Search in ${placeName(dir)}"
                    textSize = 16f; setTextColor(pal.text); setHintTextColor(pal.textDim)
                    isSingleLine = true; imeOptions = EditorInfo.IME_ACTION_SEARCH
                    background = rounded(pal.field, dpf(20)); setPadding(dp(16), 0, dp(16), 0)
                    addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                        override fun afterTextChanged(s: Editable?) { query = s?.toString().orEmpty(); filter() }
                    })
                    setOnEditorActionListener { _, _, _ -> hideIme(); true }
                }
                bar.addView(et, llp(0, dp(42), 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
                bar.addView(symbolButton("close", "Clear the search") { if (et.text.isEmpty()) closeSearch() else et.setText("") }, llp(dp(44), dp(44)))
                et.requestFocus()
                et.post { et.windowInsetsController?.show(WindowInsets.Type.ime()) }
            }
            else -> {
                if (narrow) bar.addView(symbolButton("menu", "Places") { v -> placesMenu(v.rectIn(rootView)) }, llp(dp(44), dp(44)))
                bar.addView(symbolButton("arrow_back", "Back") { goBack() }.apply { dimIf(history.isEmpty()) }, llp(dp(44), dp(44)))
                bar.addView(symbolButton("arrow_upward", "Up a folder") { up() }.apply { dimIf(parentOf(dir) == null) }, llp(dp(44), dp(44)))
                bar.addView(crumbs(), llp(0, MATCH, 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
                bar.addView(symbolButton("search", "Search in this folder") { openSearch() }, llp(dp(44), dp(44)))
                bar.addView(symbolButton(if (listMode) "grid_view" else "view_list", if (listMode) "Show as tiles" else "Show as a list") {
                    listMode = !listMode; applyMode(); bindBar()
                }, llp(dp(44), dp(44)))
                bar.addView(symbolButton("more_vert", "More") { v -> folderMenu(v.rectIn(rootView)) }, llp(dp(44), dp(44)))
            }
        }
    }

    private fun View.dimIf(off: Boolean) { alpha = if (off) 0.35f else 1f; isEnabled = !off }

    /** The path as buttons: Internal storage › Download › … ; tap one to go there. */
    private fun crumbs(): View {
        val row = linear(this, false) { gravity = Gravity.CENTER_VERTICAL }
        val base = volumeOf(dir)
        val parts = ArrayList<File>()
        var f: File? = dir
        while (f != null) { parts.add(0, f); if (f == base) break; f = f.parentFile }
        parts.forEachIndexed { i, p ->
            if (i > 0) row.addView(TextView(this).symbolText("chevron_right", 18f, pal.textDim), llp(dp(18), MATCH))
            val last = i == parts.size - 1
            row.addView(RoomyText(this).label(if (last) pal.text else pal.textDim, 15f, bold = last).apply {
                text = placeName(p); isSingleLine = true; gravity = Gravity.CENTER
                setPadding(dp(8), 0, dp(8), 0)
                background = rippleBg(pal.ripple, dpf(14))
                if (!last) setOnClickListener { go(p) }
            }, llp(WRAP, dp(36)))
        }
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row, FrameLayout.LayoutParams(WRAP, MATCH))
        }
        // Show the end of the path (the folder you're in) once it has its real width, and again when the window resizes.
        val toEnd = View.OnLayoutChangeListener { _, l, _, r, _, ol, _, or, _ -> if (r - l != or - ol) scroll.scrollTo(row.width, 0) }
        row.addOnLayoutChangeListener(toEnd)
        scroll.addOnLayoutChangeListener(toEnd)
        crumbsScroll = scroll
        return scroll
    }

    private fun openSearch() { searching = true; query = ""; clearSelection(rebind = false); bindBar() }

    private fun closeSearch() {
        searching = false; query = ""
        hideIme()
        bindBar(); filter()
    }

    // ------------------------------------------------------------ places

    /** The same folder by its usual name: /sdcard and /storage/self/primary are other names for internal storage. */
    private fun norm(f: File): File {
        val p = f.absolutePath.trimEnd('/')
        for (alias in listOf("/sdcard", "/storage/self/primary", "/mnt/sdcard"))
            if (p == alias || p.startsWith("$alias/")) return File(storageRoot.path + p.removePrefix(alias))
        return File(p.ifEmpty { "/" })
    }

    /** The storage a folder is on: internal storage, or an SD card or USB stick. */
    private fun volumes(): List<Pair<File, String>> {
        val out = arrayListOf(storageRoot to "Internal storage")
        try {
            val sm = getSystemService(StorageManager::class.java)
            for (v in sm.storageVolumes) {
                val d = v.directory ?: continue
                if (v.isPrimary || v.state != Environment.MEDIA_MOUNTED || !d.isDirectory) continue
                out += d to (v.getDescription(this) ?: d.name)
            }
        } catch (_: Exception) {}
        return out
    }

    private fun volumeOf(f: File): File = volumes().map { it.first }.filter { f.path == it.path || f.path.startsWith(it.path + "/") }
        .maxByOrNull { it.path.length } ?: storageRoot

    /** Up from [f], unless it's the top of its storage. */
    private fun parentOf(f: File): File? = if (f == volumeOf(f)) null else f.parentFile

    private fun placeName(f: File): String = volumes().firstOrNull { it.first == f }?.second ?: f.name.ifEmpty { f.path }

    private fun placeList(): List<Triple<String, String, File>> {
        val out = ArrayList<Triple<String, String, File>>()
        val vols = volumes()
        out += Triple(vols[0].second, "tablet_android", vols[0].first)
        for ((label, sym, type) in listOf(
            Triple("Download", "download", Environment.DIRECTORY_DOWNLOADS),
            Triple("Documents", "description", Environment.DIRECTORY_DOCUMENTS),
            Triple("Pictures", "image", Environment.DIRECTORY_PICTURES),
            Triple("Camera", "photo_camera", Environment.DIRECTORY_DCIM),
            Triple("Music", "music_note", Environment.DIRECTORY_MUSIC),
            Triple("Movies", "movie", Environment.DIRECTORY_MOVIES),
        )) {
            val f = Environment.getExternalStoragePublicDirectory(type)
            if (f.isDirectory) out += Triple(label, sym, f)
        }
        for ((d, name) in vols.drop(1)) out += Triple(name, "storage", d)
        return out
    }

    /** The folders the desktop's and the dock's Folder Views show. */
    private fun folderViewPlaces(): List<Pair<String, File>> = try {
        (Prefs.loadDesktop() + Prefs.loadDock()).filter { it.type == DesktopItem.FILES }
            .map { FolderViews.labelOf(it) to norm(File(it.key)) }.distinctBy { it.second.path }.filter { it.second.isDirectory }
    } catch (_: Exception) { emptyList() }

    private fun buildSidebar() {
        sideList.removeAllViews(); places.clear()
        sideList.addView(heading("Places"))
        for ((label, sym, f) in placeList()) sideList.addView(placeRow(label, sym, f), llp(MATCH, dp(44)))
        val fv = folderViewPlaces()
        if (fv.isNotEmpty()) {
            sideList.addView(heading("Folder Views"))
            for ((label, f) in fv) sideList.addView(placeRow(label, "folder_open", f), llp(MATCH, dp(44)))
        }
        try {
            val st = StatFs(storageRoot.path)
            sideList.addView(TextView(this).label(pal.textDim, 12.5f).apply {
                text = "${Formatter.formatShortFileSize(this@FilesActivity, st.availableBytes)} free of " +
                    Formatter.formatShortFileSize(this@FilesActivity, st.totalBytes)
                setPadding(dp(12), dp(18), dp(12), 0)
            })
        } catch (_: Exception) {}
        markPlace()
    }

    private fun heading(text: String) = TextView(this).label(pal.textDim, 12.5f, bold = true).apply {
        this.text = text; setPadding(dp(12), dp(12), dp(12), dp(6))
    }

    private fun placeRow(label: String, sym: String, f: File): View {
        val row = linear(this, false) {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(10), 0)
            addView(TextView(this@FilesActivity).symbolText(sym, 20f, pal.accent, fill = true), llp(dp(26), MATCH))
            addView(TextView(this@FilesActivity).label(pal.text, 14.5f).apply {
                text = label; isSingleLine = true; ellipsize = TextUtils.TruncateAt.END; setPadding(dp(12), 0, 0, 0)
            }, llp(0, WRAP, 1f))
            setOnClickListener { go(f) }
            tooltipText = f.path
        }
        places += f to row
        return row
    }

    /** Highlights the place the folder shown is in (the closest one). */
    private fun markPlace() {
        val best = places.map { it.first }.filter { dir.path == it.path || dir.path.startsWith(it.path + "/") }.maxByOrNull { it.path.length }
        for ((f, v) in places) v.background = rippleBg(pal.ripple, dpf(12), if (f == best) pal.accentSoft else 0)
    }

    private fun placesMenu(at: Rect) {
        val list = ArrayList<MenuEntry>()
        for ((label, sym, f) in placeList()) list += MenuEntry(label, icon = SymbolDrawable(this, sym, pal.text)) { go(f) }
        val fv = folderViewPlaces()
        if (fv.isNotEmpty()) {
            list += MenuEntry.DIVIDER
            for ((label, f) in fv) list += MenuEntry(label, icon = SymbolDrawable(this, "folder_open", pal.text)) { go(f) }
        }
        overlays.menu(at, list, "Places")
    }

    // ------------------------------------------------------------ moving around

    private fun go(d: File) {
        if (d == dir) return
        history += dir
        if (history.size > 50) history.removeAt(0)
        dir = d
        showFolder()
    }

    private fun goBack() {
        val d = history.removeLastOrNull() ?: return
        dir = d
        showFolder()
    }

    private fun up() { parentOf(dir)?.let { go(it) } }

    private fun showFolder() {
        clearSelection(rebind = false)
        if (searching) { searching = false; query = ""; hideIme() }
        all = emptyList(); entries = emptyList(); loaded = false
        (grid.adapter as? BaseAdapter)?.notifyDataSetChanged()
        bindBar(); markPlace(); bindPaste()
        load()
        grid.setSelection(0)
    }

    private fun load() {
        if (!FolderViews.canRead) {
            watch(null)
            showMessage("TabDesk Files needs Android's “All files access” to show your files.", "Turn it on") { explainAccess() }
            return
        }
        val d = dir
        watch(d)
        val hidden = showHidden; val order = sort
        FolderViews.io.execute {
            val list = listDir(d, hidden, order)
            main.post {
                if (d != dir || isDestroyed) return@post
                if (list == null) {
                    if (!d.exists()) parentOf(d)?.let { dir = it; showFolder(); return@post }
                    all = emptyList(); loaded = true; filter()
                    showMessage("Can't open this folder.", null, null)
                    return@post
                }
                all = list; loaded = true
                selected.retainAll(list.map { it.file }.toSet())
                filter()
                if (selected.isEmpty() && !searching) bindBar()
            }
        }
    }

    private fun listDir(d: File, hidden: Boolean, order: Int): List<FileEntry>? {
        val files = d.listFiles() ?: return null
        val byName = Comparator<FileEntry> { a, b -> String.CASE_INSENSITIVE_ORDER.compare(a.name, b.name) }
        val by = when (order) {
            SORT_NEWEST -> compareByDescending<FileEntry> { it.modified }.then(byName)
            SORT_LARGEST -> compareByDescending<FileEntry> { it.size }.then(byName)
            else -> byName
        }
        return files.asSequence().filter { hidden || !it.name.startsWith(".") }
            .map { FileEntry(it, it.isDirectory, it.lastModified()) }
            .sortedWith(compareBy<FileEntry> { !it.dir }.then(by)).toList()
    }

    /** Shows the folder's files that match the search. */
    private fun filter() {
        val q = query.trim()
        entries = if (q.isEmpty()) all else all.filter { it.name.contains(q, ignoreCase = true) }
        (grid.adapter as? BaseAdapter)?.notifyDataSetChanged()
        when {
            !loaded -> showMessage(null, null, null)
            entries.isNotEmpty() -> showMessage(null, null, null)
            q.isNotEmpty() -> showMessage("Nothing in this folder matches “$q”.", null, null)
            else -> showMessage("This folder is empty.\nDrop files here, or paste, to put them in it.", null, null)
        }
    }

    private fun showMessage(text: String?, button: String?, action: (() -> Unit)?) {
        message.visibility = if (text != null) View.VISIBLE else View.GONE
        grid.visibility = if (text != null && entries.isEmpty()) View.INVISIBLE else View.VISIBLE
        messageText.text = text
        messageButton?.let { message.removeView(it) }; messageButton = null
        if (button != null && action != null)
            messageButton = overlays.button(button, true, action).also { message.addView(it, llp(WRAP, dp(40))) }
    }

    /** Re-reads the folder when something in it changes (a download finishing, another app's files). */
    private fun watch(d: File?) {
        observer?.stopWatching(); observer = null
        if (d == null) return
        observer = object : FileObserver(d, CREATE or DELETE or MOVED_FROM or MOVED_TO or CLOSE_WRITE or DELETE_SELF or MOVE_SELF) {
            override fun onEvent(event: Int, path: String?) {
                if (event and FolderViews.WATCH_REMOVED != 0 || observer !== this) return
                if (path?.startsWith(".") == true && !showHidden && event and (DELETE_SELF or MOVE_SELF) == 0) return
                main.removeCallbacks(refresh); main.postDelayed(refresh, 350)
            }
        }.also { it.startWatching() }
    }

    /** Something changed here (rename, delete, paste): show it now rather than waiting for the watcher. */
    private fun changed() { main.removeCallbacks(refresh); main.postDelayed(refresh, 120) }

    private fun explainAccess() {
        val card = overlays.dialogCard(dp(460), "Show your files")
        card.addView(TextView(this).label(pal.text, 14.5f).apply {
            text = "To show your files, TabDesk Files needs Android's “All files access”. Turn it on for TabDesk on " +
                "the next page, then come back.\n\nTabDesk only changes files when you ask it to: rename, delete, " +
                "paste, new folder, or dropping files in."
            setLineSpacing(0f, 1.15f)
        }, llp(MATCH, WRAP).apply { bottomMargin = dp(16) })
        val buttons = linear(this, false) { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        buttons.addView(overlays.button("Not now", false) { overlays.dismissTop() }, llp(WRAP, dp(40)))
        buttons.addView(overlays.button("Open settings", true) { overlays.dismissTop(); FolderViews.openAccessSettings(this) },
            llp(WRAP, dp(40)).apply { marginStart = dp(8) })
        card.addView(buttons, llp(MATCH, WRAP))
        overlays.show(card, dim = true)
    }

    // ------------------------------------------------------------ files

    private inner class Cell(val list: Boolean) : LinearLayout(this@FilesActivity) {
        val img = ImageView(context)
        val check = TextView(context).symbolText("check_circle", 20f, pal.accent, fill = true)
        val name = TextView(context).label(pal.text, if (list) 14.5f else 13f)
        val info = TextView(context).label(pal.textDim, 12.5f)
        var entry: FileEntry? = null
        var index = -1
        private var meta = 0
        private val iconPx = dp(if (list) 36 else 56)

        init {
            img.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, dpf(8)) }
            }
            img.clipToOutline = true
            val icon = FrameLayout(context).apply {
                addView(img, flp(iconPx, iconPx, Gravity.CENTER))
                addView(check, flp(dp(22), dp(22), Gravity.TOP or Gravity.START))
            }
            if (list) {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(4), dp(12), dp(4))
                minimumHeight = dp(54)
                addView(icon, llp(iconPx + dp(6), iconPx + dp(4)))
                val texts = linear(context, true) { gravity = Gravity.CENTER_VERTICAL }
                name.apply { isSingleLine = true; ellipsize = TextUtils.TruncateAt.MIDDLE }
                texts.addView(name, llp(MATCH, WRAP))
                info.apply { isSingleLine = true; ellipsize = TextUtils.TruncateAt.END }
                texts.addView(info, llp(MATCH, WRAP))
                addView(texts, llp(0, WRAP, 1f).apply { marginStart = dp(12) })
            } else {
                orientation = VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(4), dp(8), dp(4), dp(8))
                addView(icon, llp(iconPx + dp(8), iconPx + dp(4)))
                name.apply {
                    maxLines = 2; ellipsize = TextUtils.TruncateAt.MIDDLE; gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(0, dp(5), 0, 0)
                }
                addView(name, llp(MATCH, WRAP))
            }
            val press = PressHelper(this,
                onTap = { entry?.let { tap(it, index, meta) } },
                onMiddle = { entry?.let { if (it.dir) go(it.file) else openFile(it.file, window = true) } },
                // A list row is wide: its menu opens where it was held. A tile's opens by the tile.
                onLong = { v, x, y -> entry?.let { itemMenu(it, if (list) pointRect(v, x, y) else v.rectIn(rootView)) } },
                onDrag = { v, _, _ -> entry?.let { dragOut(it, v) } })
            setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_DOWN) meta = e.metaState
                press.handle(e)
            }
        }

        fun bind(e: FileEntry, i: Int) {
            entry = e; index = i
            name.text = e.name
            contentDescription = e.name
            val sel = e.file in selected
            background = rippleBg(pal.ripple, dpf(12), if (sel) pal.accentSoft else 0)
            check.visibility = if (sel) View.VISIBLE else View.GONE
            if (list) info.text = details(e)
            val thumb = FolderViews.cachedThumb(e)
            if (thumb != null) { img.scaleType = ImageView.ScaleType.CENTER_CROP; img.setImageBitmap(thumb); return }
            img.scaleType = ImageView.ScaleType.FIT_CENTER
            img.setImageBitmap(FolderViews.typeIcon(this@FilesActivity, e, iconPx))
            if (FolderViews.wantsThumb(e)) FolderViews.loadThumb(this@FilesActivity, e, dp(112)) { b ->
                if (entry === e) {
                    img.scaleType = if (e.ext == "apk") ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
                    img.setImageBitmap(b)
                }
            }
        }

        val bitmap: Bitmap? get() = (img.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
    }

    private fun details(e: FileEntry): String {
        val what = if (e.dir) (if (e.count == 1) "1 item" else "${e.count} items") else Formatter.formatShortFileSize(this, e.size)
        val now = System.currentTimeMillis()
        val flags = if (DateUtils.isToday(e.modified)) DateUtils.FORMAT_SHOW_TIME
            else DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or
                (if (now - e.modified > 300L * DateUtils.DAY_IN_MILLIS) DateUtils.FORMAT_SHOW_YEAR else DateUtils.FORMAT_NO_YEAR)
        return "$what  ·  ${DateUtils.formatDateTime(this, e.modified, flags)}"
    }

    private inner class Adapter : BaseAdapter() {
        private val list = listMode
        override fun getCount() = entries.size
        override fun getItem(i: Int) = entries[i]
        override fun getItemId(i: Int) = i.toLong()
        override fun getView(i: Int, convert: View?, parent: ViewGroup?): View {
            val c = (convert as? Cell)?.takeIf { it.list == list } ?: Cell(list)
            c.bind(entries[i], i)
            return c
        }
    }

    /** A tap: open it, or while selecting (or with Ctrl / Shift and a mouse), select it. */
    private fun tap(e: FileEntry, i: Int, meta: Int) {
        val ctrl = meta and KeyEvent.META_CTRL_ON != 0
        val shift = meta and KeyEvent.META_SHIFT_ON != 0
        when {
            shift && anchorIndex in entries.indices -> {
                val (a, b) = if (anchorIndex <= i) anchorIndex to i else i to anchorIndex
                for (k in a..b) selected += entries[k].file
                selectionChanged()
            }
            ctrl || selected.isNotEmpty() -> toggle(e, i)
            e.dir -> go(e.file)
            else -> openFile(e.file, grid.getChildAt(i - grid.firstVisiblePosition))
        }
    }

    private fun toggle(e: FileEntry, i: Int = entries.indexOf(e)) {
        if (!selected.remove(e.file)) selected += e.file
        anchorIndex = i
        selectionChanged()
    }

    private fun selectAll() { selected += entries.map { it.file }; selectionChanged() }

    private fun clearSelection(rebind: Boolean = true) {
        if (selected.isEmpty()) return
        selected.clear(); anchorIndex = -1
        if (rebind) selectionChanged()
    }

    private fun selectionChanged() {
        (grid.adapter as? BaseAdapter)?.notifyDataSetChanged()
        bindBar()
    }

    // ------------------------------------------------------------ menus and actions

    private fun pointRect(v: View, x: Float, y: Float): Rect = v.rectIn(rootView).let { r ->
        Rect(r.left + x.toInt(), r.top + y.toInt(), r.left + x.toInt(), r.top + y.toInt())
    }

    private fun sym(name: String) = SymbolDrawable(this, name, pal.text)

    /** A file's or folder's menu (held, right-clicked, or ⋮ with one selected). Several selected: theirs. */
    private fun itemMenu(e: FileEntry, at: Rect) {
        if (e.file in selected && selected.size > 1) { selectionMenu(at); return }
        val f = e.file
        val list = ArrayList<MenuEntry>()
        if (e.dir) {
            list += MenuEntry("Open", icon = sym("folder_open")) { go(f) }
        } else {
            list += MenuEntry("Open", icon = sym("open_in_new")) { openFile(f) }
            if (canFloat) list += MenuEntry("Open in a window", G.RESTORE) { openFile(f, window = true) }
            list += MenuEntry("Open with…", G.GRID) { openFile(f, choose = true) }
            list += MenuEntry("Share…", icon = sym("share")) { shareFile(f) }
        }
        list += MenuEntry.DIVIDER
        list += MenuEntry(if (f in selected) "Unselect" else "Select", G.CHECK) { toggle(e) }
        list += MenuEntry("Copy", icon = sym("content_copy")) { setClip(listOf(f), move = false) }
        list += MenuEntry("Cut (to move it)", icon = sym("content_cut")) { setClip(listOf(f), move = true) }
        list += MenuEntry("Rename", G.EDIT) { rename(f) }
        list += MenuEntry("Delete", G.TRASH) { confirmDelete(listOf(f)) { selected.remove(f); changed(); bindBar() } }
        if (e.dir) {
            list += MenuEntry.DIVIDER
            list += MenuEntry("Put on the desktop", G.DESKTOP) { addFolderView(f, dock = false) }
            list += MenuEntry("Put in the widget dock", G.WIDGETS) { addFolderView(f, dock = true) }
        }
        val title = if (e.dir) e.name else "${e.name}  ·  ${Formatter.formatShortFileSize(this, e.size)}"
        overlays.menu(at, list, title)
    }

    private fun selectionMenu(at: Rect) {
        val n = selected.size
        overlays.menu(at, listOf(
            MenuEntry("Copy", icon = sym("content_copy")) { copySelection(move = false) },
            MenuEntry("Cut (to move them)", icon = sym("content_cut")) { copySelection(move = true) },
            MenuEntry("Share…", icon = sym("share")) { shareFiles(selected.toList()) },
            MenuEntry("Delete", G.TRASH) { deleteSelection() },
            MenuEntry.DIVIDER,
            MenuEntry("Select all", icon = sym("select_all")) { selectAll() },
            MenuEntry("Stop selecting", G.CLOSE) { clearSelection() },
        ), "$n selected")
    }

    /** The folder shown: its menu (⋮, or holding empty space). */
    private fun folderMenu(at: Rect) {
        val list = ArrayList<MenuEntry>()
        list += MenuEntry("New folder", icon = sym("create_new_folder")) { newFolder() }
        if (clip.isNotEmpty()) list += MenuEntry("Paste here", icon = sym("content_paste")) { paste() }
        if (entries.isNotEmpty()) list += MenuEntry("Select all", icon = sym("select_all")) { selectAll() }
        list += MenuEntry.DIVIDER
        for ((s, label) in listOf(SORT_NAME to "Sort by name", SORT_NEWEST to "Newest first", SORT_LARGEST to "Largest first"))
            list += MenuEntry(label, if (sort == s) G.CHECK else 0) { if (sort != s) { sort = s; load() } }
        list += MenuEntry(if (showHidden) "Hide hidden files" else "Show hidden files", if (showHidden) G.EYE_OFF else G.EYE) {
            showHidden = !showHidden; load()
        }
        list += MenuEntry.DIVIDER
        list += MenuEntry("Put this folder on the desktop", G.DESKTOP) { addFolderView(dir, dock = false) }
        list += MenuEntry("Put it in the widget dock", G.WIDGETS) { addFolderView(dir, dock = true) }
        overlays.menu(at, list, placeName(dir))
    }

    private fun rename(f: File) {
        overlays.prompt(if (f.isDirectory) "Rename folder" else "Rename file", f.name, f.name, null) { typed ->
            val name = FolderViews.cleanName(typed)
            when {
                name.isEmpty() || name == f.name -> {}
                File(f.parentFile, name).exists() -> toast("There's already a “$name” here")
                !f.renameTo(File(f.parentFile, name)) -> toast("Couldn't rename it")
                else -> { selected.remove(f); changed(); bindBar() }
            }
        }
    }

    private fun newFolder() {
        overlays.prompt("New folder", "", "Folder name", null) { typed ->
            val name = FolderViews.cleanName(typed)
            if (name.isEmpty()) return@prompt
            val d = File(dir, name)
            if (d.exists()) toast("There's already a “$name” here") else if (!d.mkdir()) toast("Couldn't make the folder") else changed()
        }
    }

    private fun deleteSelection() {
        val files = selected.toList()
        confirmDelete(files) { clearSelection(); changed() }
    }

    private fun copySelection(move: Boolean) {
        if (selected.isEmpty()) return
        setClip(selected.toList(), move)
        clearSelection()
    }

    private fun setClip(files: List<File>, move: Boolean) {
        clip = files; clipMove = move
        bindPaste()
    }

    private fun bindPaste() {
        if (clip.isEmpty() || clip.none { it.exists() }) { clip = emptyList(); pasteBar.visibility = View.GONE; return }
        val n = clip.size
        val what = if (n == 1) "“${clip[0].name}”" else "$n items"
        pasteText.text = if (clipMove) "$what to move" else "$what copied"
        pasteBar.visibility = View.VISIBLE
    }

    private fun paste() {
        val files = clip.filter { it.exists() }
        if (files.isEmpty()) { bindPaste(); return }
        val move = clipMove
        transfer(files, dir, move) { if (move) { clip = emptyList(); bindPaste() } }
    }

    /** Copies or moves [files] into [target], off the main thread, and says how it went. */
    private fun transfer(files: List<File>, target: File, move: Boolean, done: (() -> Unit)? = null) {
        val todo = files.filter { it != target && !(move && it.parentFile == target) }
        if (todo.isEmpty()) { if (move) toast("Already here"); done?.invoke(); return }
        if (todo.size > 1 || todo.any { it.isDirectory || it.length() > 20L shl 20 })
            toast(if (move) "Moving ${count(todo.size)}…" else "Copying ${count(todo.size)}…")
        FolderViews.io.execute {
            val ok = todo.count { FolderViews.moveOrCopy(it, target, move) }
            main.post {
                if (isDestroyed) return@post
                toast(when {
                    ok == todo.size -> (if (move) "Moved " else "Copied ") + count(ok) + " into ${placeName(target)}"
                    ok == 0 -> if (move) "Couldn't move that here" else "Couldn't copy that here"
                    else -> "Done with $ok of ${todo.size}"
                })
                changed(); done?.invoke()
            }
        }
    }

    private fun count(n: Int) = if (n == 1) "1 item" else "$n items"

    /** Asks the home screen to show [f] as a Folder View (TabDesk's settings belong to its process). */
    private fun addFolderView(f: File, dock: Boolean) {
        sendBroadcast(Intent(ACTION_ADD_FOLDER_VIEW).setPackage(packageName).putExtra(EXTRA_PATH, f.path).putExtra(EXTRA_DOCK, dock),
            ADD_PERMISSION)
        toast(if (dock) "Added to the widget dock" else "Added to the desktop")
    }

    // ------------------------------------------------------------ drag and drop

    /** Drag a file (or everything selected, if it's selected) into another app, another folder here, or a Folder View. */
    private fun dragOut(e: FileEntry, v: View) {
        val files = if (e.file in selected) selected.toList() else listOf(e.file)
        val onlyFiles = files.all { it.isFile }
        val bmp = (v as? Cell)?.bitmap ?: FolderViews.typeIcon(this, e, dp(56))
        val clipData = if (onlyFiles) {
            val mimes = files.map { FilesProvider.mimeOf(it) }.distinct().toTypedArray()
            ClipData(if (files.size == 1) e.name else "${files.size} files", mimes, ClipData.Item(FilesProvider.uriFor(files[0]))).apply {
                files.drop(1).forEach { addItem(ClipData.Item(FilesProvider.uriFor(it))) }
            }
        } else ClipData(e.name, arrayOf(ClipDescription.MIMETYPE_TEXT_PLAIN), ClipData.Item(e.file.path))
        // Folders stay inside TabDesk Files; files can go to other apps too (they get to read just those files).
        val flags = View.DRAG_FLAG_OPAQUE or if (onlyFiles) View.DRAG_FLAG_GLOBAL or View.DRAG_FLAG_GLOBAL_URI_READ else 0
        if (onlyFiles) FolderViews.noteDrag(this, files)
        if (v.startDragAndDrop(clipData, BitmapShadow(bmp, dp(56)), FilesDrag(files), flags)) overlays.dismissAll()
    }

    private var dropHighlight = false

    private fun onDrag(e: DragEvent): Boolean {
        when (e.action) {
            DragEvent.ACTION_DRAG_STARTED -> return e.localState is FilesDrag || (e.localState == null && FolderViews.canRead && dir.isDirectory)
            DragEvent.ACTION_DRAG_ENTERED -> highlightDrop(true)
            DragEvent.ACTION_DRAG_EXITED, DragEvent.ACTION_DRAG_ENDED -> highlightDrop(false)
            DragEvent.ACTION_DROP -> { highlightDrop(false); return drop(e) }
        }
        return true
    }

    private fun highlightDrop(on: Boolean) {
        if (dropHighlight == on) return
        dropHighlight = on
        mainArea.foreground = if (on) rounded(withAlpha(pal.accent, 0x18), dpf(14), dp(2), pal.accent) else null
    }

    /** Where a drop at (x, y) goes: a folder's tile under it, or the folder shown. */
    private fun targetAt(x: Float, y: Float): File {
        val i = grid.pointToPosition((x - grid.left).toInt(), (y - grid.top).toInt())
        return entries.getOrNull(i)?.takeIf { it.dir }?.file ?: dir
    }

    private fun drop(e: DragEvent): Boolean {
        val target = targetAt(e.x, e.y)
        val s = e.localState
        // From here, or from a Folder View: move or copy. From other apps: copy.
        val own = (s as? FilesDrag)?.files ?: FolderViews.ownDrop(this, e.clipData)
        if (own != null) {
            val files = own.filter { it != target && it.parentFile != target }
            if (files.isEmpty()) return false
            val what = if (files.size == 1) files[0].name else "${files.size} items"
            overlays.menu(pointRect(mainArea, e.x, e.y), listOf(
                MenuEntry("Move here", icon = sym("drive_file_move")) { transfer(files, target, move = true) { if (s is FilesDrag) clearSelection() } },
                MenuEntry("Copy here", icon = sym("content_copy")) { transfer(files, target, move = false) },
                MenuEntry("Cancel", G.CLOSE),
            ), "$what → ${placeName(target)}")
            return true
        }
        val c = e.clipData ?: return false
        val uris = (0 until c.itemCount).mapNotNull { c.getItemAt(it).uri }
        if (uris.isEmpty()) { toast("Only files can be dropped here"); return false }
        val perms = requestDragAndDropPermissions(e)
        FolderViews.io.execute {
            val ok = uris.count { FolderViews.copyInto(this, it, target) }
            perms?.release()
            main.post {
                toast(when {
                    ok == uris.size -> "Copied ${count(ok)} into ${placeName(target)}"
                    ok == 0 -> "Couldn't copy that here"
                    else -> "Copied $ok of ${uris.size}"
                })
                changed()
            }
        }
        return true
    }

    // ------------------------------------------------------------ keyboard

    override fun onKeyDown(keyCode: Int, e: KeyEvent): Boolean {
        val typing = currentFocus is EditText
        if (e.isCtrlPressed && !typing) when (keyCode) {
            KeyEvent.KEYCODE_A -> { selectAll(); return true }
            KeyEvent.KEYCODE_C -> { copySelection(move = false); return true }
            KeyEvent.KEYCODE_X -> { copySelection(move = true); return true }
            KeyEvent.KEYCODE_V -> { if (clip.isNotEmpty()) paste(); return true }
        }
        if (e.isCtrlPressed && keyCode == KeyEvent.KEYCODE_F) { openSearch(); return true }
        if (e.isAltPressed) when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> { goBack(); return true }
            KeyEvent.KEYCODE_DPAD_UP -> { up(); return true }
        }
        if (!typing) when (keyCode) {
            KeyEvent.KEYCODE_FORWARD_DEL, KeyEvent.KEYCODE_DEL -> if (selected.isNotEmpty()) { deleteSelection(); return true }
            KeyEvent.KEYCODE_F2 -> if (selected.size == 1) { rename(selected.first()); return true }
            KeyEvent.KEYCODE_ESCAPE -> { handleBack(); return true }
        }
        return super.onKeyDown(keyCode, e)
    }

    // ------------------------------------------------------------ small views

    private fun symbolButton(name: String, desc: String, onClick: (View) -> Unit) = TextView(this).symbolText(name, 22f, pal.text).apply {
        background = rippleBg(pal.ripple, dpf(22)); contentDescription = desc; tooltipText = desc
        setOnClickListener { onClick(it) }
    }

    private fun TextView.symbolText(name: String, size: Float, color: Int, fill: Boolean = false): TextView = apply {
        typeface = Icons.symbolFont(this@FilesActivity, fill = fill); textSize = size; setTextColor(color); gravity = Gravity.CENTER
        text = Icons.symbol(this@FilesActivity, name)?.text ?: "•"
        includeFontPadding = false
    }
}

/** A Material symbol as a drawable (menu icons). */
class SymbolDrawable(ctx: Context, name: String, color: Int) : Drawable() {
    private val text = Icons.symbol(ctx, name)?.text ?: ""
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Icons.symbolFont(ctx, fill = false); this.color = color; textAlign = Paint.Align.CENTER
    }

    override fun draw(c: Canvas) {
        val b = bounds
        paint.textSize = b.height().toFloat()
        val fm = paint.fontMetrics
        c.drawText(text, b.exactCenterX(), b.exactCenterY() - (fm.ascent + fm.descent) / 2f, paint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

