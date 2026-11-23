package com.p2pworld.client.shared;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.p2pworld.FabricP2PMod;
import com.p2pworld.client.network.P2PNetworkManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Manages Shared Cloud Worlds with SHA-256 Incremental (Delta) Sync!
 * Only uploads/downloads NEW or MODIFIED files (unchanged region files are skipped: 0 bytes transferred).
 * Supports both direct Google Drive API v3 and local synced folders (Google Drive Desktop / OneDrive / Syncthing).
 */
public class SharedWorldManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final long STALE_LOCK_MS = 45_000L;
    private static final String LOCK_FILE = "session.lock.json";
    private static final String META_FILE = "meta.json";
    private static final String MANIFEST_FILE = "manifest.json";
    private static final String FILES_SUBDIR = "files";

    private static SharedWorldManager instance;

    private volatile String activeHostedWorldName = null;
    private volatile Path activeHostedLocalDir = null;
    private volatile String activeRoomCode = null;
    private volatile String activeModeName = null;
    private volatile boolean syncing = false;
    private volatile ScheduledExecutorService heartbeatExec = null;
    private final Set<String> knownDriveWorldNames = ConcurrentHashMap.newKeySet();

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
     * Checks whether the given server's world is registered as a Shared Cloud World.
     */
    public boolean isCurrentWorldShared(MinecraftServer server) {
        if (server == null) return false;
        SharedWorldConfig cfg = SharedWorldConfig.getInstance();
        if (!cfg.isEnabled()) return false;
        String worldName = sanitizeName(server.getWorldPath(LevelResource.ROOT).normalize().getFileName().toString());
        if (worldName.equals(activeHostedWorldName)) return true;
        if (worldName.equals(cfg.getLastSharedWorldName())) return true;
        if (cfg.getLinkedDriveFolderId(worldName) != null) return true;
        if (knownDriveWorldNames.contains(worldName)) return true;
        if (cfg.hasValidSyncFolder()) {
            Path sharedDir = Path.of(cfg.getSyncFolderPath()).resolve(worldName).normalize();
            return Files.exists(sharedDir);
        }
        return false;
    }

    /**
     * Resolves (or creates) the Drive folder ID for a specific shared world, respecting individually linked worlds.
     */
    public String resolveWorldDriveFolderId(String worldName, boolean createIfMissing) throws IOException {
        String safeName = sanitizeName(worldName);
        SharedWorldConfig cfg = SharedWorldConfig.getInstance();
        String linkedId = cfg.getLinkedDriveFolderId(safeName);
        if (linkedId != null && !linkedId.isBlank()) {
            return linkedId;
        }
        GoogleDriveService drive = GoogleDriveService.getInstance();
        String rootId = drive.getOrCreateRootFolderId();
        return createIfMissing
                ? drive.getOrCreateSubfolder(rootId, safeName)
                : drive.findSubfolder(rootId, safeName);
    }

    /**
     * Scans either Google Drive (when signed in) or the configured local sync folder for shared worlds.
     * NOTE: May perform network I/O — call from a background thread!
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
                Map<String, String> worldFolders = new LinkedHashMap<>();

                for (GoogleDriveService.DriveItem folder : drive.listSubfolders(rootId)) {
                    String safeName = sanitizeName(folder.name());
                    if (!safeName.isEmpty()) {
                        worldFolders.put(safeName, folder.id());
                    }
                }
                for (Map.Entry<String, String> linked : cfg.getLinkedDriveWorlds().entrySet()) {
                    String safeName = sanitizeName(linked.getKey());
                    if (!safeName.isEmpty()) {
                        worldFolders.putIfAbsent(safeName, linked.getValue());
                    }
                }

                knownDriveWorldNames.clear();
                knownDriveWorldNames.addAll(worldFolders.keySet());

                for (Map.Entry<String, String> wf : worldFolders.entrySet()) {
                    String worldName = wf.getKey();
                    String folderId = wf.getValue();
                    try {
                        String metaJson = drive.readTextFile(folderId, META_FILE);
                        String lockJson = drive.readTextFile(folderId, LOCK_FILE);
                        WorldMeta meta = parseJson(metaJson, WorldMeta.class);
                        SessionLock lock = parseJson(lockJson, SessionLock.class);

                        int rev = (meta != null) ? meta.revision : 1;
                        String lastHost = (meta != null && meta.lastHost != null) ? meta.lastHost : "Google Drive";
                        long updatedAt = (meta != null) ? meta.updatedAtMs : 0L;
                        boolean live = lock != null && lock.active && lock.roomCode != null && !lock.roomCode.isBlank()
                                && (System.currentTimeMillis() - lock.heartbeatMs) < STALE_LOCK_MS;

                        results.add(new SharedWorldEntry(
                                worldName, rev, lastHost, updatedAt, true,
                                live, live ? lock.hostName : null, live ? lock.roomCode : null, live ? lock.connectionMode : null
                        ));
                    } catch (Exception e) {
                        FabricP2PMod.LOGGER.warn("[SharedWorlds] Skipping Drive world '{}': {}", worldName, e.getMessage());
                    }
                }
            } catch (Exception e) {
                FabricP2PMod.LOGGER.warn("[SharedWorlds] Google Drive list error: {}", e.getMessage());
            }

            sortWorldEntries(results);
            return results;
        }

        if (!cfg.hasValidSyncFolder()) {
            return results;
        }

        Path root = Path.of(cfg.getSyncFolderPath());
        try (Stream<Path> dirs = Files.list(root)) {
            dirs.filter(Files::isDirectory).forEach(dir -> {
                Path manifestPath = dir.resolve(MANIFEST_FILE);
                Path metaPath = dir.resolve(META_FILE);
                Path lockPath = dir.resolve(LOCK_FILE);

                if (!Files.exists(manifestPath) && !Files.exists(metaPath) && !Files.exists(lockPath)) {
                    return;
                }

                String worldName = sanitizeName(dir.getFileName().toString());
                if (worldName.isEmpty()) return;

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
                        Files.exists(manifestPath),
                        live,
                        live ? lock.hostName : null,
                        live ? lock.roomCode : null,
                        live ? lock.connectionMode : null
                ));
            });
        } catch (Exception e) {
            FabricP2PMod.LOGGER.warn("[SharedWorlds] Failed to list shared worlds: {}", e.getMessage());
        }

        sortWorldEntries(results);
        return results;
    }

    private static void sortWorldEntries(List<SharedWorldEntry> results) {
        results.sort(Comparator.comparing(SharedWorldEntry::live).reversed()
                .thenComparing(SharedWorldEntry::updatedAtMs, Comparator.reverseOrder()));
    }

    /**
     * Incrementally uploads ONLY new or modified files of the current singleplayer world.
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
                return uploadIncrementalToGoogleDrive(worldDir, worldName, hostName);
            }
            return uploadIncrementalToLocalFolder(worldDir, worldName, hostName, true);
        });
    }

    /**
     * Incrementally pulls ONLY missing or modified files into .minecraft/saves/<worldName>,
     * and cleans up files removed in the latest revision.
     */
    public CompletableFuture<SyncOpResult> pullWorldToLocalSavesAsync(Minecraft mc, String worldName) {
        SharedWorldConfig cfg = SharedWorldConfig.getInstance();
        if (!cfg.isEnabled() || (!cfg.hasValidSyncFolder() && !cfg.isGoogleSignedIn())) {
            return CompletableFuture.completedFuture(new SyncOpResult(false, "Sign in with Google or set a Sync Folder first."));
        }

        String safeName = sanitizeName(worldName);
        if (safeName.isEmpty()) {
            return CompletableFuture.completedFuture(new SyncOpResult(false, "Invalid world name."));
        }

        Path savesRoot = mc.gameDirectory.toPath().resolve("saves").toAbsolutePath().normalize();
        Path targetWorldDir = savesRoot.resolve(safeName).normalize();
        if (!targetWorldDir.startsWith(savesRoot) || targetWorldDir.equals(savesRoot)) {
            return CompletableFuture.completedFuture(new SyncOpResult(false, "Unsafe target world path rejected."));
        }

        // Prevent overwriting an actively loaded singleplayer world on disk
        IntegratedServer openServer = mc.getSingleplayerServer();
        if (openServer != null) {
            Path openWorldDir = openServer.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
            if (openWorldDir.equals(targetWorldDir)) {
                return CompletableFuture.completedFuture(new SyncOpResult(
                        false, "Save & Quit to Title Screen before pulling updates into this open world."
                ));
            }
        }

        return CompletableFuture.supplyAsync(() -> {
            this.syncing = true;
            try {
                Files.createDirectories(targetWorldDir);

                if (cfg.isGoogleSignedIn() && !cfg.hasValidSyncFolder()) {
                    return pullIncrementalFromGoogleDrive(safeName, targetWorldDir);
                } else {
                    return pullIncrementalFromLocalFolder(cfg.getSyncFolderPath(), safeName, targetWorldDir);
                }
            } catch (Exception e) {
                FabricP2PMod.LOGGER.error("[SharedWorlds] Incremental pull failed for '{}': {}", safeName, e.getMessage());
                return new SyncOpResult(false, "Pull failed: " + e.getMessage());
            } finally {
                this.syncing = false;
            }
        });
    }

    // ==================== INCREMENTAL GOOGLE DRIVE SYNC ====================

    private SyncOpResult uploadIncrementalToGoogleDrive(Path worldDir, String worldName, String hostName) {
        this.syncing = true;
        try {
            GoogleDriveService drive = GoogleDriveService.getInstance();
            String worldFolderId = resolveWorldDriveFolderId(worldName, true);
            String filesFolderId = drive.getOrCreateSubfolder(worldFolderId, FILES_SUBDIR);

            String oldManifestJson = drive.readTextFile(worldFolderId, MANIFEST_FILE);
            WorldManifest oldManifest = parseJson(oldManifestJson, WorldManifest.class);
            if (oldManifest == null) oldManifest = new WorldManifest();
            if (oldManifest.files == null) oldManifest.files = new LinkedHashMap<>();

            WorldManifest newManifest = new WorldManifest();
            newManifest.revision = oldManifest.revision + 1;
            newManifest.lastHost = hostName;
            newManifest.updatedAtMs = System.currentTimeMillis();

            int uploaded = 0;
            int skipped = 0;

            List<Path> localFiles = collectWorldFiles(worldDir);
            for (Path p : localFiles) {
                String rel = worldDir.relativize(p).toString().replace("\\", "/");
                String sha = sha256(p);
                long size = Files.size(p);

                ManifestFile prev = oldManifest.files.get(rel);
                if (prev != null && sha.equals(prev.sha256) && prev.driveFileId != null && !prev.driveFileId.isBlank()) {
                    newManifest.files.put(rel, prev);
                    skipped++;
                } else {
                    String existingId = (prev != null) ? prev.driveFileId : null;
                    String fileId = drive.uploadOrUpdateFileById(filesFolderId, existingId, rel, "application/octet-stream", p);
                    ManifestFile mf = new ManifestFile();
                    mf.sha256 = sha;
                    mf.size = size;
                    mf.driveFileId = fileId;
                    newManifest.files.put(rel, mf);
                    uploaded++;
                }
            }

            // Delete files removed on host
            for (Map.Entry<String, ManifestFile> oldEntry : oldManifest.files.entrySet()) {
                if (!newManifest.files.containsKey(oldEntry.getKey()) && oldEntry.getValue() != null) {
                    drive.deleteFileById(oldEntry.getValue().driveFileId);
                }
            }

            drive.uploadTextFile(worldFolderId, MANIFEST_FILE, GSON.toJson(newManifest));

            WorldMeta meta = new WorldMeta();
            meta.worldName = worldName;
            meta.revision = newManifest.revision;
            meta.lastHost = hostName;
            meta.updatedAtMs = newManifest.updatedAtMs;
            drive.uploadTextFile(worldFolderId, META_FILE, GSON.toJson(meta));

            knownDriveWorldNames.add(worldName);
            SharedWorldConfig.getInstance().setLastSharedWorldName(worldName);
            FabricP2PMod.LOGGER.info("[SharedWorlds] Drive incremental sync '{}' Rev #{}: {} uploaded, {} skipped.",
                    worldName, newManifest.revision, uploaded, skipped);
            return new SyncOpResult(true, "Drive Sync Rev #" + newManifest.revision + ": " + uploaded + " changed, " + skipped + " skipped!");
        } catch (Exception e) {
            return new SyncOpResult(false, "Google Drive sync failed: " + e.getMessage());
        } finally {
            this.syncing = false;
        }
    }

    private SyncOpResult pullIncrementalFromGoogleDrive(String worldName, Path targetWorldDir) throws IOException {
        GoogleDriveService drive = GoogleDriveService.getInstance();
        String worldFolderId = resolveWorldDriveFolderId(worldName, false);
        if (worldFolderId == null || worldFolderId.isBlank()) {
            return new SyncOpResult(false, "World folder '" + worldName + "' not found on Google Drive.");
        }

        String lockJson = drive.readTextFile(worldFolderId, LOCK_FILE);
        SessionLock lock = parseJson(lockJson, SessionLock.class);
        if (lock != null && lock.active && (System.currentTimeMillis() - lock.heartbeatMs) < STALE_LOCK_MS) {
            return new SyncOpResult(false, "World is currently hosted by " + lock.hostName + "! Join them instead.");
        }

        String manifestJson = drive.readTextFile(worldFolderId, MANIFEST_FILE);
        if (manifestJson == null) {
            return new SyncOpResult(false, "No manifest found on Google Drive for '" + worldName + "'.");
        }
        WorldManifest manifest = parseJson(manifestJson, WorldManifest.class);
        if (manifest == null || manifest.files == null) {
            return new SyncOpResult(false, "Corrupt manifest on Google Drive.");
        }

        int downloaded = 0;
        int skipped = 0;
        int failed = 0;
        Path normalizedRoot = targetWorldDir.toAbsolutePath().normalize();
        Set<Path> expectedFiles = new HashSet<>();

        for (Map.Entry<String, ManifestFile> entry : manifest.files.entrySet()) {
            String rel = entry.getKey();
            ManifestFile mf = entry.getValue();
            if (rel == null || mf == null) continue;
            Path dest = normalizedRoot.resolve(rel).normalize();
            if (!dest.startsWith(normalizedRoot) || dest.equals(normalizedRoot)) continue;
            expectedFiles.add(dest);

            if (Files.exists(dest) && mf.sha256 != null && mf.sha256.equals(sha256(dest))) {
                skipped++;
                continue;
            }
            if (drive.downloadFileById(mf.driveFileId, dest)) {
                downloaded++;
            } else {
                failed++;
                FabricP2PMod.LOGGER.warn("[SharedWorlds] Failed to download '{}' (id={})", rel, mf.driveFileId);
            }
        }

        if (failed > 0) {
            return new SyncOpResult(false, "Incomplete pull: " + failed + " file(s) failed to download.");
        }

        pruneRemovedLocalFiles(normalizedRoot, expectedFiles);
        SharedWorldConfig.getInstance().setLastSharedWorldName(worldName);
        return new SyncOpResult(true, "Pulled Rev #" + manifest.revision + ": " + downloaded + " updated, " + skipped + " already up to date!");
    }

    // ==================== INCREMENTAL LOCAL FOLDER SYNC ====================

    private SyncOpResult uploadIncrementalToLocalFolder(Path worldDir, String worldName, String hostName, boolean preserveLockIfActive) {
        SharedWorldConfig cfg = SharedWorldConfig.getInstance();
        this.syncing = true;
        try {
            Path syncRoot = Path.of(cfg.getSyncFolderPath()).toAbsolutePath().normalize();
            Path sharedDir = syncRoot.resolve(worldName).normalize();
            if (!sharedDir.startsWith(syncRoot) || sharedDir.equals(syncRoot)) {
                return new SyncOpResult(false, "Invalid world name for sync folder.");
            }
            Path filesRoot = sharedDir.resolve(FILES_SUBDIR).normalize();
            Files.createDirectories(filesRoot);

            Path manifestPath = sharedDir.resolve(MANIFEST_FILE);
            WorldManifest oldManifest = readJson(manifestPath, WorldManifest.class);
            if (oldManifest == null) oldManifest = new WorldManifest();
            if (oldManifest.files == null) oldManifest.files = new LinkedHashMap<>();

            WorldManifest newManifest = new WorldManifest();
            newManifest.revision = oldManifest.revision + 1;
            newManifest.lastHost = hostName;
            newManifest.updatedAtMs = System.currentTimeMillis();

            int copied = 0;
            int skipped = 0;

            for (Path p : collectWorldFiles(worldDir)) {
                String rel = worldDir.relativize(p).toString().replace("\\", "/");
                String sha = sha256(p);
                long size = Files.size(p);

                Path sharedCopy = filesRoot.resolve(rel).normalize();
                if (!sharedCopy.startsWith(filesRoot) || sharedCopy.equals(filesRoot)) continue;

                ManifestFile prev = oldManifest.files.get(rel);
                if (prev != null && sha.equals(prev.sha256) && Files.exists(sharedCopy)) {
                    newManifest.files.put(rel, prev);
                    skipped++;
                } else {
                    Files.createDirectories(sharedCopy.getParent());
                    Files.copy(p, sharedCopy, StandardCopyOption.REPLACE_EXISTING);
                    ManifestFile mf = new ManifestFile();
                    mf.sha256 = sha;
                    mf.size = size;
                    newManifest.files.put(rel, mf);
                    copied++;
                }
            }

            // Clean up removed files safely inside filesRoot
            for (String oldRel : oldManifest.files.keySet()) {
                if (!newManifest.files.containsKey(oldRel)) {
                    Path oldFile = filesRoot.resolve(oldRel).normalize();
                    if (oldFile.startsWith(filesRoot) && !oldFile.equals(filesRoot)) {
                        try { Files.deleteIfExists(oldFile); } catch (IOException ignored) {}
                    }
                }
            }

            Files.writeString(manifestPath, GSON.toJson(newManifest), StandardCharsets.UTF_8);

            WorldMeta meta = new WorldMeta();
            meta.worldName = worldName;
            meta.revision = newManifest.revision;
            meta.lastHost = hostName;
            meta.updatedAtMs = newManifest.updatedAtMs;
            Files.writeString(sharedDir.resolve(META_FILE), GSON.toJson(meta), StandardCharsets.UTF_8);

            cfg.setLastSharedWorldName(worldName);
            if (!preserveLockIfActive) {
                writeSessionLock(sharedDir, false, hostName, "", "", worldName);
            }

            FabricP2PMod.LOGGER.info("[SharedWorlds] Local incremental sync '{}' Rev #{}: {} changed, {} skipped.",
                    worldName, newManifest.revision, copied, skipped);
            return new SyncOpResult(true, "Synced Rev #" + newManifest.revision + ": " + copied + " changed, " + skipped + " skipped!");
        } catch (Exception e) {
            return new SyncOpResult(false, "Sync failed: " + e.getMessage());
        } finally {
            this.syncing = false;
        }
    }

    private SyncOpResult pullIncrementalFromLocalFolder(String syncFolder, String worldName, Path targetWorldDir) throws IOException {
        Path syncRoot = Path.of(syncFolder).toAbsolutePath().normalize();
        Path sharedDir = syncRoot.resolve(worldName).normalize();
        if (!sharedDir.startsWith(syncRoot) || sharedDir.equals(syncRoot)) {
            return new SyncOpResult(false, "Invalid world path.");
        }
        SessionLock lock = readJson(sharedDir.resolve(LOCK_FILE), SessionLock.class);
        if (lock != null && lock.active && (System.currentTimeMillis() - lock.heartbeatMs) < STALE_LOCK_MS) {
            return new SyncOpResult(false, "World is currently hosted by " + lock.hostName + "! Join them instead.");
        }

        WorldManifest manifest = readJson(sharedDir.resolve(MANIFEST_FILE), WorldManifest.class);
        if (manifest == null || manifest.files == null) {
            return new SyncOpResult(false, "No manifest found in shared folder for '" + worldName + "'.");
        }

        Path filesRoot = sharedDir.resolve(FILES_SUBDIR).normalize();
        Path normalizedRoot = targetWorldDir.toAbsolutePath().normalize();
        int copied = 0;
        int skipped = 0;
        int missing = 0;
        Set<Path> expectedFiles = new HashSet<>();

        for (Map.Entry<String, ManifestFile> entry : manifest.files.entrySet()) {
            String rel = entry.getKey();
            ManifestFile mf = entry.getValue();
            if (rel == null || mf == null) continue;
            Path src = filesRoot.resolve(rel).normalize();
            Path dest = normalizedRoot.resolve(rel).normalize();
            if (!src.startsWith(filesRoot) || !dest.startsWith(normalizedRoot) || dest.equals(normalizedRoot)) continue;
            if (!Files.exists(src)) {
                missing++;
                continue;
            }
            expectedFiles.add(dest);

            if (Files.exists(dest) && mf.sha256 != null && mf.sha256.equals(sha256(dest))) {
                skipped++;
                continue;
            }
            Files.createDirectories(dest.getParent());
            Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
            copied++;
        }

        if (missing > 0) {
            return new SyncOpResult(false, "Incomplete pull: " + missing + " file(s) missing in sync folder.");
        }

        pruneRemovedLocalFiles(normalizedRoot, expectedFiles);
        SharedWorldConfig.getInstance().setLastSharedWorldName(worldName);
        return new SyncOpResult(true, "Pulled Rev #" + manifest.revision + ": " + copied + " updated, " + skipped + " already up to date!");
    }

    private static void pruneRemovedLocalFiles(Path normalizedRoot, Set<Path> expectedFiles) {
        try {
            for (Path existing : collectWorldFiles(normalizedRoot)) {
                Path norm = existing.toAbsolutePath().normalize();
                if (!expectedFiles.contains(norm) && norm.startsWith(normalizedRoot)) {
                    try {
                        Files.deleteIfExists(norm);
                    } catch (IOException ignored) {}
                }
            }
        } catch (IOException ignored) {}
    }

    // ==================== LIVE LOCK & HEARTBEAT ====================

    public void onP2PHostStarted(Minecraft mc, String roomCode, P2PNetworkManager.ActiveHostingMode mode) {
        SharedWorldConfig cfg = SharedWorldConfig.getInstance();
        if (!cfg.isEnabled() || (!cfg.hasValidSyncFolder() && !cfg.isGoogleSignedIn())
                || mc == null || mc.getSingleplayerServer() == null) {
            return;
        }

        IntegratedServer server = mc.getSingleplayerServer();
        Path worldDir = server.getWorldPath(LevelResource.ROOT).normalize();
        String worldName = sanitizeName(worldDir.getFileName().toString());
        String hostName = mc.getUser() != null ? mc.getUser().getName() : "Host";

        boolean useDrive = cfg.isGoogleSignedIn() && !cfg.hasValidSyncFolder();
        if (!useDrive) {
            Path sharedDir = Path.of(cfg.getSyncFolderPath()).resolve(worldName);
            if (!Files.exists(sharedDir) && !worldName.equals(cfg.getLastSharedWorldName())) {
                return;
            }
        } else {
            boolean matchesKnown = worldName.equals(cfg.getLastSharedWorldName())
                    || cfg.getLinkedDriveFolderId(worldName) != null
                    || knownDriveWorldNames.contains(worldName);
            if (!matchesKnown) {
                try {
                    if (resolveWorldDriveFolderId(worldName, false) == null) {
                        return;
                    }
                    knownDriveWorldNames.add(worldName);
                } catch (Exception e) {
                    return;
                }
            }
        }

        this.activeHostedWorldName = worldName;
        this.activeHostedLocalDir = worldDir;
        this.activeRoomCode = roomCode;
        this.activeModeName = mode.name();

        stopHeartbeat();
        this.heartbeatExec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "FabricP2P-SharedWorld-Heartbeat");
            t.setDaemon(true);
            return t;
        });

        Runnable publishLock = () -> {
            if (activeHostedWorldName == null || activeRoomCode == null) return;
            if (useDrive) {
                writeDriveSessionLock(worldName, true, hostName, activeRoomCode, activeModeName);
            } else {
                writeSessionLock(Path.of(cfg.getSyncFolderPath()).resolve(worldName), true, hostName, activeRoomCode, activeModeName, worldName);
            }
        };
        this.heartbeatExec.scheduleAtFixedRate(publishLock, 0, 12, TimeUnit.SECONDS);
    }

    public void onP2PHostStopped(Minecraft mc) {
        stopHeartbeat();
        String worldName = this.activeHostedWorldName;
        Path worldDir = this.activeHostedLocalDir;
        this.activeHostedWorldName = null;
        this.activeHostedLocalDir = null;
        this.activeRoomCode = null;
        this.activeModeName = null;

        SharedWorldConfig cfg = SharedWorldConfig.getInstance();
        if (worldName == null || !cfg.isEnabled() || (!cfg.hasValidSyncFolder() && !cfg.isGoogleSignedIn())) {
            return;
        }

        boolean useDrive = cfg.isGoogleSignedIn() && !cfg.hasValidSyncFolder();
        String hostName = (mc != null && mc.getUser() != null) ? mc.getUser().getName() : "Host";

        // Trigger world flush if server is still running (e.g. clicking "Stop P2P Host" in-game)
        IntegratedServer runningServer = (mc != null) ? mc.getSingleplayerServer() : null;
        CompletableFuture<Void> saveReady = new CompletableFuture<>();
        if (runningServer != null && runningServer.isRunning()) {
            runningServer.execute(() -> {
                try {
                    runningServer.saveEverything(true, true, true);
                } catch (Throwable ignored) {
                } finally {
                    saveReady.complete(null);
                }
            });
        } else {
            saveReady.complete(null);
        }

        // Use a non-daemon worker thread so closing Minecraft right after disconnecting still completes sync & lock release
        Thread exitSyncThread = new Thread(() -> {
            try {
                try {
                    saveReady.get(4, TimeUnit.SECONDS);
                } catch (Exception ignored) {}
                if (runningServer == null) {
                    Thread.sleep(1500);
                }
                if (cfg.isAutoSyncOnExit() && worldDir != null && Files.exists(worldDir)) {
                    if (useDrive) {
                        uploadIncrementalToGoogleDrive(worldDir, worldName, hostName);
                    } else {
                        uploadIncrementalToLocalFolder(worldDir, worldName, hostName, false);
                    }
                }
            } catch (Exception e) {
                FabricP2PMod.LOGGER.warn("[SharedWorlds] Auto-sync on exit failed: {}", e.getMessage());
            } finally {
                if (useDrive) {
                    writeDriveSessionLock(worldName, false, hostName, "", "");
                } else {
                    writeSessionLock(Path.of(cfg.getSyncFolderPath()).resolve(worldName), false, hostName, "", "", worldName);
                }
            }
        }, "FabricP2P-SharedWorld-ExitSync");
        exitSyncThread.setDaemon(false);
        exitSyncThread.start();
    }

    private void writeDriveSessionLock(String worldName, boolean active, String hostName, String roomCode, String mode) {
        try {
            GoogleDriveService drive = GoogleDriveService.getInstance();
            String worldFolderId = resolveWorldDriveFolderId(worldName, true);
            SessionLock lock = new SessionLock();
            lock.active = active;
            lock.hostName = hostName;
            lock.roomCode = roomCode;
            lock.connectionMode = mode;
            lock.worldName = worldName;
            lock.heartbeatMs = System.currentTimeMillis();
            drive.uploadTextFile(worldFolderId, LOCK_FILE, GSON.toJson(lock));
        } catch (Exception e) {
            FabricP2PMod.LOGGER.warn("[SharedWorlds] Failed to update Drive session lock: {}", e.getMessage());
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

    private static List<Path> collectWorldFiles(Path worldDir) throws IOException {
        List<Path> list = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(worldDir)) {
            for (Path p : walk.toList()) {
                if (Files.isDirectory(p)) continue;
                String name = p.getFileName().toString();
                if (name.equals("session.lock")) continue;
                list.add(p);
            }
        }
        return list;
    }

    private static String sha256(Path file) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[16384];
            try (InputStream is = Files.newInputStream(file)) {
                int n;
                while ((n = is.read(buf)) != -1) {
                    md.update(buf, 0, n);
                }
            }
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static <T> T readJson(Path path, Class<T> clazz) {
        if (!Files.exists(path)) return null;
        try {
            return parseJson(Files.readString(path, StandardCharsets.UTF_8), clazz);
        } catch (Exception e) {
            return null;
        }
    }

    private static <T> T parseJson(String json, Class<T> clazz) {
        if (json == null || json.isBlank()) return null;
        try {
            return GSON.fromJson(json, clazz);
        } catch (Exception e) {
            return null;
        }
    }

    public static String sanitizeName(String name) {
        if (name == null) return "";
        String cleaned = name.replaceAll("[^a-zA-Z0-9._ -]", "_").trim();
        while (cleaned.startsWith(".") || cleaned.endsWith(".") || cleaned.endsWith(" ")) {
            cleaned = cleaned.replaceAll("^\\.+|[. ]+$", "").trim();
        }
        if (cleaned.equals("..") || cleaned.equals(".")) {
            return "";
        }
        return cleaned;
    }

    private static final class WorldMeta {
        String worldName;
        int revision;
        String lastHost;
        long updatedAtMs;
    }

    private static final class WorldManifest {
        int revision = 0;
        String lastHost = "";
        long updatedAtMs = 0L;
        Map<String, ManifestFile> files = new LinkedHashMap<>();
    }

    private static final class ManifestFile {
        String sha256;
        long size;
        String driveFileId;
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
