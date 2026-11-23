# TabDesk

A small desktop-style home screen for the Lenovo Tab One (TB305FU, Android 15).
Plain Kotlin + framework Views (no AndroidX, no Compose). The release APK is about 147 KB, and it uses about 50 MB PSS at idle.

## Using it

| Where | Do | Result |
|---|---|---|
| Taskbar ◉ | tap / long-press | Start menu / Customize |
| Empty desktop | swipe up / swipe down | Start menu / Android's notification panel |
| Empty desktop | double-tap | lock the screen (needs Accessibility → **TabDesk screen lock**; the first double-tap explains) |
| Empty desktop | long-press | Add widget · Arrange icons · Wallpaper · Customize |
| Any app (desktop, taskbar, Start) | tap | open |
| | long-press | app shortcuts, **open in floating window**, **always open floating** (toggle: a normal tap then opens it floating), pin/unpin taskbar, add/remove desktop, rename, hide (Start), info, uninstall |
| Recent app on the taskbar | long-press → Remove from taskbar | hidden until you next open that app |
| "Recently added" app in Start | long-press → Remove from Recently added | dropped from that list for good |
| | long-press **and keep moving** | drag: Start → desktop, Start/desktop → taskbar, reorder taskbar, move on desktop |
| Widget | long-press | Resize (drag the dot) · Widget settings · Remove; long-press + move drags it |
| Start A–Z bar | tap a letter / number | shows only apps starting with it (tap again or Back for all); only characters your apps start with appear |
| Start search | type, then Enter | opens the top hit, or searches the web |
| Start search | a sum, e.g. `12*8+4`, `(2+3)^2/4`, `200*15%` | the answer at the top; tap it to copy |
| Start search | `web:github.com` / `web:pizza near me` | opens the site in your default browser / searches the web |
| Desktop app | drag onto another desktop app | makes a folder of the two; drop more apps onto it to add them |
| Folder | tap / long-press | open it (tap the name to rename; drag an app out) / Rename · Ungroup · Remove from desktop |
| Taskbar battery | tap / long-press | Android's notification + quick-settings panel / battery usage |
| Taskbar clock | tap | calendar |
| Customize → Icons → Your themes | tap / long-press | switch to a saved icon theme (Undo goes back) / rename, export to a file, delete; **Save as theme** and **Import** below |
| Customize → Icons → Style | tap a style | Original, or redraw every app as Themed (accent colours), Watercolour, Inked, Minimal, Outline, Paper cut, Long shadow or Round badge |
| Customize → Icons → Colours | tap a set | Accent, App colours, Auto (black or white to suit what's behind), White on black, Black on white, or a set: Sakura, Summer, Autumn, Winter, Ukiyo-e, Matcha, Indigo, Maple, Sepia, Riso, Chalkboard, Blueprint, Terminal, Synthwave, Gold |
| Customize → Icons → Effect | tap an effect | applied last: Glass, 8-bit, 16-bit, CRT, Neon, Neon glass, Halftone, Pencil sketch, Cross-stitch, Mosaic, Embossed, Sticker |
| Customize → Icons → Icon pack | tap a pack | use an installed icon pack (the Nova / ADW kind sold on the Play Store); with none installed, **Find packs** searches the Play Store |
| App (anywhere) | long-press → Change icon | pick any of ~4,000 Material symbols or any icon from an installed pack for that app, or keep its own icon in a themed style |
| Customize → Look → Font | Change | pick any of ~1,200 Google Fonts (search, filter by style; each shown in its own face) for all of TabDesk's text |
| Customize → Look → Font | Save font file | saves the chosen font (Regular, or a heavier weight) as a .ttf anywhere, e.g. Downloads, for reading apps that take custom fonts |
| Customize → Screen & system | slider | screen timeout from 15 s to 2 hours, or Never (needs "Modify system settings", one tap on Grant) |
| Quick settings → Keep awake | tap / long-press | switch between your timeout and Never / open Customize on Screen & system; add it with Customize → Screen & system → Add tile |
| Customize → Backup | Export / Import | save or restore the whole setup as one small file, e.g. in Google Drive; **Undo import** puts back what you had |

Portrait and landscape keep separate desktop arrangements. Saved positions are anchors: changing
icon size / grid density scales the layout for display without overwriting it, so changing back restores it.

Customize (long-press Start) is split into tabs: down the left on a tablet in landscape, across the
top in portrait or on a phone. It reopens on the tab you last used, and stays open (re-laid out) when
the tablet is rotated:

- **Look**: theme, accent colour, font
- **Icons**: your saved icon themes, then style, colours, effect and icon pack
- **Desktop**: icon size, grid density, labels, double-tap to lock
- **Taskbar & Start**: taskbar size, recently used apps, hidden apps
- **Screen & system**: screen timeout and the Keep awake tile, hiding Android's status bar on the
  desktop (one swipe from the top shows it, a second opens Lenovo's quick-settings panel),
  notification dots, default home app
- **Backup**: export, import, undo import

Start shows a "Recently added" section for apps installed in the last 14 days.

**Icon styles.** Themed, Watercolour, Inked and Minimal redraw every app the same way. Each app's
drawing is, in order: one you picked (long-press → Change icon), the app's own one-colour icon
(Android 13+, most popular apps have one), a Material symbol for what the app does (found by asking
Android which app takes photos, calculates and so on, then by words in its name), or its initial.
The newer styles: Outline traces every edge of the drawing in one even line with no tile (line-art
pack icons are used as they are), Paper cut cuts the drawing through two stacked sheets so you look
down through a rim of the set's colour (on dark sets the bottom layer is the set's brightest colour,
like a lightbox), Long shadow is a flat tile with the drawing casting a long diagonal shadow, and
Round badge puts the drawing on a disc with an inner ring. Style numbers are stored (and saved
themes use names like `"papercut"`), so new styles are added at the end of the list.
**Icon colours** are a separate choice that works with every style: each set gives a tile, paper,
ink, wash and drawing colour, and each style uses the parts it needs. Some pairings make their own
look: Black on white + Watercolour is ink-wash painting, Sakura + Watercolour cherry blossom,
Blueprint + Inked an architect's drawing, Terminal + Minimal an old green screen. A few sets add a
finishing touch in every style: Summer warm light and glints, Winter a fall of snow, Ukiyo-e a red seal like a woodblock print's,
Riso a second pink ink printed slightly out of line (overlaps darken, as real risograph inks do),
Chalkboard chalky grain in the lines, Synthwave an 80s neon grid running to a glowing horizon. App colours uses
each app's own main and second colour. Where an icon depends on what's behind it (Auto, or Minimal
in your accent), TabDesk draws two copies: one for the wallpaper (light or dark, as Android reports
it) and one for the taskbar and Start (light or dark with the theme). Desktop labels also switch to
dark text on a light wallpaper.

**Effects** are applied last, to whatever the style, colours and pack drew: 8-bit and 16-bit
(a 16×16 or 32×32 grid of hard pixels in at most 3 or 15 colours, like console sprites), Cross-stitch
(X stitches in up to 8 thread colours on cloth), Halftone (flat colours, a 45° dot screen and an ink
outline), Mosaic (tiles set in grout), Sticker (a die-cut border and shadow), CRT (glow, colour
fringes and scanlines), Neon (the outlines as glowing tubes in the app's colours on a dark plate),
Pencil sketch (graphite outlines and coloured-pencil hatching on paper) and Embossed (pressed up out
of a plate, lit from the top left). Glass, in the spirit of Apple's Liquid Glass, turns an app's own
background into a tinted glass tile with the rest of the icon floating inside (apps without one get
clear frosted glass); Neon glass seals the neon tubes in smoked glass. Glass, Neon glass, Pencil and
Embossed find an icon's background by sampling round its edges, and a Themed tile is drawn without
its set's finish under them so the grid, snow or sun isn't left behind as stray marks. Android doesn't
let apps read the wallpaper image, so the glass is see-through rather than blurring what's behind it.
An app set to "App's own icon" gets no effect.

**Icon themes** save the recipe (style, colours, icon pack, effect and icons picked for single apps)
under a name, not the drawn icons, so a theme is a few hundred bytes, covers apps installed later,
and draws another person's apps the same way when they import it. A few starters (Original, Ink
wash, Cherry blossom, Woodblock, Retro arcade, Green screen, Summer stickers, Frosted glass, Neon
sign, Neon glass, Synthwave, Sketchbook, Line art, Paper craft, Flat design, Badges) are added once and can be deleted; starters added in an
update reach people who already have the earlier ones. Exported themes are `.json` files (`"kind": "iconTheme"`). The style, colours, effect,
picked icons and saved themes are all in backups (importing a backup adds its themes to yours). Icons are Material Symbols Rounded by Google, bundled under the
Apache License 2.0 (`assets/symbols.ttf`, trimmed to the filled/outlined axis; names in `assets/symbols.txt`).

**Icon packs** are found by the intents packs announce themselves with (`org.adw.launcher.THEMES`,
`com.novalauncher.THEME` and others). TabDesk reads the pack's `appfilter.xml` (from `res/xml` or
`assets`): its icon for each app (matched by activity, then by package), `calendar` entries (a new
icon for each day of the month), and, for apps the pack doesn't have, its `iconback` / `iconmask` /
`iconupon` / `scale` layers. With the Original style the pack's icons show as they are. With another
style, a pack icon that's all one colour (line-art packs like Arcticons) becomes the drawing that
style paints in your icon colours; full-colour pack icons are only used there if picked for an app.
One-colour pack icons are drawn dark on light backgrounds and light on dark ones, so a white
line-art pack still shows on a light taskbar. Change icon browses a pack's `drawable.xml` (or
everything in its appfilter). If the pack is uninstalled, apps go back to their own icons; installing
it again brings it back (backups keep the pack's package name, not its icons).

**Fonts** come from Google Play services' font provider (`com.google.android.gms.fonts`), the same
one Android apps use for "downloadable fonts": no internet permission, API key or library in TabDesk.
The chosen family (regular + a 600 weight, or synthetic bold) is copied to `files/font/` so it loads
instantly and offline at startup. The list in `app/src/main/assets/fonts.txt` is Latin-script Google
Fonts under 3 MB, most popular first, one per line as category letter (S sans, R serif, D display,
H handwriting, M mono) + family name, generated from `https://fonts.google.com/metadata/fonts`.
Devices without Play services just don't get the Change button.

**Screen timeout / Keep awake** write `Settings.System.SCREEN_OFF_TIMEOUT` (the `WRITE_SETTINGS` app-op,
granted by the user). Never is `Int.MAX_VALUE`, which Android treats as no timeout; your own value
is remembered so Keep awake can switch back. Android 13+ adds the tile with one tap
(`StatusBarManager.requestAddTileService`). Pairs with double-tap-to-lock.

**Backup** goes through Android's file picker (Storage Access Framework). The Google Drive app shows
up there as a place to save/open, so there's no Google sign-in or Drive API to set up; on devices
without Drive the buttons read "Export backup" / "Import backup". The file holds the Customize
settings, taskbar pins, renames, hidden apps, font and the desktop (folders, shortcuts, widgets
with their provider). On import, widgets still bound on this device come straight back; elsewhere
TabDesk binds them again if Android allows it silently, and otherwise names them so you can re-add
them. The setup from before the import is kept in `files/before-import.json` for **Undo import**.

## Build & install

```sh
./deploy.sh      # build (signed with the local debug key), install -r, and bring TabDesk back to the front

# one-time grants (avoid the bind-widget dialog and Android 15's "restricted setting" block)
adb shell appwidget grantbind --package dev.tabdesk --user 0
adb shell cmd notification allow_listener dev.tabdesk/dev.tabdesk.DotsService
adb shell appops set dev.tabdesk GET_USAGE_STATS allow      # recently used apps on the taskbar

# make it the home app
adb shell cmd package set-home-activity dev.tabdesk/.HomeActivity
```

### Go back to the Lenovo launcher

```sh
adb shell cmd package set-home-activity com.zui.launcher/.drawer.DrawerLauncher
```
(or Settings → Apps → Default apps → Home app)

## Code map (`app/src/main/java/dev/tabdesk/`)

- `HomeActivity.kt`: window/insets, layers, launching, context menus, back/home handling
- `Desktop.kt`: snap-to-grid desktop (icons, shortcuts, widgets), drag & drop, widget resize
- `DesktopItem.kt`: what's on the desktop (apps, shortcuts, widgets, folders) and how it's saved
- `Shell.kt`: taskbar (pins, battery, clock) and Start menu (A–Z list, search, footer)
- `Widgets.kt`: AppWidgetHost, bind/configure flow, picker
- `Overlays.kt`: context menus, dialogs, rename prompt
- `Folders.kt`: desktop folder popup and menu (drop-to-merge lives in `Desktop.kt`)
- `Search.kt`: calculator and `web:` handling for Start search
- `Fonts.kt`: Google Fonts download/cache and the font picker
- `Backup.kt`: export, import (with confirm) and undo of the whole setup
- `Screen.kt`: screen timeout and the Keep awake quick settings tile
- `Prefs.kt`: every setting, and turning them into / out of a backup
- `Apps.kt`: installed apps and their drawn icons, recently used apps, notification-dot state
- `Services.kt`: notification-dot listener, double-tap-to-lock accessibility helper, "Add to Home screen" shortcut handler
- `Ui.kt`: palette, drawn line icons, icon view, gesture helper, drag shadows, battery view

`customize/`: the Customize panel (long-press Start)

- `Customize.kt`: the panel itself (tabs down the side or across the top, remembering tab and scroll) and the shared rows (sections, notes, sliders, switches)
- `LookPage.kt`, `IconsPage.kt`, `DesktopPage.kt`, `TaskbarPage.kt`, `SystemPage.kt`, `BackupPage.kt`: one file per tab

`icons/`: everything about app icons

- `Icons.kt`: what each app is drawn from (picked icon, icon pack, the app's own one-colour drawing, a matching Material symbol, or its initial) and the order the steps run in
- `IconStyles.kt`: painting a drawing in each style (Themed, Watercolour, Inked, Minimal, Outline, Paper cut, Long shadow, Round badge) and the colour sets' finishes (seal, snow, sun, grid, Riso, chalk)
- `IconColours.kt`: the colour sets, Accent / App colours / Auto
- `IconEffects.kt`: the effects applied last (8-bit, Glass, Neon, Halftone…)
- `IconPicker.kt`: the Change icon picker (symbols and icon pack browsing)
- `IconThemes.kt`: saved icon themes (starters, apply with undo, rename, delete, export / import)
- `IconPacks.kt`: finding installed icon packs and reading them (appfilter, theming layers, drawable list, picker thumbnails)

Outside the app: `tools/` has the adb test helpers, the font scripts and a test icon pack (see
`tools/README.md`); `archive/` keeps local snapshots of the source from before big changes.

## Floating windows

This ROM has `android.software.freeform_window_management`. TabDesk opens a floating window by
putting `android.activity.windowingMode = 5` (freeform) in the launch-options bundle;
`ActivityOptions.setLaunchWindowingMode` itself is hidden API and blocked on Android 15. Lenovo's
window manager chooses the window size. The ••• bar at the top of a floating window has Lenovo's
full screen / split screen / floating / minimise buttons; swipe up on its bottom bar to close it.
Lenovo refuses to float some apps ("… does not support floating window"), even ones Android treats as
resizable, such as Claude. Turning on Developer options → **Force activities to be resizable** fixes it
(this tablet also has **Enable freeform windows** on; both need a reboot to settle).
Launch options can turn a full-screen window floating, but not a floating one back to full screen,
which is why TabDesk has no "open full screen" entry.
