#!/bin/bash
# Starts Kilnmark and opens it in the browser: kilnmark.sh up, for the menu entry create-shortcut.sh
# makes. Opened from the menu, the terminal stays open on a failure so you can read why.
here="$(cd "$(dirname "$(readlink -f "$0")")" && pwd)"
if ! "$here/kilnmark.sh" up "$@"; then
  if [ -t 0 ]; then read -r -p "Press Enter to close. " _ || true; fi
  exit 1
fi
