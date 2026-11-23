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
     * Intercepts publishServer on Minecraft 26.3: applies Offline/Online LAN Auth when Fabric P2P
     * hosting is starting/active, and explicitly restores Mojang Online Auth for Vanilla "Open to LAN"
     * so stopping Offline P2P can never leave Vanilla LAN stuck in offline mode.
     */
    @Inject(method = "publishServer(Lnet/minecraft/server/MinecraftServer$MultiplayerScope;ZI)Z", at = @At("HEAD"))
    private void onPublishServerHead(MinecraftServer.MultiplayerScope scope, boolean cheatsAllowed, int port, CallbackInfoReturnable<Boolean> cir) {
        P2PNetworkManager manager = P2PNetworkManager.getInstance();
        if (!manager.isStarting() && !manager.isP2PActive()) {
            ((MinecraftServer) (Object) this).setUsesAuthentication(true);
            return;
        }
        boolean useAuth = !manager.isOfflineModeEnabled();
        ((MinecraftServer) (Object) this).setUsesAuthentication(useAuth);
        FabricP2PMod.LOGGER.info("[P2P-Auth 26.3] Setting usesAuthentication = {} for IntegratedServer (Port: {}).", useAuth, port);
    }

    @Inject(method = "publishServer(Lnet/minecraft/server/MinecraftServer$MultiplayerScope;ZI)Z", at = @At("RETURN"))
    private void onPublishServerReturn(MinecraftServer.MultiplayerScope scope, boolean cheatsAllowed, int port, CallbackInfoReturnable<Boolean> cir) {
        P2PNetworkManager manager = P2PNetworkManager.getInstance();
        if (!manager.isStarting() && !manager.isP2PActive()) {
            return;
        }
        ((MinecraftServer) (Object) this).setUsesAuthentication(!manager.isOfflineModeEnabled());
    }
}
