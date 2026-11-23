#!/bin/bash
# theme.sh <theme name> <out.png>: tap that theme in Customize → Icons, close, screenshot.
S=$(cd "$(dirname "$0")/.." && pwd)   # tools/
for t in 1 2 3; do adb shell input swipe 27 758 27 758 900; sleep 1.8; $S/tablet/bounds.sh | grep -q '^Close|' && break; adb shell input keyevent 4; sleep 1; done
$S/tablet/taptext.sh Icons >/dev/null; sleep 2; adb shell input swipe 780 300 780 650 300; sleep 0.8
y=262
for i in 1 2 3; do adb shell input swipe 530 $y 1050 $y 250; sleep 0.4; done; sleep 0.6
for i in 1 2 3 4 5 6 7 8; do $S/tablet/bounds.sh | grep -q "^$1|" && break; adb shell input swipe 1000 $y 650 $y 1000; sleep 1; done
$S/tablet/tapdesc.sh "$1"; sleep 4.5; adb shell input tap 1047 131; sleep 1.2; adb exec-out screencap -p > "$2"
