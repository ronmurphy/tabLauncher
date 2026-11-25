package dev.tabdesk

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.text.TextUtils
import android.util.SizeF
import android.view.Gravity
import android.view.MotionEvent
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.Collator
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.math.max

/** Widget host view that lets the desktop steal a long-press (menu / drag) from the widget's own content. */
class TabWidgetView(ctx: Context) : AppWidgetHostView(ctx) {
    var press: PressHelper? = null
    var lastW = 0
    var lastH = 0

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        val p = press ?: return false
        p.handle(e)
        return p.longPressed
    }

    override fun onTouchEvent(e: MotionEvent): Boolean = press?.handle(e) ?: super.onTouchEvent(e)

    override fun requestDisallowInterceptTouchEvent(disallow: Boolean) {
        // A scrolling list inside the widget took over: it's not a long-press.
        if (disallow) press?.cancelLong()
        super.requestDisallowInterceptTouchEvent(disallow)
    }
}

class Widgets(private val act: HomeActivity) {
    companion object {
        const val HOST_ID = 0x7AB
        const val REQ_BIND = 11
        const val REQ_CONFIG = 12
    }

    val mgr: AppWidgetManager = AppWidgetManager.getInstance(act)
    private val host = object : AppWidgetHost(act, HOST_ID) {
        override fun onCreateView(ctx: Context, id: Int, info: AppWidgetProviderInfo?): AppWidgetHostView = TabWidgetView(ctx)
    }
    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun startListening() = try { host.startListening() } catch (e: Exception) { }
    fun stopListening() = try { host.stopListening() } catch (e: Exception) { }

    fun createView(item: DesktopItem): TabWidgetView? {
        val info = mgr.getAppWidgetInfo(item.widgetId) ?: return null
        return (host.createView(act, item.widgetId, info) as TabWidgetView).apply { setPadding(0, 0, 0, 0) }
    }

    fun info(item: DesktopItem): AppWidgetProviderInfo? = mgr.getAppWidgetInfo(item.widgetId)

    /** Provider and name of a placed widget, for backups. */
    fun providerOf(id: Int): Pair<ComponentName, String>? = try {
        mgr.getAppWidgetInfo(id)?.let { it.provider to it.loadLabel(act.packageManager) }
    } catch (e: Exception) { null }

    /**
     * A widget from a backup: keep [id] if it's still that widget here; otherwise bind a fresh one,
     * which Android allows without asking only if you've said "always allow" before. -1 = add it again by hand.
     */
    fun restore(id: Int, provider: ComponentName?): Int {
        val cur = if (id != -1 && id in host.appWidgetIds) mgr.getAppWidgetInfo(id) else null
        if (cur != null && (provider == null || cur.provider == provider)) return id
        if (provider == null) return -1
        val fresh = host.allocateAppWidgetId()
        val ok = try { mgr.bindAppWidgetIdIfAllowed(fresh, provider) } catch (e: Exception) { false }
        if (!ok) { delete(fresh); return -1 }
        return fresh
    }

    fun delete(id: Int) { if (id != -1) try { host.deleteAppWidgetId(id) } catch (e: Exception) { } }

    /** Free widget ids the host still holds but no desktop item uses (e.g. an abandoned add). */
    fun cleanup(used: Set<Int>) {
        val pending = Prefs.pendingWidget
        // Widgets in the "undo import" copy stay bound, so undoing brings them back.
        val held = Backup.heldWidgets(act)
        host.appWidgetIds.filter { it !in used && it != pending && it !in held }.forEach { delete(it) }
    }

    fun reportSize(v: TabWidgetView, w: Int, h: Int) {
        if (w <= 0 || h <= 0 || (w == v.lastW && h == v.lastH)) return
        v.lastW = w; v.lastH = h
        val d = act.resources.displayMetrics.density
        try { v.updateAppWidgetSize(Bundle(), listOf(SizeF(w / d, h / d))) } catch (e: Exception) { }
    }

    // ------------------------------------------------------------ sizing

    fun spansFor(info: AppWidgetProviderInfo): Pair<Int, Int> {
        val d = act.desktop
        if (info.targetCellWidth > 0 && info.targetCellHeight > 0)
            return info.targetCellWidth.coerceIn(1, d.cols) to info.targetCellHeight.coerceIn(1, d.rows)
        val sx = ceil((info.minWidth + act.dp(10)) / d.cellW).toInt().coerceIn(1, d.cols)
        val sy = ceil((info.minHeight + act.dp(10)) / d.cellH).toInt().coerceIn(1, d.rows)
        return sx to sy
    }

    /** min spanX, min spanY, axes bitmask (1 = horizontal, 2 = vertical). */
    fun resizeLimits(item: DesktopItem, cellW: Float, cellH: Float): Triple<Int, Int, Int> {
        val info = info(item) ?: return Triple(1, 1, 3)
        val axes = (if (info.resizeMode and AppWidgetProviderInfo.RESIZE_HORIZONTAL != 0) 1 else 0) or
            (if (info.resizeMode and AppWidgetProviderInfo.RESIZE_VERTICAL != 0) 2 else 0)
        val mw = if (info.minResizeWidth > 0) info.minResizeWidth else info.minWidth
        val mh = if (info.minResizeHeight > 0) info.minResizeHeight else info.minHeight
        return Triple(max(1, ceil(mw / cellW).toInt()), max(1, ceil(mh / cellH).toInt()), axes)
    }

    fun canResize(item: DesktopItem) = (info(item)?.resizeMode ?: 0) != AppWidgetProviderInfo.RESIZE_NONE

    fun canReconfigure(item: DesktopItem): Boolean {
        val i = info(item) ?: return false
        return i.configure != null && i.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_RECONFIGURABLE != 0
    }

    fun reconfigure(item: DesktopItem) {
        try { host.startAppWidgetConfigureActivityForResult(act, item.widgetId, 0, 0, null) } catch (e: Exception) { act.toast("Can't open widget settings") }
    }

    // ------------------------------------------------------------ add flow: allocate → bind → configure → place

    fun begin(provider: AppWidgetProviderInfo, cell: IntArray?, toDock: Boolean = false) {
        val id = host.allocateAppWidgetId()
        Prefs.pendingWidget = id
        Prefs.pendingCell = cell
        Prefs.pendingDock = toDock
        val ok = try { mgr.bindAppWidgetIdIfAllowed(id, provider.profile, provider.provider, null) } catch (e: Exception) { false }
        if (ok) afterBind(id)
        else act.startActivityForResult(
            Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, provider.provider)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, provider.profile),
            REQ_BIND,
        )
    }

    private fun afterBind(id: Int) {
        val info = mgr.getAppWidgetInfo(id) ?: return cancel(id)
        val needsConfig = info.configure != null &&
            info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL == 0
        if (!needsConfig) return place(id)
        try { host.startAppWidgetConfigureActivityForResult(act, id, 0, REQ_CONFIG, null) } catch (e: Exception) { cancel(id) }
    }

    fun onResult(req: Int, res: Int, data: Intent?): Boolean {
        if (req != REQ_BIND && req != REQ_CONFIG) return false
        val id = data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)?.takeIf { it != -1 } ?: Prefs.pendingWidget
        if (id == -1) return true
        if (res == Activity.RESULT_OK) { if (req == REQ_BIND) afterBind(id) else place(id) } else cancel(id)
        return true
    }

    private fun place(id: Int) {
        val info = mgr.getAppWidgetInfo(id) ?: return cancel(id)
        val (sx, sy) = spansFor(info)
        val cell = Prefs.pendingCell
        val toDock = Prefs.pendingDock
        Prefs.pendingWidget = -1; Prefs.pendingCell = null; Prefs.pendingDock = false
        if (toDock) act.dock.addWidget(id) else act.desktop.addWidget(id, sx, sy, cell)
    }

    private fun cancel(id: Int) {
        delete(id)
        Prefs.pendingWidget = -1; Prefs.pendingCell = null; Prefs.pendingDock = false
    }

    // ------------------------------------------------------------ picker

    fun showPicker(cell: IntArray?, toDock: Boolean = false) {
        val pal = act.pal
        val pm = act.packageManager
        val providers = mgr.getInstalledProvidersForProfile(Process.myUserHandle())
            .filter { it.widgetCategory and AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN != 0 }
        val collator = Collator.getInstance()
        val groups = providers.groupBy { it.provider.packageName }
            .map { (pkg, list) ->
                val label = try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (e: Exception) { pkg }
                Triple(pkg, label, list.sortedBy { it.loadLabel(pm) })
            }
            .sortedWith { a, b -> collator.compare(a.second, b.second) }

        val card = act.overlays.dialogCard(act.dp(520), if (toDock) "Add a widget to the dock" else "Add a widget")
        val content = linear(act, true) { setPadding(0, 0, 0, act.dp(8)) }
        if (groups.isEmpty()) content.addView(TextView(act).label(pal.textDim, 14f).apply { text = "No widgets installed."; setPadding(act.dp(8), act.dp(16), act.dp(8), act.dp(16)) })
        lateinit var layer: Overlays.Layer
        for ((pkg, label, list) in groups) {
            val head = linear(act, false) { gravity = Gravity.CENTER_VERTICAL; setPadding(act.dp(6), act.dp(14), act.dp(6), act.dp(6)) }
            head.addView(IconView(act).apply { bitmap = Apps.firstOf(pkg)?.icon }, llp(act.dp(22), act.dp(22)))
            head.addView(TextView(act).label(pal.textDim, 13f, bold = true).apply { text = label; setPadding(act.dp(10), 0, 0, 0) })
            content.addView(head)
            for (p in list) {
                val row = linear(act, false) {
                    gravity = Gravity.CENTER_VERTICAL
                    background = rippleBg(pal.ripple, act.dpf(14), pal.field)
                    setPadding(act.dp(10), act.dp(10), act.dp(14), act.dp(10))
                }
                val preview = ImageView(act).apply { scaleType = ImageView.ScaleType.FIT_CENTER; adjustViewBounds = true }
                row.addView(preview, llp(act.dp(132), act.dp(84)))
                val (sx, sy) = spansFor(p)
                val text = linear(act, true) { setPadding(act.dp(14), 0, 0, 0) }
                text.addView(TextView(act).label(pal.text, 15f).apply { this.text = p.loadLabel(pm); maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
                text.addView(TextView(act).label(pal.textDim, 12.5f).apply { this.text = "$sx × $sy" + (p.loadDescription(act)?.let { "  ·  $it" } ?: ""); maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
                row.addView(text, llp(0, WRAP, 1f))
                row.setOnClickListener { act.overlays.dismiss(layer); begin(p, cell, toDock) }
                content.addView(row, llp(MATCH, WRAP).apply { bottomMargin = act.dp(6) })
                loadPreview(p, preview)
            }
        }
        val scroll = ScrollView(act).apply { isVerticalScrollBarEnabled = false; addView(content) }
        card.addView(scroll, llp(MATCH, WRAP))
        layer = act.overlays.show(card, dim = true)
    }

    private fun loadPreview(p: AppWidgetProviderInfo, into: ImageView) {
        val dpi = act.resources.displayMetrics.densityDpi
        exec.execute {
            val d: Drawable? = try { p.loadPreviewImage(act, dpi) ?: p.loadIcon(act, dpi) } catch (e: Exception) { null }
            main.post { into.setImageDrawable(d) }
        }
    }
}
