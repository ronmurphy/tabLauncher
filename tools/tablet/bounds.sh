#!/bin/sh
# Print "name|bounds" for every view with a content description on screen.
adb shell uiautomator dump /sdcard/td_ui.xml >/dev/null 2>&1
adb shell cat /sdcard/td_ui.xml | tr '>' '\n' | grep -E 'content-desc="[^"]+"' | sed -E 's/.*content-desc="([^"]*)".*bounds="([^"]*)".*/\1|\2/'
adb shell rm /sdcard/td_ui.xml
