package com.p2pworld.client.shared;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.p2pworld.FabricP2PMod;
import com.p2pworld.client.network.P2PNetworkManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Manages Shared Cloud Worlds inside a user-chosen synced folder
 * (Google Drive Desktop, OneDrive, Dropbox, Syncthing, etc.):
 * - Live Host Lockfile (session.lock.json) with 10s heartbeat & 45s stale expiry
 * - Atomic world zip upload & pull
 * - Automatic rotating host: whoever launches when idle becomes Host; others auto-join them!
 */
public class SharedWorldManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final long STALE_LOCK_MS = 45_000L;
    private static final String LOCK_FILE = "session.lock.json";
    private static final String META_FILE = "meta.json";
    private static final String WORLD_ZIP = "world_latest.zip";

    private static SharedWorldManager instance;

    private volatile String activeHostedWorldName = null;
    private volatile Path activeHostedLocalDir = null;
    private volatile String activeRoomCode = null;
    private volatile String activeModeName = null;
    private volatile boolean syncing = false;
    private volatile ScheduledExecutorService heartbeatExec = null;

    private SharedWorldManager() {}

    public static synchronized SharedWorldManager getInstance() {
        if (instance == null) {
            instance = new SharedWorldManager();
        }
        return instance;
    }

    public boolean isSyncing() {
        return syncing;
    }

    public String getActiveHostedWorldName() {
        return activeHostedWorldName;
    }

    /**
     * Scans either Google Drive (when signed in) or the configured local sync folder for shared worlds.
     */
    public List<SharedWorldEntry> listSharedWorlds() {
        List<SharedWorldEntry> results = new ArrayList<>();
        SharedWorldConfig cfg = SharedWorldConfig.getInstance();
        if (!cfg.isEnabled()) {
            return results;
        }

        if (cfg.isGoogleSignedIn() && !cfg.hasValidSyncFolder()) {
            try {
                GoogleDriveService drive = GoogleDriveService.getInstance();
                String rootId = drive.getOrCreateRootFolderId();
                for (GoogleDriveService.DriveItem folder : drive.listSubfolders(rootId)) {
                    String metaJson = drive.readTextFile(folder.id(), META_FILE);
                    String lockJson = drive.readTextFile(folder.id(), LOCK_FILE);
                    WorldMeta meta = metaJson != null ? GSON.fromJson(metaJson, WorldMeta.class) : null;
                    SessionLock lock = lockJson != null ? GSON.fromJson(lockJson, SessionLock.class) : null;

                    int rev = (meta != null) ? meta.revision : 1;
                    String lastHost = (meta != null && meta.lastHost != null) ? meta.lastHost : "Google Drive";
                    long updatedAt = (meta != null) ? meta.updatedAtMs : 0L;
                    boolean live = lock != null && lock.active && lock.roomCode != null && !lock.roomCode.isBlank()
                            && (System.currentTimeMillis() - lock.heartbeatMs) < STALE_LOCK_MS;

                    results.add(new SharedWorldEntry(
                            folder.name(), rev, lastHost, updatedAt, true,
                            live, live ? lock.hostName : null, live ? lock.roomCode : null, live ? lock.connectionMode : null
                    ));
                }
            } catch (Exception e) {
                FabricP2PMod.LOGGER.warn("[SharedWorlds] Google Drive list error: {}", e.getMessage());
            }
            return results;
        }

        if (!cfg.hasValidSyncFolder()) {
            return results;
        }

        Path root = Path.of(cfg.getSyncFolderPath());
        try (Stream<Path> dirs = Files.list(root)) {
            dirs.filter(Files::isDirectory).forEach(dir -> {
                Path zipPath = dir.resolve(WORLD_ZIP);
                Path metaPath = dir.resolve(META_FILE);
                Path lockPath = dir.resolve(LOCK_FILE);

                if (!Files.exists(zipPath) && !Files.exists(metaPath) && !Files.exists(lockPath)) {
                    return;
                }

                String worldName = dir.getFileName().toString();
                WorldMeta meta = readJson(metaPath, WorldMeta.class);
                SessionLock lock = readJson(lockPath, SessionLock.class);

                int rev = (meta != null) ? meta.revision : 1;
                String lastHost = (meta != null && meta.lastHost != null) ? meta.lastHost : "Unknown";
                long updatedAt = (meta != null) ? meta.updatedAtMs : 0L;

                boolean live = lock != null
                        && lock.active
                        && lock.roomCode != null
                        && !lock.roomCode.isBlank()
                        && (System.currentTimeMillis() - lock.heartbeatMs) < STALE_LOCK_MS;

                results.add(new SharedWorldEntry(
                        worldName,
                        rev,
                        lastHost,
                        updatedAt,
                        Files.exists(zipPath),
                        live,
                        live ? lock.hostName : null,
                        live ? lock.roomCode : null,
                        live ? lock.connectionMode : null
                ));
            });
        } catch (Exception e) {
            FabricP2PMod.LOGGER.warn("[SharedWorlds] Failed to list shared worlds: {}", e.getMessage());
        }

        results.sort(Comparator.comparing(SharedWorldEntry::live).reversed()
                .thenComparing(SharedWorldEntry::updatedAtMs, Comparator.reverseOrder()));
        return results;
    }

    /**
     * Uploads the currently open singleplayer world into Google Drive or the local shared folder.
     */
    public CompletableFuture<SyncOpResult> uploadCurrentWorldAsync(Minecraft mc) {
        SharedWorldConfig cfg = SharedWorldConfig.getInstance();
        if (!cfg.isEnabled() || (!cfg.hasValidSyncFolder() && !cfg.isGoogleSignedIn())) {
            return CompletableFuture.completedFuture(new SyncOpResult(false, "Sign in with Google or set a Sync Folder first."));
        }
        if (mc == null || mc.getSingleplayerServer() == null) {
            return CompletableFuture.completedFuture(new SyncOpResult(false, "Open a singleplayer world first."));
        }

        IntegratedServer server = mc.getSingleplayerServer();
        Path worldDir = server.getWorldPath(LevelResource.ROOT).normalize();
        String worldName = sanitizeName(worldDir.getFileName().toString());
        String hostName = mc.getUser() != null ? mc.getUser().getName() : "Host";

        // Flush chunks to disk on server thread before zipping
        CompletableFuture<Void> saveFuture = new CompletableFuture<>();
        server.execute(() -> {
            try {
                server.saveEverything(true, true, true);
                saveFuture.complete(null);
            } catch (Throwable t) {
                saveFuture.completeExceptionally(t);
            }
        });

        return saveFuture.thenApplyAsync(v -> {
            if (cfg.isGoogleSignedIn() && !cfg.hasValidSyncFolder()) {
                return uploadWorldToGoogleDrive(worldDir, worldName, hostName);
            }
            return uploadWorldDirectory(worldDir, worldName, hostName, true);
        });
    }

    private SyncOpResult uploadWorldToGoogleDrive(Path worldDir, String worldName, String hostName) {
        this.syncing = true;
        Path tmpZip = null;
        try {
            tmpZip = Files.createTempFile("fp2p_" + worldName + "_", ".zip");
            zipDirectory(worldDir, tmpZip);

            GoogleDriveService drive = GoogleDriveService.getInstance();
            String rootId = drive.getOrCreateRootFolderId();
            String worldFolderId = drive.getOrCreateSubfolder(rootId, worldName);
            drive.uploadFile(worldFolderId, WORLD_ZIP, "application/zip", tmpZip);

            String oldMetaJson = drive.readTextFile(worldFolderId, META_FILE);
            WorldMeta oldMeta = oldMetaJson != null ? GSON.fromJson(oldMetaJson, WorldMeta.class) : null;
            int nextRev = (oldMeta != null ? oldMeta.revision : 0) + 1;

            WorldMeta newMeta = new WorldMeta();
            newMeta.worldName = worldName;
            newMeta.revision = nextRev;
            newMeta.lastHost = hostName;
            newMeta.updatedAtMs = System.currentTimeMillis();
            drive.uploadTextFile(worldFolderId, META_FILE, GSON.toJson(newMeta));

            SharedWorldConfig.getInstance().setLastSharedWorldName(worldName);
            return new SyncOpResult(true, "Uploaded '" + worldName + "' (Rev #" + nextRev + ") to Google Drive!");
        } catch (Exception e) {
            return new SyncOpResult(false, "Google Drive upload failed: " + e.getMessage());
        } finally {
            this.syncing = false;
            if (tmpZip != null) {
                try { Files.deleteIfExists(tmpZip); } catch (IOException ignored) {}
            }
        }
    }

    /**
     * Pulls the latest zip of a shared world from Google Drive or the local sync folder into .minecraft/saves/<worldName>.
     */
    public CompletableFuture<SyncOpResult> pullWorldToLocalSavesAsync(Minecraft mc, String worldName) {
        SharedWorldConfig cfg = SharedWorldConfig.getInstance();
        if (!cfg.isEnabled() || (!cfg.hasValidSyncFolder() && !cfg.isGoogleSignedIn())) {
            return CompletableFuture.completedFuture(new SyncOpResult(false, "Sign in with Google or set a Sync Folder first."));
        }

        return CompletableFuture.supplyAsync(() -> {
            this.syncing = true;
            Path downloadedTempZip = null;
            try {
                Path zipPath;
                if (cfg.isGoogleSignedIn() && !cfg.hasValidSyncFolder()) {
                    GoogleDriveService drive = GoogleDriveService.getInstance();
                    String rootId = drive.getOrCreateRootFolderId();
                    String worldFolderId = drive.getOrCreateSubfolder(rootId, worldName);
                    downloadedTempZip = Files.createTempFile("fp2p_dl_", ".zip");
                    boolean ok = drive.downloadFile(worldFolderId, WORLD_ZIP, downloadedTempZip);
                    if (!ok) {
                        return new SyncOpResult(false, "World archive not found on Google Drive.");
                    }
                    zipPath = downloadedTempZip;
                } else {
                    Path sharedDir = Path.of(cfg.getSyncFolderPath()).resolve(worldName);
                    zipPath = sharedDir.resolve(WORLD_ZIP);
                    if (!Files.exists(zipPath)) {
                        return new SyncOpResult(false, "World archive not found in shared folder.");
                    }
                    SessionLock lock = readJson(sharedDir.resolve(LOCK_FILE), SessionLock.class);
                    if (lock != null && lock.active && (System.currentTimeMillis() - lock.heartbeatMs) < STALE_LOCK_MS) {
                        return new SyncOpResult(false, "World is currently hosted by " + lock.hostName + "! Join them instead.");
                    }
                }

                Path savesDir = mc.gameDirectory.toPath().resolve("saves");
                Path targetWorldDir = savesDir.resolve(worldName);
                Path tempExtractDir = savesDir.resolve(worldName + "_sync_tmp");

                deleteDirectoryQuietly(tempExtractDir);
                Files.createDirectories(tempExtractDir);
                unzipDirectory(zipPath, tempExtractDir);

                // Atomic swap with backup
                Path backupDir = savesDir.resolve(worldName + "_backup_prev");
                deleteDirectoryQuietly(backupDir);
                if (Files.exists(targetWorldDir)) {
                    Files.move(targetWorldDir, backupDir, StandardCopyOption.REPLACE_EXISTING);
                }
                Files.move(tempExtractDir, targetWorldDir, StandardCopyOption.REPLACE_EXISTING);

                cfg.setLastSharedWorldName(worldName);
                FabricP2PMod.LOGGER.info("[SharedWorlds] Pulled latest '{}' into local saves!", worldName);
                return new SyncOpResult(true, "Synced latest '" + worldName + "'! You can now open & host it.");
            } catch (Exception e) {
                FabricP2PMod.LOGGER.error("[SharedWorlds] Failed to pull world '{}': {}", worldName, e.getMessage());
                return new SyncOpResult(false, "Pull failed: " + e.getMessage());
            } finally {
                this.syncing = false;
                if (downloadedTempZip != null) {
                    try { Files.deleteIfExists(downloadedTempZip); } catch (IOException ignored) {}
                }
            }
        });
    }

    /**
     * Called by P2PNetworkManager when hosting becomes LIVE.
     * If Shared Worlds is enabled and this world is in the shared folder, writes session.lock.json & starts heartbeat.
     */
    public void onP2PHostStarted(Minecraft mc, String roomCode, P2PNetworkManager.ActiveHostingMode mode) {
        SharedWorldConfig cfg = SharedWorldConfig.getInstance();
        if (!cfg.isEnabled() || !cfg.hasValidSyncFolder() || mc == null || mc.getSingleplayerServer() == null) {
            return;
        }

        IntegratedServer server = mc.getSingleplayerServer();
        Path worldDir = server.getWorldPath(LevelResource.ROOT).normalize();
        String worldName = sanitizeName(worldDir.getFileName().toString());
        Path sharedDir = Path.of(cfg.getSyncFolderPath()).resolve(worldName);

        // Only auto-lock if this world is already tracked in the shared folder (or matches lastSharedWorldName)
        if (!Files.exists(sharedDir) && !worldName.equals(cfg.getLastSharedWorldName())) {
            return;
        }

        this.activeHostedWorldName = worldName;
        this.activeHostedLocalDir = worldDir;
        this.activeRoomCode = roomCode;
        this.activeModeName = mode.name();
        String hostName = mc.getUser() != null ? mc.getUser().getName() : "Host";

        writeSessionLock(sharedDir, true, hostName, roomCode, mode.name(), worldName);

        stopHeartbeat();
        this.heartbeatExec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "FabricP2P-SharedWorld-Heartbeat");
            t.setDaemon(true);
            return t;
        });
        this.heartbeatExec.scheduleAtFixedRate(() -> {
            if (activeHostedWorldName != null && activeRoomCode != null) {
                writeSessionLock(sharedDir, true, hostName, activeRoomCode, activeModeName, worldName);
            }
        }, 10, 10, TimeUnit.SECONDS);

        FabricP2PMod.LOGGER.info("[SharedWorlds] Acquired live lock for '{}' (RoomCode={}).", worldName, roomCode);
    }

    /**
     * Called when P2P hosting stops or the host disconnects from the world.
     * Automatically syncs the updated world back to the shared folder and releases session.lock.json!
     */
    public void onP2PHostStopped(Minecraft mc) {
        stopHeartbeat();
        String worldName = this.activeHostedWorldName;
        Path worldDir = this.activeHostedLocalDir;
        this.activeHostedWorldName = null;
        this.activeHostedLocalDir = null;
        this.activeRoomCode = null;
        this.activeModeName = null;

        SharedWorldConfig cfg = SharedWorldConfig.getInstance();
        if (worldName == null || !cfg.isEnabled() || !cfg.hasValidSyncFolder()) {
            return;
        }

        Path sharedDir = Path.of(cfg.getSyncFolderPath()).resolve(worldName);
        String hostName = (mc != null && mc.getUser() != null) ? mc.getUser().getName() : "Host";

        CompletableFuture.runAsync(() -> {
            try {
                // Brief wait for MC to finish flushing region files on disconnect
                Thread.sleep(1200);
                if (cfg.isAutoSyncOnExit() && worldDir != null && Files.exists(worldDir)) {
                    uploadWorldDirectory(worldDir, worldName, hostName, false);
                }
            } catch (Exception e) {
                FabricP2PMod.LOGGER.warn("[SharedWorlds] Auto-sync on exit failed: {}", e.getMessage());
            } finally {
                writeSessionLock(sharedDir, false, hostName, "", "", worldName);
                FabricP2PMod.LOGGER.info("[SharedWorlds] Released lock for '{}'.", worldName);
            }
        });
    }

    private SyncOpResult uploadWorldDirectory(Path worldDir, String worldName, String hostName, boolean preserveLockIfActive) {
        SharedWorldConfig cfg = SharedWorldConfig.getInstance();
        this.syncing = true;
        try {
            Path sharedDir = Path.of(cfg.getSyncFolderPath()).resolve(worldName);
            Files.createDirectories(sharedDir);

            Path tmpZip = sharedDir.resolve(WORLD_ZIP + ".tmp");
            Path finalZip = sharedDir.resolve(WORLD_ZIP);
            zipDirectory(worldDir, tmpZip);
            Files.move(tmpZip, finalZip, StandardCopyOption.REPLACE_EXISTING);

            Path metaPath = sharedDir.resolve(META_FILE);
            WorldMeta oldMeta = readJson(metaPath, WorldMeta.class);
            int nextRev = (oldMeta != null ? oldMeta.revision : 0) + 1;

            WorldMeta newMeta = new WorldMeta();
            newMeta.worldName = worldName;
            newMeta.revision = nextRev;
            newMeta.lastHost = hostName;
            newMeta.updatedAtMs = System.currentTimeMillis();
            Files.writeString(metaPath, GSON.toJson(newMeta), StandardCharsets.UTF_8);

            cfg.setLastSharedWorldName(worldName);

            if (!preserveLockIfActive) {
                writeSessionLock(sharedDir, false, hostName, "", "", worldName);
            }

            FabricP2PMod.LOGGER.info("[SharedWorlds] Uploaded '{}' Rev #{} to shared folder!", worldName, nextRev);
            return new SyncOpResult(true, "Uploaded '" + worldName + "' (Rev #" + nextRev + ") to shared folder!");
        } catch (Exception e) {
            FabricP2PMod.LOGGER.error("[SharedWorlds] Upload failed for '{}': {}", worldName, e.getMessage());
            return new SyncOpResult(false, "Upload failed: " + e.getMessage());
        } finally {
            this.syncing = false;
        }
    }

    private void writeSessionLock(Path sharedDir, boolean active, String hostName, String roomCode, String mode, String worldName) {
        try {
            Files.createDirectories(sharedDir);
            SessionLock lock = new SessionLock();
            lock.active = active;
            lock.hostName = hostName;
            lock.roomCode = roomCode;
            lock.connectionMode = mode;
            lock.worldName = worldName;
            lock.heartbeatMs = System.currentTimeMillis();

            Path tmp = sharedDir.resolve(LOCK_FILE + ".tmp");
            Path target = sharedDir.resolve(LOCK_FILE);
            Files.writeString(tmp, GSON.toJson(lock), StandardCharsets.UTF_8);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            FabricP2PMod.LOGGER.warn("[SharedWorlds] Failed to write session lock: {}", e.getMessage());
        }
    }

    private void stopHeartbeat() {
        if (heartbeatExec != null) {
            heartbeatExec.shutdownNow();
            heartbeatExec = null;
        }
    }

    private static void zipDirectory(Path sourceDir, Path zipFile) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipFile));
             Stream<Path> walk = Files.walk(sourceDir)) {
            for (Path p : walk.toList()) {
                if (Files.isDirectory(p)) continue;
                String fileName = p.getFileName().toString();
                if (fileName.equals("session.lock")) continue; // Skip MC's OS file lock

                String rel = sourceDir.relativize(p).toString().replace("\\", "/");
                zos.putNextEntry(new ZipEntry(rel));
                try (InputStream is = Files.newInputStream(p)) {
                    is.transferTo(zos);
                } catch (IOException ignored) {}
                zos.closeEntry();
            }
        }
    }

    private static void unzipDirectory(Path zipFile, Path targetDir) throws IOException {
        Path normalizedTarget = targetDir.toAbsolutePath().normalize();
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                Path outPath = normalizedTarget.resolve(entry.getName()).normalize();
                if (!outPath.startsWith(normalizedTarget)) {
                    throw new IOException("Blocked zip-slip entry: " + entry.getName());
                }
                Files.createDirectories(outPath.getParent());
                try (OutputStream os = Files.newOutputStream(outPath)) {
                    zis.transferTo(os);
                }
                zis.closeEntry();
            }
        }
    }

    private static void deleteDirectoryQuietly(Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
            });
        } catch (IOException ignored) {}
    }

    private static <T> T readJson(Path path, Class<T> clazz) {
        if (!Files.exists(path)) return null;
        try {
            return GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), clazz);
        } catch (Exception e) {
            return null;
        }
    }

    private static String sanitizeName(String name) {
        return name.replaceAll("[^a-zA-Z0-9._ -]", "_").trim();
    }

    private static final class WorldMeta {
        String worldName;
        int revision;
        String lastHost;
        long updatedAtMs;
    }

    private static final class SessionLock {
        boolean active;
        String hostName;
        String roomCode;
        String connectionMode;
        String worldName;
        long heartbeatMs;
    }

    public record SharedWorldEntry(
            String worldName,
            int revision,
            String lastHost,
            long updatedAtMs,
            boolean hasArchive,
            boolean live,
            String liveHostName,
            String liveRoomCode,
            String liveMode
    ) {}

    public record SyncOpResult(boolean success, String message) {}
}
