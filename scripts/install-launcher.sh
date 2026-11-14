#!/usr/bin/env bash
# Build the launcher and install it on the TV as Home, then grant what the Keep watching row needs.
# The TV's own home screen and setup app stay ON: switching them off can make some Google TVs restart
# forever (couch-tv found this on a Sony Bravia; our Vitron hung the same way). Home still opens this
# launcher: set-home-activity, plus HomeKeyService sending the stock home back here (stock_home_redirect).
# Usage: scripts/install-launcher.sh <tv ip>
set -euo pipefail
cd "$(dirname "$0")/.."
[ -x tools/venv/bin/python ] || { python3 -m venv tools/venv && tools/venv/bin/pip install -q -r tools/requirements.txt; }
export TV_ADB_BINARY=adb TV_HOST="${1:?usage: install-launcher.sh <tv ip>}:5555"
adb connect "$TV_HOST"
STOCK_LAUNCHER="${STOCK_LAUNCHER:-none}" launcher/build-and-install.sh
# Earlier versions switched these off: turn them back on (a no-op when they are on or not installed).
for p in com.google.android.apps.tv.launcherx com.google.android.tvlauncher com.google.android.tungsten.setupwraith; do
  adb -s "$TV_HOST" shell pm enable "$p" >/dev/null 2>&1 || true
done
adb -s "$TV_HOST" shell cmd package set-home-activity --user 0 com.gathin.tvhome/.MainActivity >/dev/null
adb -s "$TV_HOST" shell settings put secure enabled_accessibility_services com.gathin.tvhome/.SleepService:com.gathin.tvhome/.HomeKeyService
adb -s "$TV_HOST" shell settings put secure accessibility_enabled 1
adb -s "$TV_HOST" shell pm grant com.gathin.tvhome android.permission.READ_TV_LISTINGS
