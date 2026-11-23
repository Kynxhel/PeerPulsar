package com.p2pworld.client.gui.screen;

import com.p2pworld.client.gui.fx.NeonRenderUtils;
import com.p2pworld.client.gui.fx.NeonTheme;
import com.p2pworld.client.gui.fx.ParticleBackground;
import com.p2pworld.client.gui.widget.NeonButton;
import com.p2pworld.client.gui.widget.NeonToggleCard;
import com.p2pworld.client.network.RoomCodeHelper;
import com.p2pworld.client.network.UdpHolePunchService;
import com.p2pworld.client.shared.GoogleAuthService;
import com.p2pworld.client.shared.SharedWorldConfig;
import com.p2pworld.client.shared.SharedWorldManager;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Toggleable Shared Cloud Worlds screen (Google Drive API v3 / OneDrive / Dropbox / Syncthing folder).
 * Performs all Google Drive / disk scans asynchronously so the Minecraft render thread never freezes.
 */
public class SharedWorldsScreen extends Screen {
    private final Screen parent;
    private final SharedWorldConfig config = SharedWorldConfig.getInstance();
    private final SharedWorldManager manager = SharedWorldManager.getInstance();
    private final ParticleBackground particleBg = new ParticleBackground();
    private final long openTimeMs = System.currentTimeMillis();
    private final AtomicInteger refreshGeneration = new AtomicInteger(0);

    private NeonToggleCard enableFeatureCard;
    private NeonToggleCard autoSyncExitCard;
    private EditBox folderPathInput;
    private NeonButton saveFolderButton;
    private NeonButton refreshButton;
    private NeonButton googleSignInButton;
    private NeonButton uploadCurrentButton;
    private NeonButton worldActionButton;
    private NeonButton nextWorldButton;
    private NeonButton backButton;

    private String savedFolderInput;
    private List<SharedWorldManager.SharedWorldEntry> worlds = new ArrayList<>();
    private int selectedWorldIndex = 0;
    private boolean busy = false;
    private boolean loadingWorlds = false;

    private String toastMessage = null;
    private int toastAccent = NeonTheme.CYAN;
    private long toastStartMs = 0;

    // Layout metrics
    private int panelW;
    private int left;
    private int topY;
    private int folderY;
    private int worldCardY;
    private boolean compact;

    public SharedWorldsScreen(Screen parent) {
        super(Component.translatable("screen.fabricp2p.shared.title"));
        this.parent = parent;
        this.savedFolderInput = config.getSyncFolderPath();
    }

    private static String tr(String key, Object... args) {
        return Component.translatable(key, args).getString();
    }

    private void computeLayout() {
        this.compact = this.height < 268;
        this.panelW = Math.min(356, this.width - 20);
        this.left = (this.width - this.panelW) / 2;
        int totalH = compact ? 222 : 254;
        this.topY = Math.max(8, (this.height - totalH) / 2);
        int toggleY = topY + (compact ? 30 : 38);
        int cardH = compact ? 30 : 34;
        int googleY = toggleY + cardH + (compact ? 5 : 6);
        this.folderY = googleY + (compact ? 32 : 36);
        this.worldCardY = this.folderY + (compact ? 26 : 30);
    }

    @Override
    protected void init() {
        computeLayout();

        // Row 1: Master Feature Toggle + Auto-Sync on Exit Toggle
        int cardW = (panelW - 8) / 2;
        int cardH = compact ? 30 : 34;
        int toggleY = topY + (compact ? 30 : 38);

        this.enableFeatureCard = new NeonToggleCard(
                left, toggleY, cardW, cardH,
                tr("screen.fabricp2p.shared.card_enable_title"),
                () -> config.isEnabled()
                        ? tr("screen.fabricp2p.shared.card_enable_on")
                        : tr("screen.fabricp2p.shared.card_enable_off"),
                config::isEnabled,
                () -> config.isEnabled() ? NeonTheme.EMERALD : NeonTheme.SLATE,
                () -> {
                    boolean next = !config.isEnabled();
                    config.setEnabled(next);
                    refreshWorldsListAsync(false);
                    updateWidgetStates();
                    showToast(next ? tr("screen.fabricp2p.shared.toast_enabled") : tr("screen.fabricp2p.shared.toast_disabled"),
                            next ? NeonTheme.EMERALD : NeonTheme.SLATE);
                }
        );
        this.addRenderableWidget(enableFeatureCard);

        this.autoSyncExitCard = new NeonToggleCard(
                left + cardW + 8, toggleY, cardW, cardH,
                tr("screen.fabricp2p.shared.card_autosync_title"),
                () -> config.isAutoSyncOnExit()
                        ? tr("screen.fabricp2p.shared.card_autosync_on")
                        : tr("screen.fabricp2p.shared.card_autosync_off"),
                config::isAutoSyncOnExit,
                () -> config.isAutoSyncOnExit() ? NeonTheme.CYAN : NeonTheme.SLATE,
                () -> {
                    config.setAutoSyncOnExit(!config.isAutoSyncOnExit());
                    updateWidgetStates();
                }
        );
        this.addRenderableWidget(autoSyncExitCard);

        // Row 2: Full-Width Google Sign-In Bar
        int googleY = toggleY + cardH + (compact ? 5 : 6);
        this.googleSignInButton = new NeonButton(
                left, googleY, panelW, compact ? 20 : 22,
                Component.translatable("screen.fabricp2p.shared.btn_google_signin").withStyle(ChatFormatting.BOLD),
                NeonTheme.CYAN,
                this::handleGoogleSignInOrOut
        );
        this.addRenderableWidget(googleSignInButton);

        // Row 3: Optional Local Sync Folder Path
        this.folderPathInput = new EditBox(this.font, left + 10, folderY + 5, panelW - 122, 14, Component.literal("Sync Folder"));
        this.folderPathInput.setMaxLength(512);
        this.folderPathInput.setBordered(false);
        this.folderPathInput.setTextColor(NeonTheme.TEXT_PRIMARY);
        this.folderPathInput.setValue(this.savedFolderInput != null ? this.savedFolderInput : "");
        this.folderPathInput.setResponder(v -> this.savedFolderInput = v);
        this.folderPathInput.setHint(Component.translatable("screen.fabricp2p.shared.folder_placeholder").withStyle(ChatFormatting.DARK_GRAY));
        this.addRenderableWidget(folderPathInput);

        this.saveFolderButton = new NeonButton(
                left + panelW - 104, folderY, 52, 22,
                Component.translatable("screen.fabricp2p.shared.btn_save"),
                NeonTheme.CYAN,
                () -> {
                    config.setSyncFolderPath(folderPathInput.getValue());
                    refreshWorldsListAsync(false);
                    updateWidgetStates();
                    if (config.hasValidSyncFolder()) {
                        showToast(tr("screen.fabricp2p.shared.toast_folder_ok"), NeonTheme.EMERALD);
                    } else if (folderPathInput.getValue().isBlank() && config.isGoogleSignedIn()) {
                        showToast("☁ Using Google Drive direct sync.", NeonTheme.CYAN);
                    } else {
                        showToast(tr("screen.fabricp2p.shared.toast_folder_invalid"), NeonTheme.CRIMSON);
                    }
                }
        );
        this.addRenderableWidget(saveFolderButton);

        this.refreshButton = new NeonButton(
                left + panelW - 48, folderY, 48, 22,
                Component.literal("⟳"),
                NeonTheme.VIOLET,
                () -> refreshWorldsListAsync(true)
        );
        this.addRenderableWidget(refreshButton);

        // Row 4: Active World Card Action Buttons
        int worldCardH = compact ? 60 : 66;
        int worldBtnY = worldCardY + worldCardH - 24;
        this.worldActionButton = new NeonButton(
                left + 10, worldBtnY, panelW - 96, 20,
                Component.translatable("screen.fabricp2p.shared.btn_pull"),
                NeonTheme.EMERALD,
                this::handleSelectedWorldAction
        );
        this.addRenderableWidget(worldActionButton);

        this.nextWorldButton = new NeonButton(
                left + panelW - 80, worldBtnY, 70, 20,
                Component.literal("Next ▸"),
                NeonTheme.SLATE,
                () -> {
                    if (!worlds.isEmpty()) {
                        selectedWorldIndex = (selectedWorldIndex + 1) % worlds.size();
                        updateWidgetStates();
                    }
                }
        );
        this.addRenderableWidget(nextWorldButton);

        // Row 5: Upload Current Singleplayer World + Back
        int bottomY = Math.min(this.height - 24, worldCardY + worldCardH + (compact ? 4 : 6));
        int uploadW = Math.min(198, (panelW * 56) / 100);
        this.uploadCurrentButton = new NeonButton(
                left, bottomY, uploadW, 22,
                Component.translatable("screen.fabricp2p.shared.btn_upload_current").withStyle(ChatFormatting.BOLD),
                NeonTheme.VIOLET,
                this::handleUploadCurrentWorld
        );
        this.addRenderableWidget(uploadCurrentButton);

        this.backButton = new NeonButton(
                left + uploadW + 8, bottomY, panelW - uploadW - 8, 22,
                Component.literal("← ").append(Component.translatable("gui.back")),
                NeonTheme.SLATE,
                () -> {
                    if (this.minecraft != null) this.minecraft.setScreenAndShow(this.parent);
                }
        );
        this.addRenderableWidget(backButton);

        updateWidgetStates();
        refreshWorldsListAsync(false);
    }

    /**
     * Fetches shared worlds asynchronously so Google Drive API calls never stall Minecraft's render thread.
     */
    private void refreshWorldsListAsync(boolean showToastOnDone) {
        if (!config.isEnabled() || (!config.hasValidSyncFolder() && !config.isGoogleSignedIn())) {
            this.worlds = new ArrayList<>();
            this.selectedWorldIndex = 0;
            this.loadingWorlds = false;
            updateWidgetStates();
            return;
        }

        int gen = refreshGeneration.incrementAndGet();
        this.loadingWorlds = true;
        updateWidgetStates();

        CompletableFuture.supplyAsync(manager::listSharedWorlds).thenAccept(result -> {
            if (this.minecraft == null) return;
            this.minecraft.execute(() -> {
                if (refreshGeneration.get() != gen) return;
                this.loadingWorlds = false;
                this.worlds = result != null ? result : new ArrayList<>();
                if (selectedWorldIndex >= this.worlds.size()) {
                    selectedWorldIndex = 0;
                }
                updateWidgetStates();
                if (showToastOnDone) {
                    showToast(tr("screen.fabricp2p.shared.toast_refreshed", this.worlds.size()), NeonTheme.VIOLET);
                }
            });
        });
    }

    private SharedWorldManager.SharedWorldEntry getSelectedWorld() {
        if (worlds.isEmpty() || selectedWorldIndex < 0 || selectedWorldIndex >= worlds.size()) {
            return null;
        }
        return worlds.get(selectedWorldIndex);
    }

    private void handleSelectedWorldAction() {
        SharedWorldManager.SharedWorldEntry entry = getSelectedWorld();
        if (entry == null || this.minecraft == null || busy) return;

        if (entry.live() && entry.liveRoomCode() != null) {
            RoomCodeHelper.ConnectionTarget target = RoomCodeHelper.parseInput(entry.liveRoomCode());
            if (target == null) {
                showToast("⚠ Invalid live room code in lockfile.", NeonTheme.CRIMSON);
                return;
            }
            if (target.isUdpHolePunch()) {
                this.busy = true;
                updateWidgetStates();
                showToast("⚡ Joining " + entry.liveHostName() + " via UDP Hole Punch...", NeonTheme.CYAN);
                UdpHolePunchService.getInstance().startClientProxyAsync(target.host()).thenAccept(res -> {
                    if (this.minecraft == null) return;
                    this.minecraft.execute(() -> {
                        this.busy = false;
                        updateWidgetStates();
                        if (res.success()) {
                            connectToAddress("Shared: " + entry.worldName(), "127.0.0.1", res.localPort());
                        } else {
                            showToast("⚠ UDP Join failed: " + res.error(), NeonTheme.CRIMSON);
                        }
                    });
                });
            } else {
                connectToAddress("Shared: " + entry.worldName(), target.host(), target.port());
            }
            return;
        }

        this.busy = true;
        updateWidgetStates();
        showToast("☁ Pulling latest '" + entry.worldName() + "' (Rev #" + entry.revision() + ")...", NeonTheme.CYAN);
        manager.pullWorldToLocalSavesAsync(this.minecraft, entry.worldName()).thenAccept(res -> {
            if (this.minecraft == null) return;
            this.minecraft.execute(() -> {
                this.busy = false;
                refreshWorldsListAsync(false);
                updateWidgetStates();
                showToast(res.success() ? ("✅ " + res.message()) : ("⚠ " + res.message()),
                        res.success() ? NeonTheme.EMERALD : NeonTheme.CRIMSON);
            });
        });
    }

    private void connectToAddress(String label, String host, int port) {
        if (this.minecraft == null) return;
        ServerData serverData = new ServerData(label, host + ":" + port, ServerData.Type.OTHER);
        ConnectScreen.startConnecting(
                this.parent,
                this.minecraft,
                new ServerAddress(host, port),
                serverData,
                false,
                null
        );
    }

    private void handleGoogleSignInOrOut() {
        GoogleAuthService auth = GoogleAuthService.getInstance();
        if (auth.isSignedIn()) {
            auth.signOut();
            refreshWorldsListAsync(false);
            updateWidgetStates();
            showToast("Signed out of Google.", NeonTheme.SLATE);
            return;
        }
        this.busy = true;
        updateWidgetStates();
        showToast("🌐 Opening Google Account Picker in browser...", NeonTheme.CYAN);
        auth.signInAsync().thenAccept(res -> {
            if (this.minecraft == null) return;
            this.minecraft.execute(() -> {
                this.busy = false;
                refreshWorldsListAsync(false);
                updateWidgetStates();
                if (res.success()) {
                    showToast("✅ Signed in as " + res.email() + "!", NeonTheme.EMERALD);
                } else {
                    showToast("⚠ Google Sign-In failed: " + res.error(), NeonTheme.CRIMSON);
                }
            });
        });
    }

    private void handleUploadCurrentWorld() {
        if (this.minecraft == null || busy) return;
        this.busy = true;
        updateWidgetStates();
        showToast("☁ Syncing changed world files (SHA-256 delta)...", NeonTheme.VIOLET);
        manager.uploadCurrentWorldAsync(this.minecraft).thenAccept(res -> {
            if (this.minecraft == null) return;
            this.minecraft.execute(() -> {
                this.busy = false;
                refreshWorldsListAsync(false);
                updateWidgetStates();
                showToast(res.success() ? ("✅ " + res.message()) : ("⚠ " + res.message()),
                        res.success() ? NeonTheme.EMERALD : NeonTheme.CRIMSON);
            });
        });
    }

    private void updateWidgetStates() {
        boolean enabled = config.isEnabled();
        boolean backendReady = enabled && (config.hasValidSyncFolder() || config.isGoogleSignedIn());
        boolean inSingleplayer = this.minecraft != null && this.minecraft.getSingleplayerServer() != null;
        boolean working = busy || manager.isSyncing() || GoogleAuthService.getInstance().isSigningIn();

        if (autoSyncExitCard != null) autoSyncExitCard.active = enabled && !working;
        if (googleSignInButton != null) {
            googleSignInButton.active = enabled && !working;
            if (GoogleAuthService.getInstance().isSigningIn()) {
                googleSignInButton.setAccentRgb(NeonTheme.AMBER);
                googleSignInButton.setMessage(Component.literal("⏳ WAITING FOR GOOGLE LOGIN...").withStyle(ChatFormatting.BOLD));
            } else if (config.isGoogleSignedIn()) {
                googleSignInButton.setAccentRgb(NeonTheme.EMERALD);
                googleSignInButton.setMessage(Component.literal("● Google: " + config.getGoogleUserEmail() + " (Sign Out)")
                        .withStyle(ChatFormatting.BOLD));
            } else {
                googleSignInButton.setAccentRgb(NeonTheme.CYAN);
                googleSignInButton.setMessage(Component.translatable("screen.fabricp2p.shared.btn_google_signin")
                        .withStyle(ChatFormatting.BOLD));
            }
        }
        if (folderPathInput != null) folderPathInput.active = enabled && !working;
        if (saveFolderButton != null) saveFolderButton.active = enabled && !working;
        if (refreshButton != null) refreshButton.active = backendReady && !working && !loadingWorlds;
        if (uploadCurrentButton != null) uploadCurrentButton.active = backendReady && inSingleplayer && !working;
        if (nextWorldButton != null) nextWorldButton.active = backendReady && worlds.size() > 1 && !working && !loadingWorlds;

        SharedWorldManager.SharedWorldEntry entry = getSelectedWorld();
        if (worldActionButton != null) {
            if (!backendReady || entry == null || working || loadingWorlds) {
                worldActionButton.active = false;
                worldActionButton.setAccentRgb(NeonTheme.SLATE);
                worldActionButton.setPulsingBeacon(false);
                worldActionButton.setMessage(Component.translatable(
                        (working || loadingWorlds) ? "screen.fabricp2p.shared.btn_syncing" : "screen.fabricp2p.shared.btn_no_world"
                ));
            } else if (entry.live()) {
                worldActionButton.active = true;
                worldActionButton.setAccentRgb(NeonTheme.EMERALD);
                worldActionButton.setPulsingBeacon(true);
                worldActionButton.setMessage(Component.literal("▶ JOIN " + entry.liveHostName().toUpperCase() + "'S LIVE WORLD")
                        .withStyle(ChatFormatting.BOLD));
            } else {
                worldActionButton.active = true;
                worldActionButton.setAccentRgb(NeonTheme.CYAN);
                worldActionButton.setPulsingBeacon(false);
                worldActionButton.setMessage(Component.literal("☁ PULL LATEST (REV #" + entry.revision() + ") TO SAVES")
                        .withStyle(ChatFormatting.BOLD));
            }
        }
    }

    private void showToast(String msg, int accentRgb) {
        this.toastMessage = msg;
        this.toastAccent = accentRgb;
        this.toastStartMs = System.currentTimeMillis();
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        double timeSec = NeonTheme.nowSec();
        float enterT = NeonTheme.easeOutCubic(Math.min(1.0f, (System.currentTimeMillis() - openTimeMs) / 360.0f));

        graphics.blurBeforeThisStratum();
        particleBg.render(graphics, this.width, this.height, mouseX, mouseY, config.isEnabled());

        int cx = this.width / 2;
        int slideOffset = Math.round((1.0f - enterT) * -10.0f);
        int accent = config.isEnabled() ? NeonTheme.CYAN : NeonTheme.SLATE;

        // Header
        int headerY = topY + slideOffset;
        int logoSize = compact ? 24 : 28;
        NeonRenderUtils.drawModLogo(graphics, left + 2, headerY, logoSize, accent, timeSec);
        graphics.text(this.font, Component.translatable("screen.fabricp2p.shared.title").withStyle(ChatFormatting.BOLD),
                left + logoSize + 10, headerY + 2, NeonTheme.TEXT_PRIMARY, true);
        if (!compact) {
            graphics.text(this.font, tr("screen.fabricp2p.shared.subtitle"),
                    left + logoSize + 10, headerY + 15, NeonTheme.TEXT_SECONDARY, false);
        }
        NeonRenderUtils.drawBadge(graphics, this.font, left + panelW - 58, headerY + (compact ? 4 : 6),
                config.isEnabled() ? "ON" : "OFF",
                config.isEnabled() ? NeonTheme.EMERALD : NeonTheme.SLATE,
                config.isEnabled(), timeSec);

        // Local Sync Folder Frame
        graphics.text(this.font, tr("screen.fabricp2p.shared.folder_label"),
                left, folderY - 10, NeonTheme.TEXT_SECONDARY, false);

        int inputW = panelW - 110;
        boolean backendReady = config.hasValidSyncFolder() || config.isGoogleSignedIn();
        graphics.fill(left, folderY, left + inputW, folderY + 22, NeonTheme.INPUT_BG);
        graphics.outline(left, folderY, inputW, 22,
                NeonTheme.withAlpha(config.isEnabled() ? (backendReady ? NeonTheme.EMERALD : NeonTheme.AMBER) : NeonTheme.SLATE, 160));

        // Shared World Card
        int worldCardH = compact ? 60 : 66;
        SharedWorldManager.SharedWorldEntry entry = getSelectedWorld();
        int cardAccent = (entry != null && entry.live()) ? NeonTheme.EMERALD : accent;
        NeonRenderUtils.drawGlassCard(graphics, left, worldCardY, panelW, worldCardH, cardAccent, entry != null && entry.live(), timeSec);

        if (!config.isEnabled()) {
            graphics.centeredText(this.font, tr("screen.fabricp2p.shared.disabled_hint"),
                    cx, worldCardY + 14, NeonTheme.TEXT_MUTED);
        } else if (!backendReady) {
            graphics.centeredText(this.font, tr("screen.fabricp2p.shared.setup_hint"),
                    cx, worldCardY + 14, NeonTheme.withAlpha(NeonTheme.AMBER, 230));
        } else if (loadingWorlds) {
            graphics.centeredText(this.font, tr("screen.fabricp2p.shared.loading_hint"),
                    cx, worldCardY + 10, NeonTheme.withAlpha(NeonTheme.CYAN, 235));
            NeonRenderUtils.drawScannerBar(graphics, left + 20, worldCardY + 24, panelW - 40, NeonTheme.CYAN, timeSec);
        } else if (entry == null) {
            graphics.centeredText(this.font, tr("screen.fabricp2p.shared.empty_hint"),
                    cx, worldCardY + 14, NeonTheme.TEXT_SECONDARY);
        } else {
            String indexTag = worlds.size() > 1 ? (" (" + (selectedWorldIndex + 1) + "/" + worlds.size() + ")") : "";
            graphics.text(this.font, Component.literal("☁ " + entry.worldName() + indexTag).withStyle(ChatFormatting.BOLD),
                    left + 10, worldCardY + 7, NeonTheme.TEXT_PRIMARY, true);

            String statusStr = entry.live()
                    ? ("● LIVE — Hosted by " + entry.liveHostName() + " • Rev #" + entry.revision())
                    : ("○ STANDBY — Last host: " + entry.lastHost() + " • Rev #" + entry.revision());
            graphics.text(this.font, statusStr,
                    left + 10, worldCardY + 20,
                    NeonTheme.withAlpha(entry.live() ? NeonTheme.EMERALD : NeonTheme.TEXT_SECONDARY, 255), entry.live());
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        NeonRenderUtils.drawToast(graphics, this.font, this.width, this.height,
                toastMessage, toastAccent, toastStartMs, 3600);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
