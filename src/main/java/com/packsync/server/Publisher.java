package com.packsync.server;

import com.packsync.core.Hashing;
import com.packsync.core.IO;
import com.packsync.core.Json;
import com.packsync.core.Manifest;
import com.packsync.core.PackClientConfig;
import com.packsync.core.PathRules;
import com.packsync.core.Signing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Turns the admin's folder packsync/pack/ into a signed, content-addressed release in
 * packsync/web/ (manifest.json + files/<sha256> + pack.zip + index.html).
 * packsync/web is plain static files, so it can also be mirrored to any web host/CDN.
 */
public final class Publisher {
    private static final Logger LOG = LoggerFactory.getLogger("PackSync");

    public final Path base;
    public final Path packDir;
    public final Path webDir;
    public final Path filesDir;
    public final Path historyDir;
    private final Path keysDir;
    private ServerConfig cfg;
    private KeyPair keys;
    private volatile Current current;
    private final ReentrantLock lock = new ReentrantLock();

    public record Current(Manifest manifest, String hash) {}

    public record PackFile(String path, Path source, String sha256, long size, boolean once) {}

    public record Diff(List<String> added, List<String> changed, List<String> removed) {
        public boolean isEmpty() { return added.isEmpty() && changed.isEmpty() && removed.isEmpty(); }
        public String summary() { return "+" + added.size() + " ~" + changed.size() + " -" + removed.size(); }
    }

    public record Result(boolean published, int version, int files, long totalBytes, Diff diff, List<String> warnings) {}

    public Publisher(Path serverRoot, ServerConfig cfg) {
        this.base = serverRoot.toAbsolutePath().normalize().resolve("packsync");
        this.packDir = base.resolve("pack");
        this.webDir = base.resolve("web");
        this.filesDir = webDir.resolve("files");
        this.historyDir = base.resolve("history");
        this.keysDir = base.resolve("keys");
        this.cfg = cfg;
    }

    public void setConfig(ServerConfig cfg) { this.cfg = cfg; }

    public void init() throws IOException, GeneralSecurityException {
        Files.createDirectories(packDir.resolve("mods"));
        Files.createDirectories(packDir.resolve("config"));
        Files.createDirectories(filesDir);
        Files.createDirectories(historyDir);
        keys = Signing.loadOrCreate(keysDir);
        Path m = webDir.resolve("manifest.json");
        if (Files.isRegularFile(m)) {
            try {
                Manifest.Verified v = Manifest.open(Files.readAllBytes(m), keys.getPublic());
                current = new Current(v.manifest(), v.hash());
            } catch (IOException e) {
                LOG.error("[PackSync] Existing manifest could not be verified ({}); it will be replaced on next publish", e.getMessage());
            }
        }
        writeHowTo();
    }

    public Current current() { return current; }

    public String publicKey() { return Signing.encodePublic(keys.getPublic()); }

    public boolean isBusy() { return lock.isLocked(); }

    // ------------------------------------------------------------------ scan

    public List<PackFile> scan(List<String> warnings) throws IOException {
        Map<String, PackFile> out = new TreeMap<>();
        Set<String> keys = new HashSet<>();
        // 1) packsync/pack/ (client-only extras) wins over everything else
        walk(packDir.toAbsolutePath().normalize(), "", out, keys, warnings);
        // 2) the server's own mods/ and config/ — so the admin never copies anything by hand
        if (cfg.syncServerFiles) {
            Path serverRoot = base.getParent();
            for (String dir : List.of("mods", "config", "kubejs", "defaultconfigs")) {
                Path d = serverRoot.resolve(dir).toAbsolutePath().normalize();
                if (Files.isDirectory(d)) walk(d, dir + "/", out, keys, warnings);
            }
        }
        if (out.size() > Manifest.MAX_FILES) throw new IOException("Too many files in pack (" + out.size() + ")");
        return new ArrayList<>(out.values());
    }

    /** Cheap fingerprint (paths + sizes + mtimes) used to auto-publish when anything changes. */
    public String fingerprint() {
        StringBuilder sb = new StringBuilder();
        List<Path> roots = new ArrayList<>(List.of(packDir));
        if (cfg.syncServerFiles) for (String dir : List.of("mods", "config", "kubejs", "defaultconfigs")) roots.add(base.getParent().resolve(dir));
        for (Path r : roots) {
            if (!Files.isDirectory(r)) continue;
            try (java.util.stream.Stream<Path> st = Files.walk(r)) {
                st.filter(Files::isRegularFile).sorted().forEach(f -> {
                    try { sb.append(f).append(Files.size(f)).append(Files.getLastModifiedTime(f).toMillis()); }
                    catch (IOException ignored) {}
                });
            } catch (IOException ignored) {}
        }
        return Integer.toHexString(sb.toString().hashCode()) + ":" + sb.length();
    }

    private void walk(Path root, String prefix, Map<String, PackFile> out, Set<String> keys, List<String> warnings) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(root) && attrs.isSymbolicLink()) return FileVisitResult.SKIP_SUBTREE;
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!attrs.isRegularFile() || attrs.isSymbolicLink()) return FileVisitResult.CONTINUE;
                String rel = prefix + root.relativize(file).toString().replace('\\', '/');
                if (com.packsync.core.Globs.matchesAny(cfg.ignore, rel)) return FileVisitResult.CONTINUE;
                if (!prefix.isEmpty() && com.packsync.core.Globs.matchesAny(cfg.serverOnlyFiles, rel)) return FileVisitResult.CONTINUE;
                if (rel.equalsIgnoreCase(PackClientConfig.PATH)) return FileVisitResult.CONTINUE;
                if (!PathRules.isValid(rel)) {
                    warnings.add("Skipped unsafe/unsupported path: " + rel);
                    return FileVisitResult.CONTINUE;
                }
                if (!keys.add(PathRules.key(rel))) return FileVisitResult.CONTINUE; // already provided by packsync/pack
                if (prefix.equals("mods/") && isServerOnlyMod(file)) return FileVisitResult.CONTINUE;
                boolean once = com.packsync.core.Globs.matchesAny(cfg.installOnce, rel);
                out.put(rel, new PackFile(rel, file, Hashing.sha256(file), attrs.size(), once));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                warnings.add("Could not read " + file.getFileName() + ": " + exc.getMessage());
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** A mod that declares displayTest IGNORE_ALL_VERSION is server-only: players don't get it. */
    static boolean isServerOnlyMod(Path jar) {
        if (!jar.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".jar")) return false;
        try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(jar.toFile())) {
            java.util.zip.ZipEntry e = z.getEntry("META-INF/mods.toml");
            if (e == null) return false;
            String toml = new String(z.getInputStream(e).readAllBytes(), StandardCharsets.UTF_8).replace(" ", "");
            return toml.contains("displayTest=\"IGNORE_ALL_VERSION\"");
        } catch (Exception ex) {
            return false;
        }
    }

    private byte[] clientConfigBytes() {
        PackClientConfig c = new PackClientConfig();
        c.packName = cfg.packName;
        String baseUrl = cfg.effectiveBaseUrl();
        c.manifestUrl = baseUrl.isEmpty() ? "" : baseUrl + "/manifest.json";
        c.mirrors = new ArrayList<>(cfg.mirrors);
        c.publicKey = publicKey();
        c.serverAddress = cfg.publicAddress.trim();
        return Json.GSON.toJson(c).getBytes(StandardCharsets.UTF_8);
    }

    private Manifest buildManifest(List<PackFile> files, Entryish generated) {
        Manifest m = new Manifest();
        m.packName = cfg.packName;
        m.strictMods = cfg.strictMods;
        m.optionalMods = new ArrayList<>(cfg.optionalClientMods);
        for (PackFile f : files) m.files.add(new Manifest.Entry(f.path(), f.sha256(), f.size(), f.once()));
        m.files.add(new Manifest.Entry(PackClientConfig.PATH, generated.sha, generated.size, false));
        m.sortFiles();
        m.contentHash = m.computeContentHash();
        return m;
    }

    private record Entryish(String sha, long size) {}

    public Diff diffAgainstCurrent(Manifest next) {
        Map<String, String> old = new HashMap<>();
        if (current != null) for (Manifest.Entry e : current.manifest().files) old.put(e.path, e.sha256);
        List<String> added = new ArrayList<>(), changed = new ArrayList<>(), removed = new ArrayList<>();
        Set<String> now = new HashSet<>();
        for (Manifest.Entry e : next.files) {
            now.add(e.path);
            String prev = old.get(e.path);
            if (prev == null) added.add(e.path);
            else if (!prev.equals(e.sha256)) changed.add(e.path);
        }
        for (String p : old.keySet()) if (!now.contains(p)) removed.add(p);
        added.sort(null); changed.sort(null); removed.sort(null);
        return new Diff(added, changed, removed);
    }

    /** Dry run: what would change if published now. */
    public Diff preview(List<String> warnings) throws IOException {
        List<PackFile> files = scan(warnings);
        byte[] cc = clientConfigBytes();
        return diffAgainstCurrent(buildManifest(files, new Entryish(Hashing.sha256(cc), cc.length)));
    }

    // --------------------------------------------------------------- publish

    public Result publish(List<String> changelog, boolean force) throws IOException, GeneralSecurityException {
        if (!lock.tryLock()) throw new IllegalStateException("A publish is already running");
        try {
            List<String> warnings = new ArrayList<>();
            if (cfg.effectiveBaseUrl().isEmpty()) {
                warnings.add("publicAddress/publicUrl is not set in config/packsync-server.json — players cannot download updates until you set it");
            }
            List<PackFile> files = scan(warnings);
            byte[] cc = clientConfigBytes();
            String ccSha = Hashing.sha256(cc);
            Manifest next = buildManifest(files, new Entryish(ccSha, cc.length));
            Diff diff = diffAgainstCurrent(next);
            if (!force && current != null && next.contentHash.equals(current.manifest().contentHash)) {
                return new Result(false, current.manifest().version, current.manifest().files.size(), current.manifest().totalSize(), diff, warnings);
            }

            // Store content-addressed copies first, so the manifest never points at a missing file.
            for (PackFile f : files) store(f.source(), f.sha256());
            Path ccStore = filesDir.resolve(ccSha);
            if (!Files.exists(ccStore)) IO.atomicWrite(ccStore, cc);
            IO.atomicWrite(webDir.resolve("packsync-client.json"), cc);

            next.version = current == null ? 1 : current.manifest().version + 1;
            next.created = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
            next.changelog = cleanChangelog(changelog);
            byte[] payload = next.toBytes();
            byte[] envelope = Manifest.seal(payload, keys.getPrivate());

            IO.atomicWrite(historyDir.resolve(String.format("manifest-v%06d.json", next.version)), envelope);
            IO.atomicWrite(webDir.resolve("manifest.json"), envelope);
            current = new Current(next, Hashing.sha256(payload));

            try {
                if (cfg.buildZip) buildZip(next);
                writeIndex(next);
                collectGarbage();
            } catch (IOException e) {
                warnings.add("Post-publish step failed: " + e.getMessage());
                LOG.warn("[PackSync] post-publish step failed", e);
            }
            return new Result(true, next.version, next.files.size(), next.totalSize(), diff, warnings);
        } finally {
            lock.unlock();
        }
    }

    private static List<String> cleanChangelog(List<String> in) {
        List<String> out = new ArrayList<>();
        if (in != null) {
            for (String s : in) {
                if (s == null) continue;
                for (String line : s.split("\\s*(\\||\\n)\\s*")) {
                    String t = line.strip();
                    if (t.isEmpty()) continue;
                    if (t.length() > 200) t = t.substring(0, 200);
                    out.add(t);
                    if (out.size() >= 50) return out;
                }
            }
        }
        return out;
    }

    private void store(Path source, String expectedSha) throws IOException {
        Path target = filesDir.resolve(expectedSha);
        if (Files.isRegularFile(target)) return;
        Path tmp = filesDir.resolve(expectedSha + ".part-" + System.nanoTime());
        try {
            Files.copy(source, tmp);
            String actual = Hashing.sha256(tmp);
            if (!actual.equals(expectedSha)) {
                throw new IOException("File changed while publishing: " + packDir.relativize(source) + " — run publish again");
            }
            IO.move(tmp, target);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private void buildZip(Manifest m) throws IOException {
        Path tmp = webDir.resolve("pack.zip.part");
        try (OutputStream raw = new BufferedOutputStream(Files.newOutputStream(tmp), 1 << 16);
             ZipOutputStream zip = new ZipOutputStream(raw, StandardCharsets.UTF_8)) {
            zip.setLevel(Deflater.BEST_SPEED);
            for (Manifest.Entry e : m.files) {
                zip.putNextEntry(new ZipEntry(e.path));
                Files.copy(filesDir.resolve(e.sha256), zip);
                zip.closeEntry();
            }
            zip.putNextEntry(new ZipEntry("PACKSYNC-README.txt"));
            zip.write(("Pack: " + m.packName + "  (version " + m.version + ")\r\n\r\n"
                    + "EN: Extract everything into your Minecraft instance folder (the one that has mods/ and config/),\r\n"
                    + "    using Forge 1.20.1. After that, updates install automatically when you join.\r\n\r\n"
                    + "AR: فك الضغط داخل مجلد نسخة ماينكرافت (اللي فيه mods و config) على فورج 1.20.1.\r\n"
                    + "    بعدها كل التحديثات تنزل تلقائياً لما تدخل السيرفر.\r\n").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        IO.move(tmp, webDir.resolve("pack.zip"));
    }

    private void collectGarbage() throws IOException {
        List<Path> history = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(historyDir, "manifest-v*.json")) {
            for (Path p : ds) history.add(p);
        }
        history.sort(null); // zero-padded names sort by version
        Set<String> keep = new HashSet<>();
        int from = Math.max(0, history.size() - cfg.keepVersions);
        for (int i = from; i < history.size(); i++) {
            try {
                for (Manifest.Entry e : Manifest.openUnchecked(Files.readAllBytes(history.get(i))).files) keep.add(e.sha256);
            } catch (IOException | RuntimeException e) {
                LOG.warn("[PackSync] unreadable history file {}, keeping all files this round", history.get(i).getFileName());
                return;
            }
        }
        if (current != null) for (Manifest.Entry e : current.manifest().files) keep.add(e.sha256);
        long cutoff = System.currentTimeMillis() - 3600_000L;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(filesDir)) {
            for (Path p : ds) {
                String name = p.getFileName().toString();
                if (Hashing.isSha256(name)) {
                    if (!keep.contains(name)) Files.deleteIfExists(p);
                } else if (Files.getLastModifiedTime(p).toMillis() < cutoff) {
                    Files.deleteIfExists(p); // stale partial copies
                }
            }
        }
        for (int i = 0; i < history.size() - 200; i++) Files.deleteIfExists(history.get(i));
    }

    private void writeIndex(Manifest m) throws IOException {
        StringBuilder log = new StringBuilder();
        for (String line : m.changelog) log.append("<li>").append(html(line)).append("</li>");
        if (log.length() == 0) log.append("<li>—</li>");
        String page = """
                <!doctype html><html lang="ar" dir="rtl"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1"><title>%1$s</title>
                <style>
                body{margin:0;font-family:system-ui,Segoe UI,Tahoma,sans-serif;background:#0f1115;color:#e8e8ea}
                main{max-width:640px;margin:0 auto;padding:40px 16px}
                h1{margin:0 0 4px;font-size:28px}.muted{color:#9aa0a6}
                .card{background:#171a21;border:1px solid #262b36;border-radius:12px;padding:20px;margin-top:20px}
                a.btn{display:inline-block;background:#3ba55d;color:#fff;text-decoration:none;padding:12px 22px;border-radius:8px;font-weight:600}
                code{background:#0b0d11;padding:2px 6px;border-radius:4px}
                </style></head><body><main>
                <h1>%1$s</h1><div class="muted">الإصدار %2$d · %3$s · %4$d ملف · %5$s</div>
                <div class="card"><a class="btn" href="pack.zip">تحميل المودباك كامل (pack.zip)</a>
                <p>فك الضغط داخل مجلد نسخة ماينكرافت (Forge 1.20.1). بعدها التحديثات تنزل تلقائياً عند الدخول للسيرفر.</p>
                <p class="muted" dir="ltr">Extract into your Forge 1.20.1 instance folder. Updates then install automatically when you join.</p></div>
                <div class="card"><b>آخر التغييرات</b><ul>%6$s</ul></div>
                </main></body></html>
                """.formatted(html(m.packName), m.version, html(m.created), m.files.size(), IO.humanBytes(m.totalSize()), log);
        IO.atomicWrite(webDir.resolve("index.html"), page.getBytes(StandardCharsets.UTF_8));
    }

    private static String html(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private void writeHowTo() throws IOException {
        Path howTo = base.resolve("HOW-TO.txt");
        String text = """
                PackSync — how to update your modpack
                ======================================
                1) Put the CLIENT files of your modpack inside:  packsync/pack/
                     packsync/pack/mods/        <- client mods (jar)
                     packsync/pack/config/      <- client configs
                     (any other folder works too: resourcepacks/, shaderpacks/, kubejs/, defaultconfigs/ ...)
                2) In game (op) or console:   /packsync publish <what changed>
                     e.g.  /packsync publish Fixed crash | Added Create 0.5.1
                3) Players with an old pack get the "Install update" screen when they join.
                   To push it to players who are online now:  /packsync forceupdate 30

                Other commands: /packsync status, /packsync preview, /packsync reload, /packsync url
                Settings: config/packsync-server.json   (set publicAddress first!)
                Open TCP port httpPort (default 8100) on your host/firewall.
                Give NEW players the full pack: http://<your-address>:8100/  (or packsync/web/pack.zip)
                NEVER share packsync/keys/private.key.

                ----------------------------------------------------------------
                PackSync — طريقة تحديث المودباك
                1) حط ملفات اللاعبين (الكلاينت) داخل المجلد  packsync/pack/  (mods و config وغيرها)
                2) اكتب:  /packsync publish وصف التحديث
                3) أي لاعب نسخته قديمة يطلع له زر "تثبيت التحديث" أول ما يدخل.
                   ولو تبي تحدّث اللاعبين المتصلين الحين:  /packsync forceupdate 30
                الإعدادات: config/packsync-server.json  (لا تنسى publicAddress وفتح البورت 8100)
                لا تشارك الملف packsync/keys/private.key أبداً.
                """;
        IO.atomicWrite(howTo, text.getBytes(StandardCharsets.UTF_8));
    }
}
