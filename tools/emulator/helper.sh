#!/bin/sh
# Runs one of TabDesk's helper commands on a device from this computer, as the shell user, the way TabDesk's helper
# runs, e.g. to try window calls on a new Android version:
#   tools/emulator/helper.sh tasks
#   tools/emulator/helper.sh min 36          (minimise task 36; front 36 brings it back)
#   tools/emulator/helper.sh resize 36 0 0 1400 1850
#   tools/emulator/helper.sh shot 36 480      (then: adb exec-out cat /data/local/tmp/shot.jpg > shot.jpg)
#   tools/emulator/helper.sh methods snapshot (the system calls with that word, as this Android has them)
# Usage: [SERIAL=emulator-5554] tools/emulator/helper.sh <command> [args]
serial=${SERIAL:-emulator-5554}
apk=$(adb -s "$serial" shell pm path dev.tabdesk | head -n1 | cut -d: -f2 | tr -d '\r')
[ -n "$apk" ] || { echo "TabDesk isn't installed on $serial" >&2; exit 1; }
adb -s "$serial" shell CLASSPATH="$apk" app_process /system/bin dev.tabdesk.helper.Helper "$@"
