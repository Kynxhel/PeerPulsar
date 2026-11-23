package com.p2pworld.client.gui.screen;

import com.p2pworld.client.gui.fx.NeonRenderUtils;
import com.p2pworld.client.gui.fx.NeonTheme;
import com.p2pworld.client.gui.fx.ParticleBackground;
import com.p2pworld.client.gui.widget.NeonButton;
import com.p2pworld.client.gui.widget.NeonModeOptionCard;
import com.p2pworld.client.gui.widget.NeonToggleCard;
import com.p2pworld.client.network.NetworkDiagnosticsService;
import com.p2pworld.client.network.P2PNetworkManager;
import com.p2pworld.client.network.P2PNetworkManager.ConnectionMode;
import com.p2pworld.client.network.RoomCodeHelper;
import com.p2pworld.client.network.UPnPService;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.GameType;

public class P2PHostScreen extends Screen {
    private final Screen parent;
    private final P2PNetworkManager networkManager;
    private final ParticleBackground particleBg = new ParticleBackground();
    private final long openTimeMs = System.currentTimeMillis();

    private NeonToggleCard offlineAuthCard;
    private NeonToggleCard gameModeCard;
    private NeonToggleCard cheatsCard;
    private NeonToggleCard portCard;

    private NeonModeOptionCard directModeCard;
    private NeonModeOptionCard udpPunchModeCard;
    private NeonModeOptionCard tunnelModeCard;
    private NeonModeOptionCard lanModeCard;

    private NeonButton startStopButton;
    private NeonButton copyDiscordButton;
    private NeonButton copyCodeQuickButton;
    private NeonButton retestButton;
    private NeonButton backButton;

    private String toastMessage = null;
    private int toastAccent = NeonTheme.EMERALD;
    private long toastStartMs = 0;
    private static final long TOAST_DURATION_MS = 3400;

    // Layout metrics computed from screen size
    private int panelW;
    private int sideW;
    private int sideGap;
    private int panelLeft;
    private int sideLeft;
    private int topY;

    public P2PHostScreen(Screen parent) {
        super(Component.translatable("screen.fabricp2p.host.title"));
        this.parent = parent;
        this.networkManager = P2PNetworkManager.getInstance();
    }

    private static String tr(String key, Object... args) {
        return Component.translatable(key, args).getString();
    }

    private void computeLayout() {
        this.sideGap = 8;
        this.sideW = (this.width >= 520) ? 168 : 152;
        this.panelW = Math.min(316, Math.max(256, this.width - this.sideW - this.sideGap - 16));
        int totalW = this.panelW + this.sideGap + this.sideW;
        this.panelLeft = (this.width - totalW) / 2;
        this.sideLeft = this.panelLeft + this.panelW + this.sideGap;
        this.topY = Math.max(16, (this.height - 252) / 2);
    }

    @Override
    protected void init() {
        computeLayout();

        // Quick Re-Test Internet & NAT Diagnostics button (top-right inside status hero card)
        this.retestButton = new NeonButton(
                panelLeft + panelW - 56, topY + 41, 46, 14,
                Component.translatable("screen.fabricp2p.host.btn_retest"),
                NeonTheme.AMBER,
                () -> runNetworkCheck(true)
        );
        this.addRenderableWidget(retestButton);

        // Quick Copy Raw Room Code button (inside status hero card)
        this.copyCodeQuickButton = new NeonButton(
                panelLeft + panelW - 84, topY + 91, 74, 18,
                Component.translatable("screen.fabricp2p.host.btn_copy_raw"),
                NeonTheme.CYAN,
                () -> {
                    String code = networkManager.getHostedRoomCode();
                    if (code != null && this.minecraft != null) {
                        this.minecraft.keyboardHandler.setClipboard(code);
                        particleBg.spawnBurst(copyCodeQuickButton.getX() + 37, copyCodeQuickButton.getY() + 9, NeonTheme.CYAN, 26);
                        showToast(tr("screen.fabricp2p.host.toast_code_copied", code), NeonTheme.CYAN);
                    }
                }
        );
        this.addRenderableWidget(copyCodeQuickButton);

        // 2x2 Neon Toggle Card Grid (left main panel)
        int gridTop = topY + 122;
        int gapX = 8;
        int gapY = 8;
        int cardW = (panelW - gapX) / 2;
        int cardH = 34;

        this.offlineAuthCard = new NeonToggleCard(
                panelLeft, gridTop, cardW, cardH,
                tr("screen.fabricp2p.host.card_offline_title"),
                () -> networkManager.isCrackSupportEnabled()
                        ? tr("screen.fabricp2p.host.card_offline_on")
                        : tr("screen.fabricp2p.host.card_offline_off"),
                networkManager::isCrackSupportEnabled,
                () -> networkManager.isCrackSupportEnabled() ? NeonTheme.EMERALD : NeonTheme.CRIMSON,
                () -> {
                    boolean next = !networkManager.isCrackSupportEnabled();
                    networkManager.setCrackSupportEnabled(next);
                    particleBg.spawnBurst(offlineAuthCard.getX() + cardW - 20, offlineAuthCard.getY() + cardH / 2,
                            next ? NeonTheme.EMERALD : NeonTheme.CRIMSON, 14);
                }
        );
        this.addRenderableWidget(offlineAuthCard);

        this.gameModeCard = new NeonToggleCard(
                panelLeft + cardW + gapX, gridTop, cardW, cardH,
                tr("screen.fabricp2p.host.card_gamemode_title"),
                () -> networkManager.getSelectedGameType().getName().toUpperCase(),
                null,
                this::getGameModeAccent,
                () -> {
                    GameType current = networkManager.getSelectedGameType();
                    GameType next;
                    if (current == GameType.SURVIVAL) next = GameType.CREATIVE;
                    else if (current == GameType.CREATIVE) next = GameType.ADVENTURE;
                    else if (current == GameType.ADVENTURE) next = GameType.SPECTATOR;
                    else next = GameType.SURVIVAL;
                    networkManager.setSelectedGameType(next);
                    particleBg.spawnBurst(gameModeCard.getX() + cardW - 14, gameModeCard.getY() + cardH / 2,
                            getGameModeAccent(), 14);
                }
        );
        this.addRenderableWidget(gameModeCard);

        this.cheatsCard = new NeonToggleCard(
                panelLeft, gridTop + cardH + gapY, cardW, cardH,
                tr("screen.fabricp2p.host.card_cheats_title"),
                () -> networkManager.isAllowCheats()
                        ? tr("screen.fabricp2p.host.card_cheats_on")
                        : tr("screen.fabricp2p.host.card_cheats_off"),
                networkManager::isAllowCheats,
                () -> networkManager.isAllowCheats() ? NeonTheme.AMBER : NeonTheme.SLATE,
                () -> {
                    boolean next = !networkManager.isAllowCheats();
                    networkManager.setAllowCheats(next);
                    particleBg.spawnBurst(cheatsCard.getX() + cardW - 20, cheatsCard.getY() + cardH / 2,
                            next ? NeonTheme.AMBER : NeonTheme.SLATE, 14);
                }
        );
        this.addRenderableWidget(cheatsCard);

        this.portCard = new NeonToggleCard(
                panelLeft + cardW + gapX, gridTop + cardH + gapY, cardW, cardH,
                tr("screen.fabricp2p.host.card_port_title"),
                () -> networkManager.isUseDefaultPort()
                        ? tr("screen.fabricp2p.host.card_port_default")
                        : tr("screen.fabricp2p.host.card_port_random"),
                networkManager::isUseDefaultPort,
                () -> networkManager.isUseDefaultPort() ? NeonTheme.CYAN : NeonTheme.VIOLET,
                () -> {
                    boolean next = !networkManager.isUseDefaultPort();
                    networkManager.setUseDefaultPort(next);
                    particleBg.spawnBurst(portCard.getX() + cardW - 20, portCard.getY() + cardH / 2,
                            next ? NeonTheme.CYAN : NeonTheme.VIOLET, 14);
                }
        );
        this.addRenderableWidget(portCard);

        // Right-Side Connection Mode Selector Cards (4 options)
        int sideCardX = sideLeft + 8;
        int sideCardW = sideW - 16;
        int sideCardH = 31;
        int sideStartY = topY + 60;
        int sideCardGap = 5;

        NetworkDiagnosticsService diag = NetworkDiagnosticsService.getInstance();

        this.directModeCard = new NeonModeOptionCard(
                sideCardX, sideStartY, sideCardW, sideCardH,
                tr("screen.fabricp2p.host.mode_direct_title"),
                () -> {
                    if (diag.isTesting()) return tr("screen.fabricp2p.host.mode_testing");
                    NetworkDiagnosticsService.DiagnosticReport r = diag.getLastReport();
                    if (r.status() == NetworkDiagnosticsService.P2PCompatibilityStatus.DIRECT_SUPPORTED) {
                        return tr("screen.fabricp2p.host.mode_direct_ok");
                    }
                    if (r.status() == NetworkDiagnosticsService.P2PCompatibilityStatus.CGNAT_DETECTED) {
                        return tr("screen.fabricp2p.host.mode_direct_cgnat");
                    }
                    if (r.status() == NetworkDiagnosticsService.P2PCompatibilityStatus.UPNP_UNAVAILABLE) {
                        return tr("screen.fabricp2p.host.mode_direct_upnp");
                    }
                    if (r.status() == NetworkDiagnosticsService.P2PCompatibilityStatus.NO_INTERNET) {
                        return tr("screen.fabricp2p.host.mode_no_internet");
                    }
                    return tr("screen.fabricp2p.host.mode_untested");
                },
                () -> networkManager.isModeSupported(ConnectionMode.DIRECT_UPNP, diag.getLastReport()),
                diag::isTesting,
                () -> networkManager.getSelectedConnectionMode() == ConnectionMode.DIRECT_UPNP,
                () -> networkManager.getRecommendedMode(diag.getLastReport()) == ConnectionMode.DIRECT_UPNP,
                NeonTheme.EMERALD,
                () -> selectMode(ConnectionMode.DIRECT_UPNP, directModeCard, NeonTheme.EMERALD)
        );
        this.addRenderableWidget(directModeCard);

        this.udpPunchModeCard = new NeonModeOptionCard(
                sideCardX, sideStartY + (sideCardH + sideCardGap), sideCardW, sideCardH,
                tr("screen.fabricp2p.host.mode_udp_title"),
                () -> {
                    if (diag.isTesting()) return tr("screen.fabricp2p.host.mode_testing");
                    NetworkDiagnosticsService.DiagnosticReport r = diag.getLastReport();
                    if (r.udpHolePunchSupported()) {
                        return tr("screen.fabricp2p.host.mode_udp_ok");
                    }
                    return r.status() == NetworkDiagnosticsService.P2PCompatibilityStatus.NO_INTERNET
                            ? tr("screen.fabricp2p.host.mode_no_internet")
                            : tr("screen.fabricp2p.host.mode_udp_fail");
                },
                () -> networkManager.isModeSupported(ConnectionMode.UDP_HOLE_PUNCH, diag.getLastReport()),
                diag::isTesting,
                () -> networkManager.getSelectedConnectionMode() == ConnectionMode.UDP_HOLE_PUNCH,
                () -> networkManager.getRecommendedMode(diag.getLastReport()) == ConnectionMode.UDP_HOLE_PUNCH,
                NeonTheme.CYAN,
                () -> selectMode(ConnectionMode.UDP_HOLE_PUNCH, udpPunchModeCard, NeonTheme.CYAN)
        );
        this.addRenderableWidget(udpPunchModeCard);

        this.tunnelModeCard = new NeonModeOptionCard(
                sideCardX, sideStartY + (sideCardH + sideCardGap) * 2, sideCardW, sideCardH,
                tr("screen.fabricp2p.host.mode_tunnel_title"),
                () -> {
                    if (diag.isTesting()) return tr("screen.fabricp2p.host.mode_testing");
                    NetworkDiagnosticsService.DiagnosticReport r = diag.getLastReport();
                    if (r.tunnelAvailable()) {
                        return tr("screen.fabricp2p.host.mode_tunnel_ok", r.tunnelLatencyMs());
                    }
                    return r.status() == NetworkDiagnosticsService.P2PCompatibilityStatus.NO_INTERNET
                            ? tr("screen.fabricp2p.host.mode_no_internet")
                            : tr("screen.fabricp2p.host.mode_tunnel_fail");
                },
                () -> networkManager.isModeSupported(ConnectionMode.TUNNEL_RELAY, diag.getLastReport()),
                diag::isTesting,
                () -> networkManager.getSelectedConnectionMode() == ConnectionMode.TUNNEL_RELAY,
                () -> networkManager.getRecommendedMode(diag.getLastReport()) == ConnectionMode.TUNNEL_RELAY,
                NeonTheme.VIOLET,
                () -> selectMode(ConnectionMode.TUNNEL_RELAY, tunnelModeCard, NeonTheme.VIOLET)
        );
        this.addRenderableWidget(tunnelModeCard);

        this.lanModeCard = new NeonModeOptionCard(
                sideCardX, sideStartY + (sideCardH + sideCardGap) * 3, sideCardW, sideCardH,
                tr("screen.fabricp2p.host.mode_lan_title"),
                () -> tr("screen.fabricp2p.host.mode_lan_ok"),
                () -> true,
                () -> false,
                () -> networkManager.getSelectedConnectionMode() == ConnectionMode.LOCAL_LAN,
                () -> !diag.isTesting() && networkManager.getRecommendedMode(diag.getLastReport()) == ConnectionMode.LOCAL_LAN,
                NeonTheme.EMERALD,
                () -> selectMode(ConnectionMode.LOCAL_LAN, lanModeCard, NeonTheme.EMERALD)
        );
        this.addRenderableWidget(lanModeCard);

        // Primary Action Bar (left main panel)
        int actionY = gridTop + (cardH + gapY) * 2 + 6;
        int halfW = (panelW - gapX) / 2;

        this.startStopButton = new NeonButton(
                panelLeft, actionY, halfW, 24,
                Component.translatable("screen.fabricp2p.host.btn_start").withStyle(ChatFormatting.BOLD),
                NeonTheme.EMERALD,
                this::handleStartOrStop
        );
        this.addRenderableWidget(startStopButton);

        this.copyDiscordButton = new NeonButton(
                panelLeft + halfW + gapX, actionY, halfW, 24,
                Component.translatable("screen.fabricp2p.host.btn_copy_invite"),
                NeonTheme.VIOLET,
                () -> {
                    if (networkManager.getHostedRoomCode() != null && this.minecraft != null) {
                        String discordMsg = RoomCodeHelper.generateDiscordInvite(
                                networkManager.getHostedRoomCode(),
                                networkManager.getHostedPublicHost(),
                                networkManager.getHostedPublicPort(),
                                networkManager.isCrackSupportEnabled(),
                                networkManager.getSelectedGameType().getName()
                        );
                        this.minecraft.keyboardHandler.setClipboard(discordMsg);
                        particleBg.spawnBurst(copyDiscordButton.getX() + halfW / 2, copyDiscordButton.getY() + 12, NeonTheme.VIOLET, 30);
                        showToast(tr("screen.fabricp2p.host.toast_invite_copied"), NeonTheme.VIOLET);
                    } else {
                        showToast(tr("screen.fabricp2p.host.toast_start_first"), NeonTheme.AMBER);
                    }
                }
        );
        this.addRenderableWidget(copyDiscordButton);

        this.backButton = new NeonButton(
                this.width / 2 - 144, actionY + 30, 138, 20,
                Component.literal("← ").append(Component.translatable("gui.back")),
                NeonTheme.SLATE,
                () -> {
                    if (this.minecraft != null) {
                        this.minecraft.setScreenAndShow(this.parent);
                    }
                }
        );
        this.addRenderableWidget(backButton);

        NeonButton sharedWorldsBtn = new NeonButton(
                this.width / 2 + 6, actionY + 30, 138, 20,
                Component.literal("☁ ").append(Component.translatable("menu.fabricp2p.shared_worlds")),
                NeonTheme.VIOLET,
                () -> {
                    if (this.minecraft != null) {
                        this.minecraft.setScreenAndShow(new SharedWorldsScreen(this));
                    }
                }
        );
        this.addRenderableWidget(sharedWorldsBtn);

        updateWidgetStates();

        // Auto-run internet & NAT compatibility check when opening the host screen
        if (!networkManager.isP2PActive() && !diag.hasRecentReport()) {
            runNetworkCheck(false);
        } else if (diag.hasRecentReport()) {
            networkManager.syncSelectedModeWithDiagnostics(diag.getLastReport());
            updateWidgetStates();
        }
    }

    private void selectMode(ConnectionMode mode, NeonModeOptionCard card, int accent) {
        networkManager.setSelectedConnectionMode(mode, true);
        particleBg.spawnBurst(card.getX() + card.getWidth() / 2.0f, card.getY() + card.getHeight() / 2.0f, accent, 18);
        updateWidgetStates();
    }

    private void runNetworkCheck(boolean showResultToast) {
        NetworkDiagnosticsService diag = NetworkDiagnosticsService.getInstance();
        if (diag.isTesting()) return;

        diag.runDiagnosticsAsync().thenAccept(report -> {
            if (this.minecraft != null) {
                this.minecraft.execute(() -> {
                    networkManager.syncSelectedModeWithDiagnostics(report);
                    updateWidgetStates();
                    if (showResultToast) {
                        switch (report.status()) {
                            case DIRECT_SUPPORTED -> showToast(tr("screen.fabricp2p.host.toast_diag_direct"), NeonTheme.EMERALD);
                            case CGNAT_DETECTED -> showToast(
                                    report.tunnelAvailable()
                                            ? tr("screen.fabricp2p.host.toast_diag_cgnat_tunnel", report.tunnelLatencyMs())
                                            : tr("screen.fabricp2p.host.toast_diag_blocked"),
                                    report.tunnelAvailable() ? NeonTheme.VIOLET : NeonTheme.CRIMSON
                            );
                            case UPNP_UNAVAILABLE -> showToast(
                                    report.tunnelAvailable()
                                            ? tr("screen.fabricp2p.host.toast_diag_upnp_tunnel", report.tunnelLatencyMs())
                                            : tr("screen.fabricp2p.host.toast_diag_blocked"),
                                    report.tunnelAvailable() ? NeonTheme.VIOLET : NeonTheme.CRIMSON
                            );
                            case NO_INTERNET -> showToast(tr("screen.fabricp2p.host.toast_diag_offline"), NeonTheme.CRIMSON);
                            default -> {}
                        }
                    }
                });
            }
        });
        updateWidgetStates();
    }

    private void handleStartOrStop() {
        if (this.minecraft == null) return;

        if (networkManager.isP2PActive()) {
            networkManager.stopP2PHost(this.minecraft);
            particleBg.spawnBurst(startStopButton.getX() + startStopButton.getWidth() / 2.0f,
                    startStopButton.getY() + 12, NeonTheme.CRIMSON, 32);
            updateWidgetStates();
            showToast(tr("screen.fabricp2p.host.toast_stopped"), NeonTheme.CRIMSON);
            return;
        }

        int port = networkManager.allocatePort();
        startStopButton.active = false;
        startStopButton.setAccentRgb(NeonTheme.AMBER);
        startStopButton.setMessage(Component.translatable("screen.fabricp2p.host.btn_starting").withStyle(ChatFormatting.BOLD));

        networkManager.startP2PHost(
                this.minecraft,
                networkManager.getSelectedGameType(),
                networkManager.isAllowCheats(),
                port,
                networkManager.isCrackSupportEnabled(),
                success -> {
                    updateWidgetStates();
                    if (success) {
                        particleBg.spawnBurst(this.width / 2.0f, this.height / 2.0f - 40, NeonTheme.EMERALD, 45);
                        particleBg.spawnBurst(this.width / 2.0f, this.height / 2.0f - 40, NeonTheme.CYAN, 25);
                        P2PNetworkManager.ActiveHostingMode activeMode = networkManager.getActiveHostingMode();
                        String toastKey = activeMode == P2PNetworkManager.ActiveHostingMode.TUNNEL_RELAY
                                ? "screen.fabricp2p.host.toast_started_tunnel"
                                : "screen.fabricp2p.host.toast_started";
                        int accent = activeMode == P2PNetworkManager.ActiveHostingMode.TUNNEL_RELAY
                                ? NeonTheme.VIOLET : NeonTheme.EMERALD;
                        showToast(tr(toastKey, networkManager.getHostedRoomCode()), accent);
                    } else {
                        String err = networkManager.getLastErrorMessage();
                        showToast(err != null ? ("⚠ " + err) : tr("screen.fabricp2p.host.toast_start_error"), NeonTheme.CRIMSON);
                    }
                }
        );
    }

    private void updateWidgetStates() {
        boolean active = networkManager.isP2PActive();
        boolean busy = networkManager.isStarting();
        NetworkDiagnosticsService diag = NetworkDiagnosticsService.getInstance();
        boolean testing = diag.isTesting();
        ConnectionMode selected = networkManager.getSelectedConnectionMode();

        if (startStopButton != null) {
            if (active) {
                startStopButton.active = true;
                startStopButton.setAccentRgb(NeonTheme.CRIMSON);
                startStopButton.setPulsingBeacon(true);
                startStopButton.setMessage(Component.translatable("screen.fabricp2p.host.btn_stop").withStyle(ChatFormatting.BOLD));
            } else if (busy) {
                startStopButton.active = false;
                startStopButton.setAccentRgb(NeonTheme.AMBER);
                startStopButton.setPulsingBeacon(false);
                startStopButton.setMessage(Component.translatable("screen.fabricp2p.host.btn_starting").withStyle(ChatFormatting.BOLD));
            } else if (testing && selected != ConnectionMode.LOCAL_LAN) {
                startStopButton.active = false;
                startStopButton.setAccentRgb(NeonTheme.AMBER);
                startStopButton.setPulsingBeacon(false);
                startStopButton.setMessage(Component.translatable("screen.fabricp2p.host.btn_testing_net").withStyle(ChatFormatting.BOLD));
            } else if (selected == ConnectionMode.UDP_HOLE_PUNCH) {
                startStopButton.active = true;
                startStopButton.setAccentRgb(NeonTheme.CYAN);
                startStopButton.setPulsingBeacon(false);
                startStopButton.setMessage(Component.translatable("screen.fabricp2p.host.btn_start_udp").withStyle(ChatFormatting.BOLD));
            } else if (selected == ConnectionMode.TUNNEL_RELAY) {
                startStopButton.active = true;
                startStopButton.setAccentRgb(NeonTheme.VIOLET);
                startStopButton.setPulsingBeacon(false);
                startStopButton.setMessage(Component.translatable("screen.fabricp2p.host.btn_start_tunnel").withStyle(ChatFormatting.BOLD));
            } else if (selected == ConnectionMode.LOCAL_LAN) {
                startStopButton.active = true;
                startStopButton.setAccentRgb(NeonTheme.CYAN);
                startStopButton.setPulsingBeacon(false);
                startStopButton.setMessage(Component.translatable("screen.fabricp2p.host.btn_start_lan").withStyle(ChatFormatting.BOLD));
            } else {
                boolean directOk = diag.getStatus() == NetworkDiagnosticsService.P2PCompatibilityStatus.IDLE
                        || networkManager.isModeSupported(ConnectionMode.DIRECT_UPNP, diag.getLastReport());
                startStopButton.active = directOk;
                startStopButton.setAccentRgb(directOk ? NeonTheme.EMERALD : NeonTheme.CRIMSON);
                startStopButton.setPulsingBeacon(false);
                startStopButton.setMessage(Component.translatable(
                        directOk ? "screen.fabricp2p.host.btn_start" : "screen.fabricp2p.host.btn_unsupported"
                ).withStyle(ChatFormatting.BOLD));
            }
        }
        if (retestButton != null) {
            retestButton.visible = !active;
            retestButton.active = !active && !busy && !testing;
        }
        if (copyDiscordButton != null) copyDiscordButton.active = active;
        if (copyCodeQuickButton != null) {
            copyCodeQuickButton.active = active;
            copyCodeQuickButton.visible = active;
        }
        if (offlineAuthCard != null) offlineAuthCard.active = !active && !busy;
        if (portCard != null) portCard.active = !active && !busy;
        if (gameModeCard != null) gameModeCard.active = !busy;
        if (cheatsCard != null) cheatsCard.active = !active && !busy;

        boolean canSwitchMode = !active && !busy;
        if (directModeCard != null) directModeCard.active = canSwitchMode;
        if (udpPunchModeCard != null) udpPunchModeCard.active = canSwitchMode;
        if (tunnelModeCard != null) tunnelModeCard.active = canSwitchMode;
        if (lanModeCard != null) lanModeCard.active = canSwitchMode;
    }

    private int getGameModeAccent() {
        GameType gt = networkManager.getSelectedGameType();
        if (gt == GameType.CREATIVE) return NeonTheme.CYAN;
        if (gt == GameType.ADVENTURE) return NeonTheme.VIOLET;
        if (gt == GameType.SPECTATOR) return NeonTheme.AMBER;
        return NeonTheme.EMERALD;
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
        particleBg.render(graphics, this.width, this.height, mouseX, mouseY, networkManager.isP2PActive());

        int slideOffset = Math.round((1.0f - enterT) * -10.0f);
        boolean active = networkManager.isP2PActive();
        int primaryAccent = active ? NeonTheme.EMERALD : NeonTheme.CYAN;

        // 1. Header with Custom Generated Logo & Live Status Badges (spanning full dual-pane width)
        int headerY = topY + slideOffset;
        int totalRight = sideLeft + sideW;
        NeonRenderUtils.drawModLogo(graphics, panelLeft + 2, headerY, 28, primaryAccent, timeSec);

        graphics.text(this.font, Component.literal("FABRIC P2P WORLD").withStyle(ChatFormatting.BOLD),
                panelLeft + 38, headerY + 2, NeonTheme.TEXT_PRIMARY, true);
        graphics.text(this.font, tr("screen.fabricp2p.host.subtitle"),
                panelLeft + 38, headerY + 15, NeonTheme.TEXT_SECONDARY, false);

        String stateBadge = active
                ? tr("screen.fabricp2p.host.badge_live")
                : tr("screen.fabricp2p.host.badge_standby");
        int stateBadgeW = this.font.width(stateBadge) + 12 + (active ? 10 : 0);
        int stateBadgeX = totalRight - stateBadgeW;

        NeonRenderUtils.drawBadge(
                graphics, this.font,
                stateBadgeX, headerY + 6,
                stateBadge,
                active ? NeonTheme.EMERALD : NeonTheme.SLATE,
                active, timeSec
        );
        NeonRenderUtils.drawBadge(
                graphics, this.font,
                stateBadgeX - 56, headerY + 6,
                "MC 26.3",
                NeonTheme.VIOLET,
                false, timeSec
        );

        // 2. Hero Telemetry & Room Code Glass Card (Left Panel)
        int cardY = topY + 36;
        int cardH = 78;
        NeonRenderUtils.drawGlassCard(graphics, panelLeft, cardY, panelW, cardH, primaryAccent, true, timeSec);

        UPnPService upnp = UPnPService.getInstance();
        NetworkDiagnosticsService diag = NetworkDiagnosticsService.getInstance();
        NetworkDiagnosticsService.DiagnosticReport report = diag.getLastReport();
        String statusLabel;
        int statusColor;

        if (active && networkManager.getActiveHostingMode() == P2PNetworkManager.ActiveHostingMode.UDP_HOLE_PUNCH) {
            statusLabel = tr("screen.fabricp2p.host.status_udp_live");
            statusColor = NeonTheme.CYAN;
        } else if (active && networkManager.getActiveHostingMode() == P2PNetworkManager.ActiveHostingMode.TUNNEL_RELAY) {
            statusLabel = tr("screen.fabricp2p.host.status_tunnel_live");
            statusColor = NeonTheme.VIOLET;
        } else if (active && networkManager.getActiveHostingMode() == P2PNetworkManager.ActiveHostingMode.LOCAL_LAN) {
            statusLabel = tr("screen.fabricp2p.host.status_lan_live");
            statusColor = NeonTheme.CYAN;
        } else if (active) {
            statusLabel = tr("screen.fabricp2p.host.upnp_ok");
            statusColor = NeonTheme.EMERALD;
        } else if (diag.isTesting()) {
            statusLabel = tr("screen.fabricp2p.host.diag_testing");
            statusColor = NeonTheme.AMBER;
        } else if (report.status() == NetworkDiagnosticsService.P2PCompatibilityStatus.DIRECT_SUPPORTED) {
            statusLabel = tr("screen.fabricp2p.host.diag_direct_ok");
            statusColor = NeonTheme.EMERALD;
        } else if (report.status() == NetworkDiagnosticsService.P2PCompatibilityStatus.CGNAT_DETECTED) {
            statusLabel = report.tunnelAvailable()
                    ? tr("screen.fabricp2p.host.diag_cgnat_tunnel", report.tunnelLatencyMs())
                    : tr("screen.fabricp2p.host.diag_cgnat_blocked");
            statusColor = report.tunnelAvailable() ? NeonTheme.VIOLET : NeonTheme.CRIMSON;
        } else if (report.status() == NetworkDiagnosticsService.P2PCompatibilityStatus.UPNP_UNAVAILABLE) {
            statusLabel = report.tunnelAvailable()
                    ? tr("screen.fabricp2p.host.diag_upnp_tunnel", report.tunnelLatencyMs())
                    : tr("screen.fabricp2p.host.diag_upnp_blocked");
            statusColor = report.tunnelAvailable() ? NeonTheme.AMBER : NeonTheme.CRIMSON;
        } else if (report.status() == NetworkDiagnosticsService.P2PCompatibilityStatus.NO_INTERNET) {
            statusLabel = tr("screen.fabricp2p.host.diag_no_internet");
            statusColor = NeonTheme.CRIMSON;
        } else {
            statusLabel = tr("screen.fabricp2p.host.upnp_idle");
            statusColor = NeonTheme.TEXT_SECONDARY;
        }
        graphics.text(this.font, "◈ " + statusLabel, panelLeft + 10, cardY + 9, NeonTheme.withAlpha(statusColor, 255), true);

        if (diag.isTesting() || upnp.getStatus() == UPnPService.UPnPStatus.SEARCHING || networkManager.isStarting()) {
            NeonRenderUtils.drawScannerBar(graphics, panelLeft + 10, cardY + 21, panelW - 20, NeonTheme.AMBER, timeSec);
        } else {
            graphics.horizontalLine(panelLeft + 10, panelLeft + panelW - 10, cardY + 22, NeonTheme.withAlpha(primaryAccent, 45));
        }

        int localPort = networkManager.getHostedPort();
        String extAddr = active
                ? (networkManager.getHostedPublicHost() + ":" + networkManager.getHostedPublicPort())
                : (report.publicIp() != null ? report.publicIp() : "---");
        String localAddr = active ? (upnp.getLocalIp() + ":" + localPort) : upnp.getLocalIp();
        String playerCount = active
                ? (networkManager.getOnlinePlayerCount(this.minecraft) + "/" + networkManager.getMaxPlayerCount(this.minecraft))
                : "0/8";

        int col2X = panelLeft + (panelW * 44) / 100;
        int col3X = panelLeft + panelW - 58;

        graphics.text(this.font, tr("screen.fabricp2p.host.label_ext_ip"), panelLeft + 10, cardY + 28, NeonTheme.TEXT_MUTED, false);
        graphics.text(this.font, extAddr, panelLeft + 10, cardY + 39, active ? NeonTheme.TEXT_PRIMARY : NeonTheme.TEXT_MUTED, true);

        graphics.text(this.font, tr("screen.fabricp2p.host.label_lan_ip"), col2X, cardY + 28, NeonTheme.TEXT_MUTED, false);
        graphics.text(this.font, localAddr, col2X, cardY + 39, active ? NeonTheme.TEXT_SECONDARY : NeonTheme.TEXT_MUTED, false);

        graphics.text(this.font, tr("screen.fabricp2p.host.label_players"), col3X, cardY + 28, NeonTheme.TEXT_MUTED, false);
        graphics.text(this.font, playerCount, col3X, cardY + 39, active ? NeonTheme.withAlpha(NeonTheme.EMERALD, 255) : NeonTheme.TEXT_MUTED, true);

        int codeBoxY = cardY + 53;
        int codeBoxRight = active ? (panelLeft + panelW - 90) : (panelLeft + panelW - 10);
        graphics.fill(panelLeft + 10, codeBoxY, codeBoxRight, codeBoxY + 18, NeonTheme.INPUT_BG);
        graphics.outline(panelLeft + 10, codeBoxY, codeBoxRight - (panelLeft + 10), 18,
                NeonTheme.withAlpha(active ? NeonTheme.CYAN : NeonTheme.SLATE, active ? 175 : 70));

        String code = networkManager.getHostedRoomCode();
        if (code != null) {
            String codeLabel = tr("screen.fabricp2p.host.label_room_code");
            graphics.text(this.font, codeLabel, panelLeft + 16, codeBoxY + 5, NeonTheme.withAlpha(NeonTheme.AMBER, 255), false);
            graphics.text(this.font, Component.literal(code).withStyle(ChatFormatting.BOLD),
                    panelLeft + 20 + this.font.width(codeLabel), codeBoxY + 5, NeonTheme.withAlpha(NeonTheme.CYAN, 255), true);
        } else {
            graphics.text(this.font, tr("screen.fabricp2p.host.room_code_placeholder"),
                    panelLeft + 16, codeBoxY + 5, NeonTheme.TEXT_MUTED, false);
        }

        // 3. Right-Side Connection Mode Selector Panel
        int sidePanelH = 216;
        int sideAccent = switch (networkManager.getSelectedConnectionMode()) {
            case UDP_HOLE_PUNCH -> NeonTheme.CYAN;
            case TUNNEL_RELAY -> NeonTheme.VIOLET;
            case LOCAL_LAN -> NeonTheme.EMERALD;
            default -> NeonTheme.EMERALD;
        };
        NeonRenderUtils.drawGlassCard(graphics, sideLeft, cardY, sideW, sidePanelH, sideAccent, false, timeSec);

        graphics.text(this.font, Component.translatable("screen.fabricp2p.host.side_menu_title").withStyle(ChatFormatting.BOLD),
                sideLeft + 10, cardY + 9, NeonTheme.TEXT_PRIMARY, true);
        graphics.horizontalLine(sideLeft + 10, sideLeft + sideW - 10, cardY + 20, NeonTheme.withAlpha(sideAccent, 70));

        // Legend at bottom of right-side panel: Green dot = Supported, Red dot = Unsupported
        int legendY = cardY + sidePanelH - 16;
        graphics.horizontalLine(sideLeft + 10, sideLeft + sideW - 10, legendY - 5, NeonTheme.withAlpha(NeonTheme.SLATE, 55));

        // Green dot + label
        graphics.fill(sideLeft + 10, legendY + 1, sideLeft + 15, legendY + 6, NeonTheme.withAlpha(NeonTheme.EMERALD, 255));
        String supText = tr("screen.fabricp2p.host.legend_supported");
        graphics.text(this.font, supText, sideLeft + 18, legendY, NeonTheme.TEXT_SECONDARY, false);

        // Red dot + label
        int redDotX = sideLeft + 24 + this.font.width(supText);
        graphics.fill(redDotX, legendY + 1, redDotX + 5, legendY + 6, NeonTheme.withAlpha(NeonTheme.CRIMSON, 255));
        graphics.text(this.font, tr("screen.fabricp2p.host.legend_unsupported"), redDotX + 8, legendY, NeonTheme.TEXT_SECONDARY, false);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);

        NeonRenderUtils.drawToast(graphics, this.font, this.width, this.height,
                toastMessage, toastAccent, toastStartMs, TOAST_DURATION_MS);
    }

    @Override
    public boolean isPauseScreen() {
        return true;
    }
}
