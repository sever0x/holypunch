package io.github.sever0x.holypunch.client.transfer;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Atomic read/write of .holypunch-state.json in the destination directory.
 *
 * State file is written via temp file + ATOMIC_MOVE so a crash mid-write
 * never leaves a corrupted state.
 *
 * Callers must flush chunk data to the OS (FileChannel.force) BEFORE calling save(),
 * so the on-disk state never claims a chunk was received when it wasn't.
 */
public class ResumeStateManager {

    public static final String STATE_FILENAME = ".holypunch-state.json";

    private final Path stateFile;
    private final Path tempFile;
    private final ObjectMapper mapper;

    public ResumeStateManager(Path destinationDir, ObjectMapper mapper) {
        this.stateFile = destinationDir.resolve(STATE_FILENAME);
        this.tempFile  = destinationDir.resolve(STATE_FILENAME + ".tmp");
        this.mapper    = mapper;
    }

    /** Loads persisted state, or returns an empty state if the file is absent or corrupted. */
    public ResumeState load() {
        if (!Files.exists(stateFile)) return ResumeState.empty();
        try {
            return mapper.readValue(stateFile.toFile(), ResumeState.class);
        } catch (IOException e) {
            return ResumeState.empty(); // corrupted state → start fresh
        }
    }

    /** Atomically persists the current state to disk. */
    public void save(ResumeState state) throws IOException {
        byte[] json = mapper.writeValueAsBytes(state);
        Files.write(tempFile, json, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.DSYNC);
        Files.move(tempFile, stateFile,
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Removes the state file after a successful transfer. */
    public void delete() throws IOException {
        Files.deleteIfExists(stateFile);
        Files.deleteIfExists(tempFile);
    }

    public boolean exists() {
        return Files.exists(stateFile);
    }

    // -------------------------------------------------------------------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ResumeState {

        public String manifestHash;
        /** fileIndex (as String key for JSON) → list of received chunk indices */
        public Map<Integer, List<Integer>> receivedChunks;

        // Jackson needs a no-args constructor
        public ResumeState() {
            this.receivedChunks = new HashMap<>();
        }

        public ResumeState(String manifestHash, Map<Integer, List<Integer>> receivedChunks) {
            this.manifestHash = manifestHash;
            this.receivedChunks = receivedChunks;
        }

        public static ResumeState empty() {
            return new ResumeState(null, new HashMap<>());
        }

        public boolean hasChunk(int fileIndex, int chunkIndex) {
            List<Integer> chunks = receivedChunks.get(fileIndex);
            return chunks != null && chunks.contains(chunkIndex);
        }

        /** Records a received chunk. Not thread-safe — call from a single writer thread. */
        public void markChunk(int fileIndex, int chunkIndex) {
            receivedChunks.computeIfAbsent(fileIndex, k -> new ArrayList<>()).add(chunkIndex);
        }

        /** Returns all received chunk indices for a file, as a set for O(1) lookup. */
        public Set<Integer> receivedChunksFor(int fileIndex) {
            List<Integer> list = receivedChunks.get(fileIndex);
            return list != null ? new HashSet<>(list) : Set.of();
        }

        /** True if all chunks for a file are recorded in state. */
        public boolean isFileComplete(int fileIndex, int chunkCount) {
            List<Integer> received = receivedChunks.get(fileIndex);
            return received != null && received.size() >= chunkCount;
        }
    }
}
