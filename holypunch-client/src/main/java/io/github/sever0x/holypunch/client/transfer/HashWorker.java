package io.github.sever0x.holypunch.client.transfer;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

public class HashWorker {

    private static final int READ_BUFFER = 128 * 1024; // 128 KB read buffer

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * Hashes all files in {@code manifest} in parallel using virtual threads.
     * Updates each {@code FileEntry.sha256} as it completes.
     * Calls {@code onProgress(completed, total)} after each file finishes.
     *
     * @return a future that completes when all files have been hashed
     */
    public CompletableFuture<Void> hashAll(
            FileManifest manifest,
            Path baseDir,
            BiConsumer<Integer, Integer> onProgress) {

        int total = manifest.files.size();
        AtomicInteger done = new AtomicInteger(0);

        List<CompletableFuture<Void>> futures = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            FileManifest.FileEntry entry = manifest.files.get(i);
            Path absolutePath = baseDir.resolve(entry.path.replace('/', java.io.File.separatorChar));
            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    entry.sha256 = sha256(absolutePath);
                } catch (IOException e) {
                    throw new RuntimeException("Failed to hash " + absolutePath, e);
                }
                onProgress.accept(done.incrementAndGet(), total);
            }, executor));
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }

    /** Blocks until a single file is hashed. Returns hex SHA-256. */
    public static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            if (Files.size(file) == 0) {
                return HexFormat.of().formatHex(digest.digest());
            }
            try (InputStream in = new BufferedInputStream(Files.newInputStream(file), READ_BUFFER)) {
                byte[] buf = new byte[READ_BUFFER];
                int read;
                while ((read = in.read(buf)) != -1) {
                    digest.update(buf, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    public void shutdown() {
        executor.shutdown();
    }
}
