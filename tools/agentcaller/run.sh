#!/bin/sh
# Drives the test caller on a device or emulator (TabDesk's agent service, plan T9). Its log is tagged AgentCaller.
#   tools/agentcaller/run.sh build            builds and installs it (debug key)
#   tools/agentcaller/run.sh build-alt        the same app signed with another key (certificate test)
#   tools/agentcaller/run.sh bind | connect | status | version | init | list | stop | resume | unbind | die
#   tools/agentcaller/run.sh call usage '{"limit":5}'
#   tools/agentcaller/run.sh cancel c1:4
#   tools/agentcaller/run.sh burst 6
#   tools/agentcaller/run.sh log              what it has logged so far
# Usage: [SERIAL=emulator-5554] tools/agentcaller/run.sh <command> [args]
set -e
serial=${SERIAL:-emulator-5554}
here=$(cd "$(dirname "$0")" && pwd)
start() { adb -s "$serial" shell am start -n dev.tabdesk.agentcaller/.MainActivity "$@" >/dev/null; }
case "$1" in
  build|build-alt)
    alt=""
    if [ "$1" = build-alt ]; then
      [ -f "$here/alt.jks" ] || keytool -genkeypair -keystore "$here/alt.jks" -storepass agentcaller -keypass agentcaller \
        -alias alt -keyalg RSA -keysize 2048 -validity 3650 -dname "CN=AgentCaller alt" >/dev/null 2>&1
      alt="-Palt"
    fi
    "$here/../../gradlew" -p "$here" --offline -q assembleDebug $alt
    adb -s "$serial" install -r "$here/build/outputs/apk/debug/AgentCaller-debug.apk" ;;
  log) adb -s "$serial" logcat -d -s AgentCaller:I | grep -v "^-----" ;;
  call) start --es cmd call --es tool "$2" --es args64 "$(printf '%s' "${3:-{\}}" | base64 -w0)" ;;
  cancel) start --es cmd cancel --es id "$2" ;;
  burst) start --es cmd burst --es n "${2:-6}" ;;
  raw) start --es cmd raw --es b64 "$(printf '%s' "$2" | base64 -w0)" ;;
  *) start --es cmd "$1" ;;
esac
