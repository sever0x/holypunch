package io.github.sever0x.holypunch.client.net;

import java.io.IOException;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.concurrent.BlockingDeque;

/**
 * Transport implementation that relays all data through the signaling server.
 *
 * Created by SignalingClient.switchToRelay() after the server sends RELAY_READY.
 * The WebSocket and the shared message queue are already live — this class just
 * adds the Transport interface on top of them.
 *
 * Binary frames for chunk data and text frames for JSON control messages are
 * both forwarded transparently by the server's RelayForwarder.
 */
public class RelayTransport implements Transport {

    private final WebSocket ws;
    private final BlockingDeque<Message> queue;
    private volatile boolean open = true;

    RelayTransport(WebSocket ws, BlockingDeque<Message> queue) {
        this.ws = ws;
        this.queue = queue;
    }

    @Override
    public void sendBinary(byte[] data) throws IOException {
        ensureOpen();
        try {
            ws.sendBinary(ByteBuffer.wrap(data), true).join();
        } catch (Exception e) {
            throw new IOException("sendBinary failed", e);
        }
    }

    @Override
    public void sendText(String json) throws IOException {
        ensureOpen();
        try {
            ws.sendText(json, true).join();
        } catch (Exception e) {
            throw new IOException("sendText failed", e);
        }
    }

    /**
     * Blocks until the next message arrives from the peer (via server relay).
     * Returns null on clean close (PEER_DISCONNECTED sentinel injected by SignalingClient).
     */
    @Override
    public Message receive() throws IOException, InterruptedException {
        Message msg = queue.take();
        // Sentinel: server or SignalingClient signals peer has gone
        if (!msg.binary() && "{\"type\":\"PEER_DISCONNECTED\"}".equals(msg.text())) {
            open = false;
            return null;
        }
        return msg;
    }

    @Override
    public boolean isOpen() {
        return open && !ws.isOutputClosed();
    }

    @Override
    public void close() {
        open = false;
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "");
    }

    private void ensureOpen() throws IOException {
        if (!isOpen()) throw new IOException("Transport is closed");
    }
}
