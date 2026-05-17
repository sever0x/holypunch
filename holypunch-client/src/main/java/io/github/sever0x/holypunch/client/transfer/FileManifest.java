package io.github.sever0x.holypunch.client.transfer;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * Describes all files to be transferred in a single session.
 * Sent by sender to receiver as the first control message.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FileManifest {

    public String hash;        // SHA-256 of the manifest with hash=null; set after build
    public int totalFiles;
    public long totalBytes;
    public List<FileEntry> files;

    public FileManifest() {}

    public FileManifest(String hash, int totalFiles, long totalBytes, List<FileEntry> files) {
        this.hash = hash;
        this.totalFiles = totalFiles;
        this.totalBytes = totalBytes;
        this.files = files;
    }

    public static FileManifest of(List<FileEntry> files) {
        long totalBytes = files.stream().mapToLong(e -> e.size).sum();
        return new FileManifest(null, files.size(), totalBytes, files);
    }

    /**
     * Computes the manifest hash from the JSON representation with hash=null.
     * Sets this.hash and returns it.
     */
    public String computeAndSetHash(ObjectMapper mapper) throws IOException {
        FileManifest copy = new FileManifest(null, totalFiles, totalBytes, files);
        byte[] json = mapper.writeValueAsBytes(copy);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(json);
            this.hash = HexFormat.of().formatHex(digest);
            return this.hash;
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class FileEntry {
        public String path;       // relative, forward-slash separated
        public long size;
        public String sha256;     // null until HashWorker computes it
        public int chunkCount;

        public FileEntry() {}

        public FileEntry(String path, long size, String sha256, int chunkCount) {
            this.path = path;
            this.size = size;
            this.sha256 = sha256;
            this.chunkCount = chunkCount;
        }
    }
}
