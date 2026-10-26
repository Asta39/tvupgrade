#!/usr/bin/env bash
# Build the launcher and install it on the TV as Home, then grant what the Keep watching row needs.
# Usage: scripts/install-launcher.sh <tv ip>
set -euo pipefail
cd "$(dirname "$0")/.."
[ -x tools/venv/bin/python ] || { python3 -m venv tools/venv && tools/venv/bin/pip install -q -r tools/requirements.txt; }
export TV_ADB_BINARY=adb TV_HOST="${1:?usage: install-launcher.sh <tv ip>}:5555"
adb connect "$TV_HOST"
launcher/build-and-install.sh
adb -s "$TV_HOST" shell pm grant com.gathin.tvhome android.permission.READ_TV_LISTINGS
