#!/bin/bash
# Puts Kilnmark in the applications menu and on the desktop (Ubuntu and other freedesktop
# desktops), once, from the Kilnmark folder: ./create-shortcut.sh
# Kilnmark starts it (and Keycloak, when .env says COMPOSE_PROFILES=auth) and opens it in the
# browser; Stop Kilnmark stops it, keeping its data. Run it again after moving the folder.
set -euo pipefail
here="$(dirname "$(readlink -f "$0")")"
apps="${XDG_DATA_HOME:-$HOME/.local/share}/applications"
mkdir -p "$apps"

# One menu entry: its file, name, description and the script it runs, in a terminal for progress.
entry() {
  cat > "$1" <<EOF
[Desktop Entry]
Type=Application
Version=1.0
Name=$2
Comment=$3
Exec="$here/$4"
Path=$here
Icon=$here/kilnmark.png
Terminal=true
Categories=Development;
EOF
  chmod +x "$1"
}
entry "$apps/kilnmark.desktop" "Kilnmark" "Start Kilnmark and open it in the browser" start-kilnmark.sh
entry "$apps/kilnmark-stop.desktop" "Stop Kilnmark" "Stop Kilnmark; its data is kept" stop-kilnmark.sh
chmod +x "$here/start-kilnmark.sh" "$here/stop-kilnmark.sh"

desktop="$(xdg-user-dir DESKTOP 2> /dev/null || echo "$HOME/Desktop")"
where="the applications menu"
if [ -d "$desktop" ]; then
  for f in kilnmark.desktop kilnmark-stop.desktop; do
    cp "$apps/$f" "$desktop/$f"
    # GNOME runs a desktop launcher only once it is marked trusted.
    gio set "$desktop/$f" metadata::trusted true 2> /dev/null || true
  done
  where="the applications menu and on the desktop"
fi
echo "Kilnmark and Stop Kilnmark are in $where, starting $here."
