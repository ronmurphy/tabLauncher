package dev.tabdesk.helper

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log

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
        val wasOn = wirelessOn(ctx)
        if (!wasOn) {
            if (!canSwitch(ctx)) throw Problem("Wireless debugging is off")
            setWireless(ctx, true)
            Log.i(TAG, "switched wireless debugging on")
        }
        try {
            // Just switched on, it takes a moment to start advertising.
            val port = findAdbPort(ctx, timeoutMs = if (wasOn) 6000 else 15_000) { Log.i(TAG, it) }
                ?: throw Problem(if (wasOn) "Couldn't find wireless debugging on Wi-Fi" else "Wireless debugging didn't start (is Wi-Fi on?)")
            val t = SystemClock.elapsedRealtime()
            return AdbLink.connect(port, key, cert).use { link ->
                Log.i(TAG, "connected in ${SystemClock.elapsedRealtime() - t} ms")
                block(link)
            }
        } finally {
            // Switching it off stops adb altogether unless USB debugging is on, which would end the helper too.
            if (!wasOn && restore && usbOn(ctx)) { setWireless(ctx, false); Log.i(TAG, "switched wireless debugging back off") }
        }
    }
}
