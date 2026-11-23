# Tools

Helpers used while building TabDesk. None of this is part of the app.

## tablet/: driving the tablet over adb for testing

Coordinates assume this tablet in landscape (1340×800 as adb sees it).

- `bounds.sh`: list every on-screen view with a content description, as `name|[x1,y1][x2,y2]`
- `tapdesc.sh "<name>"`: tap the view with that content description
- `taptext.sh "<text>"`: tap the view showing exactly that text
- `theme.sh "<theme>" out.png`: open Customize → Icons, tap that saved theme, close, screenshot.
  It finds names by content description, so a theme that shares its name with a style or colour chip
  (e.g. Original, Synthwave) can hit the chip instead: check the screenshot.
- `combo.sh <style> "<colour set>" out.png`: set an icon style and colour set, close, screenshot
- `sheet.py`, `sheet_fx.py`, `sheet_rows.py`: crop the option previews out of Customize screenshots
  into one comparison sheet (Python + Pillow; see the usage line at the top of each)

Run them from bash (zsh doesn't split `$var` into words the way these expect).

## fonts/: making the bundled Material Symbols font

`app/src/main/assets/symbols.ttf` and `symbols.txt` come from Google's Material Symbols Rounded
variable font (Apache 2.0) using fontTools:

- `inst.py <font.ttf> <out dir>`: pins the variable font's axes; `fill_axis.ttf` (FILL 0–1, weight 400)
  is the one TabDesk bundles
- `names.py <font.ttf> <font.codepoints> symbols.txt`: one line per glyph, its code point and names

## testpack/: a fake icon pack for testing icon pack support

`testpack.apk` (package `dev.tabdesk.testpack`, shows as "Test Pack") has full-colour icons for a few
apps, a calendar icon per day, `iconback`/`iconmask`/`iconupon` layers for the apps it doesn't
cover, and 400 one-colour shapes for the Change icon picker. Install with `adb install -r`,
remove with `adb uninstall dev.tabdesk.testpack`. `testpack-src.tgz` has its manifest and resources.
