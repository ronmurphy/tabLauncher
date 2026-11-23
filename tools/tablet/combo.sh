#!/bin/bash
# combo.sh <style> <colour set> <out.png>: set both in Customize, close it, screenshot.
S=$(cd "$(dirname "$0")/.." && pwd)   # tools/
NAMES='^(Accent|App colours|Auto|White on black|Black on white|Sakura|Summer|Autumn|Winter|Ukiyo-e|Matcha|Indigo|Maple|Sepia|Riso|Chalkboard|Blueprint|Terminal|Synthwave|Gold)\|'
for t in 1 2 3; do adb shell input swipe 27 758 27 758 900; sleep 1.8; $S/tablet/bounds.sh | grep -q '^Original|' && break; adb shell input keyevent 4; sleep 1; done
$S/tablet/tapdesc.sh "$1"; sleep 4
y=$($S/tablet/bounds.sh | grep -E "$NAMES" | head -1 | sed -E 's/.*\]\[[0-9]+,([0-9]+)\]$/\1/'); y=$((y-40))
for i in 1 2 3; do adb shell input swipe 530 $y 1050 $y 250; sleep 0.4; done; sleep 0.6
for i in 1 2 3 4 5 6 7 8; do $S/tablet/bounds.sh | grep -q "^$2|" && break; adb shell input swipe 1000 $y 650 $y 1000; sleep 1; done
$S/tablet/tapdesc.sh "$2"; sleep 4; adb shell input tap 1047 131; sleep 1.2; adb exec-out screencap -p > "$3"
