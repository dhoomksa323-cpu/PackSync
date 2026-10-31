package com.packsync.net;

import com.packsync.PackSync;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.function.Supplier;

public final class Network {
    public static final String PROTOCOL = "1";

    /**
     * Missing-on-the-other-side is accepted at the Forge handshake on purpose: the server then
     * kicks such players itself, with a clear message and the download link, instead of
     * Forge's generic "mod rejected" screen.
     */
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(PackSync.MODID, "sync"),
            () -> PROTOCOL,
            NetworkRegistry.acceptMissingOr(PROTOCOL),
            NetworkRegistry.acceptMissingOr(PROTOCOL));

    private Network() {}

    public static void register() {
        CHANNEL.messageBuilder(Hello.class, 0, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(Hello::encode)
                .decoder(Hello::decode)
                .consumerMainThread(Hello::handle)
                .add();
        CHANNEL.messageBuilder(Report.class, 1, NetworkDirection.PLAY_TO_SERVER)
                .encoder(Report::encode)
                .decoder(Report::decode)
                .consumerMainThread(Report::handle)
                .add();
    }

    /** Server → client: "this is the pack version I expect". */
    public record Hello(String manifestHash, int version, String packName) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeUtf(manifestHash, 128);
            buf.writeVarInt(version);
            buf.writeUtf(packName, 256);
        }

        public static Hello decode(FriendlyByteBuf buf) {
            return new Hello(buf.readUtf(128), buf.readVarInt(), buf.readUtf(256));
        }

        public void handle(Supplier<NetworkEvent.Context> ctx) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.packsync.client.ClientEvents.onHello(this));
        }
    }

    /** Client → server: result of comparing the local game folder with that version. */
    public record Report(int status, String manifestHash, String modVersion) {
        public static final int OK = 0, OUTDATED = 1, UNKNOWN = 2, NOT_CONFIGURED = 3;

        public void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(status);
            buf.writeUtf(manifestHash, 128);
            buf.writeUtf(modVersion, 64);
        }

        public static Report decode(FriendlyByteBuf buf) {
            return new Report(buf.readVarInt(), buf.readUtf(128), buf.readUtf(64));
        }

        public void handle(Supplier<NetworkEvent.Context> ctx) {
            ServerPlayer sender = ctx.get().getSender();
            if (sender != null) com.packsync.server.ServerHandler.onReport(sender, this);
        }
    }
}
