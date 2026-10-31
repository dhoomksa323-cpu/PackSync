package com.packsync.client;

import com.packsync.PackSync;
import com.packsync.client.core.Installer;
import com.packsync.client.core.LocalFiles;
import com.packsync.client.core.UpdateChecker;
import com.packsync.client.gui.UpdateScreen;
import com.packsync.net.Network;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Client side: decides WHEN to show the update screen. */
public final class ClientEvents {
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "PackSync-Check");
        t.setDaemon(true);
        return t;
    });

    private static volatile UpdateChecker.Result lastResult;
    private static CompletableFuture<UpdateChecker.Result> running;
    private static boolean startupHandled;
    private static boolean promptedThisSession;
    /** Set when the server told us we're outdated — the next disconnect screen becomes the update screen. */
    private static volatile boolean serverSaidOutdated;
    private static volatile String lastServerAddress;

    private ClientEvents() {}

    public static void register(IEventBus bus) {
        bus.addListener(ClientEvents::onScreenOpening);
    }

    public static Path gameDir() {
        return FMLPaths.GAMEDIR.get().toAbsolutePath().normalize();
    }

    public static String lastServerAddress() {
        return lastServerAddress;
    }

    public static synchronized CompletableFuture<UpdateChecker.Result> checkAsync(String expectedHash) {
        if (expectedHash == null && running != null && !running.isDone()) return running;
        CompletableFuture<UpdateChecker.Result> f = CompletableFuture.supplyAsync(() -> new UpdateChecker(gameDir()).check(expectedHash), EXEC);
        f.thenAccept(r -> lastResult = r);
        if (expectedHash == null) running = f;
        return f;
    }

    private static void onScreenOpening(ScreenEvent.Opening event) {
        Screen next = event.getNewScreen();
        Minecraft mc = Minecraft.getInstance();

        if (next instanceof TitleScreen && !startupHandled) {
            startupHandled = true;
            showInstallResult(mc);
            if (Installer.hasPendingPlan(gameDir())) {
                event.setNewScreen(UpdateScreen.resume(next));
                return;
            }
            if (LocalFiles.Prefs.load(gameDir()).checkOnStartup) {
                checkAsync(null).thenAccept(r -> mc.execute(() -> {
                    if (r.status == UpdateChecker.Status.OUTDATED && mc.screen instanceof TitleScreen && !promptedThisSession) {
                        promptedThisSession = true;
                        mc.setScreen(UpdateScreen.forResult(mc.screen, r, false));
                    }
                }));
            }
            return;
        }

        if (next instanceof JoinMultiplayerScreen && !promptedThisSession) {
            UpdateChecker.Result r = lastResult;
            if (r != null && r.status == UpdateChecker.Status.OUTDATED) {
                promptedThisSession = true;
                event.setNewScreen(UpdateScreen.forResult(next, r, false));
            }
            return;
        }

        if (next instanceof DisconnectedScreen) {
            if (serverSaidOutdated) {
                serverSaidOutdated = false;
                event.setNewScreen(UpdateScreen.checking(next, true));
                return;
            }
            // Forge itself may reject an outdated client before PackSync can talk (missing mods):
            // check quietly and replace the error with the update screen if that's the cause.
            if (UpdateChecker.loadClientConfig(gameDir()).isUsable()) {
                checkAsync(null).thenAccept(r -> mc.execute(() -> {
                    if (r.status == UpdateChecker.Status.OUTDATED && mc.screen == next) {
                        mc.setScreen(UpdateScreen.forResult(next, r, true));
                    }
                }));
            }
        }
    }

    private static void showInstallResult(Minecraft mc) {
        String res = Installer.consumeResult(gameDir());
        if (res == null) return;
        String[] p = res.split("\t", 2);
        if (p[0].equals("OK")) {
            SystemToast.add(mc.getToasts(), SystemToast.SystemToastIds.PERIODIC_NOTIFICATION,
                    Component.translatable("packsync.toast.updated.title"),
                    Component.translatable("packsync.toast.updated.body", p.length > 1 ? p[1] : "?"));
        } else {
            SystemToast.add(mc.getToasts(), SystemToast.SystemToastIds.PERIODIC_NOTIFICATION,
                    Component.translatable("packsync.toast.failed.title"),
                    Component.literal(p.length > 1 ? p[1] : ""));
        }
    }

    /** Server asked us to verify our pack. */
    public static void onHello(Network.Hello hello) {
        Minecraft mc = Minecraft.getInstance();
        ServerData sd = mc.getCurrentServer();
        if (sd != null && sd.ip != null && !sd.ip.isBlank()) lastServerAddress = sd.ip;
        String modVersion = ModList.get().getModContainerById(PackSync.MODID)
                .map(c -> c.getModInfo().getVersion().toString()).orElse("?");
        checkAsync(hello.manifestHash()).thenAccept(r -> mc.execute(() -> {
            int status;
            switch (r.status) {
                case UP_TO_DATE -> status = r.manifestHash.equals(hello.manifestHash()) ? Network.Report.OK : Network.Report.OUTDATED;
                case OUTDATED -> status = Network.Report.OUTDATED;
                case NOT_CONFIGURED -> status = Network.Report.NOT_CONFIGURED;
                default -> status = Network.Report.UNKNOWN;
            }
            if (status == Network.Report.OUTDATED) serverSaidOutdated = true;
            if (mc.getConnection() != null) {
                Network.CHANNEL.sendToServer(new Network.Report(status, r.manifestHash == null ? "" : r.manifestHash, modVersion));
            }
        }));
    }

    public static void clearPrompted() {
        promptedThisSession = true;
    }
}
