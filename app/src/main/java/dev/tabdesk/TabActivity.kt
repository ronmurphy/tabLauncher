package dev.tabdesk

import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.view.WindowInsets
import android.widget.Toast

/**
 * What TabDesk's windows share: the home screen ([HomeActivity]) and TabDesk Files ([FilesActivity]). Menus, dialogs
 * and the file actions work on either.
 */
abstract class TabActivity : Activity() {
    lateinit var pal: Palette; protected set
    lateinit var overlays: Overlays; protected set

    /** A floating window covers this one, so menus go in a window above it (only the home screen does that). */
    open val coveredByWindows: Boolean get() = false

    fun hideIme() { window.insetsController?.hide(WindowInsets.Type.ime()) }

    fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    fun safeStart(i: Intent) {
        try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { toast("Nothing to open that") }
    }

    /** Floating windows: the maker says so, or Developer options → "Enable freeform windows" is on. */
    val canFloat: Boolean by lazy {
        packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT) ||
            android.provider.Settings.Global.getInt(contentResolver, "enable_freeform_support", 0) != 0
    }

    /** Options that open an activity in a floating (freeform) window, centred and sized for the orientation. */
    fun floatingOptions(): Bundle {
        val b = windowManager.currentWindowMetrics.bounds
        val land = b.width() > b.height()
        val w = (b.width() * if (land) 0.5f else 0.74f).toInt()
        val h = (b.height() * if (land) 0.74f else 0.5f).toInt()
        val l = (b.width() - w) / 2
        val t = ((b.height() - h) / 2.6f).toInt()
        val opts = ActivityOptions.makeBasic().setLaunchBounds(Rect(l, t, l + w, t + h)).toBundle()
        // Launch bounds alone are ignored unless the window is freeform. The setter for that is hidden API
        // (blocked on Android 15), but the system reads the mode from this bundle key, so set it directly.
        opts.putInt("android.activity.windowingMode", 5 /* WINDOWING_MODE_FREEFORM */)
        return opts
    }
}
