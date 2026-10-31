package com.packsync.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** The signed description of one published pack version. */
public final class Manifest {
    public static final int FORMAT = 1;
    public static final int MAX_FILES = 20000;

    public int format = FORMAT;
    public String packName = "";
    public int version;
    public String created = "";
    public String contentHash = "";
    public List<String> changelog = new ArrayList<>();
    /** When true, jars in mods/ that are not part of the pack are moved to a backup. */
    public boolean strictMods = true;
    /** Globs of client-only mods players may keep (minimaps, shaders...). */
    public List<String> optionalMods = new ArrayList<>();
    public List<Entry> files = new ArrayList<>();

    public static final class Entry {
        public String path;
        public String sha256;
        public long size;
        /** Install only when missing; never overwrite the player's copy. */
        public boolean once;

        public Entry() {}

        public Entry(String path, String sha256, long size, boolean once) {
            this.path = path;
            this.sha256 = sha256;
            this.size = size;
            this.once = once;
        }
    }

    /** What is actually served: the manifest bytes plus a signature, in one atomic file. */
    public static final class Envelope {
        public int format = FORMAT;
        public String payload;
        public String signature;
    }

    /** A manifest whose signature has been checked. */
    public record Verified(Manifest manifest, String hash, byte[] envelope) {}

    public void sortFiles() {
        files.sort(Comparator.comparing(e -> e.path));
    }

    public String computeContentHash() {
        StringBuilder sb = new StringBuilder();
        List<Entry> sorted = new ArrayList<>(files);
        sorted.sort(Comparator.comparing(e -> e.path));
        for (Entry e : sorted) sb.append(e.path).append('\0').append(e.sha256).append('\0').append(e.once).append('\n');
        sb.append("strict=").append(strictMods).append('\n');
        for (String g : optionalMods) sb.append("opt=").append(g).append('\n');
        return Hashing.sha256(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    public void validate() throws IOException {
        if (format != FORMAT) throw new IOException("Unsupported manifest format " + format + " (update PackSync)");
        if (version <= 0) throw new IOException("Bad manifest version");
        if (files == null || files.size() > MAX_FILES) throw new IOException("Bad file list");
        if (changelog == null) changelog = new ArrayList<>();
        if (optionalMods == null) optionalMods = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Entry e : files) {
            if (e == null) throw new IOException("Null entry");
            try {
                e.path = PathRules.normalize(e.path);
            } catch (IllegalArgumentException ex) {
                throw new IOException(ex.getMessage());
            }
            if (!Hashing.isSha256(e.sha256)) throw new IOException("Bad hash for " + e.path);
            if (e.size < 0 || e.size > 4L * 1024 * 1024 * 1024) throw new IOException("Bad size for " + e.path);
            if (!seen.add(PathRules.key(e.path))) throw new IOException("Duplicate path " + e.path);
        }
    }

    public long totalSize() {
        long t = 0;
        for (Entry e : files) t += e.size;
        return t;
    }

    public byte[] toBytes() {
        return Json.GSON.toJson(this).getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] seal(byte[] payload, PrivateKey key) throws GeneralSecurityException {
        Envelope env = new Envelope();
        env.payload = Base64.getEncoder().encodeToString(payload);
        env.signature = Base64.getEncoder().encodeToString(Signing.sign(key, payload));
        return Json.GSON.toJson(env).getBytes(StandardCharsets.UTF_8);
    }

    /** Verifies the signature and returns the parsed, validated manifest. */
    public static Verified open(byte[] envelopeBytes, PublicKey key) throws IOException {
        Envelope env;
        try {
            env = Json.GSON.fromJson(new String(envelopeBytes, StandardCharsets.UTF_8), Envelope.class);
        } catch (RuntimeException e) {
            throw new IOException("Manifest is not valid JSON");
        }
        if (env == null || env.payload == null || env.signature == null) throw new IOException("Manifest envelope incomplete");
        byte[] payload;
        byte[] sig;
        try {
            payload = Base64.getDecoder().decode(env.payload);
            sig = Base64.getDecoder().decode(env.signature);
        } catch (IllegalArgumentException e) {
            throw new IOException("Manifest encoding broken");
        }
        if (!Signing.verify(key, payload, sig)) throw new IOException("SIGNATURE_INVALID");
        Manifest m;
        try {
            m = Json.GSON.fromJson(new String(payload, StandardCharsets.UTF_8), Manifest.class);
        } catch (RuntimeException e) {
            throw new IOException("Manifest payload is not valid JSON");
        }
        if (m == null) throw new IOException("Empty manifest");
        m.validate();
        return new Verified(m, Hashing.sha256(payload), envelopeBytes);
    }

    /** Reads payload hash + manifest WITHOUT verifying (server reading its own history). */
    public static Manifest openUnchecked(byte[] envelopeBytes) throws IOException {
        Envelope env = Json.GSON.fromJson(new String(envelopeBytes, StandardCharsets.UTF_8), Envelope.class);
        if (env == null || env.payload == null) throw new IOException("bad envelope");
        return Json.GSON.fromJson(new String(Base64.getDecoder().decode(env.payload), StandardCharsets.UTF_8), Manifest.class);
    }
}
