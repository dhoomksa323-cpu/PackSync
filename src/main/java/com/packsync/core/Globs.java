package com.packsync.core;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Platform-independent, case-insensitive glob matching on '/' paths.
 * '*' = any chars except '/', '**' = anything, '?' = one char.
 * A pattern without '/' is matched against the file name only.
 */
public final class Globs {
    private static final Map<String, Pattern> CACHE = new ConcurrentHashMap<>();

    private Globs() {}

    public static boolean matchesAny(List<String> globs, String relPath) {
        if (globs == null || globs.isEmpty()) return false;
        String path = relPath.replace('\\', '/').toLowerCase(Locale.ROOT);
        String name = path.substring(path.lastIndexOf('/') + 1);
        for (String g : globs) {
            if (g == null || g.isBlank()) continue;
            String glob = g.trim().replace('\\', '/').toLowerCase(Locale.ROOT);
            String target = glob.indexOf('/') >= 0 ? path : name;
            if (CACHE.computeIfAbsent(glob, Globs::compile).matcher(target).matches()) return true;
        }
        return false;
    }

    private static Pattern compile(String glob) {
        StringBuilder re = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*') {
                if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                    re.append(".*");
                    i++;
                    if (i + 1 < glob.length() && glob.charAt(i + 1) == '/') i++; // "**/" also matches zero dirs
                } else {
                    re.append("[^/]*");
                }
            } else if (c == '?') {
                re.append("[^/]");
            } else {
                re.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(re.toString());
    }
}
