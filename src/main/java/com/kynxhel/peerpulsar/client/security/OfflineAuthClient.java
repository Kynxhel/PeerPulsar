package com.kynxhel.peerpulsar.client.security;

import com.kynxhel.peerpulsar.PeerPulsar;
import com.kynxhel.peerpulsar.client.network.P2PNetworkManager;
import com.kynxhel.peerpulsar.security.ClientIdentity;
import com.kynxhel.peerpulsar.security.OfflineAuth;
import com.kynxhel.peerpulsar.security.RoomKeys;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.client.networking.v1.ClientLoginNetworking;
import net.minecraft.client.User;
import net.minecraft.network.FriendlyByteBuf;

import java.util.concurrent.CompletableFuture;

/**
 * Client-side login query handler for PeerPulsar Room Key, Ed25519 identity, and hybrid Mojang proof.
 */
public final class OfflineAuthClient {
    private OfflineAuthClient() {}

    public static void registerClientSide() {
        ClientLoginNetworking.registerGlobalReceiver(OfflineAuth.CHANNEL, (client, handler, buf, listenerAdder) -> {
            int hostProtocol = buf.readVarInt();
            byte[] nonce = buf.readByteArray(64);
            boolean hybridAllowed = buf.readBoolean();
            String hybridServerId = buf.readUtf(64);
            String roomKey = P2PNetworkManager.getInstance().consumePendingJoinKey();
            User user = client.getUser();
            String name = user.getName();

            return CompletableFuture.supplyAsync(() -> {
                ClientIdentity identity = ClientIdentity.get();

                boolean attemptedHybrid = false;
                if (hybridAllowed && hybridServerId != null && !hybridServerId.isEmpty() && isLikelyMojangToken(user)) {
                    try {
                        client.services().sessionService().joinServer(user.getProfileId(), user.getAccessToken(), hybridServerId);
                        attemptedHybrid = true;
                    } catch (Exception e) {
                        PeerPulsar.LOGGER.debug("[PeerPulsar-Auth] Hybrid Mojang joinServer skipped/failed, falling back to key UUID: {}", e.getMessage());
                    }
                }

                FriendlyByteBuf out = new FriendlyByteBuf(Unpooled.buffer());
                out.writeVarInt(OfflineAuth.PROTOCOL);
                out.writeBoolean(roomKey != null);
                out.writeByteArray(roomKey != null ? RoomKeys.loginProof(roomKey, nonce, name) : new byte[0]);
                out.writeByteArray(identity.publicKeyEncoded());
                out.writeByteArray(identity.sign(RoomKeys.identityPayload(nonce, name)));
                out.writeBoolean(attemptedHybrid);
                if (hostProtocol != OfflineAuth.PROTOCOL) {
                    PeerPulsar.LOGGER.warn("[PeerPulsar-Auth] Host protocol={} differs from client protocol={}", hostProtocol, OfflineAuth.PROTOCOL);
                }
                return out;
            }, P2PNetworkManager.NETWORK_IO_EXECUTOR);
        });
    }

    private static boolean isLikelyMojangToken(User user) {
        if (user == null || user.getProfileId() == null) return false;
        String token = user.getAccessToken();
        return token != null && !token.isBlank() && !"0".equals(token) && token.length() > 16;
    }
}
