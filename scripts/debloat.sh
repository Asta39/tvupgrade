#!/usr/bin/env bash
# Disable (never uninstall) the packages in debloat/tcl-g10-google-tv.txt. Backs up state first.
# Usage: scripts/debloat.sh <tv ip>
set -euo pipefail
cd "$(dirname "$0")/.."
[ -x tools/venv/bin/python ] || { python3 -m venv tools/venv && tools/venv/bin/pip install -q -r tools/requirements.txt; }
export TV_ADB_BINARY=adb TV_HOST="${1:?usage: debloat.sh <tv ip>}:5555"
adb connect "$TV_HOST"
mkdir -p backup
adb -s "$TV_HOST" shell pm list packages -d > backup/packages-disabled.txt
for n in system secure global; do adb -s "$TV_HOST" shell settings list $n > backup/settings-$n.txt; done
tools/disable.sh debloat/tcl-g10-google-tv.txt
