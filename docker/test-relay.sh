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

echo "=== Building JAR ==="
./mvnw package -DskipTests -q -pl holypunch-client,holypunch-server

echo "=== Generating test data ==="
mkdir -p testdata output
dd if=/dev/urandom of=testdata/relay-test.bin bs=1M count=20 2>/dev/null

echo "=== Building images ==="
docker compose -f docker/compose.yml -f docker/compose.relay.yml build

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
docker compose -f docker/compose.yml -f docker/compose.relay.yml run --rm \
  --cap-add NET_ADMIN \
  -e HOLYPUNCH_SERVER=ws://hp-server:8080/signal \
  -v "$(pwd)/output:/output" \
  hp-sender sh -c "
    apk add -q iptables 2>/dev/null
    iptables -A OUTPUT -p udp -j DROP
    java -jar app.jar receive $CODE /output
  "

echo "=== Verifying ==="
SENT=$(find testdata -type f | wc -l)
RECV=$(find output   -type f | wc -l)
[ "$SENT" -eq "$RECV" ] && echo "PASS: file count matches" || { echo "FAIL"; exit 1; }
diff <(cd testdata && find . -type f -exec sha256sum {} \; | sort) \
     <(cd output   && find . -type f -exec sha256sum {} \; | sort) \
  && echo "PASS: checksums match" || { echo "FAIL: checksum mismatch"; exit 1; }

docker compose -f docker/compose.yml -f docker/compose.relay.yml down
rm -rf testdata output

echo ""
echo "ALL TESTS PASSED (relay mode)"
