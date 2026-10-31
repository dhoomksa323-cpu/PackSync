package com.packsync.client.core;

import com.packsync.core.Hashing;
import com.packsync.core.IO;
import com.packsync.core.Manifest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Parallel, verified downloads into packsync/staging/<sha256>.
 * Every byte is hashed while streaming; a file is only kept if size AND SHA-256 match the
 * signed manifest. Already-staged files are reused, so a cancelled/failed update resumes.
 */
public final class Downloader {
    private static final Logger LOG = LoggerFactory.getLogger("PackSync");

    public static final class Progress {
        public final AtomicLong done = new AtomicLong();
        public volatile long total;
        public final AtomicInteger filesDone = new AtomicInteger();
        public volatile int filesTotal;
        public volatile String current = "";
        public final long startNanos = System.nanoTime();

        public double fraction() { return total <= 0 ? 1.0 : Math.min(1.0, done.get() / (double) total); }

        public double bytesPerSecond() {
            double secs = (System.nanoTime() - startNanos) / 1e9;
            return secs < 0.25 ? 0 : done.get() / secs;
        }
    }

    private final Path gameDir;
    private final List<String> bases;
    private final int threads;

    public Downloader(Path gameDir, List<String> bases, int threads) {
        this.gameDir = gameDir;
        this.bases = bases;
        this.threads = Math.max(1, Math.min(8, threads));
    }

    public Path stagedFile(String sha) {
        return LocalFiles.staging(gameDir).resolve(sha);
    }

    public void downloadAll(List<Manifest.Entry> entries, AtomicBoolean cancel, Progress progress) throws IOException {
        Path staging = LocalFiles.staging(gameDir);
        Files.createDirectories(staging);
        Map<String, Manifest.Entry> unique = new LinkedHashMap<>();
        for (Manifest.Entry e : entries) unique.putIfAbsent(e.sha256, e);
        long total = 0;
        for (Manifest.Entry e : unique.values()) total += e.size;
        progress.total = total;
        progress.filesTotal = unique.size();

        long free = Files.getFileStore(staging).getUsableSpace();
        long needed = total * 2 + 64L * 1024 * 1024; // staging copy + installed copy + margin
        if (free > 0 && free < needed) {
            throw new IOException("NOT_ENOUGH_SPACE:" + IO.humanBytes(needed) + ":" + IO.humanBytes(free));
        }

        ExecutorService pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "PackSync-Download");
            t.setDaemon(true);
            return t;
        });
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (Manifest.Entry e : unique.values()) {
                futures.add(pool.submit(() -> {
                    fetchOne(e, cancel, progress);
                    return null;
                }));
            }
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (ExecutionException ex) {
                    cancel.set(true);
                    Throwable c = ex.getCause();
                    if (c instanceof CancellationException ce) throw ce;
                    if (c instanceof IOException io) throw io;
                    throw new IOException(c);
                } catch (InterruptedException ex) {
                    cancel.set(true);
                    Thread.currentThread().interrupt();
                    throw new CancellationException();
                }
            }
        } finally {
            pool.shutdownNow();
        }
        if (cancel.get()) throw new CancellationException();
    }

    private void fetchOne(Manifest.Entry e, AtomicBoolean cancel, Progress progress) throws IOException {
        Path target = stagedFile(e.sha256);
        if (Files.isRegularFile(target) && Files.size(target) == e.size && Hashing.sha256(target).equals(e.sha256)) {
            progress.done.addAndGet(e.size);
            progress.filesDone.incrementAndGet();
            return;
        }
        IOException last = null;
        for (int round = 0; round < 3; round++) {
            for (String base : bases) {
                if (cancel.get()) throw new CancellationException();
                progress.current = e.path;
                long[] counted = {0};
                try {
                    download(base + "/files/" + e.sha256, e, target, cancel, progress, counted);
                    progress.filesDone.incrementAndGet();
                    return;
                } catch (CancellationException ce) {
                    progress.done.addAndGet(-counted[0]);
                    throw ce;
                } catch (IOException ex) {
                    progress.done.addAndGet(-counted[0]);
                    last = ex;
                    LOG.warn("[PackSync] download {} from {} failed: {}", e.path, base, ex.getMessage());
                    int backoff = (ex instanceof Http.StatusException se && (se.status == 429 || se.status == 503)) ? 2000 : 700;
                    sleep(backoff * (round + 1), cancel);
                }
            }
        }
        throw new IOException("DOWNLOAD_FAILED:" + e.path + ":" + (last == null ? "?" : last.getMessage()));
    }

    private void download(String url, Manifest.Entry e, Path target, AtomicBoolean cancel, Progress progress, long[] counted) throws IOException {
        Path part = target.resolveSibling(e.sha256 + ".part");
        HttpURLConnection c = Http.open(url, 8000, 30000);
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new Http.StatusException(code, url);
            long len = c.getContentLengthLong();
            if (len >= 0 && len != e.size) throw new IOException("Size mismatch from server for " + e.path);
            MessageDigest md = Hashing.newSha256();
            long got = 0;
            try (InputStream in = c.getInputStream(); OutputStream out = Files.newOutputStream(part)) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (cancel.get()) throw new CancellationException();
                    got += n;
                    if (got > e.size) throw new IOException("Server sent too much data for " + e.path);
                    md.update(buf, 0, n);
                    out.write(buf, 0, n);
                    progress.done.addAndGet(n);
                    counted[0] += n;
                }
            }
            if (got != e.size) throw new IOException("Incomplete download of " + e.path);
            if (!Hashing.hex(md.digest()).equals(e.sha256)) throw new IOException("Checksum mismatch for " + e.path);
            IO.move(part, target);
        } finally {
            c.disconnect();
            Files.deleteIfExists(part);
        }
    }

    private static void sleep(long ms, AtomicBoolean cancel) {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (cancel.get()) throw new CancellationException();
            try { Thread.sleep(100); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw new CancellationException(); }
        }
    }
}
