#!/usr/bin/env bash
# Push a theme (config.json, wallpapers/, depth/) to the launcher and restart Home.
# Uses plain `adb push`, because tools/theme.sh's shell chunks are too long for Google's adb.
# Usage: scripts/push-theme.sh <tv ip> [theme folder, default themes/ian-dusk]
set -euo pipefail
cd "$(dirname "$0")/.."
[ -x tools/venv/bin/python ] || { python3 -m venv tools/venv && tools/venv/bin/pip install -q -r tools/requirements.txt; }
S="${1:?usage: push-theme.sh <tv ip> [theme]}:5555"; T="${2:-themes/ian-dusk}"
P=com.gathin.tvhome; D=/sdcard/Android/data/$P/files; U=content://$P.theme; X=/data/local/tmp/cyt
adb connect "$S" >/dev/null
put() { adb -s "$S" push -q "$1" $X-x >/dev/null && adb -s "$S" shell "rm -f $D/$2; content write --uri $U/$2 < $X-x || cp $X-x $D/$2; rm -f $X-x"; }
adb -s "$S" shell "mkdir -p $D/wallpapers $D/depth; content delete --uri $U/wallpapers 2>/dev/null; rm -f $D/wallpapers/* $D/depth/*"
put "$T/config.json" config.json
for f in "$T"/wallpapers/*; do [ -f "$f" ] && put "$f" "wallpapers/$(basename "$f")"; done
for f in "$T"/depth/*.png; do [ -f "$f" ] && put "$f" "depth/$(basename "$f")"; done
uuidgen | tr -d - | tr -d '\n' > "${TMPDIR:-/tmp}/rev"; put "${TMPDIR:-/tmp}/rev" theme_revision
TV_ADB_BINARY=adb TV_HOST="$S" tools/venv/bin/python -I tools/theme_restart.py >/dev/null
echo "theme pushed: $T"
