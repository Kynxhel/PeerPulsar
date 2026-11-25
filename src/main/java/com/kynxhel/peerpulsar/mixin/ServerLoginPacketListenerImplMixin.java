package com.kynxhel.peerpulsar.mixin;

import com.kynxhel.peerpulsar.client.network.P2PNetworkManager;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.login.ServerboundKeyPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLoginPacketListenerImpl.class)
public abstract class ServerLoginPacketListenerImplMixin {
    @Shadow
    @Final
    private Connection connection;

    @Shadow
    private String requestedUsername;

    @Shadow
    private void startClientVerification(GameProfile profile) {
    }

    @Unique
    private boolean peerpulsar$offlineEncrypting;

    @Unique
    private static boolean peerpulsar$isOfflineP2PHosting() {
        P2PNetworkManager m = P2PNetworkManager.getInstance();
        return (m.isP2PActive() || m.isStarting()) && m.isOfflineModeEnabled();
    }

    /**
     * Keeps vanilla login encryption enabled during Offline LAN Auth P2P hosting so
     * ClientboundHelloPacket and ServerboundKeyPacket establish a confidential channel.
     */
    @Redirect(
            method = "handleHello",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/MinecraftServer;usesAuthentication()Z"
            ),
            require = 1
    )
    private boolean peerpulsar$redirectUsesAuthentication(MinecraftServer serverInstance) {
        if (!this.connection.isMemoryConnection() && peerpulsar$isOfflineP2PHosting()) {
            return true;
        }
        return serverInstance.usesAuthentication();
    }

    @ModifyArg(
            method = "handleHello",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/network/protocol/login/ClientboundHelloPacket;<init>(Ljava/lang/String;[B[BZ)V"
            ),
            index = 3,
            require = 1
    )
    private boolean peerpulsar$modifyShouldAuthenticate(boolean shouldAuthenticate) {
        if (!this.connection.isMemoryConnection() && peerpulsar$isOfflineP2PHosting()) {
            this.peerpulsar$offlineEncrypting = true;
            return false;
        }
        return shouldAuthenticate;
    }

    @Inject(
            method = "handleKey",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/network/Connection;setEncryptionKey(Ljavax/crypto/Cipher;Ljavax/crypto/Cipher;)V",
                    shift = At.Shift.AFTER
            ),
            cancellable = true,
            require = 1
    )
    private void peerpulsar$afterEncryptionEnabled(ServerboundKeyPacket packet, CallbackInfo ci) {
        if (this.peerpulsar$offlineEncrypting) {
            this.peerpulsar$offlineEncrypting = false;
            this.startClientVerification(UUIDUtil.createOfflineProfile(this.requestedUsername));
            ci.cancel();
        }
    }
}
