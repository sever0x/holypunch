#!/bin/bash
# test-relay.sh — Forces relay mode by blocking UDP outbound on the sender
# container right before the ICE probing phase starts.
#
# The signaling WebSocket runs over TCP, so blocking UDP does not affect it.
# ICE probing will get no responses → 15 s timeout → RELAY_REQUEST → relay path.
#
# Requires: containers built with --cap-add NET_ADMIN (see compose.relay.yml)
#
# Usage: ./docker/test-relay.sh

set -e
cd "$(dirname "$0")/.."

build_images() {
  if [ "${REBUILD:-0}" = "1" ] || \
     ! docker image inspect holypunch-server:local >/dev/null 2>&1 || \
     ! docker image inspect holypunch-client:local >/dev/null 2>&1; then
    echo "=== Building Docker images ==="
    docker compose -f docker/compose.yml build
  else
    echo "=== Images already built (REBUILD=1 to force) ==="
  fi
}

echo "=== Generating test data ==="
mkdir -p testdata output
dd if=/dev/urandom of=testdata/relay-test.bin bs=1M count=20 2>/dev/null

build_images

echo "=== Starting server + sender ==="
docker compose -f docker/compose.yml -f docker/compose.relay.yml up -d hp-server hp-sender

echo "=== Blocking UDP on sender (forces relay fallback) ==="
# Wait for sender to be up, then block UDP before ICE probing starts.
# The sender connects to signaling first (TCP), then waits for receiver → safe window.
sleep 5
docker compose -f docker/compose.yml -f docker/compose.relay.yml exec hp-sender \
  sh -c "apk add -q iptables 2>/dev/null; iptables -A OUTPUT -p udp -j DROP"
echo "UDP blocked on sender."

echo "=== Waiting for code ==="
CODE=""
for i in $(seq 1 30); do
  CODE=$(docker compose -f docker/compose.yml -f docker/compose.relay.yml logs hp-sender 2>/dev/null \
    | grep -oE '[a-z]+-[a-z]+-[a-z]+-[a-z]+' | head -1)
  [ -n "$CODE" ] && break
  sleep 2
done

[ -z "$CODE" ] && { echo "ERROR: no code"; docker compose -f docker/compose.yml -f docker/compose.relay.yml down; exit 1; }
echo "=== Code: $CODE ==="

echo "=== Starting receiver (also UDP-blocked → relay on both sides) ==="
MSYS_NO_PATHCONV=1 docker compose -f docker/compose.yml -f docker/compose.relay.yml run --rm \
  --entrypoint sh \
  --cap-add NET_ADMIN \
  -e HOLYPUNCH_SERVER=ws://hp-server:8080/signal \
  -v "$(pwd -W)/output:/output" \
  hp-sender -c "
    apk add -q iptables 2>/dev/null
    iptables -A OUTPUT -p udp -j DROP
    java -jar app.jar receive $CODE /output
  "

echo "=== Verifying ==="
SENT=$(find testdata -type f | wc -l)
RECV=$(find output   -type f | wc -l)
[ "$SENT" -eq "$RECV" ] && echo "PASS: file count matches" || { echo "FAIL"; exit 1; }
(cd testdata && find . -type f -exec sha256sum {} \; | sort) > /tmp/sent.sha
(cd output   && find . -type f -exec sha256sum {} \; | sort) > /tmp/recv.sha
diff /tmp/sent.sha /tmp/recv.sha \
  && echo "PASS: checksums match" || { echo "FAIL: checksum mismatch"; exit 1; }

docker compose -f docker/compose.yml -f docker/compose.relay.yml down
rm -rf testdata output

echo ""
echo "ALL TESTS PASSED (relay mode)"
