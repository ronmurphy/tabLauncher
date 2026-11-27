# TabDesk

A small desktop-style home screen for Android tablets: a desktop with icons, folders and widgets, a
taskbar, and a Start menu. Built on a Lenovo Tab One (TB305FU, Android 15); it needs Android 12 or later.
Plain Kotlin + framework Views (no AndroidX, no Compose).
The release APK is about 1 MB (mostly the bundled Material Symbols font), and it uses about 50 MB PSS at idle; the optional helper process about 50 MB more.

## Install

1. On the tablet, open the [latest release](https://github.com/ronmurphy/tabLauncher/releases/latest)
   and download the `.apk` file under **Assets**.
2. Open it (from the download notification, or Files → Downloads). The first time, Android asks to let
   your browser install apps: tap **Settings**, switch on **Allow from this source**, go back, and tap
   **Install**.
3. If Play Protect steps in, it's because TabDesk isn't on the Play Store, so Google hasn't seen it before.
   If it recommends a scan, tap **Scan app**, then **Install** once it says the app looks safe. If it says it
   doesn't recognise the app, tap **More details** → **Install anyway**.
4. Tap **Open** when it has installed (or TabDesk's icon among your apps). Android asks "Set TabDesk as
   your default home app?": choose **TabDesk**, then **Set as default**.
5. Optional, to have the taskbar show the apps that are running: open TabDesk's icon again (once TabDesk is
   your home app, it opens Customize), go to **Helper**, and follow the steps. It takes a few minutes, once.
   On OnePlus, Oppo, realme and Xiaomi tablets there's one extra switch, and the page says where.

**Updating:** once a day TabDesk asks GitHub whether there's a new version, and the Start menu says when
there is: tap that, then **Update** (or Customize → About → **Check for updates**). The first time, Android
asks you to switch on **Allow from this source** for TabDesk; with the helper set up, TabDesk does that
itself. Play Protect may ask to scan it, as above. Versions 1.0.1 and older can't do this yet: download the
new `.apk` and open it, as when installing. Your setup is kept either way.

**Going back:** Settings → Apps → Default apps → Home app, and pick the one you had.

## Using it

| Where | Do | Result |
|---|---|---|
| Taskbar ◉ | tap / long-press | Start menu / Customize |
| Empty desktop | swipe up / swipe down | Start menu / Android's notification panel |
| Empty desktop | double-tap | lock the screen (needs Accessibility → **TabDesk screen lock**; the first double-tap explains) |
| Empty desktop | long-press | Add widget · Add Folder View · Arrange icons · Wallpaper · Customize |
| Any app (desktop, taskbar, Start) | tap | open |
| | long-press | app shortcuts, **open in floating window**, **always open floating** (toggle: a normal tap then opens it floating), pin/unpin taskbar, add/remove desktop, rename, hide (Start), info, uninstall |
| Recent app on the taskbar (no helper) | long-press → Remove from taskbar | hidden until you next open that app |
| Running app (taskbar, desktop, Start) | long-press → Close | closes it, as swiping it away in Recents does (needs the helper) |
| Running app on the taskbar | tap | the window in use minimises; a minimised one (or one behind others) comes back; with several windows, their previews show to pick from (tap again to close them) (needs the helper) |
| | long-press | also **Minimise** / **Restore**, and for a floating window **Snap left / right** (top / bottom in portrait), **Fill the screen** and **Back to its size** |
| Preview of a window | tap / × | brings that window to the front / closes it |
| Taskbar, far right (floating windows + helper) | tap **Show desktop** | minimises every window; tap again to bring them back as they were |
| "Recently added" app in Start | long-press → Remove from Recently added | dropped from that list for good |
| | long-press **and keep moving** | drag: Start → desktop, Start/desktop → taskbar, reorder taskbar, move on desktop |
| Widget | long-press | Resize (drag the dot) · Widget settings · Move to the dock · Remove; long-press + move drags it |
| Taskbar widgets button (left of the battery) | tap | the **widget dock**: widgets and Folder Views in a panel at the right, kept off the desktop. **+** adds one; drag its left edge to make it wider; long-press an item for Resize (drag the bar under it) · Move up / down · Move to the desktop · Remove. Opens above floating windows; a tap outside closes it |
| Folder View (one icon) | tap / rest the mouse on it / long-press | a folder's files in a popup (moving the mouse away closes it) / the same / New folder · Newest first · Show another folder · Rename · Show as a panel · Move to the dock · Remove. Needs Android's **All files access**, which the first Folder View asks you to turn on |
| Folder View (panel, or in the dock) | tap a file / middle-click / long-press it | open it / open it in a floating window / Open · Open in a window · Open with · Share · Rename · Delete; long-press **and move** drags the file into another app, or onto another Folder View or TabDesk Files (Move here / Copy here); files dragged in from other apps are copied in. On the desktop, long-press the panel's title, empty space or edge **and move** to move the panel |
| **TabDesk Files** (in Start, or a Folder View's long-press menu → Open in TabDesk Files) | tap | a file manager in a window of its own, which floats like any app: places at the left (under ☰ when the window is narrow), with your Folder Views' folders among them; Back, Up, the path (tap a part of it to go there), search in this folder, grid or list, and ⋮ for New folder · Paste here · Select all · sort by name / newest / largest · show hidden files · Put this folder on the desktop or in the dock |
| A file or folder in TabDesk Files | tap / middle-click / long-press | open it / open it in a floating window / Open · Open in a window · Open with · Share · Select · Copy · Cut · Rename · Delete, and for a folder Put on the desktop · Put in the widget dock. With something selected, taps select more, and the top bar has Select all · Copy · Cut · Share · Delete; **Paste** at the bottom puts what you copied or cut in the folder you're in. Long-press **and move** drags files onto a folder or a Folder View (Move here / Copy here), or into another app, which gets a copy |
| **TabDesk Tasks** (in Start; needs the helper) | tap an app | what's running, in a window of its own: CPU, memory and battery at the top, then each app's share of the CPU over the last moment and the memory it uses, busiest first (the button at the top right sorts by memory instead). Tap an app for Open · Close its windows · App info (Force stop is there). TabDesk's own row is the home screen, Files and the helper together. Without the helper it says so, with a button to set it up |
| Start A–Z bar | tap a letter / number | shows only apps starting with it (tap again or Back for all); only characters your apps start with appear |
| Start search | type, then Enter | opens the top hit, or searches the web |
| Start search | a sum, e.g. `12*8+4`, `(2+3)^2/4`, `200*15%` | the answer at the top; tap it to copy |
| Start search | `web:github.com` / `web:pizza near me` | opens the site in your default browser / searches the web |
| Desktop app | drag onto another desktop app | makes a folder of the two; drop more apps onto it to add them |
| Folder | tap / long-press | open it (tap the name to rename; drag an app out) / Rename · Ungroup · Remove from desktop |
| Taskbar battery | tap / long-press | Android's notification + quick-settings panel / battery usage |
| Taskbar clock | tap | calendar |
| Taskbar pulse (after the clock) | tap | Customize → Helper; lit while the helper is running, dim when it isn't |
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

### With a mouse or trackball

- **Right-click** does what a long-press does, straight away: app, folder, widget and desktop menus,
  Customize from the Start button, battery usage from the battery, the menu on a saved icon theme.
- **Middle-click** (press the wheel) on an app, on the desktop, taskbar, in Start or a folder, opens it in a
  floating window.
- **Click and drag** moves desktop icons, taskbar buttons, Start entries and folder apps without
  holding still first. Widgets still need a hold, so dragging inside one scrolls or slides its own content.
  In Start, scroll with the wheel: dragging an entry picks it up.
- The **pointer lights up** what it rests on; taskbar buttons show the app's name after a moment, or,
  for a running app (with the helper), previews of its windows, which stay while the pointer is on them.
- The **wheel** scrolls Start, Customize and folders; tilt it (or a sideways wheel) for the rows of choices.
- A mouse's back button works as Back.
- In **TabDesk Files**: Ctrl+click picks files one at a time and Shift+click a run of them; Ctrl+A / C / X / V
  select all, copy, cut and paste; Ctrl+F searches; Delete and F2 delete and rename; Alt+← and Alt+↑ go back
  and up; Esc works as Back (it clears the selection or search first).

Portrait and landscape keep separate desktop arrangements. Saved positions are anchors: changing
icon size / grid density scales the layout for display without overwriting it, so changing back restores it.

Customize (long-press or right-click Start) is split into tabs: down the left on a tablet in landscape, across the
top in portrait or on a phone. It reopens on the tab you last used, and stays open (re-laid out) when
the tablet is rotated:

- **Look**: theme, accent colour, font
- **Icons**: your saved icon themes, then style, colours, effect and icon pack
- **Desktop**: icon size, grid density, labels, double-tap to lock
- **Taskbar & Start**: taskbar size, recently used apps, hidden apps
- **Screen & system**: screen timeout and the Keep awake tile, hiding Android's status bar on the
  desktop (one swipe from the top shows it, a second opens Lenovo's quick-settings panel),
  notification dots, default home app
- **Helper**: setting up the running-apps helper, step by step, and whether it's running
- **Agent**: letting an app such as DroidACP give an AI agent use of the tablet: the master switch (off
  by default), the apps that asked and the approved ones (each approval lasts a week; Keep allowing
  renews it, Revoke takes it back), the apps the agent may read and open (none until you switch them
  on), whether it may see the names of running apps, wake the screen, or tap and type, the shared
  TabDeskAgent folder, the tablet's name, and a log of the recent calls. See [Agent control](#agent-control)
- **Backup**: export, import, undo import

Start shows a "Recently added" section for apps installed in the last 14 days.

**Running apps (the helper).** Android doesn't let an app see which other apps are running, but adb
can. TabDesk pairs with the tablet's own wireless debugging, the way a computer would, and starts a
small helper process through it. The helper tells TabDesk whenever apps open, move or close, and
closes, minimises, restores and resizes windows when asked, and measures how busy each app is for
TabDesk Tasks. With it, the taskbar shows the apps that
are running (after the pins, which get the same mark): a short grey line under a running app, a
longer one in the accent colour while it's on screen, e.g. in a floating window, and a tap minimises
or restores it (see Floating windows). Without it, the taskbar shows recently used apps instead.
Customize → **Helper** sets it up in five steps that tick themselves off: Developer options, Wi-Fi,
Wireless debugging, **Pair** (TabDesk opens Wireless debugging; tap "Pair device with pairing code"
and type the code into TabDesk's notification, since the code has to stay on screen) and **Allow**.
Allow has TabDesk grant itself, through adb, what otherwise needs a computer: switching wireless
debugging on, recently used apps, binding widgets without a prompt, notification dots, lifting
Android's restricted-settings block on double-tap to lock, and drawing over other apps (window
previews and menus above floating windows; on a tablet set up before that, the helper grants it once). After a restart TabDesk switches wireless
debugging on, starts the helper and switches it off again (the tile lights up for a few seconds).
The helper keeps running while USB debugging is on, so TabDesk turns that on too; plugging into a
computer still asks before that computer can use adb. Everything stays on the tablet: TabDesk only
connects to 127.0.0.1.

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
one Android apps use for "downloadable fonts": no API key or library in TabDesk, and TabDesk itself
doesn't go online (its internet permission is only for talking to the tablet's own adb on 127.0.0.1).
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
./deploy.sh      # build (signed with TabDesk's release key), install -r, and bring TabDesk back to the front

# one-time grants (avoid the bind-widget dialog and Android 15's "restricted setting" block);
# Customize → Helper → Allow does these itself once TabDesk is paired
adb shell appwidget grantbind --package dev.tabdesk --user 0
adb shell cmd notification allow_listener dev.tabdesk/dev.tabdesk.DotsService
adb shell appops set dev.tabdesk GET_USAGE_STATS allow      # recently used apps on the taskbar

# make it the home app
adb shell cmd package set-home-activity dev.tabdesk/.HomeActivity
```

**Signing.** Release builds are signed with TabDesk's own key, `signing/tabdesk-release.jks`, its
password in `signing/keystore.properties`. Both are kept out of git: **back them up**, because Android
only installs an update signed with the same key. Without them, builds fall back to this computer's
debug key. Copies installed before the release key existed were signed with the debug key;
`signing/lineage.bin` (in git) is Android's proof that the debug key handed over to the release key
(APK Signature Scheme v3 rotation), so those update in place and keep their setup. The build signs the
APK again with it (`signReleaseWithLineage` in `app/build.gradle.kts`).

**Releasing.** `./release.sh 1.0.2` does a whole release: it sets the version (the version code follows from it),
builds, checks the APK is signed with TabDesk's key and that lint finds nothing that needs a newer Android than
12, shows the release notes (the commits since the last release, or `./release.sh 1.0.2 notes.md`) and asks
before it commits, tags, pushes, publishes the GitHub release with `TabDesk-1.0.2.apk` attached, and checks
the download. Commit the work itself first, so the release commit is just the version.

### Go back to the Lenovo launcher

```sh
adb shell cmd package set-home-activity com.zui.launcher/.drawer.DrawerLauncher
```
(or Settings → Apps → Default apps → Home app)

## Code map (`app/src/main/java/dev/tabdesk/`)

- `HomeActivity.kt`: window/insets, layers, launching, context menus, back/home handling
- `TabActivity.kt`: what the home screen, TabDesk Files and TabDesk Tasks share (colours, menus and dialogs,
  floating launches)
- `FilesActivity.kt`: TabDesk Files, the file manager window. It runs in a process and task of its own
  (`:files`), reads TabDesk's settings without changing them, and asks the home screen for a new Folder View
  with a broadcast only TabDesk can send (`dev.tabdesk.permission.FOLDER_VIEWS`)
- `TasksActivity.kt`: TabDesk Tasks, the what's-running window. It runs in the home screen's process (it needs
  the helper connection) but in a task of its own, and asks the helper for a sample every few seconds while
  it's on screen
- `Desktop.kt`: snap-to-grid desktop (icons, shortcuts, widgets), drag & drop, widget resize
- `DesktopItem.kt`: what's on the desktop (apps, shortcuts, widgets, folders) and how it's saved
- `Shell.kt`: taskbar (pins, battery, clock) and Start menu (A–Z list, search, footer)
- `Widgets.kt`: AppWidgetHost, bind/configure flow, picker
- `Overlays.kt`: context menus, dialogs, rename prompt; with floating windows on screen they go in a window
  above them, which takes the keyboard only while a dialog is open
- `Dock.kt`: the widget dock (widgets and Folder Views in a panel at the right)
- `FolderView.kt`: Folder Views (a folder's files as an icon, a desktop panel or a dock item), the folder
  picker, file menus, drag in / out; `FilesProvider.kt` hands one file at a time to the app opening it
- `Windows.kt`: window management through the helper: taskbar tap to minimise / restore, snapping,
  Show desktop, window previews
- `Folders.kt`: desktop folder popup and menu (drop-to-merge lives in `Desktop.kt`)
- `Search.kt`: calculator and `web:` handling for Start search
- `Fonts.kt`: Google Fonts download/cache and the font picker
- `Backup.kt`: export, import (with confirm) and undo of the whole setup
- `Updates.kt`: checking GitHub for a newer release, downloading and checking its APK, and installing it
  (an app may update itself without asking on Android 12 and later); coming back to the front afterwards
- `Screen.kt`: screen timeout and the Keep awake quick settings tile
- `Prefs.kt`: every setting, and turning them into / out of a backup
- `Apps.kt`: installed apps and their drawn icons, recently used apps, notification-dot state
- `WelcomeActivity.kt`: TabDesk's app icon: asks to become the home app after installing; afterwards opens Customize
- `Services.kt`: notification-dot listener, double-tap-to-lock accessibility helper, "Add to Home screen" shortcut handler
- `Ui.kt`: palette, drawn line icons, icon view, gesture helper, drag shadows, battery view

`customize/`: the Customize panel (long-press or right-click Start)

- `Customize.kt`: the panel itself (tabs down the side or across the top, remembering tab and scroll) and the shared rows (sections, notes, sliders, switches)
- `LookPage.kt`, `IconsPage.kt`, `DesktopPage.kt`, `TaskbarPage.kt`, `SystemPage.kt`, `HelperPage.kt`, `AgentPage.kt`, `BackupPage.kt`, `AboutPage.kt`: one file per tab

`agent/`: agent control (`docs/agent-control.md`); the Binder interface is in `app/src/main/aidl/`

- `AgentService.kt`: the bound service: who the caller is (its UID and signing certificate), the MCP
  messages, the checks before each call, one call at a time per caller, answers by reply or by pipe
- `Agent.kt`: the master switch and the others, approvals and their weekly renewal, who is using the
  tablet now (idle, active, paused, stopped), and the notifications; kept in `no_backup/`, so no backup carries an approval over
- `Tools.kt`: the tool list, the tools `apps`, `open_app` and `usage`, and the shapes of results and refusals
- `ScreenTools.kt`: `screen` and `get_item`: the checks before a read (screen on and unlocked, an allowed app
  in front), turning what the helper read into the agent's items, the last read for item ids, and
  locators (an item by its id or by what it says)
- `ActTools.kt`: tool list 2, `tap`, `type`, `swipe`, `scroll`, `key` and `wait_for`: their arguments, the
  "Let the agent tap and type" switch, and what the helper's answer means for the agent
- `SharedFolder.kt`: tool list 3, the shared TabDeskAgent folder: setting it up through Android's folder
  picker, listing it, reading and writing files in chunks (a hidden part file renamed when it's whole, never
  replacing a file), and removing unfinished copies
- `Never.kt`: the apps the agent can never read or use, worked out on the tablet
- `AgentLog.kt`: the last 500 calls, without what was read
- `AgentPerson.kt`: the agent as its Pixel Person on the taskbar: who it says it is (each call's `_meta`,
  only drawn, never trusted) and its pose from what it's doing
- `AgentReceiver.kt`, `AgentSettingsActivity.kt`: Stop and Continue on the notification and the weekly
  alarm; `dev.tabdesk.action.AGENT_SETTINGS`, which opens Customize → Agent

`people/`: the Pixel People, ported from DroidACP so both apps draw the same people

- `PixelPeople.kt`: reads `assets/people/sprites.txt` (DroidACP's file, copied as it is) and draws a person
  in a pose, 32 x 32 pixels, and the bar in its agent's colours; keep it in step with DroidACP's
- `PersonView.kt`: one person on its bar, a whole number of screen pixels per sprite pixel, unsmoothed

`helper/`: the running-apps helper

- `AdbLink.kt`: a minimal adb client (TLS with TabDesk's key, then adb's messages, enough for shell commands), TabDesk's key, and finding the wireless-debugging port with network service discovery
- `AdbPairing.kt`: pairing with a 6-digit code: TLS, SPAKE2 (BoringSSL's, in plain Kotlin) and the encrypted key exchange; making TabDesk's key and certificate
- `Pairing.kt`: the pairing notification (watches for the pairing service, takes the code)
- `Setup.kt`: the setup steps' state and buttons, the grants behind Allow, and Copy details
- `Device.kt`: which maker's Android this is (OxygenOS, HyperOS, One UI, ZUI…), for maker-specific steps
- `Adb.kt`: running commands through wireless debugging, switching it on for the moment if it's off, and
  remembering its port (Android 12 stops announcing it soon after pairing)
- `Helper.kt`: the helper itself, run as the shell user with `app_process`: watches tasks, measures CPU and memory
  per app, serves TabDesk on 127.0.0.1
- `Reader.kt`: reading the allowed app's windows for agent control (UiAutomation), and its screenshots
- `Actor.kt`: acting in it: injected touches, setting a field's text, Back, Enter and Home, each after
  checking the window under the finger or the focus is the app's
- `Touches.kt`: watching the touchscreen (`getevent`) while an agent is using the tablet, for the pause
- `HelperClient.kt`: TabDesk's side: starts the helper (at startup, after a restart, when a new version is installed), stays connected, and passes on the running tasks and the usage samples

`icons/`: everything about app icons

- `Icons.kt`: what each app is drawn from (picked icon, icon pack, the app's own one-colour drawing, a matching Material symbol, or its initial) and the order the steps run in
- `IconStyles.kt`: painting a drawing in each style (Themed, Watercolour, Inked, Minimal, Outline, Paper cut, Long shadow, Round badge) and the colour sets' finishes (seal, snow, sun, grid, Riso, chalk)
- `IconColours.kt`: the colour sets, Accent / App colours / Auto
- `IconEffects.kt`: the effects applied last (8-bit, Glass, Neon, Halftone…)
- `IconPicker.kt`: the Change icon picker (symbols and icon pack browsing)
- `IconThemes.kt`: saved icon themes (starters, apply with undo, rename, delete, export / import)
- `IconPacks.kt`: finding installed icon packs and reading them (appfilter, theming layers, drawable list, picker thumbnails)

Outside the app: `tools/` has the adb test helpers, the font scripts, a test icon pack, a stand-in for
the tablet's pairing service, and `tools/agentcaller/`, a test app that calls the agent service as
DroidACP would (`tools/agentcaller/run.sh`; see
`tools/README.md`); `archive/` keeps local snapshots of the source from before big changes.

## How the helper works

- **Pairing** follows Android's adb (`packages/modules/adb`, `pairing_auth` and `pairing_connection`):
  TLS 1.3 to the port that "Pair device with pairing code" advertises as `_adb-tls-pairing._tcp`, then
  SPAKE2 over the code plus 64 bytes exported from the TLS session (label `adb-label\0`), then each side
  sends its key encrypted with AES-128-GCM (HKDF-SHA256 of SPAKE2's result). TabDesk's RSA-2048 key is
  sent as adb writes `adbkey.pub`, named "TabDesk", and kept in `no_backup/adb/` (not in backups).
  `tools/pairing/fake_pair_server.py` plays the tablet's side for testing; the PC's `adb pair` pairs with it.
- **Connecting**: TLS 1.3 with the same key to the port advertised as `_adb-tls-connect._tcp` (the one
  whose address is this tablet's), then `CNXN` / `OPEN shell:` messages, as `adb shell` does.
- **The helper** is TabDesk's own APK run by the shell user: `CLASSPATH=<apk> app_process /system/bin
  dev.tabdesk.helper.Helper serve`, started with `setsid` so it outlives the adb command. It lists tasks
  with `ActivityTaskManager.getRecentTasks` (`isRunning`, `isVisible`) and closes them with `removeTask`,
  which the shell user may do. It registers for task changes (`registerTaskStackListener`, with a plain
  Binder standing in for the hidden listener interface) and pushes the list to TabDesk over a socket on
  127.0.0.1; the port and a random token are passed in environment variables, and a connection that
  doesn't send the token first is dropped. It only takes these lines:
  - `close <task>`: `removeTask`.
  - `min <task>`: takes a snapshot first (`takeTaskSnapshot`, for the previews), then a
    `WindowContainerTransaction.reorder(token, false)` through `getWindowOrganizerController()`, as
    Android's own desktop windowing minimises. The task keeps running, not visible.
  - `front <task>`: `startActivityFromRecents`, or `moveTaskToFront` (needs only REORDER_TASKS).
  - `resize <task> l t r b`: `resizeTask`, as `am task resize` does (floating windows only).
  - `shot <task> <px>`: the window's last snapshot (`getTaskSnapshot`, else `takeTaskSnapshot`), scaled
    and sent back as a base64 JPEG on one line, off the reading thread.
  - `usage <ms>`: reads `/proc/<pid>/stat` and `statm` for every process twice, `<ms>` apart (the shell
    user may read them all), and answers with the CPU's busy share, how long since the screen was last
    touched (`dumpsys power`), and per app (by uid; system processes together as "android") its CPU share,
    CPU time, resident memory and process names. It runs on a thread of its own, so task updates keep
    coming meanwhile.
  - `ping`, `quit`.
  Each task in the list also carries its windowing mode, whether it has focus, its bounds, and the
  activity it was started with (TabDesk's own windows, Files and Tasks, share one package).
  `tools/emulator/helper.sh tasks|min|front|resize|shot …` runs the same calls from a computer.
- **Lifetime**: the helper is in adb's process group, so it ends when adb does. adb runs while USB or
  wireless debugging is on, so with USB debugging on, wireless debugging can be switched off again.
  TabDesk replaces a helper left from an older version (it reports a build id), and if the helper
  stops, tries again after 5 s, 30 s, 2 min, then every 10 min, or when Wi-Fi comes back.

## Agent control

Another app on the tablet can let an AI agent use it through TabDesk; the first is DroidACP, which relays
for Claude Code on a computer. TabDesk is the only part that reads, and it decides what's allowed. The
interface is in `docs/agent-control.md` (released with this code), and the plan shared by the three apps
is kept outside the repo.

- **Off until you say so:** Customize → Agent → "Let apps use this tablet". An app asks when you switch
  on its own tablet setting; TabDesk then posts "<app> wants to use this tablet", and you approve it on
  that page, never in a pop-up. An approval is tied to the app's signing certificate and lasts a week;
  TabDesk reminds you the day before, and Keep allowing gives it another week.
- **What an agent can do so far:** read the app in front if you've allowed it (`screen`: its buttons,
  text and fields, optionally with a screenshot of that app alone; `get_item`), open an app you've
  allowed (`open_app`), list them (`apps`), and see how busy the tablet is (`usage`: the CPU, memory and
  battery, and each running app's share, as TabDesk Tasks shows them). An app you haven't allowed shows
  as "blocked app" unless you switch on "Let the agent see which apps are running".
- **Tapping and typing** (from 1.6.0), only once you switch on "Let the agent tap and type": in the apps you've allowed, the agent can tap (or long-press) a button or a point, type
  into a field, swipe, scroll, press Back, Enter or Home, and wait for something to show up. Each action
  checks again, just before it happens, that the allowed app is still in front and that the spot isn't
  under another window (DroidACP's chat, a notification, the keyboard), and never types into a password
  field. Text goes straight into the field, not through the keyboard, so the keyboard never learns it.
- **The shared folder** (from 1.7.0), only once you set it up and switch it on: Customize → Agent →
  Shared folder → **Set up** makes Download/TabDeskAgent and asks Android to let TabDesk use it (tap "Use
  this folder", then Allow); then switch on "Let the agent use the TabDeskAgent folder". The agent's app
  can then list the files there, copy them to the computer the agent runs on, and put new files there,
  up to 50 MB each. Nothing outside that folder. It never deletes or replaces a file (a new one with a
  name that's taken gets "(1)"), never puts apps (APK files) there, and never opens what it puts there.
  Each file copied is logged once, and a copy left unfinished is removed.
- **Touch the screen to pause it:** while the agent is using an allowed app, a touch of yours pauses
  it, like Stop, until your next chat message or Continue. The agent's own taps don't count, since
  they don't come from the touchscreen.
- **How it reads:** TabDesk's helper reads the screen through Android's UiAutomation (as `uiautomator`
  does), only while an app is using the tablet, and only the allowed app in front. It never reads a
  password field's text. Windows over the app (the keyboard, a notification, another app) are named
  only as far as you've allowed, and blacked out of screenshots, as is the status bar. If the app's
  window is see-through and another app could show through it, there's no screenshot at all.
- **Never:** TabDesk itself (home screen, Files, Tasks, Customize), System UI, Settings, the keyboard,
  Google Play services, the file picker, package installers, or any app that asked to use the tablet
  (dismissing it from "Apps that asked" doesn't change that).
- **While an app is using it,** the taskbar and a notification say so, with **Stop**. Stop refuses
  everything until you send your next chat message or tap Continue. Another approved app gets "busy"
  meanwhile, and can't stop or resume it.
- **The agent itself** stands beside that on the taskbar as its Pixel Person, the small person
  DroidACP draws for each agent, posed by what it's doing: holding a tablet while it reads the screen,
  opens an app or taps, a magnifying glass for one item or while it waits, a book for the app list, `usage` and the shared folder, thinking
  between calls, a sweat drop after a refusal, and hands up when stopped. Its look and name come from
  the agent's host with each call; without them it's a default person for the approved app. The
  approved app's own name always stays on the pill.
- **Every call is logged** (the last 500, in Customize → Agent): when, which app, what it asked for,
  the outcome, and which of the switches above were on, never what was read.
- **A new signature turns the switches off:** if an approved app comes back signed differently, it's a
  different app, so "tap and type", "wake the screen", "see which apps are running" and the shared folder go off. Its row
  says which were on, so setting them again is quick.

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
which is why TabDesk has no "open full screen" entry. TabDesk offers floating windows when the tablet
has the feature or Developer options → **Enable freeform windows** is on.

**Managing them (with the helper)**, in `Windows.kt`:

- Floating windows sit above TabDesk's own window, so they cover its menus. While one is on screen,
  menus, dialogs, window previews and the widget dock go in a window of their own above apps
  (`TYPE_APPLICATION_OVERLAY`, "Display over other apps"). That window takes the keyboard and Back only
  while a dialog is open in it, so typing and Back otherwise stay with the app in use.
- "Display over other apps" comes from Allow in Customize → Helper. A tablet set up before this shows
  Allow again there. Nothing is granted without that tap.
- Android's Settings, permission requests and the installer hide other apps' overlays while they're on
  screen, for safety, and TabDesk isn't told. While one of those is visible, menus and previews go
  back to TabDesk's window, where a window may cover part of them.
- Snapping fills half of the screen apart from the taskbar and the status bar: Android keeps floating
  windows below the status bar even while TabDesk hides it, and moves one placed higher down whole.
- Android leaves floating windows where they are when the screen turns. Windows TabDesk snapped, and
  nobody has moved since, are fitted to the new shape (left becomes top, right becomes bottom).
- "Back to its size" remembers a window's place from before its first snap, until it closes.
- Show desktop remembers which windows it minimised and brings them back in their order.
- Tested on emulators: Android 16 (all of it) and Android 12 (the helper's calls; Android 12's floating
  windows don't draw on a headless emulator). On Android 16, going Home also minimises every floating
  window, and a floating window can cover the taskbar.
- TabDesk Files floats on this tablet. Android 16's own desktop windows keep the home app's activities
  full screen, so on an Android 16 device using those, TabDesk Files opens full screen.
