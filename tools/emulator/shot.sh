#!/bin/sh
# Screenshot of an emulator (or any device), shrunk to half size so it's quick to look at.
# Usage: tools/emulator/shot.sh <out.png> [serial]   (serial defaults to emulator-5554)
# Half size: tap at twice the coordinates you see in the picture.
out=${1:?out.png}; serial=${2:-emulator-5554}
adb -s "$serial" exec-out screencap -p | magick - -resize 50% "$out"
