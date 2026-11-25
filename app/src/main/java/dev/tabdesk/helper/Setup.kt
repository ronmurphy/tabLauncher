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
    /** What the last step couldn't do although it otherwise worked, e.g. "notification dots". */
    var skipped: List<String> = emptyList()
        private set
    /** What the last step's commands printed, for "Copy setup details". */
    private var lastResult = ""

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
    fun devOptionsHow(): String = when (Device.family) {
        Device.Family.OPLUS -> "Tap Open, then Version, then tap Build number 7 times. Android counts down, " +
            "then asks for your PIN and says you’re now a developer."
        Device.Family.SAMSUNG -> "Tap Open, then Software information, then tap Build number 7 times. Android counts down, " +
            "then asks for your PIN and says you’re now a developer."
        Device.Family.XIAOMI -> "Tap Open, then tap OS version 7 times. Android counts down, then asks for your PIN " +
            "and says you’re now a developer."
        Device.Family.OTHER -> "Tap Open, then tap Build number 7 times (on some tablets it’s inside Software information or " +
            "Version). Android counts down, then asks for your PIN and says you’re now a developer."
    }

    /** A step some makers add: a switch in Developer options that must be on before wireless debugging may grant permissions. */
    class Extra(val title: String, val how: String, val fix: String)

    fun extraStep(): Extra? = when (Device.family) {
        Device.Family.OPLUS -> Extra("Switch on “Disable permission monitoring”",
            "Tap Open, scroll to the bottom of Developer options, and switch on Disable permission monitoring. " +
                "${Device.system.substringBefore(' ')} doesn’t let wireless debugging change TabDesk’s permissions without it.",
            "In Developer options, switch on Disable permission monitoring (near the bottom), then tap Allow again. " +
                "If it still doesn’t work, restart the tablet and try once more")
        Device.Family.XIAOMI -> Extra("Switch on “USB debugging (Security settings)”",
            "Tap Open and, under Debugging, switch on USB debugging (Security settings). Xiaomi may ask you to sign in " +
                "to a Xiaomi account first. Without it, wireless debugging can’t change TabDesk’s permissions.",
            "In Developer options, switch on USB debugging (Security settings), then tap Allow again")
        else -> null
    }

    fun openDeveloperOptions(act: HomeActivity) = act.safeStart(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))

    fun openAbout(act: HomeActivity) = act.safeStart(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS))

    fun openWirelessDebugging(act: HomeActivity) {
        // Some Settings (e.g. Android 16's) open Developer options first, which opens Wireless debugging on top and
        // opens it again whenever Back returns to it: no history drops it once it has, so Back comes back here.
        try { act.startActivity(Pairing.wirelessDebuggingIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY)) }
        catch (_: Exception) { act.safeStart(Pairing.developerOptionsIntent()) }
    }

    /** Pair: needs TabDesk's notifications, which is where the code is typed. */
    fun pair(act: HomeActivity) {
        // Android 13 added asking for notifications; on 12 they're on unless switched off in Settings (checked below).
        if (Build.VERSION.SDK_INT >= 33 &&
            act.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
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
        finishing = true; problem = null; skipped = emptyList(); changed()
        val app = act.applicationContext
        val p = app.packageName
        val dots = android.content.ComponentName(app, DotsService::class.java).flattenToShortString()
        val script = listOf(
            "pm grant $p android.permission.WRITE_SECURE_SETTINGS" to "secure",   // switch wireless debugging on itself
            "appops set $p GET_USAGE_STATS allow" to "usage",                      // recent apps on the taskbar
            "appwidget grantbind --package $p --user $(am get-current-user)" to "widgets",  // add widgets without asking ("--user current" crashes on some tablets)
            "cmd notification allow_listener $dots" to "dots",                     // notification dots
            // Lets the user turn on double-tap to lock. Restricted settings arrived in Android 13.
            if (Build.VERSION.SDK_INT >= 33) "appops set $p ACCESS_RESTRICTED_SETTINGS allow" to "restricted" else null,
            "appops set $p SYSTEM_ALERT_WINDOW allow" to "overlay",                // window previews above floating windows
        ).filterNotNull().joinToString("; ") { (cmd, tag) ->
            // "ok:<what>", or "no:<what>:" and the line of the error that says why.
            "o=\$($cmd 2>&1) && echo ok:$tag || echo \"no:$tag:\$(echo \"\$o\" | grep -m1 -iE 'exception: |denied|error: |not allowed' || echo \"\$o\" | head -n1)\""
        }
        thread(name = "setup") {
            val result = try { Adb.use(app) { it.shell(script) }.trim() } catch (e: Exception) { "failed: ${e.message}" }
            Log.i("TabDeskSetup", "finish: ${result.replace('\n', ' ')}")
            val failed = result.lines().filter { it.startsWith("no:") }.map { it.removePrefix("no:").substringBefore(':') }
            main.post {
                finishing = false
                lastResult = result
                problem = when {
                    Adb.canSwitch(app) -> null
                    result.startsWith("failed: ") -> result.removePrefix("failed: ")
                    // Refused (or "done" without it taking): on some makers' Android, a switch in Developer options is missing.
                    else -> extraStep()?.let { "${Device.system.substringBefore(' ')} didn’t allow it. ${it.fix}" }
                        ?: ("Android didn’t allow it. Some tablets have an extra switch for this in Developer options, " +
                            "usually about permissions or security near the bottom. Turn it on, then tap Allow again")
                }
                skipped = if (problem != null) emptyList() else failed.mapNotNull { WHAT[it] }
                changed()
                if (problem == null) HelperClient.ensure(app)
            }
        }
    }

    private val WHAT = mapOf("usage" to "recently used apps", "widgets" to "adding widgets without asking",
        "dots" to "notification dots", "restricted" to "double-tap to lock", "overlay" to "window previews")

    /** Everything about this tablet's setup, as text to paste into a message when something doesn't work. */
    fun details(ctx: Context): String {
        val s = status(ctx)
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        fun yes(b: Boolean) = if (b) "yes" else "no"
        return listOf(
            "TabDesk ${pi.versionName} (${pi.longVersionCode})",
            "Tablet: ${Device.summary} (${Build.MANUFACTURER}/${Build.BRAND}, SDK ${Build.VERSION.SDK_INT})",
            "Build: ${Build.DISPLAY}",
            "Developer options: ${yes(s.devOptions)}, Wi-Fi: ${yes(s.wifi)}, wireless debugging: ${yes(s.wireless)}, " +
                "USB debugging: ${yes(Adb.usbOn(ctx))}, paired: ${yes(s.paired)}, allowed: ${yes(s.finished)}",
            "Helper: " + when {
                HelperClient.connected -> "running, ${HelperClient.tasks.size} tasks"
                HelperClient.starting -> "starting"
                else -> "not running" + (HelperClient.problem?.let { " ($it)" } ?: "")
            },
            "Last Allow: " + (lastResult.ifEmpty { "not tried since TabDesk started" }).replace("\n", " | "),
        ).joinToString("\n")
    }
}
