#!/bin/bash
# km-clean: a fresh Ubuntu 26.04 for the Linux walk. As root: the admin user (sudo), a second
# ordinary user, and Podman with podman-compose.
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive
id kmadmin > /dev/null 2>&1 || useradd --create-home --shell /bin/bash --groups sudo kmadmin
id kmuser2 > /dev/null 2>&1 || useradd --create-home --shell /bin/bash kmuser2
# kmadmin may sudo without a password in this throwaway distribution only
echo 'kmadmin ALL=(ALL) NOPASSWD:ALL' > /etc/sudoers.d/kmadmin && chmod 440 /etc/sudoers.d/kmadmin
apt-get update -q
apt-get install -y -q podman podman-compose curl tar uidmap slirp4netns passt dbus-user-session
podman --version
podman-compose --version || true
