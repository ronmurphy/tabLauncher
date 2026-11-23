package dev.tabdesk.customize

import android.content.Intent
import android.provider.Settings
import android.view.Gravity
import android.widget.TextView
import dev.tabdesk.*

// Customize → Taskbar & Start: taskbar size, recent apps, hidden apps.


internal fun Page.taskbarStart() {
    section("Taskbar")
    val sizes = listOf(48, 56, 64)
    segmented(listOf("Small", "Medium", "Large"), sizes.indexOf(Prefs.taskbarDp)) { Prefs.taskbarDp = sizes[it]; act.applyThemeChange() }
    add(switchRow(act, "Show recently used apps", Prefs.recents) {
        Prefs.recents = it; Recents.refresh(act) { act.taskbar.bindRecents() }
    }, llp(MATCH, WRAP).apply { topMargin = act.dp(6) })
    if (!Recents.granted(act)) {
        noteWithButton("Needs usage access to see which apps you used.", act.overlays.button("Grant", true) {
            act.safeStart(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, android.net.Uri.parse("package:${act.packageName}")))
        })
        addNote("Or: adb shell appops set ${act.packageName} GET_USAGE_STATS allow")
    }

    section("Hidden from Start")
    val hidden = Apps.all.filter { Prefs.isHidden(it.key) }
    val hiddenList = linear(act, true)
    val emptyNote = note("No hidden apps. Long-press an app in Start and choose Hide.")
    if (hidden.isEmpty()) hiddenList.addView(emptyNote)
    hidden.forEach { app ->
        val r = linear(act, false) { gravity = Gravity.CENTER_VERTICAL; setPadding(0, act.dp(4), 0, act.dp(4)) }
        r.addView(IconView(act).apply { bitmap = app.icon }, llp(act.dp(30), act.dp(30)))
        r.addView(TextView(act).label(pal.text, 14.5f).apply { text = app.label; setPadding(act.dp(12), 0, 0, 0) }, llp(0, WRAP, 1f))
        r.addView(act.overlays.button("Unhide", false) {
            Prefs.setHidden(app.key, false); Apps.notifyChanged()
            hiddenList.removeView(r)
            if (hiddenList.childCount == 0) hiddenList.addView(emptyNote)
        }, llp(WRAP, act.dp(36)))
        hiddenList.addView(r, llp(MATCH, WRAP))
    }
    add(hiddenList)
}
