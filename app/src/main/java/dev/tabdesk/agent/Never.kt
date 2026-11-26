package dev.tabdesk.agent

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.net.Uri
import android.provider.Settings
import android.view.inputmethod.InputMethodManager

/**
 * The never list (docs/agent-control.md, Part 3): apps the agent can never read or use, and that can't be allowed.
 * It's worked out on the tablet each time rather than hard-coded, since makers rename these apps.
 */
object Never {
    /** Package → the name it may be called by ("Settings", "the keyboard"…). */
    fun list(ctx: Context): Map<String, String> {
        val pm = ctx.packageManager
        val m = LinkedHashMap<String, String>()
        fun put(pkg: String?, name: String) { if (!pkg.isNullOrEmpty() && pkg !in m) m[pkg] = name }
        fun resolved(i: Intent) = try {
            pm.queryIntentActivities(i, PackageManager.MATCH_DEFAULT_ONLY).map { it.activityInfo.packageName }
        } catch (_: Exception) { emptyList() }

        put(ctx.packageName, "TabDesk")
        put("android", "Android's own dialogs")
        put(systemComponent("config_systemUIServiceComponent"), "System UI")
        // Recents is the system launcher's on newer Android (Lenovo's ZUI: com.zui.launcher).
        put(systemComponent("config_recentsComponentName"), "the Recents screen")
        resolved(Intent(Settings.ACTION_SETTINGS)).forEach { put(it, "Settings") }
        resolved(Intent("android.content.pm.action.REQUEST_PERMISSIONS")).forEach { put(it, "the permission controller") }
        resolved(Intent(Intent.ACTION_INSTALL_PACKAGE).setDataAndType(Uri.parse("content://x/y.apk"),
            "application/vnd.android.package-archive")).forEach { put(it, "the package installer") }
        resolved(Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.parse("package:x"))).forEach { put(it, "the package installer") }
        try {
            ctx.getSystemService(InputMethodManager::class.java).enabledInputMethodList.forEach { put(it.packageName, "the keyboard") }
        } catch (_: Exception) {}
        put("com.google.android.gms", "Google Play services")
        resolved(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"))
            .forEach { put(it, "the file picker") }
        // Every app that has asked to use the tablet, approved or not: DroidACP's chat has the agent's own Allow and
        // Deny buttons.
        (Agent.approvals().map { it.pkg } + Agent.asked().map { it.pkg }).forEach { put(it, Agent.label(it)) }
        return m
    }

    /** The package of a component Android names in its own resources, e.g. System UI's. */
    private fun systemComponent(name: String): String? = try {
        val id = Resources.getSystem().getIdentifier(name, "string", "android")
        if (id == 0) null else ComponentName.unflattenFromString(Resources.getSystem().getString(id))?.packageName
    } catch (_: Exception) { null }
}
