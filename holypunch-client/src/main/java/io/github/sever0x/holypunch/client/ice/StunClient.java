package io.github.sever0x.holypunch.client.ice;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.security.SecureRandom;

/**
 * Minimal STUN Binding Request (RFC 5389) to discover the server-reflexive address.
 *
 * Sends one STUN Binding Request to the given STUN server and parses the
 * XOR-MAPPED-ADDRESS (or MAPPED-ADDRESS) from the response.
 *
 * The channel is temporarily configured to non-blocking for the query duration
 * and restored afterwards.
 */
public final class StunClient {

    private static final int  MAGIC_COOKIE      = 0x2112A442;
    private static final int  BINDING_REQUEST   = 0x0001;
    private static final int  BINDING_SUCCESS   = 0x0101;
    private static final int  XOR_MAPPED_ADDR   = 0x0020;
    private static final int  MAPPED_ADDR       = 0x0001;
    private static final long TIMEOUT_MS        = 3_000;

    private StunClient() {}

    /**
     * Queries the STUN server using the given DatagramChannel.
     * Returns the server-reflexive address, or null on timeout / failure.
     *
     * The channel must be unconnected and bound to a local port.
     */
    public static InetSocketAddress query(DatagramChannel channel,
                                          InetSocketAddress stunServer) {
        boolean wasBlocking = channel.isBlocking();
        try {
            channel.configureBlocking(false);

            byte[] txId = new byte[12];
            new SecureRandom().nextBytes(txId);

            ByteBuffer req = ByteBuffer.allocate(20);
            req.putShort((short) BINDING_REQUEST);
            req.putShort((short) 0);
            req.putInt(MAGIC_COOKIE);
            req.put(txId);
            req.flip();
            channel.send(req, stunServer);

            ByteBuffer resp = ByteBuffer.allocate(1500);
            try (Selector sel = Selector.open()) {
                channel.register(sel, SelectionKey.OP_READ);
                long deadline = System.currentTimeMillis() + TIMEOUT_MS;

                while (System.currentTimeMillis() < deadline) {
                    long wait = deadline - System.currentTimeMillis();
                    if (sel.select(Math.max(1, wait)) == 0) break;
                    sel.selectedKeys().clear();

                    resp.clear();
                    if (channel.receive(resp) == null) continue;
                    resp.flip();

                    InetSocketAddress result = parseResponse(resp);
                    if (result != null) return result;
                }
            }
        } catch (IOException e) {
            // Fall through, return null
        } finally {
            try { channel.configureBlocking(wasBlocking); } catch (IOException ignored) {}
        }
        return null;
    }

    private static InetSocketAddress parseResponse(ByteBuffer buf) {
        if (buf.remaining() < 20) return null;
        int type   = buf.getShort() & 0xFFFF;
        /* length */ buf.getShort();
        int cookie = buf.getInt();
        if (type != BINDING_SUCCESS || cookie != MAGIC_COOKIE) return null;
        buf.position(buf.position() + 12); // skip transaction ID

        while (buf.remaining() >= 4) {
            int attrType = buf.getShort() & 0xFFFF;
            int attrLen  = buf.getShort() & 0xFFFF;

            if ((attrType == XOR_MAPPED_ADDR || attrType == MAPPED_ADDR) && attrLen == 8) {
                buf.get(); // reserved
                int family = buf.get() & 0xFF;
                if (family != 0x01) { skip(buf, 6); continue; } // not IPv4

                boolean xor = (attrType == XOR_MAPPED_ADDR);
                int rawPort = buf.getShort() & 0xFFFF;
                int rawIp   = buf.getInt();
                int port    = xor ? (rawPort ^ (MAGIC_COOKIE >>> 16)) : rawPort;
                int ip      = xor ? (rawIp   ^ MAGIC_COOKIE)          : rawIp;

                try {
                    return new InetSocketAddress(
                            InetAddress.getByAddress(new byte[]{
                                    (byte)(ip >> 24), (byte)(ip >> 16),
                                    (byte)(ip >> 8),  (byte) ip}),
                            port);
                } catch (Exception e) { return null; }
            } else {
                int padded = attrLen + (4 - attrLen % 4) % 4;
                skip(buf, padded);
            }
        }
        return null;
    }

    private static void skip(ByteBuffer buf, int n) {
        buf.position(Math.min(buf.limit(), buf.position() + n));
    }
}
