package com.p2pworld.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Custom Fabric Networking payloads for cryptographic Google OIDC ID Token verification
 * (anti-spoofing) and scoped per-world Google Drive sharing.
 */
public final class P2PIdentityPayloads {
    private P2PIdentityPayloads() {}

    /**
     * Carries the client's Google-signed OIDC id_token (JWT) so the Host can verify identity
     * cryptographically via Google's tokeninfo endpoint instead of trusting plaintext email claims.
     */
    public record GoogleIdentityC2SPayload(String idToken) implements CustomPacketPayload {
        public static final Type<GoogleIdentityC2SPayload> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath("fabricp2p", "google_identity_c2s"));

        public static final StreamCodec<RegistryFriendlyByteBuf, GoogleIdentityC2SPayload> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.stringUtf8(8192), GoogleIdentityC2SPayload::idToken,
                        GoogleIdentityC2SPayload::new
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record SharedWorldGrantS2CPayload(String worldFolderId, String worldName) implements CustomPacketPayload {
        public static final Type<SharedWorldGrantS2CPayload> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath("fabricp2p", "shared_world_grant_s2c"));

        public static final StreamCodec<RegistryFriendlyByteBuf, SharedWorldGrantS2CPayload> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.stringUtf8(256), SharedWorldGrantS2CPayload::worldFolderId,
                        ByteBufCodecs.stringUtf8(256), SharedWorldGrantS2CPayload::worldName,
                        SharedWorldGrantS2CPayload::new
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
