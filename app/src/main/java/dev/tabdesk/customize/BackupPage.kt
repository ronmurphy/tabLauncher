package dev.tabdesk.customize

import android.view.Gravity
import dev.tabdesk.*

// Customize → Backup: export, import and undo.


internal fun Page.backup() {
    section("Backup")
    val drive = Backup.driveInstalled(act)
    val br = linear(act, false) { gravity = Gravity.CENTER_VERTICAL }
    fun backupButton(text: String, filled: Boolean, onClick: () -> Unit) = act.overlays.button(text, filled, onClick).apply {
        isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END
        setPadding(act.dp(10), 0, act.dp(10), 0)
    }
    br.addView(backupButton(if (drive) "Export to Google Drive" else "Export backup", true) { Backup.export(act) }, llp(0, act.dp(40), 1f))
    br.addView(backupButton(if (drive) "Import from Google Drive" else "Import backup", false) { Backup.import(act) },
        llp(0, act.dp(40), 1f).apply { marginStart = act.dp(8) })
    add(br)
    addNote("Saves your desktop, folders, taskbar, hidden and renamed apps, picked icons, icon themes, font and Customize settings as one small file. " +
        (if (drive) "In the file picker, choose Drive (tap ☰ the first time); it opens there after that. " else "") +
        "Widgets come back on this device; on a new one, some may need adding again.")
    if (Backup.canUndo(act))
        noteWithButton("Not happy with the last import?", act.overlays.button("Undo import", false) { Backup.undo(act) }, top = 6)
}
