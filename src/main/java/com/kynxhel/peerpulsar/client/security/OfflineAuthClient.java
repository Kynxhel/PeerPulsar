package com.kynxhel.peerpulsar.client.security;

import com.kynxhel.peerpulsar.PeerPulsar;
import com.kynxhel.peerpulsar.client.network.P2PNetworkManager;
import com.kynxhel.peerpulsar.mixin.ClientHandshakePacketListenerAccessor;
import com.kynxhel.peerpulsar.security.ClientIdentity;
import com.kynxhel.peerpulsar.security.OfflineAuth;
import com.kynxhel.peerpulsar.security.RoomKeys;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.client.networking.v1.ClientLoginNetworking;
import net.minecraft.client.User;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;

/**
 * Client-side login query handler for PeerPulsar Room Key and channel-bound Ed25519 identity proof.
 */
public final class OfflineAuthClient {
    private static final Map<Connection, byte[]> SERVER_PUBKEY_HASHES =
            Collections.synchronizedMap(new WeakHashMap<>());

    private OfflineAuthClient() {}

    public static void recordServerPublicKeyHash(Connection conn, byte[] hash) {
        if (conn != null && hash != null) {
            SERVER_PUBKEY_HASHES.put(conn, hash);
        }
    }

    public static byte[] getServerPublicKeyHash(Connection conn) {
        return conn != null ? SERVER_PUBKEY_HASHES.get(conn) : null;
    }

    public static void registerClientSide() {
        ClientLoginNetworking.registerGlobalReceiver(OfflineAuth.CHANNEL, (client, handler, buf, listenerAdder) -> {
            int hostProtocol = buf.readVarInt();
            byte[] nonce = buf.readByteArray(64);
            boolean offlineMode = buf.readBoolean();
            Connection conn = ((ClientHandshakePacketListenerAccessor) handler).peerpulsar$getConnection();
            byte[] recordedHash = getServerPublicKeyHash(conn);
            byte[] serverPubKeyHash = recordedHash != null ? recordedHash : new byte[0];

            String roomKey = P2PNetworkManager.getInstance().consumePendingJoinKey();
            User user = client.getUser();
            String name = user.getName();

            return CompletableFuture.supplyAsync(() -> {
                ClientIdentity identity = ClientIdentity.get();

                FriendlyByteBuf out = new FriendlyByteBuf(Unpooled.buffer());
                out.writeVarInt(OfflineAuth.PROTOCOL);
                out.writeBoolean(roomKey != null);
                out.writeByteArray(roomKey != null
                        ? RoomKeys.loginProof(roomKey, nonce, name, serverPubKeyHash)
                        : new byte[0]);
                out.writeByteArray(identity.publicKeyEncoded());
                out.writeByteArray(identity.sign(RoomKeys.identityPayload(nonce, name, serverPubKeyHash)));
                if (hostProtocol != OfflineAuth.PROTOCOL) {
                    PeerPulsar.LOGGER.warn("[PeerPulsar-Auth] Host protocol={} differs from client protocol={} (offlineMode={})",
                            hostProtocol, OfflineAuth.PROTOCOL, offlineMode);
                }
                return out;
            }, P2PNetworkManager.NETWORK_IO_EXECUTOR);
        });
    }
}
