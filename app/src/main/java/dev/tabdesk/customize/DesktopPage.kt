package dev.tabdesk.customize

import android.widget.TextView
import dev.tabdesk.*

// Customize → Desktop: icon size, grid, labels, gestures.


internal fun Page.desktop() {
    section("Icons and grid")
    val gridValue = TextView(act).label(pal.textDim, 13f)
    fun showGrid() { val (c, r) = act.desktop.gridPreview(); gridValue.text = "$c × $r" }
    val iconValue = TextView(act).label(pal.textDim, 13f).apply { text = "${Prefs.iconDp}" }
    add(sliderRow(act, "Icon size", iconValue, 40, 72, 4, Prefs.iconDp) {
        Prefs.iconDp = it; iconValue.text = "$it"; act.desktop.applyMetrics(); showGrid()
    })
    add(sliderRow(act, "Grid", gridValue, 72, 140, 4, Prefs.cellDp, invert = true) {
        Prefs.cellDp = it; act.desktop.applyMetrics(); showGrid()
    })
    showGrid()
    add(switchRow(act, "Show icon labels", Prefs.labels) { Prefs.labels = it; act.desktop.applyMetrics(); showGrid() })
    addNote("Icons keep their places when the grid changes; widgets keep their size in cells. Drop one app on another to make a folder.")

    section("Gestures")
    add(switchRow(act, "Double-tap the desktop to lock the screen", Prefs.doubleTapLock) { Prefs.doubleTapLock = it })
    if (!LockService.enabled) addNote("Needs “TabDesk screen lock” on in Accessibility settings. Double-tap the desktop to set it up.")
    addNote("Swipe up on the desktop for Start, down for Android's notifications.")
}
