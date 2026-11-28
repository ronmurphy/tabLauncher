package dev.tabdesk.helper

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import java.io.File
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** Running shell commands through this tablet's own wireless debugging, with TabDesk's paired key. */
internal object Adb {
    private const val TAG = "TabDeskAdb"
    private const val WIFI_SETTING = "adb_wifi_enabled"

    class Problem(message: String) : Exception(message)

    fun canSwitch(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun usbOn(ctx: Context) = try { Settings.Global.getInt(ctx.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1 } catch (_: Exception) { false }

    fun wirelessOn(ctx: Context) = try { Settings.Global.getInt(ctx.contentResolver, WIFI_SETTING, 0) == 1 } catch (_: Exception) { false }

    /** Switches wireless debugging on or off; needs WRITE_SECURE_SETTINGS. */
    fun setWireless(ctx: Context, on: Boolean) = try {
        Settings.Global.putInt(ctx.contentResolver, WIFI_SETTING, if (on) 1 else 0)
    } catch (e: SecurityException) { false }

    /**
     * Connects and runs [block]. If wireless debugging is off and TabDesk may switch it, it's switched on for the
     * moment (and back off afterwards when [restore]). Blocks: call off the main thread.
     */
    fun <T> use(ctx: Context, restore: Boolean = true, block: (AdbLink) -> T): T {
        val (key, cert) = AdbKey.load(ctx) ?: throw Problem("TabDesk isn't paired yet")
        users.incrementAndGet()
        val wasOn = wirelessOn(ctx)
        if (!wasOn) {
            if (!canSwitch(ctx)) { users.decrementAndGet(); throw Problem("Wireless debugging is off") }
            setWireless(ctx, true)
            Log.i(TAG, "switched wireless debugging on")
        }
        try {
            val t = SystemClock.elapsedRealtime()
            return open(ctx, key, cert, justSwitchedOn = !wasOn).use { link ->
                Log.i(TAG, "connected in ${SystemClock.elapsedRealtime() - t} ms")
                block(link)
            }
        } finally {
            // Switching it off stops adb altogether unless USB debugging is on, which would end the helper too.
            try {
                if (!wasOn && restore && usbOn(ctx)) { setWireless(ctx, false); Log.i(TAG, "switched wireless debugging back off") }
            } finally { users.decrementAndGet() }
        }
    }

    /** How many [use]s are running: their own switching isn't the user's. */
    private val users = AtomicInteger(0)
    private val watching = AtomicBoolean(false)
    private val noting = AtomicBoolean(false)

    /**
     * Notes wireless debugging's port each time it's switched on, while it's freshly announced: Android 12 stops
     * announcing it soon after. With the port noted, [use] never has to switch it off and on to find it, which would
     * end every other connection to it (the user's computer's too) and change its port. Then, if the user (not
     * TabDesk) switched it on, [switchedOn] runs, off the main thread.
     */
    fun watch(ctx: Context, switchedOn: () -> Unit) {
        if (!watching.compareAndSet(false, true)) return
        val app = ctx.applicationContext
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                if (!wirelessOn(app)) return
                val outside = users.get() == 0
                if (!noting.compareAndSet(false, true)) return
                thread(name = "adb-port", isDaemon = true) {
                    try {
                        // Just switched on, it takes a moment to start announcing itself.
                        findAdbPort(app, timeoutMs = 15_000) { Log.i(TAG, it) }?.let {
                            rememberPort(app, it); Log.i(TAG, "wireless debugging was switched on, at port $it")
                        }
                    } finally { noting.set(false) }
                    if (outside && wirelessOn(app)) switchedOn()
                }
            }
        }
        try { app.contentResolver.registerContentObserver(Settings.Global.getUriFor(WIFI_SETTING), false, observer) }
        catch (e: Exception) { Log.w(TAG, "can't watch wireless debugging", e) }
    }

    /**
     * Connects to wireless debugging: at the port it had last time (it only changes when wireless debugging is
     * switched off and on, and [watch] notes it then), else wherever it's announced on Wi-Fi. Never switches off
     * wireless debugging that was on.
     */
    private fun open(ctx: Context, key: PrivateKey, cert: X509Certificate, justSwitchedOn: Boolean): AdbLink {
        if (!justSwitchedOn) savedPort(ctx)?.let { p ->
            try { return AdbLink.connect(p, key, cert) } catch (e: Exception) { Log.i(TAG, "port $p no longer answers") }
        }
        // Just switched on, it takes a moment to start announcing itself.
        val port = findAdbPort(ctx, timeoutMs = if (justSwitchedOn) 15_000 else 6000) { Log.i(TAG, it) }
        port ?: throw Problem(when {
            justSwitchedOn -> "Wireless debugging didn't start (is Wi-Fi on?)"
            else -> "Couldn’t find wireless debugging. Switch Use wireless debugging off and on again, then try once more"
        })
        rememberPort(ctx, port)
        return AdbLink.connect(port, key, cert)
    }

    /** Finds wireless debugging's port while its announcement is fresh (just after pairing) and remembers it. */
    fun notePort(ctx: Context) { findAdbPort(ctx, timeoutMs = 4000) { Log.i(TAG, it) }?.let { rememberPort(ctx, it) } }

    private fun portFile(ctx: Context) = File(ctx.noBackupFilesDir, "adb/port")
    private fun savedPort(ctx: Context): Int? = try { portFile(ctx).readText().trim().toInt() } catch (_: Exception) { null }
    private fun rememberPort(ctx: Context, port: Int) {
        try { portFile(ctx).apply { parentFile?.mkdirs() }.writeText("$port") } catch (_: Exception) {}
    }
}
