package dev.tabdesk.customize

import android.content.Intent
import android.net.Uri
import dev.tabdesk.*
import dev.tabdesk.Updates.State

// Customize → About: TabDesk's version and its updates.

internal fun Page.about() {
    val u = Updates
    val mine = u.current(act)
    val r = u.available

    section("Updates")
    fun button(text: String, filled: Boolean, enabled: Boolean = true, onClick: () -> Unit) =
        act.overlays.button(text, filled, onClick).apply { isEnabled = enabled; alpha = if (enabled) 1f else 0.55f }
    when (u.state) {
        State.IDLE -> noteWithButton("You have TabDesk $mine.", button("Check for updates", true) { u.check(act) })
        State.CHECKING -> noteWithButton("Asking GitHub for the newest version…", button("Checking…", true, enabled = false) {})
        State.LATEST -> noteWithButton("TabDesk $mine is the newest version.", button("Check again", false) { u.check(act) })
        State.AVAILABLE -> noteWithButton("TabDesk ${r?.version} is available. You have $mine.", button("Update", true) { u.update(act) })
        State.NEEDS_PERMISSION -> noteWithButton("Android needs your OK first: switch on “Allow from this source” for TabDesk, " +
            "then come back here and the update carries on.", button("Open", true) { u.update(act) })
        State.DOWNLOADING -> noteWithButton("Downloading TabDesk ${r?.version}… ${u.progress}%",
            button("Updating…", true, enabled = false) {})
        State.INSTALLING -> noteWithButton("Installing. TabDesk closes for a moment and comes back as ${r?.version}.",
            button("Updating…", true, enabled = false) {})
        State.FAILED -> noteWithButton("That didn’t work: ${u.problem}.",
            button("Try again", true) { if (r != null) u.update(act) else u.check(act) })
    }
    if (r != null && r.notes.isNotBlank() && u.state != State.LATEST) {
        addNote("What’s new in ${r.version}:\n${r.notes}")
    }

    add(switchRow(act, "Check automatically", Prefs.autoUpdates) { Prefs.autoUpdates = it },
        llp(MATCH, WRAP).apply { topMargin = act.dp(14) })
    addNote("Once a day, TabDesk asks GitHub for its newest version number and lets you know when there’s a new one. " +
        "Nothing about you or this tablet is sent. Updating needs “Install unknown apps” allowed for TabDesk once; " +
        "if the helper is set up, TabDesk allows that itself.")

    section("TabDesk")
    noteWithButton("Version $mine. Every version and what changed in it is on GitHub.",
        button("Releases", false) { act.safeStart(Intent(Intent.ACTION_VIEW, Uri.parse(Updates.RELEASES))) })
}
