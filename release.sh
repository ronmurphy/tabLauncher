#!/usr/bin/env bash
# Publishes a new TabDesk release on GitHub, in one go:
#   ./release.sh 1.0.2              notes made from the commits since the last release
#   ./release.sh 1.0.2 notes.md     notes from a file (Markdown)
#   ./release.sh -y 1.0.2 ...       don't ask before committing and publishing
#
# It sets the version, builds the APK (signed with TabDesk's key from signing/), checks the signature and that
# nothing needs a newer Android than TabDesk supports, then commits, tags, pushes, publishes the release with the
# APK attached, and checks the download. It stops before committing anything if a check fails.
set -euo pipefail
cd "$(dirname "$0")"

yes=0
[ "${1:-}" = "-y" ] && { yes=1; shift; }
version=${1:-}
notes_file=${2:-}

die() { echo "release: $*" >&2; exit 1; }
ask() { [ $yes = 1 ] && return 0; read -r -p "$1 [y/N] " a; [ "$a" = y ] || [ "$a" = Y ]; }

# ---- checks before changing anything
[[ $version =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || die "usage: ./release.sh [-y] <version like 1.0.2> [notes.md]"
[ -z "$notes_file" ] || [ -f "$notes_file" ] || die "no notes file $notes_file"
[ "$(git branch --show-current)" = main ] || die "not on main"
git rev-parse -q --verify "refs/tags/v$version" >/dev/null && die "v$version already exists"
[ -f signing/keystore.properties ] || die "signing/keystore.properties is missing: releases must be signed with TabDesk's key"
command -v gh >/dev/null || die "the GitHub CLI (gh) isn't installed"

gradle_file=app/build.gradle.kts
current=$(sed -n 's/^ *versionName = "\(.*\)"/\1/p' $gradle_file)
newest=$(printf '%s\n%s\n' "$current" "$version" | sort -V | tail -1)
[ "$version" != "$current" ] && [ "$newest" = "$version" ] || die "$version isn't newer than $current"

git fetch -q origin
[ "$(git rev-list --count HEAD..origin/main)" = 0 ] || die "GitHub has commits this copy doesn't: git pull first"
repo=$(gh repo view --json nameWithOwner -q .nameWithOwner)

if [ -n "$(git status --porcelain)" ]; then
    echo "Not committed yet:"; git status --short
    ask "Include these in the release?" || die "stopped"
fi

sdk=$(sed -n 's/^sdk.dir=//p' local.properties 2>/dev/null); sdk=${sdk:-${ANDROID_HOME:-$HOME/Android/Sdk}}
tools=$(ls -d "$sdk"/build-tools/* | sort -V | tail -1)

# ---- version, build, checks
echo "Building $current → $version…"
sed -i "s/^\( *versionName = \)\".*\"/\1\"$version\"/" $gradle_file
restore() { git checkout -q -- $gradle_file; }
trap 'echo "release: failed, version left at $current"; restore' ERR

./gradlew --offline -q assembleRelease 2>/dev/null || ./gradlew -q assembleRelease
apk=app/build/outputs/apk/release/app-release.apk

# (Captured first: piping into head or grep -q can cut the tool off, which pipefail counts as failing.)
badging=$("$tools/aapt2" dump badging $apk)
[[ $badging == *"versionName='$version'"* ]] || { echo "release: the APK says ${badging%%$'\n'*}"; false; }
signature=$("$tools/apksigner" verify -v --print-certs $apk)
[[ $signature == *"Verified using v3 scheme (APK Signature Scheme v3): true"* ]] \
    || { echo "release: the APK isn't signed with the v3 scheme"; false; }
[[ $signature == *"certificate DN: CN=TabDesk"* ]] || { echo "release: the APK isn't signed with TabDesk's key"; false; }

if [ "${SKIP_LINT:-0}" != 1 ]; then
    echo "Checking for calls that need a newer Android (lint)…"
    report=app/build/reports/lint-results-debug.xml
    rm -f $report
    ./gradlew -q lintDebug >/dev/null 2>&1 || true   # fails on any error; only calls too new for Android 12 matter here
    [ -f $report ] || { echo "release: lint didn't run (it needs the internet the first time). SKIP_LINT=1 skips it"; false; }
    too_new=$(python3 - app/build/reports/lint-results-debug.xml <<'EOF'
import sys, xml.etree.ElementTree as ET
for i in ET.parse(sys.argv[1]).getroot().iter('issue'):
    if i.get('id') == 'NewApi':
        l = i.find('location')
        print(f"  {l.get('file').split('/java/')[-1]}:{l.get('line')}: {i.get('message')}")
EOF
)
    [ -z "$too_new" ] || { echo "release: these need a newer Android than TabDesk supports:"; echo "$too_new"; false; }
fi
trap - ERR

# ---- notes
previous=$(git describe --tags --abbrev=0 2>/dev/null || true)
notes=$(mktemp); trap 'rm -f "$notes"' EXIT
if [ -n "$notes_file" ]; then cat "$notes_file" > "$notes"
else
    { echo "Changes since ${previous:-the start}:"; echo
      git log --format='- %s' ${previous:+$previous..}HEAD | grep -v '^- Release ' || true; } > "$notes"
fi
printf '\n**Installing or updating:** download `TabDesk-%s.apk` below on the tablet and open it. ' "$version" >> "$notes"
printf 'See the [install steps](https://github.com/%s#install).\n' "$repo" >> "$notes"

size=$(du -k $apk | cut -f1)
echo; echo "TabDesk $version: APK ${size} KB, signed with TabDesk's key. Release notes:"; echo; sed 's/^/    /' "$notes"; echo
ask "Commit, push and publish TabDesk $version on GitHub?" || { restore; die "stopped, version left at $current"; }

# ---- publish
git add -A
git commit -q -m "Release $version"
git tag -a "v$version" -m "TabDesk $version"
git push -q origin main --follow-tags
out=$(mktemp -d); cp $apk "$out/TabDesk-$version.apk"
gh release create "v$version" "$out/TabDesk-$version.apk" --repo "$repo" --title "TabDesk $version" --notes-file "$notes"

# ---- check what people will download
if curl -sfL -o "$out/check.apk" "https://github.com/$repo/releases/download/v$version/TabDesk-$version.apk" \
    && cmp -s "$out/check.apk" "$out/TabDesk-$version.apk"; then
    echo "Published: https://github.com/$repo/releases/tag/v$version (download checked)"
else
    echo "Published, but the download didn't match the build: check https://github.com/$repo/releases/tag/v$version"
fi
rm -rf "$out"
