#!/bin/sh
# Drives the test caller on a device or emulator (TabDesk's agent service, plan T9). Its log is tagged AgentCaller.
#   tools/agentcaller/run.sh build            builds and installs it (debug key)
#   tools/agentcaller/run.sh build-alt        the same app signed with another key (certificate test)
#   tools/agentcaller/run.sh bind | connect | status | version | init | list | stop | resume | unbind | die
#   tools/agentcaller/run.sh call usage '{"limit":5}'
#   META='{"dev.tabdesk/agent":{"name":"tabLauncher","agent":"claude","person":{"hair":"swoop"}}}' run.sh call apps
#                                             the same, with the call's _meta (who the agent is, for its Pixel Person)
#   tools/agentcaller/run.sh cancel c1:4
#   tools/agentcaller/run.sh burst 6
#   tools/agentcaller/run.sh pw               shows a password field holding "hunter2" (screen reader tests)
#   tools/agentcaller/run.sh over             pw, with tools/seethrough's see-through card over it in the same task
#   tools/agentcaller/run.sh heads            a heads-up notification over the top of the screen (plan T12)
#   tools/agentcaller/run.sh overlay          a small window with a text field over every app, which takes the
#                                             keyboard's focus (overlay-off removes it); grant both first, see
#                                             MainActivity.kt
#   tools/agentcaller/run.sh put NAME SIZE [CHUNK] [STOPAFTER]
#                                             puts made-up bytes in the shared folder through folder_write (plan T13)
#   tools/agentcaller/run.sh get NAME         reads a file from the shared folder back, and logs its SHA-256
#   tools/agentcaller/run.sh streamping | streamhalf | streamstall | streambig
#                                             sendStream: whole, half then closed, half then 13 s, 2.5 MiB
#   tools/agentcaller/run.sh log              what it has logged so far
# Usage: [SERIAL=emulator-5554] [TWO=1] tools/agentcaller/run.sh <command> [args]
# TWO=1: the second caller (build it with "TWO=1 run.sh build"), to test two approved apps. It logs as AgentCaller2.
set -e
serial=${SERIAL:-emulator-5554}
if [ -n "$TWO" ]; then pkg=dev.tabdesk.agentcaller.two; tag=AgentCaller2; two=-Ptwo; else pkg=dev.tabdesk.agentcaller; tag=AgentCaller; two=""; fi
here=$(cd "$(dirname "$0")" && pwd)
# Commands go as broadcasts, so the app in front stays there (FRONT=1: the caller's own window comes to the front).
start() {
  if [ -n "$FRONT" ]; then adb -s "$serial" shell am start -n $pkg/dev.tabdesk.agentcaller.MainActivity "$@" >/dev/null
  else adb -s "$serial" shell am broadcast -n $pkg/dev.tabdesk.agentcaller.CommandReceiver "$@" >/dev/null; fi
}
case "$1" in
  build|build-alt)
    alt=""
    if [ "$1" = build-alt ]; then
      [ -f "$here/alt.jks" ] || keytool -genkeypair -keystore "$here/alt.jks" -storepass agentcaller -keypass agentcaller \
        -alias alt -keyalg RSA -keysize 2048 -validity 3650 -dname "CN=AgentCaller alt" >/dev/null 2>&1
      alt="-Palt"
    fi
    "$here/../../gradlew" -p "$here" --offline -q assembleDebug $alt $two
    adb -s "$serial" install -r "$here/build/outputs/apk/debug/AgentCaller-debug.apk" ;;
  log) adb -s "$serial" logcat -d -s $tag:I | grep -v "^-----" ;;
  call)
    if [ -n "$META" ]; then start --es cmd call --es tool "$2" --es args64 "$(printf '%s' "${3:-{\}}" | base64 -w0)" --es meta64 "$(printf '%s' "$META" | base64 -w0)"
    else start --es cmd call --es tool "$2" --es args64 "$(printf '%s' "${3:-{\}}" | base64 -w0)"; fi ;;
  cancel) start --es cmd cancel --es id "$2" ;;
  burst) start --es cmd burst --es n "${2:-6}" ;;
  pw) FRONT=1 start --es cmd pw ;;
  over) FRONT=1 start --es cmd over ;;
  raw) start --es cmd raw --es b64 "$(printf '%s' "$2" | base64 -w0)" ;;
  put) start --es cmd put --es name "$2" --es size "$3" ${4:+--es chunk "$4"} ${5:+--es stopafter "$5"} ;;
  get) start --es cmd get --es name "$2" ;;
  *) start --es cmd "$1" ;;
esac
