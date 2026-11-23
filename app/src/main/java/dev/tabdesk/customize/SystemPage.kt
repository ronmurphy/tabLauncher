package dev.tabdesk.customize

import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import android.widget.TextView
import dev.tabdesk.*

// Customize → Screen & system: screen timeout, status bar, notification dots, home app.


internal fun Page.system() {
    section("Screen timeout")
    if (!ScreenTimeout.canWrite(act)) {
        noteWithButton("To set the screen timeout (any length, or never), TabDesk needs permission to modify system settings.",
            act.overlays.button("Grant", true) { act.safeStartForResult(ScreenTimeout.permissionIntent(act), ScreenTimeout.REQ_PERMISSION) })
    } else {
        val now = ScreenTimeout.get(act)
        val tv = TextView(act).label(pal.textDim, 13f).apply { text = ScreenTimeout.label(now) }
        val steps = ScreenTimeout.STEPS
        add(sliderRow(act, "Turn the screen off after", tv, 0, steps.lastIndex, 1, ScreenTimeout.nearestStep(now)) { i ->
            ScreenTimeout.set(act, steps[i]); tv.text = ScreenTimeout.label(steps[i])
        })
        addNote("Never keeps the screen on until you lock it: double-tap the desktop or press the power button.")
    }
    val tileText = "The Keep awake tile in quick settings switches between your timeout and never."
    if (Prefs.tileAdded) add(note("$tileText It's in your quick settings.").apply { setPadding(act.dp(2), act.dp(10), 0, 0) })
    else noteWithButton(tileText, act.overlays.button("Add tile", false) { ScreenTimeout.requestTile(act) }, top = 6)

    section("Status bar")
    add(switchRow(act, "Hide Android's status bar on the desktop", Prefs.hideStatus) { Prefs.hideStatus = it; act.applyStatusBar() })
    addNote("Swipe down from the top edge to show it again; swipe twice for notifications.")

    section("Notification dots")
    val cn = ComponentName(act, DotsService::class.java)
    val granted = act.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(cn)
    add(switchRow(act, "Show dots on apps with notifications", Prefs.dots) { Prefs.dots = it; act.onBadgesChanged() })
    if (!granted) {
        noteWithButton("TabDesk needs notification access to show dots.", act.overlays.button("Grant", true) {
            act.safeStart(Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, cn.flattenToString()))
        })
        addNote("If Android blocks it as a restricted setting, run:\nadb shell cmd notification allow_listener ${cn.flattenToShortString()}")
    }

    section("Home app")
    val rm = act.getSystemService(RoleManager::class.java)
    if (rm.isRoleHeld(RoleManager.ROLE_HOME)) addNote("TabDesk is your home app.")
    else noteWithButton("TabDesk isn't your default home app yet.", act.overlays.button("Make default", true) {
        act.safeStartForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), 21)
    })
}
