#!/bin/bash
# test-loss.sh — Transfers a file while simulating configurable packet loss.
#
# Tries tc-netem first (requires sch_netem kernel module). If unavailable
# (common on Docker Desktop / WSL2), falls back to iptables statistic mode.
#
# Usage: ./docker/test-loss.sh [LOSS_PERCENT]   (default: 20)

set -e
cd "$(dirname "$0")/.."
LOSS=${1:-20}

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

# Injects packet loss inside a container. Tries netem, falls back to iptables.
# Args: LOSS_PERCENT  (e.g. 20)
inject_loss_cmd() {
  local pct=$1
  # awk computes the probability as a float (e.g. 20 → 0.20) without needing bc.
  cat <<EOF
apk add -q iproute2 iptables 2>/dev/null
if tc qdisc add dev eth0 root netem loss ${pct}% 2>/dev/null; then
  echo "tc-netem: ${pct}% packet loss active"
else
  PROB=\$(awk 'BEGIN {printf "%.2f", ${pct}/100}')
  if iptables -A OUTPUT -p udp -m statistic --mode random --probability \$PROB -j DROP 2>/dev/null; then
    echo "iptables-statistic: ~${pct}% UDP loss active"
  else
    echo "WARNING: packet loss injection failed (netem and iptables both unavailable)"
  fi
fi
EOF
}

echo "=== Generating test data ==="
mkdir -p testdata output
dd if=/dev/urandom of=testdata/arq-test.bin bs=1M count=30 2>/dev/null

build_images

echo "=== Starting server + sender ==="
docker compose -f docker/compose.yml -f docker/compose.relay.yml up -d hp-server hp-sender

echo "=== Injecting ${LOSS}% packet loss on sender ==="
sleep 5
docker compose -f docker/compose.yml -f docker/compose.relay.yml exec hp-sender \
  sh -c "$(inject_loss_cmd "$LOSS")"

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

echo "=== Starting receiver with ${LOSS}% packet loss ==="
MSYS_NO_PATHCONV=1 docker compose -f docker/compose.yml -f docker/compose.relay.yml run --rm \
  --entrypoint sh \
  --cap-add NET_ADMIN \
  -e HOLYPUNCH_SERVER=ws://hp-server:8080/signal \
  -v "$(pwd -W)/output:/output" \
  hp-sender -c "
    $(inject_loss_cmd "$LOSS")
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
