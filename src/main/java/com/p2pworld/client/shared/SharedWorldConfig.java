package com.p2pworld.client.shared;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.p2pworld.FabricP2PMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Persistent configuration for the toggleable Shared Cloud Worlds feature.
 * Stored in .minecraft/config/fabricp2p_shared_worlds.json.
 */
public class SharedWorldConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance()
            .getConfigDir()
            .resolve("fabricp2p_shared_worlds.json");

    private static SharedWorldConfig instance;

    private boolean enabled = false;
    private String syncFolderPath = "";
    private boolean autoSyncOnExit = true;
    private String lastSharedWorldName = "";

    // Google OAuth 2.0 + Google Drive API v3 fields
    private String googleClientId = "";
    private String googleClientSecret = "";
    private String googleAccessToken = "";
    private String googleRefreshToken = "";
    private String googleIdToken = "";
    private long googleTokenExpiryMs = 0L;
    private String googleUserEmail = "";
    private String googleDriveFolderId = "";

    // Individually shared world folders (worldName -> Google Drive folderId)
    // Kept separate from googleDriveFolderId so joining a friend's world never overwrites the user's own root folder.
    private Map<String, String> linkedDriveWorlds = new LinkedHashMap<>();

    private SharedWorldConfig() {}

    public static synchronized SharedWorldConfig getInstance() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    public synchronized boolean isEnabled() {
        return enabled;
    }

    public synchronized void setEnabled(boolean enabled) {
        this.enabled = enabled;
        save();
    }

    public synchronized String getSyncFolderPath() {
        return syncFolderPath != null ? syncFolderPath : "";
    }

    public synchronized void setSyncFolderPath(String path) {
        this.syncFolderPath = path != null ? path.trim() : "";
        save();
    }

    public synchronized boolean isAutoSyncOnExit() {
        return autoSyncOnExit;
    }

    public synchronized void setAutoSyncOnExit(boolean autoSyncOnExit) {
        this.autoSyncOnExit = autoSyncOnExit;
        save();
    }

    public synchronized String getLastSharedWorldName() {
        return lastSharedWorldName != null ? lastSharedWorldName : "";
    }

    public synchronized void setLastSharedWorldName(String name) {
        this.lastSharedWorldName = name != null ? name.trim() : "";
        save();
    }

    public synchronized String getGoogleClientId() {
        return googleClientId != null ? googleClientId : "";
    }

    public synchronized void setGoogleClientId(String clientId) {
        this.googleClientId = clientId != null ? clientId.trim() : "";
        save();
    }

    public synchronized String getGoogleClientSecret() {
        return googleClientSecret != null ? googleClientSecret : "";
    }

    public synchronized void setGoogleClientSecret(String clientSecret) {
        this.googleClientSecret = clientSecret != null ? clientSecret.trim() : "";
        save();
    }

    public synchronized String getGoogleAccessToken() {
        return googleAccessToken != null ? googleAccessToken : "";
    }

    public synchronized String getGoogleRefreshToken() {
        return googleRefreshToken != null ? googleRefreshToken : "";
    }

    public synchronized String getGoogleIdToken() {
        return googleIdToken != null ? googleIdToken : "";
    }

    public synchronized long getGoogleTokenExpiryMs() {
        return googleTokenExpiryMs;
    }

    public synchronized String getGoogleUserEmail() {
        return googleUserEmail != null ? googleUserEmail : "";
    }

    public synchronized String getGoogleDriveFolderId() {
        return googleDriveFolderId != null ? googleDriveFolderId : "";
    }

    public synchronized void setGoogleDriveFolderId(String folderId) {
        this.googleDriveFolderId = folderId != null ? folderId.trim() : "";
        save();
    }

    public synchronized Map<String, String> getLinkedDriveWorlds() {
        if (linkedDriveWorlds == null) {
            linkedDriveWorlds = new LinkedHashMap<>();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(linkedDriveWorlds));
    }

    public synchronized String getLinkedDriveFolderId(String worldName) {
        if (linkedDriveWorlds == null || worldName == null) return null;
        return linkedDriveWorlds.get(worldName);
    }

    public synchronized void putLinkedDriveWorld(String worldName, String folderId) {
        if (worldName == null || worldName.isBlank() || folderId == null || folderId.isBlank()) {
            return;
        }
        if (linkedDriveWorlds == null) {
            linkedDriveWorlds = new LinkedHashMap<>();
        }
        linkedDriveWorlds.put(worldName.trim(), folderId.trim());
        save();
    }

    public synchronized boolean isGoogleSignedIn() {
        return googleRefreshToken != null && !googleRefreshToken.isBlank()
                && googleUserEmail != null && !googleUserEmail.isBlank()
                && googleUserEmail.contains("@");
    }

    public synchronized void setGoogleTokens(String accessToken, String refreshToken, String idToken, long expiryMs, String email) {
        this.googleAccessToken = accessToken != null ? accessToken : "";
        if (refreshToken != null && !refreshToken.isBlank()) {
            this.googleRefreshToken = refreshToken;
        }
        if (idToken != null && !idToken.isBlank()) {
            this.googleIdToken = idToken;
        }
        this.googleTokenExpiryMs = expiryMs;
        if (email != null && !email.isBlank() && email.contains("@")) {
            this.googleUserEmail = email.trim();
        }
        save();
    }

    public synchronized void clearGoogleAuth() {
        this.googleAccessToken = "";
        this.googleRefreshToken = "";
        this.googleIdToken = "";
        this.googleTokenExpiryMs = 0L;
        this.googleUserEmail = "";
        this.googleDriveFolderId = "";
        this.lastSharedWorldName = "";
        if (this.linkedDriveWorlds != null) {
            this.linkedDriveWorlds.clear();
        }
        save();
    }

    public synchronized boolean hasValidSyncFolder() {
        if (syncFolderPath == null || syncFolderPath.isBlank()) return false;
        try {
            Path p = Path.of(syncFolderPath);
            return Files.exists(p) && Files.isDirectory(p);
        } catch (Exception e) {
            return false;
        }
    }

    private static SharedWorldConfig load() {
        if (Files.exists(CONFIG_PATH)) {
            try {
                String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
                SharedWorldConfig loaded = GSON.fromJson(json, SharedWorldConfig.class);
                if (loaded != null) {
                    if (loaded.linkedDriveWorlds == null) {
                        loaded.linkedDriveWorlds = new LinkedHashMap<>();
                    }
                    return loaded;
                }
            } catch (Exception e) {
                FabricP2PMod.LOGGER.warn("[SharedWorlds] Failed to load config: {}", e.getMessage());
            }
        }
        return new SharedWorldConfig();
    }

    public synchronized void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            Path tmp = CONFIG_PATH.resolveSibling(CONFIG_PATH.getFileName().toString() + ".tmp");
            Files.writeString(tmp, GSON.toJson(this), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, CONFIG_PATH, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp, CONFIG_PATH, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            FabricP2PMod.LOGGER.warn("[SharedWorlds] Failed to save config: {}", e.getMessage());
        }
    }
}
