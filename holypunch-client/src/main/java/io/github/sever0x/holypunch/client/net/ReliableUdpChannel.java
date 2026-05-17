package io.github.sever0x.holypunch.client.net;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Reliable, ordered message delivery over a connected UDP DatagramChannel.
 *
 * ── Wire format ──────────────────────────────────────────────────────────────
 *
 * DATA packet:
 *   [1]  type     = 0x00
 *   [4]  seqnum   (global, monotonic)
 *   [4]  msg_id   (per-message counter)
 *   [4]  frag_idx (0-based fragment index within the message)
 *   [4]  frag_tot (total fragments in this message)
 *   [n]  payload  (1 ≤ n ≤ MTU)
 *   Total header: 17 bytes
 *
 * ACK packet:
 *   [1]  type     = 0x01
 *   [4]  seqnum   being ACKed
 *   Total: 5 bytes
 *
 * ── Reliability ──────────────────────────────────────────────────────────────
 * Sliding window (WINDOW_SIZE = 2048 unACKed packets).
 * Background thread retransmits packets older than RTO_MS.
 * Each received fragment is individually ACKed.
 *
 * ── Ordering ─────────────────────────────────────────────────────────────────
 * Fragments are buffered per message and reassembled when all arrive.
 * Complete messages are delivered in msg_id order.
 */
public class ReliableUdpChannel implements Closeable {

    // Wire constants
    private static final byte TYPE_DATA = 0x00;
    private static final byte TYPE_ACK  = 0x01;
    static final int MTU         = 1280;  // payload bytes per datagram (safe internet MTU)
    static final int DATA_HEADER = 17;    // 1 + 4*4
    static final int WINDOW_SIZE = 2048;
    private static final long RTO_MS  = 250;

    private final DatagramChannel channel;
    private final AtomicBoolean   closed = new AtomicBoolean(false);

    // ── Send state ────────────────────────────────────────────────────────────
    private final AtomicInteger nextSeq   = new AtomicInteger(0);
    private final AtomicInteger nextMsgId = new AtomicInteger(0);
    /** seqnum → raw packet bytes for unACKed fragments */
    private final ConcurrentSkipListMap<Integer, byte[]> unAcked   = new ConcurrentSkipListMap<>();
    /** seqnum → System.currentTimeMillis() of last send */
    private final ConcurrentSkipListMap<Integer, Long>   sendTimes = new ConcurrentSkipListMap<>();
    private final Semaphore windowSlots = new Semaphore(WINDOW_SIZE, true);

    // ── Receive state ─────────────────────────────────────────────────────────
    /** msg_id → reassembly buffer */
    private final ConcurrentSkipListMap<Integer, FragmentBuffer> recvBuffers = new ConcurrentSkipListMap<>();
    /** msg_id → complete message (waiting to be delivered in order) */
    private final ConcurrentSkipListMap<Integer, byte[]>         readyMsgs   = new ConcurrentSkipListMap<>();
    private final AtomicInteger nextDeliverMsgId = new AtomicInteger(0);
    private final BlockingQueue<byte[]> deliveryQueue = new LinkedBlockingQueue<>();

    // ── Background threads ────────────────────────────────────────────────────
    private final Thread readerThread;
    private final Thread retransmitThread;

    public ReliableUdpChannel(DatagramChannel channel) {
        this.channel = channel;
        readerThread      = Thread.ofVirtual().name("arq-read").start(this::readLoop);
        retransmitThread  = Thread.ofVirtual().name("arq-rtx").start(this::retransmitLoop);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Sends {@code data} reliably. Blocks until all fragments are ACKed. */
    public void send(byte[] data) throws IOException, InterruptedException {
        int fragTotal = data.length == 0 ? 1 : (data.length + MTU - 1) / MTU;
        int msgId     = nextMsgId.getAndIncrement();
        int lastSeq   = -1;

        for (int fi = 0; fi < fragTotal; fi++) {
            windowSlots.acquire();               // respect window; blocks if full
            int    offset   = fi * MTU;
            int    len      = Math.min(MTU, data.length - offset);
            int    seq      = nextSeq.getAndIncrement();
            byte[] pkt      = buildData(seq, msgId, fi, fragTotal, data, offset, len);
            unAcked.put(seq, pkt);
            sendTimes.put(seq, System.currentTimeMillis());
            sendRaw(pkt);
            lastSeq = seq;
        }

        // Wait until all fragments of this message are ACKed
        while (!closed.get() && unAcked.containsKey(lastSeq)) {
            Thread.sleep(5);
        }
        if (closed.get()) throw new IOException("Channel closed while sending");
    }

    /** Blocks until the next complete message is available. */
    public byte[] receive() throws InterruptedException {
        return deliveryQueue.take();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            readerThread.interrupt();
            retransmitThread.interrupt();
            try { channel.close(); } catch (IOException ignored) {}
        }
    }

    public boolean isClosed() { return closed.get(); }

    // ── Internals ─────────────────────────────────────────────────────────────

    private void readLoop() {
        ByteBuffer buf = ByteBuffer.allocateDirect(MTU + DATA_HEADER + 8);
        while (!closed.get()) {
            try {
                buf.clear();
                if (channel.receive(buf) == null) continue;
                buf.flip();
                if (buf.remaining() < 1) continue;

                byte type = buf.get();
                if (type == TYPE_DATA) onData(buf);
                else if (type == TYPE_ACK) onAck(buf);
            } catch (IOException e) {
                if (!closed.get()) break;
            }
        }
    }

    private void onAck(ByteBuffer buf) {
        if (buf.remaining() < 4) return;
        int seq = buf.getInt();
        if (unAcked.remove(seq) != null) {
            sendTimes.remove(seq);
            windowSlots.release();
        }
    }

    private void onData(ByteBuffer buf) throws IOException {
        if (buf.remaining() < 16) return;
        int seq      = buf.getInt();
        int msgId    = buf.getInt();
        int fragIdx  = buf.getInt();
        int fragTot  = buf.getInt();

        // ACK immediately
        sendAck(seq);

        FragmentBuffer fb = recvBuffers.computeIfAbsent(msgId, k -> new FragmentBuffer(fragTot));
        fb.put(fragIdx, buf);

        if (fb.isComplete()) {
            byte[] complete = fb.assemble();
            recvBuffers.remove(msgId);
            readyMsgs.put(msgId, complete);
            drainDelivery();
        }
    }

    private void drainDelivery() {
        while (true) {
            int    expected = nextDeliverMsgId.get();
            byte[] msg      = readyMsgs.remove(expected);
            if (msg == null) break;
            nextDeliverMsgId.incrementAndGet();
            deliveryQueue.offer(msg);
        }
    }

    private void retransmitLoop() {
        while (!closed.get()) {
            try {
                Thread.sleep(50);
                long now = System.currentTimeMillis();
                for (var e : unAcked.entrySet()) {
                    Long sent = sendTimes.get(e.getKey());
                    if (sent != null && now - sent > RTO_MS) {
                        sendRaw(e.getValue());
                        sendTimes.put(e.getKey(), now);
                    }
                }
            } catch (InterruptedException e) {
                break;
            } catch (IOException ignored) {}
        }
    }

    private void sendAck(int seq) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(5);
        buf.put(TYPE_ACK).putInt(seq).flip();
        channel.write(buf);   // connected channel → write() instead of send()
    }

    private void sendRaw(byte[] pkt) throws IOException {
        channel.write(ByteBuffer.wrap(pkt));
    }

    private static byte[] buildData(int seq, int msgId, int fi, int ft,
                                    byte[] data, int off, int len) {
        byte[] pkt = new byte[DATA_HEADER + len];
        ByteBuffer b = ByteBuffer.wrap(pkt);
        b.put(TYPE_DATA).putInt(seq).putInt(msgId).putInt(fi).putInt(ft);
        b.put(data, off, len);
        return pkt;
    }

    // ── Fragment reassembly ───────────────────────────────────────────────────

    private static class FragmentBuffer {
        private final byte[][] frags;
        private int received = 0;

        FragmentBuffer(int total) { frags = new byte[total][];}

        synchronized void put(int idx, ByteBuffer payload) {
            if (idx < 0 || idx >= frags.length || frags[idx] != null) return;
            byte[] bytes = new byte[payload.remaining()];
            payload.get(bytes);
            frags[idx] = bytes;
            received++;
        }

        boolean isComplete() { return received == frags.length; }

        byte[] assemble() {
            int total = Arrays.stream(frags).mapToInt(f -> f == null ? 0 : f.length).sum();
            byte[] out = new byte[total];
            int pos = 0;
            for (byte[] f : frags) {
                if (f != null) { System.arraycopy(f, 0, out, pos, f.length); pos += f.length; }
            }
            return out;
        }
    }
}
