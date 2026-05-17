package io.github.sever0x.holypunch.client.net;

import java.io.IOException;

/**
 * Abstraction over the data channel between sender and receiver.
 *
 * Two implementations:
 *   - RelayTransport  – data flows through the signaling server (always available as fallback)
 *   - DirectTransport – direct P2P UDP with reliable delivery (added in Phase 6b)
 *
 * Both sides of the channel (sender and receiver) use this interface.
 * ChunkStreamer and ChunkReceiver are unaware of which path is active.
 */
public interface Transport {

    /** Sends a binary chunk frame. Blocks until the send is accepted by the underlying transport. */
    void sendBinary(byte[] data) throws IOException;

    /** Sends a JSON control message as a text frame. */
    void sendText(String json) throws IOException;

    /**
     * Blocks until the next message arrives.
     * Returns null if the transport closed cleanly (peer disconnected).
     */
    Message receive() throws IOException, InterruptedException;

    /**
     * Waits up to {@code timeoutMs} for the next message.
     * Returns null on timeout or clean close.
     */
    default Message receiveWithTimeout(long timeoutMs) throws IOException, InterruptedException {
        return receive(); // default: no timeout; override in concrete classes
    }

    boolean isOpen();

    void close();

    // -------------------------------------------------------------------------

    record Message(boolean binary, byte[] data, String text) {
        public static Message ofText(String text) {
            return new Message(false, null, text);
        }

        public static Message ofBinary(byte[] data) {
            return new Message(true, data, null);
        }
    }
}
