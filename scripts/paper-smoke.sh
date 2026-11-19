#!/usr/bin/env bash
# Integration smoke: start a real Paper server with the shaded CloverChat JAR,
# verify plugin lifecycle and Redis connectivity, then stop cleanly.
set -Eeuo pipefail

if (( $# == 0 )); then
  echo "Usage: bash scripts/paper-smoke.sh <paper-version> [<paper-version> ...]" >&2
  exit 2
fi

PLUGIN_VERSION="$(sed -n 's/^pluginVersion=//p' gradle.properties)"
PLUGIN_JAR="$(pwd)/build/libs/CloverChat.jar"
test -s "$PLUGIN_JAR"

for PAPER_VERSION in "$@"; do
  echo "=== CloverChat Paper ${PAPER_VERSION} smoke test ==="
  SMOKE_DIR="${RUNNER_TEMP:-${TMPDIR:-/tmp}}/cloverchat-paper-${PAPER_VERSION}"
  mkdir -p "$SMOKE_DIR/plugins/CloverChat"

  PAPER_META="$(curl -fsSL --retry 3 \
    -H "User-Agent: CloverChat-CI/${PLUGIN_VERSION} (slyphmp4@users.noreply.github.com)" \
    "https://fill.papermc.io/v3/projects/paper/versions/${PAPER_VERSION}/builds")"
  PAPER_URL="$(jq -r '[.[] | select(.channel == "STABLE")][0].downloads["server:default"].url // empty' <<< "$PAPER_META")"
  PAPER_SHA256="$(jq -r '[.[] | select(.channel == "STABLE")][0].downloads["server:default"].checksums.sha256 // empty' <<< "$PAPER_META")"
  test -n "$PAPER_URL"
  test -n "$PAPER_SHA256"
  test "$PAPER_SHA256" != "null"

  curl -fsSL --retry 3 \
    -H "User-Agent: CloverChat-CI/${PLUGIN_VERSION} (slyphmp4@users.noreply.github.com)" \
    "$PAPER_URL" -o "$SMOKE_DIR/paper.jar"
  printf '%s  %s\n' "$PAPER_SHA256" "$SMOKE_DIR/paper.jar" | sha256sum --check --strict

  cp "$PLUGIN_JAR" "$SMOKE_DIR/plugins/CloverChat.jar"
  cat > "$SMOKE_DIR/plugins/CloverChat/config.yml" <<'EOF'
language: en
proxy-sync:
  enabled: true
  transport: REDIS
  server-id: ci-paper
  shared-secret: ci-only-secret-012345678901234567890123456789
  redis:
    mode: STANDALONE
    channel: cloverchat:test:paper
    username: clover
    password: test-only-password
    standalone:
      host: 127.0.0.1
      port: 16379
EOF
  printf 'eula=true\n' > "$SMOKE_DIR/eula.txt"
  printf '%s\n' \
    'online-mode=false' \
    'server-port=0' \
    'view-distance=2' \
    'simulation-distance=2' \
    > "$SMOKE_DIR/server.properties"

  (
    cd "$SMOKE_DIR"
    coproc SERVER { java -Xms512M -Xmx1G -jar paper.jar --nogui > server.log 2>&1; }
    SERVER_PROCESS=$SERVER_PID
    READY=false
    for _ in $(seq 1 120); do
      if grep -Fq 'Done (' server.log; then
        READY=true
        break
      fi
      if ! kill -0 "$SERVER_PROCESS" 2>/dev/null; then
        break
      fi
      sleep 1
    done
    if [ "$READY" != true ]; then
      cat server.log
      kill "$SERVER_PROCESS" 2>/dev/null || true
      exit 1
    fi

    grep -Fq "[CloverChat] Enabling CloverChat v$PLUGIN_VERSION" server.log
    for _ in $(seq 1 20); do
      if grep -Fq '[Redis] Connected: STANDALONE' server.log; then
        break
      fi
      sleep 1
    done
    if ! grep -Fq '[Redis] Connected: STANDALONE' server.log; then
      cat server.log
      printf 'stop\n' >&"${SERVER[1]}" || true
      wait "$SERVER_PROCESS" || true
      exit 1
    fi

    printf 'stop\n' >&"${SERVER[1]}"
    wait "$SERVER_PROCESS"
    grep -Fq "[CloverChat] Disabling CloverChat v$PLUGIN_VERSION" server.log
    if grep -Eiq 'Error occurred while (loading|enabling) CloverChat|Could not load.*CloverChat|NoClassDefFoundError|NoSuchMethodError|AbstractMethodError|UnsupportedClassVersionError|LinkageError' server.log; then
      cat server.log
      exit 1
    fi
    grep -E '\[CloverChat\] (Enabling|Disabling)|\[Redis\] Connected|Done \(' server.log
  )
done
