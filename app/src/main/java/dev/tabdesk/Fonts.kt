package dev.tabdesk

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.provider.FontRequest
import android.provider.FontsContract
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Google Fonts for TabDesk's own text, fetched through Google Play services' font provider: no internet
 * permission or API key here, and Play services caches the files for every app. The chosen family is
 * copied into TabDesk's storage so it's there instantly at startup, even offline.
 */
object Fonts {
    private const val AUTHORITY = "com.google.android.gms.fonts"
    private const val PROVIDER = "com.google.android.gms"
    private val SYSTEM_MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    /** Body text. */
    var regular: Typeface = Typeface.DEFAULT; private set
    /** Titles, buttons and other bold text. */
    var medium: Typeface = SYSTEM_MEDIUM; private set

    class Family(val name: String, val category: Int)
    val CATEGORIES = listOf("All", "Sans serif", "Serif", "Display", "Handwriting", "Monospace")
    private const val CODES = "SRDHM"

    private var list: List<Family>? = null

    /** Families from assets/fonts.txt (Latin-script Google Fonts, most popular first). */
    fun catalogue(ctx: Context): List<Family> = list ?: try {
        ctx.assets.open("fonts.txt").bufferedReader().readLines().filter { it.length > 1 }
            .map { Family(it.substring(1), CODES.indexOf(it[0]) + 1) }
    } catch (e: Exception) { emptyList() }.also { list = it }

    fun available(ctx: Context) = try { ctx.packageManager.resolveContentProvider(AUTHORITY, 0) != null } catch (e: Exception) { false }

    private fun dir(ctx: Context) = File(ctx.filesDir, "font")
    private fun savedFamily(ctx: Context) = try { File(dir(ctx), "name").readText() } catch (e: Exception) { null }

    /** A font is chosen but its files aren't here yet (e.g. just restored from a backup). */
    fun isMissing(ctx: Context) = Prefs.font != null && savedFamily(ctx) != Prefs.font

    /** Load the chosen font from storage; the system font if there's none. */
    fun load(ctx: Context) {
        regular = Typeface.DEFAULT; medium = SYSTEM_MEDIUM
        if (Prefs.font == null || isMissing(ctx)) return
        val files = dir(ctx).listFiles().orEmpty()
        val r = files.firstOrNull { it.name.startsWith("r") } ?: return
        regular = typeface(r) ?: return
        medium = files.firstOrNull { it.name.startsWith("m") }?.let { typeface(it) } ?: Typeface.create(regular, Typeface.BOLD)
    }

    fun useSystem(ctx: Context) {
        Prefs.font = null
        dir(ctx).deleteRecursively()
        load(ctx)
    }

    /** Files are named r400 / m600: the weight follows the letter, and is also set on variable fonts. */
    private fun typeface(f: File): Typeface? {
        val w = f.name.drop(1).toIntOrNull() ?: 400
        return try { Typeface.Builder(f).setWeight(w).setFontVariationSettings("'wght' $w").build() } catch (e: Exception) { null }
    }

    // ------------------------------------------------------------ downloading

    private val dl = Executors.newSingleThreadExecutor()
    private val previewExec = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private val waiting = HashMap<String, MutableList<(Boolean) -> Unit>>()

    /** Fetch [family] into storage (regular + a medium/bold weight). [done] runs on the main thread. */
    fun download(ctx: Context, family: String, done: (Boolean) -> Unit) {
        waiting[family]?.let { it += done; return }
        waiting[family] = mutableListOf(done)
        val app = ctx.applicationContext
        dl.execute {
            val ok = fetchFamily(app, family)
            main.post { waiting.remove(family)?.forEach { it(ok) } }
        }
    }

    private fun fetchFamily(ctx: Context, family: String): Boolean {
        val tmp = File(ctx.filesDir, "font-new").apply { deleteRecursively(); mkdirs() }
        try {
            val rw = fetchFile(ctx, family, 400, tmp, 'r') ?: return false
            // Ask for 600; families without a heavier weight fall back to a synthetic bold.
            val mw = fetchFile(ctx, family, 600, tmp, 'm')
            if (mw != null && mw <= rw) tmp.listFiles()?.filter { it.name.startsWith("m") }?.forEach { it.delete() }
            File(tmp, "name").writeText(family)
            val d = dir(ctx)
            d.deleteRecursively()
            return tmp.renameTo(d)
        } catch (e: Exception) {
            return false
        } finally {
            if (tmp.exists()) tmp.deleteRecursively()
        }
    }

    private fun fetchFile(ctx: Context, family: String, weight: Int, into: File, prefix: Char): Int? {
        val f = query(ctx, family, weight) ?: return null
        val out = File(into, "$prefix${f.weight}")
        try {
            ctx.contentResolver.openFileDescriptor(f.uri, "r")?.use { pfd ->
                FileInputStream(pfd.fileDescriptor).use { inp -> out.outputStream().use { inp.copyTo(it) } }
            } ?: return null
        } catch (e: Exception) { return null }
        return if (typeface(out) != null) f.weight else { out.delete(); null }
    }

    private fun query(ctx: Context, family: String, weight: Int): FontsContract.FontInfo? = try {
        val r = FontsContract.fetchFonts(ctx, null, FontRequest(AUTHORITY, PROVIDER, "name=$family&weight=$weight&besteffort=true"))
        val ok = r.fonts.orEmpty().filter { it.resultCode == FontsContract.Columns.RESULT_CODE_OK }
        if (r.statusCode != FontsContract.FontFamilyResult.STATUS_OK) null else ok.firstOrNull { !it.isItalic } ?: ok.firstOrNull()
    } catch (e: Exception) { null }

    // ------------------------------------------------------------ save a copy (for reading apps)

    const val REQ_SAVE = 33
    private var saving: File? = null

    class SavedFile(val style: String, val file: File)

    /** The chosen font's files on the tablet, regular first, each with a style name ("Regular", "SemiBold"…). */
    fun files(ctx: Context): List<SavedFile> {
        if (Prefs.font == null || isMissing(ctx)) return emptyList()
        return dir(ctx).listFiles().orEmpty().filter { it.name.firstOrNull() in listOf('r', 'm') }
            .sortedBy { it.name[0] != 'r' }
            .map { SavedFile(styleName(it.name.drop(1).toIntOrNull() ?: 400), it) }
    }

    private fun styleName(w: Int) = when {
        w <= 150 -> "Thin"; w <= 250 -> "ExtraLight"; w <= 350 -> "Light"; w <= 450 -> "Regular"
        w <= 550 -> "Medium"; w <= 650 -> "SemiBold"; w <= 750 -> "Bold"; w <= 850 -> "ExtraBold"; else -> "Black"
    }

    /** ttf / otf / ttc from the file's first bytes. */
    private fun extension(f: File): String = try {
        val b = ByteArray(4); f.inputStream().use { it.read(b) }
        when (String(b, Charsets.ISO_8859_1)) { "OTTO" -> "otf"; "ttcf" -> "ttc"; else -> "ttf" }
    } catch (e: Exception) { "ttf" }

    /** Ask where to save a copy of [f] (e.g. Downloads or Drive), named like "Nunito-Regular.ttf". */
    fun save(act: HomeActivity, f: SavedFile) {
        saving = f.file
        val name = "${Prefs.font.orEmpty().replace(" ", "")}-${f.style}.${extension(f.file)}"
        act.safeStartForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/octet-stream").putExtra(Intent.EXTRA_TITLE, name), REQ_SAVE)
    }

    fun onResult(act: HomeActivity, req: Int, res: Int, data: Intent?): Boolean {
        if (req != REQ_SAVE) return false
        val src = saving.also { saving = null }
        val uri = data?.data
        if (res != Activity.RESULT_OK || uri == null || src == null) return true
        val app = act.applicationContext
        dl.execute {
            val ok = try {
                app.contentResolver.openOutputStream(uri, "w")!!.use { out -> src.inputStream().use { it.copyTo(out) } }; true
            } catch (e: Exception) { false }
            main.post { act.toast(if (ok) "Font saved. Reading apps can load it from there" else "Couldn't save the font file") }
        }
        return true
    }

    // ------------------------------------------------------------ previews for the picker

    private val previews = object : LinkedHashMap<String, Typeface>(64, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Typeface>?) = size > 120
    }
    private val failed = HashSet<String>()
    private val pending = HashSet<String>()

    fun preview(name: String): Typeface? = previews[name]

    /** Fetch [name] for a picker row, skipped if [wanted] says it's scrolled away by the time its turn comes. */
    fun requestPreview(ctx: Context, name: String, wanted: (String) -> Boolean, done: (String) -> Unit) {
        if (name in previews || name in failed || !pending.add(name)) return
        val app = ctx.applicationContext
        previewExec.execute {
            val go = wanted(name)
            val tf = if (!go) null else try {
                query(app, name, 400)?.let { FontsContract.buildTypeface(app, null, arrayOf(it)) }
            } catch (e: Exception) { null }
            main.post {
                pending -= name
                if (tf != null) { previews[name] = tf; done(name) } else if (go) failed += name
            }
        }
    }
}

// ---------------------------------------------------------------- picker

private class FontRow(val name: TextView, val kind: TextView, val check: ImageView) { var family: String? = null }

/** Search and filter Google Fonts; each row is drawn in its own font, fetched as it comes into view. */
fun HomeActivity.showFontPicker() {
    val all = Fonts.catalogue(this)
    val card = overlays.dialogCard(dp(540), "Font")
    var cat = 0
    var query = ""
    var busy = false
    var idle = true
    var rows: List<Fonts.Family?> = emptyList()
    val visible: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val list = ListView(this)
    val status = TextView(this).label(pal.textDim, 12.5f).apply { setPadding(dp(4), dp(10), 0, 0) }
    lateinit var layer: Overlays.Layer

    fun newRow(): View {
        val r = linear(this, false) {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(12), 0)
            background = rippleBg(pal.ripple, dpf(12))
            layoutParams = AbsListView.LayoutParams(MATCH, dp(52))
        }
        val check = ImageView(this).apply { setImageDrawable(Glyph(G.CHECK, pal.accent)) }
        val name = TextView(this).label(pal.text, 19f).apply { isSingleLine = true; ellipsize = TextUtils.TruncateAt.END }
        val kind = TextView(this).label(pal.textDim, 12f)
        r.addView(check, llp(dp(18), dp(18)).apply { marginEnd = dp(12) })
        r.addView(name, llp(0, WRAP, 1f))
        r.addView(kind, llp(WRAP, WRAP).apply { marginStart = dp(10) })
        r.tag = FontRow(name, kind, check)
        return r
    }

    fun showPreview(name: String) {
        for (i in 0 until list.childCount) {
            val h = list.getChildAt(i).tag as? FontRow ?: continue
            if (h.family == name) Fonts.preview(name)?.let { h.name.typeface = it }
        }
    }

    val adapter = object : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(p: Int) = rows[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, convert: View?, parent: ViewGroup): View {
            val v = convert ?: newRow()
            val h = v.tag as FontRow
            val f = rows[p]
            h.family = f?.name
            h.name.text = f?.name ?: "System default"
            h.name.typeface = if (f == null) Typeface.DEFAULT else Fonts.preview(f.name) ?: Fonts.regular
            h.kind.text = if (f == null) "" else Fonts.CATEGORIES[f.category]
            h.check.visibility = if (f?.name == Prefs.font) View.VISIBLE else View.INVISIBLE
            if (f != null && idle && Fonts.preview(f.name) == null) {
                visible += f.name
                Fonts.requestPreview(this@showFontPicker, f.name, { it in visible }) { showPreview(it) }
            }
            return v
        }
    }

    fun refresh() {
        // Unfiltered: System default first, then the current font (so it's easy to find), then the rest.
        val current = all.firstOrNull { it.name == Prefs.font }
        rows = if (cat == 0 && query.isEmpty()) listOf<Fonts.Family?>(null) + listOfNotNull(current) + all.filter { it !== current }
        else all.filter { (cat == 0 || it.category == cat) && (query.isEmpty() || it.name.contains(query, ignoreCase = true)) }
        adapter.notifyDataSetChanged()
        list.setSelection(0)
        if (!busy) status.text = if (rows.isEmpty()) "No fonts match" else "%,d fonts from Google Fonts".format(rows.count { it != null })
    }

    fun choose(f: Fonts.Family?) {
        if (busy) return
        if (f == null) { Fonts.useSystem(this); applyThemeChange(); return }
        if (f.name == Prefs.font && !Fonts.isMissing(this)) { overlays.dismiss(layer); return }
        busy = true
        status.text = "Downloading ${f.name}…"
        Fonts.download(this, f.name) { ok ->
            busy = false
            if (!ok) { status.text = "Couldn't get ${f.name}. Check the internet connection and try again."; return@download }
            Prefs.font = f.name
            Fonts.load(this)
            if (overlays.isOpen(layer)) applyThemeChange() else rebuildUi()
        }
    }

    // ---- search
    val search = EditText(this).apply {
        hint = "Search %,d fonts".format(all.size)
        isSingleLine = true
        imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        background = rounded(pal.field, dpf(12))
        setTextColor(pal.text); setHintTextColor(pal.textDim); textSize = 15f; typeface = Fonts.regular
        setPadding(dp(14), dp(10), dp(14), dp(10))
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { query = s.toString().trim(); refresh() }
        })
    }
    card.addView(search, llp(MATCH, WRAP).apply { topMargin = dp(4) })

    // ---- categories
    val chips = linear(this, false) { gravity = Gravity.CENTER_VERTICAL }
    fun bindChips() {
        chips.removeAllViews()
        Fonts.CATEGORIES.forEachIndexed { i, name ->
            val on = i == cat
            chips.addView(RoomyText(this).label(if (on) pal.onAccent else pal.text, 13.5f, bold = true).apply {
                text = name; gravity = Gravity.CENTER
                setPadding(dp(14), 0, dp(14), 0)
                background = if (on) rounded(pal.accent, dpf(16)) else rippleBg(pal.ripple, dpf(16))
                setOnClickListener { if (cat != i) { cat = i; bindChips(); refresh() } }
            }, llp(WRAP, dp(34)).apply { marginEnd = dp(4) })
        }
    }
    bindChips()
    card.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) },
        llp(MATCH, WRAP).apply { topMargin = dp(10); bottomMargin = dp(6) })

    // ---- list
    list.apply {
        divider = null
        selector = ColorDrawable(0)
        isVerticalScrollBarEnabled = true
        this.adapter = adapter
        setOnItemClickListener { _, _, p, _ -> choose(rows[p]) }
        setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(v: AbsListView, state: Int) {
                idle = state == AbsListView.OnScrollListener.SCROLL_STATE_IDLE
                if (idle) adapter.notifyDataSetChanged()
            }
            override fun onScroll(v: AbsListView, first: Int, count: Int, total: Int) {
                visible.clear()
                for (i in first until minOf(first + count, rows.size)) rows[i]?.let { visible += it.name }
            }
        })
    }
    card.addView(list, llp(MATCH, dp(330)))
    card.addView(status, llp(MATCH, WRAP))
    refresh()
    layer = overlays.show(card, dim = true)
}
