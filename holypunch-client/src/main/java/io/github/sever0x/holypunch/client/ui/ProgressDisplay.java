package io.github.sever0x.holypunch.client.ui;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Renders a single-line \r-based progress bar to stdout.
 *
 * Tracks transfer speed via a 5-second sliding window and computes ETA.
 * Thread-safe: update() is synchronized.
 *
 * Usage:
 *   ProgressDisplay p = new ProgressDisplay(totalBytes, "relay");
 *   streamer.setProgressCallback(p::update);
 *   p.complete();
 */
public class ProgressDisplay {

    private static final int BAR_WIDTH = 24;
    private static final long SPEED_WINDOW_MS = 5_000;

    private volatile long totalBytes;
    private final String mode;
    private final Deque<long[]> speedWindow = new ArrayDeque<>(); // [epochMs, bytes]

    public ProgressDisplay(long totalBytes, String mode) {
        this.totalBytes = totalBytes;
        this.mode = mode;
    }

    /** Called by ChunkStreamer / ChunkReceiver progress callback. */
    public synchronized void update(long bytesDone, long total) {
        if (total > 0) this.totalBytes = total;
        render(bytesDone);
    }

    public synchronized void update(long bytesDone) {
        render(bytesDone);
    }

    /** Finishes the progress line with a newline. */
    public void complete() {
        System.out.println();
    }

    // ── Hashing progress (static, called before transfer starts) ─────────────

    public static void printHashProgress(int done, int total) {
        int pct    = total > 0 ? done * 100 / total : 100;
        int filled = total > 0 ? BAR_WIDTH * done / total : BAR_WIDTH;
        String bar = "[" + "█".repeat(filled) + "░".repeat(BAR_WIDTH - filled) + "]";
        System.out.printf("\rHashing... %s %3d%%  (%d/%d files)  ", bar, pct, done, total);
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private void render(long bytesDone) {
        long now = System.currentTimeMillis();
        speedWindow.addLast(new long[]{now, bytesDone});
        while (!speedWindow.isEmpty() && now - speedWindow.peekFirst()[0] > SPEED_WINDOW_MS) {
            speedWindow.removeFirst();
        }

        double bps = 0;
        if (speedWindow.size() >= 2) {
            long[] first = speedWindow.peekFirst();
            long[] last  = speedWindow.peekLast();
            long dt = last[0] - first[0];
            if (dt > 0) bps = (double) (last[1] - first[1]) * 1000 / dt;
        }

        int pct    = totalBytes > 0 ? (int) (bytesDone * 100 / totalBytes) : 0;
        int filled = totalBytes > 0 ? (int) (BAR_WIDTH * bytesDone / totalBytes) : 0;
        String bar = "[" + "█".repeat(filled) + "░".repeat(BAR_WIDTH - filled) + "]";

        String line = String.format("\r  %s %3d%%  %s / %s",
                bar, pct, formatSize(bytesDone), formatSize(totalBytes));

        if (bps > 0) {
            long etaSecs = bps > 0 ? (long) ((totalBytes - bytesDone) / bps) : 0;
            line += String.format("  %s/s  ETA %s  [%s]",
                    formatSize((long) bps), formatEta(etaSecs), mode);
        }

        // Pad to 110 chars so shorter updates erase previous longer lines
        System.out.printf("%-110s", line);
    }

    // ── Public formatting helpers (used by commands too) ─────────────────────

    public static String formatSize(long bytes) {
        if (bytes < 1024L)             return bytes + " B";
        if (bytes < 1024L * 1024)      return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private static String formatEta(long secs) {
        if (secs < 60)   return secs + "s";
        if (secs < 3600) return String.format("%dm %ds", secs / 60, secs % 60);
        return String.format("%dh %dm", secs / 3600, (secs % 3600) / 60);
    }
}
