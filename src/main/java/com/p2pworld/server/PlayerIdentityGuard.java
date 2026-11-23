package com.p2pworld.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.p2pworld.FabricP2PMod;
import com.p2pworld.client.shared.GoogleAuthService;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Cryptographic Anti-Spoofing Guard for Offline LAN & Shared Worlds.
 * Verifies each joining player's Google-signed OIDC id_token via Google's tokeninfo API
 * and stores a non-reversible SHA-256 identity hash in <world>/fabricp2p_identities.json
 * (never leaking plaintext email addresses in shared world files).
 */
public final class PlayerIdentityGuard {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type MAP_TYPE = new TypeToken<Map<String, String>>() {}.getType();
    private static final String IDENTITY_FILE = "fabricp2p_identities.json";
    private static final String HASH_PREFIX = "sha256:";

    private static final AtomicLong JOIN_SEQ = new AtomicLong(0);
    private static final Map<UUID, Long> activeJoinToken = new ConcurrentHashMap<>();
    private static final Set<UUID> verifiedThisSession = ConcurrentHashMap.newKeySet();

    private PlayerIdentityGuard() {}

    /**
     * Called when a player joins the server. If their username is already bound to a Google account,
     * they must present a valid Google-signed id_token matching that account within 8 seconds or be disconnected.
     */
    public static void onPlayerJoin(ServerPlayer player, MinecraftServer server) {
        if (server == null || player == null) return;
        UUID uuid = player.getUUID();
        verifiedThisSession.remove(uuid);
        long joinToken = JOIN_SEQ.incrementAndGet();
        activeJoinToken.put(uuid, joinToken);

        String usernameKey = player.getName().getString().toLowerCase(Locale.ROOT);
        Map<String, String> bindings = loadBindings(server);
        String boundIdentity = bindings.get(usernameKey);

        if (boundIdentity == null || boundIdentity.isBlank()) {
            return; // Not yet bound to any Google account
        }

        // Wait up to 8 seconds for client to refresh/send & server to verify Google OIDC id_token
        CompletableFuture.delayedExecutor(8, TimeUnit.SECONDS).execute(() -> {
            if (!server.isRunning()) return;
            server.execute(() -> {
                Long currentToken = activeJoinToken.get(uuid);
                // Ignore stale timer if player already disconnected and reconnected
                if (currentToken == null || currentToken != joinToken) {
                    return;
                }
                if (!verifiedThisSession.contains(uuid)) {
                    ServerPlayer target = server.getPlayerList().getPlayer(uuid);
                    if (target != null) {
                        FabricP2PMod.LOGGER.warn("[IdentityGuard] Kicked unverified player '{}' (bound to Google account).",
                                target.getName().getString());
                        target.connection.disconnect(Component.literal(
                                "§c[FabricP2P] Username '" + target.getName().getString()
                                        + "' is protected by Google Sign-In!\n§7Sign in with the owner's Google account in Shared Worlds."
                        ));
                    }
                }
            });
        });
    }

    /**
     * Verifies a Google-verified identity (obtained from Google's tokeninfo endpoint) against the world's bindings.
     * Returns true if allowed (newly bound or matches existing binding), false if spoofed & kicked.
     */
    public static boolean verifyAndBind(ServerPlayer player, GoogleAuthService.VerifiedGoogleIdentity verified, MinecraftServer server) {
        if (player == null || server == null) return false;
        String username = player.getName().getString();
        String usernameKey = username.toLowerCase(Locale.ROOT);

        Map<String, String> bindings = loadBindings(server);
        String existingBinding = bindings.get(usernameKey);

        if (existingBinding != null && !existingBinding.isBlank()) {
            boolean matches = false;
            if (verified != null) {
                String expectedHash = computeIdentityHash(verified.email());
                if (existingBinding.startsWith(HASH_PREFIX)) {
                    matches = existingBinding.equals(expectedHash);
                } else {
                    // Legacy v2.1.0 plaintext email binding: compare & transparently migrate to SHA-256 hash
                    matches = existingBinding.equalsIgnoreCase(verified.email());
                    if (matches) {
                        bindings.put(usernameKey, expectedHash);
                        saveBindings(server, bindings);
                    }
                }
            }

            if (!matches) {
                FabricP2PMod.LOGGER.warn("[IdentityGuard] Blocked nickname spoof for '{}'!", username);
                player.connection.disconnect(Component.literal(
                        "§c[FabricP2P] Access Denied!\n§7Username '" + username
                                + "' belongs to a different Google account."
                ));
                return false;
            }
            verifiedThisSession.add(player.getUUID());
            return true;
        }

        // First time this username joins with a cryptographically verified Google account -> bind hash!
        if (verified != null && verified.email() != null && verified.email().contains("@")) {
            bindings.put(usernameKey, computeIdentityHash(verified.email()));
            saveBindings(server, bindings);
            verifiedThisSession.add(player.getUUID());
            FabricP2PMod.LOGGER.info("[IdentityGuard] Bound username '{}' to verified Google account.", username);
            player.sendSystemMessage(Component.literal(
                    "§a🔒 [FabricP2P] §fUsername §b" + username + " §fis now protected by your verified Google account!"
            ));
            return true;
        }

        return true;
    }

    public static void onPlayerDisconnect(UUID uuid) {
        verifiedThisSession.remove(uuid);
        activeJoinToken.remove(uuid);
    }

    private static String computeIdentityHash(String normalizedEmail) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(("fabricp2p-id-v2:" + normalizedEmail.trim().toLowerCase(Locale.ROOT))
                    .getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(HASH_PREFIX);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static synchronized Map<String, String> loadBindings(MinecraftServer server) {
        Map<String, String> map = new ConcurrentHashMap<>();
        try {
            Path file = server.getWorldPath(LevelResource.ROOT).resolve(IDENTITY_FILE);
            if (Files.exists(file)) {
                Map<String, String> parsed = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), MAP_TYPE);
                if (parsed != null) {
                    map.putAll(parsed);
                }
            }
        } catch (Exception e) {
            FabricP2PMod.LOGGER.warn("[IdentityGuard] Failed to read identity bindings: {}", e.getMessage());
        }
        return map;
    }

    private static synchronized void saveBindings(MinecraftServer server, Map<String, String> bindings) {
        try {
            Path file = server.getWorldPath(LevelResource.ROOT).resolve(IDENTITY_FILE);
            Files.writeString(file, GSON.toJson(bindings), StandardCharsets.UTF_8);
        } catch (Exception e) {
            FabricP2PMod.LOGGER.warn("[IdentityGuard] Failed to save identity bindings: {}", e.getMessage());
        }
    }
}
