package dev.tabdesk

import android.app.PendingIntent
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlin.math.abs

/**
 * Android's screen timeout, settable to any length or Never (needs the "Modify system settings"
 * permission). The Keep awake tile switches between the user's timeout and Never.
 */
object ScreenTimeout {
    const val REQ_PERMISSION = 34
    /** What apps use for "never": about 24 days, which Android treats as no timeout. */
    const val NEVER = Int.MAX_VALUE
    val STEPS = intArrayOf(15_000, 30_000, 60_000, 120_000, 300_000, 600_000, 900_000, 1_800_000, 3_600_000, 7_200_000, NEVER)

    fun canWrite(ctx: Context) = Settings.System.canWrite(ctx)

    fun get(ctx: Context): Int = try {
        Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT)
    } catch (e: Exception) { 60_000 }

    fun set(ctx: Context, ms: Int): Boolean {
        if (!canWrite(ctx)) return false
        if (ms != NEVER) Prefs.screenTimeout = ms
        return try { Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, ms) } catch (e: Exception) { false }
    }

    /** Anything over a day counts as never (some apps use other big numbers). */
    fun isNever(ms: Int) = ms > 86_400_000

    fun nearestStep(ms: Int) = if (isNever(ms)) STEPS.lastIndex else STEPS.indices.minBy { abs(STEPS[it].toLong() - ms) }

    fun label(ms: Int): String = when {
        isNever(ms) -> "Never"
        ms < 60_000 -> "${ms / 1000} s"
        ms < 3_600_000 -> "${ms / 60_000} min"
        ms == 3_600_000 -> "1 hour"
        else -> "${"%.1f".format(ms / 3_600_000f).removeSuffix(".0")} hours"
    }

    fun awake(ctx: Context) = isNever(get(ctx))

    /** Keep awake on: remember the timeout and switch to Never. Off: back to the remembered timeout. */
    fun toggleAwake(ctx: Context): Boolean {
        if (awake(ctx)) return set(ctx, Prefs.screenTimeout.takeIf { it in 5_000..86_400_000 } ?: 120_000)
        if (!canWrite(ctx)) return false
        Prefs.screenTimeout = get(ctx)
        return set(ctx, NEVER)
    }

    fun permissionIntent(ctx: Context) = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${ctx.packageName}"))

    /** Ask Android to add the Keep awake tile to quick settings (Android 13+ shows a one-tap dialog). */
    fun requestTile(act: HomeActivity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            act.toast("Edit quick settings and drag “Keep awake” in"); return
        }
        val sbm = act.getSystemService(StatusBarManager::class.java)
        try {
            sbm.requestAddTileService(ComponentName(act, AwakeTile::class.java), act.getString(R.string.awake_tile),
                Icon.createWithResource(act, R.drawable.ic_tile_awake), act.mainExecutor) { r ->
                when (r) {
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> { Prefs.tileAdded = true; act.toast("Added. It's at the end of quick settings"); act.refreshSettings() }
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> { Prefs.tileAdded = true; act.toast("It's already in quick settings"); act.refreshSettings() }
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> {}
                    else -> act.toast("Couldn't add it. Edit quick settings and drag “Keep awake” in")
                }
            }
        } catch (e: Exception) { act.toast("Couldn't add it. Edit quick settings and drag “Keep awake” in") }
    }
}

/** Quick settings tile: tap to keep the screen on (timeout Never), tap again to go back to your timeout. */
class AwakeTile : TileService() {
    override fun onTileAdded() { Prefs.init(this); Prefs.tileAdded = true }
    override fun onTileRemoved() { Prefs.init(this); Prefs.tileAdded = false }
    override fun onStartListening() { Prefs.init(this); update() }

    override fun onClick() {
        Prefs.init(this)
        if (!ScreenTimeout.canWrite(this)) {
            // First use: Android needs the user to allow TabDesk to change the timeout.
            val i = ScreenTimeout.permissionIntent(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE))
            else @Suppress("DEPRECATION") startActivityAndCollapse(i)
            return
        }
        ScreenTimeout.toggleAwake(this)
        update()
    }

    private fun update() {
        val t = qsTile ?: return
        val can = ScreenTimeout.canWrite(this)
        val on = can && ScreenTimeout.awake(this)
        t.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = getString(R.string.awake_tile)
        t.subtitle = when {
            !can -> "Tap to set up"
            on -> "Screen stays on"
            else -> "Off after ${ScreenTimeout.label(ScreenTimeout.get(this))}"
        }
        t.updateTile()
    }
}
