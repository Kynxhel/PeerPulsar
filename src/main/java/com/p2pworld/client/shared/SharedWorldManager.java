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
    private final AtomicLong heartbeatSessionSeq = new AtomicLong(0);

    private volatile String activeHostedWorldName = null;
    private volatile boolean activeHostedUseDrive = false;
    private volatile Path activeHostedLocalDir = null;
    private volatile String activeRoomCode = null;
    private volatile String activeModeName = null;
    private volatile ScheduledExecutorService heartbeatExec = null;
    private final Set<String> knownDriveWorldNames = ConcurrentHashMap.newKeySet();

    private SharedWorldManager() {}

    public static synchronized SharedWorldManager getInstance() {
        if (instance == null) {
            instance = new SharedWorldManager();
        }
        return instance;
    }

    public boolean isUsingGoogleDrive() {
        SharedWorldConfig cfg = SharedWorldConfig.getInstance();
        return cfg.isGoogleSignedIn() && !cfg.hasValidSyncFolder();
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
        String worldName = sanitizeName(server.getWorldPath(LevelResource.ROOT).normalize().getFileName().toString());
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

        if (isUsingGoogleDrive()) {
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
            return CompletableFuture.completedFuture(new SyncOpResult(false, "Sign in with Google or set a Sync Folder first."));
        }
        if (mc == null || mc.getSingleplayerServer() == null) {
            return CompletableFuture.completedFuture(new SyncOpResult(false, "Open a singleplayer world first."));
        }

        IntegratedServer server = mc.getSingleplayerServer();
        if (!server.isRunning() || server.isStopped()) {
            return CompletableFuture.completedFuture(new SyncOpResult(false, "Singleplayer server is stopping or already stopped."));
        }

        Path worldDir = server.getWorldPath(LevelResource.ROOT).normalize();
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
                    return uploadIncrementalToLocalFolder(worldDir, worldName, hostName, true, false);
                } finally {
                    releaseSync.run();
                }
            }
        }).exceptionally(err -> {
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
        }).exceptionally(err -> {
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
            for (GoogleDriveService.DriveItem item : drive.listFilesInFolder(filesFolderId)) {
                if (item.name() != null && !item.name().isBlank() && item.id() != null && !item.id().isBlank()) {
                    existingDriveFilesByName.putIfAbsent(item.name(), item.id());
                }
            }

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
                try (FileSnapshot snap = FileSnapshot.capture(p)) {
                    ManifestFile prev = oldManifest.files.get(rel);
                    if (prev != null && snap.sha256.equals(prev.sha256) && prev.driveFileId != null && !prev.driveFileId.isBlank()) {
                        newManifest.files.put(rel, prev);
                        skipped++;
                    } else {
                        String existingId = (prev != null && prev.driveFileId != null && !prev.driveFileId.isBlank())
                                ? prev.driveFileId
                                : existingDriveFilesByName.get(rel);
                        String fileId = (snap.bytes != null)
                                ? drive.uploadOrUpdateBytesById(filesFolderId, existingId, rel, "application/octet-stream", snap.bytes)
                                : drive.uploadOrUpdateFileById(filesFolderId, existingId, rel, "application/octet-stream", snap.tempFile);
                        existingDriveFilesByName.put(rel, fileId);
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
        if (manifest == null || manifest.files == null) {
            return new SyncOpResult(false, "Corrupt manifest on Google Drive.");
        }

        Files.createDirectories(targetWorldDir);

        int downloaded = 0;
        int skipped = 0;
        int failed = 0;
        Path normalizedRoot = targetWorldDir.toAbsolutePath().normalize();
        Set<Path> expectedFiles = new HashSet<>();

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
            if (drive.downloadFileById(mf.driveFileId, dest)) {
                if (mf.sha256 != null && !mf.sha256.isBlank() && !mf.sha256.equals(sha256(dest))) {
                    failed++;
                    FabricP2PMod.LOGGER.warn("[SharedWorlds] SHA-256 mismatch after downloading '{}' (id={})", rel, mf.driveFileId);
                } else {
                    downloaded++;
                }
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

    private SyncOpResult uploadIncrementalToLocalFolder(Path worldDir, String worldName, String hostName, boolean preserveLockIfActive, boolean isExitingHost) {
        SharedWorldConfig cfg = SharedWorldConfig.getInstance();
        try {
            Path syncRoot = Path.of(cfg.getSyncFolderPath()).toAbsolutePath().normalize();
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
            if (!preserveLockIfActive && !isExitingHost) {
                writeSessionLock(sharedDir, false, hostName, "", "", worldName);
            }

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
        if (manifest == null || manifest.files == null) {
            return new SyncOpResult(false, "No manifest found in shared folder for '" + worldName + "'.");
        }

        Files.createDirectories(targetWorldDir);

        Path filesRoot = sharedDir.resolve(FILES_SUBDIR).normalize();
        Path normalizedRoot = targetWorldDir.toAbsolutePath().normalize();
        int copied = 0;
        int skipped = 0;
        int missing = 0;
        int failed = 0;
        Set<Path> expectedFiles = new HashSet<>();

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
            copyFileAtomically(src, dest);
            if (mf.sha256 != null && !mf.sha256.isBlank() && !mf.sha256.equals(sha256(dest))) {
                failed++;
                FabricP2PMod.LOGGER.warn("[SharedWorlds] SHA-256 mismatch after copying '{}' from sync folder", rel);
            } else {
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
        boolean useDrive = isUsingGoogleDrive();
        if (!cfg.isEnabled() || (!useDrive && !cfg.hasValidSyncFolder())
                || mc == null || mc.getSingleplayerServer() == null) {
            return;
        }

        IntegratedServer server = mc.getSingleplayerServer();
        Path worldDir = server.getWorldPath(LevelResource.ROOT).normalize();
        String worldName = sanitizeName(worldDir.getFileName().toString());
        if (worldName.isEmpty()) {
            return;
        }
        String hostName = (mc.getUser() != null && mc.getUser().getName() != null && !mc.getUser().getName().isBlank())
                ? mc.getUser().getName()
                : "Host";

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

        if (!P2PNetworkManager.getInstance().isP2PActive()) {
            return;
        }

        synchronized (heartbeatLock) {
            if (!P2PNetworkManager.getInstance().isP2PActive()) {
                return;
            }
            stopHeartbeat();
            this.activeHostedWorldName = worldName;
            this.activeHostedUseDrive = useDrive;
            this.activeHostedLocalDir = worldDir;
            this.activeRoomCode = roomCode;
            this.activeModeName = mode.name();
            final long sessionSeq = heartbeatSessionSeq.incrementAndGet();

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
                if (heartbeatSessionSeq.get() != sessionSeq
                        || !worldName.equals(currentWorld)
                        || currentRoom == null) {
                    return;
                }
                if (useDrive) {
                    writeDriveSessionLock(worldName, true, hostName, currentRoom, currentMode);
                } else {
                    writeSessionLock(Path.of(cfg.getSyncFolderPath()).resolve(worldName), true, hostName, currentRoom, currentMode, worldName);
                }
            };
            newExec.scheduleAtFixedRate(publishLock, 0, 12, TimeUnit.SECONDS);
        }
    }

    public void onP2PHostStopped(Minecraft mc) {
        String worldName;
        Path worldDir;
        boolean useDrive;
        ScheduledExecutorService stoppedHeartbeat;
        synchronized (heartbeatLock) {
            worldName = this.activeHostedWorldName;
            worldDir = this.activeHostedLocalDir;
            useDrive = this.activeHostedUseDrive;
            this.activeHostedWorldName = null;
            this.activeHostedLocalDir = null;
            this.activeHostedUseDrive = false;
            this.activeRoomCode = null;
            this.activeModeName = null;
            heartbeatSessionSeq.incrementAndGet();
            stoppedHeartbeat = stopHeartbeat();
        }

        SharedWorldConfig cfg = SharedWorldConfig.getInstance();
        if (worldName == null || !cfg.isEnabled()
                || (useDrive && !cfg.isGoogleSignedIn())
                || (!useDrive && !cfg.hasValidSyncFolder())) {
            return;
        }

        String hostName = (mc != null && mc.getUser() != null && mc.getUser().getName() != null && !mc.getUser().getName().isBlank())
                ? mc.getUser().getName()
                : "Host";

        // Trigger world flush if server is still running (e.g. clicking "Stop P2P Host" in-game)
        IntegratedServer runningServer = (mc != null) ? mc.getSingleplayerServer() : null;
        CompletableFuture<Void> saveReady = new CompletableFuture<>();
        if (runningServer != null && runningServer.isRunning()) {
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
        Thread exitSyncThread = new Thread(() -> {
            try {
                if (runningServer == null) {
                    Thread.sleep(1500);
                } else {
                    try {
                        saveReady.get(4, TimeUnit.SECONDS);
                    } catch (Exception ignored) {}
                    if (!runningServer.isRunning()) {
                        long deadline = System.currentTimeMillis() + 5_000L;
                        while (!runningServer.isStopped() && System.currentTimeMillis() < deadline) {
                            Thread.sleep(100);
                        }
                    }
                }

                // Wait for any in-flight heartbeat to terminate before syncing or releasing lock
                awaitHeartbeatTermination(stoppedHeartbeat);

                if (cfg.isAutoSyncOnExit() && worldDir != null && Files.exists(worldDir)) {
                    synchronized (syncLock) {
                        activeSyncOps.incrementAndGet();
                        try {
                            if (useDrive) {
                                uploadIncrementalToGoogleDrive(worldDir, worldName, hostName, true);
                            } else {
                                uploadIncrementalToLocalFolder(worldDir, worldName, hostName, false, true);
                            }
                        } finally {
                            activeSyncOps.decrementAndGet();
                        }
                    }
                }
            } catch (Exception e) {
                FabricP2PMod.LOGGER.warn("[SharedWorlds] Auto-sync on exit failed: {}", e.getMessage());
            } finally {
                awaitHeartbeatTermination(stoppedHeartbeat);
                if (!worldName.equals(this.activeHostedWorldName)) {
                    if (useDrive) {
                        writeDriveSessionLock(worldName, false, hostName, "", "");
                    } else {
                        writeSessionLock(Path.of(cfg.getSyncFolderPath()).resolve(worldName), false, hostName, "", "", worldName);
                    }
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
        Path tmp = dest.resolveSibling(dest.getFileName().toString() + ".p2p.tmp");
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
        Path tmp = dest.resolveSibling(dest.getFileName().toString() + ".p2p.tmp");
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
