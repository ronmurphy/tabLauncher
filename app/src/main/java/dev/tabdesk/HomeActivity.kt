package dev.tabdesk

import dev.tabdesk.helper.HelperClient
import dev.tabdesk.helper.Setup
import dev.tabdesk.customize.Customize
import dev.tabdesk.icons.*
import android.app.Activity
import android.app.ActivityOptions
import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.content.res.Configuration
import android.graphics.Insets
import android.graphics.Rect
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.Toast
import android.window.OnBackInvokedDispatcher

class HomeActivity : TabActivity() {
    companion object {
        private const val EXTRA_CUSTOMIZE = "dev.tabdesk.customize"

        /**
         * Opens TabDesk with Customize showing ([tab], or where it was last). It's a Home intent, so TabDesk opens as
         * the home screen even when it isn't running; started directly, it would be an ordinary app on top of the
         * home screen, and gone after an update.
         */
        fun customizeIntent(ctx: Context, tab: Int = -1): Intent = homeIntent(ctx).putExtra(EXTRA_CUSTOMIZE, tab)

        /** TabDesk as the home screen. */
        fun homeIntent(ctx: Context): Intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            .setPackage(ctx.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        /** TabDesk's home screen is in front (resumed). */
        @Volatile var inFront = false
            private set
    }

    lateinit var root: FrameLayout; private set
    lateinit var desktop: DesktopLayout; private set
    lateinit var taskbar: Taskbar; private set
    lateinit var widgets: Widgets; private set
    lateinit var dock: Dock; private set
    private lateinit var startLayer: FrameLayout
    lateinit var startMenu: StartMenu; private set
    val la: LauncherApps by lazy { getSystemService(LauncherApps::class.java) }

    private var bars: Insets = Insets.NONE
    private var ime = 0
    private var isResumedNow = false
    private var nightFlags = 0
    private var desktopRev = 0
    private val main = Handler(Looper.getMainLooper())

    private val appsListener: () -> Unit = { onAppsChanged() }
    private val badgeListener: () -> Unit = { onBadgesChanged() }
    private val setupListener: () -> Unit = { Customize.refreshIf(this, Customize.HELPER) }
    private val tasksListener: () -> Unit = { taskbar.bindRecents(force = false); taskbar.bindHelper(); Windows.track(this) }
    private val updatesListener: () -> Unit = { Customize.refreshIf(this, Customize.ABOUT) }
    private val agentListener: () -> Unit = { taskbar.bindAgent(); Customize.refreshIf(this, Customize.AGENT) }
    /** Each call the agent makes: its Pixel Person's pose, and its name. */
    private val personListener: () -> Unit = { taskbar.bindAgent() }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            if (level >= 0) taskbar.battery.update(level * 100 / scale, charging)
        }
    }

    /**
     * TabDesk Files asks for a folder to be shown as a Folder View: it runs in its own process, which mustn't change
     * TabDesk's settings, so the home screen does it. Only TabDesk can send this ([FilesActivity.ADD_PERMISSION]).
     */
    private val folderViewReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val path = i.getStringExtra(FilesActivity.EXTRA_PATH)?.takeIf { java.io.File(it).isDirectory } ?: return
            if (i.getBooleanExtra(FilesActivity.EXTRA_DOCK, false)) dock.addFolderView(path, show = false)
            else desktop.addFolderView(path, null)
        }
    }

    private val wallpaperListener = WallpaperManager.OnColorsChangedListener { _, which ->
        if (which and WallpaperManager.FLAG_SYSTEM == 0) return@OnColorsChangedListener
        updateSystemBars()
        // Desktop labels and some icon colours follow whether the wallpaper is light or dark.
        if (wallpaperIsLight() != lightWallpaper) rebuildUi()
    }

    /** True if the wallpaper is light enough that dark text reads better on it. */
    var lightWallpaper = false; private set

    private fun wallpaperIsLight(): Boolean = try {
        val colors = WallpaperManager.getInstance(this).getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
        colors != null && (colors.colorHints and WallpaperColors.HINT_SUPPORTS_DARK_TEXT) != 0
    } catch (e: Exception) { false }

    // ------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        Fonts.load(this)
        Apps.init(this)
        window.setDecorFitsSystemWindows(false)
        window.isNavigationBarContrastEnforced = false
        nightFlags = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        desktopRev = Prefs.desktopRev
        widgets = Widgets(this)
        buildUi()
        Apps.listeners += appsListener
        Badges.listeners += badgeListener
        Setup.onChange = setupListener
        HelperClient.listeners += setupListener
        HelperClient.listeners += tasksListener
        dev.tabdesk.agent.Agent.init(this)
        dev.tabdesk.agent.Agent.listeners += agentListener
        dev.tabdesk.agent.AgentPerson.listeners += personListener
        if (Setup.ready(this)) HelperClient.ensure(this)
        Updates.onChange = updatesListener
        Updates.afterStart(this)
        customizeTab(intent)?.let { tab -> root.post { showSettings(tab.takeIf { it >= 0 }) } }
        Apps.load()
        registerReceiver(folderViewReceiver, IntentFilter(FilesActivity.ACTION_ADD_FOLDER_VIEW), FilesActivity.ADD_PERMISSION, null,
            Context.RECEIVER_NOT_EXPORTED)
        // Android 13 and later; Android 12 calls onBackPressed instead.
        if (Build.VERSION.SDK_INT >= 33)
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { handleBack() }
        WallpaperManager.getInstance(this).addOnColorsChangedListener(wallpaperListener, main)
    }

    override fun onStart() {
        super.onStart()
        widgets.startListening()
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    override fun onResume() {
        super.onResume()
        isResumedNow = true
        inFront = true
        if (Prefs.desktopRev != desktopRev) { desktopRev = Prefs.desktopRev; desktop.reload() }
        ensureFont()
        Recents.refresh(this) { taskbar.bindRecents() }
        Customize.refreshIf(this, Customize.HELPER)
        Updates.resumed(this)
        FolderViews.resumed(this)
    }

    override fun onPause() {
        super.onPause()
        isResumedNow = false
        inFront = false
        Previews.hide()
        overlays.dismissAbove()
    }

    override fun onStop() {
        super.onStop()
        // An app opened from it (or anything else) is in front: the dock is closed when you come back.
        dock.close()
        widgets.stopListening()
        try { unregisterReceiver(batteryReceiver) } catch (e: Exception) { }
    }

    override fun onDestroy() {
        if (Setup.onChange === setupListener) Setup.onChange = null
        if (Updates.onChange === updatesListener) Updates.onChange = null
        HelperClient.listeners -= setupListener
        HelperClient.listeners -= tasksListener
        dev.tabdesk.agent.Agent.listeners -= agentListener
        dev.tabdesk.agent.AgentPerson.listeners -= personListener
        Apps.listeners -= appsListener
        Badges.listeners -= badgeListener
        WallpaperManager.getInstance(this).removeOnColorsChangedListener(wallpaperListener)
        try { unregisterReceiver(folderViewReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == android.service.quicksettings.TileService.ACTION_QS_TILE_PREFERENCES) {
            overlays.dismissAll(); closeStart(false)
            root.post { Customize.show(this, restoreScroll = false, tab = Customize.SYSTEM) }
            return
        }
        customizeTab(intent)?.let { tab ->   // TabDesk's app icon, or the "new version" notification
            overlays.dismissAll()
            root.post { showSettings(tab.takeIf { it >= 0 }) }
            return
        }
        if (intent.action == Intent.ACTION_MAIN) {
            // Home pressed: tidy up whatever is open.
            val animate = isResumedNow
            overlays.dismissAll()
            desktop.endResize()
            closeStart(animate)
        }
    }

    override fun onConfigurationChanged(c: Configuration) {
        super.onConfigurationChanged(c)
        // Customize comes back after a rotation, on the same tab, laid out for the new shape.
        val reopen = Customize.isShowing(this)
        overlays.dismissAll()
        val night = c.uiMode and Configuration.UI_MODE_NIGHT_MASK
        if (night != nightFlags && Prefs.themeMode == 0) { nightFlags = night; rebuildUi() }
        else { nightFlags = night; root.requestApplyInsets() }
        if (reopen) root.post { Customize.show(this, restoreScroll = true) }
        // Once the taskbar has its new place: snapped windows fit the turned screen.
        root.postDelayed({ Windows.refit(this) }, 300)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (widgets.onResult(requestCode, resultCode, data)) return
        if (Backup.onResult(this, requestCode, resultCode, data)) return
        if (IconThemes.onResult(this, requestCode, resultCode, data)) return
        if (Fonts.onResult(this, requestCode, resultCode, data)) return
        if (requestCode == ScreenTimeout.REQ_PERMISSION) { refreshSettings(); return }
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        if (requestCode == Setup.REQ_NOTIFY) Setup.onNotifyPermission(this, results.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED)
        else super.onRequestPermissionsResult(requestCode, permissions, results)
    }

    // ------------------------------------------------------------ UI

    private fun buildUi() {
        pal = Palette.resolve(this)
        lightWallpaper = wallpaperIsLight()
        if (Icons.update(pal, lightWallpaper) && Apps.loaded) Apps.load()
        root = FrameLayout(this)

        desktop = DesktopLayout(this)
        root.addView(desktop, flp(MATCH, MATCH))

        startLayer = FrameLayout(this).apply {
            visibility = View.GONE
            setOnClickListener { closeStart() }
        }
        startMenu = StartMenu(this)
        startLayer.addView(startMenu, flp(dp(400), dp(600)))
        root.addView(startLayer, flp(MATCH, MATCH))

        taskbar = Taskbar(this)
        registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { batteryReceiver.onReceive(this, it) }
        root.addView(taskbar, flp(MATCH, dp(Prefs.taskbarDp), android.view.Gravity.BOTTOM))

        overlays = Overlays(this)
        root.addView(overlays, flp(MATCH, MATCH))
        dock = Dock(this)

        root.setOnApplyWindowInsetsListener { _, ins -> applyInsets(ins); WindowInsets.CONSUMED }
        setContentView(root)
        updateSystemBars()
        applyStatusBar()
        if (Apps.loaded) onAppsChanged()
    }

    fun rebuildUi() {
        val wasStart = startMenu.isOpen
        buildUi()
        root.requestApplyInsets()
        if (wasStart) openStart()
    }

    /** Theme/accent/taskbar size changed in settings: rebuild and reopen the panel where it was. */
    fun applyThemeChange() {
        overlays.dismissAll()
        rebuildUi()
        root.post { Customize.show(this, restoreScroll = true) }
    }

    /** Hide the status bar while on the desktop (the taskbar has the clock and battery). Swipe down from the top still shows it. */
    fun applyStatusBar() {
        val c = window.insetsController ?: return
        c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (Prefs.hideStatus) c.hide(WindowInsets.Type.statusBars()) else c.show(WindowInsets.Type.statusBars())
    }

    private fun applyInsets(ins: WindowInsets) {
        // With the status bar hidden, it only shows for a moment (a swipe down, or a dialog above floating windows
        // holding the keyboard): the desktop keeps its size rather than shuffling icons under it.
        val barTypes = if (Prefs.hideStatus) WindowInsets.Type.navigationBars() or WindowInsets.Type.captionBar()
            else WindowInsets.Type.systemBars()
        bars = ins.getInsets(barTypes or WindowInsets.Type.displayCutout())
        ime = ins.getInsets(WindowInsets.Type.ime()).bottom
        taskbar.applyInsets(bars)
        desktop.setPadding(bars.left + dp(6), bars.top + dp(6), bars.right + dp(6), dp(Prefs.taskbarDp) + bars.bottom + dp(4))
        val wm = windowManager.currentWindowMetrics.bounds
        startMenu.position(wm.width(), wm.height(), bars, ime)
        overlays.setInsets(bars, ime)
    }

    private fun updateSystemBars() {
        val darkText = wallpaperIsLight()
        var flags = 0
        if (darkText) flags = flags or WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
        if (!pal.dark) flags = flags or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(flags,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
    }

    private fun onAppsChanged() {
        seedIfNeeded()
        taskbar.bindPins()
        desktop.refreshApps()
        if (startMenu.isOpen) startMenu.refresh()
        widgets.cleanup(desktop.widgetIds() + dock.widgetIds())
    }

    fun onBadgesChanged() {
        taskbar.refreshDots()
        desktop.refreshDots()
        if (startMenu.isOpen) startMenu.refresh()
    }

    /** First launch: put a few familiar apps on the taskbar and desktop. */
    private fun seedIfNeeded() {
        if (Prefs.seeded || Apps.all.isEmpty()) return
        Prefs.seeded = true
        Prefs.pins = listOf(
            "com.android.chrome", "com.google.android.apps.nbu.files", "com.google.android.gm",
            "com.google.android.youtube", "com.google.android.apps.photos", "com.android.vending",
        ).mapNotNull { Apps.firstOf(it)?.key }
        listOf(
            "com.anthropic.claude", "com.google.android.calendar", "com.google.android.apps.maps",
            "com.zui.camera", "com.google.android.calculator", "com.android.settings",
        ).mapNotNull { Apps.firstOf(it)?.key }.forEach { desktop.addApp(it) }
    }

    // ------------------------------------------------------------ start menu

    fun toggleStart() = if (startMenu.isOpen) closeStart() else openStart()

    fun openStart() {
        if (startMenu.isOpen) return
        overlays.dismissAll()
        desktop.endResize()
        startLayer.visibility = View.VISIBLE
        startMenu.open()
        taskbar.setStartActive(true)
    }

    fun closeStart(animate: Boolean = true) {
        if (!startMenu.isOpen && startLayer.visibility != View.VISIBLE) return
        hideIme()
        taskbar.setStartActive(false)
        startMenu.close(animate) { startLayer.visibility = View.GONE }
    }

    fun hideStartForDrag() {
        hideIme()
        startLayer.visibility = View.INVISIBLE
    }

    /** Tap on empty desktop. */
    fun closeTransient() {
        if (!desktop.endResize()) closeStart()
    }

    @Deprecated("Android 12's Back; 13 and later use the callback registered in onCreate")
    override fun onBackPressed() {
        if (Build.VERSION.SDK_INT < 33) handleBack() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    private fun handleBack() {
        if (overlays.dismissTop()) return
        if (desktop.endResize()) return
        // Back first clears a letter picked in Start's A–Z bar, then closes Start.
        if (startMenu.isOpen && !startMenu.clearLetter()) closeStart()
    }

    fun onDragEnded(p: DragPayload) {
        p.sourceView?.visibility = View.VISIBLE
        p.sourceView?.alpha = 1f
        if (p.source == DragPayload.MENU) closeStart(false)
    }

    // ------------------------------------------------------------ launching

    fun launch(app: AppInfo, from: View?) {
        if (canFloat && app.key in Prefs.floatApps) { launchFloating(app); return }
        val opts = from?.let { ActivityOptions.makeClipRevealAnimation(it, 0, 0, it.width, it.height).toBundle() }
        try { la.startMainActivity(app.component, app.user, from?.screenRect(), opts) }
        catch (e: Exception) { toast("Can't open ${app.label}") }
        if (startMenu.isOpen) main.postDelayed({ closeStart(false) }, 300)
    }

    /** Middle-click on an app: a floating window where the tablet has them, otherwise a normal launch. */
    fun launchInWindow(app: AppInfo, from: View?) = if (canFloat) launchFloating(app) else launch(app, from)

    override val coveredByWindows: Boolean get() = Windows.covering(this)

    val isLandscape: Boolean get() = windowManager.currentWindowMetrics.bounds.let { it.width() > it.height() }

    /**
     * The screen apart from the taskbar and the status bar, in screen pixels: where snapped windows go. Android keeps
     * floating windows below the status bar even while TabDesk hides it, moving one placed higher down (whole).
     */
    fun workArea(): Rect {
        val m = windowManager.currentWindowMetrics
        val b = m.bounds
        val ins = m.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout())
        val at = IntArray(2).also { taskbar.getLocationOnScreen(it) }
        val bottom = if (taskbar.isShown && at[1] > b.top) at[1] else b.bottom - ins.bottom
        return Rect(b.left + ins.left, b.top + ins.top, b.right - ins.right, bottom)
    }

    /** Open [app] in a floating (freeform) window. */
    fun launchFloating(app: AppInfo) {
        try { la.startMainActivity(app.component, app.user, null, floatingOptions()) } catch (e: Exception) { toast("Can't open ${app.label}") }
        if (startMenu.isOpen) main.postDelayed({ closeStart(false) }, 300)
    }

    /** Double-tap on empty desktop: lock the screen through [LockService], or explain how to switch it on. */
    fun doubleTapDesktop() {
        if (!Prefs.doubleTapLock || LockService.lock()) return
        val card = overlays.dialogCard(dp(440), "Double-tap to lock")
        card.addView(android.widget.TextView(this).label(pal.text, 14.5f).apply {
            text = "To lock the screen with a double-tap, turn on “TabDesk screen lock” in Accessibility settings " +
                "(on Lenovo it's under Downloaded apps).\n\nAndroid will warn that it can control your device. " +
                "TabDesk only uses it to lock the screen; it can't read the screen or what you type."
            setLineSpacing(0f, 1.15f)
        }, llp(MATCH, WRAP).apply { bottomMargin = dp(16) })
        val buttons = linear(this, false) { gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL }
        buttons.addView(overlays.button("Turn off double-tap", false) {
            Prefs.doubleTapLock = false; overlays.dismissTop(); toast("Turn it back on in Customize")
        }, llp(WRAP, dp(40)))
        buttons.addView(overlays.button("Open settings", true) { overlays.dismissTop(); openLockSettings() },
            llp(WRAP, dp(40)).apply { marginStart = dp(8) })
        card.addView(buttons, llp(MATCH, WRAP))
        overlays.show(card, dim = true)
    }

    fun openLockSettings() {
        val c = android.content.ComponentName(this, LockService::class.java).flattenToString()
        try {
            startActivity(Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                .putExtra(Intent.EXTRA_COMPONENT_NAME, c).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) { safeStart(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
    }

    /** Pull down Android's notification / quick-settings panel (needs EXPAND_STATUS_BAR, a normal permission). */
    fun openShade() {
        overlays.dismissAll(); closeStart()
        try {
            val sbm = getSystemService("statusbar")
            sbm.javaClass.getMethod("expandNotificationsPanel").invoke(sbm)
        } catch (e: Throwable) { toast("Swipe down from the top edge for notifications") }
    }

    fun launchShortcut(item: DesktopItem, from: View?) {
        val pkg = item.key.substringBefore('/')
        val id = item.key.substringAfter('/')
        try { la.startShortcut(pkg, id, from?.screenRect(), null, android.os.Process.myUserHandle()) }
        catch (e: Exception) { toast("Shortcut unavailable") }
    }

    fun unpinShortcut(removed: DesktopItem, remaining: List<DesktopItem>) {
        val pkg = removed.key.substringBefore('/')
        val keep = remaining.filter { it.type == DesktopItem.SHORTCUT && it.key.startsWith("$pkg/") }.map { it.key.substringAfter('/') }
        try { la.pinShortcuts(pkg, keep, android.os.Process.myUserHandle()) } catch (e: Exception) { }
    }

    /** Put back pinned shortcuts from a restored backup (Android forgets pins when a launcher is reinstalled). */
    fun repinShortcuts(items: List<DesktopItem>) {
        items.filter { it.type == DesktopItem.SHORTCUT }.groupBy { it.key.substringBefore('/') }.forEach { (pkg, list) ->
            try { la.pinShortcuts(pkg, list.map { it.key.substringAfter('/') }, android.os.Process.myUserHandle()) } catch (e: Exception) { }
        }
    }

    /** The chosen font isn't downloaded yet (restored from a backup, or offline last time): fetch it, then redraw. */
    fun ensureFont() {
        val family = Prefs.font ?: return
        if (!Fonts.isMissing(this) || !Fonts.available(this)) return
        Fonts.download(this, family) { ok -> if (ok && Prefs.font == family && !isDestroyed) { Fonts.load(this); rebuildUi() } }
    }

    fun safeStartForResult(i: Intent, req: Int) {
        try { startActivityForResult(i, req) } catch (e: Exception) { toast("Nothing to open that") }
    }

    fun openCalendar() {
        val cal = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR)
        try { startActivity(cal.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { safeStart(Intent(AlarmClock.ACTION_SHOW_ALARMS)) }
    }

    fun pickWallpaper() = safeStart(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Choose wallpaper"))

    /** Search the Play Store app, or the store's web page if it isn't installed. */
    fun openPlayStoreSearch(q: String) {
        overlays.dismissAll()
        val enc = Uri.encode(q)
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=$enc&c=apps")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (e: Exception) { safeStart(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/search?q=$enc&c=apps"))) }
    }

    /** Redraw Customize where it was scrolled to (e.g. after a permission or tile was granted). */
    fun refreshSettings() { overlays.dismissAll(); root.post { Customize.show(this, restoreScroll = true) } }

    /** The Customize tab an intent from [customizeIntent] asks for (-1: where it was last), or null if it doesn't. */
    private fun customizeTab(intent: Intent?): Int? =
        intent?.takeIf { it.hasExtra(EXTRA_CUSTOMIZE) }?.getIntExtra(EXTRA_CUSTOMIZE, -1)

    fun showSettings(tab: Int? = null) { closeStart(false); Customize.show(this, restoreScroll = false, tab = tab) }

    // ------------------------------------------------------------ pins

    fun pinAt(key: String, index: Int) {
        val list = Prefs.pins.filter { it != key }.toMutableList()
        list.add(index.coerceIn(0, list.size), key)
        Prefs.pins = list
        taskbar.bindPins()
    }

    private fun togglePin(app: AppInfo) {
        val list = Prefs.pins
        Prefs.pins = if (app.key in list) list - app.key else list + app.key
        taskbar.bindPins()
    }

    // ------------------------------------------------------------ context menus

    private fun anchorOf(v: View) = v.rectIn(root)

    /** [extra] is a context-specific entry (e.g. remove from taskbar recents) shown after the pin entry. */
    fun appMenu(app: AppInfo?, anchor: View, desktopItem: DesktopItem? = null, inStart: Boolean = false, extra: MenuEntry? = null) {
        if (app == null) {
            if (desktopItem != null) overlays.menu(anchorOf(anchor), listOf(MenuEntry("Remove from desktop", G.CLOSE) { desktop.remove(desktopItem) }))
            return
        }
        val e = ArrayList<MenuEntry>()
        val dpi = resources.displayMetrics.densityDpi
        for (si in Apps.shortcuts(app)) {
            val icon = try { la.getShortcutIconDrawable(si, dpi) } catch (ex: Exception) { null }
            e += MenuEntry(si.shortLabel ?: si.id, icon = icon) {
                try { la.startShortcut(si, anchor.screenRect(), null) } catch (ex: Exception) { toast("Shortcut unavailable") }
            }
        }
        if (e.isNotEmpty()) e += MenuEntry.DIVIDER
        if (canFloat) {
            val always = app.key in Prefs.floatApps
            // When it always floats, a tap already floats it; Lenovo's ••• bar on the window has full screen / split.
            if (!always) e += MenuEntry("Open in floating window", G.WINDOW) { launchFloating(app) }
            e += MenuEntry("Always open floating", if (always) G.BOX_ON else G.BOX) {
                Prefs.floatApps = if (always) Prefs.floatApps - app.key else Prefs.floatApps + app.key
                toast(if (always) "${app.label} will open full screen" else "${app.label} will open in a floating window")
            }
        }
        val pinned = app.key in Prefs.pins
        e += MenuEntry(if (pinned) "Unpin from taskbar" else "Pin to taskbar", if (pinned) G.UNPIN else G.PIN) { togglePin(app) }
        if (extra != null) e += extra
        if (desktopItem != null) e += MenuEntry("Remove from desktop", G.CLOSE) { desktop.remove(desktopItem) }
        else if (!desktop.has(app.key)) e += MenuEntry("Add to desktop", G.MONITOR) { desktop.addApp(app.key); closeStart() }
        e += MenuEntry("Rename", G.EDIT) { rename(app) }
        e += MenuEntry("Change icon", G.IMAGE) { showIconPicker(app) }
        if (inStart) e += MenuEntry("Hide from Start", G.EYE_OFF) {
            Prefs.setHidden(app.key, true); Apps.notifyChanged()
            toast("Hidden — unhide it in Customize")
        }
        e += MenuEntry("App info", G.INFO) {
            try { la.startAppDetailsActivity(app.component, app.user, anchor.screenRect(), null) } catch (ex: Exception) { }
        }
        // TabDesk Files is part of TabDesk: uninstalling "it" would remove TabDesk.
        if (!app.system && app.pkg != packageName) e += MenuEntry("Uninstall", G.TRASH) {
            safeStart(Intent(Intent.ACTION_DELETE, Uri.fromParts("package", app.pkg, null)))
        }
        // Running (the helper knows): minimise or restore, snap a floating window, and close it, as swiping it
        // away in Recents does.
        val tasks = HelperClient.tasks.filter { it.isOf(app) }
        if (tasks.any { it.running }) {
            e += MenuEntry.DIVIDER
            e += Windows.menuEntries(this, app)
            e += MenuEntry(if (tasks.size > 1) "Close ${tasks.size} windows" else "Close", G.CLOSE) {
                tasks.forEach { HelperClient.close(it.id) }
            }
        }
        overlays.menu(anchorOf(anchor), e, app.label)
    }

    fun shortcutMenu(item: DesktopItem, anchor: View) {
        overlays.menu(anchorOf(anchor), listOf(
            MenuEntry("Rename", G.EDIT) {
                overlays.prompt("Rename shortcut", item.label ?: "", "Name", null) { n ->
                    if (n.isNotBlank()) { item.label = n.trim(); desktop.save(); desktop.refreshApps() }
                }
            },
            MenuEntry("Remove from desktop", G.CLOSE) { desktop.remove(item) },
        ), item.label)
    }

    fun widgetMenu(item: DesktopItem, anchor: View) {
        val e = ArrayList<MenuEntry>()
        if (widgets.canResize(item)) e += MenuEntry("Resize", G.RESIZE) { desktop.startResize(item) }
        if (widgets.canReconfigure(item)) e += MenuEntry("Widget settings", G.SLIDERS) { widgets.reconfigure(item) }
        e += MenuEntry("Move to the dock", G.WIDGETS) { dock.takeFromDesktop(item, anchor.height) }
        e += MenuEntry("Remove widget", G.TRASH) { desktop.remove(item) }
        overlays.menu(anchorOf(anchor), e, widgets.info(item)?.loadLabel(packageManager))
    }

    fun desktopMenu(x: Float, y: Float, cell: IntArray) {
        closeStart()
        overlays.menu(Rect(x.toInt(), y.toInt(), x.toInt(), y.toInt()), listOf(
            MenuEntry("Add widget", G.WIDGETS) { widgets.showPicker(cell) },
            MenuEntry("Add Folder View", G.FOLDER) { addFolderView(cell) },
            MenuEntry("Arrange icons", G.GRID) { desktop.arrange() },
            MenuEntry("Wallpaper", G.IMAGE) { pickWallpaper() },
            MenuEntry("Customize", G.SLIDERS) { showSettings() },
        ))
    }

    private fun rename(app: AppInfo) {
        overlays.prompt("Rename app", app.label, app.originalLabel, if (Prefs.renameOf(app.key) != null) {
            { Prefs.setRename(app.key, null); Apps.resort(); Apps.rerender(app.key) }
        } else null) { name ->
            Prefs.setRename(app.key, name.takeIf { it.isNotBlank() && it.trim() != app.originalLabel })
            Apps.resort()
            Apps.rerender(app.key)  // an app drawn as its initial follows its new name
        }
    }
}
