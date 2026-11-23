package dev.tabdesk.customize

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import dev.tabdesk.*

// Customize → Look: theme, accent colour, font.


internal fun Page.look() {
    section("Theme")
    segmented(listOf("System", "Light", "Dark"), Prefs.themeMode) { Prefs.themeMode = it; act.applyThemeChange() }

    section("Accent colour")
    val row = linear(act, false) { gravity = Gravity.CENTER_VERTICAL }
    val sz = act.dp(36)
    val swatchNames = listOf("Wallpaper colour", "Blue", "Teal", "Green", "Yellow", "Orange", "Red", "Pink", "Purple", "Slate")
    Palette.PRESETS.forEachIndexed { i, c ->
        val color = if (c == 0) Palette.wallpaperAccent(act, pal.dark) else c
        val sel = Prefs.accent == c
        val sw = FrameLayout(act).apply {
            background = oval(color)
            foreground = if (sel) android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL; setStroke(act.dp(3), pal.text)
            } else null
            contentDescription = "${swatchNames.getOrElse(i) { "Colour" }} accent"
            val g = when { sel -> G.CHECK; c == 0 -> G.IMAGE; else -> 0 }
            if (g != 0) addView(ImageView(act).apply {
                setImageDrawable(Glyph(g, if (Color.luminance(color) > 0.45f) 0xFF111111.toInt() else Color.WHITE))
            }, flp(act.dp(18), act.dp(18), Gravity.CENTER))
            setOnClickListener { if (!sel) { Prefs.accent = c; act.applyThemeChange() } }
        }
        row.addView(sw, llp(sz, sz).apply { marginEnd = act.dp(8) })
    }
    add(android.widget.HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; addView(row) })
    addNote("The first swatch follows your wallpaper's colours.")

    section("Font")
    val fontRow = linear(act, false) { gravity = Gravity.CENTER_VERTICAL }
    fontRow.addView(TextView(act).label(pal.text, 17f).apply {
        text = when { Prefs.font == null -> "System default"; Fonts.isMissing(act) -> "${Prefs.font}  (downloading…)"; else -> Prefs.font }
    }, llp(0, WRAP, 1f))
    val fonts = Fonts.available(act)
    val fontFiles = Fonts.files(act)
    if (fontFiles.isNotEmpty()) {
        lateinit var saveBtn: View
        saveBtn = act.overlays.button("Save font file", false) {
            if (fontFiles.size == 1) Fonts.save(act, fontFiles[0])
            else act.overlays.menu(saveBtn.rectIn(act.root), fontFiles.map { f ->
                MenuEntry("${Prefs.font} ${f.style}", G.SAVE) { Fonts.save(act, f) }
            }, "Save which weight?")
        }
        fontRow.addView(saveBtn, llp(WRAP, act.dp(38)).apply { marginEnd = act.dp(6) })
    }
    if (fonts) fontRow.addView(act.overlays.button("Change", true) { act.showFontPicker() }, llp(WRAP, act.dp(38)))
    add(fontRow)
    addNote(when {
        !fonts -> "Google Fonts need Google Play services, which this device doesn't have."
        fontFiles.isNotEmpty() -> "Used for all of TabDesk's text; other apps keep their own fonts. Save font file puts a copy in Downloads or Drive so reading apps (Moon+ Reader, ReadEra, KOReader…) can use it too."
        else -> "Choose from over a thousand Google Fonts for all of TabDesk's text. Other apps keep their own fonts."
    })
}
