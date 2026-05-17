package io.github.sever0x.holypunch.client.ice;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Simplified ICE connectivity checker.
 *
 * Both peers simultaneously probe each other's candidates. The first candidate
 * pair from which a PROBE packet arrives is elected as the P2P path.
 *
 * Protocol:
 *   Both sides send PROBE_MAGIC to every remote candidate every 100 ms.
 *   On receiving PROBE_MAGIC, the side responds with PROBE_MAGIC once (for
 *   the sender that probed last-mile NAT state) and records the winner.
 *
 * This handles endpoint-independent mapping (most home NATs) and LAN peers.
 * Symmetric NAT (CGNAT, strict corporate) will time out → relay fallback.
 */
public final class ConnectionEstablisher {

    /** 9-byte probe marker; chosen to be unlikely to collide with application data. */
    static final byte[] PROBE_MAGIC = "HLYPNCH\r\n".getBytes();

    private static final int  PROBE_INTERVAL_MS = 100;
    private static final int  RECV_BUF_SIZE     = 1500;

    private ConnectionEstablisher() {}

    /**
     * Attempts to establish a direct UDP path between local and remote candidates.
     *
     * @param channel          the local DatagramChannel (unconnected, bound)
     * @param localCandidates  gathered by IceAgent
     * @param remoteCandidates received from peer via signaling
     * @param timeoutMs        how long to try before giving up
     * @return the channel connected to the winning remote address,
     *         or null if no path was found within the timeout
     */
    public static DatagramChannel tryConnect(
            DatagramChannel channel,
            List<IceCandidate> localCandidates,
            List<IceCandidate> remoteCandidates,
            long timeoutMs) throws IOException, InterruptedException {

        // Sort remote candidates by priority (host first, then srflx, then relay)
        List<IceCandidate> sorted = new ArrayList<>(remoteCandidates);
        sorted.sort((a, b) -> Integer.compare(b.priority(), a.priority()));

        AtomicReference<InetSocketAddress> winner = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        // ── Receiver thread ───────────────────────────────────────────────────
        Thread receiver = Thread.ofVirtual().name("ice-recv").start(() -> {
            ByteBuffer buf = ByteBuffer.allocate(RECV_BUF_SIZE);
            while (!Thread.interrupted()) {
                try {
                    buf.clear();
                    InetSocketAddress from = (InetSocketAddress) channel.receive(buf);
                    if (from == null) { Thread.sleep(5); continue; }
                    buf.flip();
                    if (!isProbe(buf)) continue;

                    // Respond once so the other side also gets confirmation
                    channel.send(ByteBuffer.wrap(PROBE_MAGIC), from);

                    if (winner.compareAndSet(null, from)) {
                        latch.countDown();
                    }
                } catch (InterruptedException | IOException e) {
                    break;
                }
            }
        });

        // ── Sender loop ───────────────────────────────────────────────────────
        long deadline = System.currentTimeMillis() + timeoutMs;
        try {
            while (System.currentTimeMillis() < deadline && winner.get() == null) {
                for (IceCandidate remote : sorted) {
                    try {
                        channel.send(ByteBuffer.wrap(PROBE_MAGIC),
                                remote.toSocketAddress());
                    } catch (IOException ignored) {}
                }
                latch.await(PROBE_INTERVAL_MS, TimeUnit.MILLISECONDS);
            }
        } finally {
            receiver.interrupt();
        }

        InetSocketAddress winnerAddr = winner.get();
        if (winnerAddr == null) return null;

        // Connect the channel so future sends/receives are bound to this path
        channel.configureBlocking(true);
        channel.connect(winnerAddr);
        return channel;
    }

    private static boolean isProbe(ByteBuffer buf) {
        if (buf.remaining() < PROBE_MAGIC.length) return false;
        for (byte b : PROBE_MAGIC) {
            if (buf.get() != b) return false;
        }
        return true;
    }
}
