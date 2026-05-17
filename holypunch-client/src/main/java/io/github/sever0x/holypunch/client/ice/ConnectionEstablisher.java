package io.github.sever0x.holypunch.client.ice;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Simplified ICE connectivity checker.
 *
 * Both peers simultaneously probe each other's candidates with PROBE_MAGIC.
 * The first candidate pair from which a probe arrives is elected as the P2P path.
 *
 * IMPORTANT: the receiver thread uses non-blocking receive + AtomicBoolean stop flag.
 * Thread.interrupt() MUST NOT be used on a thread blocked in DatagramChannel.receive()
 * because Java NIO closes the channel on ClosedByInterruptException, which would
 * corrupt the channel before we can call channel.connect(winner).
 */
public final class ConnectionEstablisher {

    static final byte[] PROBE_MAGIC = "HLYPNCH\r\n".getBytes();

    private static final int  PROBE_INTERVAL_MS = 100;
    private static final int  RECV_BUF_SIZE     = 1500;
    private static final long RECV_POLL_MS      = 5;

    private ConnectionEstablisher() {}

    public static DatagramChannel tryConnect(
            DatagramChannel channel,
            List<IceCandidate> localCandidates,
            List<IceCandidate> remoteCandidates,
            long timeoutMs) throws IOException, InterruptedException {

        List<IceCandidate> sorted = new ArrayList<>(remoteCandidates);
        sorted.sort((a, b) -> Integer.compare(b.priority(), a.priority()));

        AtomicReference<InetSocketAddress> winner = new AtomicReference<>();
        CountDownLatch latch  = new CountDownLatch(1);
        AtomicBoolean  stop   = new AtomicBoolean(false);

        // Switch to non-blocking so the receiver thread never calls blocking receive,
        // which would make Thread.interrupt() close the channel via ClosedByInterruptException.
        channel.configureBlocking(false);

        Thread receiver = Thread.ofVirtual().name("ice-recv").start(() -> {
            ByteBuffer buf = ByteBuffer.allocate(RECV_BUF_SIZE);
            while (!stop.get()) {
                try {
                    buf.clear();
                    InetSocketAddress from = (InetSocketAddress) channel.receive(buf);
                    if (from == null) {
                        Thread.sleep(RECV_POLL_MS);
                        continue;
                    }
                    buf.flip();
                    if (!isProbe(buf)) continue;

                    // Send response so the other side also confirms connectivity
                    channel.send(ByteBuffer.wrap(PROBE_MAGIC), from);

                    if (winner.compareAndSet(null, from)) {
                        latch.countDown();
                    }
                } catch (InterruptedException e) {
                    break;
                } catch (IOException e) {
                    break;
                }
            }
        });

        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline && winner.get() == null) {
            for (IceCandidate remote : sorted) {
                try {
                    channel.send(ByteBuffer.wrap(PROBE_MAGIC), remote.toSocketAddress());
                } catch (IOException ignored) {}
            }
            latch.await(PROBE_INTERVAL_MS, TimeUnit.MILLISECONDS);
        }

        // Signal receiver thread to stop cleanly — no interrupt, channel stays open
        stop.set(true);

        InetSocketAddress winnerAddr = winner.get();
        if (winnerAddr == null) {
            channel.configureBlocking(true);
            return null;
        }

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
