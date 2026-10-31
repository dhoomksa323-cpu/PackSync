package com.packsync.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.packsync.core.IO;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.loading.FMLPaths;

import java.util.ArrayList;
import java.util.List;

/**
 * /packsync status | publish [changelog] | republish [changelog] | preview | forceupdate [sec] | url | reload
 * Changelog lines are separated with " | ".
 */
final class PackCommands {
    private PackCommands() {}

    static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("packsync")
                .requires(s -> s.hasPermission(ServerHandler.config == null ? 3 : ServerHandler.config.commandPermissionLevel))
                .executes(PackCommands::status)
                .then(Commands.literal("status").executes(PackCommands::status))
                .then(Commands.literal("url").executes(PackCommands::url))
                .then(Commands.literal("preview").executes(PackCommands::preview))
                .then(Commands.literal("reload").executes(PackCommands::reload))
                .then(Commands.literal("publish")
                        .executes(c -> publish(c, "", false))
                        .then(Commands.argument("changelog", StringArgumentType.greedyString())
                                .executes(c -> publish(c, StringArgumentType.getString(c, "changelog"), false))))
                .then(Commands.literal("republish")
                        .executes(c -> publish(c, "", true))
                        .then(Commands.argument("changelog", StringArgumentType.greedyString())
                                .executes(c -> publish(c, StringArgumentType.getString(c, "changelog"), true))))
                .then(Commands.literal("forceupdate")
                        .executes(c -> force(c, 30))
                        .then(Commands.argument("seconds", IntegerArgumentType.integer(0, 600))
                                .executes(c -> force(c, IntegerArgumentType.getInteger(c, "seconds"))))));
    }

    private static boolean ready(CommandSourceStack src) {
        if (ServerHandler.publisher == null) {
            src.sendFailure(Component.literal("PackSync is disabled (see server log for the startup error)."));
            return false;
        }
        return true;
    }

    private static void ok(CommandSourceStack src, Component c) {
        src.sendSuccess(() -> c, false);
    }

    private static MutableComponent line(String label, String value) {
        return Component.literal(label + ": ").withStyle(ChatFormatting.GRAY).append(Component.literal(value).withStyle(ChatFormatting.WHITE));
    }

    private static int status(CommandContext<CommandSourceStack> c) {
        CommandSourceStack src = c.getSource();
        if (!ready(src)) return 0;
        Publisher.Current cur = ServerHandler.publisher.current();
        ok(src, Component.literal("━━ PackSync ━━").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
        if (cur == null) {
            ok(src, Component.literal("No version published yet. Put files in packsync/pack/ then /packsync publish").withStyle(ChatFormatting.YELLOW));
        } else {
            ok(src, line("Version", "v" + cur.manifest().version + "  (" + cur.manifest().created + ")"));
            ok(src, line("Files", cur.manifest().files.size() + "  /  " + IO.humanBytes(cur.manifest().totalSize())));
        }
        WebServer w = ServerHandler.web;
        ok(src, line("File server", w != null && w.isRunning() ? "running on port " + w.boundPort() : "OFF"));
        String url = ServerHandler.config.effectiveBaseUrl();
        ok(src, line("Public URL", url.isEmpty() ? "NOT SET (config/packsync-server.json → publicAddress)" : url));
        MinecraftServer server = src.getServer();
        int online = server.getPlayerList().getPlayerCount();
        int verified = 0;
        if (cur != null) for (String h : ServerHandler.VERIFIED.values()) if (h.equals(cur.hash())) verified++;
        ok(src, line("Players up to date", verified + " / " + online));
        if (ServerHandler.publisher.isBusy()) ok(src, Component.literal("A publish is running…").withStyle(ChatFormatting.YELLOW));
        return 1;
    }

    private static int url(CommandContext<CommandSourceStack> c) {
        CommandSourceStack src = c.getSource();
        if (!ready(src)) return 0;
        String url = ServerHandler.config.effectiveBaseUrl();
        if (url.isEmpty()) {
            src.sendFailure(Component.literal("publicAddress / publicUrl is not set in config/packsync-server.json"));
            return 0;
        }
        ok(src, Component.literal("Download page (new players): ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(url + "/").withStyle(Style.EMPTY.withColor(ChatFormatting.GREEN).withUnderlined(true)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url + "/")))));
        return 1;
    }

    private static int reload(CommandContext<CommandSourceStack> c) {
        CommandSourceStack src = c.getSource();
        try {
            ServerHandler.config = ServerConfig.load(FMLPaths.CONFIGDIR.get().resolve("packsync-server.json"));
            if (ServerHandler.publisher != null) ServerHandler.publisher.setConfig(ServerHandler.config);
            ServerHandler.startWeb();
            ok(src, Component.literal("PackSync config reloaded. Run /packsync publish to apply URL/rule changes to players.").withStyle(ChatFormatting.GREEN));
            return 1;
        } catch (Exception e) {
            src.sendFailure(Component.literal("Config error: " + e.getMessage()));
            return 0;
        }
    }

    private static int preview(CommandContext<CommandSourceStack> c) {
        CommandSourceStack src = c.getSource();
        if (!ready(src)) return 0;
        MinecraftServer server = src.getServer();
        ok(src, Component.literal("Scanning packsync/pack/ …").withStyle(ChatFormatting.GRAY));
        ServerHandler.WORKER.execute(() -> {
            List<String> warnings = new ArrayList<>();
            try {
                Publisher.Diff d = ServerHandler.publisher.preview(warnings);
                server.execute(() -> {
                    if (d.isEmpty()) {
                        ok(src, Component.literal("No changes since the last publish.").withStyle(ChatFormatting.GREEN));
                    } else {
                        ok(src, Component.literal("Pending changes " + d.summary()).withStyle(ChatFormatting.AQUA));
                        listSome(src, "+ ", d.added(), ChatFormatting.GREEN);
                        listSome(src, "~ ", d.changed(), ChatFormatting.YELLOW);
                        listSome(src, "- ", d.removed(), ChatFormatting.RED);
                    }
                    warnings.forEach(w -> ok(src, Component.literal("⚠ " + w).withStyle(ChatFormatting.GOLD)));
                });
            } catch (Exception e) {
                server.execute(() -> src.sendFailure(Component.literal("Scan failed: " + e.getMessage())));
            }
        });
        return 1;
    }

    private static void listSome(CommandSourceStack src, String prefix, List<String> items, ChatFormatting color) {
        int shown = 0;
        for (String s : items) {
            if (shown++ >= 15) {
                ok(src, Component.literal("  … +" + (items.size() - 15) + " more").withStyle(ChatFormatting.DARK_GRAY));
                return;
            }
            ok(src, Component.literal("  " + prefix + s).withStyle(color));
        }
    }

    private static int publish(CommandContext<CommandSourceStack> c, String changelog, boolean force) {
        CommandSourceStack src = c.getSource();
        if (!ready(src)) return 0;
        if (ServerHandler.publisher.isBusy()) {
            src.sendFailure(Component.literal("A publish is already running."));
            return 0;
        }
        MinecraftServer server = src.getServer();
        ok(src, Component.literal("Publishing… (hashing files, this can take a moment)").withStyle(ChatFormatting.GRAY));
        ServerHandler.WORKER.execute(() -> {
            try {
                Publisher.Result r = ServerHandler.publisher.publish(changelog.isBlank() ? List.of() : List.of(changelog), force);
                server.execute(() -> {
                    if (!r.published()) {
                        ok(src, Component.literal("Nothing changed — still v" + r.version() + ". (Use /packsync republish to force.)").withStyle(ChatFormatting.YELLOW));
                    } else {
                        ok(src, Component.literal("✔ Published v" + r.version() + " — " + r.files() + " files, " + IO.humanBytes(r.totalBytes())
                                + "  " + r.diff().summary()).withStyle(ChatFormatting.GREEN));
                        ok(src, Component.literal("Online players can be updated now with /packsync forceupdate 30").withStyle(ChatFormatting.GRAY));
                        if (ServerHandler.config.announceUpdates) {
                            server.getPlayerList().broadcastSystemMessage(Component.translatableWithFallback("packsync.announce.published",
                                    "A modpack update (v%s) is available. It will install next time you join.", r.version())
                                    .withStyle(ChatFormatting.AQUA), false);
                        }
                    }
                    r.warnings().forEach(w -> ok(src, Component.literal("⚠ " + w).withStyle(ChatFormatting.GOLD)));
                });
            } catch (Exception e) {
                server.execute(() -> src.sendFailure(Component.literal("Publish failed: " + e.getMessage())));
            }
        });
        return 1;
    }

    private static int force(CommandContext<CommandSourceStack> c, int seconds) {
        CommandSourceStack src = c.getSource();
        if (!ready(src)) return 0;
        if (ServerHandler.publisher.current() == null) {
            src.sendFailure(Component.literal("Publish a version first."));
            return 0;
        }
        ServerHandler.forceAt = System.currentTimeMillis() + seconds * 1000L;
        ok(src, Component.literal("Players without the latest pack will be sent to the update screen in " + seconds + "s.").withStyle(ChatFormatting.GREEN));
        return 1;
    }
}
