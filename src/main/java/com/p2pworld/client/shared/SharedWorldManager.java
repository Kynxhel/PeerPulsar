package com.p2pworld.client.shared;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.p2pworld.FabricP2PMod;
import com.p2pworld.client.network.P2PNetworkManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Manages Shared Cloud Worlds with SHA-256 Incremental (Delta) Sync!
 * Only uploads/downloads NEW or MODIFIED files (unchanged region files are skipped: 0 bytes transferred).
 * Supports both direct Google Drive API v3 and local synced folders (Google Drive Desktop / OneDrive / Syncthing).
 */
public class SharedWorldManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final long STALE_LOCK_MS = 45_000L;
    private static final long FUTURE_CLOCK_SKEW_MS = 30_000L;
    private static final long IN_MEMORY_SNAPSHOT_LIMIT = 4L * 1024L * 1024L;
    private static final String LOCK_FILE = "session.lock.json";
    private static final String META_FILE = "meta.json";
    private static final String MANIFEST_FILE = "manifest.json";
    private static final String FILES_SUBDIR = "files";
    private static final Pattern WINDOWS_RESERVED_NAME = Pattern.compile(
            "^(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\s*\\..*)?$"
    );

    private static SharedWorldManager instance;

    private final Object syncLock = new Object();
    private final Object heartbeatLock = new Object();
    private final AtomicInteger activeSyncOps = new AtomicInteger(0);
    private final Map<String, AtomicLong> worldHeartbeatSeq = new ConcurrentHashMap<>();

    private volatile String activeHostedWorldName = null;
    private volatile boolean activeHostedUseDrive = false;
    private volatile Path activeHostedLocalDir = null;
    private volatile Path activeHostedSyncRoot = null;
    private volatile String activeHostedSessionId = null;
    private volatile String activeRoomCode = null;
    private volatile String activeModeName = null;
    private volatile ScheduledExecutorService heartbeatExec = null;
    private final Set<String> knownDriveWorldNames = ConcurrentHashMap.newKeySet();
    private final Set<String> activeExitSyncSessions = ConcurrentHashMap.newKeySet();

    private SharedWorldManager() {}

    public static synchronized SharedWorldManager getInstance() {
        if (instance == null) {
            instance = new SharedWorldManager();
        }
        return instance;
    }

    public boolean isUsingGoogleDrive() {
        SharedWorldConfig cfg = SharedWorldConfig.getInstance();
        return cfg.isGoogleSignedIn() && cfg.getSyncFolderPath().isBlank();
    }

    public void clearDriveCache() {
        knownDriveWorldNames.clear();
    }

    public boolean isSyncing() {
        return activeSyncOps.get() > 0;
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
        String worldName = sanitizeName(server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getFileName().toString());
        if (worldName.isEmpty()) return false;
        if (worldName.equals(activeHostedWorldName)) return true;
        if (worldName.equals(cfg.getLastSharedWorldName())) return true;
        if (isUsingGoogleDrive()) {
            if (cfg.getLinkedDriveFolderId(worldName) != null) return true;
            return knownDriveWorldNames.contains(worldName);
        }
        if (cfg.hasValidSyncFolder()) {
            Path sharedDir = Path.of(cfg.getSyncFolderPath()).resolve(worldName).normalize();
            return Files.exists(sharedDir);
        }
        return false;
    }

    /**
     * Resolves (or creates) the Drive folder ID for a specific shared world, respecting individually linked worlds.
     * Matches listSharedWorlds() priority: owned Drive folder first, then linked world folder.
     */
    public String resolveWorldDriveFolderId(String worldName, boolean createIfMissing) throws IOException {
        String safeName = sanitizeName(worldName);
        if (safeName.isEmpty()) {
            return null;
        }
        SharedWorldConfig cfg = SharedWorldConfig.getInstance();
        GoogleDriveService drive = GoogleDriveService.getInstance();
        String rootId = drive.getOrCreateRootFolderId();
        String ownedId = drive.findSubfolder(rootId, safeName);
        if (ownedId != null && !ownedId.isBlank()) {
            return ownedId;
        }
        String linkedId = cfg.getLinkedDriveFolderId(safeName);
        if (linkedId != null && !linkedId.isBlank()) {
            return linkedId;
        }
        return createIfMissing ? drive.getOrCreateSubfolder(rootId, safeName) : null;
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

        if (isUsingGoogleDrive()) {
            try {
                GoogleDriveService drive = GoogleDriveService.getInstance();
                String rootId = drive.getOrCreateRootFolderId();
                Map<String, String> worldFolders = new LinkedHashMap<>();

                for (GoogleDriveService.DriveItem folder : drive.listSubfolders(rootId)) {
                    String safeName = sanitizeName(folder.name());
                    if (!safeName.isEmpty()) {
                        worldFolders.putIfAbsent(safeName, folder.id());
                    }
                }
                for (Map.Entry<String, String> linked : cfg.getLinkedDriveWorlds().entrySet()) {
                    String safeName = sanitizeName(linked.getKey());
                    if (!safeName.isEmpty()) {
                        worldFolders.putIfAbsent(safeName, linked.getValue());
                    }
                }

                knownDriveWorldNames.addAll(worldFolders.keySet());
                knownDriveWorldNames.retainAll(worldFolders.keySet());

                for (Map.Entry<String, String> wf : worldFolders.entrySet()) {
                    String worldName = wf.getKey();
                    String folderId = wf.getValue();
                    try {
                        String metaJson = drive.readTextFile(folderId, META_FILE);
                        String lockJson = drive.readTextFile(folderId, LOCK_FILE);
                        WorldMeta meta = parseJson(metaJson, WorldMeta.class);
                        SessionLock lock = parseJson(lockJson, SessionLock.class);

                        int rev = (meta != null) ? meta.revision : 1;
                        String lastHost = (meta != null && meta.lastHost != null && !meta.lastHost.isBlank())
                                ? meta.lastHost
                                : "Google Drive";
                        long updatedAt = (meta != null) ? meta.updatedAtMs : 0L;
                        boolean live = isLockLive(lock);

                        results.add(new SharedWorldEntry(
                                worldName, rev, lastHost, updatedAt, meta != null,
                                live, live ? resolveLockHostName(lock) : null, live ? lock.roomCode : null, live ? lock.connectionMode : null
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
                String lastHost = (meta != null && meta.lastHost != null && !meta.lastHost.isBlank())
                        ? meta.lastHost
                        : "Unknown";
                long updatedAt = (meta != null) ? meta.updatedAtMs : 0L;
                boolean live = isLockLive(lock);

                results.add(new SharedWorldEntry(
                        worldName,
                        rev,
                        lastHost,
                        updatedAt,
                        Files.exists(manifestPath),
                        live,
                        live ? resolveLockHostName(lock) : null,
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
        boolean useDrive = isUsingGoogleDrive();
        if (!cfg.isEnabled() || (!useDrive && !cfg.hasValidSyncFolder())) {
            String msg = (!cfg.getSyncFolderPath().isBlank() && !cfg.hasValidSyncFolder())
                    ? "Configured Sync Folder is missing or unavailable: " + cfg.getSyncFolderPath()
                    : "Sign in with Google or set a Sync Folder first.";
            return CompletableFuture.completedFuture(new SyncOpResult(false, msg));
        }
        if (mc == null || mc.getSingleplayerServer() == null) {
            return CompletableFuture.completedFuture(new SyncOpResult(false, "Open a singleplayer world first."));
        }

        IntegratedServer server = mc.getSingleplayerServer();
        if (!server.isRunning() || server.isStopped()) {
            return CompletableFuture.completedFuture(new SyncOpResult(false, "Singleplayer server is stopping or already stopped."));
        }

        Path worldDir = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        String worldName = sanitizeName(worldDir.getFileName().toString());
        if (worldName.isEmpty()) {
            return CompletableFuture.completedFuture(new SyncOpResult(false, "Invalid world name."));
        }
        String hostName = (mc.getUser() != null && mc.getUser().getName() != null && !mc.getUser().getName().isBlank())
                ? mc.getUser().getName()
                : "Host";

        if (!activeSyncOps.compareAndSet(0, 1)) {
            return CompletableFuture.completedFuture(new SyncOpResult(false, "Another sync operation is already in progress."));
        }
        final AtomicBoolean released = new AtomicBoolean(false);
        final Runnable releaseSync = () -> {
            if (released.compareAndSet(false, true)) {
                activeSyncOps.decrementAndGet();
            }
        };

        CompletableFuture<Void> saveFuture = new CompletableFuture<>();
        try {
            server.execute(() -> {
                try {
                    if (!server.isRunning() || server.isStopped()) {
                        saveFuture.completeExceptionally(new IllegalStateException("Server stopped before save could complete."));
                        return;
                    }
                    server.saveEverything(true, true, true);
                    saveFuture.complete(null);
                } catch (Throwable t) {
                    saveFuture.completeExceptionally(t);
                }
            });
        } catch (Throwable t) {
            releaseSync.run();
            return CompletableFuture.completedFuture(new SyncOpResult(false, "Save failed before sync: " + errorMessage(t)));
        }

        return saveFuture.orTimeout(15, TimeUnit.SECONDS).handleAsync((v, saveErr) -> {
            synchronized (syncLock) {
                if (saveErr != null) {
                    releaseSync.run();
                    return new SyncOpResult(false, "Save failed before sync: " + errorMessage(saveErr));
                }
                try {
                    if (useDrive) {
                        return uploadIncrementalToGoogleDrive(worldDir, worldName, hostName, false);
                    }
                    return uploadIncrementalToLocalFolder(worldDir, worldName, hostName, false, null);
                } finally {
                    releaseSync.run();
                }
            }
        }, P2PNetworkManager.NETWORK_IO_EXECUTOR).exceptionally(err -> {
            releaseSync.run();
            return new SyncOpResult(false, "Save failed before sync: " + errorMessage(err));
        });
    }

    /**
     * Incrementally pulls ONLY missing or modified files into .minecraft/saves/<worldName>,
     * and cleans up files removed in the latest revision.
     */
    public CompletableFuture<SyncOpResult> pullWorldToLocalSavesAsync(Minecraft mc, String worldName) {
        SharedWorldConfig cfg = SharedWorldConfig.getInstance();
        boolean useDrive = isUsingGoogleDrive();
        if (!cfg.isEnabled() || (!useDrive && !cfg.hasValidSyncFolder())) {
            String msg = (!cfg.getSyncFolderPath().isBlank() && !cfg.hasValidSyncFolder())
                    ? "Configured Sync Folder is missing or unavailable: " + cfg.getSyncFolderPath()
                    : "Sign in with Google or set a Sync Folder first.";
            return CompletableFuture.completedFuture(new SyncOpResult(false, msg));
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

        if (!activeSyncOps.compareAndSet(0, 1)) {
            return CompletableFuture.completedFuture(new SyncOpResult(false, "Another sync operation is already in progress."));
        }
        final AtomicBoolean released = new AtomicBoolean(false);
        final Runnable releaseSync = () -> {
            if (released.compareAndSet(false, true)) {
                activeSyncOps.decrementAndGet();
            }
        };

        return CompletableFuture.supplyAsync(() -> {
            synchronized (syncLock) {
                try {
                    if (useDrive) {
                        return pullIncrementalFromGoogleDrive(safeName, targetWorldDir);
                    } else {
                        return pullIncrementalFromLocalFolder(cfg.getSyncFolderPath(), safeName, targetWorldDir);
                    }
                } catch (Exception e) {
                    FabricP2PMod.LOGGER.error("[SharedWorlds] Incremental pull failed for '{}': {}", safeName, e.getMessage());
                    return new SyncOpResult(false, "Pull failed: " + e.getMessage());
                } finally {
                    releaseSync.run();
                }
            }
        }, P2PNetworkManager.NETWORK_IO_EXECUTOR).exceptionally(err -> {
            releaseSync.run();
            return new SyncOpResult(false, "Pull failed: " + errorMessage(err));
        });
    }

    // ==================== INCREMENTAL GOOGLE DRIVE SYNC ====================

    private SyncOpResult uploadIncrementalToGoogleDrive(Path worldDir, String worldName, String hostName, boolean isExitingHost) {
        try {
            GoogleDriveService drive = GoogleDriveService.getInstance();
            String worldFolderId = resolveWorldDriveFolderId(worldName, true);
            if (!isExitingHost && !worldName.equals(this.activeHostedWorldName)) {
                String lockJson = drive.readTextFile(worldFolderId, LOCK_FILE);
                SessionLock lock = parseJson(lockJson, SessionLock.class);
                if (isLockFresh(lock)) {
                    return new SyncOpResult(false, "World is currently hosted by " + resolveLockHostName(lock) + "! Wait until they stop hosting before uploading.");
                }
            }
            String filesFolderId = drive.getOrCreateSubfolder(worldFolderId, FILES_SUBDIR);

            // Single paginated query to deduplicate uploads when recovering from an interrupted prior sync
            Map<String, String> existingDriveFilesByName = new HashMap<>();
            Set<String> existingDriveFileIds = new HashSet<>();
            for (GoogleDriveService.DriveItem item : drive.listFilesInFolder(filesFolderId)) {
                if (item.id() != null && !item.id().isBlank()) {
                    existingDriveFileIds.add(item.id());
                    if (item.name() != null && !item.name().isBlank()) {
                        existingDriveFilesByName.putIfAbsent(item.name(), item.id());
                    }
                }
            }

            String oldManifestJson = drive.readTextFile(worldFolderId, MANIFEST_FILE);
            WorldManifest oldManifest = parseJson(oldManifestJson, WorldManifest.class);
            if (oldManifest == null && oldManifestJson != null && !oldManifestJson.isBlank()) {
                return new SyncOpResult(false, "Could not read existing Drive manifest.json; aborting to prevent revision reset.");
            }
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
                try (FileSnapshot snap = FileSnapshot.capture(p)) {
                    ManifestFile prev = oldManifest.files.get(rel);
                    boolean prevIdValid = prev != null && prev.driveFileId != null
                            && existingDriveFileIds.contains(prev.driveFileId);
                    if (prevIdValid && snap.sha256.equals(prev.sha256)) {
                        newManifest.files.put(rel, prev);
                        skipped++;
                    } else {
                        String existingId = prevIdValid
                                ? prev.driveFileId
                                : existingDriveFilesByName.get(rel);
                        String fileId = (snap.bytes != null)
                                ? drive.uploadOrUpdateBytesById(filesFolderId, existingId, rel, "application/octet-stream", snap.bytes)
                                : drive.uploadOrUpdateFileById(filesFolderId, existingId, rel, "application/octet-stream", snap.tempFile);
                        existingDriveFilesByName.put(rel, fileId);
                        existingDriveFileIds.add(fileId);
                        ManifestFile mf = new ManifestFile();
                        mf.sha256 = snap.sha256;
                        mf.size = snap.size;
                        mf.driveFileId = fileId;
                        newManifest.files.put(rel, mf);
                        uploaded++;
                    }
                } catch (NoSuchFileException e) {
                    FabricP2PMod.LOGGER.debug("[SharedWorlds] Skipping transient missing file during Drive upload '{}': {}", rel, e.getMessage());
                }
            }

            drive.uploadTextFile(worldFolderId, MANIFEST_FILE, GSON.toJson(newManifest));

            WorldMeta meta = new WorldMeta();
            meta.worldName = worldName;
            meta.revision = newManifest.revision;
            meta.lastHost = hostName;
            meta.updatedAtMs = newManifest.updatedAtMs;
            drive.uploadTextFile(worldFolderId, META_FILE, GSON.toJson(meta));

            // Delete files removed on host only AFTER the new manifest is safely committed
            for (Map.Entry<String, ManifestFile> oldEntry : oldManifest.files.entrySet()) {
                if (!newManifest.files.containsKey(oldEntry.getKey()) && oldEntry.getValue() != null) {
                    drive.deleteFileById(oldEntry.getValue().driveFileId);
                }
            }

            knownDriveWorldNames.add(worldName);
            SharedWorldConfig.getInstance().setLastSharedWorldName(worldName);
            FabricP2PMod.LOGGER.info("[SharedWorlds] Drive incremental sync '{}' Rev #{}: {} uploaded, {} skipped.",
                    worldName, newManifest.revision, uploaded, skipped);
            return new SyncOpResult(true, "Drive Sync Rev #" + newManifest.revision + ": " + uploaded + " changed, " + skipped + " skipped!");
        } catch (Exception e) {
            return new SyncOpResult(false, "Google Drive sync failed: " + e.getMessage());
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
        if (isLockFresh(lock)) {
            return new SyncOpResult(false, "World is currently hosted by " + resolveLockHostName(lock) + "! Join them instead.");
        }

        String manifestJson = drive.readTextFile(worldFolderId, MANIFEST_FILE);
        if (manifestJson == null) {
            return new SyncOpResult(false, "No manifest found on Google Drive for '" + worldName + "'.");
        }
        WorldManifest manifest = parseJson(manifestJson, WorldManifest.class);
        if (manifest == null || manifest.files == null || manifest.files.isEmpty()) {
            return new SyncOpResult(false, "Corrupt or empty manifest on Google Drive.");
        }

        int downloaded = 0;
        int skipped = 0;
        int failed = 0;
        Path normalizedRoot = targetWorldDir.toAbsolutePath().normalize();
        Path stagingDir = normalizedRoot.resolveSibling(".p2p_stage_" + UUID.randomUUID());
        Set<Path> expectedFiles = new HashSet<>();
        List<Map.Entry<Path, Path>> stagedMoves = new ArrayList<>();

        try {
            Files.createDirectories(stagingDir);

            for (Map.Entry<String, ManifestFile> entry : manifest.files.entrySet()) {
                String rel = entry.getKey();
                ManifestFile mf = entry.getValue();
                if (rel == null || mf == null || isSessionLockEntry(rel)) continue;
                Path dest = normalizedRoot.resolve(rel).normalize();
                if (!dest.startsWith(normalizedRoot) || dest.equals(normalizedRoot)) continue;
                if (dest.getFileName() != null && dest.getFileName().toString().equalsIgnoreCase("session.lock")) continue;
                expectedFiles.add(dest);

                if (Files.exists(dest) && mf.sha256 != null && !mf.sha256.isBlank()) {
                    try {
                        if (mf.sha256.equals(sha256(dest))) {
                            skipped++;
                            continue;
                        }
                    } catch (IOException ignored) {
                        // Existing local file unreadable; re-download below
                    }
                }

                Path staged = stagingDir.resolve(rel).normalize();
                if (!staged.startsWith(stagingDir) || staged.equals(stagingDir)) continue;
                if (drive.downloadFileById(mf.driveFileId, staged)) {
                    if (mf.sha256 != null && !mf.sha256.isBlank() && !mf.sha256.equals(sha256(staged))) {
                        failed++;
                        FabricP2PMod.LOGGER.warn("[SharedWorlds] SHA-256 mismatch after downloading '{}' (id={})", rel, mf.driveFileId);
                    } else {
                        stagedMoves.add(Map.entry(staged, dest));
                        downloaded++;
                    }
                } else {
                    failed++;
                    FabricP2PMod.LOGGER.warn("[SharedWorlds] Failed to download '{}' (id={})", rel, mf.driveFileId);
                }
            }

            if (failed > 0) {
                return new SyncOpResult(false, "Incomplete pull: " + failed + " file(s) failed to download or failed SHA-256 verification.");
            }
            if (expectedFiles.isEmpty()) {
                return new SyncOpResult(false, "Manifest contains no valid world files; aborting pull.");
            }

            Files.createDirectories(normalizedRoot);
            for (Map.Entry<Path, Path> move : stagedMoves) {
                Path dest = move.getValue();
                if (dest.getParent() != null) {
                    Files.createDirectories(dest.getParent());
                }
                moveReplacingAtomically(move.getKey(), dest);
            }

            pruneRemovedLocalFiles(normalizedRoot, expectedFiles);
            SharedWorldConfig.getInstance().setLastSharedWorldName(worldName);
            return new SyncOpResult(true, "Pulled Rev #" + manifest.revision + ": " + downloaded + " updated, " + skipped + " already up to date!");
        } finally {
            deleteDirectoryRecursively(stagingDir);
        }
    }

    // ==================== INCREMENTAL LOCAL FOLDER SYNC ====================

    private SyncOpResult uploadIncrementalToLocalFolder(Path worldDir, String worldName, String hostName, boolean isExitingHost, Path overrideSyncRoot) {
        SharedWorldConfig cfg = SharedWorldConfig.getInstance();
        try {
            Path syncRoot = (overrideSyncRoot != null)
                    ? overrideSyncRoot.toAbsolutePath().normalize()
                    : Path.of(cfg.getSyncFolderPath()).toAbsolutePath().normalize();
            Path sharedDir = syncRoot.resolve(worldName).normalize();
            if (!sharedDir.startsWith(syncRoot) || sharedDir.equals(syncRoot)) {
                return new SyncOpResult(false, "Invalid world name for sync folder.");
            }
            if (!isExitingHost && !worldName.equals(this.activeHostedWorldName)) {
                SessionLock lock = readJson(sharedDir.resolve(LOCK_FILE), SessionLock.class);
                if (isLockFresh(lock)) {
                    return new SyncOpResult(false, "World is currently hosted by " + resolveLockHostName(lock) + "! Wait until they stop hosting before uploading.");
                }
            }
            Path filesRoot = sharedDir.resolve(FILES_SUBDIR).normalize();
            Files.createDirectories(filesRoot);

            Path manifestPath = sharedDir.resolve(MANIFEST_FILE);
            WorldManifest oldManifest = readJson(manifestPath, WorldManifest.class);
            if (oldManifest == null && Files.exists(manifestPath)) {
                return new SyncOpResult(false, "Could not read existing manifest.json; aborting to prevent revision reset.");
            }
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
                Path sharedCopy = filesRoot.resolve(rel).normalize();
                if (!sharedCopy.startsWith(filesRoot) || sharedCopy.equals(filesRoot)) continue;

                try (FileSnapshot snap = FileSnapshot.capture(p)) {
                    ManifestFile prev = oldManifest.files.get(rel);
                    if (prev != null && snap.sha256.equals(prev.sha256) && Files.exists(sharedCopy)) {
                        newManifest.files.put(rel, prev);
                        skipped++;
                    } else {
                        if (snap.bytes != null) {
                            writeBytesAtomically(snap.bytes, sharedCopy);
                        } else {
                            copyFileAtomically(snap.tempFile, sharedCopy);
                        }
                        ManifestFile mf = new ManifestFile();
                        mf.sha256 = snap.sha256;
                        mf.size = snap.size;
                        newManifest.files.put(rel, mf);
                        copied++;
                    }
                } catch (NoSuchFileException e) {
                    FabricP2PMod.LOGGER.debug("[SharedWorlds] Skipping transient missing file during local upload '{}': {}", rel, e.getMessage());
                }
            }

            writeBytesAtomically(GSON.toJson(newManifest).getBytes(StandardCharsets.UTF_8), manifestPath);

            WorldMeta meta = new WorldMeta();
            meta.worldName = worldName;
            meta.revision = newManifest.revision;
            meta.lastHost = hostName;
            meta.updatedAtMs = newManifest.updatedAtMs;
            writeBytesAtomically(GSON.toJson(meta).getBytes(StandardCharsets.UTF_8), sharedDir.resolve(META_FILE));

            // Clean up removed files safely inside filesRoot only AFTER the new manifest is committed
            for (String oldRel : oldManifest.files.keySet()) {
                if (!newManifest.files.containsKey(oldRel)) {
                    Path oldFile = filesRoot.resolve(oldRel).normalize();
                    if (oldFile.startsWith(filesRoot) && !oldFile.equals(filesRoot)) {
                        try { Files.deleteIfExists(oldFile); } catch (IOException ignored) {}
                    }
                }
            }

            cfg.setLastSharedWorldName(worldName);

            FabricP2PMod.LOGGER.info("[SharedWorlds] Local incremental sync '{}' Rev #{}: {} changed, {} skipped.",
                    worldName, newManifest.revision, copied, skipped);
            return new SyncOpResult(true, "Synced Rev #" + newManifest.revision + ": " + copied + " changed, " + skipped + " skipped!");
        } catch (Exception e) {
            return new SyncOpResult(false, "Sync failed: " + e.getMessage());
        }
    }

    SyncOpResult pullIncrementalFromLocalFolder(String syncFolder, String worldName, Path targetWorldDir) throws IOException {
        Path syncRoot = Path.of(syncFolder).toAbsolutePath().normalize();
        Path sharedDir = syncRoot.resolve(worldName).normalize();
        if (!sharedDir.startsWith(syncRoot) || sharedDir.equals(syncRoot)) {
            return new SyncOpResult(false, "Invalid world path.");
        }
        SessionLock lock = readJson(sharedDir.resolve(LOCK_FILE), SessionLock.class);
        if (isLockFresh(lock)) {
            return new SyncOpResult(false, "World is currently hosted by " + resolveLockHostName(lock) + "! Join them instead.");
        }

        WorldManifest manifest = readJson(sharedDir.resolve(MANIFEST_FILE), WorldManifest.class);
        if (manifest == null || manifest.files == null || manifest.files.isEmpty()) {
            return new SyncOpResult(false, "No valid manifest found in shared folder for '" + worldName + "'.");
        }

        Path filesRoot = sharedDir.resolve(FILES_SUBDIR).normalize();
        Path normalizedRoot = targetWorldDir.toAbsolutePath().normalize();
        Path stagingDir = normalizedRoot.resolveSibling(".p2p_stage_" + UUID.randomUUID());
        int copied = 0;
        int skipped = 0;
        int missing = 0;
        int failed = 0;
        Set<Path> expectedFiles = new HashSet<>();
        List<Map.Entry<Path, Path>> stagedMoves = new ArrayList<>();

        try {
            Files.createDirectories(stagingDir);

            for (Map.Entry<String, ManifestFile> entry : manifest.files.entrySet()) {
                String rel = entry.getKey();
                ManifestFile mf = entry.getValue();
                if (rel == null || mf == null || isSessionLockEntry(rel)) continue;
                Path src = filesRoot.resolve(rel).normalize();
                Path dest = normalizedRoot.resolve(rel).normalize();
                if (!src.startsWith(filesRoot) || !dest.startsWith(normalizedRoot) || dest.equals(normalizedRoot)) continue;
                if (dest.getFileName() != null && dest.getFileName().toString().equalsIgnoreCase("session.lock")) continue;
                if (!Files.exists(src)) {
                    missing++;
                    continue;
                }
                expectedFiles.add(dest);

                if (Files.exists(dest) && mf.sha256 != null && !mf.sha256.isBlank()) {
                    try {
                        if (mf.sha256.equals(sha256(dest))) {
                            skipped++;
                            continue;
                        }
                    } catch (IOException ignored) {
                        // Existing local file unreadable; recopy below
                    }
                }
                Path staged = stagingDir.resolve(rel).normalize();
                if (!staged.startsWith(stagingDir) || staged.equals(stagingDir)) continue;
                copyFileAtomically(src, staged);
                if (mf.sha256 != null && !mf.sha256.isBlank() && !mf.sha256.equals(sha256(staged))) {
                    failed++;
                    FabricP2PMod.LOGGER.warn("[SharedWorlds] SHA-256 mismatch after copying '{}' from sync folder", rel);
                } else {
                    stagedMoves.add(Map.entry(staged, dest));
                    copied++;
                }
            }

            if (missing > 0 || failed > 0) {
                if (failed > 0 && missing == 0) {
                    return new SyncOpResult(false, "Incomplete pull: " + failed + " file(s) failed SHA-256 verification.");
                }
                if (missing > 0 && failed == 0) {
                    return new SyncOpResult(false, "Incomplete pull: " + missing + " file(s) missing in sync folder.");
                }
                return new SyncOpResult(false, "Incomplete pull: " + missing + " missing, " + failed + " failed SHA-256 verification.");
            }
            if (expectedFiles.isEmpty()) {
                return new SyncOpResult(false, "Manifest contains no valid world files; aborting pull.");
            }

            Files.createDirectories(normalizedRoot);
            for (Map.Entry<Path, Path> move : stagedMoves) {
                Path dest = move.getValue();
                if (dest.getParent() != null) {
                    Files.createDirectories(dest.getParent());
                }
                moveReplacingAtomically(move.getKey(), dest);
            }

            pruneRemovedLocalFiles(normalizedRoot, expectedFiles);
            SharedWorldConfig.getInstance().setLastSharedWorldName(worldName);
            return new SyncOpResult(true, "Pulled Rev #" + manifest.revision + ": " + copied + " updated, " + skipped + " already up to date!");
        } finally {
            deleteDirectoryRecursively(stagingDir);
        }
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

    public void onP2PHostStarted(Minecraft mc, String roomCode, P2PNetworkManager.ActiveHostingMode mode, long hostGen) {
        SharedWorldConfig cfg = SharedWorldConfig.getInstance();
        boolean useDrive = isUsingGoogleDrive();
        if (!cfg.isEnabled() || (!useDrive && !cfg.hasValidSyncFolder())
                || mc == null || mc.getSingleplayerServer() == null) {
            return;
        }

        IntegratedServer server = mc.getSingleplayerServer();
        Path worldDir = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        String worldName = sanitizeName(worldDir.getFileName().toString());
        if (worldName.isEmpty()) {
            return;
        }
        String hostName = (mc.getUser() != null && mc.getUser().getName() != null && !mc.getUser().getName().isBlank())
                ? mc.getUser().getName()
                : "Host";
        Path snapshottedSyncRoot = useDrive ? null : Path.of(cfg.getSyncFolderPath()).toAbsolutePath().normalize();

        if (!useDrive) {
            Path sharedDir = snapshottedSyncRoot.resolve(worldName);
            if (!Files.exists(sharedDir) && !worldName.equals(cfg.getLastSharedWorldName())) {
                return;
            }
            SessionLock existingLock = readJson(sharedDir.resolve(LOCK_FILE), SessionLock.class);
            if (isLockFresh(existingLock)) {
                notifyHostLockConflict(mc, worldName, existingLock, false);
                return;
            }
        } else {
            boolean matchesKnown = worldName.equals(cfg.getLastSharedWorldName())
                    || cfg.getLinkedDriveFolderId(worldName) != null
                    || knownDriveWorldNames.contains(worldName);
            String worldFolderId = null;
            if (!matchesKnown) {
                try {
                    worldFolderId = resolveWorldDriveFolderId(worldName, false);
                    if (worldFolderId == null) {
                        return;
                    }
                    knownDriveWorldNames.add(worldName);
                } catch (Exception e) {
                    return;
                }
            }
            try {
                if (worldFolderId == null) {
                    worldFolderId = resolveWorldDriveFolderId(worldName, false);
                }
                if (worldFolderId != null) {
                    String lockJson = GoogleDriveService.getInstance().readTextFile(worldFolderId, LOCK_FILE);
                    SessionLock existingLock = parseJson(lockJson, SessionLock.class);
                    if (isLockFresh(existingLock)) {
                        notifyHostLockConflict(mc, worldName, existingLock, true);
                        return;
                    }
                }
            } catch (Exception e) {
                FabricP2PMod.LOGGER.warn("[SharedWorlds] Could not check Drive session lock for '{}': {}", worldName, e.getMessage());
                return;
            }
        }

        if (!P2PNetworkManager.getInstance().isHostGenerationActive(hostGen)) {
            return;
        }

        synchronized (heartbeatLock) {
            if (!P2PNetworkManager.getInstance().isHostGenerationActive(hostGen)) {
                return;
            }
            stopHeartbeat();
            String sessionId = UUID.randomUUID().toString();
            this.activeHostedWorldName = worldName;
            this.activeHostedUseDrive = useDrive;
            this.activeHostedLocalDir = worldDir;
            this.activeHostedSyncRoot = snapshottedSyncRoot;
            this.activeHostedSessionId = sessionId;
            this.activeRoomCode = roomCode;
            this.activeModeName = mode.name();
            final AtomicLong worldSeqCounter = worldHeartbeatSeq.computeIfAbsent(worldName, k -> new AtomicLong(0));
            final long sessionSeq = worldSeqCounter.incrementAndGet();

            ScheduledExecutorService newExec = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "FabricP2P-SharedWorld-Heartbeat");
                t.setDaemon(true);
                return t;
            });
            this.heartbeatExec = newExec;

            Runnable publishLock = () -> {
                String currentWorld = activeHostedWorldName;
                String currentRoom = activeRoomCode;
                String currentMode = activeModeName;
                if (worldSeqCounter.get() != sessionSeq
                        || !worldName.equals(currentWorld)
                        || currentRoom == null) {
                    return;
                }
                if (useDrive) {
                    writeDriveSessionLock(worldName, true, hostName, currentRoom, currentMode, sessionId);
                } else {
                    writeSessionLock(snapshottedSyncRoot.resolve(worldName), true, hostName, currentRoom, currentMode, worldName, sessionId);
                }
            };
            newExec.scheduleAtFixedRate(publishLock, 0, 12, TimeUnit.SECONDS);
        }
    }

    public void onP2PHostStopped(Minecraft mc, boolean worldDisconnecting) {
        String worldName;
        Path worldDir;
        Path syncRoot;
        String sessionId;
        boolean useDrive;
        long stoppedSeq;
        AtomicLong worldSeqCounter;
        ScheduledExecutorService stoppedHeartbeat;
        synchronized (heartbeatLock) {
            worldName = this.activeHostedWorldName;
            worldDir = this.activeHostedLocalDir;
            syncRoot = this.activeHostedSyncRoot;
            sessionId = this.activeHostedSessionId;
            useDrive = this.activeHostedUseDrive;
            this.activeHostedWorldName = null;
            this.activeHostedLocalDir = null;
            this.activeHostedSyncRoot = null;
            this.activeHostedSessionId = null;
            this.activeRoomCode = null;
            this.activeModeName = null;
            worldSeqCounter = (worldName != null)
                    ? worldHeartbeatSeq.computeIfAbsent(worldName, k -> new AtomicLong(0))
                    : null;
            stoppedSeq = (worldSeqCounter != null) ? worldSeqCounter.incrementAndGet() : 0L;
            stoppedHeartbeat = stopHeartbeat();
        }

        SharedWorldConfig cfg = SharedWorldConfig.getInstance();
        if (worldName == null || !cfg.isEnabled()
                || (useDrive && !cfg.isGoogleSignedIn())
                || (!useDrive && syncRoot == null)) {
            return;
        }

        String hostName = (mc != null && mc.getUser() != null && mc.getUser().getName() != null && !mc.getUser().getName().isBlank())
                ? mc.getUser().getName()
                : "Host";

        // Trigger world flush if server is still running (e.g. clicking "Stop P2P Host" in-game)
        IntegratedServer runningServer = (mc != null) ? mc.getSingleplayerServer() : null;
        CompletableFuture<Void> saveReady = new CompletableFuture<>();
        if (runningServer != null && runningServer.isRunning() && !worldDisconnecting) {
            try {
                runningServer.execute(() -> {
                    try {
                        runningServer.saveEverything(true, true, true);
                    } catch (Throwable ignored) {
                    } finally {
                        saveReady.complete(null);
                    }
                });
            } catch (Throwable ignored) {
                saveReady.complete(null);
            }
        } else {
            saveReady.complete(null);
        }

        // Use a non-daemon worker thread so closing Minecraft right after disconnecting still completes sync & lock release
        if (sessionId != null && !sessionId.isBlank()) {
            activeExitSyncSessions.add(sessionId);
        }
        Thread exitSyncThread = new Thread(() -> {
            ScheduledExecutorService exitLockRenewer = null;
            try {
                if (runningServer == null) {
                    Thread.sleep(1500);
                } else {
                    try {
                        saveReady.get(4, TimeUnit.SECONDS);
                    } catch (Exception ignored) {}
                    if (worldDisconnecting || !runningServer.isRunning()) {
                        long deadline = System.currentTimeMillis() + 6_000L;
                        while (!runningServer.isStopped() && System.currentTimeMillis() < deadline) {
                            Thread.sleep(100);
                        }
                    }
                }

                // Wait for any in-flight heartbeat to terminate before syncing or releasing lock
                awaitHeartbeatTermination(stoppedHeartbeat);

                if (cfg.isAutoSyncOnExit() && worldDir != null && Files.exists(worldDir)) {
                    // Renew session.lock.json (active=true, roomCode="") while exit upload runs so large worlds don't expire the 45s lock
                    exitLockRenewer = Executors.newSingleThreadScheduledExecutor(r -> {
                        Thread t = new Thread(r, "FabricP2P-ExitLock-Renew");
                        t.setDaemon(true);
                        return t;
                    });
                    exitLockRenewer.scheduleAtFixedRate(() -> {
                        if (worldSeqCounter.get() != stoppedSeq) {
                            return;
                        }
                        if (useDrive) {
                            writeDriveSessionLock(worldName, true, hostName, "", "", sessionId);
                        } else {
                            writeSessionLock(syncRoot.resolve(worldName), true, hostName, "", "", worldName, sessionId);
                        }
                    }, 0, 12, TimeUnit.SECONDS);

                    synchronized (syncLock) {
                        activeSyncOps.incrementAndGet();
                        try {
                            if (useDrive) {
                                uploadIncrementalToGoogleDrive(worldDir, worldName, hostName, true);
                            } else {
                                uploadIncrementalToLocalFolder(worldDir, worldName, hostName, true, syncRoot);
                            }
                        } finally {
                            activeSyncOps.decrementAndGet();
                        }
                    }
                }
            } catch (Exception e) {
                FabricP2PMod.LOGGER.warn("[SharedWorlds] Auto-sync on exit failed: {}", e.getMessage());
            } finally {
                if (exitLockRenewer != null) {
                    exitLockRenewer.shutdownNow();
                    awaitHeartbeatTermination(exitLockRenewer);
                }
                awaitHeartbeatTermination(stoppedHeartbeat);
                if (worldSeqCounter.get() == stoppedSeq) {
                    if (useDrive) {
                        writeDriveSessionLock(worldName, false, hostName, "", "", sessionId);
                    } else {
                        writeSessionLock(syncRoot.resolve(worldName), false, hostName, "", "", worldName, sessionId);
                    }
                }
                if (sessionId != null) {
                    activeExitSyncSessions.remove(sessionId);
                }
            }
        }, "FabricP2P-SharedWorld-ExitSync");
        exitSyncThread.setDaemon(false);
        exitSyncThread.start();
    }

    private void notifyHostLockConflict(Minecraft mc, String worldName, SessionLock existingLock, boolean isDrive) {
        boolean isOwnExitSync = existingLock != null
                && existingLock.sessionId != null
                && activeExitSyncSessions.contains(existingLock.sessionId);
        String activeHost = resolveLockHostName(existingLock);
        boolean isSyncingLock = existingLock != null
                && (existingLock.roomCode == null || existingLock.roomCode.isBlank());
        String label = isDrive ? "Drive world" : "World";
        if (isOwnExitSync) {
            FabricP2PMod.LOGGER.warn("[SharedWorlds] {} '{}' still has an in-flight exit sync; skipping live lock & exit auto-sync.", label, worldName);
            mc.execute(() -> {
                if (mc.player != null) {
                    mc.player.sendSystemMessage(Component.literal(
                            "§e⏳ [SharedWorlds] §fPrevious exit sync for '" + worldName
                                    + "' is still finishing — live lock & auto-sync skipped to prevent conflict."));
                }
            });
            return;
        }
        String action = isSyncingLock ? "currently syncing by " : "currently hosted by ";
        FabricP2PMod.LOGGER.warn("[SharedWorlds] {} '{}' is already locked ({}{}); skipping live lock & exit auto-sync.",
                label, worldName, action, activeHost);
        mc.execute(() -> {
            if (mc.player != null) {
                mc.player.sendSystemMessage(Component.literal(
                        "§e⚠ [SharedWorlds] §f'" + worldName + "' is " + action + "§b" + activeHost
                                + "§f — live lock & auto-sync skipped to prevent conflict."));
            }
        });
    }

    private void writeDriveSessionLock(String worldName, boolean active, String hostName, String roomCode, String mode, String sessionId) {
        try {
            GoogleDriveService drive = GoogleDriveService.getInstance();
            String worldFolderId = resolveWorldDriveFolderId(worldName, true);
            SessionLock lock = new SessionLock();
            lock.active = active;
            lock.hostName = hostName;
            lock.roomCode = roomCode;
            lock.connectionMode = mode;
            lock.worldName = worldName;
            lock.sessionId = sessionId;
            lock.heartbeatMs = System.currentTimeMillis();
            drive.uploadTextFile(worldFolderId, LOCK_FILE, GSON.toJson(lock));
        } catch (Exception e) {
            FabricP2PMod.LOGGER.warn("[SharedWorlds] Failed to update Drive session lock: {}", e.getMessage());
        }
    }

    private void writeSessionLock(Path sharedDir, boolean active, String hostName, String roomCode, String mode, String worldName, String sessionId) {
        try {
            Files.createDirectories(sharedDir);
            SessionLock lock = new SessionLock();
            lock.active = active;
            lock.hostName = hostName;
            lock.roomCode = roomCode;
            lock.connectionMode = mode;
            lock.worldName = worldName;
            lock.sessionId = sessionId;
            lock.heartbeatMs = System.currentTimeMillis();

            writeBytesAtomically(GSON.toJson(lock).getBytes(StandardCharsets.UTF_8), sharedDir.resolve(LOCK_FILE));
        } catch (Exception e) {
            FabricP2PMod.LOGGER.warn("[SharedWorlds] Failed to write session lock: {}", e.getMessage());
        }
    }

    private ScheduledExecutorService stopHeartbeat() {
        ScheduledExecutorService exec;
        synchronized (heartbeatLock) {
            exec = this.heartbeatExec;
            this.heartbeatExec = null;
        }
        if (exec != null) {
            exec.shutdownNow();
        }
        return exec;
    }

    private static void awaitHeartbeatTermination(ScheduledExecutorService exec) {
        if (exec == null || exec.isTerminated()) return;
        try {
            exec.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean isLockFresh(SessionLock lock) {
        if (lock == null || !lock.active) return false;
        long age = System.currentTimeMillis() - lock.heartbeatMs;
        return age >= -FUTURE_CLOCK_SKEW_MS && age < STALE_LOCK_MS;
    }

    private static boolean isLockLive(SessionLock lock) {
        return isLockFresh(lock) && lock.roomCode != null && !lock.roomCode.isBlank();
    }

    private static String resolveLockHostName(SessionLock lock) {
        return (lock != null && lock.hostName != null && !lock.hostName.isBlank()) ? lock.hostName : "Host";
    }

    private static boolean isSessionLockEntry(String rel) {
        if (rel == null) return false;
        String normalized = rel.replace("\\", "/").toLowerCase(Locale.ROOT);
        return normalized.equals("session.lock") || normalized.endsWith("/session.lock");
    }

    private static String errorMessage(Throwable err) {
        Throwable cause = (err != null && err.getCause() != null) ? err.getCause() : err;
        if (cause != null && cause.getMessage() != null && !cause.getMessage().isBlank()) {
            return cause.getMessage();
        }
        return cause != null ? cause.getClass().getSimpleName() : "Unknown error";
    }

    private static void copyFileAtomically(Path source, Path dest) throws IOException {
        Path parent = dest.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = Files.createTempFile(parent != null ? parent : Path.of("."),
                "." + dest.getFileName().toString() + ".", ".p2p.tmp");
        boolean moved = false;
        try {
            Files.copy(source, tmp, StandardCopyOption.REPLACE_EXISTING);
            moveReplacingAtomically(tmp, dest);
            moved = true;
        } finally {
            if (!moved) {
                try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
            }
        }
    }

    private static void writeBytesAtomically(byte[] data, Path dest) throws IOException {
        Path parent = dest.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = Files.createTempFile(parent != null ? parent : Path.of("."),
                "." + dest.getFileName().toString() + ".", ".p2p.tmp");
        boolean moved = false;
        try {
            Files.write(tmp, data);
            moveReplacingAtomically(tmp, dest);
            moved = true;
        } finally {
            if (!moved) {
                try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
            }
        }
    }

    private static void deleteDirectoryRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
            });
        } catch (IOException ignored) {}
    }

    private static void moveReplacingAtomically(Path tmp, Path dest) throws IOException {
        try {
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFallback) {
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static List<Path> collectWorldFiles(Path worldDir) throws IOException {
        List<Path> list = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(worldDir)) {
            for (Path p : walk.toList()) {
                if (Files.isDirectory(p)) continue;
                String name = p.getFileName().toString();
                if (name.equalsIgnoreCase("session.lock") || name.endsWith(".p2p.tmp")) continue;
                list.add(p);
            }
        }
        return list;
    }

    private static String sha256(Path file) throws IOException {
        MessageDigest md = newSha256();
        byte[] buf = new byte[16384];
        try (InputStream is = Files.newInputStream(file)) {
            int n;
            while ((n = is.read(buf)) != -1) {
                md.update(buf, 0, n);
            }
        }
        return toHex(md.digest());
    }

    private static String sha256Bytes(byte[] data) {
        MessageDigest md = newSha256();
        md.update(data);
        return toHex(md.digest());
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", e);
        }
    }

    private static String toHex(byte[] digest) {
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static final class FileSnapshot implements AutoCloseable {
        final byte[] bytes;
        final Path tempFile;
        final long size;
        final String sha256;

        private FileSnapshot(byte[] bytes, Path tempFile, long size, String sha256) {
            this.bytes = bytes;
            this.tempFile = tempFile;
            this.size = size;
            this.sha256 = sha256;
        }

        static FileSnapshot capture(Path source) throws IOException {
            long initialSize = Files.size(source);
            if (initialSize <= IN_MEMORY_SNAPSHOT_LIMIT) {
                byte[] data = Files.readAllBytes(source);
                String hash = sha256Bytes(data);
                if (data.length <= IN_MEMORY_SNAPSHOT_LIMIT) {
                    return new FileSnapshot(data, null, data.length, hash);
                }
                Path tmp = Files.createTempFile("fp2p-sync-", ".tmp");
                try {
                    Files.write(tmp, data);
                    return new FileSnapshot(null, tmp, data.length, hash);
                } catch (Throwable t) {
                    try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
                    throw t;
                }
            } else {
                Path tmp = Files.createTempFile("fp2p-sync-", ".tmp");
                try {
                    Files.copy(source, tmp, StandardCopyOption.REPLACE_EXISTING);
                    long snapSize = Files.size(tmp);
                    String hash = sha256(tmp);
                    return new FileSnapshot(null, tmp, snapSize, hash);
                } catch (Throwable t) {
                    try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
                    throw t;
                }
            }
        }

        @Override
        public void close() {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException ignored) {}
            }
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
        String normalized = Normalizer.normalize(name, Normalizer.Form.NFC);
        String cleaned = normalized.replaceAll("[^\\p{L}\\p{N}._ -]", "_").trim();
        while (cleaned.startsWith(".") || cleaned.endsWith(".") || cleaned.endsWith(" ")) {
            cleaned = cleaned.replaceAll("^\\.+|[. ]+$", "").trim();
        }
        cleaned = cleaned.replaceAll("\\.{2,}", "_");
        while (cleaned.startsWith(".") || cleaned.endsWith(".") || cleaned.endsWith(" ")) {
            cleaned = cleaned.replaceAll("^\\.+|[. ]+$", "").trim();
        }
        if (cleaned.isEmpty() || cleaned.equals("..") || cleaned.equals(".")) {
            return "";
        }
        if (WINDOWS_RESERVED_NAME.matcher(cleaned).matches()) {
            cleaned = "world_" + cleaned;
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
        String sessionId;
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
