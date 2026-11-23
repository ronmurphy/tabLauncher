package dev.tabdesk.customize

import dev.tabdesk.icons.*
import android.graphics.Bitmap
import android.view.Gravity
import android.view.View
import android.widget.TextView
import dev.tabdesk.*

// Customize → Icons: saved themes, style, colours, effect, icon pack.


internal fun Page.icons() {
    // Each option shows a real app (the first one on the taskbar) drawn that way.
    val sample = Prefs.pins.firstNotNullOfOrNull { Apps.get(it) } ?: Apps.all.firstOrNull()
    val base = Icons.spec
    themes(base)

    section("Style")
    iconChoices(sample, Icons.STYLES, Prefs.iconStyle, Icons.STYLES.indices.map { base.copy(style = it) }) { i ->
        Prefs.iconStyle = i; act.applyThemeChange()
    }
    if (Prefs.iconStyle == Icons.ORIGINAL) {
        addNote("Redraw every app the same way: in your accent colour, each app's own colours, or a colour set.")
    } else {
        addNote((if (Prefs.iconPack != null) "Apps are drawn from your icon pack's line art, then their own one-colour icon"
            else "Apps are drawn from their own one-colour icon") + " where they have one, otherwise a matching symbol " +
            "or their initial. Long-press an app and choose Change icon to pick one yourself.")
        section("Colours")
        val ids = IconColours.CHOICES
        iconChoices(sample, ids.map { it.second }, ids.indexOfFirst { it.first == Prefs.iconColours },
            ids.map { base.copy(colours = it.first) }) { i -> Prefs.iconColours = ids[i].first; act.applyThemeChange() }
        addNote("Auto picks black or white to stand out on your wallpaper and on the taskbar. The sets suit every " +
            "style; try Black on white, Sakura or Ukiyo-e with Watercolour, Chalkboard, Blueprint or Synthwave " +
            "with Inked, Winter or Summer with Themed, or Riso or Terminal with Minimal.")
    }

    section("Effect")
    val fx = IconEffects.ALL
    iconChoices(sample, fx.map { it.second }, fx.indexOfFirst { it.first == Prefs.iconEffect },
        fx.map { base.copy(effect = it.first) }) { i -> Prefs.iconEffect = fx[i].first; act.applyThemeChange() }
    addNote("Applied last, over the style, colours and pack. 8-bit and 16-bit turn apps into game sprites of 3 or 15 " +
        "colours; Glass and Neon glass turn an app's own background into tinted or smoked glass. Try 8-bit with " +
        "Original, Halftone with Riso, Sticker with Summer, Neon with Synthwave, or CRT with Terminal.")

    iconPacks(base)
}

/** Saved icon themes: tap to use, long-press to rename, export or delete; save or import more. */
private fun Page.themes(base: Icons.Spec) {
    section("Your themes")
    val themes = IconThemes.all()
    val active = IconThemes.active()
    val samples = (Prefs.pins.mapNotNull { Apps.get(it) } + Apps.all).distinct().take(3)
    val row = linear(act, false)
    var selectedIndex = -1
    themes.forEachIndexed { idx, t ->
        val on = active?.name == t.name
        if (on) selectedIndex = idx
        val icons = List(samples.size) { IconView(act) }
        val card = linear(act, true) {
            setPadding(act.dp(10), act.dp(10), act.dp(10), act.dp(8))
            background = if (on) rounded(pal.accentSoft, act.dpf(14)) else rippleBg(pal.ripple, act.dpf(14))
            contentDescription = t.name
            val strip = linear(act, false) { gravity = Gravity.CENTER_VERTICAL }
            icons.forEach { strip.addView(it, llp(act.dp(36), act.dp(36)).apply { marginEnd = act.dp(3) }) }
            addView(strip)
            addView(TextView(act).label(pal.text, 13.5f, bold = true).apply {
                text = t.name; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(act.dp(2), act.dp(8), 0, 0)
            }, llp(MATCH, WRAP))
            addView(TextView(act).label(pal.textDim, 11.5f).apply {
                text = t.describe(act); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(act.dp(2), act.dp(2), 0, 0)
            }, llp(MATCH, WRAP))
            setOnClickListener { if (!on) IconThemes.apply(act, t) }
            setOnLongClickListener { v -> themeMenu(v, t); true }
        }
        row.addView(card, llp(act.dp(150), MATCH).apply { marginEnd = act.dp(8) })
        val spec = base.copy(style = t.style, colours = t.colours, pack = t.pack, effect = t.effect)
        Apps.previewSet(samples, spec, t.icons) { bs -> bs.forEachIndexed { i, b -> icons.getOrNull(i)?.bitmap = b } }
    }
    val scroller = android.widget.HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; addView(row) }
    add(scroller)
    if (selectedIndex > 0) scroller.post { row.getChildAt(selectedIndex)?.let { scroller.scrollTo((it.left - act.dp(48)).coerceAtLeast(0), 0) } }

    val undo = IconThemes.undo
    val bar = linear(act, false) { gravity = Gravity.CENTER_VERTICAL; setPadding(0, act.dp(6), 0, 0) }
    bar.addView(note(when {
        undo != null && active?.name == undo.first -> "Switched to “${undo.first}”."
        active == null -> "Your icons right now aren't saved as a theme."
        else -> "Tap a theme to use it; long-press one to rename, share or delete it."
    }), llp(0, WRAP, 1f))
    fun button(text: String, filled: Boolean, onClick: () -> Unit) =
        bar.addView(act.overlays.button(text, filled, onClick), llp(WRAP, act.dp(38)).apply { marginStart = act.dp(8) })
    if (undo != null && active?.name == undo.first) button("Undo", false) { IconThemes.undoApply(act) }
    if (active == null) button("Save as theme", true) {
        act.overlays.prompt("Save icon theme", IconThemes.freeName(IconThemes.current().describe(act)), "Theme name", null) { name ->
            IconThemes.saveCurrent(name.trim().ifEmpty { "My icons" }.take(60)); act.refreshSettings()
        }
    }
    button("Import", false) { IconThemes.import(act) }
    add(bar)
}

private fun Page.themeMenu(anchor: View, t: IconThemes.Theme) {
    act.overlays.menu(anchor.rectIn(act.root), listOf(
        MenuEntry("Rename", G.EDIT) {
            act.overlays.prompt("Rename theme", t.name, "Theme name", null) { name ->
                if (name.isNotBlank()) IconThemes.rename(t.name, name); act.refreshSettings()
            }
        },
        MenuEntry("Export to a file", G.SAVE) { IconThemes.export(act, t) },
        MenuEntry("Delete", G.TRASH) {
            val card = act.overlays.dialogCard(act.dp(420), "Delete “${t.name}”?")
            card.addView(TextView(act).label(pal.text, 14.5f).apply {
                text = "Your icons stay as they are; only the saved theme goes."
            }, llp(MATCH, WRAP).apply { bottomMargin = act.dp(16) })
            val buttons = linear(act, false) { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
            buttons.addView(act.overlays.button("Cancel", false) { act.overlays.dismissTop() }, llp(WRAP, act.dp(40)))
            buttons.addView(act.overlays.button("Delete", true) { IconThemes.delete(t.name); act.refreshSettings() },
                llp(WRAP, act.dp(40)).apply { marginStart = act.dp(8) })
            card.addView(buttons, llp(MATCH, WRAP))
            act.overlays.show(card, dim = true)
        },
    ), t.name)
}

/** Icon packs from the Play Store (Nova / ADW format), each previewed with an app it has an icon for. */
private fun Page.iconPacks(base: Icons.Spec) {
    section("Icon pack")
    val packs = IconPacks.installed(act)
    val current = Prefs.iconPack
    if (packs.isEmpty()) {
        noteWithButton(if (current != null) "The icon pack you chose isn't installed any more, so apps use their own icons " +
                "until it's back. There are thousands of others on the Play Store, many free."
            else "TabDesk uses icon packs made for Nova, ADW and most other launchers. You don't have one installed yet; " +
                "there are thousands on the Play Store, many free.",
            act.overlays.button("Find packs", filled = false) { act.openPlayStoreSearch("icon pack") })
        return
    }
    val selected = if (current == null) 0 else packs.indexOfFirst { it.pkg == current }.let { if (it < 0) -1 else it + 1 }
    val candidates = (Prefs.pins.mapNotNull { Apps.get(it) } + Apps.all).distinct()
    iconChoices(candidates.firstOrNull(), listOf("None") + packs.map { it.label }, selected,
        listOf(base.copy(pack = null)) + packs.map { base.copy(pack = it.pkg) },
        previews = { done -> Apps.packPreviews(candidates, listOf(null) + packs.map { it.pkg }, base, done) }) { i ->
        Prefs.iconPack = if (i == 0) null else packs[i - 1].pkg
        act.applyThemeChange()
    }
    val styled = Prefs.iconStyle != Icons.ORIGINAL
    addNote(when {
        current != null && selected < 0 -> "The pack you chose before isn't installed; apps follow the icon style until it is."
        styled -> "With ${Icons.STYLES[Prefs.iconStyle]}, a line-art pack (one colour, like Arcticons) is redrawn in your " +
            "icon colours; a full-colour pack shows as it is with Original. Change icon lets you pick any icon from a pack."
        else -> "Apps the pack has get its icon; the others keep theirs, or get the pack's backdrop if it has one. " +
            "Change icon (long-press an app) lets you pick any icon from the pack."
    })
}

/** A row of cards, each showing [sample] drawn with one of [specs] (drawn in the background), named by [names]. */
private fun Page.iconChoices(sample: AppInfo?, names: List<String>, selected: Int, specs: List<Icons.Spec>,
                             previews: ((done: (List<Bitmap?>) -> Unit) -> Unit)? = null, onPick: (Int) -> Unit) {
    val row = linear(act, false)
    val icons = names.mapIndexed { i, name ->
        val on = i == selected
        val iv = IconView(act)
        row.addView(linear(act, true) {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(act.dp(4), act.dp(10), act.dp(4), act.dp(8))
            background = if (on) rounded(pal.accentSoft, act.dpf(14)) else rippleBg(pal.ripple, act.dpf(14))
            contentDescription = name
            addView(iv, llp(act.dp(46), act.dp(46)))
            addView(TextView(act).label(if (on) pal.text else pal.textDim, 12.5f, bold = on).apply {
                text = name; gravity = Gravity.CENTER; maxLines = 2; setPadding(0, act.dp(6), 0, 0)
            }, llp(MATCH, WRAP))
            setOnClickListener { if (!on) onPick(i) }
        }, llp(act.dp(96), MATCH).apply { marginEnd = act.dp(6) })
        iv
    }
    val fill: (List<Bitmap?>) -> Unit = { bitmaps -> bitmaps.forEachIndexed { i, b -> icons.getOrNull(i)?.bitmap = b } }
    if (previews != null) previews(fill) else if (sample != null) Apps.previews(sample, specs, fill)
    val scroller = android.widget.HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; addView(row) }
    add(scroller)
    // Keep the chosen card in view (the colour sets run off the edge).
    if (selected > 0) scroller.post { row.getChildAt(selected)?.let { scroller.scrollTo((it.left - act.dp(48)).coerceAtLeast(0), 0) } }
}
