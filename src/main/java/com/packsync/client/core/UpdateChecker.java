package com.packsync.client.core;

import com.packsync.core.Globs;
import com.packsync.core.Json;
import com.packsync.core.Manifest;
import com.packsync.core.PackClientConfig;
import com.packsync.core.PathRules;
import com.packsync.core.Signing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Fetches + verifies the signed manifest and compares it to the local game folder. */
public final class UpdateChecker {
    private static final Logger LOG = LoggerFactory.getLogger("PackSync");

    public enum Status { NOT_CONFIGURED, UP_TO_DATE, OUTDATED, ERROR }

    public static final class Result {
        public Status status;
        public String error = "";
        public Manifest manifest;
        public String manifestHash = "";
        public byte[] envelope;
        public List<String> bases = new ArrayList<>();
        public boolean offline;
        public int localVersion;
        public final List<Manifest.Entry> downloads = new ArrayList<>();
        public final List<String> deletions = new ArrayList<>();
        public long downloadBytes;

        public boolean hasChanges() { return !downloads.isEmpty() || !deletions.isEmpty(); }

        static Result of(Status s, String err) {
            Result r = new Result();
            r.status = s;
            r.error = err == null ? "" : err;
            return r;
        }
    }

    private final Path gameDir;

    public UpdateChecker(Path gameDir) {
        this.gameDir = gameDir.toAbsolutePath().normalize();
    }

    public static PackClientConfig loadClientConfig(Path gameDir) {
        Path f = gameDir.resolve(PackClientConfig.PATH);
        try {
            if (Files.isRegularFile(f)) return Json.read(f, PackClientConfig.class);
        } catch (IOException | RuntimeException e) {
            LOG.warn("[PackSync] {} unreadable: {}", PackClientConfig.PATH, e.getMessage());
        }
        return new PackClientConfig();
    }

    /**
     * @param expectedHash manifest hash announced by the server we just joined, or null.
     */
    public Result check(String expectedHash) {
        try {
            return doCheck(expectedHash);
        } catch (RuntimeException e) {
            LOG.error("[PackSync] check failed", e);
            return Result.of(Status.ERROR, e.toString());
        }
    }

    private Result doCheck(String expectedHash) {
        PackClientConfig cfg = loadClientConfig(gameDir);
        if (!cfg.isUsable()) return Result.of(Status.NOT_CONFIGURED, "config/packsync-client.json missing");
        PublicKey key;
        try {
            key = Signing.decodePublic(cfg.publicKey);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return Result.of(Status.ERROR, "Invalid public key in " + PackClientConfig.PATH);
        }
        LocalFiles.State state = LocalFiles.State.load(gameDir);
        List<String> bases = cfg.bases();
        if (bases.isEmpty()) return Result.of(Status.ERROR, "No valid update URL configured");

        Manifest.Verified best = null;
        String lastError = "unreachable";
        for (String base : bases) {
            try {
                byte[] bytes = Http.get(base + "/manifest.json?t=" + System.currentTimeMillis(), 16 * 1024 * 1024);
                Manifest.Verified v = Manifest.open(bytes, key);
                if (expectedHash != null && !expectedHash.isEmpty() && !v.hash().equals(expectedHash)) {
                    lastError = "mirror " + base + " is not in sync with the server";
                    if (best == null) best = v;
                    continue;
                }
                best = v;
                lastError = null;
                // Prefer the base that answered first.
                if (!base.equals(bases.get(0))) {
                    bases = new ArrayList<>(bases);
                    bases.remove(base);
                    bases.add(0, base);
                }
                break;
            } catch (IOException e) {
                lastError = e.getMessage();
                LOG.warn("[PackSync] {} -> {}", base, e.getMessage());
            }
        }

        boolean offline = false;
        if (best == null || (lastError != null && expectedHash != null && !expectedHash.isEmpty())) {
            // Network failed or mirrors stale: we can still VERIFY against the last signed manifest
            // if it is exactly what the server announced.
            Manifest.Verified cached = readCached(key);
            if (cached != null && expectedHash != null && cached.hash().equals(expectedHash)) {
                best = cached;
                offline = true;
            } else if (best == null) {
                return Result.of(Status.ERROR, lastError);
            } else {
                return Result.of(Status.ERROR, lastError);
            }
        }

        // Anti-downgrade: never accept an older signed manifest than one we've already seen,
        // unless the server we are connected to explicitly announced it.
        boolean announced = expectedHash != null && expectedHash.equals(best.hash());
        if (!announced && best.manifest().version < state.highestSeen) {
            return Result.of(Status.ERROR, "Received an older pack version (" + best.manifest().version + " < " + state.highestSeen + ")");
        }

        if (!offline) saveCached(best.envelope());

        Result r = new Result();
        r.manifest = best.manifest();
        r.manifestHash = best.hash();
        r.envelope = best.envelope();
        r.bases = bases;
        r.offline = offline;
        r.localVersion = state.version;
        try {
            diff(r, state);
        } catch (IOException e) {
            return Result.of(Status.ERROR, "Could not read local files: " + e.getMessage());
        }
        r.status = r.hasChanges() ? Status.OUTDATED : Status.UP_TO_DATE;
        if (r.status == Status.UP_TO_DATE && (!state.manifestHash.equals(r.manifestHash) || state.version != r.manifest.version)) {
            // Files already match (e.g. fresh zip install). Record it so the server check is instant.
            state.version = r.manifest.version;
            state.manifestHash = r.manifestHash;
            state.highestSeen = Math.max(state.highestSeen, r.manifest.version);
            state.managed = new ArrayList<>();
            for (Manifest.Entry e : r.manifest.files) state.managed.add(e.path);
            try { state.save(gameDir); } catch (IOException ignored) { }
        }
        return r;
    }

    private void diff(Result r, LocalFiles.State state) throws IOException {
        LocalFiles.HashCache cache = new LocalFiles.HashCache(gameDir);
        LocalFiles.Prefs prefs = LocalFiles.Prefs.load(gameDir);
        Set<String> inPack = new HashSet<>();
        for (Manifest.Entry e : r.manifest.files) {
            inPack.add(PathRules.key(e.path));
            Path target = PathRules.resolveInside(gameDir, e.path);
            if (Files.isDirectory(target)) throw new IOException("A folder is in the way of " + e.path);
            boolean exists = Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS);
            if (e.once && exists) continue;
            if (exists && Files.size(target) == e.size && cache.sha256(e.path, target).equals(e.sha256)) continue;
            r.downloads.add(e);
            r.downloadBytes += e.size;
        }
        Set<String> deleteKeys = new HashSet<>();
        // 1) Files we installed before that the admin removed from the pack.
        for (String old : state.managed) {
            if (!PathRules.isValid(old)) continue;
            String k = PathRules.key(old);
            if (inPack.contains(k)) continue;
            Path p = PathRules.resolveInside(gameDir, old);
            if (Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS) && deleteKeys.add(k)) r.deletions.add(PathRules.normalize(old));
        }
        // 2) Strict mode: unknown jars in mods/ (stale versions, wrong mods) — moved to a backup, never destroyed.
        if (r.manifest.strictMods) {
            Path mods = gameDir.resolve("mods");
            if (Files.isDirectory(mods)) {
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(mods, "*.{jar,JAR,Jar}")) {
                    for (Path p : ds) {
                        if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) continue;
                        String rel = "mods/" + p.getFileName();
                        if (!PathRules.isValid(rel)) continue;
                        String k = PathRules.key(rel);
                        if (inPack.contains(k) || deleteKeys.contains(k)) continue;
                        if (Globs.matchesAny(r.manifest.optionalMods, rel) || Globs.matchesAny(prefs.keepMods, rel)) continue;
                        deleteKeys.add(k);
                        r.deletions.add(rel);
                    }
                }
            }
        }
        cache.save();
    }

    private Manifest.Verified readCached(PublicKey key) {
        Path f = LocalFiles.lastManifest(gameDir);
        try {
            if (Files.isRegularFile(f)) return Manifest.open(Files.readAllBytes(f), key);
        } catch (IOException e) {
            LOG.debug("[PackSync] cached manifest invalid: {}", e.getMessage());
        }
        return null;
    }

    private void saveCached(byte[] envelope) {
        try {
            com.packsync.core.IO.atomicWrite(LocalFiles.lastManifest(gameDir), envelope);
        } catch (IOException ignored) { }
    }
}
