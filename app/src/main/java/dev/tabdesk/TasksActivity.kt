package dev.tabdesk

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.window.OnBackInvokedDispatcher
import dev.tabdesk.customize.Customize
import dev.tabdesk.helper.HelperClient
import java.util.Locale
import kotlin.math.max

/**
 * TabDesk Tasks: what each app is using right now (CPU over the last moment, memory) and what the tablet has left,
 * looked at every few seconds while the window shows. Android lets an app see only its own processes, so the
 * numbers come from TabDesk's helper (HelperClient.usage). It runs in the home screen's process, which holds the
 * one connection to the helper.
 */
class TasksActivity : TabActivity() {
    companion object {
        private const val SORT_CPU = 0
        private const val SORT_MEMORY = 1
        /** How long each look takes, and the pause between looks. */
        private const val SAMPLE_MS = 1500L
        private const val PAUSE_MS = 1500L
    }

    /** One row: an app (its processes added up), or Android's own processes. */
    private class Row(val key: String, val pkg: String?, val label: String, val icon: Drawable?,
                      var cpu: Int, var cpuSeconds: Long, var memKb: Long, val names: LinkedHashSet<String>)

    /** An app's name and icon, looked up once. */
    private class Who(val pkg: String?, val label: String, val icon: Drawable?)

    private lateinit var sp: SharedPreferences
    private val main = Handler(Looper.getMainLooper())
    private val who = HashMap<String, Who>()
    private var rows: List<Row> = emptyList()
    private var looking = false
    private var resumed = false
    private var themeKey = ""

    private var sort: Int
        get() = sp.getInt("sort", SORT_CPU)
        set(v) = sp.edit().putInt("sort", v).apply()

    private lateinit var rootView: FrameLayout
    private lateinit var content: LinearLayout
    private lateinit var list: ListView
    private lateinit var cpuTile: Pair<TextView, TextView>
    private lateinit var memTile: Pair<TextView, TextView>
    private lateinit var batteryTile: Pair<TextView, TextView>
    private lateinit var message: LinearLayout
    private lateinit var messageText: TextView
    private var messageButton: View? = null

    private val helperChanged: () -> Unit = { if (resumed) { if (HelperClient.connected) look() else showMissing() } }

    // ------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        Fonts.load(this)
        sp = getSharedPreferences("tasks", Context.MODE_PRIVATE)
        window.setDecorFitsSystemWindows(false)
        pal = Palette.resolve(this)
        themeKey = themeKeyNow()
        buildUi()
        if (Build.VERSION.SDK_INT >= 33)
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { handleBack() }
        HelperClient.ensure(this)
    }

    override fun onResume() {
        super.onResume()
        if (themeKeyNow() != themeKey) { recreate(); return }
        resumed = true
        HelperClient.listeners += helperChanged
        showSystem()
        if (HelperClient.connected) look() else showMissing()
    }

    override fun onPause() {
        super.onPause()
        // Looking costs a little battery itself, so only while the window shows.
        resumed = false
        HelperClient.listeners -= helperChanged
        main.removeCallbacksAndMessages(null)
    }

    @Deprecated("Android 12 only; later versions use the OnBackInvokedCallback")
    override fun onBackPressed() = handleBack()

    private fun handleBack() { if (!overlays.dismissTop()) finish() }

    private fun themeKeyNow() = "${Prefs.themeMode}/${Prefs.accent}/${Prefs.font}/" +
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)

    // ------------------------------------------------------------ the window

    private fun buildUi() {
        overlays = Overlays(this)
        rootView = FrameLayout(this).apply { setBackgroundColor(pal.surfaceSolid) }
        content = linear(this, true)
        rootView.addView(content, flp(MATCH, MATCH))

        val bar = linear(this, false) { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(18), 0, dp(6), 0) }
        bar.addView(TextView(this).label(pal.text, 17f, bold = true).apply { text = "What's running"; isSingleLine = true },
            llp(0, WRAP, 1f))
        bar.addView(symbolButton("sort", "Sort") { v -> sortMenu(v) }, llp(dp(44), dp(44)))
        content.addView(bar, llp(MATCH, dp(56)))
        content.addView(Hairline(this).apply { setBackgroundColor(pal.divider) }, llp(MATCH, 1))

        // The tablet as a whole: CPU, memory, battery.
        val tiles = linear(this, false) { setPadding(dp(12), dp(12), dp(12), dp(4)) }
        fun tile(title: String): Pair<TextView, TextView> {
            val big = TextView(this).label(pal.text, 20f, bold = true).apply { isSingleLine = true }
            val small = TextView(this).label(pal.textDim, 12.5f).apply { isSingleLine = true }
            tiles.addView(linear(this, true) {
                background = rounded(pal.field, dpf(14))
                setPadding(dp(14), dp(10), dp(14), dp(10))
                addView(TextView(this@TasksActivity).label(pal.textDim, 12.5f).apply { text = title }, llp(MATCH, WRAP))
                addView(big, llp(MATCH, WRAP))
                addView(small, llp(MATCH, WRAP))
            }, llp(0, WRAP, 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
            return big to small
        }
        cpuTile = tile("CPU")
        memTile = tile("Memory")
        batteryTile = tile("Battery")
        content.addView(tiles, llp(MATCH, WRAP))

        val body = FrameLayout(this)
        list = ListView(this).apply {
            divider = null
            selector = android.graphics.drawable.ColorDrawable(0)
            clipToPadding = false
            setPadding(dp(8), dp(8), dp(8), dp(16))
            adapter = Adapter()
        }
        body.addView(list, flp(MATCH, MATCH))
        messageText = TextView(this).label(pal.textDim, 15f).apply {
            gravity = Gravity.CENTER; setLineSpacing(0f, 1.15f); setPadding(dp(24), 0, dp(24), dp(12))
        }
        message = linear(this, true) { gravity = Gravity.CENTER; addView(messageText, llp(MATCH, WRAP)); visibility = View.GONE }
        body.addView(message, flp(MATCH, MATCH))
        content.addView(body, llp(MATCH, 0, 1f))

        rootView.addView(overlays, flp(MATCH, MATCH))
        rootView.setOnApplyWindowInsetsListener { _, ins ->
            val b = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            content.setPadding(b.left, b.top, b.right, b.bottom)
            overlays.setInsets(b, 0)
            WindowInsets.CONSUMED
        }
        setContentView(rootView)
        window.insetsController?.setSystemBarsAppearance(
            if (pal.dark) 0 else WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
    }

    private fun showMessage(text: String?, button: String? = null, action: (() -> Unit)? = null) {
        message.visibility = if (text != null) View.VISIBLE else View.GONE
        list.visibility = if (text != null) View.INVISIBLE else View.VISIBLE
        messageText.text = text
        messageButton?.let { message.removeView(it) }; messageButton = null
        if (button != null && action != null)
            messageButton = overlays.button(button, true, action).also { message.addView(it, llp(WRAP, dp(40))) }
    }

    private fun showMissing() {
        cpuTile.first.text = "–"; cpuTile.second.text = "needs the helper"
        showMessage(
            if (HelperClient.starting) "Starting TabDesk's helper…"
            else "Android only lets an app see its own processes, so TabDesk Tasks asks TabDesk's helper. " +
                "It isn't running" + (HelperClient.problem?.let { ": ${it.replaceFirstChar { c -> c.lowercase() }}." } ?: "."),
            if (HelperClient.starting) null else "Set up the helper",
        ) { safeStart(HomeActivity.customizeIntent(this, Customize.HELPER)) }
    }

    // ------------------------------------------------------------ looking

    /** One look at what's running; the next follows after a pause, while the window shows. */
    private fun look() {
        if (looking || !resumed) return
        looking = true
        HelperClient.usage(SAMPLE_MS) { u ->
            looking = false
            if (!resumed) return@usage
            if (u == null) { if (!HelperClient.connected) showMissing() }
            else show(u)
            showSystem()
            main.removeCallbacksAndMessages(null)
            main.postDelayed({ look() }, PAUSE_MS)
        }
    }

    private fun show(u: HelperClient.Usage) {
        cpuTile.first.text = percent(u.busy)
        cpuTile.second.text = "${u.cpus} CPUs"
        val byPkg = LinkedHashMap<String, Row>()
        for (a in u.apps) {
            val w = whoIs(a)
            // An app's throwaway-uid processes (and TabDesk's helper) join the app's own row.
            val key = w.pkg ?: a.owner
            val r = byPkg.getOrPut(key) { Row(key, w.pkg, w.label, w.icon, 0, 0, 0, LinkedHashSet()) }
            r.cpu += a.cpu; r.cpuSeconds += a.cpuSeconds; r.memKb += a.memKb; r.names += a.names
        }
        // While a menu is open over the list, the rows stay as they are, so what it's about doesn't move.
        if (overlays.isOpen) return
        setRows(byPkg.values.toList())
        showMessage(if (rows.isEmpty()) "Nothing to show just now." else null)
    }

    private fun setRows(r: List<Row>) {
        rows = r.sortedWith(
            if (sort == SORT_MEMORY) compareByDescending<Row> { it.memKb }.thenByDescending { it.cpu }
            else compareByDescending<Row> { it.cpu }.thenByDescending { it.memKb })
        (list.adapter as BaseAdapter).notifyDataSetChanged()
    }

    /** Memory and battery, which TabDesk can read itself. */
    private fun showSystem() {
        val mi = ActivityManager.MemoryInfo().also { getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        memTile.first.text = size((mi.totalMem - mi.availMem) / 1024)
        memTile.second.text = "used of " + size(mi.totalMem / 1024)
        val b = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = b?.let { it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / max(1, it.getIntExtra(BatteryManager.EXTRA_SCALE, 100)) }
        val status = b?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        batteryTile.first.text = if (level != null && level >= 0) "$level%" else "–"
        batteryTile.second.text = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
            BatteryManager.BATTERY_STATUS_FULL -> "full"
            else -> "on battery"
        }
    }

    /** Which app a group of processes is, by its uid or the package its name starts with. */
    private fun whoIs(a: HelperClient.AppUsage): Who = who.getOrPut(a.owner) {
        val pm = packageManager
        val pkg = when {
            a.owner == "android" -> null
            a.owner.startsWith("p") -> a.owner.drop(1)
            else -> a.owner.drop(1).toIntOrNull()?.let { uid ->
                val pkgs = pm.getPackagesForUid(uid).orEmpty()
                // Several packages can share a uid; the one its processes are named after is the one to show.
                pkgs.firstOrNull { p -> a.names.any { it == p || it.startsWith("$p:") } } ?: pkgs.firstOrNull()
            }
        }
        if (pkg == null) return@getOrPut Who(null, if (a.owner == "android") "Android system" else a.names.first(), null)
        // TabDesk's own launcher entries are Files and Tasks, so its row takes the app's own name and icon.
        if (pkg == packageName) return@getOrPut Who(pkg, getString(R.string.app_name), pm.getApplicationIcon(applicationInfo))
        val app = Apps.all.firstOrNull { it.pkg == pkg }
        val label = app?.label ?: try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { a.names.first() }
        val icon = app?.icon?.let { BitmapDrawable(resources, it) } ?: try { pm.getApplicationIcon(pkg) } catch (_: Exception) { null }
        Who(pkg, label, icon)
    }

    // ------------------------------------------------------------ rows

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(i: Int) = rows[i]
        override fun getItemId(i: Int) = i.toLong()
        override fun getView(i: Int, convertView: View?, parent: ViewGroup): View =
            ((convertView as? RowView) ?: RowView()).also { it.bind(rows[i]) }
    }

    private inner class RowView : LinearLayout(this@TasksActivity) {
        val img = ImageView(context)
        val glyph = TextView(context).symbolText("android", 26f, pal.textDim)
        val name = TextView(context).label(pal.text, 14.5f).apply { isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END }
        val info = TextView(context).label(pal.textDim, 12.5f).apply { isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END }
        val cpu = RoomyText(context).label(pal.text, 15f, bold = true).apply { gravity = Gravity.END }
        val mem = RoomyText(context).label(pal.textDim, 12.5f).apply { gravity = Gravity.END }
        var row: Row? = null

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(58)
            setPadding(dp(12), dp(6), dp(14), dp(6))
            background = rippleBg(pal.ripple, dpf(12))
            addView(FrameLayout(context).apply {
                addView(img, flp(dp(36), dp(36), Gravity.CENTER))
                addView(glyph, flp(dp(36), dp(36), Gravity.CENTER))
            }, llp(dp(40), dp(40)))
            addView(linear(context, true) {
                addView(name, llp(MATCH, WRAP))
                addView(info, llp(MATCH, WRAP))
            }, llp(0, WRAP, 1f).apply { marginStart = dp(12); marginEnd = dp(12) })
            addView(linear(context, true) {
                gravity = Gravity.END
                addView(cpu, llp(WRAP, WRAP))
                addView(mem, llp(WRAP, WRAP))
            }, llp(WRAP, WRAP))
            setOnClickListener { row?.let { rowMenu(it, this) } }
        }

        fun bind(r: Row) {
            row = r
            name.text = r.label
            info.text = describe(r)
            cpu.text = percent(r.cpu)
            cpu.setTextColor(if (r.cpu >= 100) pal.accent else pal.text)
            mem.text = size(r.memKb)
            img.setImageDrawable(r.icon)
            img.visibility = if (r.icon != null) View.VISIBLE else View.GONE
            glyph.visibility = if (r.icon == null) View.VISIBLE else View.GONE
        }
    }

    /** The line under an app's name: what its processes are, and the CPU time they've used in all. */
    private fun describe(r: Row): String {
        val parts = ArrayList<String>()
        when {
            r.pkg == null -> parts += "Android's own processes"
            // Tasks runs in the home screen's process, so it counts there.
            r.pkg == packageName -> parts += listOf(packageName to "home screen", "$packageName:files" to "Files", "tabdesk_helper" to "helper")
                .filter { (n, _) -> n in r.names }.joinToString(", ") { it.second }
            r.names.size > 1 -> parts += "${r.names.size} processes"
        }
        parts += "CPU time " + duration(r.cpuSeconds)
        return parts.joinToString("  ·  ")
    }

    private fun rowMenu(r: Row, v: View) {
        val pkg = r.pkg
        if (pkg == null) { toast("Android itself: the system, its services and the parts that run the hardware"); return }
        val entries = ArrayList<MenuEntry>()
        packageManager.getLaunchIntentForPackage(pkg)?.let { launch ->
            if (pkg != packageName) entries += MenuEntry("Open", icon = SymbolDrawable(this, "open_in_new", pal.text)) { safeStart(launch) }
        }
        val tasks = HelperClient.tasks.filter { it.pkg == pkg }
        if (tasks.isNotEmpty() && pkg != packageName)
            entries += MenuEntry(if (tasks.size == 1) "Close its window" else "Close its ${tasks.size} windows",
                icon = SymbolDrawable(this, "close", pal.text)) {
                tasks.forEach { HelperClient.close(it.id) }
                toast("Closed ${r.label}")
            }
        entries += MenuEntry("App info (Force stop is there)", icon = SymbolDrawable(this, "info", pal.text)) {
            safeStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))
        }
        overlays.menu(v.rectIn(rootView), entries, r.label)
    }

    private fun sortMenu(v: View) {
        fun mark(on: Boolean) = if (on) SymbolDrawable(this, "check", pal.text) else null
        overlays.menu(v.rectIn(rootView), listOf(
            MenuEntry("Busiest first", icon = mark(sort == SORT_CPU)) { sort = SORT_CPU; setRows(rows) },
            MenuEntry("Most memory first", icon = mark(sort == SORT_MEMORY)) { sort = SORT_MEMORY; setRows(rows) },
        ), "Sort")
    }

    // ------------------------------------------------------------ numbers

    /** Per mille of the whole device as a percentage: "0%", "0.4%", "12%". */
    private fun percent(perMille: Int) = when {
        perMille <= 0 -> "0%"
        perMille < 100 -> String.format(Locale.getDefault(), "%.1f%%", perMille / 10f)
        else -> "${perMille / 10}%"
    }

    private fun size(kb: Long) = when {
        kb >= 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f GB", kb / (1024f * 1024f))
        else -> "${max(1, kb / 1024)} MB"
    }

    private fun duration(s: Long) = when {
        s < 60 -> "${s}s"
        s < 3600 -> "${s / 60} min"
        else -> String.format(Locale.getDefault(), "%.1f h", s / 3600f)
    }
}
