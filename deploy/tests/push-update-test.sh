#!/usr/bin/env bash
# Run the real publisher against a local fake SSH/SCP host, never the cloud.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
TMP_ROOT=${YBCASE_TEST_TMP_ROOT:-/mnt/c/Users/drxuj/Temp}
mkdir -p "$TMP_ROOT"
WORK=$(mktemp -d "$TMP_ROOT/ybcase-publish-test-XXXXXX")
case "$WORK" in *OneDrive*) exit 1;; esac
trap 'rm -rf -- "$WORK"' EXIT
mkdir -p "$WORK/bin" "$WORK/client/deploy" "$WORK/client/server/target" "$WORK/client/frontend/dist/assets"
cp "$ROOT/deploy/"{push-update.sh,upgrade.sh,Caddyfile,docker-compose.yml} "$WORK/client/deploy/"
printf new-jar > "$WORK/client/server/target/ybcase-server-1.2.0.jar"
printf new-index > "$WORK/client/frontend/dist/index.html"
printf new-chunk > "$WORK/client/frontend/dist/assets/new.js"
printf '#!/usr/bin/env bash\nprintf backup\\n >> "$MOCK_HOME/commands"\n[ "${MOCK_FAIL:-}" != backup ]\n' > "$WORK/client/deploy/backup.sh"
printf '#!/usr/bin/env bash\nexit 0\n' > "$WORK/client/deploy/healthcheck.sh"
cat > "$WORK/bin/ssh" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
[ "${MOCK_FAIL:-}" != ssh ] || exit 44
[ "$1" = ybcase-hk ] || exit 99
cmd=${2//\/opt\/ybcase/$MOCK_HOME}
YBCASE_HOME="$MOCK_HOME" bash -c "$cmd"
MOCK
cat > "$WORK/bin/scp" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
[ "${MOCK_FAIL:-}" != upload ] || exit 45
args=("$@")
last=$(($#-1)); dest=${args[$last]#ybcase-hk:}
case "$dest" in /opt/ybcase/*) ;; *) exit 99;; esac
dest=${dest/\/opt\/ybcase/$MOCK_HOME}
unset 'args[last]'
cp "${args[@]}" "$dest"
MOCK
cat > "$WORK/bin/sudo" <<'MOCK'
#!/usr/bin/env bash
exec "$@"
MOCK
cat > "$WORK/bin/cp" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
if [ "${MOCK_FAIL:-}" = static-copy ] && [[ "$*" == *'-t dist/'* ]]; then exit 46; fi
exec /usr/bin/cp "$@"
MOCK
cat > "$WORK/bin/sleep" <<'MOCK'
#!/usr/bin/env bash
exit 0
MOCK
cat > "$WORK/bin/docker" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "$MOCK_HOME/commands"
case "$*" in
 *'config --quiet'*) [ "${MOCK_FAIL:-}" != config ] ;;
 *'up -d --force-recreate app'*) [ "${MOCK_FAIL:-}" != recreate ] ;;
 *'exec -T app sh -c'*health*) printf '{"status":"UP"}\n' ;;
 *'exec -T caddy caddy reload'*) [ "${MOCK_FAIL:-}" != reload ] ;;
 'compose up -d'|'compose ps') : ;;
 *) echo "Unexpected docker command $*" >&2; exit 99 ;;
esac
MOCK
chmod +x "$WORK/bin/"*
export PATH="$WORK/bin:$PATH"
for scenario in success backup config upload ssh recreate reload static-copy; do
 export MOCK_HOME="$WORK/host-$scenario" MOCK_FAIL=$scenario
 mkdir -p "$MOCK_HOME/dist/assets"
 printf old-jar > "$MOCK_HOME/ybcase-server.jar"
 printf old-index > "$MOCK_HOME/dist/index.html"
 printf old-chunk > "$MOCK_HOME/dist/assets/old.js"
 printf old-compose > "$MOCK_HOME/docker-compose.yml"
 printf old-caddy > "$MOCK_HOME/Caddyfile"
 printf 'DEMO_DOMAIN=example.invalid\n' > "$MOCK_HOME/.env"
 before_jar=$(stat -c%i "$MOCK_HOME/ybcase-server.jar")
 before_caddy=$(stat -c%i "$MOCK_HOME/Caddyfile")
 rc=0
 bash "$WORK/client/deploy/push-update.sh" --prebuilt > "$WORK/$scenario.log" 2>&1 || rc=$?
 if [ "$scenario" = success ]; then
  [ "$rc" -eq 0 ] || { cat "$WORK/$scenario.log"; exit 1; }
  [ "$(cat "$MOCK_HOME/ybcase-server.jar")" = new-jar ]
  [ "$(cat "$MOCK_HOME/ybcase-server.jar.prev")" = old-jar ]
  [ "$(stat -c%i "$MOCK_HOME/ybcase-server.jar")" != "$before_jar" ]
  [ "$(stat -c%i "$MOCK_HOME/Caddyfile")" = "$before_caddy" ]
  cmp "$ROOT/deploy/docker-compose.yml" "$MOCK_HOME/docker-compose.yml"
  cmp "$ROOT/deploy/Caddyfile" "$MOCK_HOME/Caddyfile"
  [ "$(cat "$MOCK_HOME/dist/index.html")" = new-index ]
  [ -f "$MOCK_HOME/dist/assets/old.js" ] && [ -f "$MOCK_HOME/dist/assets/new.js" ]
  archive=("$MOCK_HOME"/rollback/*/dist.tar.gz)
  [ "$(tar -xOzf "${archive[0]}" dist/index.html)" = old-index ]
  grep -q 'caddy reload' "$MOCK_HOME/commands"
 else
  [ "$rc" -ne 0 ] || { cat "$WORK/$scenario.log"; exit 1; }
  ! grep -q '^==> 完成' "$WORK/$scenario.log"
  case "$scenario" in
   backup|config|upload|ssh)
    [ "$(cat "$MOCK_HOME/ybcase-server.jar")" = old-jar ]
    [ "$(cat "$MOCK_HOME/dist/index.html")" = old-index ]
    [ "$(cat "$MOCK_HOME/docker-compose.yml")" = old-compose ] ;;
  esac
  if [ "$scenario" = recreate ]; then ! grep -q 'exec -T app' "$MOCK_HOME/commands"; fi
  if [ "$scenario" = static-copy ]; then
   [ "$(cat "$MOCK_HOME/dist/index.html")" = old-index ]
   ! grep -q 'force-recreate' "$MOCK_HOME/commands"
  fi
 fi
 echo "PASS: publisher-$scenario"
done
