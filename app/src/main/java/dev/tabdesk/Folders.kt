package dev.tabdesk

import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

// Desktop folders. Made by dropping one app onto another (see DesktopLayout.merge); opening one
// shows its apps in a popup next to the folder icon.

/** Popup with the folder's apps. Tap opens, long-press for the app menu, long-press and move to drag one out. */
fun HomeActivity.openFolder(folder: DesktopItem, from: View) {
    overlays.dismissAll()
    val apps = folder.apps.mapNotNull { Apps.get(it) }
    if (apps.isEmpty()) return
    val card = linear(this, true) {
        background = rounded(pal.surfaceSolid, dpf(22), 1, pal.divider)
        elevation = dpf(18)
        setPadding(dp(10), dp(8), dp(10), dp(10))
    }
    lateinit var layer: Overlays.Layer
    card.addView(TextView(this).label(pal.text, 16f, bold = true).apply {
        text = folder.label ?: "Folder"
        gravity = Gravity.CENTER
        isSingleLine = true; ellipsize = TextUtils.TruncateAt.END
        setPadding(dp(12), dp(8), dp(12), dp(10))
        background = rippleBg(pal.ripple, dpf(12))
        contentDescription = "Rename folder"
        setOnClickListener { overlays.dismiss(layer); renameFolder(folder) }
    }, llp(MATCH, WRAP))

    val cols = min(4, max(2, ceil(sqrt(apps.size.toDouble())).toInt()))
    val grid = linear(this, true)
    for (chunk in apps.chunked(cols)) {
        val row = linear(this, false)
        chunk.forEach { app -> row.addView(folderTile(folder, app, from) { layer }, llp(dp(88), WRAP)) }
        grid.addView(row, llp(WRAP, WRAP))
    }
    card.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(grid) }, llp(WRAP, WRAP))
    layer = overlays.show(card, from.rectIn(root))
}

private fun HomeActivity.folderTile(folder: DesktopItem, app: AppInfo, from: View, layer: () -> Overlays.Layer): View {
    val t = linear(this, true) {
        gravity = Gravity.CENTER_HORIZONTAL
        background = rippleBg(pal.ripple, dpf(14))
        setPadding(dp(4), dp(8), dp(4), dp(8))
        contentDescription = app.label
    }
    val icon = IconView(this).apply { bitmap = app.icon; dotColor = pal.accent; dotRing = pal.surfaceSolid; dot = Badges.has(app.pkg) }
    t.addView(icon, llp(dp(50), dp(50)))
    t.addView(TextView(this).label(pal.text, 12.5f).apply {
        text = app.label; maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, dp(6), 0, 0)
    }, llp(MATCH, WRAP))
    t.setOnTouchListener(PressHelper(t,
        onTap = { overlays.dismiss(layer()); launch(app, icon) },
        onMiddle = { overlays.dismiss(layer()); launchInWindow(app, icon) },
        onLong = { v, _, _ ->
            appMenu(app, v, extra = MenuEntry("Remove from folder", G.CLOSE) {
                desktop.removeFromFolder(folder, app.key)
                overlays.dismiss(layer())
                // Still a folder? Reopen it so several apps can be taken out in a row.
                if (folder.apps.size >= 2) desktop.viewOf(folder)?.let { openFolder(folder, it) }
            })
        },
        onDrag = { v, _, _ ->
            val size = dp(58)
            val p = DragPayload(DragPayload.FOLDER, app.key, null, size / 2f, size / 2f, size, size, null, folder)
            v.startDragAndDrop(null, BitmapShadow(app.icon, size), p, View.DRAG_FLAG_OPAQUE)
            overlays.dismissAll()
        }))
    return t
}

fun HomeActivity.renameFolder(folder: DesktopItem) {
    overlays.prompt("Rename folder", folder.label ?: "Folder", "Folder", null) { name ->
        folder.label = name.trim().ifEmpty { "Folder" }
        desktop.save(); desktop.refreshApps()
    }
}

fun HomeActivity.folderMenu(folder: DesktopItem, anchor: View) {
    overlays.menu(anchor.rectIn(root), listOf(
        MenuEntry("Rename", G.EDIT) { renameFolder(folder) },
        MenuEntry("Ungroup", G.GRID) { desktop.ungroup(folder) },
        MenuEntry("Remove from desktop", G.CLOSE) {
            desktop.remove(folder)
            toast("Folder removed. Its apps are still in Start")
        },
    ), folder.label ?: "Folder")
}
