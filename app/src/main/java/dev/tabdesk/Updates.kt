package dev.tabdesk

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import dev.tabdesk.customize.Customize
import dev.tabdesk.helper.Adb
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Updates from TabDesk's releases on GitHub: asks for the latest release, downloads its APK, checks it, and has
 * Android install it. Android 12 and later let an app update itself without asking each time, once it may install
 * apps ("Install unknown apps"), which the helper can allow by itself.
 */
object Updates {
    private const val REPO = "ronmurphy/tabLauncher"
    const val RELEASES = "https://github.com/$REPO/releases"
    private const val TAG = "TabDeskUpdates"
    private const val CHANNEL = "updates"
    private const val NOTIFY_ID = 7
    private const val HOUR = 3600_000L

    class Release(val version: String, val notes: String, val apk: String, val size: Long, val sha256: String?)

    enum class State { IDLE, CHECKING, LATEST, AVAILABLE, NEEDS_PERMISSION, DOWNLOADING, INSTALLING, FAILED }

    var state = State.IDLE; private set
    /** The newer release the last check found. */
    var available: Release? = null; private set
    /** Why the last step didn't work. */
    var problem: String? = null; private set
    /** Percent downloaded, in steps of 10. */
    var progress = 0; private set
    /** Told on the main thread whenever any of the above changes. */
    var onChange: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val busy get() = state == State.CHECKING || state == State.DOWNLOADING || state == State.INSTALLING

    private class Problem(message: String) : Exception(message)

    /** Main thread only. */
    private fun set(s: State, why: String? = null) { state = s; problem = why; onChange?.invoke() }

    fun current(ctx: Context): String =
        try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0" } catch (_: Exception) { "0" }

    /** Whether version [a] (like "1.0.2") is newer than [b]. */
    fun newer(a: String, b: String): Boolean {
        fun parts(v: String) = v.removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: 0 }
        val x = parts(a); val y = parts(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

    // ------------------------------------------------------------ checking

    /** Asks GitHub for the latest release. [quiet] is the daily check: it only speaks up when there's a new version. */
    fun check(ctx: Context, quiet: Boolean = false) {
        if (busy) return
        val app = ctx.applicationContext
        if (!quiet) set(State.CHECKING)
        worker.execute {
            val result = runCatching { latest() }
            main.post {
                result.onSuccess { r ->
                    Prefs.updateChecked = System.currentTimeMillis()
                    available = r.takeIf { newer(it.version, current(app)) }
                    when {
                        available != null -> { set(State.AVAILABLE); if (quiet) notify(app, r) }
                        !quiet -> set(State.LATEST)
                    }
                }.onFailure { e ->
                    Log.i(TAG, "check failed: $e")
                    Prefs.updateChecked = System.currentTimeMillis() - 23 * HOUR   // the daily check tries again in an hour
                    if (!quiet) set(State.FAILED, reason(e))
                }
            }
        }
    }

    /** When TabDesk comes to the front: carries on after "Install unknown apps" was allowed, or the daily check. */
    fun resumed(act: HomeActivity) {
        if (state == State.NEEDS_PERMISSION && act.packageManager.canRequestPackageInstalls()) update(act)
        else if (Prefs.autoUpdates && System.currentTimeMillis() - Prefs.updateChecked > 24 * HOUR) check(act, quiet = true)
    }

    private fun connect(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000; readTimeout = 30_000
        setRequestProperty("User-Agent", "TabDesk")   // GitHub asks for one
    }

    private fun latest(): Release {
        val c = connect("https://api.github.com/repos/$REPO/releases/latest")
        c.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            when (c.responseCode) {
                200 -> {}
                403, 429 -> throw Problem("GitHub is busy. Try again in an hour")
                404 -> throw Problem("There are no releases yet")
                else -> throw Problem("GitHub answered ${c.responseCode}")
            }
            val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val assets = j.getJSONArray("assets")
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk") } ?: throw Problem("The newest release has no APK yet")
            return Release(j.getString("tag_name").removePrefix("v"), notes(j.optString("body")),
                apk.getString("browser_download_url"), apk.optLong("size"),
                apk.optString("digest").takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:"))
        } finally { c.disconnect() }
    }

    /** Release notes as plain text: bullets kept, Markdown's marks and the install note every release ends with dropped. */
    private fun notes(md: String): String = md.replace("\r", "").lines()
        .takeWhile { !it.startsWith("**Installing or updating") }
        .joinToString("\n") { line ->
            line.replace(Regex("""^\s*[-*] """), "• ")
                .replace(Regex("""\[([^\]]+)]\([^)]*\)"""), "\$1")
                .replace("**", "").replace("`", "")
                .replace(Regex("""(^|[\s(])\*([^*\s][^*]*)\*"""), "\$1\$2")
        }.trim()

    private fun notify(ctx: Context, r: Release) {
        if (Prefs.updateNotified == r.version) return
        Prefs.updateNotified = r.version
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) return   // the Start menu shows it too
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Updates", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(ctx, 0,
            HomeActivity.customizeIntent(ctx, Customize.ABOUT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(NOTIFY_ID, Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_note_tabdesk)
            .setContentTitle("TabDesk ${r.version} is available")
            .setContentText("Tap to see what’s new and update.")
            .setContentIntent(open).setAutoCancel(true)
            .build())
    }

    // ------------------------------------------------------------ updating

    /** Downloads the available release, checks it, and installs it. */
    fun update(act: HomeActivity) {
        val r = available ?: return
        if (busy) return
        val app = act.applicationContext
        if (!app.packageManager.canRequestPackageInstalls() && !Adb.canSwitch(app)) { askToInstall(act); return }
        progress = 0
        set(State.DOWNLOADING)
        worker.execute {
            try {
                val apk = download(app, r)
                if (!app.packageManager.canRequestPackageInstalls()) {
                    // With the helper set up, TabDesk can allow itself to install apps, as Settings' switch would.
                    runCatching { Adb.use(app) { it.shell("appops set ${app.packageName} REQUEST_INSTALL_PACKAGES allow") } }
                    if (!app.packageManager.canRequestPackageInstalls()) { main.post { askToInstall(act) }; return@execute }
                }
                Prefs.updatingTo = r.version   // before Android ends TabDesk to replace it
                main.post { set(State.INSTALLING) }
                install(app, apk)
            } catch (e: Exception) {
                Log.w(TAG, "update failed", e)
                main.post { set(State.FAILED, reason(e)) }
            }
        }
    }

    /** Android's "Install unknown apps" switch for TabDesk. Coming back with it on, the update carries on ([resumed]). */
    private fun askToInstall(act: HomeActivity) {
        set(State.NEEDS_PERMISSION)
        act.safeStart(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${act.packageName}")))
    }

    private fun download(ctx: Context, r: Release): File {
        val dir = File(ctx.cacheDir, "update").apply { deleteRecursively(); mkdirs() }
        val f = File(dir, "TabDesk-${r.version}.apk")
        val c = connect(r.apk)
        try {
            if (c.responseCode != 200) throw Problem("The download didn’t start (GitHub answered ${c.responseCode})")
            val total = c.contentLengthLong.takeIf { it > 0 } ?: r.size
            val sha = MessageDigest.getInstance("SHA-256")
            var got = 0L
            var shown = 0
            c.inputStream.use { inp ->
                f.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n); sha.update(buf, 0, n); got += n
                        val pct = if (total > 0) (got * 10 / total).toInt() * 10 else 0
                        if (pct != shown) { shown = pct; main.post { progress = pct; onChange?.invoke() } }
                    }
                }
            }
            if (r.sha256 != null && sha.digest().joinToString("") { "%02x".format(it) } != r.sha256.lowercase())
                throw Problem("The download was damaged. Try again")
        } finally { c.disconnect() }
        // Before handing it to Android, which itself checks it's signed with TabDesk's key: it must be TabDesk, and newer.
        val info = ctx.packageManager.getPackageArchiveInfo(f.path, 0) ?: throw Problem("The download isn’t an app. Try again")
        if (info.packageName != ctx.packageName) throw Problem("The download isn’t TabDesk")
        if (!newer(info.versionName ?: "0", current(ctx))) throw Problem("The download isn’t newer than this TabDesk")
        return f
    }

    private fun install(ctx: Context, apk: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            setSize(apk.length())
            // An app updating itself may skip Android's "Do you want to update?" (Android 12 and later).
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { s ->
            s.openWrite("TabDesk.apk", 0, apk.length()).use { out -> apk.inputStream().use { it.copyTo(out) }; s.fsync(out) }
            val answer = PendingIntent.getBroadcast(ctx, id, Intent(ctx, UpdateReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)   // mutable: Android adds the result
            s.commit(answer.intentSender)
        }
        Log.i(TAG, "installing ${apk.name}")
    }

    /** Android's answer, when it isn't simply TabDesk restarting as the new version. */
    internal fun installAnswer(ctx: Context, status: Int, message: String?) {
        Log.i(TAG, "install: $status $message")
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> {}   // TabDesk is replaced and restarts as the new version
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {}   // Android is asking; it answers again after
            else -> {
                Prefs.updatingTo = null
                set(State.FAILED, when (status) {
                    PackageInstaller.STATUS_FAILURE_ABORTED -> "The update was cancelled"
                    PackageInstaller.STATUS_FAILURE_STORAGE -> "There isn’t enough space on the tablet"
                    PackageInstaller.STATUS_FAILURE_BLOCKED -> "Android blocked it" + (message?.let { " ($it)" } ?: "")
                    else -> "Android didn’t install it" + (message?.let { " ($it)" } ?: "")
                })
            }
        }
    }

    /** At TabDesk's start: after an update, say so and tidy up. */
    fun afterStart(act: HomeActivity) {
        val to = Prefs.updatingTo ?: return
        if (state == State.INSTALLING) return   // only the screen was rebuilt; still installing
        Prefs.updatingTo = null
        File(act.cacheDir, "update").deleteRecursively()
        if (current(act) == to) {
            act.getSystemService(NotificationManager::class.java).cancel(NOTIFY_ID)
            act.toast("TabDesk updated to $to")
        }
    }

    /** "TabDesk is updated": tapping it goes back to TabDesk, when Android showed another home screen instead. */
    internal fun notifyUpdated(ctx: Context, to: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Updates", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(ctx, 1, HomeActivity.homeIntent(ctx),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(NOTIFY_ID, Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_note_tabdesk)
            .setContentTitle("TabDesk is updated to $to")
            .setContentText("Tap to go back to TabDesk.")
            .setContentIntent(open).setAutoCancel(true)
            .build())
    }

    private fun reason(e: Throwable): String = when (e) {
        is Problem -> e.message ?: "Something went wrong"
        is UnknownHostException, is java.net.ConnectException, is java.net.SocketTimeoutException ->
            "Couldn’t reach GitHub. Is the tablet online?"
        else -> "Something went wrong (${e.javaClass.simpleName})"
    }
}

/**
 * TabDesk's new version has been installed. While TabDesk was being replaced, Android may have shown the tablet's
 * own home screen instead, and Android 12 stays there afterwards. After TabDesk's own update, this brings it back:
 * Android doesn't let TabDesk open itself from the background then, but the helper can, and otherwise a
 * notification does it with one tap.
 */
class UpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        Prefs.init(ctx)
        val to = Prefs.updatingTo ?: return   // updated some other way: the user may be busy in another app
        val app = ctx.applicationContext
        val done = goAsync()
        thread(name = "updated") {
            try {
                Thread.sleep(2000)   // Android 16 brings TabDesk back by itself
                if (HomeActivity.inFront) return@thread
                val viaHelper = (Adb.canSwitch(app) || Adb.wirelessOn(app)) && runCatching {
                    Adb.use(app) { it.shell("am start -a android.intent.action.MAIN -c android.intent.category.HOME") }
                }.isSuccess
                Log.i("TabDeskUpdates", "updated to $to; back to the front ${if (viaHelper) "through the helper" else "by a notification"}")
                if (!viaHelper) Updates.notifyUpdated(app, to)
            } finally { done.finish() }
        }
    }
}

/** Android's answer to an update TabDesk asked it to install. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // Android wants the user to confirm (some makers' Android always does, or TabDesk may not skip it).
            @Suppress("DEPRECATION")
            val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_INTENT)
            try { confirm?.let { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
            catch (e: Exception) { Log.w("TabDeskUpdates", "couldn't ask", e) }
        }
        Updates.installAnswer(ctx, status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
    }
}
