package com.packsync;

import com.packsync.net.Network;
import com.packsync.server.ServerHandler;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(PackSync.MODID)
public final class PackSync {
    public static final String MODID = "packsync";
    public static final Logger LOG = LoggerFactory.getLogger("PackSync");

    public PackSync() {
        Network.register();
        if (FMLEnvironment.dist == Dist.DEDICATED_SERVER) {
            ServerHandler.register(MinecraftForge.EVENT_BUS);
        } else {
            com.packsync.client.ClientEvents.register(MinecraftForge.EVENT_BUS);
        }
    }
}
