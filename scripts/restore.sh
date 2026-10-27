#!/usr/bin/env bash
# Re-enable everything the debloat list disabled, give Home back to Google TV and reset Private DNS.
# Usage: scripts/restore.sh <tv ip>
set -euo pipefail
cd "$(dirname "$0")/.."
export TV_ADB_BINARY=adb TV_HOST="${1:?usage: restore.sh <tv ip>}:5555"
adb connect "$TV_HOST"
tools/restore.sh debloat/tcl-g10-google-tv.txt com.google.android.apps.tv.launcherx
adb -s "$TV_HOST" shell settings put global private_dns_mode opportunistic
# Google's ambient screensaver, as it was before StandBy took over.
adb -s "$TV_HOST" shell settings put secure screensaver_components com.google.android.apps.tv.dreamx/.service.Backdrop
echo "Done. Optional: adb -s $TV_HOST uninstall com.gathin.tvhome"
