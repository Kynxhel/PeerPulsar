package com.p2pworld.mixin;

import com.p2pworld.FabricP2PMod;
import com.p2pworld.client.network.P2PNetworkManager;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(IntegratedServer.class)
public abstract class IntegratedServerMixin {
    /**
     * Intercepts publishServer on Minecraft 26.3 to apply Offline LAN Auth mode when enabled.
     */
    @Inject(method = "publishServer(Lnet/minecraft/server/MinecraftServer$MultiplayerScope;ZI)Z", at = @At("HEAD"))
    private void onPublishServerHead(MinecraftServer.MultiplayerScope scope, boolean cheatsAllowed, int port, CallbackInfoReturnable<Boolean> cir) {
        P2PNetworkManager manager = P2PNetworkManager.getInstance();
        if (manager.isOfflineModeEnabled()) {
            ((MinecraftServer) (Object) this).setUsesAuthentication(false);
            FabricP2PMod.LOGGER.info("[P2P-OfflineLAN 26.3] Setting usesAuthentication = FALSE for IntegratedServer (Port: {}).", port);
        }
    }

    @Inject(method = "publishServer(Lnet/minecraft/server/MinecraftServer$MultiplayerScope;ZI)Z", at = @At("RETURN"))
    private void onPublishServerReturn(MinecraftServer.MultiplayerScope scope, boolean cheatsAllowed, int port, CallbackInfoReturnable<Boolean> cir) {
        P2PNetworkManager manager = P2PNetworkManager.getInstance();
        if (manager.isOfflineModeEnabled()) {
            ((MinecraftServer) (Object) this).setUsesAuthentication(false);
        }
    }
}
