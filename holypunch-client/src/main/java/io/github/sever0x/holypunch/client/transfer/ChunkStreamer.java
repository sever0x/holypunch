package io.github.sever0x.holypunch.client.transfer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.sever0x.holypunch.client.net.Transport;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * Sender side of the transfer protocol.
 *
 * Handshake: reads RESUME_STATE → sends MANIFEST → reads TRANSFER_READY
 * Streaming: sends all missing chunk frames (binary) in file order
 * Verification: reads FILE_ACK per file, re-sends chunks listed in CHUNKS_MISSING
 * Completion: sends ALL_FILES_SENT, reads TRANSFER_COMPLETE
 *
 * All operations block the calling thread. Intended to be run on a dedicated
 * thread (e.g. the CLI command thread).
 */
public class ChunkStreamer {

    private final Transport transport;
    private final Path baseDir;
    private final FileManifest manifest;
    private final ObjectMapper mapper;
    private BiConsumer<Long, Long> onProgress = (done, total) -> {};

    public ChunkStreamer(Transport transport, Path baseDir, FileManifest manifest, ObjectMapper mapper) {
        this.transport = transport;
        this.baseDir = baseDir;
        this.manifest = manifest;
        this.mapper = mapper;
    }

    public void setProgressCallback(BiConsumer<Long, Long> onProgress) {
        this.onProgress = onProgress;
    }

    public void stream() throws IOException, InterruptedException {
        // 1. Read RESUME_STATE from receiver
        Transport.Message msg = receiveText();
        JsonNode resumeNode = mapper.readTree(msg.text());
        Map<Integer, Set<Integer>> alreadyReceived = parseAlreadyReceived(resumeNode);

        // 2. Send MANIFEST
        transport.sendText(buildManifestJson());

        // 3. Read TRANSFER_READY
        receiveTextOfType(TransferProtocol.TRANSFER_READY);

        // 4. Stream all missing chunks across all files in one sequential pass
        long totalSent = 0;
        for (int fi = 0; fi < manifest.files.size(); fi++) {
            FileManifest.FileEntry entry = manifest.files.get(fi);
            Set<Integer> received = alreadyReceived.getOrDefault(fi, Set.of());
            totalSent += sendMissingChunks(fi, entry, received, totalSent);
        }

        // 5. Collect one FILE_ACK per file.
        //    If sha256Match=false the next message is CHUNKS_MISSING — read it immediately.
        //    FILE_ACKs arrive while we were streaming; they're queued in Transport.
        Map<Integer, List<Integer>> resendMap = new HashMap<>();
        for (int i = 0; i < manifest.totalFiles; i++) {
            msg = receiveText();
            JsonNode ack = mapper.readTree(msg.text());
            if (!ack.path("sha256Match").asBoolean(true)) {
                msg = receiveText(); // CHUNKS_MISSING sent right after FILE_ACK by receiver
                JsonNode missing = mapper.readTree(msg.text());
                int fi = missing.path("fileIndex").asInt();
                List<Integer> indices = new ArrayList<>();
                missing.path("chunkIndices").forEach(n -> indices.add(n.intValue()));
                resendMap.put(fi, indices);
            }
        }

        // 6. Re-send any chunks requested by the receiver
        for (Map.Entry<Integer, List<Integer>> e : resendMap.entrySet()) {
            sendSpecificChunks(e.getKey(), manifest.files.get(e.getKey()), e.getValue());
        }

        // 7. Signal completion
        transport.sendText("{\"type\":\"" + TransferProtocol.ALL_FILES_SENT + "\"}");

        // 8. Wait for TRANSFER_COMPLETE
        msg = receiveText();
        JsonNode complete = mapper.readTree(msg.text());
        if (!complete.path("ok").asBoolean(false)) {
            throw new IOException("Receiver reported transfer completed with errors");
        }
    }

    // -------------------------------------------------------------------------

    /**
     * Sends chunks of one file that are not in {@code alreadyReceived}.
     * Returns total bytes sent for this file.
     */
    private long sendMissingChunks(
            int fi,
            FileManifest.FileEntry entry,
            Set<Integer> alreadyReceived,
            long runningTotal) throws IOException {

        if (entry.chunkCount == 0) return 0;

        Path filePath = baseDir.resolve(entry.path.replace('/', File.separatorChar));
        long fileSent = 0;

        try (FileChannel channel = FileChannel.open(filePath, StandardOpenOption.READ)) {
            byte[] buf = new byte[TransferProtocol.CHUNK_SIZE];
            for (int ci = 0; ci < entry.chunkCount; ci++) {
                if (alreadyReceived.contains(ci)) continue;

                long fileOffset = (long) ci * TransferProtocol.CHUNK_SIZE;
                int chunkSize = (int) Math.min(TransferProtocol.CHUNK_SIZE, entry.size - fileOffset);

                readFully(channel, buf, fileOffset, chunkSize);

                byte[] frame = TransferProtocol.buildFrame(fi, ci, buf, 0, chunkSize);
                transport.sendBinary(frame);

                fileSent += chunkSize;
                onProgress.accept(runningTotal + fileSent, manifest.totalBytes);
            }
        }
        return fileSent;
    }

    private void sendSpecificChunks(int fi, FileManifest.FileEntry entry, List<Integer> chunkIndices)
            throws IOException {
        if (chunkIndices.isEmpty()) return;
        Path filePath = baseDir.resolve(entry.path.replace('/', File.separatorChar));
        byte[] buf = new byte[TransferProtocol.CHUNK_SIZE];
        try (FileChannel channel = FileChannel.open(filePath, StandardOpenOption.READ)) {
            for (int ci : chunkIndices) {
                long fileOffset = (long) ci * TransferProtocol.CHUNK_SIZE;
                int chunkSize = (int) Math.min(TransferProtocol.CHUNK_SIZE, entry.size - fileOffset);
                readFully(channel, buf, fileOffset, chunkSize);
                transport.sendBinary(TransferProtocol.buildFrame(fi, ci, buf, 0, chunkSize));
            }
        }
    }

    /** Reads exactly {@code length} bytes from {@code channel} at {@code fileOffset} into {@code buf}. */
    private static void readFully(FileChannel channel, byte[] buf, long fileOffset, int length)
            throws IOException {
        ByteBuffer bb = ByteBuffer.wrap(buf, 0, length);
        while (bb.hasRemaining()) {
            int read = channel.read(bb, fileOffset + bb.position());
            if (read < 0) throw new IOException("Unexpected EOF at offset " + fileOffset);
        }
    }

    /**
     * Parses alreadyReceived from RESUME_STATE.
     * Returns empty map if manifest hash doesn't match (different or first-time session).
     */
    private Map<Integer, Set<Integer>> parseAlreadyReceived(JsonNode resumeNode) {
        Map<Integer, Set<Integer>> result = new HashMap<>();
        String peerHash = resumeNode.path("manifestHash").asText(null);
        if (!manifest.hash.equals(peerHash)) return result;

        resumeNode.path("receivedChunks").fields().forEachRemaining(e -> {
            int fi = Integer.parseInt(e.getKey());
            Set<Integer> set = new HashSet<>();
            e.getValue().forEach(n -> set.add(n.intValue()));
            result.put(fi, set);
        });
        return result;
    }

    private String buildManifestJson() throws IOException {
        ObjectNode node = mapper.createObjectNode();
        node.put("type", TransferProtocol.MANIFEST);
        node.put("hash", manifest.hash);
        node.put("totalFiles", manifest.totalFiles);
        node.put("totalBytes", manifest.totalBytes);
        node.set("files", mapper.valueToTree(manifest.files));
        return mapper.writeValueAsString(node);
    }

    /** Reads the next text message, skipping any stray binary frames. */
    private Transport.Message receiveText() throws IOException, InterruptedException {
        while (true) {
            Transport.Message msg = transport.receive();
            if (msg == null) throw new IOException("Transport closed while waiting for message");
            if (!msg.binary()) return msg;
        }
    }

    private void receiveTextOfType(String expected) throws IOException, InterruptedException {
        Transport.Message msg = receiveText();
        String actual = mapper.readTree(msg.text()).path("type").asText();
        if (!expected.equals(actual)) {
            throw new IOException("Protocol error: expected " + expected + ", got " + actual);
        }
    }
}
