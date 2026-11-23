package dev.tabdesk.icons

import android.graphics.Bitmap
import android.graphics.drawable.ColorDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.GridView
import android.widget.ImageView
import android.widget.TextView
import dev.tabdesk.*

// ---------------------------------------------------------------- Change icon

fun HomeActivity.showIconPicker(app: AppInfo) {
    val syms = Icons.symbols(this)
    val font = Icons.symbolFont(this, fill = true)
    val choice = Prefs.iconOf(app.key)
    val picked = choice?.takeIf { it.startsWith(Icons.SYM) }?.removePrefix(Icons.SYM)
    val packs = IconPacks.installed(this)
    val card = overlays.dialogCard(dp(560), "Icon for ${app.label}")
    lateinit var layer: Overlays.Layer

    fun choose(v: String?) {
        Prefs.setIcon(app.key, v)
        Apps.rerender(app.key)
        overlays.dismiss(layer)
    }

    // ---- automatic / the app's own icon, each with a preview
    val top = linear(this, false) { gravity = Gravity.CENTER_VERTICAL }
    fun option(title: String, preview: Bitmap?, selected: Boolean, value: String?) = linear(this, false) {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), dp(6), dp(14), dp(6))
        background = if (selected) rounded(pal.accentSoft, dpf(14)) else rippleBg(pal.ripple, dpf(14))
        addView(IconView(context).apply { bitmap = preview }, llp(dp(40), dp(40)))
        addView(TextView(context).label(pal.text, 14.5f, bold = selected).apply { text = title; setPadding(dp(10), 0, 0, 0) })
        setOnClickListener { choose(value) }
    }
    val automaticIsOwn = Icons.spec.style == Icons.ORIGINAL && Icons.spec.pack == null && Icons.spec.effect == IconEffects.NONE
    top.addView(option(if (automaticIsOwn) "App's own icon" else "Automatic", Apps.preview(app, null), choice == null, null),
        llp(WRAP, WRAP).apply { marginEnd = dp(8) })
    if (!automaticIsOwn) top.addView(option("App's own icon", Apps.preview(app, Icons.OWN), choice == Icons.OWN, Icons.OWN))
    card.addView(top, llp(MATCH, WRAP).apply { bottomMargin = dp(10) })

    // ---- where the icons come from: Material symbols, or one of the installed icon packs
    val pickedPack = choice?.takeIf { it.startsWith(Icons.PACK) }?.removePrefix(Icons.PACK)
    var source: String? = pickedPack?.substringBefore('/')?.takeIf { p -> packs.any { it.pkg == p } }
        ?: Icons.spec.pack?.takeIf { p -> packs.any { it.pkg == p } }
    val status = TextView(this).label(pal.textDim, 12.5f).apply { setPadding(dp(4), dp(10), 0, 0) }
    val grid = GridView(this).apply {
        numColumns = GridView.AUTO_FIT
        columnWidth = dp(52)
        stretchMode = GridView.STRETCH_COLUMN_WIDTH
        selector = ColorDrawable(0)
    }
    lateinit var search: EditText

    // Symbols
    var symRows: List<Icons.Symbol> = emptyList()
    val symAdapter = object : BaseAdapter() {
        override fun getCount() = symRows.size
        override fun getItem(p: Int) = symRows[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, convert: View?, parent: ViewGroup): View {
            val v = (convert as? TextView) ?: TextView(this@showIconPicker).apply {
                typeface = font; textSize = 26f; gravity = Gravity.CENTER; setTextColor(pal.text)
                layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52))
            }
            val s = symRows[p]
            v.text = s.text
            v.setOnClickListener { choose(Icons.SYM + s.name) }
            v.tooltipText = s.name.replace('_', ' ')
            v.contentDescription = v.tooltipText
            v.background = if (s.name == picked || picked in s.names) rounded(pal.accentSoft, dpf(12)) else rippleBg(pal.ripple, dpf(12))
            return v
        }
    }

    // A pack's icons, drawn in the background as they scroll into view
    var packNames: List<String> = emptyList()
    var packRows: List<String> = emptyList()
    var packOwn: String? = null
    val packAdapter = object : BaseAdapter() {
        override fun getCount() = packRows.size
        override fun getItem(p: Int) = packRows[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, convert: View?, parent: ViewGroup): View {
            val pack = source ?: return View(this@showIconPicker)
            val v = (convert as? ImageView) ?: ImageView(this@showIconPicker).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(dp(7), dp(7), dp(7), dp(7))
                layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52))
            }
            val name = packRows[p]
            v.tag = "$pack/$name"
            val have = IconPacks.thumb(pack, name, onLight = !pal.dark)
            v.setImageBitmap(have)
            if (have == null) IconPacks.requestThumb(this@showIconPicker, pack, name, dp(38), onLight = !pal.dark) { b -> if (v.tag == "$pack/$name") v.setImageBitmap(b) }
            v.setOnClickListener { choose(Icons.PACK + pack + "/" + name) }
            v.tooltipText = name.replace('_', ' ')
            v.contentDescription = v.tooltipText
            v.background = if (pickedPack == "$pack/$name") rounded(pal.accentSoft, dpf(12)) else rippleBg(pal.ripple, dpf(12))
            return v
        }
    }

    fun filter(q: String) {
        val query = q.trim().lowercase().replace(' ', '_')
        val pack = source
        if (pack == null) {
            symRows = if (query.isEmpty()) {
                // Current pick, then what TabDesk suggests for this app, then the usual app symbols, then everything.
                val first = (listOfNotNull(picked, Icons.suggest(app.pkg, app.label, null, Icons.roles(this))) + Icons.COMMON)
                    .mapNotNull { Icons.symbol(this, it) }.distinct()
                first + syms.filter { it !in first }
            } else syms.filter { s -> s.names.any { it.contains(query) } }
                .sortedBy { s -> if (s.names.any { it.startsWith(query) }) 0 else 1 }
            symAdapter.notifyDataSetChanged()
            status.text = if (symRows.isEmpty()) "No icons match" else "%,d icons · Material Symbols by Google · long-press one for its name".format(symRows.size)
        } else {
            packRows = if (query.isEmpty()) {
                // The pick, the pack's icon for this app, ones named like the app, then the rest.
                val word = app.label.lowercase().split(' ').firstOrNull { it.length > 2 } ?: app.label.lowercase()
                val first = (listOfNotNull(pickedPack?.takeIf { it.startsWith("$pack/") }?.substringAfter('/'), packOwn) +
                    packNames.filter { it.contains(word) }.take(30)).distinct()
                first + packNames.filter { it !in first }
            } else packNames.filter { it.lowercase().contains(query) }.sortedBy { if (it.lowercase().startsWith(query)) 0 else 1 }
            packAdapter.notifyDataSetChanged()
            val label = packs.firstOrNull { it.pkg == pack }?.label ?: ""
            status.text = when {
                packNames.isEmpty() -> "Loading $label…"
                packRows.isEmpty() -> "No icons match"
                else -> "%,d icons · %s · long-press one for its name".format(packRows.size, label)
            }
        }
    }

    val chips = linear(this, false) { gravity = Gravity.CENTER_VERTICAL }
    fun showSource(pack: String?) {
        source = pack
        for (i in 0 until chips.childCount) {
            val c = chips.getChildAt(i) as TextView
            val on = c.tag == (pack ?: "")
            c.background = if (on) rounded(pal.accentSoft, dpf(16)) else rippleBg(pal.ripple, dpf(16))
            c.setTextColor(if (on) pal.text else pal.textDim)
        }
        search.hint = if (pack == null) "Search icons: book, camera, music…" else "Search ${packs.firstOrNull { it.pkg == pack }?.label ?: "pack"}…"
        if (pack == null) { grid.adapter = symAdapter; filter(search.text.toString()); return }
        packNames = emptyList(); packOwn = null
        grid.adapter = packAdapter
        filter(search.text.toString())
        val ctx = applicationContext
        Thread {
            val names = IconPacks.allNames(ctx, pack)
            val own = IconPacks.nameFor(ctx, pack, app.component)
            runOnUiThread {
                if (source != pack) return@runOnUiThread
                packNames = names; packOwn = own
                filter(search.text.toString())
            }
        }.start()
    }
    if (packs.isNotEmpty()) {
        fun chip(title: String, pack: String?) = RoomyText(this).label(pal.textDim, 14f, bold = true).apply {
            text = title; tag = pack ?: ""; isSingleLine = true
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnClickListener { if (source != pack) showSource(pack) }
        }
        chips.addView(chip("Symbols", null), llp(WRAP, WRAP).apply { marginEnd = dp(6) })
        packs.forEach { chips.addView(chip(it.label, it.pkg), llp(WRAP, WRAP).apply { marginEnd = dp(6) }) }
        card.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) },
            llp(MATCH, WRAP).apply { bottomMargin = dp(8) })
    }

    search = EditText(this).apply {
        isSingleLine = true
        imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        background = rounded(pal.field, dpf(12))
        setTextColor(pal.text); setHintTextColor(pal.textDim); textSize = 15f; typeface = Fonts.regular
        setPadding(dp(14), dp(10), dp(14), dp(10))
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { filter(s.toString()) }
        })
    }
    card.addView(search, llp(MATCH, WRAP).apply { bottomMargin = dp(8) })
    card.addView(grid, llp(MATCH, dp(300)))
    card.addView(status, llp(MATCH, WRAP))
    showSource(source)
    layer = overlays.show(card, dim = true)
}
