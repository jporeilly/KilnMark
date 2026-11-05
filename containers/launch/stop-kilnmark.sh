#!/bin/bash
# Stops Kilnmark; its data is kept: kilnmark.sh down, for the menu entry create-shortcut.sh makes.
here="$(cd "$(dirname "$(readlink -f "$0")")" && pwd)"
if ! "$here/kilnmark.sh" down; then
  if [ -t 0 ]; then read -r -p "Press Enter to close. " _ || true; fi
  exit 1
fi
sleep 2
