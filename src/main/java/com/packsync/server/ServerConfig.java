package com.packsync.server;

import com.packsync.core.Json;
import com.packsync.core.PackClientConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** config/packsync-server.json */
public final class ServerConfig {
    public String _readme = "PackSync server config. Edit, then run /packsync reload. Guide: packsync/HOW-TO.txt";

    public String packName = "My Server Pack";
    /** The address players type to join, e.g. "play.example.com" or "1.2.3.4:25565". Used for auto-reconnect. */
    public String publicAddress = "";

    public boolean httpEnabled = true;
    public String httpBind = "0.0.0.0";
    public int httpPort = 8100;
    /** Full public URL of the file server. Blank = http://<publicAddress host>:<httpPort> */
    public String publicUrl = "";
    /** Optional extra base URLs that host a copy of packsync/web (CDN, second host...). */
    public List<String> mirrors = new ArrayList<>();
    public int httpMaxConnections = 64;
    public int httpMaxConnectionsPerIp = 16;

    /** Kick players whose pack differs from the published one (they get the update screen). */
    public boolean enforce = true;
    /** Kick players who join without PackSync installed. */
    public boolean requireClientMod = true;
    public int verifyTimeoutSeconds = 30;
    /** Ops with at least this permission level skip the check (-1 = nobody skips). */
    public int bypassPermissionLevel = -1;
    public int commandPermissionLevel = 3;

    public boolean strictMods = true;
    public List<String> optionalClientMods = new ArrayList<>(List.of(
            "*oculus*.jar", "*iris*.jar", "*journeymap*.jar", "*xaero*.jar", "*jei*.jar.disabled"));
    public List<String> installOnce = new ArrayList<>(List.of("options.txt", "servers.dat", "config/**/client-local*"));
    public List<String> ignore = new ArrayList<>(List.of(".DS_Store", "Thumbs.db", "desktop.ini", "*.tmp", "*.bak", "*.log", ".git/**"));

    /** Send the server's own mods/, config/, kubejs/, defaultconfigs/ to players automatically. */
    public boolean syncServerFiles = true;
    /** Server files that must NOT go to players (server-only mods etc). */
    public List<String> serverOnlyFiles = new ArrayList<>(List.of(
            "config/packsync-server.json", "config/**/*server*.toml", "mods/*server-only*"));
    /** Re-publish automatically whenever files change (checked every N seconds, 0 = off). */
    public int autoPublishSeconds = 30;

    public boolean autoPublishOnStart = true;
    public boolean announceUpdates = true;
    public boolean buildZip = true;
    public int keepVersions = 5;

    public String missingModMessage = "This server uses an auto-updating modpack. Download it from: {url}\n"
            + "هذا السيرفر يستخدم مودباك يتحدث تلقائياً. حمّله من: {url}";

    public static ServerConfig load(Path file) throws IOException {
        ServerConfig cfg;
        if (Files.isRegularFile(file)) {
            cfg = Json.read(file, ServerConfig.class);
        } else {
            cfg = new ServerConfig();
        }
        cfg.sanitize();
        Json.write(file, cfg); // writes defaults / newly added keys
        return cfg;
    }

    private void sanitize() {
        if (mirrors == null) mirrors = new ArrayList<>();
        if (optionalClientMods == null) optionalClientMods = new ArrayList<>();
        if (installOnce == null) installOnce = new ArrayList<>();
        if (ignore == null) ignore = new ArrayList<>();
        if (serverOnlyFiles == null) serverOnlyFiles = new ArrayList<>();
        if (packName == null || packName.isBlank()) packName = "Server Pack";
        if (publicAddress == null) publicAddress = "";
        if (publicUrl == null) publicUrl = "";
        httpPort = Math.max(1, Math.min(65535, httpPort));
        httpMaxConnections = Math.max(4, Math.min(1024, httpMaxConnections));
        httpMaxConnectionsPerIp = Math.max(2, Math.min(256, httpMaxConnectionsPerIp));
        verifyTimeoutSeconds = Math.max(5, Math.min(300, verifyTimeoutSeconds));
        keepVersions = Math.max(1, Math.min(100, keepVersions));
        commandPermissionLevel = Math.max(0, Math.min(4, commandPermissionLevel));
    }

    /** Host part of publicAddress (no port). */
    public String publicHost() {
        String a = publicAddress.trim();
        if (a.startsWith("[")) { // IPv6 literal
            int end = a.indexOf(']');
            return end > 0 ? a.substring(0, end + 1) : a;
        }
        int colon = a.lastIndexOf(':');
        return colon > 0 && a.indexOf(':') == colon ? a.substring(0, colon) : a;
    }

    public String effectiveBaseUrl() {
        if (!publicUrl.isBlank()) return PackClientConfig.trimSlash(publicUrl.trim());
        String host = publicHost();
        if (host.isBlank()) return "";
        return "http://" + host + ":" + httpPort;
    }
}
