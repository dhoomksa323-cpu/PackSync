package com.packsync.core;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 * Validates every relative path that travels between server and client.
 * Nothing in a manifest can ever point outside the game folder, into PackSync's own
 * folder, or at a name Windows can't create.
 */
public final class PathRules {
    private static final Set<String> BLOCKED_ROOTS = Set.of("packsync", "saves", "logs", "crash-reports", "screenshots", "backups");
    private static final Set<String> WINDOWS_RESERVED = Set.of("con", "prn", "aux", "nul",
            "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
            "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9");

    private PathRules() {}

    /** Returns the normalized path ("mods/x.jar") or throws if it is unsafe. */
    public static String normalize(String raw) {
        if (raw == null) throw new IllegalArgumentException("null path");
        String p = raw.replace('\\', '/');
        if (p.isEmpty() || p.length() > 400) throw bad(raw, "length");
        if (p.startsWith("/")) throw bad(raw, "absolute");
        if (p.indexOf(':') >= 0) throw bad(raw, "colon");
        String[] parts = p.split("/", -1);
        if (parts.length > 16) throw bad(raw, "too deep");
        for (String s : parts) {
            if (s.isEmpty() || s.equals(".") || s.equals("..")) throw bad(raw, "segment");
            if (s.endsWith(".") || s.endsWith(" ") || s.startsWith(" ")) throw bad(raw, "windows name");
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c < 32 || c == 127 || "<>\"|?*".indexOf(c) >= 0) throw bad(raw, "character");
            }
            String base = s.toLowerCase(Locale.ROOT);
            int dot = base.indexOf('.');
            if (dot >= 0) base = base.substring(0, dot);
            if (WINDOWS_RESERVED.contains(base)) throw bad(raw, "reserved name");
        }
        if (BLOCKED_ROOTS.contains(parts[0].toLowerCase(Locale.ROOT))) throw bad(raw, "protected folder");
        return p;
    }

    public static boolean isValid(String raw) {
        try {
            normalize(raw);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Resolves a validated relative path and double-checks it stays inside root. */
    public static Path resolveInside(Path root, String rel) {
        Path base = root.toAbsolutePath().normalize();
        Path out = base.resolve(normalize(rel)).normalize();
        if (!out.startsWith(base) || out.equals(base)) throw bad(rel, "escapes root");
        return out;
    }

    public static String key(String rel) {
        return rel.toLowerCase(Locale.ROOT);
    }

    private static IllegalArgumentException bad(String p, String why) {
        return new IllegalArgumentException("Unsafe path '" + p + "' (" + why + ")");
    }
}
