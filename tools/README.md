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
- `mouse.py "to X Y" "right" ... | adb shell uinput -`: a virtual mouse for a few seconds (hover, clicks,
  drags, wheel), using Android's own `uinput` test tool; no root. Steps are listed at the top of the file.
  Screenshots taken meanwhile show the pointer. Pointer scale is measured for this tablet's speed setting.
- `sheet.py`, `sheet_fx.py`, `sheet_rows.py`: crop the option previews out of Customize screenshots
  into one comparison sheet (Python + Pillow; see the usage line at the top of each)

Run them from bash (zsh doesn't split `$var` into words the way these expect).

## emulator/: testing a fresh install in Android's emulator

Two emulators are set up (in `~/.android/avd`): `TabDesk_A16` (Android 16 with Play Store, at the OnePlus Pad's
2800 × 2000) and `TabDesk_A12` (Android 12, the oldest TabDesk supports). Start one without a window, then drive
it over adb as `emulator-5554`:

    ~/Android/Sdk/emulator/emulator -avd TabDesk_A16 -no-window -no-boot-anim -no-audio -gpu swiftshader_indirect -no-snapshot-save &
    adb -s emulator-5554 install app/build/outputs/apk/release/app-release.apk
    tools/emulator/shot.sh /tmp/shot.png        # half-size screenshot: tap at twice what you see
    tools/emulator/find.sh "Build number"       # where some text is on screen, in real pixels
    tools/emulator/helper.sh read - - -1 1024   # what an agent reads of the app in front, with a 1024 px picture
                                                # (then: adb exec-out cat /data/local/tmp/read.jpg > read.jpg)
    tools/emulator/helper.sh windows            # the windows on screen: type, layer, task, bounds, package
    adb -s emulator-5554 emu avd snapshot save default_boot   # keep what you set up (see below)
    adb -s emulator-5554 emu kill               # shut it down

**Snapshots:** a normal start loads the quick-boot snapshot `default_boot`, which puts the disk back as it was when
that snapshot was saved. With `-no-snapshot-save` nothing new is saved, so whatever you installed or set since is gone
at the next start. Save the snapshot before shutting down, as above, or start with `-no-snapshot-load` (a cold boot
onto the current disk). A snapshot loads only with the GPU mode it was saved with, so always start with
`-gpu swiftshader_indirect` as above. Both were saved 2026-11-26 08:58 with the `agent-control` build after the code
review (532ae19). TabDesk_A16: both test callers approved, Clock and See-through allowed in Customize → Agent.
TabDesk_A12: the test caller approved; its helper isn't paired, so test the reader there with `helper.sh read`.

`find.sh` and `helper.sh read` each need Android's one UiAutomation connection: neither works while the other, or
TabDesk's helper, is reading (it lets go 15 s after an agent's last call).

Before a release, `./gradlew lintDebug` also catches calls that need a newer Android than 12.

## fonts/: making the bundled Material Symbols font

`app/src/main/assets/symbols.ttf` and `symbols.txt` come from Google's Material Symbols Rounded
variable font (Apache 2.0) using fontTools:

- `inst.py <font.ttf> <out dir>`: pins the variable font's axes; `fill_axis.ttf` (FILL 0–1, weight 400)
  is the one TabDesk bundles
- `names.py <font.ttf> <font.codepoints> symbols.txt`: one line per glyph, its code point and names

## pairing/: a stand-in for the tablet's pairing service

`fake_pair_server.py [port] [code]` acts as "Pair device with pairing code" (TLS 1.3, SPAKE2, the
encrypted key exchange) with its own SPAKE2 written to match BoringSSL's. `adb pair 127.0.0.1:<port>
<code>` against it checks that SPAKE2 against adb's own; it prints the key it receives and exits.
Needs pyOpenSSL (`python3 -m venv venv && venv/bin/pip install pyopenssl`). To point TabDesk at it,
`adb reverse tcp:<port> tcp:<port>` makes it reachable on the tablet's 127.0.0.1.

## testpack/: a fake icon pack for testing icon pack support

`testpack.apk` (package `dev.tabdesk.testpack`, shows as "Test Pack") has full-colour icons for a few
apps, a calendar icon per day, `iconback`/`iconmask`/`iconupon` layers for the apps it doesn't
cover, and 400 one-colour shapes for the Change icon picker. Install with `adb install -r`,
remove with `adb uninstall dev.tabdesk.testpack`. `testpack-src.tgz` has its manifest and resources.

## agentcaller/: a test caller for agent control

A small app (package `dev.tabdesk.agentcaller`, "Agent caller (test)") that binds TabDesk's agent service and
sends it MCP messages the way DroidACP does (plan T9, `docs/agent-control.md`). It's a separate Gradle project,
so TabDesk's own build never includes it. Everything it hears is logged with the tag `AgentCaller`. Commands go
to it as broadcasts, so the app in front stays in front to be read (the caller itself is never readable, as the
app that asked).

```sh
export SERIAL=emulator-5554                       # never the user's tablet before the branch's review
tools/agentcaller/run.sh build                    # build and install (this computer's debug key)
tools/agentcaller/run.sh bind; tools/agentcaller/run.sh connect; tools/agentcaller/run.sh status
tools/agentcaller/run.sh call usage '{"limit":5}' # then approve it in Customize → Agent and call again
tools/agentcaller/run.sh cancel c1:4              # the ids it logs
tools/agentcaller/run.sh burst 6                  # six calls at once: the rate limit refuses two
tools/agentcaller/run.sh call open_app '{"app":"Clock"}'   # after switching Clock on in Customize → Agent
tools/agentcaller/run.sh call screen '{"screenshot":"small"}'
tools/agentcaller/run.sh pw                       # a password field holding "hunter2", to check it's never read
tools/agentcaller/run.sh over                     # pw, with seethrough/'s see-through card over it in the same task
TWO=1 tools/agentcaller/run.sh build              # a second caller ("Agent caller 2", logs as AgentCaller2), to
TWO=1 tools/agentcaller/run.sh call apps          #   test two approved apps: busy, and Stop and Resume
tools/agentcaller/run.sh build-alt                # the same app with another key (uninstall it first)
tools/agentcaller/run.sh log
```

## seethrough/: a see-through window for screenshot tests

A small app (package `dev.tabdesk.seethrough`, "See-through (test)") with one see-through activity: a yellow card
in the middle of a translucent window, so whatever is under it shows around the card. Switch it on in Customize →
Agent, then:

```sh
./gradlew -p tools/seethrough --offline assembleDebug
adb -s emulator-5554 install -r tools/seethrough/build/outputs/apk/debug/SeeThrough-debug.apk
adb -s emulator-5554 shell am start -n dev.tabdesk.seethrough/.Card   # in its own task: on Android 12L and later
                                                                     # the picture is the card on black; before
                                                                     # that, there's none
tools/agentcaller/run.sh over                                        # over the caller in its task: no picture
tools/agentcaller/run.sh call screen '{"screenshot":"large"}'
```
