package com.packsync.server;

import com.packsync.net.Network;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.network.PacketDistributor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Dedicated-server side: file server, publishing, and per-player pack verification. */
public final class ServerHandler {
    private static final Logger LOG = LoggerFactory.getLogger("PackSync");

    static MinecraftServer server;
    static ServerConfig config;
    static Publisher publisher;
    static WebServer web;
    static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "PackSync-Publisher");
        t.setDaemon(true);
        return t;
    });

    /** Players we asked to verify and are waiting on: uuid -> deadline (ms). */
    private static final Map<UUID, Long> PENDING = new ConcurrentHashMap<>();
    /** Players confirmed to match the current pack hash. */
    static final Map<UUID, String> VERIFIED = new ConcurrentHashMap<>();
    /** /packsync forceupdate countdown target (ms), 0 = none. */
    static volatile long forceAt;
    private static int lastAnnouncedSecond = -1;

    private ServerHandler() {}

    public static void register(IEventBus bus) {
        bus.addListener(ServerHandler::onStarting);
        bus.addListener(ServerHandler::onStopping);
        bus.addListener(ServerHandler::onLogin);
        bus.addListener(ServerHandler::onLogout);
        bus.addListener(ServerHandler::onTick);
        bus.addListener(ServerHandler::onCommands);
    }

    static Path root() {
        return FMLPaths.GAMEDIR.get().toAbsolutePath().normalize();
    }

    private static void onStarting(ServerStartingEvent event) {
        server = event.getServer();
        try {
            config = ServerConfig.load(FMLPaths.CONFIGDIR.get().resolve("packsync-server.json"));
            if (config.publicAddress.isBlank() && config.publicUrl.isBlank()) {
                String ip = detectPublicIp();
                if (!ip.isEmpty()) {
                    config.publicAddress = ip + (server.getPort() > 0 && server.getPort() != 25565 ? ":" + server.getPort() : "");
                    LOG.info("[PackSync] publicAddress auto-detected: {}", config.publicAddress);
                }
            }
            publisher = new Publisher(root(), config);
            publisher.init();
        } catch (Exception e) {
            LOG.error("[PackSync] failed to initialise — PackSync is DISABLED for this run", e);
            publisher = null;
            return;
        }
        startWeb();
        if (config.publicAddress.isBlank() && config.publicUrl.isBlank()) {
            LOG.warn("[PackSync] Set \"publicAddress\" in config/packsync-server.json so players can download updates.");
        }
        if (config.autoPublishOnStart) {
            WORKER.execute(() -> {
                try {
                    Publisher.Result r = publisher.publish(List.of("Auto-publish on server start"), false);
                    if (r.published()) LOG.info("[PackSync] Published pack v{} ({} files) on startup", r.version(), r.files());
                    else LOG.info("[PackSync] Pack v{} is up to date", r.version());
                    r.warnings().forEach(w -> LOG.warn("[PackSync] {}", w));
                } catch (Exception e) {
                    LOG.error("[PackSync] auto-publish failed", e);
                }
            });
        }
    }

    static String detectPublicIp() {
        for (String u : List.of("https://api.ipify.org", "https://checkip.amazonaws.com", "https://ifconfig.me/ip")) {
            try {
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(u).openConnection();
                c.setConnectTimeout(4000);
                c.setReadTimeout(4000);
                try (java.io.InputStream in = c.getInputStream()) {
                    String ip = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
                    if (ip.matches("[0-9a-fA-F:.]{3,45}")) return ip;
                }
            } catch (Exception ignored) {}
        }
        return "";
    }

    private static String lastFingerprint;
    private static long nextAutoCheck;

    /** Watches the files and publishes on its own — the admin just edits files. */
    private static void autoPublishTick() {
        if (publisher == null || config.autoPublishSeconds <= 0 || publisher.isBusy()) return;
        long now = System.currentTimeMillis();
        if (now < nextAutoCheck) return;
        nextAutoCheck = now + config.autoPublishSeconds * 1000L;
        WORKER.execute(() -> {
            try {
                String fp = publisher.fingerprint();
                if (fp.equals(lastFingerprint)) return;
                boolean first = lastFingerprint == null;
                lastFingerprint = fp;
                if (first) return; // startup publish already handled
                Publisher.Result r = publisher.publish(List.of("Auto update"), false);
                if (!r.published()) return;
                LOG.info("[PackSync] Auto-published pack v{} ({})", r.version(), r.diff().summary());
                if (config.announceUpdates) server.execute(() -> server.getPlayerList().broadcastSystemMessage(
                        Component.literal("📦 تحديث جديد للمودباك! اطلع وادخل عشان تثبته. / New modpack update — rejoin to install.")
                                .withStyle(ChatFormatting.GOLD), false));
            } catch (Exception e) {
                LOG.error("[PackSync] auto-publish failed", e);
            }
        });
    }

    static void startWeb() {
        stopWeb();
        if (!config.httpEnabled || publisher == null) return;
        try {
            web = new WebServer(publisher.webDir, config.httpBind, config.httpPort, config.httpMaxConnections, config.httpMaxConnectionsPerIp);
            web.start();
        } catch (Exception e) {
            LOG.error("[PackSync] Could not open the file server on port {}: {}. Is the port free/allowed by your host?", config.httpPort, e.getMessage());
            web = null;
        }
    }

    static void stopWeb() {
        if (web != null) {
            web.close();
            web = null;
        }
    }

    private static void onStopping(ServerStoppingEvent event) {
        stopWeb();
        PENDING.clear();
        VERIFIED.clear();
        forceAt = 0;
    }

    static boolean active() {
        return server != null && publisher != null && publisher.current() != null && config.enforce;
    }

    private static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!active()) return;
        if (config.bypassPermissionLevel >= 0 && player.hasPermissions(config.bypassPermissionLevel)) return;
        askToVerify(player);
    }

    static void askToVerify(ServerPlayer player) {
        Publisher.Current cur = publisher.current();
        if (cur == null) return;
        VERIFIED.remove(player.getUUID());
        PENDING.put(player.getUUID(), System.currentTimeMillis() + config.verifyTimeoutSeconds * 1000L);
        Network.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new Network.Hello(cur.hash(), cur.manifest().version, cur.manifest().packName));
    }

    private static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        PENDING.remove(event.getEntity().getUUID());
        VERIFIED.remove(event.getEntity().getUUID());
    }

    public static void onReport(ServerPlayer player, Network.Report report) {
        if (server == null || publisher == null) return; // integrated server / disabled
        Long deadline = PENDING.remove(player.getUUID());
        if (deadline == null) return; // unsolicited
        Publisher.Current cur = publisher.current();
        if (cur == null) return;
        switch (report.status()) {
            case Network.Report.OK -> {
                if (cur.hash().equals(report.manifestHash())) {
                    VERIFIED.put(player.getUUID(), cur.hash());
                } else {
                    kickOutdated(player); // a newer version was published while they were checking
                }
            }
            case Network.Report.OUTDATED -> kickOutdated(player);
            case Network.Report.NOT_CONFIGURED -> {
                LOG.info("[PackSync] {} has PackSync but no pack config", player.getGameProfile().getName());
                player.connection.disconnect(missingModMessage());
            }
            default -> LOG.warn("[PackSync] {} could not verify their pack (update server unreachable from their side?) — allowed in",
                    player.getGameProfile().getName());
        }
    }

    private static void kickOutdated(ServerPlayer player) {
        LOG.info("[PackSync] {} has an outdated pack — sent to the update screen", player.getGameProfile().getName());
        player.connection.disconnect(Component.translatableWithFallback("packsync.kick.outdated",
                "Your modpack is out of date. Install the update, then rejoin.\nالمودباك عندك قديم، ثبّت التحديث وارجع ادخل."));
    }

    static Component missingModMessage() {
        String url = config.effectiveBaseUrl();
        if (url.isEmpty()) url = "(ask the server admin)";
        return Component.literal(config.missingModMessage.replace("{url}", url + "/"));
    }

    private static void onTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || server == null) return;
        autoPublishTick();
        long now = System.currentTimeMillis();
        if (!PENDING.isEmpty()) {
            for (Map.Entry<UUID, Long> e : PENDING.entrySet()) {
                if (now < e.getValue()) continue;
                PENDING.remove(e.getKey());
                ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
                if (p != null && config.requireClientMod) {
                    LOG.info("[PackSync] {} did not answer the pack check (PackSync missing?) — disconnected", p.getGameProfile().getName());
                    p.connection.disconnect(missingModMessage());
                }
            }
        }
        if (forceAt > 0) {
            int left = (int) Math.ceil((forceAt - now) / 1000.0);
            if (left <= 0) {
                forceAt = 0;
                lastAnnouncedSecond = -1;
                for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                    String hash = VERIFIED.get(p.getUUID());
                    if (publisher.current() != null && !publisher.current().hash().equals(hash)) askToVerify(p);
                }
            } else if (left != lastAnnouncedSecond && (left <= 5 || left % 10 == 0)) {
                lastAnnouncedSecond = left;
                server.getPlayerList().broadcastSystemMessage(Component.translatableWithFallback("packsync.announce.countdown",
                        "Modpack update in %s seconds — you will be asked to install it.", left).withStyle(ChatFormatting.GOLD), false);
            }
        }
    }

    private static void onCommands(RegisterCommandsEvent event) {
        PackCommands.register(event.getDispatcher());
    }
}
