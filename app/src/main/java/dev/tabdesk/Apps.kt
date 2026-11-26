package dev.tabdesk

import dev.tabdesk.helper.HelperClient
import dev.tabdesk.icons.*
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.util.DisplayMetrics
import java.text.Collator
import java.util.concurrent.Executors

// ---------------------------------------------------------------- installed apps

class AppInfo(
    val key: String,
    val component: ComponentName,
    val user: UserHandle,
    val originalLabel: String,
    /** For TabDesk's own surfaces (taskbar, Start, menus). */
    var icon: Bitmap,
    /** For the desktop, over the wallpaper; the same bitmap unless the icon style depends on what's behind it. */
    var deskIcon: Bitmap,
    val system: Boolean,
    val installed: Long,
) {
    val pkg: String get() = component.packageName
    val label: String get() = Prefs.renameOf(key) ?: originalLabel
}

object Apps {
    var all: List<AppInfo> = emptyList(); private set
    var loaded = false; private set
    private var byKey: Map<String, AppInfo> = emptyMap()
    val listeners = LinkedHashSet<() -> Unit>()

    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var appCtx: Context
    private lateinit var la: LauncherApps
    private var seq = 0
    private val collator = Collator.getInstance()

    fun init(ctx: Context) {
        if (::la.isInitialized) return
        appCtx = ctx.applicationContext
        la = appCtx.getSystemService(LauncherApps::class.java)
        la.registerCallback(object : LauncherApps.Callback() {
            override fun onPackageRemoved(p: String, u: UserHandle) = reload()
            override fun onPackageAdded(p: String, u: UserHandle) = reload()
            override fun onPackageChanged(p: String, u: UserHandle) = reload()
            override fun onPackagesAvailable(p: Array<out String>, u: UserHandle, r: Boolean) = reload()
            override fun onPackagesUnavailable(p: Array<out String>, u: UserHandle, r: Boolean) = reload()
            override fun onShortcutsChanged(p: String, s: MutableList<ShortcutInfo>, u: UserHandle) = notifyChanged()
        }, main)
    }

    private val reloadRunnable = Runnable { load() }
    /** Apps were installed, removed or changed (an icon pack may be among them). */
    fun reload() { IconPacks.invalidate(); main.removeCallbacks(reloadRunnable); main.postDelayed(reloadRunnable, 250) }

    private fun iconSize() = appCtx.dp(64).coerceAtLeast(96)

    fun load() {
        val my = ++seq
        val size = iconSize()
        val spec = Icons.spec
        exec.execute {
            val roles = if (spec.style != Icons.ORIGINAL) Icons.roles(appCtx, refresh = true) else emptyMap()
            val list = la.getActivityList(null, Process.myUserHandle())
                // TabDesk's own entries aren't apps to launch from here, apart from TabDesk Files and TabDesk Tasks.
                .filter { it.componentName.packageName != appCtx.packageName ||
                    it.componentName.className in setOf(FilesActivity::class.java.name, TasksActivity::class.java.name) }
                .map { info(it, size, spec, roles) }
            main.post {
                if (my != seq) return@post
                byKey = list.associateBy { it.key }
                all = list
                loaded = true
                resort()
            }
        }
    }

    private fun info(a: LauncherActivityInfo, size: Int, spec: Icons.Spec, roles: Map<String, String>): AppInfo {
        val flags = a.applicationInfo.flags
        val installed = try { appCtx.packageManager.getPackageInfo(a.componentName.packageName, 0).firstInstallTime } catch (e: Exception) { 0L }
        val key = a.componentName.flattenToString()
        val (icon, desk) = drawIcons(a, size, spec, roles, Prefs.iconOf(key))
        return AppInfo(
            key, a.componentName, a.user, a.label.toString(),
            icon, desk,
            (flags and ApplicationInfo.FLAG_SYSTEM) != 0,
            installed,
        )
    }

    /**
     * An app's icon in [spec]'s style, with [choice] (Prefs.iconOf) applied: one for TabDesk's surfaces
     * (light or dark with the theme) and one for the desktop (light or dark with the wallpaper).
     */
    private fun drawIcons(a: LauncherActivityInfo, size: Int, spec: Icons.Spec, roles: Map<String, String>,
                          choice: String?): Pair<Bitmap, Bitmap> {
        val d = a.getIcon(DisplayMetrics.DENSITY_XXHIGH)
        val own = render(d, size)
        val key = a.componentName.flattenToString()
        val label = Prefs.renameOf(key) ?: a.label.toString()
        // "App's own icon" means untouched: no style and no effect.
        val fx = if (choice == Icons.OWN) IconEffects.NONE else spec.effect
        val seed = a.componentName.packageName.hashCode()
        fun make(onLight: Boolean) = IconEffects.render(Icons.make(appCtx, d, own, a.componentName, label,
            a.applicationInfo, choice, roles, size, spec, onLight), fx, seed)
        val surface = make(onLight = !spec.dark)
        val desk = if (spec.lightWall != !spec.dark && Icons.dependsOnBackground(spec, choice)) make(onLight = spec.lightWall) else surface
        return surface to desk
    }

    private fun activity(app: AppInfo): LauncherActivityInfo? = try {
        la.getActivityList(app.pkg, app.user).firstOrNull { it.componentName == app.component }
    } catch (e: Exception) { null }

    /** Draw one app's icon again (after Change icon). */
    fun rerender(key: String) {
        val app = byKey[key] ?: return
        val spec = Icons.spec; val size = iconSize(); val choice = Prefs.iconOf(key)
        exec.execute {
            val a = activity(app) ?: return@execute
            val (b, desk) = drawIcons(a, size, spec, Icons.roles(appCtx), choice)
            main.post { app.icon = b; app.deskIcon = desk; notifyChanged() }
        }
    }

    /** What [app]'s icon would look like with [choice], in [spec]'s style (default: the current one). */
    fun preview(app: AppInfo, choice: String?, spec: Icons.Spec = Icons.spec): Bitmap? =
        activity(app)?.let { drawIcons(it, iconSize(), spec, Icons.roles(appCtx), choice).first }

    private val previewExec = Executors.newSingleThreadExecutor()

    /** [app] drawn in each of [specs] (with its own picked icon, if any), off the main thread; for Customize. */
    fun previews(app: AppInfo, specs: List<Icons.Spec>, done: (List<Bitmap?>) -> Unit) {
        val choice = Prefs.iconOf(app.key)?.takeIf { it != Icons.OWN }
        previewExec.execute {
            val a = activity(app)
            val roles = Icons.roles(appCtx)
            val out = specs.map { s -> a?.let { drawIcons(it, iconSize(), s, roles, choice).first } }
            main.post { done(out) }
        }
    }

    /**
     * For Customize's icon pack row: each pack (null = none) previewed with the first of [candidates]
     * it has an icon for, so a card doesn't just show an app the pack lacks.
     */
    fun packPreviews(candidates: List<AppInfo>, packs: List<String?>, base: Icons.Spec, done: (List<Bitmap?>) -> Unit) {
        previewExec.execute {
            val roles = Icons.roles(appCtx)
            val out = packs.map { p ->
                val app = (if (p == null) null else candidates.firstOrNull { IconPacks.covers(appCtx, p, it.component) }) ?: candidates.firstOrNull()
                val a = app?.let { activity(it) }
                a?.let { drawIcons(it, iconSize(), base.copy(pack = p), roles, Prefs.iconOf(app.key)?.takeIf { c -> c != Icons.OWN }).first }
            }
            main.post { done(out) }
        }
    }

    /** [apps] drawn with [spec] and the picked icons [picks] (an icon theme's), off the main thread. */
    fun previewSet(apps: List<AppInfo>, spec: Icons.Spec, picks: Map<String, String>, done: (List<Bitmap?>) -> Unit) {
        previewExec.execute {
            val roles = Icons.roles(appCtx)
            val out = apps.map { app -> activity(app)?.let { drawIcons(it, iconSize(), spec, roles, picks[app.key]).first } }
            main.post { done(out) }
        }
    }

    fun render(d: Drawable, size: Int): Bitmap {
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val inset = if (d is AdaptiveIconDrawable) (size * 0.03f).toInt() else 0
        d.setBounds(inset, inset, size - inset, size - inset)
        d.draw(Canvas(b))
        return b
    }

    /** Re-sort after a rename and tell everyone. */
    fun resort() {
        all = all.sortedWith { a, b -> collator.compare(a.label, b.label) }
        notifyChanged()
    }

    fun notifyChanged() = listeners.toList().forEach { it() }
    fun get(key: String?): AppInfo? = key?.let { byKey[it] }
    fun visible() = all.filter { !Prefs.isHidden(it.key) }
    fun firstOf(pkg: String) = all.firstOrNull { it.pkg == pkg }
    /** The app a running task belongs to (see [HelperClient.Task.isOf]). */
    internal fun ofTask(t: HelperClient.Task) = all.firstOrNull { t.isOf(it) }

    fun shortcuts(app: AppInfo): List<ShortcutInfo> = try {
        if (!la.hasShortcutHostPermission()) emptyList()
        else la.getShortcuts(
            LauncherApps.ShortcutQuery().setPackage(app.pkg).setActivity(app.component)
                .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST),
            app.user,
        ).orEmpty().filter { it.isEnabled }
            .sortedWith(compareBy({ !it.isDeclaredInManifest }, { it.rank })).take(4)
    } catch (e: Exception) { emptyList() }

    fun pinnedShortcut(pkg: String, id: String): ShortcutInfo? = try {
        la.getShortcuts(
            LauncherApps.ShortcutQuery().setPackage(pkg).setShortcutIds(listOf(id))
                .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED),
            Process.myUserHandle(),
        )?.firstOrNull()
    } catch (e: Exception) { null }
}

// ---------------------------------------------------------------- recently used apps

/** Recently used packages (newest first) from UsageStats; needs the usage-access app-op. */
object Recents {
    var packages: List<String> = emptyList(); private set
    private var lastUsed: Map<String, Long> = emptyMap()
    private const val WINDOW = 3 * 24 * 3600_000L

    /** [packages] minus the ones removed from the taskbar since they were last opened. */
    fun shown(): List<String> {
        val removed = Prefs.removedRecents
        return packages.filter { (removed[it] ?: Long.MIN_VALUE) < (lastUsed[it] ?: 0L) }
    }

    /** Hide [pkg] from the taskbar until it's next opened. */
    fun remove(pkg: String) {
        val now = System.currentTimeMillis()
        Prefs.removedRecents = Prefs.removedRecents.filterValues { it > now - WINDOW } + (pkg to now)
    }
    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun granted(ctx: Context): Boolean {
        val aom = ctx.getSystemService(AppOpsManager::class.java)
        return aom.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName) == AppOpsManager.MODE_ALLOWED
    }

    fun refresh(ctx: Context, changed: () -> Unit) {
        if (!Prefs.recents || !granted(ctx)) {
            if (packages.isNotEmpty()) { packages = emptyList(); lastUsed = emptyMap(); changed() }
            return
        }
        val app = ctx.applicationContext
        exec.execute {
            val usm = app.getSystemService(UsageStatsManager::class.java)
            val now = System.currentTimeMillis()
            val last = HashMap<String, Long>()
            try {
                val ev = usm.queryEvents(now - WINDOW, now)
                val e = UsageEvents.Event()
                while (ev.hasNextEvent()) {
                    ev.getNextEvent(e)
                    if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) last[e.packageName] = e.timeStamp
                }
            } catch (e: Exception) { }
            last.remove(app.packageName)
            val sorted = last.entries.sortedByDescending { it.value }.map { it.key }
            main.post { if (last != lastUsed) { packages = sorted; lastUsed = last; changed() } }
        }
    }
}

// ---------------------------------------------------------------- notification dots

object Badges {
    private var counts: Map<String, Int> = emptyMap()
    val listeners = LinkedHashSet<() -> Unit>()

    fun has(pkg: String) = Prefs.dots && (counts[pkg] ?: 0) > 0

    fun set(m: Map<String, Int>) {
        if (m == counts) return
        counts = m
        listeners.toList().forEach { it() }
    }
}
