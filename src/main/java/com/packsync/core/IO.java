package com.packsync.core;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ThreadLocalRandom;

/** Small file helpers: atomic writes and retrying moves (Windows/antivirus file locks). */
public final class IO {
    private IO() {}

    public static void atomicWrite(Path target, byte[] data) throws IOException {
        Path parent = target.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path tmp = parent.resolve(target.getFileName() + ".tmp-" + Long.toHexString(ThreadLocalRandom.current().nextLong()));
        try {
            Files.write(tmp, data);
            move(tmp, target);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** Move replacing the target, atomically when the filesystem allows it. */
    public static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public interface IOAction { void run() throws IOException; }

    /** Retries an action that may fail because another process briefly holds the file. */
    public static void retry(IOAction action) throws IOException {
        IOException last = null;
        long sleep = 100;
        for (int attempt = 0; attempt < 25; attempt++) {
            try {
                action.run();
                return;
            } catch (IOException e) {
                last = e;
                try { Thread.sleep(sleep); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw e; }
                sleep = Math.min(1000, sleep + 100);
            }
        }
        throw last;
    }

    public static String humanBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double v = bytes / 1024.0;
        if (v < 1024) return String.format(java.util.Locale.ROOT, "%.1f KB", v);
        v /= 1024.0;
        if (v < 1024) return String.format(java.util.Locale.ROOT, "%.1f MB", v);
        return String.format(java.util.Locale.ROOT, "%.2f GB", v / 1024.0);
    }
}
