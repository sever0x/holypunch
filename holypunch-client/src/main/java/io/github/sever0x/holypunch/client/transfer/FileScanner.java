package io.github.sever0x.holypunch.client.transfer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class FileScanner {

    /**
     * Recursively scans {@code dir}, returning a manifest with sha256=null for each file.
     * Entries matching {@code .holypunch-*} (files and directories) are excluded.
     */
    public FileManifest scan(Path dir) throws IOException {
        List<FileManifest.FileEntry> entries = new ArrayList<>();

        try (var stream = Files.walk(dir)) {
            stream
                .filter(p -> !isExcluded(dir, p))
                .filter(Files::isRegularFile)
                .sorted(Comparator.comparing(p -> dir.relativize(p).toString()))
                .forEach(p -> {
                    try {
                        String relativePath = dir.relativize(p).toString().replace('\\', '/');
                        long size = Files.size(p);
                        int chunks = TransferProtocol.chunkCount(size);
                        entries.add(new FileManifest.FileEntry(relativePath, size, null, chunks));
                    } catch (IOException e) {
                        throw new RuntimeException("Cannot stat " + p, e);
                    }
                });
        }

        return FileManifest.of(entries);
    }

    private boolean isExcluded(Path base, Path candidate) {
        // Exclude the base directory itself
        if (candidate.equals(base)) return false; // walk includes root; Files::isRegularFile filters it
        // Exclude any path component starting with ".holypunch-"
        for (Path component : candidate) {
            if (component.getFileName().toString().startsWith(".holypunch-")) return true;
        }
        return false;
    }
}
