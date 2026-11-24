package com.kynxhel.peerpulsar.mixin;

import com.kynxhel.peerpulsar.client.network.P2PNetworkManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ServerLoginPacketListenerImpl.class)
public abstract class ServerLoginPacketListenerImplMixin {
    /**
     * Applies Offline LAN Auth mode during client login when enabled on the P2P Host screen.
     */
    @Redirect(
            method = "handleHello",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/MinecraftServer;usesAuthentication()Z"
            ),
            require = 1
    )
    private boolean redirectUsesAuthentication(MinecraftServer serverInstance) {
        P2PNetworkManager manager = P2PNetworkManager.getInstance();
        if (manager.isP2PActive() && manager.isOfflineModeEnabled()) {
            return false;
        }
        return serverInstance.usesAuthentication();
    }
}
