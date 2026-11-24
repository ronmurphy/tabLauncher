package dev.tabdesk.customize

import android.content.Intent
import android.provider.Settings
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import dev.tabdesk.*
import dev.tabdesk.helper.HelperClient
import dev.tabdesk.helper.Pairing
import dev.tabdesk.helper.Setup

// Customize → Helper: what the running-apps helper is, and its setup, step by step.

internal fun Page.helper() {
    section("Running apps")
    addNote("TabDesk’s helper lets the taskbar show which apps are still running, and close them. It works " +
        "through Android’s wireless debugging, which TabDesk pairs with once, the way a computer would. " +
        "It all stays on this tablet.")

    section("Set up")
    val s = Setup.status(act)
    step(1, s.devOptions, "Turn on Developer options", Setup.devOptionsHow(), "Open") { Setup.openAbout(act) }
    step(2, s.wifi, "Connect to Wi-Fi", "Wireless debugging only works on Wi-Fi.", "Wi-Fi") {
        act.safeStart(Intent(Settings.ACTION_WIFI_SETTINGS))
    }
    step(3, s.wireless, "Turn on Wireless debugging",
        "Tap Open and switch on Use wireless debugging. If Android asks, allow it on this network.", "Open") {
        Setup.openWirelessDebugging(act)
    }
    val pairHow = if (Pairing.active)
        "Waiting for you: in Wireless debugging, tap “Pair device with pairing code”, then pull down " +
            "TabDesk’s notification and type the code into it."
    else "Tap Pair. In Wireless debugging, tap “Pair device with pairing code”, then pull down " +
        "TabDesk’s notification and type the code into it."
    step(4, s.paired, "Pair TabDesk", if (s.paired) "TabDesk is paired. It shows as “TabDesk” under Paired devices." else pairHow,
        if (s.paired) "Pair again" else "Pair", always = true) { Setup.pair(act) }
    step(5, s.finished, "Let TabDesk finish setting up",
        if (s.finished) "Done. TabDesk can switch wireless debugging on by itself when it needs it."
        else "Tap Allow to let TabDesk, through wireless debugging:\n" +
            "• switch wireless debugging on by itself, e.g. after a restart\n" +
            "• show your recently used apps on the taskbar\n" +
            "• add widgets without asking each time\n" +
            "• show notification dots on apps\n" +
            "• let you turn on double-tap to lock in Accessibility settings\n" +
            "TabDesk also keeps USB debugging on, so the helper keeps running when wireless debugging is off." +
            (Setup.problem?.let { "\n\nThat didn't work: $it." } ?: ""),
        if (Setup.finishing) "Working…" else "Allow", enabled = s.paired) { Setup.finish(act) }

    if (s.paired) {
        section("Helper")
        val h = HelperClient
        val open = h.tasks.count { it.running }
        when {
            h.connected -> addNote("Running. ${if (open == 1) "1 app is" else "$open apps are"} open right now.")
            h.starting -> addNote("Starting…")
            else -> noteWithButton("Not running" + (h.problem?.let { ": $it" } ?: "") + ".",
                act.overlays.button("Start", true) { HelperClient.ensure(act) })
        }
    }
}

/** One setup step: its number (a tick once done), what to do, and the button that starts it. */
private fun Page.step(n: Int, done: Boolean, title: String, how: String, action: String, always: Boolean = false,
                      enabled: Boolean = true, onClick: () -> Unit) {
    val row = linear(act, false) { gravity = Gravity.TOP; setPadding(0, act.dp(6), 0, act.dp(8)) }
    val badge = FrameLayout(act).apply { background = rounded(if (done) pal.accent else pal.field, act.dpf(13)) }
    if (done) badge.addView(ImageView(act).apply { setImageDrawable(Glyph(G.CHECK, pal.onAccent)) },
        FrameLayout.LayoutParams(act.dp(16), act.dp(16), Gravity.CENTER))
    else badge.addView(TextView(act).label(pal.text, 13f, bold = true).apply { text = "$n"; gravity = Gravity.CENTER },
        FrameLayout.LayoutParams(MATCH, MATCH))
    row.addView(badge, llp(act.dp(26), act.dp(26)).apply { topMargin = act.dp(1) })

    val text = linear(act, true)
    text.addView(TextView(act).label(pal.text, 14.5f).apply { this.text = title })
    text.addView(note(how).apply { if (done && !always) visibility = android.view.View.GONE })
    row.addView(text, llp(0, WRAP, 1f).apply { marginStart = act.dp(12) })

    if ((!done || always) && enabled)
        row.addView(act.overlays.button(action, !done, onClick), llp(WRAP, act.dp(38)).apply { marginStart = act.dp(10) })
    add(row)
}
