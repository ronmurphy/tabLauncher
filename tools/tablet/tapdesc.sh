#!/bin/sh
# Tap the on-screen view whose content description is exactly $1.
adb shell uiautomator dump /sdcard/td_ui.xml >/dev/null 2>&1
b=$(adb shell cat /sdcard/td_ui.xml | tr '>' '\n' | grep "content-desc=\"$(printf %s "$1" | sed "s/&/&amp;/g")\"" | head -1 | sed -E 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/')
adb shell rm /sdcard/td_ui.xml
[ -z "$b" ] && { echo "not found: $1"; exit 1; }
set -- $b
adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
