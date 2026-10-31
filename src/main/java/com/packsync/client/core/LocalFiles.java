package com.packsync.client.core;

import com.packsync.core.IO;
import com.packsync.core.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/** Client-side files under <game>/packsync/. */
public final class LocalFiles {
    private static final Logger LOG = LoggerFactory.getLogger("PackSync");

    private LocalFiles() {}

    public static Path dir(Path gameDir) { return gameDir.resolve("packsync"); }
    public static Path staging(Path gameDir) { return dir(gameDir).resolve("staging"); }
    public static Path pending(Path gameDir) { return dir(gameDir).resolve("pending"); }
    public static Path planFile(Path gameDir) { return pending(gameDir).resolve("plan.txt"); }
    public static Path resultFile(Path gameDir) { return pending(gameDir).resolve("result.txt"); }
    public static Path lastManifest(Path gameDir) { return dir(gameDir).resolve("last-manifest.json"); }

    /** Player preferences: packsync/local.json (never overwritten by the pack). */
    public static final class Prefs {
        public String _readme = "Your personal PackSync settings. keepMods = extra client-only mods you added yourself (globs).";
        public List<String> keepMods = new ArrayList<>();
        public boolean autoRestart = true;
        public boolean checkOnStartup = true;
        public int downloadThreads = 4;

        public static Prefs load(Path gameDir) {
            Path f = dir(gameDir).resolve("local.json");
            Prefs p;
            try {
                p = Files.isRegularFile(f) ? Json.read(f, Prefs.class) : new Prefs();
            } catch (IOException | RuntimeException e) {
                LOG.warn("[PackSync] local.json unreadable, using defaults: {}", e.getMessage());
                p = new Prefs();
            }
            if (p.keepMods == null) p.keepMods = new ArrayList<>();
            p.downloadThreads = Math.max(1, Math.min(8, p.downloadThreads));
            try {
                if (!Files.exists(f)) Json.write(f, p);
            } catch (IOException ignored) { }
            return p;
        }
    }

    /** What PackSync installed last time: packsync/state.json. */
    public static final class State {
        public int version;
        public int highestSeen;
        public String manifestHash = "";
        public String updated = "";
        public List<String> managed = new ArrayList<>();

        public static State load(Path gameDir) {
            Path f = dir(gameDir).resolve("state.json");
            try {
                if (Files.isRegularFile(f)) {
                    State s = Json.read(f, State.class);
                    if (s.managed == null) s.managed = new ArrayList<>();
                    if (s.manifestHash == null) s.manifestHash = "";
                    return s;
                }
            } catch (IOException | RuntimeException e) {
                LOG.warn("[PackSync] state.json unreadable: {}", e.getMessage());
            }
            return new State();
        }

        public void save(Path gameDir) throws IOException {
            Json.write(dir(gameDir).resolve("state.json"), this);
        }
    }

    /** Remembers file hashes by (size, mtime) so checks are instant after the first run. */
    public static final class HashCache {
        private final Path file;
        private final Map<String, String> map = new ConcurrentHashMap<>();
        private volatile boolean dirty;

        public HashCache(Path gameDir) {
            this.file = dir(gameDir).resolve("hash-cache.properties");
            if (Files.isRegularFile(file)) {
                Properties p = new Properties();
                try (InputStream in = Files.newInputStream(file)) {
                    p.load(in);
                    for (String k : p.stringPropertyNames()) map.put(k, p.getProperty(k));
                } catch (IOException | IllegalArgumentException e) {
                    map.clear();
                }
            }
        }

        public String sha256(String rel, Path abs) throws IOException {
            long size = Files.size(abs);
            long mtime = Files.getLastModifiedTime(abs).toMillis();
            String prefix = size + "|" + mtime + "|";
            String hit = map.get(rel);
            if (hit != null && hit.startsWith(prefix)) return hit.substring(prefix.length());
            String sha = com.packsync.core.Hashing.sha256(abs);
            map.put(rel, prefix + sha);
            dirty = true;
            return sha;
        }

        public void save() {
            if (!dirty) return;
            Properties p = new Properties();
            p.putAll(map);
            try {
                Files.createDirectories(file.getParent());
                Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
                try (OutputStream out = Files.newOutputStream(tmp)) {
                    p.store(out, "PackSync hash cache (safe to delete)");
                }
                IO.move(tmp, file);
                dirty = false;
            } catch (IOException e) {
                LOG.debug("[PackSync] could not save hash cache: {}", e.getMessage());
            }
        }
    }
}
