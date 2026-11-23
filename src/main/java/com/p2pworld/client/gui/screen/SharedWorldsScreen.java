package com.p2pworld.client.gui.screen;

import com.p2pworld.client.gui.fx.NeonRenderUtils;
import com.p2pworld.client.gui.fx.NeonTheme;
import com.p2pworld.client.gui.fx.ParticleBackground;
import com.p2pworld.client.gui.widget.NeonButton;
import com.p2pworld.client.gui.widget.NeonToggleCard;
import com.p2pworld.client.network.P2PNetworkManager;
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
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
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
    private boolean screenActive = true;

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
        this.screenActive = true;
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
                    String entered = folderPathInput.getValue() != null ? folderPathInput.getValue().trim() : "";
                    if (entered.isBlank()) {
                        config.setSyncFolderPath("");
                        this.savedFolderInput = "";
                        refreshWorldsListAsync(false);
                        updateWidgetStates();
                        if (config.isGoogleSignedIn()) {
                            showToast(tr("screen.fabricp2p.shared.toast_using_drive"), NeonTheme.CYAN);
                        } else {
                            showToast(tr("screen.fabricp2p.shared.toast_folder_cleared"), NeonTheme.SLATE);
                        }
                        return;
                    }

                    boolean validDir = false;
                    try {
                        Path candidate = Path.of(entered);
                        validDir = Files.exists(candidate) && Files.isDirectory(candidate);
                    } catch (Exception ignored) {}

                    if (!validDir) {
                        showToast(tr("screen.fabricp2p.shared.toast_folder_invalid"), NeonTheme.CRIMSON);
                        return;
                    }

                    config.setSyncFolderPath(entered);
                    this.savedFolderInput = entered;
                    refreshWorldsListAsync(false);
                    updateWidgetStates();
                    showToast(tr("screen.fabricp2p.shared.toast_folder_ok"), NeonTheme.EMERALD);
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
                Component.translatable("screen.fabricp2p.shared.btn_next"),
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

    private static String safeHostName(String name) {
        return (name != null && !name.isBlank()) ? name : "Host";
    }

    private static String extractErrorMessage(Throwable err) {
        if (err == null) return "Unknown error";
        Throwable cause = (err instanceof CompletionException && err.getCause() != null) ? err.getCause() : err;
        String msg = cause.getMessage();
        return (msg != null && !msg.isBlank()) ? msg : cause.getClass().getSimpleName();
    }

    private String truncatePlain(String text, int maxWidth) {
        if (text == null || maxWidth <= 0) return "";
        if (this.font.width(text) <= maxWidth) return text;
        int ellW = this.font.width("...");
        if (maxWidth <= ellW) {
            return this.font.plainSubstrByWidth(text, maxWidth);
        }
        return this.font.plainSubstrByWidth(text, maxWidth - ellW) + "...";
    }

    private String truncateBold(String text, int maxWidth) {
        if (text == null || maxWidth <= 0) return "";
        if (this.font.width(Component.literal(text).withStyle(ChatFormatting.BOLD)) <= maxWidth) return text;
        int ellW = this.font.width(Component.literal("...").withStyle(ChatFormatting.BOLD));
        if (maxWidth <= ellW) {
            return this.font.plainSubstrByWidth(text, maxWidth);
        }
        String trimmed = this.font.plainSubstrByWidth(text, Math.max(8, maxWidth - ellW));
        while (!trimmed.isEmpty() && this.font.width(Component.literal(trimmed + "...").withStyle(ChatFormatting.BOLD)) > maxWidth) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed + "...";
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

        CompletableFuture.supplyAsync(manager::listSharedWorlds).whenComplete((result, err) -> {
            if (this.minecraft == null) return;
            this.minecraft.execute(() -> {
                if (refreshGeneration.get() != gen) return;
                this.loadingWorlds = false;
                if (err != null) {
                    updateWidgetStates();
                    showToast(tr("screen.fabricp2p.shared.toast_refresh_error", extractErrorMessage(err)), NeonTheme.CRIMSON);
                    return;
                }
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
                showToast(tr("screen.fabricp2p.shared.toast_invalid_lock_code"), NeonTheme.CRIMSON);
                return;
            }
            if (target.isUdpHolePunch()) {
                String hostName = safeHostName(entry.liveHostName());
                this.busy = true;
                updateWidgetStates();
                showToast(tr("screen.fabricp2p.shared.toast_joining_udp", hostName), NeonTheme.CYAN);
                UdpHolePunchService.getInstance().startClientProxyAsync(target.host()).whenComplete((res, err) -> {
                    if (this.minecraft == null) return;
                    this.minecraft.execute(() -> {
                        this.busy = false;
                        if (!this.screenActive) {
                            if (err == null && res != null && res.success()) {
                                UdpHolePunchService.getInstance().stopClientProxy();
                            }
                            return;
                        }
                        updateWidgetStates();
                        if (err != null) {
                            showToast(tr("screen.fabricp2p.shared.toast_udp_fail", extractErrorMessage(err)), NeonTheme.CRIMSON);
                            return;
                        }
                        if (res != null && res.success()) {
                            if (this.minecraft.level != null) {
                                P2PNetworkManager.getInstance().suppressNextClientProxyDisconnectCleanup();
                            }
                            connectToAddress("Shared: " + entry.worldName(), "127.0.0.1", res.localPort());
                        } else {
                            String reason = (res != null && res.error() != null) ? res.error() : "timeout";
                            showToast(tr("screen.fabricp2p.shared.toast_udp_fail", reason), NeonTheme.CRIMSON);
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
        showToast(tr("screen.fabricp2p.shared.toast_pulling", entry.worldName(), entry.revision()), NeonTheme.CYAN);
        manager.pullWorldToLocalSavesAsync(this.minecraft, entry.worldName()).whenComplete((res, err) -> {
            if (this.minecraft == null) return;
            this.minecraft.execute(() -> {
                this.busy = false;
                refreshWorldsListAsync(false);
                updateWidgetStates();
                if (err != null) {
                    showToast("⚠ " + extractErrorMessage(err), NeonTheme.CRIMSON);
                } else if (res != null) {
                    showToast(res.success() ? ("✅ " + res.message()) : ("⚠ " + res.message()),
                            res.success() ? NeonTheme.EMERALD : NeonTheme.CRIMSON);
                } else {
                    showToast(tr("screen.fabricp2p.shared.toast_op_failed"), NeonTheme.CRIMSON);
                }
            });
        });
    }

    private void connectToAddress(String label, String host, int port) {
        if (this.minecraft == null) return;
        Screen returnScreen = (this.minecraft.level != null || this.parent instanceof P2PHostScreen)
                ? new TitleScreen()
                : this.parent;
        String formattedAddr = RoomCodeHelper.formatHostPort(host, port);
        ServerData serverData = new ServerData(label, formattedAddr, ServerData.Type.OTHER);
        ConnectScreen.startConnecting(
                returnScreen,
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
            showToast(tr("screen.fabricp2p.shared.toast_signed_out"), NeonTheme.SLATE);
            return;
        }
        this.busy = true;
        updateWidgetStates();
        showToast(tr("screen.fabricp2p.shared.toast_google_opening"), NeonTheme.CYAN);
        auth.signInAsync().whenComplete((res, err) -> {
            if (this.minecraft == null) return;
            this.minecraft.execute(() -> {
                this.busy = false;
                refreshWorldsListAsync(false);
                updateWidgetStates();
                if (err != null) {
                    showToast(tr("screen.fabricp2p.shared.toast_google_fail", extractErrorMessage(err)), NeonTheme.CRIMSON);
                } else if (res != null && res.success()) {
                    showToast(tr("screen.fabricp2p.shared.toast_google_ok", res.email()), NeonTheme.EMERALD);
                } else {
                    String reason = (res != null && res.error() != null) ? res.error() : "unknown error";
                    showToast(tr("screen.fabricp2p.shared.toast_google_fail", reason), NeonTheme.CRIMSON);
                }
            });
        });
    }

    private void handleUploadCurrentWorld() {
        if (this.minecraft == null || busy) return;
        this.busy = true;
        updateWidgetStates();
        showToast(tr("screen.fabricp2p.shared.toast_uploading"), NeonTheme.VIOLET);
        manager.uploadCurrentWorldAsync(this.minecraft).whenComplete((res, err) -> {
            if (this.minecraft == null) return;
            this.minecraft.execute(() -> {
                this.busy = false;
                refreshWorldsListAsync(false);
                updateWidgetStates();
                if (err != null) {
                    showToast("⚠ " + extractErrorMessage(err), NeonTheme.CRIMSON);
                } else if (res != null) {
                    showToast(res.success() ? ("✅ " + res.message()) : ("⚠ " + res.message()),
                            res.success() ? NeonTheme.EMERALD : NeonTheme.CRIMSON);
                } else {
                    showToast(tr("screen.fabricp2p.shared.toast_op_failed"), NeonTheme.CRIMSON);
                }
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
                googleSignInButton.setMessage(Component.translatable("screen.fabricp2p.shared.btn_google_waiting").withStyle(ChatFormatting.BOLD));
            } else if (config.isGoogleSignedIn()) {
                googleSignInButton.setAccentRgb(NeonTheme.EMERALD);
                String prefix = tr("screen.fabricp2p.shared.btn_google_signed_in_prefix");
                String suffix = tr("screen.fabricp2p.shared.btn_google_signed_in_suffix");
                String email = config.getGoogleUserEmail();
                int maxTotalW = Math.max(60, googleSignInButton.getWidth() - 24);
                int fixedW = this.font.width(Component.literal(prefix + suffix).withStyle(ChatFormatting.BOLD));
                String displayEmail = truncateBold(email, Math.max(24, maxTotalW - fixedW));
                googleSignInButton.setMessage(Component.literal(prefix + displayEmail + suffix)
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
                String hostName = safeHostName(entry.liveHostName()).toUpperCase();
                String liveLabel = truncateBold(
                        tr("screen.fabricp2p.shared.btn_join_live", hostName),
                        Math.max(40, worldActionButton.getWidth() - 26)
                );
                worldActionButton.setMessage(Component.literal(liveLabel).withStyle(ChatFormatting.BOLD));
            } else {
                worldActionButton.active = true;
                worldActionButton.setAccentRgb(NeonTheme.CYAN);
                worldActionButton.setPulsingBeacon(false);
                String pullLabel = truncateBold(
                        tr("screen.fabricp2p.shared.btn_pull_rev", entry.revision()),
                        Math.max(40, worldActionButton.getWidth() - 18)
                );
                worldActionButton.setMessage(Component.literal(pullLabel).withStyle(ChatFormatting.BOLD));
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
                tr(config.isEnabled() ? "screen.fabricp2p.shared.badge_on" : "screen.fabricp2p.shared.badge_off"),
                config.isEnabled() ? NeonTheme.EMERALD : NeonTheme.SLATE,
                config.isEnabled(), timeSec);

        // Local Sync Folder Frame
        graphics.text(this.font, truncatePlain(tr("screen.fabricp2p.shared.folder_label"), Math.max(40, panelW)),
                left, folderY - 10, NeonTheme.TEXT_SECONDARY, false);

        int inputW = panelW - 110;
        boolean backendReady = config.hasValidSyncFolder() || config.isGoogleSignedIn();
        graphics.fill(left, folderY, left + inputW, folderY + 22, NeonTheme.INPUT_BG);
        graphics.outline(left, folderY, inputW, 22,
                NeonTheme.withAlpha(config.isEnabled() ? (backendReady ? NeonTheme.EMERALD : NeonTheme.AMBER) : NeonTheme.SLATE, 160));

        // Shared World Card
        int worldCardH = compact ? 60 : 66;
        int maxCardTextW = Math.max(40, panelW - 20);
        SharedWorldManager.SharedWorldEntry entry = getSelectedWorld();
        int cardAccent = (entry != null && entry.live()) ? NeonTheme.EMERALD : accent;
        NeonRenderUtils.drawGlassCard(graphics, left, worldCardY, panelW, worldCardH, cardAccent, entry != null && entry.live(), timeSec);

        if (!config.isEnabled()) {
            graphics.centeredText(this.font, truncatePlain(tr("screen.fabricp2p.shared.disabled_hint"), maxCardTextW),
                    cx, worldCardY + 14, NeonTheme.TEXT_MUTED);
        } else if (!backendReady) {
            graphics.centeredText(this.font, truncatePlain(tr("screen.fabricp2p.shared.setup_hint"), maxCardTextW),
                    cx, worldCardY + 14, NeonTheme.withAlpha(NeonTheme.AMBER, 230));
        } else if (loadingWorlds) {
            graphics.centeredText(this.font, truncatePlain(tr("screen.fabricp2p.shared.loading_hint"), maxCardTextW),
                    cx, worldCardY + 10, NeonTheme.withAlpha(NeonTheme.CYAN, 235));
            NeonRenderUtils.drawScannerBar(graphics, left + 20, worldCardY + 24, panelW - 40, NeonTheme.CYAN, timeSec);
        } else if (entry == null) {
            graphics.centeredText(this.font, truncatePlain(tr("screen.fabricp2p.shared.empty_hint"), maxCardTextW),
                    cx, worldCardY + 14, NeonTheme.TEXT_SECONDARY);
        } else {
            String indexTag = worlds.size() > 1 ? (" (" + (selectedWorldIndex + 1) + "/" + worlds.size() + ")") : "";
            String titlePrefix = "☁ ";
            int fixedTitleW = this.font.width(Component.literal(titlePrefix + indexTag).withStyle(ChatFormatting.BOLD));
            String worldName = entry.worldName() != null ? entry.worldName() : "";
            String displayWorldName = truncateBold(worldName, Math.max(24, maxCardTextW - fixedTitleW));
            graphics.text(this.font, Component.literal(titlePrefix + displayWorldName + indexTag).withStyle(ChatFormatting.BOLD),
                    left + 10, worldCardY + 7, NeonTheme.TEXT_PRIMARY, true);

            String hostName = safeHostName(entry.liveHostName());
            String lastHost = (entry.lastHost() != null && !entry.lastHost().isBlank()) ? entry.lastHost() : "Unknown";
            String statusStr = entry.live()
                    ? tr("screen.fabricp2p.shared.status_live", hostName, entry.revision())
                    : tr("screen.fabricp2p.shared.status_standby", lastHost, entry.revision());
            statusStr = truncatePlain(statusStr, maxCardTextW);
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
    public void removed() {
        this.screenActive = false;
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
