package com.packsync.updater;

import com.packsync.core.Hashing;
import com.packsync.core.IO;
import com.packsync.core.PathRules;

import javax.swing.BorderFactory;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.ComponentOrientation;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Standalone updater (plain Java, no Minecraft classes). Runs from a COPY of the mod jar.
 *
 *  1. waits for the game process to exit (jars are locked while it runs)
 *  2. re-verifies every staged file's SHA-256
 *  3. moves each replaced/removed file into packsync/backups/<time>/ and installs the new one
 *  4. on any error: rolls everything back exactly as it was
 *  5. records the new state and restarts the game (auto-joining the server) when possible
 */
public final class UpdaterMain {
    private static PrintWriter log;
    private static boolean arabic;
    private static Ui ui;

    private UpdaterMain() {}

    record Plan(Path gameDir, int version, String lang, String pack, List<String[]> puts, List<String> dels, Path stateNew, Path manifestNew) {}

    record Done(Path target, Path backup, boolean created) {}

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("usage: UpdaterMain <plan.txt> <game-pid>");
            System.exit(2);
        }
        Path planFile = Path.of(args[0]).toAbsolutePath().normalize();
        long pid;
        try {
            pid = Long.parseLong(args[1]);
        } catch (NumberFormatException e) {
            System.exit(2);
            return;
        }
        List<String> relaunch = new ArrayList<>();
        String workDir = null;
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            String first = r.readLine();
            int n = first == null ? 0 : Integer.parseInt(first.trim());
            for (int i = 0; i < n && i < 4096; i++) {
                String a = r.readLine();
                if (a == null) break;
                relaunch.add(a);
            }
            workDir = r.readLine();
        } catch (IOException | NumberFormatException e) {
            relaunch.clear();
        }

        Plan plan;
        try {
            plan = readPlan(planFile);
        } catch (IOException | RuntimeException e) {
            System.err.println("Invalid plan: " + e);
            System.exit(3);
            return;
        }
        arabic = plan.lang().startsWith("ar");
        Path psDir = plan.gameDir().resolve("packsync");
        try {
            Files.createDirectories(psDir.resolve("logs"));
            log = new PrintWriter(Files.newBufferedWriter(psDir.resolve("logs").resolve("updater.log"), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND), true);
        } catch (IOException e) {
            log = new PrintWriter(System.out, true);
        }
        log("==== PackSync updater: v" + plan.version() + ", " + plan.puts().size() + " files, " + plan.dels().size() + " removals, relaunch args=" + relaunch.size());

        ui = Ui.create(plan.pack());
        int exit = 0;
        try (FileChannel ch = FileChannel.open(psDir.resolve("pending").resolve("updater.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lock = ch.tryLock()) {
            if (lock == null) {
                log("another updater is running; exiting");
                ui.close();
                System.exit(0);
                return;
            }
            ui.status(t("بانتظار إغلاق اللعبة…", "Waiting for the game to close…"), -1);
            waitForGame(pid);
            ui.status(t("جارٍ التحقق من الملفات…", "Verifying files…"), 0);
            apply(plan);
            writeResult(psDir, "OK\t" + plan.version());
            Files.deleteIfExists(planFile);
            cleanup(plan, psDir);
            log("update applied successfully");

            boolean relaunched = false;
            if (!relaunch.isEmpty()) {
                ui.status(t("تم التحديث! جارٍ تشغيل اللعبة…", "Updated! Starting the game…"), 100);
                relaunched = relaunch(relaunch, workDir == null ? plan.gameDir().toString() : workDir);
            }
            if (relaunched) {
                Thread.sleep(2500);
                ui.close();
            } else {
                ui.done(t("تم تثبيت التحديث بنجاح ✔\nشغّل اللعبة من اللانشر وادخل السيرفر.",
                        "Update installed successfully ✔\nStart the game from your launcher and join the server."));
            }
        } catch (Exception e) {
            exit = 1;
            log("FAILED: " + stack(e));
            try {
                writeResult(psDir, "FAIL\t" + describe(e));
                Files.deleteIfExists(planFile); // never loop on a broken plan; staged files are kept for a fast retry
            } catch (IOException ignored) { }
            ui.error(t("تعذّر تثبيت التحديث، وتم إرجاع ملفاتك كما كانت.\nالسبب: ", "The update could not be installed; your files were restored.\nReason: ")
                    + describe(e) + "\n\n" + t("السجل: ", "Log: ") + psDir.resolve("logs").resolve("updater.log"));
        }
        System.exit(exit);
    }

    // ------------------------------------------------------------------ plan

    static Plan readPlan(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (lines.isEmpty() || !lines.get(0).startsWith("PACKSYNC-PLAN\t1")) throw new IOException("bad header");
        if (!lines.get(lines.size() - 1).equals("END")) throw new IOException("truncated plan");
        Path game = null;
        int version = 0;
        String lang = "en", pack = "Modpack";
        List<String[]> puts = new ArrayList<>();
        List<String> dels = new ArrayList<>();
        String state = null, manifest = null;
        for (String line : lines) {
            String[] p = line.split("\t", -1);
            switch (p[0]) {
                case "GAMEDIR" -> game = Path.of(p[1]).toAbsolutePath().normalize();
                case "VERSION" -> version = Integer.parseInt(p[1]);
                case "LANG" -> lang = p[1];
                case "PACK" -> pack = p[1];
                case "PUT" -> {
                    if (p.length != 3 || !Hashing.isSha256(p[1])) throw new IOException("bad PUT line");
                    puts.add(new String[]{p[1], PathRules.normalize(p[2])});
                }
                case "DEL" -> dels.add(PathRules.normalize(p[1]));
                case "STATE" -> state = p[1];
                case "MANIFEST" -> manifest = p[1];
                default -> { }
            }
        }
        if (game == null || version <= 0 || state == null || manifest == null) throw new IOException("incomplete plan");
        if (!file.getParent().equals(game.resolve("packsync").resolve("pending"))) throw new IOException("plan is not inside the game folder");
        Path pending = file.getParent();
        return new Plan(game, version, lang, pack, puts, dels, pending.resolve(state).normalize(), pending.resolve(manifest).normalize());
    }

    // ----------------------------------------------------------------- apply

    private static void waitForGame(long pid) throws InterruptedException {
        Optional<ProcessHandle> h = ProcessHandle.of(pid);
        if (h.isPresent()) {
            long deadline = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(10);
            boolean warned = false;
            while (h.get().isAlive()) {
                if (System.currentTimeMillis() > deadline) throw new InterruptedException("The game did not close within 10 minutes");
                if (!warned && System.currentTimeMillis() > deadline - TimeUnit.MINUTES.toMillis(9)) {
                    ui.status(t("أغلق ماينكرافت لإكمال التحديث…", "Please close Minecraft to finish the update…"), -1);
                    warned = true;
                }
                Thread.sleep(250);
            }
        }
        Thread.sleep(1200); // let the OS release file handles
    }

    private static void apply(Plan plan) throws IOException {
        Path game = plan.gameDir();
        Path staging = game.resolve("packsync").resolve("staging");
        Path backupRoot = game.resolve("packsync").resolve("backups")
                .resolve(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "-v" + plan.version());

        // Verify everything BEFORE touching the game folder.
        int total = plan.puts().size() + plan.dels().size();
        int i = 0;
        for (String[] put : plan.puts()) {
            Path staged = staging.resolve(put[0]);
            if (!Files.isRegularFile(staged)) throw new IOException("Missing downloaded file for " + put[1]);
            if (!Hashing.sha256(staged).equals(put[0])) throw new IOException("Downloaded file is corrupted: " + put[1]);
            ui.status(t("جارٍ التحقق من الملفات…", "Verifying files…"), (int) (++i * 40L / Math.max(1, plan.puts().size())));
        }

        List<Done> done = new ArrayList<>();
        try {
            i = 0;
            for (String rel : plan.dels()) {
                Path target = PathRules.resolveInside(game, rel);
                if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                    Path backup = PathRules.resolveInside(backupRoot, rel);
                    Files.createDirectories(backup.getParent());
                    IO.retry(() -> IO.move(target, backup));
                    done.add(new Done(target, backup, false));
                    log("removed " + rel);
                }
                ui.status(t("جارٍ تثبيت التحديث…", "Installing update…"), 40 + (int) (++i * 60L / Math.max(1, total)));
            }
            for (String[] put : plan.puts()) {
                String rel = put[1];
                Path target = PathRules.resolveInside(game, rel);
                Path staged = staging.resolve(put[0]);
                if (Files.isDirectory(target)) throw new IOException("A folder is in the way of " + rel);
                Files.createDirectories(target.getParent());
                Path backup = null;
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    backup = PathRules.resolveInside(backupRoot, rel);
                    Files.createDirectories(backup.getParent());
                    Path b = backup;
                    IO.retry(() -> IO.move(target, b));
                }
                done.add(new Done(target, backup, true)); // record first: rollback must restore the backup even if the copy fails
                Path tmp = target.resolveSibling(target.getFileName() + ".packsync-new");
                Files.copy(staged, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                IO.retry(() -> IO.move(tmp, target));
                log("installed " + rel);
                ui.status(t("جارٍ تثبيت التحديث…", "Installing update…"), 40 + (int) (++i * 60L / Math.max(1, total)));
            }
            Path ps = game.resolve("packsync");
            IO.retry(() -> IO.move(plan.stateNew(), ps.resolve("state.json")));
            IO.retry(() -> IO.move(plan.manifestNew(), ps.resolve("last-manifest.json")));
        } catch (IOException | RuntimeException e) {
            log("rolling back " + done.size() + " operations");
            rollback(done);
            throw e instanceof IOException io ? io : new IOException(e);
        }
    }

    private static void rollback(List<Done> done) {
        List<Done> rev = new ArrayList<>(done);
        Collections.reverse(rev);
        for (Done d : rev) {
            try {
                if (d.created()) {
                    Files.deleteIfExists(d.target().resolveSibling(d.target().getFileName() + ".packsync-new"));
                    Files.deleteIfExists(d.target());
                }
                if (d.backup() != null && Files.exists(d.backup())) IO.retry(() -> IO.move(d.backup(), d.target()));
            } catch (IOException e) {
                log("ROLLBACK PROBLEM for " + d.target() + ": " + e.getMessage() + " (backup kept at " + d.backup() + ")");
            }
        }
    }

    private static void cleanup(Plan plan, Path psDir) {
        try {
            for (String[] put : plan.puts()) Files.deleteIfExists(psDir.resolve("staging").resolve(put[0]));
        } catch (IOException ignored) { }
        // Keep only the 3 newest backups.
        Path backups = psDir.resolve("backups");
        if (!Files.isDirectory(backups)) return;
        List<Path> dirs = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(backups)) {
            for (Path p : ds) if (Files.isDirectory(p)) dirs.add(p);
        } catch (IOException e) {
            return;
        }
        dirs.sort(null);
        for (int i = 0; i < dirs.size() - 3; i++) deleteTree(dirs.get(i));
    }

    private static void deleteTree(Path root) {
        try (var walk = Files.walk(root)) {
            walk.sorted(Collections.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }

    private static boolean relaunch(List<String> cmd, String workDir) {
        try {
            Path wd = Path.of(workDir);
            Process p = new ProcessBuilder(cmd)
                    .directory(Files.isDirectory(wd) ? wd.toFile() : null)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            p.getOutputStream().close();
            if (p.waitFor(6, TimeUnit.SECONDS)) {
                log("relaunched game exited immediately with code " + p.exitValue());
                return false;
            }
            log("game relaunched (pid " + p.pid() + ")");
            return true;
        } catch (IOException | RuntimeException e) {
            log("relaunch failed: " + e);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void writeResult(Path psDir, String text) throws IOException {
        IO.atomicWrite(psDir.resolve("pending").resolve("result.txt"), text.getBytes(StandardCharsets.UTF_8));
    }

    // -------------------------------------------------------------- helpers

    private static String t(String ar, String en) { return arabic ? ar : en; }

    private static void log(String s) {
        log.println(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + "  " + s);
    }

    private static String oneLine(String s) {
        return s == null ? "unknown error" : s.replace('\n', ' ').replace('\t', ' ');
    }

    private static String describe(Throwable e) {
        String msg = e.getMessage();
        if (e instanceof java.nio.file.FileSystemException fse) {
            String why = fse.getReason() == null ? e.getClass().getSimpleName() : fse.getReason();
            return oneLine(why + ": " + fse.getFile());
        }
        return msg == null || msg.isBlank() ? e.getClass().getSimpleName() : oneLine(msg);
    }

    private static String stack(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    /** Small progress window; silently does nothing on headless systems. */
    static final class Ui {
        private JFrame frame;
        private JLabel label;
        private JProgressBar bar;

        static Ui create(String pack) {
            Ui u = new Ui();
            if (GraphicsEnvironment.isHeadless()) return u;
            try {
                SwingUtilities.invokeAndWait(() -> u.build(pack));
            } catch (Exception e) {
                u.frame = null;
            }
            return u;
        }

        private void build(String pack) {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) { }
            frame = new JFrame(pack + " — " + t("تحديث", "Update"));
            frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
            JPanel root = new JPanel(new BorderLayout(0, 12));
            root.setBorder(BorderFactory.createEmptyBorder(22, 26, 22, 26));
            root.setBackground(new Color(0x16, 0x18, 0x1d));
            JLabel title = new JLabel(pack, SwingConstants.CENTER);
            title.setFont(new Font(Font.DIALOG, Font.BOLD, 18));
            title.setForeground(Color.WHITE);
            label = new JLabel(" ", SwingConstants.CENTER);
            label.setFont(new Font(Font.DIALOG, Font.PLAIN, 14));
            label.setForeground(new Color(0xd0, 0xd4, 0xda));
            bar = new JProgressBar(0, 100);
            bar.setPreferredSize(new Dimension(380, 14));
            bar.setIndeterminate(true);
            root.add(title, BorderLayout.NORTH);
            root.add(label, BorderLayout.CENTER);
            root.add(bar, BorderLayout.SOUTH);
            if (arabic) root.applyComponentOrientation(ComponentOrientation.RIGHT_TO_LEFT);
            frame.setContentPane(root);
            frame.pack();
            frame.setResizable(false);
            frame.setLocationRelativeTo(null);
            frame.setAlwaysOnTop(true);
            frame.setVisible(true);
        }

        void status(String text, int pct) {
            if (frame == null) return;
            SwingUtilities.invokeLater(() -> {
                label.setText(text);
                if (pct < 0) {
                    bar.setIndeterminate(true);
                } else {
                    bar.setIndeterminate(false);
                    bar.setValue(Math.max(0, Math.min(100, pct)));
                }
            });
        }

        void done(String msg) { dialog(msg, JOptionPane.INFORMATION_MESSAGE); }

        void error(String msg) { dialog(msg, JOptionPane.ERROR_MESSAGE); }

        private void dialog(String msg, int type) {
            System.out.println(msg);
            if (frame == null) return;
            try {
                SwingUtilities.invokeAndWait(() -> {
                    frame.setVisible(false);
                    JOptionPane pane = new JOptionPane(msg, type);
                    if (arabic) pane.applyComponentOrientation(ComponentOrientation.RIGHT_TO_LEFT);
                    var d = pane.createDialog(null, frame.getTitle());
                    d.setAlwaysOnTop(true);
                    d.setVisible(true);
                    frame.dispose();
                });
            } catch (Exception ignored) { }
        }

        void close() {
            if (frame == null) return;
            SwingUtilities.invokeLater(() -> frame.dispose());
        }
    }
}
