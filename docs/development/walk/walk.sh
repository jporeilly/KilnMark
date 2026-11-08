#!/bin/bash
# The Linux walk of the guided install (release 0.1.2), in the throwaway WSL distribution km-clean.
# Run as root: wsl -d km-clean -u root -- bash .../docs/development/walk/walk.sh RELEASE.tar.gz [PART]
# Each step runs as the account a real install would use (kmadmin, an admin who may sudo; kilnmark,
# the account that runs Kilnmark; kmuser2, another ordinary user), and prints
# "WALK [n] title: OK (t s)" or "FAILED (t s)". PART: starter, production, or both (the default).
set -uo pipefail
release="$1" part="${2:-both}"
here="$(cd "$(dirname "$0")" && pwd)"
n=0 failed=0
now() { date +%s.%N; }
step() {
  local title="$1" t0 t1 rc; shift
  n=$((n + 1)); t0="$(now)"
  echo "WALK [$n] $title"
  echo "WALK [$n] \$ $*"
  "$@"; rc=$?
  t1="$(now)"
  if [ "$rc" -eq 0 ]; then echo "WALK [$n] $title: OK ($(awk -v a="$t0" -v b="$t1" 'BEGIN{printf "%.1f", b-a}') s)"
  else echo "WALK [$n] $title: FAILED rc=$rc ($(awk -v a="$t0" -v b="$t1" 'BEGIN{printf "%.1f", b-a}') s)"; failed=$((failed + 1)); fi
}
# what must fail, as a step that is OK when it does
refused() { if "$@"; then echo "  (it was allowed)"; return 1; else echo "  (refused, as it should be)"; return 0; fi; }
as() { local u="$1"; shift; runuser -l "$u" -c "$*"; }
# the account's own session, as sudo -iu kilnmark gives it once lingering is on
km() { as kilnmark "export XDG_RUNTIME_DIR=/run/user/\$(id -u) DBUS_SESSION_BUS_ADDRESS=unix:path=/run/user/\$(id -u)/bus; $*"; }

mkdir -p /home/kmadmin/walk && cd /home/kmadmin/walk
cp "$release" /home/kmadmin/walk/ && chown -R kmadmin:kmadmin /home/kmadmin/walk
rel="$(basename "$release")" dir="${rel%.tar.gz}"
# the walk's throwaway passwords, never printed
for f in admin-pw people-pw person-pw; do
  [ -f "/home/kmadmin/walk/$f" ] || head -c 18 /dev/urandom | base64 | tr -d '/+=' > "/home/kmadmin/walk/$f"
done
cp /home/kmadmin/walk/people-pw /home/kmadmin/walk/person-pw /var/tmp/ && chmod 644 /var/tmp/people-pw /var/tmp/person-pw
chown kmadmin:kmadmin /home/kmadmin/walk/*-pw && chmod 600 /home/kmadmin/walk/*-pw
cat > /home/kmadmin/walk/starter.conf <<'EOF'
mode=starter-pack
email_domain=walk.example
host=localhost
tls=off
port=18780
https_port=18443
db_port=15442
keycloak_port=18180
admin_email=ada@walk.example
admin_first_name=Ada
admin_last_name=Walker
project=kmwalk
EOF
cat > /home/kmadmin/walk/production.conf <<'EOF'
mode=production
identity=bundled
email_domain=walk.example
host=localhost
tls=off
port=19780
https_port=19443
db_port=19442
keycloak_port=19180
admin_email=ada@walk.example
admin_first_name=Ada
admin_last_name=Walker
project=kmwalkprod
program_dir=/opt/kilnmark walk
data_dir=/var/opt/kilnmark walk
EOF
chown kmadmin:kmadmin /home/kmadmin/walk/*.conf

if [ "$part" != production ]; then
  echo "===== Starter Pack ====="
  step "kmadmin unpacks the release" as kmadmin "cd ~/walk && tar -xzf $rel && ls $dir | head -30"
  step "kmadmin checks the machine" as kmadmin "cd ~/walk/$dir && ./kilnmark.sh check"
  step "kmadmin checks the answers (dry run)" as kmadmin "cd ~/walk/$dir && ./kilnmark.sh install --answers ~/walk/starter.conf --dry-run"
  step "an answers file with a secret is refused" refused as kmadmin "cd ~/walk/$dir && cp ~/walk/starter.conf ~/walk/bad.conf && echo admin_password=nope-nope-nope >> ~/walk/bad.conf && ./kilnmark.sh install --answers ~/walk/bad.conf --dry-run"
  step "kmadmin installs (it asks sudo itself), building the images" as kmadmin "cd ~/walk/$dir && ./kilnmark.sh install --answers ~/walk/starter.conf --build --skip-verify < ~/walk/admin-pw"
  step "the folders: program root's, data the account's" bash -c "stat -c '%U:%G %a %n' /opt/kilnmark /opt/kilnmark/kilnmark.sh /opt/kilnmark/data-folder.txt /opt/kilnmark/versions/* /var/opt/kilnmark /var/opt/kilnmark/config /var/opt/kilnmark/config/.env /var/opt/kilnmark/config/install.conf; cat /opt/kilnmark/data-folder.txt; ! grep -Eiq '(password|secret|token)=' /var/opt/kilnmark/config/install.conf && echo 'install.conf: no secret'"
  step "kilnmark: status" km "/opt/kilnmark/kilnmark.sh status"
  step "kilnmark: up again (nothing to do but start)" km "/opt/kilnmark/kilnmark.sh up --no-browser"
  step "kilnmark: the hourly timer, its path quoted" km "systemctl --user cat kilnmark-kmwalk-people-expire.service && systemctl --user list-timers --all | grep kilnmark"
  step "kilnmark: the timer's run" km "systemctl --user start kilnmark-kmwalk-people-expire.service; sleep 2; tail -n 3 /var/opt/kilnmark/logs/people-expire-\$(date -u +%Y%m%d).log"
  step "kilnmark: starter people (password piped in)" km "/opt/kilnmark/kilnmark.sh starter people < /var/tmp/people-pw"
  step "sign in as an example person, vera.lane (viewer), through Keycloak" km "python3 $here/signin.py http://localhost:18780 vera.lane@kilnmark.com < /var/tmp/people-pw"
  step "kilnmark: people add" km "printf '%s\n' bob@walk.example Bob Builder kilnmark-viewers \"\$(cat /var/tmp/person-pw)\" | /opt/kilnmark/kilnmark.sh people add"
  step "kilnmark: people list (ada, bob)" km "/opt/kilnmark/kilnmark.sh people list | grep -E 'ada@walk.example|bob@walk.example'"
  step "kilnmark: check" km "/opt/kilnmark/kilnmark.sh check"
  step "kilnmark: configure (show)" km "/opt/kilnmark/kilnmark.sh configure"
  step "kilnmark: configure email_domain" km "/opt/kilnmark/kilnmark.sh configure email_domain=walk2.example"
  step "kilnmark: configure mode is refused" refused km "/opt/kilnmark/kilnmark.sh configure mode=production"
  step "kilnmark: configure port (restarts)" km "/opt/kilnmark/kilnmark.sh configure port=18781 && curl -s -o /dev/null -w '%{http_code}\n' http://localhost:18781/q/health/ready && /opt/kilnmark/kilnmark.sh configure port=18780"
  step "kilnmark: backup" km "/opt/kilnmark/kilnmark.sh backup && ls -la /var/opt/kilnmark/backups/*/"
  step "kmuser2 cannot write the program folder" refused as kmuser2 "touch /opt/kilnmark/planted"
  step "kmuser2 cannot change the launcher" refused as kmuser2 "echo '# planted' >> /opt/kilnmark/kilnmark.sh"
  step "kmuser2 cannot change the data folder" refused as kmuser2 "touch /var/opt/kilnmark/planted"
  step "kmuser2 cannot read the settings" refused as kmuser2 "cat /var/opt/kilnmark/config/.env"
  step "kmuser2 cannot run Kilnmark" refused as kmuser2 "/opt/kilnmark/kilnmark.sh status"
  step "root's day-to-day command is refused" refused bash -c "/opt/kilnmark/kilnmark.sh up --no-browser"
  step "kmadmin uninstalls with --purge" as kmadmin "echo DELETE | sudo /opt/kilnmark/kilnmark.sh uninstall --purge"
  step "nothing left: folders, timer, containers" bash -c "! ls -d /opt/kilnmark /var/opt/kilnmark 2> /dev/null && ! runuser -l kilnmark -c 'systemctl --user list-timers --all' | grep -q kilnmark && runuser -l kilnmark -c 'podman ps -a --format {{.Names}}' | { ! grep -q kmwalk; } && echo 'nothing left'"
fi

if [ "$part" != starter ]; then
  echo "===== Production with the bundled Keycloak, folders with spaces ====="
  [ -d "/home/kmadmin/walk/$dir" ] || as kmadmin "cd ~/walk && tar -xzf $rel"
  step "kmadmin installs production (images built again)" as kmadmin "cd ~/walk/$dir && ./kilnmark.sh install --answers ~/walk/production.conf --build --skip-verify < ~/walk/admin-pw"
  step "the folders, with spaces" bash -c "stat -c '%U:%G %a %n' '/opt/kilnmark walk' '/opt/kilnmark walk/kilnmark.sh' '/var/opt/kilnmark walk' '/var/opt/kilnmark walk/config/.env'"
  step "kilnmark: status" km "'/opt/kilnmark walk/kilnmark.sh' status"
  step "kilnmark: check" km "'/opt/kilnmark walk/kilnmark.sh' check"
  step "kilnmark: the timer's unit quotes the path" km "systemctl --user cat kilnmark-kmwalkprod-people-expire.service | grep ExecStart && systemctl --user start kilnmark-kmwalkprod-people-expire.service && sleep 2 && tail -n 2 \"/var/opt/kilnmark walk/logs/people-expire-\$(date -u +%Y%m%d).log\""
  step "kilnmark: people list (the first admin)" km "'/opt/kilnmark walk/kilnmark.sh' people list | grep 'ada@walk.example'"
  step "kilnmark: starter people is refused on production" refused km "'/opt/kilnmark walk/kilnmark.sh' starter people < /var/tmp/people-pw"
  step "kilnmark: backup into a folder with a space" km "'/opt/kilnmark walk/kilnmark.sh' backup && ls '/var/opt/kilnmark walk/backups/'"
  step "kmuser2 cannot write the program folder" refused as kmuser2 "touch '/opt/kilnmark walk/planted'"
  step "kmuser2 cannot change the data folder" refused as kmuser2 "touch '/var/opt/kilnmark walk/planted'"
  step "kmadmin uninstalls with --purge" as kmadmin "echo DELETE | sudo '/opt/kilnmark walk/kilnmark.sh' uninstall --purge"
  step "nothing left" bash -c "! ls -d '/opt/kilnmark walk' '/var/opt/kilnmark walk' 2> /dev/null && echo 'nothing left'"
fi
echo "WALK done: $n steps, $failed FAILED"
