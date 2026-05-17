package io.github.sever0x.holypunch.client.transfer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.sever0x.holypunch.client.net.Transport;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
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
 * Receiver side of the transfer protocol.
 *
 * Handshake: sends RESUME_STATE → reads MANIFEST → pre-allocates files → sends TRANSFER_READY
 * Receive loop: reads binary chunk frames and writes to FileChannel at correct offsets.
 *               Sends FILE_ACK (+ optional CHUNKS_MISSING) as each file completes.
 *               Exits loop on ALL_FILES_SENT.
 * Completion: sends TRANSFER_COMPLETE, deletes state file on success.
 *
 * Key invariants:
 *   - FileChannel.force() is called after each chunk write BEFORE state is saved.
 *     This ensures on-disk data matches the state file after a crash.
 *   - State is saved after every chunk via ResumeStateManager.save() (atomic move).
 *   - In-session duplicate chunks are detected via a per-file Set and silently dropped.
 */
public class ChunkReceiver {

    private final Transport transport;
    private final Path destDir;
    private final ObjectMapper mapper;
    private BiConsumer<Long, Long> onProgress = (done, total) -> {};

    public ChunkReceiver(Transport transport, Path destDir, ObjectMapper mapper) {
        this.transport = transport;
        this.destDir = destDir;
        this.mapper = mapper;
    }

    public void setProgressCallback(BiConsumer<Long, Long> onProgress) {
        this.onProgress = onProgress;
    }

    public void receive() throws IOException, InterruptedException {
        ResumeStateManager stateManager = new ResumeStateManager(destDir, mapper);
        ResumeStateManager.ResumeState state = stateManager.load();

        // 1. Send RESUME_STATE (empty or loaded from .holypunch-state.json)
        transport.sendText(buildResumeStateJson(state));

        // 2. Read MANIFEST
        Transport.Message msg = receiveText();
        FileManifest manifest = mapper.readValue(msg.text(), FileManifest.class);
        validateManifest(manifest);

        // If manifest changed since last session: discard previous resume state
        if (!manifest.hash.equals(state.manifestHash)) {
            state = ResumeStateManager.ResumeState.empty();
            state.manifestHash = manifest.hash;
        }

        // In-session chunk sets for O(1) lookup and duplicate rejection
        Map<Integer, Set<Integer>> sessionChunks = new HashMap<>();
        for (Map.Entry<Integer, List<Integer>> e : state.receivedChunks.entrySet()) {
            sessionChunks.put(e.getKey(), new HashSet<>(e.getValue()));
        }

        // 3. Pre-allocate destination files; immediately ACK zero-chunk (empty) files
        // channels is declared before the try so the finally block can close them on interrupt
        Map<Integer, FileChannel> channels = new HashMap<>();
        try {
        for (int fi = 0; fi < manifest.files.size(); fi++) {
            FileManifest.FileEntry entry = manifest.files.get(fi);
            Path filePath = resolveDestPath(entry.path);
            Files.createDirectories(filePath.getParent());
            preallocateIfAbsent(filePath, entry.size);

            if (entry.chunkCount == 0) {
                sendFileAck(fi, true); // empty file, trivially complete
            } else {
                channels.put(fi, FileChannel.open(filePath,
                        StandardOpenOption.READ, StandardOpenOption.WRITE));
            }
        }

        // 4. Signal readiness
        transport.sendText("{\"type\":\"" + TransferProtocol.TRANSFER_READY + "\"}");

        // 5. Receive chunk frames until ALL_FILES_SENT
        long totalReceived = 0;
        long totalBytes = manifest.totalBytes;

        while (true) {
            msg = transport.receive();
            if (msg == null) throw new IOException("Transport closed during transfer");

            if (msg.binary()) {
                int[] header = TransferProtocol.parseHeader(msg.data());
                int fi = header[0], ci = header[1];

                Set<Integer> fileChunks = sessionChunks.computeIfAbsent(fi, k -> new HashSet<>());
                if (fileChunks.contains(ci)) continue; // duplicate — drop silently

                FileManifest.FileEntry entry = manifest.files.get(fi);
                FileChannel channel = channels.get(fi);

                // Write chunk data at the correct file offset
                int dataOffset = TransferProtocol.BINARY_HEADER_BYTES;
                int dataLen    = msg.data().length - dataOffset;
                long fileOffset = (long) ci * TransferProtocol.CHUNK_SIZE;

                ByteBuffer bb = ByteBuffer.wrap(msg.data(), dataOffset, dataLen);
                while (bb.hasRemaining()) {
                    channel.write(bb, fileOffset + (dataLen - bb.remaining()));
                }

                // Durability: flush chunk to OS before marking it received in state
                channel.force(false);

                fileChunks.add(ci);
                state.markChunk(fi, ci);
                stateManager.save(state);

                totalReceived += dataLen;
                onProgress.accept(totalReceived, totalBytes);

                // If all chunks for this file are in, verify and ACK
                if (fileChunks.size() == entry.chunkCount) {
                    channel.close();
                    channels.remove(fi);

                    boolean ok = verifyFile(resolveDestPath(entry.path), entry.sha256);
                    sendFileAck(fi, ok);
                    if (!ok) {
                        // No per-chunk hashes available — request full re-send
                        sendChunksMissing(fi, allChunkIndices(entry.chunkCount));
                    }
                }

            } else {
                // Text frame: check for ALL_FILES_SENT
                JsonNode node = mapper.readTree(msg.text());
                if (TransferProtocol.ALL_FILES_SENT.equals(node.path("type").asText())) break;
            }
        }

        // Close any channels still open (shouldn't happen under normal flow)
        for (FileChannel ch : channels.values()) ch.close();
        channels.clear();

        // 6. Final result
        boolean allComplete = channels.isEmpty();
        transport.sendText("{\"type\":\"" + TransferProtocol.TRANSFER_COMPLETE
                + "\",\"ok\":" + allComplete + "}");

        if (allComplete) {
            stateManager.delete();
        }
        } finally {
            // Ensure file channels are closed even on interrupt/exception.
            // Data written before force() may not be on disk, but the state file
            // only records chunks after force(), so resume will re-request those.
            for (FileChannel ch : channels.values()) {
                try { ch.close(); } catch (IOException ignored) {}
            }
        }
    }

    // -------------------------------------------------------------------------

    /**
     * Creates the file if it doesn't exist yet and pre-allocates its size.
     * Does NOT truncate existing files — resume case must preserve written data.
     */
    private void preallocateIfAbsent(Path file, long size) throws IOException {
        if (Files.exists(file)) return;
        try (FileChannel ch = FileChannel.open(file,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            if (size > 0) {
                // Write one zero byte at the last position; NTFS/ext4 handle this efficiently
                ch.write(ByteBuffer.wrap(new byte[]{0}), size - 1);
            }
        }
    }

    private boolean verifyFile(Path file, String expectedSha256) throws IOException {
        if (expectedSha256 == null) return true; // no hash in manifest, skip verification
        return HashWorker.sha256(file).equals(expectedSha256);
    }

    private void sendFileAck(int fi, boolean sha256Match) throws IOException {
        transport.sendText("{\"type\":\"" + TransferProtocol.FILE_ACK
                + "\",\"fileIndex\":" + fi
                + ",\"sha256Match\":" + sha256Match + "}");
    }

    private void sendChunksMissing(int fi, List<Integer> indices) throws IOException {
        ObjectNode node = mapper.createObjectNode();
        node.put("type", TransferProtocol.CHUNKS_MISSING);
        node.put("fileIndex", fi);
        node.set("chunkIndices", mapper.valueToTree(indices));
        transport.sendText(mapper.writeValueAsString(node));
    }

    private List<Integer> allChunkIndices(int count) {
        List<Integer> indices = new ArrayList<>(count);
        for (int i = 0; i < count; i++) indices.add(i);
        return indices;
    }

    private String buildResumeStateJson(ResumeStateManager.ResumeState state) throws IOException {
        ObjectNode node = mapper.createObjectNode();
        node.put("type", TransferProtocol.RESUME_STATE);
        if (state.manifestHash != null) node.put("manifestHash", state.manifestHash);
        node.set("receivedChunks", mapper.valueToTree(state.receivedChunks));
        return mapper.writeValueAsString(node);
    }

    private void validateManifest(FileManifest manifest) throws IOException {
        if (manifest.files == null) throw new IOException("Manifest has no file list");
        for (FileManifest.FileEntry entry : manifest.files) {
            if (entry.path == null || entry.path.isEmpty())
                throw new IOException("Manifest entry has null or empty path");
            if (entry.path.startsWith("/") || entry.path.startsWith("\\"))
                throw new IOException("Absolute path rejected in manifest: " + entry.path);
            if (entry.path.contains("\0"))
                throw new IOException("Null byte in manifest path: " + entry.path);
            String normalized = entry.path.replace('\\', '/');
            for (String segment : normalized.split("/", -1)) {
                if ("..".equals(segment))
                    throw new IOException("Path traversal rejected in manifest: " + entry.path);
            }
        }
    }

    private Path resolveDestPath(String relativePath) throws IOException {
        Path base = destDir.toAbsolutePath().normalize();
        Path resolved = base.resolve(relativePath).normalize();
        if (!resolved.startsWith(base))
            throw new IOException("Path traversal detected: " + relativePath);
        return resolved;
    }

    /** Reads the next text frame, skipping unexpected binary frames. */
    private Transport.Message receiveText() throws IOException, InterruptedException {
        while (true) {
            Transport.Message msg = transport.receive();
            if (msg == null) throw new IOException("Transport closed while waiting for message");
            if (!msg.binary()) return msg;
        }
    }
}
