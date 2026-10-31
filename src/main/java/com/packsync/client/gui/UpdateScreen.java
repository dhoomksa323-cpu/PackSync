package com.packsync.client.gui;

import com.packsync.PackSync;
import com.packsync.client.ClientEvents;
import com.packsync.client.core.Downloader;
import com.packsync.client.core.Installer;
import com.packsync.client.core.LocalFiles;
import com.packsync.client.core.Relauncher;
import com.packsync.client.core.UpdateChecker;
import com.packsync.core.IO;
import com.packsync.core.Manifest;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraftforge.fml.ModList;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/** The "update available → download → install → restart" screen. */
public final class UpdateScreen extends Screen {
    private enum State { CHECKING, AVAILABLE, UP_TO_DATE, DOWNLOADING, INSTALLING, ERROR, RESUME, NOT_CONFIGURED }

    private static final int C_BORDER = 0xFF2E3440;
    private static final int C_PANEL = 0xF20E1116;
    private static final int C_TEXT = 0xFFE6E8EB;
    private static final int C_MUTED = 0xFF9AA3AE;
    private static final int C_GREEN = 0xFF3BA55D;
    private static final int C_GREEN_DARK = 0xFF1F6B3D;
    private static final int C_RED = 0xFF8E2F35;
    private static final int C_RED_DARK = 0xFF5A1D22;
    private static final int C_BLUE = 0xFF2E5F9E;
    private static final int C_BLUE_DARK = 0xFF1E3D66;

    private final Screen parent;
    private final boolean mandatory;
    private State state;
    private UpdateChecker.Result result;
    private Component errorText = Component.empty();
    private Downloader downloader;
    private Downloader.Progress progress;
    private final AtomicBoolean cancel = new AtomicBoolean();
    private volatile boolean updaterStarted;
    private int installTicks;
    private int ticks;
    private double scroll;
    private int contentHeight;
    private boolean checkStarted;
    private final List<FormattedCharSequence> body = new ArrayList<>();

    private UpdateScreen(Screen parent, boolean mandatory, State state, UpdateChecker.Result result) {
        super(Component.translatable("packsync.screen.title"));
        this.parent = parent;
        this.mandatory = mandatory;
        this.state = state;
        this.result = result;
    }

    public static UpdateScreen forResult(Screen parent, UpdateChecker.Result r, boolean mandatory) {
        UpdateScreen s = new UpdateScreen(parent, mandatory, State.AVAILABLE, r);
        s.applyResult(r);
        return s;
    }

    public static UpdateScreen checking(Screen parent, boolean mandatory) {
        return new UpdateScreen(parent, mandatory, State.CHECKING, null);
    }

    public static UpdateScreen resume(Screen parent) {
        return new UpdateScreen(parent, false, State.RESUME, null);
    }

    // ------------------------------------------------------------- layout

    private int pw() { return Math.min(460, width - 24); }
    private int ph() { return Math.min(280, height - 24); }
    private int px() { return (width - pw()) / 2; }
    private int py() { return (height - ph()) / 2; }

    @Override
    protected void init() {
        if (state == State.CHECKING && !checkStarted) startCheck();
        int bw = (pw() - 36) / 2;
        int by = py() + ph() - 30;
        int left = px() + 12;
        int right = px() + pw() - 12 - bw;
        switch (state) {
            case CHECKING -> addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> onClose())
                    .bounds((width - bw) / 2, by, bw, 20).build());
            case AVAILABLE -> {
                addRenderableWidget(Button.builder(Component.translatable("packsync.button.install").withStyle(ChatFormatting.BOLD), b -> startInstall())
                        .bounds(left, by, bw, 20).build());
                addRenderableWidget(Button.builder(Component.translatable(mandatory ? "gui.back" : "packsync.button.later"), b -> onClose())
                        .bounds(right, by, bw, 20).build());
            }
            case UP_TO_DATE, NOT_CONFIGURED -> addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                    .bounds((width - bw) / 2, by, bw, 20).build());
            case DOWNLOADING -> addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> cancel.set(true))
                    .bounds((width - bw) / 2, by, bw, 20).build());
            case INSTALLING -> { }
            case ERROR -> {
                addRenderableWidget(Button.builder(Component.translatable("packsync.button.retry"), b -> retry())
                        .bounds(left, by, bw, 20).build());
                addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> onClose())
                        .bounds(right, by, bw, 20).build());
            }
            case RESUME -> {
                addRenderableWidget(Button.builder(Component.translatable("packsync.button.finish").withStyle(ChatFormatting.BOLD), b -> resumeInstall())
                        .bounds(left, by, bw, 20).build());
                addRenderableWidget(Button.builder(Component.translatable("packsync.button.discard"), b -> {
                    Installer.discardPendingPlan(ClientEvents.gameDir());
                    onClose();
                }).bounds(right, by, bw, 20).build());
            }
        }
        rebuildBody();
    }

    private void setState(State s) {
        state = s;
        scroll = 0;
        if (minecraft != null) rebuildWidgets();
    }

    private void rebuildBody() {
        body.clear();
        int wrap = pw() - 32;
        List<Component> lines = new ArrayList<>();
        switch (state) {
            case CHECKING -> lines.add(Component.translatable("packsync.screen.checking").withStyle(ChatFormatting.GRAY));
            case UP_TO_DATE -> lines.add(Component.translatable("packsync.screen.uptodate").withStyle(ChatFormatting.GREEN));
            case NOT_CONFIGURED -> lines.add(Component.translatable("packsync.screen.notconfigured"));
            case RESUME -> lines.add(Component.translatable("packsync.screen.resume"));
            case INSTALLING -> {
                lines.add(Component.translatable("packsync.screen.installing").withStyle(ChatFormatting.GREEN));
                lines.add(Component.empty());
                lines.add(Component.translatable("packsync.screen.installing_note").withStyle(ChatFormatting.GRAY));
            }
            case ERROR -> {
                lines.add(errorText.copy().withStyle(ChatFormatting.RED));
                lines.add(Component.empty());
                lines.add(Component.translatable("packsync.screen.error_hint").withStyle(ChatFormatting.GRAY));
            }
            case DOWNLOADING -> { }
            case AVAILABLE -> {
                Manifest m = result.manifest;
                if (result.localVersion > 0 && result.localVersion != m.version) {
                    lines.add(Component.translatable("packsync.screen.versions", result.localVersion, m.version).withStyle(ChatFormatting.WHITE));
                } else if (result.localVersion == m.version) {
                    lines.add(Component.translatable("packsync.screen.repair", m.version).withStyle(ChatFormatting.WHITE));
                } else {
                    lines.add(Component.translatable("packsync.screen.version_new", m.version).withStyle(ChatFormatting.WHITE));
                }
                if (!result.downloads.isEmpty()) {
                    lines.add(Component.translatable("packsync.screen.download", result.downloads.size(), IO.humanBytes(result.downloadBytes))
                            .withStyle(ChatFormatting.AQUA));
                }
                if (!result.deletions.isEmpty()) {
                    lines.add(Component.translatable("packsync.screen.remove", result.deletions.size()).withStyle(ChatFormatting.GOLD));
                }
                if (!m.changelog.isEmpty()) {
                    lines.add(Component.empty());
                    lines.add(Component.translatable("packsync.screen.changelog").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD));
                    for (String c : m.changelog) lines.add(Component.literal("• " + c).withStyle(ChatFormatting.WHITE));
                }
                if (!result.deletions.isEmpty()) {
                    lines.add(Component.empty());
                    lines.add(Component.translatable("packsync.screen.removed_list").withStyle(ChatFormatting.GOLD));
                    int n = 0;
                    for (String d : result.deletions) {
                        if (n++ >= 12) {
                            lines.add(Component.literal("  … +" + (result.deletions.size() - 12)).withStyle(ChatFormatting.DARK_GRAY));
                            break;
                        }
                        lines.add(Component.literal("  " + d).withStyle(ChatFormatting.GRAY));
                    }
                    lines.add(Component.translatable("packsync.screen.backup_note").withStyle(ChatFormatting.DARK_GRAY));
                }
                lines.add(Component.empty());
                lines.add(Component.translatable("packsync.screen.restart_note").withStyle(ChatFormatting.GRAY));
            }
        }
        for (Component c : lines) {
            if (c.getString().isEmpty()) body.add(FormattedCharSequence.EMPTY);
            else body.addAll(font.split(c, wrap));
        }
        contentHeight = body.size() * 11;
    }

    // ------------------------------------------------------------- actions

    private void startCheck() {
        checkStarted = true;
        ClientEvents.checkAsync(null).thenAccept(r -> {
            if (minecraft != null) minecraft.execute(() -> applyResultAndShow(r));
        });
    }

    private void applyResultAndShow(UpdateChecker.Result r) {
        applyResult(r);
        if (minecraft != null && minecraft.screen == this) rebuildWidgets();
    }

    private void applyResult(UpdateChecker.Result r) {
        result = r;
        switch (r.status) {
            case OUTDATED -> state = State.AVAILABLE;
            case UP_TO_DATE -> state = State.UP_TO_DATE;
            case NOT_CONFIGURED -> state = State.NOT_CONFIGURED;
            default -> {
                state = State.ERROR;
                errorText = Component.translatable("packsync.error.check", r.error);
            }
        }
        if (state == State.AVAILABLE && r.offline) {
            state = State.ERROR;
            errorText = Component.translatable("packsync.error.offline");
        }
        scroll = 0;
    }

    private void retry() {
        checkStarted = false;
        cancel.set(false);
        setState(State.CHECKING);
    }

    private void startInstall() {
        if (result == null || result.manifest == null) return;
        cancel.set(false);
        LocalFiles.Prefs prefs = LocalFiles.Prefs.load(ClientEvents.gameDir());
        downloader = new Downloader(ClientEvents.gameDir(), result.bases, prefs.downloadThreads);
        if (result.downloads.isEmpty()) {
            beginInstalling(false);
            return;
        }
        progress = new Downloader.Progress();
        setState(State.DOWNLOADING);
        Thread t = new Thread(() -> {
            try {
                downloader.downloadAll(result.downloads, cancel, progress);
                minecraft.execute(() -> beginInstalling(false));
            } catch (CancellationException e) {
                minecraft.execute(() -> setState(State.AVAILABLE));
            } catch (Exception e) {
                PackSync.LOG.error("[PackSync] download failed", e);
                minecraft.execute(() -> fail(describe(e)));
            }
        }, "PackSync-Download-Main");
        t.setDaemon(true);
        t.start();
    }

    private void resumeInstall() {
        beginInstalling(true);
    }

    private void beginInstalling(boolean planAlreadyWritten) {
        updaterStarted = false;
        installTicks = 50;
        setState(State.INSTALLING);
        String lang = minecraft.getLanguageManager().getSelected();
        String address = ClientEvents.lastServerAddress();
        Thread t = new Thread(() -> {
            try {
                Path game = ClientEvents.gameDir();
                if (!planAlreadyWritten) Installer.writePlan(game, result, downloader, lang);
                String target = address; // server the player was joining; else the one the admin configured
                if (target == null || target.isBlank()) target = UpdateChecker.loadClientConfig(game).serverAddress;
                List<String> relaunch = LocalFiles.Prefs.load(game).autoRestart ? Relauncher.build(target) : null;
                Path self = ModList.get().getModFileById(PackSync.MODID).getFile().getFilePath();
                if (!Files.isRegularFile(self)) throw new IllegalStateException("PackSync is not running from a jar (development environment)");
                Installer.spawnUpdater(game, self, relaunch);
                updaterStarted = true;
            } catch (Exception e) {
                PackSync.LOG.error("[PackSync] could not start the updater", e);
                minecraft.execute(() -> fail(Component.translatable("packsync.error.updater", String.valueOf(e.getMessage()))));
            }
        }, "PackSync-Install");
        t.setDaemon(true);
        t.start();
    }

    private void fail(Component message) {
        errorText = message;
        setState(State.ERROR);
    }

    private static Component describe(Exception e) {
        String msg = String.valueOf(e.getMessage());
        if (msg.startsWith("NOT_ENOUGH_SPACE:")) {
            String[] p = msg.split(":", 3);
            return Component.translatable("packsync.error.space", p.length > 1 ? p[1] : "?", p.length > 2 ? p[2] : "?");
        }
        if (msg.startsWith("DOWNLOAD_FAILED:")) {
            String[] p = msg.split(":", 3);
            return Component.translatable("packsync.error.download", p.length > 1 ? p[1] : "?", p.length > 2 ? p[2] : "");
        }
        return Component.literal(msg);
    }

    @Override
    public void tick() {
        ticks++;
        if (state == State.INSTALLING && updaterStarted) {
            if (--installTicks <= 0) {
                updaterStarted = false;
                minecraft.stop();
            }
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return state != State.INSTALLING;
    }

    @Override
    public void onClose() {
        if (state == State.INSTALLING) return;
        cancel.set(true);
        if (state == State.AVAILABLE) ClientEvents.clearPrompted();
        minecraft.setScreen(parent);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        scroll = Mth.clamp(scroll - delta * 12, 0, Math.max(0, contentHeight - bodyHeight()));
        return true;
    }

    private int bodyTop() { return py() + 44; }

    private int bodyHeight() {
        int bottom = py() + ph() - (state == State.DOWNLOADING ? 80 : 40);
        return Math.max(20, bottom - bodyTop());
    }

    // ------------------------------------------------------------- render

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        int x = px(), y = py(), w = pw(), h = ph();
        int top, bottom;
        switch (state) {
            case ERROR -> { top = C_RED; bottom = C_RED_DARK; }
            case CHECKING, RESUME, NOT_CONFIGURED -> { top = C_BLUE; bottom = C_BLUE_DARK; }
            default -> { top = C_GREEN; bottom = C_GREEN_DARK; }
        }
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, C_BORDER);
        g.fill(x, y, x + w, y + h, C_PANEL);
        g.fillGradient(x, y, x + w, y + 34, top, bottom);

        Component heading = switch (state) {
            case CHECKING -> Component.translatable("packsync.title.checking");
            case AVAILABLE -> Component.translatable(result != null && result.localVersion == result.manifest.version
                    ? "packsync.title.repair" : "packsync.title.available");
            case UP_TO_DATE -> Component.translatable("packsync.title.uptodate");
            case DOWNLOADING -> Component.translatable("packsync.title.downloading");
            case INSTALLING -> Component.translatable("packsync.title.installing");
            case ERROR -> Component.translatable("packsync.title.error");
            case RESUME -> Component.translatable("packsync.title.resume");
            case NOT_CONFIGURED -> Component.translatable("packsync.title.notconfigured");
        };
        g.drawCenteredString(font, heading.copy().withStyle(ChatFormatting.BOLD), width / 2, y + 9, 0xFFFFFF);
        String pack = result != null && result.manifest != null ? result.manifest.packName : "";
        if (!pack.isEmpty()) g.drawCenteredString(font, Component.literal(pack), width / 2, y + 21, 0xDDEEEEEE);

        if (state == State.CHECKING || state == State.INSTALLING) drawSpinner(g, width / 2, y + h - 52);

        // Scrollable body
        boolean rtl = Language.getInstance().isDefaultRightToLeft();
        int bx1 = x + 16, bx2 = x + w - 16, by1 = bodyTop(), bh = bodyHeight();
        g.enableScissor(bx1, by1, bx2, by1 + bh);
        int ly = by1 - (int) scroll;
        for (FormattedCharSequence line : body) {
            if (ly > by1 - 11 && ly < by1 + bh) {
                int lx = rtl ? bx2 - font.width(line) : bx1;
                g.drawString(font, line, lx, ly, C_TEXT, false);
            }
            ly += 11;
        }
        g.disableScissor();
        if (contentHeight > bh) { // scrollbar
            int track = bh;
            int thumb = Math.max(12, track * track / contentHeight);
            int ty = by1 + (int) ((track - thumb) * (scroll / Math.max(1, contentHeight - bh)));
            g.fill(x + w - 8, by1, x + w - 6, by1 + track, 0x40FFFFFF);
            g.fill(x + w - 8, ty, x + w - 6, ty + thumb, 0xC0FFFFFF);
        }

        if (state == State.DOWNLOADING && progress != null) drawProgress(g, x, y, w, h);
        if (state == State.INSTALLING) {
            int secs = Math.max(1, (installTicks + 19) / 20);
            Component c = updaterStarted ? Component.translatable("packsync.screen.closing_in", secs) : Component.translatable("packsync.screen.preparing");
            g.drawCenteredString(font, c, width / 2, y + h - 30, C_MUTED);
        }
        super.render(g, mouseX, mouseY, partialTick);
    }

    private void drawProgress(GuiGraphics g, int x, int y, int w, int h) {
        int bx = x + 16, bw = w - 32, by = y + h - 64;
        double f = progress.fraction();
        g.fill(bx - 1, by - 1, bx + bw + 1, by + 11, 0xFF000000);
        g.fill(bx, by, bx + bw, by + 10, 0xFF242A33);
        int fill = (int) (bw * f);
        g.fillGradient(bx, by, bx + fill, by + 10, 0xFF4CC274, C_GREEN_DARK);
        // moving shine
        int shine = (int) ((ticks * 6L) % Math.max(1, bw + 60)) - 60;
        if (fill > 0) {
            int s1 = Math.max(bx, bx + shine), s2 = Math.min(bx + fill, bx + shine + 40);
            if (s2 > s1) g.fill(s1, by, s2, by + 10, 0x30FFFFFF);
        }
        double bps = progress.bytesPerSecond();
        long left = progress.total - progress.done.get();
        String eta = bps > 1 ? formatEta((long) (left / bps)) : "--:--";
        String stats = String.format(Locale.ROOT, "%d%%   %s / %s   %s/s   %s",
                (int) (f * 100), IO.humanBytes(progress.done.get()), IO.humanBytes(progress.total), IO.humanBytes((long) bps), eta);
        g.drawCenteredString(font, stats, width / 2, by - 12, C_TEXT);
        String cur = progress.current == null ? "" : progress.current;
        MutableComponent files = Component.translatable("packsync.screen.files", progress.filesDone.get(), progress.filesTotal);
        g.drawCenteredString(font, files, width / 2, by + 14, C_MUTED);
        String shown = font.plainSubstrByWidth(cur, bw);
        g.drawCenteredString(font, shown, width / 2, by + 25, 0xFF6F7884);
    }

    private void drawSpinner(GuiGraphics g, int cx, int cy) {
        for (int i = 0; i < 8; i++) {
            double a = (ticks * 0.35) + i * Math.PI / 4;
            int px = cx + (int) Math.round(Math.cos(a) * 7);
            int py = cy + (int) Math.round(Math.sin(a) * 7);
            int alpha = 0x30 + i * 0x18;
            g.fill(px - 1, py - 1, px + 2, py + 2, (alpha << 24) | 0xFFFFFF);
        }
    }

    private static String formatEta(long secs) {
        if (secs < 0) return "--:--";
        return String.format(Locale.ROOT, "%d:%02d", secs / 60, secs % 60);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
