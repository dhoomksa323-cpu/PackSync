package com.packsync.client.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Rebuilds the exact command line this game was started with, so the updater can start it
 * again after installing — and adds --quickPlayMultiplayer so it joins the server directly.
 *
 * Works for launchers that pass the session on the command line (official launcher,
 * CurseForge, ATLauncher, MultiMC-style with direct launch, TLauncher...). Launchers that
 * feed arguments through stdin (Prism/MultiMC "EntryPoint") are detected and skipped: the
 * player then simply presses Play, and everything is already installed.
 */
public final class Relauncher {
    private static final Logger LOG = LoggerFactory.getLogger("PackSync");

    private Relauncher() {}

    public static List<String> build(String quickPlayAddress) {
        try {
            List<String> cmd = currentCommand();
            if (cmd == null || cmd.size() < 3) return null;
            if (!cmd.contains("--accessToken") || !cmd.contains("--version")) {
                LOG.info("[PackSync] Launcher does not expose game arguments; auto-restart disabled for this launch");
                return null;
            }
            List<String> out = new ArrayList<>(cmd.size() + 2);
            for (int i = 0; i < cmd.size(); i++) {
                String a = cmd.get(i);
                if (a.startsWith("--quickPlay")) { // drop old quick play target + its value
                    if (i + 1 < cmd.size() && !cmd.get(i + 1).startsWith("--")) i++;
                    continue;
                }
                out.add(a);
            }
            if (quickPlayAddress != null && !quickPlayAddress.isBlank()) {
                out.add("--quickPlayMultiplayer");
                out.add(quickPlayAddress.trim());
            }
            return out;
        } catch (RuntimeException e) {
            LOG.warn("[PackSync] could not rebuild launch command: {}", e.toString());
            return null;
        }
    }

    private static List<String> currentCommand() {
        ProcessHandle.Info info = ProcessHandle.current().info();
        Optional<String> exe = info.command();
        Optional<String[]> args = info.arguments();
        if (exe.isPresent() && args.isPresent() && args.get().length > 0) {
            List<String> out = new ArrayList<>();
            out.add(exe.get());
            out.addAll(List.of(args.get()));
            return out;
        }
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            String line = windowsCommandLine(ProcessHandle.current().pid());
            if (line == null || line.isBlank()) return null;
            List<String> parsed = parseWindows(line);
            if (parsed.isEmpty()) return null;
            if (exe.isPresent()) parsed.set(0, exe.get()); // full path is more reliable than argv[0]
            return parsed;
        }
        return null;
    }

    private static String windowsCommandLine(long pid) {
        String script = "[Console]::OutputEncoding=[System.Text.Encoding]::UTF8;"
                + "(Get-CimInstance Win32_Process -Filter 'ProcessId=" + pid + "').CommandLine";
        try {
            Process p = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", script)
                    .redirectErrorStream(false)
                    .start();
            p.getOutputStream().close();
            byte[] out;
            try (InputStream in = p.getInputStream()) {
                out = in.readAllBytes();
            }
            if (!p.waitFor(15, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            String s = new String(out, StandardCharsets.UTF_8).strip();
            if (!s.isEmpty() && s.charAt(0) == '﻿') s = s.substring(1);
            return s;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** Same splitting rules as Windows' CommandLineToArgvW / the MSVC runtime. */
    public static List<String> parseWindows(String line) {
        List<String> args = new ArrayList<>();
        int i = 0, n = line.length();
        while (i < n) {
            while (i < n && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) i++;
            if (i >= n) break;
            StringBuilder sb = new StringBuilder();
            boolean quoted = false;
            while (i < n) {
                char c = line.charAt(i);
                if (c == '\\') {
                    int start = i;
                    while (i < n && line.charAt(i) == '\\') i++;
                    int count = i - start;
                    if (i < n && line.charAt(i) == '"') {
                        sb.append("\\".repeat(count / 2));
                        if (count % 2 == 1) {
                            sb.append('"');
                            i++;
                        }
                    } else {
                        sb.append("\\".repeat(count));
                    }
                } else if (c == '"') {
                    if (quoted && i + 1 < n && line.charAt(i + 1) == '"') {
                        sb.append('"');
                        i += 2;
                    } else {
                        quoted = !quoted;
                        i++;
                    }
                } else if ((c == ' ' || c == '\t') && !quoted) {
                    break;
                } else {
                    sb.append(c);
                    i++;
                }
            }
            args.add(sb.toString());
        }
        return args;
    }
}
