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
        if (candidate.equals(base)) return false;
        String name = candidate.getFileName().toString();
        // Exclude the utility itself in all its forms
        if (name.equals("holypunch") || name.equals("holypunch.exe") || name.equals("holypunch.jar")
                || name.startsWith("holypunch-client") && name.endsWith(".jar")) return true;
        // Exclude state / temp files created by holypunch
        if (name.startsWith(".holypunch-")) return true;
        return false;
    }
}
