package com.p2pworld.client.shared;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.p2pworld.FabricP2PMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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
    private long googleTokenExpiryMs = 0L;
    private String googleUserEmail = "";
    private String googleDriveFolderId = "";

    private SharedWorldConfig() {}

    public static synchronized SharedWorldConfig getInstance() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        save();
    }

    public String getSyncFolderPath() {
        return syncFolderPath != null ? syncFolderPath : "";
    }

    public void setSyncFolderPath(String path) {
        this.syncFolderPath = path != null ? path.trim() : "";
        save();
    }

    public boolean isAutoSyncOnExit() {
        return autoSyncOnExit;
    }

    public void setAutoSyncOnExit(boolean autoSyncOnExit) {
        this.autoSyncOnExit = autoSyncOnExit;
        save();
    }

    public String getLastSharedWorldName() {
        return lastSharedWorldName != null ? lastSharedWorldName : "";
    }

    public void setLastSharedWorldName(String name) {
        this.lastSharedWorldName = name != null ? name.trim() : "";
        save();
    }

    public String getGoogleClientId() {
        return googleClientId != null ? googleClientId : "";
    }

    public void setGoogleClientId(String clientId) {
        this.googleClientId = clientId != null ? clientId.trim() : "";
        save();
    }

    public String getGoogleClientSecret() {
        return googleClientSecret != null ? googleClientSecret : "";
    }

    public void setGoogleClientSecret(String clientSecret) {
        this.googleClientSecret = clientSecret != null ? clientSecret.trim() : "";
        save();
    }

    public String getGoogleAccessToken() {
        return googleAccessToken != null ? googleAccessToken : "";
    }

    public String getGoogleRefreshToken() {
        return googleRefreshToken != null ? googleRefreshToken : "";
    }

    public long getGoogleTokenExpiryMs() {
        return googleTokenExpiryMs;
    }

    public String getGoogleUserEmail() {
        return googleUserEmail != null ? googleUserEmail : "";
    }

    public String getGoogleDriveFolderId() {
        return googleDriveFolderId != null ? googleDriveFolderId : "";
    }

    public void setGoogleDriveFolderId(String folderId) {
        this.googleDriveFolderId = folderId != null ? folderId.trim() : "";
        save();
    }

    public boolean isGoogleSignedIn() {
        return googleRefreshToken != null && !googleRefreshToken.isBlank()
                && googleUserEmail != null && !googleUserEmail.isBlank();
    }

    public void setGoogleTokens(String accessToken, String refreshToken, long expiryMs, String email) {
        this.googleAccessToken = accessToken != null ? accessToken : "";
        if (refreshToken != null && !refreshToken.isBlank()) {
            this.googleRefreshToken = refreshToken;
        }
        this.googleTokenExpiryMs = expiryMs;
        if (email != null && !email.isBlank()) {
            this.googleUserEmail = email;
        }
        save();
    }

    public void clearGoogleAuth() {
        this.googleAccessToken = "";
        this.googleRefreshToken = "";
        this.googleTokenExpiryMs = 0L;
        this.googleUserEmail = "";
        save();
    }

    public boolean hasValidSyncFolder() {
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
                    return loaded;
                }
            } catch (Exception e) {
                FabricP2PMod.LOGGER.warn("[SharedWorlds] Failed to load config: {}", e.getMessage());
            }
        }
        return new SharedWorldConfig();
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            Files.writeString(CONFIG_PATH, GSON.toJson(this), StandardCharsets.UTF_8);
        } catch (IOException e) {
            FabricP2PMod.LOGGER.warn("[SharedWorlds] Failed to save config: {}", e.getMessage());
        }
    }
}
