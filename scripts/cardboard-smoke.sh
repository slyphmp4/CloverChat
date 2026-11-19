#!/usr/bin/env bash
# Disposable Fabric 26.3 + Cardboard integration startup check.
set -Eeuo pipefail

MC_VERSION="26.3"
FABRIC_LOADER="0.20.1"
FABRIC_INSTALLER="1.1.2"
FABRIC_API_VERSION="0.166.2+26.3"
CARDBOARD_VERSION="26.3.0"
ROOT="${RUNNER_TEMP:-${TMPDIR:-/tmp}}/cloverchat-fabric-cardboard-26.3"
PLUGIN_VERSION="$(sed -n 's/^pluginVersion=//p' gradle.properties)"
PLUGIN_JAR="$(pwd)/build/libs/CloverChat.jar"

test -s "$PLUGIN_JAR"
mkdir -p "$ROOT/mods" "$ROOT/plugins/CloverChat"

curl -fsSL --retry 3 \
  "https://meta.fabricmc.net/v2/versions/loader/$MC_VERSION/$FABRIC_LOADER/$FABRIC_INSTALLER/server/jar" \
  -o "$ROOT/fabric-server.jar"
jar tf "$ROOT/fabric-server.jar" >/dev/null

curl -fsSL --retry 3 \
  "https://github.com/slyphmp4/Cardboard/releases/download/v$CARDBOARD_VERSION/Cardboard-$CARDBOARD_VERSION.jar" \
  -o "$ROOT/mods/Cardboard-$CARDBOARD_VERSION.jar"
curl -fsSL --retry 3 \
  "https://github.com/slyphmp4/Cardboard/releases/download/v$CARDBOARD_VERSION/SHA256SUMS.txt" \
  -o "$ROOT/mods/SHA256SUMS.txt"
(cd "$ROOT/mods" && sha256sum --check --strict SHA256SUMS.txt)

FABRIC_API_META="$(curl -fsSL --retry 3 \
  -H 'User-Agent: CloverChat-CI/1.0 (slyphmp4@users.noreply.github.com)' \
  "https://api.modrinth.com/v2/project/P7dR8mSH/version?game_versions=%5B%22$MC_VERSION%22%5D&loaders=%5B%22fabric%22%5D")"
FABRIC_API_URL="$(jq -r --arg v "$FABRIC_API_VERSION" '[.[] | select(.version_number == $v) | .files[] | select(.primary == true)][0].url // empty' <<< "$FABRIC_API_META")"
FABRIC_API_SHA512="$(jq -r --arg v "$FABRIC_API_VERSION" '[.[] | select(.version_number == $v) | .files[] | select(.primary == true)][0].hashes.sha512 // empty' <<< "$FABRIC_API_META")"
test -n "$FABRIC_API_URL"
test -n "$FABRIC_API_SHA512"
test "$FABRIC_API_SHA512" != "null"
curl -fsSL --retry 3 "$FABRIC_API_URL" -o "$ROOT/mods/fabric-api.jar"
printf '%s  %s\n' "$FABRIC_API_SHA512" "$ROOT/mods/fabric-api.jar" | sha512sum --check --strict

cp "$PLUGIN_JAR" "$ROOT/plugins/CloverChat.jar"
cat > "$ROOT/plugins/CloverChat/config.yml" <<'EOF'
language: en
proxy-sync:
  enabled: true
  transport: REDIS
  server-id: ci-cardboard
  shared-secret: ci-only-secret-012345678901234567890123456789
  redis:
    mode: STANDALONE
    channel: cloverchat:test:cardboard
    username: clover
    password: test-only-password
    standalone:
      host: 127.0.0.1
      port: 16379
EOF
printf 'eula=true\n' > "$ROOT/eula.txt"
printf '%s\n' \
  'online-mode=false' \
  'server-ip=127.0.0.1' \
  'server-port=0' \
  'view-distance=2' \
  'simulation-distance=2' \
  > "$ROOT/server.properties"

PIPE="$ROOT/console-in"
rm -f "$PIPE"
mkfifo "$PIPE"
(cd "$ROOT" && java -Xms512M -Xmx2G -jar fabric-server.jar nogui < "$PIPE" > server.log 2>&1) &
SERVER_PID=$!
exec 3>"$PIPE"

cleanup() {
  if kill -0 "$SERVER_PID" 2>/dev/null; then
    printf 'stop\n' >&3 || true
    sleep 2
    kill "$SERVER_PID" 2>/dev/null || true
    wait "$SERVER_PID" 2>/dev/null || true
  fi
  exec 3>&- || true
  rm -f "$PIPE"
}
trap cleanup EXIT

READY=false
for _ in $(seq 1 240); do
  if grep -Eiq 'Failed to run bootstrapper|Error occurred while (loading|enabling)|Mixin apply failed|NoClassDefFoundError|UnsupportedClassVersionError' "$ROOT/server.log"; then
    break
  fi
  if grep -Fq 'Done (' "$ROOT/server.log"; then
    READY=true
    break
  fi
  if ! kill -0 "$SERVER_PID" 2>/dev/null; then
    break
  fi
  sleep 1
done

if [ "$READY" != true ]; then
  echo "Fabric/Cardboard 26.3 failed to become ready" >&2
  tail -n 220 "$ROOT/server.log" || true
  exit 1
fi
grep -Fq "[CloverChat] Enabling CloverChat v$PLUGIN_VERSION" "$ROOT/server.log"
for _ in $(seq 1 30); do
  if grep -Fq '[Redis] Connected: STANDALONE' "$ROOT/server.log"; then
    break
  fi
  sleep 1
done
if ! grep -Fq '[Redis] Connected: STANDALONE' "$ROOT/server.log"; then
  tail -n 220 "$ROOT/server.log"
  exit 1
fi
printf 'stop\n' >&3
wait "$SERVER_PID"
grep -Fq "[CloverChat] Disabling CloverChat v$PLUGIN_VERSION" "$ROOT/server.log"
if grep -Eiq 'Error occurred while (loading|enabling) CloverChat|Could not load.*CloverChat|NoClassDefFoundError|NoSuchMethodError|AbstractMethodError|UnsupportedClassVersionError|LinkageError' "$ROOT/server.log"; then
  tail -n 220 "$ROOT/server.log"
  exit 1
fi
grep -E 'Cardboard|\[CloverChat\] (Enabling|Disabling)|\[Redis\] Connected|Done \(' "$ROOT/server.log" | tail -n 45
trap - EXIT
exec 3>&-
rm -f "$PIPE"
