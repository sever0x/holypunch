package io.github.sever0x.holypunch.client.net;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

/**
 * Client-side WebSocket connection to the signaling/relay server.
 *
 * Responsibilities:
 *   - send JOIN_SENDER / JOIN_RECEIVER
 *   - receive SESSION_CREATED, PEER_JOINED, ICE_CANDIDATES, RELAY_READY, errors
 *   - after RELAY_READY: hand off the connection to RelayTransport
 *
 * Uses java.net.http.WebSocket (GraalVM native-image compatible, no external deps).
 * HttpClient is kept as a field — closing it would block until WebSocket is closed.
 */
public class SignalingClient {

    private static final int QUEUE_CAPACITY = 4096;
    private static final long CONNECT_TIMEOUT_SECONDS = 10;

    private final String serverUrl;
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private WebSocket webSocket;
    private volatile boolean open = false;

    /** Shared queue: populated by the WebSocket listener, drained by receive() / RelayTransport. */
    final BlockingDeque<Transport.Message> queue = new LinkedBlockingDeque<>(QUEUE_CAPACITY);

    public SignalingClient(String serverUrl) {
        this.serverUrl = serverUrl;
    }

    public void connect() throws IOException, InterruptedException {
        try {
            webSocket = httpClient
                    .newWebSocketBuilder()
                    .buildAsync(URI.create(serverUrl), new Listener())
                    .get(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            open = true;
        } catch (Exception e) {
            throw new IOException("Failed to connect to " + serverUrl, e);
        }
    }

    public void sendText(String json) throws IOException {
        if (!open) throw new IOException("WebSocket is not open");
        webSocket.sendText(json, true).join();
    }

    /**
     * Blocks until a text message arrives or the timeout elapses.
     * Returns null on timeout or close.
     */
    public String receiveText(long timeoutMs) throws InterruptedException {
        Transport.Message msg = queue.poll(timeoutMs, TimeUnit.MILLISECONDS);
        return msg != null && !msg.binary() ? msg.text() : null;
    }

    /**
     * Receives the next message regardless of type.
     * Returns null on timeout.
     */
    public Transport.Message receive(long timeoutMs) throws InterruptedException {
        return queue.poll(timeoutMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Hands the WebSocket connection over to a RelayTransport.
     * After this call, incoming messages flow through the returned transport.
     * Do not call sendText / receiveText on this client afterwards.
     */
    public RelayTransport switchToRelay() {
        return new RelayTransport(webSocket, queue);
    }

    public boolean isOpen() {
        return open;
    }

    public void close() {
        if (open) {
            open = false;
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "");
        }
    }

    // -------------------------------------------------------------------------

    private class Listener implements WebSocket.Listener {

        private final StringBuilder textBuf = new StringBuilder();
        private final ByteArrayOutputStream binaryBuf = new ByteArrayOutputStream();

        @Override
        public void onOpen(WebSocket ws) {
            ws.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            textBuf.append(data);
            if (last) {
                queue.offer(Transport.Message.ofText(textBuf.toString()));
                textBuf.setLength(0);
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket ws, ByteBuffer data, boolean last) {
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            binaryBuf.write(bytes, 0, bytes.length);
            if (last) {
                queue.offer(Transport.Message.ofBinary(binaryBuf.toByteArray()));
                binaryBuf.reset();
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            open = false;
            queue.offer(Transport.Message.ofText("{\"type\":\"PEER_DISCONNECTED\"}"));
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            open = false;
            queue.offer(Transport.Message.ofText("{\"type\":\"PEER_DISCONNECTED\"}"));
        }
    }
}
