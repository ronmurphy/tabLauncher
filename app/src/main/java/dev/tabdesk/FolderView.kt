package dev.tabdesk

import android.app.ActivityOptions
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Environment
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.TextUtils
import android.util.LruCache
import android.util.Size
import android.view.DragEvent
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.tabdesk.icons.Icons
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.min

// Folder View: a folder's files on the desktop, after Plasma's. At 1×1 it's an icon that opens the folder in a popup
// (tap, or rest the mouse on it); bigger, it's a panel showing the files where it sits. Tap a file to open it, hold
// for its menu, or drag it out into another app's window. Files dropped on it from other apps are copied in.
//
// Showing other apps' files needs Android's "All files access", which the user turns on themselves (TabDesk opens
// the page); nothing is asked until a Folder View is added.

/** A file or folder in a Folder View. */
class FileEntry(val file: File, val dir: Boolean, val modified: Long) {
    val name: String get() = file.name
    val ext get() = if (dir) "" else name.substringAfterLast('.', "").lowercase()
    /** Bytes (0 for a folder), read when first asked. */
    val size: Long by lazy { if (dir) 0L else file.length() }
    /** How many things a folder holds (shown in TabDesk Files' list), read when first asked. */
    val count: Int by lazy { if (dir) file.list()?.size ?: 0 else 0 }
}

/** A file being dragged out of a Folder View (the drag's local state, so another Folder View can take it). */
class FileDrag(val file: File, val from: FolderPanel)

object FolderViews {
    const val NEWEST_FIRST = 1
    /**
     * inotify's "this watch was removed": a folder watcher gets it when it's stopped, which happens on every reload.
     * It's no change to the folder; reloading on it restarted the watcher, so the folder reloaded three times a second.
     */
    const val WATCH_REMOVED = 0x8000

    val canRead get() = Environment.isExternalStorageManager()
    private val storageRoot: File get() = Environment.getExternalStorageDirectory()

    /** Where to add a Folder View once All files access is on (the user went to turn it on). */
    var pendingAdd: IntArray? = null
    /** The Folder View waiting on All files access goes in the widget dock. */
    var pendingDock = false

    internal val io = Executors.newFixedThreadPool(2)
    internal val main = Handler(Looper.getMainLooper())
    private val thumbs = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val icons = HashMap<String, Bitmap>()

    fun nameOf(path: String): String = if (File(path) == storageRoot) "Internal storage" else File(path).name.ifEmpty { path }
    fun labelOf(item: DesktopItem) = item.label ?: nameOf(item.key)

    fun list(dir: File, newestFirst: Boolean): List<FileEntry>? {
        val files = dir.listFiles() ?: return null
        val byName = Comparator<FileEntry> { a, b -> String.CASE_INSENSITIVE_ORDER.compare(a.name, b.name) }
        val order = if (newestFirst) compareByDescending<FileEntry> { it.modified }.then(byName) else byName
        return files.asSequence().filter { !it.name.startsWith(".") }
            .map { FileEntry(it, it.isDirectory, it.lastModified()) }
            .sortedWith(compareBy<FileEntry> { !it.dir }.then(order)).toList()
    }

    // ------------------------------------------------------------ icons

    private val IMAGE = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "avif")
    private val VIDEO = setOf("mp4", "mkv", "webm", "mov", "avi", "3gp", "m4v")
    private val AUDIO = setOf("mp3", "m4a", "aac", "flac", "ogg", "opus", "wav", "mid")
    private val ARCHIVE = setOf("zip", "7z", "rar", "tar", "gz", "xz", "bz2", "zst")
    private val TEXT = setOf("txt", "md", "doc", "docx", "odt", "rtf", "epub", "log")
    private val SHEET = setOf("xls", "xlsx", "ods", "csv", "tsv")
    private val SLIDES = setOf("ppt", "pptx", "odp")
    private val CODE = setOf("kt", "java", "py", "js", "ts", "sh", "c", "cpp", "h", "rs", "go", "json", "xml", "html", "css", "yaml", "yml", "toml")

    fun isImage(e: FileEntry) = e.ext in IMAGE
    fun isVideo(e: FileEntry) = e.ext in VIDEO

    /** A Material symbol and colour for a kind of file (0 = the accent colour). */
    private fun kindOf(e: FileEntry): Pair<String, Int> = when {
        e.dir -> "folder" to 0
        e.ext in IMAGE -> "photo" to 0xFF1E9E8E.toInt()
        e.ext in VIDEO -> "video_file" to 0xFFE5534B.toInt()
        e.ext in AUDIO -> "audio_file" to 0xFFD99A1E.toInt()
        e.ext == "pdf" -> "picture_as_pdf" to 0xFFE0473E.toInt()
        e.ext in ARCHIVE -> "folder_zip" to 0xFF8D6E63.toInt()
        e.ext == "apk" -> "android" to 0xFF3DA35D.toInt()
        e.ext in TEXT -> "article" to 0xFF4C8DF6.toInt()
        e.ext in SHEET -> "table" to 0xFF2E9E5B.toInt()
        e.ext in SLIDES -> "slideshow" to 0xFFEF7C1A.toInt()
        e.ext in CODE -> "code" to 0xFF8667D6.toInt()
        else -> "draft" to 0xFF8A9BB0.toInt()
    }

    /** The plain icon for a kind of file: its symbol on a soft tile of its colour (a folder is just the symbol). */
    fun typeIcon(act: TabActivity, e: FileEntry, size: Int): Bitmap {
        val (name, c) = kindOf(e)
        val color = if (c == 0) act.pal.accent else c
        val key = "$name/$color/$size"
        icons[key]?.let { return it }
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val cv = Canvas(b)
        val folder = name == "folder"
        if (!folder) {
            val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = withAlpha(color, 0x30) }
            val m = size * 0.1f
            cv.drawRoundRect(RectF(m, m, size - m, size - m), size * 0.2f, size * 0.2f, bg)
        }
        val tf = Icons.symbolFont(act, fill = true)
        val text = Icons.symbol(act, name)?.text
        if (tf != null && text != null) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = tf; textSize = size * if (folder) 1.0f else 0.52f; this.color = color; textAlign = Paint.Align.CENTER
            }
            val fm = p.fontMetrics
            cv.drawText(text, size / 2f, size / 2f - (fm.ascent + fm.descent) / 2f, p)
        }
        icons[key] = b
        return b
    }

    /** A Folder View's desktop icon: an accent tile with a folder on it, like an app's. */
    fun folderIcon(act: HomeActivity): Bitmap {
        val key = "desk/${act.pal.accent}"
        icons[key]?.let { return it }
        val size = 192
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val cv = Canvas(b)
        cv.drawRoundRect(RectF(8f, 8f, size - 8f, size - 8f), 52f, 52f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = act.pal.accent })
        val tf = Icons.symbolFont(act, fill = true)
        val text = Icons.symbol(act, "folder_open")?.text
        if (tf != null && text != null) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = tf; textSize = size * 0.58f; color = act.pal.onAccent; textAlign = Paint.Align.CENTER
            }
            val fm = p.fontMetrics
            cv.drawText(text, size / 2f, size / 2f - (fm.ascent + fm.descent) / 2f, p)
        }
        icons[key] = b
        return b
    }

    fun cachedThumb(e: FileEntry): Bitmap? = thumbs.get("${e.file.path}:${e.modified}")

    fun wantsThumb(e: FileEntry) = !e.dir && (e.ext in IMAGE || e.ext in VIDEO || e.ext == "apk")

    /** A picture of the file (photos, videos, an APK's app icon), made off the main thread. */
    fun loadThumb(act: Context, e: FileEntry, px: Int, done: (Bitmap) -> Unit) {
        io.execute {
            val b = try {
                when {
                    e.ext in IMAGE -> ThumbnailUtils.createImageThumbnail(e.file, Size(px, px), null)
                    e.ext in VIDEO -> ThumbnailUtils.createVideoThumbnail(e.file, Size(px, px), null)
                    e.ext == "apk" -> act.packageManager.getPackageArchiveInfo(e.file.path, 0)?.applicationInfo?.let { ai ->
                        ai.sourceDir = e.file.path; ai.publicSourceDir = e.file.path
                        Apps.render(ai.loadIcon(act.packageManager), px)
                    }
                    else -> null
                }
            } catch (_: Throwable) { null } ?: return@execute
            thumbs.put("${e.file.path}:${e.modified}", b)
            main.post { done(b) }
        }
    }

    // ------------------------------------------------------------ All files access

    /** Why TabDesk asks, then Android's page for it. */
    fun explainAccess(act: HomeActivity, cell: IntArray? = null, toDock: Boolean = false) {
        val card = act.overlays.dialogCard(act.dp(460), "Show your files")
        card.addView(TextView(act).label(act.pal.text, 14.5f).apply {
            text = "To show a folder's files, TabDesk needs Android's “All files access”. Turn it on for TabDesk on " +
                "the next page, then come back.\n\nTabDesk only reads the folders you put on the desktop, and only " +
                "changes files when you rename, delete, or drop files on a Folder View."
            setLineSpacing(0f, 1.15f)
        }, llp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = act.dp(16) })
        val buttons = linear(act, false) { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        buttons.addView(act.overlays.button("Not now", false) { act.overlays.dismissTop() }, llp(ViewGroup.LayoutParams.WRAP_CONTENT, act.dp(40)))
        buttons.addView(act.overlays.button("Open settings", true) {
            act.overlays.dismissTop()
            pendingAdd = cell; pendingDock = toDock
            openAccessSettings(act)
        }, llp(ViewGroup.LayoutParams.WRAP_CONTENT, act.dp(40)).apply { marginStart = act.dp(8) })
        card.addView(buttons, llp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        act.overlays.show(card, dim = true)
    }

    fun openAccessSettings(act: TabActivity) {
        try {
            act.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${act.packageName}")))
        } catch (_: ActivityNotFoundException) {
            try { act.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
            catch (_: ActivityNotFoundException) { act.toast("This tablet has no All files access page") }
        }
    }

    /** TabDesk is in front again: if the user just turned access on to add a Folder View, carry on. */
    fun resumed(act: HomeActivity) {
        act.desktop.refreshFolderViews()
        act.dock.refreshFolderViews()
        val cell = pendingAdd ?: return
        pendingAdd = null
        if (canRead) { if (pendingDock) act.addFolderViewToDock() else act.addFolderView(cell) }
        pendingDock = false
    }

    // ------------------------------------------------------------ hover to open

    private var hoverOpen: Runnable? = null

    /** The mouse rests on a Folder View's icon: open it after a moment; it closes again if the mouse moves away. */
    fun hover(act: HomeActivity, item: DesktopItem, v: View, on: Boolean) {
        hoverOpen?.let { main.removeCallbacks(it) }
        hoverOpen = null
        if (!on || !canRead || act.overlays.isOpen) return
        val r = Runnable { hoverOpen = null; if (v.isAttachedToWindow && v.isHovered) act.openFolderView(item, v, byHover = true) }
        hoverOpen = r
        main.postDelayed(r, 550)
    }

    // ------------------------------------------------------------ file operations

    /** A name not yet used in [dir]: "name (2).ext" and so on. */
    fun freeName(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        val base = name.substring(0, dot); val ext = name.substring(dot)
        var n = 2
        while (f.exists()) { f = File(dir, "$base ($n)$ext"); n++ }
        return f
    }

    /** A file name someone typed, made safe: no folders in it, nothing hidden or empty. */
    fun cleanName(s: String) = s.trim().replace('/', '-').replace('\u0000', ' ').trimStart('.').take(200)

    /** Where TabDesk notes the files it last started dragging, in either of its processes (other apps can't read it). */
    private fun dragNote(ctx: Context) = File(ctx.filesDir, "outgoing-drag")

    fun noteDrag(ctx: Context, files: List<File>) {
        try { dragNote(ctx).writeText(files.joinToString("\n") { it.canonicalPath }) } catch (_: Exception) {}
    }

    /**
     * The files a drop carries when TabDesk itself dragged them from its other process (TabDesk Files to a Folder View,
     * or back), so they can be moved as well as copied. Null for anything else, including links made to look like ours.
     */
    fun ownDrop(ctx: Context, clip: ClipData?): List<File>? {
        if (clip == null || clip.itemCount == 0) return null
        val files = (0 until clip.itemCount).map { i ->
            val u = clip.getItemAt(i).uri ?: return null
            if (u.authority != FilesProvider.AUTHORITY) return null
            FilesProvider.fileOf(u) ?: return null
        }
        val noted = try { dragNote(ctx).readLines().toSet() } catch (_: Exception) { return null }
        return files.takeIf { f -> f.map { it.canonicalPath }.toSet() == noted }
    }

    fun copyInto(act: Context, uri: Uri, dir: File): Boolean = try {
        val name = act.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }?.let { cleanName(it) }?.takeIf { it.isNotEmpty() } ?: (uri.lastPathSegment?.let { cleanName(it.substringAfterLast('/')) }?.ifEmpty { null } ?: "Dropped file")
        val out = freeName(dir, name)
        val part = File(dir, ".${out.name}.part")
        act.contentResolver.openInputStream(uri)!!.use { i -> part.outputStream().use { o -> i.copyTo(o) } }
        part.renameTo(out)
    } catch (e: Exception) { false }

    fun moveOrCopy(src: File, dir: File, move: Boolean): Boolean {
        if (dir.canonicalPath.startsWith(src.canonicalPath + "/") || dir.canonicalFile == src.canonicalFile) return false
        val out = freeName(dir, src.name)
        return try {
            if (move && src.renameTo(out)) true
            else {
                val ok = src.copyRecursively(out, overwrite = false)
                if (ok && move) src.deleteRecursively() else ok
            }
        } catch (_: Exception) { false }
    }
}

// ---------------------------------------------------------------- the panel

/**
 * A folder's files: a title row (back arrow inside a subfolder) and a grid. On the desktop when the Folder View is
 * bigger than one cell, or in the popup from its icon ([popup]).
 */
class FolderPanel(private val act: HomeActivity, val item: DesktopItem, private val popup: Boolean,
                  private val inDock: Boolean = false) : LinearLayout(act) {
    private val pal get() = act.pal
    private val root get() = File(item.key)
    var dir: File = root; private set
    /** The popup layer it's in, to close it when a file opens. */
    var layer: Overlays.Layer? = null
    /** The pointer came onto the panel (a popup opened by hovering then stays open). */
    var onHoverIn: (() -> Unit)? = null

    private val back = TextView(act)
    private val title = TextView(act).label(pal.text, 15f, bold = true)
    private val grid = GridView(act)
    private val message = linear(act, true) { gravity = Gravity.CENTER }
    private val messageText = TextView(act).label(pal.textDim, 14f)
    private var messageButton: View? = null
    private var entries: List<FileEntry> = emptyList()
    private var observer: FileObserver? = null
    private var highlight = false
    private val tileIcon = dp(48)
    private val refresh = Runnable { load() }
    /** The title row's buttons; hidden when the panel is too narrow to show the title beside them. */
    private val actions = ArrayList<View>()

    init {
        orientation = VERTICAL
        background = when {
            inDock -> rounded(pal.field, dpf(16))
            popup -> rounded(pal.surfaceSolid, dpf(22), 1, pal.divider)
            else -> rounded(withAlpha(pal.surfaceSolid, 0xE6), dpf(18), 1, pal.divider)
        }
        elevation = dpf(if (popup) 18 else if (inDock) 0 else 4)
        setPadding(dp(8), dp(6), dp(8), dp(4))

        val head = linear(act, false) { gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(40) }
        back.apply {
            typeface = Icons.symbolFont(act, fill = false); textSize = 22f; setTextColor(pal.text); gravity = Gravity.CENTER
            text = Icons.symbol(act, "arrow_back")?.text ?: "<"
            background = rippleBg(pal.ripple, dpf(16)); contentDescription = "Back"; tooltipText = "Back"
            setOnClickListener { up() }
            visibility = GONE
        }
        head.addView(back, llp(dp(36), dp(36)))
        title.apply {
            isSingleLine = true; ellipsize = TextUtils.TruncateAt.MIDDLE
            setPadding(dp(8), 0, dp(8), 0)
        }
        head.addView(title, llp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        // On the desktop panel: open the folder in TabDesk Files, and show the Folder View as an icon (both in its
        // menu too). The popup is the icon's already, and the dock has its own menu.
        if (!popup && !inDock) {
            fun headButton(symbol: String, words: String, click: () -> Unit) = TextView(act).apply {
                typeface = Icons.symbolFont(act, fill = false); textSize = 20f; setTextColor(pal.text); gravity = Gravity.CENTER
                text = Icons.symbol(act, symbol)?.text ?: words.take(1)
                background = rippleBg(pal.ripple, dpf(16)); contentDescription = words; tooltipText = words
                setOnClickListener { click() }
            }.also { head.addView(it, llp(dp(36), dp(36))); actions += it }
            headButton("open_in_new", "Open in TabDesk Files") { act.openInFiles(dir) }
            headButton("collapse_content", "Show as an icon") { act.showFolderViewAsIcon(item) }
        }
        // Hold the title row (or right-click it) for the Folder View's menu. On the desktop, hold and move it to move
        // the panel; the empty space in the grid and the panel's edges do the same, so it's easy to grab.
        val move: ((View, Float, Float) -> Unit)? = if (popup || inDock) null
            else { v, x, y -> act.desktop.startDrag(item, this, x + v.left, y + v.top) }
        head.setOnTouchListener(PressHelper(head, onLong = { v, _, _ -> act.folderViewMenu(item, v, this) }, onDrag = move))
        if (move != null) setOnTouchListener(PressHelper(this,
            onLong = { _, _, _ -> act.folderViewMenu(item, this, this) },
            onDrag = { _, x, y -> act.desktop.startDrag(item, this, x, y) }))
        addView(head, llp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        grid.apply {
            numColumns = GridView.AUTO_FIT
            columnWidth = dp(if (popup) 92 else 84)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            verticalSpacing = dp(2)
            selector = ColorDrawable(Color.TRANSPARENT)
            isVerticalScrollBarEnabled = true
            clipToPadding = false
            setPadding(0, dp(2), 0, dp(6))
            adapter = Adapter()
        }
        // Holding empty space in the grid (or right-clicking it) gives the Folder View's menu too, and moving on
        // from there moves the panel.
        val empty = PressHelper(grid, onLong = { _, x, y -> act.folderViewMenu(item, grid, this, x, y) }, onDrag = move)
        grid.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) emptyDown = grid.pointToPosition(e.x.toInt(), e.y.toInt()) == AdapterView.INVALID_POSITION
            // Once held, the grid doesn't scroll as well while the panel moves.
            emptyDown && empty.handle(e) && empty.longPressed && e.actionMasked == MotionEvent.ACTION_MOVE
        }
        addView(grid, llp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        messageText.apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.15f); setPadding(dp(16), 0, dp(16), dp(10)) }
        message.addView(messageText, llp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        message.visibility = GONE
        addView(message, llp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        bindTitle()
    }

    private var emptyDown = false

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val show = w >= dp(200)
        if (actions.any { (it.visibility == VISIBLE) != show }) post { actions.forEach { it.visibility = if (show) VISIBLE else GONE } }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        load()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        observer?.stopWatching(); observer = null
        removeCallbacks(refresh)
    }

    override fun dispatchHoverEvent(e: MotionEvent): Boolean {
        if (e.actionMasked != MotionEvent.ACTION_HOVER_EXIT) onHoverIn?.invoke()
        return super.dispatchHoverEvent(e)
    }

    /** The folder changed, or access was turned on, or the Folder View now points somewhere else. */
    fun reload(toRoot: Boolean = false) {
        if (toRoot || !dir.path.startsWith(root.path)) dir = root
        bindTitle()
        load()
    }

    private fun bindTitle() {
        title.text = if (dir == root) FolderViews.labelOf(item) else dir.name
        title.contentDescription = dir.path   // not a tooltip: that would take the long press meant for the row
        back.visibility = if (dir != root) VISIBLE else GONE
    }

    private fun up() {
        if (dir == root) return
        dir = dir.parentFile ?: root
        bindTitle(); load()
    }

    private fun into(d: File) {
        dir = d
        bindTitle(); load()
    }

    private fun load() {
        if (!isAttachedToWindow) return
        if (!FolderViews.canRead) {
            show(null, "To show this folder, TabDesk needs Android's “All files access”.", "Turn it on") {
                act.overlays.dismissAll(); FolderViews.explainAccess(act)
            }
            return
        }
        val d = dir
        val newest = item.opts and FolderViews.NEWEST_FIRST != 0
        watch(d)
        FolderViews.io.execute {
            val list = if (d.isDirectory) FolderViews.list(d, newest) else null
            post {
                if (d != dir) return@post
                when {
                    list == null && d == root -> show(null, "This folder isn't on this tablet:\n${d.path}", "Choose a folder") {
                        act.overlays.dismissAll(); act.changeFolderView(item)
                    }
                    list == null -> up()
                    list.isEmpty() -> show(list, "Nothing here yet. Drop files here to copy them in.", null, null)
                    else -> show(list, null, null, null)
                }
            }
        }
    }

    private fun show(list: List<FileEntry>?, text: String?, button: String?, action: (() -> Unit)?) {
        entries = list ?: emptyList()
        (grid.adapter as Adapter).notifyDataSetChanged()
        grid.visibility = if (text == null) VISIBLE else GONE
        message.visibility = if (text != null) VISIBLE else GONE
        messageText.text = text
        messageButton?.let { message.removeView(it) }; messageButton = null
        if (button != null && action != null) {
            messageButton = act.overlays.button(button, true, action).also { message.addView(it, llp(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40))) }
        }
        if (popup) fitPopup()
    }

    /** A popup is as tall as its files need, up to the height it was given. */
    var maxHeight = 0
    private fun fitPopup() {
        val lp = layoutParams ?: return
        if (maxHeight == 0) maxHeight = lp.height
        val cols = maxOf(1, (lp.width - paddingLeft - paddingRight) / dp(92))
        val rows = if (grid.visibility == VISIBLE) (entries.size + cols - 1) / cols else 0
        val body = if (rows > 0) rows * dp(106) + dp(10) else dp(150)
        val h = minOf(maxHeight, paddingTop + paddingBottom + dp(42) + body)
        if (lp.height != h) { lp.height = h; layoutParams = lp }
    }

    /** Re-reads the folder when a file in it is added, removed, renamed or finished writing (e.g. a download). */
    private fun watch(d: File) {
        observer?.stopWatching()
        observer = object : FileObserver(d, CREATE or DELETE or MOVED_FROM or MOVED_TO or CLOSE_WRITE or DELETE_SELF or MOVE_SELF) {
            override fun onEvent(event: Int, path: String?) {
                if (event and FolderViews.WATCH_REMOVED != 0 || observer !== this) return
                if (path?.startsWith(".") == true && event and (DELETE_SELF or MOVE_SELF) == 0) return   // our own .part files
                removeCallbacks(refresh); postDelayed(refresh, 350)
            }
        }.also { it.startWatching() }
    }

    // ------------------------------------------------------------ files

    private inner class Tile : LinearLayout(act) {
        val img = ImageView(act)
        val name = TextView(act).label(pal.text, 12.5f)
        var entry: FileEntry? = null

        init {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = rippleBg(pal.ripple, dpf(12))
            setPadding(dp(4), dp(8), dp(4), dp(6))
            img.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, dpf(8)) }
            }
            img.clipToOutline = true
            addView(img, llp(tileIcon, tileIcon))
            name.apply {
                maxLines = 2; ellipsize = TextUtils.TruncateAt.MIDDLE; gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, dp(5), 0, 0)
            }
            addView(name, llp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            setOnTouchListener(PressHelper(this,
                onTap = { entry?.let { open(it, this) } },
                onMiddle = { entry?.let { if (it.dir) open(it, this) else act.openFile(it.file, window = true) } },
                onLong = { v, _, _ -> entry?.let { act.fileMenu(it, v, this@FolderPanel) } },
                onDrag = { v, _, _ -> entry?.let { dragOut(it, v) } }))
        }

        fun bind(e: FileEntry) {
            entry = e
            name.text = e.name
            contentDescription = e.name
            val thumb = FolderViews.cachedThumb(e)
            if (thumb != null) { img.scaleType = ImageView.ScaleType.CENTER_CROP; img.setImageBitmap(thumb); return }
            img.scaleType = ImageView.ScaleType.FIT_CENTER
            img.setImageBitmap(FolderViews.typeIcon(act, e, tileIcon))
            if (FolderViews.wantsThumb(e)) FolderViews.loadThumb(act, e, dp(96)) { b ->
                if (entry === e) {
                    img.scaleType = if (e.ext == "apk") ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
                    img.setImageBitmap(b)
                }
            }
        }

        val bitmap: Bitmap? get() = (img.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = entries.size
        override fun getItem(i: Int) = entries[i]
        override fun getItemId(i: Int) = i.toLong()
        override fun getView(i: Int, convert: View?, parent: ViewGroup?): View {
            val t = convert as? Tile ?: Tile()
            t.bind(entries[i])
            return t
        }
    }

    fun open(e: FileEntry, from: View) {
        if (e.dir) { into(e.file); return }
        if (popup) layer?.let { act.overlays.dismiss(it) }
        act.openFile(e.file, from)
    }

    /** Drag a file into another app's window (it gets to read that one file), or onto another Folder View. */
    private fun dragOut(e: FileEntry, v: View) {
        val bmp = (v as? Tile)?.bitmap ?: FolderViews.typeIcon(act, e, tileIcon)
        val clip = if (e.dir) ClipData(e.name, arrayOf(ClipDescription.MIMETYPE_TEXT_PLAIN), ClipData.Item(e.file.path))
            else ClipData(e.name, arrayOf(FilesProvider.mimeOf(e.file)), ClipData.Item(FilesProvider.uriFor(e.file)))
        val flags = View.DRAG_FLAG_OPAQUE or if (e.dir) 0 else View.DRAG_FLAG_GLOBAL or View.DRAG_FLAG_GLOBAL_URI_READ
        if (!e.dir) FolderViews.noteDrag(act, listOf(e.file))
        // The file's menu (from holding it) goes, and a popup too, so the file can go onto whatever is under it.
        if (v.startDragAndDrop(clip, BitmapShadow(bmp, dp(56)), FileDrag(e.file, this), flags)) act.overlays.dismissAll()
    }

    // ------------------------------------------------------------ dropping files in

    override fun onDragEvent(e: DragEvent): Boolean {
        when (e.action) {
            DragEvent.ACTION_DRAG_STARTED -> {
                val s = e.localState
                // Desktop icons being moved aren't files; anything from another app might be.
                return s is FileDrag || (s == null && FolderViews.canRead && dir.isDirectory)
            }
            DragEvent.ACTION_DRAG_ENTERED -> setHighlight(true)
            DragEvent.ACTION_DRAG_EXITED, DragEvent.ACTION_DRAG_ENDED -> setHighlight(false)
            DragEvent.ACTION_DROP -> { setHighlight(false); return drop(e) }
        }
        return true
    }

    private fun setHighlight(on: Boolean) {
        if (highlight == on) return
        highlight = on
        foreground = if (on) rounded(withAlpha(pal.accent, 0x22), dpf(if (popup) 22 else 18), dp(2), pal.accent) else null
    }

    /** The folder a drop at (x, y) goes into: a subfolder's tile, or the folder shown. */
    private fun targetAt(x: Float, y: Float): File {
        val gx = (x - grid.left).toInt(); val gy = (y - grid.top).toInt()
        val i = grid.pointToPosition(gx, gy)
        return entries.getOrNull(i)?.takeIf { it.dir }?.file ?: dir
    }

    private fun drop(e: DragEvent): Boolean {
        val target = targetAt(e.x, e.y)
        val s = e.localState
        // From another Folder View, or from TabDesk Files: move or copy. From other apps: copy.
        val own = if (s is FileDrag) listOf(s.file) else FolderViews.ownDrop(act, e.clipData)
        if (own != null) {
            val files = own.filter { it != target && it.parentFile != target }
            if (files.isEmpty()) return false
            val at = act.anchorFor(this).let { r -> Rect(r.left + e.x.toInt(), r.top + e.y.toInt(), r.left + e.x.toInt(), r.top + e.y.toInt()) }
            val what = if (files.size == 1) files[0].name else "${files.size} files"
            act.overlays.menu(at, listOf(
                MenuEntry("Move here", G.SAVE) { files.forEach { transfer(it, target, move = true) } },
                MenuEntry("Copy here", G.PLUS) { files.forEach { transfer(it, target, move = false) } },
                MenuEntry("Cancel", G.CLOSE),
            ), "$what → ${target.name}")
            return true
        }
        val clip = e.clipData ?: return false
        val uris = (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
        if (uris.isEmpty()) { act.toast("Only files can be dropped here"); return false }
        val perms = act.requestDragAndDropPermissions(e)
        FolderViews.io.execute {
            val ok = uris.count { FolderViews.copyInto(act, it, target) }
            perms?.release()
            post {
                act.toast(when {
                    ok == uris.size -> if (ok == 1) "Copied into ${target.name}" else "Copied $ok files into ${target.name}"
                    ok == 0 -> "Couldn't copy that here"
                    else -> "Copied $ok of ${uris.size} files"
                })
            }
        }
        return true
    }

    private fun transfer(src: File, target: File, move: Boolean) {
        FolderViews.io.execute {
            val ok = FolderViews.moveOrCopy(src, target, move)
            post { if (!ok) act.toast(if (move) "Couldn't move ${src.name}" else "Couldn't copy ${src.name}") }
        }
    }

    /** A rename, delete or new folder happened here: show it now rather than waiting for the folder watcher. */
    fun changed() { removeCallbacks(refresh); postDelayed(refresh, 120) }
}

// ---------------------------------------------------------------- actions (HomeActivity)

/** Where [v] is, in the desktop's coordinates (for menus), even when it's in the window above floating windows. */
fun HomeActivity.anchorFor(v: View): Rect {
    val r = v.screenRect(); val o = IntArray(2).also { root.getLocationOnScreen(it) }
    r.offset(-o[0], -o[1]); return r
}

/** Add a Folder View at [cell]: asks for All files access first if needed, then which folder. */
fun HomeActivity.addFolderView(cell: IntArray?) {
    if (!FolderViews.canRead) { FolderViews.explainAccess(this, cell ?: intArrayOf(-1, -1)); return }
    pickFolder(null, "Show which folder?") { f -> desktop.addFolderView(f.path, cell?.takeIf { it[0] >= 0 }) }
}

/** The same, into the widget dock (from its + button). */
fun HomeActivity.addFolderViewToDock() {
    if (!FolderViews.canRead) { FolderViews.explainAccess(this, intArrayOf(-1, -1), toDock = true); return }
    dock.close()
    pickFolder(null, "Show which folder?") { f -> dock.addFolderView(f.path) }
}

/** A Folder View's folder, name or sorting changed: save it wherever it is, and show it. */
fun HomeActivity.folderViewsChanged(toRoot: Boolean = false) {
    desktop.save(); dock.save()
    desktop.refreshFolderViews(toRoot); dock.refreshFolderViews(toRoot)
}

fun HomeActivity.changeFolderView(item: DesktopItem) {
    if (!FolderViews.canRead) { FolderViews.explainAccess(this); return }
    val back = dock.has(item)
    dock.close()
    pickFolder(File(item.key).takeIf { it.isDirectory }, "Show which folder?") { f ->
        item.key = f.path; item.label = null
        folderViewsChanged(toRoot = true)
        if (back) dock.open()
    }
}

/** The popup from a Folder View's icon. */
fun HomeActivity.openFolderView(item: DesktopItem, from: View, byHover: Boolean = false) {
    if (!FolderViews.canRead) { FolderViews.explainAccess(this); return }
    overlays.dismissAll()
    val panel = FolderPanel(this, item, popup = true)
    val w = min(dp(560), root.width - dp(24)); val h = min(dp(470), root.height - dp(140))
    panel.layoutParams = FrameLayout.LayoutParams(w, h)
    val layer = overlays.show(panel, from.rectIn(root), aboveWindows = true)
    panel.layer = layer
    if (byHover) {
        // Opened by resting the mouse on the icon: it closes if the mouse leaves without going onto it.
        var entered = false
        val close = Runnable { if (!entered && overlays.isOpen(layer)) overlays.dismiss(layer) }
        panel.onHoverIn = { entered = true; layer.removeCallbacks(close) }
        layer.setOnHoverListener { l, e ->
            if (!entered) {
                val r = from.screenRect(); val o = IntArray(2).also { l.getLocationOnScreen(it) }
                r.offset(-o[0], -o[1])
                if (r.contains(e.x.toInt(), e.y.toInt())) l.removeCallbacks(close)
                else if (e.actionMasked == MotionEvent.ACTION_HOVER_MOVE || e.actionMasked == MotionEvent.ACTION_HOVER_ENTER) {
                    l.removeCallbacks(close); l.postDelayed(close, 450)
                }
            }
            false
        }
    }
}

/** TabDesk Files at [dir], in a floating window if Files is set to open in one. */
fun HomeActivity.openInFiles(dir: File) {
    dock.close(); overlays.dismissAll()
    val key = android.content.ComponentName(this, FilesActivity::class.java).flattenToString()
    val opts = if (canFloat && key in Prefs.floatApps) floatingOptions() else null
    try { startActivity(FilesActivity.intent(this, dir.takeIf { it.isDirectory }), opts) } catch (_: Exception) { toast("Can't open TabDesk Files") }
}

/** Open a file in the app Android picks for it ([choose]: let the user pick; [window]: in a floating window). */
fun TabActivity.openFile(f: File, from: View? = null, window: Boolean = false, choose: Boolean = false) {
    (this as? HomeActivity)?.dock?.close()
    val uri = FilesProvider.uriFor(f)
    val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, FilesProvider.mimeOf(f))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    val intent = if (choose) Intent.createChooser(view, "Open ${f.name} with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) else view
    val opts = when {
        window && canFloat -> floatingOptions()
        from != null -> ActivityOptions.makeClipRevealAnimation(from, 0, 0, from.width, from.height).toBundle()
        else -> null
    }
    try { startActivity(intent, opts) }
    catch (_: ActivityNotFoundException) { toast("No app here opens .${f.extension.ifEmpty { "?" }} files") }
    catch (_: SecurityException) { toast("Can't open ${f.name}") }
}

fun TabActivity.shareFile(f: File) = shareFiles(listOf(f))

/** Share one file or several (Android's share sheet: Taildrop, Quick Share, mail, …). */
fun TabActivity.shareFiles(files: List<File>) {
    (this as? HomeActivity)?.dock?.close()
    val list = files.filter { it.isFile }
    if (list.isEmpty()) { toast("Folders can't be shared; share the files in them"); return }
    val uris = ArrayList(list.map { FilesProvider.uriFor(it) })
    val types = list.map { FilesProvider.mimeOf(it) }.distinct()
    val type = types.singleOrNull() ?: types.map { it.substringBefore('/') }.distinct().singleOrNull()?.let { "$it/*" } ?: "*/*"
    val send = if (uris.size == 1) Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, uris[0])
        else Intent(Intent.ACTION_SEND_MULTIPLE).setType(type).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    // The share sheet's preview and some apps read the files from the clip.
    send.clipData = ClipData.newRawUri(null, uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
    val title = if (list.size == 1) "Share ${list[0].name}" else "Share ${list.size} files"
    try { startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    catch (_: ActivityNotFoundException) { toast("No app here can share it") }
}

/** A file's menu: open, open with, share, rename, delete. */
fun HomeActivity.fileMenu(e: FileEntry, anchor: View, panel: FolderPanel) {
    val f = e.file
    val list = ArrayList<MenuEntry>()
    if (e.dir) list += MenuEntry("Open", G.FOLDER) { panel.open(e, anchor) }
    else {
        list += MenuEntry("Open", G.WINDOW) { panel.open(e, anchor) }
        if (canFloat) list += MenuEntry("Open in a window", G.RESTORE) { openFile(f, window = true) }
        list += MenuEntry("Open with…", G.GRID) { openFile(f, choose = true) }
        list += MenuEntry("Share…", G.GLOBE) { shareFile(f) }
    }
    list += MenuEntry.DIVIDER
    list += MenuEntry("Rename", G.EDIT) {
        overlays.prompt(if (e.dir) "Rename folder" else "Rename file", f.name, f.name, null) { typed ->
            val name = FolderViews.cleanName(typed)
            when {
                name.isEmpty() || name == f.name -> {}
                File(f.parentFile, name).exists() -> toast("There's already a “$name” here")
                !f.renameTo(File(f.parentFile, name)) -> toast("Couldn't rename it")
                else -> panel.changed()
            }
        }
    }
    list += MenuEntry("Delete", G.TRASH) { confirmDelete(e, panel) }
    overlays.menu(anchorFor(anchor), list, f.name)
}

private fun HomeActivity.confirmDelete(e: FileEntry, panel: FolderPanel) = confirmDelete(listOf(e.file)) { panel.changed() }

/** "Delete …?", then deletes [files] (folders with what's in them) and calls [done] on the main thread. */
fun TabActivity.confirmDelete(files: List<File>, done: () -> Unit) {
    if (files.isEmpty()) return
    val one = files.singleOrNull()
    val inside = if (one != null && one.isDirectory) (one.listFiles()?.size ?: 0) else 0
    val card = overlays.dialogCard(dp(440), when {
        one == null -> "Delete ${files.size} items?"
        one.isDirectory -> "Delete this folder?"
        else -> "Delete this file?"
    })
    card.addView(TextView(this).label(pal.text, 14.5f).apply {
        text = (if (one != null) "“${one.name}”" + (if (inside > 0) " and the $inside ${if (inside == 1) "item" else "items"} in it" else "")
            else "These ${files.size} items" + (if (files.any { it.isDirectory }) " (and everything in the folders)" else "")) +
            " will be deleted from the tablet. This can't be undone."
        setLineSpacing(0f, 1.15f)
    }, llp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(16) })
    val buttons = linear(this, false) { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
    buttons.addView(overlays.button("Cancel", false) { overlays.dismissTop() }, llp(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)))
    buttons.addView(overlays.button("Delete", true) {
        overlays.dismissTop()
        FolderViews.io.execute {
            var ok = true
            for (f in files) if (!(if (f.isDirectory) f.deleteRecursively() else f.delete())) ok = false
            runOnUiThread { if (!ok) toast("Couldn't delete all of it"); done() }
        }
    }, llp(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)).apply { marginStart = dp(8) })
    card.addView(buttons, llp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    overlays.show(card, dim = true)
}

/** A desktop Folder View panel becomes one icon, remembering its size for [showFolderViewAsPanel]. */
fun HomeActivity.showFolderViewAsIcon(item: DesktopItem) {
    overlays.dismissAll()
    item.panelX = item.spanX; item.panelY = item.spanY
    desktop.setFolderViewSize(item, 1, 1)
}

/** A desktop Folder View icon becomes a panel again, at the size it had (4 × 3 the first time). */
fun HomeActivity.showFolderViewAsPanel(item: DesktopItem) =
    if (item.panelX > 0 && item.panelY > 0) desktop.setFolderViewSize(item, item.panelX, item.panelY)
    else desktop.setFolderViewSize(item, 4, 3)

/** The Folder View's own menu (its icon, title row, or empty space in it). */
fun HomeActivity.folderViewMenu(item: DesktopItem, anchor: View, panel: FolderPanel? = null, x: Float = -1f, y: Float = -1f) {
    val list = ArrayList<MenuEntry>()
    val asPanel = item.spanX > 1 || item.spanY > 1
    if (panel != null) list += MenuEntry("New folder", G.FOLDER) {
        overlays.prompt("New folder", "", "Folder name", null) { typed ->
            val name = FolderViews.cleanName(typed)
            if (name.isEmpty()) return@prompt
            val d = File(panel.dir, name)
            if (d.exists()) toast("There's already a “$name” here") else if (!d.mkdir()) toast("Couldn't make the folder") else panel.changed()
        }
    }
    val newest = item.opts and FolderViews.NEWEST_FIRST != 0
    list += MenuEntry(if (newest) "Sort by name" else "Newest first", G.EQUALS) {
        item.opts = item.opts xor FolderViews.NEWEST_FIRST
        folderViewsChanged()
        panel?.reload()
    }
    list += MenuEntry("Show another folder…", G.FOLDER) { changeFolderView(item) }
    list += MenuEntry("Open in TabDesk Files", G.WINDOW) { openInFiles(panel?.dir ?: File(item.key)) }
    list += MenuEntry("Rename", G.EDIT) {
        overlays.prompt("Rename Folder View", FolderViews.labelOf(item), FolderViews.nameOf(item.key),
            if (item.label != null) { { item.label = null; folderViewsChanged() } } else null) { typed ->
            item.label = typed.trim().takeIf { it.isNotEmpty() && it != FolderViews.nameOf(item.key) }
            folderViewsChanged()
        }
    }
    list += MenuEntry.DIVIDER
    if (dock.has(item)) {
        list += dock.entriesFor(item)
        overlays.menu(anchorFor(anchor).let { r -> if (x >= 0) Rect(r.left + x.toInt(), r.top + y.toInt(), r.left + x.toInt(), r.top + y.toInt()) else r },
            list, FolderViews.labelOf(item))
        return
    }
    if (asPanel) {
        list += MenuEntry("Resize", G.RESIZE) { desktop.startResize(item) }
        list += MenuEntry("Show as an icon", G.MINIMISE) { showFolderViewAsIcon(item) }
    } else if (panel == null) {
        list += MenuEntry("Show as a panel", G.WINDOW) { showFolderViewAsPanel(item) }
    }
    list += MenuEntry("Move to the dock", G.WIDGETS) { dock.takeFromDesktop(item, desktop.viewOf(item)?.height?.takeIf { asPanel } ?: dp(300)) }
    list += MenuEntry("Remove from desktop", G.CLOSE) {
        overlays.dismissAll(); desktop.remove(item); toast("Removed. The folder and its files are still there")
    }
    val at = if (x >= 0) anchorFor(anchor).let { r -> Rect(r.left + x.toInt(), r.top + y.toInt(), r.left + x.toInt(), r.top + y.toInt()) }
        else anchorFor(anchor)
    overlays.menu(at, list, FolderViews.labelOf(item))
}

// ---------------------------------------------------------------- choosing a folder

/** TabDesk's own folder chooser (Android's won't offer Download and some others): shortcuts, then browse. */
fun TabActivity.pickFolder(start: File?, heading: String, onPick: (File) -> Unit) {
    val rootDir = Environment.getExternalStorageDirectory()
    var cur = start ?: rootDir
    val card = overlays.dialogCard(dp(480), heading)
    lateinit var layer: Overlays.Layer

    val places = linear(this, false)
    val placesScroll = android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(places) }
    card.addView(placesScroll, llp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })

    val path = linear(this, false) { gravity = Gravity.CENTER_VERTICAL }
    val upBtn = TextView(this).apply {
        typeface = Icons.symbolFont(this@pickFolder, fill = false); textSize = 22f; setTextColor(pal.text); gravity = Gravity.CENTER
        text = Icons.symbol(this@pickFolder, "arrow_back")?.text ?: "<"
        background = rippleBg(pal.ripple, dpf(16)); contentDescription = "Up a folder"
    }
    val where = TextView(this).label(pal.text, 15f, bold = true).apply { isSingleLine = true; ellipsize = TextUtils.TruncateAt.START; setPadding(dp(8), 0, 0, 0) }
    path.addView(upBtn, llp(dp(36), dp(36)))
    path.addView(where, llp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    card.addView(path, llp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

    val rows = linear(this, true)
    val scroll = ScrollView(this).apply { addView(rows) }
    card.addView(scroll, llp(ViewGroup.LayoutParams.MATCH_PARENT, dp(300)).apply { topMargin = dp(4); bottomMargin = dp(12) })

    fun show(d: File) {
        cur = d
        where.text = if (d == rootDir) "Internal storage" else d.path.removePrefix(rootDir.path + "/")
        upBtn.visibility = if (d == rootDir) View.INVISIBLE else View.VISIBLE
        rows.removeAllViews()
        val subs = d.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }?.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }).orEmpty()
        if (subs.isEmpty()) rows.addView(TextView(this).label(pal.textDim, 14f).apply {
            text = "No folders inside"; setPadding(dp(12), dp(12), dp(12), dp(12))
        })
        for (s in subs) rows.addView(linear(this, false) {
            gravity = Gravity.CENTER_VERTICAL
            background = rippleBg(pal.ripple, dpf(10))
            setPadding(dp(10), 0, dp(10), 0)
            addView(ImageView(this@pickFolder).apply { setImageDrawable(Glyph(G.FOLDER, pal.accent)) }, llp(dp(22), dp(22)))
            addView(TextView(this@pickFolder).label(pal.text, 14.5f).apply {
                text = s.name; isSingleLine = true; ellipsize = TextUtils.TruncateAt.END; setPadding(dp(12), 0, 0, 0)
            }, llp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            setOnClickListener { show(s) }
        }, llp(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
        scroll.scrollTo(0, 0)
    }
    upBtn.setOnClickListener { cur.parentFile?.takeIf { cur != rootDir }?.let { show(it) } }

    for ((name, dir) in listOf(
        "Download" to Environment.DIRECTORY_DOWNLOADS, "Documents" to Environment.DIRECTORY_DOCUMENTS,
        "Pictures" to Environment.DIRECTORY_PICTURES, "Camera" to Environment.DIRECTORY_DCIM,
        "Music" to Environment.DIRECTORY_MUSIC, "Movies" to Environment.DIRECTORY_MOVIES,
    )) {
        val f = Environment.getExternalStoragePublicDirectory(dir)
        if (!f.isDirectory) continue
        places.addView(TextView(this).label(pal.text, 13.5f).apply {
            text = name; gravity = Gravity.CENTER
            setPadding(dp(14), 0, dp(14), 0)
            background = rippleBg(pal.ripple, dpf(16), pal.field)
            setOnClickListener { show(f) }
        }, llp(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)).apply { marginEnd = dp(6) })
    }

    val buttons = linear(this, false) { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
    buttons.addView(overlays.button("New folder", false) {
        overlays.prompt("New folder", "", "Folder name", null) { typed ->
            val name = FolderViews.cleanName(typed)
            if (name.isEmpty()) return@prompt
            val d = File(cur, name)
            if (d.isDirectory || d.mkdir()) show(d) else toast("Couldn't make the folder")
        }
    }, llp(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)))
    buttons.addView(View(this), llp(0, 1, 1f))
    buttons.addView(overlays.button("Cancel", false) { overlays.dismiss(layer) }, llp(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)))
    buttons.addView(overlays.button("Use this folder", true) { overlays.dismiss(layer); onPick(cur) },
        llp(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)).apply { marginStart = dp(8) })
    card.addView(buttons, llp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    show(if (cur.isDirectory) cur else rootDir)
    layer = overlays.show(card, dim = true)
}
