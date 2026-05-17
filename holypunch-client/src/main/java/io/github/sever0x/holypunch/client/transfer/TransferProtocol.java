package io.github.sever0x.holypunch.client.transfer;

import java.nio.ByteBuffer;

/**
 * Wire-level constants and binary frame helpers for the transfer protocol.
 *
 * Binary frame layout: [4 bytes fileIndex][4 bytes chunkIndex][...chunk data]
 * Control messages: JSON text frames.
 */
public final class TransferProtocol {

    public static final int CHUNK_SIZE = 4 * 1024 * 1024; // 4 MB
    public static final int BINARY_HEADER_BYTES = 8;      // fileIndex + chunkIndex

    // Control message types
    public static final String RESUME_STATE    = "RESUME_STATE";
    public static final String MANIFEST        = "MANIFEST";
    public static final String TRANSFER_READY  = "TRANSFER_READY";
    public static final String FILE_ACK        = "FILE_ACK";
    public static final String CHUNKS_MISSING  = "CHUNKS_MISSING";
    public static final String ALL_FILES_SENT  = "ALL_FILES_SENT";
    public static final String TRANSFER_COMPLETE = "TRANSFER_COMPLETE";

    // Signaling message types (shared with server signaling protocol)
    public static final String ICE_CANDIDATES    = "ICE_CANDIDATES";
    public static final String RELAY_REQUEST     = "RELAY_REQUEST";
    public static final String RELAY_READY       = "RELAY_READY";
    public static final String PEER_JOINED       = "PEER_JOINED";
    public static final String PEER_DISCONNECTED = "PEER_DISCONNECTED";
    public static final String SESSION_CREATED   = "SESSION_CREATED";
    public static final String PAIRED            = "PAIRED";
    public static final String ERROR             = "ERROR";

    private TransferProtocol() {}

    /** Builds an 8-byte binary header prepended to each chunk frame. */
    public static byte[] buildHeader(int fileIndex, int chunkIndex) {
        ByteBuffer buf = ByteBuffer.allocate(BINARY_HEADER_BYTES);
        buf.putInt(fileIndex);
        buf.putInt(chunkIndex);
        return buf.array();
    }

    /** Returns [fileIndex, chunkIndex] extracted from the first 8 bytes of a frame. */
    public static int[] parseHeader(byte[] frame) {
        ByteBuffer buf = ByteBuffer.wrap(frame, 0, BINARY_HEADER_BYTES);
        return new int[]{buf.getInt(), buf.getInt()};
    }

    /** Builds a complete binary frame: header + chunk data. */
    public static byte[] buildFrame(int fileIndex, int chunkIndex, byte[] data, int offset, int length) {
        byte[] frame = new byte[BINARY_HEADER_BYTES + length];
        ByteBuffer.wrap(frame).putInt(fileIndex).putInt(chunkIndex);
        System.arraycopy(data, offset, frame, BINARY_HEADER_BYTES, length);
        return frame;
    }

    public static int chunkCount(long fileSize) {
        if (fileSize == 0) return 0;
        return (int) ((fileSize + CHUNK_SIZE - 1) / CHUNK_SIZE);
    }
}
