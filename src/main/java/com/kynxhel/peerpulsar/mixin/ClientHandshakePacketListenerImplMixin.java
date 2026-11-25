package com.kynxhel.peerpulsar.mixin;

import com.kynxhel.peerpulsar.client.security.OfflineAuthClient;
import com.kynxhel.peerpulsar.security.RoomKeys;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.login.ClientboundHelloPacket;
import net.minecraft.util.CryptException;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.security.PublicKey;

@Mixin(ClientHandshakePacketListenerImpl.class)
public abstract class ClientHandshakePacketListenerImplMixin {
    @Shadow
    @Final
    private Connection connection;

    @Inject(method = "handleHello", at = @At("HEAD"))
    private void peerpulsar$onHandleHello(ClientboundHelloPacket packet, CallbackInfo ci) {
        try {
            PublicKey key = packet.getPublicKey();
            if (key != null) {
                byte[] encoded = key.getEncoded();
                if (encoded != null) {
                    OfflineAuthClient.recordServerPublicKeyHash(this.connection, RoomKeys.sha256(encoded));
                }
            }
        } catch (CryptException ignored) {
        }
    }
}
