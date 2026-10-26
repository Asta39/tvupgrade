#!/usr/bin/env bash
# One-time grants: media sessions for Now Playing, the sleep service for Control Center's Sleep and timer.
# Usage: scripts/grant.sh <tv ip>
set -euo pipefail
S="${1:?usage: grant.sh <tv ip>}:5555"
adb connect "$S" >/dev/null
adb -s "$S" shell cmd notification allow_listener com.gathin.tvhome/.GuardRevival
adb -s "$S" shell settings put secure enabled_accessibility_services com.gathin.tvhome/.SleepService
adb -s "$S" shell settings put secure accessibility_enabled 1
adb -s "$S" shell pm grant com.gathin.tvhome android.permission.READ_TV_LISTINGS
adb -s "$S" shell appops set com.gathin.tvhome WRITE_SETTINGS allow
adb -s "$S" shell cmd notification allow_dnd com.gathin.tvhome
echo granted
