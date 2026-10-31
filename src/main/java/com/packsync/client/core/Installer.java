package com.packsync.client.core;

import com.packsync.core.IO;
import com.packsync.core.Json;
import com.packsync.core.Manifest;
import com.packsync.core.PathRules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Hands the verified, already-downloaded update to the standalone updater process.
 * The game must be closed for the swap because Windows locks loaded mod jars; the updater
 * waits for this process to exit, swaps files with full backup + rollback, then restarts the game.
 */
public final class Installer {
    private static final Logger LOG = LoggerFactory.getLogger("PackSync");
    public static final String UPDATER_MAIN = "com.packsync.updater.UpdaterMain";

    private Installer() {}

    /** Writes pending/plan.txt plus the new state/manifest the updater will move into place. */
    public static Path writePlan(Path gameDir, UpdateChecker.Result r, Downloader downloader, String lang) throws IOException {
        Path pending = LocalFiles.pending(gameDir);
        Files.createDirectories(pending);
        Files.deleteIfExists(LocalFiles.resultFile(gameDir));

        LocalFiles.State old = LocalFiles.State.load(gameDir);
        LocalFiles.State next = new LocalFiles.State();
        next.version = r.manifest.version;
        next.highestSeen = Math.max(old.highestSeen, r.manifest.version);
        next.manifestHash = r.manifestHash;
        next.updated = Instant.now().toString();
        next.managed = new ArrayList<>();
        for (Manifest.Entry e : r.manifest.files) next.managed.add(e.path);
        Path stateNew = pending.resolve("state.new.json");
        Json.write(stateNew, next);
        Path manifestNew = pending.resolve("manifest.new.json");
        IO.atomicWrite(manifestNew, r.envelope);

        StringBuilder sb = new StringBuilder();
        sb.append("PACKSYNC-PLAN\t1\n");
        sb.append("GAMEDIR\t").append(gameDir.toAbsolutePath().normalize()).append('\n');
        sb.append("VERSION\t").append(r.manifest.version).append('\n');
        sb.append("LANG\t").append(lang == null ? "en" : lang.toLowerCase(Locale.ROOT)).append('\n');
        sb.append("PACK\t").append(r.manifest.packName.replace('\t', ' ').replace('\n', ' ')).append('\n');
        for (Manifest.Entry e : r.downloads) {
            Path staged = downloader.stagedFile(e.sha256);
            if (!Files.isRegularFile(staged)) throw new IOException("Missing staged file for " + e.path);
            sb.append("PUT\t").append(e.sha256).append('\t').append(PathRules.normalize(e.path)).append('\n');
        }
        for (String d : r.deletions) sb.append("DEL\t").append(PathRules.normalize(d)).append('\n');
        sb.append("STATE\tstate.new.json\n");
        sb.append("MANIFEST\tmanifest.new.json\n");
        sb.append("END\n");
        Path plan = LocalFiles.planFile(gameDir);
        IO.atomicWrite(plan, sb.toString().getBytes(StandardCharsets.UTF_8));
        return plan;
    }

    public static boolean hasPendingPlan(Path gameDir) {
        return Files.isRegularFile(LocalFiles.planFile(gameDir));
    }

    public static void discardPendingPlan(Path gameDir) {
        try {
            Files.deleteIfExists(LocalFiles.planFile(gameDir));
        } catch (IOException ignored) { }
    }

    /** Reads + deletes the updater's result marker. Returns e.g. "OK\t12" / "FAIL\tmessage", or null. */
    public static String consumeResult(Path gameDir) {
        Path f = LocalFiles.resultFile(gameDir);
        try {
            if (!Files.isRegularFile(f)) return null;
            String s = Files.readString(f, StandardCharsets.UTF_8).strip();
            Files.deleteIfExists(f);
            return s;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Starts the updater (a copy of this mod jar, so it can replace the mod itself).
     * The relaunch command (contains the session token) goes through stdin only — never to disk.
     */
    public static void spawnUpdater(Path gameDir, Path selfJar, List<String> relaunch) throws IOException {
        Path plan = LocalFiles.planFile(gameDir);
        if (!Files.isRegularFile(plan)) throw new IOException("No pending update");
        Path updaterDir = LocalFiles.dir(gameDir).resolve("updater");
        Files.createDirectories(updaterDir);
        Path jar = updaterDir.resolve("packsync-updater.jar");
        Path tmp = updaterDir.resolve("packsync-updater.jar.tmp");
        Files.copy(selfJar, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        IO.retry(() -> IO.move(tmp, jar));

        Path logs = LocalFiles.dir(gameDir).resolve("logs");
        Files.createDirectories(logs);

        List<String> cmd = new ArrayList<>();
        cmd.add(javaExecutable());
        cmd.add("-Xmx256m");
        cmd.add("-Dfile.encoding=UTF-8");
        cmd.add("-Dsun.jnu.encoding=UTF-8");
        cmd.add("-cp");
        cmd.add(jar.toString());
        cmd.add(UPDATER_MAIN);
        cmd.add(plan.toAbsolutePath().toString());
        cmd.add(Long.toString(ProcessHandle.current().pid()));

        ProcessBuilder pb = new ProcessBuilder(cmd)
                .directory(gameDir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(logs.resolve("updater-console.log").toFile()));
        Process p = pb.start();
        try (OutputStream os = p.getOutputStream(); PrintWriter w = new PrintWriter(os, true, StandardCharsets.UTF_8)) {
            List<String> args = relaunch == null ? List.of() : relaunch;
            w.println(args.size());
            for (String a : args) w.println(a.replace("\r", "").replace("\n", " "));
            w.println(System.getProperty("user.dir", gameDir.toString()));
        }
        try {
            Thread.sleep(600);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!p.isAlive() && p.exitValue() != 0) {
            throw new IOException("Updater exited immediately (code " + p.exitValue() + "), see packsync/logs/updater-console.log");
        }
        LOG.info("[PackSync] Updater started (pid {}), relaunch={}", p.pid(), relaunch != null);
    }

    private static String javaExecutable() {
        Path bin = Path.of(System.getProperty("java.home"), "bin");
        boolean win = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        if (win) {
            Path javaw = bin.resolve("javaw.exe"); // no console window
            if (Files.isRegularFile(javaw)) return javaw.toString();
            return bin.resolve("java.exe").toString();
        }
        return bin.resolve("java").toString();
    }
}
