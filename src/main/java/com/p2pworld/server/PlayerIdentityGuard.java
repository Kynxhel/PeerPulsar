package com.p2pworld.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.p2pworld.FabricP2PMod;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Login-style Anti-Spoofing Guard for Offline LAN & Shared Worlds.
 * Binds each player's Minecraft username to their verified Google account email inside
 * <world>/fabricp2p_identities.json so nobody can impersonate another player's nickname.
 */
public final class PlayerIdentityGuard {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type MAP_TYPE = new TypeToken<Map<String, String>>() {}.getType();
    private static final String IDENTITY_FILE = "fabricp2p_identities.json";

    private static final Set<UUID> verifiedThisSession = ConcurrentHashMap.newKeySet();

    private PlayerIdentityGuard() {}

    /**
     * Called when a player joins the server. If their username is already bound to a Google account,
     * they must present the matching Google identity payload within 6 seconds or be disconnected.
     */
    public static void onPlayerJoin(ServerPlayer player, MinecraftServer server) {
        if (server == null || player == null) return;
        verifiedThisSession.remove(player.getUUID());

        String usernameKey = player.getName().getString().toLowerCase(Locale.ROOT);
        Map<String, String> bindings = loadBindings(server);
        String boundEmail = bindings.get(usernameKey);

        if (boundEmail == null || boundEmail.isBlank()) {
            return; // Not yet bound to any Google account
        }

        // Wait 6 seconds for client to send GoogleIdentityC2SPayload
        UUID uuid = player.getUUID();
        CompletableFuture.delayedExecutor(6, TimeUnit.SECONDS).execute(() -> {
            server.execute(() -> {
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
     * Verifies an incoming Google email against the world's identity bindings.
     * Returns true if allowed (either newly bound or matches existing binding), false if spoofed & kicked.
     */
    public static boolean verifyAndBind(ServerPlayer player, String rawEmail, MinecraftServer server) {
        if (player == null || server == null) return false;
        String email = rawEmail != null ? rawEmail.trim().toLowerCase(Locale.ROOT) : "";
        String username = player.getName().getString();
        String usernameKey = username.toLowerCase(Locale.ROOT);

        Map<String, String> bindings = loadBindings(server);
        String existingEmail = bindings.get(usernameKey);

        if (existingEmail != null && !existingEmail.isBlank()) {
            if (!existingEmail.equals(email)) {
                FabricP2PMod.LOGGER.warn("[IdentityGuard] Blocked nickname spoof for '{}'! Expected {}, got '{}'.",
                        username, existingEmail, email.isEmpty() ? "<not signed in>" : email);
                player.connection.disconnect(Component.literal(
                        "§c[FabricP2P] Access Denied!\n§7Username '" + username
                                + "' belongs to a different Google account."
                ));
                return false;
            }
            verifiedThisSession.add(player.getUUID());
            return true;
        }

        // First time this username joins with a Google account -> bind it!
        if (!email.isBlank() && email.contains("@")) {
            bindings.put(usernameKey, email);
            saveBindings(server, bindings);
            verifiedThisSession.add(player.getUUID());
            FabricP2PMod.LOGGER.info("[IdentityGuard] Bound username '{}' -> Google account {}", username, email);
            player.sendSystemMessage(Component.literal(
                    "§a🔒 [FabricP2P] §fUsername §b" + username + " §fis now protected by your Google account!"
            ));
            return true;
        }

        return true;
    }

    public static void onPlayerDisconnect(UUID uuid) {
        verifiedThisSession.remove(uuid);
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
