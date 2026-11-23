#!/bin/sh
# Build, install over the existing app (keeps your layout and settings), and bring TabDesk back to the front.
# Android stops an app while updating it, so without the last step Lenovo's old launcher (still parked in the
# background) can show until Home is pressed. Works even if the tablet is locked.
set -e
cd "$(dirname "$0")"
./gradlew --offline -q assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null 2>&1
echo "TabDesk updated."
