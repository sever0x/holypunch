package io.github.sever0x.holypunch.client.net;

import java.io.IOException;
import java.nio.channels.DatagramChannel;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Transport implementation over a connected UDP channel with sliding-window ARQ.
 *
 * Each message (text or binary) is prefixed with a 1-byte type marker before
 * being handed to ReliableUdpChannel:
 *   0x00 = text (UTF-8 JSON control message)
 *   0x01 = binary (chunk frame)
 *
 * Created by ConnectionEstablisher after a direct P2P path is confirmed.
 */
public class DirectTransport implements Transport {

    private static final byte MARK_TEXT   = 0x00;
    private static final byte MARK_BINARY = 0x01;

    private final ReliableUdpChannel arq;
    private volatile boolean open = true;

    public DirectTransport(DatagramChannel channel) {
        this.arq = new ReliableUdpChannel(channel);
    }

    @Override
    public void sendBinary(byte[] data) throws IOException {
        sendWrapped(MARK_BINARY, data);
    }

    @Override
    public void sendText(String json) throws IOException {
        sendWrapped(MARK_TEXT, json.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public Message receive() throws IOException, InterruptedException {
        return parse(arq.receive());
    }

    @Override
    public Message receiveWithTimeout(long timeoutMs) throws IOException, InterruptedException {
        return parse(arq.receiveWithTimeout(timeoutMs));
    }

    private Message parse(byte[] raw) {
        if (raw == null || raw.length < 1) return null;
        byte[] payload = Arrays.copyOfRange(raw, 1, raw.length);
        return raw[0] == MARK_TEXT
                ? Message.ofText(new String(payload, StandardCharsets.UTF_8))
                : Message.ofBinary(payload);
    }

    @Override
    public boolean isOpen() {
        return open && !arq.isClosed();
    }

    @Override
    public void close() {
        open = false;
        arq.close();
    }

    private void sendWrapped(byte mark, byte[] payload) throws IOException {
        byte[] wrapped = new byte[1 + payload.length];
        wrapped[0] = mark;
        System.arraycopy(payload, 0, wrapped, 1, payload.length);
        try {
            arq.send(wrapped);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted during send", e);
        }
    }
}
