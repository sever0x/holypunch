#!/bin/bash
# test-p2p.sh — Direct P2P test between two containers on the same Docker bridge.
#
# Both containers land on 172.x.x.x addresses that are directly reachable,
# so ICE local candidates succeed and the transfer goes over UDP — same as two
# machines on a LAN.
#
# Usage: ./docker/test-p2p.sh

set -e
cd "$(dirname "$0")/.."

# Build images only if they don't exist yet. Pass REBUILD=1 to force a rebuild.
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

echo "=== Generating test data (60 MB total) ==="
mkdir -p testdata output
dd if=/dev/urandom of=testdata/small.bin  bs=1M count=5  2>/dev/null
dd if=/dev/urandom of=testdata/medium.bin bs=1M count=50 2>/dev/null
mkdir -p testdata/sub
dd if=/dev/urandom of=testdata/sub/nested.bin bs=1M count=5 2>/dev/null

build_images

echo "=== Starting server and sender ==="
docker compose -f docker/compose.yml up -d hp-server hp-sender

echo "=== Waiting for code from sender (up to 60 s) ==="
CODE=""
for i in $(seq 1 30); do
  CODE=$(docker compose -f docker/compose.yml logs hp-sender 2>/dev/null \
    | grep -oE '[a-z]+-[a-z]+-[a-z]+-[a-z]+' | head -1)
  [ -n "$CODE" ] && break
  sleep 2
done

if [ -z "$CODE" ]; then
  echo "ERROR: sender did not emit a code. Logs:"
  docker compose -f docker/compose.yml logs hp-sender
  docker compose -f docker/compose.yml down
  exit 1
fi

echo "=== Code: $CODE ==="

echo "=== Starting receiver ==="
# MSYS_NO_PATHCONV=1 prevents Git Bash from converting the container path /output
# to a Windows path (C:/Program Files/Git/output) when passed as a CLI argument.
MSYS_NO_PATHCONV=1 docker compose -f docker/compose.yml run --rm \
  -e HOLYPUNCH_SERVER=ws://hp-server:8080/signal \
  -v "$(pwd -W)/output:/output" \
  hp-sender receive "$CODE" /output

echo "=== Verifying ==="
SENT=$(find testdata -type f | wc -l)
RECV=$(find output   -type f | wc -l)
echo "Files sent: $SENT  |  Files received: $RECV"
[ "$SENT" -eq "$RECV" ] && echo "PASS: file count matches" || { echo "FAIL: count mismatch"; exit 1; }

echo "=== Comparing checksums ==="
(cd testdata && find . -type f -exec sha256sum {} \; | sort) > /tmp/sent.sha
(cd output   && find . -type f -exec sha256sum {} \; | sort) > /tmp/recv.sha
diff /tmp/sent.sha /tmp/recv.sha && echo "PASS: all checksums match" || { echo "FAIL: checksum mismatch"; exit 1; }

echo "=== Cleanup ==="
docker compose -f docker/compose.yml down
rm -rf testdata output

echo ""
echo "ALL TESTS PASSED (P2P mode)"
