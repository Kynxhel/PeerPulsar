package com.p2pworld.mixin;

import com.p2pworld.client.network.P2PNetworkManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ServerLoginPacketListenerImpl.class)
public abstract class ServerLoginPacketListenerImplMixin {
    @Shadow @Final MinecraftServer server;

    /**
     * Bypasses Mojang online authentication check during client login in Minecraft 26.3
     * when Crack Support is enabled. This allows offline/crack players to join without session verification.
     */
    @Redirect(
            method = "handleHello",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/MinecraftServer;usesAuthentication()Z"
            ),
            require = 0
    )
    private boolean redirectUsesAuthentication(MinecraftServer serverInstance) {
        P2PNetworkManager manager = P2PNetworkManager.getInstance();
        if (manager.isP2PActive() && manager.isCrackSupportEnabled()) {
            return false; // Force offline authentication path
        }
        return serverInstance.usesAuthentication();
    }
}

