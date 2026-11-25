package com.kynxhel.peerpulsar.mixin;

import com.kynxhel.peerpulsar.PeerPulsar;
import com.kynxhel.peerpulsar.client.network.P2PNetworkManager;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.ServerOpListEntry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(IntegratedServer.class)
public abstract class IntegratedServerMixin {
    /**
     * Intercepts publishServer on Minecraft 26.3: applies Offline/Online LAN Auth when PeerPulsar
     * hosting is starting/active, and explicitly restores Mojang Online Auth for Vanilla "Open to LAN"
     * so stopping Offline P2P can never leave Vanilla LAN stuck in offline mode.
     */
    @Inject(method = "publishServer(Lnet/minecraft/server/MinecraftServer$MultiplayerScope;I)Z", at = @At("HEAD"))
    private void onPublishServerHead(MinecraftServer.MultiplayerScope scope, int port, CallbackInfoReturnable<Boolean> cir) {
        P2PNetworkManager manager = P2PNetworkManager.getInstance();
        if (!manager.isStarting() && !manager.isP2PActive()) {
            ((MinecraftServer) (Object) this).setUsesAuthentication(true);
            return;
        }
        boolean useAuth = !manager.isOfflineModeEnabled();
        ((MinecraftServer) (Object) this).setUsesAuthentication(useAuth);
        PeerPulsar.LOGGER.info("[P2P-Auth 26.3] Setting usesAuthentication = {} for IntegratedServer (Port: {}).", useAuth, port);
    }

    @Inject(method = "publishServer(Lnet/minecraft/server/MinecraftServer$MultiplayerScope;I)Z", at = @At("RETURN"))
    private void onPublishServerReturn(MinecraftServer.MultiplayerScope scope, int port, CallbackInfoReturnable<Boolean> cir) {
        P2PNetworkManager manager = P2PNetworkManager.getInstance();
        if (!manager.isStarting() && !manager.isP2PActive()) {
            return;
        }
        ((MinecraftServer) (Object) this).setUsesAuthentication(!manager.isOfflineModeEnabled());
    }

    /**
     * Honors per-guest OP entries from {@code PlayerList.op(NameAndId)} on IntegratedServer
     * even when global {@code guestCommandAccess} is disabled, so the host can grant and revoke
     * OP individually for each connected guest.
     */
    @Inject(
            method = "getProfilePermissions(Lnet/minecraft/server/players/NameAndId;)Lnet/minecraft/server/permissions/LevelBasedPermissionSet;",
            at = @At("HEAD"),
            cancellable = true
    )
    private void peerpulsar$honorPerGuestOp(NameAndId nameAndId, CallbackInfoReturnable<LevelBasedPermissionSet> cir) {
        MinecraftServer server = (MinecraftServer) (Object) this;
        if (nameAndId != null && !server.isSingleplayerOwner(nameAndId) && server.getPlayerList().isOp(nameAndId)) {
            ServerOpListEntry entry = server.getPlayerList().getOps().get(nameAndId);
            cir.setReturnValue(entry != null ? entry.permissions() : server.operatorUserPermissions());
        }
    }
}
