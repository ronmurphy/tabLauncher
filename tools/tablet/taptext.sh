#!/bin/sh
# Tap the first on-screen view whose text is exactly "$1".
b=$(adb shell uiautomator dump /sdcard/td_ui.xml >/dev/null 2>&1; adb shell cat /sdcard/td_ui.xml | tr '>' '\n' | grep -F "text=\"$1\"" | head -1 | sed -E 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/')
adb shell rm /sdcard/td_ui.xml
[ -z "$b" ] && { echo "not found: $1"; exit 1; }
set -- $b; adb shell input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 ))
