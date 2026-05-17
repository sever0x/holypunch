#!/bin/bash
# test-loss.sh — Transfers a file while simulating configurable packet loss on
# the sender's network interface using Linux tc-netem.
#
# This stress-tests the sliding-window ARQ in ReliableUdpChannel: with 20% loss
# the retransmit loop should recover every dropped packet and the transfer must
# still complete with matching checksums.
#
# Usage: ./docker/test-loss.sh [LOSS_PERCENT]   (default: 20)

set -e
cd "$(dirname "$0")/.."
LOSS=${1:-20}

echo "=== Generating test data ==="
mkdir -p testdata output
dd if=/dev/urandom of=testdata/arq-test.bin bs=1M count=30 2>/dev/null

echo "=== Building images ==="
docker compose -f docker/compose.yml -f docker/compose.relay.yml build

echo "=== Starting server + sender ==="
docker compose -f docker/compose.yml -f docker/compose.relay.yml up -d hp-server hp-sender

echo "=== Injecting ${LOSS}% packet loss on sender eth0 ==="
sleep 5
docker compose -f docker/compose.yml -f docker/compose.relay.yml exec hp-sender \
  sh -c "apk add -q iproute2 2>/dev/null; tc qdisc add dev eth0 root netem loss ${LOSS}%"
echo "Packet loss active."

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

echo "=== Starting receiver ==="
MSYS_NO_PATHCONV=1 docker compose -f docker/compose.yml -f docker/compose.relay.yml run --rm \
  --cap-add NET_ADMIN \
  -e HOLYPUNCH_SERVER=ws://hp-server:8080/signal \
  -v "$(pwd -W)/output:/output" \
  hp-sender sh -c "
    apk add -q iproute2 2>/dev/null
    tc qdisc add dev eth0 root netem loss ${LOSS}%
    java -jar app.jar receive $CODE /output
  "

echo "=== Verifying ==="
(cd testdata && find . -type f -exec sha256sum {} \; | sort) > /tmp/sent.sha
(cd output   && find . -type f -exec sha256sum {} \; | sort) > /tmp/recv.sha
diff /tmp/sent.sha /tmp/recv.sha \
  && echo "PASS: all checksums match under ${LOSS}% packet loss" \
  || { echo "FAIL"; exit 1; }

docker compose -f docker/compose.yml -f docker/compose.relay.yml down
rm -rf testdata output

echo ""
echo "ALL TESTS PASSED (${LOSS}% packet loss)"
