package com.p2pworld.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Custom Fabric Networking payloads for Google Identity verification (anti-spoofing)
 * and automatic Google Drive Shared World linking.
 */
public final class P2PIdentityPayloads {
    private P2PIdentityPayloads() {}

    public record GoogleIdentityC2SPayload(String email) implements CustomPacketPayload {
        public static final Type<GoogleIdentityC2SPayload> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath("fabricp2p", "google_identity_c2s"));

        public static final StreamCodec<RegistryFriendlyByteBuf, GoogleIdentityC2SPayload> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.STRING_UTF8, GoogleIdentityC2SPayload::email,
                        GoogleIdentityC2SPayload::new
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record SharedWorldGrantS2CPayload(String driveFolderId, String worldName) implements CustomPacketPayload {
        public static final Type<SharedWorldGrantS2CPayload> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath("fabricp2p", "shared_world_grant_s2c"));

        public static final StreamCodec<RegistryFriendlyByteBuf, SharedWorldGrantS2CPayload> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.STRING_UTF8, SharedWorldGrantS2CPayload::driveFolderId,
                        ByteBufCodecs.STRING_UTF8, SharedWorldGrantS2CPayload::worldName,
                        SharedWorldGrantS2CPayload::new
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
