package dev.tabdesk.helper

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import dev.tabdesk.HomeActivity
import dev.tabdesk.DotsService
import kotlin.concurrent.thread

/** The helper's setup steps: what's done so far, and the buttons that do the next one. */
internal object Setup {
    const val REQ_NOTIFY = 31

    class Status(val devOptions: Boolean, val wifi: Boolean, val wireless: Boolean, val paired: Boolean, val finished: Boolean)

    /** Told (on the main thread) when a step finishes, so an open settings page can update. */
    var onChange: (() -> Unit)? = null
    fun changed() = onChange?.invoke()
    private val main = Handler(Looper.getMainLooper())

    /** The last step is running. */
    @Volatile var finishing = false
        private set
    /** Why the last step didn't work, to show under it. */
    var problem: String? = null
        private set

    /** Set up enough for the helper to start: paired, and wireless debugging on or TabDesk able to switch it on. */
    fun ready(ctx: Context) = AdbKey.exists(ctx) && (Adb.canSwitch(ctx) || Adb.wirelessOn(ctx))

    fun status(ctx: Context): Status {
        val cr = ctx.contentResolver
        fun global(name: String) = try { Settings.Global.getInt(cr, name, 0) == 1 } catch (_: Exception) { false }
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val wifi = try { cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
            catch (_: Exception) { false }
        return Status(global(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED), wifi, global("adb_wifi_enabled"),
            AdbKey.exists(ctx), Adb.canSwitch(ctx))
    }

    /** Where Build number is, which differs between makers. */
    fun devOptionsHow(): String = when (Build.MANUFACTURER.lowercase()) {
        "oneplus", "oppo", "realme" -> "Tap Open, then Version, then tap Build number 7 times. Android counts down, " +
            "then asks for your PIN and says you’re now a developer."
        "samsung" -> "Tap Open, then Software information, then tap Build number 7 times. Android counts down, " +
            "then asks for your PIN and says you’re now a developer."
        "xiaomi", "redmi", "poco" -> "Tap Open, then tap OS version 7 times. Android counts down, then asks for your PIN " +
            "and says you’re now a developer."
        else -> "Tap Open, then tap Build number 7 times (on some tablets it’s inside Software information or " +
            "Version). Android counts down, then asks for your PIN and says you’re now a developer."
    }

    fun openAbout(act: HomeActivity) = act.safeStart(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS))

    fun openWirelessDebugging(act: HomeActivity) {
        try { act.startActivity(Pairing.wirelessDebuggingIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (_: Exception) { act.safeStart(Pairing.developerOptionsIntent()) }
    }

    /** Pair: needs TabDesk's notifications, which is where the code is typed. */
    fun pair(act: HomeActivity) {
        if (act.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            act.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY); return
        }
        if (!act.getSystemService(NotificationManager::class.java).areNotificationsEnabled()) {
            act.toast("Allow TabDesk’s notifications first: the pairing code goes there")
            act.safeStart(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, act.packageName))
            return
        }
        Pairing.start(act)
        openWirelessDebugging(act)
    }

    fun onNotifyPermission(act: HomeActivity, granted: Boolean) {
        if (granted) pair(act)
        else {
            act.toast("Allow TabDesk’s notifications first: the pairing code goes there")
            act.safeStart(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, act.packageName))
        }
    }

    /**
     * The last step: through wireless debugging, TabDesk grants itself what otherwise takes a computer with adb.
     * Each line prints "ok:<what>" if it worked, so the log shows which didn't.
     */
    fun finish(act: HomeActivity) {
        if (finishing) return
        finishing = true; problem = null; changed()
        val app = act.applicationContext
        val p = app.packageName
        val dots = android.content.ComponentName(app, DotsService::class.java).flattenToShortString()
        val script = listOf(
            "pm grant $p android.permission.WRITE_SECURE_SETTINGS" to "secure",   // switch wireless debugging on itself
            "appops set $p GET_USAGE_STATS allow" to "usage",                      // recent apps on the taskbar
            "appwidget grantbind --package $p --user $(am get-current-user)" to "widgets",  // add widgets without asking ("--user current" crashes on some tablets)
            "cmd notification allow_listener $dots" to "dots",                     // notification dots
            "appops set $p ACCESS_RESTRICTED_SETTINGS allow" to "restricted",      // lets the user turn on double-tap to lock
        ).joinToString("; ") { (cmd, tag) -> "$cmd >/dev/null 2>&1 && echo ok:$tag" }
        thread(name = "setup") {
            val result = try { Adb.use(app) { it.shell(script) }.trim() } catch (e: Exception) { "failed: ${e.message}" }
            Log.i("TabDeskSetup", "finish: ${result.replace('\n', ' ')}")
            main.post {
                finishing = false
                problem = when {
                    Adb.canSwitch(app) -> null
                    result.startsWith("failed: ") -> result.removePrefix("failed: ")
                    else -> "Android didn't allow it"
                }
                changed()
                if (problem == null) HelperClient.ensure(app)
            }
        }
    }
}
