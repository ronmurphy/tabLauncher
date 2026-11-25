#!/bin/sh
# Prints the centre (x y, in real screen pixels) of the first on-screen element whose text or description contains
# the given words, e.g.  tools/emulator/find.sh "Build number"  →  1400 1820.  Nothing printed = not on screen.
# Usage: tools/emulator/find.sh <text> [serial]
text=${1:?text}; serial=${2:-emulator-5554}
adb -s "$serial" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
adb -s "$serial" exec-out cat /sdcard/ui.xml | python3 -c '
import sys, re
want = sys.argv[1].lower()
for m in re.finditer(r"<node [^>]*>", sys.stdin.read()):
    n = m.group(0)
    t = (re.search(r"text=\"([^\"]*)\"", n).group(1) + " " + re.search(r"content-desc=\"([^\"]*)\"", n).group(1)).lower()
    if want in t:
        a, b, c, d = map(int, re.search(r"bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"", n).groups())
        print((a + c) // 2, (b + d) // 2); break
' "$text"
